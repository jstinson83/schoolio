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

// dueDate/dueTime are nullable, separate fields (rather than one combined
// timestamp) because a school email frequently states only a date ("permission
// slips due Friday") with no time - forcing a time would mean inventing one.
// ISO-8601 (YYYY-MM-DD / HH:MM 24h) - enforced via the prompt/schema below,
// not parsed/validated here, since Gemini is the one doing the extraction.
data class ActionItem(val description: String, val dueDate: String? = null, val dueTime: String? = null)

data class EmailExtraction(val summary: String, val actionItems: List<ActionItem>)

interface GeminiClient {
    suspend fun extract(subject: String, from: String, bodyText: String): EmailExtraction
}

@Serializable
private data class GenerateContentRequest(val contents: List<GeminiContent>, val generationConfig: GeminiGenerationConfig)

@Serializable
private data class GeminiContent(val parts: List<GeminiPart>)

@Serializable
private data class GeminiPart(val text: String)

@Serializable
private data class GeminiGenerationConfig(val responseMimeType: String = "application/json", val responseSchema: JsonObject)

@Serializable
private data class GenerateContentResponse(val candidates: List<GeminiCandidate> = emptyList())

@Serializable
private data class GeminiCandidate(val content: GeminiContent)

@Serializable
private data class ExtractionPayload(val summary: String, val actionItems: List<ActionItemPayload> = emptyList())

@Serializable
private data class ActionItemPayload(val description: String, val dueDate: String? = null, val dueTime: String? = null)

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
                    put("description", buildJsonObject { put("type", "STRING") })
                    put("dueDate", buildJsonObject { put("type", "STRING") })
                    put("dueTime", buildJsonObject { put("type", "STRING") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("description"))))
            })
        })
    })
    put("required", JsonArray(listOf(JsonPrimitive("summary"), JsonPrimitive("actionItems"))))
}

// Plain REST calls against Gemini's API, not a provider SDK - same
// no-framework call as RestGmailClient/GoogleAuthFlow.kt.
class RestGeminiClient(
    private val httpClient: HttpClient,
    private val apiKey: String = System.getenv("GEMINI_API_KEY") ?: "",
    private val model: String = System.getenv("GEMINI_MODEL") ?: "gemini-2.5-flash"
) : GeminiClient {
    override suspend fun extract(subject: String, from: String, bodyText: String): EmailExtraction {
        val prompt = """
            You are helping a parent keep track of school-related email. Read the email below and:
            1. Write a one-to-two sentence summary of what it's about.
            2. List any action items the parent needs to do (permission slips to sign, forms to
               return, events to attend, deadlines to meet). If the email states a date and/or
               time for an item, include it (dueDate as YYYY-MM-DD, dueTime as 24-hour HH:MM);
               omit whichever one isn't stated. If there's nothing actionable, return an empty list.

            From: $from
            Subject: $subject
            Body:
            $bodyText
        """.trimIndent()

        val response = httpClient.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent") {
            parameter("key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                GenerateContentRequest(
                    contents = listOf(GeminiContent(listOf(GeminiPart(prompt)))),
                    generationConfig = GeminiGenerationConfig(responseSchema = extractionSchema)
                )
            )
        }.body<GenerateContentResponse>()

        val text = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: return EmailExtraction(summary = "", actionItems = emptyList())
        val payload = Json { ignoreUnknownKeys = true }.decodeFromString<ExtractionPayload>(text)
        return EmailExtraction(
            summary = payload.summary,
            actionItems = payload.actionItems.map { ActionItem(it.description, it.dueDate, it.dueTime) }
        )
    }
}
