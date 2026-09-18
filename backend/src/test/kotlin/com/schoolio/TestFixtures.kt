package com.schoolio

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.Instant

// Shared fakes/helpers for AuthTest and InboxTest, mirroring foodie's
// TestFixtures.kt shape (one file, grouped by what's faked rather than by
// test file).

const val TEST_SUB = "test-sub"
const val TEST_EMAIL = "test@example.com"
const val TEST_NAME = "Test User"
const val TEST_SENDER = "teacher@school.example"

class FakeUserRepository : UserRepository {
    val created = mutableListOf<User>()
    private val usersById = mutableMapOf<String, User>()

    override suspend fun findOrCreateByGoogle(googleSub: String, email: String, name: String): User {
        val existing = usersById[googleSub]
        val user = existing?.copy(email = email, name = name)
            ?: User(id = googleSub, googleSub = googleSub, email = email, name = name)
        usersById[googleSub] = user
        created.add(user)
        return user
    }

    override suspend fun find(id: String): User? = usersById[id]

    override suspend fun findByEmail(email: String): User? = usersById.values.find { it.email == email }

    override suspend fun saveGmailAppPassword(id: String, appPassword: String) {
        usersById[id]?.let { usersById[id] = it.copy(gmailAppPassword = appPassword) }
    }

    override suspend fun savePushSubscription(id: String, subscription: PushSubscription) {
        usersById[id]?.let {
            val updated = it.pushSubscriptions.filterNot { existing -> existing.endpoint == subscription.endpoint } + subscription
            usersById[id] = it.copy(pushSubscriptions = updated)
        }
    }

    override suspend fun removePushSubscription(id: String, endpoint: String) {
        usersById[id]?.let { usersById[id] = it.copy(pushSubscriptions = it.pushSubscriptions.filterNot { existing -> existing.endpoint == endpoint }) }
    }
}

class FakeNotificationStateRepository : NotificationStateRepository {
    private var lastSentDate: String? = null

    override suspend fun getLastDailyDigestDate(): String? = lastSentDate

    override suspend fun recordDailyDigestSent(date: String) {
        lastSentDate = date
    }
}

// Records every send rather than actually reaching a push service - lets a
// test assert exactly which subscriptions got notified (and with what
// title/body/url) without any real network call or real VAPID keys.
class FakeWebPushSender(private val result: PushSendResult = PushSendResult.Sent) : WebPushSender {
    data class SentPush(val subscription: PushSubscription, val title: String, val body: String, val url: String)

    val sent = mutableListOf<SentPush>()

    override suspend fun send(subscription: PushSubscription, title: String, body: String, url: String): PushSendResult {
        sent.add(SentPush(subscription, title, body, url))
        return result
    }
}

class FakeGmailClient(private val messages: List<GmailMessage> = emptyList()) : GmailClient {
    var lastEmailUsed: String? = null
        private set
    var lastAppPasswordUsed: String? = null
        private set
    var lastSendersUsed: List<String>? = null
        private set
    var lastSinceUsed: Instant? = null
        private set
    // Lets a test prove a *second* real pull actually happened (not just
    // that its eventual state looks the same as after one pull) - useful
    // now that GET /inbox's resync cooldown (see InboxRoutes.kt) means a
    // second GET doesn't necessarily trigger a second call on its own.
    var searchCallCount = 0
        private set

    override suspend fun searchMessages(email: String, appPassword: String, senders: List<String>, since: Instant): List<GmailMessage> {
        lastEmailUsed = email
        lastAppPasswordUsed = appPassword
        lastSendersUsed = senders
        lastSinceUsed = since
        searchCallCount++
        return messages
    }
}

// In-memory stand-in for GcsAttachmentStore - a plain map keyed by
// storagePath, same "no real GCP call in tests" reasoning as
// FakeMessageRepository standing in for FirestoreMessageStore.
class FakeAttachmentStore : AttachmentRepository {
    private val blobs = mutableMapOf<String, ByteArray>()
    val uploaded = mutableListOf<String>()

