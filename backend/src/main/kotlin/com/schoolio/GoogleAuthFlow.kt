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

// Identity only - openid/email/profile are all "non-sensitive" scopes, so
// this needs no consent-screen review, no test-user allowlist, no
// unverified-app warning. Gmail *data* access is a separate concern
// entirely: it goes through IMAP with a per-user app password (see
// GmailClient.kt/UserStore.kt), not an OAuth scope on this login flow -
// deliberately, to avoid gmail.readonly's "restricted scope" verification
// requirement (a paid, recurring CASA Tier 2 security assessment - see
// context.md's Gmail integration notes for the full reasoning).
private val GOOGLE_SCOPES = listOf("openid", "email", "profile")

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
                defaultScopes = GOOGLE_SCOPES
                // No extraAuthParameters (access_type=offline/prompt=consent)
                // here anymore - those existed only to reliably get a Gmail
                // refresh_token back, which this flow no longer requests at
                // all. Plain identity sign-in doesn't need a refresh token or
                // forced re-consent.
            )
        }
        client = oauthHttpClient
    }
}

suspend fun fetchGoogleUserInfo(oauthHttpClient: HttpClient, accessToken: String): GoogleUserInfo =
    oauthHttpClient.get("https://www.googleapis.com/oauth2/v3/userinfo") {
        header(HttpHeaders.Authorization, "Bearer $accessToken")
    }.body()
