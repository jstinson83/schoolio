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

    override suspend fun saveGmailAppPassword(id: String, appPassword: String) {
        usersById[id]?.let { usersById[id] = it.copy(gmailAppPassword = appPassword) }
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

    override suspend fun searchMessages(email: String, appPassword: String, senders: List<String>, since: Instant): List<GmailMessage> {
        lastEmailUsed = email
        lastAppPasswordUsed = appPassword
        lastSendersUsed = senders
        lastSinceUsed = since
        return messages
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
// map keyed by id gives storeIfAbsent's dedupe semantics for free via
// putIfAbsent.
class FakeMessageRepository : MessageRepository {
    private val messages = mutableMapOf<String, EmailMessage>()

    override suspend fun getAll(): List<EmailMessage> = messages.values.sortedByDescending { it.receivedAt }
    override suspend fun getPending(): List<EmailMessage> = messages.values.filter { it.status == MessageStatus.PENDING }

    override suspend fun storeIfAbsent(message: EmailMessage) {
        messages.putIfAbsent(message.id, message)
    }

    override suspend fun markProcessed(id: String, summary: String) {
        messages[id]?.let { messages[id] = it.copy(status = MessageStatus.PROCESSED, summary = summary, failureReason = null) }
    }

    override suspend fun markFailed(id: String, reason: String) {
        messages[id]?.let { messages[id] = it.copy(status = MessageStatus.FAILED, failureReason = reason) }
    }
}

class FakeActionItemRepository : ActionItemRepository {
    val items = mutableListOf<ActionItem>()

    override suspend fun getAll(): List<ActionItem> = items.toList()

    override suspend fun addAll(items: List<ActionItem>) {
        this.items.addAll(items)
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
    )
) : GeminiClient {
    val extractedSubjects = mutableListOf<String>()

    override suspend fun extract(subject: String, from: String, bodyText: String): EmailExtraction {
        extractedSubjects.add(subject)
        return extraction
    }
}

// Polls messageStore (same shape as foodie's awaitEditResolved) until every
// message has left PENDING - the debounced processing pass runs on
// Application.kt's module-level backgroundScope, a different coroutine
// context than the test itself, so a test can't just assume it's done the
// moment its triggering request (GET /inbox) returns.
suspend fun awaitMessagesProcessed(messageStore: MessageRepository) {
    repeat(100) {
        if (messageStore.getPending().isEmpty()) return
        delay(20)
    }
    error("Messages never finished processing")
}

// Same polling shape as awaitMessagesProcessed, for a test that specifically
// needs to observe a message reach FAILED (which getPending() alone can't
// distinguish from PROCESSED, since both leave PENDING).
suspend fun awaitMessageStatus(messageStore: MessageRepository, id: String, status: MessageStatus) {
    repeat(100) {
        if (messageStore.getAll().find { it.id == id }?.status == status) return
        delay(20)
    }
    error("Message $id never reached status $status")
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
    oauthClient: HttpClient = fakeGoogleOAuthClient(),
    oauthRedirectBaseUrl: String = "http://localhost:8080",
    sessionSecret: String = "test-session-secret",
    allowedEmails: Set<String> = setOf(TEST_EMAIL),
    settingsStore: SettingsRepository = FakeSettingsRepository(),
    messageStore: MessageRepository = FakeMessageRepository(),
    actionItemStore: ActionItemRepository = FakeActionItemRepository(),
    scanStateStore: ScanStateRepository = FakeScanStateRepository(),
    // Most tests want processing to happen practically immediately rather
    // than waiting out the real production debounce - see
    // awaitMessagesProcessed's doc comment for how a test then observes it.
    inboxProcessDebounceMs: Long = 10L
) {
    application {
        module(
            userStore = userStore,
            gmailClient = gmailClient,
            geminiClient = geminiClient,
            oauthClient = oauthClient,
            oauthRedirectBaseUrl = oauthRedirectBaseUrl,
            sessionSecret = sessionSecret,
            allowedEmails = allowedEmails,
            settingsStore = settingsStore,
            messageStore = messageStore,
            actionItemStore = actionItemStore,
            scanStateStore = scanStateStore,
            inboxProcessDebounceMs = inboxProcessDebounceMs
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