    override suspend fun upload(storagePath: String, contentType: String, bytes: ByteArray) {
        blobs[storagePath] = bytes
        uploaded.add(storagePath)
    }

    override suspend fun download(storagePath: String): ByteArray? = blobs[storagePath]
}

class FakeCalendarClient(private var events: List<CalendarEvent> = emptyList()) : CalendarClient {
    // Lets a test change what the "calendar" returns between two pulls (e.g.
    // simulating the school retitling/rescheduling an event) without
    // needing a second FakeCalendarClient/testModule setup.
    fun setEvents(newEvents: List<CalendarEvent>) {
        events = newEvents
    }

    var lastCalendarIdUsed: String? = null
        private set
    var lastFromUsed: Instant? = null
        private set
    var lastUntilUsed: Instant? = null
        private set
    // Same "prove a second real pull actually happened" purpose as
    // FakeGmailClient.searchCallCount above.
    var fetchCallCount = 0
        private set

    override suspend fun fetchEvents(calendarId: String, from: Instant, until: Instant): List<CalendarEvent> {
        lastCalendarIdUsed = calendarId
        lastFromUsed = from
        lastUntilUsed = until
        fetchCallCount++
        return events
    }
}

class FakeSettingsRepository(initial: ScanSettings = ScanSettings(listOf(TEST_SENDER), 4)) : SettingsRepository {
    var current: ScanSettings = initial
        private set
    val saved = mutableListOf<ScanSettings>()

    override suspend fun get(): ScanSettings = current

    override suspend fun save(settings: ScanSettings) {
        current = settings
        saved.add(settings)
    }
}

// In-memory stand-in for MessageStore.kt's Firestore implementation - a plain
// map keyed by id, plus an explicit contentHash scan mirroring the real
// FirestoreMessageStore.storeIfAbsent's two checks (id, then contentHash).
class FakeMessageRepository : MessageRepository {
    private val messages = mutableMapOf<String, EmailMessage>()

    override suspend fun getAll(): List<EmailMessage> = messages.values.sortedByDescending { it.receivedAt }
    override suspend fun get(id: String): EmailMessage? = messages[id]
    override suspend fun getPending(): List<EmailMessage> = messages.values.filter { it.status == MessageStatus.PENDING }

    override suspend fun storeIfAbsent(message: EmailMessage) {
        if (messages.containsKey(message.id)) return
        if (messages.values.any { it.contentHash == message.contentHash }) return
        messages[message.id] = message
    }

    override suspend fun markProcessed(id: String, summary: String) {
        messages[id]?.let { messages[id] = it.copy(status = MessageStatus.PROCESSED, summary = summary, failureReason = null) }
    }

    override suspend fun markFailed(id: String, reason: String) {
        messages[id]?.let { messages[id] = it.copy(status = MessageStatus.FAILED, failureReason = reason) }
    }

    override suspend fun dismiss(id: String) {
        messages[id]?.let { messages[id] = it.copy(dismissed = true) }
    }

    override suspend fun restore(id: String) {
        messages[id]?.let { messages[id] = it.copy(dismissed = false) }
    }
}

class FakeActionItemRepository : ActionItemRepository {
    val items = mutableListOf<ActionItem>()

    override suspend fun getAll(): List<ActionItem> = items.toList()

    override suspend fun addAll(items: List<ActionItem>) {
        this.items.addAll(items)
    }

    override suspend fun upsertFromCalendar(item: ActionItem) {
        val index = items.indexOfFirst { it.id == item.id }
        if (index >= 0) items[index] = item.copy(dismissed = items[index].dismissed) else items.add(item)
    }

    override suspend fun dismiss(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items[index] = items[index].copy(dismissed = true)
    }

    override suspend fun restore(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items[index] = items[index].copy(dismissed = false)
    }

