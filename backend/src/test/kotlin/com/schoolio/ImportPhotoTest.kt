package com.schoolio

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.ByteArrayOutputStream
import kotlin.test.*

// The photo-import feature doesn't need Gmail connected at all (unlike most
// of InboxTest) - it's reachable by any signed-in user, so these tests use
// plain signInFakeUser() rather than signInFakeUserWithGmailConnected.
//
// There's no separate page for this at all - the FAB lives directly on
// GET /inbox (see inbox.ftl/app.js). A photo goes to
// POST /inbox/import-photo/extract via fetch() and its JSON response is
// what app.js appends into the on-page review list, so most of these tests
// exercise that JSON endpoint directly rather than a server-rendered page.
class ImportPhotoTest {
    private fun fakePhotoFormData(fileName: String = "calendar.jpg", contentType: String = "image/jpeg", bytes: ByteArray = byteArrayOf(1, 2, 3, 4)) =
        formData {
            append("photo", bytes, Headers.build {
                append(HttpHeaders.ContentType, contentType)
                append(HttpHeaders.ContentDisposition, "form-data; name=\"photo\"; filename=\"$fileName\"")
            })
        }

    @Test
    fun testFabIsOnMainInboxPageEvenWithoutGmailConnected() = testApplication {
        testModule()
        val client = signInFakeUser()

        // No app password saved - GET /inbox takes the needsGmailAccess
        // branch (see InboxRoutes.kt), which renders none of the usual
        // action-item content. The FAB markup lives outside that branch
        // entirely, so it should still be there.
        val response = client.get("/inbox")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Gmail isn't connected yet"))
        assertTrue(body.contains("id=\"fabButton\""))
        assertTrue(body.contains("Take a photo"))
        assertTrue(body.contains("Choose a file"))
    }

    @Test
    fun testExtractingAPhotoReturnsEventsAsJsonWithoutPersistingThemYet() = testApplication {
        val geminiClient = FakeGeminiClient(
            photoEvents = listOf(
                ExtractedCalendarEvent(title = "Picture day", date = "2026-09-25"),
                ExtractedCalendarEvent(title = "Early dismissal", date = "2026-09-30", time = "13:00", description = "1pm release")
            )
        )
        val actionItemStore = FakeActionItemRepository()
        testModule(geminiClient = geminiClient, actionItemStore = actionItemStore)
        val client = signInFakeUser()

        val response = client.submitFormWithBinaryData("/inbox/import-photo/extract", fakePhotoFormData())
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<PhotoExtractionResponse>(response.bodyAsText())

        assertNull(body.error)
        assertEquals(2, body.events.size)
        assertEquals("Picture day", body.events[0].title)
        assertEquals("2026-09-25", body.events[0].date)
        assertEquals("Early dismissal", body.events[1].title)
        assertEquals("13:00", body.events[1].time)
        assertEquals("1pm release", body.events[1].description)
        assertEquals(4, geminiClient.lastImageBytesSize)
        assertEquals("image/jpeg", geminiClient.lastImageMimeType)
        // Nothing should be created until the review step is confirmed.
        assertTrue(actionItemStore.getAll().isEmpty())
    }

