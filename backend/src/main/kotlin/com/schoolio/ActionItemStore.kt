package com.schoolio

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore

// A single actionable to-do extracted from a message, or pulled directly
// from a calendar event - a first-class entity (its own top-level
// collection) rather than nested inside the message doc, so it can later
// grow its own lifecycle (done/dismissed/pushed-to-calendar) without
// rewriting the whole message every time that changes. Exactly one of
// sourceMessageId/sourceCalendarEventId is set, matching which pipeline
// produced this item (see InboxProcessingSweep.kt vs InboxRoutes.kt's
// pullAndStoreCalendarEvents) - both are provenance/debugging links, not
// part of the main app flow, which just renders items inline (see
// inbox.ftl). A calendar-derived item has no source message, so
// buildDateGroups/buildFlatActionItemViews (InboxRoutes.kt) treat a null
// sourceMessageId the same as an unmatched one: blank subject/from/summary.
// date is a single combined ISO-8601 field (YYYY-MM-DD, or YYYY-MM-DD'T'HH:MM
// when a time is known) - InboxRoutes combines ExtractedActionItem's
// separate dueDate/dueTime (email path) or CalendarEvent.start (calendar
// path) into this one field at persistence time; null if the email stated no
// date at all (never null for a calendar-derived item - a VEVENT always has
// at least DTSTART).
// dismissed is the first bit of that "done/dismissed/pushed-to-calendar"
// lifecycle mentioned above - a dismissed item drops out of the main /inbox
// view (see InboxRoutes.kt's active/dismissed split) but isn't deleted, so
// it can still be reviewed/restored from GET /inbox/dismissed.
data class ActionItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sourceMessageId: String? = null,
    val sourceCalendarEventId: String? = null,
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
    // created together (see processPendingMessages). Always inserts - safe
    // because Gemini-derived items always get a fresh random id (see
    // ActionItem.id's default), so there's nothing to collide with.
    suspend fun addAll(items: List<ActionItem>)
    // Unlike addAll, a no-op if [item.id] already exists - for the calendar
    // pull (pullAndStoreCalendarEvents), which re-fetches the same [from,
    // until] window on every sync and needs re-seeing an already-stored
    // event to not duplicate it, same "storeIfAbsent" dedup shape as
    // MessageRepository (see MessageStore.kt) keyed on a stable id (here,
    // the calendar event's own uid) rather than a fresh random one.
    suspend fun storeIfAbsent(item: ActionItem)
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

    // Not transactional (a get followed by a separate set) - same
    // "proportionate to a two-person app, not overengineered" call as the
    // rest of this codebase; the calendar pull's own per-user resync
    // cooldown (InboxRoutes.kt) already keeps concurrent calls for the same
    // user vanishingly unlikely.
    override suspend fun storeIfAbsent(item: ActionItem) {
        val docRef = collection.document(item.id)
        if (docRef.get().get().exists()) return
        docRef.set(itemToMap(item)).get()
    }

    override suspend fun dismiss(id: String) {
        collection.document(id).update("dismissed", true).get()
    }

    override suspend fun restore(id: String) {
        collection.document(id).update("dismissed", false).get()
    }

    private fun DocumentSnapshot.toActionItem(): ActionItem = ActionItem(
        id = id,
        sourceMessageId = getString("sourceMessageId"),
        sourceCalendarEventId = getString("sourceCalendarEventId"),
        title = getString("title") ?: "",
        description = getString("description") ?: "",
        date = getString("date"),
        dismissed = getBoolean("dismissed") ?: false
    )

    private fun itemToMap(item: ActionItem): Map<String, Any?> = mapOf(
        "sourceMessageId" to item.sourceMessageId,
        "sourceCalendarEventId" to item.sourceCalendarEventId,
        "title" to item.title,
        "description" to item.description,
        "date" to item.date,
        "dismissed" to item.dismissed
    )
}
