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
        val client = signInFakeUserWithGmailConnected(userStore, appPassword = "fake-app-password")

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
        assertEquals(TEST_EMAIL, gmailClient.lastEmailUsed)
        assertEquals("fake-app-password", gmailClient.lastAppPasswordUsed)
        assertEquals(listOf(TEST_SENDER), gmailClient.lastSendersUsed)
        assertEquals(3, gmailClient.lastSinceWeeksUsed)
        assertEquals(listOf("Field trip permission slip"), geminiClient.extractedSubjects)
    }

    @Test
    fun testInboxPromptsToConnectGmailWithNoStoredAppPassword() = testApplication {
        val userStore = FakeUserRepository()
        testModule(userStore = userStore)
        val client = signInFakeUser()

        val response = client.get("/inbox")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Gmail isn't connected yet"))
    }

    @Test
    fun testConnectingGmailSavesAppPasswordAndEnablesScanning() = testApplication {
        val gmailClient = FakeGmailClient(emptyList())
        val userStore = FakeUserRepository()
        testModule(userStore = userStore, gmailClient = gmailClient)
        val client = signInFakeUser()

        // Not connected yet - /inbox shouldn't even attempt a scan.
        assertTrue(client.get("/inbox").bodyAsText().contains("Gmail isn't connected yet"))
        assertEquals(null, gmailClient.lastAppPasswordUsed)

        val connectResponse = client.submitForm(
            url = "/inbox/connect-gmail",
            formParameters = Parameters.build { append("appPassword", "new-app-password") }
        )
        assertEquals(HttpStatusCode.Found, connectResponse.status)
        assertEquals("/inbox", connectResponse.headers[HttpHeaders.Location])
        assertEquals("new-app-password", userStore.find(TEST_SUB)?.gmailAppPassword)

        val inboxResponse = client.get("/inbox")
        assertFalse(inboxResponse.bodyAsText().contains("Gmail isn't connected yet"))
        assertEquals("new-app-password", gmailClient.lastAppPasswordUsed)
    }

    // A blank submission (e.g. the form's re-submitted without typing
    // anything into the now-empty-by-design field) shouldn't wipe out an
    // already-stored app password - see InboxRoutes.kt's isNullOrEmpty check.
    @Test
    fun testConnectingGmailWithBlankPasswordDoesNotOverwriteExisting() = testApplication {
        val userStore = FakeUserRepository()
        testModule(userStore = userStore)
        val client = signInFakeUserWithGmailConnected(userStore, appPassword = "original-password")

        client.submitForm(
            url = "/inbox/connect-gmail",
            formParameters = Parameters.build { append("appPassword", "") }
        )

        assertEquals("original-password", userStore.find(TEST_SUB)?.gmailAppPassword)
    }

    @Test
    fun testInboxPromptsToConfigureSendersWhenNoneSet() = testApplication {
        val gmailClient = FakeGmailClient()
        val userStore = FakeUserRepository()
        testModule(userStore = userStore, gmailClient = gmailClient, settingsStore = FakeSettingsRepository(ScanSettings(emptyList(), 4)))
        val client = signInFakeUserWithGmailConnected(userStore)

        val response = client.get("/inbox")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("No school senders are configured yet"))
        // Never even calls Gmail when nothing's configured to search for.
        assertEquals(null, gmailClient.lastAppPasswordUsed)
    }

    @Test
    fun testInboxShowsNoMessagesFoundWhenSearchReturnsNothing() = testApplication {
        val userStore = FakeUserRepository()
        testModule(userStore = userStore, gmailClient = FakeGmailClient(emptyList()))
        val client = signInFakeUserWithGmailConnected(userStore)

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
