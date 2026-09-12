package com.schoolio

import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// The app's main flow: scan the last `lookbackWeeks` of email from
// `schoolSenders` only (never the whole inbox - see README's "Sender
// filtering" scope item) and run each through Gemini to pull out a summary
// and action items, with dates/times when the email states one.
fun Route.inboxRoutes(
    userStore: UserRepository,
    gmailClient: GmailClient,
    geminiClient: GeminiClient,
    schoolSenders: List<String>,
    lookbackWeeks: Int
) {
    get("/inbox") {
        val userId = call.requireUserId()
        val user = userStore.find(userId)
        val refreshToken = user?.googleRefreshToken
        if (refreshToken == null) {
            call.respond(FreeMarkerContent("inbox.ftl", mapOf("needsGmailAccess" to true) + call.currentUserModel()))
            return@get
        }
        if (schoolSenders.isEmpty()) {
            call.respond(FreeMarkerContent("inbox.ftl", mapOf("noSendersConfigured" to true) + call.currentUserModel()))
            return@get
        }

        val messages = gmailClient.searchMessages(refreshToken, schoolSenders, lookbackWeeks)
        val items = messages.map { message ->
            val extraction = geminiClient.extract(message.subject, message.from, message.bodyText)
            mapOf(
                "subject" to message.subject,
                "from" to message.from,
                "date" to message.date,
                "summary" to extraction.summary,
                "actionItems" to extraction.actionItems
            )
        }
        call.respond(
            FreeMarkerContent(
                "inbox.ftl",
                mapOf("items" to items, "lookbackWeeks" to lookbackWeeks) + call.currentUserModel()
            )
        )
    }
}
