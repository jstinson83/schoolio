package com.schoolio

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.*

// Exercises RestGmailClient's actual request/response handling (unlike
// InboxTest, which only ever goes through FakeGmailClient) - a MockEngine
// standing in for both the token-refresh endpoint and the Gmail API itself.
class GmailClientTest {
    private fun mockClient(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine { addHandler(handler) }
        }

    private fun base64Url(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    @Test
    fun testSearchMessagesScopesQueryToSendersAndFetchesFullBody() = runBlocking {
        var sawRefreshRequest = false
        var searchQuery: String? = null
        val bodyText = "Please sign and return by Friday."
        val httpClient = mockClient { request ->
            val url = request.url.toString()
            when {
                url.startsWith("https://oauth2.googleapis.com/token") -> {
                    sawRefreshRequest = true
                    respond(
                        """{"access_token":"fake-access-token"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                url.startsWith("https://gmail.googleapis.com/gmail/v1/users/me/messages/") -> respond(
                    """{"id":"msg-1","snippet":"short preview","payload":{
                        "mimeType":"multipart/alternative",
                        "headers":[
                            {"name":"Subject","value":"Field trip permission slip"},
                            {"name":"From","value":"teacher@school.example"},
                            {"name":"Date","value":"Mon, 1 Sep 2026 10:00:00 -0400"}
                        ],
                        "parts":[
                            {"mimeType":"text/plain","body":{"data":"${base64Url(bodyText)}"}},
                            {"mimeType":"text/html","body":{"data":"${base64Url("<p>$bodyText</p>")}"}}
                        ]
                    }}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                url.startsWith("https://gmail.googleapis.com/gmail/v1/users/me/messages") -> {
                    searchQuery = request.url.parameters["q"]
                    respond(
                        """{"messages":[{"id":"msg-1"}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                else -> error("Unexpected request to $url")
            }
        }

        val messages = RestGmailClient(httpClient).searchMessages("fake-refresh-token", listOf("teacher@school.example"), sinceWeeks = 2)

        assertTrue(sawRefreshRequest)
        assertNotNull(searchQuery)
        assertTrue(searchQuery!!.contains("after:"))
        assertTrue(searchQuery!!.contains("from:teacher@school.example"))
        assertEquals(1, messages.size)
        assertEquals("Field trip permission slip", messages[0].subject)
        assertEquals("teacher@school.example", messages[0].from)
        assertEquals(bodyText, messages[0].bodyText)
    }

    @Test
    fun testSearchMessagesFallsBackToSnippetWhenNoPlainTextPart() = runBlocking {
        val httpClient = mockClient { request ->
            val url = request.url.toString()
            when {
                url.startsWith("https://oauth2.googleapis.com/token") -> respond(
                    """{"access_token":"fake-access-token"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                url.startsWith("https://gmail.googleapis.com/gmail/v1/users/me/messages/") -> respond(
                    """{"id":"msg-1","snippet":"short preview only","payload":{
                        "mimeType":"text/html",
                        "headers":[{"name":"Subject","value":"No plain text"}],
                        "body":{"data":"${base64Url("<p>html only</p>")}"}
                    }}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                url.startsWith("https://gmail.googleapis.com/gmail/v1/users/me/messages") -> respond(
                    """{"messages":[{"id":"msg-1"}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                else -> error("Unexpected request to $url")
            }
        }

        val messages = RestGmailClient(httpClient).searchMessages("fake-refresh-token", listOf("teacher@school.example"), sinceWeeks = 4)

        assertEquals("short preview only", messages[0].bodyText)
    }

    @Test
    fun testSearchMessagesReturnsEmptyWhenNothingMatches() = runBlocking {
        val httpClient = mockClient { request ->
            val url = request.url.toString()
            when {
                url.startsWith("https://oauth2.googleapis.com/token") -> respond(
                    """{"access_token":"fake-access-token"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                url.startsWith("https://gmail.googleapis.com/gmail/v1/users/me/messages") -> respond(
                    """{}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                else -> error("Unexpected request to $url")
            }
        }

        val messages = RestGmailClient(httpClient).searchMessages("fake-refresh-token", listOf("teacher@school.example"), sinceWeeks = 4)

        assertEquals(emptyList(), messages)
    }
}
