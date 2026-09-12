package com.schoolio

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore

// A single actionable to-do extracted from a message - a first-class entity
// (its own top-level collection) rather than nested inside the message doc,
// so it can later grow its own lifecycle (done/dismissed/pushed-to-calendar)
// without rewriting the whole message every time that changes. sourceMessageId
// links back to the raw EmailMessage it came from (see MessageStore.kt) for
// provenance/debugging - that link isn't part of the main app flow today,
// which only renders action items inline under their message (see inbox.ftl).
// date is a single combined ISO-8601 field (YYYY-MM-DD, or YYYY-MM-DD'T'HH:MM
// when Gemini also extracted a time) - InboxRoutes combines
// ExtractedActionItem's separate dueDate/dueTime into this one field at
// persistence time; null if the email stated no date at all.
// dismissed is the first bit of that "done/dismissed/pushed-to-calendar"
// lifecycle mentioned above - a dismissed item drops out of the main /inbox
// view (see InboxRoutes.kt's active/dismissed split) but isn't deleted, so
// it can still be reviewed/restored from GET /inbox/dismissed.
data class ActionItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sourceMessageId: String,
    val title: String,
    val description: String,
    val date: String? = null,
    val dismissed: Boolean = false
)

interface ActionItemRepository {
    // Every action item across every message, dismissed or not - the inbox
    // page's join target, grouped by sourceMessageId at the call site (see
    // InboxRoutes.kt), which itself splits on [ActionItem.dismissed].
    suspend fun getAll(): List<ActionItem>
    // Batched rather than one write per item - a single message can produce
    // several action items in the same Gemini call, and they're always
    // created together (see processPendingMessages).
    suspend fun addAll(items: List<ActionItem>)
    // Flips dismissed on/off for one item - a single-field update rather than
    // rewriting the whole document, since nothing else about the item changes
    // when it's dismissed or restored.
    suspend fun dismiss(id: String)
    suspend fun restore(id: String)
}

class FirestoreActionItemStore(private val firestore: Firestore) : ActionItemRepository {
    private val collection = firestore.collection("actionItems")

    override suspend fun getAll(): List<ActionItem> =
        collection.get().get().documents.map { it.toActionItem() }

    override suspend fun addAll(items: List<ActionItem>) {
        if (items.isEmpty()) return
        val batch = firestore.batch()
        for (item in items) {
            batch.set(collection.document(item.id), itemToMap(item))
        }
        batch.commit().get()
    }

    override suspend fun dismiss(id: String) {
        collection.document(id).update("dismissed", true).get()
    }

    override suspend fun restore(id: String) {
        collection.document(id).update("dismissed", false).get()
    }

    private fun DocumentSnapshot.toActionItem(): ActionItem = ActionItem(
        id = id,
        sourceMessageId = getString("sourceMessageId") ?: "",
        title = getString("title") ?: "",
        description = getString("description") ?: "",
        date = getString("date"),
        dismissed = getBoolean("dismissed") ?: false
    )

    private fun itemToMap(item: ActionItem): Map<String, Any?> = mapOf(
        "sourceMessageId" to item.sourceMessageId,
        "title" to item.title,
        "description" to item.description,
        "date" to item.date,
        "dismissed" to item.dismissed
    )
}