    override suspend fun get(id: String): ActionItem? = items.find { it.id == id }

    override suspend fun updateDate(id: String, date: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items[index] = items[index].copy(date = date)
    }
}

class FakeScanStateRepository(initial: Map<String, Instant> = emptyMap()) : ScanStateRepository {
    private val watermarks = initial.toMutableMap()

    override suspend fun getWatermarks(): Map<String, Instant> = watermarks.toMap()

    override suspend fun recordSeen(sender: String, at: Instant) {
        val existing = watermarks[sender]
        if (existing == null || at.isAfter(existing)) watermarks[sender] = at
    }
}

// Returns the same fixed extraction for every message - InboxTest only
// needs to prove the extraction reaches the page, not exercise prompt
// content (that's RestGeminiClient's own job, and it never touches Gemini
// for real in tests - see GeminiClientTest).
class FakeGeminiClient(
    private val extraction: EmailExtraction = EmailExtraction(
        summary = "Fake summary",
        actionItems = listOf(ExtractedActionItem(title = "Sign and return the form", description = "Sign and return the form", dueDate = "2026-09-19"))
    ),
    // Same "fixed canned result, not a real vision model" reasoning as
    // extraction above - the photo-import route's own logic (review page
    // rendering, confirm creating ActionItems) is what InboxTest exercises,
    // not Gemini's actual image reading (see GeminiClientTest for that).
    private val photoEvents: List<ExtractedCalendarEvent> = listOf(
        ExtractedCalendarEvent(title = "Picture day", date = "2026-09-25")
    )
) : GeminiClient {
    val extractedSubjects = mutableListOf<String>()
    var lastImageBytesSize: Int? = null
        private set
    var lastImageMimeType: String? = null
        private set
    var lastDocumentText: String? = null
        private set
    // Lets a test prove attachments actually reached the extraction call
    // (see InboxProcessingSweep.kt's attachmentsForExtraction), same purpose
    // lastImageBytesSize/lastImageMimeType serve for the photo-import path.
    var lastAttachmentCount: Int? = null
        private set

    override suspend fun extract(subject: String, from: String, bodyText: String, attachments: List<ExtractionAttachment>): EmailExtraction {
        extractedSubjects.add(subject)
        lastAttachmentCount = attachments.size
        return extraction
    }

    override suspend fun extractCalendarEventsFromImage(imageBytes: ByteArray, mimeType: String): List<ExtractedCalendarEvent> {
        lastImageBytesSize = imageBytes.size
        lastImageMimeType = mimeType
        return photoEvents
    }

    override suspend fun extractCalendarEventsFromText(documentText: String): List<ExtractedCalendarEvent> {
        lastDocumentText = documentText
        return photoEvents
    }
}

// Generic poll loop (same shape foodie's awaitEditResolved uses) - both the
// Gmail pull and the debounced Gemini processing pass run on Application.kt's
// module-level backgroundScope, a different coroutine context than the test
// itself, so a test can't just assume either is done the moment its
// triggering request (GET /inbox) returns.
suspend fun awaitCondition(failureMessage: String, timeoutIterations: Int = 150, intervalMs: Long = 20, predicate: suspend () -> Boolean) {
    repeat(timeoutIterations) {
        if (predicate()) return
        delay(intervalMs)
    }
    error(failureMessage)
}

// Waits for at least [expectedCount] messages to have been pulled AND left
// PENDING. The expectedCount check matters now that the Gmail pull itself is
// backgrounded (see InboxRoutes.kt's scheduleSync) - right after GET /inbox
// returns, the message store can still be completely empty (nothing pulled
// yet), which would otherwise make the old "getPending().isEmpty()" check
// vacuously true before the pull has even run.
suspend fun awaitMessagesProcessed(messageStore: MessageRepository, expectedCount: Int = 1) =
    awaitCondition("Messages never finished pulling/processing") {
        val all = messageStore.getAll()
        all.size >= expectedCount && all.none { it.status == MessageStatus.PENDING }
    }

