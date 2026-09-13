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
    testImplementation("io.ktor:ktor-server-tests-jvm")
    testImplementation("io.ktor:ktor-client-mock-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    // In-process fake SMTP/IMAP server so ImapGmailClientTest exercises real
    // IMAP protocol handling, not just a hand-rolled fake - MockEngine (used
    // for the Gmail REST/OAuth-era tests) doesn't apply to IMAP.
    testImplementation("com.icegreen:greenmail:2.0.1")
}
