package com.schoolio

import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.AndTerm
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.ReceivedDateTerm
import jakarta.mail.search.SearchTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Date
import java.util.Properties

// What callers (InboxRoutes.kt) actually need - trimmed down from a full
// jakarta.mail Message, same "shape of what we use" reasoning as
// GoogleUserInfo in GoogleAuthFlow.kt. bodyText is the plain-text email body
// (falling back to a crude HTML-stripped version if no text/plain part is
// found) - GeminiClient needs the actual content, not just headers. date is
// the display string (as before); receivedAt is the same moment as a
// comparable Instant, used for sorting stored messages and advancing
// ScanState's per-sender watermark (see ScanStateStore.kt) - a formatted
// string isn't safely comparable/sortable across the differing date formats
// real email clients send.
data class GmailMessage(
    val id: String,
    val subject: String,
    val from: String,
    val date: String,
    val receivedAt: Instant,
    val bodyText: String,
    // Raw bytes, not yet uploaded anywhere - InboxRoutes.kt's
    // pullAndStoreNewMessages is what turns these into a StoredAttachment
    // (uploading to Cloud Storage via AttachmentRepository) at the point an
    // EmailMessage is persisted. Kept as a plain class rather than a data
    // class - a ByteArray field would give this a content-based equals/
    // hashCode that's never actually used (nothing compares two
    // EmailAttachments), and a data class default toString() would dump raw
    // bytes into any log line that happens to include one.
    val attachments: List<EmailAttachment> = emptyList()
)

class EmailAttachment(val filename: String, val contentType: String, val bytes: ByteArray)

interface GmailClient {
    // email/appPassword are per-user (User.email/User.gmailAppPassword) - the
    // caller is responsible for having one (i.e. having entered an app
    // password via the /inbox connect-Gmail form); this interface doesn't
    // touch UserRepository itself. senders scopes the IMAP SEARCH server-side
    // (never pulling the whole mailbox locally to filter) - see README's "I
    // don't want to pull all my email" framing. since is an absolute point in
    // time rather than a rolling "weeks back" window - the caller (InboxRoutes)
    // resolves it from ScanState's per-sender watermark, falling back to
    // ScanSettings.lookbackWeeks only when no watermark exists yet (first-time
    // scan) - see ScanStateStore.kt.
    suspend fun searchMessages(email: String, appPassword: String, senders: List<String>, since: Instant): List<GmailMessage>
}

// Comma-separated list of sender addresses/domains to scan for - IMAP's FROM
// search term does a substring match, so either a full address or a bare
// domain works. Configurable via SCHOOL_SENDERS rather than hardcoded, since
// which teachers/school accounts to watch differs per household and changes
// over time. Empty/unset means "nothing configured yet", a distinct state
// from "configured but zero messages found" - InboxRoutes checks for it
// before ever calling Gmail.
fun parseSchoolSenders(raw: String?): List<String> =
    (raw ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }

