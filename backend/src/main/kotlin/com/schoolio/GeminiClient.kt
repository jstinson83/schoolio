package com.schoolio

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.Base64

// Gemini's raw extraction output for one action item - an input to building
// the persisted ActionItem (see ActionItemStore.kt), not that type itself:
// dueDate/dueTime stay separate here (rather than one combined date field)
// because a school email frequently states only a date ("permission slips due
// Friday") with no time - forcing a time would mean inventing one. ISO-8601
// (YYYY-MM-DD / HH:MM 24h) - enforced via the prompt/schema below, not parsed/
// validated here, since Gemini is the one doing the extraction. InboxRoutes
// combines title/description/dueDate/dueTime into ActionItem.date at
// persistence time.
data class ExtractedActionItem(val title: String, val description: String, val dueDate: String? = null, val dueTime: String? = null)

data class EmailExtraction(val summary: String, val actionItems: List<ExtractedActionItem>)

// One email attachment worth handing to Gemini alongside the body text -
// InboxProcessingSweep.kt only ever builds these for image/PDF attachments
// (the ones Gemini can read via inlineData, same restriction
// extractCalendarEventsFromImage already has), fetching the bytes back from
// Cloud Storage via AttachmentRepository since EmailMessage.attachments only
// carries metadata (see MessageStore.kt's StoredAttachment). Plain class, not
// a data class - same "don't want a ByteArray-based equals/hashCode or a
// byte-dumping toString" reasoning as GmailClient.kt's EmailAttachment.
class ExtractionAttachment(val bytes: ByteArray, val mimeType: String)

// Gemini's raw output for one event read off an uploaded calendar (a photo of
// a physical wall/paper calendar, a printed school schedule, a whiteboard, a
// PDF, or a Word document) - the photo-import counterpart of
// ExtractedActionItem above. Unlike an email, there's no "nothing actionable"
// case worth modeling: a calendar entry is itself the thing to track, so date
// is non-optional here (Gemini's prompt asks it to skip anything it can't
// date confidently rather than emit a dateless entry) - InboxRoutes still
// lets a household member edit/reject each one on the review step before
// anything is persisted, since OCR/handwriting reads (and even a plain-text
// transcription) are much more error-prone than either the email or
// Calendar-API extraction paths.
data class ExtractedCalendarEvent(val title: String, val date: String, val time: String? = null, val description: String? = null)

interface GeminiClient {
    // attachments defaults to empty so extract()'s existing text-only call
    // shape still compiles everywhere it's already used (tests included) -
    // only InboxProcessingSweep.kt's real pull path ever passes anything
    // here.
    suspend fun extract(subject: String, from: String, bodyText: String, attachments: List<ExtractionAttachment> = emptyList()): EmailExtraction
    suspend fun extractCalendarEventsFromImage(imageBytes: ByteArray, mimeType: String): List<ExtractedCalendarEvent>
    suspend fun extractCalendarEventsFromText(documentText: String): List<ExtractedCalendarEvent>
}

@Serializable
private data class GenerateContentRequest(val contents: List<GeminiContent>, val generationConfig: GeminiGenerationConfig)

@Serializable
private data class GeminiContent(val parts: List<GeminiPart>)

// text and inlineData are mutually exclusive per the Gemini API's own Part
// union - a text-only request (extract) only ever sets text; the photo path
// (extractCalendarEventsFromImage) sends one text part (the prompt) and one
// inlineData part (the photo) in the same GeminiContent. Field names already
// match the API's own camelCase JSON (inlineData/mimeType), same convention
// as responseMimeType/responseSchema below, so no @SerialName is needed.
@Serializable
private data class GeminiPart(val text: String? = null, val inlineData: GeminiInlineData? = null)

@Serializable
private data class GeminiInlineData(val mimeType: String, val data: String)

@Serializable
private data class GeminiGenerationConfig(val responseMimeType: String = "application/json", val responseSchema: JsonObject)

@Serializable
private data class GenerateContentResponse(val candidates: List<GeminiCandidate> = emptyList())

@Serializable
private data class GeminiCandidate(val content: GeminiContent)

@Serializable
private data class ExtractionPayload(val summary: String, val actionItems: List<ActionItemPayload> = emptyList())

@Serializable
private data class ActionItemPayload(val title: String, val description: String, val dueDate: String? = null, val dueTime: String? = null)

