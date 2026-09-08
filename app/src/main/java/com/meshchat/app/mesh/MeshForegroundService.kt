package com.meshchat.app.mesh

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.meshchat.app.EXTRA_OPEN_CONVERSATION_ID
import com.meshchat.app.MainActivity
import com.meshchat.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MeshForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notificationPolicy = MeshNotificationPolicy()
    private val meshManager by lazy { MeshRuntime.manager(applicationContext) }
    private var messagesObserverJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
        if (!hasMeshRuntimePermissions(this)) {
            // A revoked permission can race with service startup. Promote with a
            // non-radio type before stopping so Android never reports an FGS timeout.
            ServiceCompat.startForeground(
                this,
                FOREGROUND_NOTIFICATION_ID,
                buildForegroundNotification(starting = true),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
            )
            stopForegroundCompat()
            stopSelf()
            return
        }
        ServiceCompat.startForeground(
            this,
            FOREGROUND_NOTIFICATION_ID,
            buildForegroundNotification(starting = true),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        )
        // Promote first, then seed disk history BEFORE initializing the runtime's async restore.
        // Sender timestamps cannot distinguish history from a newly received delayed mesh message.
        notificationPolicy.seedHistory(SecureLocalStore(applicationContext).loadMessages().map { it.notificationKey() })
        notificationPolicy.seedHistory(meshManager.messages.value.map { it.notificationKey() })
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
            meshManager.messages.collect { messages ->
                val mutedConversations = SecureLocalStore(applicationContext).loadConversationStates()
                    .filter { it.isMuted }.mapTo(hashSetOf()) { it.conversationId }
                val notificationsEnabled = MeshNotifications.canPost(this@MeshForegroundService)
                messages.sortedBy { it.createdAtMs }.forEach { message ->
                    if (notificationPolicy.shouldNotify(
                        key = message.notificationKey(),
                        isLocal = message.isLocal,
                        isDeleted = message.isDeleted,
                        isSystem = message.isSystem,
                        isMuted = message.conversationId in mutedConversations,
                        isVisible = MeshNotificationVisibility.isConversationVisible(message.conversationId),
                        notificationsEnabled = notificationsEnabled
                    )) postIncomingNotification(message)
                }
            }
        }
    }

    private fun ChatMessage.notificationKey() = MeshNotificationMessageKey(id, conversationId, originNodeId)

    private fun postIncomingNotification(message: ChatMessage) {
        if (!MeshNotifications.canPost(this) ||
            MeshNotificationVisibility.isConversationVisible(message.conversationId) ||
            MeshNotifications.isConversationMuted(this, message.conversationId)) return
        val conversationTitle = message.conversationTitle
            ?.trim()
            ?.ifBlank { null }
            ?: message.senderAlias
            ?.trim()
            ?.ifBlank { null }
            ?: getString(R.string.notification_chat_title)
        val contentText = if (message.contentType == ChatContentType.FILE) {
            val fileName = message.attachment?.fileName ?: message.text.ifBlank { getString(R.string.notification_file_name) }
            getString(R.string.notification_file_content, fileName)
        } else {
            message.text.trim().ifBlank { getString(R.string.notification_new_message) }
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

        val builder = NotificationCompat.Builder(this, MeshNotifications.CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_meshgram_notification)
            .setContentTitle(conversationTitle)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup("meshgram_chat_${message.conversationId}")
            .setContentIntent(launchPendingIntent)
            .addAction(0, getString(R.string.notification_mark_read), markReadPendingIntent)
        MeshNotifications.applyIncomingAlertSettings(this, builder)
        // Stable identity avoids overwriting unrelated chats when a rolling integer counter wraps.
        val tag = listOf(message.conversationId, message.originNodeId, message.id)
            .joinToString("") { "${it.length}:$it" }
        postNotificationSafely(INCOMING_NOTIFICATION_ID, builder.build(), tag)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun postNotificationSafely(id: Int, notification: Notification, tag: String? = null) {
        val channel = if (id == FOREGROUND_NOTIFICATION_ID) MeshNotifications.CHANNEL_FOREGROUND
            else MeshNotifications.CHANNEL_INCOMING
        if (!MeshNotifications.canPost(this, channel)) return
        runCatching { NotificationManagerCompat.from(this).notify(tag, id, notification) }
    }

    private fun buildForegroundNotification(starting: Boolean = false): Notification {
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

        val status = if (!starting && meshManager.isRunning.value) {
            getString(R.string.notification_network_active)
        } else {
            getString(R.string.notification_network_starting)
        }
        return NotificationCompat.Builder(this, MeshNotifications.CHANNEL_FOREGROUND)
            .setSmallIcon(R.drawable.ic_meshgram_notification)
            .setContentTitle(getString(R.string.notification_network_title))
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setSound(null)
            .setVibrate(null)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(launchPendingIntent)
            .addAction(0, getString(R.string.notification_stop), stopPendingIntent)
            .build()
    }

    private fun ensureNotificationChannels() {
        MeshNotifications.ensureChannels(this)
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
        private const val FOREGROUND_NOTIFICATION_ID = 7101
        private const val INCOMING_NOTIFICATION_ID = 7200

        private const val ACTION_START = "com.meshchat.app.mesh.action.START"
        private const val ACTION_STOP = "com.meshchat.app.mesh.action.STOP"
        private const val ACTION_MARK_READ = "com.meshchat.app.mesh.action.MARK_READ"
        private const val EXTRA_MARK_READ_CONVERSATION_ID = "conversation_id"

        fun start(context: Context) {
            if (!hasMeshRuntimePermissions(context)) return
            val intent = Intent(context, MeshForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshForegroundService::class.java))
        }

        fun refreshNotificationChannels(context: Context) {
            MeshNotificationPreferences.refreshChannels(context)
        }

        private fun hasMeshRuntimePermissions(context: Context): Boolean {
            val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            return required.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
