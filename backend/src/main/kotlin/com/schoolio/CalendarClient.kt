package com.schoolio

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

// What callers will eventually need from a pulled calendar event - same
// "trimmed to what we use" shape as GmailMessage in GmailClient.kt. end is
// nullable since a bare VEVENT technically only requires DTSTART.
data class CalendarEvent(val uid: String, val summary: String, val description: String?, val start: Instant, val end: Instant?)

interface CalendarClient {
    // email/appPassword are per-user (User.email/User.calendarAppPassword,
    // see UserStore.kt's doc comment on why it's a separate field from
    // gmailAppPassword) - same "caller supplies credentials, this interface
    // doesn't touch UserRepository" split as GmailClient. [from, until] is a
    // bounded window, not an open-ended "since" the way GmailClient's lookback
    // is - unlike email (where what matters is catching up on everything back
    // to some point), what's useful here is what's coming up, and an unbounded
    // upper bound risks the server trying to return recurring-event instances
    // indefinitely. The caller (InboxRoutes.kt) currently always passes
    // [now, now + lookahead], recomputed fresh on every pull - see its own
    // doc comment on why no watermark is needed here the way there is for
    // email.
    suspend fun fetchEvents(email: String, appPassword: String, from: Instant, until: Instant): List<CalendarEvent>
}

// The CalDAV request shape below (REPORT/calendar-query, Basic Auth with a
// Google app password, same app-password mechanism IMAP already uses - see
// context.md's Gmail integration notes for why that sidesteps OAuth
// entirely) is real, but the multistatus/iCalendar response parsing has only
// been checked against the fabricated response in CalDavCalendarClientTest,
// not a live Google account. Known gaps, same "documented, not silently
// wrong" spirit as this codebase's other known-incomplete corners (see
// CLAUDE.md): recurring events (RRULE) aren't expanded into individual
// occurrences, all-day events (DTSTART;VALUE=DATE:yyyyMMdd, no time
// component) aren't parsed, and a TZID-qualified local DTSTART/DTEND (rather
// than a bare UTC ...Z timestamp) isn't parsed either - all three should be
// verified against a real response before this is trusted for anything
// beyond the plain-UTC-timed-event case.
class CalDavCalendarClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://apidata.googleusercontent.com/caldav/v2"
) : CalendarClient {
    override suspend fun fetchEvents(email: String, appPassword: String, from: Instant, until: Instant): List<CalendarEvent> =
        withContext(Dispatchers.IO) {
            val response = httpClient.request("$baseUrl/$email/events") {
                method = HttpMethod("REPORT")
                header(HttpHeaders.Authorization, basicAuthHeader(email, appPassword))
                header("Depth", "1")
                contentType(ContentType.Application.Xml)
                setBody(calendarQueryBody(from, until))
            }
            val body = response.bodyAsText()
            // A non-2xx response (401 - basic auth/app password rejected, 404
            // - wrong calendar id/URL, etc.) still has a body, and
            // parseEvents would just find no <calendar-data> tags in it and
            // silently return an empty list - indistinguishable from "no
            // upcoming events" at every call site above this. Fail loudly
            // instead so a real problem shows up in scheduleSync's caught
            // exception (see InboxRoutes.kt) rather than looking like an
            // empty calendar.
            if (!response.status.isSuccess()) {
                error("CalDAV request to $baseUrl failed: ${response.status} - ${body.take(500)}")
            }
            parseEvents(body)
        }

    private fun basicAuthHeader(email: String, appPassword: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$email:$appPassword".toByteArray(Charsets.UTF_8))

    // RFC 4791 calendar-query REPORT, filtered to VEVENTs in [from, until] -
    // server-side filtering, same "never pull everything and filter locally"
    // principle GmailClient's IMAP SEARCH already follows.
    private fun calendarQueryBody(from: Instant, until: Instant): String {
        val start = icsUtcFormatter.format(from)
        val end = icsUtcFormatter.format(until)
        return """
            <?xml version="1.0" encoding="utf-8" ?>
            <C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <D:prop>
                <C:calendar-data/>
              </D:prop>
              <C:filter>
                <C:comp-filter name="VCALENDAR">
                  <C:comp-filter name="VEVENT">
                    <C:time-range start="$start" end="$end"/>
                  </C:comp-filter>
                </C:comp-filter>
              </C:filter>
            </C:calendar-query>
        """.trimIndent()
    }

    // Crude regex-based extraction rather than a full WebDAV XML parser (no
    // such dependency in this project yet) - same "hand-rolled, not a full
    // parser library" call as GmailClient.kt's HTML tag-strip fallback. Pulls
    // each <calendar-data> block's raw iCalendar text out of the multistatus
    // response, then parses each one as a single VEVENT directly.
    private fun parseEvents(xml: String): List<CalendarEvent> =
        calendarDataTagRegex.findAll(xml)
            .mapNotNull { parseVEvent(unescapeXml(it.groupValues[1])) }
            .toList()

    private fun unescapeXml(raw: String): String = raw
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun parseVEvent(ics: String): CalendarEvent? {
        val lines = ics.lines().map { it.trim() }
        fun field(name: String): String? = lines
            .firstOrNull { it.startsWith("$name:") || it.startsWith("$name;") }
            ?.substringAfter(":")
        val uid = field("UID") ?: return null
        val start = field("DTSTART")?.let { parseIcsUtcDateTime(it) } ?: return null
        return CalendarEvent(
            uid = uid,
            summary = field("SUMMARY") ?: "(no title)",
            description = field("DESCRIPTION"),
            start = start,
            end = field("DTEND")?.let { parseIcsUtcDateTime(it) }
        )
    }

    // Only handles the plain UTC "yyyyMMdd'T'HHmmss'Z'" form - see this
    // class's doc comment for the date shapes this doesn't handle yet.
    private fun parseIcsUtcDateTime(raw: String): Instant? =
        runCatching { LocalDateTime.parse(raw, icsUtcFormatter).toInstant(ZoneOffset.UTC) }.getOrNull()

    private companion object {
        val icsUtcFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        val calendarDataTagRegex = Regex("<(?:[A-Za-z0-9]+:)?calendar-data[^>]*>([\\s\\S]*?)</(?:[A-Za-z0-9]+:)?calendar-data>")
    }
}
