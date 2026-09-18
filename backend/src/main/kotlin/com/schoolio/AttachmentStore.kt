package com.schoolio

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Where email attachment bytes actually live (Cloud Storage), separate from
// StoredAttachment (MessageStore.kt), which is just the Firestore-persisted
// metadata pointing at a path here. Deliberately dumb (upload/download a
// path, nothing about messages or filenames) - InboxRoutes.kt's
// pullAndStoreNewMessages is what decides the path for a given attachment and
// builds the StoredAttachment record; this interface doesn't need to know
// about EmailMessage at all.
interface AttachmentRepository {
    suspend fun upload(storagePath: String, contentType: String, bytes: ByteArray)
    suspend fun download(storagePath: String): ByteArray?
}

// google-cloud-storage's client (like google-cloud-firestore's) is blocking
// I/O - withContext(Dispatchers.IO), same reasoning as ImapGmailClient's own
// wrapper. Uses the same Application Default Credentials as firestoreClient
// (Application.kt's storageClient) - no separate credential to manage, just a
// bucket the runtime service account needs Storage Object Admin on (a
// one-time manual step, same shape as Calendar's service-account-sharing
// step - see context.md's "Email attachments" section for why this can't be
// skipped even on a fresh deploy).
class GcsAttachmentStore(private val storage: Storage, private val bucketName: String) : AttachmentRepository {
    override suspend fun upload(storagePath: String, contentType: String, bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            storage.create(BlobInfo.newBuilder(BlobId.of(bucketName, storagePath)).setContentType(contentType).build(), bytes)
        }

    override suspend fun download(storagePath: String): ByteArray? =
        withContext(Dispatchers.IO) {
            storage.get(BlobId.of(bucketName, storagePath))?.getContent()
        }
}
