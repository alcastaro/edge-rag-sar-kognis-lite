package io.kognis.tactical.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import io.kognis.tactical.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * D-DIL 11.2 — AES-256-GCM field-level encryption for ObjectBox DocumentChunk.
 *
 * Key lives in Android Keystore (never leaves secure hardware).
 * Encrypted values format: "ENC:<base64(12-byte-IV || ciphertext)>"
 * Falls back to plaintext if Keystore unavailable (degraded mode, never crashes).
 *
 * Gated by BuildConfig.KB_ENCRYPTION_ENABLED. When disabled, encrypt() is a pass-through;
 * decrypt() still recognizes the ENC: prefix so a DB built under encrypted=true keeps
 * working after the flag is flipped (graceful read-through). Re-enable once a DEK/KEK
 * scheme replaces the per-call Keystore round-trip (~1 ms × 20k chunks = ~30 s ingest).
 */
object ChunkEncryptor {

    private const val TAG = "ChunkEncryptor"
    private const val KEY_ALIAS = "kognis_chunk_enc_v1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12
    const val PREFIX = "ENC:"

    @Volatile private var key: SecretKey? = null

    fun init(context: Context) {
        if (!BuildConfig.KB_ENCRYPTION_ENABLED) {
            Log.i(TAG, "KB_ENCRYPTION_ENABLED=false — ChunkEncryptor in pass-through mode")
            return
        }
        if (key != null) return
        synchronized(this) {
            if (key != null) return
            try {
                val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
                key = if (ks.containsAlias(KEY_ALIAS)) {
                    Log.d(TAG, "Loaded existing Keystore key: $KEY_ALIAS")
                    (ks.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
                } else {
                    Log.i(TAG, "Generating AES-256-GCM key: $KEY_ALIAS")
                    val spec = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                        .apply { init(spec) }.generateKey()
                }
                Log.i(TAG, "ChunkEncryptor ready")
            } catch (e: Exception) {
                Log.e(TAG, "ChunkEncryptor init failed — encryption disabled for this session", e)
            }
        }
    }

    fun encrypt(plaintext: String): String {
        if (!BuildConfig.KB_ENCRYPTION_ENABLED) return plaintext
        val k = key ?: return plaintext
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val iv = cipher.iv
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed — storing plaintext", e)
            plaintext
        }
    }

    // decrypt always honors ENC: prefix even when flag is off — lets a previously-encrypted
    // ObjectBox DB stay readable after we disable encryption.
    fun decrypt(value: String): String {
        if (!value.startsWith(PREFIX)) return value
        if (!BuildConfig.KB_ENCRYPTION_ENABLED && key == null) {
            // Flag off and key never initialized — opportunistically initialize so we can
            // decrypt a legacy DB. Subsequent encrypts still pass through.
            // (No-op cost ~10 ms once.)
            initKeyOnly()
        }
        val k = key ?: return value
        return try {
            val payload = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, IV_LEN)
            val ct = payload.copyOfRange(IV_LEN, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed — returning raw value", e)
            value
        }
    }

    private fun initKeyOnly() {
        if (key != null) return
        synchronized(this) {
            if (key != null) return
            try {
                val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
                if (ks.containsAlias(KEY_ALIAS)) {
                    key = (ks.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
                    Log.i(TAG, "Legacy Keystore key loaded for read-through decrypt")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not load legacy Keystore key — encrypted DB rows will read raw", e)
            }
        }
    }

    fun isEncrypted(value: String) = value.startsWith(PREFIX)
}
