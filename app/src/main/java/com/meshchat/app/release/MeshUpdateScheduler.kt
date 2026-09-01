package com.meshchat.app.release

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.meshchat.app.BuildConfig
import com.meshchat.app.MainActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object MeshUpdateScheduler {
    private const val JOB_ID = 41873
    private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
    const val ACTION_INSTALL_UPDATE = "com.meshchat.app.action.INSTALL_UPDATE"
    const val EXTRA_UPDATE_APK_PATH = "com.meshchat.app.extra.UPDATE_APK_PATH"
    const val UPDATE_DIRECTORY = "meshgram-updates"

    fun schedule(context: Context) {
        if (!isConfigured()) return
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.allPendingJobs.any { it.id == JOB_ID }) return

        val jobBuilder = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, MeshUpdateJobService::class.java)
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresDeviceIdle(true)
            .setPeriodic(CHECK_INTERVAL_MS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            jobBuilder.setRequiresBatteryNotLow(true)
        }
        scheduler.schedule(jobBuilder.build())
    }

    fun checkNow(context: Context) {
        if (!isConfigured()) return
        val appContext = context.applicationContext
        thread(name = "meshgram-update-check-now", start = true) {
            runCatching { MeshUpdateManager(appContext).checkAndDownload() }
                .getOrNull()
                ?.let { update ->
                    MeshUpdateNotifier(appContext).notifyReady(update)
                }
        }
    }

    private fun isConfigured(): Boolean {
        return BuildConfig.MESHGRAM_UPDATE_MANIFEST_URL.isNotBlank() &&
            BuildConfig.MESHGRAM_RELEASE_PUBLIC_KEY_BASE64.isNotBlank()
    }
}

class MeshUpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "meshgram-update-check", start = true) {
            runCatching { MeshUpdateManager(applicationContext).checkAndDownload() }
                .getOrNull()
                ?.let { packageInfo ->
                    MeshUpdateNotifier(applicationContext).notifyReady(packageInfo)
                }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

private class MeshUpdateManager(private val context: Context) {
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun checkAndDownload(): DownloadedUpdate? {
        val manifestUrl = BuildConfig.MESHGRAM_UPDATE_MANIFEST_URL.trim()
        val trustedKey = BuildConfig.MESHGRAM_RELEASE_PUBLIC_KEY_BASE64.trim()
        if (!isHttpsUrl(manifestUrl) || trustedKey.isBlank()) return null

        val rawManifest = fetchText(manifestUrl, MAX_MANIFEST_BYTES) ?: return null
        val manifest = MeshReleaseVerifier.parseManifest(rawManifest) ?: return null
        if (!MeshReleaseVerifier.validateManifest(manifest, context.packageName)) return null
        if (!MeshReleaseVerifier.verifyManifestSignature(manifest, trustedKey)) return null
        if (manifest.versionCode <= currentVersionCode()) return null

        val updateDirectory = File(context.cacheDir, MeshUpdateScheduler.UPDATE_DIRECTORY)
        if (!updateDirectory.mkdirs() && !updateDirectory.isDirectory) return null
        val apk = File(updateDirectory, "meshgram-${manifest.versionCode}.apk")
        if (!isVerifiedApk(apk, manifest)) {
            apk.delete()
            if (!downloadApk(manifest.apkUrl, apk)) {
                apk.delete()
                return null
            }
        }
        if (!isVerifiedApk(apk, manifest)) {
            apk.delete()
            return null
        }

        pendingPreferences().edit()
            .putString(KEY_PENDING_PATH, apk.absolutePath)
            .putInt(KEY_PENDING_VERSION, manifest.versionCode)
            .putString(KEY_PENDING_NAME, manifest.versionName)
            .putString(KEY_PENDING_CHANGELOG, manifest.changelog.joinToString("\n"))
            .apply()
        return DownloadedUpdate(manifest, apk)
    }

    private fun currentVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun isVerifiedApk(apk: File, manifest: MeshReleaseManifest): Boolean {
        return MeshReleaseVerifier.verifyApkSha256(apk, manifest.apkSha256) &&
            MeshReleaseVerifier.verifyApkPackageName(context, apk, context.packageName) &&
            MeshReleaseVerifier.verifyApkSigningCertificate(
                context,
                apk,
                manifest.signingCertificateSha256
            )
    }

