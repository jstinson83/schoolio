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
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import freemarker.cache.ClassTemplateLoader
import freemarker.core.HTMLOutputFormat
import freemarker.template.Configuration
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

// Shared by the Google OAuth/userinfo calls and Gmail API calls - all fast,
// low-volume requests, so one client with CIO's default timeouts covers both.
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
// client share it.
private val geminiHttpClient: HttpClient by lazy {
    HttpClient(CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        engine {
            endpoint {
                requestTimeout = 60_000
            }
        }
    }
}

fun Application.module(
    userStore: UserRepository = FirestoreUserStore(firestoreClient),
    gmailClient: GmailClient = RestGmailClient(oauthHttpClient),
    geminiClient: GeminiClient = RestGeminiClient(geminiHttpClient),
    oauthClient: HttpClient = oauthHttpClient,
    oauthRedirectBaseUrl: String = System.getenv("OAUTH_REDIRECT_BASE_URL") ?: "http://localhost:8080",
    sessionSecret: String = System.getenv("SESSION_SECRET") ?: "dev-insecure-session-secret",
    allowedEmails: Set<String> = parseAllowedEmails(System.getenv("ALLOWED_EMAILS")),
    // How far back and who to scan - see README's "I don't want to pull all
    // my email" framing. Defaults to 4 weeks when unset; SCHOOL_SENDERS has
    // no fallback (empty means unconfigured, not "match everything") - see
    // parseSchoolSenders' doc comment.
    schoolSenders: List<String> = parseSchoolSenders(System.getenv("SCHOOL_SENDERS")),
    lookbackWeeks: Int = System.getenv("LOOKBACK_WEEKS")?.toIntOrNull() ?: 4
) {
    install(FreeMarker) {
        templateLoader = ClassTemplateLoader(this::class.java.classLoader, "templates")
        setOutputFormat(HTMLOutputFormat.INSTANCE)
        autoEscapingPolicy = Configuration.ENABLE_IF_DEFAULT_AUTO_ESCAPING_POLICY
    }

    installGoogleAuth(oauthClient, oauthRedirectBaseUrl, sessionSecret)

    routing {
        staticResources("/", "static")

        get("/") {
            val revision = System.getenv("K_REVISION")
            call.respond(
                FreeMarkerContent(
                    "splash.ftl",
                    mapOf("revision" to revision, "authError" to (call.request.queryParameters["authError"] != null)) + call.currentUserModel()
                )
            )
        }

        authRoutes(oauthClient, userStore, allowedEmails)

        authenticate(USER_SESSION_PROVIDER_NAME) {
            inboxRoutes(userStore, gmailClient, geminiClient, schoolSenders, lookbackWeeks)
        }
    }
}
