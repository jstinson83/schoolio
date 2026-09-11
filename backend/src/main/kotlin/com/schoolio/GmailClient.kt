package com.schoolio

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// What callers (InboxRoutes.kt) actually need - trimmed down from Gmail's
// full message resource, same "shape of what we use, not what the API
// returns" reasoning as GoogleUserInfo in GoogleAuthFlow.kt.
data class GmailMessageSummary(val id: String, val subject: String, val from: String, val date: String)

interface GmailClient {
    // refreshToken is per-user (User.googleRefreshToken) - the caller is
    // responsible for having one (i.e. having signed in and granted Gmail
    // access at least once); this interface doesn't touch UserRepository
    // itself.
    suspend fun listRecentMessages(refreshToken: String, maxResults: Int = 10): List<GmailMessageSummary>
}

@Serializable
private data class TokenRefreshResponse(@SerialName("access_token") val accessToken: String)

@Serializable
private data class GmailMessageListResponse(val messages: List<GmailMessageId> = emptyList())

@Serializable
private data class GmailMessageId(val id: String)

@Serializable
private data class GmailMessageDetail(val id: String, val payload: GmailMessagePayload)

@Serializable
private data class GmailMessagePayload(val headers: List<GmailHeader> = emptyList())

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

    override suspend fun listRecentMessages(refreshToken: String, maxResults: Int): List<GmailMessageSummary> {
        val accessToken = refreshAccessToken(refreshToken)
        val list = httpClient.get("https://gmail.googleapis.com/gmail/v1/users/me/messages") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("maxResults", maxResults)
        }.body<GmailMessageListResponse>()

        // format=metadata (not full) pulls just the headers we need per
        // message - one request per id is the price of that, but avoids
        // downloading full message bodies just to list a subject/sender/date.
        return list.messages.map { msg ->
            val detail = httpClient.get("https://gmail.googleapis.com/gmail/v1/users/me/messages/${msg.id}") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                parameter("format", "metadata")
                parameter("metadataHeaders", "Subject")
                parameter("metadataHeaders", "From")
                parameter("metadataHeaders", "Date")
            }.body<GmailMessageDetail>()
            val headers = detail.payload.headers.associateBy { it.name }
            GmailMessageSummary(
                id = detail.id,
                subject = headers["Subject"]?.value ?: "(no subject)",
                from = headers["From"]?.value ?: "(unknown sender)",
                date = headers["Date"]?.value ?: ""
            )
        }
    }
}
