package com.schoolio

import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// The app's main flow: scan the last `lookbackWeeks` of email from
// `schoolSenders` only (never the whole inbox - see README's "Sender
// filtering" scope item) and run each through Gemini to pull out a summary
// and action items, with dates/times when the email states one.
// schoolSenders/lookbackWeeks live in Firestore (SettingsRepository), edited
// via the form on this page - not just a one-time env var read - since both
// household accounts need to see/change the same values without a redeploy.
fun Route.inboxRoutes(
    userStore: UserRepository,
    gmailClient: GmailClient,
    geminiClient: GeminiClient,
    settingsStore: SettingsRepository
) {
    get("/inbox") {
        val userId = call.requireUserId()
        val user = userStore.find(userId)
        val refreshToken = user?.googleRefreshToken
        val settings = settingsStore.get()
        // Included on every branch below so the settings form always shows
        // the current values, whether or not a scan actually ran this request.
        val settingsModel = mapOf(
            "sendersText" to settings.schoolSenders.joinToString(", "),
            "lookbackWeeks" to settings.lookbackWeeks
        )

        if (refreshToken == null) {
            call.respond(FreeMarkerContent("inbox.ftl", mapOf("needsGmailAccess" to true) + settingsModel + call.currentUserModel()))
            return@get
        }
        if (settings.schoolSenders.isEmpty()) {
            call.respond(FreeMarkerContent("inbox.ftl", mapOf("noSendersConfigured" to true) + settingsModel + call.currentUserModel()))
            return@get
        }

        val messages = gmailClient.searchMessages(refreshToken, settings.schoolSenders, settings.lookbackWeeks)
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
        call.respond(FreeMarkerContent("inbox.ftl", mapOf("items" to items) + settingsModel + call.currentUserModel()))
    }

    post("/inbox/settings") {
        val form = call.receiveParameters()
        val senders = parseSchoolSenders(form["senders"])
        // Clamped rather than trusting raw input - a stray huge number would
        // turn into an equally huge Gmail `after:` window for no benefit;
        // zero/negative would search a nonsensical or empty window.
        val lookbackWeeks = (form["lookbackWeeks"]?.toIntOrNull() ?: 4).coerceIn(1, 52)
        settingsStore.save(ScanSettings(senders, lookbackWeeks))
        // Redirect-after-post so reloading /inbox re-runs the scan with the
        // new settings instead of resubmitting the form.
        call.respondRedirect("/inbox")
    }
}
