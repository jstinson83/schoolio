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
    // One entry per device/browser that's opted into the daily digest (see
    // POST /push/subscribe, WebPush.kt) - the browser's own
    // PushSubscription.toJSON(), flattened. A list, not a single nullable
    // field, because the same signed-in account is routinely used from more
    // than one device (a phone and a Chromebook, say) - a single field was
    // tried first and each new subscribe silently clobbered whichever one
    // was stored, so only the most-recently-subscribed device ever actually
    // got notified. Keyed by endpoint (each device/browser gets its own
    // distinct push-service URL), not by any app-level id. Not encrypted at
    // rest the way gmailAppPassword is: unlike an IMAP credential, a leaked
    // push subscription only lets someone send *that browser* a push
    // notification, not read any of this app's data - a materially
    // different, much lower-stakes exposure.
    val pushSubscriptions: List<PushSubscription> = emptyList(),
    // No calendarAppPassword field (there was one, briefly) - Calendar access
    // now goes through a single shared service account (GoogleCalendarApiClient,
    // see CalendarClient.kt) rather than a per-user credential at all. Google's
    // CalDAV endpoint turned out to reject Basic Auth/app passwords outright
    // (401, verified against a live account) - unlike IMAP, that protocol
    // doesn't accept them - so there was never a working per-user credential
    // to store here. See context.md's Calendar pull section for the full story.
    val createdAt: Instant? = null
)

interface UserRepository {
    suspend fun findOrCreateByGoogle(googleSub: String, email: String, name: String): User
    suspend fun find(id: String): User?
    // Needed by POST /internal/sync (InboxRoutes.kt) - that route has only
    // ALLOWED_EMAILS to work from (there's no session, so no User.id/googleSub
    // the way every other route gets one from requireUserId()), and has to
    // look up each allowed account's stored gmailAppPassword by email instead.
    suspend fun findByEmail(email: String): User?
    suspend fun saveGmailAppPassword(id: String, appPassword: String)
    // Upserts by subscription.endpoint - a device re-subscribing (a
    // permission reset, a service worker update) replaces its own prior
    // entry rather than appending a duplicate, but every other device's
    // entry is left alone.
    suspend fun savePushSubscription(id: String, subscription: PushSubscription)
    // Removes just the one subscription matching endpoint, not every
    // subscription this user has. Called when a push send comes back Gone
    // (see WebPush.kt's PushSendResult) - the push service has permanently
    // discarded that one subscription, so holding onto it would just mean
    // failing the same way every future send. Also how a signed-in user
    // disables notifications on the device they're using (POST
    // /push/unsubscribe) without affecting their other devices.
    suspend fun removePushSubscription(id: String, endpoint: String)
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

    override suspend fun findByEmail(email: String): User? {
        val doc = collection.whereEqualTo("email", email).limit(1).get().get().documents.firstOrNull() ?: return null
        return toUser(doc.id, doc.data ?: emptyMap())
    }

    override suspend fun saveGmailAppPassword(id: String, appPassword: String) {
        collection.document(id).update("gmailAppPassword", AppPasswordCipher.encrypt(appPassword, appPasswordEncryptionKey)).get()
    }

    override suspend fun savePushSubscription(id: String, subscription: PushSubscription) {
        val docRef = collection.document(id)
        val existing = parsePushSubscriptions(docRef.get().get().data ?: emptyMap())
        val updated = existing.filterNot { it.endpoint == subscription.endpoint } + subscription
        docRef.update("pushSubscriptions", updated.map { it.toFirestoreMap() }).get()
    }

    override suspend fun removePushSubscription(id: String, endpoint: String) {
        val docRef = collection.document(id)
        val existing = parsePushSubscriptions(docRef.get().get().data ?: emptyMap())
        docRef.update("pushSubscriptions", existing.filterNot { it.endpoint == endpoint }.map { it.toFirestoreMap() }).get()
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
        pushSubscriptions = parsePushSubscriptions(data),
        createdAt = (data["createdAt"] as? Timestamp)?.let { Instant.ofEpochSecond(it.seconds, it.nanos.toLong()) }
    )

    // Reads the current "pushSubscriptions" array field, falling back to the
    // old pre-multi-device singular "pushSubscription" field when the array
    // isn't there yet - so a subscription saved before this migration isn't
    // silently dropped on first read after deploy. The next
    // savePushSubscription/removePushSubscription call for that user
    // migrates it to the array field for good.
    private fun parsePushSubscriptions(data: Map<String, Any?>): List<PushSubscription> {
        (data["pushSubscriptions"] as? List<*>)?.let { list ->
            return list.mapNotNull { (it as? Map<*, *>)?.toPushSubscription() }
        }
        return (data["pushSubscription"] as? Map<*, *>)?.toPushSubscription()?.let { listOf(it) } ?: emptyList()
    }

    private fun Map<*, *>.toPushSubscription(): PushSubscription? {
        val endpoint = this["endpoint"] as? String
        val p256dh = this["p256dh"] as? String
        val auth = this["auth"] as? String
        return if (endpoint != null && p256dh != null && auth != null) PushSubscription(endpoint, p256dh, auth) else null
    }

    private fun PushSubscription.toFirestoreMap(): Map<String, String> =
        mapOf("endpoint" to endpoint, "p256dh" to p256dh, "auth" to auth)
}
