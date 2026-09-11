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
    // Only ever set from a token exchange that actually returned one (Google
    // only issues a refresh token on first consent, or when the authorize
    // request forces re-consent - see googleOAuthProvider's extraAuthParameters
    // in GoogleAuthFlow.kt). Nullable rather than required so a user can still
    // sign in (browse-only) even if Gmail access hasn't been granted/stored
    // yet - GmailClient callers must check for null and prompt to (re)connect.
    val googleRefreshToken: String? = null,
    val createdAt: Instant? = null
)

interface UserRepository {
    suspend fun findOrCreateByGoogle(googleSub: String, email: String, name: String): User
    suspend fun find(id: String): User?

    // Separate from findOrCreateByGoogle because a refresh token is only
    // present on some sign-ins (see User.googleRefreshToken's doc comment) -
    // keeping it its own write means a sign-in that didn't get a new one
    // doesn't accidentally null out a previously stored one.
    suspend fun saveGoogleRefreshToken(id: String, refreshToken: String)
}

// Uses ApiFuture.get() (blocking the calling thread inside a suspend fun),
// same as foodie's FirestoreUserStore - the Google Cloud Java client returns
// ApiFuture, not a kotlinx.coroutines-friendly Task, so there's no .await()
// extension for it here.
class FirestoreUserStore(private val firestore: Firestore) : UserRepository {
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

    override suspend fun saveGoogleRefreshToken(id: String, refreshToken: String) {
        collection.document(id).update("googleRefreshToken", refreshToken).get()
    }

    private fun toUser(id: String, data: Map<String, Any?>): User = User(
        id = id,
        googleSub = data["googleSub"] as? String ?: id,
        email = data["email"] as? String ?: "",
        name = data["name"] as? String ?: "",
        googleRefreshToken = data["googleRefreshToken"] as? String,
        createdAt = (data["createdAt"] as? Timestamp)?.let { Instant.ofEpochSecond(it.seconds, it.nanos.toLong()) }
    )
}
