package com.schoolio

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.time.Instant
import kotlin.test.*

// POST /internal/sync - the Cloud Scheduler-driven pull, distinct from
// GET /inbox's per-signed-in-user scheduleSync (see InboxRoutes.kt's
// internalSyncRoutes doc comment and current.md's "Periodic sync" sprint
// task).
class InternalSyncTest {
    @Test
    fun testMissingSecretHeaderIsRejected() = testApplication {
        val gmailClient = FakeGmailClient()
        testModule(gmailClient = gmailClient)

        val response = client.post("/internal/sync")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, gmailClient.searchCallCount)
    }

    @Test
    fun testWrongSecretHeaderIsRejected() = testApplication {
        val gmailClient = FakeGmailClient()
        testModule(gmailClient = gmailClient, internalSyncSecret = "correct-secret")

        val response = client.post("/internal/sync") {
            header("X-Internal-Sync-Secret", "wrong-secret")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, gmailClient.searchCallCount)
    }

    // Unset INTERNAL_SYNC_SECRET must fail closed (never accept a blank
    // header as "matching"), not just reject a wrong value - see
    // internalSyncRoutes' doc comment.
    @Test
    fun testUnsetSecretRejectsEvenABlankHeader() = testApplication {
        val gmailClient = FakeGmailClient()
        testModule(gmailClient = gmailClient, internalSyncSecret = null)

        val response = client.post("/internal/sync") {
            header("X-Internal-Sync-Secret", "")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, gmailClient.searchCallCount)
    }

    // No school senders configured yet - the shared no-op case, checked once
    // rather than per allowed account.
    @Test
    fun testNoSendersConfiguredNoOpsTheGmailPull() = testApplication {
        val gmailClient = FakeGmailClient()
        val userStore = FakeUserRepository()
        val settingsStore = FakeSettingsRepository(ScanSettings(emptyList(), 4))
        testModule(
            userStore = userStore, gmailClient = gmailClient, settingsStore = settingsStore,
            allowedEmails = setOf(TEST_EMAIL), internalSyncSecret = "s"
        )
        userStore.findOrCreateByGoogle(TEST_SUB, TEST_EMAIL, TEST_NAME)
        userStore.saveGmailAppPassword(TEST_SUB, "app-password")

        val response = client.post("/internal/sync") { header("X-Internal-Sync-Secret", "s") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, gmailClient.searchCallCount)
    }

    // An allowed email with no stored User (never signed in) or no saved app
    // password must be skipped, not treated as an error.
    @Test
    fun testAllowedEmailWithNoAppPasswordIsSkipped() = testApplication {
        val gmailClient = FakeGmailClient()
        testModule(
            gmailClient = gmailClient,
            settingsStore = FakeSettingsRepository(ScanSettings(listOf(TEST_SENDER), 4)),
            allowedEmails = setOf(TEST_EMAIL, "spouse@example.com"),
            internalSyncSecret = "s"
        )

        val response = client.post("/internal/sync") { header("X-Internal-Sync-Secret", "s") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, gmailClient.searchCallCount)
    }

    @Test
    fun testPullsAndProcessesForEveryConfiguredAllowedAccount() = testApplication {
        val gmailClient = FakeGmailClient(
            listOf(
                GmailMessage(
                    id = "1",
                    subject = "Field trip permission slip",
                    from = "Ms. Rivera <$TEST_SENDER>",
                    date = "Mon, 1 Sep 2026 10:00:00 -0400",
                    receivedAt = Instant.parse("2026-09-01T14:00:00Z"),
                    bodyText = "Please sign and return by Friday."
                )
            )
        )
        val geminiClient = FakeGeminiClient()
        val userStore = FakeUserRepository()
        val messageStore = FakeMessageRepository()
        val actionItemStore = FakeActionItemRepository()
        val calendarClient = FakeCalendarClient()
        testModule(
            userStore = userStore, gmailClient = gmailClient, geminiClient = geminiClient,
            messageStore = messageStore, actionItemStore = actionItemStore, calendarClient = calendarClient,
            settingsStore = FakeSettingsRepository(ScanSettings(listOf(TEST_SENDER), 4)),
            allowedEmails = setOf(TEST_EMAIL), internalSyncSecret = "s"
        )
        userStore.findOrCreateByGoogle(TEST_SUB, TEST_EMAIL, TEST_NAME)
        userStore.saveGmailAppPassword(TEST_SUB, "app-password")

        val response = client.post("/internal/sync") { header("X-Internal-Sync-Secret", "s") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, gmailClient.searchCallCount)
        assertEquals(TEST_EMAIL, gmailClient.lastEmailUsed)
        assertEquals("app-password", gmailClient.lastAppPasswordUsed)
        assertEquals(1, calendarClient.fetchCallCount)
        // The route runs processing synchronously (no debounce, unlike
        // scheduleSync/scheduleProcessing) - a pulled message should already
        // be PROCESSED by the time the response comes back.
        val stored = messageStore.getAll().single()
        assertEquals(MessageStatus.PROCESSED, stored.status)
        assertEquals(1, actionItemStore.getAll().size)
    }

    // calendarClient == null (not configured on this deployment) must skip
    // the calendar pull entirely rather than NPE.
    @Test
    fun testNoCalendarClientSkipsCalendarPull() = testApplication {
        testModule(
            settingsStore = FakeSettingsRepository(ScanSettings(emptyList(), 4)),
            calendarClient = null,
            allowedEmails = setOf(TEST_EMAIL), internalSyncSecret = "s"
        )

        val response = client.post("/internal/sync") { header("X-Internal-Sync-Secret", "s") }

        assertEquals(HttpStatusCode.OK, response.status)
    }
}
