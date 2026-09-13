package com.schoolio

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.*

// Exercises GoogleCalendarApiClient's real request/response handling against
// a MockEngine standing in for the Calendar API - same "test the real HTTP
// client separately from the route that calls it" split as GeminiClientTest
// vs InboxTest (which only ever goes through FakeCalendarClient).
// GoogleCredentials.create(AccessToken) wraps a fixed token (no real network
// call, no expiry) - the standard way to satisfy a GoogleCredentials
// dependency in a test without touching real service-account auth, which
// this class deliberately doesn't own itself (see its doc comment - it only
// mints/refreshes a token the caller already scoped, doesn't load a key).
class GoogleCalendarApiClientTest {
    private fun mockClient(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine) { engine { addHandler(handler) } }

    private fun fakeCredentials(token: String = "fake-access-token"): GoogleCredentials =
        GoogleCredentials.create(AccessToken.newBuilder().setTokenValue(token).build())

    private val eventsListResponse = """
        {
          "items": [
            {
              "id": "event-1",
              "summary": "Science Fair",
              "description": "Bring your project by 8am",
              "start": {"dateTime": "2026-09-20T09:00:00-04:00"},
              "end": {"dateTime": "2026-09-20T11:00:00-04:00"}
            },
            {
              "id": "event-2",
              "summary": "Teacher PD Day - No School",
              "start": {"date": "2026-09-25"},
              "end": {"date": "2026-09-26"}
            },
            {
              "id": "event-3",
              "summary": "Cancelled Assembly",
              "status": "cancelled",
              "start": {"dateTime": "2026-09-22T09:00:00-04:00"}
            }
          ]
        }
    """.trimIndent()

    @Test
    fun testFetchEventsSendsBearerTokenAndTimeRangeQueryParams() = runBlocking {
        var sawAuth: String? = null
        var sawUrl: Url? = null
        val httpClient = mockClient { request ->
            sawAuth = request.headers[HttpHeaders.Authorization]
            sawUrl = request.url
            respond("""{"items":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }

        GoogleCalendarApiClient(httpClient, fakeCredentials("fake-access-token"))
            .fetchEvents("test@example.com", Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-08T00:00:00Z"))

        assertEquals("Bearer fake-access-token", sawAuth)
        assertEquals("https", sawUrl?.protocol?.name)
        // calendarId is percent-encoded (encodeURLParameter) before going
        // into the path - correct and safe even though a bare email address
        // technically wouldn't need it (see RFC 3986, '@' is a valid pchar).
        assertEquals("/calendar/v3/calendars/test%40example.com/events", sawUrl?.encodedPath)
        assertEquals("2026-09-01T00:00:00Z", sawUrl?.parameters?.get("timeMin"))
        assertEquals("2026-09-08T00:00:00Z", sawUrl?.parameters?.get("timeMax"))
        assertEquals("true", sawUrl?.parameters?.get("singleEvents"))
    }

    @Test
    fun testFetchEventsParsesTimedAllDayAndSkipsCancelledEvents() = runBlocking {
        val httpClient = mockClient {
            respond(eventsListResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }

        val events = GoogleCalendarApiClient(httpClient, fakeCredentials())
            .fetchEvents("test@example.com", Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"))

        assertEquals(2, events.size, "The cancelled event should be excluded")

        val timed = events.single { it.uid == "event-1" }
        assertEquals("Science Fair", timed.summary)
        assertEquals("Bring your project by 8am", timed.description)
        assertFalse(timed.allDay)
        assertEquals(Instant.parse("2026-09-20T13:00:00Z"), timed.start)
        assertEquals(Instant.parse("2026-09-20T15:00:00Z"), timed.end)

        val allDay = events.single { it.uid == "event-2" }
        assertEquals("Teacher PD Day - No School", allDay.summary)
        assertTrue(allDay.allDay)
        assertEquals(Instant.parse("2026-09-25T00:00:00Z"), allDay.start)
    }

    @Test
    fun testFetchEventsThrowsOnNonSuccessResponseInsteadOfSilentlyReturningEmpty() = runBlocking {
        val httpClient = mockClient {
            respond("""{"error": {"code": 403, "message": "Forbidden"}}""", HttpStatusCode.Forbidden)
        }

        val exception = assertFailsWith<IllegalStateException> {
            GoogleCalendarApiClient(httpClient, fakeCredentials())
                .fetchEvents("test@example.com", Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-08T00:00:00Z"))
        }
        assertTrue(exception.message?.contains("403") == true)
    }
}
