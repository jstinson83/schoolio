package com.schoolio

import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class AuthTest {
    @Test
    fun testSplashShowsSignInWhenSignedOut() = testApplication {
        testModule()
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("/auth/google"), "Signed-out splash page should link to the Google sign-in route")
        assertFalse(body.contains("Signed in as"), "Signed-out splash page shouldn't show the signed-in state")
    }

    // Regression test for a real bug: Application.kt always puts "authError"
    // in splash.ftl's model as a Boolean (true or false), never absent, so
    // the template's old "authError??" check (which tests whether the
    // variable is defined, not whether it's true) was always true - the
    // "Couldn't sign you in" banner showed on every plain visit to "/",
    // even one that never touched /auth/google at all.
    @Test
    fun testSplashHidesErrorBannerWithNoAuthErrorParam() = testApplication {
        testModule()
        val response = client.get("/")
        assertFalse(
            response.bodyAsText().contains("Couldn't sign you in"),
            "A plain signed-out visit with no ?authError param shouldn't show the sign-in error banner"
        )
    }

    @Test
    fun testAllowedEmailCanSignIn() = testApplication {
        val userStore = FakeUserRepository()
        testModule(userStore = userStore, allowedEmails = setOf(TEST_EMAIL))
        val client = signInFakeUser()

        // "/" sends signed-in visitors straight to the inbox now rather than
        // rendering splash.ftl's "Signed in as" state.
        val response = client.get("/")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/inbox", response.headers[HttpHeaders.Location])
        assertEquals(1, userStore.created.size)
        // Google sign-in no longer carries any Gmail credential - a fresh
        // account has no app password until the separate /inbox/connect-gmail
        // form is submitted (see InboxTest).
        assertEquals(null, userStore.find(TEST_SUB)?.gmailAppPassword)
    }

    @Test
    fun testDisallowedEmailCannotSignIn() = testApplication {
        val userStore = FakeUserRepository()
        testModule(
            userStore = userStore,
            oauthClient = fakeGoogleOAuthClient(email = "not-allowed@example.com"),
            allowedEmails = setOf(TEST_EMAIL)
        )
        val client = signInFakeUser()

        assertEquals(0, userStore.created.size, "No user should be created for a disallowed email")

        val response = client.get("/")
        assertFalse(response.bodyAsText().contains("Signed in as"), "A disallowed sign-in shouldn't produce a session")
    }

    @Test
    fun testDisallowedSignInShowsErrorBanner() = testApplication {
        testModule(oauthClient = fakeGoogleOAuthClient(email = "not-allowed@example.com"), allowedEmails = setOf(TEST_EMAIL))
        val client = createClient { install(HttpCookies); followRedirects = false }
        val loginResponse = client.get("/auth/google")
        val state = Url(loginResponse.headers[HttpHeaders.Location]!!).parameters["state"]!!

        val callbackResponse = client.get("/auth/google/callback?code=fake-code&state=$state")
        assertEquals("/?authError=1", callbackResponse.headers[HttpHeaders.Location])

        assertTrue(client.get("/?authError=1").bodyAsText().contains("Couldn't sign you in"))
    }

    // Regression test for the real bug hit in production: a duplicate
    // /auth/google/callback request (reusing an already-consumed
    // authorization code) can redirect to /?authError=1 even after an
    // earlier request already completed sign-in successfully - a signed-in
    // visit to "/" must never show the error banner, or a successful
    // sign-in looks broken. Now that "/" redirects signed-in visitors
    // straight to /inbox, splash.ftl (and its banner) never even renders
    // for them, stale authError param or not.
    @Test
    fun testSuccessfulSignInHidesErrorBannerEvenWithStaleAuthErrorParam() = testApplication {
        testModule()
        val client = signInFakeUser()

        val page = client.get("/?authError=1")
        assertEquals(HttpStatusCode.Found, page.status)
        assertEquals("/inbox", page.headers[HttpHeaders.Location])
    }

    @Test
    fun testUnauthenticatedPageRequestIsUnauthorized() = testApplication {
        testModule()
        val response = client.get("/inbox")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun testUnauthenticatedBrowserNavigationRedirectsToSplash() = testApplication {
        testModule()
        val client = createClient { followRedirects = false }
        val response = client.get("/inbox") { header(HttpHeaders.Accept, "text/html") }
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/", response.headers[HttpHeaders.Location])
    }

    @Test
    fun testLogoutClearsSession() = testApplication {
        testModule()
        val client = signInFakeUser()
        assertEquals("/inbox", client.get("/").headers[HttpHeaders.Location])

        client.post("/logout")

        assertFalse(client.get("/").bodyAsText().contains("Signed in as"))
    }
}
