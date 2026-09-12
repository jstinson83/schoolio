package com.schoolio

import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// How long to wait after a Gmail pull before running Gemini over whatever's
// PENDING - long enough that a settings save (which redirects straight back
// into another pull) or a quick page reload coalesces into one processing
// pass instead of two overlapping ones. Overridable (see processDebounceMs
// below) so tests don't have to actually sleep the real default.
const val DEFAULT_INBOX_PROCESS_DEBOUNCE_MS = 5_000L

// Wire shapes for GET /inbox/status - a plain mapOf(...) mixing Strings/Ints/
// nested lists is a Map<String, Any>, which kotlinx.serialization can't
// encode without a contextual serializer for Any (see CLAUDE.md's
// ContentNegotiation gotcha) - real @Serializable classes sidestep that.
@Serializable
data class ActionItemSummary(val title: String, val description: String, val date: String? = null)

@Serializable
data class InboxMessageSummary(
    val id: String,
    val subject: String,
    val from: String,
    val date: String,
    val summary: String,
    val actionItems: List<ActionItemSummary>
)

@Serializable
data class FailedMessageSummary(val id: String, val subject: String, val reason: String?)

// [messages] is every PROCESSED message regardless of how long ago (same
// "full snapshot, not just what's new" shape as foodie's /recipe/status) -
// the poller reconciles by id (see app.js), skipping ones already in the DOM.
@Serializable
data class InboxStatusResponse(val pending: Int, val messages: List<InboxMessageSummary>, val failed: List<FailedMessageSummary>)

