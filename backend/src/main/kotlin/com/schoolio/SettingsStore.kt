package com.schoolio

import com.google.cloud.firestore.Firestore

// Shared between both allowed accounts, not per-user - same "two-person
// household, one shared view" reasoning as ALLOWED_EMAILS/User.id, just
// applied to scan config instead of identity. One doc, not a collection:
// there's exactly one set of scan settings for the whole app.
data class ScanSettings(val schoolSenders: List<String>, val lookbackWeeks: Int)

interface SettingsRepository {
    suspend fun get(): ScanSettings
    suspend fun save(settings: ScanSettings)
}

// Falls back to the SCHOOL_SENDERS/LOOKBACK_WEEKS env vars only until the
// settings doc exists - once the /inbox settings form is submitted once,
// Firestore is the source of truth and the env vars stop being read. Lets a
// fresh deploy still work from env vars before anyone's touched the UI,
// without a migration step to pre-seed the doc.
class FirestoreSettingsStore(
    private val firestore: Firestore,
    private val defaultSchoolSenders: List<String>,
    private val defaultLookbackWeeks: Int
) : SettingsRepository {
    private val docRef = firestore.collection("settings").document("scan")

    override suspend fun get(): ScanSettings {
        val doc = docRef.get().get()
        if (!doc.exists()) return ScanSettings(defaultSchoolSenders, defaultLookbackWeeks)
        @Suppress("UNCHECKED_CAST")
        val senders = (doc.get("schoolSenders") as? List<String>) ?: defaultSchoolSenders
        val lookbackWeeks = doc.getLong("lookbackWeeks")?.toInt() ?: defaultLookbackWeeks
        return ScanSettings(senders, lookbackWeeks)
    }

    override suspend fun save(settings: ScanSettings) {
        docRef.set(mapOf("schoolSenders" to settings.schoolSenders, "lookbackWeeks" to settings.lookbackWeeks)).get()
    }
}