// Plain IMAP (Jakarta Mail), not the Gmail REST API/OAuth this replaced -
// see UserStore.kt's User.gmailAppPassword doc comment for why: avoids
// gmail.readonly's restricted-scope OAuth verification requirement (a paid,
// recurring CASA Tier 2 assessment) entirely, at the cost of a broader
// per-mailbox credential instead of a narrowly-scoped OAuth token. host/port/
// protocol are constructor params (not hardcoded) purely so tests can point
// this at an in-process fake IMAP server (GreenMail) instead of real Gmail -
// see ImapGmailClientTest.
class ImapGmailClient(
    private val host: String = "imap.gmail.com",
    private val port: Int = 993,
    private val protocol: String = "imaps"
) : GmailClient {
    // Jakarta Mail's Store/Folder/Message API is blocking I/O, not
    // coroutine-friendly - withContext(Dispatchers.IO) keeps it off whatever
    // thread called this suspend fun, same reasoning Ktor's own docs give for
    // wrapping blocking JDBC/file calls.
    override suspend fun searchMessages(email: String, appPassword: String, senders: List<String>, since: Instant): List<GmailMessage> =
        withContext(Dispatchers.IO) {
            val session = Session.getInstance(Properties().apply { put("mail.store.protocol", protocol) })
            val store = session.getStore(protocol)
            try {
                store.connect(host, port, email, appPassword)
                val folder = store.getFolder("INBOX")
                folder.open(Folder.READ_ONLY)
                try {
                    folder.search(buildSearchTerm(senders, since)).map { it.toGmailMessage() }
                } finally {
                    folder.close(false)
                }
            } finally {
                store.close()
            }
        }

    private fun buildSearchTerm(senders: List<String>, since: Instant): SearchTerm {
        val senderTerm = OrTerm(senders.map { FromStringTerm(it) }.toTypedArray())
        return AndTerm(senderTerm, ReceivedDateTerm(ComparisonTerm.GE, Date.from(since)))
    }

    private fun Message.toGmailMessage(): GmailMessage {
        val at = (sentDate ?: receivedDate)?.toInstant() ?: Instant.now()
        return GmailMessage(
            // Message-ID header when available (stable across sessions) -
            // messageNumber alone can be reassigned between IMAP sessions, so
            // it's only a last-resort fallback for a message that somehow lacks
            // one.
            id = (this as? MimeMessage)?.messageID ?: "msg-$messageNumber",
            subject = subject ?: "(no subject)",
            from = from?.firstOrNull()?.toString() ?: "(unknown sender)",
            date = (sentDate ?: receivedDate)?.toString() ?: "",
            receivedAt = at,
            bodyText = extractPlainText(this).ifBlank { extractFirstHtmlAsText(this) },
            attachments = extractAttachments(this)
        )
    }

    private fun extractPlainText(part: Part): String {
        if (part.isMimeType("text/plain")) return (part.content as? String) ?: ""
        if (part.isMimeType("multipart/*")) {
            val multipart = part.content as Multipart
            for (i in 0 until multipart.count) {
                val text = extractPlainText(multipart.getBodyPart(i))
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    // Last resort for a message with no text/plain part at all (some HTML-only
    // newsletters) - a crude tag strip rather than pulling in a full HTML
    // parser (Jsoup, as foodie uses) just for this one fallback path.
    private fun extractFirstHtmlAsText(part: Part): String {
        if (part.isMimeType("text/html")) {
            val html = (part.content as? String) ?: return ""
            return html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        }
        if (part.isMimeType("multipart/*")) {
            val multipart = part.content as Multipart
            for (i in 0 until multipart.count) {
                val text = extractFirstHtmlAsText(multipart.getBodyPart(i))
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    // Any part with a filename that isn't the text/plain or text/html body
    // itself - covers both Part.ATTACHMENT (a real attached file) and
    // Part.INLINE parts that still carry a filename (some mail clients mark
    // an embedded image that way instead of ATTACHMENT); a filename is what
    // actually distinguishes "a file the sender attached" from an inline
    // multipart/alternative body part, not the disposition header, which
    // real-world senders are inconsistent about setting at all. Skips
    // anything over MAX_IMPORT_FILE_BYTES (InboxRoutes.kt's existing cap on
    // an uploaded calendar photo/PDF - reused here rather than a second
    // constant, same "reject something absurd, not a real capacity limit for
    // a two-person app" reasoning) instead of throwing, so one oversized
    // attachment doesn't fail the whole message pull.
    private fun extractAttachments(part: Part): List<EmailAttachment> {
        if (part.isMimeType("multipart/*")) {
            val multipart = part.content as Multipart
            return (0 until multipart.count).flatMap { extractAttachments(multipart.getBodyPart(it)) }
        }
        val filename = part.fileName
        if (filename.isNullOrBlank() || part.isMimeType("text/plain") || part.isMimeType("text/html")) return emptyList()
        val bytes = part.inputStream.readBytes()
        if (bytes.size > MAX_IMPORT_FILE_BYTES) return emptyList()
        // Lowercased - a MIME content-type's type/subtype is case-insensitive
        // per RFC 2045, but not every server preserves the case it was sent
        // in (GreenMail's own IMAP fetch round-trips "application/pdf" back
        // as "APPLICATION/PDF" - caught by ImapGmailClientTest, not
        // theoretical). InboxProcessingSweep.kt's image/PDF filter does a
        // literal string comparison, so normalizing here once is simpler
        // than case-insensitive-comparing everywhere downstream.
        val contentType = part.contentType.substringBefore(";").trim().lowercase()
        return listOf(EmailAttachment(filename, contentType, bytes))
    }
}
