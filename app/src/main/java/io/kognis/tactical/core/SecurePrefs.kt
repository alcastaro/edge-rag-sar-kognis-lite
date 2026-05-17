package io.kognis.tactical.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * AES-256-GCM encrypted SharedPreferences (11.1).
 * Keys: AES256_SIV. Values: AES256_GCM. Master key in Android Keystore.
 * Migrates existing unencrypted "kognis_prefs" on first call.
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"
    private const val SECURE_FILE = "kognis_prefs_secure"
    private const val LEGACY_FILE = "kognis_prefs"

    @Volatile private var instance: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        return instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val secure = EncryptedSharedPreferences.create(
                context,
                SECURE_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            migrateIfNeeded(context, secure)
            Log.i(TAG, "EncryptedSharedPreferences ready (AES-256-GCM)")
            secure
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences init failed — using unencrypted fallback", e)
            context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        }
    }

    private fun migrateIfNeeded(context: Context, secure: SharedPreferences) {
        val legacy = context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        if (secure.all.isNotEmpty()) {
            legacy.edit().clear().apply()
            return
        }
        val editor = secure.edit()
        var migrated = 0
        @Suppress("UNCHECKED_CAST")
        legacy.all.forEach { (key, value) ->
            try {
                when (value) {
                    is String  -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int     -> editor.putInt(key, value)
                    is Long    -> editor.putLong(key, value)
                    is Float   -> editor.putFloat(key, value)
                    is Set<*>  -> editor.putStringSet(key, value as Set<String>)
                }
                migrated++
            } catch (e: Exception) {
                Log.w(TAG, "Skipped key during migration: $key (${e.message})")
            }
        }
        editor.apply()
        legacy.edit().clear().apply()
        Log.i(TAG, "Migrated $migrated keys from $LEGACY_FILE to encrypted storage")
    }
}
