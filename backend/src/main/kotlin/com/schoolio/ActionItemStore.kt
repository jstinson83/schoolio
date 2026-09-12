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
data class ActionItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sourceMessageId: String,
    val title: String,
    val description: String,
    val date: String? = null
)

interface ActionItemRepository {
    // Every action item across every message - the inbox page's join target,
    // grouped by sourceMessageId at the call site (see InboxRoutes.kt).
    suspend fun getAll(): List<ActionItem>
    // Batched rather than one write per item - a single message can produce
    // several action items in the same Gemini call, and they're always
    // created together (see processPendingMessages).
    suspend fun addAll(items: List<ActionItem>)
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

    private fun DocumentSnapshot.toActionItem(): ActionItem = ActionItem(
        id = id,
        sourceMessageId = getString("sourceMessageId") ?: "",
        title = getString("title") ?: "",
        description = getString("description") ?: "",
        date = getString("date")
    )

    private fun itemToMap(item: ActionItem): Map<String, Any?> = mapOf(
        "sourceMessageId" to item.sourceMessageId,
        "title" to item.title,
        "description" to item.description,
        "date" to item.date
    )
}
