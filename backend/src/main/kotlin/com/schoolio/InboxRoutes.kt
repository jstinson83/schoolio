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
        // Included on every branch below so the settings/app-password forms
        // always show the current values, whether or not a scan actually ran
        // this request.
        val settingsModel = mapOf(
            "sendersText" to settings.schoolSenders.joinToString(", "),
            "lookbackWeeks" to settings.lookbackWeeks,
            "hasAppPassword" to (appPassword != null)
        )

        if (appPassword == null) {
            call.respond(FreeMarkerContent("inbox.ftl", mapOf("needsGmailAccess" to true) + settingsModel + call.currentUserModel()))
            return@get
        }
        if (settings.schoolSenders.isEmpty()) {
            call.respond(FreeMarkerContent("inbox.ftl", mapOf("noSendersConfigured" to true) + settingsModel + call.currentUserModel()))
            return@get
        }

        pullAndStoreNewMessages(user.email, appPassword, settings, gmailClient, messageStore, scanStateStore)
        scheduleProcessing()

        val messages = messageStore.getAll()
        val actionItemsByMessage = actionItemStore.getAll().groupBy { it.sourceMessageId }
        val items = messages.map { message -> messagePageModel(message, actionItemsByMessage[message.id] ?: emptyList()) }
        val pendingCount = messages.count { it.status == MessageStatus.PENDING }
        call.respond(
            FreeMarkerContent(
                "inbox.ftl",
                mapOf("items" to items, "pendingCount" to pendingCount) + settingsModel + call.currentUserModel()
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

private fun messagePageModel(message: EmailMessage, actionItems: List<ActionItem>): Map<String, Any?> = mapOf(
    "id" to message.id,
    "subject" to message.subject,
    "from" to message.from,
    "date" to message.date,
    "status" to message.status.name,
    "summary" to message.summary,
    "failureReason" to message.failureReason,
    "actionItems" to actionItems.map { mapOf("title" to it.title, "description" to it.description, "date" to it.date) }
)
