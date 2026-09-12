package com.schoolio

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// AES-256-GCM at-rest encryption for User.gmailAppPassword before it's
// written to Firestore (see UserStore.kt's FirestoreUserStore) - an app
// password is a long-lived, broad-access credential (full IMAP mailbox
// access), unlike the short-lived OAuth token it replaced, so storing it in
// plaintext in Firestore was worth closing. The key comes from a plain
// string env var (any string - SHA-256'd here into a 256-bit AES key, so
// the maintainer doesn't need to generate/base64-encode a proper key by
// hand) rather than Google Cloud KMS/Secret Manager - one env var is
// proportionate for a two-person app; KMS would be real infra for a threat
// this small.
object AppPasswordCipher {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    private fun key(secret: String): SecretKeySpec =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8)), "AES")

    // IV is random per call and prepended to the returned ciphertext (so
    // decrypt() doesn't need it passed separately) - never reuse an IV with
    // the same key, that breaks GCM's security guarantees, which is exactly
    // why this generates a fresh one every time rather than deriving one
    // deterministically from the plaintext.
    fun encrypt(plaintext: String, secret: String): String {
        val iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key(secret), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    fun decrypt(encoded: String, secret: String): String {
        val combined = Base64.getDecoder().decode(encoded)
        val iv = combined.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = combined.copyOfRange(IV_LENGTH_BYTES, combined.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key(secret), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
