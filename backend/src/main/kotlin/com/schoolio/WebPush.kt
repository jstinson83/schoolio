package com.schoolio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import nl.martijndwars.webpush.Encoding
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.PushService
import nl.martijndwars.webpush.Utils
import org.apache.http.util.EntityUtils
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.interfaces.ECPrivateKey
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.slf4j.LoggerFactory
import java.security.KeyPairGenerator
import java.security.Security
import java.time.Duration
import java.util.Base64

private val logger = LoggerFactory.getLogger("WebPush")

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
    // body carries the push service's own rejection text when it has one
    // (FCM/autopush both return a short explanation on 4xx, e.g. an invalid
    // or mismatched VAPID key) - genuinely the only way to tell "wrong
    // audience" apart from "expired credentials" apart from "malformed
    // request" from the status code alone.
    data class Failed(val status: Int, val body: String? = null) : PushSendResult
}

interface WebPushSender {
    suspend fun send(subscription: PushSubscription, title: String, body: String, url: String): PushSendResult
}

// One PushService per deployment (holds the VAPID key pair + subject), same
// "one client, reused across calls" shape as GmailClient/CalendarClient.
class LibraryWebPushSender(
    private val vapidPublicKey: String,
    vapidPrivateKey: String,
    vapidSubject: String
) : WebPushSender {
    init {
        check(bouncyCastleRegistered)
        // A correctly-generated VAPID key pair (generateVapidKeyPair's own
        // format - a 65-byte uncompressed EC point / a 32-byte scalar, both
        // base64url without padding) is always exactly 87 / 43 characters.
        // A key that's been truncated (a copy-paste mishap setting the env
        // var, a shell quoting issue) is otherwise invisible until a send
        // fails against a live push service with a cryptic rejection - log
        // this once at startup so a wrong length is immediately checkable
        // without waiting for that failure.
        if (vapidPublicKey.length != 87) {
            logger.warn("VAPID_PUBLIC_KEY is {} characters, expected 87 - likely truncated or malformed", vapidPublicKey.length)
        }
        if (vapidPrivateKey.length != 43) {
            logger.warn("VAPID_PRIVATE_KEY is {} characters, expected 43 - likely truncated or malformed", vapidPrivateKey.length)
        }
    }

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
            // A prior diagnostic pass confirmed the aes128gcm request this
            // library builds is exactly correct per RFC 8291/8292 - right
            // Content-Encoding, an Authorization "k=" that exactly matches
            // the configured VAPID_PUBLIC_KEY at the expected length - and
            // FCM still 403ed with "crypto-key header had invalid format...
            // p256ecdsa=base64(...)". That's the literal shape of the
            // *older* aesgcm encoding's Crypto-Key header, so rather than
            // keep assuming that wording is just stale, this takes it at
            // face value and sends aesgcm explicitly instead of the
            // library's aes128gcm default - the library supports both
            // (Encoding.AESGCM/AES128GCM), and this is the cheapest way to
            // find out whether FCM genuinely wants the older shape here.
            logger.info(
                "Daily digest diagnostic: sending with Encoding.AESGCM instead of the default AES128GCM (see WebPush.kt's send() comment)"
            )
            val response = pushService.send(notification, Encoding.AESGCM)
            when (val status = response.statusLine.statusCode) {
                200, 201, 202 -> PushSendResult.Sent
                404, 410 -> PushSendResult.Gone
                else -> {
                    // The entity can only be consumed once and only while
                    // the response is still open - read it now rather than
                    // handing the raw HttpResponse back to the caller.
                    val bodyText = runCatching { response.entity?.let { EntityUtils.toString(it) } }.getOrNull()
                    PushSendResult.Failed(status, bodyText)
                }
            }
        }
}

// [title]/[body]/[url] are always plain server-authored strings (no user
// input reaches a push payload), so this only needs to escape the two JSON
// metacharacters that could actually appear in them (a quote, a backslash) -
// not a general-purpose JSON encoder.
private fun jsonString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
