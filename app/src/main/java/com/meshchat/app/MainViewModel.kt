package com.meshchat.app

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.mesh.ChatMessage
import com.meshchat.app.mesh.ChatContentType
import com.meshchat.app.mesh.CollectiveInvitePayload
import com.meshchat.app.mesh.ConversationLocalState
import com.meshchat.app.mesh.ConversationSummary
import com.meshchat.app.mesh.ConversationType
import com.meshchat.app.mesh.CollectiveRole
import com.meshchat.app.mesh.IncomingFileTransferProgress
import com.meshchat.app.mesh.MeshForegroundService
import com.meshchat.app.mesh.MeshContact
import com.meshchat.app.mesh.MeshGroup
import com.meshchat.app.mesh.MeshMessagePayload
import com.meshchat.app.mesh.MeshRuntime
import com.meshchat.app.mesh.MeshTab
import com.meshchat.app.mesh.MeshUiState
import com.meshchat.app.mesh.Peer
import com.meshchat.app.mesh.PeerIdentity
import com.meshchat.app.mesh.OutgoingFileTransferProgress
import com.meshchat.app.mesh.SAVED_MESSAGES_CONVERSATION_ID
import com.meshchat.app.mesh.SecureLocalStore
import com.meshchat.app.mesh.ScheduledMessageRecord
import com.meshchat.app.mesh.directConversationId
import com.meshchat.app.mesh.isSavedMessagesConversation
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val meshManager = MeshRuntime.manager(appContext)
    private val localStore = SecureLocalStore(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    private val _groups = MutableStateFlow(localStore.loadGroups())
    private val _conversationStates = MutableStateFlow(
        localStore.loadConversationStates()
            .filter { it.conversationId.isNotBlank() }
            .associateBy { it.conversationId }
    )
    private val _selectedConversationId = MutableStateFlow<String?>(null)
    private val _selectedTab = MutableStateFlow(MeshTab.MAP)
    private val _isConversationOpen = MutableStateFlow(false)
    private val seenIncomingMessageIds = linkedSetOf<String>().apply {
        addAll(
            meshManager.messages.value
                .filter { !it.isLocal && it.id.isNotBlank() }
                .map { it.id }
                .takeLast(MAX_TRACKED_INCOMING_MESSAGES)
        )
    }

    val groups: StateFlow<List<MeshGroup>> = _groups.asStateFlow()

    init {
        viewModelScope.launch {
            meshManager.messages.collect { messages ->
                discoverGroupsFromMessages(messages)
                reconcileConversationStates(messages)
                applyUnreadCounters(messages)
            }
        }
    }

    private val networkState = combine(
        combine(
            listOf(
                meshManager.nodeAlias,
                meshManager.nodeFingerprint,
                meshManager.nodeAvatarData,
                meshManager.isRunning,
                meshManager.status,
                meshManager.peers
            )
        ) { values ->
            NetworkState(
                alias = values[0] as String,
                fingerprint = values[1] as String,
                avatarData = values[2] as String,
                isRunning = values[3] as Boolean,
                status = values[4] as String,
                peers = values[5] as List<Peer>
            )
        },
        combine(
            meshManager.wifiLanActiveState,
            meshManager.relayEnabled,
            meshManager.relayUrl,
            meshManager.relayConnected
        ) { wifiLanActive, relayEnabled, relayUrl, relayConnected ->
            RelayState(
                wifiLanActive = wifiLanActive,
                enabled = relayEnabled,
                url = relayUrl,
                connected = relayConnected
            )
        },
        meshManager.knownIdentities,
        meshManager.messages,
        combine(
            meshManager.scheduledMessages,
            meshManager.outgoingFileTransfers,
            meshManager.incomingFileTransfers
        ) { scheduledMessages, outgoingFileTransfers, incomingFileTransfers ->
            TransferQueueState(
                scheduledMessages = scheduledMessages,
                outgoingFileTransfers = outgoingFileTransfers,
                incomingFileTransfers = incomingFileTransfers
            )
        }
    ) { state, relayState, knownIdentities, messages, transferQueue ->
        state.copy(
            wifiLanActive = relayState.wifiLanActive,
            relayEnabled = relayState.enabled,
            relayUrl = relayState.url,
            relayConnected = relayState.connected,
            knownIdentities = knownIdentities,
            messages = messages,
            scheduledMessages = transferQueue.scheduledMessages,
            outgoingFileTransfers = transferQueue.outgoingFileTransfers,
            incomingFileTransfers = transferQueue.incomingFileTransfers
        )
    }

    val uiState: StateFlow<MeshUiState> = combine(
        combine(networkState, _groups, _conversationStates) { state, groups, conversationStates ->
            UiCombineState(
                network = state,
                groups = groups,
                conversationStates = conversationStates
            )
        },
        _selectedConversationId,
        _selectedTab,
        _isConversationOpen
    ) { combined, selectedConversationId, selectedTab, isConversationOpen ->
        val networkState = combined.network
        val groups = combined.groups
        val conversationStates = combined.conversationStates
        val alias = networkState.alias
        val fingerprint = networkState.fingerprint
        val running = networkState.isRunning
        val status = networkState.status
        val peers = networkState.peers
        val wifiLanActive = networkState.wifiLanActive
        val relayEnabled = networkState.relayEnabled
        val relayUrl = networkState.relayUrl
        val relayConnected = networkState.relayConnected
        val knownIdentities = networkState.knownIdentities
        val messages = networkState.messages
        val scheduledMessages = networkState.scheduledMessages
        val outgoingFileTransfers = networkState.outgoingFileTransfers
        val incomingFileTransfers = networkState.incomingFileTransfers

        val contacts = buildContacts(peers, knownIdentities)
        val conversations = buildConversations(
            nodeId = meshManager.nodeId,
            contacts = contacts,
            groups = groups,
            messages = messages,
            conversationStates = conversationStates
        )
        val resolvedConversationId = selectedConversationId
            ?.takeIf { selected -> conversations.any { it.id == selected } }
            ?: conversations.firstOrNull()?.id
        val activeConversation = conversations.firstOrNull { it.id == resolvedConversationId }
        val activePermissions = activeConversation?.let { conversation ->
            resolveConversationPermissions(conversation)
        } ?: ConversationPermissions(
            role = CollectiveRole.OWNER,
            canPost = true,
            canModerate = false,
            canPin = true,
            canReact = true,
            canEditOwn = true,
            canDeleteOwn = true,
            canManageRoles = false,
            isAdmin = false,
            isModerator = false
        )
        val activeMessages = if (resolvedConversationId == null) {
            emptyList()
        } else {
            messages
                .filter { it.conversationId == resolvedConversationId }
                .sortedBy { it.createdAtMs }
        }
        val activeDraft = resolvedConversationId
            ?.let { conversationStates[it]?.draftText.orEmpty() }
            .orEmpty()
        val activeScheduledMessages = resolvedConversationId
            ?.let { conversationId ->
                scheduledMessages
                    .filter { record -> record.conversationId == conversationId }
                    .sortedBy { record -> record.scheduledAtMs }
            }
            .orEmpty()
        val activeFileTransfers = resolvedConversationId
            ?.let { conversationId ->
                outgoingFileTransfers
                    .filter { transfer -> transfer.conversationId == conversationId }
                    .sortedByDescending { transfer -> transfer.updatedAtMs }
            }
            .orEmpty()
        val activeIncomingFileTransfers = resolvedConversationId
            ?.let { conversationId ->
                incomingFileTransfers
                    .filter { transfer -> transfer.conversationId == conversationId }
                    .sortedByDescending { transfer -> transfer.updatedAtMs }
            }
            .orEmpty()

        MeshUiState(
            nodeId = meshManager.nodeId,
            nodeAlias = alias,
            nodeAvatarData = networkState.avatarData,
            nodeFingerprint = fingerprint,
            isRunning = running,
            status = status,
            peers = peers,
            contacts = contacts,
            messages = messages,
            conversations = conversations,
            groups = groups,
            activeConversationId = resolvedConversationId,
            activeConversationTitle = activeConversation?.title ?: "Mesh Chat",
            activeConversationSubtitle = activeConversation?.subtitle ?: "Select a chat",
            activeConversationType = activeConversation?.type ?: ConversationType.DIRECT,
            activeConversationRole = activePermissions.role,
            activeConversationCanPost = activePermissions.canPost,
            activeConversationCanModerate = activePermissions.canModerate,
            activeConversationCanPin = activePermissions.canPin,
            activeConversationCanReact = activePermissions.canReact,
            activeConversationCanEditOwn = activePermissions.canEditOwn,
            activeConversationCanDeleteOwn = activePermissions.canDeleteOwn,
            activeConversationCanManageRoles = activePermissions.canManageRoles,
            activeConversationIsAdmin = activePermissions.isAdmin,
            activeConversationIsModerator = activePermissions.isModerator,
            activeMessages = activeMessages,
            activeScheduledMessages = activeScheduledMessages,
            activeFileTransfers = activeFileTransfers,
            activeIncomingFileTransfers = activeIncomingFileTransfers,
            activeDraft = activeDraft,
            wifiLanActive = wifiLanActive,
            relayEnabled = relayEnabled,
            relayUrl = relayUrl,
            relayConnected = relayConnected,
            selectedTab = selectedTab,
            isConversationOpen = isConversationOpen && resolvedConversationId != null
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MeshUiState(nodeId = meshManager.nodeId)
    )

    fun startMesh() {
        meshManager.start()
        MeshForegroundService.start(appContext)
    }

    fun stopMesh() {
        meshManager.stop()
        MeshForegroundService.stop(appContext)
    }

    fun updateAlias(alias: String) = meshManager.updateAlias(alias)

    fun updateAvatarData(avatarData: String) = meshManager.updateAvatarData(avatarData)

    fun updateRelaySettings(enabled: Boolean, relayUrl: String) {
        meshManager.updateRelaySettings(enabled = enabled, relayUrl = relayUrl)
    }

    fun selectTab(tab: MeshTab) {
        _selectedTab.value = tab
        if (tab != MeshTab.CHATS) {
            _isConversationOpen.value = false
        }
    }

    fun openConversation(conversationId: String) {
        _selectedConversationId.value = conversationId
        _selectedTab.value = MeshTab.CHATS
        _isConversationOpen.value = true
        markConversationRead(conversationId)
    }

    fun closeConversation() {
        _isConversationOpen.value = false
    }

    fun openDirectChat(peerNodeId: String) {
        val conversationId = directConversationId(meshManager.nodeId, peerNodeId)
        openConversation(conversationId)
    }

    fun openSavedMessages() {
        openConversation(SAVED_MESSAGES_CONVERSATION_ID)
    }

    fun updateDraftForActiveConversation(draftText: String) {
        val conversationId = uiState.value.activeConversationId ?: return
        val normalized = draftText.take(MAX_DRAFT_LENGTH)
        upsertConversationState(conversationId) { current ->
            if (current.draftText == normalized) {
                current
            } else {
                current.copy(
                    draftText = normalized,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
        }
    }

    fun pinConversation(conversationId: String, pinned: Boolean) {
        val id = conversationId.trim()
        if (id.isBlank()) return
        upsertConversationState(id) { current ->
            if (current.isPinned == pinned) {
                current
            } else {
                current.copy(
                    isPinned = pinned,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
        }
    }

    fun muteConversation(conversationId: String, muted: Boolean) {
        val id = conversationId.trim()
        if (id.isBlank()) return
        upsertConversationState(id) { current ->
            if (current.isMuted == muted) {
                current
            } else {
                current.copy(
                    isMuted = muted,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
        }
    }

    fun archiveConversation(conversationId: String, archived: Boolean) {
        val id = conversationId.trim()
        if (id.isBlank()) return
        upsertConversationState(id) { current ->
            if (current.isArchived == archived) {
                current
            } else {
                current.copy(
                    isArchived = archived,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
        }
    }

    fun markConversationRead(conversationId: String) {
        val id = conversationId.trim()
        if (id.isBlank()) return
        upsertConversationState(id) { current ->
            if (current.unreadCount == 0) {
                current
            } else {
                current.copy(
                    unreadCount = 0,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
        }
    }

    fun createGroup(title: String, memberNodeIds: List<String>): Boolean {
        val cleanTitle = title.trim().take(MAX_GROUP_TITLE)
        if (cleanTitle.isBlank()) return false

        val normalizedMembers = (memberNodeIds + meshManager.nodeId)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedMembers.size < 2) return false

        val newGroup = MeshGroup(
            id = "grp-${UUID.randomUUID().toString().replace("-", "").take(12)}",
            title = cleanTitle,
            memberNodeIds = normalizedMembers,
            createdAtMs = System.currentTimeMillis(),
            createdByNodeId = meshManager.nodeId,
            type = ConversationType.GROUP,
            adminNodeIds = listOf(meshManager.nodeId),
            moderatorNodeIds = emptyList(),
            isBroadcastOnly = false,
            allowMemberReactions = true,
            allowMemberEditOwnMessages = true,
            allowMemberDeleteOwnMessages = true
        )

        _groups.update { current ->
            val updated = (listOf(newGroup) + current).distinctBy { it.id }
            localStore.persistGroups(updated)
            updated
        }
        upsertConversationState(newGroup.id) { current ->
            current.copy(updatedAtMs = System.currentTimeMillis())
        }
        meshManager.sendCollectiveUpdate(
            collectiveId = newGroup.id,
            collectiveTitle = newGroup.title,
            conversationType = newGroup.type,
            memberNodeIds = newGroup.memberNodeIds,
            adminNodeIds = newGroup.adminNodeIds,
            moderatorNodeIds = newGroup.moderatorNodeIds,
            isBroadcastOnly = newGroup.isBroadcastOnly,
            allowMemberReactions = newGroup.allowMemberReactions,
            allowMemberEditOwnMessages = newGroup.allowMemberEditOwnMessages,
            allowMemberDeleteOwnMessages = newGroup.allowMemberDeleteOwnMessages,
            noteText = "Group created"
        )
        openConversation(newGroup.id)
        return true
    }

    fun createChannel(title: String, memberNodeIds: List<String>): Boolean {
        val cleanTitle = title.trim().take(MAX_CHANNEL_TITLE)
        if (cleanTitle.isBlank()) return false

        val normalizedMembers = (memberNodeIds + meshManager.nodeId)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedMembers.size < 2) return false

        val newChannel = MeshGroup(
            id = "chn-${UUID.randomUUID().toString().replace("-", "").take(12)}",
            title = cleanTitle,
            memberNodeIds = normalizedMembers,
            createdAtMs = System.currentTimeMillis(),
            createdByNodeId = meshManager.nodeId,
            type = ConversationType.CHANNEL,
            adminNodeIds = listOf(meshManager.nodeId),
            moderatorNodeIds = emptyList(),
            isBroadcastOnly = true,
            allowMemberReactions = true,
            allowMemberEditOwnMessages = false,
            allowMemberDeleteOwnMessages = false
        )

        _groups.update { current ->
            val updated = (listOf(newChannel) + current).distinctBy { it.id }
            localStore.persistGroups(updated)
            updated
        }
        upsertConversationState(newChannel.id) { current ->
            current.copy(updatedAtMs = System.currentTimeMillis())
        }
        meshManager.sendCollectiveUpdate(
            collectiveId = newChannel.id,
            collectiveTitle = newChannel.title,
            conversationType = newChannel.type,
            memberNodeIds = newChannel.memberNodeIds,
            adminNodeIds = newChannel.adminNodeIds,
            moderatorNodeIds = newChannel.moderatorNodeIds,
            isBroadcastOnly = newChannel.isBroadcastOnly,
            allowMemberReactions = newChannel.allowMemberReactions,
            allowMemberEditOwnMessages = newChannel.allowMemberEditOwnMessages,
            allowMemberDeleteOwnMessages = newChannel.allowMemberDeleteOwnMessages,
            noteText = "Channel created"
        )
        openConversation(newChannel.id)
        return true
    }

    fun generateInviteCode(conversationId: String): String? {
        val collective = collectiveByConversationId(conversationId) ?: return null
        if (collective.type == ConversationType.DIRECT) return null
        val admins = collective.adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(collective.createdByNodeId) }
        val payload = CollectiveInvitePayload(
            collectiveId = collective.id,
            title = collective.title,
            type = if (collective.type == ConversationType.CHANNEL) "channel" else "group",
            memberNodeIds = collective.memberNodeIds,
            ownerNodeId = collective.createdByNodeId,
            adminNodeIds = admins,
            moderatorNodeIds = collective.moderatorNodeIds,
            isBroadcastOnly = collective.isBroadcastOnly,
            allowMemberReactions = collective.allowMemberReactions,
            allowMemberEditOwnMessages = collective.allowMemberEditOwnMessages,
            allowMemberDeleteOwnMessages = collective.allowMemberDeleteOwnMessages,
            issuedAtMs = System.currentTimeMillis()
        )
        val raw = runCatching {
            json.encodeToString(CollectiveInvitePayload.serializer(), payload)
        }.getOrNull() ?: return null
        val encoded = Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        return "$INVITE_PREFIX$encoded"
    }

    fun joinCollectiveByInviteCode(inviteCode: String): Boolean {
        val rawCode = inviteCode.trim()
        if (rawCode.isBlank()) return false
        val encoded = rawCode.removePrefix(INVITE_PREFIX).trim()
        if (encoded.isBlank()) return false
        val decodedBytes = runCatching {
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
        }.getOrNull() ?: return false
        val invite = runCatching {
            json.decodeFromString(
                CollectiveInvitePayload.serializer(),
                decodedBytes.toString(Charsets.UTF_8)
            )
        }.getOrNull() ?: return false

        val collectiveId = invite.collectiveId.trim()
        val title = invite.title.trim()
        if (collectiveId.isBlank() || title.isBlank()) return false
        val type = if (invite.type.equals("channel", ignoreCase = true)) {
            ConversationType.CHANNEL
        } else {
            ConversationType.GROUP
        }
        val members = (invite.memberNodeIds + meshManager.nodeId)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (members.size < 2) return false
        val existing = collectiveByConversationId(collectiveId)
        val ownerId = invite.ownerNodeId
            ?.trim()
            ?.ifBlank { null }
            ?: existing?.createdByNodeId
            ?: invite.adminNodeIds.firstOrNull { it.isNotBlank() }
            ?: invite.memberNodeIds.firstOrNull { it.isNotBlank() }
            ?: meshManager.nodeId
        val defaultAdmin = invite.adminNodeIds.firstOrNull { it.isNotBlank() }
            ?: invite.memberNodeIds.firstOrNull { it.isNotBlank() }
            ?: ownerId
        val admins = invite.adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && members.contains(it) }
            .distinct()
            .let { raw ->
                val withOwner = if (raw.contains(ownerId)) raw else listOf(ownerId) + raw
                if (withOwner.isEmpty()) listOf(defaultAdmin) else withOwner
            }
            .distinct()
        val moderators = invite.moderatorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && members.contains(it) && !admins.contains(it) }
            .distinct()

        val updatedCollective = MeshGroup(
            id = collectiveId,
            title = if (type == ConversationType.CHANNEL) {
                title.take(MAX_CHANNEL_TITLE)
            } else {
                title.take(MAX_GROUP_TITLE)
            },
            memberNodeIds = members,
            createdAtMs = existing?.createdAtMs ?: invite.issuedAtMs,
            createdByNodeId = ownerId,
            type = type,
            adminNodeIds = admins,
            moderatorNodeIds = moderators,
            isBroadcastOnly = if (type == ConversationType.CHANNEL) invite.isBroadcastOnly else false,
            allowMemberReactions = invite.allowMemberReactions,
            allowMemberEditOwnMessages = if (type == ConversationType.CHANNEL) {
                invite.allowMemberEditOwnMessages
            } else {
                true
            },
            allowMemberDeleteOwnMessages = if (type == ConversationType.CHANNEL) {
                invite.allowMemberDeleteOwnMessages
            } else {
                true
            }
        )
        replaceCollective(updatedCollective)
        upsertConversationState(updatedCollective.id) { current ->
            current.copy(updatedAtMs = System.currentTimeMillis())
        }
        openConversation(updatedCollective.id)

        meshManager.sendCollectiveUpdate(
            collectiveId = updatedCollective.id,
            collectiveTitle = updatedCollective.title,
            conversationType = updatedCollective.type,
            memberNodeIds = updatedCollective.memberNodeIds,
            adminNodeIds = updatedCollective.adminNodeIds,
            moderatorNodeIds = updatedCollective.moderatorNodeIds,
            isBroadcastOnly = updatedCollective.isBroadcastOnly,
            allowMemberReactions = updatedCollective.allowMemberReactions,
            allowMemberEditOwnMessages = updatedCollective.allowMemberEditOwnMessages,
            allowMemberDeleteOwnMessages = updatedCollective.allowMemberDeleteOwnMessages,
            noteText = "Joined via invite"
        )
        return true
    }

    fun setChannelBroadcastMode(conversationId: String, broadcastOnly: Boolean): Boolean {
        val collective = collectiveByConversationId(conversationId) ?: return false
        if (collective.type == ConversationType.DIRECT) return false
        if (!canManageCollectiveRoles(collective.id)) return false
        if (collective.isBroadcastOnly == broadcastOnly) return true

        val nextBroadcastOnly = if (collective.type == ConversationType.CHANNEL) {
            broadcastOnly
        } else {
            broadcastOnly
        }
        val updated = collective.copy(
            isBroadcastOnly = nextBroadcastOnly,
            allowMemberEditOwnMessages = if (nextBroadcastOnly) {
                false
            } else {
                collective.allowMemberEditOwnMessages
            },
            allowMemberDeleteOwnMessages = if (nextBroadcastOnly) {
                false
            } else {
                collective.allowMemberDeleteOwnMessages
            }
        )
        replaceCollective(updated)
        meshManager.sendCollectiveUpdate(
            collectiveId = updated.id,
            collectiveTitle = updated.title,
            conversationType = updated.type,
            memberNodeIds = updated.memberNodeIds,
            adminNodeIds = updated.adminNodeIds,
            moderatorNodeIds = updated.moderatorNodeIds,
            isBroadcastOnly = updated.isBroadcastOnly,
            allowMemberReactions = updated.allowMemberReactions,
            allowMemberEditOwnMessages = updated.allowMemberEditOwnMessages,
            allowMemberDeleteOwnMessages = updated.allowMemberDeleteOwnMessages,
            noteText = if (nextBroadcastOnly) {
                if (updated.type == ConversationType.CHANNEL) {
                    "Channel switched to admin-only posting"
                } else {
                    "Group switched to admin-only posting"
                }
            } else {
                if (updated.type == ConversationType.CHANNEL) {
                    "Channel switched to open posting"
                } else {
                    "Group switched to open posting"
                }
            }
        )
        return true
    }

    fun updateCollectiveMembers(conversationId: String, memberNodeIds: List<String>): Boolean {
        val collective = collectiveByConversationId(conversationId) ?: return false
        if (collective.type == ConversationType.DIRECT) return false
        if (!canManageCollectiveRoles(collective.id)) return false

        val ownerId = collective.createdByNodeId.trim().ifBlank { meshManager.nodeId }
        val normalizedMembers = (memberNodeIds + meshManager.nodeId + ownerId)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedMembers.size < 2) return false

        val normalizedAdmins = collective.adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizedMembers.contains(it) }
            .let { admins ->
                if (admins.contains(ownerId)) admins else listOf(ownerId) + admins
            }
            .distinct()
            .ifEmpty { listOf(ownerId) }
        val normalizedModerators = collective.moderatorNodeIds
            .map { it.trim() }
            .filter {
                it.isNotBlank() &&
                    normalizedMembers.contains(it) &&
                    !normalizedAdmins.contains(it)
            }
            .distinct()

        if (normalizedMembers == collective.memberNodeIds &&
            normalizedAdmins == collective.adminNodeIds &&
            normalizedModerators == collective.moderatorNodeIds
        ) {
            return true
        }

        val updated = collective.copy(
            memberNodeIds = normalizedMembers,
            adminNodeIds = normalizedAdmins,
            moderatorNodeIds = normalizedModerators
        )
        replaceCollective(updated)
        meshManager.sendCollectiveUpdate(
            collectiveId = updated.id,
            collectiveTitle = updated.title,
            conversationType = updated.type,
            memberNodeIds = updated.memberNodeIds,
            adminNodeIds = updated.adminNodeIds,
            moderatorNodeIds = updated.moderatorNodeIds,
            isBroadcastOnly = updated.isBroadcastOnly,
            allowMemberReactions = updated.allowMemberReactions,
            allowMemberEditOwnMessages = updated.allowMemberEditOwnMessages,
            allowMemberDeleteOwnMessages = updated.allowMemberDeleteOwnMessages,
            noteText = if (updated.type == ConversationType.CHANNEL) {
                "Channel members updated"
            } else {
                "Group members updated"
            }
        )
        return true
    }

    fun updateChannelAdmins(conversationId: String, adminNodeIds: List<String>): Boolean {
        val collective = collectiveByConversationId(conversationId) ?: return false
        if (collective.type == ConversationType.DIRECT) return false
        if (!canManageCollectiveRoles(collective.id)) return false
        val ownerId = collective.createdByNodeId.trim().ifBlank { meshManager.nodeId }
        val normalizedAdmins = adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && collective.memberNodeIds.contains(it) }
            .distinct()
            .let { admins ->
                if (admins.contains(ownerId)) admins else listOf(ownerId) + admins
            }
            .distinct()
        val normalizedModerators = collective.moderatorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && collective.memberNodeIds.contains(it) && !normalizedAdmins.contains(it) }
            .distinct()
        if (normalizedAdmins == collective.adminNodeIds &&
            normalizedModerators == collective.moderatorNodeIds
        ) return true

        val updated = collective.copy(
            adminNodeIds = normalizedAdmins,
            moderatorNodeIds = normalizedModerators
        )
        replaceCollective(updated)
        meshManager.sendCollectiveUpdate(
            collectiveId = updated.id,
            collectiveTitle = updated.title,
            conversationType = updated.type,
            memberNodeIds = updated.memberNodeIds,
            adminNodeIds = updated.adminNodeIds,
            moderatorNodeIds = updated.moderatorNodeIds,
            isBroadcastOnly = updated.isBroadcastOnly,
            allowMemberReactions = updated.allowMemberReactions,
            allowMemberEditOwnMessages = updated.allowMemberEditOwnMessages,
            allowMemberDeleteOwnMessages = updated.allowMemberDeleteOwnMessages,
            noteText = if (updated.type == ConversationType.CHANNEL) {
                "Channel admins updated"
            } else {
                "Group admins updated"
            }
        )
        return true
    }

    fun updateCollectiveModerators(conversationId: String, moderatorNodeIds: List<String>): Boolean {
        val collective = collectiveByConversationId(conversationId) ?: return false
        if (collective.type == ConversationType.DIRECT) return false
        if (!canManageCollectiveRoles(collective.id)) return false
        val normalizedAdmins = collective.adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && collective.memberNodeIds.contains(it) }
            .distinct()
            .ifEmpty {
                listOf(collective.createdByNodeId.trim().ifBlank { meshManager.nodeId })
            }
        val normalizedModerators = moderatorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && collective.memberNodeIds.contains(it) && !normalizedAdmins.contains(it) }
            .distinct()
        if (normalizedModerators == collective.moderatorNodeIds) return true

        val updated = collective.copy(moderatorNodeIds = normalizedModerators)
        replaceCollective(updated)
        meshManager.sendCollectiveUpdate(
            collectiveId = updated.id,
            collectiveTitle = updated.title,
            conversationType = updated.type,
            memberNodeIds = updated.memberNodeIds,
            adminNodeIds = updated.adminNodeIds,
            moderatorNodeIds = updated.moderatorNodeIds,
            isBroadcastOnly = updated.isBroadcastOnly,
            allowMemberReactions = updated.allowMemberReactions,
            allowMemberEditOwnMessages = updated.allowMemberEditOwnMessages,
            allowMemberDeleteOwnMessages = updated.allowMemberDeleteOwnMessages,
            noteText = if (updated.type == ConversationType.CHANNEL) {
                "Channel moderators updated"
            } else {
                "Group moderators updated"
            }
        )
        return true
    }

    fun updateCollectiveMemberPermissions(
        conversationId: String,
        allowMemberReactions: Boolean,
        allowMemberEditOwnMessages: Boolean,
        allowMemberDeleteOwnMessages: Boolean
    ): Boolean {
        val collective = collectiveByConversationId(conversationId) ?: return false
        if (collective.type == ConversationType.DIRECT) return false
        if (!canManageCollectiveRoles(collective.id)) return false

        val normalizedEditOwn = if (collective.isBroadcastOnly) {
            false
        } else {
            allowMemberEditOwnMessages
        }
        val normalizedDeleteOwn = if (collective.isBroadcastOnly) {
            false
        } else {
            allowMemberDeleteOwnMessages
        }
        if (collective.allowMemberReactions == allowMemberReactions &&
            collective.allowMemberEditOwnMessages == normalizedEditOwn &&
            collective.allowMemberDeleteOwnMessages == normalizedDeleteOwn
        ) {
            return true
        }

        val updated = collective.copy(
            allowMemberReactions = allowMemberReactions,
            allowMemberEditOwnMessages = normalizedEditOwn,
            allowMemberDeleteOwnMessages = normalizedDeleteOwn
        )
        replaceCollective(updated)
        meshManager.sendCollectiveUpdate(
            collectiveId = updated.id,
            collectiveTitle = updated.title,
            conversationType = updated.type,
            memberNodeIds = updated.memberNodeIds,
            adminNodeIds = updated.adminNodeIds,
            moderatorNodeIds = updated.moderatorNodeIds,
            isBroadcastOnly = updated.isBroadcastOnly,
            allowMemberReactions = updated.allowMemberReactions,
            allowMemberEditOwnMessages = updated.allowMemberEditOwnMessages,
            allowMemberDeleteOwnMessages = updated.allowMemberDeleteOwnMessages,
            noteText = if (updated.type == ConversationType.CHANNEL) {
                "Channel member permissions updated"
            } else {
                "Group member permissions updated"
            }
        )
        return true
    }

    fun sendToActiveConversation(
        text: String,
        replyToMessageId: String? = null,
        replyToPreview: String? = null,
        forwardedFromAlias: String? = null,
        forwardedFromMessageId: String? = null
    ): Boolean {
        val draft = text.trim()
        if (draft.isBlank()) return false

        val state = uiState.value
        val activeConversationId = state.activeConversationId ?: return false
        val sent = sendTextToConversation(
            state = state,
            conversationId = activeConversationId,
            text = draft,
            replyToMessageId = replyToMessageId,
            replyToPreview = replyToPreview,
            forwardedFromAlias = forwardedFromAlias,
            forwardedFromMessageId = forwardedFromMessageId
        )
        if (sent) {
            clearConversationDraft(activeConversationId)
        }
        return sent
    }

    fun scheduleMessageInActiveConversation(
        text: String,
        scheduledAtMs: Long,
        replyToMessageId: String? = null,
        replyToPreview: String? = null
    ): Boolean {
        val body = text.trim()
        if (body.isBlank()) return false
        val state = uiState.value
        val conversationId = state.activeConversationId ?: return false
        val conversation = state.conversations.firstOrNull { it.id == conversationId } ?: return false
        if (!isConversationPostingAllowed(conversation)) return false
        val targetNodeId = if (conversation.type == ConversationType.DIRECT &&
            !isSavedMessagesConversation(conversation.id)
        ) {
            conversation.memberNodeIds.firstOrNull { it != meshManager.nodeId } ?: return false
        } else {
            null
        }
        val scheduledId = meshManager.scheduleTextMessage(
            text = body,
            conversationId = conversation.id,
            conversationTitle = conversation.title,
            conversationType = conversation.type,
            scheduledAtMs = scheduledAtMs,
            targetNodeId = targetNodeId,
            memberNodeIds = conversation.memberNodeIds,
            adminNodeIds = conversation.adminNodeIds,
            moderatorNodeIds = conversation.moderatorNodeIds,
            isBroadcastOnly = conversation.isBroadcastOnly,
            allowMemberReactions = conversation.allowMemberReactions,
            allowMemberEditOwnMessages = conversation.allowMemberEditOwnMessages,
            allowMemberDeleteOwnMessages = conversation.allowMemberDeleteOwnMessages,
            replyToMessageId = replyToMessageId,
            replyToPreview = replyToPreview
        )
        if (scheduledId != null) clearConversationDraft(conversation.id)
        return scheduledId != null
    }

    fun cancelScheduledMessage(messageId: String): Boolean {
        return meshManager.cancelScheduledMessage(messageId)
    }

    fun cancelOutgoingFileTransfer(transferId: String): Boolean {
        return meshManager.cancelOutgoingFileTransfer(transferId)
    }

    fun retryOutgoingFileTransfer(transferId: String): Boolean {
        return meshManager.retryOutgoingFileTransfer(transferId)
    }

    fun retryIncomingFileTransfer(transferId: String): Boolean {
        return meshManager.retryIncomingFileTransfer(transferId)
    }

    fun cancelIncomingFileTransfer(transferId: String): Boolean {
        return meshManager.cancelIncomingFileTransfer(transferId)
    }

    fun editMessageInActiveConversation(messageId: String, updatedText: String): Boolean {
        val targetId = messageId.trim()
        val text = updatedText.trim()
        if (targetId.isBlank() || text.isBlank()) return false

        val state = uiState.value
        val activeConversationId = state.activeConversationId ?: return false
        val conversation = state.conversations.firstOrNull { it.id == activeConversationId } ?: return false
        val targetMessage = state.messages.firstOrNull { message ->
            message.id == targetId && message.conversationId == conversation.id
        } ?: return false
        if (targetMessage.isDeleted || targetMessage.contentType != ChatContentType.TEXT) return false
        if (!isLocalMessageOwner(targetMessage)) return false
        if (!canLocalActorEditOwnMessage(conversation)) return false

        if (isSavedMessagesConversation(conversation.id)) {
            return meshManager.editLocalMessage(targetId, text)
        }

        return when (conversation.type) {
            ConversationType.DIRECT -> {
                val targetNodeId = conversation.memberNodeIds.firstOrNull { it != meshManager.nodeId }
                    ?: return false
                meshManager.editDirectMessage(
                    targetNodeId = targetNodeId,
                    conversationId = conversation.id,
                    conversationTitle = conversation.title,
                    targetMessageId = targetId,
                    editedText = text
                )
            }

            ConversationType.GROUP -> {
                meshManager.editGroupMessage(
                    groupId = conversation.id,
                    groupTitle = conversation.title,
                    memberNodeIds = conversation.memberNodeIds,
                    targetMessageId = targetId,
                    editedText = text,
                    chatType = MeshMessagePayload.CHAT_TYPE_GROUP,
                    adminNodeIds = conversation.adminNodeIds,
                    moderatorNodeIds = conversation.moderatorNodeIds,
                    isBroadcastOnly = conversation.isBroadcastOnly,
                    allowMemberReactions = conversation.allowMemberReactions,
                    allowMemberEditOwnMessages = conversation.allowMemberEditOwnMessages,
                    allowMemberDeleteOwnMessages = conversation.allowMemberDeleteOwnMessages
                )
            }

            ConversationType.CHANNEL -> {
                meshManager.editGroupMessage(
                    groupId = conversation.id,
                    groupTitle = conversation.title,
                    memberNodeIds = conversation.memberNodeIds,
                    targetMessageId = targetId,
                    editedText = text,
                    chatType = MeshMessagePayload.CHAT_TYPE_CHANNEL,
                    adminNodeIds = conversation.adminNodeIds,
                    moderatorNodeIds = conversation.moderatorNodeIds,
                    isBroadcastOnly = conversation.isBroadcastOnly,
                    allowMemberReactions = conversation.allowMemberReactions,
                    allowMemberEditOwnMessages = conversation.allowMemberEditOwnMessages,
                    allowMemberDeleteOwnMessages = conversation.allowMemberDeleteOwnMessages
                )
            }
        }
    }

    fun deleteMessageInActiveConversation(messageId: String): Boolean {
        val targetId = messageId.trim()
        if (targetId.isBlank()) return false

        val state = uiState.value
        val activeConversationId = state.activeConversationId ?: return false
        val conversation = state.conversations.firstOrNull { it.id == activeConversationId } ?: return false
        val targetMessage = state.messages.firstOrNull { message ->
            message.id == targetId && message.conversationId == conversation.id
        } ?: return false
        if (targetMessage.isDeleted) return false
        val isOwner = isLocalMessageOwner(targetMessage)
        val canDeleteOwn = canLocalActorDeleteOwnMessage(conversation)
        val canModerate = canLocalActorModerate(conversation)
        if (!(canModerate || (isOwner && canDeleteOwn))) return false

        if (isSavedMessagesConversation(conversation.id)) {
            return meshManager.deleteLocalMessage(targetId)
        }

        return when (conversation.type) {
            ConversationType.DIRECT -> {
                val targetNodeId = conversation.memberNodeIds.firstOrNull { it != meshManager.nodeId }
                    ?: return false
                meshManager.deleteDirectMessage(
                    targetNodeId = targetNodeId,
                    conversationId = conversation.id,
                    conversationTitle = conversation.title,
                    targetMessageId = targetId
                )
            }

            ConversationType.GROUP -> {
                meshManager.deleteGroupMessage(
                    groupId = conversation.id,
                    groupTitle = conversation.title,
                    memberNodeIds = conversation.memberNodeIds,
                    targetMessageId = targetId,
                    chatType = MeshMessagePayload.CHAT_TYPE_GROUP,
                    adminNodeIds = conversation.adminNodeIds,
                    moderatorNodeIds = conversation.moderatorNodeIds,
                    isBroadcastOnly = conversation.isBroadcastOnly,
                    allowMemberReactions = conversation.allowMemberReactions,
                    allowMemberEditOwnMessages = conversation.allowMemberEditOwnMessages,
                    allowMemberDeleteOwnMessages = conversation.allowMemberDeleteOwnMessages
                )
            }

            ConversationType.CHANNEL -> {
                meshManager.deleteGroupMessage(
                    groupId = conversation.id,
                    groupTitle = conversation.title,
                    memberNodeIds = conversation.memberNodeIds,
                    targetMessageId = targetId,
                    chatType = MeshMessagePayload.CHAT_TYPE_CHANNEL,
                    adminNodeIds = conversation.adminNodeIds,
                    moderatorNodeIds = conversation.moderatorNodeIds,
                    isBroadcastOnly = conversation.isBroadcastOnly,
                    allowMemberReactions = conversation.allowMemberReactions,
                    allowMemberEditOwnMessages = conversation.allowMemberEditOwnMessages,
                    allowMemberDeleteOwnMessages = conversation.allowMemberDeleteOwnMessages
                )
            }
        }
    }

    fun reactToMessageInActiveConversation(messageId: String, emoji: String): Boolean {
        val targetId = messageId.trim()
        if (targetId.isBlank()) return false

        val state = uiState.value
        val activeConversationId = state.activeConversationId ?: return false
        val conversation = state.conversations.firstOrNull { it.id == activeConversationId } ?: return false
        val targetMessage = state.messages.firstOrNull { message ->
            message.id == targetId && message.conversationId == conversation.id
        } ?: return false
        if (targetMessage.isDeleted) return false
        if (!canLocalActorReact(conversation)) return false

        if (isSavedMessagesConversation(conversation.id)) {
            return meshManager.reactLocalMessage(targetId, emoji)
        }

        return when (conversation.type) {
            ConversationType.DIRECT -> {
                val targetNodeId = conversation.memberNodeIds.firstOrNull { it != meshManager.nodeId }
                    ?: return false
                meshManager.reactDirectMessage(
                    targetNodeId = targetNodeId,
                    conversationId = conversation.id,
                    conversationTitle = conversation.title,
                    targetMessageId = targetId,
                    emoji = emoji
                )
            }

            ConversationType.GROUP -> {
                meshManager.reactGroupMessage(
                    groupId = conversation.id,
                    groupTitle = conversation.title,
                    memberNodeIds = conversation.memberNodeIds,
                    targetMessageId = targetId,
                    emoji = emoji,
                    chatType = MeshMessagePayload.CHAT_TYPE_GROUP,
                    adminNodeIds = conversation.adminNodeIds,
                    moderatorNodeIds = conversation.moderatorNodeIds,
                    isBroadcastOnly = conversation.isBroadcastOnly,
                    allowMemberReactions = conversation.allowMemberReactions,
                    allowMemberEditOwnMessages = conversation.allowMemberEditOwnMessages,
                    allowMemberDeleteOwnMessages = conversation.allowMemberDeleteOwnMessages
                )
            }

            ConversationType.CHANNEL -> {
                meshManager.reactGroupMessage(
                    groupId = conversation.id,
                    groupTitle = conversation.title,
                    memberNodeIds = conversation.memberNodeIds,
                    targetMessageId = targetId,
                    emoji = emoji,
                    chatType = MeshMessagePayload.CHAT_TYPE_CHANNEL,
                    adminNodeIds = conversation.adminNodeIds,
                    moderatorNodeIds = conversation.moderatorNodeIds,
                    isBroadcastOnly = conversation.isBroadcastOnly,
                    allowMemberReactions = conversation.allowMemberReactions,
                    allowMemberEditOwnMessages = conversation.allowMemberEditOwnMessages,
                    allowMemberDeleteOwnMessages = conversation.allowMemberDeleteOwnMessages
                )
            }
        }
    }

    fun pinMessageInActiveConversation(messageId: String, pinEnabled: Boolean): Boolean {
        val targetId = messageId.trim()
        if (targetId.isBlank()) return false

        val state = uiState.value
        val activeConversationId = state.activeConversationId ?: return false
        val conversation = state.conversations.firstOrNull { it.id == activeConversationId } ?: return false
        val targetMessage = state.messages.firstOrNull { message ->
            message.id == targetId && message.conversationId == conversation.id
        } ?: return false
        if (targetMessage.isDeleted) return false
        if (!canLocalActorPin(conversation)) return false

        if (isSavedMessagesConversation(conversation.id)) {
            return meshManager.pinLocalMessage(
                conversationId = conversation.id,
                targetMessageId = targetId,
                pinEnabled = pinEnabled
            )
        }

        return when (conversation.type) {
            ConversationType.DIRECT -> {
                val targetNodeId = conversation.memberNodeIds.firstOrNull { it != meshManager.nodeId }
                    ?: return false
                meshManager.pinDirectMessage(
                    targetNodeId = targetNodeId,
                    conversationId = conversation.id,
                    conversationTitle = conversation.title,
                    targetMessageId = targetId,
                    pinEnabled = pinEnabled
                )
            }

            ConversationType.GROUP -> {
                meshManager.pinGroupMessage(
                    groupId = conversation.id,
                    groupTitle = conversation.title,
                    memberNodeIds = conversation.memberNodeIds,
                    targetMessageId = targetId,
                    pinEnabled = pinEnabled,
                    chatType = MeshMessagePayload.CHAT_TYPE_GROUP,
                    adminNodeIds = conversation.adminNodeIds,
                    moderatorNodeIds = conversation.moderatorNodeIds,
                    isBroadcastOnly = conversation.isBroadcastOnly,
                    allowMemberReactions = conversation.allowMemberReactions,
                    allowMemberEditOwnMessages = conversation.allowMemberEditOwnMessages,
                    allowMemberDeleteOwnMessages = conversation.allowMemberDeleteOwnMessages
                )
            }

            ConversationType.CHANNEL -> {
                meshManager.pinGroupMessage(
                    groupId = conversation.id,
                    groupTitle = conversation.title,
                    memberNodeIds = conversation.memberNodeIds,
                    targetMessageId = targetId,
                    pinEnabled = pinEnabled,
                    chatType = MeshMessagePayload.CHAT_TYPE_CHANNEL,
                    adminNodeIds = conversation.adminNodeIds,
                    moderatorNodeIds = conversation.moderatorNodeIds,
                    isBroadcastOnly = conversation.isBroadcastOnly,
                    allowMemberReactions = conversation.allowMemberReactions,
                    allowMemberEditOwnMessages = conversation.allowMemberEditOwnMessages,
                    allowMemberDeleteOwnMessages = conversation.allowMemberDeleteOwnMessages
                )
            }
        }
    }

    fun updateSavedMessageTags(messageId: String, tags: List<String>): Boolean {
        val targetId = messageId.trim()
        if (targetId.isBlank()) return false
        val state = uiState.value
        val conversationId = state.activeConversationId ?: return false
        if (!isSavedMessagesConversation(conversationId)) return false
        val target = state.messages.firstOrNull { message ->
            message.id == targetId && message.conversationId == conversationId && !message.isDeleted
        } ?: return false
        return meshManager.updateLocalMessageTags(target.id, tags)
    }

    fun forwardMessageToConversation(sourceMessageId: String, targetConversationId: String): Boolean {
        return forwardMessageToConversations(sourceMessageId, listOf(targetConversationId)) > 0
    }

    fun forwardMessageToConversations(
        sourceMessageId: String,
        targetConversationIds: List<String>
    ): Int {
        val sourceId = sourceMessageId.trim()
        val targetIds = targetConversationIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (sourceId.isBlank() || targetIds.isEmpty()) return 0

        val state = uiState.value
        val sourceMessage = state.messages.firstOrNull { it.id == sourceId } ?: return 0
        if (sourceMessage.isDeleted) return 0

        val forwardedFromAlias = if (sourceMessage.isLocal) {
            state.nodeAlias
        } else {
            sourceMessage.senderAlias?.trim()?.ifBlank { null } ?: "Unknown"
        }

        return when (sourceMessage.contentType) {
            ChatContentType.TEXT -> {
                val sourceText = sourceMessage.text.trim()
                if (sourceText.isBlank()) return 0
                var sentCount = 0
                targetIds.forEach { targetId ->
                    val sent = sendTextToConversation(
                        state = state,
                        conversationId = targetId,
                        text = sourceText,
                        forwardedFromAlias = forwardedFromAlias,
                        forwardedFromMessageId = sourceMessage.id
                    )
                    if (sent) {
                        sentCount++
                    }
                }
                sentCount
            }

            ChatContentType.FILE -> {
                val localAttachment = sourceMessage.attachment?.localUri?.trim()?.ifBlank { null }
                    ?: return 0
                val sourceUri = resolveAttachmentUri(localAttachment) ?: return 0
                var sentCount = 0
                targetIds.forEach { targetId ->
                    if (sendFileToConversation(
                            state = state,
                            conversationId = targetId,
                            fileUri = sourceUri
                        )
                    ) {
                        sentCount++
                    }
                }
                sentCount
            }
        }
    }

    fun sendFileToActiveConversation(fileUri: Uri): Boolean {
        val state = uiState.value
        val activeConversationId = state.activeConversationId ?: return false
        val sent = sendFileToConversation(
            state = state,
            conversationId = activeConversationId,
            fileUri = fileUri
        )
        if (sent) {
            clearConversationDraft(activeConversationId)
        }
        return sent
    }

    fun sendMediaAlbumToActiveConversation(fileUris: List<Uri>, caption: String): Int {
        val state = uiState.value
        val conversationId = state.activeConversationId ?: return 0
        val normalizedUris = fileUris.distinct().take(MAX_MEDIA_ALBUM_ITEMS)
        if (normalizedUris.isEmpty()) return 0
        val albumId = if (normalizedUris.size > 1) {
            "album:${UUID.randomUUID()}"
        } else {
            null
        }
        var sentCount = 0
        normalizedUris.forEachIndexed { index, uri ->
            val sent = sendFileToConversation(
                state = state,
                conversationId = conversationId,
                fileUri = uri,
                caption = if (index == 0) caption else "",
                mediaAlbumId = albumId,
                mediaAlbumIndex = index,
                mediaAlbumCount = normalizedUris.size
            )
            if (sent) sentCount++
        }
        if (sentCount > 0) clearConversationDraft(conversationId)
        return sentCount
    }

    fun sendTextToConversationById(conversationId: String, text: String): Boolean {
        val id = conversationId.trim()
        val body = text.trim()
        if (id.isBlank() || body.isBlank()) return false
        return sendTextToConversation(
            state = uiState.value,
            conversationId = id,
            text = body
        )
    }

    fun sendFileToConversationById(conversationId: String, fileUri: Uri): Boolean {
        val id = conversationId.trim()
        if (id.isBlank()) return false
        return sendFileToConversation(
            state = uiState.value,
            conversationId = id,
            fileUri = fileUri
        )
    }

    private fun sendFileToConversation(
        state: MeshUiState,
        conversationId: String,
        fileUri: Uri,
        caption: String = "",
        mediaAlbumId: String? = null,
        mediaAlbumIndex: Int = 0,
        mediaAlbumCount: Int = 1
    ): Boolean {
        val conversation = state.conversations.firstOrNull { it.id == conversationId } ?: return false
        if (isSavedMessagesConversation(conversation.id)) {
            return meshManager.saveLocalFileMessage(
                fileUri = fileUri,
                conversationId = conversation.id,
                conversationTitle = conversation.title,
                caption = caption,
                mediaAlbumId = mediaAlbumId,
                mediaAlbumIndex = mediaAlbumIndex,
                mediaAlbumCount = mediaAlbumCount
            )
        }
        val sent = when (conversation.type) {
            ConversationType.DIRECT -> {
                val targetNodeId = conversation.memberNodeIds.firstOrNull { it != meshManager.nodeId }
                    ?: return false
                meshManager.sendDirectFile(
                    fileUri = fileUri,
                    targetNodeId = targetNodeId,
                    conversationId = conversation.id,
                    conversationTitle = conversation.title,
                    caption = caption,
                    mediaAlbumId = mediaAlbumId,
                    mediaAlbumIndex = mediaAlbumIndex,
                    mediaAlbumCount = mediaAlbumCount
                )
            }

            ConversationType.GROUP -> {
                if (!isConversationPostingAllowed(conversation)) return false
                meshManager.sendGroupFile(
                    fileUri = fileUri,
                    groupId = conversation.id,
                    groupTitle = conversation.title,
                    memberNodeIds = conversation.memberNodeIds,
                    adminNodeIds = conversation.adminNodeIds,
                    moderatorNodeIds = conversation.moderatorNodeIds,
                    isBroadcastOnly = conversation.isBroadcastOnly,
                    allowMemberReactions = conversation.allowMemberReactions,
                    allowMemberEditOwnMessages = conversation.allowMemberEditOwnMessages,
                    allowMemberDeleteOwnMessages = conversation.allowMemberDeleteOwnMessages,
                    chatType = MeshMessagePayload.CHAT_TYPE_GROUP,
                    conversationType = ConversationType.GROUP,
                    caption = caption,
                    mediaAlbumId = mediaAlbumId,
                    mediaAlbumIndex = mediaAlbumIndex,
                    mediaAlbumCount = mediaAlbumCount
                ) > 0
            }

            ConversationType.CHANNEL -> {
                if (!isConversationPostingAllowed(conversation)) return false
                meshManager.sendGroupFile(
                    fileUri = fileUri,
                    groupId = conversation.id,
                    groupTitle = conversation.title,
                    memberNodeIds = conversation.memberNodeIds,
                    adminNodeIds = conversation.adminNodeIds,
                    moderatorNodeIds = conversation.moderatorNodeIds,
                    isBroadcastOnly = conversation.isBroadcastOnly,
                    allowMemberReactions = conversation.allowMemberReactions,
                    allowMemberEditOwnMessages = conversation.allowMemberEditOwnMessages,
                    allowMemberDeleteOwnMessages = conversation.allowMemberDeleteOwnMessages,
                    chatType = MeshMessagePayload.CHAT_TYPE_CHANNEL,
                    conversationType = ConversationType.CHANNEL,
                    caption = caption,
                    mediaAlbumId = mediaAlbumId,
                    mediaAlbumIndex = mediaAlbumIndex,
                    mediaAlbumCount = mediaAlbumCount
                ) > 0
            }
        }
        return sent
    }

    private fun resolveAttachmentUri(rawPath: String): Uri? {
        val normalized = rawPath.trim()
        if (normalized.isBlank()) return null
        val parsed = Uri.parse(normalized)
        if (!parsed.scheme.isNullOrBlank()) return parsed
        return Uri.fromFile(File(normalized))
    }

    fun exportPortableBackup(uri: Uri, passphrase: String): Boolean {
        val encrypted = localStore.exportPortableBackupBytes(passphrase) ?: return false
        val resolver = getApplication<Application>().contentResolver
        return runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(encrypted)
                output.flush()
                true
            } ?: false
        }.getOrDefault(false)
    }

    fun importPortableBackup(uri: Uri, passphrase: String): Boolean {
        val resolver = getApplication<Application>().contentResolver
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return false
        val imported = localStore.importPortableBackupBytes(bytes, passphrase)
        if (!imported) return false

        meshManager.reloadFromSecureStore()
        _groups.value = localStore.loadGroups()
        _conversationStates.value = localStore.loadConversationStates()
            .filter { it.conversationId.isNotBlank() }
            .associateBy { it.conversationId }
        _selectedConversationId.value = null
        _isConversationOpen.value = false
        _selectedTab.value = MeshTab.CHATS
        return true
    }

    private fun buildContacts(
        peers: List<Peer>,
        knownIdentities: List<PeerIdentity>
    ): List<MeshContact> {
        val onlineByNodeId = peers
            .mapNotNull { peer ->
                val peerNodeId = peer.nodeId ?: return@mapNotNull null
                peerNodeId to peer
            }
            .toMap()
        val knownByNodeId = knownIdentities.associateBy { it.nodeId }
        val allNodeIds = linkedSetOf<String>().apply {
            addAll(knownByNodeId.keys)
            addAll(onlineByNodeId.keys)
        }
        return allNodeIds
            .map { peerNodeId ->
                val onlinePeer = onlineByNodeId[peerNodeId]
                val knownIdentity = knownByNodeId[peerNodeId]
                val alias = onlinePeer?.alias?.ifBlank { null }
                    ?: knownIdentity?.alias?.ifBlank { null }
                    ?: "Node-${peerNodeId.take(4)}"
                val fingerprint = onlinePeer?.fingerprintShort
                    ?: knownIdentity?.fingerprint?.take(12)
                MeshContact(
                    nodeId = peerNodeId,
                    alias = alias,
                    fingerprintShort = fingerprint,
                    isOnline = onlinePeer?.isConnected == true,
                    avatarData = onlinePeer?.let { knownIdentity?.avatarData.orEmpty() }
                        ?: knownIdentity?.avatarData.orEmpty()
                )
            }
            .sortedWith(
                compareByDescending<MeshContact> { it.isOnline }
                    .thenBy { it.alias.lowercase() }
            )
    }

    private fun buildConversations(
        nodeId: String,
        contacts: List<MeshContact>,
        groups: List<MeshGroup>,
        messages: List<ChatMessage>,
        conversationStates: Map<String, ConversationLocalState>
    ): List<ConversationSummary> {
        val contactByNode = contacts.associateBy { it.nodeId }
        val conversationMap = linkedMapOf<String, ConversationSummary>()
        val lastMessageByConversationId = mutableMapOf<String, ChatMessage>()

        conversationMap[SAVED_MESSAGES_CONVERSATION_ID] = ConversationSummary(
            id = SAVED_MESSAGES_CONVERSATION_ID,
            type = ConversationType.DIRECT,
            title = SAVED_MESSAGES_TITLE,
            subtitle = "Private encrypted notes and files",
            memberNodeIds = listOf(nodeId),
            isOnline = true,
            isPinned = conversationStates[SAVED_MESSAGES_CONVERSATION_ID]?.isPinned != false
        )

        contacts.forEach { contact ->
            val conversationId = directConversationId(nodeId, contact.nodeId)
            conversationMap[conversationId] = ConversationSummary(
                id = conversationId,
                type = ConversationType.DIRECT,
                title = contact.alias,
                subtitle = if (contact.isOnline) "online" else "offline",
                memberNodeIds = listOf(nodeId, contact.nodeId),
                isOnline = contact.isOnline,
                avatarData = contact.avatarData
            )
        }

        groups.forEach { group ->
            val members = (group.memberNodeIds + nodeId).distinct()
            val ownerId = group.createdByNodeId.trim().ifBlank { members.firstOrNull() ?: nodeId }
            val admins = group.adminNodeIds
                .map { it.trim() }
                .filter { it.isNotBlank() && members.contains(it) }
                .let { raw ->
                    if (raw.contains(ownerId)) raw else listOf(ownerId) + raw
                }
                .distinct()
            val moderators = group.moderatorNodeIds
                .map { it.trim() }
                .filter { it.isNotBlank() && members.contains(it) && !admins.contains(it) }
                .distinct()
            val online = members.any { member ->
                member != nodeId && contactByNode[member]?.isOnline == true
            }
            val subtitle = if (group.type == ConversationType.CHANNEL) {
                "${members.size} subscribers"
            } else {
                "${members.size} members"
            }
            conversationMap[group.id] = ConversationSummary(
                id = group.id,
                type = group.type,
                title = group.title,
                subtitle = subtitle,
                memberNodeIds = members,
                isOnline = online,
                ownerNodeId = ownerId,
                adminNodeIds = admins,
                moderatorNodeIds = moderators,
                isBroadcastOnly = group.isBroadcastOnly,
                allowMemberReactions = group.allowMemberReactions,
                allowMemberEditOwnMessages = group.allowMemberEditOwnMessages,
                allowMemberDeleteOwnMessages = group.allowMemberDeleteOwnMessages
            )
        }

        messages.forEach { message ->
            val conversationId = resolveConversationId(message = message, localNodeId = nodeId)
            if (!conversationMap.containsKey(conversationId)) {
                conversationMap[conversationId] = inferConversation(
                    message = message,
                    localNodeId = nodeId,
                    contactByNode = contactByNode
                )
            }

            val existing = lastMessageByConversationId[conversationId]
            if (existing == null || message.createdAtMs >= existing.createdAtMs) {
                lastMessageByConversationId[conversationId] = message
            }
        }

        val enriched = conversationMap.mapValues { (conversationId, conversation) ->
            val lastMessage = lastMessageByConversationId[conversationId] ?: return@mapValues conversation
            conversation.copy(
                lastMessageAtMs = lastMessage.createdAtMs,
                lastMessagePreview = buildMessagePreview(lastMessage)
            )
        }.values
            .map { conversation ->
                val localState = conversationStates[conversation.id]
                conversation.copy(
                    isPinned = if (isSavedMessagesConversation(conversation.id)) {
                        localState?.isPinned != false
                    } else {
                        localState?.isPinned == true
                    },
                    unreadCount = localState?.unreadCount ?: 0,
                    draftText = localState?.draftText.orEmpty(),
                    isMuted = localState?.isMuted == true,
                    isArchived = localState?.isArchived == true
                )
            }
            .toList()

        return enriched.sortedWith(
            compareBy<ConversationSummary> { it.isArchived }
                .thenByDescending { it.isPinned }
                .thenByDescending { it.lastMessageAtMs }
                .thenBy { it.title.lowercase() }
        )
    }

    private fun resolveConversationId(message: ChatMessage, localNodeId: String): String {
        val explicit = message.conversationId.trim()
        if (explicit.isNotBlank() && explicit != ChatMessage.LEGACY_BROADCAST_CONVERSATION_ID) {
            return explicit
        }
        return when (message.conversationType) {
            ConversationType.GROUP -> "grp:${message.originNodeId}:${message.id.take(6)}"
            ConversationType.CHANNEL -> "chn:${message.originNodeId}:${message.id.take(6)}"
            ConversationType.DIRECT -> {
                val otherNode = resolveOtherNodeId(message, localNodeId)
                directConversationId(localNodeId, otherNode)
            }
        }
    }

    private fun inferConversation(
        message: ChatMessage,
        localNodeId: String,
        contactByNode: Map<String, MeshContact>
    ): ConversationSummary {
        return when (message.conversationType) {
            ConversationType.GROUP -> {
                val conversationId = resolveConversationId(message, localNodeId)
                val rawMembers = message.memberNodeIds
                    .ifEmpty { listOf(localNodeId, message.originNodeId) }
                    .distinct()
                val ownerId = message.collectiveOwnerNodeId
                    ?.trim()
                    ?.ifBlank { null }
                    ?: message.collectiveAdminNodeIds.firstOrNull { it.isNotBlank() }
                    ?: message.originNodeId
                val members = (rawMembers + ownerId).distinct()
                val admins = message.collectiveAdminNodeIds
                    .map { it.trim() }
                    .filter { it.isNotBlank() && members.contains(it) }
                    .distinct()
                    .let { raw ->
                        if (raw.contains(ownerId)) raw else listOf(ownerId) + raw
                    }
                    .ifEmpty { listOf(ownerId) }
                val moderators = message.collectiveModeratorNodeIds
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !admins.contains(it) && members.contains(it) }
                    .distinct()
                ConversationSummary(
                    id = conversationId,
                    type = ConversationType.GROUP,
                    title = message.conversationTitle ?: "Group ${conversationId.takeLast(4)}",
                    subtitle = "${members.size} members",
                    memberNodeIds = members,
                    isOnline = members.any { member ->
                        member != localNodeId && contactByNode[member]?.isOnline == true
                    },
                    ownerNodeId = ownerId,
                    adminNodeIds = admins,
                    moderatorNodeIds = moderators,
                    isBroadcastOnly = message.collectiveBroadcastOnly ?: false,
                    allowMemberReactions = message.collectiveAllowMemberReactions ?: true,
                    allowMemberEditOwnMessages = message.collectiveAllowMemberEditOwnMessages ?: true,
                    allowMemberDeleteOwnMessages = message.collectiveAllowMemberDeleteOwnMessages ?: true
                )
            }

            ConversationType.CHANNEL -> {
                val conversationId = resolveConversationId(message, localNodeId)
                val rawMembers = message.memberNodeIds
                    .ifEmpty { listOf(localNodeId, message.originNodeId) }
                    .distinct()
                val ownerId = message.collectiveOwnerNodeId
                    ?.trim()
                    ?.ifBlank { null }
                    ?: message.collectiveAdminNodeIds.firstOrNull { it.isNotBlank() }
                    ?: message.originNodeId
                val members = (rawMembers + ownerId).distinct()
                val admins = message.collectiveAdminNodeIds
                    .map { it.trim() }
                    .filter { it.isNotBlank() && members.contains(it) }
                    .distinct()
                    .let { raw ->
                        if (raw.contains(ownerId)) raw else listOf(ownerId) + raw
                    }
                    .ifEmpty { listOf(ownerId) }
                val moderators = message.collectiveModeratorNodeIds
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !admins.contains(it) && members.contains(it) }
                    .distinct()
                ConversationSummary(
                    id = conversationId,
                    type = ConversationType.CHANNEL,
                    title = message.conversationTitle ?: "Channel ${conversationId.takeLast(4)}",
                    subtitle = "${members.size} subscribers",
                    memberNodeIds = members,
                    isOnline = members.any { member ->
                        member != localNodeId && contactByNode[member]?.isOnline == true
                    },
                    ownerNodeId = ownerId,
                    adminNodeIds = admins,
                    moderatorNodeIds = moderators,
                    isBroadcastOnly = message.collectiveBroadcastOnly ?: true,
                    allowMemberReactions = message.collectiveAllowMemberReactions ?: true,
                    allowMemberEditOwnMessages = message.collectiveAllowMemberEditOwnMessages ?: false,
                    allowMemberDeleteOwnMessages = message.collectiveAllowMemberDeleteOwnMessages ?: false
                )
            }

            ConversationType.DIRECT -> {
                val otherNode = resolveOtherNodeId(message, localNodeId)
                val title = contactByNode[otherNode]?.alias
                    ?: message.senderAlias
                    ?: "Node-${otherNode.take(4)}"
                ConversationSummary(
                    id = directConversationId(localNodeId, otherNode),
                    type = ConversationType.DIRECT,
                    title = title,
                    subtitle = if (contactByNode[otherNode]?.isOnline == true) "online" else "offline",
                    memberNodeIds = listOf(localNodeId, otherNode),
                    isOnline = contactByNode[otherNode]?.isOnline == true,
                    avatarData = contactByNode[otherNode]?.avatarData.orEmpty()
                )
            }
        }
    }

    private fun resolveOtherNodeId(message: ChatMessage, localNodeId: String): String {
        val fromMembers = message.memberNodeIds.firstOrNull { it != localNodeId }
        if (!fromMembers.isNullOrBlank()) return fromMembers

        val fromTarget = message.targetNodeId
            ?.takeIf { it.isNotBlank() && it != localNodeId }
        if (!fromTarget.isNullOrBlank()) return fromTarget

        if (message.originNodeId != localNodeId) return message.originNodeId
        return localNodeId
    }

    private fun buildMessagePreview(message: ChatMessage): String {
        if (message.isDeleted) {
            return if (message.isLocal) "You: Message deleted" else "Message deleted"
        }
        val fwdPrefix = message.forwardedFromAlias?.trim()?.ifBlank { null }?.let { "Fwd:$it " } ?: ""
        if (message.contentType == ChatContentType.FILE) {
            val fileName = message.attachment?.fileName ?: message.text.ifBlank { "File" }
            val mimeType = message.attachment?.mimeType.orEmpty().lowercase()
            val label = when {
                mimeType.startsWith("image/") -> "photo"
                mimeType.startsWith("video/") -> "video"
                mimeType.startsWith("audio/") -> "voice"
                else -> "file"
            }
            val base = "$fwdPrefix[$label] $fileName".trim()
            return if (message.isLocal) "You: $base" else base
        }
        val base = message.text.trim().replace("\n", " ")
        val clipped = if (base.length > 46) "${base.take(43)}..." else base
        val withPrefix = "$fwdPrefix$clipped".trim()
        return if (message.isLocal) "You: $withPrefix" else withPrefix
    }

    private fun collectiveByConversationId(conversationId: String): MeshGroup? {
        val id = conversationId.trim()
        if (id.isBlank()) return null
        return _groups.value.firstOrNull { it.id == id }
    }

    private fun replaceCollective(updatedCollective: MeshGroup) {
        _groups.update { current ->
            val byId = current.associateBy { it.id }.toMutableMap()
            byId[updatedCollective.id] = updatedCollective
            val updated = byId.values.sortedByDescending { it.createdAtMs }
            localStore.persistGroups(updated)
            updated
        }
    }

    private fun isConversationAdmin(conversationId: String): Boolean {
        val role = resolveConversationRole(conversationId)
        return role == CollectiveRole.OWNER || role == CollectiveRole.ADMIN
    }

    private fun isConversationModerator(conversationId: String): Boolean {
        val role = resolveConversationRole(conversationId)
        return role == CollectiveRole.OWNER ||
            role == CollectiveRole.ADMIN ||
            role == CollectiveRole.MODERATOR
    }

    private fun canManageCollectiveRoles(conversationId: String): Boolean {
        val role = resolveConversationRole(conversationId)
        return role == CollectiveRole.OWNER || role == CollectiveRole.ADMIN
    }

    private fun resolveConversationRole(conversationId: String): CollectiveRole {
        val collective = collectiveByConversationId(conversationId) ?: return CollectiveRole.MEMBER
        if (collective.type == ConversationType.DIRECT) return CollectiveRole.OWNER
        val ownerId = collective.createdByNodeId.trim().ifBlank { meshManager.nodeId }
        if (ownerId == meshManager.nodeId) return CollectiveRole.OWNER
        val admins = collective.adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .let { admins ->
                if (admins.contains(ownerId)) admins else listOf(ownerId) + admins
            }
            .distinct()
        if (admins.contains(meshManager.nodeId)) return CollectiveRole.ADMIN
        val moderators = collective.moderatorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && !admins.contains(it) }
            .distinct()
        return if (moderators.contains(meshManager.nodeId)) {
            CollectiveRole.MODERATOR
        } else {
            CollectiveRole.MEMBER
        }
    }

    private fun resolveConversationPermissions(conversation: ConversationSummary): ConversationPermissions {
        if (conversation.type == ConversationType.DIRECT) {
            return ConversationPermissions(
                role = CollectiveRole.OWNER,
                canPost = true,
                canModerate = false,
                canPin = true,
                canReact = true,
                canEditOwn = true,
                canDeleteOwn = true,
                canManageRoles = false,
                isAdmin = false,
                isModerator = false
            )
        }
        val ownerId = collectiveByConversationId(conversation.id)
            ?.createdByNodeId
            ?.trim()
            ?.ifBlank { null }
            ?: conversation.ownerNodeId?.trim()?.ifBlank { null }
            ?: conversation.adminNodeIds.firstOrNull { it.isNotBlank() }
            ?: meshManager.nodeId
        val admins = conversation.adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && conversation.memberNodeIds.contains(it) }
            .let { raw ->
                if (raw.contains(ownerId)) raw else listOf(ownerId) + raw
            }
            .distinct()
        val moderators = conversation.moderatorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && conversation.memberNodeIds.contains(it) && !admins.contains(it) }
            .distinct()
        val role = when {
            ownerId == meshManager.nodeId -> CollectiveRole.OWNER
            admins.contains(meshManager.nodeId) -> CollectiveRole.ADMIN
            moderators.contains(meshManager.nodeId) -> CollectiveRole.MODERATOR
            else -> CollectiveRole.MEMBER
        }
        val canPost = if (!conversation.isBroadcastOnly) {
            true
        } else {
            role == CollectiveRole.OWNER ||
                role == CollectiveRole.ADMIN ||
                role == CollectiveRole.MODERATOR
        }
        val canModerate = role == CollectiveRole.OWNER ||
            role == CollectiveRole.ADMIN ||
            role == CollectiveRole.MODERATOR
        val canPin = role == CollectiveRole.OWNER ||
            role == CollectiveRole.ADMIN ||
            role == CollectiveRole.MODERATOR
        val canReact = if (canModerate) {
            true
        } else {
            conversation.allowMemberReactions
        }
        val canEditOwn = if (canModerate) {
            true
        } else if (conversation.isBroadcastOnly) {
            false
        } else {
            conversation.allowMemberEditOwnMessages
        }
        val canDeleteOwn = if (canModerate) {
            true
        } else {
            conversation.allowMemberDeleteOwnMessages
        }
        val canManageRoles = role == CollectiveRole.OWNER || role == CollectiveRole.ADMIN
        return ConversationPermissions(
            role = role,
            canPost = canPost,
            canModerate = canModerate,
            canPin = canPin,
            canReact = canReact,
            canEditOwn = canEditOwn,
            canDeleteOwn = canDeleteOwn,
            canManageRoles = canManageRoles,
            isAdmin = role == CollectiveRole.OWNER || role == CollectiveRole.ADMIN,
            isModerator = role == CollectiveRole.MODERATOR
        )
    }

    private fun isConversationPostingAllowed(conversation: ConversationSummary): Boolean {
        return resolveConversationPermissions(conversation).canPost
    }

    private fun canLocalActorEditOwnMessage(conversation: ConversationSummary): Boolean {
        return resolveConversationPermissions(conversation).canEditOwn
    }

    private fun canLocalActorDeleteOwnMessage(conversation: ConversationSummary): Boolean {
        return resolveConversationPermissions(conversation).canDeleteOwn
    }

    private fun canLocalActorReact(conversation: ConversationSummary): Boolean {
        return resolveConversationPermissions(conversation).canReact
    }

    private fun canLocalActorModerate(conversation: ConversationSummary): Boolean {
        return resolveConversationPermissions(conversation).canModerate
    }

    private fun canLocalActorPin(conversation: ConversationSummary): Boolean {
        return resolveConversationPermissions(conversation).canPin
    }

    private fun isLocalMessageOwner(message: ChatMessage): Boolean {
        return message.isLocal || message.originNodeId == meshManager.nodeId
    }

    private fun sendTextToConversation(
        state: MeshUiState,
        conversationId: String,
        text: String,
        replyToMessageId: String? = null,
        replyToPreview: String? = null,
        forwardedFromAlias: String? = null,
        forwardedFromMessageId: String? = null
    ): Boolean {
        val draft = text.trim()
        if (draft.isBlank()) return false
        val conversation = state.conversations.firstOrNull { it.id == conversationId } ?: return false
        if (!isConversationPostingAllowed(conversation)) {
            return false
        }
        if (isSavedMessagesConversation(conversation.id)) {
            return meshManager.saveLocalTextMessage(
                text = draft,
                conversationId = conversation.id,
                conversationTitle = conversation.title,
                replyToMessageId = replyToMessageId,
                replyToPreview = replyToPreview,
                forwardedFromAlias = forwardedFromAlias,
                forwardedFromMessageId = forwardedFromMessageId
            )
        }
        return when (conversation.type) {
            ConversationType.DIRECT -> {
                val targetNodeId = conversation.memberNodeIds.firstOrNull { it != meshManager.nodeId }
                    ?: return false
                meshManager.sendDirectMessage(
                    text = draft,
                    targetNodeId = targetNodeId,
                    conversationId = conversation.id,
                    conversationTitle = conversation.title,
                    replyToMessageId = replyToMessageId,
                    replyToPreview = replyToPreview,
                    forwardedFromAlias = forwardedFromAlias,
                    forwardedFromMessageId = forwardedFromMessageId
                )
            }

            ConversationType.GROUP -> {
                meshManager.sendGroupMessage(
                    text = draft,
                    groupId = conversation.id,
                    groupTitle = conversation.title,
                    memberNodeIds = conversation.memberNodeIds,
                    adminNodeIds = conversation.adminNodeIds,
                    moderatorNodeIds = conversation.moderatorNodeIds,
                    isBroadcastOnly = conversation.isBroadcastOnly,
                    allowMemberReactions = conversation.allowMemberReactions,
                    allowMemberEditOwnMessages = conversation.allowMemberEditOwnMessages,
                    allowMemberDeleteOwnMessages = conversation.allowMemberDeleteOwnMessages,
                    replyToMessageId = replyToMessageId,
                    replyToPreview = replyToPreview,
                    forwardedFromAlias = forwardedFromAlias,
                    forwardedFromMessageId = forwardedFromMessageId,
                    chatType = MeshMessagePayload.CHAT_TYPE_GROUP,
                    conversationType = ConversationType.GROUP
                ) > 0
            }

            ConversationType.CHANNEL -> {
                meshManager.sendGroupMessage(
                    text = draft,
                    groupId = conversation.id,
                    groupTitle = conversation.title,
                    memberNodeIds = conversation.memberNodeIds,
                    adminNodeIds = conversation.adminNodeIds,
                    moderatorNodeIds = conversation.moderatorNodeIds,
                    isBroadcastOnly = conversation.isBroadcastOnly,
                    allowMemberReactions = conversation.allowMemberReactions,
                    allowMemberEditOwnMessages = conversation.allowMemberEditOwnMessages,
                    allowMemberDeleteOwnMessages = conversation.allowMemberDeleteOwnMessages,
                    replyToMessageId = replyToMessageId,
                    replyToPreview = replyToPreview,
                    forwardedFromAlias = forwardedFromAlias,
                    forwardedFromMessageId = forwardedFromMessageId,
                    chatType = MeshMessagePayload.CHAT_TYPE_CHANNEL,
                    conversationType = ConversationType.CHANNEL
                ) > 0
            }
        }
    }

    private fun clearConversationDraft(conversationId: String) {
        val id = conversationId.trim()
        if (id.isBlank()) return
        upsertConversationState(id) { current ->
            if (current.draftText.isBlank()) {
                current
            } else {
                current.copy(
                    draftText = "",
                    updatedAtMs = System.currentTimeMillis()
                )
            }
        }
    }

    private fun applyUnreadCounters(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val activeConversationId = _selectedConversationId.value
        val isActiveChatOpen = _isConversationOpen.value && _selectedTab.value == MeshTab.CHATS

        var touched = false
        val now = System.currentTimeMillis()
        val updates = _conversationStates.value.toMutableMap()

        messages
            .filter { message ->
                !message.isLocal &&
                    !message.isDeleted &&
                    message.id.isNotBlank() &&
                    !seenIncomingMessageIds.contains(message.id)
            }
            .forEach { message ->
                seenIncomingMessageIds += message.id
                val conversationId = resolveConversationId(message, meshManager.nodeId)
                val isActiveConversation = isActiveChatOpen && activeConversationId == conversationId
                val current = updates[conversationId] ?: defaultConversationState(conversationId)
                val next = if (isActiveConversation) {
                    if (current.unreadCount == 0) {
                        current
                    } else {
                        current.copy(unreadCount = 0, updatedAtMs = now)
                    }
                } else {
                    current.copy(
                        unreadCount = (current.unreadCount + 1).coerceAtMost(MAX_UNREAD_COUNTER),
                        updatedAtMs = now
                    )
                }
                if (next != current) {
                    updates[conversationId] = next
                    touched = true
                } else if (!updates.containsKey(conversationId)) {
                    updates[conversationId] = next
                    touched = true
                }
            }

        while (seenIncomingMessageIds.size > MAX_TRACKED_INCOMING_MESSAGES) {
            val first = seenIncomingMessageIds.firstOrNull() ?: break
            seenIncomingMessageIds.remove(first)
        }

        if (touched) {
            _conversationStates.value = updates
            persistConversationStates(updates)
        }
    }

    private fun reconcileConversationStates(messages: List<ChatMessage>) {
        val current = _conversationStates.value
        var changed = false
        val updated = current.toMutableMap()
        val knownConversationIds = linkedSetOf<String>()
        _groups.value.forEach { knownConversationIds += it.id }
        messages.forEach { message ->
            knownConversationIds += resolveConversationId(message, meshManager.nodeId)
        }
        knownConversationIds.forEach { conversationId ->
            if (conversationId.isBlank()) return@forEach
            if (!updated.containsKey(conversationId)) {
                updated[conversationId] = defaultConversationState(conversationId)
                changed = true
            }
        }
        if (changed) {
            _conversationStates.value = updated
            persistConversationStates(updated)
        }
    }

    private fun upsertConversationState(
        conversationId: String,
        transform: (ConversationLocalState) -> ConversationLocalState
    ) {
        val id = conversationId.trim()
        if (id.isBlank()) return
        val currentMap = _conversationStates.value
        val current = currentMap[id] ?: defaultConversationState(id)
        val updated = transform(current).copy(conversationId = id)
        if (updated == current && currentMap.containsKey(id)) return
        val nextMap = currentMap.toMutableMap().apply { put(id, updated) }
        _conversationStates.value = nextMap
        persistConversationStates(nextMap)
    }

    private fun defaultConversationState(conversationId: String): ConversationLocalState {
        return ConversationLocalState(
            conversationId = conversationId,
            draftText = "",
            unreadCount = 0,
            isPinned = false,
            isMuted = false,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun persistConversationStates(statesMap: Map<String, ConversationLocalState>) {
        val snapshot = statesMap.values
            .filter { it.conversationId.isNotBlank() }
            .sortedByDescending { it.updatedAtMs }
            .take(MAX_CONVERSATION_STATES)
        localStore.persistConversationStates(snapshot)
    }

    private fun discoverGroupsFromMessages(messages: List<ChatMessage>) {
        val groupMessages = messages.filter {
            it.conversationType == ConversationType.GROUP || it.conversationType == ConversationType.CHANNEL
        }
        if (groupMessages.isEmpty()) return

        _groups.update { current ->
            var changed = false
            val byId = current.associateBy { it.id }.toMutableMap()

            groupMessages.forEach { message ->
                val groupId = message.conversationId.trim()
                if (groupId.isBlank() || groupId == ChatMessage.LEGACY_BROADCAST_CONVERSATION_ID) {
                    return@forEach
                }

                val existing = byId[groupId]
                val members = message.memberNodeIds
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .ifEmpty { existing?.memberNodeIds ?: listOf(meshManager.nodeId, message.originNodeId) }
                val type = if (message.conversationType == ConversationType.CHANNEL) {
                    ConversationType.CHANNEL
                } else {
                    existing?.type ?: ConversationType.GROUP
                }
                val title = message.conversationTitle?.trim()?.ifBlank { null }
                    ?: existing?.title
                    ?: if (type == ConversationType.CHANNEL) {
                        "Channel ${groupId.takeLast(4)}"
                    } else {
                        "Group ${groupId.takeLast(4)}"
                    }
                val ownerId = message.collectiveOwnerNodeId
                    ?.trim()
                    ?.ifBlank { null }
                    ?: existing?.createdByNodeId
                    ?: message.collectiveAdminNodeIds.firstOrNull { it.isNotBlank() }
                    ?: message.originNodeId
                val normalizedMembers = if (members.contains(ownerId)) {
                    members
                } else {
                    listOf(ownerId) + members
                }
                val adminsFromMessage = message.collectiveAdminNodeIds
                    .map { it.trim() }
                    .filter { it.isNotBlank() && normalizedMembers.contains(it) }
                    .distinct()
                val admins = if (adminsFromMessage.isNotEmpty()) {
                    val withOwner = if (adminsFromMessage.contains(ownerId)) {
                        adminsFromMessage
                    } else {
                        listOf(ownerId) + adminsFromMessage
                    }
                    withOwner.distinct()
                } else {
                    existing?.adminNodeIds
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() && normalizedMembers.contains(it) }
                        ?.let { raw ->
                            if (raw.contains(ownerId)) raw else listOf(ownerId) + raw
                        }
                        ?.distinct()
                        ?.ifEmpty { listOf(ownerId) }
                        ?: listOf(ownerId)
                }
                val moderatorsFromMessage = message.collectiveModeratorNodeIds
                    .map { it.trim() }
                    .filter {
                        it.isNotBlank() &&
                            normalizedMembers.contains(it) &&
                            !admins.contains(it)
                    }
                    .distinct()
                val moderators = if (moderatorsFromMessage.isNotEmpty()) {
                    moderatorsFromMessage
                } else {
                    existing?.moderatorNodeIds
                        .orEmpty()
                        .map { it.trim() }
                        .filter {
                            it.isNotBlank() &&
                                normalizedMembers.contains(it) &&
                                !admins.contains(it)
                        }
                        .distinct()
                }
                val isBroadcastOnly = if (type == ConversationType.CHANNEL) {
                    message.collectiveBroadcastOnly
                        ?: existing?.isBroadcastOnly
                        ?: true
                } else {
                    message.collectiveBroadcastOnly
                        ?: existing?.isBroadcastOnly
                        ?: false
                }
                val allowMemberReactions = message.collectiveAllowMemberReactions
                    ?: existing?.allowMemberReactions
                    ?: true
                val allowMemberEditOwnMessages = if (isBroadcastOnly) {
                    false
                } else {
                    message.collectiveAllowMemberEditOwnMessages
                        ?: existing?.allowMemberEditOwnMessages
                        ?: true
                }
                val allowMemberDeleteOwnMessages = if (isBroadcastOnly) {
                    false
                } else {
                    message.collectiveAllowMemberDeleteOwnMessages
                        ?: existing?.allowMemberDeleteOwnMessages
                        ?: true
                }

                if (existing == null) {
                    byId[groupId] = MeshGroup(
                        id = groupId,
                        title = title,
                        memberNodeIds = normalizedMembers,
                        createdAtMs = message.createdAtMs,
                        createdByNodeId = ownerId,
                        type = type,
                        adminNodeIds = admins,
                        moderatorNodeIds = moderators,
                        isBroadcastOnly = isBroadcastOnly,
                        allowMemberReactions = allowMemberReactions,
                        allowMemberEditOwnMessages = allowMemberEditOwnMessages,
                        allowMemberDeleteOwnMessages = allowMemberDeleteOwnMessages
                    )
                    changed = true
                } else if (
                    existing.title != title ||
                    existing.memberNodeIds != normalizedMembers ||
                    existing.createdByNodeId != ownerId ||
                    existing.type != type ||
                    existing.adminNodeIds != admins ||
                    existing.moderatorNodeIds != moderators ||
                    existing.isBroadcastOnly != isBroadcastOnly ||
                    existing.allowMemberReactions != allowMemberReactions ||
                    existing.allowMemberEditOwnMessages != allowMemberEditOwnMessages ||
                    existing.allowMemberDeleteOwnMessages != allowMemberDeleteOwnMessages
                ) {
                    byId[groupId] = existing.copy(
                        title = title,
                        memberNodeIds = normalizedMembers,
                        createdByNodeId = ownerId,
                        type = type,
                        adminNodeIds = admins,
                        moderatorNodeIds = moderators,
                        isBroadcastOnly = isBroadcastOnly,
                        allowMemberReactions = allowMemberReactions,
                        allowMemberEditOwnMessages = allowMemberEditOwnMessages,
                        allowMemberDeleteOwnMessages = allowMemberDeleteOwnMessages
                    )
                    changed = true
                }
            }

                if (!changed) {
                    current
                } else {
                    val updated = byId.values.sortedByDescending { it.createdAtMs }
                    localStore.persistGroups(updated)
                    updated
                }
            }
        }

    override fun onCleared() {
        super.onCleared()
    }

    companion object {
        private const val INVITE_PREFIX = "MESHINV1:"
        private const val SAVED_MESSAGES_TITLE = "Saved Messages"
        private const val MAX_GROUP_TITLE = 36
        private const val MAX_CHANNEL_TITLE = 42
        private const val MAX_DRAFT_LENGTH = 2_000
        private const val MAX_CONVERSATION_STATES = 1_200
        private const val MAX_UNREAD_COUNTER = 9_999
        private const val MAX_TRACKED_INCOMING_MESSAGES = 8_000
        private const val MAX_MEDIA_ALBUM_ITEMS = 10
    }

    private data class RelayState(
        val wifiLanActive: Boolean,
        val enabled: Boolean,
        val url: String,
        val connected: Boolean
    )

    private data class ConversationPermissions(
        val role: CollectiveRole,
        val canPost: Boolean,
        val canModerate: Boolean,
        val canPin: Boolean,
        val canReact: Boolean,
        val canEditOwn: Boolean,
        val canDeleteOwn: Boolean,
        val canManageRoles: Boolean,
        val isAdmin: Boolean,
        val isModerator: Boolean
    )

    private data class NetworkState(
        val alias: String,
        val fingerprint: String,
        val avatarData: String,
        val isRunning: Boolean,
        val status: String,
        val peers: List<Peer>,
        val wifiLanActive: Boolean = false,
        val relayEnabled: Boolean = false,
        val relayUrl: String = "",
        val relayConnected: Boolean = false,
        val knownIdentities: List<PeerIdentity> = emptyList(),
        val messages: List<ChatMessage> = emptyList(),
        val scheduledMessages: List<ScheduledMessageRecord> = emptyList(),
        val outgoingFileTransfers: List<OutgoingFileTransferProgress> = emptyList(),
        val incomingFileTransfers: List<IncomingFileTransferProgress> = emptyList()
    )

    private data class TransferQueueState(
        val scheduledMessages: List<ScheduledMessageRecord>,
        val outgoingFileTransfers: List<OutgoingFileTransferProgress>,
        val incomingFileTransfers: List<IncomingFileTransferProgress>
    )

    private data class UiCombineState(
        val network: NetworkState,
        val groups: List<MeshGroup>,
        val conversationStates: Map<String, ConversationLocalState>
    )
}
