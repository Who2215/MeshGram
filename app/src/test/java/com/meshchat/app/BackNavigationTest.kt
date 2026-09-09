package com.meshchat.app

import com.meshchat.app.ui.BackDestination
import com.meshchat.app.ui.backDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class BackNavigationTest {
    @Test fun rootTabsReturnToMapButNestedScreensKeepPriority() {
        assertEquals(BackDestination.TAB, backDestination(false, false, false, false, false, false, false, false, true))
        assertEquals(BackDestination.CHAT, backDestination(false, false, false, false, false, false, false, true, true))
        assertEquals(BackDestination.PROFILE, backDestination(false, false, false, false, false, false, true, false, true))
    }
    @Test fun nestedMediaClosesBeforeChat() {
        assertEquals(BackDestination.MEDIA, backDestination(true, true, true, true, true, true, false, true))
    }
    @Test fun selectionDoesNotExitChat() {
        assertEquals(BackDestination.SELECTION, backDestination(false, false, false, true, false, false, false, true))
    }
    @Test fun onlyRootDelegatesToSystem() {
        assertEquals(BackDestination.SYSTEM, backDestination(false, false, false, false, false, false, false, false))
        assertEquals(BackDestination.CHAT, backDestination(false, false, false, false, false, false, false, true))
        assertEquals(BackDestination.PROFILE, backDestination(false, false, false, false, false, false, true, false))
    }
}
