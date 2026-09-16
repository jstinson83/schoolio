package com.schoolio

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

// Hard cap on an uploaded calendar file (see POST /inbox/import-photo/extract) - a
// generous ceiling for an actual phone-camera photo or a multi-page PDF/docx
// (a few MB), just there to reject something absurd (a misdirected large
// file) before it's read into memory and sent to Gemini, not a real capacity
// limit for a two-person app.
const val MAX_IMPORT_FILE_BYTES = 15 * 1024 * 1024

private const val DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

// Word's own paragraph/table text, not any embedded images/headers-footers -
// XWPFWordExtractor.text already joins paragraphs with newlines, which is
// all Gemini needs as plain-text calendar/schedule content (see
// GeminiClient.kt's extractCalendarEventsFromText). Throws on anything that
// isn't a real .docx (e.g. an old binary .doc renamed to that extension) -
// left to the caller's existing try/catch around the whole extraction, same
// as a Gemini failure.
private fun extractDocxText(bytes: ByteArray): String =
    XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
        XWPFWordExtractor(doc).use { it.text }
    }

// Every "what day/time is this" computation across the app - calendar event
// display, calendar all-day-event date anchoring (CalendarClient.kt), the
// email-fallback date-grouping heading, and "is this past due" - uses this
// single zone rather than a mix of UTC and local, so a date never disagrees
// with itself between sections (e.g. a 9pm Eastern event landing under the
// wrong day's heading because it was formatted in UTC, where it's already
// past midnight). Hardcoded rather than configurable - this is a two-person
// household app for one specific household, not a multi-timezone product;
// ZoneId (not a fixed ZoneOffset) so DST transitions (EST/EDT) are handled
// automatically instead of drifting an hour off twice a year.
val HOUSEHOLD_ZONE: ZoneId = ZoneId.of("America/New_York")

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

// Wire shape for POST /inbox/import-photo/extract - the photo-import FAB
// on the main /inbox page (inbox.ftl/app.js) calls this once per photo via
// fetch() and appends [events] into that page's own on-page review list,
// rather than the server re-rendering a whole page per photo the way a plain
// form post would (see that route's doc comment for why: multiple photos
// need to accumulate into one review batch before anything is confirmed).
// [error] covers every "nothing to show" case - no file chosen, a too-large
// upload, Gemini throwing, or a real zero-event extraction - as one optional
// field rather than a non-2xx status, so the client's fetch handling doesn't
// need a separate branch for each: it always parses the JSON body and only
// ever checks whether [error] is set.
@Serializable
data class ExtractedEventSummary(val title: String, val date: String, val time: String? = null, val description: String? = null)

