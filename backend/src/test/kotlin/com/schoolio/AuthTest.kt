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

    @Test
    fun testAllowedEmailCanSignIn() = testApplication {
        val userStore = FakeUserRepository()
        testModule(userStore = userStore, allowedEmails = setOf(TEST_EMAIL))
        val client = signInFakeUser()

        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Signed in as $TEST_EMAIL"))
        assertEquals(1, userStore.created.size)
        // extraAuthParameters (GoogleAuthFlow.kt) requests a fresh
        // refresh_token on every sign-in - confirm it actually got saved,
        // not just that sign-in succeeded.
        assertEquals("fake-refresh-token", userStore.find(TEST_SUB)?.googleRefreshToken)
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
    // earlier request already completed sign-in successfully - splash.ftl
    // must not show the error banner once a real session exists, or a
    // successful sign-in looks broken.
    @Test
    fun testSuccessfulSignInHidesErrorBannerEvenWithStaleAuthErrorParam() = testApplication {
        testModule()
        val client = signInFakeUser()

        val page = client.get("/?authError=1")
        assertTrue(page.bodyAsText().contains("Signed in as"))
        assertFalse(page.bodyAsText().contains("Couldn't sign you in"))
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
        assertTrue(client.get("/").bodyAsText().contains("Signed in as"))

        client.post("/logout")

        assertFalse(client.get("/").bodyAsText().contains("Signed in as"))
    }
}
