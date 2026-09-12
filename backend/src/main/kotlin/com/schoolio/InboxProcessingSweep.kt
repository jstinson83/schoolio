package com.schoolio

// Runs Gemini extraction over every still-PENDING message and turns each
// one's action items into persisted ActionItems (see ActionItemStore.kt) -
// the debounced background pass InboxRoutes.kt schedules after every Gmail
// pull (see scheduleProcessing), same "pull instantly, process a moment
// later" split as foodie's resolveEditedGroceryItems.
suspend fun processPendingMessages(
    messageStore: MessageRepository,
    actionItemStore: ActionItemRepository,
    geminiClient: GeminiClient
) {
    for (message in messageStore.getPending()) {
        try {
            val extraction = geminiClient.extract(message.subject, message.from, message.bodyText)
            actionItemStore.addAll(extraction.actionItems.map { it.toActionItem(message.id) })
            messageStore.markProcessed(message.id, extraction.summary)
        } catch (e: Exception) {
            // Marked FAILED (not left PENDING for a silent auto-retry) so the
            // raw message stays visible with a reason instead of quietly
            // vanishing into an indefinite pending state - same "never lose
            // the source data" reasoning as storing the raw message at all.
            // A future retry is a manual action, not built here yet.
            messageStore.markFailed(message.id, e.message ?: "Unknown error")
        }
    }
}

// dueDate/dueTime stay separate through extraction (see ExtractedActionItem's
// doc comment) but collapse into ActionItem's one combined [date] field here,
// at the point the extraction becomes a persisted domain object. A bare time
// with no date is dropped rather than kept - a time alone isn't a usable
// calendar-ready date.
private fun ExtractedActionItem.toActionItem(sourceMessageId: String): ActionItem = ActionItem(
    sourceMessageId = sourceMessageId,
    title = title,
    description = description,
    date = dueDate?.let { d -> if (dueTime != null) "${d}T$dueTime" else d }
)
