package com.schoolio

import com.google.cloud.firestore.Firestore

// One doc tracking the last date the daily digest push (see WebPush.kt,
// InboxRoutes.kt's internalNotifyRoutes) actually went out - guards against
// sending the same day's digest twice if POST /internal/notify-daily is
// retried or accidentally fired more than once on its Cloud Scheduler cron.
// Deliberately its own tiny repository rather than folding into
// ScanSettings/SettingsStore - that doc is scan configuration (senders,
// lookback), not notification bookkeeping, same "one doc per concern"
// granularity as ScanStateStore/MessageStore/ActionItemStore.
interface NotificationStateRepository {
    suspend fun getLastDailyDigestDate(): String?
    suspend fun recordDailyDigestSent(date: String)
}

class FirestoreNotificationStateStore(private val firestore: Firestore) : NotificationStateRepository {
    private val docRef = firestore.collection("notifications").document("daily")

    override suspend fun getLastDailyDigestDate(): String? = docRef.get().get().getString("lastSentDate")

    override suspend fun recordDailyDigestSent(date: String) {
        docRef.set(mapOf("lastSentDate" to date)).get()
    }
}
