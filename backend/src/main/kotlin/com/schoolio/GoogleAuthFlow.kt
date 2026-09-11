package com.schoolio

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.auth.*
import kotlinx.serialization.Serializable

// Shape of https://www.googleapis.com/oauth2/v3/userinfo, trimmed to the
// fields we actually use - same as foodie's GoogleUserInfo.
@Serializable
data class GoogleUserInfo(val sub: String, val email: String, val name: String)

const val GOOGLE_OAUTH_PROVIDER_NAME = "auth-google"

// Scope requested on every sign-in: identity (openid/email/profile) plus
// read-only Gmail access - schoolio's whole point is pulling school email,
// so the Gmail scope is requested up front at login rather than as a
// separate "connect Gmail" step later. gmail.readonly is a Google "restricted
// scope" - see context.md's Gmail integration notes for what that means for
// the OAuth consent screen (test users, unverified-app warning) before this
// will actually work.
private val GOOGLE_SCOPES = listOf(
    "openid", "email", "profile", "https://www.googleapis.com/auth/gmail.readonly"
)

// Registers Ktor's OAuth provider for Google sign-in. Hitting /auth/google
// with no principal yet is what makes this provider redirect to Google;
// Google then redirects back to /auth/google/callback (must match
// urlProvider below) with a code, which the provider exchanges for a token
// before the callback route (Auth.kt) runs.
fun AuthenticationConfig.googleOAuthProvider(oauthHttpClient: HttpClient, redirectBaseUrl: String) {
    oauth(GOOGLE_OAUTH_PROVIDER_NAME) {
        urlProvider = { "$redirectBaseUrl/auth/google/callback" }
        providerLookup = {
            OAuthServerSettings.OAuth2ServerSettings(
                name = "google",
                authorizeUrl = "https://accounts.google.com/o/oauth2/auth",
                accessTokenUrl = "https://oauth2.googleapis.com/token",
                requestMethod = HttpMethod.Post,
                clientId = System.getenv("GOOGLE_CLIENT_ID") ?: "",
                clientSecret = System.getenv("GOOGLE_CLIENT_SECRET") ?: "",
                defaultScopes = GOOGLE_SCOPES,
                // access_type=offline is what makes Google's token exchange
                // include a refresh_token at all (otherwise only a short-lived
                // access token comes back); prompt=consent forces the consent
                // screen - and a fresh refresh_token - on every sign-in rather
                // than only the very first one, since with just two accounts
                // signing in rarely, "always get one back" is simpler than
                // conditionally requesting re-consent only when we don't
                // already have a stored token.
                extraAuthParameters = listOf("access_type" to "offline", "prompt" to "consent")
            )
        }
        client = oauthHttpClient
    }
}

suspend fun fetchGoogleUserInfo(oauthHttpClient: HttpClient, accessToken: String): GoogleUserInfo =
    oauthHttpClient.get("https://www.googleapis.com/oauth2/v3/userinfo") {
        header(HttpHeaders.Authorization, "Bearer $accessToken")
    }.body()
