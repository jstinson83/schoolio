package com.schoolio

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import java.time.Instant
import java.util.Date

enum class MessageStatus { PENDING, PROCESSED, FAILED }

// One raw pulled email, stored the moment it's fetched from Gmail -
// independent of whether Gemini has processed it into a summary/action items
// yet (see MessageStatus). Shared across both household accounts (one
// collection, not per-user), same "one shared view" reasoning as
// ScanSettings. id is the Gmail Message-ID - the natural dedup key that makes
// an overlapping rescan (see ScanStateStore's per-sender watermark) safe to
// re-pull without ever double-storing or double-processing the same message.
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
    val dismissed: Boolean = false
)

interface MessageRepository {
    // Every stored message, most recent first - the inbox page's full list,
    // spanning every status (a still-PENDING message renders with no summary/
    // action items yet - see inbox.ftl's pending state).
    suspend fun getAll(): List<EmailMessage>
    // What the debounced processing sweep works through - see
    // InboxRoutes.processPendingMessages.
    suspend fun getPending(): List<EmailMessage>
    // No-op if a message with this id is already stored - the dedupe guard
    // that makes it safe to re-pull messages already inside the search
    // window (see ScanStateStore's min-across-senders "since" computation)
    // without resetting an already-PROCESSED message back to PENDING.
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

    override suspend fun getPending(): List<EmailMessage> =
        collection.whereEqualTo("status", MessageStatus.PENDING.name).get().get().documents.map { it.toEmailMessage() }

    override suspend fun storeIfAbsent(message: EmailMessage) {
        val ref = collection.document(sanitizeMessageDocId(message.id))
        // Transactional read-then-write (not a plain get+set) so two
        // near-simultaneous scans racing on the same newly-arrived message
        // can't both see "doesn't exist yet" and both write it.
        firestore.runTransaction { txn ->
            if (txn.get(ref).get().exists()) return@runTransaction
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

    private fun DocumentSnapshot.toEmailMessage(): EmailMessage = EmailMessage(
        id = getString("messageId") ?: id,
        subject = getString("subject") ?: "",
        from = getString("from") ?: "",
        date = getString("date") ?: "",
        receivedAt = getDate("receivedAt")?.toInstant() ?: Instant.EPOCH,
        bodyText = getString("bodyText") ?: "",
        status = getString("status")?.let { runCatching { MessageStatus.valueOf(it) }.getOrNull() } ?: MessageStatus.PENDING,
        summary = getString("summary"),
        failureReason = getString("failureReason"),
        dismissed = getBoolean("dismissed") ?: false
    )

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
        "dismissed" to message.dismissed
    )
}

// Firestore doc ids can't contain "/" - a Gmail Message-ID never does in
// practice, but sanitize defensively rather than trust an external header.
// messageId is kept as its own field (see toEmailMessage) so the real,
// unsanitized id is still what the rest of the app (action items'
// sourceMessageId, GET /inbox's rendering) ever sees.
private fun sanitizeMessageDocId(messageId: String): String = messageId.replace("/", "_")