    @Test
    fun testExtractingAPdfGoesThroughTheSameInlineDataPathAsAPhoto() = testApplication {
        val geminiClient = FakeGeminiClient()
        testModule(geminiClient = geminiClient)
        val client = signInFakeUser()

        val response = client.submitFormWithBinaryData(
            "/inbox/import-photo/extract",
            fakePhotoFormData(fileName = "schedule.pdf", contentType = "application/pdf", bytes = byteArrayOf(1, 2, 3, 4, 5))
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<PhotoExtractionResponse>(response.bodyAsText())
        assertNull(body.error)
        assertEquals(5, geminiClient.lastImageBytesSize)
        assertEquals("application/pdf", geminiClient.lastImageMimeType)
        assertNull(geminiClient.lastDocumentText)
    }

    @Test
    fun testExtractingAWordDocumentSendsItsExtractedTextToGeminiAsPlainText() = testApplication {
        val geminiClient = FakeGeminiClient(
            photoEvents = listOf(ExtractedCalendarEvent(title = "Spirit week", date = "2026-10-13"))
        )
        testModule(geminiClient = geminiClient)
        val client = signInFakeUser()

        // A real .docx, not a hand-rolled fake - proves InboxRoutes' extractDocxText
        // actually reads it via Apache POI rather than just forwarding raw bytes.
        val docxBytes = ByteArrayOutputStream().use { out ->
            XWPFDocument().use { doc ->
                doc.createParagraph().createRun().setText("Oct 13-17: Spirit week")
                doc.write(out)
            }
            out.toByteArray()
        }

        val response = client.submitFormWithBinaryData(
            "/inbox/import-photo/extract",
            fakePhotoFormData(
                fileName = "schedule.docx",
                contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                bytes = docxBytes
            )
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<PhotoExtractionResponse>(response.bodyAsText())
        assertNull(body.error)
        assertEquals("Spirit week", body.events[0].title)
        assertNull(geminiClient.lastImageBytesSize)
        assertNotNull(geminiClient.lastDocumentText)
        assertTrue(geminiClient.lastDocumentText!!.contains("Oct 13-17: Spirit week"))
    }

    @Test
    fun testUnsupportedFileTypeReturnsAnErrorInsteadOfCallingGemini() = testApplication {
        val geminiClient = FakeGeminiClient()
        testModule(geminiClient = geminiClient)
        val client = signInFakeUser()

        val response = client.submitFormWithBinaryData(
            "/inbox/import-photo/extract",
            fakePhotoFormData(fileName = "notes.txt", contentType = "text/plain", bytes = byteArrayOf(1, 2, 3))
        )

        val body = Json.decodeFromString<PhotoExtractionResponse>(response.bodyAsText())
        assertEquals("Choose a photo, PDF, or Word document to upload.", body.error)
        assertNull(geminiClient.lastImageBytesSize)
        assertNull(geminiClient.lastDocumentText)
    }

    @Test
    fun testNoFileSelectedReturnsAnErrorInsteadOfCallingGemini() = testApplication {
        val geminiClient = FakeGeminiClient()
        testModule(geminiClient = geminiClient)
        val client = signInFakeUser()

        val response = client.submitFormWithBinaryData("/inbox/import-photo/extract", emptyList())
        val body = Json.decodeFromString<PhotoExtractionResponse>(response.bodyAsText())
        assertEquals("Choose a photo, PDF, or Word document to upload.", body.error)
        assertTrue(body.events.isEmpty())
        assertNull(geminiClient.lastImageBytesSize)
    }

    @Test
    fun testExtractionFailureReturnsAFriendlyErrorInsteadOfCrashing() = testApplication {
        val geminiClient = object : GeminiClient {
            override suspend fun extract(subject: String, from: String, bodyText: String): EmailExtraction =
                error("not used")

            override suspend fun extractCalendarEventsFromImage(imageBytes: ByteArray, mimeType: String): List<ExtractedCalendarEvent> =
                error("Gemini vision call failed")

            override suspend fun extractCalendarEventsFromText(documentText: String): List<ExtractedCalendarEvent> =
                error("not used")
        }
        testModule(geminiClient = geminiClient)
        val client = signInFakeUser()

        val response = client.submitFormWithBinaryData("/inbox/import-photo/extract", fakePhotoFormData())
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<PhotoExtractionResponse>(response.bodyAsText())
        assertEquals("Couldn't read that file - try again.", body.error)
    }

    @Test
    fun testNoEventsFoundReturnsAnEmptyResultMessage() = testApplication {
        val geminiClient = FakeGeminiClient(photoEvents = emptyList())
        testModule(geminiClient = geminiClient)
        val client = signInFakeUser()

        val response = client.submitFormWithBinaryData("/inbox/import-photo/extract", fakePhotoFormData())
        val body = Json.decodeFromString<PhotoExtractionResponse>(response.bodyAsText())
        assertEquals("No events found in that file.", body.error)
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
        assertTrue(inboxBody.contains("From a file you uploaded"))
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
