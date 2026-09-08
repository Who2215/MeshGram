package com.meshchat.app.ui.audio

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns only cacheDir/[DIRECTORY_NAME]. Keep this directory OUT of generic recursive
 * cache cleaners. Use [clearUnused] instead, or release playback before clearing it.
 * A resolver transfers ownership of its returned decrypted file to this component.
 */
object AudioPreviewFiles {
    const val DIRECTORY_NAME = "inline_audio"

    private val mutex = Mutex()
    private val leases = mutableMapOf<File, Int>()

    /** Safe during playback and resolution; also removes leftovers after process death. */
    suspend fun clearUnused(context: Context): Int = withContext(Dispatchers.IO) {
        mutex.withLock { clearUnusedLocked(directory(context)) }
    }

    // The caller runs this on IO in a NonCancellable section, so a late resolver
    // result always gets a lease and is subsequently released, even after app lock.
    internal suspend fun acquire(context: Context, resolveFile: suspend () -> File?): File =
        mutex.withLock {
            val directory = directory(context)
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Cannot create audio preview directory")
            }
            clearUnusedLocked(directory)
            try {
                val file = resolveFile()?.canonicalFile ?: throw IOException("Audio unavailable")
                // Never take ownership of an attachment, export, or arbitrary caller file.
                if (file.parentFile != directory || !file.isFile || file.length() == 0L) {
                    throw IOException("Resolver must return a nonempty file in the audio preview directory")
                }
                leases[file] = (leases[file] ?: 0) + 1
                file
            } catch (error: Exception) {
                clearUnusedLocked(directory)
                throw error
            }
        }

    internal suspend fun release(file: File) = mutex.withLock {
        val remaining = (leases[file] ?: return@withLock) - 1
        if (remaining > 0) {
            leases[file] = remaining
        } else {
            leases.remove(file)
            file.delete()
        }
    }

    private fun directory(context: Context): File =
        File(context.applicationContext.cacheDir, DIRECTORY_NAME).canonicalFile

    private fun clearUnusedLocked(directory: File): Int =
        directory.listFiles()?.count { file ->
            // Direct files only; do not follow unexpected directories or symlinks.
            val canonical = file.canonicalFile
            canonical.parentFile == directory && file.isFile &&
                canonical !in leases && file.delete()
        } ?: 0
}