    private fun fetchText(url: String, maxBytes: Int): String? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            executeHttpsRedirects(request)?.use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                readBounded(body.byteStream(), maxBytes)?.toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private fun downloadApk(url: String, destination: File): Boolean {
        return runCatching {
            val request = Request.Builder().url(url).build()
            executeHttpsRedirects(request)?.use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                if (body.contentLength() > MAX_APK_BYTES) return false

                val partial = File(destination.parentFile, "${destination.name}.part")
                partial.delete()
                val copied = copyBounded(body.byteStream(), partial, MAX_APK_BYTES)
                if (!copied || !partial.renameTo(destination)) {
                    partial.delete()
                    return false
                }
                true
            }
        }.getOrDefault(false) == true
    }

    private fun executeHttpsRedirects(request: Request): okhttp3.Response? {
        var nextRequest = request
        repeat(MAX_REDIRECTS + 1) { attempt ->
            val response = httpClient.newCall(nextRequest).execute()
            if (!response.isRedirect) return response
            if (attempt >= MAX_REDIRECTS) {
                response.close()
                return null
            }
            val location = response.header("Location")
            val nextUrl = location?.let { nextRequest.url.resolve(it) }
            response.close()
            if (nextUrl == null || !isHttpsUrl(nextUrl.toString())) return null
            nextRequest = nextRequest.newBuilder().url(nextUrl).build()
        }
        return null
    }

    private fun pendingPreferences() = context.getSharedPreferences(
        PENDING_PREFS,
        Context.MODE_PRIVATE
    )

    private fun isHttpsUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            !uri.host.isNullOrBlank()
    }

    companion object {
        private const val PENDING_PREFS = "meshgram_pending_update"
        private const val KEY_PENDING_PATH = "path"
        private const val KEY_PENDING_VERSION = "version"
        private const val KEY_PENDING_NAME = "name"
        private const val KEY_PENDING_CHANGELOG = "changelog"
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_APK_BYTES = 128L * 1024L * 1024L
        private const val MAX_REDIRECTS = 3

        private fun readBounded(input: InputStream, maxBytes: Int): ByteArray? {
            return input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }

        private fun copyBounded(input: InputStream, destination: File, maxBytes: Long): Boolean {
            return input.use { stream ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) return false
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                    true
                }
            }
        }
    }
}

private data class DownloadedUpdate(
    val manifest: MeshReleaseManifest,
    val apk: File
)

private class MeshUpdateNotifier(private val context: Context) {
    fun notifyReady(update: DownloadedUpdate) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        val preferences = context.getSharedPreferences(
            "meshgram_pending_update",
            Context.MODE_PRIVATE
        )
        if (preferences.getInt(KEY_NOTIFIED_VERSION, -1) >= update.manifest.versionCode) return

        ensureChannel()
        val installIntent = Intent(context, MainActivity::class.java).apply {
            action = MeshUpdateScheduler.ACTION_INSTALL_UPDATE
            putExtra(MeshUpdateScheduler.EXTRA_UPDATE_APK_PATH, update.apk.absolutePath)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            update.manifest.versionCode,
            installIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val changelog = update.manifest.changelog.firstOrNull().orEmpty()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.meshchat.app.R.drawable.ic_meshgram_notification)
            .setContentTitle("MeshGram ${update.manifest.versionName}")
            .setContentText(changelog.ifBlank { "Update downloaded and ready to install" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(changelog))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        @Suppress("MissingPermission")
        val posted = runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID, notification)
        }.isSuccess
        if (posted) {
            preferences.edit()
                .putInt(KEY_NOTIFIED_VERSION, update.manifest.versionCode)
                .apply()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "MeshGram updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Verified MeshGram release updates"
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "mesh_updates"
        private const val NOTIFICATION_ID = 7301
        private const val KEY_NOTIFIED_VERSION = "notified_version"
    }
}

object MeshUpdateInstaller {
    fun openIfRequested(activity: Activity, intent: Intent?): Boolean {
        val updateIntent = intent ?: return false
        if (updateIntent.action != MeshUpdateScheduler.ACTION_INSTALL_UPDATE) return false
        val rawPath = updateIntent.getStringExtra(MeshUpdateScheduler.EXTRA_UPDATE_APK_PATH)
            ?.trim()
            .orEmpty()
        if (rawPath.isBlank()) return false

        return runCatching {
            val updatesRoot = File(
                activity.cacheDir,
                MeshUpdateScheduler.UPDATE_DIRECTORY
            ).canonicalFile
            val apk = File(rawPath).canonicalFile
            val rootPath = updatesRoot.path + File.separator
            if (!apk.path.startsWith(rootPath) || !apk.isFile) return false

            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apk
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(installIntent)
            true
        }.getOrDefault(false)
    }
}