// The app's main flow: pull the last-unseen email from `schoolSenders` only
// (never the whole inbox - see README's "Sender filtering" scope item),
// store it immediately, and let a debounced background pass run each raw
// message through Gemini. The GET /inbox request itself only ever does the
// (fast, one-round-trip) IMAP pull - it never blocks on Gemini, which is what
// used to make the page freeze while scanning. schoolSenders/lookbackWeeks
// live in Firestore (SettingsRepository), edited via the form on this page.
fun Route.inboxRoutes(
    userStore: UserRepository,
    gmailClient: GmailClient,
    geminiClient: GeminiClient,
    settingsStore: SettingsRepository,
    messageStore: MessageRepository,
    actionItemStore: ActionItemRepository,
    scanStateStore: ScanStateRepository,
    backgroundScope: CoroutineScope,
    processDebounceMs: Long = DEFAULT_INBOX_PROCESS_DEBOUNCE_MS
) {
    // A single shared job, not keyed per user - both household accounts share
    // one inbox/one set of messages (see MessageStore.kt), so their pulls
    // should coalesce into one debounced processing pass, not run one each.
    var processingJob: Job? = null

    fun scheduleProcessing() {
        processingJob?.cancel()
        processingJob = backgroundScope.launch {
            delay(processDebounceMs)
            processPendingMessages(messageStore, actionItemStore, geminiClient)
        }
    }

    get("/inbox") {
        val userId = call.requireUserId()
        val user = userStore.find(userId)
        val appPassword = user?.gmailAppPassword
        val settings = settingsStore.get()
        val navModel = mapOf("activeNav" to "inbox")

        if (appPassword == null) {
            call.respond(FreeMarkerContent("inbox.ftl", mapOf("needsGmailAccess" to true) + navModel + call.currentUserModel()))
            return@get
        }
        if (settings.schoolSenders.isEmpty()) {
            call.respond(FreeMarkerContent("inbox.ftl", mapOf("noSendersConfigured" to true) + navModel + call.currentUserModel()))
            return@get
        }

        pullAndStoreNewMessages(user.email, appPassword, settings, gmailClient, messageStore, scanStateStore)
        scheduleProcessing()

        val messages = messageStore.getAll()
        val messagesById = messages.associateBy { it.id }
        val allActionItems = actionItemStore.getAll()
        val actionItemsByMessage = allActionItems.groupBy { it.sourceMessageId }
        val processedWithNoActionItems = messages.filter {
            it.status == MessageStatus.PROCESSED && (actionItemsByMessage[it.id] ?: emptyList()).isEmpty()
        }
        val failedMessages = messages.filter { it.status == MessageStatus.FAILED }
        val pendingMessages = messages.filter { it.status == MessageStatus.PENDING }
        call.respond(
            FreeMarkerContent(
                "inbox.ftl",
                mapOf(
                    "dateGroups" to buildDateGroups(allActionItems, messagesById),
                    "pendingMessages" to pendingMessages.map { mapOf("subject" to it.subject) },
                    "noActionMessages" to processedWithNoActionItems.map { mapOf("subject" to it.subject, "summary" to it.summary) },
                    "failedMessages" to failedMessages.map { mapOf("subject" to it.subject, "reason" to it.failureReason) },
                    "pendingCount" to pendingMessages.size
                ) + navModel + call.currentUserModel()
            )
        )
    }

    get("/inbox/settings") {
        val userId = call.requireUserId()
        val user = userStore.find(userId)
        val settings = settingsStore.get()
        call.respond(
            FreeMarkerContent(
                "settings.ftl",
                mapOf(
                    "sendersText" to settings.schoolSenders.joinToString(", "),
                    "lookbackWeeks" to settings.lookbackWeeks,
                    "hasAppPassword" to (user?.gmailAppPassword != null),
                    "activeNav" to "settings"
                ) + call.currentUserModel()
            )
        )
    }

    // Polled by inbox.ftl's processing banner (see app.js) while pendingCount
    // > 0 at page load - lets a message that finishes processing after the
    // page rendered show up without a manual reload, same pattern as
    // foodie's GET /recipe/status.
    get("/inbox/status") {
        val messages = messageStore.getAll()
        val actionItemsByMessage = actionItemStore.getAll().groupBy { it.sourceMessageId }
        call.respond(
            InboxStatusResponse(
                pending = messages.count { it.status == MessageStatus.PENDING },
                messages = messages.filter { it.status == MessageStatus.PROCESSED }.map { message ->
                    InboxMessageSummary(
                        id = message.id,
                        subject = message.subject,
                        from = message.from,
                        date = message.date,
                        summary = message.summary ?: "",
                        actionItems = (actionItemsByMessage[message.id] ?: emptyList())
                            .map { ActionItemSummary(it.title, it.description, it.date) }
                    )
                },
                failed = messages.filter { it.status == MessageStatus.FAILED }
                    .map { FailedMessageSummary(it.id, it.subject, it.failureReason) }
            )
        )
    }

    // Separate from Google sign-in entirely (see User.gmailAppPassword's doc
    // comment) - this is how a signed-in user grants IMAP access to their own
    // mailbox. Never echoes the stored value back to the form (the model's
    // hasAppPassword is a boolean, not the secret itself), so this form is
    // always blank - submitting it always overwrites, which is also how
    // rotating a revoked/changed app password works, not just first-time setup.
    post("/inbox/connect-gmail") {
        val userId = call.requireUserId()
        val appPassword = call.receiveParameters()["appPassword"]?.trim()
        if (!appPassword.isNullOrEmpty()) {
            userStore.saveGmailAppPassword(userId, appPassword)
        }
        call.respondRedirect("/inbox")
    }

    post("/inbox/settings") {
        val form = call.receiveParameters()
        val senders = parseSchoolSenders(form["senders"])
        // Clamped rather than trusting raw input - a stray huge number would
        // turn into an equally huge IMAP search window for no benefit;
        // zero/negative would search a nonsensical or empty window.
        val lookbackWeeks = (form["lookbackWeeks"]?.toIntOrNull() ?: 4).coerceIn(1, 52)
        settingsStore.save(ScanSettings(senders, lookbackWeeks))
        // Redirect-after-post so reloading /inbox re-runs the scan with the
        // new settings instead of resubmitting the form.
        call.respondRedirect("/inbox")
    }
}

