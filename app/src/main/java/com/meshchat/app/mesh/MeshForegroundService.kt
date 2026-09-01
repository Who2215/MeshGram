package com.meshchat.app.mesh

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.meshchat.app.EXTRA_OPEN_CONVERSATION_ID
import com.meshchat.app.MainActivity
import com.meshchat.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max

object MeshNotificationPreferences {
    const val PREFS_NAME = "meshgram_notification_prefs"
    const val KEY_SOUND = "sound"
    const val KEY_VIBRATION = "vibration"
    const val DEFAULT_SOUND = "default"
    const val SILENT_SOUND = "silent"
    const val NORMAL_VIBRATION = "normal"

    fun soundUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.getString(KEY_SOUND, DEFAULT_SOUND) == SILENT_SOUND) {
            null
        } else {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
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

    fun refreshChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel("mesh_incoming")
        val incoming = NotificationChannel(
            "mesh_incoming",
            "Mesh Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming secure mesh messages"
            setSound(
                soundUri(context),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
            )
            vibrationPattern = vibrationPattern(context)
            enableVibration(vibrationPattern != null)
            setShowBadge(true)
        }
        manager.createNotificationChannel(incoming)
    }
}

class MeshForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val knownIncomingMessageIds = linkedSetOf<String>()
    private val meshManager by lazy { MeshRuntime.manager(applicationContext) }
    private var messagesObserverJob: Job? = null
    private var incomingNotificationId = INCOMING_NOTIFICATION_BASE_ID

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())
        knownIncomingMessageIds += meshManager.messages.value
            .filter { !it.isLocal }
            .map { it.id }
            .takeLast(MAX_KNOWN_MESSAGE_IDS)
        observeIncomingMessages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            meshManager.stop("Mesh service stopped")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_MARK_READ) {
            val conversationId = intent.getStringExtra(EXTRA_MARK_READ_CONVERSATION_ID)
                ?.trim()
                .orEmpty()
            if (conversationId.isNotBlank()) {
                markConversationRead(conversationId)
            }
            return START_NOT_STICKY
        }

        meshManager.start()
        postNotificationSafely(
            FOREGROUND_NOTIFICATION_ID,
            buildForegroundNotification()
        )
        return START_STICKY
    }

    override fun onDestroy() {
        messagesObserverJob?.cancel()
        messagesObserverJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeIncomingMessages() {
        messagesObserverJob?.cancel()
        messagesObserverJob = serviceScope.launch {
            meshManager.messages.collectLatest { messages ->
                val incoming = messages
                    .filter { message ->
                        !message.isLocal &&
                            !message.isDeleted &&
                            message.id.isNotBlank() &&
                            !knownIncomingMessageIds.contains(message.id)
                    }
                    .sortedBy { it.createdAtMs }

                if (incoming.isNotEmpty()) {
                    incoming.forEach { message ->
                        knownIncomingMessageIds += message.id
                        trimKnownIncomingMessageIds()
                        if (!isNotificationMutedForConversation(message.conversationId)) {
                            postIncomingNotification(message)
                        }
                    }
                }
            }
        }
    }

    private fun trimKnownIncomingMessageIds() {
        while (knownIncomingMessageIds.size > MAX_KNOWN_MESSAGE_IDS) {
            val first = knownIncomingMessageIds.firstOrNull() ?: break
            knownIncomingMessageIds.remove(first)
        }
    }

    private fun isNotificationMutedForConversation(conversationId: String): Boolean {
        if (conversationId.isBlank()) return false
        val states = SecureLocalStore(applicationContext).loadConversationStates()
        return states.firstOrNull { it.conversationId == conversationId }?.isMuted == true
    }

    private fun postIncomingNotification(message: ChatMessage) {
        if (!canPostNotifications()) return
        val conversationTitle = message.conversationTitle
            ?.trim()
            ?.ifBlank { null }
            ?: message.senderAlias
            ?.trim()
            ?.ifBlank { null }
            ?: "Mesh chat"
        val contentText = if (message.contentType == ChatContentType.FILE) {
            val fileName = message.attachment?.fileName ?: message.text.ifBlank { "File" }
            "File: $fileName"
        } else {
            message.text.trim().ifBlank { "New message" }
        }
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = "meshgram.open.chat.${message.conversationId}.${message.id}"
            putExtra(EXTRA_OPEN_CONVERSATION_ID, message.conversationId)
        }
        val launchPendingIntent = PendingIntent.getActivity(
            this,
            "${message.conversationId}:${message.id}".hashCode(),
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val markReadIntent = Intent(this, MeshForegroundService::class.java).apply {
            action = ACTION_MARK_READ
            putExtra(EXTRA_MARK_READ_CONVERSATION_ID, message.conversationId)
        }
        val markReadPendingIntent = PendingIntent.getService(
            this,
            "mark_read_${message.conversationId}".hashCode(),
            markReadIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_meshgram_notification)
            .setContentTitle(conversationTitle)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSound(MeshNotificationPreferences.soundUri(this))
            .setVibrate(MeshNotificationPreferences.vibrationPattern(this))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setGroup("meshgram_chat_${message.conversationId}")
            .setContentIntent(launchPendingIntent)
            .addAction(0, "Mark read", markReadPendingIntent)
            .build()

        incomingNotificationId = max(
            INCOMING_NOTIFICATION_BASE_ID,
            (incomingNotificationId + 1).coerceAtMost(INCOMING_NOTIFICATION_MAX_ID)
        )
        postNotificationSafely(incomingNotificationId, notification)
        if (incomingNotificationId >= INCOMING_NOTIFICATION_MAX_ID) {
            incomingNotificationId = INCOMING_NOTIFICATION_BASE_ID
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun postNotificationSafely(id: Int, notification: Notification) {
        if (!canPostNotifications()) return
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val launchPendingIntent = PendingIntent.getActivity(
            this,
            10,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, MeshForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            11,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val status = if (meshManager.isRunning.value) {
            "Mesh routing active"
        } else {
            "Starting mesh network"
        }
        return NotificationCompat.Builder(this, CHANNEL_FOREGROUND)
            .setSmallIcon(R.drawable.ic_meshgram_notification)
            .setContentTitle("MeshGram network")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(launchPendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val foregroundChannel = NotificationChannel(
            CHANNEL_FOREGROUND,
            "Mesh Network",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Foreground mesh routing status"
            setShowBadge(false)
        }
        val incomingChannel = NotificationChannel(
            CHANNEL_INCOMING,
            "Mesh Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming secure mesh messages"
            setSound(
                MeshNotificationPreferences.soundUri(this@MeshForegroundService),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
            )
            vibrationPattern = MeshNotificationPreferences.vibrationPattern(this@MeshForegroundService)
            enableVibration(vibrationPattern != null)
            setShowBadge(true)
        }
        manager.createNotificationChannel(foregroundChannel)
        manager.createNotificationChannel(incomingChannel)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun markConversationRead(conversationId: String) {
        val store = SecureLocalStore(applicationContext)
        val states = store.loadConversationStates()
        val now = System.currentTimeMillis()
        val updated = states.map { state ->
            if (state.conversationId == conversationId && state.unreadCount > 0) {
                state.copy(unreadCount = 0, updatedAtMs = now)
            } else {
                state
            }
        }
        store.persistConversationStates(updated)
    }

    companion object {
        private const val CHANNEL_FOREGROUND = "mesh_foreground"
        private const val CHANNEL_INCOMING = "mesh_incoming"
        private const val FOREGROUND_NOTIFICATION_ID = 7101
        private const val INCOMING_NOTIFICATION_BASE_ID = 7200
        private const val INCOMING_NOTIFICATION_MAX_ID = 7999
        private const val MAX_KNOWN_MESSAGE_IDS = 6000

        private const val ACTION_START = "com.meshchat.app.mesh.action.START"
        private const val ACTION_STOP = "com.meshchat.app.mesh.action.STOP"
        private const val ACTION_MARK_READ = "com.meshchat.app.mesh.action.MARK_READ"
        private const val EXTRA_MARK_READ_CONVERSATION_ID = "conversation_id"

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun refreshNotificationChannels(context: Context) {
            MeshNotificationPreferences.refreshChannels(context)
        }
    }
}
