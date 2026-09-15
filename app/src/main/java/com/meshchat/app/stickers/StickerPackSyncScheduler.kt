package com.meshchat.app.stickers

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.meshchat.app.BuildConfig
import com.meshchat.app.ui.MeshExpressions
import kotlin.concurrent.thread

object StickerPackSyncScheduler {
    private const val JOB_ID = 41874
    private const val CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L
    private const val STARTUP_THROTTLE_MS = 6L * 60L * 60L * 1000L
    private const val PREFS = "meshgram_sticker_pack_sync"
    private const val KEY_LAST_ATTEMPT_MS = "last_attempt_ms"

    fun schedule(context: Context) {
        if (!isConfigured()) return
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.allPendingJobs.any { it.id == JOB_ID }) return

        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, StickerPackSyncJobService::class.java)
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresDeviceIdle(true)
            .setPeriodic(CHECK_INTERVAL_MS)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setRequiresBatteryNotLow(true)
                }
            }
            .build()
        scheduler.schedule(job)
    }

    @Synchronized
    fun checkNowIfStale(context: Context) {
        if (!isConfigured()) return
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastAttempt = preferences.getLong(KEY_LAST_ATTEMPT_MS, 0L)
        if (lastAttempt in 1..now && now - lastAttempt < STARTUP_THROTTLE_MS) return

        // Record before starting so rapid activity recreation cannot create parallel downloads.
        preferences.edit().putLong(KEY_LAST_ATTEMPT_MS, now).apply()
        thread(name = "meshgram-sticker-pack-sync-now", start = true) {
            sync(appContext)
        }
    }

    @Synchronized
    internal fun sync(context: Context): StickerPackSyncResult =
        StickerPackSyncManager(context).sync(reservedIds = MeshExpressions.stickers.toSet())

    private fun isConfigured(): Boolean =
        BuildConfig.MESHGRAM_STICKER_INDEX_URL.isNotBlank() &&
            BuildConfig.MESHGRAM_RELEASE_PUBLIC_KEY_BASE64.isNotBlank()
}

class StickerPackSyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "meshgram-sticker-pack-sync", start = true) {
            runCatching { StickerPackSyncScheduler.sync(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
