package com.schoolio

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore

// A single actionable to-do extracted from a message, pulled directly from a
// calendar event, or transcribed from a photo of a calendar - a first-class
// entity (its own top-level collection) rather than nested inside the
// message doc, so it can later grow its own lifecycle
// (done/dismissed/pushed-to-calendar) without rewriting the whole message
// every time that changes. At most one of sourceMessageId/
// sourceCalendarEventId is set, matching which pipeline produced this item
// (see InboxProcessingSweep.kt vs InboxRoutes.kt's pullAndStoreCalendarEvents
// vs the photo-import routes) - both are provenance/debugging links, not
// part of the main app flow, which just renders items inline (see
// inbox.ftl). A calendar-derived item has no source message, so
// buildDateGroups/buildFlatActionItemViews (InboxRoutes.kt) treat a null
// sourceMessageId the same as an unmatched one: blank subject/from/summary.
// sourcePhotoImport marks the third case (neither field set) so those
// builders can still tell it apart from a calendar-derived item when
// choosing what "From ..." text to show - a plain random-id insert
// (ActionItemRepository.addAll), same as the email path, since a one-off
// photo import has no stable id to upsert against the way a recurring
// calendar pull does.
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
    val sourcePhotoImport: Boolean = false,
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
    // For the calendar pull (pullAndStoreCalendarEvents), keyed on a stable
    // id (the calendar event's own uid) rather than addAll's fresh random
    // one. Unlike email (an EmailMessage's content is immutable once
    // received, so MessageRepository.storeIfAbsent's plain "skip if it
    // already exists" dedup is correct there), Google Calendar is the
    // ongoing source of truth for an event - it can be retitled or
    // rescheduled between pulls, so this refreshes title/description/date
    // on every pull rather than only inserting once. Preserves whatever the
    // household already decided about [dismissed] across that refresh - an
    // item they dismissed shouldn't silently reappear just because the
    // school edited its description. (Was a plain storeIfAbsent at first,
    // dedup-only like the email pattern - that's what made the Eastern-time
    // fix (see HOUSEHOLD_ZONE) not visibly take effect for an event already
    // pulled before that fix shipped: the stored id already existed, so the
    // freshly-recomputed value was silently discarded every sync after.)
    suspend fun upsertFromCalendar(item: ActionItem)
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
    // user vanishingly unlikely. Reads the existing doc only to carry its
    // [dismissed] value forward - everything else in [item] always wins,
    // since Calendar is the source of truth for title/description/date.
    override suspend fun upsertFromCalendar(item: ActionItem) {
        val docRef = collection.document(item.id)
        val existingDismissed = docRef.get().get().getBoolean("dismissed")
        docRef.set(itemToMap(item.copy(dismissed = existingDismissed ?: item.dismissed))).get()
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
        sourcePhotoImport = getBoolean("sourcePhotoImport") ?: false,
        title = getString("title") ?: "",
        description = getString("description") ?: "",
        date = getString("date"),
        dismissed = getBoolean("dismissed") ?: false
    )

    private fun itemToMap(item: ActionItem): Map<String, Any?> = mapOf(
        "sourceMessageId" to item.sourceMessageId,
        "sourceCalendarEventId" to item.sourceCalendarEventId,
        "sourcePhotoImport" to item.sourcePhotoImport,
        "title" to item.title,
        "description" to item.description,
        "date" to item.date,
        "dismissed" to item.dismissed
    )
}
