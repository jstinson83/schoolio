package com.schoolio

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
}