// Same polling shape as awaitMessagesProcessed, for a test that specifically
// needs to observe a message reach FAILED (which getPending() alone can't
// distinguish from PROCESSED, since both leave PENDING).
suspend fun awaitMessageStatus(messageStore: MessageRepository, id: String, status: MessageStatus) =
    awaitCondition("Message $id never reached status $status") {
        messageStore.getAll().find { it.id == id }?.status == status
    }

// Polls GET /inbox/status until neither a Gmail pull nor Gemini processing
// is in flight for anyone - the single source of truth app.js's own banner
// polls (see InboxRoutes.kt's InboxStatusResponse), so tests that just need
// "the background work triggered by my last GET /inbox is done" (regardless
// of exactly how many messages that involved) can wait on it directly
// instead of reasoning about messageStore state.
suspend fun HttpClient.awaitInboxSettled() =
    awaitCondition("Inbox never finished syncing/processing") {
        val status = Json.decodeFromString<InboxStatusResponse>(get("/inbox/status").bodyAsText())
        !status.syncing && status.pending == 0
    }

// Stands in for Google's OAuth token/userinfo endpoints, same shape as
// foodie's fakeGoogleOAuthClient - lets AuthTest drive the real
// /auth/google -> /auth/google/callback round trip without leaving the
// process. No refresh_token in the token response anymore - this flow only
// ever requests identity scopes now (see GoogleAuthFlow.kt), Gmail access is
// a separate IMAP app-password concern entirely (GmailClient.kt).
fun fakeGoogleOAuthClient(
    sub: String = TEST_SUB,
    email: String = TEST_EMAIL,
    name: String = TEST_NAME
): HttpClient =
    HttpClient(MockEngine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        engine {
            addHandler { request ->
                val url = request.url.toString()
                when {
                    url.startsWith("https://oauth2.googleapis.com/token") -> respond(
                        """{"access_token":"fake-access-token","token_type":"Bearer"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                    url.startsWith("https://www.googleapis.com/oauth2/v3/userinfo") -> respond(
                        """{"sub":"$sub","email":"$email","name":"$name"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                    else -> error("Unexpected OAuth request to $url")
                }
            }
        }
    }

fun ApplicationTestBuilder.testModule(
    userStore: UserRepository = FakeUserRepository(),
    gmailClient: GmailClient = FakeGmailClient(),
    geminiClient: GeminiClient = FakeGeminiClient(),
    // Nullable like the real module()'s default - null means Calendar isn't
    // configured on this deployment (see InboxRoutes.kt's scheduleSync).
    // Non-null by default here so most tests exercise the calendar pull path
    // too (with zero events, a no-op); pass null explicitly to test the
    // "not configured at all" case.
    calendarClient: CalendarClient? = FakeCalendarClient(),
    calendarServiceAccountEmail: String? = "schoolio-calendar@test-project.iam.gserviceaccount.com",
    oauthClient: HttpClient = fakeGoogleOAuthClient(),
    oauthRedirectBaseUrl: String = "http://localhost:8080",
    sessionSecret: String = "test-session-secret",
    allowedEmails: Set<String> = setOf(TEST_EMAIL),
    settingsStore: SettingsRepository = FakeSettingsRepository(),
    messageStore: MessageRepository = FakeMessageRepository(),
    actionItemStore: ActionItemRepository = FakeActionItemRepository(),
    scanStateStore: ScanStateRepository = FakeScanStateRepository(),
    // Non-null by default (unlike the real module()'s "unset env var" default)
    // so most tests exercise the attachment upload/extraction path too - pass
    // null explicitly to test the "ATTACHMENTS_BUCKET not configured" case.
    attachmentStore: AttachmentRepository? = FakeAttachmentStore(),
    // Most tests want the pull and processing steps to happen practically
    // immediately rather than waiting out the real production debounces -
    // see awaitMessagesProcessed's doc comment for how a test then observes
    // it.
    inboxProcessDebounceMs: Long = 10L,
    inboxPullDebounceMs: Long = 10L,
    // Deliberately much longer than any single test's runtime (unlike the
    // debounces above) - most tests pull once, then re-fetch /inbox to check
    // rendered content, and that second GET shouldn't itself kick off (and
    // show the "checking" banner for) another real pull - see
    // InboxRoutes.kt's scheduleSync doc comment on why the cooldown exists
    // at all. Tests that specifically exercise a second real pull (re-pull
    // dedup, watermark advancement) pass 0 to bypass it.
    inboxResyncCooldownMs: Long = 600_000L,
    // Non-null default (unlike the real module()'s unset-means-401 default)
    // so a test can hit POST /internal/sync successfully without every
    // existing testModule() call needing to know about it.
    internalSyncSecret: String? = "test-internal-sync-secret",
    notificationStateStore: NotificationStateRepository = FakeNotificationStateRepository(),
    // Null by default (Web Push "not configured" - see WebPush.kt) so
    // existing tests that don't touch notifications don't need to know
    // about it; tests exercising POST /internal/notify-daily pass a
    // FakeWebPushSender explicitly.
    webPushSender: WebPushSender? = null,
    vapidPublicKey: String? = null
) {
    application {
        module(
            userStore = userStore,
            gmailClient = gmailClient,
            geminiClient = geminiClient,
            calendarClient = calendarClient,
            calendarServiceAccountEmail = calendarServiceAccountEmail,
            oauthClient = oauthClient,
            oauthRedirectBaseUrl = oauthRedirectBaseUrl,
            sessionSecret = sessionSecret,
            allowedEmails = allowedEmails,
            settingsStore = settingsStore,
            messageStore = messageStore,
            actionItemStore = actionItemStore,
            scanStateStore = scanStateStore,
            attachmentStore = attachmentStore,
            notificationStateStore = notificationStateStore,
            inboxProcessDebounceMs = inboxProcessDebounceMs,
            inboxPullDebounceMs = inboxPullDebounceMs,
            inboxResyncCooldownMs = inboxResyncCooldownMs,
            internalSyncSecret = internalSyncSecret,
            vapidPublicKey = vapidPublicKey,
            vapidPrivateKey = null,
            webPushSender = webPushSender
        )
    }
}

// Drives the real /auth/google -> /auth/google/callback round trip (against
// whatever fakeGoogleOAuthClient(...) was wired into this test's module()
// call as oauthClient) so gated-route tests exercise the exact production
// code path that sets the session cookie, same reasoning as foodie's
// signInFakeUser().
suspend fun ApplicationTestBuilder.signInFakeUser(): HttpClient {
    val client = createClient {
        install(HttpCookies)
        followRedirects = false
    }
    val loginResponse = client.get("/auth/google")
    val location = loginResponse.headers[HttpHeaders.Location]
        ?: error("Expected a redirect from /auth/google, got ${loginResponse.status}")
    val state = Url(location).parameters["state"]
        ?: error("Expected a state param in the Google authorize URL: $location")
    client.get("/auth/google/callback?code=fake-code&state=$state")
    return client
}

// Signs in (as signInFakeUser does) and separately saves a Gmail app
// password via the userStore directly, bypassing the /inbox/connect-gmail
// form - for tests whose focus is "given a connected account, does the
// inbox scan behave correctly", not the connect-Gmail flow itself.
suspend fun ApplicationTestBuilder.signInFakeUserWithGmailConnected(
    userStore: UserRepository,
    appPassword: String = "fake-app-password",
    sub: String = TEST_SUB
): HttpClient {
    val client = signInFakeUser()
    userStore.saveGmailAppPassword(sub, appPassword)
    return client
}
