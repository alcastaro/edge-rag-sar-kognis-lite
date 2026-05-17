package io.kognis.tactical

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.kognis.tactical.core.ChunkEncryptor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D-DIL 11.2 — P0 tests for ChunkEncryptor.
 * Must run on device (Android Keystore not available on JVM).
 */
@RunWith(AndroidJUnit4::class)
class ChunkEncryptorTest {

    @Before
    fun setup() {
        ChunkEncryptor.init(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun encryptDecrypt_roundTrip() {
        val plaintext = "Kognis Lite — AES-256-GCM round-trip"
        val encrypted = ChunkEncryptor.encrypt(plaintext)
        val decrypted = ChunkEncryptor.decrypt(encrypted)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun encrypt_producesEncPrefix() {
        val encrypted = ChunkEncryptor.encrypt("test")
        assertTrue("Encrypted value must start with ENC:", encrypted.startsWith(ChunkEncryptor.PREFIX))
    }

    @Test
    fun encrypt_ivRandomization_differentCiphertexts() {
        val plaintext = "same plaintext"
        val enc1 = ChunkEncryptor.encrypt(plaintext)
        val enc2 = ChunkEncryptor.encrypt(plaintext)
        assertNotEquals("Each encryption must produce unique ciphertext (random IV)", enc1, enc2)
    }

    @Test
    fun decrypt_plaintext_passthrough() {
        val raw = "not encrypted"
        assertEquals(raw, ChunkEncryptor.decrypt(raw))
    }

    @Test
    fun decrypt_malformedEnc_returnsRaw() {
        val malformed = "${ChunkEncryptor.PREFIX}not_valid_base64!!!"
        val result = ChunkEncryptor.decrypt(malformed)
        assertEquals(malformed, result)
    }

    @Test
    fun isEncrypted_detectsPrefix() {
        assertTrue(ChunkEncryptor.isEncrypted("${ChunkEncryptor.PREFIX}data"))
        assertFalse(ChunkEncryptor.isEncrypted("plain text"))
    }

    @Test
    fun emptyString_roundTrip() {
        val encrypted = ChunkEncryptor.encrypt("")
        assertEquals("", ChunkEncryptor.decrypt(encrypted))
    }

    @Test
    fun unicodeString_roundTrip() {
        val text = "Herido grave — MEDEVAC requerido 🚨"
        assertEquals(text, ChunkEncryptor.decrypt(ChunkEncryptor.encrypt(text)))
    }
}
