package com.meshchat.app.mesh

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.ParcelUuid
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import kotlin.math.min
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

class BleMeshManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var gattServer: BluetoothGattServer? = null
    private var started = false
    private var presenceJob: Job? = null
    private var transferFlushJob: Job? = null
    private var scanningEnabled = false
    private var advertisingActive = false
    private var advertisingUsesMinimalPayload = false
    private var advertiseRetryDone = false

    private val peerMap = linkedMapOf<String, Peer>()
    private val peerIdentityByNodeId = linkedMapOf<String, PeerIdentity>()
    private val addressToNodeId = linkedMapOf<String, String>()
    private val clientGatts = linkedMapOf<String, BluetoothGatt>()
    private val serverConnectedAddresses = mutableSetOf<String>()
    private val serverConnectedDevices = linkedMapOf<String, BluetoothDevice>()
    private val notifyEnabledAddresses = mutableSetOf<String>()
    private val connectingAddresses = mutableSetOf<String>()
    private val connectingNodeIds = mutableSetOf<String>()
    private val activeAddressByNodeId = linkedMapOf<String, String>()
    private val connectionRetryAtMs = linkedMapOf<String, Long>()
    private val connectionRetryAttempts = linkedMapOf<String, Int>()
    private val connectionRetryAtNodeIdMs = linkedMapOf<String, Long>()
    private val connectionRetryNodeAttempts = linkedMapOf<String, Int>()
    private val seenFrames = LinkedHashMap<String, Long>()
    private val frameAssemblers = linkedMapOf<String, FrameAssembler>()
    private val relayOutbox = LinkedHashMap<String, RelayFrame>()
    private val fileTransferAssemblers = linkedMapOf<String, FileTransferAssembler>()
    private val completedTransfers = LinkedHashMap<String, Long>()
    private val outgoingTransfers = linkedMapOf<String, OutgoingFileTransfer>()
    private val pendingPayloads = linkedMapOf<String, PendingPayloadDispatch>()
    private val scheduledMessageMap = linkedMapOf<String, ScheduledMessageRecord>()
    private val transportMutexByAddress = linkedMapOf<String, Mutex>()
    private val pendingWriteByAddress = linkedMapOf<String, CompletableDeferred<Boolean>>()
    private val pendingNotificationByAddress = linkedMapOf<String, CompletableDeferred<Boolean>>()
    private val notificationCallbackUnavailableAddresses = mutableSetOf<String>()
    private val activeTransferNodeIds = mutableSetOf<String>()
    private val negotiatedMtuByAddress = linkedMapOf<String, Int>()
    private val clientReadyAddresses = mutableSetOf<String>()
    private val serviceDiscoveryStartedAddresses = mutableSetOf<String>()
    private val relayHttpClient = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var wifiSendSocket: DatagramSocket? = null
    private var wifiReceiveSocket: MulticastSocket? = null
    private var wifiReceiveJob: Job? = null
    private var wifiMulticastLock: WifiManager.MulticastLock? = null
    private var wifiLanActive = false
    private var wifiBroadcastTargets: List<InetAddress> = emptyList()
    private var wifiBroadcastTargetsUpdatedAtMs: Long = 0L
    private val wifiDirectedTargets = LinkedHashMap<String, Long>()
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var wifiP2pReceiverRegistered = false
    private var wifiP2pConnected = false
    private var wifiP2pDiscoveryJob: Job? = null
    private var wifiP2pLastDiscoverAtMs: Long = 0L
    private var wifiP2pLastConnectAttemptAtMs: Long = 0L
    private var wifiP2pGroupOwnerAddress: String? = null
    private val wifiP2pPeers = linkedMapOf<String, WifiDirectPeerSnapshot>()
    private var relaySocket: WebSocket? = null
    private var relayAuthenticated = false
    private var relayReconnectJob: Job? = null
    private var lastOutgoingTransferSnapshotAtMs: Long = 0L
    private var lastIncomingTransferSnapshotAtMs: Long = 0L
    private val relayPrefs = context.getSharedPreferences(PREF_NETWORK, Context.MODE_PRIVATE)

    val nodeId: String = loadOrCreateNodeId()
    private val crypto = SecureCryptoEngine(context, nodeId)
    private val localStore = SecureLocalStore(context)

    private val _nodeAlias = MutableStateFlow(crypto.localAlias())
    val nodeAlias = _nodeAlias.asStateFlow()

    private val _nodeAvatarData = MutableStateFlow(crypto.localAvatarData())
    val nodeAvatarData = _nodeAvatarData.asStateFlow()

    private val _nodeFingerprint = MutableStateFlow(crypto.localFingerprint())
    val nodeFingerprint = _nodeFingerprint.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _status = MutableStateFlow("Idle")
    val status = _status.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers = _peers.asStateFlow()

    private val _knownIdentities = MutableStateFlow<List<PeerIdentity>>(emptyList())
    val knownIdentities = _knownIdentities.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(localStore.loadMessages())
    val messages = _messages.asStateFlow()
    private val _scheduledMessages = MutableStateFlow<List<ScheduledMessageRecord>>(emptyList())
    val scheduledMessages = _scheduledMessages.asStateFlow()
    private val _outgoingFileTransfers = MutableStateFlow<List<OutgoingFileTransferProgress>>(emptyList())
    val outgoingFileTransfers = _outgoingFileTransfers.asStateFlow()
    private val _incomingFileTransfers = MutableStateFlow<List<IncomingFileTransferProgress>>(emptyList())
    val incomingFileTransfers = _incomingFileTransfers.asStateFlow()
    private val _wifiLanActive = MutableStateFlow(false)
    val wifiLanActiveState = _wifiLanActive.asStateFlow()
    private val _relayEnabled = MutableStateFlow(
        relayPrefs.getBoolean(KEY_RELAY_ENABLED, false)
    )
    val relayEnabled = _relayEnabled.asStateFlow()
    private val _relayUrl = MutableStateFlow(
        relayPrefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL)?.trim().orEmpty()
    )
    val relayUrl = _relayUrl.asStateFlow()
    private val _relayConnected = MutableStateFlow(false)
    val relayConnected = _relayConnected.asStateFlow()

    init {
        val identities = localStore.loadPeerIdentities()
        _knownIdentities.value = identities.sortedByDescending { it.lastSeenMs }
        synchronized(lock) {
            identities.forEach { identity ->
                peerIdentityByNodeId[identity.nodeId] = identity
            }
        }
        restoreRelayOutboxFromStore()
        restoreOutgoingTransfersFromStore()
        restoreIncomingTransfersFromStore()
        restorePendingPayloadsFromStore()
        restoreScheduledMessagesFromStore()
    }

    fun updateAlias(rawAlias: String) {
        val updatedAlias = crypto.updateAlias(rawAlias)
        _nodeAlias.value = updatedAlias
        if (_isRunning.value) {
            publishHello()
        }
    }

    fun updateAvatarData(rawAvatarData: String) {
        val updatedAvatar = crypto.updateAvatarData(rawAvatarData)
        _nodeAvatarData.value = updatedAvatar
        if (_isRunning.value) {
            publishHello()
        }
    }

    fun updateRelaySettings(enabled: Boolean, relayUrl: String) {
        val normalizedUrl = normalizeRelayUrl(relayUrl)
        _relayEnabled.value = enabled
        _relayUrl.value = normalizedUrl
        relayPrefs.edit()
            .putBoolean(KEY_RELAY_ENABLED, enabled)
            .putString(KEY_RELAY_URL, normalizedUrl)
            .apply()
        if (!_isRunning.value) return
        if (!enabled || normalizedUrl.isBlank()) {
            disconnectRelay(reason = "Hybrid relay disabled")
        } else {
            connectRelayIfNeeded()
        }
    }

    fun reloadFromSecureStore() {
        val restoredMessages = localStore.loadMessages()
        val restoredIdentities = localStore.loadPeerIdentities()
        synchronized(lock) {
            peerIdentityByNodeId.clear()
            restoredIdentities.forEach { identity ->
                peerIdentityByNodeId[identity.nodeId] = identity
            }
            outgoingTransfers.clear()
            pendingPayloads.clear()
            scheduledMessageMap.clear()
            fileTransferAssemblers.clear()
            completedTransfers.clear()
        }
        restoreRelayOutboxFromStore()
        restoreOutgoingTransfersFromStore()
        restoreIncomingTransfersFromStore()
        restorePendingPayloadsFromStore()
        restoreScheduledMessagesFromStore()
        _knownIdentities.value = restoredIdentities.sortedByDescending { it.lastSeenMs }
        _messages.value = restoredMessages.takeLast(MAX_MESSAGES)
        localStore.persistMessages(_messages.value)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }

        var advertisingStarted = false
        var scanningStarted = false
        val wifiStarted = runCatching { startWifiLanTransport() }
            .onFailure { updateStatus("Wi-Fi LAN transport unavailable") }
            .getOrDefault(false)
        val wifiP2pStarted = runCatching { startWifiP2pBootstrap() }
            .onFailure { updateStatus("Wi-Fi Direct bootstrap unavailable") }
            .getOrDefault(false)
        val relayConfigured =
            _relayEnabled.value &&
            _relayUrl.value.isNotBlank()
        val localAdapter = adapter
        if (localAdapter != null && localAdapter.isEnabled) {
            val gattReady = runCatching {
                openGattServer()
                true
            }.onFailure { error ->
                updateStatus("GATT unavailable: ${error.message ?: "unknown"}")
            }.getOrDefault(false)
            if (gattReady) {
                advertisingStarted = runCatching { startAdvertisingWithFallback() }
                    .onFailure { updateStatus("BLE advertise unavailable") }
                    .getOrDefault(false)
                scanningStarted = runCatching { startScanning() }
                    .onFailure { updateStatus("BLE scan unavailable") }
                    .getOrDefault(false)
            }
        } else {
            scanningEnabled = false
            advertisingActive = false
            val reason = if (localAdapter == null) {
                "Bluetooth LE unavailable"
            } else {
                "Bluetooth disabled, local Wi-Fi + internet relay mode"
            }
            updateStatus(reason)
        }
        scanningEnabled = scanningStarted

        if (!advertisingStarted &&
            !scanningStarted &&
            !wifiStarted &&
            !wifiP2pStarted &&
            !relayConfigured
        ) {
            stop(
                "No active transport. Enable BLE, local Wi-Fi, or configure Hybrid Relay"
            )
            return
        }

        _isRunning.value = true
        if (relayConfigured) {
            connectRelayIfNeeded()
        } else {
            disconnectRelay(reason = null)
        }
        startPresenceJob()
        startTransferFlushJob()
        publishHello()
        flushRelayOutbox()
        flushPendingTransfers()
        flushPendingPayloads()
        flushScheduledMessages()
        refreshModeStatus()
    }

    @SuppressLint("MissingPermission")
    fun stop(reason: String? = null) {
        synchronized(lock) {
            if (!started) {
                _isRunning.value = false
                stopWifiLanTransport()
                disconnectRelay(reason = null)
                if (!reason.isNullOrBlank()) {
                    updateStatus(reason)
                }
                return
            }
            started = false
        }

        presenceJob?.cancel()
        presenceJob = null
        transferFlushJob?.cancel()
        transferFlushJob = null
        scanningEnabled = false
        advertisingActive = false
        advertisingUsesMinimalPayload = false
        advertiseRetryDone = false
        stopWifiLanTransport()
        stopWifiP2pBootstrap()
        disconnectRelay(reason = null)

        runCatching { scanner()?.stopScan(scanCallback) }
        runCatching { advertiser()?.stopAdvertising(advertiseCallback) }

        synchronized(lock) {
            clientGatts.values.forEach { gatt ->
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
            }
            clientGatts.clear()
            serverConnectedAddresses.clear()
            serverConnectedDevices.clear()
            notifyEnabledAddresses.clear()
            connectingAddresses.clear()
            connectionRetryAtMs.clear()
            connectionRetryAttempts.clear()
            connectingNodeIds.clear()
            activeAddressByNodeId.clear()
            connectionRetryAtNodeIdMs.clear()
            connectionRetryNodeAttempts.clear()
            fileTransferAssemblers.clear()
            completedTransfers.clear()
            transportMutexByAddress.clear()
            pendingWriteByAddress.values.forEach { pending -> pending.complete(false) }
            pendingWriteByAddress.clear()
            pendingNotificationByAddress.values.forEach { pending -> pending.complete(false) }
            pendingNotificationByAddress.clear()
            notificationCallbackUnavailableAddresses.clear()
            activeTransferNodeIds.clear()
            clientReadyAddresses.clear()
            serviceDiscoveryStartedAddresses.clear()
            negotiatedMtuByAddress.clear()
            updatePeersUnsafe()
        }

        runCatching { gattServer?.close() }
        gattServer = null
        _isRunning.value = false
        updateStatus(reason ?: "Mesh stopped")
    }

    fun send(text: String) {
        val recipients = synchronized(lock) {
            peerIdentityByNodeId.values
                .filter { it.nodeId != nodeId }
                .toList()
        }
        if (recipients.isEmpty()) {
            publishHello()
            updateStatus("Syncing peer keys, try again in a few seconds")
            return
        }

        var successCount = 0
        recipients.forEach { peer ->
            val sent = sendDirectMessage(
                text = text,
                targetNodeId = peer.nodeId,
                conversationId = directConversationId(nodeId, peer.nodeId),
                conversationTitle = peer.alias
            )
            if (sent) successCount++
        }
        if (successCount > 0) {
            updateStatus("Encrypted message sent to $successCount peer(s)")
        }
    }

    fun sendDirectMessage(
        text: String,
        targetNodeId: String,
        conversationId: String = directConversationId(nodeId, targetNodeId),
        conversationTitle: String? = null,
        replyToMessageId: String? = null,
        replyToPreview: String? = null,
        forwardedFromAlias: String? = null,
        forwardedFromMessageId: String? = null,
        messageIdOverride: String? = null
    ): Boolean {
        if (!_isRunning.value) {
            updateStatus("Start mesh before sending")
            return false
        }

        val body = text.trim()
        if (body.isEmpty()) return false

        val target = targetNodeId.trim()
        if (target.isBlank() || target == nodeId) {
            updateStatus("Select a valid peer for direct message")
            return false
        }

        val recipient = synchronized(lock) { peerIdentityByNodeId[target] }
        val messageId = messageIdOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val resolvedTitle = conversationTitle
            ?: recipient?.alias
            ?: "Node-${target.take(4)}"
        val payload = MeshMessagePayload(
            chatId = conversationId.ifBlank { directConversationId(nodeId, target) },
            chatType = MeshMessagePayload.CHAT_TYPE_DIRECT,
            chatTitle = resolvedTitle,
            memberNodeIds = listOf(nodeId, target),
            messageId = messageId,
            replyToMessageId = replyToMessageId?.trim()?.ifBlank { null },
            replyToPreview = replyToPreview?.trim()?.ifBlank { null },
            forwardedFromAlias = forwardedFromAlias?.trim()?.ifBlank { null },
            forwardedFromMessageId = forwardedFromMessageId?.trim()?.ifBlank { null },
            text = body,
            sentAtMs = System.currentTimeMillis()
        )

        val plaintext = json.encodeToString(payload)
        val sentCount = if (recipient != null) {
            sendPayloadToRecipients(
                plaintext = plaintext,
                recipients = listOf(recipient)
            )
        } else {
            0
        }
        val queuedForKeySync = recipient == null || sentCount <= 0
        if (queuedForKeySync) {
            enqueuePendingPayload(
                plaintext = plaintext,
                targetNodeIds = listOf(target),
                messageId = messageId
            )
            publishHello()
        }

        appendMessage(
            ChatMessage(
                id = messageId,
                text = body,
                originNodeId = nodeId,
                targetNodeId = target,
                relayNodeId = nodeId,
                createdAtMs = System.currentTimeMillis(),
                isLocal = true,
                isEncrypted = true,
                conversationId = payload.chatId,
                conversationType = ConversationType.DIRECT,
                conversationTitle = payload.chatTitle,
                senderAlias = _nodeAlias.value,
                memberNodeIds = payload.memberNodeIds,
                replyToMessageId = payload.replyToMessageId,
                replyToPreview = payload.replyToPreview,
                forwardedFromAlias = payload.forwardedFromAlias,
                forwardedFromMessageId = payload.forwardedFromMessageId,
                deliveryState = if (queuedForKeySync) {
                    MessageDeliveryState.PENDING
                } else {
                    MessageDeliveryState.SENT
                }
            )
        )
        updateStatus(
            if (queuedForKeySync) {
                "Queued encrypted direct message, waiting for key sync"
            } else {
                "Encrypted direct message sent"
            }
        )
        return true
    }

    fun sendGroupMessage(
        text: String,
        groupId: String,
        groupTitle: String,
        memberNodeIds: List<String>,
        adminNodeIds: List<String> = emptyList(),
        moderatorNodeIds: List<String> = emptyList(),
        isBroadcastOnly: Boolean = false,
        allowMemberReactions: Boolean = true,
        allowMemberEditOwnMessages: Boolean = true,
        allowMemberDeleteOwnMessages: Boolean = true,
        replyToMessageId: String? = null,
        replyToPreview: String? = null,
        forwardedFromAlias: String? = null,
        forwardedFromMessageId: String? = null,
        chatType: String = MeshMessagePayload.CHAT_TYPE_GROUP,
        conversationType: ConversationType = ConversationType.GROUP,
        messageIdOverride: String? = null
    ): Int {
        if (!_isRunning.value) {
            updateStatus("Start mesh before sending")
            return 0
        }

        val body = text.trim()
        if (body.isEmpty()) return 0

        val normalizedMembers = (memberNodeIds + nodeId)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedMembers.size < 2) {
            updateStatus("Group needs at least one peer")
            return 0
        }

        val targetNodeIds = normalizedMembers.filter { it != nodeId }
        if (targetNodeIds.isEmpty()) return 0
        val recipients = synchronized(lock) {
            targetNodeIds.mapNotNull { memberId -> peerIdentityByNodeId[memberId] }
        }

        val messageId = messageIdOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val normalizedAdmins = adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizedMembers.contains(it) }
            .distinct()
            .ifEmpty { listOf(nodeId) }
        val normalizedModerators = moderatorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizedMembers.contains(it) && !normalizedAdmins.contains(it) }
            .distinct()
        val normalizedOwner = normalizedAdmins.firstOrNull() ?: nodeId
        val normalizedChatType = if (
            chatType.equals(MeshMessagePayload.CHAT_TYPE_CHANNEL, ignoreCase = true)
        ) {
            MeshMessagePayload.CHAT_TYPE_CHANNEL
        } else {
            MeshMessagePayload.CHAT_TYPE_GROUP
        }
        val normalizedConversationType = if (normalizedChatType == MeshMessagePayload.CHAT_TYPE_CHANNEL) {
            ConversationType.CHANNEL
        } else {
            conversationType
        }
        val payload = MeshMessagePayload(
            chatId = groupId.ifBlank {
                if (normalizedConversationType == ConversationType.CHANNEL) {
                    "chn-${UUID.randomUUID().toString().take(10)}"
                } else {
                    "grp-${UUID.randomUUID().toString().take(10)}"
                }
            },
            chatType = normalizedChatType,
            chatTitle = groupTitle.trim().ifBlank {
                if (normalizedConversationType == ConversationType.CHANNEL) "Mesh Channel" else "Mesh Group"
            },
            memberNodeIds = normalizedMembers,
            collectiveOwnerNodeId = normalizedOwner,
            collectiveAdminNodeIds = normalizedAdmins,
            collectiveModeratorNodeIds = normalizedModerators,
            collectiveBroadcastOnly = isBroadcastOnly,
            collectiveAllowMemberReactions = allowMemberReactions,
            collectiveAllowMemberEditOwnMessages = allowMemberEditOwnMessages,
            collectiveAllowMemberDeleteOwnMessages = allowMemberDeleteOwnMessages,
            messageId = messageId,
            replyToMessageId = replyToMessageId?.trim()?.ifBlank { null },
            replyToPreview = replyToPreview?.trim()?.ifBlank { null },
            forwardedFromAlias = forwardedFromAlias?.trim()?.ifBlank { null },
            forwardedFromMessageId = forwardedFromMessageId?.trim()?.ifBlank { null },
            text = body,
            sentAtMs = System.currentTimeMillis()
        )

        val plaintext = json.encodeToString(payload)
        val sentCount = if (recipients.isEmpty()) {
            0
        } else {
            sendPayloadToRecipients(
                plaintext = plaintext,
                recipients = recipients
            )
        }
        val knownRecipientNodeIds = recipients.map { it.nodeId }.toSet()
        val missingKeyNodeIds = targetNodeIds.filterNot { knownRecipientNodeIds.contains(it) }
        val queuedNodeIds = if (sentCount > 0 && missingKeyNodeIds.isNotEmpty()) {
            missingKeyNodeIds
        } else if (sentCount <= 0) {
            targetNodeIds
        } else {
            emptyList()
        }
        if (queuedNodeIds.isNotEmpty()) {
            enqueuePendingPayload(
                plaintext = plaintext,
                targetNodeIds = queuedNodeIds,
                messageId = messageId
            )
            publishHello()
        }

        appendMessage(
            ChatMessage(
                id = messageId,
                text = body,
                originNodeId = nodeId,
                targetNodeId = null,
                relayNodeId = nodeId,
                createdAtMs = System.currentTimeMillis(),
                isLocal = true,
                isEncrypted = true,
                conversationId = payload.chatId,
                conversationType = normalizedConversationType,
                conversationTitle = payload.chatTitle,
                senderAlias = _nodeAlias.value,
                memberNodeIds = payload.memberNodeIds,
                collectiveOwnerNodeId = payload.collectiveOwnerNodeId ?: normalizedOwner,
                collectiveAdminNodeIds = payload.collectiveAdminNodeIds,
                collectiveModeratorNodeIds = payload.collectiveModeratorNodeIds,
                collectiveBroadcastOnly = payload.collectiveBroadcastOnly,
                collectiveAllowMemberReactions = payload.collectiveAllowMemberReactions,
                collectiveAllowMemberEditOwnMessages = payload.collectiveAllowMemberEditOwnMessages,
                collectiveAllowMemberDeleteOwnMessages = payload.collectiveAllowMemberDeleteOwnMessages,
                replyToMessageId = payload.replyToMessageId,
                replyToPreview = payload.replyToPreview,
                forwardedFromAlias = payload.forwardedFromAlias,
                forwardedFromMessageId = payload.forwardedFromMessageId,
                deliveryState = if (queuedNodeIds.isNotEmpty() || sentCount <= 0) {
                    MessageDeliveryState.PENDING
                } else {
                    MessageDeliveryState.SENT
                }
            )
        )
        val collectiveLabel = if (normalizedConversationType == ConversationType.CHANNEL) {
            "channel post"
        } else {
            "group message"
        }
        when {
            sentCount > 0 && queuedNodeIds.isNotEmpty() -> {
                updateStatus(
                    "Encrypted $collectiveLabel sent to $sentCount peer(s), queued for ${queuedNodeIds.size}"
                )
            }
            sentCount > 0 -> {
                updateStatus("Encrypted $collectiveLabel queued for mesh delivery to $sentCount peer(s)")
            }
            queuedNodeIds.isNotEmpty() -> {
                updateStatus(
                    "Queued encrypted $collectiveLabel for ${queuedNodeIds.size} peer(s), waiting for key sync"
                )
            }
        }
        return maxOf(sentCount, queuedNodeIds.size)
    }

    fun editDirectMessage(
        targetNodeId: String,
        conversationId: String = directConversationId(nodeId, targetNodeId),
        conversationTitle: String? = null,
        targetMessageId: String,
        editedText: String
    ): Boolean {
        if (!_isRunning.value) {
            updateStatus("Start mesh before editing")
            return false
        }
        val target = targetNodeId.trim()
        val messageId = targetMessageId.trim()
        val newText = editedText.trim()
        if (target.isBlank() || target == nodeId || messageId.isBlank() || newText.isBlank()) {
            return false
        }
        val recipient = synchronized(lock) { peerIdentityByNodeId[target] }
        val payload = MeshMessagePayload(
            chatId = conversationId.ifBlank { directConversationId(nodeId, target) },
            chatType = MeshMessagePayload.CHAT_TYPE_DIRECT,
            chatTitle = conversationTitle ?: recipient?.alias ?: "Node-${target.take(4)}",
            memberNodeIds = listOf(nodeId, target),
            payloadKind = MeshMessagePayload.KIND_MESSAGE_EDIT,
            targetMessageId = messageId,
            text = newText,
            sentAtMs = System.currentTimeMillis()
        )
        val dispatch = dispatchPayloadToTargets(
            plaintext = json.encodeToString(payload),
            targetNodeIds = listOf(target)
        )
        if (!dispatch.dispatched) return false
        val changed = applyMessageEdit(
            actorNodeId = nodeId,
            targetMessageId = messageId,
            newText = newText,
            editedAtMs = payload.sentAtMs
        )
        if (changed) {
            updateStatus(
                when {
                    dispatch.sentCount > 0 && dispatch.queuedCount > 0 ->
                        "Message edited, queued for ${dispatch.queuedCount} peer(s)"
                    dispatch.sentCount > 0 -> "Message edited"
                    else -> "Message edit queued, waiting for key sync"
                }
            )
        }
        return changed
    }

    fun editGroupMessage(
        groupId: String,
        groupTitle: String,
        memberNodeIds: List<String>,
        targetMessageId: String,
        editedText: String,
        chatType: String = MeshMessagePayload.CHAT_TYPE_GROUP,
        adminNodeIds: List<String> = emptyList(),
        moderatorNodeIds: List<String> = emptyList(),
        isBroadcastOnly: Boolean = false,
        allowMemberReactions: Boolean = true,
        allowMemberEditOwnMessages: Boolean = true,
        allowMemberDeleteOwnMessages: Boolean = true
    ): Boolean {
        if (!_isRunning.value) {
            updateStatus("Start mesh before editing")
            return false
        }
        val messageId = targetMessageId.trim()
        val newText = editedText.trim()
        if (messageId.isBlank() || newText.isBlank()) return false
        val normalizedMembers = (memberNodeIds + nodeId)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedMembers.size < 2) return false
        val targetNodeIds = normalizedMembers.filter { it != nodeId }
        if (targetNodeIds.isEmpty()) return false
        val normalizedChatType = if (
            chatType.equals(MeshMessagePayload.CHAT_TYPE_CHANNEL, ignoreCase = true)
        ) {
            MeshMessagePayload.CHAT_TYPE_CHANNEL
        } else {
            MeshMessagePayload.CHAT_TYPE_GROUP
        }
        val normalizedAdmins = adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizedMembers.contains(it) }
            .distinct()
            .ifEmpty { listOf(nodeId) }
        val normalizedModerators = moderatorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizedMembers.contains(it) && !normalizedAdmins.contains(it) }
            .distinct()
        val normalizedOwner = normalizedAdmins.firstOrNull() ?: nodeId
        val payload = MeshMessagePayload(
            chatId = groupId.ifBlank {
                if (normalizedChatType == MeshMessagePayload.CHAT_TYPE_CHANNEL) {
                    "chn-${UUID.randomUUID().toString().take(10)}"
                } else {
                    "grp-${UUID.randomUUID().toString().take(10)}"
                }
            },
            chatType = normalizedChatType,
            chatTitle = groupTitle.trim().ifBlank {
                if (normalizedChatType == MeshMessagePayload.CHAT_TYPE_CHANNEL) "Mesh Channel" else "Mesh Group"
            },
            memberNodeIds = normalizedMembers,
            collectiveOwnerNodeId = normalizedOwner,
            collectiveAdminNodeIds = normalizedAdmins,
            collectiveModeratorNodeIds = normalizedModerators,
            collectiveBroadcastOnly = isBroadcastOnly,
            collectiveAllowMemberReactions = allowMemberReactions,
            collectiveAllowMemberEditOwnMessages = allowMemberEditOwnMessages,
            collectiveAllowMemberDeleteOwnMessages = allowMemberDeleteOwnMessages,
            payloadKind = MeshMessagePayload.KIND_MESSAGE_EDIT,
            targetMessageId = messageId,
            text = newText,
            sentAtMs = System.currentTimeMillis()
        )
        val dispatch = dispatchPayloadToTargets(
            plaintext = json.encodeToString(payload),
            targetNodeIds = targetNodeIds
        )
        if (!dispatch.dispatched) return false
        val changed = applyMessageEdit(
            actorNodeId = nodeId,
            targetMessageId = messageId,
            newText = newText,
            editedAtMs = payload.sentAtMs
        )
        if (changed) {
            val base = if (normalizedChatType == MeshMessagePayload.CHAT_TYPE_CHANNEL) {
                "Post edited in channel"
            } else {
                "Message edited in group"
            }
            updateStatus(
                when {
                    dispatch.sentCount > 0 && dispatch.queuedCount > 0 -> {
                        "$base, queued for ${dispatch.queuedCount} peer(s)"
                    }
                    dispatch.sentCount > 0 -> {
                        base
                    }
                    else -> {
                        "$base, waiting for key sync"
                    }
                }
            )
        }
        return changed
    }

    fun deleteDirectMessage(
        targetNodeId: String,
        conversationId: String = directConversationId(nodeId, targetNodeId),
        conversationTitle: String? = null,
        targetMessageId: String
    ): Boolean {
        if (!_isRunning.value) {
            updateStatus("Start mesh before deleting")
            return false
        }
        val target = targetNodeId.trim()
        val messageId = targetMessageId.trim()
        if (target.isBlank() || target == nodeId || messageId.isBlank()) return false
        val recipient = synchronized(lock) { peerIdentityByNodeId[target] }
        val payload = MeshMessagePayload(
            chatId = conversationId.ifBlank { directConversationId(nodeId, target) },
            chatType = MeshMessagePayload.CHAT_TYPE_DIRECT,
            chatTitle = conversationTitle ?: recipient?.alias ?: "Node-${target.take(4)}",
            memberNodeIds = listOf(nodeId, target),
            payloadKind = MeshMessagePayload.KIND_MESSAGE_DELETE,
            targetMessageId = messageId,
            sentAtMs = System.currentTimeMillis()
        )
        val dispatch = dispatchPayloadToTargets(
            plaintext = json.encodeToString(payload),
            targetNodeIds = listOf(target)
        )
        if (!dispatch.dispatched) return false
        val changed = applyMessageDelete(
            actorNodeId = nodeId,
            targetMessageId = messageId,
            deletedAtMs = payload.sentAtMs
        )
        if (changed) {
            updateStatus(
                when {
                    dispatch.sentCount > 0 && dispatch.queuedCount > 0 ->
                        "Message deleted, queued for ${dispatch.queuedCount} peer(s)"
                    dispatch.sentCount > 0 -> "Message deleted"
                    else -> "Message delete queued, waiting for key sync"
                }
            )
        }
        return changed
    }

    fun deleteGroupMessage(
        groupId: String,
        groupTitle: String,
        memberNodeIds: List<String>,
        targetMessageId: String,
        chatType: String = MeshMessagePayload.CHAT_TYPE_GROUP,
        adminNodeIds: List<String> = emptyList(),
        moderatorNodeIds: List<String> = emptyList(),
        isBroadcastOnly: Boolean = false,
        allowMemberReactions: Boolean = true,
        allowMemberEditOwnMessages: Boolean = true,
        allowMemberDeleteOwnMessages: Boolean = true
    ): Boolean {
        if (!_isRunning.value) {
            updateStatus("Start mesh before deleting")
            return false
        }
        val messageId = targetMessageId.trim()
        if (messageId.isBlank()) return false
        val normalizedMembers = (memberNodeIds + nodeId)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedMembers.size < 2) return false
        val targetNodeIds = normalizedMembers.filter { it != nodeId }
        if (targetNodeIds.isEmpty()) return false
        val normalizedChatType = if (
            chatType.equals(MeshMessagePayload.CHAT_TYPE_CHANNEL, ignoreCase = true)
        ) {
            MeshMessagePayload.CHAT_TYPE_CHANNEL
        } else {
            MeshMessagePayload.CHAT_TYPE_GROUP
        }
        val normalizedAdmins = adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizedMembers.contains(it) }
            .distinct()
            .ifEmpty { listOf(nodeId) }
        val normalizedModerators = moderatorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizedMembers.contains(it) && !normalizedAdmins.contains(it) }
            .distinct()
        val normalizedOwner = normalizedAdmins.firstOrNull() ?: nodeId
        val payload = MeshMessagePayload(
            chatId = groupId.ifBlank {
                if (normalizedChatType == MeshMessagePayload.CHAT_TYPE_CHANNEL) {
                    "chn-${UUID.randomUUID().toString().take(10)}"
                } else {
                    "grp-${UUID.randomUUID().toString().take(10)}"
                }
            },
            chatType = normalizedChatType,
            chatTitle = groupTitle.trim().ifBlank {
                if (normalizedChatType == MeshMessagePayload.CHAT_TYPE_CHANNEL) "Mesh Channel" else "Mesh Group"
            },
            memberNodeIds = normalizedMembers,
            collectiveOwnerNodeId = normalizedOwner,
            collectiveAdminNodeIds = normalizedAdmins,
            collectiveModeratorNodeIds = normalizedModerators,
            collectiveBroadcastOnly = isBroadcastOnly,
            collectiveAllowMemberReactions = allowMemberReactions,
            collectiveAllowMemberEditOwnMessages = allowMemberEditOwnMessages,
            collectiveAllowMemberDeleteOwnMessages = allowMemberDeleteOwnMessages,
            payloadKind = MeshMessagePayload.KIND_MESSAGE_DELETE,
            targetMessageId = messageId,
            sentAtMs = System.currentTimeMillis()
        )
        val dispatch = dispatchPayloadToTargets(
            plaintext = json.encodeToString(payload),
            targetNodeIds = targetNodeIds
        )
        if (!dispatch.dispatched) return false
        val changed = applyMessageDelete(
            actorNodeId = nodeId,
            targetMessageId = messageId,
            deletedAtMs = payload.sentAtMs
        )
        if (changed) {
            val base = if (normalizedChatType == MeshMessagePayload.CHAT_TYPE_CHANNEL) {
                "Post deleted in channel"
            } else {
                "Message deleted in group"
            }
            updateStatus(
                when {
                    dispatch.sentCount > 0 && dispatch.queuedCount > 0 -> {
                        "$base, queued for ${dispatch.queuedCount} peer(s)"
                    }
                    dispatch.sentCount > 0 -> {
                        base
                    }
                    else -> {
                        "$base, waiting for key sync"
                    }
                }
            )
        }
        return changed
    }

    fun reactDirectMessage(
        targetNodeId: String,
        conversationId: String = directConversationId(nodeId, targetNodeId),
        conversationTitle: String? = null,
        targetMessageId: String,
        emoji: String
    ): Boolean {
        if (!_isRunning.value) {
            updateStatus("Start mesh before reacting")
            return false
        }
        val target = targetNodeId.trim()
        val messageId = targetMessageId.trim()
        if (target.isBlank() || target == nodeId || messageId.isBlank()) return false
        val recipient = synchronized(lock) { peerIdentityByNodeId[target] }
        val reaction = emoji.trim().take(16)
        val payload = MeshMessagePayload(
            chatId = conversationId.ifBlank { directConversationId(nodeId, target) },
            chatType = MeshMessagePayload.CHAT_TYPE_DIRECT,
            chatTitle = conversationTitle ?: recipient?.alias ?: "Node-${target.take(4)}",
            memberNodeIds = listOf(nodeId, target),
            payloadKind = MeshMessagePayload.KIND_MESSAGE_REACTION,
            targetMessageId = messageId,
            reactionEmoji = reaction.ifBlank { null },
            sentAtMs = System.currentTimeMillis()
        )
        val dispatch = dispatchPayloadToTargets(
            plaintext = json.encodeToString(payload),
            targetNodeIds = listOf(target)
        )
        if (!dispatch.dispatched) return false
        val changed = applyMessageReaction(
            actorNodeId = nodeId,
            actorAlias = _nodeAlias.value,
            targetMessageId = messageId,
            emoji = reaction,
            reactedAtMs = payload.sentAtMs
        )
        if (changed) {
            val base = if (reaction.isBlank()) "Reaction removed" else "Reaction sent"
            updateStatus(
                when {
                    dispatch.sentCount > 0 && dispatch.queuedCount > 0 ->
                        "$base, queued for ${dispatch.queuedCount} peer(s)"
                    dispatch.sentCount > 0 -> base
                    else -> "$base, waiting for key sync"
                }
            )
        }
        return changed
    }

    fun reactGroupMessage(
        groupId: String,
        groupTitle: String,
        memberNodeIds: List<String>,
        targetMessageId: String,
        emoji: String,
        chatType: String = MeshMessagePayload.CHAT_TYPE_GROUP,
        adminNodeIds: List<String> = emptyList(),
        moderatorNodeIds: List<String> = emptyList(),
        isBroadcastOnly: Boolean = false,
        allowMemberReactions: Boolean = true,
        allowMemberEditOwnMessages: Boolean = true,
        allowMemberDeleteOwnMessages: Boolean = true
    ): Boolean {
        if (!_isRunning.value) {
            updateStatus("Start mesh before reacting")
            return false
        }
        val messageId = targetMessageId.trim()
        if (messageId.isBlank()) return false
        val normalizedMembers = (memberNodeIds + nodeId)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedMembers.size < 2) return false
        val targetNodeIds = normalizedMembers.filter { it != nodeId }
        if (targetNodeIds.isEmpty()) return false
        val normalizedChatType = if (
            chatType.equals(MeshMessagePayload.CHAT_TYPE_CHANNEL, ignoreCase = true)
        ) {
            MeshMessagePayload.CHAT_TYPE_CHANNEL
        } else {
            MeshMessagePayload.CHAT_TYPE_GROUP
        }
        val normalizedAdmins = adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizedMembers.contains(it) }
            .distinct()
            .ifEmpty { listOf(nodeId) }
        val normalizedModerators = moderatorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizedMembers.contains(it) && !normalizedAdmins.contains(it) }
            .distinct()
        val reaction = emoji.trim().take(16)
        val normalizedOwner = normalizedAdmins.firstOrNull() ?: nodeId
        val payload = MeshMessagePayload(
            chatId = groupId.ifBlank {
                if (normalizedChatType == MeshMessagePayload.CHAT_TYPE_CHANNEL) {
                    "chn-${UUID.randomUUID().toString().take(10)}"
                } else {
                    "grp-${UUID.randomUUID().toString().take(10)}"
                }
            },
            chatType = normalizedChatType,
            chatTitle = groupTitle.trim().ifBlank {
                if (normalizedChatType == MeshMessagePayload.CHAT_TYPE_CHANNEL) "Mesh Channel" else "Mesh Group"
            },
            memberNodeIds = normalizedMembers,
            collectiveOwnerNodeId = normalizedOwner,
            collectiveAdminNodeIds = normalizedAdmins,
            collectiveModeratorNodeIds = normalizedModerators,
            collectiveBroadcastOnly = isBroadcastOnly,
            collectiveAllowMemberReactions = allowMemberReactions,
            collectiveAllowMemberEditOwnMessages = allowMemberEditOwnMessages,
            collectiveAllowMemberDeleteOwnMessages = allowMemberDeleteOwnMessages,
            payloadKind = MeshMessagePayload.KIND_MESSAGE_REACTION,
            targetMessageId = messageId,
            reactionEmoji = reaction.ifBlank { null },
            sentAtMs = System.currentTimeMillis()
        )
        val dispatch = dispatchPayloadToTargets(
            plaintext = json.encodeToString(payload),
            targetNodeIds = targetNodeIds
        )
        if (!dispatch.dispatched) return false
        val changed = applyMessageReaction(
            actorNodeId = nodeId,
            actorAlias = _nodeAlias.value,
            targetMessageId = messageId,
            emoji = reaction,
            reactedAtMs = payload.sentAtMs
        )
        if (changed) {
            val base = if (normalizedChatType == MeshMessagePayload.CHAT_TYPE_CHANNEL) {
                if (reaction.isBlank()) "Reaction removed" else "Reaction sent in channel"
            } else {
                if (reaction.isBlank()) "Reaction removed" else "Reaction sent in group"
            }
            updateStatus(
                when {
                    dispatch.sentCount > 0 && dispatch.queuedCount > 0 -> {
                        "$base, queued for ${dispatch.queuedCount} peer(s)"
                    }
                    dispatch.sentCount > 0 -> {
                        base
                    }
                    else -> {
                        "$base, waiting for key sync"
                    }
                }
            )
        }
        return changed
    }

    fun pinDirectMessage(
        targetNodeId: String,
        conversationId: String = directConversationId(nodeId, targetNodeId),
        conversationTitle: String? = null,
        targetMessageId: String,
        pinEnabled: Boolean
    ): Boolean {
        if (!_isRunning.value) {
            updateStatus("Start mesh before pinning")
            return false
        }
        val target = targetNodeId.trim()
        val messageId = targetMessageId.trim()
        if (target.isBlank() || target == nodeId || messageId.isBlank()) return false
        val recipient = synchronized(lock) { peerIdentityByNodeId[target] }
        val payload = MeshMessagePayload(
            chatId = conversationId.ifBlank { directConversationId(nodeId, target) },
            chatType = MeshMessagePayload.CHAT_TYPE_DIRECT,
            chatTitle = conversationTitle ?: recipient?.alias ?: "Node-${target.take(4)}",
            memberNodeIds = listOf(nodeId, target),
            payloadKind = MeshMessagePayload.KIND_MESSAGE_PIN,
            targetMessageId = messageId,
            pinEnabled = pinEnabled,
            sentAtMs = System.currentTimeMillis()
        )
        val dispatch = dispatchPayloadToTargets(
            plaintext = json.encodeToString(payload),
            targetNodeIds = listOf(target)
        )
        if (!dispatch.dispatched) return false
        val changed = applyConversationPin(
            actorNodeId = nodeId,
            conversationId = payload.chatId,
            targetMessageId = messageId,
            pinEnabled = pinEnabled,
            pinnedAtMs = payload.sentAtMs
        )
        if (changed) {
            val base = if (pinEnabled) "Message pinned" else "Message unpinned"
            updateStatus(
                when {
                    dispatch.sentCount > 0 && dispatch.queuedCount > 0 ->
                        "$base, queued for ${dispatch.queuedCount} peer(s)"
                    dispatch.sentCount > 0 -> base
                    else -> "$base, waiting for key sync"
                }
            )
        }
        return changed
    }

    fun pinGroupMessage(
        groupId: String,
        groupTitle: String,
        memberNodeIds: List<String>,
        targetMessageId: String,
        pinEnabled: Boolean,
        chatType: String = MeshMessagePayload.CHAT_TYPE_GROUP,
        adminNodeIds: List<String> = emptyList(),
        moderatorNodeIds: List<String> = emptyList(),
        isBroadcastOnly: Boolean = false,
        allowMemberReactions: Boolean = true,
        allowMemberEditOwnMessages: Boolean = true,
        allowMemberDeleteOwnMessages: Boolean = true
    ): Boolean {
        if (!_isRunning.value) {
            updateStatus("Start mesh before pinning")
            return false
        }
        val messageId = targetMessageId.trim()
        if (messageId.isBlank()) return false
        val normalizedMembers = (memberNodeIds + nodeId)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedMembers.size < 2) return false
        val targetNodeIds = normalizedMembers.filter { it != nodeId }
        if (targetNodeIds.isEmpty()) return false
        val normalizedChatType = if (
            chatType.equals(MeshMessagePayload.CHAT_TYPE_CHANNEL, ignoreCase = true)
        ) {
            MeshMessagePayload.CHAT_TYPE_CHANNEL
        } else {
            MeshMessagePayload.CHAT_TYPE_GROUP
        }
        val normalizedAdmins = adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizedMembers.contains(it) }
            .distinct()
            .ifEmpty { listOf(nodeId) }
        val normalizedModerators = moderatorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizedMembers.contains(it) && !normalizedAdmins.contains(it) }
            .distinct()
        val normalizedOwner = normalizedAdmins.firstOrNull() ?: nodeId
        val payload = MeshMessagePayload(
            chatId = groupId.ifBlank {
                if (normalizedChatType == MeshMessagePayload.CHAT_TYPE_CHANNEL) {
                    "chn-${UUID.randomUUID().toString().take(10)}"
                } else {
                    "grp-${UUID.randomUUID().toString().take(10)}"
                }
            },
            chatType = normalizedChatType,
            chatTitle = groupTitle.trim().ifBlank {
                if (normalizedChatType == MeshMessagePayload.CHAT_TYPE_CHANNEL) "Mesh Channel" else "Mesh Group"
            },
            memberNodeIds = normalizedMembers,
            collectiveOwnerNodeId = normalizedOwner,
            collectiveAdminNodeIds = normalizedAdmins,
            collectiveModeratorNodeIds = normalizedModerators,
            collectiveBroadcastOnly = isBroadcastOnly,
            collectiveAllowMemberReactions = allowMemberReactions,
            collectiveAllowMemberEditOwnMessages = allowMemberEditOwnMessages,
            collectiveAllowMemberDeleteOwnMessages = allowMemberDeleteOwnMessages,
            payloadKind = MeshMessagePayload.KIND_MESSAGE_PIN,
            targetMessageId = messageId,
            pinEnabled = pinEnabled,
            sentAtMs = System.currentTimeMillis()
        )
        val dispatch = dispatchPayloadToTargets(
            plaintext = json.encodeToString(payload),
            targetNodeIds = targetNodeIds
        )
        if (!dispatch.dispatched) return false
        val changed = applyConversationPin(
            actorNodeId = nodeId,
            conversationId = payload.chatId,
            targetMessageId = messageId,
            pinEnabled = pinEnabled,
            pinnedAtMs = payload.sentAtMs
        )
        if (changed) {
            val base = if (normalizedChatType == MeshMessagePayload.CHAT_TYPE_CHANNEL) {
                if (pinEnabled) "Post pinned in channel" else "Post unpinned in channel"
            } else {
                if (pinEnabled) "Message pinned in group" else "Message unpinned in group"
            }
            updateStatus(
                when {
                    dispatch.sentCount > 0 && dispatch.queuedCount > 0 -> {
                        "$base, queued for ${dispatch.queuedCount} peer(s)"
                    }
                    dispatch.sentCount > 0 -> {
                        base
                    }
                    else -> {
                        "$base, waiting for key sync"
                    }
                }
            )
        }
        return changed
    }

    fun sendDirectFile(
        fileUri: Uri,
        targetNodeId: String,
        conversationId: String = directConversationId(nodeId, targetNodeId),
        conversationTitle: String? = null,
        caption: String = "",
        mediaAlbumId: String? = null,
        mediaAlbumIndex: Int = 0,
        mediaAlbumCount: Int = 1
    ): Boolean {
        if (!_isRunning.value) {
            updateStatus("Start mesh before sending")
            return false
        }

        val target = targetNodeId.trim()
        if (target.isBlank() || target == nodeId) {
            updateStatus("Select a valid peer for file transfer")
            return false
        }

        val recipient = synchronized(lock) { peerIdentityByNodeId[target] }
        if (recipient == null) {
            publishHello()
            updateStatus("Peer keys are not synced yet")
            return false
        }

        val prepared = prepareAttachmentFromUri(fileUri) ?: return false
        val payloadChatId = conversationId.ifBlank { directConversationId(nodeId, target) }
        val payloadTitle = conversationTitle ?: recipient.alias
        val members = listOf(nodeId, target)

        val dispatch = sendFileChunks(
            recipients = listOf(recipient),
            chatId = payloadChatId,
            chatType = MeshMessagePayload.CHAT_TYPE_DIRECT,
            chatTitle = payloadTitle,
            memberNodeIds = members,
            collectiveAdminNodeIds = emptyList(),
            collectiveModeratorNodeIds = emptyList(),
            collectiveBroadcastOnly = false,
            caption = caption,
            mediaAlbumId = mediaAlbumId,
            mediaAlbumIndex = mediaAlbumIndex,
            mediaAlbumCount = mediaAlbumCount,
            prepared = prepared
        )
        if (!dispatch.accepted) return false

        val localPath = localStore.saveAttachment(
            transferId = prepared.transferId,
            fileName = prepared.fileName,
            bytes = prepared.originalBytes
        )
        appendMessage(
            ChatMessage(
                id = "file:${prepared.transferId}",
                text = caption.trim().take(MAX_FILE_CAPTION_LENGTH).ifBlank { prepared.fileName },
                originNodeId = nodeId,
                targetNodeId = target,
                relayNodeId = nodeId,
                createdAtMs = prepared.sentAtMs,
                isLocal = true,
                isEncrypted = true,
                conversationId = payloadChatId,
                conversationType = ConversationType.DIRECT,
                conversationTitle = payloadTitle,
                senderAlias = _nodeAlias.value,
                memberNodeIds = members,
                contentType = ChatContentType.FILE,
                deliveryState = if (dispatch.sent) {
                    MessageDeliveryState.SENT
                } else {
                    MessageDeliveryState.PENDING
                },
                attachment = MessageAttachment(
                    transferId = prepared.transferId,
                    fileName = prepared.fileName,
                    mimeType = prepared.mimeType,
                    sizeBytes = prepared.sizeBytes,
                    sha256 = prepared.sha256,
                    compressed = prepared.compressed,
                    localUri = localPath,
                    mediaAlbumId = mediaAlbumId?.trim()?.ifBlank { null },
                    mediaAlbumIndex = mediaAlbumIndex.coerceAtLeast(0),
                    mediaAlbumCount = mediaAlbumCount.coerceAtLeast(1)
                )
            )
        )
        updateStatus(
            if (dispatch.sent) {
                "Encrypted file sent: ${prepared.fileName} (${humanSize(prepared.sizeBytes)})"
            } else {
                "Encrypted file queued for delivery: ${prepared.fileName} (${humanSize(prepared.sizeBytes)})"
            }
        )
        return true
    }

    fun sendGroupFile(
        fileUri: Uri,
        groupId: String,
        groupTitle: String,
        memberNodeIds: List<String>,
        adminNodeIds: List<String> = emptyList(),
        moderatorNodeIds: List<String> = emptyList(),
        isBroadcastOnly: Boolean = false,
        allowMemberReactions: Boolean = true,
        allowMemberEditOwnMessages: Boolean = true,
        allowMemberDeleteOwnMessages: Boolean = true,
        chatType: String = MeshMessagePayload.CHAT_TYPE_GROUP,
        conversationType: ConversationType = ConversationType.GROUP,
        caption: String = "",
        mediaAlbumId: String? = null,
        mediaAlbumIndex: Int = 0,
        mediaAlbumCount: Int = 1
    ): Int {
        if (!_isRunning.value) {
            updateStatus("Start mesh before sending")
            return 0
        }
        val normalizedMembers = (memberNodeIds + nodeId)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedMembers.size < 2) {
            updateStatus("Group needs at least one peer")
            return 0
        }

        val recipients = synchronized(lock) {
            normalizedMembers
                .filter { it != nodeId }
                .mapNotNull { memberId -> peerIdentityByNodeId[memberId] }
        }
        if (recipients.isEmpty()) {
            publishHello()
            updateStatus("No online group members with synced keys yet")
            return 0
        }

        val prepared = prepareAttachmentFromUri(fileUri) ?: return 0
        val payloadChatId = groupId.ifBlank {
            if (conversationType == ConversationType.CHANNEL) {
                "chn-${UUID.randomUUID().toString().take(10)}"
            } else {
                "grp-${UUID.randomUUID().toString().take(10)}"
            }
        }
        val payloadTitle = groupTitle.trim().ifBlank {
            if (conversationType == ConversationType.CHANNEL) "Mesh Channel" else "Mesh Group"
        }
        val normalizedChatType = if (
            chatType.equals(MeshMessagePayload.CHAT_TYPE_CHANNEL, ignoreCase = true)
        ) {
            MeshMessagePayload.CHAT_TYPE_CHANNEL
        } else {
            MeshMessagePayload.CHAT_TYPE_GROUP
        }
        val normalizedConversationType = if (normalizedChatType == MeshMessagePayload.CHAT_TYPE_CHANNEL) {
            ConversationType.CHANNEL
        } else {
            conversationType
        }
        val normalizedAdmins = adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizedMembers.contains(it) }
            .distinct()
            .ifEmpty { listOf(nodeId) }
        val normalizedModerators = moderatorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizedMembers.contains(it) && !normalizedAdmins.contains(it) }
            .distinct()
        val normalizedOwner = normalizedAdmins.firstOrNull() ?: nodeId
        val dispatch = sendFileChunks(
            recipients = recipients,
            chatId = payloadChatId,
            chatType = normalizedChatType,
            chatTitle = payloadTitle,
            memberNodeIds = normalizedMembers,
            collectiveAdminNodeIds = normalizedAdmins,
            collectiveModeratorNodeIds = normalizedModerators,
            collectiveBroadcastOnly = isBroadcastOnly,
            collectiveAllowMemberReactions = allowMemberReactions,
            collectiveAllowMemberEditOwnMessages = allowMemberEditOwnMessages,
            collectiveAllowMemberDeleteOwnMessages = allowMemberDeleteOwnMessages,
            caption = caption,
            mediaAlbumId = mediaAlbumId,
            mediaAlbumIndex = mediaAlbumIndex,
            mediaAlbumCount = mediaAlbumCount,
            prepared = prepared
        )
        if (!dispatch.accepted) return 0

        val localPath = localStore.saveAttachment(
            transferId = prepared.transferId,
            fileName = prepared.fileName,
            bytes = prepared.originalBytes
        )
        appendMessage(
            ChatMessage(
                id = "file:${prepared.transferId}",
                text = caption.trim().take(MAX_FILE_CAPTION_LENGTH).ifBlank { prepared.fileName },
                originNodeId = nodeId,
                targetNodeId = null,
                relayNodeId = nodeId,
                createdAtMs = prepared.sentAtMs,
                isLocal = true,
                isEncrypted = true,
                conversationId = payloadChatId,
                conversationType = normalizedConversationType,
                conversationTitle = payloadTitle,
                senderAlias = _nodeAlias.value,
                memberNodeIds = normalizedMembers,
                collectiveOwnerNodeId = normalizedOwner,
                collectiveAdminNodeIds = normalizedAdmins,
                collectiveModeratorNodeIds = normalizedModerators,
                collectiveBroadcastOnly = isBroadcastOnly,
                collectiveAllowMemberReactions = allowMemberReactions,
                collectiveAllowMemberEditOwnMessages = allowMemberEditOwnMessages,
                collectiveAllowMemberDeleteOwnMessages = allowMemberDeleteOwnMessages,
                contentType = ChatContentType.FILE,
                deliveryState = if (dispatch.sent) {
                    MessageDeliveryState.SENT
                } else {
                    MessageDeliveryState.PENDING
                },
                attachment = MessageAttachment(
                    transferId = prepared.transferId,
                    fileName = prepared.fileName,
                    mimeType = prepared.mimeType,
                    sizeBytes = prepared.sizeBytes,
                    sha256 = prepared.sha256,
                    compressed = prepared.compressed,
                    localUri = localPath,
                    mediaAlbumId = mediaAlbumId?.trim()?.ifBlank { null },
                    mediaAlbumIndex = mediaAlbumIndex.coerceAtLeast(0),
                    mediaAlbumCount = mediaAlbumCount.coerceAtLeast(1)
                )
            )
        )
        val collectiveLabel = if (normalizedConversationType == ConversationType.CHANNEL) {
            "channel"
        } else {
            "group"
        }
        updateStatus(
            if (dispatch.sent) {
                "Encrypted file sent to ${recipients.size} $collectiveLabel peer(s): ${prepared.fileName}"
            } else {
                "Encrypted file queued for $collectiveLabel delivery: ${prepared.fileName}"
            }
        )
        return recipients.size
    }

    fun saveLocalTextMessage(
        text: String,
        conversationId: String = SAVED_MESSAGES_CONVERSATION_ID,
        conversationTitle: String = SAVED_MESSAGES_TITLE,
        replyToMessageId: String? = null,
        replyToPreview: String? = null,
        forwardedFromAlias: String? = null,
        forwardedFromMessageId: String? = null,
        messageIdOverride: String? = null
    ): Boolean {
        val body = text.trim()
        if (body.isBlank()) return false
        val now = System.currentTimeMillis()
        appendMessage(
            ChatMessage(
                id = messageIdOverride?.trim()?.takeIf { it.isNotBlank() }
                    ?: "local:${UUID.randomUUID()}",
                text = body,
                originNodeId = nodeId,
                targetNodeId = nodeId,
                relayNodeId = nodeId,
                createdAtMs = now,
                isLocal = true,
                isEncrypted = true,
                conversationId = conversationId.ifBlank { SAVED_MESSAGES_CONVERSATION_ID },
                conversationType = ConversationType.DIRECT,
                conversationTitle = conversationTitle.ifBlank { SAVED_MESSAGES_TITLE },
                senderAlias = _nodeAlias.value,
                memberNodeIds = listOf(nodeId),
                replyToMessageId = replyToMessageId?.trim()?.ifBlank { null },
                replyToPreview = replyToPreview?.trim()?.ifBlank { null },
                forwardedFromAlias = forwardedFromAlias?.trim()?.ifBlank { null },
                forwardedFromMessageId = forwardedFromMessageId?.trim()?.ifBlank { null },
                deliveryState = MessageDeliveryState.DELIVERED,
                deliveredAtMs = now,
                deliveredToNodeIds = listOf(nodeId)
            )
        )
        updateStatus("Saved locally")
        return true
    }

    fun saveLocalFileMessage(
        fileUri: Uri,
        conversationId: String = SAVED_MESSAGES_CONVERSATION_ID,
        conversationTitle: String = SAVED_MESSAGES_TITLE,
        caption: String = "",
        mediaAlbumId: String? = null,
        mediaAlbumIndex: Int = 0,
        mediaAlbumCount: Int = 1
    ): Boolean {
        val prepared = prepareAttachmentFromUri(fileUri) ?: return false
        val localPath = localStore.saveAttachment(
            transferId = prepared.transferId,
            fileName = prepared.fileName,
            bytes = prepared.originalBytes
        ) ?: return false
        appendMessage(
            ChatMessage(
                id = "file:${prepared.transferId}",
                text = caption.trim().take(MAX_FILE_CAPTION_LENGTH).ifBlank { prepared.fileName },
                originNodeId = nodeId,
                targetNodeId = nodeId,
                relayNodeId = nodeId,
                createdAtMs = prepared.sentAtMs,
                isLocal = true,
                isEncrypted = true,
                conversationId = conversationId.ifBlank { SAVED_MESSAGES_CONVERSATION_ID },
                conversationType = ConversationType.DIRECT,
                conversationTitle = conversationTitle.ifBlank { SAVED_MESSAGES_TITLE },
                senderAlias = _nodeAlias.value,
                memberNodeIds = listOf(nodeId),
                contentType = ChatContentType.FILE,
                deliveryState = MessageDeliveryState.DELIVERED,
                deliveredAtMs = prepared.sentAtMs,
                deliveredToNodeIds = listOf(nodeId),
                attachment = MessageAttachment(
                    transferId = prepared.transferId,
                    fileName = prepared.fileName,
                    mimeType = prepared.mimeType,
                    sizeBytes = prepared.sizeBytes,
                    sha256 = prepared.sha256,
                    compressed = prepared.compressed,
                    localUri = localPath,
                    mediaAlbumId = mediaAlbumId?.trim()?.ifBlank { null },
                    mediaAlbumIndex = mediaAlbumIndex.coerceAtLeast(0),
                    mediaAlbumCount = mediaAlbumCount.coerceAtLeast(1)
                )
            )
        )
        updateStatus("Saved encrypted file locally: ${prepared.fileName}")
        return true
    }

    fun editLocalMessage(targetMessageId: String, editedText: String): Boolean {
        val changed = applyMessageEdit(
            actorNodeId = nodeId,
            targetMessageId = targetMessageId,
            newText = editedText,
            editedAtMs = System.currentTimeMillis()
        )
        if (changed) updateStatus("Local message edited")
        return changed
    }

    fun deleteLocalMessage(targetMessageId: String): Boolean {
        val changed = applyMessageDelete(
            actorNodeId = nodeId,
            targetMessageId = targetMessageId,
            deletedAtMs = System.currentTimeMillis()
        )
        if (changed) updateStatus("Local message deleted")
        return changed
    }

    fun reactLocalMessage(targetMessageId: String, emoji: String): Boolean {
        val changed = applyMessageReaction(
            actorNodeId = nodeId,
            actorAlias = _nodeAlias.value,
            targetMessageId = targetMessageId,
            emoji = emoji,
            reactedAtMs = System.currentTimeMillis()
        )
        if (changed) updateStatus("Local reaction updated")
        return changed
    }

    fun pinLocalMessage(
        conversationId: String,
        targetMessageId: String,
        pinEnabled: Boolean
    ): Boolean {
        val changed = applyConversationPin(
            actorNodeId = nodeId,
            conversationId = conversationId,
            targetMessageId = targetMessageId,
            pinEnabled = pinEnabled,
            pinnedAtMs = System.currentTimeMillis()
        )
        if (changed) {
            updateStatus(if (pinEnabled) "Local message pinned" else "Local message unpinned")
        }
        return changed
    }

    fun updateLocalMessageTags(targetMessageId: String, tags: List<String>): Boolean {
        val normalizedTags = tags
            .map { tag -> tag.trim().removePrefix("#").take(MAX_SAVED_TAG_LENGTH) }
            .filter { tag -> tag.isNotBlank() }
            .distinctBy { tag -> tag.lowercase(Locale.ROOT) }
            .take(MAX_SAVED_TAGS_PER_MESSAGE)
        var changed = false
        mutateMessages { current ->
            current.map { message ->
                if (message.id != targetMessageId ||
                    message.isDeleted ||
                    !isSavedMessagesConversation(message.conversationId) ||
                    message.savedTags == normalizedTags
                ) {
                    message
                } else {
                    changed = true
                    message.copy(savedTags = normalizedTags)
                }
            }
        }
        if (changed) updateStatus("Saved message tags updated")
        return changed
    }

    fun scheduleTextMessage(
        text: String,
        conversationId: String,
        conversationTitle: String,
        conversationType: ConversationType,
        scheduledAtMs: Long,
        targetNodeId: String? = null,
        memberNodeIds: List<String> = emptyList(),
        adminNodeIds: List<String> = emptyList(),
        moderatorNodeIds: List<String> = emptyList(),
        isBroadcastOnly: Boolean = false,
        allowMemberReactions: Boolean = true,
        allowMemberEditOwnMessages: Boolean = true,
        allowMemberDeleteOwnMessages: Boolean = true,
        replyToMessageId: String? = null,
        replyToPreview: String? = null
    ): String? {
        val now = System.currentTimeMillis()
        val body = text.trim()
        val chatId = conversationId.trim()
        if (body.isBlank() || chatId.isBlank()) return null
        if (scheduledAtMs < now + MIN_SCHEDULE_DELAY_MS ||
            scheduledAtMs > now + MAX_SCHEDULE_AHEAD_MS
        ) {
            return null
        }
        val id = "scheduled:${UUID.randomUUID()}"
        val record = ScheduledMessageRecord(
            id = id,
            text = body,
            conversationId = chatId,
            conversationTitle = conversationTitle.trim().ifBlank { "Mesh Chat" },
            conversationType = conversationType,
            targetNodeId = targetNodeId?.trim()?.ifBlank { null },
            memberNodeIds = memberNodeIds.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            adminNodeIds = adminNodeIds.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            moderatorNodeIds = moderatorNodeIds.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            isBroadcastOnly = isBroadcastOnly,
            allowMemberReactions = allowMemberReactions,
            allowMemberEditOwnMessages = allowMemberEditOwnMessages,
            allowMemberDeleteOwnMessages = allowMemberDeleteOwnMessages,
            replyToMessageId = replyToMessageId?.trim()?.ifBlank { null },
            replyToPreview = replyToPreview?.trim()?.ifBlank { null },
            scheduledAtMs = scheduledAtMs,
            createdAtMs = now
        )
        synchronized(lock) {
            scheduledMessageMap[id] = record
            trimScheduledMessagesLocked()
        }
        persistScheduledMessagesSnapshot()
        updateStatus("Message scheduled")
        return id
    }

    fun cancelScheduledMessage(messageId: String): Boolean {
        val id = messageId.trim()
        if (id.isBlank()) return false
        val removed = synchronized(lock) { scheduledMessageMap.remove(id) != null }
        if (removed) {
            persistScheduledMessagesSnapshot()
            updateStatus("Scheduled message cancelled")
        }
        return removed
    }

    fun sendCollectiveUpdate(
        collectiveId: String,
        collectiveTitle: String,
        conversationType: ConversationType,
        memberNodeIds: List<String>,
        adminNodeIds: List<String>,
        moderatorNodeIds: List<String>,
        isBroadcastOnly: Boolean,
        allowMemberReactions: Boolean = true,
        allowMemberEditOwnMessages: Boolean = true,
        allowMemberDeleteOwnMessages: Boolean = true,
        noteText: String
    ): Boolean {
        if (!_isRunning.value) {
            updateStatus("Start mesh before updating collective")
            return false
        }
        if (conversationType == ConversationType.DIRECT) return false

        val normalizedMembers = (memberNodeIds + nodeId)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedMembers.size < 2) {
            updateStatus("Collective needs at least one peer")
            return false
        }
        val normalizedAdmins = adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizedMembers.contains(it) }
            .distinct()
            .ifEmpty { listOf(nodeId) }
        val normalizedModerators = moderatorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizedMembers.contains(it) && !normalizedAdmins.contains(it) }
            .distinct()
        val normalizedOwner = normalizedAdmins.firstOrNull() ?: nodeId
        val targetNodeIds = normalizedMembers.filter { it != nodeId }
        if (targetNodeIds.isEmpty()) return false

        val chatType = when (conversationType) {
            ConversationType.GROUP -> MeshMessagePayload.CHAT_TYPE_GROUP
            ConversationType.CHANNEL -> MeshMessagePayload.CHAT_TYPE_CHANNEL
            ConversationType.DIRECT -> MeshMessagePayload.CHAT_TYPE_DIRECT
        }
        val now = System.currentTimeMillis()
        val payload = MeshMessagePayload(
            chatId = collectiveId.trim().ifBlank {
                if (conversationType == ConversationType.CHANNEL) {
                    "chn-${UUID.randomUUID().toString().take(10)}"
                } else {
                    "grp-${UUID.randomUUID().toString().take(10)}"
                }
            },
            chatType = chatType,
            chatTitle = collectiveTitle.trim().ifBlank {
                if (conversationType == ConversationType.CHANNEL) "Mesh Channel" else "Mesh Group"
            },
            memberNodeIds = normalizedMembers,
            collectiveOwnerNodeId = normalizedOwner,
            collectiveAdminNodeIds = normalizedAdmins,
            collectiveModeratorNodeIds = normalizedModerators,
            collectiveBroadcastOnly = isBroadcastOnly,
            collectiveAllowMemberReactions = allowMemberReactions,
            collectiveAllowMemberEditOwnMessages = allowMemberEditOwnMessages,
            collectiveAllowMemberDeleteOwnMessages = allowMemberDeleteOwnMessages,
            text = noteText.trim().ifBlank { "Collective settings updated" },
            payloadKind = MeshMessagePayload.KIND_COLLECTIVE_UPDATE,
            messageId = "meta:${UUID.randomUUID()}",
            sentAtMs = now
        )

        val dispatch = dispatchPayloadToTargets(
            plaintext = json.encodeToString(payload),
            targetNodeIds = targetNodeIds,
            messageId = payload.messageId
        )
        if (!dispatch.dispatched) return false

        appendMessage(
            ChatMessage(
                id = payload.messageId ?: "meta:$now",
                text = payload.text,
                originNodeId = nodeId,
                targetNodeId = null,
                relayNodeId = nodeId,
                createdAtMs = now,
                isLocal = true,
                isEncrypted = true,
                isSystem = true,
                conversationId = payload.chatId,
                conversationType = conversationType,
                conversationTitle = payload.chatTitle,
                senderAlias = _nodeAlias.value,
                memberNodeIds = normalizedMembers,
                collectiveOwnerNodeId = normalizedOwner,
                collectiveAdminNodeIds = normalizedAdmins,
                collectiveModeratorNodeIds = normalizedModerators,
                collectiveBroadcastOnly = isBroadcastOnly,
                collectiveAllowMemberReactions = allowMemberReactions,
                collectiveAllowMemberEditOwnMessages = allowMemberEditOwnMessages,
                collectiveAllowMemberDeleteOwnMessages = allowMemberDeleteOwnMessages,
                deliveryState = if (dispatch.sentCount > 0) {
                    MessageDeliveryState.SENT
                } else {
                    MessageDeliveryState.PENDING
                }
            )
        )

        val collectiveLabel = if (conversationType == ConversationType.CHANNEL) {
            "channel"
        } else {
            "group"
        }
        updateStatus(
            when {
                dispatch.sentCount > 0 && dispatch.queuedCount > 0 -> {
                    "Encrypted $collectiveLabel update sent to ${dispatch.sentCount} peer(s), queued for ${dispatch.queuedCount}"
                }
                dispatch.sentCount > 0 -> {
                    "Encrypted $collectiveLabel update sent to ${dispatch.sentCount} peer(s)"
                }
                else -> {
                    "Encrypted $collectiveLabel update queued, waiting for key sync"
                }
            }
        )
        return true
    }

    private fun sendFileChunks(
        recipients: List<PeerIdentity>,
        chatId: String,
        chatType: String,
        chatTitle: String?,
        memberNodeIds: List<String>,
        collectiveAdminNodeIds: List<String>,
        collectiveModeratorNodeIds: List<String>,
        collectiveBroadcastOnly: Boolean,
        collectiveAllowMemberReactions: Boolean = true,
        collectiveAllowMemberEditOwnMessages: Boolean = true,
        collectiveAllowMemberDeleteOwnMessages: Boolean = true,
        caption: String = "",
        mediaAlbumId: String? = null,
        mediaAlbumIndex: Int = 0,
        mediaAlbumCount: Int = 1,
        prepared: OutgoingAttachment
    ): FileDispatchResult {
        val bytes = prepared.transferBytes
        val chunkCount = ((bytes.size + FILE_CHUNK_SIZE - 1) / FILE_CHUNK_SIZE).coerceAtLeast(1)
        val chunkBase64 = ArrayList<String>(chunkCount)
        for (chunkIndex in 0 until chunkCount) {
            val start = chunkIndex * FILE_CHUNK_SIZE
            val end = min(start + FILE_CHUNK_SIZE, bytes.size)
            val chunk = bytes.copyOfRange(start, end)
            chunkBase64 += Base64.encodeToString(chunk, Base64.NO_WRAP)
        }

        val transfer = OutgoingFileTransfer(
            transferId = prepared.transferId,
            chatId = chatId,
            chatType = chatType,
            chatTitle = chatTitle,
            memberNodeIds = memberNodeIds,
            collectiveOwnerNodeId = collectiveAdminNodeIds.firstOrNull { it.isNotBlank() },
            collectiveAdminNodeIds = collectiveAdminNodeIds,
            collectiveModeratorNodeIds = collectiveModeratorNodeIds,
            collectiveBroadcastOnly = collectiveBroadcastOnly,
            collectiveAllowMemberReactions = collectiveAllowMemberReactions,
            collectiveAllowMemberEditOwnMessages = collectiveAllowMemberEditOwnMessages,
            collectiveAllowMemberDeleteOwnMessages = collectiveAllowMemberDeleteOwnMessages,
            fileName = prepared.fileName,
            mimeType = prepared.mimeType,
            sizeBytes = prepared.sizeBytes,
            sha256 = prepared.sha256,
            caption = caption.trim().take(MAX_FILE_CAPTION_LENGTH),
            mediaAlbumId = mediaAlbumId?.trim()?.ifBlank { null },
            mediaAlbumIndex = mediaAlbumIndex.coerceAtLeast(0),
            mediaAlbumCount = mediaAlbumCount.coerceAtLeast(1),
            compressed = prepared.compressed,
            sentAtMs = prepared.sentAtMs,
            chunkCount = chunkCount,
            chunksBase64 = chunkBase64,
            recipients = recipients.associate { identity ->
                identity.nodeId to OutgoingTransferRecipientState(
                    nodeId = identity.nodeId,
                    alias = identity.alias,
                    chunkCount = chunkCount
                )
            }.toMutableMap(),
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis()
        )

        synchronized(lock) {
            outgoingTransfers[transfer.transferId] = transfer
            val initialCursor = if (transfer.chunkCount > 0) {
                min(INITIAL_TRANSFER_WINDOW, transfer.chunkCount) % transfer.chunkCount
            } else {
                0
            }
            transfer.recipients.values.forEach { state ->
                state.nextChunkCursor = initialCursor
            }
            trimOutgoingTransfersLocked()
        }
        persistOutgoingTransfersSnapshot(force = true)

        val initialWindow = (0 until chunkCount).take(INITIAL_TRANSFER_WINDOW)
        val initialSentAtMs = System.currentTimeMillis()
        synchronized(lock) {
            recipients.forEach { identity ->
                transfer.recipients[identity.nodeId]?.lastSentAtMs = initialSentAtMs
                transfer.recipients[identity.nodeId]?.dispatchInFlight = true
                activeTransferNodeIds += identity.nodeId
            }
            transfer.updatedAtMs = initialSentAtMs
        }
        dispatchTransferChunkIndexes(
            transfer = transfer,
            indexes = initialWindow,
            recipients = recipients
        )
        // The BLE write is asynchronous. The transfer remains queued until
        // the recipient acknowledges chunks, so the UI never claims delivery early.
        return FileDispatchResult(sent = false, queued = true)
    }

    private fun dispatchTransferChunkIndexes(
        transfer: OutgoingFileTransfer,
        indexes: List<Int>,
        recipients: List<PeerIdentity>
    ) {
        if (indexes.isEmpty() || recipients.isEmpty()) {
            synchronized(lock) {
                recipients.forEach { identity ->
                    transfer.recipients[identity.nodeId]?.dispatchInFlight = false
                    activeTransferNodeIds.remove(identity.nodeId)
                }
            }
            return
        }
        scope.launch(Dispatchers.IO) {
            val sent = runCatching {
                sendTransferChunkIndexes(
                    transfer = transfer,
                    indexes = indexes,
                    recipients = recipients
                )
            }.getOrDefault(false)
            synchronized(lock) {
                recipients.forEach { identity ->
                    val state = transfer.recipients[identity.nodeId]
                    state?.dispatchInFlight = false
                    if (!sent) {
                        state?.nextChunkCursor = indexes.firstOrNull() ?: state?.nextChunkCursor ?: 0
                        state?.lastSentAtMs = 0L
                    } else {
                        state?.lastSentAtMs = System.currentTimeMillis()
                    }
                    activeTransferNodeIds.remove(identity.nodeId)
                }
            }
            persistOutgoingTransfersSnapshot(force = !sent)
        }
    }

    private suspend fun sendTransferChunkIndexes(
        transfer: OutgoingFileTransfer,
        indexes: List<Int>,
        recipients: List<PeerIdentity>
    ): Boolean {
        if (indexes.isEmpty() || recipients.isEmpty()) return true
        for (chunkIndex in indexes) {
            if (chunkIndex < 0 || chunkIndex >= transfer.chunkCount) continue
            val payload = MeshMessagePayload(
                chatId = transfer.chatId,
                chatType = transfer.chatType,
                chatTitle = transfer.chatTitle,
                memberNodeIds = transfer.memberNodeIds,
                collectiveOwnerNodeId = transfer.collectiveOwnerNodeId,
                collectiveAdminNodeIds = transfer.collectiveAdminNodeIds,
                collectiveModeratorNodeIds = transfer.collectiveModeratorNodeIds,
                collectiveBroadcastOnly = transfer.collectiveBroadcastOnly,
                collectiveAllowMemberReactions = transfer.collectiveAllowMemberReactions,
                collectiveAllowMemberEditOwnMessages = transfer.collectiveAllowMemberEditOwnMessages,
                collectiveAllowMemberDeleteOwnMessages = transfer.collectiveAllowMemberDeleteOwnMessages,
                payloadKind = MeshMessagePayload.KIND_FILE_CHUNK,
                transferId = transfer.transferId,
                fileName = transfer.fileName,
                mimeType = transfer.mimeType,
                fileSizeBytes = transfer.sizeBytes,
                fileSha256 = transfer.sha256,
                fileCaption = transfer.caption,
                mediaAlbumId = transfer.mediaAlbumId,
                mediaAlbumIndex = transfer.mediaAlbumIndex,
                mediaAlbumCount = transfer.mediaAlbumCount,
                chunkIndex = chunkIndex,
                chunkCount = transfer.chunkCount,
                chunkBase64 = transfer.chunksBase64[chunkIndex],
                compressed = transfer.compressed,
                sentAtMs = transfer.sentAtMs
            )
            val sentCount = sendPayloadToRecipientsAwaited(
                plaintext = json.encodeToString(payload),
                recipients = recipients
            )
            if (sentCount <= 0) {
                updateStatus("File chunk ${chunkIndex + 1}/${transfer.chunkCount} failed")
                return false
            }
        }
        return true
    }

    private fun prepareAttachmentFromUri(uri: Uri): OutgoingAttachment? {
        val resolver = context.contentResolver
        val (displayName, reportedSize) = queryFileMeta(uri)
        if (reportedSize != null && reportedSize > MAX_FILE_BYTES) {
            updateStatus("File is too large: maximum ${humanSize(MAX_FILE_BYTES.toLong())}")
            return null
        }
        val fileName = displayName ?: "file_${System.currentTimeMillis()}"
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"

        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var zeroReads = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) {
                        zeroReads++
                        if (zeroReads >= MAX_ZERO_READS) {
                            throw java.io.IOException("file provider returned no data")
                        }
                        continue
                    }
                    zeroReads = 0
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_FILE_BYTES) {
                        return@runCatching null
                    }
                }
                out.toByteArray()
            }
        }.getOrNull()
        if (bytes == null) {
            updateStatus("File read failed or file is too large")
            return null
        }
        if (bytes.isEmpty()) {
            updateStatus("File is empty")
            return null
        }

        val compressedCandidate = gzip(bytes)
        val useCompressed = compressedCandidate != null &&
            compressedCandidate.size + 64 < bytes.size
        val transferBytes = if (useCompressed) compressedCandidate!! else bytes
        if (transferBytes.size > MAX_TRANSFER_BYTES) {
            updateStatus("File is too large for BLE mesh transport")
            return null
        }

        val sizeBytes = reportedSize?.takeIf { it > 0 } ?: bytes.size.toLong()
        return OutgoingAttachment(
            transferId = UUID.randomUUID().toString(),
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            sha256 = sha256Hex(bytes),
            compressed = useCompressed,
            sentAtMs = System.currentTimeMillis(),
            originalBytes = bytes,
            transferBytes = transferBytes
        )
    }

    private fun queryFileMeta(uri: Uri): Pair<String?, Long?> {
        var name: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        name = cursor.getString(nameIdx)
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        }
        return name to size
    }

    private fun sendPayloadToRecipientsDetailed(
        plaintext: String,
        recipients: List<PeerIdentity>,
        cacheForRelay: Boolean = true
    ): Set<String> {
        if (recipients.isEmpty()) return emptySet()
        val sentNodeIds = linkedSetOf<String>()
        recipients.forEach { peerIdentity ->
            runCatching {
                val packet = crypto.encryptForPeer(
                    plaintext = plaintext,
                    targetNodeId = peerIdentity.nodeId,
                    peerEncryptionPublicKey = peerIdentity.encryptionPublicKey,
                    maxHops = MESSAGE_MAX_HOPS
                )
                rememberFrame(packet.id)
                val payload = json.encodeToString(packet).toByteArray(Charsets.UTF_8)
                val dispatched = broadcastPayload(
                    frameId = packet.id,
                    payload = payload,
                    excludedAddress = null,
                    cacheForRelay = cacheForRelay
                )
                if (dispatched) {
                    sentNodeIds += peerIdentity.nodeId
                }
            }.onFailure {
                updateStatus("Encrypt/send failed for ${peerIdentity.alias}")
            }
        }
        return sentNodeIds
    }

    private fun sendPayloadToRecipients(
        plaintext: String,
        recipients: List<PeerIdentity>,
        cacheForRelay: Boolean = true
    ): Int {
        return sendPayloadToRecipientsDetailed(
            plaintext = plaintext,
            recipients = recipients,
            cacheForRelay = cacheForRelay
        ).size
    }

    private suspend fun sendPayloadToRecipientsAwaited(
        plaintext: String,
        recipients: List<PeerIdentity>,
        cacheForRelay: Boolean = true
    ): Int {
        if (recipients.isEmpty()) return 0
        var sentCount = 0
        recipients.forEach { peerIdentity ->
            val sent = runCatching {
                val packet = crypto.encryptForPeer(
                    plaintext = plaintext,
                    targetNodeId = peerIdentity.nodeId,
                    peerEncryptionPublicKey = peerIdentity.encryptionPublicKey,
                    maxHops = MESSAGE_MAX_HOPS
                )
                rememberFrame(packet.id)
                val payload = json.encodeToString(packet).toByteArray(Charsets.UTF_8)
                broadcastPayloadAndAwait(
                    frameId = packet.id,
                    payload = payload,
                    cacheForRelay = cacheForRelay
                )
            }.getOrElse {
                updateStatus("Encrypt/send failed for ${peerIdentity.alias}")
                false
            }
            if (sent) sentCount++
        }
        return sentCount
    }

    private fun dispatchPayloadToTargets(
        plaintext: String,
        targetNodeIds: List<String>,
        messageId: String? = null
    ): PayloadDispatchResult {
        val normalizedTargets = targetNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && it != nodeId }
            .distinct()
        if (normalizedTargets.isEmpty()) {
            return PayloadDispatchResult(sentCount = 0, queuedCount = 0)
        }
        val recipients = synchronized(lock) {
            normalizedTargets
                .mapNotNull { targetNodeId -> peerIdentityByNodeId[targetNodeId] }
                .distinctBy { it.nodeId }
        }
        val sentNodeIds = if (recipients.isEmpty()) {
            emptySet()
        } else {
            sendPayloadToRecipientsDetailed(
                plaintext = plaintext,
                recipients = recipients
            )
        }
        val queuedTargets = normalizedTargets.filterNot { sentNodeIds.contains(it) }
        if (queuedTargets.isNotEmpty()) {
            enqueuePendingPayload(
                plaintext = plaintext,
                targetNodeIds = queuedTargets,
                messageId = messageId
            )
            publishHello()
        }
        return PayloadDispatchResult(
            sentCount = sentNodeIds.size,
            queuedCount = queuedTargets.size
        )
    }

    private fun scanner(): BluetoothLeScanner? = adapter?.bluetoothLeScanner

    private fun advertiser(): BluetoothLeAdvertiser? = adapter?.bluetoothLeAdvertiser

    private fun startPresenceJob() {
        presenceJob?.cancel()
        presenceJob = scope.launch(Dispatchers.IO) {
            while (isActive && _isRunning.value) {
                delay(PRESENCE_INTERVAL_MS)
                if (_isRunning.value) {
                    connectRelayIfNeeded()
                    if (!BLE_ONLY_MODE) {
                        triggerWifiP2pDiscovery(force = false)
                    }
                    publishHello()
                    flushRelayOutbox()
                    flushPendingTransfers()
                    flushPendingPayloads()
                    flushScheduledMessages()
                }
            }
        }
    }

    private fun startTransferFlushJob() {
        transferFlushJob?.cancel()
        transferFlushJob = scope.launch(Dispatchers.IO) {
            while (isActive && _isRunning.value) {
                delay(600L)
                if (_isRunning.value) {
                    flushPendingTransfers()
                }
            }
        }
    }

    private fun loadOrCreateNodeId(): String {
        val prefs = context.getSharedPreferences(PREF_NODE, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_NODE_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val generated = UUID.randomUUID().toString().substring(0, 8)
        prefs.edit().putString(KEY_NODE_ID, generated).apply()
        return generated
    }

    @SuppressLint("MissingPermission")
    private fun openGattServer() {
        val server = bluetoothManager?.openGattServer(context, gattServerCallback)
            ?: throw IllegalStateException("Could not open GATT server")

        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val characteristic = BluetoothGattCharacteristic(
            MESSAGE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(characteristic)

        val notifyCharacteristic = BluetoothGattCharacteristic(
            MESSAGE_NOTIFY_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            CLIENT_CONFIG_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        notifyCharacteristic.addDescriptor(cccd)
        service.addCharacteristic(notifyCharacteristic)

        check(server.addService(service)) { "Could not add BLE service" }
        gattServer = server
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingWithFallback(): Boolean {
        val advertise = advertiser() ?: return false
        val localAdapter = adapter ?: return false
        if (!localAdapter.isMultipleAdvertisementSupported) {
            advertisingActive = false
            advertisingUsesMinimalPayload = true
            return false
        }

        advertiseRetryDone = false
        advertisingUsesMinimalPayload = false
        advertisingActive = false

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        runCatching { advertise.stopAdvertising(advertiseCallback) }
        val data = buildAdvertiseData(includeNodeId = true)
        advertise.startAdvertising(settings, data, advertiseCallback)
        return true
    }

    private fun buildAdvertiseData(includeNodeId: Boolean): AdvertiseData {
        val builder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
        if (includeNodeId) {
            // Keep the service UUID filter and fit a compact node ID into legacy BLE advertising.
            builder.addManufacturerData(MESH_MANUFACTURER_ID, compactNodeIdBytes())
        }
        return builder.build()
    }

    private fun compactNodeIdBytes(): ByteArray {
        val normalized = nodeId.take(8).padEnd(8, '0')
        return ByteArray(4) { index ->
            normalized.substring(index * 2, index * 2 + 2)
                .toIntOrNull(16)
                ?.toByte()
                ?: 0
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning(): Boolean {
        val bleScanner = scanner() ?: return false
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bleScanner.startScan(filters, settings, scanCallback)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(device: BluetoothDevice, advertisedNodeId: String? = null) {
        val address = device.address ?: return
        val knownNodeId = advertisedNodeId?.trim()?.ifBlank { null }
            ?: synchronized(lock) { addressToNodeId[address] }
        // Do not connect on an anonymous scan result: wait for the stable node ID
        // so only the lexicographically elected side opens a single GATT link.
        if (knownNodeId == null) return
        if (nodeId.compareTo(knownNodeId) >= 0) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (clientGatts.containsKey(address) || connectingAddresses.contains(address)) return
            if (now < (connectionRetryAtMs[address] ?: 0L)) return
            val activeAddress = activeAddressByNodeId[knownNodeId]
            if (activeAddress != null && activeAddress != address) return
            if (connectingNodeIds.contains(knownNodeId)) return
            if (now < (connectionRetryAtNodeIdMs[knownNodeId] ?: 0L)) return
            connectingNodeIds.add(knownNodeId)
            activeAddressByNodeId[knownNodeId] = address
            addressToNodeId[address] = knownNodeId
            connectingAddresses.add(address)
        }

        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattClientCallback)
        }

        if (gatt == null) {
            synchronized(lock) {
                connectingAddresses.remove(address)
                connectingNodeIds.remove(knownNodeId)
                if (activeAddressByNodeId[knownNodeId] == address) {
                    activeAddressByNodeId.remove(knownNodeId)
                }
            }
            scheduleConnectionRetry(address, status = null, nodeId = knownNodeId)
            upsertPeer(address = address, connected = false)
        }
    }

    private fun scheduleConnectionRetry(address: String, status: Int?, nodeId: String? = null) {
        if (address.isBlank()) return
        val retryDelayMs = synchronized(lock) {
            val attempts = (connectionRetryAttempts[address] ?: 0) + 1
            connectionRetryAttempts[address] = attempts.coerceAtMost(MAX_CONNECTION_RETRY_ATTEMPTS)
            val exponent = (attempts - 1).coerceIn(0, 6)
            val delayMs = (CONNECTION_RETRY_BASE_DELAY_MS * (1L shl exponent))
                .coerceAtMost(MAX_CONNECTION_RETRY_DELAY_MS)
            connectionRetryAtMs[address] = System.currentTimeMillis() + delayMs
            nodeId?.let { stableId ->
                val nodeAttempts = (connectionRetryNodeAttempts[stableId] ?: 0) + 1
                connectionRetryNodeAttempts[stableId] =
                    nodeAttempts.coerceAtMost(MAX_CONNECTION_RETRY_ATTEMPTS)
                val nodeExponent = (nodeAttempts - 1).coerceIn(0, 6)
                val nodeDelayMs = (CONNECTION_RETRY_BASE_DELAY_MS * (1L shl nodeExponent))
                    .coerceAtMost(MAX_CONNECTION_RETRY_DELAY_MS)
                connectionRetryAtNodeIdMs[stableId] = System.currentTimeMillis() + nodeDelayMs
            }
            delayMs
        }
        Log.w(
            BLE_TAG,
            "BLE connection failed address=${address.takeLast(5)} status=${status ?: "null"}; " +
                "retry in ${retryDelayMs}ms"
        )
    }

    private fun clearConnectionRetry(address: String) {
        synchronized(lock) {
            val nodeId = addressToNodeId[address]
            connectionRetryAtMs.remove(address)
            connectionRetryAttempts.remove(address)
            nodeId?.let {
                connectionRetryAtNodeIdMs.remove(it)
                connectionRetryNodeAttempts.remove(it)
            }
        }
    }

    private fun publishHello(excludedAddress: String? = null) {
        if (!_isRunning.value) return
        val helloPacket = crypto.createHelloPacket(maxHops = HELLO_MAX_HOPS)
        rememberFrame(helloPacket.frameId)
        val payload = json.encodeToString(helloPacket).toByteArray(Charsets.UTF_8)
        broadcastPayload(
            frameId = helloPacket.frameId,
            payload = payload,
            excludedAddress = excludedAddress,
            cacheForRelay = false
        )
    }

    /**
     * Returns only application-level BLE links that can carry a complete frame.
     * A random Bluetooth device never becomes a MeshGram route.
     */
    private fun readyBleTargets(excludedAddress: String?): Pair<List<BluetoothGatt>, List<BluetoothDevice>> {
        return synchronized(lock) {
            val clientTargets = clientGatts
                .filterKeys { address ->
                    address != excludedAddress && clientReadyAddresses.contains(address)
                }
                .values
                .toList()
            val clientAddresses = clientTargets.mapNotNull { it.device.address }.toSet()
            val serverTargets = serverConnectedDevices
                .filterKeys { address ->
                    address != excludedAddress &&
                        notifyEnabledAddresses.contains(address) &&
                        !clientAddresses.contains(address)
                }
                .values
                .toList()
            clientTargets to serverTargets
        }
    }

    private fun hasReadyBleTransport(excludedAddress: String? = null): Boolean {
        val (clientTargets, serverTargets) = readyBleTargets(excludedAddress)
        return clientTargets.isNotEmpty() || serverTargets.isNotEmpty()
    }

    private fun isInternetAvailable(): Boolean {
        val manager = connectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @SuppressLint("MissingPermission")
    private fun broadcastPayload(
        frameId: String,
        payload: ByteArray,
        excludedAddress: String?,
        cacheForRelay: Boolean = true,
        sendToRelay: Boolean = true,
        sendToWifiLan: Boolean = true,
        completion: CompletableDeferred<Boolean>? = null
    ): Boolean {
        if (!_isRunning.value) return false
        val allowNetworkTransports = !BLE_ONLY_MODE
        if (cacheForRelay && (BLE_ONLY_MODE || allowNetworkTransports)) {
            cacheRelayFrame(frameId = frameId, payload = payload)
        }
        val bleTargets = readyBleTargets(excludedAddress)
        val recipients = bleTargets.first
        val notifyTargets = bleTargets.second
        if (recipients.isEmpty() && notifyTargets.isEmpty()) {
            // No MeshGram BLE route is currently available. The relay is an
            // app-level fallback; it does not switch or disable phone radios.
            var networkAccepted = false
            if (sendToRelay && allowNetworkTransports) {
                networkAccepted = publishFrameToRelay(frameId = frameId, payload = payload)
            }
            if (sendToWifiLan && allowNetworkTransports && wifiLanActive) {
                publishPayloadToWifiLan(frameId = frameId, payload = payload)
                networkAccepted = true
            }
            completion?.complete(networkAccepted)
            return networkAccepted
        }

        // BLE is the preferred route. Do not publish the same outbound frame
        // to the internet while a MeshGram BLE path is available.
        if (allowNetworkTransports && hasReadyBleTransport(excludedAddress)) {
            disconnectRelay(reason = "BLE route preferred")
        }

        val remainingTransports = AtomicInteger(recipients.size + notifyTargets.size)
        val anyTransportSucceeded = AtomicBoolean(false)
        fun completeTransport(success: Boolean) {
            if (success) anyTransportSucceeded.set(true)
            if (remainingTransports.decrementAndGet() == 0) {
                completion?.complete(anyTransportSucceeded.get())
            }
        }

        var dispatched = false
        recipients.forEach { gatt ->
            val address = gatt.device.address ?: run {
                completeTransport(false)
                return@forEach
            }
            val packets = packetize(
                payload = payload,
                frameKey = frameId.hashCode(),
                chunkPayloadSize = chunkPayloadSizeForAddress(address)
            )
            if (packets.isEmpty()) {
                completeTransport(false)
                if (BLE_ONLY_MODE || (!sendToRelay && !sendToWifiLan)) {
                    updateStatus("Packet too large for BLE transport")
                }
                return@forEach
            }
            dispatched = true
            scope.launch(Dispatchers.IO) {
                transportMutexForAddress(address).withLock {
                    val sent = sendPacketsWithRetries(packets = packets) { packet ->
                        writePacket(gatt = gatt, packet = packet)
                    }
                    if (!sent) {
                        Log.w(BLE_TAG, "BLE frame send failed address=${address.takeLast(5)}")
                        scheduleFrameRetry(frameId, cacheForRelay)
                    }
                    completeTransport(sent)
                }
            }
        }

        notifyTargets.forEach { device ->
            val address = device.address ?: run {
                completeTransport(false)
                return@forEach
            }
            val packets = packetize(
                payload = payload,
                frameKey = frameId.hashCode(),
                chunkPayloadSize = chunkPayloadSizeForAddress(address)
            )
            if (packets.isEmpty()) {
                completeTransport(false)
                if (BLE_ONLY_MODE || (!sendToRelay && !sendToWifiLan)) {
                    updateStatus("Packet too large for BLE transport")
                }
                return@forEach
            }
            dispatched = true
            scope.launch(Dispatchers.IO) {
                transportMutexForAddress(address).withLock {
                    val sent = sendPacketsWithRetries(packets = packets) { packet ->
                        notifyPacket(device = device, packet = packet)
                    }
                    if (!sent) {
                        Log.w(BLE_TAG, "BLE notify failed address=${address.takeLast(5)}")
                        scheduleFrameRetry(frameId, cacheForRelay)
                    }
                    completeTransport(sent)
                }
            }
        }
        return dispatched
    }

    private suspend fun broadcastPayloadAndAwait(
        frameId: String,
        payload: ByteArray,
        cacheForRelay: Boolean = true
    ): Boolean {
        val completion = CompletableDeferred<Boolean>()
        if (!broadcastPayload(
                frameId = frameId,
                payload = payload,
                excludedAddress = null,
                cacheForRelay = cacheForRelay,
                completion = completion
            )
        ) {
            return false
        }
        return completion.await()
    }

    private fun scheduleFrameRetry(frameId: String, cacheForRelay: Boolean) {
        if (!cacheForRelay || !_isRunning.value) return
        synchronized(lock) {
            relayOutbox[frameId]?.lastSentAtMs = 0L
        }
        persistRelayOutboxSnapshot()
        scope.launch(Dispatchers.IO) {
            delay(BLE_FRAME_RETRY_DELAY_MS)
            flushRelayOutbox()
        }
    }

    private fun cacheRelayFrame(frameId: String, payload: ByteArray) {
        val shouldPersist = synchronized(lock) {
            val now = System.currentTimeMillis()
            relayOutbox[frameId] = RelayFrame(
                frameId = frameId,
                payload = payload,
                createdAtMs = now,
                lastSentAtMs = now
            )
            trimRelayOutboxLocked(now)
            true
        }
        if (shouldPersist) {
            persistRelayOutboxSnapshot()
        }
    }

    private fun flushRelayOutbox() {
        if (!_isRunning.value) return
        val result = synchronized(lock) {
            val now = System.currentTimeMillis()
            val beforeSize = relayOutbox.size
            trimRelayOutboxLocked(now)
            val frames = relayOutbox.values
                .filter { frame ->
                    now - frame.createdAtMs <= RELAY_OUTBOX_TTL_MS &&
                        now - frame.lastSentAtMs >= RELAY_OUTBOX_RESEND_GAP_MS
                }
                .take(RELAY_OUTBOX_FLUSH_BATCH)
                .map { frame ->
                    frame.lastSentAtMs = now
                    frame.copy()
                }
            val changed = relayOutbox.size != beforeSize || frames.isNotEmpty()
            frames to changed
        }
        val frames = result.first
        if (result.second) {
            persistRelayOutboxSnapshot()
        }
        frames.forEach { frame ->
            broadcastPayload(
                frameId = frame.frameId,
                payload = frame.payload,
                excludedAddress = null,
                cacheForRelay = false,
                sendToRelay = true,
                sendToWifiLan = true
            )
        }
    }

    private fun trimRelayOutboxLocked(nowMs: Long = System.currentTimeMillis()) {
        val iterator = relayOutbox.iterator()
        while (iterator.hasNext()) {
            val (_, relayFrame) = iterator.next()
            if (nowMs - relayFrame.createdAtMs > RELAY_OUTBOX_TTL_MS) {
                iterator.remove()
            }
        }
        while (relayOutbox.size > MAX_RELAY_OUTBOX_FRAMES) {
            val first = relayOutbox.entries.firstOrNull()?.key ?: break
            relayOutbox.remove(first)
        }
    }

    private fun restoreRelayOutboxFromStore() {
        val persisted = localStore.loadRelayFrames()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            relayOutbox.clear()
            persisted
                .sortedBy { it.createdAtMs }
                .forEach { record ->
                    if (record.frameId.isBlank()) return@forEach
                    val payload = runCatching {
                        Base64.decode(record.payloadBase64, Base64.NO_WRAP)
                    }.getOrNull() ?: return@forEach
                    if (payload.isEmpty() || payload.size > MAX_RELAY_FRAME_PAYLOAD_BYTES) {
                        return@forEach
                    }
                    val payloadType = runCatching {
                        json.parseToJsonElement(payload.decodeToString())
                            .jsonObject["type"]
                            ?.jsonPrimitive
                            ?.content
                    }.getOrNull()
                    if (payloadType == HelloPacket.TYPE) {
                        return@forEach
                    }
                    if (now - record.createdAtMs > RELAY_OUTBOX_TTL_MS) {
                        return@forEach
                    }
                    relayOutbox[record.frameId] = RelayFrame(
                        frameId = record.frameId,
                        payload = payload,
                        createdAtMs = record.createdAtMs,
                        lastSentAtMs = maxOf(record.lastSentAtMs, record.createdAtMs)
                    )
            }
            trimRelayOutboxLocked(now)
        }
        persistRelayOutboxSnapshot()
    }

    private fun persistRelayOutboxSnapshot() {
        val snapshot = synchronized(lock) {
            relayOutbox.values
                .sortedBy { it.createdAtMs }
                .map { frame ->
                    RelayFrameRecord(
                        frameId = frame.frameId,
                        payloadBase64 = Base64.encodeToString(frame.payload, Base64.NO_WRAP),
                        createdAtMs = frame.createdAtMs,
                        lastSentAtMs = frame.lastSentAtMs
                    )
                }
        }
        localStore.persistRelayFrames(snapshot)
    }

    private fun restoreOutgoingTransfersFromStore() {
        val persisted = localStore.loadOutgoingTransfers()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            outgoingTransfers.clear()
            persisted
                .sortedBy { it.createdAtMs }
                .forEach { record ->
                    val transferId = record.transferId.trim().ifBlank { return@forEach }
                    val chunkCount = record.chunkCount.coerceAtLeast(1)
                    val chunksBase64 = record.chunksBase64
                        .take(chunkCount)
                        .map { it.trim() }
                    if (chunksBase64.size != chunkCount || chunksBase64.any { it.isBlank() }) {
                        return@forEach
                    }

                    val createdAtMs = record.createdAtMs.takeIf { it > 0 } ?: now
                    if (now - createdAtMs > OUTGOING_TRANSFER_TTL_MS) {
                        return@forEach
                    }

                    val normalizedRecipients = linkedMapOf<String, OutgoingTransferRecipientState>()
                    record.recipients.forEach recipientLoop@{ recipient ->
                        val recipientNodeId = recipient.nodeId.trim().ifBlank { return@recipientLoop }
                        val acked = recipient.ackedChunkIndexes
                            .filter { it in 0 until chunkCount }
                            .toMutableSet()
                        if (acked.size >= chunkCount) return@recipientLoop
                        val cursor = recipient.nextChunkCursor
                            .coerceIn(0, (chunkCount - 1).coerceAtLeast(0))
                        normalizedRecipients[recipientNodeId] = OutgoingTransferRecipientState(
                            nodeId = recipientNodeId,
                            alias = recipient.alias.trim().ifBlank { recipientNodeId.take(12) },
                            chunkCount = chunkCount,
                            ackedChunks = acked,
                            lastAckAtMs = recipient.lastAckAtMs.coerceAtLeast(0L),
                            lastSentAtMs = recipient.lastSentAtMs.coerceAtLeast(0L),
                            nextChunkCursor = cursor
                        )
                    }
                    if (normalizedRecipients.isEmpty()) return@forEach

                    val memberNodeIds = (record.memberNodeIds + nodeId)
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()

                    outgoingTransfers[transferId] = OutgoingFileTransfer(
                        transferId = transferId,
                        chatId = record.chatId.trim().ifBlank {
                            ChatMessage.LEGACY_BROADCAST_CONVERSATION_ID
                        },
                        chatType = record.chatType.trim().ifBlank {
                            MeshMessagePayload.CHAT_TYPE_DIRECT
                        },
                        chatTitle = record.chatTitle?.trim()?.ifBlank { null },
                        memberNodeIds = memberNodeIds,
                        collectiveOwnerNodeId = record.collectiveOwnerNodeId?.trim()?.ifBlank { null },
                        collectiveAdminNodeIds = record.collectiveAdminNodeIds
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinct(),
                        collectiveModeratorNodeIds = record.collectiveModeratorNodeIds
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinct(),
                        collectiveBroadcastOnly = record.collectiveBroadcastOnly,
                        collectiveAllowMemberReactions = record.collectiveAllowMemberReactions,
                        collectiveAllowMemberEditOwnMessages = record.collectiveAllowMemberEditOwnMessages,
                        collectiveAllowMemberDeleteOwnMessages = record.collectiveAllowMemberDeleteOwnMessages,
                        fileName = record.fileName.trim().ifBlank { "file_$transferId" },
                        mimeType = record.mimeType.trim().ifBlank { "application/octet-stream" },
                        sizeBytes = record.sizeBytes.coerceAtLeast(0L),
                        sha256 = record.sha256.trim(),
                        caption = record.caption.trim().take(MAX_FILE_CAPTION_LENGTH),
                        mediaAlbumId = record.mediaAlbumId?.trim()?.ifBlank { null },
                        mediaAlbumIndex = record.mediaAlbumIndex.coerceAtLeast(0),
                        mediaAlbumCount = record.mediaAlbumCount.coerceAtLeast(1),
                        compressed = record.compressed,
                        sentAtMs = record.sentAtMs.takeIf { it > 0 } ?: createdAtMs,
                        chunkCount = chunkCount,
                        chunksBase64 = chunksBase64,
                        recipients = normalizedRecipients,
                        createdAtMs = createdAtMs,
                        updatedAtMs = maxOf(record.updatedAtMs, createdAtMs)
                    )
                }
            trimOutgoingTransfersLocked(now)
        }
        persistOutgoingTransfersSnapshot(force = true)
    }

    private fun persistOutgoingTransfersSnapshot(force: Boolean = false) {
        publishOutgoingTransferProgressSnapshot()
        val snapshot = synchronized(lock) {
            val now = System.currentTimeMillis()
            if (!force && now - lastOutgoingTransferSnapshotAtMs < OUTGOING_TRANSFER_PERSIST_GAP_MS) {
                null
            } else {
                lastOutgoingTransferSnapshotAtMs = now
                outgoingTransfers.values
                    .sortedBy { it.createdAtMs }
                    .map { transfer ->
                        OutgoingFileTransferRecord(
                            transferId = transfer.transferId,
                            chatId = transfer.chatId,
                            chatType = transfer.chatType,
                            chatTitle = transfer.chatTitle,
                            memberNodeIds = transfer.memberNodeIds,
                            collectiveOwnerNodeId = transfer.collectiveOwnerNodeId,
                            collectiveAdminNodeIds = transfer.collectiveAdminNodeIds,
                            collectiveModeratorNodeIds = transfer.collectiveModeratorNodeIds,
                            collectiveBroadcastOnly = transfer.collectiveBroadcastOnly,
                            collectiveAllowMemberReactions = transfer.collectiveAllowMemberReactions,
                            collectiveAllowMemberEditOwnMessages = transfer.collectiveAllowMemberEditOwnMessages,
                            collectiveAllowMemberDeleteOwnMessages = transfer.collectiveAllowMemberDeleteOwnMessages,
                            fileName = transfer.fileName,
                            mimeType = transfer.mimeType,
                            sizeBytes = transfer.sizeBytes,
                            sha256 = transfer.sha256,
                            caption = transfer.caption,
                            mediaAlbumId = transfer.mediaAlbumId,
                            mediaAlbumIndex = transfer.mediaAlbumIndex,
                            mediaAlbumCount = transfer.mediaAlbumCount,
                            compressed = transfer.compressed,
                            sentAtMs = transfer.sentAtMs,
                            chunkCount = transfer.chunkCount,
                            chunksBase64 = transfer.chunksBase64,
                            recipients = transfer.recipients.values
                                .sortedBy { it.nodeId }
                                .map { recipient ->
                                    OutgoingTransferRecipientRecord(
                                        nodeId = recipient.nodeId,
                                        alias = recipient.alias,
                                        chunkCount = recipient.chunkCount,
                                        ackedChunkIndexes = recipient.ackedChunks.toList().sorted(),
                                        lastAckAtMs = recipient.lastAckAtMs,
                                        lastSentAtMs = recipient.lastSentAtMs,
                                        nextChunkCursor = recipient.nextChunkCursor
                                    )
                                },
                            createdAtMs = transfer.createdAtMs,
                            updatedAtMs = transfer.updatedAtMs
                        )
                    }
            }
        } ?: return
        localStore.persistOutgoingTransfers(snapshot)
    }

    private fun publishOutgoingTransferProgressSnapshot() {
        val snapshot = synchronized(lock) {
            outgoingTransfers.values
                .sortedBy { it.createdAtMs }
                .map { transfer ->
                    val recipients = transfer.recipients.values
                        .sortedBy { it.alias.lowercase() }
                        .map { recipient ->
                            FileTransferRecipientProgress(
                                nodeId = recipient.nodeId,
                                alias = recipient.alias,
                                acknowledgedChunks = recipient.ackedChunks.size
                                    .coerceAtMost(transfer.chunkCount),
                                totalChunks = transfer.chunkCount,
                                lastAcknowledgedAtMs = recipient.lastAckAtMs
                            )
                        }
                    OutgoingFileTransferProgress(
                        transferId = transfer.transferId,
                        conversationId = transfer.chatId,
                        fileName = transfer.fileName,
                        sizeBytes = transfer.sizeBytes,
                        acknowledgedChunks = recipients.sumOf { it.acknowledgedChunks },
                        totalChunks = transfer.chunkCount * recipients.size,
                        completedRecipients = recipients.count {
                            it.acknowledgedChunks >= it.totalChunks
                        },
                        totalRecipients = recipients.size,
                        recipients = recipients,
                        createdAtMs = transfer.createdAtMs,
                        updatedAtMs = transfer.updatedAtMs
                    )
                }
        }
        _outgoingFileTransfers.value = snapshot
    }

    fun cancelOutgoingFileTransfer(transferId: String): Boolean {
        val normalizedId = transferId.trim()
        if (normalizedId.isBlank()) return false
        val removed = synchronized(lock) {
            outgoingTransfers.remove(normalizedId) != null
        }
        if (!removed) return false
        persistOutgoingTransfersSnapshot(force = true)
        updateStatus("Stopped retries for file transfer: $normalizedId")
        return true
    }

    fun retryOutgoingFileTransfer(transferId: String): Boolean {
        val normalizedId = transferId.trim()
        if (normalizedId.isBlank()) return false
        val queued = synchronized(lock) {
            val transfer = outgoingTransfers[normalizedId] ?: return@synchronized false
            transfer.recipients.values.forEach { recipient ->
                recipient.lastSentAtMs = 0L
                recipient.nextChunkCursor = 0
            }
            transfer.updatedAtMs = System.currentTimeMillis() - TRANSFER_RESEND_GAP_MS
            true
        }
        if (!queued) return false
        persistOutgoingTransfersSnapshot(force = true)
        updateStatus("Retrying missing file chunks: $normalizedId")
        flushPendingTransfers()
        return true
    }

    private fun restoreIncomingTransfersFromStore() {
        val persisted = localStore.loadIncomingTransfers()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            fileTransferAssemblers.clear()
            persisted
                .sortedBy { it.createdAtMs }
                .forEach { record ->
                    val transferId = record.transferId.trim().ifBlank { return@forEach }
                    val originNodeId = record.originNodeId.trim().ifBlank { return@forEach }
                    val conversationId = record.conversationId.trim().ifBlank { return@forEach }
                    val chunkCount = record.chunkCount
                    if (chunkCount !in 1..MAX_INCOMING_FILE_CHUNKS) return@forEach
                    val updatedAtMs = record.updatedAtMs.takeIf { it > 0L } ?: return@forEach
                    if (now - updatedAtMs > FILE_ASSEMBLER_TTL_MS) return@forEach

                    var totalBytes = 0
                    val chunks = linkedMapOf<Int, ByteArray>()
                    record.chunks.sortedBy { it.index }.forEach chunkLoop@{ chunk ->
                        if (chunk.index !in 0 until chunkCount || chunks.containsKey(chunk.index)) {
                            return@chunkLoop
                        }
                        val decoded = runCatching {
                            Base64.decode(chunk.payloadBase64, Base64.NO_WRAP)
                        }.getOrNull() ?: return@chunkLoop
                        if (decoded.isEmpty() || decoded.size > FILE_CHUNK_SIZE) return@chunkLoop
                        totalBytes += decoded.size
                        if (totalBytes > MAX_FILE_BYTES) return@forEach
                        chunks[chunk.index] = decoded
                    }
                    if (chunks.isEmpty() || chunks.size >= chunkCount) return@forEach

                    val createdAtMs = record.createdAtMs.takeIf { it > 0L } ?: updatedAtMs
                    val assemblerKey = "$originNodeId:$conversationId:$transferId"
                    fileTransferAssemblers[assemblerKey] = FileTransferAssembler(
                        transferId = transferId,
                        originNodeId = originNodeId,
                        senderAlias = record.senderAlias.trim().ifBlank { originNodeId.take(12) },
                        conversationId = conversationId,
                        conversationType = record.conversationType,
                        conversationTitle = record.conversationTitle?.trim()?.ifBlank { null },
                        memberNodeIds = record.memberNodeIds
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinct(),
                        collectiveOwnerNodeId = record.collectiveOwnerNodeId?.trim()?.ifBlank { null },
                        collectiveAdminNodeIds = record.collectiveAdminNodeIds
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinct(),
                        collectiveModeratorNodeIds = record.collectiveModeratorNodeIds
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinct(),
                        collectiveBroadcastOnly = record.collectiveBroadcastOnly,
                        collectiveAllowMemberReactions = record.collectiveAllowMemberReactions,
                        collectiveAllowMemberEditOwnMessages = record.collectiveAllowMemberEditOwnMessages,
                        collectiveAllowMemberDeleteOwnMessages = record.collectiveAllowMemberDeleteOwnMessages,
                        fileName = record.fileName.trim().ifBlank { "file_$transferId" },
                        mimeType = record.mimeType.trim().ifBlank { "application/octet-stream" },
                        sizeBytes = record.sizeBytes.coerceIn(0L, MAX_FILE_BYTES.toLong()),
                        sha256 = record.sha256.trim(),
                        caption = record.caption.trim().take(MAX_FILE_CAPTION_LENGTH),
                        mediaAlbumId = record.mediaAlbumId?.trim()?.ifBlank { null },
                        mediaAlbumIndex = record.mediaAlbumIndex.coerceAtLeast(0),
                        mediaAlbumCount = record.mediaAlbumCount.coerceAtLeast(1),
                        compressed = record.compressed,
                        chunkCount = chunkCount,
                        sentAtMs = record.sentAtMs.takeIf { it > 0L } ?: createdAtMs,
                        createdAtMs = createdAtMs,
                        updatedAtMs = updatedAtMs,
                        chunks = chunks
                    )
                }
            trimIncomingTransfersLocked(now)
        }
        persistIncomingTransfersSnapshot(force = true)
    }

    private fun persistIncomingTransfersSnapshot(force: Boolean = false) {
        publishIncomingTransferProgressSnapshot()
        val snapshot = synchronized(lock) {
            val now = System.currentTimeMillis()
            if (!force && now - lastIncomingTransferSnapshotAtMs < INCOMING_TRANSFER_PERSIST_GAP_MS) {
                null
            } else {
                lastIncomingTransferSnapshotAtMs = now
                fileTransferAssemblers.values
                    .sortedBy { it.createdAtMs }
                    .map { assembler ->
                        IncomingFileTransferRecord(
                            transferId = assembler.transferId,
                            originNodeId = assembler.originNodeId,
                            senderAlias = assembler.senderAlias,
                            conversationId = assembler.conversationId,
                            conversationType = assembler.conversationType,
                            conversationTitle = assembler.conversationTitle,
                            memberNodeIds = assembler.memberNodeIds,
                            collectiveOwnerNodeId = assembler.collectiveOwnerNodeId,
                            collectiveAdminNodeIds = assembler.collectiveAdminNodeIds,
                            collectiveModeratorNodeIds = assembler.collectiveModeratorNodeIds,
                            collectiveBroadcastOnly = assembler.collectiveBroadcastOnly,
                            collectiveAllowMemberReactions = assembler.collectiveAllowMemberReactions,
                            collectiveAllowMemberEditOwnMessages = assembler.collectiveAllowMemberEditOwnMessages,
                            collectiveAllowMemberDeleteOwnMessages = assembler.collectiveAllowMemberDeleteOwnMessages,
                            fileName = assembler.fileName,
                            mimeType = assembler.mimeType,
                            sizeBytes = assembler.sizeBytes,
                            sha256 = assembler.sha256,
                            caption = assembler.caption,
                            mediaAlbumId = assembler.mediaAlbumId,
                            mediaAlbumIndex = assembler.mediaAlbumIndex,
                            mediaAlbumCount = assembler.mediaAlbumCount,
                            compressed = assembler.compressed,
                            chunkCount = assembler.chunkCount,
                            sentAtMs = assembler.sentAtMs,
                            createdAtMs = assembler.createdAtMs,
                            updatedAtMs = assembler.updatedAtMs,
                            chunks = assembler.chunks
                                .toSortedMap()
                                .map { (index, bytes) ->
                                    IncomingFileChunkRecord(
                                        index = index,
                                        payloadBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                    )
                                }
                        )
                    }
            }
        } ?: return
        localStore.persistIncomingTransfers(snapshot)
    }

    private fun restorePendingPayloadsFromStore() {
        val persisted = localStore.loadPendingPayloads()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            pendingPayloads.clear()
            persisted
                .sortedBy { it.createdAtMs }
                .forEach { record ->
                    val queueId = record.queueId.trim().ifBlank { return@forEach }
                    val payloadText = record.plaintext.trim().ifBlank { return@forEach }
                    val targets = record.targetNodeIds
                        .map { it.trim() }
                        .filter { it.isNotBlank() && it != nodeId }
                        .distinct()
                    if (targets.isEmpty()) return@forEach
                    val createdAtMs = record.createdAtMs.takeIf { it > 0 } ?: now
                    if (now - createdAtMs > PENDING_PAYLOAD_TTL_MS) {
                        return@forEach
                    }
                    val lastAttemptAtMs = record.lastAttemptAtMs.coerceAtLeast(0L)
                    pendingPayloads[queueId] = PendingPayloadDispatch(
                        queueId = queueId,
                        messageId = record.messageId?.trim()?.ifBlank { null },
                        plaintext = payloadText,
                        targetNodeIds = targets,
                        createdAtMs = createdAtMs,
                        lastAttemptAtMs = lastAttemptAtMs
                    )
                }
            trimPendingPayloadsLocked(now)
        }
        persistPendingPayloadsSnapshot()
    }

    private fun persistPendingPayloadsSnapshot() {
        val snapshot = synchronized(lock) {
            pendingPayloads.values
                .sortedBy { it.createdAtMs }
                .map { pending ->
                    PendingPayloadRecord(
                        queueId = pending.queueId,
                        messageId = pending.messageId,
                        plaintext = pending.plaintext,
                        targetNodeIds = pending.targetNodeIds,
                        createdAtMs = pending.createdAtMs,
                        lastAttemptAtMs = pending.lastAttemptAtMs
                    )
                }
        }
        localStore.persistPendingPayloads(snapshot)
    }

    private fun restoreScheduledMessagesFromStore() {
        val now = System.currentTimeMillis()
        val restored = localStore.loadScheduledMessages()
        synchronized(lock) {
            scheduledMessageMap.clear()
            restored
                .sortedBy { it.scheduledAtMs }
                .forEach { record ->
                    val id = record.id.trim().ifBlank { return@forEach }
                    if (record.text.isBlank() || record.conversationId.isBlank()) return@forEach
                    if (now - record.scheduledAtMs > SCHEDULED_MESSAGE_EXPIRY_MS) return@forEach
                    scheduledMessageMap[id] = record.copy(id = id)
                }
            trimScheduledMessagesLocked(now)
        }
        persistScheduledMessagesSnapshot()
    }

    private fun persistScheduledMessagesSnapshot() {
        val snapshot = synchronized(lock) {
            scheduledMessageMap.values
                .sortedBy { it.scheduledAtMs }
                .take(MAX_SCHEDULED_MESSAGES)
        }
        _scheduledMessages.value = snapshot
        localStore.persistScheduledMessages(snapshot)
    }

    private fun flushScheduledMessages() {
        val now = System.currentTimeMillis()
        val dueMessages = synchronized(lock) {
            trimScheduledMessagesLocked(now)
            scheduledMessageMap.values
                .filter { record ->
                    record.scheduledAtMs <= now &&
                        now - record.lastAttemptAtMs >= SCHEDULED_RETRY_GAP_MS
                }
                .sortedBy { it.scheduledAtMs }
                .take(SCHEDULED_DISPATCH_BATCH)
        }
        if (dueMessages.isEmpty()) return

        var changed = false
        dueMessages.forEach { record ->
            val sent = dispatchScheduledMessage(record)
            synchronized(lock) {
                val current = scheduledMessageMap[record.id] ?: return@synchronized
                if (sent) {
                    scheduledMessageMap.remove(record.id)
                } else {
                    scheduledMessageMap[record.id] = current.copy(lastAttemptAtMs = now)
                }
                changed = true
            }
        }
        if (changed) persistScheduledMessagesSnapshot()
    }

    private fun dispatchScheduledMessage(record: ScheduledMessageRecord): Boolean {
        if (isSavedMessagesConversation(record.conversationId)) {
            return saveLocalTextMessage(
                text = record.text,
                conversationId = record.conversationId,
                conversationTitle = record.conversationTitle,
                replyToMessageId = record.replyToMessageId,
                replyToPreview = record.replyToPreview,
                messageIdOverride = record.id
            )
        }
        return when (record.conversationType) {
            ConversationType.DIRECT -> {
                val target = record.targetNodeId?.trim()?.ifBlank { null } ?: return false
                sendDirectMessage(
                    text = record.text,
                    targetNodeId = target,
                    conversationId = record.conversationId,
                    conversationTitle = record.conversationTitle,
                    replyToMessageId = record.replyToMessageId,
                    replyToPreview = record.replyToPreview,
                    messageIdOverride = record.id
                )
            }

            ConversationType.GROUP,
            ConversationType.CHANNEL -> {
                sendGroupMessage(
                    text = record.text,
                    groupId = record.conversationId,
                    groupTitle = record.conversationTitle,
                    memberNodeIds = record.memberNodeIds,
                    adminNodeIds = record.adminNodeIds,
                    moderatorNodeIds = record.moderatorNodeIds,
                    isBroadcastOnly = record.isBroadcastOnly,
                    allowMemberReactions = record.allowMemberReactions,
                    allowMemberEditOwnMessages = record.allowMemberEditOwnMessages,
                    allowMemberDeleteOwnMessages = record.allowMemberDeleteOwnMessages,
                    replyToMessageId = record.replyToMessageId,
                    replyToPreview = record.replyToPreview,
                    chatType = if (record.conversationType == ConversationType.CHANNEL) {
                        MeshMessagePayload.CHAT_TYPE_CHANNEL
                    } else {
                        MeshMessagePayload.CHAT_TYPE_GROUP
                    },
                    conversationType = record.conversationType,
                    messageIdOverride = record.id
                ) > 0
            }
        }
    }

    private fun trimScheduledMessagesLocked(nowMs: Long = System.currentTimeMillis()) {
        val iterator = scheduledMessageMap.iterator()
        while (iterator.hasNext()) {
            val (_, record) = iterator.next()
            if (record.text.isBlank() ||
                record.conversationId.isBlank() ||
                nowMs - record.scheduledAtMs > SCHEDULED_MESSAGE_EXPIRY_MS
            ) {
                iterator.remove()
            }
        }
        while (scheduledMessageMap.size > MAX_SCHEDULED_MESSAGES) {
            val farthest = scheduledMessageMap.values.maxByOrNull { it.scheduledAtMs } ?: break
            scheduledMessageMap.remove(farthest.id)
        }
    }

    private fun transportMutexForAddress(address: String): Mutex {
        synchronized(lock) {
            return transportMutexByAddress.getOrPut(address) { Mutex() }
        }
    }

    private fun rememberNegotiatedMtu(address: String, mtu: Int) {
        if (address.isBlank() || mtu <= 0) return
        synchronized(lock) {
            negotiatedMtuByAddress[address] = mtu
        }
        flushRelayOutbox()
        flushPendingTransfers()
        flushPendingPayloads()
    }

    private fun chunkPayloadSizeForAddress(address: String): Int {
        val mtu = synchronized(lock) { negotiatedMtuByAddress[address] }
            ?: return DEFAULT_BLE_CHUNK_PAYLOAD_SIZE
        val characteristicPayloadLimit = mtu - ATT_HEADER_BYTES - CHUNK_HEADER_SIZE
        return characteristicPayloadLimit.coerceIn(
            DEFAULT_BLE_CHUNK_PAYLOAD_SIZE,
            MAX_BLE_CHUNK_PAYLOAD_SIZE
        )
    }

    private suspend fun sendPacketsWithRetries(
        packets: List<ByteArray>,
        sendPacket: suspend (ByteArray) -> Boolean
    ): Boolean {
        repeat(FRAME_RETRY_COUNT) { attempt ->
            var frameDelivered = true
            for (packet in packets) {
                val ok = sendPacket(packet)
                if (!ok) {
                    frameDelivered = false
                    break
                }
                delay(PACKET_GAP_MS)
            }
            if (frameDelivered) {
                return true
            }
            if (attempt < FRAME_RETRY_COUNT - 1) {
                delay(FRAME_RETRY_GAP_MS)
            }
        }
        return false
    }

    private fun scheduleClientTransportReady(address: String) {
        scope.launch(Dispatchers.IO) {
            delay(CLIENT_READY_DELAY_MS)
            val becameReady = synchronized(lock) {
                if (!clientGatts.containsKey(address)) {
                    false
                } else {
                    clientReadyAddresses.add(address)
                }
            }
            if (becameReady) {
                publishHello()
                flushRelayOutbox()
                flushPendingTransfers()
                flushPendingPayloads()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServicesOnce(gatt: BluetoothGatt): Boolean {
        val address = gatt.device.address ?: return false
        synchronized(lock) {
            if (!clientGatts.containsKey(address) ||
                !serviceDiscoveryStartedAddresses.add(address)
            ) {
                return false
            }
        }
        val requested = runCatching { gatt.discoverServices() }.getOrDefault(false)
        if (!requested) {
            synchronized(lock) {
                serviceDiscoveryStartedAddresses.remove(address)
            }
        }
        return requested
    }

    private fun packetize(
        payload: ByteArray,
        frameKey: Int,
        chunkPayloadSize: Int = DEFAULT_BLE_CHUNK_PAYLOAD_SIZE
    ): List<ByteArray> {
        if (payload.isEmpty()) return emptyList()
        val safeChunkPayloadSize = chunkPayloadSize.coerceIn(
            DEFAULT_BLE_CHUNK_PAYLOAD_SIZE,
            MAX_BLE_CHUNK_PAYLOAD_SIZE
        )
        val total = (payload.size + safeChunkPayloadSize - 1) / safeChunkPayloadSize
        if (total > 255) return emptyList()

        return (0 until total).map { index ->
            val start = index * safeChunkPayloadSize
            val end = min(start + safeChunkPayloadSize, payload.size)
            val chunk = payload.copyOfRange(start, end)
            val packet = ByteArray(CHUNK_HEADER_SIZE + chunk.size)

            packet[0] = MAGIC_BYTE
            ByteBuffer.wrap(packet, 1, 4).putInt(frameKey)
            packet[5] = total.toByte()
            packet[6] = index.toByte()
            packet[7] = 0
            chunk.copyInto(packet, CHUNK_HEADER_SIZE)
            packet
        }
    }

    private fun handleIncomingPacket(address: String, packet: ByteArray) {
        if (packet.size < CHUNK_HEADER_SIZE + 1) return
        if (packet[0] != MAGIC_BYTE) return

        val frameKey = ByteBuffer.wrap(packet, 1, 4).int
        val total = packet[5].toInt() and 0xFF
        val index = packet[6].toInt() and 0xFF
        if (total <= 0 || index >= total) return

        val chunk = packet.copyOfRange(CHUNK_HEADER_SIZE, packet.size)
        val assemblerKey = "$address:$frameKey"
        val now = System.currentTimeMillis()

        cleanupAssemblers(now)

        val assembledPayload: ByteArray? = synchronized(lock) {
            val existing = frameAssemblers[assemblerKey]
            val assembler = if (existing == null || existing.total != total) {
                FrameAssembler(total = total, updatedAtMs = now).also {
                    frameAssemblers[assemblerKey] = it
                }
            } else {
                existing
            }

            assembler.updatedAtMs = now
            assembler.parts[index] = chunk
            if (assembler.parts.size != total) return@synchronized null

            val out = ByteArrayOutputStream()
            for (i in 0 until total) {
                val part = assembler.parts[i] ?: return@synchronized null
                out.write(part)
            }
            frameAssemblers.remove(assemblerKey)
            out.toByteArray()
        }

        if (assembledPayload != null) {
            onPayloadDecoded(
                payload = assembledPayload,
                fromAddress = address,
                sourceTransport = TransportSource.BLE
            )
        }
    }

    private fun onPayloadDecoded(
        payload: ByteArray,
        fromAddress: String?,
        sourceTransport: TransportSource
    ) {
        val raw = payload.decodeToString()
        val type = runCatching {
            json.parseToJsonElement(raw).jsonObject["type"]?.jsonPrimitive?.content
        }.getOrNull()

        if (type == null) {
            val helloDecoded = runCatching { json.decodeFromString<HelloPacket>(raw) }
                .onSuccess { packet -> onHelloReceived(packet, fromAddress, sourceTransport) }
                .isSuccess
            if (helloDecoded) return

            runCatching { json.decodeFromString<SecureMessagePacket>(raw) }
                .onSuccess { packet -> onSecureMessageReceived(packet, fromAddress, sourceTransport) }
                .onFailure { updateStatus("Incoming packet decode failed") }
            return
        }

        when (type) {
            HelloPacket.TYPE -> {
                runCatching { json.decodeFromString<HelloPacket>(raw) }
                    .onSuccess { packet -> onHelloReceived(packet, fromAddress, sourceTransport) }
                    .onFailure { updateStatus("HELLO decode failed") }
            }

            SecureMessagePacket.TYPE -> {
                runCatching { json.decodeFromString<SecureMessagePacket>(raw) }
                    .onSuccess { packet -> onSecureMessageReceived(packet, fromAddress, sourceTransport) }
                    .onFailure { updateStatus("Encrypted packet decode failed") }
            }
        }
    }

    private fun onHelloReceived(
        packet: HelloPacket,
        fromAddress: String?,
        sourceTransport: TransportSource
    ) {
        if (!isValidHelloHopEnvelope(packet.hops, packet.maxHops)) {
            updateStatus("Dropped HELLO with invalid hops envelope")
            return
        }
        if (isKnownFrame(packet.frameId)) return
        rememberFrame(packet.frameId)

        if (packet.originNodeId == nodeId) return
        if (!crypto.verifyHelloSignature(packet)) {
            updateStatus("Dropped HELLO with bad signature")
            return
        }

        var keyChanged = false
        synchronized(lock) {
            val existing = peerIdentityByNodeId[packet.originNodeId]
            if (existing != null &&
                (existing.encryptionPublicKey != packet.encryptionPublicKey ||
                    existing.signingPublicKey != packet.signingPublicKey)
            ) {
                keyChanged = true
            } else {
                val now = System.currentTimeMillis()
                peerIdentityByNodeId[packet.originNodeId] = if (existing == null) {
                    PeerIdentity(
                        nodeId = packet.originNodeId,
                        alias = packet.alias,
                        encryptionPublicKey = packet.encryptionPublicKey,
                        signingPublicKey = packet.signingPublicKey,
                        fingerprint = packet.fingerprint,
                        firstSeenMs = now,
                        lastSeenMs = now,
                        avatarData = packet.avatarData
                    )
                } else {
                    existing.copy(
                        alias = packet.alias,
                        fingerprint = packet.fingerprint,
                        lastSeenMs = now,
                        avatarData = packet.avatarData.ifBlank { existing.avatarData }
                    )
                }
                if (sourceTransport == TransportSource.BLE && !fromAddress.isNullOrBlank()) {
                    addressToNodeId[fromAddress] = packet.originNodeId
                }
            }
        }
        if (keyChanged) {
            updateStatus("Security warning: key rotation for ${packet.alias} blocked")
            return
        }
        persistPeerIdentityCache()

        val uiAddress = when (sourceTransport) {
            TransportSource.BLE -> fromAddress ?: "ble:${packet.originNodeId.take(6)}"
            TransportSource.WIFI_LAN -> "wifi:${fromAddress ?: packet.originNodeId.take(6)}"
            TransportSource.RELAY -> "relay:${packet.originNodeId.take(6)}"
        }
        upsertPeer(
            address = uiAddress,
            nodeId = packet.originNodeId,
            alias = packet.alias,
            fingerprintShort = packet.fingerprint.take(12),
            connected = true
        )
        flushPendingPayloads()

        if (shouldRelayByHops(packet.hops, packet.maxHops)) {
            val relayed = packet.copy(
                relayNodeId = nodeId,
                hops = packet.hops + 1
            )
            val relayPayload = json.encodeToString(relayed).toByteArray(Charsets.UTF_8)
            broadcastPayload(
                frameId = relayed.frameId,
                payload = relayPayload,
                excludedAddress = fromAddress,
                cacheForRelay = false,
                sendToRelay = sourceTransport != TransportSource.RELAY,
                sendToWifiLan = sourceTransport != TransportSource.WIFI_LAN
            )
        }
    }

    private fun onSecureMessageReceived(
        packet: SecureMessagePacket,
        fromAddress: String?,
        sourceTransport: TransportSource
    ) {
        if (!isValidMessageHopEnvelope(packet.hops, packet.maxHops)) {
            updateStatus("Dropped encrypted packet with invalid hops envelope")
            return
        }
        if (isKnownFrame(packet.id)) return
        rememberFrame(packet.id)

        if (packet.originNodeId == nodeId) return

        val computedFingerprint = crypto.fingerprintForKeys(
            encryptionPublicKey = packet.senderEncryptionPublicKey,
            signingPublicKey = packet.senderSigningPublicKey
        )
        if (computedFingerprint != packet.senderFingerprint) {
            updateStatus("Dropped message with invalid sender fingerprint")
            return
        }

        val senderIdentity = synchronized(lock) {
            val existing = peerIdentityByNodeId[packet.originNodeId]
            if (existing != null &&
                (existing.encryptionPublicKey != packet.senderEncryptionPublicKey ||
                    existing.signingPublicKey != packet.senderSigningPublicKey)
            ) {
                return@synchronized null
            }

            val now = System.currentTimeMillis()
            val identity = if (existing == null) {
                PeerIdentity(
                    nodeId = packet.originNodeId,
                    alias = packet.senderAlias.ifBlank { "Node-${packet.originNodeId.take(4)}" },
                    encryptionPublicKey = packet.senderEncryptionPublicKey,
                    signingPublicKey = packet.senderSigningPublicKey,
                    fingerprint = packet.senderFingerprint,
                    firstSeenMs = now,
                    lastSeenMs = now,
                    avatarData = ""
                )
            } else {
                existing.copy(
                    alias = packet.senderAlias.ifBlank { existing.alias },
                    fingerprint = packet.senderFingerprint,
                    lastSeenMs = now
                )
            }
            peerIdentityByNodeId[packet.originNodeId] = identity
            if (sourceTransport == TransportSource.BLE && !fromAddress.isNullOrBlank()) {
                addressToNodeId[fromAddress] = packet.originNodeId
            }
            identity
        }

        if (senderIdentity == null) {
            updateStatus("Security warning: sender key rotation blocked")
            return
        }
        persistPeerIdentityCache()

        val uiAddress = when (sourceTransport) {
            TransportSource.BLE -> fromAddress ?: "ble:${packet.originNodeId.take(6)}"
            TransportSource.WIFI_LAN -> "wifi:${fromAddress ?: packet.originNodeId.take(6)}"
            TransportSource.RELAY -> "relay:${packet.originNodeId.take(6)}"
        }
        upsertPeer(
            address = uiAddress,
            nodeId = packet.originNodeId,
            alias = senderIdentity.alias,
            fingerprintShort = senderIdentity.fingerprint.take(12),
            connected = true
        )
        flushPendingPayloads()

        if (packet.targetNodeId == nodeId) {
            val signatureValid = runCatching {
                crypto.verifyMessageSignature(
                    packet = packet,
                    senderSigningPublicKey = packet.senderSigningPublicKey
                )
            }.getOrDefault(false)

            if (!signatureValid) {
                updateStatus("Bad signature from ${senderIdentity.alias}")
                return
            }

            val plaintext = runCatching { crypto.decryptIncomingMessage(packet) }
                .getOrNull()
            if (plaintext.isNullOrBlank()) {
                updateStatus("Decrypt failed from ${senderIdentity.alias}")
                return
            }

            val meshPayload = runCatching {
                json.decodeFromString<MeshMessagePayload>(plaintext)
            }.getOrNull()
            if (meshPayload?.type == MeshMessagePayload.TYPE &&
                meshPayload.payloadKind == MeshMessagePayload.KIND_FILE_CHUNK
            ) {
                handleIncomingFileChunk(
                    senderIdentity = senderIdentity,
                    packet = packet,
                    payload = meshPayload
                )
                return
            }
            if (meshPayload?.type == MeshMessagePayload.TYPE &&
                meshPayload.payloadKind == MeshMessagePayload.KIND_FILE_ACK
            ) {
                handleIncomingFileAck(
                    senderNodeId = packet.originNodeId,
                    payload = meshPayload
                )
                return
            }
            if (meshPayload?.type == MeshMessagePayload.TYPE &&
                meshPayload.payloadKind == MeshMessagePayload.KIND_MESSAGE_RELAY_ACK
            ) {
                val ackMessageId = meshPayload.ackMessageId?.trim().orEmpty()
                val relayed = if (ackMessageId.isNotBlank()) {
                    applyMessageRelayed(
                        targetMessageId = ackMessageId,
                        relayedByNodeId = packet.originNodeId
                    )
                } else {
                    false
                }
                if (relayed) {
                    updateStatus("Relayed by ${senderIdentity.alias}")
                }
                return
            }
            if (meshPayload?.type == MeshMessagePayload.TYPE &&
                meshPayload.payloadKind == MeshMessagePayload.KIND_MESSAGE_DELIVERY_ACK
            ) {
                val ackMessageId = meshPayload.ackMessageId?.trim().orEmpty()
                val delivered = if (ackMessageId.isNotBlank()) {
                    applyMessageDelivered(
                        targetMessageId = ackMessageId,
                        deliveredByNodeId = packet.originNodeId,
                        deliveredAtMs = meshPayload.sentAtMs.takeIf { it > 0 } ?: packet.createdAtMs
                    )
                } else {
                    false
                }
                if (delivered) {
                    updateStatus("Delivered by ${senderIdentity.alias}")
                }
                return
            }
            if (meshPayload?.type == MeshMessagePayload.TYPE &&
                meshPayload.payloadKind == MeshMessagePayload.KIND_MESSAGE_EDIT
            ) {
                val targetMessageId = meshPayload.targetMessageId?.trim().orEmpty()
                val newText = meshPayload.text.trim()
                val edited = if (targetMessageId.isNotBlank() && newText.isNotBlank()) {
                    applyMessageEdit(
                        actorNodeId = packet.originNodeId,
                        targetMessageId = targetMessageId,
                        newText = newText,
                        editedAtMs = meshPayload.sentAtMs.takeIf { it > 0 } ?: packet.createdAtMs
                    )
                } else {
                    false
                }
                if (edited) {
                    updateStatus("Message edited by ${senderIdentity.alias}")
                }
                return
            }
            if (meshPayload?.type == MeshMessagePayload.TYPE &&
                meshPayload.payloadKind == MeshMessagePayload.KIND_MESSAGE_DELETE
            ) {
                val targetMessageId = meshPayload.targetMessageId?.trim().orEmpty()
                val deleted = if (targetMessageId.isNotBlank()) {
                    applyMessageDelete(
                        actorNodeId = packet.originNodeId,
                        targetMessageId = targetMessageId,
                        deletedAtMs = meshPayload.sentAtMs.takeIf { it > 0 } ?: packet.createdAtMs
                    )
                } else {
                    false
                }
                if (deleted) {
                    updateStatus("Message deleted by ${senderIdentity.alias}")
                }
                return
            }
            if (meshPayload?.type == MeshMessagePayload.TYPE &&
                meshPayload.payloadKind == MeshMessagePayload.KIND_MESSAGE_REACTION
            ) {
                val targetMessageId = meshPayload.targetMessageId?.trim().orEmpty()
                val emoji = meshPayload.reactionEmoji?.trim().orEmpty().take(16)
                val reacted = if (targetMessageId.isNotBlank()) {
                    applyMessageReaction(
                        actorNodeId = packet.originNodeId,
                        actorAlias = senderIdentity.alias,
                        targetMessageId = targetMessageId,
                        emoji = emoji,
                        reactedAtMs = meshPayload.sentAtMs.takeIf { it > 0 } ?: packet.createdAtMs
                    )
                } else {
                    false
                }
                if (reacted) {
                    updateStatus("Reaction received from ${senderIdentity.alias}")
                }
                return
            }
            if (meshPayload?.type == MeshMessagePayload.TYPE &&
                meshPayload.payloadKind == MeshMessagePayload.KIND_MESSAGE_PIN
            ) {
                val targetMessageId = meshPayload.targetMessageId?.trim().orEmpty()
                val isGroupLike = !meshPayload.chatType.equals(
                    MeshMessagePayload.CHAT_TYPE_DIRECT,
                    ignoreCase = true
                )
                val conversationId = meshPayload.chatId.trim().ifBlank {
                    if (isGroupLike) {
                        if (meshPayload.chatType.equals(
                                MeshMessagePayload.CHAT_TYPE_CHANNEL,
                                ignoreCase = true
                            )
                        ) {
                            "chn:${packet.originNodeId}:mesh"
                        } else {
                            "grp:${packet.originNodeId}:mesh"
                        }
                    } else {
                        directConversationId(nodeId, packet.originNodeId)
                    }
                }
                val pinChanged = if (targetMessageId.isNotBlank()) {
                    applyConversationPin(
                        actorNodeId = packet.originNodeId,
                        conversationId = conversationId,
                        targetMessageId = targetMessageId,
                        pinEnabled = meshPayload.pinEnabled != false,
                        pinnedAtMs = meshPayload.sentAtMs.takeIf { it > 0 } ?: packet.createdAtMs
                    )
                } else {
                    false
                }
                if (pinChanged) {
                    updateStatus("Pinned message updated by ${senderIdentity.alias}")
                }
                return
            }
            if (meshPayload?.type == MeshMessagePayload.TYPE &&
                meshPayload.payloadKind == MeshMessagePayload.KIND_COLLECTIVE_UPDATE
            ) {
                val conversationMeta = resolveConversationMeta(meshPayload, senderIdentity)
                val messageId = meshPayload.messageId?.trim().orEmpty().ifBlank { packet.id }
                val adminNodeIds = meshPayload.collectiveAdminNodeIds
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .ifEmpty { listOf(packet.originNodeId) }
                val ownerNodeId = meshPayload.collectiveOwnerNodeId
                    ?.trim()
                    ?.ifBlank { null }
                    ?: adminNodeIds.firstOrNull()
                    ?: packet.originNodeId
                val moderatorNodeIds = meshPayload.collectiveModeratorNodeIds
                    .map { it.trim() }
                    .filter { it.isNotBlank() && conversationMeta.memberNodeIds.contains(it) && !adminNodeIds.contains(it) }
                    .distinct()
                appendMessage(
                    ChatMessage(
                        id = messageId,
                        text = meshPayload.text.trim().ifBlank { "Collective settings updated" },
                        originNodeId = packet.originNodeId,
                        targetNodeId = packet.targetNodeId,
                        relayNodeId = packet.relayNodeId,
                        createdAtMs = packet.createdAtMs,
                        isLocal = false,
                        isEncrypted = true,
                        isSystem = true,
                        conversationId = conversationMeta.conversationId,
                        conversationType = conversationMeta.conversationType,
                        conversationTitle = conversationMeta.conversationTitle,
                        senderAlias = senderIdentity.alias,
                        memberNodeIds = conversationMeta.memberNodeIds,
                        collectiveOwnerNodeId = ownerNodeId,
                        collectiveAdminNodeIds = adminNodeIds,
                        collectiveModeratorNodeIds = moderatorNodeIds,
                        collectiveBroadcastOnly = meshPayload.collectiveBroadcastOnly,
                        collectiveAllowMemberReactions = meshPayload.collectiveAllowMemberReactions,
                        collectiveAllowMemberEditOwnMessages = meshPayload.collectiveAllowMemberEditOwnMessages,
                        collectiveAllowMemberDeleteOwnMessages = meshPayload.collectiveAllowMemberDeleteOwnMessages,
                        deliveryState = MessageDeliveryState.DELIVERED,
                        deliveredAtMs = System.currentTimeMillis(),
                        deliveredToNodeIds = listOf(nodeId)
                    )
                )
                sendDeliveryAck(
                    senderIdentity = senderIdentity,
                    ackMessageId = messageId,
                    conversationId = conversationMeta.conversationId,
                    conversationType = conversationMeta.conversationType,
                    conversationTitle = conversationMeta.conversationTitle,
                    memberNodeIds = conversationMeta.memberNodeIds
                )
                updateStatus("Collective settings updated by ${senderIdentity.alias}")
                return
            }

            val decodedPayload = decodeIncomingPayload(
                plaintext = plaintext,
                senderIdentity = senderIdentity,
                decodedPayload = meshPayload,
                fallbackMessageId = packet.id
            )

            appendMessage(
                ChatMessage(
                    id = decodedPayload.messageId,
                    text = decodedPayload.text,
                    originNodeId = packet.originNodeId,
                    targetNodeId = packet.targetNodeId,
                    relayNodeId = packet.relayNodeId,
                    createdAtMs = packet.createdAtMs,
                    isLocal = false,
                    isEncrypted = true,
                    conversationId = decodedPayload.conversationId,
                    conversationType = decodedPayload.conversationType,
                    conversationTitle = decodedPayload.conversationTitle,
                    senderAlias = senderIdentity.alias,
                    memberNodeIds = decodedPayload.memberNodeIds,
                    collectiveOwnerNodeId = decodedPayload.collectiveOwnerNodeId,
                    collectiveAdminNodeIds = decodedPayload.collectiveAdminNodeIds,
                    collectiveModeratorNodeIds = decodedPayload.collectiveModeratorNodeIds,
                    collectiveBroadcastOnly = decodedPayload.collectiveBroadcastOnly,
                    collectiveAllowMemberReactions = decodedPayload.collectiveAllowMemberReactions,
                    collectiveAllowMemberEditOwnMessages = decodedPayload.collectiveAllowMemberEditOwnMessages,
                    collectiveAllowMemberDeleteOwnMessages = decodedPayload.collectiveAllowMemberDeleteOwnMessages,
                    replyToMessageId = decodedPayload.replyToMessageId,
                    replyToPreview = decodedPayload.replyToPreview,
                    forwardedFromAlias = decodedPayload.forwardedFromAlias,
                    forwardedFromMessageId = decodedPayload.forwardedFromMessageId,
                    deliveryState = MessageDeliveryState.DELIVERED,
                    deliveredAtMs = System.currentTimeMillis(),
                    deliveredToNodeIds = listOf(nodeId)
                )
            )
            sendDeliveryAck(
                senderIdentity = senderIdentity,
                ackMessageId = decodedPayload.messageId,
                conversationId = decodedPayload.conversationId,
                conversationType = decodedPayload.conversationType,
                conversationTitle = decodedPayload.conversationTitle,
                memberNodeIds = decodedPayload.memberNodeIds
            )
            updateStatus("Encrypted message from ${senderIdentity.alias}")
            return
        }

        if (shouldRelayByHops(packet.hops, packet.maxHops) && packet.originNodeId != nodeId) {
            sendRelayAck(
                senderIdentity = senderIdentity,
                relayedMessageId = packet.id
            )
            val relayed = packet.copy(
                relayNodeId = nodeId,
                hops = packet.hops + 1
            )
            val relayPayload = json.encodeToString(relayed).toByteArray(Charsets.UTF_8)
            broadcastPayload(
                frameId = relayed.id,
                payload = relayPayload,
                excludedAddress = fromAddress,
                sendToRelay = sourceTransport != TransportSource.RELAY,
                sendToWifiLan = sourceTransport != TransportSource.WIFI_LAN
            )
        }
    }

    private fun decodeIncomingPayload(
        plaintext: String,
        senderIdentity: PeerIdentity,
        decodedPayload: MeshMessagePayload? = null,
        fallbackMessageId: String
    ): DecodedIncomingPayload {
        val legacyMessageId = decodedPayload?.messageId?.trim().orEmpty().ifBlank {
            fallbackMessageId
        }
        val legacyDirect = DecodedIncomingPayload(
            messageId = legacyMessageId,
            text = plaintext,
            conversationId = directConversationId(nodeId, senderIdentity.nodeId),
            conversationType = ConversationType.DIRECT,
            conversationTitle = senderIdentity.alias,
            memberNodeIds = listOf(nodeId, senderIdentity.nodeId),
            collectiveOwnerNodeId = null,
            collectiveAdminNodeIds = emptyList(),
            collectiveModeratorNodeIds = emptyList(),
            collectiveBroadcastOnly = null,
            collectiveAllowMemberReactions = null,
            collectiveAllowMemberEditOwnMessages = null,
            collectiveAllowMemberDeleteOwnMessages = null,
            replyToMessageId = null,
            replyToPreview = null,
            forwardedFromAlias = null,
            forwardedFromMessageId = null
        )

        val payload = decodedPayload ?: runCatching {
            json.decodeFromString<MeshMessagePayload>(plaintext)
        }.getOrNull()
            ?: return legacyDirect

        if (payload.type != MeshMessagePayload.TYPE ||
            payload.payloadKind != MeshMessagePayload.KIND_TEXT ||
            payload.text.isBlank()
        ) {
            return legacyDirect
        }

        val isChannel = payload.chatType.equals(
            MeshMessagePayload.CHAT_TYPE_CHANNEL,
            ignoreCase = true
        )
        val isGroup = payload.chatType.equals(
            MeshMessagePayload.CHAT_TYPE_GROUP,
            ignoreCase = true
        )
        val isGroupLike = isGroup || isChannel
        if (!isGroupLike) {
            val messageId = payload.messageId?.trim().orEmpty().ifBlank { legacyMessageId }
            return DecodedIncomingPayload(
                messageId = messageId,
                text = payload.text.trim(),
                conversationId = directConversationId(nodeId, senderIdentity.nodeId),
                conversationType = ConversationType.DIRECT,
                conversationTitle = senderIdentity.alias,
                memberNodeIds = listOf(nodeId, senderIdentity.nodeId),
                collectiveOwnerNodeId = null,
                collectiveAdminNodeIds = emptyList(),
                collectiveModeratorNodeIds = emptyList(),
                collectiveBroadcastOnly = null,
                collectiveAllowMemberReactions = null,
                collectiveAllowMemberEditOwnMessages = null,
                collectiveAllowMemberDeleteOwnMessages = null,
                replyToMessageId = payload.replyToMessageId?.trim()?.ifBlank { null },
                replyToPreview = payload.replyToPreview?.trim()?.ifBlank { null },
                forwardedFromAlias = payload.forwardedFromAlias?.trim()?.ifBlank { null },
                forwardedFromMessageId = payload.forwardedFromMessageId?.trim()?.ifBlank { null }
            )
        }

        val messageId = payload.messageId?.trim().orEmpty().ifBlank { legacyMessageId }
        val conversationId = payload.chatId
            .trim()
            .ifBlank {
                if (isChannel) {
                    "chn:${senderIdentity.nodeId}:mesh"
                } else {
                    "grp:${senderIdentity.nodeId}:mesh"
                }
            }
        val memberNodeIds = payload.memberNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .let { members ->
                val withSender = if (members.contains(senderIdentity.nodeId)) {
                    members
                } else {
                    members + senderIdentity.nodeId
                }
                if (withSender.contains(nodeId)) withSender else withSender + nodeId
            }
        val conversationTitle = payload.chatTitle?.trim()?.ifBlank { null }
            ?: if (isChannel) {
                "Channel ${conversationId.takeLast(4)}"
            } else {
                "Group ${conversationId.takeLast(4)}"
            }
        val adminNodeIds = payload.collectiveAdminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf(senderIdentity.nodeId) }
        val ownerNodeId = payload.collectiveOwnerNodeId
            ?.trim()
            ?.ifBlank { null }
            ?: adminNodeIds.firstOrNull()
            ?: senderIdentity.nodeId
        val moderatorNodeIds = payload.collectiveModeratorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && memberNodeIds.contains(it) && !adminNodeIds.contains(it) }
            .distinct()

        return DecodedIncomingPayload(
            messageId = messageId,
            text = payload.text.trim(),
            conversationId = conversationId,
            conversationType = if (isChannel) {
                ConversationType.CHANNEL
            } else {
                ConversationType.GROUP
            },
            conversationTitle = conversationTitle,
            memberNodeIds = memberNodeIds,
            collectiveOwnerNodeId = ownerNodeId,
            collectiveAdminNodeIds = adminNodeIds,
            collectiveModeratorNodeIds = moderatorNodeIds,
            collectiveBroadcastOnly = payload.collectiveBroadcastOnly,
            collectiveAllowMemberReactions = payload.collectiveAllowMemberReactions,
            collectiveAllowMemberEditOwnMessages = payload.collectiveAllowMemberEditOwnMessages,
            collectiveAllowMemberDeleteOwnMessages = payload.collectiveAllowMemberDeleteOwnMessages,
            replyToMessageId = payload.replyToMessageId?.trim()?.ifBlank { null },
            replyToPreview = payload.replyToPreview?.trim()?.ifBlank { null },
            forwardedFromAlias = payload.forwardedFromAlias?.trim()?.ifBlank { null },
            forwardedFromMessageId = payload.forwardedFromMessageId?.trim()?.ifBlank { null }
        )
    }

    private fun handleIncomingFileChunk(
        senderIdentity: PeerIdentity,
        packet: SecureMessagePacket,
        payload: MeshMessagePayload
    ) {
        val transferId = payload.transferId?.trim().orEmpty()
        if (transferId.isBlank() || transferId.length > MAX_TRANSFER_ID_LENGTH) return
        if (isTransferCompleted(transferId)) return

        val chunkB64 = payload.chunkBase64 ?: return
        val chunkBytes = runCatching { Base64.decode(chunkB64, Base64.NO_WRAP) }.getOrNull() ?: return
        if (chunkBytes.isEmpty() || chunkBytes.size > FILE_CHUNK_SIZE) return

        if (payload.chunkCount !in 1..MAX_INCOMING_FILE_CHUNKS) return
        if (payload.fileSizeBytes !in 1L..MAX_FILE_BYTES.toLong()) return
        if (payload.fileName.orEmpty().length > MAX_FILE_NAME_LENGTH) return
        if (payload.mimeType.orEmpty().length > MAX_MIME_TYPE_LENGTH) return
        if (payload.fileSha256.orEmpty().length > MAX_HASH_LENGTH) return
        val chunkCount = payload.chunkCount
        val chunkIndex = payload.chunkIndex
        if (chunkIndex < 0 || chunkIndex >= chunkCount) return

        val conversationMeta = resolveConversationMeta(payload, senderIdentity)
        val assemblerKey = "${packet.originNodeId}:${conversationMeta.conversationId}:$transferId"
        val now = System.currentTimeMillis()
        cleanupFileTransferAssemblers(now)

        val result = synchronized(lock) {
            val existing = fileTransferAssemblers[assemblerKey]
            val assembler = if (existing == null || existing.chunkCount != chunkCount) {
                FileTransferAssembler(
                    transferId = transferId,
                    originNodeId = packet.originNodeId,
                    senderAlias = senderIdentity.alias,
                    conversationId = conversationMeta.conversationId,
                    conversationType = conversationMeta.conversationType,
                    conversationTitle = conversationMeta.conversationTitle,
                    memberNodeIds = conversationMeta.memberNodeIds,
                    collectiveOwnerNodeId = conversationMeta.collectiveOwnerNodeId,
                    collectiveAdminNodeIds = conversationMeta.collectiveAdminNodeIds,
                    collectiveModeratorNodeIds = conversationMeta.collectiveModeratorNodeIds,
                    collectiveBroadcastOnly = conversationMeta.collectiveBroadcastOnly,
                    collectiveAllowMemberReactions = conversationMeta.collectiveAllowMemberReactions,
                    collectiveAllowMemberEditOwnMessages = conversationMeta.collectiveAllowMemberEditOwnMessages,
                    collectiveAllowMemberDeleteOwnMessages = conversationMeta.collectiveAllowMemberDeleteOwnMessages,
                    fileName = payload.fileName?.trim()?.ifBlank { null } ?: "file_$transferId",
                    mimeType = payload.mimeType?.trim()?.ifBlank { null } ?: "application/octet-stream",
                    sizeBytes = payload.fileSizeBytes ?: 0L,
                    sha256 = payload.fileSha256?.trim().orEmpty(),
                    caption = payload.fileCaption?.trim()?.take(MAX_FILE_CAPTION_LENGTH).orEmpty(),
                    mediaAlbumId = payload.mediaAlbumId?.trim()?.ifBlank { null },
                    mediaAlbumIndex = payload.mediaAlbumIndex.coerceAtLeast(0),
                    mediaAlbumCount = payload.mediaAlbumCount.coerceAtLeast(1),
                    compressed = payload.compressed,
                    chunkCount = chunkCount,
                    sentAtMs = payload.sentAtMs.takeIf { it > 0 } ?: packet.createdAtMs,
                    createdAtMs = now,
                    updatedAtMs = now
                ).also { fileTransferAssemblers[assemblerKey] = it }
            } else {
                existing
            }

            assembler.updatedAtMs = now
            assembler.chunks[chunkIndex] = chunkBytes
            if (assembler.chunks.values.sumOf { it.size } > MAX_TRANSFER_BYTES) {
                fileTransferAssemblers.remove(assemblerKey)
                return@synchronized null
            }
            if (assembler.chunks.size != assembler.chunkCount) {
                val shouldAck = now - assembler.lastAckSentAtMs >= FILE_ACK_INTERVAL_MS ||
                    assembler.chunks.size - assembler.lastAckedChunkCount >= FILE_ACK_BATCH_SIZE
                val ackIndexes = if (shouldAck) assembler.chunks.keys.sorted() else emptyList()
                if (shouldAck) {
                    assembler.lastAckSentAtMs = now
                    assembler.lastAckedChunkCount = assembler.chunks.size
                }
                return@synchronized IncomingFileChunkResult(
                    completedTransfer = null,
                    shouldAck = shouldAck,
                    ackIndexes = ackIndexes,
                    ackComplete = false
                )
            }

            val assembledBytes = ByteArrayOutputStream().use { out ->
                for (i in 0 until assembler.chunkCount) {
                    val part = assembler.chunks[i] ?: return@synchronized null
                    out.write(part)
                }
                out.toByteArray()
            }
            fileTransferAssemblers.remove(assemblerKey)
            IncomingFileChunkResult(
                completedTransfer = CompletedTransfer(assembler = assembler, transferBytes = assembledBytes),
                shouldAck = true,
                ackIndexes = emptyList(),
                ackComplete = true
            )
        }
        val chunkResult = result ?: return
        persistIncomingTransfersSnapshot(force = chunkResult.completedTransfer != null)

        if (chunkResult.shouldAck) {
            sendFileAck(
                senderIdentity = senderIdentity,
                transferId = transferId,
                chunkCount = chunkCount,
                ackIndexes = chunkResult.ackIndexes,
                ackComplete = chunkResult.ackComplete,
                conversationMeta = conversationMeta
            )
        }
        val completedTransfer = chunkResult.completedTransfer ?: return

        val rawBytes = if (completedTransfer.assembler.compressed) {
            ungzip(completedTransfer.transferBytes)
        } else {
            completedTransfer.transferBytes
        } ?: run {
            requestFullIncomingTransferRetry(
                senderIdentity = senderIdentity,
                transferId = transferId,
                chunkCount = chunkCount,
                conversationMeta = conversationMeta
            )
            updateStatus("Failed to decode received file ${completedTransfer.assembler.fileName}")
            return
        }

        val calculatedHash = sha256Hex(rawBytes)
        if (completedTransfer.assembler.sha256.isNotBlank() &&
            !calculatedHash.equals(completedTransfer.assembler.sha256, ignoreCase = true)
        ) {
            requestFullIncomingTransferRetry(
                senderIdentity = senderIdentity,
                transferId = transferId,
                chunkCount = chunkCount,
                conversationMeta = conversationMeta
            )
            updateStatus("Dropped file with invalid integrity hash")
            return
        }

        val localPath = localStore.saveAttachment(
            transferId = completedTransfer.assembler.transferId,
            fileName = completedTransfer.assembler.fileName,
            bytes = rawBytes
        ) ?: run {
            requestFullIncomingTransferRetry(
                senderIdentity = senderIdentity,
                transferId = transferId,
                chunkCount = chunkCount,
                conversationMeta = conversationMeta
            )
            updateStatus("Failed to store encrypted file ${completedTransfer.assembler.fileName}")
            return
        }
        appendMessage(
            ChatMessage(
                id = "file:${completedTransfer.assembler.transferId}",
                text = completedTransfer.assembler.caption.ifBlank {
                    completedTransfer.assembler.fileName
                },
                originNodeId = completedTransfer.assembler.originNodeId,
                targetNodeId = nodeId,
                relayNodeId = packet.relayNodeId,
                createdAtMs = completedTransfer.assembler.sentAtMs,
                isLocal = false,
                isEncrypted = true,
                conversationId = completedTransfer.assembler.conversationId,
                conversationType = completedTransfer.assembler.conversationType,
                conversationTitle = completedTransfer.assembler.conversationTitle,
                senderAlias = completedTransfer.assembler.senderAlias,
                memberNodeIds = completedTransfer.assembler.memberNodeIds,
                collectiveOwnerNodeId = completedTransfer.assembler.collectiveOwnerNodeId,
                collectiveAdminNodeIds = completedTransfer.assembler.collectiveAdminNodeIds,
                collectiveModeratorNodeIds = completedTransfer.assembler.collectiveModeratorNodeIds,
                collectiveBroadcastOnly = completedTransfer.assembler.collectiveBroadcastOnly,
                collectiveAllowMemberReactions = completedTransfer.assembler.collectiveAllowMemberReactions,
                collectiveAllowMemberEditOwnMessages = completedTransfer.assembler.collectiveAllowMemberEditOwnMessages,
                collectiveAllowMemberDeleteOwnMessages = completedTransfer.assembler.collectiveAllowMemberDeleteOwnMessages,
                contentType = ChatContentType.FILE,
                deliveryState = MessageDeliveryState.DELIVERED,
                deliveredAtMs = System.currentTimeMillis(),
                deliveredToNodeIds = listOf(nodeId),
                attachment = MessageAttachment(
                    transferId = completedTransfer.assembler.transferId,
                    fileName = completedTransfer.assembler.fileName,
                    mimeType = completedTransfer.assembler.mimeType,
                    sizeBytes = if (completedTransfer.assembler.sizeBytes > 0) {
                        completedTransfer.assembler.sizeBytes
                    } else {
                        rawBytes.size.toLong()
                    },
                    sha256 = calculatedHash,
                    compressed = completedTransfer.assembler.compressed,
                    localUri = localPath,
                    mediaAlbumId = completedTransfer.assembler.mediaAlbumId,
                    mediaAlbumIndex = completedTransfer.assembler.mediaAlbumIndex,
                    mediaAlbumCount = completedTransfer.assembler.mediaAlbumCount
                )
            )
        )
        sendDeliveryAck(
            senderIdentity = senderIdentity,
            ackMessageId = "file:${completedTransfer.assembler.transferId}",
            conversationId = completedTransfer.assembler.conversationId,
            conversationType = completedTransfer.assembler.conversationType,
            conversationTitle = completedTransfer.assembler.conversationTitle,
            memberNodeIds = completedTransfer.assembler.memberNodeIds
        )
        sendFileAck(
            senderIdentity = senderIdentity,
            transferId = transferId,
            chunkCount = chunkCount,
            ackIndexes = emptyList(),
            ackComplete = true,
            conversationMeta = conversationMeta
        )
        markTransferCompleted(completedTransfer.assembler.transferId)
        updateStatus(
            "Encrypted file from ${completedTransfer.assembler.senderAlias}: ${completedTransfer.assembler.fileName}"
        )
    }

    private fun resolveConversationMeta(
        payload: MeshMessagePayload,
        senderIdentity: PeerIdentity
    ): ConversationMeta {
        val isChannel = payload.chatType.equals(
            MeshMessagePayload.CHAT_TYPE_CHANNEL,
            ignoreCase = true
        )
        val isGroup = payload.chatType.equals(MeshMessagePayload.CHAT_TYPE_GROUP, ignoreCase = true)
        if (!isGroup && !isChannel) {
            return ConversationMeta(
                conversationId = directConversationId(nodeId, senderIdentity.nodeId),
                conversationType = ConversationType.DIRECT,
                conversationTitle = senderIdentity.alias,
                memberNodeIds = listOf(nodeId, senderIdentity.nodeId),
                collectiveOwnerNodeId = null,
                collectiveAdminNodeIds = emptyList(),
                collectiveModeratorNodeIds = emptyList(),
                collectiveBroadcastOnly = null,
                collectiveAllowMemberReactions = null,
                collectiveAllowMemberEditOwnMessages = null,
                collectiveAllowMemberDeleteOwnMessages = null
            )
        }

        val conversationId = payload.chatId
            .trim()
            .ifBlank {
                if (isChannel) {
                    "chn:${senderIdentity.nodeId}:mesh"
                } else {
                    "grp:${senderIdentity.nodeId}:mesh"
                }
            }
        val memberNodeIds = payload.memberNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .let { members ->
                val withSender = if (members.contains(senderIdentity.nodeId)) {
                    members
                } else {
                    members + senderIdentity.nodeId
                }
                if (withSender.contains(nodeId)) withSender else withSender + nodeId
            }
        val conversationTitle = payload.chatTitle?.trim()?.ifBlank { null }
            ?: if (isChannel) {
                "Channel ${conversationId.takeLast(4)}"
            } else {
                "Group ${conversationId.takeLast(4)}"
            }
        val adminNodeIds = payload.collectiveAdminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf(senderIdentity.nodeId) }
        val ownerNodeId = payload.collectiveOwnerNodeId
            ?.trim()
            ?.ifBlank { null }
            ?: adminNodeIds.firstOrNull()
            ?: senderIdentity.nodeId
        val moderatorNodeIds = payload.collectiveModeratorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && memberNodeIds.contains(it) && !adminNodeIds.contains(it) }
            .distinct()
        return ConversationMeta(
            conversationId = conversationId,
            conversationType = if (isChannel) ConversationType.CHANNEL else ConversationType.GROUP,
            conversationTitle = conversationTitle,
            memberNodeIds = memberNodeIds,
            collectiveOwnerNodeId = ownerNodeId,
            collectiveAdminNodeIds = adminNodeIds,
            collectiveModeratorNodeIds = moderatorNodeIds,
            collectiveBroadcastOnly = payload.collectiveBroadcastOnly,
            collectiveAllowMemberReactions = payload.collectiveAllowMemberReactions,
            collectiveAllowMemberEditOwnMessages = payload.collectiveAllowMemberEditOwnMessages,
            collectiveAllowMemberDeleteOwnMessages = payload.collectiveAllowMemberDeleteOwnMessages
        )
    }

    private fun sendFileAck(
        senderIdentity: PeerIdentity,
        transferId: String,
        chunkCount: Int,
        ackIndexes: List<Int>,
        ackComplete: Boolean,
        conversationMeta: ConversationMeta,
        retryMissingChunks: Boolean = false
    ) {
        val ackPayload = MeshMessagePayload(
            chatId = conversationMeta.conversationId,
            chatType = when (conversationMeta.conversationType) {
                ConversationType.GROUP -> MeshMessagePayload.CHAT_TYPE_GROUP
                ConversationType.CHANNEL -> MeshMessagePayload.CHAT_TYPE_CHANNEL
                ConversationType.DIRECT -> MeshMessagePayload.CHAT_TYPE_DIRECT
            },
            chatTitle = conversationMeta.conversationTitle,
            memberNodeIds = conversationMeta.memberNodeIds,
            collectiveOwnerNodeId = conversationMeta.collectiveOwnerNodeId,
            collectiveAdminNodeIds = conversationMeta.collectiveAdminNodeIds,
            collectiveModeratorNodeIds = conversationMeta.collectiveModeratorNodeIds,
            collectiveBroadcastOnly = conversationMeta.collectiveBroadcastOnly,
            collectiveAllowMemberReactions = conversationMeta.collectiveAllowMemberReactions,
            collectiveAllowMemberEditOwnMessages = conversationMeta.collectiveAllowMemberEditOwnMessages,
            collectiveAllowMemberDeleteOwnMessages = conversationMeta.collectiveAllowMemberDeleteOwnMessages,
            payloadKind = MeshMessagePayload.KIND_FILE_ACK,
            transferId = transferId,
            chunkCount = chunkCount,
            ackChunkIndexes = ackIndexes,
            ackComplete = ackComplete,
            retryMissingChunks = retryMissingChunks,
            sentAtMs = System.currentTimeMillis()
        )
        sendPayloadToRecipients(
            plaintext = json.encodeToString(ackPayload),
            recipients = listOf(senderIdentity)
        )
    }

    private fun requestFullIncomingTransferRetry(
        senderIdentity: PeerIdentity,
        transferId: String,
        chunkCount: Int,
        conversationMeta: ConversationMeta
    ) {
        sendFileAck(
            senderIdentity = senderIdentity,
            transferId = transferId,
            chunkCount = chunkCount,
            ackIndexes = emptyList(),
            ackComplete = false,
            conversationMeta = conversationMeta,
            retryMissingChunks = true
        )
    }

    private fun publishIncomingTransferProgressSnapshot() {
        val snapshot = synchronized(lock) {
            fileTransferAssemblers.values
                .sortedByDescending { it.updatedAtMs }
                .map { assembler ->
                    IncomingFileTransferProgress(
                        transferId = assembler.transferId,
                        conversationId = assembler.conversationId,
                        senderNodeId = assembler.originNodeId,
                        senderAlias = assembler.senderAlias,
                        fileName = assembler.fileName,
                        sizeBytes = assembler.sizeBytes,
                        receivedChunks = assembler.chunks.size.coerceAtMost(assembler.chunkCount),
                        totalChunks = assembler.chunkCount,
                        updatedAtMs = assembler.updatedAtMs
                    )
                }
        }
        _incomingFileTransfers.value = snapshot
    }

    fun retryIncomingFileTransfer(transferId: String): Boolean {
        val normalizedId = transferId.trim()
        if (normalizedId.isBlank()) return false
        val request = synchronized(lock) {
            val assembler = fileTransferAssemblers.values
                .firstOrNull { it.transferId == normalizedId }
                ?: return@synchronized null
            val senderIdentity = peerIdentityByNodeId[assembler.originNodeId]
                ?: return@synchronized null
            IncomingTransferRetryRequest(
                senderIdentity = senderIdentity,
                transferId = assembler.transferId,
                chunkCount = assembler.chunkCount,
                receivedIndexes = assembler.chunks.keys.sorted(),
                conversationMeta = ConversationMeta(
                    conversationId = assembler.conversationId,
                    conversationType = assembler.conversationType,
                    conversationTitle = assembler.conversationTitle,
                    memberNodeIds = assembler.memberNodeIds,
                    collectiveOwnerNodeId = assembler.collectiveOwnerNodeId,
                    collectiveAdminNodeIds = assembler.collectiveAdminNodeIds,
                    collectiveModeratorNodeIds = assembler.collectiveModeratorNodeIds,
                    collectiveBroadcastOnly = assembler.collectiveBroadcastOnly,
                    collectiveAllowMemberReactions = assembler.collectiveAllowMemberReactions,
                    collectiveAllowMemberEditOwnMessages = assembler.collectiveAllowMemberEditOwnMessages,
                    collectiveAllowMemberDeleteOwnMessages = assembler.collectiveAllowMemberDeleteOwnMessages
                )
            )
        } ?: run {
            updateStatus("Sender is not reachable for file retry")
            return false
        }
        sendFileAck(
            senderIdentity = request.senderIdentity,
            transferId = request.transferId,
            chunkCount = request.chunkCount,
            ackIndexes = request.receivedIndexes,
            ackComplete = false,
            conversationMeta = request.conversationMeta,
            retryMissingChunks = true
        )
        updateStatus("Requested missing chunks for ${request.transferId}")
        return true
    }

    fun cancelIncomingFileTransfer(transferId: String): Boolean {
        val normalizedId = transferId.trim()
        if (normalizedId.isBlank()) return false
        val removed = synchronized(lock) {
            val key = fileTransferAssemblers.entries
                .firstOrNull { it.value.transferId == normalizedId }
                ?.key
                ?: return@synchronized false
            fileTransferAssemblers.remove(key) != null
        }
        if (!removed) return false
        persistIncomingTransfersSnapshot(force = true)
        updateStatus("Stopped receiving file transfer: $normalizedId")
        return true
    }

    private fun handleIncomingFileAck(
        senderNodeId: String,
        payload: MeshMessagePayload
    ) {
        val transferId = payload.transferId?.trim().orEmpty()
        if (transferId.isBlank()) return
        val ackIndexes = payload.ackChunkIndexes
            .filter { it >= 0 && it < payload.chunkCount.coerceAtLeast(1) }
            .distinct()
        var changed = false
        val completed = synchronized(lock) {
            val transfer = outgoingTransfers[transferId] ?: return@synchronized false
            val recipient = transfer.recipients[senderNodeId] ?: return@synchronized false
            if (payload.chunkCount > 0 && payload.chunkCount != transfer.chunkCount) {
                return@synchronized false
            }
            val now = System.currentTimeMillis()
            val beforeAcked = recipient.ackedChunks.toSet()
            recipient.lastAckAtMs = now
            if (payload.retryMissingChunks) {
                recipient.ackedChunks.clear()
                recipient.ackedChunks.addAll(ackIndexes)
                recipient.nextChunkCursor = 0
            } else {
                ackIndexes.forEach { recipient.ackedChunks += it }
            }
            if (payload.ackComplete && !payload.retryMissingChunks) {
                repeat(transfer.chunkCount) { recipient.ackedChunks += it }
            }
            changed = changed || recipient.ackedChunks != beforeAcked
            transfer.updatedAtMs = if (payload.retryMissingChunks) {
                now - TRANSFER_RESEND_GAP_MS
            } else {
                now
            }
            if (transfer.recipients.values.all { it.ackedChunks.size >= transfer.chunkCount }) {
                outgoingTransfers.remove(transferId)
                changed = true
                true
            } else {
                false
            }
        }
        if (changed) {
            persistOutgoingTransfersSnapshot(force = completed)
        }
        if (completed) {
            updateStatus("File delivered to all recipients: $transferId")
        } else {
            flushPendingTransfers()
            flushPendingPayloads()
        }
    }

    private fun selectMissingChunkIndexesForRecipient(
        state: OutgoingTransferRecipientState,
        chunkCount: Int,
        windowSize: Int
    ): List<Int> {
        if (chunkCount <= 0 || windowSize <= 0) return emptyList()
        if (state.ackedChunks.size >= chunkCount) return emptyList()

        val limit = min(windowSize, chunkCount)
        val selected = ArrayList<Int>(limit)
        var cursor = if (state.nextChunkCursor in 0 until chunkCount) {
            state.nextChunkCursor
        } else {
            0
        }
        var traversed = 0
        while (selected.size < limit && traversed < chunkCount) {
            if (!state.ackedChunks.contains(cursor)) {
                selected += cursor
            }
            cursor = (cursor + 1) % chunkCount
            traversed++
        }
        state.nextChunkCursor = cursor
        return selected
    }

    private fun cleanupFileTransferAssemblers(nowMs: Long) {
        synchronized(lock) {
            trimIncomingTransfersLocked(nowMs)
        }
    }

    private fun trimIncomingTransfersLocked(nowMs: Long = System.currentTimeMillis()) {
        val iterator = fileTransferAssemblers.iterator()
        while (iterator.hasNext()) {
            val (_, assembler) = iterator.next()
            if (nowMs - assembler.updatedAtMs > FILE_ASSEMBLER_TTL_MS) {
                iterator.remove()
            }
        }
        while (fileTransferAssemblers.size > MAX_INCOMING_TRANSFERS) {
            val oldestKey = fileTransferAssemblers.entries
                .minByOrNull { it.value.updatedAtMs }
                ?.key
                ?: break
            fileTransferAssemblers.remove(oldestKey)
        }
    }

    private fun flushPendingTransfers() {
        if (!_isRunning.value) return
        var trimmed: Boolean
        val transferPlans = synchronized(lock) {
            val now = System.currentTimeMillis()
            val beforeTrim = outgoingTransfers.size
            trimOutgoingTransfersLocked(now)
            trimmed = outgoingTransfers.size != beforeTrim
            val reservedNodeIds = linkedSetOf<String>()
            outgoingTransfers.values.mapNotNull transferLoop@{ transfer ->
                val pendingRecipients = transfer.recipients.values.mapNotNull recipientLoop@{ state ->
                    if (state.dispatchInFlight ||
                        activeTransferNodeIds.contains(state.nodeId) ||
                        !reservedNodeIds.add(state.nodeId)
                    ) {
                        return@recipientLoop null
                    }
                    if (now - state.lastSentAtMs < TRANSFER_RESEND_GAP_MS) {
                        reservedNodeIds.remove(state.nodeId)
                        return@recipientLoop null
                    }
                    val identity = peerIdentityByNodeId[state.nodeId] ?: run {
                        reservedNodeIds.remove(state.nodeId)
                        return@recipientLoop null
                    }
                    val missing = selectMissingChunkIndexesForRecipient(
                        state = state,
                        chunkCount = transfer.chunkCount,
                        windowSize = RESEND_WINDOW_CHUNKS
                    )
                    if (missing.isEmpty()) {
                        reservedNodeIds.remove(state.nodeId)
                        return@recipientLoop null
                    }
                    state.lastSentAtMs = now
                    state.dispatchInFlight = true
                    activeTransferNodeIds += state.nodeId
                    identity to missing
                }
                if (pendingRecipients.isEmpty()) {
                    null
                } else {
                    transfer.updatedAtMs = now
                    transfer to pendingRecipients
                }
            }
        }
        transferPlans.forEach { (transfer, recipients) ->
            recipients.forEach { (recipient, missingIndexes) ->
                dispatchTransferChunkIndexes(
                    transfer = transfer,
                    indexes = missingIndexes,
                    recipients = listOf(recipient)
                )
            }
        }
        if (trimmed) {
            persistOutgoingTransfersSnapshot(force = true)
        }
    }

    private fun enqueuePendingPayload(
        plaintext: String,
        targetNodeIds: List<String>,
        messageId: String? = null
    ) {
        val normalizedTargets = targetNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && it != nodeId }
            .distinct()
        if (normalizedTargets.isEmpty()) return
        val payloadText = plaintext.trim()
        if (payloadText.isEmpty()) return

        synchronized(lock) {
            val now = System.currentTimeMillis()
            val normalizedMessageId = messageId?.trim()?.ifBlank { null }
            val queueId = normalizedMessageId ?: "pending:${UUID.randomUUID()}"
            val existing = pendingPayloads[queueId]
            pendingPayloads[queueId] = if (existing == null) {
                PendingPayloadDispatch(
                    queueId = queueId,
                    messageId = normalizedMessageId,
                    plaintext = payloadText,
                    targetNodeIds = normalizedTargets,
                    createdAtMs = now,
                    lastAttemptAtMs = 0L
                )
            } else {
                existing.copy(
                    plaintext = payloadText,
                    targetNodeIds = (existing.targetNodeIds + normalizedTargets).distinct(),
                    lastAttemptAtMs = 0L
                )
            }
            trimPendingPayloadsLocked(now)
        }
        persistPendingPayloadsSnapshot()
    }

    private fun flushPendingPayloads() {
        if (!_isRunning.value) return
        var changed = false
        val plans = synchronized(lock) {
            val now = System.currentTimeMillis()
            trimPendingPayloadsLocked(now)
            pendingPayloads.values.mapNotNull { pending ->
                if (now - pending.lastAttemptAtMs < PENDING_PAYLOAD_RESEND_GAP_MS) {
                    null
                } else {
                    val recipients = pending.targetNodeIds
                        .mapNotNull { targetNodeId -> peerIdentityByNodeId[targetNodeId] }
                        .distinctBy { it.nodeId }
                    changed = true
                    pendingPayloads[pending.queueId] = pending.copy(lastAttemptAtMs = now)
                    if (recipients.isEmpty()) {
                        null
                    } else {
                        pending to recipients
                    }
                }
            }
        }
        plans.forEach { (pending, recipients) ->
            val sentNodeIds = sendPayloadToRecipientsDetailed(
                plaintext = pending.plaintext,
                recipients = recipients
            )
            var promoteMessageId: String? = null
            synchronized(lock) {
                val current = pendingPayloads[pending.queueId] ?: return@synchronized
                val remainingTargets = current.targetNodeIds.filterNot { sentNodeIds.contains(it) }
                if (remainingTargets.isEmpty()) {
                    changed = true
                    pendingPayloads.remove(pending.queueId)
                    promoteMessageId = current.messageId
                } else {
                    changed = true
                    pendingPayloads[pending.queueId] = current.copy(
                        targetNodeIds = remainingTargets,
                        lastAttemptAtMs = System.currentTimeMillis()
                    )
                }
            }
            if (!promoteMessageId.isNullOrBlank()) {
                markLocalMessageSentIfPending(promoteMessageId!!)
            }
        }
        if (changed) {
            persistPendingPayloadsSnapshot()
        }
    }

    private fun trimPendingPayloadsLocked(nowMs: Long = System.currentTimeMillis()) {
        val iterator = pendingPayloads.iterator()
        while (iterator.hasNext()) {
            val (_, pending) = iterator.next()
            if (pending.targetNodeIds.isEmpty() ||
                nowMs - pending.createdAtMs > PENDING_PAYLOAD_TTL_MS
            ) {
                iterator.remove()
            }
        }
        while (pendingPayloads.size > MAX_PENDING_PAYLOADS) {
            val first = pendingPayloads.entries.firstOrNull()?.key ?: break
            pendingPayloads.remove(first)
        }
    }

    private fun markLocalMessageSentIfPending(messageId: String) {
        val targetId = messageId.trim()
        if (targetId.isBlank()) return
        mutateMessages { current ->
            current.map { message ->
                if (message.id == targetId &&
                    message.isLocal &&
                    message.deliveryState == MessageDeliveryState.PENDING
                ) {
                    message.copy(deliveryState = MessageDeliveryState.SENT)
                } else {
                    message
                }
            }
        }
    }

    private fun trimOutgoingTransfersLocked(nowMs: Long = System.currentTimeMillis()) {
        val iterator = outgoingTransfers.iterator()
        while (iterator.hasNext()) {
            val (_, transfer) = iterator.next()
            if (nowMs - transfer.createdAtMs > OUTGOING_TRANSFER_TTL_MS) {
                iterator.remove()
            }
        }
        while (outgoingTransfers.size > MAX_OUTGOING_TRANSFERS) {
            val first = outgoingTransfers.entries.firstOrNull()?.key ?: break
            outgoingTransfers.remove(first)
        }
    }

    private fun isTransferCompleted(transferId: String): Boolean {
        synchronized(lock) {
            return completedTransfers.containsKey(transferId)
        }
    }

    private fun markTransferCompleted(transferId: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            completedTransfers[transferId] = now
            val iterator = completedTransfers.iterator()
            while (iterator.hasNext()) {
                val (_, ts) = iterator.next()
                if (now - ts > COMPLETED_TRANSFER_TTL_MS) {
                    iterator.remove()
                }
            }
            while (completedTransfers.size > MAX_COMPLETED_TRANSFERS) {
                val first = completedTransfers.entries.firstOrNull()?.key ?: break
                completedTransfers.remove(first)
            }
        }
    }

    private fun appendMessage(message: ChatMessage) {
        mutateMessages { current ->
            val withoutDuplicate = current.filterNot { it.id == message.id }
            withoutDuplicate + message
        }
    }

    private fun applyMessageEdit(
        actorNodeId: String,
        targetMessageId: String,
        newText: String,
        editedAtMs: Long
    ): Boolean {
        if (targetMessageId.isBlank()) return false
        val body = newText.trim()
        if (body.isBlank()) return false
        var changed = false
        mutateMessages { current ->
            current.map { message ->
                if (message.id != targetMessageId) {
                    message
                } else if (!canActorEditMessage(actorNodeId, message) ||
                    message.contentType != ChatContentType.TEXT ||
                    message.isDeleted
                ) {
                    message
                } else if (message.text == body && message.isEdited) {
                    message
                } else {
                    changed = true
                    message.copy(
                        text = body,
                        isEdited = true,
                        editedAtMs = editedAtMs,
                        isDeleted = false
                    )
                }
            }
        }
        return changed
    }

    private fun applyMessageDelete(
        actorNodeId: String,
        targetMessageId: String,
        deletedAtMs: Long
    ): Boolean {
        if (targetMessageId.isBlank()) return false
        var changed = false
        mutateMessages { current ->
            current.map { message ->
                if (message.id != targetMessageId) {
                    message
                } else if (message.isDeleted) {
                    message
                } else {
                    if (message.conversationType != ConversationType.DIRECT &&
                        !isActorInConversationMembers(actorNodeId, message)
                    ) {
                        return@map message
                    }
                    val canModerate = canActorModerateMessage(actorNodeId, message)
                    val canDeleteOwn = canActorDeleteOwnMessage(actorNodeId, message)
                    if (message.originNodeId == actorNodeId && !canDeleteOwn) {
                        message
                    } else if (message.originNodeId != actorNodeId && !canModerate) {
                        message
                    } else {
                        changed = true
                        message.copy(
                            text = "Message deleted",
                            isDeleted = true,
                            isEdited = false,
                            editedAtMs = deletedAtMs,
                            contentType = ChatContentType.TEXT,
                            attachment = null,
                            reactions = emptyList(),
                            savedTags = emptyList(),
                            pinnedAtMs = null
                        )
                    }
                }
            }
        }
        return changed
    }

    private fun applyMessageReaction(
        actorNodeId: String,
        actorAlias: String?,
        targetMessageId: String,
        emoji: String,
        reactedAtMs: Long
    ): Boolean {
        if (targetMessageId.isBlank()) return false
        val normalizedEmoji = emoji.trim().take(16)
        var changed = false
        mutateMessages { current ->
            current.map { message ->
                if (message.id != targetMessageId || message.isDeleted) {
                    message
                } else if (!canActorReactToMessage(actorNodeId, message)) {
                    message
                } else {
                    val withoutActor = message.reactions.filterNot { it.nodeId == actorNodeId }
                    val nextReactions = if (normalizedEmoji.isBlank()) {
                        withoutActor
                    } else {
                        withoutActor + MessageReaction(
                            emoji = normalizedEmoji,
                            nodeId = actorNodeId,
                            senderAlias = actorAlias?.trim()?.ifBlank { null },
                            createdAtMs = reactedAtMs
                        )
                    }
                    if (nextReactions == message.reactions) {
                        message
                    } else {
                        changed = true
                        message.copy(reactions = nextReactions)
                    }
                }
            }
        }
        return changed
    }

    private fun applyConversationPin(
        actorNodeId: String,
        conversationId: String,
        targetMessageId: String,
        pinEnabled: Boolean,
        pinnedAtMs: Long
    ): Boolean {
        if (conversationId.isBlank() || targetMessageId.isBlank()) return false
        var changed = false
        mutateMessages { current ->
            val targetMessage = current.firstOrNull { message ->
                message.conversationId == conversationId &&
                    message.id == targetMessageId &&
                    !message.isDeleted
            }
            if (targetMessage == null) {
                return@mutateMessages current
            }
            if (!canActorPinMessage(actorNodeId, targetMessage)) {
                return@mutateMessages current
            }
            current.map { message ->
                if (message.conversationId != conversationId) {
                    message
                } else {
                    val shouldPin = pinEnabled &&
                        message.id == targetMessageId &&
                        !message.isDeleted
                    val nextPinnedAt = if (shouldPin) pinnedAtMs else null
                    if (message.pinnedAtMs == nextPinnedAt) {
                        message
                    } else {
                        changed = true
                        message.copy(pinnedAtMs = nextPinnedAt)
                    }
                }
            }
        }
        return changed
    }

    private fun canActorEditMessage(actorNodeId: String, message: ChatMessage): Boolean {
        val actor = actorNodeId.trim()
        if (actor.isBlank()) return false
        if (message.originNodeId != actor) return false
        return when (message.conversationType) {
            ConversationType.DIRECT -> isActorInConversationMembers(actor, message)
            ConversationType.GROUP, ConversationType.CHANNEL -> {
                if (!isActorInConversationMembers(actor, message)) {
                    false
                } else if (canActorPublishInCollective(actor, message)) {
                    true
                } else if (message.collectiveBroadcastOnly == true) {
                    false
                } else {
                    message.collectiveAllowMemberEditOwnMessages != false
                }
            }
        }
    }

    private fun canActorReactToMessage(actorNodeId: String, message: ChatMessage): Boolean {
        val actor = actorNodeId.trim()
        if (actor.isBlank()) return false
        if (!isActorInConversationMembers(actor, message)) return false
        return when (message.conversationType) {
            ConversationType.DIRECT -> true
            ConversationType.GROUP, ConversationType.CHANNEL -> {
                canActorPublishInCollective(actor, message) ||
                    message.collectiveAllowMemberReactions != false
            }
        }
    }

    private fun canActorDeleteOwnMessage(actorNodeId: String, message: ChatMessage): Boolean {
        val actor = actorNodeId.trim()
        if (actor.isBlank()) return false
        if (message.originNodeId != actor) return false
        if (!isActorInConversationMembers(actor, message)) return false
        return when (message.conversationType) {
            ConversationType.DIRECT -> true
            ConversationType.GROUP, ConversationType.CHANNEL -> {
                if (canActorPublishInCollective(actor, message)) {
                    true
                } else if (message.collectiveBroadcastOnly == true) {
                    false
                } else {
                    message.collectiveAllowMemberDeleteOwnMessages != false
                }
            }
        }
    }

    private fun canActorPinMessage(actorNodeId: String, message: ChatMessage): Boolean {
        val actor = actorNodeId.trim()
        if (actor.isBlank()) return false
        return when (message.conversationType) {
            ConversationType.DIRECT -> isActorInConversationMembers(actor, message)
            ConversationType.GROUP, ConversationType.CHANNEL -> canActorModerateMessage(actor, message)
        }
    }

    private fun canActorPublishInCollective(actorNodeId: String, message: ChatMessage): Boolean {
        val actor = actorNodeId.trim()
        if (actor.isBlank()) return false
        return isActorCollectiveAdmin(actor, message) || isActorCollectiveModerator(actor, message)
    }

    private fun canActorModerateMessage(actorNodeId: String, message: ChatMessage): Boolean {
        val actor = actorNodeId.trim()
        if (actor.isBlank()) return false
        if (!isActorInConversationMembers(actor, message)) return false
        return when (message.conversationType) {
            ConversationType.DIRECT -> false
            ConversationType.GROUP, ConversationType.CHANNEL -> canActorPublishInCollective(actor, message)
        }
    }

    private fun isActorCollectiveAdmin(actorNodeId: String, message: ChatMessage): Boolean {
        val actor = actorNodeId.trim()
        if (actor.isBlank()) return false
        val ownerId = message.collectiveOwnerNodeId
            ?.trim()
            ?.ifBlank { null }
            ?: message.originNodeId
        val admins = message.collectiveAdminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .let { current ->
                if (current.contains(ownerId)) current else listOf(ownerId) + current
            }
            .distinct()
        return admins.contains(actor)
    }

    private fun isActorCollectiveModerator(actorNodeId: String, message: ChatMessage): Boolean {
        val actor = actorNodeId.trim()
        if (actor.isBlank()) return false
        val moderators = message.collectiveModeratorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return moderators.contains(actor)
    }

    private fun isActorInConversationMembers(actorNodeId: String, message: ChatMessage): Boolean {
        val actor = actorNodeId.trim()
        if (actor.isBlank()) return false
        val explicitMembers = message.memberNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (explicitMembers.isNotEmpty()) {
            return explicitMembers.contains(actor)
        }
        return when (message.conversationType) {
            ConversationType.DIRECT -> {
                val fallbackMembers = buildList {
                    add(message.originNodeId)
                    message.targetNodeId?.let { add(it) }
                    add(nodeId)
                }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                fallbackMembers.contains(actor)
            }
            ConversationType.GROUP, ConversationType.CHANNEL -> {
                actor == message.originNodeId || actor == nodeId
            }
        }
    }

    private fun applyMessageDelivered(
        targetMessageId: String,
        deliveredByNodeId: String,
        deliveredAtMs: Long
    ): Boolean {
        if (targetMessageId.isBlank() || deliveredByNodeId.isBlank()) return false
        var changed = false
        mutateMessages { current ->
            current.map { message ->
                if (message.id != targetMessageId || !message.isLocal) {
                    message
                } else {
                    val deliveredSet = (message.deliveredToNodeIds + deliveredByNodeId)
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                    val nextState = if (deliveredSet.isNotEmpty()) {
                        MessageDeliveryState.DELIVERED
                    } else {
                        MessageDeliveryState.SENT
                    }
                    if (deliveredSet == message.deliveredToNodeIds &&
                        nextState == message.deliveryState
                    ) {
                        message
                    } else {
                        changed = true
                        message.copy(
                            deliveryState = nextState,
                            deliveredAtMs = deliveredAtMs,
                            deliveredToNodeIds = deliveredSet
                        )
                    }
                }
            }
        }
        return changed
    }

    private fun applyMessageRelayed(
        targetMessageId: String,
        relayedByNodeId: String
    ): Boolean {
        if (targetMessageId.isBlank() || relayedByNodeId.isBlank()) return false
        var changed = false
        mutateMessages { current ->
            current.map { message ->
                if (message.id != targetMessageId || !message.isLocal) {
                    message
                } else {
                    val relayedSet = (message.relayedByNodeIds + relayedByNodeId)
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                    val nextState = if (message.deliveryState == MessageDeliveryState.DELIVERED) {
                        MessageDeliveryState.DELIVERED
                    } else if (relayedSet.isNotEmpty()) {
                        MessageDeliveryState.RELAYED
                    } else {
                        MessageDeliveryState.SENT
                    }
                    if (relayedSet == message.relayedByNodeIds &&
                        nextState == message.deliveryState
                    ) {
                        message
                    } else {
                        changed = true
                        message.copy(
                            deliveryState = nextState,
                            relayedByNodeIds = relayedSet
                        )
                    }
                }
            }
        }
        return changed
    }

    private fun sendRelayAck(
        senderIdentity: PeerIdentity,
        relayedMessageId: String
    ) {
        val messageId = relayedMessageId.trim()
        if (messageId.isBlank()) return
        val payload = MeshMessagePayload(
            chatId = "relay:$messageId",
            chatType = MeshMessagePayload.CHAT_TYPE_DIRECT,
            payloadKind = MeshMessagePayload.KIND_MESSAGE_RELAY_ACK,
            ackMessageId = messageId,
            sentAtMs = System.currentTimeMillis()
        )
        sendPayloadToRecipients(
            plaintext = json.encodeToString(payload),
            recipients = listOf(senderIdentity),
            cacheForRelay = false
        )
    }

    private fun sendDeliveryAck(
        senderIdentity: PeerIdentity,
        ackMessageId: String,
        conversationId: String,
        conversationType: ConversationType,
        conversationTitle: String?,
        memberNodeIds: List<String>
    ) {
        val messageId = ackMessageId.trim()
        if (messageId.isBlank()) return
        val chatId = conversationId.trim()
        if (chatId.isBlank()) return
        val payload = MeshMessagePayload(
            chatId = chatId,
            chatType = when (conversationType) {
                ConversationType.GROUP -> MeshMessagePayload.CHAT_TYPE_GROUP
                ConversationType.CHANNEL -> MeshMessagePayload.CHAT_TYPE_CHANNEL
                ConversationType.DIRECT -> MeshMessagePayload.CHAT_TYPE_DIRECT
            },
            chatTitle = conversationTitle,
            memberNodeIds = memberNodeIds,
            payloadKind = MeshMessagePayload.KIND_MESSAGE_DELIVERY_ACK,
            ackMessageId = messageId,
            sentAtMs = System.currentTimeMillis()
        )
        sendPayloadToRecipients(
            plaintext = json.encodeToString(payload),
            recipients = listOf(senderIdentity),
            cacheForRelay = false
        )
    }

    private fun mutateMessages(
        transform: (List<ChatMessage>) -> List<ChatMessage>
    ) {
        _messages.update { current ->
            val next = transform(current)
            val trimmed = if (next.size <= MAX_MESSAGES) next else next.takeLast(MAX_MESSAGES)
            localStore.persistMessages(trimmed)
            trimmed
        }
    }

    private fun persistPeerIdentityCache() {
        val snapshot = synchronized(lock) {
            peerIdentityByNodeId.values
                .sortedByDescending { it.lastSeenMs }
                .take(MAX_PERSISTED_IDENTITIES)
        }
        localStore.persistPeerIdentities(snapshot)
        _knownIdentities.value = snapshot
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun gzip(data: ByteArray): ByteArray? {
        return runCatching {
            val out = ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(out).use { it.write(data) }
            out.toByteArray()
        }.getOrNull()
    }

    private fun ungzip(data: ByteArray): ByteArray? {
        return runCatching {
            java.util.zip.GZIPInputStream(data.inputStream()).use { it.readBytes() }
        }.getOrNull()
    }

    private fun humanSize(sizeBytes: Long): String {
        if (sizeBytes <= 0L) return "0 B"
        val units = listOf("B", "KB", "MB")
        var value = sizeBytes.toDouble()
        var idx = 0
        while (value >= 1024 && idx < units.lastIndex) {
            value /= 1024
            idx++
        }
        return if (idx == 0) {
            "${value.toInt()} ${units[idx]}"
        } else {
            String.format(java.util.Locale.US, "%.1f %s", value, units[idx])
        }
    }

    private fun cleanupAssemblers(nowMs: Long) {
        synchronized(lock) {
            val iterator = frameAssemblers.iterator()
            while (iterator.hasNext()) {
                val (_, assembler) = iterator.next()
                if (nowMs - assembler.updatedAtMs > ASSEMBLER_TTL_MS) {
                    iterator.remove()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writePacket(gatt: BluetoothGatt, packet: ByteArray): Boolean {
        val service = gatt.getService(SERVICE_UUID) ?: return false
        val characteristic = service.getCharacteristic(MESSAGE_CHARACTERISTIC_UUID) ?: return false
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val address = gatt.device.address ?: return false
        val pending = CompletableDeferred<Boolean>()
        synchronized(lock) {
            pendingWriteByAddress.remove(address)?.complete(false)
            pendingWriteByAddress[address] = pending
        }

        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                packet,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.value = packet
                gatt.writeCharacteristic(characteristic)
            }
        }
        if (!accepted) {
            synchronized(lock) {
                if (pendingWriteByAddress[address] === pending) {
                    pendingWriteByAddress.remove(address)
                }
            }
            pending.complete(false)
            return false
        }
        val ack = withTimeoutOrNull(BLE_WRITE_CALLBACK_TIMEOUT_MS) {
            pending.await()
        }
        if (ack != null) {
            return ack
        }
        synchronized(lock) {
            if (pendingWriteByAddress[address] === pending) {
                pendingWriteByAddress.remove(address)
            }
        }
        return true
    }

    private fun completePendingWrite(address: String, status: Int) {
        if (address.isBlank()) return
        val pending = synchronized(lock) {
            pendingWriteByAddress.remove(address)
        } ?: return
        pending.complete(status == BluetoothGatt.GATT_SUCCESS)
    }

    @SuppressLint("MissingPermission")
    private suspend fun notifyPacket(device: BluetoothDevice, packet: ByteArray): Boolean {
        val server = gattServer ?: return false
        val service = server.getService(SERVICE_UUID) ?: return false
        val notifyCharacteristic = service.getCharacteristic(MESSAGE_NOTIFY_CHARACTERISTIC_UUID)
            ?: return false
        val address = device.address ?: return false
        val callbackUnavailable = synchronized(lock) {
            notificationCallbackUnavailableAddresses.contains(address)
        }
        if (callbackUnavailable) {
            // Some OEM stacks accept notifications but never call onNotificationSent.
            // Keep a small pacing gap; transfer ACKs provide the end-to-end guarantee.
            delay(BLE_NOTIFY_FALLBACK_GAP_MS)
            return true
        }
        val pending = CompletableDeferred<Boolean>()
        synchronized(lock) {
            pendingNotificationByAddress.remove(address)?.complete(false)
            pendingNotificationByAddress[address] = pending
        }

        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, notifyCharacteristic, false, packet) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                notifyCharacteristic.value = packet
                server.notifyCharacteristicChanged(device, notifyCharacteristic, false)
            }
        }
        if (!accepted) {
            synchronized(lock) {
                if (pendingNotificationByAddress[address] === pending) {
                    pendingNotificationByAddress.remove(address)
                }
            }
            return false
        }

        val delivered = withTimeoutOrNull(BLE_NOTIFY_CALLBACK_TIMEOUT_MS) {
            pending.await()
        }
        if (delivered != null) return delivered
        synchronized(lock) {
            if (pendingNotificationByAddress[address] === pending) {
                pendingNotificationByAddress.remove(address)
            }
            notificationCallbackUnavailableAddresses.add(address)
        }
        // Some Android/OEM GATT stacks do not emit onNotificationSent even
        // after accepting the notification. The transfer-level ACK remains
        // the source of truth, so do not block the BLE queue or retry here.
        Log.d(BLE_TAG, "BLE notify callback unavailable address=${address.takeLast(5)}")
        return true
    }

    @SuppressLint("MissingPermission")
    private fun enableIncomingNotify(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(SERVICE_UUID) ?: return false
        val notifyCharacteristic = service.getCharacteristic(MESSAGE_NOTIFY_CHARACTERISTIC_UUID) ?: return false
        val descriptor = notifyCharacteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID) ?: return false

        val notificationsEnabled = runCatching {
            gatt.setCharacteristicNotification(notifyCharacteristic, true)
        }.getOrDefault(false)
        if (!notificationsEnabled) return false

        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            }.getOrDefault(false)
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }.getOrDefault(false)
        }
    }

    private fun isKnownFrame(frameId: String): Boolean {
        val id = frameId.trim()
        if (id.isBlank()) return true
        synchronized(lock) {
            val now = System.currentTimeMillis()
            pruneSeenFramesUnsafe(now)
            val known = seenFrames.containsKey(id)
            if (known) {
                // Refresh timestamp to keep hot duplicate frames in cache.
                seenFrames[id] = now
            }
            return known
        }
    }

    private fun rememberFrame(frameId: String) {
        val id = frameId.trim()
        if (id.isBlank()) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            seenFrames[id] = now
            pruneSeenFramesUnsafe(now)
        }
    }

    private fun pruneSeenFramesUnsafe(now: Long) {
        val iterator = seenFrames.iterator()
        while (iterator.hasNext()) {
            val (_, ts) = iterator.next()
            if (now - ts > SEEN_FRAME_TTL_MS) {
                iterator.remove()
            }
        }
        while (seenFrames.size > MAX_SEEN_FRAMES) {
            val firstKey = seenFrames.entries.firstOrNull()?.key ?: break
            seenFrames.remove(firstKey)
        }
    }

    private fun shouldRelayByHops(hops: Int, maxHops: Int): Boolean {
        return MeshRelayPolicy.canForward(hops, maxHops)
    }

    private fun isValidHelloHopEnvelope(hops: Int, maxHops: Int): Boolean {
        return MeshRelayPolicy.isValidEnvelope(
            hops = hops,
            maxHops = maxHops,
            absoluteMaxHops = HELLO_MAX_HOPS
        )
    }

    private fun isValidMessageHopEnvelope(hops: Int, maxHops: Int): Boolean {
        return MeshRelayPolicy.isValidEnvelope(
            hops = hops,
            maxHops = maxHops,
            absoluteMaxHops = MESSAGE_MAX_HOPS
        )
    }

    private fun upsertPeer(
        address: String,
        nodeId: String? = null,
        alias: String? = null,
        fingerprintShort: String? = null,
        connected: Boolean? = null
    ) {
        synchronized(lock) {
            val current = peerMap[address]
            peerMap[address] = Peer(
                address = address,
                nodeId = nodeId ?: current?.nodeId,
                alias = alias ?: current?.alias ?: address.takeLast(5),
                fingerprintShort = fingerprintShort ?: current?.fingerprintShort,
                isConnected = connected ?: current?.isConnected ?: false,
                lastSeenMs = System.currentTimeMillis()
            )
            updatePeersUnsafe()
        }
    }

    private fun removePeerConnection(address: String) {
        synchronized(lock) {
            val current = peerMap[address] ?: return
            transportMutexByAddress.remove(address)
            clientReadyAddresses.remove(address)
            peerMap[address] = current.copy(
                isConnected = false,
                lastSeenMs = System.currentTimeMillis()
            )
            updatePeersUnsafe()
        }
    }

    private fun updatePeersUnsafe() {
        val connectedSet = clientGatts.keys + serverConnectedAddresses
        val now = System.currentTimeMillis()
        val normalized = peerMap.values.map { peer ->
            val overlayAddress = peer.address.startsWith("wifi:") ||
                peer.address.startsWith("relay:") ||
                peer.address.startsWith("mesh:") ||
                peer.address.startsWith("p2p:")
            val connected = if (overlayAddress) {
                now - peer.lastSeenMs <= OVERLAY_PEER_ONLINE_TTL_MS
            } else {
                connectedSet.contains(peer.address)
            }
            peer.copy(isConnected = connected)
        }.sortedByDescending { it.lastSeenMs }
        _peers.value = normalized
    }

    private val wifiP2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    )
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled) {
                        synchronized(lock) {
                            wifiP2pConnected = false
                            wifiP2pGroupOwnerAddress = null
                            wifiP2pPeers.clear()
                        }
                    }
                    if (enabled && _isRunning.value) {
                        triggerWifiP2pDiscovery(force = true)
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    requestWifiP2pPeers()
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = readNetworkInfoFromIntent(intent)
                    val connected = networkInfo?.isConnected == true
                    synchronized(lock) {
                        wifiP2pConnected = connected
                        if (!connected) {
                            wifiP2pGroupOwnerAddress = null
                        }
                    }
                    if (connected) {
                        requestWifiP2pConnectionInfo()
                        requestWifiP2pGroupInfo()
                        publishHello()
                        flushPendingTransfers()
                        flushPendingPayloads()
                    } else if (_isRunning.value) {
                        triggerWifiP2pDiscovery(force = true)
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    requestWifiP2pPeers()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startWifiP2pBootstrap(): Boolean {
        if (BLE_ONLY_MODE) return false
        val manager = wifiP2pManager ?: return false
        if (!canUseWifiP2p()) return false

        if (wifiP2pChannel == null) {
            wifiP2pChannel = runCatching {
                manager.initialize(context, context.mainLooper) {
                    wifiP2pChannel = null
                    if (_isRunning.value) {
                        runCatching { startWifiP2pBootstrap() }
                    }
                }
            }.getOrNull()
        }
        if (wifiP2pChannel == null) return false

        registerWifiP2pReceiverIfNeeded()
        wifiP2pDiscoveryJob?.cancel()
        wifiP2pDiscoveryJob = scope.launch(Dispatchers.IO) {
            triggerWifiP2pDiscovery(force = true)
            while (isActive && _isRunning.value) {
                delay(WIFI_P2P_DISCOVERY_INTERVAL_MS)
                triggerWifiP2pDiscovery(force = false)
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun stopWifiP2pBootstrap() {
        wifiP2pDiscoveryJob?.cancel()
        wifiP2pDiscoveryJob = null
        val manager = wifiP2pManager
        val channel = wifiP2pChannel
        if (manager != null && channel != null && canUseWifiP2p()) {
            runCatching { manager.stopPeerDiscovery(channel, null) }
            runCatching { manager.cancelConnect(channel, null) }
        }
        unregisterWifiP2pReceiverIfNeeded()
        synchronized(lock) {
            wifiP2pConnected = false
            wifiP2pGroupOwnerAddress = null
            wifiP2pPeers.clear()
        }
    }

    private fun registerWifiP2pReceiverIfNeeded() {
        if (wifiP2pReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        runCatching {
            context.registerReceiver(wifiP2pReceiver, filter)
            wifiP2pReceiverRegistered = true
        }
    }

    private fun unregisterWifiP2pReceiverIfNeeded() {
        if (!wifiP2pReceiverRegistered) return
        runCatching {
            context.unregisterReceiver(wifiP2pReceiver)
        }
        wifiP2pReceiverRegistered = false
    }

    @SuppressLint("MissingPermission")
    private fun triggerWifiP2pDiscovery(force: Boolean) {
        if (!_isRunning.value) return
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        if (!canUseWifiP2p()) return

        val now = System.currentTimeMillis()
        if (!force && now - wifiP2pLastDiscoverAtMs < WIFI_P2P_DISCOVERY_MIN_GAP_MS) return
        wifiP2pLastDiscoverAtMs = now
        runCatching {
            manager.discoverPeers(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        requestWifiP2pPeers()
                    }

                    override fun onFailure(reason: Int) {
                        if (reason != WifiP2pManager.BUSY) {
                            updateStatus("Wi-Fi Direct discovery failed (${wifiP2pReasonLabel(reason)})")
                        }
                    }
                }
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestWifiP2pPeers() {
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        if (!canUseWifiP2p()) return
        runCatching {
            manager.requestPeers(channel) { peers ->
                onWifiP2pPeersAvailable(peers)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onWifiP2pPeersAvailable(peerList: WifiP2pDeviceList?) {
        if (peerList == null) return
        val now = System.currentTimeMillis()
        val snapshots = peerList.deviceList
            .map { device ->
                WifiDirectPeerSnapshot(
                    deviceAddress = device.deviceAddress?.trim().orEmpty(),
                    deviceName = device.deviceName?.trim().orEmpty(),
                    status = device.status,
                    lastSeenMs = now
                )
            }
            .filter { it.deviceAddress.isNotBlank() }

        synchronized(lock) {
            wifiP2pPeers.clear()
            snapshots.forEach { snapshot ->
                wifiP2pPeers[snapshot.deviceAddress] = snapshot
            }
        }

        val activeOverlayKeys = linkedSetOf<String>()
        snapshots.forEach { peer ->
            val alias = if (peer.deviceName.isBlank()) {
                "Wi-Fi Direct ${peer.deviceAddress.takeLast(5)}"
            } else {
                peer.deviceName
            }
            val overlayAddress = "p2p:${peer.deviceAddress.lowercase()}"
            activeOverlayKeys += overlayAddress
            upsertPeer(
                address = overlayAddress,
                alias = alias,
                connected = peer.status == WifiP2pDevice.CONNECTED || wifiP2pConnected
            )
        }
        synchronized(lock) {
            val staleKeys = peerMap.keys.filter { key ->
                key.startsWith("p2p:") && !activeOverlayKeys.contains(key)
            }
            staleKeys.forEach { key ->
                val current = peerMap[key] ?: return@forEach
                peerMap[key] = current.copy(
                    isConnected = false,
                    lastSeenMs = now
                )
            }
            if (staleKeys.isNotEmpty()) {
                updatePeersUnsafe()
            }
        }
        maybeAutoConnectWifiP2pPeer()
    }

    @SuppressLint("MissingPermission")
    private fun maybeAutoConnectWifiP2pPeer() {
        if (!_isRunning.value) return
        if (wifiP2pConnected) return
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        if (!canUseWifiP2p()) return

        val candidate = synchronized(lock) {
            wifiP2pPeers.values.firstOrNull { peer ->
                peer.status == WifiP2pDevice.AVAILABLE || peer.status == WifiP2pDevice.INVITED
            }
        } ?: return

        val now = System.currentTimeMillis()
        if (now - wifiP2pLastConnectAttemptAtMs < WIFI_P2P_CONNECT_MIN_GAP_MS) return
        wifiP2pLastConnectAttemptAtMs = now

        val config = WifiP2pConfig().apply {
            deviceAddress = candidate.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 7
        }
        runCatching {
            manager.connect(
                channel,
                config,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        updateStatus(
                            "Wi-Fi Direct invitation sent to ${candidate.deviceName.ifBlank { candidate.deviceAddress.takeLast(5) }}"
                        )
                    }

                    override fun onFailure(reason: Int) {
                        if (reason != WifiP2pManager.BUSY) {
                            updateStatus("Wi-Fi Direct connect failed (${wifiP2pReasonLabel(reason)})")
                        }
                    }
                }
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestWifiP2pConnectionInfo() {
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        if (!canUseWifiP2p()) return
        runCatching {
            manager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                if (info != null && info.groupFormed) {
                    val groupOwnerHost = info.groupOwnerAddress?.hostAddress
                    synchronized(lock) {
                        wifiP2pConnected = true
                        wifiP2pGroupOwnerAddress = groupOwnerHost
                    }
                    rememberWifiDirectedTarget(groupOwnerHost)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestWifiP2pGroupInfo() {
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        if (!canUseWifiP2p()) return
        runCatching {
            manager.requestGroupInfo(channel) { group: WifiP2pGroup? ->
                if (group != null && group.isGroupOwner) {
                    val clients = group.clientList.orEmpty().mapNotNull { it?.deviceAddress }
                    if (clients.isNotEmpty()) {
                        updateStatus("Wi-Fi Direct group active: ${clients.size} client(s)")
                    }
                }
            }
        }
    }

    private fun readNetworkInfoFromIntent(intent: Intent): NetworkInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
        }
    }

    private fun canUseWifiP2p(): Boolean {
        if (BLE_ONLY_MODE) return false
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun wifiP2pReasonLabel(reason: Int): String {
        return when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
            WifiP2pManager.BUSY -> "busy"
            WifiP2pManager.ERROR -> "error"
            else -> reason.toString()
        }
    }

    @SuppressLint("WifiManagerPotentialLeak")
    private fun startWifiLanTransport(): Boolean {
        if (BLE_ONLY_MODE) return false
        if (wifiLanActive) return true
        val receiveSocket = runCatching {
            MulticastSocket(WIFI_LAN_PORT).apply {
                reuseAddress = true
                soTimeout = WIFI_SOCKET_TIMEOUT_MS
                val multicast = InetAddress.getByName(WIFI_MULTICAST_GROUP)
                runCatching {
                    NetworkInterface.getNetworkInterfaces()
                        ?.asSequence()
                        ?.filter { iface ->
                            runCatching {
                                iface.isUp && !iface.isLoopback && iface.supportsMulticast()
                            }.getOrDefault(false)
                        }
                        ?.forEach { iface ->
                            runCatching { joinGroup(java.net.InetSocketAddress(multicast, WIFI_LAN_PORT), iface) }
                        }
                }
                if (networkInterface == null) {
                    runCatching { joinGroup(multicast) }
                }
            }
        }.getOrNull() ?: return false

        val sendSocket = runCatching {
            DatagramSocket().apply { broadcast = true }
        }.getOrNull() ?: run {
            runCatching { receiveSocket.close() }
            return false
        }

        val lock = runCatching {
            wifiManager?.createMulticastLock("meshgram_wifi_lan_lock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        wifiReceiveSocket = receiveSocket
        wifiSendSocket = sendSocket
        wifiMulticastLock = lock
        wifiLanActive = true
        _wifiLanActive.value = true
        wifiBroadcastTargets = resolveWifiLanBroadcastTargets(force = true)

        wifiReceiveJob?.cancel()
        wifiReceiveJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(WIFI_MAX_PACKET_BYTES)
            while (isActive && wifiLanActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                val received = runCatching {
                    receiveSocket.receive(packet)
                    true
                }.getOrDefault(false)
                if (!received) continue
                val size = packet.length
                if (size <= 0) continue
                val bytes = packet.data.copyOfRange(packet.offset, packet.offset + size)
                val host = packet.address?.hostAddress
                rememberWifiDirectedTarget(host)
                onPayloadDecoded(
                    payload = bytes,
                    fromAddress = host,
                    sourceTransport = TransportSource.WIFI_LAN
                )
            }
        }
        return true
    }

    private fun stopWifiLanTransport() {
        wifiReceiveJob?.cancel()
        wifiReceiveJob = null
        wifiLanActive = false
        _wifiLanActive.value = false
        wifiBroadcastTargets = emptyList()
        wifiBroadcastTargetsUpdatedAtMs = 0L
        synchronized(lock) {
            wifiDirectedTargets.clear()
        }

        val receiveSocket = wifiReceiveSocket
        wifiReceiveSocket = null
        runCatching {
            receiveSocket?.leaveGroup(InetAddress.getByName(WIFI_MULTICAST_GROUP))
        }
        runCatching { receiveSocket?.close() }

        val sendSocket = wifiSendSocket
        wifiSendSocket = null
        runCatching { sendSocket?.close() }

        val lock = wifiMulticastLock
        wifiMulticastLock = null
        runCatching {
            if (lock?.isHeld == true) {
                lock.release()
            }
        }
    }

    private fun publishPayloadToWifiLan(frameId: String, payload: ByteArray) {
        if (!_isRunning.value || !wifiLanActive) return
        if (payload.isEmpty() || payload.size > WIFI_MAX_PACKET_BYTES) return
        val sendSocket = wifiSendSocket ?: return
        if (frameId.isBlank()) return

        val multicastAddress = runCatching {
            InetAddress.getByName(WIFI_MULTICAST_GROUP)
        }.getOrNull()
        val targets = resolveWifiLanBroadcastTargets(force = false)
        val directedTargets = resolveWifiDirectedTargets()

        if (multicastAddress != null) {
            runCatching {
                val packet = DatagramPacket(payload, payload.size, multicastAddress, WIFI_LAN_PORT)
                sendSocket.send(packet)
            }.onFailure {
                updateStatus("Wi-Fi LAN multicast send failed")
            }
        }

        targets.forEach { target ->
            runCatching {
                val packet = DatagramPacket(payload, payload.size, target, WIFI_LAN_PORT)
                sendSocket.send(packet)
            }
        }

        directedTargets.forEach { target ->
            runCatching {
                val packet = DatagramPacket(payload, payload.size, target, WIFI_LAN_PORT)
                sendSocket.send(packet)
            }
        }
    }

    private fun rememberWifiDirectedTarget(hostAddress: String?) {
        val host = hostAddress?.trim()?.ifBlank { null } ?: return
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isMulticastAddress ||
            address.isLinkLocalAddress ||
            address.isBroadcastAddress()
        ) {
            return
        }
        synchronized(lock) {
            val now = System.currentTimeMillis()
            wifiDirectedTargets[host] = now
            trimWifiDirectedTargetsLocked(now)
        }
    }

    private fun resolveWifiDirectedTargets(): List<InetAddress> {
        val now = System.currentTimeMillis()
        val targetHosts = synchronized(lock) {
            trimWifiDirectedTargetsLocked(now)
            val hosts = linkedSetOf<String>()
            wifiP2pGroupOwnerAddress
                ?.trim()
                ?.ifBlank { null }
                ?.let { hosts += it }
            hosts += wifiDirectedTargets.keys
            hosts.toList()
        }
        return targetHosts.mapNotNull { host ->
            runCatching { InetAddress.getByName(host) }.getOrNull()
        }.filterNot { address ->
            address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isMulticastAddress ||
                address.isLinkLocalAddress ||
                address.isBroadcastAddress()
        }.distinctBy { it.hostAddress }
    }

    private fun trimWifiDirectedTargetsLocked(nowMs: Long = System.currentTimeMillis()) {
        val iterator = wifiDirectedTargets.iterator()
        while (iterator.hasNext()) {
            val (_, lastSeenAtMs) = iterator.next()
            if (nowMs - lastSeenAtMs > WIFI_DIRECTED_TARGET_TTL_MS) {
                iterator.remove()
            }
        }
        while (wifiDirectedTargets.size > MAX_WIFI_DIRECTED_TARGETS) {
            val first = wifiDirectedTargets.entries.firstOrNull()?.key ?: break
            wifiDirectedTargets.remove(first)
        }
    }

    private fun InetAddress.isBroadcastAddress(): Boolean {
        return hostAddress == WIFI_BROADCAST_ADDRESS ||
            runCatching {
                address.all { byte -> byte.toInt() and 0xFF == 255 }
            }.getOrDefault(false)
    }

    private fun resolveWifiLanBroadcastTargets(force: Boolean): List<InetAddress> {
        val now = System.currentTimeMillis()
        if (!force &&
            wifiBroadcastTargets.isNotEmpty() &&
            now - wifiBroadcastTargetsUpdatedAtMs < WIFI_BROADCAST_TARGETS_REFRESH_MS
        ) {
            return wifiBroadcastTargets
        }
        val targets = linkedSetOf<InetAddress>()
        runCatching {
            targets += InetAddress.getByName(WIFI_BROADCAST_ADDRESS)
        }
        runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.filter { iface ->
                    runCatching { iface.isUp && !iface.isLoopback }
                        .getOrDefault(false)
                }
                ?.forEach { iface ->
                    iface.interfaceAddresses
                        ?.mapNotNull { address ->
                            val ip = address.address
                            if (ip is Inet4Address && !ip.isLoopbackAddress) {
                                address.broadcast
                            } else {
                                null
                            }
                        }
                        ?.forEach { broadcast ->
                            targets += broadcast
                        }
                }
        }
        val snapshot = targets.toList()
        wifiBroadcastTargets = snapshot
        wifiBroadcastTargetsUpdatedAtMs = now
        return snapshot
    }

    private fun connectRelayIfNeeded() {
        if (OFFLINE_ONLY_MODE || BLE_ONLY_MODE) {
            disconnectRelay(reason = null, updateStateOnly = true)
            return
        }
        if (!_isRunning.value) return
        if (hasReadyBleTransport()) {
            // Presence of a local MeshGram route is enough to keep app traffic
            // off the internet. Reconnect when that route disappears.
            disconnectRelay(reason = "BLE route preferred")
            return
        }
        if (!_relayEnabled.value) {
            disconnectRelay(reason = null, updateStateOnly = true)
            return
        }
        val relayUrl = _relayUrl.value
        if (relayUrl.isBlank()) {
            disconnectRelay(reason = null, updateStateOnly = true)
            return
        }
        if (!isInternetAvailable()) return
        if (relaySocket != null) return

        val request = runCatching { Request.Builder().url(relayUrl).build() }
            .getOrElse {
                _relayConnected.value = false
                updateStatus("Hybrid relay URL is invalid")
                return
            }
        relayReconnectJob?.cancel()
        relayReconnectJob = null
        relaySocket = relayHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    relayAuthenticated = false
                    _relayConnected.value = true
                    refreshModeStatus("relay connected")
                    sendRelayAuthHello(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleRelayEnvelope(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    runCatching { webSocket.close(1000, null) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (relaySocket === webSocket) {
                        relaySocket = null
                    }
                    relayAuthenticated = false
                    _relayConnected.value = false
                    if (_isRunning.value) {
                        scheduleRelayReconnect()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (relaySocket === webSocket) {
                        relaySocket = null
                    }
                    relayAuthenticated = false
                    _relayConnected.value = false
                    if (_isRunning.value && _relayEnabled.value) {
                        scheduleRelayReconnect()
                    }
                }
            }
        )
    }

    private fun disconnectRelay(reason: String?, updateStateOnly: Boolean = false) {
        relayReconnectJob?.cancel()
        relayReconnectJob = null
        val socket = relaySocket
        relaySocket = null
        _relayConnected.value = false
        if (!updateStateOnly) {
            runCatching { socket?.close(1000, reason ?: "closed") }
            runCatching { socket?.cancel() }
        }
    }

    private fun scheduleRelayReconnect() {
        if (OFFLINE_ONLY_MODE) return
        if (!_isRunning.value || !_relayEnabled.value || _relayUrl.value.isBlank()) return
        if (relayReconnectJob?.isActive == true) return
        relayReconnectJob = scope.launch(Dispatchers.IO) {
            delay(RELAY_RECONNECT_DELAY_MS)
            connectRelayIfNeeded()
        }
    }

    private fun handleRelayEnvelope(raw: String) {
        val type = runCatching {
            json.parseToJsonElement(raw).jsonObject["type"]?.jsonPrimitive?.content
        }.getOrNull() ?: return
        when (type) {
            RELAY_AUTH_CHALLENGE_TYPE -> handleRelayAuthChallenge(raw)
            RELAY_AUTH_ACCEPTED_TYPE -> handleRelayAuthAccepted(raw)
            RELAY_AUTH_REJECTED_TYPE -> {
                relayAuthenticated = false
                relaySocket?.close(1008, "relay authentication rejected")
            }
            RELAY_FRAME_TYPE -> handleRelayFrame(raw)
        }
    }

    private fun handleRelayFrame(raw: String) {
        if (!relayAuthenticated) return
        val envelope = runCatching { json.decodeFromString<RelayFrameEnvelope>(raw) }
            .getOrNull() ?: return
        if (envelope.type != RELAY_FRAME_TYPE) return
        if (envelope.viaNodeId == nodeId) return
        if (envelope.recipientNodeId.isNotBlank() && envelope.recipientNodeId != nodeId) return
        if (isKnownFrame(envelope.frameId)) return
        val payload = runCatching { Base64.decode(envelope.payloadBase64, Base64.NO_WRAP) }
            .getOrNull() ?: return
        if (payload.isEmpty()) return
        onPayloadDecoded(
            payload = payload,
            fromAddress = null,
            sourceTransport = TransportSource.RELAY
        )
    }

    private fun sendRelayAuthHello(socket: WebSocket) {
        val hello = RelayAuthHelloEnvelope(
            nodeId = nodeId,
            signingPublicKey = crypto.localSigningPublicKey()
        )
        runCatching { socket.send(json.encodeToString(hello)) }
            .onFailure { updateStatus("relay authentication hello failed") }
    }

    private fun handleRelayAuthChallenge(raw: String) {
        val challenge = runCatching { json.decodeFromString<RelayAuthChallengeEnvelope>(raw) }
            .getOrNull() ?: return
        if (challenge.sessionId.isBlank() || challenge.challengeBase64.isBlank()) return
        val response = RelayAuthResponseEnvelope(
            sessionId = challenge.sessionId,
            nodeId = nodeId,
            signingPublicKey = crypto.localSigningPublicKey(),
            signatureBase64 = crypto.signRelayChallenge(
                sessionId = challenge.sessionId,
                challengeBase64 = challenge.challengeBase64
            )
        )
        relaySocket?.let { socket ->
            runCatching { socket.send(json.encodeToString(response)) }
                .onFailure { updateStatus("relay authentication response failed") }
        }
    }

    private fun handleRelayAuthAccepted(raw: String) {
        val accepted = runCatching { json.decodeFromString<RelayAuthAcceptedEnvelope>(raw) }
            .getOrNull() ?: return
        if (accepted.nodeId != nodeId) return
        relayAuthenticated = true
        refreshModeStatus("relay authenticated")
        flushRelayOutboxToRelay()
    }

    private fun publishFrameToRelay(frameId: String, payload: ByteArray): Boolean {
        if (OFFLINE_ONLY_MODE) return false
        if (!_isRunning.value) return false
        if (!_relayEnabled.value) return false
        if (_relayUrl.value.isBlank()) return false
        val socket = relaySocket ?: run {
            connectRelayIfNeeded()
            // The frame is already persisted in relayOutbox. Treat this as
            // accepted so it is not duplicated into a second pending queue.
            return true
        }
        if (!relayAuthenticated) return true
        val envelope = RelayFrameEnvelope(
            frameId = frameId,
            payloadBase64 = Base64.encodeToString(payload, Base64.NO_WRAP),
            viaNodeId = nodeId,
            recipientNodeId = relayRecipientNodeId(payload),
            sentAtMs = System.currentTimeMillis()
        )
        val sent = runCatching {
            socket.send(json.encodeToString(envelope))
        }.getOrDefault(false)
        if (!sent) {
            if (relaySocket === socket) {
                relaySocket = null
            }
            _relayConnected.value = false
            scheduleRelayReconnect()
        }
        return sent
    }

    private fun flushRelayOutboxToRelay() {
        if (!_relayConnected.value || !relayAuthenticated || relaySocket == null) return
        if (hasReadyBleTransport() || !isInternetAvailable()) return
        val frames = synchronized(lock) {
            relayOutbox.values
                .map { frame -> frame.copy() }
        }
        frames.forEach { frame ->
            publishFrameToRelay(frame.frameId, frame.payload)
        }
    }

    private fun relayRecipientNodeId(payload: ByteArray): String {
        return runCatching {
            val element = json.parseToJsonElement(payload.toString(Charsets.UTF_8))
            element.jsonObject["targetNodeId"]?.jsonPrimitive?.content
                ?.trim()
                ?.takeIf { it.isNotBlank() && it.length <= MAX_NODE_ID_LENGTH }
                .orEmpty()
        }.getOrDefault("")
    }

    private fun normalizeRelayUrl(input: String): String {
        val raw = input.trim()
        if (raw.isBlank()) return ""
        val lowered = raw.lowercase()
        val candidate = if (lowered.startsWith("ws://") || lowered.startsWith("wss://")) {
            raw
        } else {
            "ws://$raw"
        }
        val parsed = runCatching { Uri.parse(candidate) }.getOrNull() ?: return ""
        val scheme = parsed.scheme?.lowercase(Locale.US) ?: return ""
        val host = parsed.host?.lowercase(Locale.US) ?: return ""
        if (parsed.encodedUserInfo != null) return ""
        return when {
            scheme == "wss" -> candidate
            scheme == "ws" && isLocalRelayHost(host) -> candidate
            else -> ""
        }
    }

    private fun isLocalRelayHost(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host.endsWith(".local")) return true
        val octets = host.split('.')
        if (octets.size != 4 || octets.any { it.toIntOrNull() == null }) return false
        val values = octets.map { it.toInt() }
        if (values.any { it !in 0..255 }) return false
        return values[0] == 10 ||
            (values[0] == 172 && values[1] in 16..31) ||
            (values[0] == 192 && values[1] == 168) ||
            (values[0] == 127)
    }

    private fun updateStatus(value: String) {
        _status.value = value
    }

    private fun refreshModeStatus(suffix: String? = null) {
        if (!_isRunning.value) return

        val bleMode = when {
            scanningEnabled && advertisingActive -> "scan + advertise"
            scanningEnabled && !advertisingActive -> "scan-only"
            !scanningEnabled && advertisingActive -> "advertise-only"
            else -> "transport degraded"
        }
        val wifiHint = if (!BLE_ONLY_MODE && wifiLanActive) ", wifi-lan" else ""
        val wifiDirectHint = if (!BLE_ONLY_MODE && wifiP2pConnected) ", wifi-direct" else ""

        val minimalHint = if (advertisingUsesMinimalPayload && advertisingActive) {
            ", reduced-advertise"
        } else {
            ""
        }

        val relayConfigured = relayConfigured()
        val relayHint = when {
            !relayConfigured -> ""
            _relayConnected.value -> ", relay-connected"
            else -> ", relay-connecting"
        }
        val mode = if (BLE_ONLY_MODE) {
            bleMode
        } else if (!scanningEnabled && !advertisingActive && wifiLanActive) {
            "wifi-lan-only"
        } else if (!scanningEnabled && !advertisingActive && wifiP2pConnected && !wifiLanActive) {
            "wifi-direct-only"
        } else if (relayConfigured && !scanningEnabled && !advertisingActive && !wifiLanActive) {
            "relay-only"
        } else {
            bleMode
        }

        val courierHint = if (BLE_ONLY_MODE) ", courier-store" else ""
        val routeHint = if (relayConfigured) ", BLE-priority, internet-fallback" else ", BLE-priority"
        val base = "Mesh online ($mode$minimalHint$wifiHint$wifiDirectHint$relayHint$routeHint$courierHint, E2E enabled)"
        updateStatus(if (suffix.isNullOrBlank()) base else "$base: $suffix")
    }

    private fun relayConfigured(): Boolean {
        return !OFFLINE_ONLY_MODE &&
            !BLE_ONLY_MODE &&
            _relayEnabled.value &&
            _relayUrl.value.isNotBlank()
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            if (address == adapter?.address) return

            val advertisedNodeId = result.scanRecord
                ?.getServiceData(ParcelUuid(SERVICE_UUID))
                ?.toString(Charsets.UTF_8)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: result.scanRecord
                    ?.getManufacturerSpecificData(MESH_MANUFACTURER_ID)
                    ?.takeIf { it.size >= COMPACT_NODE_ID_BYTES }
                    ?.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
            if (advertisedNodeId == nodeId) return

            upsertPeer(
                address = address,
                nodeId = advertisedNodeId,
                alias = advertisedNodeId?.let { "Node-${it.take(4)}" } ?: address.takeLast(5)
            )
            if (advertisedNodeId != null) {
                synchronized(lock) {
                    addressToNodeId[address] = advertisedNodeId
                }
            }
            connectToPeer(device, advertisedNodeId)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanningEnabled = false
            if (advertisingActive) {
                refreshModeStatus("scan failed ($errorCode)")
            } else {
                updateStatus("BLE scan failed ($errorCode)")
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertisingActive = true
            refreshModeStatus()
        }

        @SuppressLint("MissingPermission")
        override fun onStartFailure(errorCode: Int) {
            advertisingActive = false
            if (!advertiseRetryDone) {
                advertiseRetryDone = true
                advertisingUsesMinimalPayload = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                ) {
                    updateStatus("BLE advertise permission missing")
                    return
                }
                val localAdvertiser = advertiser()
                if (localAdvertiser != null) {
                    val settings = AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                        .setConnectable(true)
                        .build()
                    val fallbackStarted = runCatching {
                        localAdvertiser.stopAdvertising(this)
                        localAdvertiser.startAdvertising(
                            settings,
                            buildAdvertiseData(includeNodeId = false),
                            this
                        )
                    }.isSuccess
                    if (fallbackStarted) {
                        if (scanningEnabled) {
                            refreshModeStatus("advertise fallback active")
                        }
                        return
                    }
                }
            }

            if (scanningEnabled) {
                refreshModeStatus("advertise unsupported ($errorCode)")
            } else {
                updateStatus("BLE advertise failed ($errorCode)")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address ?: return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    clearConnectionRetry(address)
                    synchronized(lock) {
                        connectingAddresses.remove(address)
                        clientGatts[address] = gatt
                        clientReadyAddresses.remove(address)
                        serviceDiscoveryStartedAddresses.remove(address)
                        updatePeersUnsafe()
                    }
                    upsertPeer(address = address, connected = true)
                    val mtuRequested = runCatching { gatt.requestMtu(247) }.getOrDefault(false)
                    if (!mtuRequested) {
                        discoverServicesOnce(gatt)
                    }
                    scope.launch(Dispatchers.IO) {
                        delay(1_500L)
                        discoverServicesOnce(gatt)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val stableNodeId = synchronized(lock) { addressToNodeId[address] }
                    scheduleConnectionRetry(address, status, stableNodeId)
                    synchronized(lock) {
                        connectingAddresses.remove(address)
                        stableNodeId?.let { nodeId ->
                            connectingNodeIds.remove(nodeId)
                            if (activeAddressByNodeId[nodeId] == address) {
                                activeAddressByNodeId.remove(nodeId)
                            }
                        }
                        clientGatts.remove(address)
                        clientReadyAddresses.remove(address)
                        serviceDiscoveryStartedAddresses.remove(address)
                        pendingWriteByAddress.remove(address)?.complete(false)
                        negotiatedMtuByAddress.remove(address)
                        updatePeersUnsafe()
                    }
                    removePeerConnection(address)
                    runCatching { gatt.close() }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == MESSAGE_CHARACTERISTIC_UUID) {
                completePendingWrite(gatt.device.address ?: return, status)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                rememberNegotiatedMtu(gatt.device.address ?: return, mtu)
            }
            discoverServicesOnce(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val started = enableIncomingNotify(gatt)
                if (!started) {
                    val address = gatt.device.address ?: return
                    scheduleClientTransportReady(address)
                }
            } else {
                val address = gatt.device.address ?: return
                synchronized(lock) {
                    serviceDiscoveryStartedAddresses.remove(address)
                }
                updateStatus("Service discovery failed for ${gatt.device.address}")
                scope.launch(Dispatchers.IO) {
                    delay(1_500L)
                    discoverServicesOnce(gatt)
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CLIENT_CONFIG_DESCRIPTOR_UUID &&
                descriptor.characteristic.uuid == MESSAGE_NOTIFY_CHARACTERISTIC_UUID &&
                status == BluetoothGatt.GATT_SUCCESS
            ) {
                val address = gatt.device.address ?: return
                scheduleClientTransportReady(address)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == MESSAGE_NOTIFY_CHARACTERISTIC_UUID) {
                handleIncomingPacket(gatt.device.address ?: "unknown", value)
            }
        }

        @Deprecated("Deprecated in API 33; kept for pre-Tiramisu devices.")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                characteristic.uuid == MESSAGE_NOTIFY_CHARACTERISTIC_UUID
            ) {
                val value = characteristic.value ?: return
                handleIncomingPacket(gatt.device.address ?: "unknown", value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                synchronized(lock) {
                    serverConnectedAddresses.add(address)
                    serverConnectedDevices[address] = device
                    updatePeersUnsafe()
                }
                upsertPeer(address = address, connected = true)
                flushRelayOutbox()
                flushPendingTransfers()
                flushPendingPayloads()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    synchronized(lock) {
                        serverConnectedAddresses.remove(address)
                        serverConnectedDevices.remove(address)
                        notifyEnabledAddresses.remove(address)
                        pendingNotificationByAddress.remove(address)?.complete(false)
                        notificationCallbackUnavailableAddresses.remove(address)
                        negotiatedMtuByAddress.remove(address)
                        updatePeersUnsafe()
                    }
                removePeerConnection(address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            rememberNegotiatedMtu(device.address ?: return, mtu)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val address = device.address ?: return
            val pending = synchronized(lock) {
                pendingNotificationByAddress.remove(address)
            } ?: return
            pending.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == MESSAGE_CHARACTERISTIC_UUID) {
                handleIncomingPacket(device.address ?: "unknown", value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val address = device.address ?: "unknown"
            if (descriptor.uuid == CLIENT_CONFIG_DESCRIPTOR_UUID &&
                descriptor.characteristic.uuid == MESSAGE_NOTIFY_CHARACTERISTIC_UUID
            ) {
                val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                synchronized(lock) {
                    if (enable) {
                        notifyEnabledAddresses.add(address)
                    } else {
                        notifyEnabledAddresses.remove(address)
                    }
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    private enum class TransportSource {
        BLE,
        WIFI_LAN,
        RELAY
    }

    private data class RelayFrameEnvelope(
        val type: String = RELAY_FRAME_TYPE,
        val frameId: String,
        val payloadBase64: String,
        val viaNodeId: String,
        val recipientNodeId: String = "",
        val sentAtMs: Long
    )

    private data class RelayAuthHelloEnvelope(
        val type: String = RELAY_AUTH_HELLO_TYPE,
        val nodeId: String,
        val signingPublicKey: String
    )

    private data class RelayAuthChallengeEnvelope(
        val type: String = RELAY_AUTH_CHALLENGE_TYPE,
        val sessionId: String,
        val challengeBase64: String,
        val expiresAtMs: Long
    )

    private data class RelayAuthResponseEnvelope(
        val type: String = RELAY_AUTH_RESPONSE_TYPE,
        val sessionId: String,
        val nodeId: String,
        val signingPublicKey: String,
        val signatureBase64: String
    )

    private data class RelayAuthAcceptedEnvelope(
        val type: String = RELAY_AUTH_ACCEPTED_TYPE,
        val nodeId: String,
        val expiresAtMs: Long
    )

    private data class FrameAssembler(
        val total: Int,
        var updatedAtMs: Long,
        val parts: MutableMap<Int, ByteArray> = mutableMapOf()
    )

    private data class RelayFrame(
        val frameId: String,
        val payload: ByteArray,
        val createdAtMs: Long,
        var lastSentAtMs: Long
    )

    private data class WifiDirectPeerSnapshot(
        val deviceAddress: String,
        val deviceName: String,
        val status: Int,
        val lastSeenMs: Long
    )

    private data class OutgoingAttachment(
        val transferId: String,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sha256: String,
        val compressed: Boolean,
        val sentAtMs: Long,
        val originalBytes: ByteArray,
        val transferBytes: ByteArray
    )

    private data class OutgoingFileTransfer(
        val transferId: String,
        val chatId: String,
        val chatType: String,
        val chatTitle: String?,
        val memberNodeIds: List<String>,
        val collectiveOwnerNodeId: String?,
        val collectiveAdminNodeIds: List<String>,
        val collectiveModeratorNodeIds: List<String>,
        val collectiveBroadcastOnly: Boolean,
        val collectiveAllowMemberReactions: Boolean,
        val collectiveAllowMemberEditOwnMessages: Boolean,
        val collectiveAllowMemberDeleteOwnMessages: Boolean,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sha256: String,
        val caption: String,
        val mediaAlbumId: String?,
        val mediaAlbumIndex: Int,
        val mediaAlbumCount: Int,
        val compressed: Boolean,
        val sentAtMs: Long,
        val chunkCount: Int,
        val chunksBase64: List<String>,
        val recipients: MutableMap<String, OutgoingTransferRecipientState>,
        val createdAtMs: Long,
        var updatedAtMs: Long
    )

    private data class PendingPayloadDispatch(
        val queueId: String,
        val messageId: String?,
        val plaintext: String,
        val targetNodeIds: List<String>,
        val createdAtMs: Long,
        val lastAttemptAtMs: Long
    )

    private data class FileDispatchResult(
        val sent: Boolean,
        val queued: Boolean
    ) {
        val accepted: Boolean
            get() = sent || queued
    }

    private data class PayloadDispatchResult(
        val sentCount: Int,
        val queuedCount: Int
    ) {
        val dispatched: Boolean
            get() = sentCount > 0 || queuedCount > 0
    }

    private data class OutgoingTransferRecipientState(
        val nodeId: String,
        val alias: String,
        val chunkCount: Int,
        val ackedChunks: MutableSet<Int> = mutableSetOf(),
        var lastAckAtMs: Long = 0L,
        var lastSentAtMs: Long = 0L,
        var nextChunkCursor: Int = 0,
        var dispatchInFlight: Boolean = false
    )

    private data class FileTransferAssembler(
        val transferId: String,
        val originNodeId: String,
        val senderAlias: String,
        val conversationId: String,
        val conversationType: ConversationType,
        val conversationTitle: String?,
        val memberNodeIds: List<String>,
        val collectiveOwnerNodeId: String?,
        val collectiveAdminNodeIds: List<String>,
        val collectiveModeratorNodeIds: List<String>,
        val collectiveBroadcastOnly: Boolean?,
        val collectiveAllowMemberReactions: Boolean?,
        val collectiveAllowMemberEditOwnMessages: Boolean?,
        val collectiveAllowMemberDeleteOwnMessages: Boolean?,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sha256: String,
        val caption: String,
        val mediaAlbumId: String?,
        val mediaAlbumIndex: Int,
        val mediaAlbumCount: Int,
        val compressed: Boolean,
        val chunkCount: Int,
        val sentAtMs: Long,
        val createdAtMs: Long,
        var updatedAtMs: Long,
        var lastAckSentAtMs: Long = 0L,
        var lastAckedChunkCount: Int = 0,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf()
    )

    private data class CompletedTransfer(
        val assembler: FileTransferAssembler,
        val transferBytes: ByteArray
    )

    private data class IncomingFileChunkResult(
        val completedTransfer: CompletedTransfer?,
        val shouldAck: Boolean,
        val ackIndexes: List<Int>,
        val ackComplete: Boolean
    )

    private data class IncomingTransferRetryRequest(
        val senderIdentity: PeerIdentity,
        val transferId: String,
        val chunkCount: Int,
        val receivedIndexes: List<Int>,
        val conversationMeta: ConversationMeta
    )

    private data class ConversationMeta(
        val conversationId: String,
        val conversationType: ConversationType,
        val conversationTitle: String?,
        val memberNodeIds: List<String>,
        val collectiveOwnerNodeId: String?,
        val collectiveAdminNodeIds: List<String>,
        val collectiveModeratorNodeIds: List<String>,
        val collectiveBroadcastOnly: Boolean?,
        val collectiveAllowMemberReactions: Boolean?,
        val collectiveAllowMemberEditOwnMessages: Boolean?,
        val collectiveAllowMemberDeleteOwnMessages: Boolean?
    )

    private data class DecodedIncomingPayload(
        val messageId: String,
        val text: String,
        val conversationId: String,
        val conversationType: ConversationType,
        val conversationTitle: String?,
        val memberNodeIds: List<String>,
        val collectiveOwnerNodeId: String?,
        val collectiveAdminNodeIds: List<String>,
        val collectiveModeratorNodeIds: List<String>,
        val collectiveBroadcastOnly: Boolean?,
        val collectiveAllowMemberReactions: Boolean?,
        val collectiveAllowMemberEditOwnMessages: Boolean?,
        val collectiveAllowMemberDeleteOwnMessages: Boolean?,
        val replyToMessageId: String?,
        val replyToPreview: String?,
        val forwardedFromAlias: String?,
        val forwardedFromMessageId: String?
    )

    companion object {
        private const val BLE_TAG = "MeshBle"
        private const val MESH_MANUFACTURER_ID = 0xFFFE
        private const val COMPACT_NODE_ID_BYTES = 4
        private const val MAX_CONNECTION_RETRY_ATTEMPTS = 6
        private const val CONNECTION_RETRY_BASE_DELAY_MS = 1_500L
        private const val MAX_CONNECTION_RETRY_DELAY_MS = 60_000L
        private const val BLE_FRAME_RETRY_DELAY_MS = 900L
        // Hybrid mode is app-scoped: BLE is preferred, relay is a fallback.
        // The app never changes Wi-Fi or mobile-data settings for other apps.
        private const val BLE_ONLY_MODE = false
        private const val OFFLINE_ONLY_MODE = false
        private const val PREF_NODE = "mesh_node_prefs"
        private const val KEY_NODE_ID = "node_id"
        private const val PREF_NETWORK = "mesh_network_prefs"
        private const val KEY_RELAY_ENABLED = "relay_enabled"
        private const val KEY_RELAY_URL = "relay_url"
        private const val DEFAULT_RELAY_URL = ""
        private const val SAVED_MESSAGES_TITLE = "Saved Messages"
        private const val MAX_SAVED_TAGS_PER_MESSAGE = 8
        private const val MAX_SAVED_TAG_LENGTH = 24
        private const val MAX_SCHEDULED_MESSAGES = 200
        private const val MIN_SCHEDULE_DELAY_MS = 5_000L
        private const val MAX_SCHEDULE_AHEAD_MS = 365L * 24 * 60 * 60 * 1000
        private const val SCHEDULED_MESSAGE_EXPIRY_MS = 7L * 24 * 60 * 60 * 1000
        private const val SCHEDULED_RETRY_GAP_MS = 30_000L
        private const val SCHEDULED_DISPATCH_BATCH = 12
        private const val RELAY_FRAME_TYPE = "MESH_RELAY_FRAME_V1"
        private const val RELAY_AUTH_HELLO_TYPE = "MESH_RELAY_AUTH_HELLO_V1"
        private const val RELAY_AUTH_CHALLENGE_TYPE = "MESH_RELAY_AUTH_CHALLENGE_V1"
        private const val RELAY_AUTH_RESPONSE_TYPE = "MESH_RELAY_AUTH_RESPONSE_V1"
        private const val RELAY_AUTH_ACCEPTED_TYPE = "MESH_RELAY_AUTH_ACCEPTED_V1"
        private const val RELAY_AUTH_REJECTED_TYPE = "MESH_RELAY_AUTH_REJECTED_V1"
        private const val MAX_NODE_ID_LENGTH = 96
        private const val WIFI_MULTICAST_GROUP = "239.192.46.48"
        private const val WIFI_BROADCAST_ADDRESS = "255.255.255.255"
        private const val WIFI_LAN_PORT = 35468
        private const val WIFI_MAX_PACKET_BYTES = 60 * 1024
        private const val WIFI_SOCKET_TIMEOUT_MS = 1_500
        private const val WIFI_BROADCAST_TARGETS_REFRESH_MS = 30_000L
        private const val MAX_WIFI_DIRECTED_TARGETS = 64
        private const val WIFI_DIRECTED_TARGET_TTL_MS = 20 * 60 * 1000L
        private const val WIFI_P2P_DISCOVERY_INTERVAL_MS = 18_000L
        private const val WIFI_P2P_DISCOVERY_MIN_GAP_MS = 7_000L
        private const val WIFI_P2P_CONNECT_MIN_GAP_MS = 12_000L

        private const val MAX_MESSAGES = 900
        private const val MAX_SEEN_FRAMES = 2048
        private const val SEEN_FRAME_TTL_MS = 10 * 60 * 1000L
        private const val OVERLAY_PEER_ONLINE_TTL_MS = 45_000L
        private const val MAX_PERSISTED_IDENTITIES = 800
        private const val MAX_COMPLETED_TRANSFERS = 1200
        private const val COMPLETED_TRANSFER_TTL_MS = 20 * 60 * 1000L
        private const val MAX_OUTGOING_TRANSFERS = 48
        private const val MAX_INCOMING_TRANSFERS = 24
        private const val OUTGOING_TRANSFER_TTL_MS = 40 * 60 * 1000L
        private const val MAX_PENDING_PAYLOADS = 1200
        private const val PENDING_PAYLOAD_TTL_MS = 48 * 60 * 60 * 1000L
        private const val PENDING_PAYLOAD_RESEND_GAP_MS = 6_000L

        private const val HELLO_MAX_HOPS = 8
        private const val MESSAGE_MAX_HOPS = 24
        private const val PRESENCE_INTERVAL_MS = 12_000L
        private const val PACKET_GAP_MS = 18L
        private const val FRAME_RETRY_COUNT = 3
        private const val FRAME_RETRY_GAP_MS = 55L
        private const val BLE_WRITE_CALLBACK_TIMEOUT_MS = 350L
        private const val BLE_NOTIFY_CALLBACK_TIMEOUT_MS = 350L
        private const val BLE_NOTIFY_FALLBACK_GAP_MS = 35L
        private const val CLIENT_READY_DELAY_MS = 220L
        private const val RELAY_RECONNECT_DELAY_MS = 4_000L
        private const val TRANSFER_RESEND_GAP_MS = 1_200L
        private const val OUTGOING_TRANSFER_PERSIST_GAP_MS = 4_000L
        private const val INCOMING_TRANSFER_PERSIST_GAP_MS = 4_000L
        private const val FILE_ACK_BATCH_SIZE = 8
        private const val FILE_ACK_INTERVAL_MS = 500L
        private const val INITIAL_TRANSFER_WINDOW = 12
        private const val RESEND_WINDOW_CHUNKS = 12

        private const val ASSEMBLER_TTL_MS = 35_000L
        private const val FILE_ASSEMBLER_TTL_MS = 12 * 60 * 1000L
        private const val MAX_RELAY_OUTBOX_FRAMES = 8000
        private const val RELAY_OUTBOX_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        private const val RELAY_OUTBOX_RESEND_GAP_MS = 8_000L
        private const val RELAY_OUTBOX_FLUSH_BATCH = 4
        private const val MAX_RELAY_FRAME_PAYLOAD_BYTES = 256 * 1024
        private const val ATT_HEADER_BYTES = 3
        private const val CHUNK_HEADER_SIZE = 8
        private const val DEFAULT_BLE_CHUNK_PAYLOAD_SIZE = 12
        private const val MAX_BLE_CHUNK_PAYLOAD_SIZE = 180
        private const val FILE_CHUNK_SIZE = 320
        private const val MAX_FILE_BYTES = 4 * 1024 * 1024
        private const val MAX_TRANSFER_ID_LENGTH = 96
        private const val MAX_FILE_NAME_LENGTH = 128
        private const val MAX_MIME_TYPE_LENGTH = 128
        private const val MAX_HASH_LENGTH = 64
        private const val MAX_FILE_CAPTION_LENGTH = 1_024
        private const val MAX_INCOMING_FILE_CHUNKS =
            (MAX_FILE_BYTES + FILE_CHUNK_SIZE - 1) / FILE_CHUNK_SIZE
        private const val MAX_TRANSFER_BYTES = MAX_FILE_BYTES
        private const val MAX_ZERO_READS = 3
        private const val MAGIC_BYTE: Byte = 0x4D

        private val SERVICE_UUID: UUID = UUID.fromString("88a7588a-8869-4453-91f8-f53db3564f06")
        private val MESSAGE_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("4db27553-b5d3-4626-95f6-b2e67a4b3597")
        private val MESSAGE_NOTIFY_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("0ecae0e1-53f6-4df0-b0e8-01e1478196ab")
        private val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