// One IMAP round trip per sender-set, not per sender - senders/since together
// scope a single search (see GmailClient.buildSearchTerm). since is the
// earliest point any configured sender still needs scanning from: whichever
// sender has advanced the least (its own watermark, or lookbackWeeks if it's
// never been scanned - see ScanStateStore.kt's doc comment on why the
// watermark is per-sender). Re-pulling a message from a sender whose
// watermark is already past since is harmless - storeIfAbsent no-ops on an
// id already stored, so it's never reprocessed.
private suspend fun pullAndStoreNewMessages(
    email: String,
    appPassword: String,
    settings: ScanSettings,
    gmailClient: GmailClient,
    messageStore: MessageRepository,
    scanStateStore: ScanStateRepository
) {
    val watermarks = scanStateStore.getWatermarks()
    val fallbackSince = Instant.now().minus(Duration.ofDays(settings.lookbackWeeks * 7L))
    val since = settings.schoolSenders.minOf { watermarks[it] ?: fallbackSince }

    val fetched = gmailClient.searchMessages(email, appPassword, settings.schoolSenders, since)
    for (message in fetched) {
        messageStore.storeIfAbsent(
            EmailMessage(
                id = message.id,
                subject = message.subject,
                from = message.from,
                date = message.date,
                receivedAt = message.receivedAt,
                bodyText = message.bodyText
            )
        )
    }
    for (sender in settings.schoolSenders) {
        val latestForSender = fetched.filter { it.from.contains(sender, ignoreCase = true) }.maxOfOrNull { it.receivedAt }
        if (latestForSender != null) scanStateStore.recordSeen(sender, latestForSender)
    }
}

private val groupHeadingFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")

// ActionItem.date is already YYYY-MM-DD (optionally with a 'T'HH:MM suffix -
// see ActionItemStore.kt's doc comment), so the first 10 characters are
// always the grouping key when it's set. When it's null (the email stated no
// date), the main page still needs somewhere to put the item - the email's
// own sent date (message.receivedAt, not the unparsed display string in
// EmailMessage.date) is the next-best thing, per the maintainer's ask.
// Fixed at UTC rather than the server's local zone so the grouping key is
// deterministic regardless of where this happens to run.
private fun ActionItem.dateKeyAndTime(message: EmailMessage?): Pair<String, String?> {
    val raw = date
    if (raw != null && raw.length >= 10) {
        return raw.take(10) to raw.drop(10).removePrefix("T").ifEmpty { null }
    }
    val fallbackKey = message?.receivedAt?.atZone(ZoneOffset.UTC)?.toLocalDate()?.toString() ?: "unknown-date"
    return fallbackKey to null
}

private fun formatGroupHeading(dateKey: String): String =
    runCatching { LocalDate.parse(dateKey).format(groupHeadingFormatter) }.getOrDefault(dateKey)

// Every action item across every message, grouped by the date computed
// above and sorted chronologically - the main page's primary content (see
// CLAUDE.md/this task's nav rework). messagesById supplies each item's
// source message for display context (subject/from/summary) and the
// sent-date fallback.
private fun buildDateGroups(actionItems: List<ActionItem>, messagesById: Map<String, EmailMessage>): List<Map<String, Any?>> {
    data class Dated(val dateKey: String, val time: String?, val item: ActionItem, val message: EmailMessage?)

    val dated = actionItems.map { item ->
        val message = messagesById[item.sourceMessageId]
        val (dateKey, time) = item.dateKeyAndTime(message)
        Dated(dateKey, time, item, message)
    }
    return dated.groupBy { it.dateKey }.entries.sortedBy { it.key }.map { (dateKey, entries) ->
        mapOf(
            "displayDate" to formatGroupHeading(dateKey),
            "items" to entries.map { dated ->
                mapOf(
                    "title" to dated.item.title,
                    "description" to dated.item.description,
                    "date" to dated.item.date,
                    "time" to dated.time,
                    "subject" to (dated.message?.subject ?: ""),
                    "from" to (dated.message?.from ?: ""),
                    "summary" to (dated.message?.summary ?: "")
                )
            }
        )
    }
}
