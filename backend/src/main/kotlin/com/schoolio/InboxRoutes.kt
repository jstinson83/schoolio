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
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

// How long to wait after a Gmail pull before running Gemini over whatever's
// PENDING - long enough that a settings save (which redirects straight back
// into another pull) or a quick page reload coalesces into one processing
// pass instead of two overlapping ones. Overridable (see processDebounceMs
// below) so tests don't have to actually sleep the real default.
const val DEFAULT_INBOX_PROCESS_DEBOUNCE_MS = 5_000L

// Same coalescing reasoning as the process debounce above, applied to the
// Gmail pull itself now that it also runs in the background (see
// scheduleSync) - a settings save redirecting straight back into /inbox, or
// someone just hitting refresh, shouldn't open two overlapping IMAP
// searches for the same user. Shorter than the process debounce since
// there's no Gemini call to wait out here, just consecutive page loads.
const val DEFAULT_INBOX_PULL_DEBOUNCE_MS = 2_000L

// How often a signed-in user's Gmail can actually be re-checked, at most -
// see scheduleSync's doc comment for why this exists (bounding the
// view-page -> pull -> auto-reload -> view-page cycle so it settles instead
// of looping forever). A minute is frequent enough that new mail shows up
// without a manual refresh feeling laggy, while still keeping this a
// handful of IMAP connections per hour per user rather than one every few
// seconds if the tab's left open.
const val DEFAULT_INBOX_RESYNC_COOLDOWN_MS = 60_000L

// How far into the future a calendar pull looks - the inverse of
// ScanSettings.lookbackWeeks for email (see CalendarClient.kt's doc comment
// on why a bounded lookahead, not an open-ended "since", is what calendar
// needs). Hardcoded rather than a settings-form field for now - one fixed
// value is enough until there's a reason to make it configurable.
const val CALENDAR_LOOKAHEAD_DAYS = 7L

private val logger = LoggerFactory.getLogger("InboxRoutes")

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
// [syncing] is true while any user's Gmail pull is in flight (see
// scheduleSync) - distinct from [pending], which only tracks the Gemini
// processing step that comes after a pull finishes storing raw messages.
@Serializable
data class InboxStatusResponse(
    val pending: Int,
    val syncing: Boolean,
    val messages: List<InboxMessageSummary>,
    val failed: List<FailedMessageSummary>
)

