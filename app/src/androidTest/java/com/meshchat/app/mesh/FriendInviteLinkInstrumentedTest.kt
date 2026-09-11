package com.meshchat.app.mesh

import junit.framework.TestCase

class FriendInviteLinkInstrumentedTest : TestCase() {
    private val code = FriendDirectory.INVITE_PREFIX + "signed-invite-payload"

    fun testNewShareLinkIsAnAppLinkAndRoundTrips() {
        val link = FriendDirectory.toShareUrl(code)
        assertEquals("meshgram", android.net.Uri.parse(link).scheme)
        assertEquals(code, FriendDirectory.normalizeInviteCode(link))
    }

    fun testLegacyWebLinksStillRoundTripWithOrWithoutTrailingSlash() {
        val link = FriendDirectory.toWebShareUrl(code)
        assertEquals(code, FriendDirectory.normalizeInviteCode(link))
        assertEquals(code, FriendDirectory.normalizeInviteCode(link.replace("/MeshGram/", "/MeshGram")))
    }

    fun testArbitraryWebLinksAreRejected() {
        try {
            FriendDirectory.normalizeInviteCode("https://example.com/?friend_invite=$code")
            fail("An unrelated web origin must not be accepted as an invitation")
        } catch (_: IllegalArgumentException) {
            // Expected: only MeshGram app links and the official web fallback are valid.
        }
    }
}
