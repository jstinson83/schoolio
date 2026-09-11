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
import kotlinx.serialization.json.Json

// Shared fakes/helpers for AuthTest and InboxTest, mirroring foodie's
// TestFixtures.kt shape (one file, grouped by what's faked rather than by
// test file).

const val TEST_SUB = "test-sub"
const val TEST_EMAIL = "test@example.com"
const val TEST_NAME = "Test User"

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

    override suspend fun saveGoogleRefreshToken(id: String, refreshToken: String) {
        usersById[id]?.let { usersById[id] = it.copy(googleRefreshToken = refreshToken) }
    }
}

class FakeGmailClient(private val messages: List<GmailMessageSummary> = emptyList()) : GmailClient {
    var lastRefreshTokenUsed: String? = null
        private set

    override suspend fun listRecentMessages(refreshToken: String, maxResults: Int): List<GmailMessageSummary> {
        lastRefreshTokenUsed = refreshToken
        return messages
    }
}

// Stands in for Google's OAuth token/userinfo endpoints, same shape as
// foodie's fakeGoogleOAuthClient - lets AuthTest drive the real
// /auth/google -> /auth/google/callback round trip without leaving the
// process. issuesRefreshToken mirrors extraAuthParameters actually getting a
// refresh_token back from Google (see GoogleAuthFlow.kt's doc comment on why
// that's requested on every sign-in, not just the first).
fun fakeGoogleOAuthClient(
    sub: String = TEST_SUB,
    email: String = TEST_EMAIL,
    name: String = TEST_NAME,
    issuesRefreshToken: Boolean = true
): HttpClient =
    HttpClient(MockEngine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        engine {
            addHandler { request ->
                val url = request.url.toString()
                when {
                    url.startsWith("https://oauth2.googleapis.com/token") -> {
                        val refreshTokenField = if (issuesRefreshToken) ""","refresh_token":"fake-refresh-token"""" else ""
                        respond(
                            """{"access_token":"fake-access-token","token_type":"Bearer"$refreshTokenField}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        )
                    }
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
    oauthClient: HttpClient = fakeGoogleOAuthClient(),
    oauthRedirectBaseUrl: String = "http://localhost:8080",
    sessionSecret: String = "test-session-secret",
    allowedEmails: Set<String> = setOf(TEST_EMAIL)
) {
    application {
        module(
            userStore = userStore,
            gmailClient = gmailClient,
            oauthClient = oauthClient,
            oauthRedirectBaseUrl = oauthRedirectBaseUrl,
            sessionSecret = sessionSecret,
            allowedEmails = allowedEmails
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