// The app's main flow: pull the last-unseen email from `schoolSenders` only
// (never the whole inbox - see README's "Sender filtering" scope item),
// store it, then let a debounced background pass run each raw message
// through Gemini. GET /inbox no longer waits on either step - it used to run
// the IMAP pull synchronously and only backgrounded the Gemini step, but a
// real Gmail search over weeks of mail was still slow enough to freeze the
// page load, so the pull itself now runs in the background too (see
// scheduleSync) and the page just shows a "checking for new mail" indicator
// (see inbox.ftl/app.js) until it's done. schoolSenders/lookbackWeeks live in
// Firestore (SettingsRepository), edited via the form on this page.
// scheduleSync also pulls Calendar (if configured - see calendarClient's
// nullability below) in the same background job: unlike email, a calendar
// event needs no Gemini step (it already has a title/date), so
// pullAndStoreCalendarEvents turns each one directly into an ActionItem -
// see that function's own doc comment for the lookahead window and why no
// watermark is needed here the way there is for email.
fun Route.inboxRoutes(
    userStore: UserRepository,
    gmailClient: GmailClient,
    geminiClient: GeminiClient,
    // Null means Calendar access isn't configured on this deployment at all
    // (no CALENDAR_SERVICE_ACCOUNT_KEY set - see Application.kt) - unlike
    // Gmail, there's no per-user "connect" step to gate on anymore (see
    // CalendarClient.kt's doc comment on the service-account pivot), so the
    // only question is whether the feature is set up at the deployment level.
    calendarClient: CalendarClient?,
    // Shown on the settings page so a signed-in user knows which address to
    // share their calendar with (see settings.ftl) - null alongside
    // calendarClient above when the deployment has no service account
    // configured.
    calendarServiceAccountEmail: String?,
    settingsStore: SettingsRepository,
    messageStore: MessageRepository,
    actionItemStore: ActionItemRepository,
    scanStateStore: ScanStateRepository,
    backgroundScope: CoroutineScope,
    processDebounceMs: Long = DEFAULT_INBOX_PROCESS_DEBOUNCE_MS,
    pullDebounceMs: Long = DEFAULT_INBOX_PULL_DEBOUNCE_MS,
    resyncCooldownMs: Long = DEFAULT_INBOX_RESYNC_COOLDOWN_MS
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

    // Keyed per user (unlike processingJob above) because each account pulls
    // its own Gmail mailbox with its own app password (see
    // pullAndStoreNewMessages' email/appPassword params) - one household
    // account's IMAP search shouldn't debounce against the other's. A user id
    // present in this map with an active Job means that user's pull (and the
    // short debounce delay before it) hasn't finished yet - GET /inbox and
    // GET /inbox/status both read it to show a "checking for new mail"
    // indicator instead of the old behavior of blocking the whole page load
    // on the IMAP round trip.
    val pullJobs = ConcurrentHashMap<String, Job>()

    // When each user's pull last finished (successfully or not) - see
    // resyncCooldownMs below. Absent entirely means "never synced this
    // process lifetime," which always bypasses the cooldown so a user's very
    // first /inbox visit (or the first one after this app process restarts)
    // still checks immediately.
    val lastSyncedAt = ConcurrentHashMap<String, Instant>()

    fun isSyncing(userId: String) = pullJobs[userId]?.isActive == true

    // GET /inbox calls this on *every* request, so without some limit a user
    // who leaves the tab open would retrigger a brand-new pull on every
    // single page load - including the automatic reload app.js fires once a
    // pull finishes (see its poll loop), which would otherwise turn into an
    // infinite "checking for new mail" reload loop that never settles, even
    // once there's genuinely nothing new. isSyncing above already prevents
    // two overlapping pulls for the same user; resyncCooldownMs bounds how
    // often a *new* one can even start, the same purpose lookbackWeeks'
    // watermark serves for how far back a pull searches, just for how often
    // one runs at all.
    fun scheduleSync(userId: String, email: String, appPassword: String, settings: ScanSettings) {
        if (isSyncing(userId)) return
        val lastSynced = lastSyncedAt[userId]
        if (lastSynced != null && Duration.between(lastSynced, Instant.now()) < Duration.ofMillis(resyncCooldownMs)) return
        pullJobs[userId] = backgroundScope.launch {
            try {
                delay(pullDebounceMs)
                pullAndStoreNewMessages(email, appPassword, settings, gmailClient, messageStore, scanStateStore)
            } catch (e: Exception) {
                // Best-effort - a transient IMAP failure shouldn't leave this
                // user stuck "syncing" forever (see isSyncing above); the
                // next eligible GET /inbox just retries. Still logged (not
                // just swallowed) so a real failure isn't invisible. No
                // manual retry/error banner for a failed pull yet, same "not
                // built here yet" gap as message processing's own FAILED
                // state predates a manual retry.
                logger.warn("Gmail pull failed for user {}", userId, e)
            }
            // Calendar is a separate try/catch from Gmail's above, not one
            // shared block - the two pulls are independent (a calendar
            // failure shouldn't skip Gmail or vice versa), and a calendar
            // failure is routinely *expected* until a household member does
            // the one-time "share your calendar with the service account"
            // step (see CalendarClient.kt), unlike a Gmail failure - so it
            // logs at a lower level rather than warn.
            if (calendarClient != null) {
                try {
                    pullAndStoreCalendarEvents(email, calendarClient, actionItemStore)
                } catch (e: Exception) {
                    logger.debug("Calendar pull failed for user {} (probably not shared with the service account yet)", userId, e)
                }
            }
            lastSyncedAt[userId] = Instant.now()
            // Runs whether the pull(s) above succeeded or not, so a message
            // left PENDING by an earlier successful pull still gets
            // processed even if this particular pull attempt failed. Purely
            // an email-side concern - calendar events need no Gemini step
            // (see pullAndStoreCalendarEvents' doc comment), so this doesn't
            // wait on or otherwise involve the calendar pull.
            scheduleProcessing()
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

        scheduleSync(userId, user.email, appPassword, settings)

        val messages = messageStore.getAll()
        val messagesById = messages.associateBy { it.id }
        val allActionItems = actionItemStore.getAll()
        val actionItemsByMessage = allActionItems.groupBy { it.sourceMessageId }
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val (pastActionItems, upcomingActionItems) = allActionItems
            .filterNot { it.dismissed }
            .partition { it.isPastDue(today) }
        val processedWithNoActionItems = messages.filter {
            it.status == MessageStatus.PROCESSED && !it.dismissed && (actionItemsByMessage[it.id] ?: emptyList()).isEmpty()
        }
        val failedMessages = messages.filter { it.status == MessageStatus.FAILED }
        val pendingMessages = messages.filter { it.status == MessageStatus.PENDING }
        call.respond(
            FreeMarkerContent(
                "inbox.ftl",
                mapOf(
                    "syncing" to isSyncing(userId),
                    "dateGroups" to buildDateGroups(upcomingActionItems, messagesById),
                    "pastActionItems" to buildFlatActionItemViews(pastActionItems, messagesById),
                    "pendingMessages" to pendingMessages.map { mapOf("subject" to it.subject) },
                    "noActionMessages" to processedWithNoActionItems.map { mapOf("id" to it.id, "subject" to it.subject, "summary" to it.summary) },
                    "failedMessages" to failedMessages.map { mapOf("subject" to it.subject, "reason" to it.failureReason) },
                    "pendingCount" to pendingMessages.size
                ) + navModel + call.currentUserModel()
            )
        )
    }

    // Deliberately unprominent (see nav.ftl's nav-link-subtle) - a review
    // list for action items dismissed from the main /inbox view (see
    // POST .../dismiss below), not a page either household account needs to
    // visit often. Grouped the same way as the main page's upcoming section
    // (buildDateGroups) rather than the flat list past-events uses - there's
    // no urgency ordering to preserve here, and the date headings are still
    // useful context for "what was this."
    get("/inbox/dismissed") {
        val messages = messageStore.getAll()
        val messagesById = messages.associateBy { it.id }
        val dismissedItems = actionItemStore.getAll().filter { it.dismissed }
        val dismissedMessages = messages.filter { it.dismissed }
        call.respond(
            FreeMarkerContent(
                "dismissed.ftl",
                mapOf(
                    "dateGroups" to buildDateGroups(dismissedItems, messagesById),
                    "dismissedMessages" to dismissedMessages.map { mapOf("id" to it.id, "subject" to it.subject, "summary" to it.summary) },
                    "activeNav" to "dismissed"
                ) + call.currentUserModel()
            )
        )
    }

    // Dismiss always redirects back to /inbox (only ever posted from there)
    // and restore back to /inbox/dismissed (only ever posted from there) -
    // simpler and safer than trusting a Referer header for the redirect
    // target.
    post("/inbox/action-items/{id}/dismiss") {
        call.parameters["id"]?.let { actionItemStore.dismiss(it) }
        call.respondRedirect("/inbox")
    }

    post("/inbox/action-items/{id}/restore") {
        call.parameters["id"]?.let { actionItemStore.restore(it) }
        call.respondRedirect("/inbox/dismissed")
    }

    // Same dismiss/restore shape as action items above, for an "Other
    // updates" message (see inbox.ftl) that has no ActionItem of its own to
    // carry the dismissed flag.
    post("/inbox/messages/{id}/dismiss") {
        call.parameters["id"]?.let { messageStore.dismiss(it) }
        call.respondRedirect("/inbox")
    }

    post("/inbox/messages/{id}/restore") {
        call.parameters["id"]?.let { messageStore.restore(it) }
        call.respondRedirect("/inbox/dismissed")
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
                    "calendarServiceAccountEmail" to calendarServiceAccountEmail,
                    "activeNav" to "settings"
                ) + call.currentUserModel()
            )
        )
    }

    // Polled by inbox.ftl's banner (see app.js) while syncing is true or
    // pendingCount > 0 at page load - lets new mail/finished processing that
    // happen after the page rendered show up without a manual reload, same
    // pattern as foodie's GET /recipe/status.
    get("/inbox/status") {
        val messages = messageStore.getAll()
        val actionItemsByMessage = actionItemStore.getAll().groupBy { it.sourceMessageId }
        call.respond(
            InboxStatusResponse(
                pending = messages.count { it.status == MessageStatus.PENDING },
                syncing = pullJobs.values.any { it.isActive },
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

// Fixed [now, now + CALENDAR_LOOKAHEAD_DAYS] window, recomputed fresh on
// every call - no watermark the way pullAndStoreNewMessages has one. A
// watermark exists for email to keep an otherwise-unboundedly-large search
// narrow (see that function's doc comment); the calendar window is already
// small and doesn't grow, so there's no accumulating history to avoid
// re-scanning. Dedup instead comes from ActionItemRepository.storeIfAbsent,
// keyed on the calendar event's own uid, same "safe to re-fetch, no-ops on
// what's already stored" shape as MessageRepository.storeIfAbsent. No Gemini
// step for these - a calendar event already carries a title/date natively,
// unlike an EmailMessage's free-text body that needs the LLM to find one.
// (Not yet handled: an already-stored event whose time/title changes on the
// calendar after this first pulled it - storeIfAbsent only guards against
// duplicate inserts, not updates - see context.md's reconciliation note.)
private suspend fun pullAndStoreCalendarEvents(
    email: String,
    calendarClient: CalendarClient,
    actionItemStore: ActionItemRepository
) {
    val now = Instant.now()
    val until = now.plus(Duration.ofDays(CALENDAR_LOOKAHEAD_DAYS))
    val events = calendarClient.fetchEvents(email, now, until)
    for (event in events) {
        actionItemStore.storeIfAbsent(
            ActionItem(
                id = "calendar-${event.uid}",
                sourceCalendarEventId = event.uid,
                title = event.summary,
                description = event.description ?: "",
                // An all-day event (e.g. "No School - Teacher PD Day") gets
                // no time component - CalendarEvent.allDay comes straight
                // from Google Calendar API's own date-vs-dateTime
                // distinction (see CalendarClient.kt), not fabricated here.
                date = if (event.allDay) allDayDateFormatter.format(event.start) else timedEventDateFormatter.format(event.start)
            )
        )
    }
}

private val timedEventDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneOffset.UTC)
private val allDayDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

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

// Splits the main page's active (non-dismissed) action items into "Past
// events" vs. the upcoming date-groups above it - the maintainer's ask for a
// third section between the two that already existed, for items whose known
// due date has already gone by and are "likely to be dismissed... soon"
// rather than something to still act on. Only items with an actual dueDate
// from Gemini can be "past" - an item with no date at all falls back to its
// message's sent date for grouping (see dateKeyAndTime above), which says
// nothing about whether it's still actionable, so those stay in the
// upcoming section unchanged.
private fun ActionItem.isPastDue(today: String): Boolean {
    val raw = date ?: return false
    if (raw.length < 10) return false
    return raw.take(10) < today
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
        val message = item.sourceMessageId?.let { messagesById[it] }
        val (dateKey, time) = item.dateKeyAndTime(message)
        Dated(dateKey, time, item, message)
    }
    return dated.groupBy { it.dateKey }.entries.sortedBy { it.key }.map { (dateKey, entries) ->
        mapOf(
            "displayDate" to formatGroupHeading(dateKey),
            "items" to entries.map { dated ->
                mapOf(
                    "id" to dated.item.id,
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

// The Past events section is a flat list rather than grouped-by-date like
// buildDateGroups above - these items already had their date heading's
// moment come and go, so re-emphasizing exactly which day each one was due
// isn't useful the way it is for what's still upcoming. Sorted most-recently
// -due first (a date string still sorts correctly as a string here, same
// reasoning as buildDateGroups' sortedBy on the raw key) so the items
// closest to becoming worth dismissing sit at the top.
private fun buildFlatActionItemViews(actionItems: List<ActionItem>, messagesById: Map<String, EmailMessage>): List<Map<String, Any?>> =
    actionItems.sortedByDescending { it.date }.map { item ->
        val message = item.sourceMessageId?.let { messagesById[it] }
        mapOf(
            "id" to item.id,
            "title" to item.title,
            "description" to item.description,
            "date" to item.date,
            "subject" to (message?.subject ?: ""),
            "from" to (message?.from ?: ""),
            "summary" to (message?.summary ?: "")
        )
    }