@Serializable
data class PhotoExtractionResponse(val events: List<ExtractedEventSummary> = emptyList(), val error: String? = null)

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
    resyncCooldownMs: Long = DEFAULT_INBOX_RESYNC_COOLDOWN_MS,
    // Shown on the settings page so app.js can pass it as
    // PushManager.subscribe()'s applicationServerKey - null (the
    // notifications section just says it's not configured) when
    // VAPID_PUBLIC_KEY/VAPID_PRIVATE_KEY aren't set on this deployment (see
    // Application.kt).
    vapidPublicKey: String? = null
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
        val today = LocalDate.now(HOUSEHOLD_ZONE).toString()
        val (pastActionItems, upcomingActionItems) = allActionItems
            .filterNot { it.dismissed }
            .partition { it.isPastDue(today) }
        val processedWithNoActionItems = messages.filter {
            it.status == MessageStatus.PROCESSED && !it.dismissed && (actionItemsByMessage[it.id] ?: emptyList()).isEmpty()
        }
        val failedMessages = messages.filter { it.status == MessageStatus.FAILED }
        val pendingMessages = messages.filter { it.status == MessageStatus.PENDING }
        // Split out today's own group (at most one - see buildDateGroups'
        // isToday) so inbox.ftl can render it as its own "Today" section
        // ahead of everything else, rather than just another date-group in
        // the chronological list.
        val dateGroups = buildDateGroups(upcomingActionItems, messagesById, user.email, today)
        val todayGroup = dateGroups.firstOrNull { it["isToday"] == true }
        val upcomingGroups = dateGroups.filterNot { it["isToday"] == true }
        call.respond(
            FreeMarkerContent(
                "inbox.ftl",
                mapOf(
                    "syncing" to isSyncing(userId),
                    "todayGroup" to todayGroup,
                    "upcomingGroups" to upcomingGroups,
                    "pastActionItems" to buildFlatActionItemViews(pastActionItems, messagesById, user.email),
                    "pendingMessages" to pendingMessages.map { mapOf("subject" to it.subject) },
                    "noActionMessages" to processedWithNoActionItems.map {
                        mapOf("id" to it.id, "subject" to it.subject, "summary" to it.summary, "gmailLink" to it.gmailLinkFor(user.email))
                    },
                    "failedMessages" to failedMessages.map {
                        mapOf("subject" to it.subject, "reason" to it.failureReason, "gmailLink" to it.gmailLinkFor(user.email))
                    },
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
        val userId = call.requireUserId()
        val currentUserEmail = userStore.find(userId)?.email ?: ""
        val messages = messageStore.getAll()
        val messagesById = messages.associateBy { it.id }
        val dismissedItems = actionItemStore.getAll().filter { it.dismissed }
        val dismissedMessages = messages.filter { it.dismissed }
        call.respond(
            FreeMarkerContent(
                "dismissed.ftl",
                mapOf(
                    "dateGroups" to buildDateGroups(dismissedItems, messagesById, currentUserEmail),
                    "dismissedMessages" to dismissedMessages.map {
                        mapOf("id" to it.id, "subject" to it.subject, "summary" to it.summary, "gmailLink" to it.gmailLinkFor(currentUserEmail))
                    },
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

    // Tap-the-date-pill inline edit (inbox.ftl/app.js) - the date pill itself
    // is the control; tapping it swaps in a native date input in place, and
    // picking a new date submits this form. A plain redirect back to /inbox
    // (not a fetch+DOM patch) is deliberate here, same as dismiss/restore
    // above - the item needs to regroup under its new date heading
    // (buildDateGroups), which a full re-render already does for free. The
    // edit input only ever offers a plain date, so any time-of-day already
    // on the item (see ActionItem.date's doc comment) is read back off the
    // stored item and reattached rather than trusting the client to resend it.
    post("/inbox/action-items/{id}/date") {
        val id = call.parameters["id"]
        val newDate = call.receiveParameters()["date"]?.trim()
        if (id != null && !newDate.isNullOrEmpty()) {
            val existingTime = actionItemStore.get(id)?.date?.takeIf { it.length > 10 }?.drop(10)
            actionItemStore.updateDate(id, newDate + existingTime.orEmpty())
        }
        call.respondRedirect("/inbox")
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

    // The photo-import feature lives directly on the main /inbox page
    // (inbox.ftl/app.js) - a "+" FAB in the bottom corner expands into "Take
    // a photo"/"Choose a file" (the latter also accepting a PDF or Word
    // document, not just images - see libraryInput's accept list in
    // inbox.ftl), and every upload's extracted events land in one on-page
    // review list, so adding several files for a multi-month calendar builds
    // up one review batch instead of navigating away and back. No separate
    // page for this at all (there used to be one at GET /inbox/import-photo -
    // removed since there's no reason to leave /inbox to add one). The
    // review list starts empty - nothing is rendered server-side, it's all
    // built client-side from this route's JSON responses.
    //
    // Called via fetch() once per file (see app.js) rather than a plain form
    // post, specifically so the page can append this call's events onto
    // whatever earlier uploads already added instead of a normal form
    // submission's whole-page reload wiping out that in-progress review
    // list. Returns JSON, never a rendered page - PhotoExtractionResponse's
    // doc comment covers why every "nothing to show" case (bad upload,
    // Gemini failure, zero events) is just an [error] string on an
    // otherwise-200 response rather than a non-2xx status. Doesn't persist
    // anything itself - unlike the Calendar API pull (structured fields
    // straight from Google's own data), reading dates/titles off a
    // photographed or scanned calendar is exactly the kind of error-prone
    // OCR/handwriting/transcription read that's worth a household member's
    // eyes before anything lands on /inbox (see context.md's "How much human
    // review..." open question - photo import is the one path here that
    // resolves it in favor of confirm-first), so POST
    // /inbox/import-photo/confirm below is still what actually calls
    // actionItemStore, once for every upload's events at once.
    post("/inbox/import-photo/extract") {
        var fileBytes: ByteArray? = null
        var mimeType: String? = null
        call.receiveMultipart().forEachPart { part ->
            if (part is PartData.FileItem && fileBytes == null) {
                mimeType = part.contentType?.toString()
                fileBytes = part.provider().readBytes()
            }
            part.dispose()
        }
        val bytes = fileBytes
        if (bytes == null || bytes.isEmpty()) {
            call.respond(PhotoExtractionResponse(error = "Choose a photo, PDF, or Word document to upload."))
            return@post
        }
        if (bytes.size > MAX_IMPORT_FILE_BYTES) {
            call.respond(PhotoExtractionResponse(error = "That file is too large - try a smaller one."))
            return@post
        }
        val events = try {
            when {
                mimeType == DOCX_MIME_TYPE -> geminiClient.extractCalendarEventsFromText(extractDocxText(bytes))
                mimeType == null || mimeType?.startsWith("image/") == true || mimeType == "application/pdf" ->
                    geminiClient.extractCalendarEventsFromImage(bytes, mimeType ?: "image/jpeg")
                else -> {
                    call.respond(PhotoExtractionResponse(error = "Choose a photo, PDF, or Word document to upload."))
                    return@post
                }
            }
        } catch (e: Exception) {
            logger.warn("Calendar extraction failed", e)
            call.respond(PhotoExtractionResponse(error = "Couldn't read that file - try again."))
            return@post
        }
        if (events.isEmpty()) {
            call.respond(PhotoExtractionResponse(error = "No events found in that file."))
            return@post
        }
        call.respond(
            PhotoExtractionResponse(
                events = events.map { ExtractedEventSummary(it.title, it.date, it.time, it.description) }
            )
        )
    }

    // Creates one ActionItem per checked/edited row from the on-page review
    // list above (form fields named title_0/date_0/... - built client-side
    // by app.js as each photo's POST .../extract response comes back, so
    // the indices span every photo added this visit, not just the last
    // one) - a plain addAll (fresh random ids), same "always inserts" shape
    // as the email pipeline, since a photo import is a one-off action with
    // no stable id to upsert against the way a recurring calendar pull has
    // (see ActionItem.sourcePhotoImport's doc comment). A row with its
    // checkbox unticked, or an emptied-out title, is silently skipped
    // rather than treated as an error - editing the review list down to
    // "just the ones I actually want" is the whole point of this step.
    post("/inbox/import-photo/confirm") {
        val form = call.receiveParameters()
        val count = form["count"]?.toIntOrNull() ?: 0
        val items = (0 until count).mapNotNull { i ->
            if (form["include_$i"] != "on") return@mapNotNull null
            val title = form["title_$i"]?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val date = form["date_$i"]?.trim().orEmpty()
            val time = form["time_$i"]?.trim().orEmpty()
            ActionItem(
                title = title,
                description = form["description_$i"]?.trim().orEmpty(),
                date = if (date.isEmpty()) null else if (time.isEmpty()) date else "${date}T$time",
                sourcePhotoImport = true
            )
        }
        actionItemStore.addAll(items)
        call.respondRedirect("/inbox")
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
                    "vapidPublicKey" to vapidPublicKey,
                    "hasPushSubscription" to (user?.pushSubscription != null),
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

    // Called via fetch() from the Settings page's "Enable notifications"
    // toggle (app.js), not a plain form post - there's nothing to redirect
    // to, just a client-side state flip once this confirms. Only stores the
    // subscription; POST /internal/notify-daily is what actually sends
    // anything, once a day.
    post("/push/subscribe") {
        val userId = call.requireUserId()
        val subscription = call.receive<PushSubscriptionRequest>()
        userStore.savePushSubscription(
            userId,
            PushSubscription(subscription.endpoint, subscription.keys.p256dh, subscription.keys.auth)
        )
        call.respond(HttpStatusCode.OK)
    }

    post("/push/unsubscribe") {
        val userId = call.requireUserId()
        userStore.clearPushSubscription(userId)
        call.respond(HttpStatusCode.OK)
    }
}

private const val INTERNAL_SYNC_SECRET_HEADER = "X-Internal-Sync-Secret"

// Drives the same pull+process pipeline as scheduleSync above, but for every
// ALLOWED_EMAILS account at once rather than just whoever's signed in and
// hitting GET /inbox - meant to be called on a Cloud Scheduler cron interval
// (see current.md's "Periodic sync + push notifications" sprint task), not
// from a browser. Mounted outside authenticate(USER_SESSION_PROVIDER_NAME)
// entirely (see Application.kt) - Cloud Scheduler has no user session, so
// this is gated by a shared secret header instead of session auth.
// internalSyncSecret (INTERNAL_SYNC_SECRET env var) has no dev-insecure
// fallback the way sessionSecret/appPasswordEncryptionKey do - same "unset
// means nobody gets in" call as ALLOWED_EMAILS, not "weaker but still works"
// - an open version of this route can trigger real Gemini calls and repeated
// IMAP/Calendar pulls against both mailboxes on a schedule, not just weaken
// cookie signing.
fun Route.internalSyncRoutes(
    userStore: UserRepository,
    gmailClient: GmailClient,
    geminiClient: GeminiClient,
    calendarClient: CalendarClient?,
    settingsStore: SettingsRepository,
    messageStore: MessageRepository,
    actionItemStore: ActionItemRepository,
    scanStateStore: ScanStateRepository,
    allowedEmails: Set<String>,
    internalSyncSecret: String?
) {
    post("/internal/sync") {
        val provided = call.request.headers[INTERNAL_SYNC_SECRET_HEADER]
        // MessageDigest.isEqual, not ==/.equals - a plain string compare
        // short-circuits on the first mismatched byte, which leaks the
        // correct secret's length/prefix through response timing to anyone
        // who can hit this route repeatedly. Failing closed on a blank
        // internalSyncSecret (rather than e.g. treating "" == "" as valid)
        // is what makes leaving INTERNAL_SYNC_SECRET unset actually disable
        // the route instead of accepting a blank header.
        if (internalSyncSecret.isNullOrEmpty() || provided == null ||
            !MessageDigest.isEqual(provided.toByteArray(), internalSyncSecret.toByteArray())
        ) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }

        // Same "nothing configured to scan yet" no-op GET /inbox's own
        // noSendersConfigured branch covers - schoolSenders is shared config
        // (one settings doc, not per-user), so this is checked once up front
        // rather than per account below.
        val settings = settingsStore.get()
        if (settings.schoolSenders.isEmpty()) {
            logger.debug("Internal sync: no school senders configured yet, skipping Gmail pull")
        } else {
            for (email in allowedEmails) {
                val appPassword = userStore.findByEmail(email)?.gmailAppPassword
                if (appPassword == null) {
                    // Same "hasn't connected Gmail yet" state GET /inbox's
                    // needsGmailAccess branch covers for a signed-in user -
                    // an account that's never signed in at all, or signed in
                    // but never saved an app password, isn't a failure here.
                    logger.debug("Internal sync: no Gmail app password for {} yet, skipping", email)
                    continue
                }
                try {
                    pullAndStoreNewMessages(email, appPassword, settings, gmailClient, messageStore, scanStateStore)
                } catch (e: Exception) {
                    logger.warn("Internal sync: Gmail pull failed for {}", email, e)
                }
            }
        }

        // Calendar has no per-user credential to check (see CalendarClient.kt) -
        // calendarClient's own nullability already covers "not configured on
        // this deployment at all" the same way scheduleSync's does.
        if (calendarClient != null) {
            for (email in allowedEmails) {
                try {
                    pullAndStoreCalendarEvents(email, calendarClient, actionItemStore)
                } catch (e: Exception) {
                    logger.debug("Internal sync: calendar pull failed for {} (probably not shared with the service account yet)", email, e)
                }
            }
        }

        // Runs once for every account's newly-pulled messages together, same
        // "one shared processing pass" shape as scheduleProcessing - not
        // debounced here since this route only runs on a scheduler's own
        // interval to begin with, not on every page view.
        processPendingMessages(messageStore, actionItemStore, geminiClient)
        call.respond(HttpStatusCode.OK)
    }
}

// Once-a-day digest push (WebPush.kt, current.md's "Periodic sync + push
// notifications" sprint plan) - a different cadence and concern from POST
// /internal/sync's every-few-minutes pull, so its own route on its own
// separate Cloud Scheduler job (a fixed morning time, not an interval)
// rather than folding a "is it morning yet" check into the frequent sync
// job. Reuses internalSyncSecret/the same header as internalSyncRoutes
// rather than minting a second shared secret for a two-person app - the
// trust boundary (Cloud Scheduler, no user session) is identical.
fun Route.internalNotifyRoutes(
    userStore: UserRepository,
    messageStore: MessageRepository,
    actionItemStore: ActionItemRepository,
    notificationStateStore: NotificationStateRepository,
    webPushSender: WebPushSender?,
    allowedEmails: Set<String>,
    internalSyncSecret: String?
) {
    post("/internal/notify-daily") {
        val provided = call.request.headers[INTERNAL_SYNC_SECRET_HEADER]
        if (internalSyncSecret.isNullOrEmpty() || provided == null ||
            !MessageDigest.isEqual(provided.toByteArray(), internalSyncSecret.toByteArray())
        ) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }

        // Web Push isn't configured on this deployment at all (no
        // VAPID_PUBLIC_KEY/VAPID_PRIVATE_KEY - see Application.kt) - same
        // "additive, quietly does nothing" nullability as calendarClient.
        if (webPushSender == null) {
            call.respond(HttpStatusCode.OK)
            return@post
        }

        val today = LocalDate.now(HOUSEHOLD_ZONE).toString()
        if (notificationStateStore.getLastDailyDigestDate() == today) {
            // Already sent today's digest - a Cloud Scheduler retry or an
            // accidental second trigger shouldn't ping the household twice.
            call.respond(HttpStatusCode.OK)
            return@post
        }

        // Same "what counts as today" as /inbox's own todayGroup split
        // (buildDateGroups' isToday) - dateKeyAndTime is the one place that
        // logic lives.
        val messagesById = messageStore.getAll().associateBy { it.id }
        val todayCount = actionItemStore.getAll().count { item ->
            !item.dismissed && item.dateKeyAndTime(item.sourceMessageId?.let { messagesById[it] }).first == today
        }
        if (todayCount == 0) {
            // Nothing due today - "only if there's something that day" (see
            // current.md). Deliberately doesn't record a sent date here:
            // that field means "a digest went out today," not "we checked."
            call.respond(HttpStatusCode.OK)
            return@post
        }

        val title = "What's going on today"
        val body = if (todayCount == 1) "1 thing needs your attention today." else "$todayCount things need your attention today."
        for (email in allowedEmails) {
            val subscription = userStore.findByEmail(email)?.pushSubscription ?: continue
            try {
                when (val result = webPushSender.send(subscription, title, body, "/inbox")) {
                    PushSendResult.Sent -> {}
                    PushSendResult.Gone -> {
                        // The push service has permanently discarded this
                        // subscription - clear it so future digests don't
                        // keep failing the same way for this account.
                        val userId = userStore.findByEmail(email)?.id
                        if (userId != null) userStore.clearPushSubscription(userId)
                    }
                    is PushSendResult.Failed -> logger.warn("Daily digest push failed for {} with status {}", email, result.status)
                }
            } catch (e: Exception) {
                logger.warn("Daily digest push threw for {}", email, e)
            }
        }
        notificationStateStore.recordDailyDigestSent(today)
        call.respond(HttpStatusCode.OK)
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
                bodyText = message.bodyText,
                scannedByEmail = email
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
// re-scanning. Uses ActionItemRepository.upsertFromCalendar, keyed on the
// calendar event's own uid - not just dedup (MessageRepository.storeIfAbsent's
// "skip if already stored" shape doesn't fit here), because Calendar is the
// ongoing source of truth and an event can be retitled/rescheduled between
// pulls; every pull refreshes the stored copy rather than only inserting it
// once. No Gemini step for these - a calendar event already carries a
// title/date natively, unlike an EmailMessage's free-text body that needs
// the LLM to find one.
private suspend fun pullAndStoreCalendarEvents(
    email: String,
    calendarClient: CalendarClient,
    actionItemStore: ActionItemRepository
) {
    val now = Instant.now()
    val until = now.plus(Duration.ofDays(CALENDAR_LOOKAHEAD_DAYS))
    val events = calendarClient.fetchEvents(email, now, until)
    for (event in events) {
        actionItemStore.upsertFromCalendar(
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

private val timedEventDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(HOUSEHOLD_ZONE)
private val allDayDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(HOUSEHOLD_ZONE)

private val groupHeadingFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")

// Only the household account whose own mailbox a message was pulled from can
// actually open EmailMessage.gmailSearchLink() - see EmailMessage.
// scannedByEmail's doc comment for why (a Gmail deep link is inherently
// account-scoped, and the same email delivered to both parents gets a
// different Message-ID in each mailbox). currentUserEmail is whichever
// household account is signed in for *this* request - comparing
// case-insensitively since email addresses aren't case-sensitive and Google
// account emails in particular are commonly typed/stored in mixed case.
private fun EmailMessage.gmailLinkFor(currentUserEmail: String): String? =
    if (scannedByEmail.isNotBlank() && scannedByEmail.equals(currentUserEmail, ignoreCase = true)) gmailSearchLink() else null

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
    val fallbackKey = message?.receivedAt?.atZone(HOUSEHOLD_ZONE)?.toLocalDate()?.toString() ?: "unknown-date"
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
// today, when passed, flags whichever group's dateKey matches it via
// "isToday" - lets the /inbox handler split that one group out for the
// "Today" section (see current.md's design for emphasizing today's items
// over the rest of the upcoming list) without this function needing to know
// anything about that split itself. Left null for /inbox/dismissed's own
// call, where "today" has no special meaning - isToday just comes back
// false for every group there.
private fun buildDateGroups(actionItems: List<ActionItem>, messagesById: Map<String, EmailMessage>, currentUserEmail: String, today: String? = null): List<Map<String, Any?>> {
    data class Dated(val dateKey: String, val time: String?, val item: ActionItem, val message: EmailMessage?)

    val dated = actionItems.map { item ->
        val message = item.sourceMessageId?.let { messagesById[it] }
        val (dateKey, time) = item.dateKeyAndTime(message)
        Dated(dateKey, time, item, message)
    }
    return dated.groupBy { it.dateKey }.entries.sortedBy { it.key }.map { (dateKey, entries) ->
        mapOf(
            "displayDate" to formatGroupHeading(dateKey),
            "isToday" to (dateKey == today),
            // Firestore's collection.get() (ActionItemStore.getAll) has no
            // orderBy, so the order items arrive in isn't guaranteed stable
            // across requests - without an explicit sort here, two
            // near-duplicate items (see context.md's cross-source
            // reconciliation note - a differently-worded email about the
            // same event, or an email and a calendar pull both producing an
            // item for it) could swap positions between page loads, making
            // it impossible to reliably dismiss "the one I already decided
            // was the duplicate" from the page. Sorting by title first also
            // lands likely-duplicate titles next to each other, which is
            // what actually makes them easy to compare and dismiss.
            "items" to entries.sortedWith(
                compareBy({ it.item.title.lowercase() }, { it.time ?: "" }, { it.item.id })
            ).map { dated ->
                mapOf(
                    "id" to dated.item.id,
                    "title" to dated.item.title,
                    "description" to dated.item.description,
                    "date" to dated.item.date,
                    "time" to dated.time,
                    "subject" to (dated.message?.subject ?: ""),
                    "from" to (dated.message?.from ?: ""),
                    "summary" to (dated.message?.summary ?: ""),
                    "photoImport" to dated.item.sourcePhotoImport,
                    "gmailLink" to dated.message?.gmailLinkFor(currentUserEmail)
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
// closest to becoming worth dismissing sit at the top. title/id are
// tiebreakers for items sharing a date - same "make Firestore's unordered
// getAll() deterministic across page loads, and land likely-duplicate
// titles next to each other" reasoning as buildDateGroups' own sort.
private fun buildFlatActionItemViews(actionItems: List<ActionItem>, messagesById: Map<String, EmailMessage>, currentUserEmail: String): List<Map<String, Any?>> =
    actionItems.sortedWith(
        compareByDescending<ActionItem> { it.date }.thenBy { it.title.lowercase() }.thenBy { it.id }
    ).map { item ->
        val message = item.sourceMessageId?.let { messagesById[it] }
        mapOf(
            "id" to item.id,
            "title" to item.title,
            "description" to item.description,
            "date" to item.date,
            "subject" to (message?.subject ?: ""),
            "from" to (message?.from ?: ""),
            "summary" to (message?.summary ?: ""),
            "photoImport" to item.sourcePhotoImport,
            "gmailLink" to message?.gmailLinkFor(currentUserEmail)
        )
    }
