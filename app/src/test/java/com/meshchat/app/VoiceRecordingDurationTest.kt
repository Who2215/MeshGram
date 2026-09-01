package com.meshchat.app

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceRecordingDurationTest {
    @Test
    fun runningRecordingSubtractsCompletedPauses() {
        assertEquals(
            7_000L,
            calculateVoiceRecordingElapsedMs(
                startedAtMs = 1_000L,
                accumulatedPausedMs = 2_000L,
                pausedAtMs = null,
                nowMs = 10_000L
            )
        )
    }

    @Test
    fun pausedRecordingFreezesAtPauseInstant() {
        assertEquals(
            4_000L,
            calculateVoiceRecordingElapsedMs(
                startedAtMs = 1_000L,
                accumulatedPausedMs = 1_000L,
                pausedAtMs = 6_000L,
                nowMs = 15_000L
            )
        )
    }

    @Test
    fun elapsedDurationNeverBecomesNegative() {
        assertEquals(
            0L,
            calculateVoiceRecordingElapsedMs(
                startedAtMs = 5_000L,
                accumulatedPausedMs = 2_000L,
                pausedAtMs = null,
                nowMs = 4_000L
            )
        )
    }
}
