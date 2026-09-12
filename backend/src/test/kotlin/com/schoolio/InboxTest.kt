package com.schoolio

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class InboxTest {
    @Test
    fun testInboxListsMessagesWithGeminiExtractionForSignedInUser() = testApplication {
        val gmailClient = FakeGmailClient(
            listOf(
                GmailMessage(
                    id = "1",
                    subject = "Field trip permission slip",
                    from = "Ms. Rivera <teacher@school.example>",
                    date = "Mon, 1 Sep 2026 10:00:00 -0400",
                    bodyText = "Please sign and return by Friday."
                )
            )
        )
        val geminiClient = FakeGeminiClient(
            EmailExtraction(
                summary = "Permission slip needs a signature.",
                actionItems = listOf(ActionItem("Sign and return the form", dueDate = "2026-09-04"))
            )
        )
        val userStore = FakeUserRepository()
        val settingsStore = FakeSettingsRepository(ScanSettings(listOf(TEST_SENDER), 3))
        testModule(userStore = userStore, gmailClient = gmailClient, geminiClient = geminiClient, settingsStore = settingsStore)
        val client = signInFakeUser()
        // findOrCreateByGoogle/saveGoogleRefreshToken already ran as part of
        // sign-in (see Auth.kt's callback) - nothing extra to set up here.

        val response = client.get("/inbox")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Field trip permission slip"))
        assertTrue(body.contains("teacher@school.example"))
        assertTrue(body.contains("Permission slip needs a signature."))
        assertTrue(body.contains("Sign and return the form"))
        assertTrue(body.contains("2026-09-04"))
        // The settings form should be pre-filled with the current values.
        assertTrue(body.contains(TEST_SENDER))
        assertEquals("fake-refresh-token", gmailClient.lastRefreshTokenUsed)
        assertEquals(listOf(TEST_SENDER), gmailClient.lastSendersUsed)
        assertEquals(3, gmailClient.lastSinceWeeksUsed)
        assertEquals(listOf("Field trip permission slip"), geminiClient.extractedSubjects)
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

    @Test
    fun testInboxPromptsToConfigureSendersWhenNoneSet() = testApplication {
        val gmailClient = FakeGmailClient()
        testModule(gmailClient = gmailClient, settingsStore = FakeSettingsRepository(ScanSettings(emptyList(), 4)))
        val client = signInFakeUser()

        val response = client.get("/inbox")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("No school senders are configured yet"))
        // Never even calls Gmail when nothing's configured to search for.
        assertEquals(null, gmailClient.lastRefreshTokenUsed)
    }

    @Test
    fun testInboxShowsNoMessagesFoundWhenSearchReturnsNothing() = testApplication {
        testModule(gmailClient = FakeGmailClient(emptyList()))
        val client = signInFakeUser()

        val response = client.get("/inbox")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("No messages found."))
    }

    @Test
    fun testSavingSettingsPersistsAndRedirectsToInbox() = testApplication {
        val settingsStore = FakeSettingsRepository(ScanSettings(listOf(TEST_SENDER), 4))
        testModule(settingsStore = settingsStore)
        val client = signInFakeUser()

        val response = client.submitForm(
            url = "/inbox/settings",
            formParameters = Parameters.build {
                append("senders", "teacher@school.example, pta@school.example")
                append("lookbackWeeks", "8")
            }
        )

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/inbox", response.headers[HttpHeaders.Location])
        assertEquals(listOf("teacher@school.example", "pta@school.example"), settingsStore.current.schoolSenders)
        assertEquals(8, settingsStore.current.lookbackWeeks)
    }

    @Test
    fun testSavingSettingsClampsLookbackWeeksToASaneRange() = testApplication {
        val settingsStore = FakeSettingsRepository(ScanSettings(listOf(TEST_SENDER), 4))
        testModule(settingsStore = settingsStore)
        val client = signInFakeUser()

        client.submitForm(
            url = "/inbox/settings",
            formParameters = Parameters.build {
                append("senders", TEST_SENDER)
                append("lookbackWeeks", "999")
            }
        )

        assertEquals(52, settingsStore.current.lookbackWeeks)
    }
}
