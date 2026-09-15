package com.schoolio

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*

// What we keep in the session cookie once someone signs in. userId is our
// own User.id (== googleSub - see UserStore.kt), not looked up per request
// once the cookie's signed. No householdId/readOnly flags the way foodie's
// UserSession has - schoolio has exactly two allowed accounts and one
// sign-in method, so there's nothing else to carry yet.
data class UserSession(val userId: String, val email: String, val name: String) : Principal

data class SessionData(val userId: String? = null, val email: String? = null, val name: String? = null) {
    fun toUserSession(): UserSession? =
        if (userId != null && email != null && name != null) UserSession(userId, email, name) else null
}

const val USER_SESSION_PROVIDER_NAME = "user-session"

// Cloud Run sets K_SERVICE on every revision; nothing else in local dev does,
// so it doubles as a "are we actually deployed behind HTTPS" check without a
// separate env var to remember to set - same trick foodie's AuthSession.kt uses.
private fun runningOnCloudRun(): Boolean = System.getenv("K_SERVICE") != null

fun Application.installSessionCookie(sessionSecret: String) {
    install(Sessions) {
        // Named "__session" (not "session") because Firebase Hosting strips
        // every request cookie except one literally named "__session" before
        // forwarding to Cloud Run - see CLAUDE.md's Firebase Hosting section.
        // Any other name silently breaks sign-in the moment Hosting fronts
        // this service, even though it still works hitting Cloud Run direct.
        cookie<SessionData>("__session") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = runningOnCloudRun()
            cookie.extensions["SameSite"] = "Lax"
            cookie.maxAgeInSeconds = 60 * 60 * 24 * 30
            // Without this, the cookie is just URL-encoded plaintext - anyone
            // could hand-edit their browser cookie to claim either allowed
            // account's identity.
            transform(SessionTransportTransformerMessageAuthentication(sessionSecret.toByteArray()))
        }
    }
}

fun AuthenticationConfig.userSessionProvider() {
    session<SessionData>(USER_SESSION_PROVIDER_NAME) {
        validate { data -> data.toUserSession() }
        challenge {
            // Browser navigation (a plain GET for a page) sends an Accept
            // header that prefers text/html; this app's own fetch() calls
            // (once it has any) never will - same split foodie's challenge
            // uses to tell a page request from an API call without
            // enumerating routes.
            val wantsHtml = call.request.headers[HttpHeaders.Accept]?.contains("text/html") == true
            if (wantsHtml) {
                call.respondRedirect("/")
            } else {
                call.respond(HttpStatusCode.Unauthorized)
            }
        }
    }
}

fun ApplicationCall.requireUserId(): String = principal<UserSession>()!!.userId

// Deliberately reads the session cookie directly rather than
// principal<UserSession>() - principal is only populated inside an
// authenticate(USER_SESSION_PROVIDER_NAME) block, but this is also called
// from "/" (splash.ftl), which is intentionally public so it can show
// either a sign-in link or the signed-in state. Same reasoning as foodie's
// own public "/" route reading call.sessions.get<SessionData>() directly.
fun ApplicationCall.currentUserModel(): Map<String, Any?> =
    mapOf("currentUser" to sessions.get<SessionData>()?.toUserSession())

// Comma-separated Google account emails allowed to sign in at all (not just
// reach an admin page, unlike foodie's ADMIN_EMAILS - this gates every
// sign-in, since schoolio is meant for exactly two people, not an open
// sign-up flow). No hardcoded fallback: unset/empty means nobody can sign
// in, rather than accidentally leaving the app open to any Google account.
fun parseAllowedEmails(raw: String?): Set<String> =
    (raw ?: "").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

suspend fun ApplicationCall.completeSignIn(user: User) {
    sessions.set(SessionData(user.id, user.email, user.name))
    respondRedirect("/")
}
