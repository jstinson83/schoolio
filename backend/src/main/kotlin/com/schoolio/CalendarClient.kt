package com.schoolio

import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

// What callers need from a pulled calendar event - same "trimmed to what we
// use" shape as GmailMessage in GmailClient.kt. allDay distinguishes a
// dateless event (Google Calendar API's "date" field, e.g. "No School -
// Teacher PD Day") from a timed one (its "dateTime" field) - InboxRoutes.kt's
// pullAndStoreCalendarEvents needs this to decide whether ActionItem.date
// gets a time component, rather than fabricating a fake midnight time for an
// event that never had one. end is nullable since a bare event technically
// only requires a start.
data class CalendarEvent(val uid: String, val summary: String, val description: String?, val start: Instant, val allDay: Boolean, val end: Instant?)

interface CalendarClient {
    // calendarId is the Google Calendar id to read - a personal account's
    // primary calendar id is its own email address. No per-user credential
    // here (unlike GmailClient's email/appPassword) - see
    // GoogleCalendarApiClient's doc comment on why this is a single shared
    // service-account credential instead. [from, until] is a bounded window,
    // not an open-ended "since" the way GmailClient's lookback is - unlike
    // email (catching up on the past), what's useful for calendar is what's
    // coming up, and an unbounded upper bound risks the API trying to expand
    // recurring events indefinitely. The caller (InboxRoutes.kt) always
    // passes [now, now + lookahead], recomputed fresh on every pull - see its
    // own doc comment on why no watermark is needed here the way there is
    // for email.
    suspend fun fetchEvents(calendarId: String, from: Instant, until: Instant): List<CalendarEvent>
}

// Real Google Calendar API v3 access via a single shared service account,
// not per-user OAuth or an app password. This replaces an earlier attempt at
// CalDAV + a per-user Google app password (the same mechanism that works for
// Gmail IMAP) - verified against a live account to fail with a flat 401,
// Google's CalDAV endpoint doesn't accept Basic Auth/app passwords the way
// IMAP does. A service account sidesteps the OAuth-consent-screen questions
// entirely (no "sensitive scope" verification, no Testing-status refresh
// token churn - those rules are about consumer "Sign in with Google" flows,
// which this isn't): each household member individually shares their
// personal calendar with the service account's own email address (Google
// Calendar's normal per-person sharing, no Workspace/domain requirement),
// and this one credential can then read any calendar that's been shared with
// it. credentials must already be scoped to
// https://www.googleapis.com/auth/calendar.readonly (see Application.kt's
// wiring) - this class only mints/refreshes the access token, the actual API
// call is a plain REST request over the existing Ktor HttpClient, same "no
// heavy SDK for a third-party API" convention as GeminiClient/GmailClient.
// A calendarId that hasn't shared its calendar with the service account
// yet - the expected state before that one-time setup step - comes back as a
// 403/404 from Google, which fetchEvents surfaces as a thrown exception (see
// below) rather than silently returning no events, same reasoning as the
// CalDAV attempt's own status-check fix.
class GoogleCalendarApiClient(
    private val httpClient: HttpClient,
    private val credentials: GoogleCredentials
) : CalendarClient {
    override suspend fun fetchEvents(calendarId: String, from: Instant, until: Instant): List<CalendarEvent> =
        withContext(Dispatchers.IO) {
            credentials.refreshIfExpired()
            val accessToken = credentials.accessToken.tokenValue
            val response = httpClient.get("https://www.googleapis.com/calendar/v3/calendars/${calendarId.encodeURLParameter()}/events") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                parameter("timeMin", from.toString())
                parameter("timeMax", until.toString())
                // Expands recurring events into individual occurrences within
                // the window (each with its own start/end) rather than
                // returning one record per recurring series - what a
                // household actually wants to see is "which specific
                // Tuesdays this fits in the lookahead window," not one entry
                // for an indefinitely-repeating series.
                parameter("singleEvents", "true")
                parameter("orderBy", "startTime")
            }
            val body = response.bodyAsText()
            // Same "don't silently parse a rejection as an empty result"
            // fix already made once for the CalDAV attempt - a calendar not
            // yet shared with the service account 403s/404s here, and that
            // needs to be visible (see scheduleSync's catch block in
            // InboxRoutes.kt), not indistinguishable from "no upcoming
            // events."
            if (!response.status.isSuccess()) {
                error("Calendar API request for $calendarId failed: ${response.status} - ${body.take(500)}")
            }
            parseEvents(body)
        }

    private fun parseEvents(json: String): List<CalendarEvent> =
        jsonParser.decodeFromString<EventsListResponse>(json).items.mapNotNull { it.toCalendarEvent() }

    private companion object {
        val jsonParser = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class EventsListResponse(val items: List<CalendarApiEvent> = emptyList())

@Serializable
private data class CalendarApiEvent(
    val id: String,
    val summary: String? = null,
    val description: String? = null,
    val status: String? = null,
    val start: EventDateTime? = null,
    val end: EventDateTime? = null
) {
    // A cancelled instance of a recurring event still shows up in the
    // singleEvents=true expansion (see GoogleCalendarApiClient.fetchEvents) -
    // it needs its own entry to invalidate a previously-pulled occurrence of
    // that series, but for now (no update/cancel handling yet - see
    // context.md's reconciliation note) it's simplest to just never turn one
    // into an ActionItem in the first place.
    fun toCalendarEvent(): CalendarEvent? {
        if (status == "cancelled") return null
        val startDateTime = start ?: return null
        val instant = startDateTime.toInstant() ?: return null
        return CalendarEvent(
            uid = id,
            summary = summary ?: "(no title)",
            description = description?.let { stripDisclaimerFooter(it) }?.ifBlank { null },
            start = instant,
            allDay = startDateTime.dateTime == null,
            end = end?.toInstant()
        )
    }
}

// A school calendar invite is often created by forwarding/pasting an email,
// which drags that org's email confidentiality disclaimer along into the
// event description - not useful for a household reading their kids'
// schedule, and long/ugly enough to be worth stripping rather than just
// displaying it raw (see CLAUDE.md's gotcha entry - hit for real with a
// bilingual English/French disclaimer). Cuts the description at the first
// recognized marker phrase, keeping any genuine content that came before it
// (there usually isn't any - the disclaimer is normally the entire
// description). Not a general HTML/boilerplate stripper - just these two
// specific phrasings until a different district's wording shows up.
private val disclaimerMarkers = listOf(
    "this e-mail message",
    "this email message",
    "le présent message électronique"
)

private fun stripDisclaimerFooter(description: String): String {
    val lower = description.lowercase()
    val cutIndex = disclaimerMarkers.mapNotNull { marker -> lower.indexOf(marker).takeIf { it >= 0 } }.minOrNull()
    return if (cutIndex != null) description.substring(0, cutIndex).trim() else description.trim()
}

// Google Calendar API's EventDateTime shape: a timed event sets dateTime (an
// RFC3339 timestamp with an explicit offset, e.g. "2026-09-20T13:00:00-04:00"
// - never a bare "Z"-only Instant, so this parses via OffsetDateTime, not
// Instant.parse), an all-day event sets date instead (a bare "yyyy-MM-dd"
// with no time or zone at all).
@Serializable
private data class EventDateTime(val dateTime: String? = null, val date: String? = null) {
    fun toInstant(): Instant? = when {
        dateTime != null -> runCatching { OffsetDateTime.parse(dateTime).toInstant() }.getOrNull()
        date != null -> runCatching { LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
        else -> null
    }
}
