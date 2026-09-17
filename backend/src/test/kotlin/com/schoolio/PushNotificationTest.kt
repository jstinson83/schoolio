package com.schoolio

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class PushNotificationTest {
    @Test
    fun testSubscribeStoresSubscriptionOnSignedInUser() = testApplication {
        val userStore = FakeUserRepository()
        testModule(userStore = userStore)
        val client = signInFakeUser()

        val response = client.post("/push/subscribe") {
            contentType(ContentType.Application.Json)
            setBody(
                Json.encodeToString(
                    PushSubscriptionRequest.serializer(),
                    PushSubscriptionRequest("https://push.example.com/id", PushSubscriptionKeys("p256dh-value", "auth-value"))
                )
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val stored = userStore.find(TEST_SUB)?.pushSubscription
        assertEquals(PushSubscription("https://push.example.com/id", "p256dh-value", "auth-value"), stored)
    }

    // Regression test: a real browser's PushSubscription.toJSON() includes
    // an "expirationTime" field (usually null) that PushSubscriptionRequest
    // doesn't declare - the server's ContentNegotiation originally rejected
    // any unknown JSON key by default (kotlinx.serialization's own default),
    // so every real subscribe call 400ed even though this handler never
    // needed that field. Application.kt's install(ContentNegotiation) now
    // sets ignoreUnknownKeys = true - this posts the literal shape a browser
    // sends (raw JSON, not built from PushSubscriptionRequest, since that
    // type doesn't have an expirationTime field to encode in the first
    // place) to prove the real payload shape decodes.
    @Test
    fun testSubscribeAcceptsARealBrowserPayloadWithExtraFields() = testApplication {
        val userStore = FakeUserRepository()
        testModule(userStore = userStore)
        val client = signInFakeUser()

        val response = client.post("/push/subscribe") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"endpoint":"https://push.example.com/id","expirationTime":null,"keys":{"p256dh":"p256dh-value","auth":"auth-value"}}"""
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            PushSubscription("https://push.example.com/id", "p256dh-value", "auth-value"),
            userStore.find(TEST_SUB)?.pushSubscription
        )
    }

    @Test
    fun testSubscribeRequiresSignIn() = testApplication {
        testModule()

        val response = client.post("/push/subscribe") {
            contentType(ContentType.Application.Json)
            setBody(
                Json.encodeToString(
                    PushSubscriptionRequest.serializer(),
                    PushSubscriptionRequest("https://push.example.com/id", PushSubscriptionKeys("p", "a"))
                )
            )
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun testUnsubscribeClearsSubscription() = testApplication {
        val userStore = FakeUserRepository()
        testModule(userStore = userStore)
        val client = signInFakeUser()
        userStore.savePushSubscription(TEST_SUB, PushSubscription("https://push.example.com/id", "p", "a"))

        val response = client.post("/push/unsubscribe")

        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(userStore.find(TEST_SUB)?.pushSubscription)
    }

    @Test
    fun testInternalNotifyDailyRejectsWrongSecret() = testApplication {
        val webPushSender = FakeWebPushSender()
        testModule(internalSyncSecret = "correct", webPushSender = webPushSender)

        val response = client.post("/internal/notify-daily") { header("X-Internal-Sync-Secret", "wrong") }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, webPushSender.sent.size)
    }

    @Test
    fun testInternalNotifyDailyNoOpsWhenWebPushNotConfigured() = testApplication {
        // webPushSender defaults to null (see testModule's doc comment) -
        // same "not configured on this deployment" state as no VAPID keys.
        testModule(internalSyncSecret = "s")

        val response = client.post("/internal/notify-daily") { header("X-Internal-Sync-Secret", "s") }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun testInternalNotifyDailyNoOpsWhenNothingDueToday() = testApplication {
        val webPushSender = FakeWebPushSender()
        val userStore = FakeUserRepository()
        testModule(userStore = userStore, internalSyncSecret = "s", webPushSender = webPushSender, allowedEmails = setOf(TEST_EMAIL))
        userStore.findOrCreateByGoogle(TEST_SUB, TEST_EMAIL, TEST_NAME)
        userStore.savePushSubscription(TEST_SUB, PushSubscription("https://push.example.com/id", "p", "a"))

        val response = client.post("/internal/notify-daily") { header("X-Internal-Sync-Secret", "s") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, webPushSender.sent.size)
    }

    @Test
    fun testInternalNotifyDailySendsOnlyToSubscribedAllowedAccounts() = testApplication {
        val webPushSender = FakeWebPushSender()
        val userStore = FakeUserRepository()
        val actionItemStore = FakeActionItemRepository()
        actionItemStore.addAll(listOf(ActionItem(title = "Field trip form", description = "Sign", date = todayString())))
        testModule(
            userStore = userStore, actionItemStore = actionItemStore, internalSyncSecret = "s", webPushSender = webPushSender,
            allowedEmails = setOf(TEST_EMAIL, "spouse@example.com")
        )
        userStore.findOrCreateByGoogle(TEST_SUB, TEST_EMAIL, TEST_NAME)
        userStore.savePushSubscription(TEST_SUB, PushSubscription("https://push.example.com/id", "p", "a"))
        // spouse@example.com never signed in / never subscribed - should be
        // silently skipped, not an error.

        val response = client.post("/internal/notify-daily") { header("X-Internal-Sync-Secret", "s") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, webPushSender.sent.size)
        val push = webPushSender.sent.single()
        assertEquals("https://push.example.com/id", push.subscription.endpoint)
        assertEquals("/inbox", push.url)
        assertTrue(push.body.contains("1 thing"))
    }

    @Test
    fun testInternalNotifyDailySkipsIfAlreadySentToday() = testApplication {
        val webPushSender = FakeWebPushSender()
        val userStore = FakeUserRepository()
        val actionItemStore = FakeActionItemRepository()
        actionItemStore.addAll(listOf(ActionItem(title = "Field trip form", description = "Sign", date = todayString())))
        val notificationStateStore = FakeNotificationStateRepository()
        notificationStateStore.recordDailyDigestSent(todayString())
        testModule(
            userStore = userStore, actionItemStore = actionItemStore, internalSyncSecret = "s", webPushSender = webPushSender,
            notificationStateStore = notificationStateStore, allowedEmails = setOf(TEST_EMAIL)
        )
        userStore.findOrCreateByGoogle(TEST_SUB, TEST_EMAIL, TEST_NAME)
        userStore.savePushSubscription(TEST_SUB, PushSubscription("https://push.example.com/id", "p", "a"))

        val response = client.post("/internal/notify-daily") { header("X-Internal-Sync-Secret", "s") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, webPushSender.sent.size)
    }

    @Test
    fun testInternalNotifyDailyClearsSubscriptionOnGoneResult() = testApplication {
        val webPushSender = FakeWebPushSender(result = PushSendResult.Gone)
        val userStore = FakeUserRepository()
        val actionItemStore = FakeActionItemRepository()
        actionItemStore.addAll(listOf(ActionItem(title = "Field trip form", description = "Sign", date = todayString())))
        testModule(
            userStore = userStore, actionItemStore = actionItemStore, internalSyncSecret = "s", webPushSender = webPushSender,
            allowedEmails = setOf(TEST_EMAIL)
        )
        userStore.findOrCreateByGoogle(TEST_SUB, TEST_EMAIL, TEST_NAME)
        userStore.savePushSubscription(TEST_SUB, PushSubscription("https://push.example.com/id", "p", "a"))

        val response = client.post("/internal/notify-daily") { header("X-Internal-Sync-Secret", "s") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(userStore.find(TEST_SUB)?.pushSubscription)
    }

    @Test
    fun testForceSendsEvenWithNothingDueTodayAndDoesNotRecordState() = testApplication {
        val webPushSender = FakeWebPushSender()
        val userStore = FakeUserRepository()
        val notificationStateStore = FakeNotificationStateRepository()
        testModule(
            userStore = userStore, internalSyncSecret = "s", webPushSender = webPushSender,
            notificationStateStore = notificationStateStore, allowedEmails = setOf(TEST_EMAIL)
        )
        userStore.findOrCreateByGoogle(TEST_SUB, TEST_EMAIL, TEST_NAME)
        userStore.savePushSubscription(TEST_SUB, PushSubscription("https://push.example.com/id", "p", "a"))
        // No action items at all - the real path would no-op here.

        val response = client.post("/internal/notify-daily?force=true") { header("X-Internal-Sync-Secret", "s") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, webPushSender.sent.size)
        assertTrue(webPushSender.sent.single().body.contains("Test notification"))
        // A forced send is a test ping, not today's real digest - it must
        // not suppress the actual scheduled one later that day.
        assertNull(notificationStateStore.getLastDailyDigestDate())
    }

    @Test
    fun testForceBypassesAlreadySentTodayGuard() = testApplication {
        val webPushSender = FakeWebPushSender()
        val userStore = FakeUserRepository()
        val actionItemStore = FakeActionItemRepository()
        actionItemStore.addAll(listOf(ActionItem(title = "Field trip form", description = "Sign", date = todayString())))
        val notificationStateStore = FakeNotificationStateRepository()
        notificationStateStore.recordDailyDigestSent(todayString())
        testModule(
            userStore = userStore, actionItemStore = actionItemStore, internalSyncSecret = "s", webPushSender = webPushSender,
            notificationStateStore = notificationStateStore, allowedEmails = setOf(TEST_EMAIL)
        )
        userStore.findOrCreateByGoogle(TEST_SUB, TEST_EMAIL, TEST_NAME)
        userStore.savePushSubscription(TEST_SUB, PushSubscription("https://push.example.com/id", "p", "a"))

        val response = client.post("/internal/notify-daily?force=true") { header("X-Internal-Sync-Secret", "s") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, webPushSender.sent.size)
    }

    private fun todayString(): String = java.time.LocalDate.now(HOUSEHOLD_ZONE).toString()
}
