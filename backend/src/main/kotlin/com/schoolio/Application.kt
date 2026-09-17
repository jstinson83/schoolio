package com.schoolio

import com.google.auth.oauth2.ServiceAccountCredentials
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

// Separate from oauthHttpClient/geminiHttpClient - Calendar API v3 calls are
// fast/low-volume like the OAuth calls (unlike Gemini's geminiHttpClient), so
// CIO's default timeout is fine, and there's no need for
// oauthHttpClient's ContentNegotiation plugin (GoogleCalendarApiClient
// decodes its own JSON response body directly).
private val calendarHttpClient: HttpClient by lazy { HttpClient(CIO) }

// The service account backing GoogleCalendarApiClient (see CalendarClient.kt's
// doc comment on why Calendar access is a shared service-account credential,
// not per-user OAuth or an app password) - CALENDAR_SERVICE_ACCOUNT_KEY holds
// the full downloaded JSON key content directly as an env var (same "secret
// as a plain env var" pattern as GEMINI_API_KEY, not a mounted file), so it's
// absent entirely on a deployment that hasn't set it up yet - unset locally
// is fine, it just means no calendar pull happens (see calendarClient below).
// Captured once as ServiceAccountCredentials (not yet scoped) so both the
// scoped credentials below and the display-only email on the settings page
// can be read from the same loaded key without parsing it twice.
private val calendarServiceAccount: ServiceAccountCredentials? by lazy {
    System.getenv("CALENDAR_SERVICE_ACCOUNT_KEY")?.let {
        ServiceAccountCredentials.fromStream(it.byteInputStream())
    }
}

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
    // Null (calendar pull skipped entirely - see scheduleSync in
    // InboxRoutes.kt) when CALENDAR_SERVICE_ACCOUNT_KEY isn't set.
    calendarClient: CalendarClient? = calendarServiceAccount?.let {
        GoogleCalendarApiClient(calendarHttpClient, it.createScoped(listOf("https://www.googleapis.com/auth/calendar.readonly")))
    },
    calendarServiceAccountEmail: String? = calendarServiceAccount?.clientEmail,
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
    notificationStateStore: NotificationStateRepository = FirestoreNotificationStateStore(firestoreClient),
    // Overridable only so tests don't have to sleep the real default - see
    // InboxRoutes.kt's doc comment on the default value.
    inboxProcessDebounceMs: Long = DEFAULT_INBOX_PROCESS_DEBOUNCE_MS,
    inboxPullDebounceMs: Long = DEFAULT_INBOX_PULL_DEBOUNCE_MS,
    inboxResyncCooldownMs: Long = DEFAULT_INBOX_RESYNC_COOLDOWN_MS,
    // Gates POST /internal/sync and POST /internal/notify-daily
    // (InboxRoutes.kt) - no insecure dev fallback, see internalSyncRoutes'
    // doc comment for why. Unset locally is fine, it just means both routes
    // always 401.
    internalSyncSecret: String? = System.getenv("INTERNAL_SYNC_SECRET"),
    // Web Push (WebPush.kt) - both null (notifications feature entirely
    // off, same "not configured on this deployment" nullability as
    // calendarClient) when VAPID keys haven't been generated/set yet.
    vapidPublicKey: String? = System.getenv("VAPID_PUBLIC_KEY"),
    vapidPrivateKey: String? = System.getenv("VAPID_PRIVATE_KEY"),
    // No dev-insecure fallback needed the way sessionSecret has one - unlike
    // a signing key, shipping a generic placeholder subject is harmless
    // (it's just contact info a push service *may* use if this deployment's
    // sends look abusive), so this only matters once real users are opted
    // in. Should still be set to a real contact on a real deployment.
    vapidSubject: String = System.getenv("VAPID_SUBJECT") ?: "mailto:admin@example.com",
    webPushSender: WebPushSender? = if (vapidPublicKey != null && vapidPrivateKey != null) {
        LibraryWebPushSender(vapidPublicKey, vapidPrivateKey, vapidSubject)
    } else null
) {
    install(FreeMarker) {
        templateLoader = ClassTemplateLoader(this::class.java.classLoader, "templates")
        setOutputFormat(HTMLOutputFormat.INSTANCE)
        autoEscapingPolicy = Configuration.ENABLE_IF_DEFAULT_AUTO_ESCAPING_POLICY
    }

    // Server-side JSON responses (GET /inbox/status) and requests (POST
    // /push/subscribe's PushSubscriptionRequest - see InboxRoutes.kt),
    // distinct from the client-side ContentNegotiation installed on
    // oauthHttpClient/geminiHttpClient above. ignoreUnknownKeys matters here
    // specifically for /push/subscribe: a real browser's
    // PushSubscription.toJSON() includes an expirationTime field
    // PushSubscriptionRequest doesn't declare, and kotlinx.serialization
    // rejects unknown keys by default - without this, every real subscribe
    // call 400s (BadRequestException) even though the shape it actually
    // needs (endpoint, keys.p256dh, keys.auth) decodes fine.
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
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

        // Outside authenticate(USER_SESSION_PROVIDER_NAME) - this is called by
        // Cloud Scheduler, not a signed-in browser, so it's gated by its own
        // shared-secret header instead (see internalSyncRoutes' doc comment).
        internalSyncRoutes(
            userStore, gmailClient, geminiClient, calendarClient, settingsStore,
            messageStore, actionItemStore, scanStateStore, allowedEmails, internalSyncSecret
        )
        internalNotifyRoutes(
            userStore, messageStore, actionItemStore, notificationStateStore,
            webPushSender, allowedEmails, internalSyncSecret
        )

        authenticate(USER_SESSION_PROVIDER_NAME) {
            inboxRoutes(
                userStore, gmailClient, geminiClient, calendarClient, calendarServiceAccountEmail, settingsStore,
                messageStore, actionItemStore, scanStateStore,
                backgroundScope, inboxProcessDebounceMs, inboxPullDebounceMs, inboxResyncCooldownMs,
                vapidPublicKey
            )
        }
    }
}
