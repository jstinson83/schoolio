package com.schoolio

import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// Proves the Gmail pull actually works end to end: lists the signed-in
// user's most recent messages with no sender filtering yet (that's a
// separate step - see README's "Sender filtering" scope item and
// context.md's open question on how senders get configured).
fun Route.inboxRoutes(userStore: UserRepository, gmailClient: GmailClient) {
    get("/inbox") {
        val userId = call.requireUserId()
        val user = userStore.find(userId)
        val refreshToken = user?.googleRefreshToken
        if (refreshToken == null) {
            call.respond(FreeMarkerContent("inbox.ftl", mapOf("needsGmailAccess" to true) + call.currentUserModel()))
            return@get
        }
        val messages = gmailClient.listRecentMessages(refreshToken)
        call.respond(FreeMarkerContent("inbox.ftl", mapOf("messages" to messages) + call.currentUserModel()))
    }
}
