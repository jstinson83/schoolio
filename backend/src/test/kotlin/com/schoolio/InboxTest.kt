package com.schoolio

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.time.Instant
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
                    receivedAt = Instant.parse("2026-09-01T14:00:00Z"),
                    bodyText = "Please sign and return by Friday."
                )
            )
        )
        val geminiClient = FakeGeminiClient(
            EmailExtraction(
                summary = "Permission slip needs a signature.",
                actionItems = listOf(
                    ExtractedActionItem(title = "Sign permission slip", description = "Sign and return the form", dueDate = "2026-09-04")
                )
            )
        )
        val userStore = FakeUserRepository()
        val settingsStore = FakeSettingsRepository(ScanSettings(listOf(TEST_SENDER), 3))
        val messageStore = FakeMessageRepository()
        testModule(
            userStore = userStore, gmailClient = gmailClient, geminiClient = geminiClient,
            settingsStore = settingsStore, messageStore = messageStore
        )
        val client = signInFakeUserWithGmailConnected(userStore, appPassword = "fake-app-password")

        // GET /inbox no longer blocks on the Gmail pull (or Gemini) - the
        // very first response should show a "checking your inbox" indicator
        // rather than the pulled message, which hasn't been fetched yet.
        val syncingBody = client.get("/inbox").bodyAsText()
        assertTrue(syncingBody.contains("Checking your inbox"))
        assertFalse(syncingBody.contains("Field trip permission slip"))

        awaitMessagesProcessed(messageStore)

        val body = client.get("/inbox").bodyAsText()
        assertTrue(body.contains("Field trip permission slip"))
        assertTrue(body.contains("teacher@school.example"))
        assertTrue(body.contains("Permission slip needs a signature."))
        assertTrue(body.contains("Sign permission slip"))
        assertTrue(body.contains("2026-09-04"))
        // The settings page (not the main inbox page anymore) should be
        // pre-filled with the current values.
        assertTrue(client.get("/inbox/settings").bodyAsText().contains(TEST_SENDER))
        assertEquals(TEST_EMAIL, gmailClient.lastEmailUsed)
        assertEquals("fake-app-password", gmailClient.lastAppPasswordUsed)
        assertEquals(listOf(TEST_SENDER), gmailClient.lastSendersUsed)
        assertEquals(listOf("Field trip permission slip"), geminiClient.extractedSubjects)
    }

    // Once a message is PROCESSED, re-pulling it (e.g. because another
    // sender's watermark hasn't advanced as far, see pullAndStoreNewMessages'
    // doc comment) must not run it through Gemini a second time.
    @Test
    fun testAlreadyProcessedMessageIsNotReExtractedOnANewPull() = testApplication {
        val gmailClient = FakeGmailClient(
            listOf(
                GmailMessage(
                    id = "1", subject = "Newsletter", from = TEST_SENDER,
                    date = "Mon, 1 Sep 2026 10:00:00 -0400", receivedAt = Instant.parse("2026-09-01T14:00:00Z"),
                    bodyText = "Nothing to act on."
                )
            )
        )
        val geminiClient = FakeGeminiClient(EmailExtraction(summary = "Nothing to act on.", actionItems = emptyList()))
        val userStore = FakeUserRepository()
        val messageStore = FakeMessageRepository()
        // Bypasses the resync cooldown (see testModule's doc comment) - this
        // test specifically needs a second real pull to prove it doesn't
        // re-run Gemini on an already-processed message.
        testModule(
            userStore = userStore, gmailClient = gmailClient, geminiClient = geminiClient,
            messageStore = messageStore, inboxResyncCooldownMs = 0
        )
        val client = signInFakeUserWithGmailConnected(userStore)

        client.get("/inbox")
        awaitMessagesProcessed(messageStore)
        assertEquals(1, geminiClient.extractedSubjects.size)
        assertEquals(1, gmailClient.searchCallCount)

        client.get("/inbox")
        // Confirms the second pull actually ran (not just that the store's
        // end state happens to look the same as after one pull) before
        // checking Gemini wasn't re-run on the already-processed message.
        awaitCondition("Second pull never ran") { gmailClient.searchCallCount >= 2 }
        awaitMessagesProcessed(messageStore)
        assertEquals(1, geminiClient.extractedSubjects.size, "Re-pulling an already-processed message shouldn't re-run Gemini on it")
    }

    // A message Gemini fails on stays visible with a reason instead of
    // silently vanishing - see InboxProcessingSweep.kt's markFailed comment.
    @Test
    fun testFailedExtractionShowsErrorInsteadOfLosingTheMessage() = testApplication {
        val gmailClient = FakeGmailClient(
            listOf(
                GmailMessage(
                    id = "1", subject = "Field trip form", from = TEST_SENDER,
                    date = "Mon, 1 Sep 2026 10:00:00 -0400", receivedAt = Instant.parse("2026-09-01T14:00:00Z"),
                    bodyText = "Please sign."
                )
            )
        )
        val geminiClient = object : GeminiClient {
            override suspend fun extract(subject: String, from: String, bodyText: String): EmailExtraction =
                error("Gemini is down")
        }
        val userStore = FakeUserRepository()
        val messageStore = FakeMessageRepository()
        testModule(userStore = userStore, gmailClient = gmailClient, geminiClient = geminiClient, messageStore = messageStore)
        val client = signInFakeUserWithGmailConnected(userStore)

        client.get("/inbox")
        awaitMessageStatus(messageStore, "1", MessageStatus.FAILED)

        val body = client.get("/inbox").bodyAsText()
        assertTrue(body.contains("Field trip form"))
        assertTrue(body.contains("Couldn't process this message"))
        assertTrue(body.contains("Gemini is down"))
    }

    // The second pull should scan forward from the first pull's watermark,
    // not repeat the same lookback-based window every time (see
    // pullAndStoreNewMessages' doc comment).
    @Test
    fun testSecondPullScansForwardFromTheWatermarkInsteadOfTheFullLookback() = testApplication {
        val firstMessage = GmailMessage(
            id = "1", subject = "First", from = TEST_SENDER,
            date = "Mon, 1 Sep 2026 10:00:00 -0400", receivedAt = Instant.parse("2026-09-01T14:00:00Z"),
            bodyText = "First body."
        )
        val gmailClient = FakeGmailClient(listOf(firstMessage))
        val userStore = FakeUserRepository()
        val messageStore = FakeMessageRepository()
        val scanStateStore = FakeScanStateRepository()
        // Bypasses the resync cooldown (see testModule's doc comment) - this
        // test specifically needs a second real pull to prove it scans
        // forward from the watermark left by the first one.
        testModule(
            userStore = userStore, gmailClient = gmailClient, messageStore = messageStore,
            scanStateStore = scanStateStore, inboxResyncCooldownMs = 0
        )
        val client = signInFakeUserWithGmailConnected(userStore)

        client.get("/inbox")
        awaitMessagesProcessed(messageStore)
        val firstSince = gmailClient.lastSinceUsed!!

        client.get("/inbox")
        awaitCondition("Second pull never ran") { gmailClient.lastSinceUsed != null && gmailClient.lastSinceUsed != firstSince }
        val secondSince = gmailClient.lastSinceUsed!!

        assertTrue(secondSince.isAfter(firstSince), "Second pull should scan from the watermark left by the first message, not the original lookback window")
        assertEquals(firstMessage.receivedAt, secondSince)
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
        client.awaitInboxSettled()
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

        client.get("/inbox")
        client.awaitInboxSettled()

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

    // Settings (Gmail app password + school senders) now live on their own
    // page, off the main inbox view - see InboxRoutes.kt's GET /inbox/settings.
    @Test
    fun testSettingsPageShowsCurrentConfigurationAndAppPasswordState() = testApplication {
        val userStore = FakeUserRepository()
        val settingsStore = FakeSettingsRepository(ScanSettings(listOf(TEST_SENDER), 6))
        testModule(userStore = userStore, settingsStore = settingsStore)
        val client = signInFakeUser()

        val disconnectedBody = client.get("/inbox/settings").bodyAsText()
        assertTrue(disconnectedBody.contains(TEST_SENDER))
        assertFalse(disconnectedBody.contains("already connected"))

        userStore.saveGmailAppPassword(TEST_SUB, "some-app-password")
        val connectedBody = client.get("/inbox/settings").bodyAsText()
        assertTrue(connectedBody.contains("already connected"))
    }

    // An action item whose known due date has already gone by moves to the
    // "Past events" section (between the upcoming date-groups and "Other
    // updates") rather than staying mixed into the main chronological list -
    // see InboxRoutes.kt's isPastDue/buildFlatActionItemViews.
    @Test
    fun testPastDueActionItemsAppearInThePastEventsSectionInsteadOfTheMainList() = testApplication {
        val userStore = FakeUserRepository()
        val actionItemStore = FakeActionItemRepository()
        actionItemStore.items.add(
            ActionItem(sourceMessageId = "none", title = "Overdue permission slip", description = "Sign ASAP", date = "2020-01-01")
        )
        actionItemStore.items.add(
            ActionItem(sourceMessageId = "none", title = "Upcoming field trip", description = "Pack a lunch", date = "2099-01-01")
        )
        testModule(userStore = userStore, gmailClient = FakeGmailClient(emptyList()), actionItemStore = actionItemStore)
        val client = signInFakeUserWithGmailConnected(userStore)
        client.get("/inbox")
        client.awaitInboxSettled()

        val body = client.get("/inbox").bodyAsText()
        assertTrue(body.contains("Past events"))
        val beforePastEvents = body.substringBefore("class=\"past-events\"")
        val pastEventsSection = body.substringAfter("class=\"past-events\"").substringBefore("</section>")
        assertTrue(beforePastEvents.contains("Upcoming field trip"), "Upcoming item should be in the main list, before Past events")
        assertFalse(beforePastEvents.contains("Overdue permission slip"), "Overdue item shouldn't be in the main list")
        assertTrue(pastEventsSection.contains("Overdue permission slip"))
        assertFalse(pastEventsSection.contains("Upcoming field trip"))
    }

    // Dismissing an action item (from either the main list or Past events)
    // removes it from /inbox and moves it to the unprominent GET
    // /inbox/dismissed page - see InboxRoutes.kt's dismiss/restore routes.
    @Test
    fun testDismissingAnActionItemMovesItToTheDismissedPageAndRestoreBringsItBack() = testApplication {
        val userStore = FakeUserRepository()
        val actionItemStore = FakeActionItemRepository()
        actionItemStore.items.add(
            ActionItem(id = "item-1", sourceMessageId = "none", title = "Field day forms", description = "Sign", date = "2020-01-01")
        )
        testModule(userStore = userStore, gmailClient = FakeGmailClient(emptyList()), actionItemStore = actionItemStore)
        val client = signInFakeUserWithGmailConnected(userStore)
        client.get("/inbox")
        client.awaitInboxSettled()

        assertTrue(client.get("/inbox").bodyAsText().contains("Field day forms"))
        assertFalse(client.get("/inbox/dismissed").bodyAsText().contains("Field day forms"))

        val dismissResponse = client.submitForm(url = "/inbox/action-items/item-1/dismiss", formParameters = Parameters.build {})
        assertEquals(HttpStatusCode.Found, dismissResponse.status)
        assertEquals("/inbox", dismissResponse.headers[HttpHeaders.Location])

        assertFalse(client.get("/inbox").bodyAsText().contains("Field day forms"))
        assertTrue(client.get("/inbox/dismissed").bodyAsText().contains("Field day forms"))

        val restoreResponse = client.submitForm(url = "/inbox/action-items/item-1/restore", formParameters = Parameters.build {})
        assertEquals(HttpStatusCode.Found, restoreResponse.status)
        assertEquals("/inbox/dismissed", restoreResponse.headers[HttpHeaders.Location])

        assertTrue(client.get("/inbox").bodyAsText().contains("Field day forms"))
        assertFalse(client.get("/inbox/dismissed").bodyAsText().contains("Field day forms"))
    }

    // Not prominent (see nav.ftl's nav-link-subtle), but always present so
    // dismissed items are never unreachable.
    @Test
    fun testNavIncludesALinkToDismissedActionItems() = testApplication {
        testModule()
        val client = signInFakeUser()

        assertTrue(client.get("/inbox").bodyAsText().contains("href=\"/inbox/dismissed\""))
    }
}
