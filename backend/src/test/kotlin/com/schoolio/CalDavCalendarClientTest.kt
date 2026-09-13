package com.schoolio

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.Base64
import kotlin.test.*

// Exercises CalDavCalendarClient's real request/response handling against a
// MockEngine standing in for Google's CalDAV endpoint - same "test the real
// HTTP client separately from any route" split as GeminiClientTest (nothing
// in InboxRoutes.kt calls this yet - see CalendarClient.kt's doc comment).
class CalDavCalendarClientTest {
    private fun mockClient(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine) { engine { addHandler(handler) } }

    // A fabricated multistatus response shaped like RFC 4791's calendar-query
    // examples - not verified against a real Google CalDAV response (see
    // CalDavCalendarClient's doc comment).
    private val multistatusResponse = """
        <?xml version="1.0" encoding="utf-8" ?>
        <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
          <D:response>
            <D:href>/caldav/v2/test@example.com/events/abc123.ics</D:href>
            <D:propstat>
              <D:prop>
                <C:calendar-data>BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        UID:abc123
        SUMMARY:Science Fair
        DESCRIPTION:Bring your project by 8am
        DTSTART:20260920T130000Z
        DTEND:20260920T150000Z
        END:VEVENT
        END:VCALENDAR
        </C:calendar-data>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    @Test
    fun testFetchEventsSendsCalDavReportRequestWithBasicAuthAndTimeRangeFilter() = runBlocking {
        var sawMethod: HttpMethod? = null
        var sawAuth: String? = null
        var sawUrl: String? = null
        var sawBody: String? = null
        val httpClient = mockClient { request ->
            sawMethod = request.method
            sawAuth = request.headers[HttpHeaders.Authorization]
            sawUrl = request.url.toString()
            sawBody = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.toString(Charsets.UTF_8)
            respond(multistatusResponse, HttpStatusCode.MultiStatus)
        }

        CalDavCalendarClient(httpClient, baseUrl = "https://caldav.example/v2")
            .fetchEvents("test@example.com", "fake-app-password", Instant.parse("2026-09-01T00:00:00Z"))

        assertEquals(HttpMethod("REPORT"), sawMethod)
        assertEquals("https://caldav.example/v2/test@example.com/events", sawUrl)
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("test@example.com:fake-app-password".toByteArray()),
            sawAuth
        )
        assertTrue(sawBody?.contains("time-range start=\"20260901T000000Z\"") == true)
    }

    @Test
    fun testFetchEventsParsesVEventFieldsFromCalendarData() = runBlocking {
        val httpClient = mockClient { respond(multistatusResponse, HttpStatusCode.MultiStatus) }

        val events = CalDavCalendarClient(httpClient)
            .fetchEvents("test@example.com", "fake-app-password", Instant.parse("2026-09-01T00:00:00Z"))

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals("abc123", event.uid)
        assertEquals("Science Fair", event.summary)
        assertEquals("Bring your project by 8am", event.description)
        assertEquals(Instant.parse("2026-09-20T13:00:00Z"), event.start)
        assertEquals(Instant.parse("2026-09-20T15:00:00Z"), event.end)
    }

    @Test
    fun testFetchEventsReturnsEmptyListWhenResponseHasNoCalendarData() = runBlocking {
        val httpClient = mockClient {
            respond(
                """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"></D:multistatus>""",
                HttpStatusCode.MultiStatus
            )
        }

        val events = CalDavCalendarClient(httpClient)
            .fetchEvents("test@example.com", "fake-app-password", Instant.parse("2026-09-01T00:00:00Z"))

        assertEquals(emptyList(), events)
    }
}
