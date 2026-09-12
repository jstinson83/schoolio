package com.schoolio

import kotlin.test.*

class AppPasswordCipherTest {
    @Test
    fun testEncryptThenDecryptRoundTripsToTheOriginalPlaintext() {
        val ciphertext = AppPasswordCipher.encrypt("abcd efgh ijkl mnop", secret = "test-key")
        assertEquals("abcd efgh ijkl mnop", AppPasswordCipher.decrypt(ciphertext, secret = "test-key"))
    }

    @Test
    fun testCiphertextDoesNotContainThePlaintext() {
        val ciphertext = AppPasswordCipher.encrypt("super-secret-app-password", secret = "test-key")
        assertFalse(ciphertext.contains("super-secret-app-password"))
    }

    @Test
    fun testEncryptingTheSamePlaintextTwiceProducesDifferentCiphertext() {
        // Each call uses a fresh random IV - if this ever produced identical
        // output for identical input, that'd mean the IV wasn't actually
        // random, which breaks GCM's security guarantees.
        val first = AppPasswordCipher.encrypt("same-password", secret = "test-key")
        val second = AppPasswordCipher.encrypt("same-password", secret = "test-key")
        assertNotEquals(first, second)
    }

    @Test
    fun testDecryptingWithTheWrongKeyFails() {
        val ciphertext = AppPasswordCipher.encrypt("abcd efgh ijkl mnop", secret = "test-key")
        assertFailsWith<Exception> {
            AppPasswordCipher.decrypt(ciphertext, secret = "a-different-key")
        }
    }

    @Test
    fun testDecryptingGarbageInputFails() {
        assertFailsWith<Exception> {
            AppPasswordCipher.decrypt("not-valid-ciphertext", secret = "test-key")
        }
    }
}
