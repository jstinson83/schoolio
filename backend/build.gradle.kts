plugins {
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
    id("io.ktor.plugin") version "2.3.9"
    application
}

group = "com.schoolio"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("com.schoolio.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-freemarker-jvm")
    implementation("io.ktor:ktor-server-auth-jvm")
    implementation("io.ktor:ktor-server-sessions-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("io.ktor:ktor-client-core-jvm")
    implementation("io.ktor:ktor-client-cio-jvm")
    implementation("io.ktor:ktor-client-content-negotiation-jvm")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("com.google.cloud:google-cloud-firestore:3.31.0")
    // IMAP client for ImapGmailClient - Gmail access now goes through IMAP +
    // per-user app passwords, not the Gmail REST API/OAuth (see GmailClient.kt).
    implementation("com.sun.mail:jakarta.mail:2.0.1")
    // Service-account credential loading/token minting for GoogleCalendarApiClient
    // (see CalendarClient.kt) - already resolved transitively via
    // google-cloud-firestore above at this exact version, declared explicitly
    // here since our own code imports it directly. Calendar access goes
    // through a shared service account, not a per-user OAuth flow or app
    // password - Google's CalDAV endpoint (the app-password approach tried
    // first) rejects Basic Auth outright, see context.md's Calendar pull
    // section. The actual Calendar API v3 call itself is still a plain REST
    // request via the existing Ktor HttpClient - this library is only for
    // minting the bearer token.
    implementation("com.google.auth:google-auth-library-oauth2-http:1.33.1")
    // Backs AttachmentStore.kt's GcsAttachmentStore - email attachments (PDFs/
    // images pulled off a school email, see GmailClient.kt's EmailAttachment)
    // are stored here rather than in Firestore: Firestore documents cap out at
    // 1MiB, a bad fit for binary blobs, and this is the same GCP project
    // Firestore already lives in. Uses the same ADC-based auth as
    // firestoreClient (Application.kt's storageClient) - no separate
    // credentials to manage, just a bucket the runtime service account needs
    // Storage Object Admin on (one-time manual step, same shape as Calendar's
    // service-account-sharing step - see context.md's "Email attachments"
    // section).
    implementation("com.google.cloud:google-cloud-storage:2.73.0")
    // Word-document (.docx) text extraction for the photo-import FAB's
    // "Choose a file" option (InboxRoutes.kt's extractDocxText) - Gemini's
    // generateContent doesn't accept docx as inlineData the way it does
    // images/PDFs, so the text has to be pulled out locally first and sent
    // as a plain-text prompt instead (see GeminiClient.kt's
    // extractCalendarEventsFromText).
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    // Web Push (RFC 8291 message encryption + RFC 8292 VAPID) for the daily
    // digest notification (WebPush.kt) - unlike Gmail/Gemini/Calendar, this
    // isn't a REST API with a JSON body to shape by hand, it's a real
    // client-side crypto protocol (ECDH + HKDF + AES-128-GCM), so it's the
    // one integration in this app that uses a library instead of a plain
    // HttpClient call. Pulls in BouncyCastle as a transitive dependency -
    // this library's own EC key handling is BC-specific, not pure JDK, which
    // is why WebPush.kt registers BouncyCastleProvider before using it.
    implementation("nl.martijndwars:web-push:5.1.2")
    // web-push's own POM marks this as an optional dependency, so Gradle
    // won't pull it in transitively even though the library requires it at
    // both compile and runtime (WebPush.kt references
    // org.bouncycastle.jce.interfaces.EC*PublicKey/PrivateKey directly) -
    // has to be declared here explicitly. Version pinned to match what
    // web-push:5.1.2's POM itself depends on.
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    testImplementation("io.ktor:ktor-server-tests-jvm")
    testImplementation("io.ktor:ktor-client-mock-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    // In-process fake SMTP/IMAP server so ImapGmailClientTest exercises real
    // IMAP protocol handling, not just a hand-rolled fake - MockEngine (used
    // for the Gmail REST/OAuth-era tests) doesn't apply to IMAP.
    testImplementation("com.icegreen:greenmail:2.0.1")
}
