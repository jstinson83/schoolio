package com.schoolio

import com.google.cloud.firestore.Firestore
import java.time.Instant
import java.util.Date

interface ScanStateRepository {
    // Per-sender high-water mark: the receivedAt of the most recent message
    // already seen for that sender. Deliberately per-sender rather than one
    // global value - a single shared watermark would make adding a new
    // sender to ScanSettings inherit however far another, longer-watched
    // sender has already advanced, silently skipping that new sender's own
    // older mail. A sender with no entry yet means "never scanned" - the
    // caller (InboxRoutes) falls back to ScanSettings.lookbackWeeks for it.
    suspend fun getWatermarks(): Map<String, Instant>
    // Advances sender's watermark to at - a no-op if at isn't after the
    // currently stored value, so a re-pull of already-seen mail (see
    // MessageRepository.storeIfAbsent's dedupe) can't ever move a watermark
    // backwards.
    suspend fun recordSeen(sender: String, at: Instant)
}

// One doc holding every sender's watermark as an array of {sender, seenAt}
// entries, rather than a Firestore map keyed by the raw sender string -
// sender addresses/domains routinely contain "." and "@", and Firestore's
// update()/FieldPath dotted-path handling for map keys with those characters
// is easy to get subtly wrong. An array plus a transactional read-modify-write
// (same shape as foodie's GroceryListStore.mutateItems) sidesteps that
// entirely, and this is low-volume enough (a handful of senders) that reading
// the whole doc on every write is a non-issue.
class FirestoreScanStateStore(private val firestore: Firestore) : ScanStateRepository {
    private val docRef = firestore.collection("scanState").document("watermarks")

    override suspend fun getWatermarks(): Map<String, Instant> {
        val doc = docRef.get().get()
        if (!doc.exists()) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        val entries = doc.get("bySender") as? List<Map<String, Any?>> ?: return emptyMap()
        return entries.associate { (it["sender"] as String) to (it["seenAt"] as Date).toInstant() }
    }

    override suspend fun recordSeen(sender: String, at: Instant) {
        firestore.runTransaction { txn ->
            val doc = txn.get(docRef).get()
            @Suppress("UNCHECKED_CAST")
            val entries = (doc.get("bySender") as? List<Map<String, Any?>>)?.toMutableList() ?: mutableListOf()
            val index = entries.indexOfFirst { it["sender"] == sender }
            val existingAt = (entries.getOrNull(index)?.get("seenAt") as? Date)?.toInstant()
            if (existingAt != null && !at.isAfter(existingAt)) return@runTransaction
            val entry = mapOf("sender" to sender, "seenAt" to Date.from(at))
            if (index >= 0) entries[index] = entry else entries.add(entry)
            txn.set(docRef, mapOf("bySender" to entries))
        }.get()
    }
}
