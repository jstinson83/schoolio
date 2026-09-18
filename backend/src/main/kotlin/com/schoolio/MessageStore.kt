package com.schoolio

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import java.security.MessageDigest
import java.time.Instant
import java.util.Date

enum class MessageStatus { PENDING, PROCESSED, FAILED }

// Same real email landing in both household mailboxes separately (the school
// sends to both parents' addresses directly, most commonly) is not caught by
// id-based dedup below - two mailboxes assign the same message two different
// Message-IDs. Hashed over from+subject+body only (not id/date/receivedAt,
// which differ per mailbox by design) so two independent deliveries of the
// literal same content collide; whitespace-only normalized, not a fuzzy
// match - a forwarded copy (different From, "Fwd:" subject, added forward
// headers) or a differently-worded email about the same event won't match,
// deliberately (see context.md's cross-source reconciliation entry for why
// that broader case is parked, not solved here).
fun emailContentHash(from: String, subject: String, bodyText: String): String {
    val normalizedBody = bodyText.lines().joinToString("\n") { it.trim() }.trim()
    val normalized = listOf(from.trim(), subject.trim(), normalizedBody).joinToString("\u0001")
    return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

// One raw pulled email, stored the moment it's fetched from Gmail -
// independent of whether Gemini has processed it into a summary/action items
// yet (see MessageStatus). Shared across both household accounts (one
// collection, not per-user), same "one shared view" reasoning as
// ScanSettings. id is the Gmail Message-ID - the natural dedup key that makes
// an overlapping rescan (see ScanStateStore's per-sender watermark) safe to
// re-pull without ever double-storing or double-processing the same message.
// contentHash is the *cross-mailbox* dedup key (see emailContentHash above) -
// defaulted from the other fields so existing call sites (tests included)
// don't need to pass it explicitly.
data class EmailMessage(
    val id: String,
    val subject: String,
    val from: String,
    val date: String,
    val receivedAt: Instant,
    val bodyText: String,
    val status: MessageStatus = MessageStatus.PENDING,
    val summary: String? = null,
    val failureReason: String? = null,
    // Same "get this off my plate, not delete it" lifecycle bit as
    // ActionItem.dismissed, for a PROCESSED message with no action items at
    // all (inbox.ftl's "Other updates" section) - those messages have no
    // ActionItem of their own to carry a dismissed flag.
    val dismissed: Boolean = false,
    // Defaulted from the other constructor params, so it's only ever
    // recomputed when constructed directly - a data class .copy() carries
    // the original instance's contentHash forward as-is even if the copy
    // also changes from/subject/bodyText, same reason every real .copy()
    // call site in this codebase only ever touches status/summary/dismissed/
    // failureReason, never the content fields.
    val contentHash: String = emailContentHash(from, subject, bodyText),
    // Which household account's mailbox this was pulled from (User.email -
    // see InboxRoutes.pullAndStoreNewMessages). No longer used for a Gmail
    // deep link (see attachments' own doc comment below for why that's
    // gone) - kept as provenance metadata only, e.g. for a future feature
    // that cares which mailbox something came from. Blank for any doc
    // written before this field existed.
    val scannedByEmail: String = "",
    // The full email is now stored/viewable in-app (GET /inbox/messages/{id},
    // InboxRoutes.kt) instead of linking out to Gmail - see this field's own
    // history if you're wondering why there's no gmailSearchLink()-style
    // helper here anymore: a Gmail deep link only ever worked for whichever
    // household account's mailbox a message was pulled from (a Message-ID is
    // per-mailbox, see scannedByEmail above), which made it silently dead for
    // the *other* account on every shared email - a bad tradeoff against just
    // keeping a copy of what was already being stored anyway. Raw attachment
    // bytes live in Cloud Storage, not here (see AttachmentStore.kt) -
    // Firestore documents cap out at 1MiB, a bad fit for a PDF/photo.
    val attachments: List<StoredAttachment> = emptyList()
)

// One email attachment already uploaded to Cloud Storage (AttachmentStore.kt)
// at pull time (see InboxRoutes.kt's pullAndStoreNewMessages) - metadata only,
// the bytes themselves are fetched from storagePath on demand (GET
// /inbox/messages/{id}/attachments/{index}, or InboxProcessingSweep.kt's
// re-fetch for Gemini extraction), never held in memory alongside every other
// stored message.
data class StoredAttachment(val filename: String, val contentType: String, val size: Long, val storagePath: String)

interface MessageRepository {
    // Every stored message, most recent first - the inbox page's full list,
    // spanning every status (a still-PENDING message renders with no summary/
    // action items yet - see inbox.ftl's pending state).
    suspend fun getAll(): List<EmailMessage>
    // Single-message lookup for GET /inbox/messages/{id} (the full-email view
    // replacing the old Gmail deep link) - getAll() plus a find() would work
    // too, but this lets FirestoreMessageStore do a single document read
    // instead of pulling every stored message just to show one.
    suspend fun get(id: String): EmailMessage?
    // What the debounced processing sweep works through - see
    // InboxRoutes.processPendingMessages.
    suspend fun getPending(): List<EmailMessage>
    // No-op if a message with this id is already stored - the dedupe guard
    // that makes it safe to re-pull messages already inside the search
    // window (see ScanStateStore's min-across-senders "since" computation)
    // without resetting an already-PROCESSED message back to PENDING. Also a
    // no-op if a message with the same contentHash is already stored under a
    // *different* id - catches the same email delivered separately to both
    // household mailboxes (see emailContentHash's doc comment).
    suspend fun storeIfAbsent(message: EmailMessage)
    suspend fun markProcessed(id: String, summary: String)
    suspend fun markFailed(id: String, reason: String)
    // Flips dismissed on/off for one message - same single-field-update shape
    // as ActionItemRepository.dismiss/restore, for the "Other updates"
    // messages that have no ActionItem of their own to dismiss instead.
    suspend fun dismiss(id: String)
    suspend fun restore(id: String)
}

class FirestoreMessageStore(private val firestore: Firestore) : MessageRepository {
    private val collection = firestore.collection("messages")

    override suspend fun getAll(): List<EmailMessage> =
        collection.orderBy("receivedAt", Query.Direction.DESCENDING).get().get().documents.map { it.toEmailMessage() }

    override suspend fun get(id: String): EmailMessage? =
        collection.document(sanitizeMessageDocId(id)).get().get().takeIf { it.exists() }?.toEmailMessage()

    override suspend fun getPending(): List<EmailMessage> =
        collection.whereEqualTo("status", MessageStatus.PENDING.name).get().get().documents.map { it.toEmailMessage() }

    override suspend fun storeIfAbsent(message: EmailMessage) {
        val ref = collection.document(sanitizeMessageDocId(message.id))
        // Transactional read-then-write (not a plain get+set) so two
        // near-simultaneous scans racing on the same newly-arrived message
        // can't both see "doesn't exist yet" and both write it. The
        // contentHash query catches the cross-mailbox case (same email, two
        // Message-IDs) - a single-field equality query, no composite index
        // needed.
        firestore.runTransaction { txn ->
            if (txn.get(ref).get().exists()) return@runTransaction
            val hashMatch = txn.get(collection.whereEqualTo("contentHash", message.contentHash)).get()
            if (!hashMatch.isEmpty) return@runTransaction
            txn.set(ref, messageToMap(message))
        }.get()
    }

    override suspend fun markProcessed(id: String, summary: String) {
        collection.document(sanitizeMessageDocId(id)).update(
            mapOf("status" to MessageStatus.PROCESSED.name, "summary" to summary, "failureReason" to null)
        ).get()
    }

    override suspend fun markFailed(id: String, reason: String) {
        collection.document(sanitizeMessageDocId(id)).update(
            mapOf("status" to MessageStatus.FAILED.name, "failureReason" to reason)
        ).get()
    }

    override suspend fun dismiss(id: String) {
        collection.document(sanitizeMessageDocId(id)).update("dismissed", true).get()
    }

    override suspend fun restore(id: String) {
        collection.document(sanitizeMessageDocId(id)).update("dismissed", false).get()
    }

    private fun DocumentSnapshot.toEmailMessage(): EmailMessage {
        val from = getString("from") ?: ""
        val subject = getString("subject") ?: ""
        val bodyText = getString("bodyText") ?: ""
        return EmailMessage(
            id = getString("messageId") ?: id,
            subject = subject,
            from = from,
            date = getString("date") ?: "",
            receivedAt = getDate("receivedAt")?.toInstant() ?: Instant.EPOCH,
            bodyText = bodyText,
            status = getString("status")?.let { runCatching { MessageStatus.valueOf(it) }.getOrNull() } ?: MessageStatus.PENDING,
            summary = getString("summary"),
            failureReason = getString("failureReason"),
            dismissed = getBoolean("dismissed") ?: false,
            // A doc written before contentHash existed has no such field -
            // fall back to computing it the same way a fresh write would, so
            // an old doc still participates correctly in future dedup checks.
            contentHash = getString("contentHash") ?: emailContentHash(from, subject, bodyText),
            scannedByEmail = getString("scannedByEmail") ?: "",
            // Absent entirely on a doc written before attachments existed -
            // same "old doc just has none" fallback as contentHash above,
            // rather than an error.
            attachments = (get("attachments") as? List<*>)?.mapNotNull { it.toStoredAttachment() } ?: emptyList()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.toStoredAttachment(): StoredAttachment? {
        val map = this as? Map<String, Any?> ?: return null
        val storagePath = map["storagePath"] as? String ?: return null
        return StoredAttachment(
            filename = map["filename"] as? String ?: "",
            contentType = map["contentType"] as? String ?: "application/octet-stream",
            // A raw Firestore map (unlike the typed DocumentSnapshot accessors
            // used elsewhere in this file) can hand back a Long here instead
            // of the Long this field is typed as, depending on how the value
            // was originally written - toString().toLong() normalizes either
            // way rather than an unsafe `as Long` cast (see CLAUDE.md's
            // Firestore raw-map-cast gotcha - this is the same family of
            // issue as ScanStateStore's old Date cast, just Long instead of
            // Timestamp).
            size = (map["size"] as? Number)?.toLong() ?: 0L,
            storagePath = storagePath
        )
    }

    private fun messageToMap(message: EmailMessage): Map<String, Any?> = mapOf(
        "messageId" to message.id,
        "subject" to message.subject,
        "from" to message.from,
        "date" to message.date,
        "receivedAt" to Date.from(message.receivedAt),
        "bodyText" to message.bodyText,
        "status" to message.status.name,
        "summary" to message.summary,
        "failureReason" to message.failureReason,
        "dismissed" to message.dismissed,
        "contentHash" to message.contentHash,
        "scannedByEmail" to message.scannedByEmail,
        "attachments" to message.attachments.map {
            mapOf("filename" to it.filename, "contentType" to it.contentType, "size" to it.size, "storagePath" to it.storagePath)
        }
    )
}

// Firestore doc ids can't contain "/" - a Gmail Message-ID never does in
// practice, but sanitize defensively rather than trust an external header.
// messageId is kept as its own field (see toEmailMessage) so the real,
// unsanitized id is still what the rest of the app (action items'
// sourceMessageId, GET /inbox's rendering) ever sees.
private fun sanitizeMessageDocId(messageId: String): String = messageId.replace("/", "_")
