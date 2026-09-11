package com.schoolio

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

    @Test
    fun testListRecentMessagesRefreshesTokenAndFetchesHeaders() = runBlocking {
        var sawRefreshRequest = false
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
                    """{"id":"msg-1","payload":{"headers":[
                        {"name":"Subject","value":"Field trip permission slip"},
                        {"name":"From","value":"teacher@school.example"},
                        {"name":"Date","value":"Mon, 1 Sep 2026 10:00:00 -0400"}
                    ]}}""",
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

        val messages = RestGmailClient(httpClient).listRecentMessages("fake-refresh-token")

        assertTrue(sawRefreshRequest)
        assertEquals(1, messages.size)
        assertEquals("Field trip permission slip", messages[0].subject)
        assertEquals("teacher@school.example", messages[0].from)
    }

    @Test
    fun testListRecentMessagesReturnsEmptyWhenInboxHasNoMessages() = runBlocking {
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

        val messages = RestGmailClient(httpClient).listRecentMessages("fake-refresh-token")

        assertEquals(emptyList(), messages)
    }
}
