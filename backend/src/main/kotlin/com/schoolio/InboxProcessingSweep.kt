package com.schoolio

// Runs Gemini extraction over every still-PENDING message and turns each
// one's action items into persisted ActionItems (see ActionItemStore.kt) -
// the debounced background pass InboxRoutes.kt schedules after every Gmail
// pull (see scheduleProcessing), same "pull instantly, process a moment
// later" split as foodie's resolveEditedGroceryItems.
suspend fun processPendingMessages(
    messageStore: MessageRepository,
    actionItemStore: ActionItemRepository,
    geminiClient: GeminiClient,
    // Null when ATTACHMENTS_BUCKET isn't set on this deployment (see
    // Application.kt) - same "additive, not a hard gate" nullability as
    // calendarClient. A message's attachments (if any) are just skipped for
    // extraction in that case; the email body alone still gets processed as
    // before.
    attachmentStore: AttachmentRepository?
) {
    for (message in messageStore.getPending()) {
        try {
            val attachments = message.attachmentsForExtraction(attachmentStore)
            val extraction = geminiClient.extract(message.subject, message.from, message.bodyText, attachments)
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

// Only image/PDF attachments are worth fetching back from Cloud Storage -
// Gemini's inlineData mechanism doesn't accept a docx/Word attachment any
// more than the photo-import path's extractCalendarEventsFromImage does (see
// GeminiClient.kt), and there's no extractCalendarEventsFromText-style
// local-text-extraction step wired up for a plain email's attachments. A
// download() that comes back null (e.g. the object was deleted out from
// under Firestore's own record) is dropped rather than failing the whole
// extraction.
private suspend fun EmailMessage.attachmentsForExtraction(attachmentStore: AttachmentRepository?): List<ExtractionAttachment> {
    if (attachmentStore == null) return emptyList()
    return attachments
        .filter { it.contentType.startsWith("image/") || it.contentType == "application/pdf" }
        .mapNotNull { stored -> attachmentStore.download(stored.storagePath)?.let { ExtractionAttachment(it, stored.contentType) } }
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
