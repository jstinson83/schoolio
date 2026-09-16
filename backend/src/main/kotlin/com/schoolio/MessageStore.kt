package com.schoolio

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import java.net.URLEncoder
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
    // see InboxRoutes.pullAndStoreNewMessages) - a Gmail deep link back to
    // the original message (see gmailSearchLink below) only resolves for
    // whoever's signed into *this* account, since the same email delivered
    // to both parents' mailboxes gets a different Message-ID in each one
    // (see emailContentHash's doc comment above) and Gmail has no
    // account-agnostic message URL. Blank for any doc written before this
    // field existed - InboxRoutes treats that the same as "don't show a
    // link" rather than guessing which account it came from.
    val scannedByEmail: String = ""
)

// Gmail's rfc822msgid: search operator matches the RFC822 Message-ID header
// exactly, so this needs no extra IMAP fetch (e.g. the Gmail-specific
// X-GM-MSGID extension, which plain jakarta.mail doesn't expose anyway) -
// just the Message-ID already captured as EmailMessage.id (see
// GmailClient.kt's toGmailMessage). Angle brackets are stripped before
// encoding - rfc822msgid: matches the bare header value, not the
// `<...>`-wrapped form the header is written in. Only ever meaningful when
// opened while signed into scannedByEmail's own Gmail account (see that
// field's doc comment) - callers (InboxRoutes) are responsible for only
// showing this to the matching signed-in user, not for the account check
// itself.
fun EmailMessage.gmailSearchLink(): String =
    "https://mail.google.com/mail/u/0/#search/rfc822msgid:" +
        URLEncoder.encode(id.removePrefix("<").removeSuffix(">"), "UTF-8")

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
            scannedByEmail = getString("scannedByEmail") ?: ""
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
        "scannedByEmail" to message.scannedByEmail
    )
}

// Firestore doc ids can't contain "/" - a Gmail Message-ID never does in
// practice, but sanitize defensively rather than trust an external header.
// messageId is kept as its own field (see toEmailMessage) so the real,
// unsanitized id is still what the rest of the app (action items'
// sourceMessageId, GET /inbox's rendering) ever sees.
private fun sanitizeMessageDocId(messageId: String): String = messageId.replace("/", "_")
