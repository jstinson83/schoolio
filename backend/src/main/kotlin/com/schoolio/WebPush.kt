package com.schoolio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.PushService
import nl.martijndwars.webpush.Utils
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.interfaces.ECPrivateKey
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyPairGenerator
import java.security.Security
import java.time.Duration
import java.util.Base64

// Web Push (RFC 8291 message encryption + RFC 8292 VAPID) via
// nl.martijndwars:web-push rather than hand-rolled - unlike Gmail/Gemini/
// Calendar, there's no "just shape a JSON body" REST API here, this
// literally is a client-side crypto protocol (ECDH + HKDF + AES-128-GCM), so
// a real library beats re-deriving it by hand (an earlier pass in this repo
// did exactly that, verified only by a local encrypt/decrypt round-trip
// since this environment can't reach RFC 8291's published test vector or a
// live push service to check against - not worth the residual risk once a
// maintained implementation was available). Requires BouncyCastle
// registered as a JCE provider (registerBouncyCastle below) - the library's
// own EC key handling (org.bouncycastle.jce.interfaces.ECPublicKey) is
// BC-specific, not pure JDK.
private val bouncyCastleRegistered: Boolean by lazy {
    Security.addProvider(BouncyCastleProvider())
    true
}

// A VAPID key pair (RFC 8292) identifies this server to push services (so
// they can rate-limit/contact it) - one pair for the whole deployment, not
// per-subscriber. Both halves are base64url-encoded raw EC key material
// (Utils.encode's own format) - the public half is also what the browser
// passes as PushManager.subscribe()'s applicationServerKey, so it has to be
// exactly this format for the browser to accept it.
data class VapidKeyPair(val publicKey: String, val privateKey: String)

fun generateVapidKeyPair(): VapidKeyPair {
    check(bouncyCastleRegistered)
    val generator = KeyPairGenerator.getInstance("EC", "BC")
    generator.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"))
    val pair = generator.generateKeyPair()
    return VapidKeyPair(
        publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(Utils.encode(pair.public as ECPublicKey)),
        privateKey = Base64.getUrlEncoder().withoutPadding().encodeToString(Utils.encode(pair.private as ECPrivateKey))
    )
}

// What a signed-in user's browser hands back from
// PushSubscription.toJSON() (POST /push/subscribe's request body) - endpoint
// is the push service URL to send to, p256dh/auth are that subscription's
// own EC public key and auth secret (RFC 8291), both base64url. Flattened
// (no nested "keys" object) once stored on User - same "as plain as the wire
// shape allows" treatment as gmailAppPassword.
@Serializable
data class PushSubscriptionKeys(val p256dh: String, val auth: String)

@Serializable
data class PushSubscriptionRequest(val endpoint: String, val keys: PushSubscriptionKeys)

data class PushSubscription(val endpoint: String, val p256dh: String, val auth: String)

sealed interface PushSendResult {
    data object Sent : PushSendResult
    // 404/410 - the push service has permanently discarded this subscription
    // (the user uninstalled the PWA, cleared site data, or the browser
    // itself revoked it) - the caller should stop trying to use it, not just
    // log and retry next time.
    data object Gone : PushSendResult
    data class Failed(val status: Int) : PushSendResult
}

interface WebPushSender {
    suspend fun send(subscription: PushSubscription, title: String, body: String, url: String): PushSendResult
}

// One PushService per deployment (holds the VAPID key pair + subject), same
// "one client, reused across calls" shape as GmailClient/CalendarClient.
class LibraryWebPushSender(
    vapidPublicKey: String,
    vapidPrivateKey: String,
    vapidSubject: String
) : WebPushSender {
    init { check(bouncyCastleRegistered) }

    private val pushService = PushService(vapidPublicKey, vapidPrivateKey, vapidSubject)

    // PushService.send is Apache HttpClient's blocking call, not a suspend
    // function - withContext(Dispatchers.IO), same as ImapGmailClient's own
    // blocking Jakarta Mail calls, so it doesn't tie up whatever dispatcher
    // the caller is on.
    override suspend fun send(subscription: PushSubscription, title: String, body: String, url: String): PushSendResult =
        withContext(Dispatchers.IO) {
            val payload = """{"title":${jsonString(title)},"body":${jsonString(body)},"url":${jsonString(url)}}"""
            val notification = Notification.builder()
                .endpoint(subscription.endpoint)
                .userPublicKey(subscription.p256dh)
                .userAuth(subscription.auth)
                .payload(payload)
                .ttl(Duration.ofHours(24).seconds.toInt())
                .build()
            val response = pushService.send(notification)
            when (response.statusLine.statusCode) {
                200, 201, 202 -> PushSendResult.Sent
                404, 410 -> PushSendResult.Gone
                else -> PushSendResult.Failed(response.statusLine.statusCode)
            }
        }
}

// [title]/[body]/[url] are always plain server-authored strings (no user
// input reaches a push payload), so this only needs to escape the two JSON
// metacharacters that could actually appear in them (a quote, a backslash) -
// not a general-purpose JSON encoder.
private fun jsonString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
