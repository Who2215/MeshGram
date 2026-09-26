package com.meshchat.app.mesh

import org.junit.Assert.*
import org.junit.Test

class FileTransferAcknowledgementsTest {
    @Test fun accumulatedPartialSnapshotsKeepAProbePending() {
        val first = mergeFileAcknowledgements(emptySet(), listOf(0, 1), 3, false, false)
        val second = mergeFileAcknowledgements(first, listOf(1, 2), 3, false, false)
        assertEquals(setOf(0, 1), second)
        assertEquals(listOf(2), (0 until 3).filterNot { it in second })
        assertEquals(second, mergeFileAcknowledgements(second, listOf(2), 3, false, false))
    }

    @Test fun explicitFinalReceiptCompletesAllChunks() {
        assertEquals(setOf(0, 1, 2), mergeFileAcknowledgements(emptySet(), emptyList(), 3, false, true))
    }

    @Test fun latePartialReceiptDoesNotUndoCompletedRecipient() {
        assertEquals(setOf(0, 1, 2), mergeFileAcknowledgements(setOf(0, 1, 2), listOf(0), 3, false, false))
    }

    @Test fun retryDiscardsPriorSnapshotAndOverridesCompleteFlag() {
        assertEquals(setOf(1), mergeFileAcknowledgements(setOf(0, 1, 2), listOf(1), 3, true, true))
        assertEquals(setOf(0, 1), mergeFileAcknowledgements(setOf(0, 1, 2), listOf(0, 1, 2), 3, true, false))
    }

    @Test fun singleChunkAlsoRequiresFinalReceipt() {
        assertTrue(mergeFileAcknowledgements(emptySet(), listOf(0), 1, false, false).isEmpty())
        assertEquals(setOf(0), mergeFileAcknowledgements(emptySet(), emptyList(), 1, false, true))
    }

    @Test fun invalidIndexesCannotCompleteTransfer() {
        assertEquals(setOf(0), mergeFileAcknowledgements(setOf(-1, 6), listOf(0, 0, 3, 9), 3, false, false))
    }
}
