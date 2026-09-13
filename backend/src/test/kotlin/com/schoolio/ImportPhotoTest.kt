package com.schoolio

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

// The photo-import feature doesn't need Gmail connected at all (unlike most
// of InboxTest) - it's reachable by any signed-in user, so these tests use
// plain signInFakeUser() rather than signInFakeUserWithGmailConnected.
class ImportPhotoTest {
    private fun fakePhotoFormData(fileName: String = "calendar.jpg", contentType: String = "image/jpeg", bytes: ByteArray = byteArrayOf(1, 2, 3, 4)) =
        formData {
            append("photo", bytes, Headers.build {
                append(HttpHeaders.ContentType, contentType)
                append(HttpHeaders.ContentDisposition, "form-data; name=\"photo\"; filename=\"$fileName\"")
            })
        }

    @Test
    fun testUploadFormIsReachableForAnySignedInUserWithoutGmailConnected() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.get("/inbox/import-photo")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Import from a photo"))
    }

    @Test
    fun testUploadingAPhotoShowsExtractedEventsOnAReviewPageWithoutPersistingThemYet() = testApplication {
        val geminiClient = FakeGeminiClient(
            photoEvents = listOf(
                ExtractedCalendarEvent(title = "Picture day", date = "2026-09-25"),
                ExtractedCalendarEvent(title = "Early dismissal", date = "2026-09-30", time = "13:00", description = "1pm release")
            )
        )
        val actionItemStore = FakeActionItemRepository()
        testModule(geminiClient = geminiClient, actionItemStore = actionItemStore)
        val client = signInFakeUser()

        val response = client.submitFormWithBinaryData("/inbox/import-photo", fakePhotoFormData())
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains("Picture day"))
        assertTrue(body.contains("Early dismissal"))
        assertTrue(body.contains("1pm release"))
        assertEquals(4, geminiClient.lastImageBytesSize)
        assertEquals("image/jpeg", geminiClient.lastImageMimeType)
        // Nothing should be created until the review step is confirmed.
        assertTrue(actionItemStore.getAll().isEmpty())
    }

    @Test
    fun testNoFileSelectedShowsAnErrorInsteadOfCallingGemini() = testApplication {
        val geminiClient = FakeGeminiClient()
        testModule(geminiClient = geminiClient)
        val client = signInFakeUser()

        val response = client.submitFormWithBinaryData("/inbox/import-photo", emptyList())
        assertTrue(response.bodyAsText().contains("Choose a photo to upload"))
        assertNull(geminiClient.lastImageBytesSize)
    }

    @Test
    fun testExtractionFailureShowsAFriendlyErrorInsteadOfCrashing() = testApplication {
        val geminiClient = object : GeminiClient {
            override suspend fun extract(subject: String, from: String, bodyText: String): EmailExtraction =
                error("not used")

            override suspend fun extractCalendarEventsFromImage(imageBytes: ByteArray, mimeType: String): List<ExtractedCalendarEvent> =
                error("Gemini vision call failed")
        }
        testModule(geminiClient = geminiClient)
        val client = signInFakeUser()

        val response = client.submitFormWithBinaryData("/inbox/import-photo", fakePhotoFormData())
        assertEquals(HttpStatusCode.OK, response.status)
        // Not "Couldn't ..." verbatim - FreeMarker's autoescaping (HTMLOutputFormat)
        // turns the apostrophe into an HTML entity in the rendered page.
        assertTrue(response.bodyAsText().contains("try a clearer picture"))
    }

    @Test
    fun testNoEventsFoundShowsAnEmptyResultMessage() = testApplication {
        val geminiClient = FakeGeminiClient(photoEvents = emptyList())
        testModule(geminiClient = geminiClient)
        val client = signInFakeUser()

        val response = client.submitFormWithBinaryData("/inbox/import-photo", fakePhotoFormData())
        assertTrue(response.bodyAsText().contains("No events found in that photo"))
    }

    @Test
    fun testConfirmingReviewedEventsCreatesActionItemsShownOnInboxAsFromAPhoto() = testApplication {
        val actionItemStore = FakeActionItemRepository()
        val userStore = FakeUserRepository()
        testModule(userStore = userStore, actionItemStore = actionItemStore)
        // GET /inbox only renders the action-items list once Gmail is
        // connected (see InboxRoutes.kt's needsGmailAccess branch) - a photo
        // import doesn't need that itself (see the earlier "reachable
        // without Gmail connected" test), but confirming *this* test's
        // assertion (the imported item shows up on /inbox) does.
        val client = signInFakeUserWithGmailConnected(userStore)

        val confirmResponse = client.submitForm(
            url = "/inbox/import-photo/confirm",
            formParameters = parameters {
                append("count", "2")
                append("include_0", "on")
                append("title_0", "Picture day")
                append("date_0", "2026-09-25")
                append("time_0", "")
                append("description_0", "")
                // Second row left unchecked - should be skipped entirely.
                append("title_1", "Field trip")
                append("date_1", "2026-10-01")
            }
        )
        assertEquals(HttpStatusCode.Found, confirmResponse.status)
        assertEquals("/inbox", confirmResponse.headers[HttpHeaders.Location])

        val items = actionItemStore.getAll()
        assertEquals(1, items.size)
        assertEquals("Picture day", items[0].title)
        assertEquals("2026-09-25", items[0].date)
        assertTrue(items[0].sourcePhotoImport)
        assertNull(items[0].sourceMessageId)
        assertNull(items[0].sourceCalendarEventId)

        val inboxBody = client.get("/inbox").bodyAsText()
        assertTrue(inboxBody.contains("Picture day"))
        assertTrue(inboxBody.contains("From a photo you uploaded"))
        assertFalse(inboxBody.contains("Field trip"))
    }

    @Test
    fun testConfirmingWithACombinedDateAndTimeStoresOneCombinedIsoField() = testApplication {
        val actionItemStore = FakeActionItemRepository()
        testModule(actionItemStore = actionItemStore)
        val client = signInFakeUser()

        client.submitForm(
            url = "/inbox/import-photo/confirm",
            formParameters = parameters {
                append("count", "1")
                append("include_0", "on")
                append("title_0", "Early dismissal")
                append("date_0", "2026-09-30")
                append("time_0", "13:00")
                append("description_0", "1pm release")
            }
        )

        val item = actionItemStore.getAll().single()
        assertEquals("2026-09-30T13:00", item.date)
        assertEquals("1pm release", item.description)
    }
}
