package com.schoolio

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.freemarker.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import freemarker.cache.ClassTemplateLoader
import freemarker.core.HTMLOutputFormat
import freemarker.template.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

// Must be distinct from foodie's Firestore database (foodie-nne1) - same GCP
// project (foodie-503510, see context.md's Configuration reference), so a
// name collision would be a real conflict, not just a style concern.
private val firestoreClient: Firestore by lazy {
    val databaseId = System.getenv("FIRESTORE_DATABASE_ID") ?: "schoolio"
    FirestoreOptions.newBuilder().setDatabaseId(databaseId).build().service
}

// Google OAuth token exchange + userinfo calls only now - Gmail access no
// longer goes through this client at all (IMAP instead, see
// ImapGmailClient/GmailClient.kt), just fast/low-volume identity calls, so
// CIO's default timeouts are fine.
private val oauthHttpClient: HttpClient by lazy {
    HttpClient(CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
}

// Separate from oauthHttpClient, same as foodie's own geminiHttpClient -
// Gemini generation can take noticeably longer than the fast OAuth/Gmail
// calls above, so it gets its own longer timeout rather than making every
// client share it. 120s matches foodie's documented value (CIO's 15s
// default is too short for Gemini's generation time).
private val geminiHttpClient: HttpClient by lazy {
    HttpClient(CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        engine {
            endpoint {
                requestTimeout = 120_000
            }
        }
    }
}

// Backs the debounced inbox-processing pass (see InboxRoutes.kt) - same
// SupervisorJob + Dispatchers.IO shape as foodie's own module-level
// backgroundScope, so one message's Gemini failure can't cancel the sweep
// for the rest, and IO-bound work doesn't tie up a request-handling thread.
private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

fun Application.module(
    // Falls back to a hardcoded insecure dev value if unset, same pattern as
    // sessionSecret below - fine locally, but must be set on Cloud Run or
    // every deploy effectively shares one weak key (and, unlike
    // sessionSecret, changing it after real app passwords are stored makes
    // those specific docs undecryptable - see toUser's runCatching fallback
    // in UserStore.kt for how that's handled without crashing).
    userStore: UserRepository = FirestoreUserStore(
        firestoreClient,
        System.getenv("GMAIL_APP_PASSWORD_KEY") ?: "dev-insecure-app-password-key"
    ),
    gmailClient: GmailClient = ImapGmailClient(),
    geminiClient: GeminiClient = RestGeminiClient(geminiHttpClient),
    oauthClient: HttpClient = oauthHttpClient,
    oauthRedirectBaseUrl: String = System.getenv("OAUTH_REDIRECT_BASE_URL") ?: "http://localhost:8080",
    sessionSecret: String = System.getenv("SESSION_SECRET") ?: "dev-insecure-session-secret",
    allowedEmails: Set<String> = parseAllowedEmails(System.getenv("ALLOWED_EMAILS")),
    // Seeds Firestore's settings doc only until someone saves real values via
    // the /inbox settings form (see SettingsStore.kt) - not read again after
    // that, so these env vars only matter for a fresh deploy nobody's
    // configured yet. SCHOOL_SENDERS has no fallback (empty means
    // unconfigured, not "match everything") - see parseSchoolSenders' doc
    // comment.
    settingsStore: SettingsRepository = FirestoreSettingsStore(
        firestoreClient,
        parseSchoolSenders(System.getenv("SCHOOL_SENDERS")),
        System.getenv("LOOKBACK_WEEKS")?.toIntOrNull() ?: 4
    ),
    messageStore: MessageRepository = FirestoreMessageStore(firestoreClient),
    actionItemStore: ActionItemRepository = FirestoreActionItemStore(firestoreClient),
    scanStateStore: ScanStateRepository = FirestoreScanStateStore(firestoreClient),
    // Overridable only so tests don't have to sleep the real default - see
    // InboxRoutes.kt's doc comment on the default value.
    inboxProcessDebounceMs: Long = DEFAULT_INBOX_PROCESS_DEBOUNCE_MS,
    inboxPullDebounceMs: Long = DEFAULT_INBOX_PULL_DEBOUNCE_MS,
    inboxResyncCooldownMs: Long = DEFAULT_INBOX_RESYNC_COOLDOWN_MS
) {
    install(FreeMarker) {
        templateLoader = ClassTemplateLoader(this::class.java.classLoader, "templates")
        setOutputFormat(HTMLOutputFormat.INSTANCE)
        autoEscapingPolicy = Configuration.ENABLE_IF_DEFAULT_AUTO_ESCAPING_POLICY
    }

    // Server-side JSON responses (GET /inbox/status - see InboxRoutes.kt),
    // distinct from the client-side ContentNegotiation installed on
    // oauthHttpClient/geminiHttpClient above.
    install(ContentNegotiation) {
        json()
    }

    installGoogleAuth(oauthClient, oauthRedirectBaseUrl, sessionSecret)

    routing {
        staticResources("/", "static")

        get("/") {
            val userModel = call.currentUserModel()
            if (userModel["currentUser"] != null) {
                // Signed-in visitors have no use for the splash page - send
                // them straight to the inbox instead of making them click
                // "View inbox" every time (also covers completeSignIn's
                // post-login redirect to "/", so that's one hop instead of
                // a rendered splash page in between).
                call.respondRedirect("/inbox")
                return@get
            }
            val revision = System.getenv("K_REVISION")
            call.respond(
                FreeMarkerContent(
                    "splash.ftl",
                    mapOf("revision" to revision, "authError" to (call.request.queryParameters["authError"] != null)) + userModel
                )
            )
        }

        authRoutes(oauthClient, userStore, allowedEmails)

        authenticate(USER_SESSION_PROVIDER_NAME) {
            inboxRoutes(
                userStore, gmailClient, geminiClient, settingsStore,
                messageStore, actionItemStore, scanStateStore,
                backgroundScope, inboxProcessDebounceMs, inboxPullDebounceMs, inboxResyncCooldownMs
            )
        }
    }
}
