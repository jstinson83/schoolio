package com.schoolio

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.*

// Exercises ImapGmailClient's actual IMAP protocol handling against GreenMail
// (an in-process fake SMTP/IMAP server), not a hand-rolled fake - MockEngine
// (used for the old Gmail REST/OAuth-era client) doesn't apply to IMAP.
// Plain "imap" (not "imaps") against GreenMail's test server, deliberately -
// GreenMail's IMAPS uses a self-signed cert Jakarta Mail won't trust by
// default, and that's not what this test is verifying; ImapGmailClient
// defaults to "imaps" for the real Gmail connection, "imap" is just how
// these tests point it at GreenMail instead.
class ImapGmailClientTest {
    private fun startGreenMail(): GreenMail {
        val greenMail = GreenMail(arrayOf(ServerSetupTest.SMTP, ServerSetupTest.IMAP))
        greenMail.start()
        greenMail.setUser(TEST_EMAIL, TEST_EMAIL, "app-password")
        return greenMail
    }

    private fun clientFor(greenMail: GreenMail): ImapGmailClient =
        ImapGmailClient(host = "localhost", port = greenMail.imap.serverSetup.port, protocol = "imap")

    private fun fourWeeksAgo(): Instant = Instant.now().minus(Duration.ofDays(28))

    @Test
    fun testSearchMessagesFindsMatchingSenderAndPlainTextBody() = runBlocking {
        val greenMail = startGreenMail()
        try {
            GreenMailUtil.sendTextEmail(
                TEST_EMAIL, "Ms. Rivera <$TEST_SENDER>", "Field trip permission slip",
                "Please sign and return by Friday.", greenMail.smtp.serverSetup
            )
            greenMail.waitForIncomingEmail(1)

            val messages = clientFor(greenMail).searchMessages(TEST_EMAIL, "app-password", listOf(TEST_SENDER), since = fourWeeksAgo())

            assertEquals(1, messages.size)
            assertEquals("Field trip permission slip", messages[0].subject)
            assertTrue(messages[0].from.contains(TEST_SENDER))
            assertEquals("Please sign and return by Friday.", messages[0].bodyText.trim())
        } finally {
            greenMail.stop()
        }
    }

    @Test
    fun testSearchMessagesExcludesMessagesFromOtherSenders() = runBlocking {
        val greenMail = startGreenMail()
        try {
            GreenMailUtil.sendTextEmail(TEST_EMAIL, TEST_SENDER, "From the teacher", "Relevant", greenMail.smtp.serverSetup)
            GreenMailUtil.sendTextEmail(TEST_EMAIL, "newsletter@random.example", "Unrelated newsletter", "Not school", greenMail.smtp.serverSetup)
            greenMail.waitForIncomingEmail(2)

            val messages = clientFor(greenMail).searchMessages(TEST_EMAIL, "app-password", listOf(TEST_SENDER), since = fourWeeksAgo())

            assertEquals(1, messages.size)
            assertEquals("From the teacher", messages[0].subject)
        } finally {
            greenMail.stop()
        }
    }

    @Test
    fun testSearchMessagesReturnsEmptyWhenMailboxHasNoMatches() = runBlocking {
        val greenMail = startGreenMail()
        try {
            val messages = clientFor(greenMail).searchMessages(TEST_EMAIL, "app-password", listOf(TEST_SENDER), since = fourWeeksAgo())
            assertEquals(emptyList(), messages)
        } finally {
            greenMail.stop()
        }
    }
}
