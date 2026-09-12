package com.schoolio

import io.ktor.client.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*

fun Application.installGoogleAuth(oauthHttpClient: HttpClient, redirectBaseUrl: String, sessionSecret: String) {
    installSessionCookie(sessionSecret)
    install(Authentication) {
        googleOAuthProvider(oauthHttpClient, redirectBaseUrl)
        userSessionProvider()
    }
}

fun Route.authRoutes(oauthHttpClient: HttpClient, userStore: UserRepository, allowedEmails: Set<String>) {
    // Both routes sit under the same provider: hitting /auth/google with no
    // principal yet is what makes Ktor's OAuth provider redirect to Google;
    // Google then redirects back here with a code, which the same provider
    // exchanges for a token before this block's handler runs.
    authenticate(GOOGLE_OAUTH_PROVIDER_NAME) {
        get("/auth/google") {
            // Unreached when unauthenticated - the provider redirects to
            // Google before this body runs.
        }

        get("/auth/google/callback") {
            val principal = call.principal<OAuthAccessTokenResponse.OAuth2>()
            if (principal == null) {
                call.respondRedirect("/?authError=1")
                return@get
            }
            val userInfo = fetchGoogleUserInfo(oauthHttpClient, principal.accessToken)
            if (userInfo.email.lowercase() !in allowedEmails) {
                // Not one of the two allowed accounts - no session, no user
                // record created. Doesn't distinguish "wrong account" from
                // any other failure in the redirect itself, same
                // don't-leak-detail reasoning foodie's email-sign-in flow
                // uses, just applied to the allowlist instead.
                call.respondRedirect("/?authError=1")
                return@get
            }
            val user = userStore.findOrCreateByGoogle(userInfo.sub, userInfo.email, userInfo.name)
            call.completeSignIn(user)
        }
    }

    post("/logout") {
        call.sessions.clear<SessionData>()
        call.respondRedirect("/")
    }
}
