package com.meshchat.app.mesh

internal fun mergeFileAcknowledgements(
    previous: Set<Int>,
    received: List<Int>,
    chunkCount: Int,
    retryMissing: Boolean,
    complete: Boolean
): Set<Int> {
    require(chunkCount > 0)
    val validPrevious = previous.filterTo(mutableSetOf()) { it in 0 until chunkCount }
    if (!retryMissing && (complete || validPrevious.size == chunkCount)) {
        return (0 until chunkCount).toSet()
    }
    val merged = if (retryMissing) mutableSetOf() else validPrevious
    received.filterTo(merged) { it in 0 until chunkCount }
    // Partial snapshots from different receiver sessions cannot prove durable completion.
    // Keep one probe pending until an explicit final receipt arrives; no wire/schema change.
    if (merged.size == chunkCount) merged.remove(chunkCount - 1)
    return merged
}
