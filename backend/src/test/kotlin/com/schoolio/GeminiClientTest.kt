package com.schoolio

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

// Exercises RestGeminiClient's actual request/response handling against a
// MockEngine standing in for Gemini's generateContent endpoint - the same
// "test the real REST client separately from the route" split as
// GmailClientTest vs InboxTest (which only ever goes through FakeGeminiClient).
class GeminiClientTest {
    private fun mockClient(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine { addHandler(handler) }
        }

    @Test
    fun testExtractParsesSummaryAndActionItemsFromGeminiResponse() = runBlocking {
        var sawApiKey: String? = null
        val httpClient = mockClient { request ->
            sawApiKey = request.url.parameters["key"]
            respond(
                """{
                    "candidates": [{
                        "content": {
                            "parts": [{
                                "text": "{\"summary\":\"Permission slip needs a signature.\",\"actionItems\":[{\"title\":\"Sign permission slip\",\"description\":\"Sign and return the form\",\"dueDate\":\"2026-09-19\",\"dueTime\":\"09:00\"}]}"
                            }]
                        }
                    }]
                }""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val extraction = RestGeminiClient(httpClient, apiKey = "fake-api-key")
            .extract("Field trip permission slip", "teacher@school.example", "Please sign and return by Friday at 9am.")

        assertEquals("fake-api-key", sawApiKey)
        assertEquals("Permission slip needs a signature.", extraction.summary)
        assertEquals(1, extraction.actionItems.size)
        assertEquals("Sign permission slip", extraction.actionItems[0].title)
        assertEquals("Sign and return the form", extraction.actionItems[0].description)
        assertEquals("2026-09-19", extraction.actionItems[0].dueDate)
        assertEquals("09:00", extraction.actionItems[0].dueTime)
    }

    @Test
    fun testExtractReturnsEmptyActionItemsWhenEmailHasNothingActionable() = runBlocking {
        val httpClient = mockClient {
            respond(
                """{
                    "candidates": [{
                        "content": {
                            "parts": [{"text": "{\"summary\":\"Monthly newsletter, nothing to act on.\",\"actionItems\":[]}"}]
                        }
                    }]
                }""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val extraction = RestGeminiClient(httpClient, apiKey = "fake-api-key")
            .extract("PTA Newsletter", "pta@school.example", "Here's what's happening this month...")

        assertEquals("Monthly newsletter, nothing to act on.", extraction.summary)
        assertEquals(emptyList(), extraction.actionItems)
    }

    @Test
    fun testExtractStripsMarkdownCodeFenceAroundJsonResponse() = runBlocking {
        // Gemini sometimes wraps its response in a ```json fence even with
        // responseMimeType=application/json set - same quirk foodie's
        // RecipeParser.kt works around (see GeminiClient.kt's stripJsonFence).
        val httpClient = mockClient {
            respond(
                """{
                    "candidates": [{
                        "content": {
                            "parts": [{"text": "```json\n{\"summary\":\"Fenced response.\",\"actionItems\":[]}\n```"}]
                        }
                    }]
                }""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val extraction = RestGeminiClient(httpClient, apiKey = "fake-api-key")
            .extract("PTA Newsletter", "pta@school.example", "Here's what's happening this month...")

        assertEquals("Fenced response.", extraction.summary)
    }

    @Test
    fun testExtractCalendarEventsFromImageSendsInlineDataAndParsesEvents() = runBlocking {
        var sawInlineDataMimeType: String? = null
        var sawInlineDataBase64: String? = null
        val httpClient = mockClient { request ->
            val bodyText = String((request.body as OutgoingContent.ByteArrayContent).bytes())
            val requestJson = Json.parseToJsonElement(bodyText).jsonObject
            val parts = requestJson["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
            val inlineDataPart = parts.first { it.jsonObject.containsKey("inlineData") }.jsonObject["inlineData"]!!.jsonObject
            sawInlineDataMimeType = inlineDataPart["mimeType"]?.jsonPrimitive?.content
            sawInlineDataBase64 = inlineDataPart["data"]?.jsonPrimitive?.content
            respond(
                """{
                    "candidates": [{
                        "content": {
                            "parts": [{
                                "text": "{\"events\":[{\"title\":\"Picture day\",\"date\":\"2026-09-25\"},{\"title\":\"Early dismissal\",\"date\":\"2026-09-30\",\"time\":\"13:00\",\"description\":\"1pm release\"}]}"
                            }]
                        }
                    }]
                }""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val events = RestGeminiClient(httpClient, apiKey = "fake-api-key")
            .extractCalendarEventsFromImage(byteArrayOf(1, 2, 3, 4), "image/jpeg")

        assertEquals("image/jpeg", sawInlineDataMimeType)
        assertEquals(java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)), sawInlineDataBase64)
        assertEquals(2, events.size)
        assertEquals("Picture day", events[0].title)
        assertEquals("2026-09-25", events[0].date)
        assertNull(events[0].time)
        assertEquals("Early dismissal", events[1].title)
        assertEquals("13:00", events[1].time)
        assertEquals("1pm release", events[1].description)
    }

    @Test
    fun testExtractCalendarEventsFromImageReturnsEmptyListWhenPhotoHasNoEvents() = runBlocking {
        val httpClient = mockClient {
            respond(
                """{
                    "candidates": [{
                        "content": {
                            "parts": [{"text": "{\"events\":[]}"}]
                        }
                    }]
                }""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val events = RestGeminiClient(httpClient, apiKey = "fake-api-key")
            .extractCalendarEventsFromImage(byteArrayOf(1, 2, 3), "image/png")

        assertEquals(emptyList(), events)
    }

    @Test
    fun testExtractCalendarEventsFromImageAcceptsAPdfMimeType() = runBlocking {
        // Gemini reads a PDF's pages via the same inlineData mechanism as an
        // image - extractCalendarEventsFromImage just forwards whatever
        // mimeType the upload actually was, so this proves that path isn't
        // hardcoded to image/* somewhere.
        var sawInlineDataMimeType: String? = null
        val httpClient = mockClient { request ->
            val bodyText = String((request.body as OutgoingContent.ByteArrayContent).bytes())
            val requestJson = Json.parseToJsonElement(bodyText).jsonObject
            val parts = requestJson["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
            val inlineDataPart = parts.first { it.jsonObject.containsKey("inlineData") }.jsonObject["inlineData"]!!.jsonObject
            sawInlineDataMimeType = inlineDataPart["mimeType"]?.jsonPrimitive?.content
            respond(
                """{"candidates": [{"content": {"parts": [{"text": "{\"events\":[{\"title\":\"Winter concert\",\"date\":\"2026-12-10\"}]}"}]}}]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val events = RestGeminiClient(httpClient, apiKey = "fake-api-key")
            .extractCalendarEventsFromImage(byteArrayOf(1, 2, 3), "application/pdf")

        assertEquals("application/pdf", sawInlineDataMimeType)
        assertEquals(1, events.size)
        assertEquals("Winter concert", events[0].title)
    }

    @Test
    fun testExtractCalendarEventsFromTextSendsDocumentTextAsAPlainTextPartAndParsesEvents() = runBlocking {
        var sawRequestText: String? = null
        val httpClient = mockClient { request ->
            val bodyText = String((request.body as OutgoingContent.ByteArrayContent).bytes())
            val requestJson = Json.parseToJsonElement(bodyText).jsonObject
            val parts = requestJson["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
            // Unlike the image/PDF path, there's no inlineData part at all here -
            // the extracted document text rides along inside the single text part.
            assertEquals(1, parts.size)
            sawRequestText = parts[0].jsonObject["text"]?.jsonPrimitive?.content
            respond(
                """{"candidates": [{"content": {"parts": [{"text": "{\"events\":[{\"title\":\"Spirit week\",\"date\":\"2026-10-13\"}]}"}]}}]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val events = RestGeminiClient(httpClient, apiKey = "fake-api-key")
            .extractCalendarEventsFromText("Oct 13-17: Spirit week\nOct 20: Picture retakes")

        assertTrue(sawRequestText!!.contains("Oct 13-17: Spirit week"))
        assertTrue(sawRequestText!!.contains("Oct 20: Picture retakes"))
        assertEquals(1, events.size)
        assertEquals("Spirit week", events[0].title)
        assertEquals("2026-10-13", events[0].date)
    }
}
