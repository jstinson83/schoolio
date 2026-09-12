package com.schoolio

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// What callers (InboxRoutes.kt) actually need - trimmed down from Gmail's
// full message resource, same "shape of what we use, not what the API
// returns" reasoning as GoogleUserInfo in GoogleAuthFlow.kt. bodyText is the
// plain-text email body (falling back to Gmail's snippet if no text/plain
// part is found) - GeminiClient needs the actual content, not just headers.
data class GmailMessage(val id: String, val subject: String, val from: String, val date: String, val bodyText: String)

interface GmailClient {
    // refreshToken is per-user (User.googleRefreshToken) - the caller is
    // responsible for having one (i.e. having signed in and granted Gmail
    // access at least once); this interface doesn't touch UserRepository
    // itself. senders/sinceWeeks scope the search server-side (Gmail's own
    // `q` search operators) rather than pulling everything and filtering
    // locally - see README's "I don't want to pull all my email" framing.
    suspend fun searchMessages(refreshToken: String, senders: List<String>, sinceWeeks: Int): List<GmailMessage>
}

// Comma-separated list of sender addresses/domains to scan for - Gmail's
// `from:` search operator accepts either. Configurable via SCHOOL_SENDERS
// rather than hardcoded, since which teachers/school accounts to watch
// differs per household and changes over time. Empty/unset means "nothing
// configured yet", a distinct state from "configured but zero messages
// found" - InboxRoutes checks for it before ever calling Gmail.
fun parseSchoolSenders(raw: String?): List<String> =
    (raw ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }

@Serializable
private data class TokenRefreshResponse(@SerialName("access_token") val accessToken: String)

@Serializable
private data class GmailMessageListResponse(val messages: List<GmailMessageId> = emptyList())

@Serializable
private data class GmailMessageId(val id: String)

@Serializable
private data class GmailMessageDetail(val id: String, val snippet: String = "", val payload: GmailMessagePayload)

@Serializable
private data class GmailMessagePayload(
    val mimeType: String = "",
    val headers: List<GmailHeader> = emptyList(),
    val body: GmailMessageBody = GmailMessageBody(),
    val parts: List<GmailMessagePayload> = emptyList()
)

@Serializable
private data class GmailMessageBody(val data: String? = null)

@Serializable
private data class GmailHeader(val name: String, val value: String)

// Plain REST calls against Gmail's API, not the google-api-services-gmail
// client library - consistent with how foodie's own Google/Firebase REST
// calls (GoogleAuthFlow.kt, EmailAuthFlow.kt) go through a plain injected
// HttpClient rather than a provider-specific SDK, which is also what makes
// this interface fake-able with MockEngine in tests the same way.
class RestGmailClient(private val httpClient: HttpClient) : GmailClient {
    // A short-lived access token minted fresh from the stored refresh token
    // on every call, rather than cached - schoolio pulls email rarely (on
    // app open, or periodically - see README's open questions), so there's
    // no hot path here worth the complexity of caching a ~1-hour token.
    private suspend fun refreshAccessToken(refreshToken: String): String {
        val response = httpClient.submitForm(
            url = "https://oauth2.googleapis.com/token",
            formParameters = Parameters.build {
                append("client_id", System.getenv("GOOGLE_CLIENT_ID") ?: "")
                append("client_secret", System.getenv("GOOGLE_CLIENT_SECRET") ?: "")
                append("refresh_token", refreshToken)
                append("grant_type", "refresh_token")
            }
        )
        return response.body<TokenRefreshResponse>().accessToken
    }

    // Epoch seconds (rather than Gmail's YYYY/MM/DD date form) so "n weeks"
    // is exact down to the second instead of rounding to a calendar day.
    private fun buildSearchQuery(senders: List<String>, sinceWeeks: Int): String {
        val since = Instant.now().minus(Duration.ofDays(sinceWeeks * 7L)).epochSecond
        val senderClause = senders.joinToString(" OR ") { "from:$it" }
        return "after:$since ($senderClause)"
    }

    override suspend fun searchMessages(refreshToken: String, senders: List<String>, sinceWeeks: Int): List<GmailMessage> {
        val accessToken = refreshAccessToken(refreshToken)
        val list = httpClient.get("https://gmail.googleapis.com/gmail/v1/users/me/messages") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("q", buildSearchQuery(senders, sinceWeeks))
        }.body<GmailMessageListResponse>()

        // format=full (not metadata) since Gemini needs the actual body text,
        // not just headers - the tradeoff InboxRoutes.kt's earlier
        // metadata-only proof-of-pull didn't have to make.
        return list.messages.map { msg ->
            val detail = httpClient.get("https://gmail.googleapis.com/gmail/v1/users/me/messages/${msg.id}") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                parameter("format", "full")
            }.body<GmailMessageDetail>()
            val headers = detail.payload.headers.associateBy { it.name }
            GmailMessage(
                id = detail.id,
                subject = headers["Subject"]?.value ?: "(no subject)",
                from = headers["From"]?.value ?: "(unknown sender)",
                date = headers["Date"]?.value ?: "",
                // Falls back to Gmail's own snippet (a short plain-text
                // preview it always includes) for the rare message with no
                // text/plain part at all, rather than sending nothing to
                // Gemini.
                bodyText = extractPlainText(detail.payload).ifBlank { detail.snippet }
            )
        }
    }

    private fun extractPlainText(payload: GmailMessagePayload): String {
        if (payload.mimeType == "text/plain" && payload.body.data != null) {
            return decodeBase64Url(payload.body.data)
        }
        for (part in payload.parts) {
            val text = extractPlainText(part)
            if (text.isNotBlank()) return text
        }
        return ""
    }

    // Gmail's body data is base64url-encoded, typically without padding -
    // java.util.Base64's decoder wants valid padding, so pad it back out
    // rather than relying on undocumented lenient behavior.
    private fun decodeBase64Url(data: String): String {
        val padded = data + "=".repeat((4 - data.length % 4) % 4)
        return String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
    }
}
