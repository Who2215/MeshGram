package com.meshchat.app.mesh

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.meshchat.app.MainActivity
import com.meshchat.app.R

/** Legacy preferences are initial channel defaults on O+, per-notification settings on M/N. */
object MeshNotificationPreferences {
    const val PREFS_NAME = "meshgram_notification_prefs"
    const val KEY_SOUND = "sound"
    const val KEY_VIBRATION = "vibration"
    const val DEFAULT_SOUND = "default"
    const val SILENT_SOUND = "silent"
    const val NORMAL_VIBRATION = "normal"

    fun soundUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.getString(KEY_SOUND, DEFAULT_SOUND) == SILENT_SOUND) null
        else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    fun vibrationPattern(context: Context): LongArray? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return when (prefs.getString(KEY_VIBRATION, NORMAL_VIBRATION)) {
            "off" -> null
            "soft" -> longArrayOf(0L, 80L)
            "strong" -> longArrayOf(0L, 220L, 80L, 220L)
            else -> longArrayOf(0L, 140L)
        }
    }

    /** Compatibility entry point. Never delete channels or overwrite the user's channel choices. */
    fun refreshChannels(context: Context) = MeshNotifications.ensureChannels(context)
}

object MeshNotifications {
    const val CHANNEL_INCOMING = "mesh_incoming"
    const val CHANNEL_FOREGROUND = "mesh_foreground"
    private const val TEST_NOTIFICATION_ID = 7102

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_FOREGROUND) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_FOREGROUND,
                context.getString(R.string.notification_channel_network),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_network_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        }
        if (manager.getNotificationChannel(CHANNEL_INCOMING) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_INCOMING,
                context.getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_messages_description)
                setSound(MeshNotificationPreferences.soundUri(context), AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
                vibrationPattern = MeshNotificationPreferences.vibrationPattern(context)
                enableVibration(vibrationPattern != null)
                setShowBadge(true)
                // No DND bypass. Android remains responsible for ringer mode and interruption policy.
            })
        }
    }

    /** O+: real incoming-channel settings. M/N: app details with its Notifications entry. */
    fun incomingChannelSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_INCOMING)
        } else {
            appDetailsIntent(context)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Returns false if neither the channel screen nor the OEM app-details fallback can open. */
    fun openIncomingChannelSettings(context: Context): Boolean {
        ensureChannels(context)
        return runCatching { context.startActivity(incomingChannelSettingsIntent(context)); true }
            .getOrElse {
                runCatching { context.startActivity(appDetailsIntent(context)); true }.getOrDefault(false)
            }
    }

    private fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun canPost(context: Context, channelId: String = CHANNEL_INCOMING): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            val channel = manager.getNotificationChannel(channelId) ?: return false
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && channel.group != null &&
                manager.getNotificationChannelGroup(channel.group)?.isBlocked == true) return false
        }
        return true
    }

    fun isConversationMuted(context: Context, conversationId: String): Boolean =
        conversationId.isNotBlank() && SecureLocalStore(context.applicationContext)
            .loadConversationStates().any { it.conversationId == conversationId && it.isMuted }

    /** O+ uses channel settings exclusively; no media player/vibrator or DND override. */
    fun applyIncomingAlertSettings(context: Context, builder: NotificationCompat.Builder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setDefaults(0)
                .setSound(MeshNotificationPreferences.soundUri(context))
                .setVibrate(if (ContextCompat.checkSelfPermission(context, Manifest.permission.VIBRATE) ==
                    PackageManager.PERMISSION_GRANTED) MeshNotificationPreferences.vibrationPattern(context)
                else null)
        }
    }

    enum class TestNotificationResult { POSTED, NOTIFICATIONS_DISABLED, CONVERSATION_MUTED, CONVERSATION_VISIBLE, FAILED }

    /** POSTED means submitted, not necessarily audible (DND/channel/ringer settings still apply).
     * Pass a conversation ID to test that conversation's mute/visibility policy, or null for a global test.
     * Does not request notification permission; the UI owns that request.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun postTestNotification(context: Context, conversationId: String? = null): TestNotificationResult {
        ensureChannels(context)
        if (!canPost(context)) return TestNotificationResult.NOTIFICATIONS_DISABLED
        if (conversationId != null) {
            if (isConversationMuted(context, conversationId)) return TestNotificationResult.CONVERSATION_MUTED
            if (MeshNotificationVisibility.isConversationVisible(conversationId)) {
                return TestNotificationResult.CONVERSATION_VISIBLE
            }
        }
        val launch = PendingIntent.getActivity(context, TEST_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_meshgram_notification)
            .setContentTitle(context.getString(R.string.notification_test_title))
            .setContentText(context.getString(R.string.notification_test_body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(launch)
        applyIncomingAlertSettings(context, builder)
        return runCatching {
            NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, builder.build())
            TestNotificationResult.POSTED
        }.getOrDefault(TestNotificationResult.FAILED)
    }
}
