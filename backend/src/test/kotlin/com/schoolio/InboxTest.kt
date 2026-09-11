package com.schoolio

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class InboxTest {
    @Test
    fun testInboxListsMessagesForSignedInUser() = testApplication {
        val gmailClient = FakeGmailClient(
            listOf(GmailMessageSummary("1", "Field trip permission slip", "Ms. Rivera <teacher@school.example>", "Mon, 1 Sep 2026 10:00:00 -0400"))
        )
        val userStore = FakeUserRepository()
        testModule(userStore = userStore, gmailClient = gmailClient)
        val client = signInFakeUser()
        // findOrCreateByGoogle/saveGoogleRefreshToken already ran as part of
        // sign-in (see Auth.kt's callback) - nothing extra to set up here.

        val response = client.get("/inbox")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Field trip permission slip"))
        assertTrue(body.contains("teacher@school.example"))
        assertEquals("fake-refresh-token", gmailClient.lastRefreshTokenUsed)
    }

    @Test
    fun testInboxPromptsForGmailAccessWithNoStoredRefreshToken() = testApplication {
        val userStore = FakeUserRepository()
        // issuesRefreshToken = false simulates a sign-in where Google didn't
        // return one (shouldn't normally happen given extraAuthParameters,
        // but the route needs to handle it rather than crash - see
        // InboxRoutes.kt's null check).
        testModule(userStore = userStore, oauthClient = fakeGoogleOAuthClient(issuesRefreshToken = false))
        val client = signInFakeUser()

        val response = client.get("/inbox")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Gmail access hasn't been granted yet"))
    }
}