// Gemini's structured-output schema format (OBJECT/STRING/ARRAY type names,
// not JSON Schema's lowercase) - constrains the model to return exactly the
// shape ExtractionPayload expects instead of parsing free-form prose.
private val extractionSchema = buildJsonObject {
    put("type", "OBJECT")
    put("properties", buildJsonObject {
        put("summary", buildJsonObject { put("type", "STRING") })
        put("actionItems", buildJsonObject {
            put("type", "ARRAY")
            put("items", buildJsonObject {
                put("type", "OBJECT")
                put("properties", buildJsonObject {
                    put("title", buildJsonObject { put("type", "STRING") })
                    put("description", buildJsonObject { put("type", "STRING") })
                    put("dueDate", buildJsonObject { put("type", "STRING") })
                    put("dueTime", buildJsonObject { put("type", "STRING") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("description"))))
            })
        })
    })
    put("required", JsonArray(listOf(JsonPrimitive("summary"), JsonPrimitive("actionItems"))))
}

@Serializable
private data class CalendarEventsExtractionPayload(val events: List<CalendarEventPayload> = emptyList())

@Serializable
private data class CalendarEventPayload(val title: String, val date: String, val time: String? = null, val description: String? = null)

// Same OBJECT/STRING/ARRAY schema-format convention as extractionSchema
// above - date is the only required field beyond title (see
// ExtractedCalendarEvent's doc comment on why there's no "nothing
// actionable" case here the way EmailExtraction has one).
private val calendarEventsExtractionSchema = buildJsonObject {
    put("type", "OBJECT")
    put("properties", buildJsonObject {
        put("events", buildJsonObject {
            put("type", "ARRAY")
            put("items", buildJsonObject {
                put("type", "OBJECT")
                put("properties", buildJsonObject {
                    put("title", buildJsonObject { put("type", "STRING") })
                    put("date", buildJsonObject { put("type", "STRING") })
                    put("time", buildJsonObject { put("type", "STRING") })
                    put("description", buildJsonObject { put("type", "STRING") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("date"))))
            })
        })
    })
    put("required", JsonArray(listOf(JsonPrimitive("events"))))
}

// Plain REST calls against Gemini's API, not a provider SDK - same
// no-framework call as RestGmailClient/GoogleAuthFlow.kt.
class RestGeminiClient(
    private val httpClient: HttpClient,
    private val apiKey: String = System.getenv("GEMINI_API_KEY") ?: "",
    // Matches foodie's current model (see foodie's CLAUDE.md "Gemini
    // integration gotchas" - names churn on Google's release schedule, 1.5
    // and 2.0 Flash are both already retired as of mid-2026, so this is
    // liable to need bumping again; check
    // https://ai.google.dev/gemini-api/docs/models for the current GA flash
    // model if this starts 404ing).
    private val model: String = System.getenv("GEMINI_MODEL") ?: "gemini-3.6-flash"
) : GeminiClient {
    override suspend fun extract(subject: String, from: String, bodyText: String, attachments: List<ExtractionAttachment>): EmailExtraction {
        // Same "sometimes the real content is in the attachment, not the
        // body" reasoning that gave photo-import its own extraction path -
        // a school email frequently just says "see attached" with the actual
        // permission slip/date/form as a PDF or image. Only mentioned when
        // there's actually at least one (an inlineData part with nothing to
        // say about it would just be noise for the common no-attachment
        // case).
        val attachmentNote = if (attachments.isNotEmpty()) {
            "\n\nThis email has ${attachments.size} attachment(s) included below as image/PDF data - " +
                "read them too, since the actual form/date/details are sometimes only in the attachment."
        } else ""
        val prompt = """
            You are helping a parent keep track of school-related email. Every email you see here
            already passed through a curated sender filter, so it's already been judged worth the
            parent's attention - the parent relies on your summary instead of opening the original,
            so it needs to actually stand in for the email. Read the email below and:
            1. Write a one-to-two sentence summary that captures the real content: specific names,
               dates, times, locations, amounts, or links it mentions, not just the general topic
               (prefer "Picture day is Oct 3; basic package is $12" over "This is about picture
               day"). Always write a real, specific summary - even a purely informational email
               with no action item (a newsletter, a closure notice, a schedule change, an FYI) still
               needs one. Never leave it blank, and never write a generic placeholder just because
               step 2 below turns up nothing.
            2. List any action items the parent needs to do or decide on: permission slips to sign,
               forms or money to send in, events to attend or RSVP to, deadlines to meet, supplies
               to provide, or schedule changes that affect pickup/drop-off. For each one, give a
               short title (a few words, e.g. "Sign permission slip") and a fuller description of
               what's needed. If the email states a date and/or time for an item, include it
               (dueDate as YYYY-MM-DD, dueTime as 24-hour HH:MM); omit whichever one isn't stated.
               If there's genuinely nothing the parent needs to do, return an empty list - the
               summary from step 1 is what carries the email's content in that case, so it must not
               be empty too.

            From: $from
            Subject: $subject
            Body:
            $bodyText$attachmentNote
        """.trimIndent()

        val parts = listOf(GeminiPart(prompt)) +
            attachments.map { GeminiPart(inlineData = GeminiInlineData(it.mimeType, Base64.getEncoder().encodeToString(it.bytes))) }
        val response = httpClient.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent") {
            parameter("key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                GenerateContentRequest(
                    contents = listOf(GeminiContent(parts)),
                    generationConfig = GeminiGenerationConfig(responseSchema = extractionSchema)
                )
            )
        }.body<GenerateContentResponse>()

        val text = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: return EmailExtraction(summary = "", actionItems = emptyList())
        val payload = Json { ignoreUnknownKeys = true }.decodeFromString<ExtractionPayload>(stripJsonFence(text))
        return EmailExtraction(
            summary = payload.summary,
            actionItems = payload.actionItems.map { ExtractedActionItem(it.title, it.description, it.dueDate, it.dueTime) }
        )
    }

    // Shared between extractCalendarEventsFromImage and
    // extractCalendarEventsFromText below - the two only differ in how the
    // source calendar content reaches Gemini (an inlineData part with raw
    // bytes vs. a second text part), not in what's being asked for or the
    // schema constraining the response. [source] fills in the one sentence
    // describing what's being read (a photo/PDF vs. already-extracted
    // document text) so the wording still makes sense either way. today
    // anchors the year-inference instruction (a calendar showing only
    // day-of-week/day-of-month, e.g. a whiteboard's "Tue 9/15" with no year
    // printed anywhere) - HOUSEHOLD_ZONE (InboxRoutes.kt, same package)
    // rather than the server's local zone or UTC, matching every other "what
    // day is it" decision in this app.
    private fun calendarEventsPrompt(source: String): String {
        val today = LocalDate.now(HOUSEHOLD_ZONE)
        return """
            You are helping a parent transcribe events from $source - this could be a
            physical wall/paper calendar, a printed school schedule, or a whiteboard.
            Extract every event, appointment, or reminder that's legibly written on it.
            For each one, give:
            - title: a short label (a few words).
            - date: the event's date as YYYY-MM-DD. If a month/year header is shown, use
              it. If only day numbers or a day-of-week are visible with no year printed
              anywhere, infer the year using today's date ($today) as your reference
              point - assume the nearest real-world occurrence of that month/day, not
              necessarily the current calendar year.
            - time: the event's time as 24-hour HH:MM, only if a time is actually
              written down for it. Omit this field entirely if no time is shown.
            - description: any other short notes visible for the event. Omit this
              field if there's nothing beyond the title.
            Skip anything illegible or too ambiguous to date confidently rather than
            guessing. If there's no calendar or no dated entries at all, return an
            empty list.
        """.trimIndent()
    }

    private fun parseCalendarEvents(response: GenerateContentResponse): List<ExtractedCalendarEvent> {
        val text = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: return emptyList()
        val payload = Json { ignoreUnknownKeys = true }.decodeFromString<CalendarEventsExtractionPayload>(stripJsonFence(text))
        return payload.events.map { ExtractedCalendarEvent(it.title, it.date, it.time, it.description) }
    }

    // Same generateContent endpoint as extract() above, but the request's
    // parts are (prompt text, inlineData file) instead of (prompt text
    // alone) - Gemini's vision input is just another Part in the same
    // request shape, not a different endpoint. Used for both photos and
    // PDFs (mimeType is whatever the upload actually was) - Gemini reads a
    // PDF's pages the same way it reads an image, so no separate handling is
    // needed here beyond passing the real mimeType through. Word documents
    // don't go through this path at all (Gemini doesn't accept docx as
    // inlineData) - see extractCalendarEventsFromText instead.
    override suspend fun extractCalendarEventsFromImage(imageBytes: ByteArray, mimeType: String): List<ExtractedCalendarEvent> {
        val response = httpClient.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent") {
            parameter("key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                GenerateContentRequest(
                    contents = listOf(
                        GeminiContent(
                            listOf(
                                GeminiPart(text = calendarEventsPrompt("a photo or PDF of a calendar")),
                                GeminiPart(inlineData = GeminiInlineData(mimeType, Base64.getEncoder().encodeToString(imageBytes)))
                            )
                        )
                    ),
                    generationConfig = GeminiGenerationConfig(responseSchema = calendarEventsExtractionSchema)
                )
            )
        }.body<GenerateContentResponse>()

        return parseCalendarEvents(response)
    }

    // Word-document (.docx) counterpart of extractCalendarEventsFromImage -
    // InboxRoutes.kt's extractDocxText pulls the plain text out locally
    // first (Gemini has no inlineData mimeType for docx), so this sends it
    // as a second text part appended after the prompt instead of an
    // inlineData part, same shape as extract()'s email body.
    override suspend fun extractCalendarEventsFromText(documentText: String): List<ExtractedCalendarEvent> {
        val prompt = calendarEventsPrompt("the calendar/schedule text below, extracted from an uploaded document") +
            "\n\nDocument text:\n$documentText"
        val response = httpClient.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent") {
            parameter("key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                GenerateContentRequest(
                    contents = listOf(GeminiContent(listOf(GeminiPart(text = prompt)))),
                    generationConfig = GeminiGenerationConfig(responseSchema = calendarEventsExtractionSchema)
                )
            )
        }.body<GenerateContentResponse>()

        return parseCalendarEvents(response)
    }

    // Gemini sometimes wraps its JSON response in a markdown code fence even
    // with responseMimeType=application/json set - same quirk foodie's
    // RecipeParser.kt works around, verified against a live response there.
    private fun stripJsonFence(rawText: String): String = rawText.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
}
