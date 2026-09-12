package com.schoolio

import com.google.cloud.Timestamp
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import java.time.Instant

// Deliberately simpler than foodie's User: schoolio has exactly two allowed
// accounts (see ALLOWED_EMAILS/parseAllowedEmails in AuthSession.kt), not an
// open sign-up flow, so there's no need for foodie's opaque UUID id decoupled
// from the provider - id == googleSub directly, and there's only ever one
// sign-in provider (Google) to resolve, so no findOrCreateByEmail fallback
// or household indirection either.
data class User(
    val id: String,
    val googleSub: String,
    val email: String,
    val name: String,
    // A Gmail "app password" (myaccount.google.com/apppasswords), entered by
    // the user via the /inbox connect-Gmail form - NOT part of the Google
    // sign-in OAuth flow. Deliberately decoupled: Google sign-in is just
    // identity (openid/email/profile, see GoogleAuthFlow.kt), while Gmail
    // *data* access goes through plain IMAP with this credential instead of
    // the Gmail REST API/OAuth - avoids the gmail.readonly restricted-scope
    // verification requirement entirely (see context.md's Gmail integration
    // notes). Nullable since a user can sign in without having connected
    // Gmail yet - InboxRoutes.kt checks for null and prompts to enter one.
    // Always plaintext here in memory - FirestoreUserStore encrypts it
    // (AppPasswordCipher) before it ever reaches the Firestore document and
    // decrypts it back out, so nothing above the persistence layer needs to
    // know encryption is happening at all.
    val gmailAppPassword: String? = null,
    val createdAt: Instant? = null
)

interface UserRepository {
    suspend fun findOrCreateByGoogle(googleSub: String, email: String, name: String): User
    suspend fun find(id: String): User?
    suspend fun saveGmailAppPassword(id: String, appPassword: String)
}

// Uses ApiFuture.get() (blocking the calling thread inside a suspend fun),
// same as foodie's FirestoreUserStore - the Google Cloud Java client returns
// ApiFuture, not a kotlinx.coroutines-friendly Task, so there's no .await()
// extension for it here.
class FirestoreUserStore(
    private val firestore: Firestore,
    // See AppPasswordCipher's doc comment for why this is a plain string env
    // var rather than a proper key/KMS setup.
    private val appPasswordEncryptionKey: String
) : UserRepository {
    private val collection = firestore.collection("users")

    override suspend fun findOrCreateByGoogle(googleSub: String, email: String, name: String): User {
        val docRef = collection.document(googleSub)
        val doc = docRef.get().get()
        if (doc.exists()) {
            docRef.update(mapOf("email" to email, "name" to name)).get()
            return toUser(googleSub, doc.data ?: emptyMap()).copy(email = email, name = name)
        }
        val data = mapOf(
            "googleSub" to googleSub,
            "email" to email,
            "name" to name,
            "createdAt" to FieldValue.serverTimestamp()
        )
        docRef.set(data).get()
        return User(id = googleSub, googleSub = googleSub, email = email, name = name, createdAt = Instant.now())
    }

    override suspend fun find(id: String): User? {
        val doc = collection.document(id).get().get()
        if (!doc.exists()) return null
        return toUser(id, doc.data ?: emptyMap())
    }

    override suspend fun saveGmailAppPassword(id: String, appPassword: String) {
        collection.document(id).update("gmailAppPassword", AppPasswordCipher.encrypt(appPassword, appPasswordEncryptionKey)).get()
    }

    private fun toUser(id: String, data: Map<String, Any?>): User = User(
        id = id,
        googleSub = data["googleSub"] as? String ?: id,
        email = data["email"] as? String ?: "",
        name = data["name"] as? String ?: "",
        // Falls back to null (not a thrown exception) on a decrypt failure -
        // covers a doc written before this encryption existed (plaintext,
        // won't parse as ciphertext) or the encryption key having changed
        // since. Either way, the safe behavior is "treat as not connected
        // yet" (InboxRoutes.kt prompts to (re)enter it) rather than crashing
        // every find() call - and therefore every signed-in page load - on
        // one bad field.
        gmailAppPassword = (data["gmailAppPassword"] as? String)?.let {
            runCatching { AppPasswordCipher.decrypt(it, appPasswordEncryptionKey) }.getOrNull()
        },
        createdAt = (data["createdAt"] as? Timestamp)?.let { Instant.ofEpochSecond(it.seconds, it.nanos.toLong()) }
    )
}
