package com.meshchat.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meshchat.app.mesh.ChatMessage
import com.meshchat.app.mesh.ChatContentType
import com.meshchat.app.mesh.ConversationSummary
import com.meshchat.app.mesh.ConversationType
import com.meshchat.app.mesh.IncomingFileTransferProgress
import com.meshchat.app.mesh.MeshContact
import com.meshchat.app.mesh.MeshForegroundService
import com.meshchat.app.mesh.MessageDeliveryState
import com.meshchat.app.mesh.MeshTab
import com.meshchat.app.mesh.MeshUiState
import com.meshchat.app.mesh.OutgoingFileTransferProgress
import com.meshchat.app.mesh.SecureLocalStore
import com.meshchat.app.mesh.ScheduledMessageRecord
import com.meshchat.app.mesh.isSavedMessagesConversation
import com.meshchat.app.release.MeshUpdateInstaller
import com.meshchat.app.release.MeshUpdateScheduler
import com.meshchat.app.ui.LiveMeshBackground
import com.meshchat.app.ui.MeshAmbientPalette
import com.meshchat.app.ui.MeshBackgroundStyle
import com.meshchat.app.ui.MeshRenderQuality
import com.meshchat.app.ui.MeshTheme
import com.meshchat.app.ui.RichMessageText
import com.meshchat.app.ui.ambientPaletteFromTheme
import com.meshchat.app.ui.rememberMeshRenderQuality
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.Locale
import java.util.UUID
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var externalConversationId: String? by mutableStateOf(null)
    private var externalSharePayload: ExternalSharePayload? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MeshUpdateScheduler.schedule(applicationContext)
        externalConversationId = extractConversationId(intent)
        externalSharePayload = extractSharePayload(intent)
        runCatching { pruneTransientDecryptedCaches(applicationContext) }
        setContent {
            val vm: MainViewModel = viewModel()
            MeshApp(
                viewModel = vm,
                externalConversationId = externalConversationId,
                externalSharePayload = externalSharePayload,
                onExternalConversationConsumed = { consumedId ->
                    if (externalConversationId == consumedId) {
                        externalConversationId = null
                    }
                },
                onExternalShareConsumed = { consumedToken ->
                    if (externalSharePayload?.token == consumedToken) {
                        externalSharePayload = null
                    }
                }
            )
        }
        MeshUpdateInstaller.openIfRequested(this, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalConversationId = extractConversationId(intent)
        externalSharePayload = extractSharePayload(intent)
        runCatching { pruneTransientDecryptedCaches(applicationContext) }
        MeshUpdateInstaller.openIfRequested(this, intent)
    }
}

private data class MeshVisualPalette(
    val actionBar: Color,
    val actionBarTitle: Color,
    val actionBarSubtitle: Color,
    val actionBarIcon: Color,
    val actionBarSelector: Color,
    val listBackground: Color,
    val listGradientEnd: Color,
    val card: Color,
    val searchField: Color,
    val rowText: Color,
    val rowAccent: Color,
    val rowBlue: Color,
    val rowMeta: Color,
    val divider: Color,
    val chatBackground: Color,
    val chatGradientEnd: Color,
    val bubbleIn: Color,
    val bubbleInSelected: Color,
    val bubbleOut: Color,
    val bubbleOutSelected: Color,
    val bubbleOutText: Color,
    val bubbleInText: Color,
    val messageLinkIn: Color,
    val messageLinkOut: Color,
    val composerSend: Color,
    val composerCursor: Color,
    val unreadPill: Color
)

private data class MeshThemeColors(
    val actionBar: Color,
    val backgroundStart: Color,
    val backgroundEnd: Color,
    val panel: Color,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val text: Color,
    val muted: Color,
    val bubbleIn: Color,
    val bubbleOut: Color
)

private enum class MeshVisualPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val backgroundStyle: MeshBackgroundStyle,
    private val colors: MeshThemeColors
) {
    STARFIELD(
        id = "starfield",
        title = "Starfield",
        subtitle = "Slow stars, parallax and quiet light",
        backgroundStyle = MeshBackgroundStyle.STARFIELD,
        colors = MeshThemeColors(
            actionBar = Color(0xE80A1020),
            backgroundStart = Color(0xFF050817),
            backgroundEnd = Color(0xFF120B2A),
            panel = Color(0xB8141C31),
            primary = Color(0xFFDE7CFF),
            secondary = Color(0xFF6DE9FF),
            tertiary = Color(0xFFF5D6FF),
            text = Color(0xFFF1F5FF),
            muted = Color(0xFF91A2BE),
            bubbleIn = Color(0xD2182337),
            bubbleOut = Color(0xD24B2E91)
        )
    ),
    NEBULA(
        id = "nebula",
        title = "Nebula",
        subtitle = "Deep space glow with a calm mesh",
        backgroundStyle = MeshBackgroundStyle.NEBULA,
        colors = MeshThemeColors(
            actionBar = Color(0xE8120A1F),
            backgroundStart = Color(0xFF080611),
            backgroundEnd = Color(0xFF260E32),
            panel = Color(0xB81A1630),
            primary = Color(0xFFFF73DD),
            secondary = Color(0xFF69DFFF),
            tertiary = Color(0xFFB59CFF),
            text = Color(0xFFF9F0FF),
            muted = Color(0xFFA394B8),
            bubbleIn = Color(0xD21F1A37),
            bubbleOut = Color(0xD85C2A9A)
        )
    ),
    AURORA(
        id = "aurora",
        title = "Aurora",
        subtitle = "Flowing polar light and soft shimmer",
        backgroundStyle = MeshBackgroundStyle.AURORA,
        colors = MeshThemeColors(
            actionBar = Color(0xE8061820),
            backgroundStart = Color(0xFF03151C),
            backgroundEnd = Color(0xFF06243A),
            panel = Color(0xB8102631),
            primary = Color(0xFF48F1C4),
            secondary = Color(0xFF6ED7FF),
            tertiary = Color(0xFFB2FFCB),
            text = Color(0xFFE9FFFA),
            muted = Color(0xFF92B5B6),
            bubbleIn = Color(0xD1163034),
            bubbleOut = Color(0xD1278D82)
        )
    ),
    TIDAL(
        id = "tidal",
        title = "Tidal",
        subtitle = "Bioluminescent waves in deep water",
        backgroundStyle = MeshBackgroundStyle.TIDAL,
        colors = MeshThemeColors(
            actionBar = Color(0xE8051520),
            backgroundStart = Color(0xFF03101A),
            backgroundEnd = Color(0xFF06364A),
            panel = Color(0xB8102636),
            primary = Color(0xFF39E9FF),
            secondary = Color(0xFF29C7B5),
            tertiary = Color(0xFF9BEAFF),
            text = Color(0xFFEAFBFF),
            muted = Color(0xFF89ABB9),
            bubbleIn = Color(0xD1162D3A),
            bubbleOut = Color(0xD1248396)
        )
    ),
    EMBER(
        id = "ember",
        title = "Ember",
        subtitle = "Warm sparks drifting through the dark",
        backgroundStyle = MeshBackgroundStyle.EMBER,
        colors = MeshThemeColors(
            actionBar = Color(0xE8180C15),
            backgroundStart = Color(0xFF16090B),
            backgroundEnd = Color(0xFF32111B),
            panel = Color(0xB8281720),
            primary = Color(0xFFFFB35E),
            secondary = Color(0xFFFF6487),
            tertiary = Color(0xFFFFE1A2),
            text = Color(0xFFFFF3E8),
            muted = Color(0xFFBE9C9C),
            bubbleIn = Color(0xD2281A24),
            bubbleOut = Color(0xD1763159)
        )
    ),
    MOONLIT(
        id = "moonlit",
        title = "Moonlit night",
        subtitle = "A quiet moon, soft haze and distant stars",
        backgroundStyle = MeshBackgroundStyle.MOONLIT,
        colors = MeshThemeColors(
            actionBar = Color(0xE8071222),
            backgroundStart = Color(0xFF050A18),
            backgroundEnd = Color(0xFF122746),
            panel = Color(0xB8121E33),
            primary = Color(0xFFB8A7FF),
            secondary = Color(0xFF9DD9FF),
            tertiary = Color(0xFFFFF2C4),
            text = Color(0xFFF2F4FF),
            muted = Color(0xFF9BAFCA),
            bubbleIn = Color(0xD2182B43),
            bubbleOut = Color(0xD2473D8F)
        )
    ),
    RAIN_WINDOW(
        id = "rain-window",
        title = "Rainy window",
        subtitle = "Slow rain, reflections and city light",
        backgroundStyle = MeshBackgroundStyle.RAIN_WINDOW,
        colors = MeshThemeColors(
            actionBar = Color(0xE807171D),
            backgroundStart = Color(0xFF071419),
            backgroundEnd = Color(0xFF0E2730),
            panel = Color(0xB8132931),
            primary = Color(0xFF59E5D6),
            secondary = Color(0xFF85B7FF),
            tertiary = Color(0xFFD9F4FF),
            text = Color(0xFFEAFBFF),
            muted = Color(0xFF91B1BB),
            bubbleIn = Color(0xD1172C35),
            bubbleOut = Color(0xD12B7E91)
        )
    );

    fun palette(): MeshVisualPalette {
        val c = colors
        return MeshVisualPalette(
            actionBar = c.actionBar,
            actionBarTitle = c.text,
            actionBarSubtitle = c.muted,
            actionBarIcon = c.secondary,
            actionBarSelector = c.secondary.copy(alpha = 0.18f),
            listBackground = c.backgroundStart,
            listGradientEnd = c.backgroundEnd,
            card = c.panel,
            searchField = c.panel.copy(alpha = 0.82f),
            rowText = c.text,
            rowAccent = c.primary,
            rowBlue = c.secondary,
            rowMeta = c.muted,
            divider = c.secondary.copy(alpha = 0.20f),
            chatBackground = c.backgroundStart,
            chatGradientEnd = c.backgroundEnd,
            bubbleIn = c.bubbleIn,
            bubbleInSelected = c.bubbleIn.copy(alpha = 0.97f),
            bubbleOut = c.bubbleOut,
            bubbleOutSelected = c.primary,
            bubbleOutText = c.text,
            bubbleInText = c.text,
            messageLinkIn = c.secondary,
            messageLinkOut = c.text,
            composerSend = c.secondary,
            composerCursor = c.secondary,
            unreadPill = c.primary
        )
    }

    companion object {
        fun fromId(id: String?): MeshVisualPreset {
            return when (id) {
                "starfield", "sky", "neon" -> STARFIELD
                "nebula", "graphite", "forest" -> NEBULA
                "aurora" -> AURORA
                "tidal" -> TIDAL
                "ember", "sand" -> EMBER
                "moonlit", "moon" -> MOONLIT
                "rain-window", "rain" -> RAIN_WINDOW
                else -> STARFIELD
            }
        }
    }
}
private object MeshUi {
    var glow = Color(0xFF22F7EA)
    var glowAlt = Color(0xFFFF4FF0)

    fun applyPalette(palette: MeshAmbientPalette) {
        glow = palette.secondary
        glowAlt = palette.primary
    }
}

private object TgDayPalette {
    var actionBar = Color(0xFF10131B)
    var actionBarTitle = Color(0xFFEAFDFF)
    var actionBarSubtitle = Color(0xFF8AA0B7)
    var actionBarIcon = Color(0xFF1DF7EF)
    var actionBarSelector = Color(0x3328F4EA)
    var listBackground = Color(0xFF080B12)
    var listGradientEnd = Color(0xFF171022)
    var card = Color(0xCC1A1F2B)
    var searchField = Color(0xB0212633)
    var rowText = Color(0xFFE6F8FF)
    var rowAccent = Color(0xFFFF4FF0)
    var rowBlue = Color(0xFF22F7EA)
    var rowMeta = Color(0xFF91A0B3)
    var divider = Color(0x3322F7EA)
    var chatBackground = Color(0xFF090D14)
    var chatGradientEnd = Color(0xFF1A1024)
    var bubbleIn = Color(0xD01A202C)
    var bubbleInSelected = Color(0xE0253041)
    var bubbleOut = Color(0xEE7B2DFF)
    var bubbleOutSelected = Color(0xFFF04DFF)
    var bubbleOutText = Color(0xFFFFFFFF)
    var bubbleInText = Color(0xFFEAFDFF)
    var messageLinkIn = Color(0xFF22F7EA)
    var messageLinkOut = Color(0xFFFFFFFF)
    var composerSend = Color(0xFF22F7EA)
    var composerCursor = Color(0xFF22F7EA)
    var unreadPill = Color(0xFFFF4FF0)

    fun applyPreset(preset: MeshVisualPreset) {
        val palette = preset.palette()
        actionBar = palette.actionBar
        actionBarTitle = palette.actionBarTitle
        actionBarSubtitle = palette.actionBarSubtitle
        actionBarIcon = palette.actionBarIcon
        actionBarSelector = palette.actionBarSelector
        listBackground = palette.listBackground
        listGradientEnd = palette.listGradientEnd
        card = palette.card
        searchField = palette.searchField
        rowText = palette.rowText
        rowAccent = palette.rowAccent
        rowBlue = palette.rowBlue
        rowMeta = palette.rowMeta
        divider = palette.divider
        chatBackground = palette.chatBackground
        chatGradientEnd = palette.chatGradientEnd
        bubbleIn = palette.bubbleIn
        bubbleInSelected = palette.bubbleInSelected
        bubbleOut = palette.bubbleOut
        bubbleOutSelected = palette.bubbleOutSelected
        bubbleOutText = palette.bubbleOutText
        bubbleInText = palette.bubbleInText
        messageLinkIn = palette.messageLinkIn
        messageLinkOut = palette.messageLinkOut
        composerSend = palette.composerSend
        composerCursor = palette.composerCursor
        unreadPill = palette.unreadPill
    }
}

private const val BRAND_NAME = "MeshGram"
private const val PREFS_UI = "meshgram_ui_prefs"
private const val KEY_ONBOARDING_PENDING = "onboarding_pending"
private const val KEY_VISUAL_THEME = "visual_theme"
private const val PREFS_NOTIFICATIONS = "meshgram_notification_prefs"
private const val KEY_NOTIFICATION_SOUND = "sound"
private const val KEY_NOTIFICATION_VIBRATION = "vibration"
private const val PASSCODE_MIN_LEN = 4
private const val PASSCODE_MAX_LEN = 10
const val EXTRA_OPEN_CONVERSATION_ID = "com.meshchat.app.extra.OPEN_CONVERSATION_ID"

private enum class ProfileSettingsSection {
    NETWORK,
    SECURITY,
    NOTIFICATIONS,
    APPEARANCE,
    CONTACTS,
    DATA,
    BACKUP,
    SUPPORT,
    UPDATES
}

private data class MeshStrings(
    val chats: String,
    val map: String,
    val groups: String,
    val settings: String,
    val communities: String,
    val meshHub: String,
    val profileTab: String,
    val meshMapTitle: String,
    val nodesNearby: String,
    val activeRoutes: String,
    val fileQueue: String,
    val centerMap: String,
    val scanNetwork: String,
    val nodeList: String,
    val localNode: String,
    val node: String,
    val noNodesYet: String,
    val routesWakeWhenPeersAppear: String,
    val messagesStat: String,
    val groupsStat: String,
    val contacts: String,
    val security: String,
    val notifications: String,
    val networkSettings: String,
    val dataUsage: String,
    val search: String,
    val all: String,
    val unread: String,
    val channels: String,
    val archived: String,
    val pinned: String,
    val allChats: String,
    val savedMessages: String,
    val savedMessagesSubtitle: String,
    val saved: String,
    val muted: String,
    val group: String,
    val channel: String,
    val members: String,
    val subscribers: String,
    val adminOnly: String,
    val createChannel: String,
    val create: String,
    val groupName: String,
    val channelName: String,
    val noContactsAvailable: String,
    val broadcastAdminsOnly: String,
    val joinByCode: String,
    val scanInviteQr: String,
    val messages: String,
    val noMessagesFound: String,
    val noChatsYet: String,
    val noUnreadChats: String,
    val noGroupChats: String,
    val noChannels: String,
    val noCommunities: String,
    val archiveEmpty: String,
    val newChat: String,
    val createGroup: String,
    val startDirectGroupChannel: String,
    val close: String,
    val startChat: String,
    val noPeersDiscovered: String,
    val online: String,
    val offline: String,
    val meshOnline: String,
    val meshOn: String,
    val meshOff: String,
    val peers: String,
    val searchInChat: String,
    val draft: String,
    val selectedCount: (Int) -> String,
    val forward: String,
    val forwardWithCount: (Int) -> String,
    val forwardMessage: String,
    val forwardMessages: String,
    val noMessagesSelected: String,
    val noOtherChatsAvailable: String,
    val noChatsFound: String,
    val delete: String,
    val cancel: String,
    val scheduled: String,
    val photos: String,
    val videos: String,
    val voice: String,
    val files: String,
    val mediaAndFiles: String,
    val noMediaInChat: String,
    val noMediaInTab: (String) -> String,
    val fileMessage: String,
    val fileTransfers: String,
    val receivingFiles: String,
    val retry: String,
    val requestMissing: String,
    val stopRetriesHint: String,
    val chunksFrom: (Int, Int, String) -> String,
    val onlyAdminsCanPost: String,
    val messagePlaceholder: String,
    val startSecureConversation: String,
    val scheduleMessage: String,
    val scheduleHint: String,
    val delayMinutes: String,
    val sendAt: (String) -> String,
    val schedule: String,
    val oneHour: String,
    val oneDay: String,
    val messageActions: String,
    val reply: String,
    val copy: String,
    val edit: String,
    val playVoice: String,
    val openFile: String,
    val shareFile: String,
    val shareText: String,
    val select: String,
    val pin: String,
    val unpin: String,
    val addTags: String,
    val editTags: String,
    val quickReaction: String,
    val removeMyReaction: String,
    val openChat: String,
    val pinChat: String,
    val unpinChat: String,
    val muteChat: String,
    val unmuteChat: String,
    val archiveChat: String,
    val unarchiveChat: String,
    val copyInviteCode: String,
    val showInviteQr: String,
    val enableOpenPosting: String,
    val enableAdminOnlyPosting: String,
    val manageChannelAdmins: String,
    val manageGroupAdmins: String,
    val manageChannelMembers: String,
    val manageGroupMembers: String,
    val manageChannelModerators: String,
    val manageGroupModerators: String,
    val moderatorStatus: String,
    val markAsRead: String,
    val back: String,
    val media: String,
    val chatInfo: String,
    val meshOffline: String,
    val permissionsRequired: String,
    val grant: String,
    val welcomeTitle: String,
    val welcomeSubtitle: String,
    val welcomeTagline: String,
    val startRegistration: String,
    val haveAccount: String,
    val welcomeAddressTitle: String,
    val welcomeAddressSubtitle: String,
    val welcomePrivacyTitle: String,
    val welcomePrivacySubtitle: String,
    val welcomeMeshTitle: String,
    val welcomeMeshSubtitle: String,
    val start: String,
    val stop: String,
    val welcomePermissionHint: String,
    val unlockTitle: String,
    val unlockSubtitle: String,
    val pinCode: String,
    val locked: String,
    val unlock: String,
    val appearance: String,
    val appearanceSubtitle: String,
    val selected: String,
    val profile: String,
    val displayName: String,
    val changeAvatar: String,
    val save: String,
    val backup: String,
    val network: String,
    val sound: String,
    val vibration: String,
    val systemDefault: String,
    val silent: String,
    val vibrationOff: String,
    val vibrationSoft: String,
    val vibrationNormal: String,
    val vibrationStrong: String,
    val securityHint: String,
    val encryptionLabel: String,
    val fingerprint: String,
    val pinEnabled: String,
    val pinDisabled: String,
    val enablePin: String,
    val disablePin: String,
    val lockNow: String,
    val repeatPin: String,
    val confirm: String,
    val recipients: String,
    val noRecipients: String,
    val savedByNode: String,
    val backupDescription: String,
    val export: String,
    val importText: String,
    val storagePrivacy: String,
    val tempPreviews: String,
    val encryptedHistoryKept: String,
    val clearTempCache: String,
    val tempCacheEmpty: String,
    val clearedTempFiles: (Int) -> String,
    val bluetoothMeshNetwork: String,
    val offlineOnlyDescription: String,
    val discoveryDescription: String,
    val relayEnabledLabel: String,
    val relayEnabledHint: String,
    val relayUrlLabel: String,
    val relayUrlPlaceholder: String,
    val saveRelaySettings: String,
    val relayConnectedStatus: String,
    val relayWaitingStatus: String,
    val bleOnlyStatus: String,
    val relaySettingsSaved: String,
    val projectSupport: String,
    val projectSupportDescription: String,
    val updates: String,
    val updatesDescription: String,
    val linkNotConfigured: String,
    val openLink: String,
    val themeTitles: Map<MeshVisualPreset, Pair<String, String>>
)

@Composable
private fun rememberMeshStrings(): MeshStrings {
    val configuration = LocalConfiguration.current
    val language = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        configuration.locales[0]?.language
    } else {
        @Suppress("DEPRECATION")
        configuration.locale?.language
    } ?: Locale.getDefault().language
    return remember(language) {
        if (language.equals("ru", ignoreCase = true)) {
            ruMeshStrings()
        } else {
            enMeshStrings()
        }
    }
}

private fun enMeshStrings() = MeshStrings(
    chats = "Chats",
    map = "Map",
    groups = "Groups",
    settings = "Settings",
    communities = "Communities",
    meshHub = "Mesh Hub",
    profileTab = "Profile",
    meshMapTitle = "Live Mesh Map",
    nodesNearby = "Nodes nearby",
    activeRoutes = "Active routes",
    fileQueue = "File queue",
    centerMap = "Center map",
    scanNetwork = "Scan network",
    nodeList = "Node list",
    localNode = "Local node",
    node = "Node",
    noNodesYet = "No mesh nodes nearby yet",
    routesWakeWhenPeersAppear = "Routes wake up automatically when MeshGram peers appear.",
    messagesStat = "Messages",
    groupsStat = "Groups",
    contacts = "Contacts",
    security = "Security",
    notifications = "Notifications",
    networkSettings = "Network settings",
    dataUsage = "Data usage",
    search = "Search",
    all = "All",
    unread = "Unread",
    channels = "Channels",
    archived = "Archived",
    pinned = "Pinned",
    allChats = "All Chats",
    savedMessages = "Saved Messages",
    savedMessagesSubtitle = "Private encrypted notes and files",
    saved = "saved",
    muted = "muted",
    group = "group",
    channel = "channel",
    members = "members",
    subscribers = "subscribers",
    adminOnly = "admin-only",
    createChannel = "Create Channel",
    create = "Create",
    groupName = "Group name",
    channelName = "Channel name",
    noContactsAvailable = "No contacts available",
    broadcastAdminsOnly = "Broadcast mode: only admins can post",
    joinByCode = "Join by Code",
    scanInviteQr = "Scan Invite QR",
    messages = "Messages",
    noMessagesFound = "No messages found",
    noChatsYet = "No chats yet",
    noUnreadChats = "No unread chats",
    noGroupChats = "No group chats yet",
    noChannels = "No channels yet",
    noCommunities = "No groups or channels yet. Create your first community.",
    archiveEmpty = "Archive is empty",
    newChat = "New chat",
    createGroup = "Create group",
    startDirectGroupChannel = "Start direct chat, group, or channel",
    close = "Close",
    startChat = "Start chat",
    noPeersDiscovered = "No peers discovered yet",
    online = "online",
    offline = "offline",
    meshOnline = "Mesh online",
    meshOn = "Mesh on",
    meshOff = "Mesh off",
    peers = "peers",
    searchInChat = "Search in chat",
    draft = "Draft",
    selectedCount = { count -> "$count selected" },
    forward = "Forward",
    forwardWithCount = { count -> "Forward ($count)" },
    forwardMessage = "Forward message",
    forwardMessages = "Forward messages",
    noMessagesSelected = "No messages selected",
    noOtherChatsAvailable = "No other chats available",
    noChatsFound = "No chats found",
    delete = "Delete",
    cancel = "Cancel",
    scheduled = "Scheduled",
    photos = "Photos",
    videos = "Videos",
    voice = "Voice",
    files = "Files",
    mediaAndFiles = "Media & files",
    noMediaInChat = "No media in this chat yet",
    noMediaInTab = { tab -> "No ${tab.lowercase(Locale.ROOT)} yet" },
    fileMessage = "File message",
    fileTransfers = "File transfers",
    receivingFiles = "Receiving files",
    retry = "Retry",
    requestMissing = "Request missing",
    stopRetriesHint = "Stop disables future retries; already delivered chunks remain encrypted on recipients.",
    chunksFrom = { received, total, sender -> "$received/$total chunks from $sender" },
    onlyAdminsCanPost = "Only admins/moderators can post",
    messagePlaceholder = "Message",
    startSecureConversation = "Start secure conversation",
    scheduleMessage = "Schedule message",
    scheduleHint = "The encrypted message will enter the mesh queue at the selected time.",
    delayMinutes = "Delay in minutes",
    sendAt = { time -> "Send at $time" },
    schedule = "Schedule",
    oneHour = "1 hour",
    oneDay = "1 day",
    messageActions = "Message actions",
    reply = "Reply",
    copy = "Copy",
    edit = "Edit",
    playVoice = "Play voice",
    openFile = "Open file",
    shareFile = "Share file",
    shareText = "Share text",
    select = "Select",
    pin = "Pin",
    unpin = "Unpin",
    addTags = "Add tags",
    editTags = "Edit tags",
    quickReaction = "Quick reaction",
    removeMyReaction = "Remove my reaction",
    openChat = "Open chat",
    pinChat = "Pin chat",
    unpinChat = "Unpin chat",
    muteChat = "Mute chat",
    unmuteChat = "Unmute chat",
    archiveChat = "Archive chat",
    unarchiveChat = "Unarchive chat",
    copyInviteCode = "Copy invite code",
    showInviteQr = "Show invite QR",
    enableOpenPosting = "Enable open posting",
    enableAdminOnlyPosting = "Enable admin-only posting",
    manageChannelAdmins = "Manage channel admins",
    manageGroupAdmins = "Manage group admins",
    manageChannelMembers = "Manage channel members",
    manageGroupMembers = "Manage group members",
    manageChannelModerators = "Manage channel moderators",
    manageGroupModerators = "Manage group moderators",
    moderatorStatus = "Moderator: posting and moderation enabled",
    markAsRead = "Mark as read",
    back = "Back",
    media = "Media",
    chatInfo = "Chat info",
    meshOffline = "mesh offline",
    permissionsRequired = "Bluetooth permissions are required",
    grant = "Grant",
    welcomeTitle = "Welcome to $BRAND_NAME",
    welcomeSubtitle = "A next-generation mesh messenger without a central server.",
    welcomeTagline = "Your future world of connection.",
    startRegistration = "Start registration",
    haveAccount = "I already have an account",
    welcomeAddressTitle = "Precise delivery",
    welcomeAddressSubtitle = "Messages and files are tied to the recipient Node ID.",
    welcomePrivacyTitle = "Private by design",
    welcomePrivacySubtitle = "End-to-end encryption keeps chats readable only by participants.",
    welcomeMeshTitle = "Offline mesh",
    welcomeMeshSubtitle = "Data moves through nearby devices running MeshGram.",
    start = "Start",
    stop = "Stop",
    welcomePermissionHint = "Bluetooth permissions will be requested on first launch.",
    unlockTitle = "Unlock $BRAND_NAME",
    unlockSubtitle = "Enter your local PIN to open chats and media.",
    pinCode = "PIN code",
    locked = "Locked",
    unlock = "Unlock",
    appearance = "Appearance",
    appearanceSubtitle = "Change bubbles, background, buttons and list accents.",
    selected = "Selected",
    profile = "Your profile",
    displayName = "Display name",
    changeAvatar = "Change photo",
    save = "Save",
    backup = "Backup",
    network = "Network",
    sound = "Notification sound",
    vibration = "Vibration",
    systemDefault = "System default",
    silent = "Silent",
    vibrationOff = "Off",
    vibrationSoft = "Soft",
    vibrationNormal = "Normal",
    vibrationStrong = "Strong",
    securityHint = "Your identity and local app lock",
    encryptionLabel = "Encryption",
    fingerprint = "Fingerprint",
    pinEnabled = "PIN lock is enabled",
    pinDisabled = "PIN lock is disabled",
    enablePin = "Enable PIN",
    disablePin = "Disable PIN",
    lockNow = "Lock now",
    repeatPin = "Repeat PIN",
    confirm = "Confirm",
    recipients = "Known recipients",
    noRecipients = "No recipients yet",
    savedByNode = "Saved by Node ID. Delivery is addressed to the exact recipient.",
    backupDescription = "Encrypted export and import for device migration.",
    export = "Export",
    importText = "Import",
    storagePrivacy = "Storage and privacy",
    tempPreviews = "Temporary decrypted previews",
    encryptedHistoryKept = "Encrypted chat history and encrypted attachments are not removed by this action.",
    clearTempCache = "Clear temporary cache",
    tempCacheEmpty = "Temporary cache is already empty",
    clearedTempFiles = { count -> "Cleared $count temporary file(s)" },
    bluetoothMeshNetwork = "Bluetooth Mesh Network",
    offlineOnlyDescription = "BLE is preferred for nearby MeshGram clients. Internet relay is used only when no BLE route is available.",
    discoveryDescription = "Automatic routing: only MeshGram BLE clients are accepted; the app never changes network settings for other apps.",
    relayEnabledLabel = "Internet fallback",
    relayEnabledHint = "Use the relay only when no nearby BLE route is available",
    relayUrlLabel = "Relay server address",
    relayUrlPlaceholder = "wss://your-domain.example/ws",
    saveRelaySettings = "Save hybrid routing",
    relayConnectedStatus = "Relay connected",
    relayWaitingStatus = "Relay waiting for an internet route",
    bleOnlyStatus = "BLE only until a relay is configured",
    relaySettingsSaved = "Hybrid routing saved: BLE first, internet fallback",
    projectSupport = "Support the project",
    projectSupportDescription = "Use the official support link configured by the owner. The app never stores card details.",
    updates = "Updates",
    updatesDescription = "Updates are checked only from the configured HTTPS manifest and always require Android confirmation.",
    linkNotConfigured = "The official link is not configured yet.",
    openLink = "Open link",
    themeTitles = mapOf(
        MeshVisualPreset.STARFIELD to ("Starfield" to "Slow stars, parallax and quiet light"),
        MeshVisualPreset.NEBULA to ("Nebula" to "Deep space glow with a calm mesh"),
        MeshVisualPreset.AURORA to ("Aurora" to "Flowing polar light and soft shimmer"),
        MeshVisualPreset.TIDAL to ("Tidal" to "Bioluminescent waves in deep water"),
        MeshVisualPreset.EMBER to ("Ember" to "Warm sparks drifting through the dark"),
        MeshVisualPreset.MOONLIT to ("Moonlit night" to "A quiet moon, soft haze and distant stars"),
        MeshVisualPreset.RAIN_WINDOW to ("Rainy window" to "Slow rain, reflections and city light")
    )
)

private fun ruMeshStrings() = MeshStrings(
    chats = "Чаты",
    map = "Карта",
    groups = "Группы",
    settings = "Настройки",
    communities = "Сообщества",
    meshHub = "Меш-хаб",
    profileTab = "Профиль",
    meshMapTitle = "Живая карта Mesh-узлов",
    nodesNearby = "Узлы поблизости",
    activeRoutes = "Активные маршруты",
    fileQueue = "Очередь файлов",
    centerMap = "Центрировать карту",
    scanNetwork = "Сканировать сеть",
    nodeList = "Список узлов",
    localNode = "Локальный узел",
    node = "Узел",
    noNodesYet = "Поблизости пока нет Mesh-узлов",
    routesWakeWhenPeersAppear = "Маршруты появятся автоматически, когда рядом будут устройства MeshGram.",
    messagesStat = "Сообщения",
    groupsStat = "Группы",
    contacts = "Контакты",
    security = "Безопасность",
    notifications = "Уведомления",
    networkSettings = "Настройки сети",
    dataUsage = "Использование данных",
    search = "Поиск",
    all = "Все",
    unread = "Новые",
    channels = "Каналы",
    archived = "Архив",
    pinned = "Закрепленные",
    allChats = "Все чаты",
    savedMessages = "Избранное",
    savedMessagesSubtitle = "Личные зашифрованные заметки и файлы",
    saved = "сохранено",
    muted = "без звука",
    group = "группа",
    channel = "канал",
    members = "участники",
    subscribers = "подписчики",
    adminOnly = "только админ",
    createChannel = "Создать канал",
    create = "Создать",
    groupName = "Название группы",
    channelName = "Название канала",
    noContactsAvailable = "Контактов пока нет",
    broadcastAdminsOnly = "Режим канала: писать могут только админы",
    joinByCode = "Войти по коду",
    scanInviteQr = "Сканировать QR",
    messages = "Сообщения",
    noMessagesFound = "Сообщения не найдены",
    noChatsYet = "Пока нет чатов",
    noUnreadChats = "Нет непрочитанных",
    noGroupChats = "Групп пока нет",
    noChannels = "Каналов пока нет",
    noCommunities = "Групп и каналов пока нет. Создайте первое сообщество.",
    archiveEmpty = "Архив пуст",
    newChat = "Новый чат",
    createGroup = "Создать группу",
    startDirectGroupChannel = "Начните личный чат, группу или канал",
    close = "Закрыть",
    startChat = "Начать чат",
    noPeersDiscovered = "Соседние устройства пока не найдены",
    online = "в сети",
    offline = "не в сети",
    meshOnline = "Mesh включен",
    meshOn = "Mesh включен",
    meshOff = "Mesh выключен",
    peers = "узлов",
    searchInChat = "Поиск в чате",
    draft = "Черновик",
    selectedCount = { count -> "Выбрано: $count" },
    forward = "Переслать",
    forwardWithCount = { count -> "Переслать ($count)" },
    forwardMessage = "Переслать сообщение",
    forwardMessages = "Переслать сообщения",
    noMessagesSelected = "Сообщения не выбраны",
    noOtherChatsAvailable = "Других чатов пока нет",
    noChatsFound = "Чаты не найдены",
    delete = "Удалить",
    cancel = "Отмена",
    scheduled = "Отложенные",
    photos = "Фото",
    videos = "Видео",
    voice = "Голосовые",
    files = "Файлы",
    mediaAndFiles = "Медиа и файлы",
    noMediaInChat = "В этом чате пока нет медиа",
    noMediaInTab = { tab -> "Вкладка \"$tab\" пока пуста" },
    fileMessage = "Файловое сообщение",
    fileTransfers = "Передача файлов",
    receivingFiles = "Получение файлов",
    retry = "Повторить",
    requestMissing = "Запросить недостающие",
    stopRetriesHint = "Остановка отключает новые попытки; уже доставленные части остаются зашифрованными у получателей.",
    chunksFrom = { received, total, sender -> "$received/$total частей от $sender" },
    onlyAdminsCanPost = "Писать могут только админы и модераторы",
    messagePlaceholder = "Сообщение",
    startSecureConversation = "Начните защищённый разговор",
    scheduleMessage = "Отложенное сообщение",
    scheduleHint = "Зашифрованное сообщение попадёт в mesh-очередь в выбранное время.",
    delayMinutes = "Задержка в минутах",
    sendAt = { time -> "Отправить: $time" },
    schedule = "Запланировать",
    oneHour = "1 час",
    oneDay = "1 день",
    messageActions = "Действия с сообщением",
    reply = "Ответить",
    copy = "Копировать",
    edit = "Изменить",
    playVoice = "Воспроизвести голосовое",
    openFile = "Открыть файл",
    shareFile = "Поделиться файлом",
    shareText = "Поделиться текстом",
    select = "Выбрать",
    pin = "Закрепить",
    unpin = "Открепить",
    addTags = "Добавить теги",
    editTags = "Изменить теги",
    quickReaction = "Быстрая реакция",
    removeMyReaction = "Убрать мою реакцию",
    openChat = "Открыть чат",
    pinChat = "Закрепить чат",
    unpinChat = "Открепить чат",
    muteChat = "Выключить звук",
    unmuteChat = "Включить звук",
    archiveChat = "В архив",
    unarchiveChat = "Вернуть из архива",
    copyInviteCode = "Копировать код приглашения",
    showInviteQr = "Показать QR приглашения",
    enableOpenPosting = "Разрешить сообщения всем",
    enableAdminOnlyPosting = "Писать могут только админы",
    manageChannelAdmins = "Админы канала",
    manageGroupAdmins = "Админы группы",
    manageChannelMembers = "Участники канала",
    manageGroupMembers = "Участники группы",
    manageChannelModerators = "Модераторы канала",
    manageGroupModerators = "Модераторы группы",
    moderatorStatus = "Модератор: публикация и модерация включены",
    markAsRead = "Отметить прочитанным",
    back = "Назад",
    media = "Медиа",
    chatInfo = "О чате",
    meshOffline = "mesh выключен",
    permissionsRequired = "Нужны разрешения Bluetooth",
    grant = "Разрешить",
    welcomeTitle = "Добро пожаловать в $BRAND_NAME",
    welcomeSubtitle = "Mesh-мессенджер нового поколения без центрального сервера.",
    welcomeTagline = "Твой мир связи будущего.",
    startRegistration = "Начать регистрацию",
    haveAccount = "У меня есть аккаунт",
    welcomeAddressTitle = "Точная доставка",
    welcomeAddressSubtitle = "Сообщения и файлы привязаны к Node ID получателя.",
    welcomePrivacyTitle = "Приватность по умолчанию",
    welcomePrivacySubtitle = "E2E-шифрование: читать чат могут только его участники.",
    welcomeMeshTitle = "Офлайн mesh",
    welcomeMeshSubtitle = "Данные проходят через соседние устройства с MeshGram.",
    start = "Начать",
    stop = "Остановить",
    welcomePermissionHint = "При первом запуске будут запрошены Bluetooth-разрешения.",
    unlockTitle = "Разблокировать $BRAND_NAME",
    unlockSubtitle = "Введите локальный PIN, чтобы открыть чаты и медиа.",
    pinCode = "PIN-код",
    locked = "Заблокировано",
    unlock = "Открыть",
    appearance = "Оформление",
    appearanceSubtitle = "Меняет пузыри, фон, кнопки и акценты списка.",
    selected = "Выбрано",
    profile = "Ваш профиль",
    displayName = "Имя в приложении",
    changeAvatar = "Изменить фото",
    save = "Сохранить",
    backup = "Резервная копия",
    network = "Сеть",
    sound = "Звук уведомлений",
    vibration = "Вибрация",
    systemDefault = "Системный звук",
    silent = "Без звука",
    vibrationOff = "Выключена",
    vibrationSoft = "Слабая",
    vibrationNormal = "Обычная",
    vibrationStrong = "Сильная",
    securityHint = "Ваш идентификатор и локальная защита приложения",
    encryptionLabel = "Шифрование",
    fingerprint = "Отпечаток ключа",
    pinEnabled = "PIN-защита включена",
    pinDisabled = "PIN-защита выключена",
    enablePin = "Включить PIN",
    disablePin = "Выключить PIN",
    lockNow = "Заблокировать",
    repeatPin = "Повторите PIN",
    confirm = "Подтвердить",
    recipients = "Известные получатели",
    noRecipients = "Получателей пока нет",
    savedByNode = "Сохраняются по Node ID. Доставка адресуется точному получателю.",
    backupDescription = "Зашифрованный экспорт и импорт для переноса на другое устройство.",
    export = "Экспорт",
    importText = "Импорт",
    storagePrivacy = "Память и приватность",
    tempPreviews = "Временные расшифрованные предпросмотры",
    encryptedHistoryKept = "Зашифрованная история и вложения этим действием не удаляются.",
    clearTempCache = "Очистить временный кэш",
    tempCacheEmpty = "Временный кэш уже пуст",
    clearedTempFiles = { count -> "Удалено временных файлов: $count" },
    bluetoothMeshNetwork = "Bluetooth Mesh-сеть",
    offlineOnlyDescription = "Для ближайших клиентов MeshGram используется BLE. Интернет-реле включается только если BLE-маршрута нет.",
    discoveryDescription = "Автомаршрутизация: принимаются только клиенты MeshGram; настройки сети телефона не меняются.",
    relayEnabledLabel = "Интернет как резерв",
    relayEnabledHint = "Реле используется только если рядом нет BLE-маршрута",
    relayUrlLabel = "Адрес relay-сервера",
    relayUrlPlaceholder = "wss://ваш-домен.example/ws",
    saveRelaySettings = "Сохранить гибридную маршрутизацию",
    relayConnectedStatus = "Relay подключён",
    relayWaitingStatus = "Relay ждёт доступный интернет-маршрут",
    bleOnlyStatus = "Только BLE, пока relay не настроен",
    relaySettingsSaved = "Гибридная маршрутизация сохранена: сначала BLE, затем интернет",
    projectSupport = "Поддержать проект",
    projectSupportDescription = "Используется официальная ссылка поддержки владельца. Данные карты приложение не хранит.",
    updates = "Обновления",
    updatesDescription = "Проверка идёт только по настроенному HTTPS-манифесту и всегда требует подтверждения Android.",
    linkNotConfigured = "Официальная ссылка пока не настроена.",
    openLink = "Открыть ссылку",
    themeTitles = mapOf(
        MeshVisualPreset.STARFIELD to ("Звёздное небо" to "Тихие звёзды, параллакс и мягкий свет"),
        MeshVisualPreset.NEBULA to ("Туманность" to "Глубокое космическое свечение и спокойная Mesh-сеть"),
        MeshVisualPreset.AURORA to ("Аврора" to "Плавные полярные ленты и мягкое мерцание"),
        MeshVisualPreset.TIDAL to ("Прилив" to "Биолюминесцентные волны в глубокой воде"),
        MeshVisualPreset.EMBER to ("Угли" to "Тёплые искры, медленно плывущие в темноте"),
        MeshVisualPreset.MOONLIT to ("Лунная ночь" to "Тихая луна, мягкая дымка и далёкие звёзды"),
        MeshVisualPreset.RAIN_WINDOW to ("Дождь за стеклом" to "Плавный дождь, отражения и свет города")
    )
)

private data class ExternalSharePayload(
    val token: String,
    val text: String? = null,
    val uri: Uri? = null
)

@Composable
private fun MeshApp(
    viewModel: MainViewModel,
    externalConversationId: String?,
    externalSharePayload: ExternalSharePayload?,
    onExternalConversationConsumed: (String) -> Unit,
    onExternalShareConsumed: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissions = remember { requiredPermissions() }
    var permissionsGranted by remember {
        mutableStateOf(hasAllPermissions(context, permissions))
    }
    val uiPrefs = remember {
        context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
    }
    var onboardingPending by rememberSaveable {
        mutableStateOf(uiPrefs.getBoolean(KEY_ONBOARDING_PENDING, true))
    }
    var visualThemeId by rememberSaveable {
        mutableStateOf(uiPrefs.getString(KEY_VISUAL_THEME, MeshVisualPreset.STARFIELD.id) ?: MeshVisualPreset.STARFIELD.id)
    }
    val visualThemePreset = remember(visualThemeId) {
        MeshVisualPreset.fromId(visualThemeId)
    }
    val ambientPalette = remember(visualThemePreset) {
        val palette = visualThemePreset.palette()
        ambientPaletteFromTheme(
            backgroundStart = palette.listBackground,
            backgroundEnd = palette.listGradientEnd,
            primary = palette.rowAccent,
            secondary = palette.rowBlue,
            tertiary = palette.bubbleOut,
            style = visualThemePreset.backgroundStyle
        )
    }
    val renderQuality = rememberMeshRenderQuality()
    TgDayPalette.applyPreset(visualThemePreset)
    MeshUi.applyPalette(ambientPalette)
    val appPasscodeManager = remember {
        AppPasscodeManager(context.applicationContext)
    }
    var appLockEnabled by rememberSaveable {
        mutableStateOf(appPasscodeManager.isLockEnabled())
    }
    var hasAppPasscode by rememberSaveable {
        mutableStateOf(appPasscodeManager.hasConfiguredPasscode())
    }
    var appUnlocked by rememberSaveable {
        mutableStateOf(!(appLockEnabled && hasAppPasscode))
    }
    var appUnlockError by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var appLockoutRemainingMs by rememberSaveable {
        mutableStateOf(appPasscodeManager.lockoutRemainingMs())
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, appLockEnabled, hasAppPasscode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && appLockEnabled && hasAppPasscode) {
                appUnlocked = false
                appUnlockError = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val unlockAppWithPin: (String) -> Unit = { draftPin ->
        val result = appPasscodeManager.verifyPasscode(draftPin)
        appLockoutRemainingMs = result.lockoutRemainingMs
        if (result.success) {
            appUnlocked = true
            appUnlockError = null
        } else if (result.lockoutRemainingMs > 0L) {
            appUnlockError = "Too many attempts. Try again in ${formatLockoutDuration(result.lockoutRemainingMs)}"
        } else {
            appUnlockError = "Wrong PIN code. Attempts left: ${result.attemptsLeft.coerceAtLeast(0)}"
        }
    }
    val enableAppLockWithPin: (String) -> Boolean = { draftPin ->
        val enabled = appPasscodeManager.enableAppLockWithPasscode(draftPin)
        if (enabled) {
            appLockEnabled = true
            hasAppPasscode = true
            appUnlocked = true
            appUnlockError = null
        }
        enabled
    }
    val disableAppLockWithPin: (String) -> Boolean = { draftPin ->
        val disabled = appPasscodeManager.disableAppLockWithPasscode(draftPin)
        if (disabled) {
            appLockEnabled = false
            hasAppPasscode = false
            appUnlocked = true
            appUnlockError = null
        }
        disabled
    }
    val changeAppLockPin: (String, String) -> Boolean = { currentPin, newPin ->
        appPasscodeManager.changePasscode(currentPin, newPin)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = permissions.all { permission ->
            result[permission] == true || ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    val avatarPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val encoded = uri?.let { encodeAvatarThumbnail(context, it) }
        if (!encoded.isNullOrBlank()) {
            viewModel.updateAvatarData(encoded)
        }
    }

    LaunchedEffect(onboardingPending) {
        if (!onboardingPending && !permissionsGranted) {
            permissionLauncher.launch(permissions)
        }
    }
    LaunchedEffect(appLockEnabled, hasAppPasscode) {
        if (!appLockEnabled || !hasAppPasscode) {
            appUnlocked = true
            appUnlockError = null
            appLockoutRemainingMs = 0L
        } else {
            appLockoutRemainingMs = appPasscodeManager.lockoutRemainingMs()
        }
    }
    LaunchedEffect(appUnlocked, appLockEnabled, hasAppPasscode, appLockoutRemainingMs) {
        if (!appLockEnabled || !hasAppPasscode || appUnlocked) return@LaunchedEffect
        if (appLockoutRemainingMs <= 0L) return@LaunchedEffect
        while (true) {
            val remaining = appPasscodeManager.lockoutRemainingMs()
            appLockoutRemainingMs = remaining
            if (remaining <= 0L) {
                appUnlockError = null
                break
            }
            delay(1000)
        }
    }

    LaunchedEffect(permissionsGranted, onboardingPending) {
        if (onboardingPending) {
            if (uiState.isRunning) viewModel.stopMesh()
            return@LaunchedEffect
        }
        if (permissionsGranted) {
            if (!uiState.isRunning) viewModel.startMesh()
        } else {
            viewModel.stopMesh()
        }
    }
    LaunchedEffect(
        externalConversationId,
        onboardingPending,
        permissionsGranted,
        appLockEnabled,
        hasAppPasscode,
        appUnlocked
    ) {
        val conversationId = externalConversationId?.trim().orEmpty()
        if (conversationId.isBlank()) return@LaunchedEffect
        if (onboardingPending || !permissionsGranted) return@LaunchedEffect
        if (appLockEnabled && hasAppPasscode && !appUnlocked) return@LaunchedEffect
        viewModel.openConversation(conversationId)
        onExternalConversationConsumed(conversationId)
    }

    MeshTheme {
        if (onboardingPending) {
            WelcomeScreen(
                onContinue = {
                    uiPrefs.edit().putBoolean(KEY_ONBOARDING_PENDING, false).apply()
                    onboardingPending = false
                }
            )
        } else if (appLockEnabled && hasAppPasscode && !appUnlocked) {
            AppPasscodeLockScreen(
                errorMessage = appUnlockError,
                lockoutRemainingMs = appLockoutRemainingMs,
                onUnlock = unlockAppWithPin
            )
        } else {
            MeshTelegramScreen(
                uiState = uiState,
                permissionsGranted = permissionsGranted,
                onRequestPermissions = { permissionLauncher.launch(permissions) },
                onToggleMesh = {
                    if (uiState.isRunning) viewModel.stopMesh() else viewModel.startMesh()
                },
                onSelectTab = viewModel::selectTab,
                onOpenConversation = viewModel::openConversation,
                onCloseConversation = viewModel::closeConversation,
                onOpenDirect = viewModel::openDirectChat,
                onUpdateDraft = viewModel::updateDraftForActiveConversation,
                onPinConversation = viewModel::pinConversation,
                onMuteConversation = viewModel::muteConversation,
                onArchiveConversation = viewModel::archiveConversation,
                onMarkConversationRead = viewModel::markConversationRead,
                onSend = { text, replyToId, replyPreview ->
                    viewModel.sendToActiveConversation(
                        text = text,
                        replyToMessageId = replyToId,
                        replyToPreview = replyPreview
                    )
                },
                onEditMessage = viewModel::editMessageInActiveConversation,
                onDeleteMessage = viewModel::deleteMessageInActiveConversation,
                onReactMessage = viewModel::reactToMessageInActiveConversation,
                onPinMessage = viewModel::pinMessageInActiveConversation,
                onUpdateSavedMessageTags = viewModel::updateSavedMessageTags,
                onScheduleMessage = viewModel::scheduleMessageInActiveConversation,
                onCancelScheduledMessage = viewModel::cancelScheduledMessage,
                onCancelFileTransfer = viewModel::cancelOutgoingFileTransfer,
                onRetryFileTransfer = viewModel::retryOutgoingFileTransfer,
                onRetryIncomingFileTransfer = viewModel::retryIncomingFileTransfer,
                onCancelIncomingFileTransfer = viewModel::cancelIncomingFileTransfer,
                onForwardMessage = viewModel::forwardMessageToConversations,
                onSendFile = viewModel::sendFileToActiveConversation,
                onSendMediaAlbum = viewModel::sendMediaAlbumToActiveConversation,
                onSendTextToConversation = viewModel::sendTextToConversationById,
                onSendFileToConversation = viewModel::sendFileToConversationById,
                 onExportBackup = viewModel::exportPortableBackup,
                 onImportBackup = viewModel::importPortableBackup,
                 onSaveAlias = viewModel::updateAlias,
                 onPickAvatar = { avatarPickerLauncher.launch("image/*") },
                 onSaveRelaySettings = viewModel::updateRelaySettings,
                appLockEnabled = appLockEnabled,
                hasAppPasscode = hasAppPasscode,
                onEnableAppLock = enableAppLockWithPin,
                onDisableAppLock = disableAppLockWithPin,
                onChangeAppLockPin = changeAppLockPin,
                onLockNow = {
                    if (appLockEnabled && hasAppPasscode) {
                        appUnlocked = false
                        appUnlockError = null
                    }
                },
                visualThemePreset = visualThemePreset,
                onVisualThemeChange = { preset ->
                    uiPrefs.edit().putString(KEY_VISUAL_THEME, preset.id).apply()
                    visualThemeId = preset.id
                },
                ambientPalette = ambientPalette,
                renderQuality = renderQuality,
                onCreateGroup = viewModel::createGroup,
                onCreateChannel = viewModel::createChannel,
                onGenerateInviteCode = viewModel::generateInviteCode,
                onJoinByInviteCode = viewModel::joinCollectiveByInviteCode,
                onSetChannelBroadcastMode = viewModel::setChannelBroadcastMode,
                onUpdateChannelAdmins = viewModel::updateChannelAdmins,
                onUpdateCollectiveMembers = viewModel::updateCollectiveMembers,
                onUpdateCollectiveModerators = viewModel::updateCollectiveModerators,
                onUpdateCollectiveMemberPermissions = viewModel::updateCollectiveMemberPermissions,
                externalSharePayload = externalSharePayload,
                onExternalShareConsumed = onExternalShareConsumed
            )
        }
    }
}

@Composable
private fun AppPasscodeLockScreen(
    errorMessage: String?,
    lockoutRemainingMs: Long,
    onUnlock: (String) -> Unit
) {
    val strings = rememberMeshStrings()
    var pin by rememberSaveable { mutableStateOf("") }
    val locked = lockoutRemainingMs > 0L
    val canUnlock = !locked && pin.length in PASSCODE_MIN_LEN..PASSCODE_MAX_LEN
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(TgDayPalette.listBackground, TgDayPalette.chatGradientEnd)
                )
            )
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = TgDayPalette.card)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = strings.unlockTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = TgDayPalette.actionBarTitle
                )
                Text(
                    text = strings.unlockSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TgDayPalette.rowMeta
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { next ->
                        pin = next
                            .filter { it.isDigit() }
                            .take(PASSCODE_MAX_LEN)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(strings.pinCode) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword
                    ),
                    enabled = !locked
                )
                if (locked) {
                    Text(
                        text = "${strings.locked}: ${formatLockoutDuration(lockoutRemainingMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowMeta
                    )
                }
                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFC62828)
                    )
                }
                FilledTonalButton(
                    onClick = {
                        onUnlock(pin)
                        pin = ""
                    },
                    enabled = canUnlock
                ) {
                    Text(if (locked) strings.locked else strings.unlock)
                }
            }
        }
    }
}

@Composable
private fun WelcomeScreen(onContinue: () -> Unit) {
    val strings = rememberMeshStrings()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF05070D), Color(0xFF171022))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Center
        ) {
            MeshBrandHeader(
                modifier = Modifier.padding(bottom = 28.dp),
                subtitle = strings.welcomeSubtitle
            )
            Text(
                text = strings.welcomeTitle.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = TgDayPalette.actionBarTitle
            )
            Text(
                text = strings.welcomeTagline,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                color = MeshUi.glowAlt,
                modifier = Modifier.padding(top = 6.dp, bottom = 22.dp)
            )
            NeonGlassCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WelcomeFeatureRow(
                        icon = Icons.Rounded.ChatBubble,
                        title = strings.welcomeAddressTitle,
                        subtitle = strings.welcomeAddressSubtitle
                    )
                    WelcomeFeatureRow(
                        icon = Icons.Rounded.Lock,
                        title = strings.welcomePrivacyTitle,
                        subtitle = strings.welcomePrivacySubtitle
                    )
                    WelcomeFeatureRow(
                        icon = Icons.Rounded.Group,
                        title = strings.welcomeMeshTitle,
                        subtitle = strings.welcomeMeshSubtitle
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            GlowButton(
                text = strings.startRegistration,
                modifier = Modifier.fillMaxWidth(),
                onClick = onContinue
            )
            Spacer(modifier = Modifier.height(10.dp))
            GlowButton(
                text = strings.haveAccount,
                modifier = Modifier.fillMaxWidth(),
                glow = MeshUi.glowAlt,
                onClick = onContinue
            )
            Text(
                text = strings.welcomePermissionHint,
                style = MaterialTheme.typography.labelMedium,
                color = TgDayPalette.rowMeta,
                modifier = Modifier.padding(top = 14.dp)
            )
        }
    }
}

@Composable
private fun WelcomeFeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TgDayPalette.rowBlue,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TgDayPalette.actionBarTitle
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TgDayPalette.rowMeta
            )
        }
    }
}

@Composable
private fun NeonGlassCard(
    modifier: Modifier = Modifier,
    glow: Color = MeshUi.glow,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.border(
            width = 1.dp,
            color = glow.copy(alpha = 0.35f),
            shape = RoundedCornerShape(22.dp)
        ),
        shape = RoundedCornerShape(22.dp),
        color = TgDayPalette.card,
        shadowElevation = 8.dp
    ) {
        content()
    }
}

@Composable
private fun GlowButton(
    text: String,
    modifier: Modifier = Modifier,
    glow: Color = MeshUi.glow,
    onClick: () -> Unit
) {
    Button(
        modifier = modifier
            .heightIn(min = 52.dp)
            .border(1.dp, glow.copy(alpha = 0.82f), RoundedCornerShape(18.dp)),
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = glow.copy(alpha = 0.28f),
            contentColor = Color.White
        )
    ) {
        Text(text)
    }
}

@Composable
private fun MeshBrandHeader(
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MeshLogoMark(size = 38.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = BRAND_NAME,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = TgDayPalette.actionBarTitle
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = TgDayPalette.actionBarSubtitle
                )
            }
        }
        if (trailingContent != null) {
            trailingContent()
        }
    }
}

@Composable
private fun MeshLogoMark(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val radius = this.size.minDimension * 0.34f
        val points = (0 until 6).map { index ->
            val angle = -1.5708f + index * 1.0472f
            Offset(
                center.x + cos(angle) * radius,
                center.y + sin(angle) * radius
            )
        }
        drawCircle(MeshUi.glow.copy(alpha = 0.18f), this.size.minDimension * 0.48f, center)
        points.forEachIndexed { index, point ->
            drawLine(
                color = if (index % 2 == 0) MeshUi.glow else MeshUi.glowAlt,
                start = center,
                end = point,
                strokeWidth = 2.4f
            )
            drawCircle(
                color = if (index % 2 == 0) MeshUi.glow else MeshUi.glowAlt,
                radius = 3.6f,
                center = point
            )
        }
        drawCircle(Color.Transparent, radius, center, style = Stroke(width = 1.4f))
        drawCircle(MeshUi.glow, 4.8f, center)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MeshTelegramScreen(
    uiState: MeshUiState,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleMesh: () -> Unit,
    onSelectTab: (MeshTab) -> Unit,
    onOpenConversation: (String) -> Unit,
    onCloseConversation: () -> Unit,
    onOpenDirect: (String) -> Unit,
    onUpdateDraft: (String) -> Unit,
    onPinConversation: (String, Boolean) -> Unit,
    onMuteConversation: (String, Boolean) -> Unit,
    onArchiveConversation: (String, Boolean) -> Unit,
    onMarkConversationRead: (String) -> Unit,
    onSend: (String, String?, String?) -> Boolean,
    onEditMessage: (String, String) -> Boolean,
    onDeleteMessage: (String) -> Boolean,
    onReactMessage: (String, String) -> Boolean,
    onPinMessage: (String, Boolean) -> Boolean,
    onUpdateSavedMessageTags: (String, List<String>) -> Boolean,
    onScheduleMessage: (String, Long, String?, String?) -> Boolean,
    onCancelScheduledMessage: (String) -> Boolean,
    onCancelFileTransfer: (String) -> Boolean,
    onRetryFileTransfer: (String) -> Boolean,
    onRetryIncomingFileTransfer: (String) -> Boolean,
    onCancelIncomingFileTransfer: (String) -> Boolean,
    onForwardMessage: (String, List<String>) -> Int,
    onSendFile: (Uri) -> Boolean,
    onSendMediaAlbum: (List<Uri>, String) -> Int,
    onSendTextToConversation: (String, String) -> Boolean,
    onSendFileToConversation: (String, Uri) -> Boolean,
    onExportBackup: (Uri, String) -> Boolean,
    onImportBackup: (Uri, String) -> Boolean,
    onSaveAlias: (String) -> Unit,
    onPickAvatar: () -> Unit,
    onSaveRelaySettings: (Boolean, String) -> Unit,
    appLockEnabled: Boolean,
    hasAppPasscode: Boolean,
    onEnableAppLock: (String) -> Boolean,
    onDisableAppLock: (String) -> Boolean,
    onChangeAppLockPin: (String, String) -> Boolean,
    onLockNow: () -> Unit,
    visualThemePreset: MeshVisualPreset,
    onVisualThemeChange: (MeshVisualPreset) -> Unit,
    ambientPalette: MeshAmbientPalette,
    renderQuality: MeshRenderQuality,
    onCreateGroup: (String, List<String>) -> Boolean,
    onCreateChannel: (String, List<String>) -> Boolean,
    onGenerateInviteCode: (String) -> String?,
    onJoinByInviteCode: (String) -> Boolean,
    onSetChannelBroadcastMode: (String, Boolean) -> Boolean,
    onUpdateChannelAdmins: (String, List<String>) -> Boolean,
    onUpdateCollectiveMembers: (String, List<String>) -> Boolean,
    onUpdateCollectiveModerators: (String, List<String>) -> Boolean,
    onUpdateCollectiveMemberPermissions: (String, Boolean, Boolean, Boolean) -> Boolean,
    externalSharePayload: ExternalSharePayload?,
    onExternalShareConsumed: (String) -> Unit
) {
    val localContext = LocalContext.current
    val strings = rememberMeshStrings()
    val notificationPrefs = remember {
        localContext.getSharedPreferences(PREFS_NOTIFICATIONS, Context.MODE_PRIVATE)
    }
    var notificationSound by rememberSaveable {
        mutableStateOf(notificationPrefs.getString(KEY_NOTIFICATION_SOUND, "default") ?: "default")
    }
    var vibrationLevel by rememberSaveable {
        mutableStateOf(notificationPrefs.getString(KEY_NOTIFICATION_VIBRATION, "normal") ?: "normal")
    }
    var aliasDraft by rememberSaveable(uiState.nodeAlias) { mutableStateOf(uiState.nodeAlias) }
    var profileSettingsSectionId by rememberSaveable { mutableStateOf<String?>(null) }
    val profileSettingsSection = profileSettingsSectionId?.let {
        runCatching { ProfileSettingsSection.valueOf(it) }.getOrNull()
    }
    var messageDraft by rememberSaveable(uiState.activeConversationId) {
        mutableStateOf(uiState.activeDraft)
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var chatSearchQuery by rememberSaveable(uiState.activeConversationId) { mutableStateOf("") }
    var chatSearchOpen by rememberSaveable(uiState.activeConversationId) {
        mutableStateOf(chatSearchQuery.isNotBlank())
    }
    var showDirectDialog by remember { mutableStateOf(false) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var showChannelDialog by remember { mutableStateOf(false) }
    var showJoinInviteDialog by remember { mutableStateOf(false) }
    var inviteCodeDraft by rememberSaveable { mutableStateOf("") }
    var inviteQrCode by remember { mutableStateOf<String?>(null) }
    var inviteStatusMessage by remember { mutableStateOf<String?>(null) }
    var showMediaGallery by rememberSaveable(uiState.activeConversationId) { mutableStateOf(false) }
    var showChatInfo by rememberSaveable(uiState.activeConversationId) { mutableStateOf(false) }
    var backupPassphrase by rememberSaveable { mutableStateOf("") }
    var pendingBackupUri by remember { mutableStateOf<Uri?>(null) }
    var pendingBackupMode by remember { mutableStateOf<BackupMode?>(null) }
    var showBackupPassDialog by remember { mutableStateOf(false) }
    var backupStatusMessage by remember { mutableStateOf<String?>(null) }
    var relayStatusMessage by remember { mutableStateOf<String?>(null) }
    var relayEnabledDraft by rememberSaveable { mutableStateOf(uiState.relayEnabled) }
    var relayUrlDraft by rememberSaveable { mutableStateOf(uiState.relayUrl) }
    var replyToMessageId by rememberSaveable(uiState.activeConversationId) { mutableStateOf<String?>(null) }
    var editingMessageId by rememberSaveable(uiState.activeConversationId) { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var tagActionMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var savedTagFilter by rememberSaveable(uiState.activeConversationId) { mutableStateOf<String?>(null) }
    var conversationAction by remember { mutableStateOf<ConversationSummary?>(null) }
    var manageAdminsConversation by remember { mutableStateOf<ConversationSummary?>(null) }
    var manageMembersConversation by remember { mutableStateOf<ConversationSummary?>(null) }
    var manageModeratorsConversation by remember { mutableStateOf<ConversationSummary?>(null) }
    var forwardSourceMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var attachmentPreviewMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var shareToMeshPayload by remember { mutableStateOf<ExternalSharePayload?>(null) }
    var selectedMessageIds by rememberSaveable(uiState.activeConversationId) {
        mutableStateOf(emptySet<String>())
    }
    var showForwardSelectedDialog by rememberSaveable(uiState.activeConversationId) {
        mutableStateOf(false)
    }
    var activeVoiceRecording by remember { mutableStateOf<ActiveVoiceRecording?>(null) }
    var voiceAmplitudeSamples by remember { mutableStateOf<List<Float>>(emptyList()) }
    var pendingVideoCapture by remember { mutableStateOf<PendingVideoCapture?>(null) }
    var pendingVideoPreview by remember { mutableStateOf<PendingVideoCapture?>(null) }
    var pendingMediaUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingMediaCaption by rememberSaveable { mutableStateOf("") }
    var voiceTickerMs by remember { mutableStateOf(System.currentTimeMillis()) }

    val replyToMessage = remember(uiState.activeMessages, replyToMessageId) {
        val id = replyToMessageId ?: return@remember null
        uiState.activeMessages.firstOrNull { it.id == id }
    }
    val editingMessage = remember(uiState.activeMessages, editingMessageId) {
        val id = editingMessageId ?: return@remember null
        uiState.activeMessages.firstOrNull { it.id == id }
    }
    val pinnedMessage = remember(uiState.activeMessages) {
        uiState.activeMessages
            .filter { !it.isDeleted && it.pinnedAtMs != null }
            .maxByOrNull { it.pinnedAtMs ?: 0L }
    }
    val availableSavedTags = remember(uiState.activeMessages) {
        uiState.activeMessages
            .asSequence()
            .filterNot { message -> message.isDeleted }
            .flatMap { message -> message.savedTags.asSequence() }
            .distinctBy { tag -> tag.lowercase(Locale.ROOT) }
            .sortedBy { tag -> tag.lowercase(Locale.ROOT) }
            .toList()
    }
    LaunchedEffect(availableSavedTags, savedTagFilter) {
        val selectedTag = savedTagFilter ?: return@LaunchedEffect
        if (availableSavedTags.none { tag -> tag.equals(selectedTag, ignoreCase = true) }) {
            savedTagFilter = null
        }
    }
    val filteredMessages = remember(uiState.activeMessages, chatSearchQuery, savedTagFilter) {
        val query = chatSearchQuery.trim().lowercase()
        uiState.activeMessages.filter { message ->
            val matchesTag = savedTagFilter == null || message.savedTags.any { tag ->
                tag.equals(savedTagFilter, ignoreCase = true)
            }
            val matchesQuery = query.isBlank() ||
                message.text.lowercase().contains(query) ||
                (message.replyToPreview?.lowercase()?.contains(query) == true) ||
                (message.forwardedFromAlias?.lowercase()?.contains(query) == true) ||
                (message.attachment?.fileName?.lowercase()?.contains(query) == true) ||
                message.savedTags.any { tag -> tag.lowercase().contains(query) }
            matchesTag && matchesQuery
        }
    }
    val selectedMessages = remember(uiState.activeMessages, selectedMessageIds) {
        uiState.activeMessages
            .filter { selectedMessageIds.contains(it.id) }
            .sortedBy { it.createdAtMs }
    }
    val isMessageSelectionMode = selectedMessageIds.isNotEmpty()
    val mediaMessages = remember(uiState.activeMessages) {
        uiState.activeMessages
            .filter { message ->
                !message.isDeleted &&
                    message.contentType == ChatContentType.FILE &&
                    !message.attachment?.localUri.isNullOrBlank()
            }
            .sortedByDescending { it.createdAtMs }
    }
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            retainReadPermission(localContext, uri)
            onSendFile(uri)
        }
    }
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.distinct().forEach { uri ->
            retainReadPermission(localContext, uri)
        }
        pendingMediaUris = uris.distinct().take(MAX_MEDIA_ALBUM_ITEMS)
        if (pendingMediaUris.isNotEmpty()) {
            pendingMediaCaption = messageDraft.take(MAX_MEDIA_CAPTION_LENGTH)
        }
    }
    val videoNoteCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val capture = pendingVideoCapture
        pendingVideoCapture = null
        val captured = result.resultCode == Activity.RESULT_OK &&
            capture != null &&
            capture.file.exists() &&
            capture.file.length() > 0L
        if (captured && capture != null) {
            pendingVideoPreview?.file?.let { previous ->
                if (previous.exists()) runCatching { previous.delete() }
            }
            pendingVideoPreview = capture
            inviteStatusMessage = if (capture.file.length() <= MAX_VIDEO_NOTE_BYTES) {
                "Video note ready for preview"
            } else {
                "Video note is too large; retake a shorter clip"
            }
        } else {
            inviteStatusMessage = "Video note canceled"
            capture?.file?.let { file ->
                if (file.exists()) runCatching { file.delete() }
            }
        }
    }
    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            pendingBackupUri = uri
            pendingBackupMode = BackupMode.EXPORT
            backupPassphrase = ""
            showBackupPassDialog = true
        }
    }
    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingBackupUri = uri
            pendingBackupMode = BackupMode.IMPORT
            backupPassphrase = ""
            showBackupPassDialog = true
        }
    }
    val qrInviteScanLauncher = rememberLauncherForActivityResult(
        ScanContract()
    ) { result ->
        val scanned = result.contents?.trim().orEmpty()
        if (scanned.isBlank()) {
            inviteStatusMessage = "QR scan canceled"
        } else {
            inviteCodeDraft = scanned.take(600)
            val joined = onJoinByInviteCode(scanned)
            inviteStatusMessage = if (joined) {
                "Invite QR applied, community joined"
            } else {
                "Scanned QR is not a valid Mesh invite"
            }
            if (joined) {
                showJoinInviteDialog = false
                inviteCodeDraft = ""
                onSelectTab(MeshTab.CHATS)
            } else {
                showJoinInviteDialog = true
            }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            qrInviteScanLauncher.launch(inviteQrScanOptions())
        } else {
            inviteStatusMessage = "Camera permission denied"
        }
    }
    val startInviteQrScan = {
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            localContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission) {
            qrInviteScanLauncher.launch(inviteQrScanOptions())
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    val startVideoNoteCaptureNow: () -> Unit = {
        val capture = createVideoNoteCapture(localContext)
        if (capture == null) {
            inviteStatusMessage = "Video note camera unavailable"
        } else {
            pendingVideoCapture = capture
            val captureIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, capture.uri)
                putExtra(MediaStore.EXTRA_DURATION_LIMIT, MAX_VIDEO_NOTE_DURATION_SECONDS)
                putExtra(MediaStore.EXTRA_SIZE_LIMIT, MAX_VIDEO_NOTE_BYTES)
                putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
                clipData = ClipData.newRawUri("meshgram_video_note", capture.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            runCatching { videoNoteCaptureLauncher.launch(captureIntent) }
                .onFailure {
                    pendingVideoCapture = null
                    runCatching { capture.file.delete() }
                    inviteStatusMessage = "Video note camera unavailable"
                }
        }
    }
    val videoCameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVideoNoteCaptureNow()
        } else {
            inviteStatusMessage = "Camera permission denied"
        }
    }
    val startVideoNoteCapture: () -> Unit = {
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            localContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission) {
            startVideoNoteCaptureNow()
        } else {
            videoCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    val beginVoiceCapture: () -> Unit = beginCapture@{
        if (activeVoiceRecording != null) return@beginCapture
        val started = startVoiceCapture(localContext)
        if (started != null) {
            activeVoiceRecording = started
            voiceAmplitudeSamples = emptyList()
            voiceTickerMs = System.currentTimeMillis()
            inviteStatusMessage = "Voice recording started"
        } else {
            inviteStatusMessage = "Failed to start voice recording"
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginVoiceCapture()
        } else {
            inviteStatusMessage = "Microphone permission denied"
        }
    }
    val toggleVoiceRecording = {
        if (activeVoiceRecording == null) {
            val hasAudioPermission = ContextCompat.checkSelfPermission(
                localContext,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasAudioPermission) {
                beginVoiceCapture()
            } else {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            val current = activeVoiceRecording
            activeVoiceRecording = null
            voiceAmplitudeSamples = emptyList()
            if (current != null) {
                val recordedFile = finishVoiceCapture(current, keepFile = true)
                if (recordedFile != null) {
                    val sent = onSendFile(Uri.fromFile(recordedFile))
                    if (sent) {
                        runCatching { recordedFile.delete() }
                        inviteStatusMessage = "Voice message sent"
                    } else {
                        inviteStatusMessage = "Voice message send failed"
                    }
                } else {
                    inviteStatusMessage = "Voice recording canceled"
                }
            }
        }
    }
    val cancelVoiceRecording = {
        val current = activeVoiceRecording
        activeVoiceRecording = null
        voiceAmplitudeSamples = emptyList()
        if (current != null) {
            finishVoiceCapture(current, keepFile = false)
            inviteStatusMessage = "Voice recording canceled"
        }
    }
    val pauseOrResumeVoiceRecording = pauseResume@{
        val current = activeVoiceRecording ?: return@pauseResume
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            inviteStatusMessage = "Pause requires Android 7 or newer"
            return@pauseResume
        }
        val now = System.currentTimeMillis()
        if (current.pausedAtMs == null) {
            val paused = runCatching {
                current.recorder.pause()
                true
            }.getOrDefault(false)
            if (paused) {
                activeVoiceRecording = current.copy(pausedAtMs = now)
                inviteStatusMessage = "Voice recording paused"
            } else {
                inviteStatusMessage = "Failed to pause voice recording"
            }
        } else {
            val resumed = runCatching {
                current.recorder.resume()
                true
            }.getOrDefault(false)
            if (resumed) {
                activeVoiceRecording = current.copy(
                    accumulatedPausedMs = current.accumulatedPausedMs +
                        (now - current.pausedAtMs).coerceAtLeast(0L),
                    pausedAtMs = null
                )
                voiceTickerMs = now
                inviteStatusMessage = "Voice recording resumed"
            } else {
                inviteStatusMessage = "Failed to resume voice recording"
            }
        }
    }
    val voiceRecordingElapsedMs = remember(activeVoiceRecording, voiceTickerMs) {
        val recording = activeVoiceRecording ?: return@remember 0L
        calculateVoiceRecordingElapsedMs(
            startedAtMs = recording.startedAtMs,
            accumulatedPausedMs = recording.accumulatedPausedMs,
            pausedAtMs = recording.pausedAtMs,
            nowMs = voiceTickerMs
        )
    }
    LaunchedEffect(activeVoiceRecording?.startedAtMs) {
        if (activeVoiceRecording == null) return@LaunchedEffect
        while (activeVoiceRecording != null) {
            delay(120)
            voiceTickerMs = System.currentTimeMillis()
            val recording = activeVoiceRecording
            val recorder = recording?.recorder
            val amplitude = if (recorder == null || recording.pausedAtMs != null) {
                0f
            } else {
                runCatching { recorder.maxAmplitude / 32767f }
                    .getOrDefault(0f)
                    .coerceIn(0f, 1f)
            }
            voiceAmplitudeSamples = (voiceAmplitudeSamples + amplitude).takeLast(36)
        }
    }
    DisposableEffect(uiState.activeConversationId) {
        onDispose {
            val current = activeVoiceRecording
            if (current != null) {
                finishVoiceCapture(current, keepFile = false)
                activeVoiceRecording = null
            }
            voiceAmplitudeSamples = emptyList()
            pendingVideoCapture?.file?.let { file ->
                if (file.exists()) runCatching { file.delete() }
            }
            pendingVideoCapture = null
            pendingVideoPreview?.file?.let { file ->
                if (file.exists()) runCatching { file.delete() }
            }
            pendingVideoPreview = null
        }
    }

    val inChat = uiState.selectedTab == MeshTab.CHATS &&
        uiState.isConversationOpen &&
        !uiState.activeConversationId.isNullOrBlank()
    val activeConversation = remember(uiState.conversations, uiState.activeConversationId) {
        val activeId = uiState.activeConversationId
        if (activeId.isNullOrBlank()) {
            null
        } else {
            uiState.conversations.firstOrNull { it.id == activeId }
        }
    }
    val filteredConversations = remember(uiState.conversations, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isBlank()) {
            uiState.conversations
        } else {
            uiState.conversations.filter { conversation ->
                conversation.title.lowercase().contains(query) ||
                    conversation.lastMessagePreview.lowercase().contains(query) ||
                conversation.draftText.lowercase().contains(query)
            }
        }
    }
    val globalMessageHits = remember(uiState.messages, uiState.conversations, searchQuery, uiState.nodeAlias) {
        val query = searchQuery.trim().lowercase()
        if (query.isBlank()) {
            emptyList()
        } else {
            val conversationTitles = uiState.conversations.associate { it.id to it.title }
            val selfAlias = uiState.nodeAlias.trim().ifBlank { "You" }
            uiState.messages
                .asSequence()
                .filterNot { it.isDeleted }
                .filter { message ->
                    val text = message.text.lowercase()
                    val sender = message.senderAlias?.lowercase().orEmpty()
                    val fileName = message.attachment?.fileName?.lowercase().orEmpty()
                    text.contains(query) ||
                        sender.contains(query) ||
                        fileName.contains(query) ||
                        message.savedTags.any { tag -> tag.lowercase().contains(query) }
                }
                .sortedByDescending { it.createdAtMs }
                .take(60)
                .map { message ->
                    val title = conversationTitles[message.conversationId]
                        ?: message.conversationTitle
                        ?: when (message.conversationType) {
                            ConversationType.GROUP -> "Group"
                            ConversationType.CHANNEL -> "Channel"
                            ConversationType.DIRECT -> "Direct chat"
                        }
                    val senderLabel = if (message.isLocal) {
                        selfAlias
                    } else {
                        message.senderAlias?.trim()?.ifBlank { "Unknown" } ?: "Unknown"
                    }
                    val previewText = when (message.contentType) {
                        ChatContentType.FILE -> {
                            val fileNameLabel = message.attachment?.fileName
                                ?.trim()
                                ?.ifBlank { null }
                                ?: message.text.trim().ifBlank { "file" }
                            "File: $fileNameLabel"
                        }
                        ChatContentType.TEXT -> {
                            message.text
                                .trim()
                                .replace('\n', ' ')
                                .ifBlank { "Message" }
                        }
                    }
                    GlobalSearchHit(
                        key = "${message.id}:${message.createdAtMs}",
                        conversationId = message.conversationId,
                        conversationTitle = title,
                        senderLabel = senderLabel,
                        preview = previewText.take(120),
                        timestamp = message.createdAtMs
                    )
                }
                .toList()
        }
    }
    val totalUnreadCount = remember(uiState.conversations) {
        uiState.conversations.sumOf { it.unreadCount }.coerceAtMost(9_999)
    }
    LaunchedEffect(replyToMessageId, replyToMessage) {
        if (replyToMessageId != null && replyToMessage == null) {
            replyToMessageId = null
        }
    }
    LaunchedEffect(editingMessageId, editingMessage) {
        if (editingMessageId != null && editingMessage == null) {
            editingMessageId = null
        }
    }
    LaunchedEffect(uiState.relayEnabled) {
        relayEnabledDraft = uiState.relayEnabled
    }
    LaunchedEffect(uiState.relayUrl) {
        if (relayUrlDraft.isBlank() || relayUrlDraft == uiState.relayUrl) {
            relayUrlDraft = uiState.relayUrl
        }
    }
    LaunchedEffect(uiState.activeConversationId, uiState.activeDraft, editingMessageId) {
        if (editingMessageId == null) {
            messageDraft = uiState.activeDraft
        }
    }
    LaunchedEffect(uiState.activeMessages, selectedMessageIds) {
        if (selectedMessageIds.isEmpty()) return@LaunchedEffect
        val available = uiState.activeMessages.map { it.id }.toSet()
        val normalized = selectedMessageIds.filter { available.contains(it) }.toSet()
        if (normalized != selectedMessageIds) {
            selectedMessageIds = normalized
        }
    }
    LaunchedEffect(uiState.activeConversationId) {
        selectedMessageIds = emptySet()
        showForwardSelectedDialog = false
    }
    LaunchedEffect(externalSharePayload?.token) {
        val payload = externalSharePayload ?: return@LaunchedEffect
        shareToMeshPayload = payload
        onExternalShareConsumed(payload.token)
    }
    LaunchedEffect(
        uiState.activeConversationId,
        uiState.isConversationOpen,
        uiState.selectedTab,
        uiState.activeMessages.size
    ) {
        val activeId = uiState.activeConversationId
        if (uiState.selectedTab == MeshTab.CHATS &&
            uiState.isConversationOpen &&
            !activeId.isNullOrBlank()
        ) {
            onMarkConversationRead(activeId)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (inChat) {
                ChatTopBar(
                    title = uiState.activeConversationTitle,
                    subtitle = uiState.activeConversationSubtitle,
                    mediaCount = mediaMessages.size,
                    onBack = onCloseConversation,
                    onOpenMedia = { showMediaGallery = true },
                    onOpenInfo = { showChatInfo = true },
                    onOpenSearch = { chatSearchOpen = true }
                )
            }
        },
        bottomBar = {
            if (!inChat) {
                BottomTabs(
                    selectedTab = uiState.selectedTab,
                    unreadChatsCount = totalUnreadCount,
                    onSelectTab = onSelectTab
                )
            }
        },
        floatingActionButton = {
            if (!inChat) {
                when (uiState.selectedTab) {
                    MeshTab.MAP -> Unit

                    MeshTab.CHATS -> {
                        FloatingActionButton(onClick = { showDirectDialog = true }) {
                            Icon(Icons.Rounded.Edit, contentDescription = strings.newChat)
                        }
                    }

                    MeshTab.GROUPS -> {
                        FloatingActionButton(onClick = { showGroupDialog = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = strings.createGroup)
                        }
                    }

                    MeshTab.PROFILE -> Unit

                    MeshTab.SETTINGS -> Unit
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!inChat) {
                LiveMeshBackground(
                    modifier = Modifier.fillMaxSize(),
                    palette = ambientPalette,
                    quality = renderQuality
                )
            }
            Column(modifier = Modifier.fillMaxSize()) {
                if (!permissionsGranted) {
                    PermissionBanner(onRequestPermissions = onRequestPermissions)
                }

                when (uiState.selectedTab) {
                    MeshTab.MAP -> {
                        MeshMapHome(
                            uiState = uiState
                        )
                    }

                    MeshTab.CHATS -> {
                        if (inChat) {
                            ChatThread(
                                uiState = uiState,
                                messages = filteredMessages,
                                messageDraft = messageDraft,
                                searchQuery = chatSearchQuery,
                                searchOpen = chatSearchOpen,
                                pinnedMessage = pinnedMessage,
                                replyToMessage = replyToMessage,
                                editingMessage = editingMessage,
                                selectedMessageIds = selectedMessageIds,
                                scheduledMessages = uiState.activeScheduledMessages,
                                fileTransfers = uiState.activeFileTransfers,
                                incomingFileTransfers = uiState.activeIncomingFileTransfers,
                                savedTags = availableSavedTags,
                                selectedSavedTag = savedTagFilter,
                                onDraftChange = { next ->
                                    val clipped = next.take(2000)
                                    messageDraft = clipped
                                    onUpdateDraft(clipped)
                                },
                                onSearchQueryChange = { chatSearchQuery = it },
                                onCloseSearch = {
                                    chatSearchOpen = false
                                    chatSearchQuery = ""
                                },
                                onSavedTagSelected = { tag -> savedTagFilter = tag },
                                onCancelScheduledMessage = onCancelScheduledMessage,
                                onCancelFileTransfer = onCancelFileTransfer,
                                onRetryFileTransfer = onRetryFileTransfer,
                                onRetryIncomingFileTransfer = onRetryIncomingFileTransfer,
                                onCancelIncomingFileTransfer = onCancelIncomingFileTransfer,
                                onCancelReply = { replyToMessageId = null },
                                onCancelEditing = {
                                    editingMessageId = null
                                    messageDraft = uiState.activeDraft
                                },
                                onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
                                onPickPhoto = { mediaPickerLauncher.launch(arrayOf("image/*")) },
                                onPickVideo = { mediaPickerLauncher.launch(arrayOf("video/*")) },
                                onPickAudio = { filePickerLauncher.launch(arrayOf("audio/*")) },
                                onRecordVideoNote = startVideoNoteCapture,
                                onOpenAttachment = { message -> attachmentPreviewMessage = message },
                                onMessageLongPress = { message ->
                                    if (isMessageSelectionMode) {
                                        selectedMessageIds = if (selectedMessageIds.contains(message.id)) {
                                            selectedMessageIds - message.id
                                        } else {
                                            selectedMessageIds + message.id
                                        }
                                    } else {
                                        actionMessage = message
                                    }
                                },
                                onToggleMessageSelection = { messageId ->
                                    selectedMessageIds = if (selectedMessageIds.contains(messageId)) {
                                        selectedMessageIds - messageId
                                    } else {
                                        selectedMessageIds + messageId
                                    }
                                },
                                onClearMessageSelection = {
                                    selectedMessageIds = emptySet()
                                    showForwardSelectedDialog = false
                                },
                                onDeleteSelectedMessages = {
                                    val ids = selectedMessageIds.toList()
                                    var deletedCount = 0
                                    ids.forEach { messageId ->
                                        if (onDeleteMessage(messageId)) {
                                            deletedCount++
                                        }
                                    }
                                    inviteStatusMessage = if (deletedCount > 0) {
                                        if (deletedCount == 1) {
                                            "Deleted 1 message"
                                        } else {
                                            "Deleted $deletedCount messages"
                                        }
                                    } else {
                                        "Failed to delete selected messages"
                                    }
                                    selectedMessageIds = emptySet()
                                    showForwardSelectedDialog = false
                                },
                                onForwardSelectedMessages = {
                                    if (selectedMessageIds.isNotEmpty()) {
                                        showForwardSelectedDialog = true
                                    }
                                },
                                isVoiceRecording = activeVoiceRecording != null,
                                isVoiceRecordingPaused = activeVoiceRecording?.pausedAtMs != null,
                                canPauseVoiceRecording = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N,
                                voiceRecordingElapsedMs = voiceRecordingElapsedMs,
                                voiceAmplitudeSamples = voiceAmplitudeSamples,
                                onToggleVoiceRecording = toggleVoiceRecording,
                                onPauseResumeVoiceRecording = pauseOrResumeVoiceRecording,
                                onCancelVoiceRecording = cancelVoiceRecording,
                                onSchedule = { scheduledAtMs ->
                                    val text = messageDraft.trim()
                                    if (text.isBlank() || editingMessageId != null) {
                                        false
                                    } else {
                                        val scheduled = onScheduleMessage(
                                            text,
                                            scheduledAtMs,
                                            replyToMessageId,
                                            replyToMessage?.let { replyPreview(it) }
                                        )
                                        if (scheduled) {
                                            replyToMessageId = null
                                            messageDraft = ""
                                            onUpdateDraft("")
                                        }
                                        scheduled
                                    }
                                },
                                onSend = {
                                    val text = messageDraft.trim()
                                    if (text.isNotBlank()) {
                                        val editedId = editingMessageId
                                        if (!editedId.isNullOrBlank()) {
                                            val ok = onEditMessage(editedId, text)
                                            if (ok) {
                                                editingMessageId = null
                                                messageDraft = ""
                                                onUpdateDraft("")
                                            }
                                        } else {
                                            val replyPreview = replyToMessage?.let { replyPreview(it) }
                                            val ok = onSend(text, replyToMessageId, replyPreview)
                                            if (ok) {
                                                replyToMessageId = null
                                                messageDraft = ""
                                                onUpdateDraft("")
                                            }
                                        }
                                    }
                                },
                                onToggleMesh = onToggleMesh
                            )
                        } else {
                            ChatsHome(
                                searchQuery = searchQuery,
                                onSearchQueryChange = { searchQuery = it },
                                conversations = filteredConversations,
                                globalMessageHits = globalMessageHits,
                                onOpenConversation = onOpenConversation,
                                onConversationLongPress = { conversationAction = it },
                                onOpenDirectDialog = { showDirectDialog = true },
                                onOpenGroupDialog = { showGroupDialog = true },
                                onOpenChannelDialog = { showChannelDialog = true }
                            )
                        }
                    }

                    MeshTab.GROUPS -> {
                        GroupsHome(
                            uiState = uiState,
                            onOpenConversation = onOpenConversation,
                            onOpenGroupDialog = { showGroupDialog = true },
                            onOpenChannelDialog = { showChannelDialog = true },
                            onOpenJoinInviteDialog = { showJoinInviteDialog = true },
                            onScanInviteQr = startInviteQrScan,
                            inviteStatusMessage = inviteStatusMessage
                        )
                    }

                    MeshTab.PROFILE -> {
                        if (profileSettingsSection == null) {
                            MeshProfileHome(
                                uiState = uiState,
                                aliasDraft = aliasDraft,
                                onAliasDraftChange = { aliasDraft = it },
                                onSaveAlias = { onSaveAlias(aliasDraft) },
                                onOpenSettings = { section ->
                                    profileSettingsSectionId = section.name
                                },
                                onToggleMesh = onToggleMesh,
                                onPickAvatar = onPickAvatar
                            )
                        } else {
                            SettingsHome(
                                section = profileSettingsSection,
                                uiState = uiState,
                                aliasDraft = aliasDraft,
                                onAliasDraftChange = { aliasDraft = it },
                                onSaveAlias = { onSaveAlias(aliasDraft) },
                                relayEnabledDraft = relayEnabledDraft,
                                onRelayEnabledChange = { relayEnabledDraft = it },
                                relayUrlDraft = relayUrlDraft,
                                onRelayUrlDraftChange = { relayUrlDraft = it },
                                onSaveRelay = {
                                    onSaveRelaySettings(relayEnabledDraft, relayUrlDraft)
                                    relayStatusMessage = strings.relaySettingsSaved
                                },
                                visualThemePreset = visualThemePreset,
                                onVisualThemeChange = onVisualThemeChange,
                                relayStatusMessage = relayStatusMessage,
                                backupStatusMessage = backupStatusMessage,
                                appLockEnabled = appLockEnabled,
                                hasAppPasscode = hasAppPasscode,
                                onEnableAppLock = onEnableAppLock,
                                onDisableAppLock = onDisableAppLock,
                                onChangeAppLockPin = onChangeAppLockPin,
                                onLockNow = onLockNow,
                                onExportBackup = {
                                    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
                                        .format(Date())
                                    exportBackupLauncher.launch("mesh_backup_$stamp.mbak")
                                },
                                onImportBackup = { importBackupLauncher.launch(arrayOf("*/*")) },
                                onBack = { profileSettingsSectionId = null },
                                onToggleMesh = onToggleMesh,
                                notificationSound = notificationSound,
                                vibrationLevel = vibrationLevel,
                                onNotificationSoundChange = { sound ->
                                    notificationSound = sound
                                    notificationPrefs.edit().putString(KEY_NOTIFICATION_SOUND, sound).apply()
                                    MeshForegroundService.refreshNotificationChannels(localContext)
                                },
                                onVibrationLevelChange = { level ->
                                    vibrationLevel = level
                                    notificationPrefs.edit().putString(KEY_NOTIFICATION_VIBRATION, level).apply()
                                    MeshForegroundService.refreshNotificationChannels(localContext)
                                }
                            )
                        }
                    }

                    MeshTab.SETTINGS -> onSelectTab(MeshTab.PROFILE)
                }
            }
        }
    }

    if (showDirectDialog) {
        DirectChatDialog(
            contacts = uiState.contacts,
            onDismiss = { showDirectDialog = false },
            onOpen = { nodeId ->
                showDirectDialog = false
                onOpenDirect(nodeId)
            }
        )
    }

    if (showGroupDialog) {
        CreateGroupDialog(
            contacts = uiState.contacts,
            onDismiss = { showGroupDialog = false },
            onCreate = { title, members ->
                val created = onCreateGroup(title, members)
                if (created) {
                    showGroupDialog = false
                    onSelectTab(MeshTab.CHATS)
                }
            }
        )
    }

    if (showChannelDialog) {
        CreateChannelDialog(
            contacts = uiState.contacts,
            onDismiss = { showChannelDialog = false },
            onCreate = { title, members ->
                val created = onCreateChannel(title, members)
                if (created) {
                    showChannelDialog = false
                    onSelectTab(MeshTab.CHATS)
                }
            }
        )
    }

    if (showJoinInviteDialog) {
        AlertDialog(
            onDismissRequest = {
                showJoinInviteDialog = false
                inviteCodeDraft = ""
            },
            title = { Text("Join by invite code") },
            text = {
                Column {
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = startInviteQrScan
                    ) {
                        Text("Scan invite QR")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inviteCodeDraft,
                        onValueChange = { inviteCodeDraft = it.take(600) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        label = { Text("Paste invite code") }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Use code with prefix MESHINV1:",
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowMeta
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = inviteCodeDraft.trim().isNotBlank(),
                    onClick = {
                        val joined = onJoinByInviteCode(inviteCodeDraft)
                        inviteStatusMessage = if (joined) {
                            "Invite applied, community joined"
                        } else {
                            "Invite is invalid or cannot be applied"
                        }
                        if (joined) {
                            showJoinInviteDialog = false
                            inviteCodeDraft = ""
                            onSelectTab(MeshTab.CHATS)
                        }
                    }
                ) {
                    Text("Join")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showJoinInviteDialog = false
                        inviteCodeDraft = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    val selectedActionMessage = actionMessage
    if (selectedActionMessage != null) {
        val isCollectiveConversation = (
            uiState.activeConversationType == ConversationType.CHANNEL ||
            uiState.activeConversationType == ConversationType.GROUP
        )
        val canCollectiveModerate = if (isCollectiveConversation) {
            uiState.activeConversationCanModerate
        } else {
            false
        }
        val isMessageOwner = selectedActionMessage.isLocal ||
            selectedActionMessage.originNodeId == uiState.nodeId
        val canEditOwnMessage = !isCollectiveConversation ||
            uiState.activeConversationCanEditOwn
        val canDeleteOwnMessage = !isCollectiveConversation ||
            uiState.activeConversationCanDeleteOwn
        val canEdit = isMessageOwner &&
            !selectedActionMessage.isDeleted &&
            selectedActionMessage.contentType == ChatContentType.TEXT &&
            canEditOwnMessage
        val canDelete = !selectedActionMessage.isDeleted &&
            ((isMessageOwner && canDeleteOwnMessage) || (isCollectiveConversation && canCollectiveModerate))
        val canForward = !selectedActionMessage.isDeleted && (
            (
                selectedActionMessage.contentType == ChatContentType.TEXT &&
                    selectedActionMessage.text.isNotBlank()
                ) || (
                selectedActionMessage.contentType == ChatContentType.FILE &&
                    !selectedActionMessage.attachment?.localUri.isNullOrBlank()
                )
        )
        val canOpenAttachment = selectedActionMessage.contentType == ChatContentType.FILE &&
            !selectedActionMessage.attachment?.localUri.isNullOrBlank()
        val canShare = (
            selectedActionMessage.contentType == ChatContentType.TEXT &&
                selectedActionMessage.text.trim().isNotBlank()
            ) || canOpenAttachment
        val canReact = !selectedActionMessage.isDeleted &&
            (!isCollectiveConversation || uiState.activeConversationCanReact)
        val canPin = !selectedActionMessage.isDeleted &&
            (!isCollectiveConversation || uiState.activeConversationCanPin)
        val canTag = !selectedActionMessage.isDeleted &&
            isSavedMessagesConversation(selectedActionMessage.conversationId)
        MessageActionsDialog(
            message = selectedActionMessage,
            canEdit = canEdit,
            canDelete = canDelete,
            canForward = canForward,
            canOpenAttachment = canOpenAttachment,
            canShare = canShare,
            canReact = canReact,
            canPin = canPin,
            canTag = canTag,
            isPinned = selectedActionMessage.pinnedAtMs != null,
            onDismiss = { actionMessage = null },
            onCopy = {
                val clipboard = localContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("message", selectedActionMessage.text)
                )
                actionMessage = null
            },
            onReply = {
                replyToMessageId = selectedActionMessage.id
                editingMessageId = null
                actionMessage = null
            },
            onEdit = {
                editingMessageId = selectedActionMessage.id
                replyToMessageId = null
                messageDraft = selectedActionMessage.text
                actionMessage = null
            },
            onDelete = {
                onDeleteMessage(selectedActionMessage.id)
                if (replyToMessageId == selectedActionMessage.id) {
                    replyToMessageId = null
                }
                if (editingMessageId == selectedActionMessage.id) {
                    editingMessageId = null
                    messageDraft = ""
                }
                actionMessage = null
            },
            onForward = {
                forwardSourceMessage = selectedActionMessage
                actionMessage = null
            },
            onOpenAttachment = {
                attachmentPreviewMessage = selectedActionMessage
                actionMessage = null
            },
            onShare = {
                shareMessage(localContext, selectedActionMessage)
                actionMessage = null
            },
            onSelect = {
                selectedMessageIds = setOf(selectedActionMessage.id)
                showForwardSelectedDialog = false
                actionMessage = null
            },
            onReact = { emoji ->
                onReactMessage(selectedActionMessage.id, emoji)
                actionMessage = null
            },
            onPinToggle = { pinEnabled ->
                onPinMessage(selectedActionMessage.id, pinEnabled)
                actionMessage = null
            },
            onEditTags = {
                tagActionMessage = selectedActionMessage
                actionMessage = null
            }
        )
    }

    val selectedTagMessage = tagActionMessage
    if (selectedTagMessage != null) {
        SavedMessageTagsDialog(
            message = selectedTagMessage,
            onDismiss = { tagActionMessage = null },
            onSave = { tags ->
                onUpdateSavedMessageTags(selectedTagMessage.id, tags)
                tagActionMessage = null
            }
        )
    }

    val selectedForwardMessage = forwardSourceMessage
    if (selectedForwardMessage != null) {
        ForwardMessageDialog(
            sourceMessages = listOf(selectedForwardMessage),
            conversations = uiState.conversations,
            activeConversationId = uiState.activeConversationId,
            onDismiss = { forwardSourceMessage = null },
            onForward = { conversationIds ->
                val sentCount = onForwardMessage(selectedForwardMessage.id, conversationIds)
                inviteStatusMessage = if (sentCount > 0) {
                    if (sentCount == 1) {
                        "Message forwarded to 1 chat"
                    } else {
                        "Message forwarded to $sentCount chats"
                    }
                } else {
                    "Failed to forward message"
                }
                if (sentCount > 0) {
                    forwardSourceMessage = null
                }
            }
        )
    }
    if (showForwardSelectedDialog && selectedMessages.isNotEmpty()) {
        ForwardMessageDialog(
            sourceMessages = selectedMessages,
            conversations = uiState.conversations,
            activeConversationId = uiState.activeConversationId,
            onDismiss = { showForwardSelectedDialog = false },
            onForward = { conversationIds ->
                val selectedIds = selectedMessages.map { it.id }
                var forwardedCopies = 0
                selectedIds.forEach { sourceId ->
                    forwardedCopies += onForwardMessage(sourceId, conversationIds)
                }
                val expectedCopies = selectedIds.size * conversationIds.size
                inviteStatusMessage = when {
                    forwardedCopies <= 0 -> "Failed to forward selected messages"
                    forwardedCopies < expectedCopies -> {
                        "Partially forwarded: $forwardedCopies/$expectedCopies"
                    }
                    else -> {
                        "Forwarded $forwardedCopies message copies"
                    }
                }
                if (forwardedCopies > 0) {
                    showForwardSelectedDialog = false
                    selectedMessageIds = emptySet()
                }
            }
        )
    }

    val selectedAttachmentPreview = attachmentPreviewMessage
    if (selectedAttachmentPreview != null) {
        AttachmentPreviewDialog(
            message = selectedAttachmentPreview,
            onDismiss = { attachmentPreviewMessage = null },
            onOpenExternal = {
                openAttachment(localContext, selectedAttachmentPreview)
                attachmentPreviewMessage = null
            },
            onShare = {
                shareMessage(localContext, selectedAttachmentPreview)
                attachmentPreviewMessage = null
            }
        )
    }

    val videoNotePreview = pendingVideoPreview
    if (videoNotePreview != null) {
        VideoNoteDraftDialog(
            capture = videoNotePreview,
            canSend = videoNotePreview.file.length() in 1..MAX_VIDEO_NOTE_BYTES,
            onSend = {
                val sent = onSendFile(videoNotePreview.uri)
                if (sent) {
                    pendingVideoPreview = null
                    runCatching { videoNotePreview.file.delete() }
                    inviteStatusMessage = "Video note sent"
                } else {
                    inviteStatusMessage = "Video note send failed; preview kept for retry"
                }
            },
            onRetake = {
                pendingVideoPreview = null
                runCatching { videoNotePreview.file.delete() }
                startVideoNoteCapture()
            },
            onCancel = {
                pendingVideoPreview = null
                runCatching { videoNotePreview.file.delete() }
                inviteStatusMessage = "Video note discarded"
            }
        )
    }

    if (pendingMediaUris.isNotEmpty()) {
        MediaAlbumDraftDialog(
            uris = pendingMediaUris,
            caption = pendingMediaCaption,
            onCaptionChange = { value ->
                pendingMediaCaption = value.take(MAX_MEDIA_CAPTION_LENGTH)
            },
            onSend = {
                val requestedCount = pendingMediaUris.size
                val sentCount = onSendMediaAlbum(pendingMediaUris, pendingMediaCaption)
                inviteStatusMessage = when {
                    sentCount <= 0 -> "Media album send failed"
                    sentCount < requestedCount -> "Media queued partially: $sentCount/$requestedCount"
                    requestedCount == 1 -> "Media queued for encrypted delivery"
                    else -> "$sentCount media items queued as an album"
                }
                if (sentCount > 0) {
                    pendingMediaUris = emptyList()
                    pendingMediaCaption = ""
                    messageDraft = ""
                    onUpdateDraft("")
                }
            },
            onCancel = {
                pendingMediaUris = emptyList()
                pendingMediaCaption = ""
            }
        )
    }

    val selectedSharePayload = shareToMeshPayload
    if (selectedSharePayload != null) {
        ShareToMeshDialog(
            payload = selectedSharePayload,
            conversations = uiState.conversations,
            onDismiss = { shareToMeshPayload = null },
            onSendToConversation = { conversation ->
                val sentText = selectedSharePayload.text
                    ?.trim()
                    ?.ifBlank { null }
                    ?.let { text -> onSendTextToConversation(conversation.id, text) }
                    ?: true
                val sentFile = selectedSharePayload.uri?.let { uri ->
                    onSendFileToConversation(conversation.id, uri)
                } ?: true
                val sent = sentText && sentFile
                inviteStatusMessage = if (sent) {
                    "Shared to ${conversation.title}"
                } else {
                    "Failed to share to ${conversation.title}"
                }
                if (sent) {
                    shareToMeshPayload = null
                    onOpenConversation(conversation.id)
                }
            }
        )
    }

    val selectedConversationAction = conversationAction
    if (selectedConversationAction != null) {
        val isCollective = selectedConversationAction.type != ConversationType.DIRECT
        val ownerNodeId = selectedConversationAction.ownerNodeId
            ?.trim()
            ?.ifBlank { null }
            ?: selectedConversationAction.adminNodeIds.firstOrNull { it.isNotBlank() }
            ?: uiState.nodeId
        val adminNodeIds = selectedConversationAction.adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .let { admins ->
                if (admins.contains(ownerNodeId)) admins else listOf(ownerNodeId) + admins
            }
            .distinct()
        val moderatorNodeIds = selectedConversationAction.moderatorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && !adminNodeIds.contains(it) }
            .distinct()
        val canManageCollective = isCollective && (
            uiState.nodeId == ownerNodeId || adminNodeIds.contains(uiState.nodeId)
        )
        val canModerateCollective = isCollective && (
            canManageCollective || moderatorNodeIds.contains(uiState.nodeId)
        )
        ConversationActionsDialog(
            conversation = selectedConversationAction,
            canManageCollective = canManageCollective,
            canModerateCollective = canModerateCollective,
            isCollectiveBroadcastOnly = selectedConversationAction.isBroadcastOnly,
            onDismiss = { conversationAction = null },
            onOpen = {
                onOpenConversation(selectedConversationAction.id)
                conversationAction = null
            },
            onPinToggle = { pinned ->
                onPinConversation(selectedConversationAction.id, pinned)
                conversationAction = null
            },
            onMuteToggle = { muted ->
                onMuteConversation(selectedConversationAction.id, muted)
                conversationAction = null
            },
            onArchiveToggle = { archived ->
                onArchiveConversation(selectedConversationAction.id, archived)
                conversationAction = null
            },
            onMarkRead = {
                onMarkConversationRead(selectedConversationAction.id)
                conversationAction = null
            },
            onCopyInvite = {
                val code = onGenerateInviteCode(selectedConversationAction.id)
                if (!code.isNullOrBlank()) {
                    val clipboard =
                        localContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("invite_code", code))
                    inviteStatusMessage = "Invite code copied"
                } else {
                    inviteStatusMessage = "Cannot generate invite code for this chat"
                }
                conversationAction = null
            },
            onShowInviteQr = {
                val code = onGenerateInviteCode(selectedConversationAction.id)
                if (!code.isNullOrBlank()) {
                    inviteQrCode = code
                } else {
                    inviteStatusMessage = "Cannot generate invite code for this chat"
                }
                conversationAction = null
            },
            onToggleCollectiveBroadcast = { broadcastOnly ->
                val changed = onSetChannelBroadcastMode(selectedConversationAction.id, broadcastOnly)
                inviteStatusMessage = if (changed) {
                    val collectiveLabel = if (selectedConversationAction.type == ConversationType.CHANNEL) {
                        "Channel"
                    } else {
                        "Group"
                    }
                    if (broadcastOnly) {
                        "$collectiveLabel switched to admin-only posting"
                    } else {
                        "$collectiveLabel switched to open posting"
                    }
                } else {
                    "Failed to change posting mode"
                }
                conversationAction = null
            },
            onManageCollectiveAdmins = {
                manageAdminsConversation = selectedConversationAction
                conversationAction = null
            },
            onManageCollectiveMembers = {
                manageMembersConversation = selectedConversationAction
                conversationAction = null
            },
            onManageCollectiveModerators = {
                manageModeratorsConversation = selectedConversationAction
                conversationAction = null
            }
        )
    }

    val selectedManageAdminsConversation = manageAdminsConversation
    if (selectedManageAdminsConversation != null &&
        selectedManageAdminsConversation.type != ConversationType.DIRECT
    ) {
        val contactsByNodeId = remember(uiState.contacts) {
            uiState.contacts.associateBy { it.nodeId }
        }
        val membersForDialog = remember(
            selectedManageAdminsConversation,
            contactsByNodeId,
            uiState.nodeId,
            uiState.nodeAlias
        ) {
            selectedManageAdminsConversation.memberNodeIds
                .map { nodeId ->
                    contactsByNodeId[nodeId] ?: MeshContact(
                        nodeId = nodeId,
                        alias = if (nodeId == uiState.nodeId) {
                            uiState.nodeAlias.ifBlank { "You" }
                        } else {
                            "Node-${nodeId.take(4)}"
                        },
                        fingerprintShort = null,
                        isOnline = false
                    )
                }
        }
        ManageChannelAdminsDialog(
            conversation = selectedManageAdminsConversation,
            members = membersForDialog,
            onDismiss = { manageAdminsConversation = null },
            onSave = { selectedAdmins ->
                val updated = onUpdateChannelAdmins(
                    selectedManageAdminsConversation.id,
                    selectedAdmins
                )
                inviteStatusMessage = if (updated) {
                    if (selectedManageAdminsConversation.type == ConversationType.CHANNEL) {
                        "Channel admins updated"
                    } else {
                        "Group admins updated"
                    }
                } else {
                    if (selectedManageAdminsConversation.type == ConversationType.CHANNEL) {
                        "Failed to update channel admins"
                    } else {
                        "Failed to update group admins"
                    }
                }
                manageAdminsConversation = null
            }
        )
    }

    val selectedManageModeratorsConversation = manageModeratorsConversation
    if (selectedManageModeratorsConversation != null &&
        selectedManageModeratorsConversation.type != ConversationType.DIRECT
    ) {
        val contactsByNodeId = remember(uiState.contacts) {
            uiState.contacts.associateBy { it.nodeId }
        }
        val membersForDialog = remember(
            selectedManageModeratorsConversation,
            contactsByNodeId,
            uiState.nodeId,
            uiState.nodeAlias
        ) {
            selectedManageModeratorsConversation.memberNodeIds
                .map { nodeId ->
                    contactsByNodeId[nodeId] ?: MeshContact(
                        nodeId = nodeId,
                        alias = if (nodeId == uiState.nodeId) {
                            uiState.nodeAlias.ifBlank { "You" }
                        } else {
                            "Node-${nodeId.take(4)}"
                        },
                        fingerprintShort = null,
                        isOnline = false
                    )
                }
        }
        ManageCollectiveModeratorsDialog(
            conversation = selectedManageModeratorsConversation,
            members = membersForDialog,
            onDismiss = { manageModeratorsConversation = null },
            onSave = { selectedModerators ->
                val updated = onUpdateCollectiveModerators(
                    selectedManageModeratorsConversation.id,
                    selectedModerators
                )
                inviteStatusMessage = if (updated) {
                    if (selectedManageModeratorsConversation.type == ConversationType.CHANNEL) {
                        "Channel moderators updated"
                    } else {
                        "Group moderators updated"
                    }
                } else {
                    if (selectedManageModeratorsConversation.type == ConversationType.CHANNEL) {
                        "Failed to update channel moderators"
                    } else {
                        "Failed to update group moderators"
                    }
                }
                manageModeratorsConversation = null
            }
        )
    }

    val selectedManageMembersConversation = manageMembersConversation
    if (selectedManageMembersConversation != null &&
        selectedManageMembersConversation.type != ConversationType.DIRECT
    ) {
        val contactsByNodeId = remember(uiState.contacts) {
            uiState.contacts.associateBy { it.nodeId }
        }
        val membersForDialog = remember(
            selectedManageMembersConversation,
            contactsByNodeId,
            uiState.nodeId,
            uiState.nodeAlias
        ) {
            val mergedNodeIds = linkedSetOf<String>().apply {
                addAll(selectedManageMembersConversation.memberNodeIds)
                addAll(contactsByNodeId.keys)
                add(uiState.nodeId)
            }
            mergedNodeIds.map { nodeId ->
                contactsByNodeId[nodeId] ?: MeshContact(
                    nodeId = nodeId,
                    alias = if (nodeId == uiState.nodeId) {
                        uiState.nodeAlias.ifBlank { "You" }
                    } else {
                        "Node-${nodeId.take(4)}"
                    },
                    fingerprintShort = null,
                    isOnline = false
                )
            }
        }
        ManageCollectiveMembersDialog(
            conversation = selectedManageMembersConversation,
            members = membersForDialog,
            localNodeId = uiState.nodeId,
            localAlias = uiState.nodeAlias,
            onDismiss = { manageMembersConversation = null },
            onSave = { selectedMembers ->
                val updated = onUpdateCollectiveMembers(
                    selectedManageMembersConversation.id,
                    selectedMembers
                )
                inviteStatusMessage = if (updated) {
                    if (selectedManageMembersConversation.type == ConversationType.CHANNEL) {
                        "Channel members updated"
                    } else {
                        "Group members updated"
                    }
                } else {
                    if (selectedManageMembersConversation.type == ConversationType.CHANNEL) {
                        "Failed to update channel members"
                    } else {
                        "Failed to update group members"
                    }
                }
                manageMembersConversation = null
            }
        )
    }

    val activeInviteQrCode = inviteQrCode
    if (!activeInviteQrCode.isNullOrBlank()) {
        val qrBitmap = remember(activeInviteQrCode) { generateInviteQrBitmap(activeInviteQrCode) }
        AlertDialog(
            onDismissRequest = { inviteQrCode = null },
            title = { Text("Invite QR") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Invite QR",
                            modifier = Modifier
                                .size(240.dp)
                                .clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = activeInviteQrCode,
                        style = MaterialTheme.typography.labelSmall,
                        color = TgDayPalette.rowMeta,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clipboard =
                            localContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("invite_code", activeInviteQrCode)
                        )
                        inviteStatusMessage = "Invite code copied"
                    }
                ) {
                    Text("Copy code")
                }
            },
            dismissButton = {
                TextButton(onClick = { inviteQrCode = null }) {
                    Text("Close")
                }
            }
        )
    }

    if (showChatInfo && inChat && activeConversation != null) {
        val contactsByNodeId = remember(uiState.contacts) {
            uiState.contacts.associateBy { it.nodeId }
        }
        ChatInfoDialog(
            conversation = activeConversation,
            localNodeId = uiState.nodeId,
            localAlias = uiState.nodeAlias,
            canManageCollective = uiState.activeConversationCanManageRoles,
            contactsByNodeId = contactsByNodeId,
            onUpdateAdmins = { updatedAdmins ->
                onUpdateChannelAdmins(activeConversation.id, updatedAdmins)
            },
            onUpdateMembers = { updatedMembers ->
                onUpdateCollectiveMembers(activeConversation.id, updatedMembers)
            },
            onUpdateModerators = { updatedModerators ->
                onUpdateCollectiveModerators(activeConversation.id, updatedModerators)
            },
            onUpdateMemberPermissions = { allowReactions, allowEditOwn, allowDeleteOwn ->
                onUpdateCollectiveMemberPermissions(
                    activeConversation.id,
                    allowReactions,
                    allowEditOwn,
                    allowDeleteOwn
                )
            },
            onStatusMessage = { message ->
                inviteStatusMessage = message
            },
            onDismiss = { showChatInfo = false }
        )
    }

    if (showMediaGallery && inChat) {
        MediaGalleryDialog(
            messages = mediaMessages,
            onDismiss = { showMediaGallery = false },
            onOpenAttachment = { message ->
                attachmentPreviewMessage = message
            }
        )
    }

    if (showBackupPassDialog && pendingBackupUri != null && pendingBackupMode != null) {
        AlertDialog(
            onDismissRequest = {
                showBackupPassDialog = false
                pendingBackupUri = null
                pendingBackupMode = null
            },
            title = {
                Text(
                    if (pendingBackupMode == BackupMode.EXPORT) {
                        "Backup passphrase"
                    } else {
                        "Restore passphrase"
                    }
                )
            },
            text = {
                Column {
                    Text(
                        text = "Enter passphrase (min 8 chars). Keep it safe.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = backupPassphrase,
                        onValueChange = { backupPassphrase = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Passphrase") }
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = backupPassphrase.trim().length >= 8,
                    onClick = {
                        val uri = pendingBackupUri ?: return@Button
                        val mode = pendingBackupMode ?: return@Button
                        val pass = backupPassphrase.trim()
                        val ok = if (mode == BackupMode.EXPORT) {
                            onExportBackup(uri, pass)
                        } else {
                            onImportBackup(uri, pass)
                        }
                        backupStatusMessage = if (ok) {
                            if (mode == BackupMode.EXPORT) {
                                "Encrypted backup exported"
                            } else {
                                "Backup imported and applied"
                            }
                        } else {
                            if (mode == BackupMode.EXPORT) {
                                "Backup export failed"
                            } else {
                                "Backup import failed"
                            }
                        }
                        showBackupPassDialog = false
                        pendingBackupUri = null
                        pendingBackupMode = null
                        backupPassphrase = ""
                    }
                ) {
                    Text(if (pendingBackupMode == BackupMode.EXPORT) "Export" else "Import")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBackupPassDialog = false
                        pendingBackupUri = null
                        pendingBackupMode = null
                    }
                ) { Text("Cancel") }
            }
        )
    }
}

private fun replyPreview(message: ChatMessage): String {
    if (message.contentType == ChatContentType.FILE) {
        return "[file] ${message.attachment?.fileName ?: message.text.ifBlank { "File" }}"
    }
    return message.text.trim().ifBlank { "Message" }.take(96)
}

@Composable
private fun MessageActionsDialog(
    message: ChatMessage,
    canEdit: Boolean,
    canDelete: Boolean,
    canForward: Boolean,
    canOpenAttachment: Boolean,
    canShare: Boolean,
    canReact: Boolean,
    canPin: Boolean,
    canTag: Boolean,
    isPinned: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onForward: () -> Unit,
    onOpenAttachment: () -> Unit,
    onShare: () -> Unit,
    onSelect: () -> Unit,
    onReact: (String) -> Unit,
    onPinToggle: (Boolean) -> Unit,
    onEditTags: () -> Unit
) {
    val strings = rememberMeshStrings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (message.contentType == ChatContentType.FILE) strings.fileMessage else strings.messageActions
            )
        },
        text = {
            Column {
                if (!message.isDeleted) {
                    TextButton(onClick = onReply) {
                        Text(strings.reply)
                    }
                }
                if (message.contentType == ChatContentType.TEXT) {
                    TextButton(onClick = onCopy) {
                        Text(strings.copy)
                    }
                }
                if (canEdit) {
                    TextButton(onClick = onEdit) {
                        Text(strings.edit)
                    }
                }
                if (canDelete) {
                    TextButton(onClick = onDelete) {
                        Text(strings.delete)
                    }
                }
                if (canForward) {
                    TextButton(onClick = onForward) {
                        Text(strings.forward)
                    }
                }
                if (canOpenAttachment) {
                    TextButton(onClick = onOpenAttachment) {
                        Text(
                            if (message.attachment?.mimeType
                                    ?.lowercase(Locale.ROOT)
                                    ?.startsWith("audio/") == true
                            ) {
                                strings.playVoice
                            } else {
                                strings.openFile
                            }
                        )
                    }
                }
                if (canShare) {
                    TextButton(onClick = onShare) {
                        Text(
                            if (message.contentType == ChatContentType.FILE) {
                                strings.shareFile
                            } else {
                                strings.shareText
                            }
                        )
                    }
                }
                if (!message.isDeleted) {
                    TextButton(onClick = onSelect) {
                        Text(strings.select)
                    }
                }
                if (canPin) {
                    TextButton(onClick = { onPinToggle(!isPinned) }) {
                        Text(if (isPinned) strings.unpin else strings.pin)
                    }
                }
                if (canTag) {
                    TextButton(onClick = onEditTags) {
                        Text(if (message.savedTags.isEmpty()) strings.addTags else strings.editTags)
                    }
                }
                if (canReact) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = strings.quickReaction,
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowMeta
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("👍", "❤️", "🔥").forEach { emoji ->
                            FilledTonalButton(onClick = { onReact(emoji) }) {
                                Text(emoji)
                            }
                        }
                    }
                    TextButton(onClick = { onReact("") }) {
                        Text(strings.removeMyReaction)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.close)
            }
        }
    )
}

@Composable
private fun SavedMessageTagsDialog(
    message: ChatMessage,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var tagDraft by rememberSaveable(message.id) {
        mutableStateOf(message.savedTags.joinToString(", "))
    }
    val parsedTags = remember(tagDraft) {
        tagDraft
            .split(',', '\n')
            .map { tag -> tag.trim().removePrefix("#").take(24) }
            .filter { tag -> tag.isNotBlank() }
            .distinctBy { tag -> tag.lowercase(Locale.ROOT) }
            .take(8)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved message tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Use commas to separate up to 8 tags.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgDayPalette.rowMeta
                )
                OutlinedTextField(
                    value = tagDraft,
                    onValueChange = { next -> tagDraft = next.take(240) },
                    label = { Text("Tags") },
                    placeholder = { Text("work, ideas, important") },
                    minLines = 2,
                    maxLines = 4
                )
                if (parsedTags.isNotEmpty()) {
                    Text(
                        text = parsedTags.joinToString("  ") { tag -> "#$tag" },
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowBlue
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(parsedTags) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ConversationActionsDialog(
    conversation: ConversationSummary,
    canManageCollective: Boolean,
    canModerateCollective: Boolean,
    isCollectiveBroadcastOnly: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onPinToggle: (Boolean) -> Unit,
    onMuteToggle: (Boolean) -> Unit,
    onArchiveToggle: (Boolean) -> Unit,
    onMarkRead: () -> Unit,
    onCopyInvite: () -> Unit,
    onShowInviteQr: () -> Unit,
    onToggleCollectiveBroadcast: (Boolean) -> Unit,
    onManageCollectiveAdmins: () -> Unit,
    onManageCollectiveMembers: () -> Unit,
    onManageCollectiveModerators: () -> Unit
) {
    val strings = rememberMeshStrings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(conversation.title) },
        text = {
            Column {
                TextButton(onClick = onOpen) {
                    Text(strings.openChat)
                }
                TextButton(onClick = { onPinToggle(!conversation.isPinned) }) {
                    Text(if (conversation.isPinned) strings.unpinChat else strings.pinChat)
                }
                TextButton(onClick = { onMuteToggle(!conversation.isMuted) }) {
                    Text(if (conversation.isMuted) strings.unmuteChat else strings.muteChat)
                }
                TextButton(onClick = { onArchiveToggle(!conversation.isArchived) }) {
                    Text(if (conversation.isArchived) strings.unarchiveChat else strings.archiveChat)
                }
                if (conversation.type == ConversationType.GROUP || conversation.type == ConversationType.CHANNEL) {
                    TextButton(onClick = onCopyInvite) {
                        Text(strings.copyInviteCode)
                    }
                    TextButton(onClick = onShowInviteQr) {
                        Text(strings.showInviteQr)
                    }
                }
                if ((conversation.type == ConversationType.CHANNEL || conversation.type == ConversationType.GROUP) &&
                    canManageCollective
                ) {
                    TextButton(onClick = { onToggleCollectiveBroadcast(!isCollectiveBroadcastOnly) }) {
                        Text(
                            if (isCollectiveBroadcastOnly) {
                                strings.enableOpenPosting
                            } else {
                                strings.enableAdminOnlyPosting
                            }
                        )
                    }
                    TextButton(onClick = onManageCollectiveAdmins) {
                        Text(
                            if (conversation.type == ConversationType.CHANNEL) {
                                strings.manageChannelAdmins
                            } else {
                                strings.manageGroupAdmins
                            }
                        )
                    }
                    TextButton(onClick = onManageCollectiveMembers) {
                        Text(
                            if (conversation.type == ConversationType.CHANNEL) {
                                strings.manageChannelMembers
                            } else {
                                strings.manageGroupMembers
                            }
                        )
                    }
                    TextButton(onClick = onManageCollectiveModerators) {
                        Text(
                            if (conversation.type == ConversationType.CHANNEL) {
                                strings.manageChannelModerators
                            } else {
                                strings.manageGroupModerators
                            }
                        )
                    }
                } else if ((conversation.type == ConversationType.CHANNEL || conversation.type == ConversationType.GROUP) &&
                    canModerateCollective
                ) {
                    Text(
                        text = strings.moderatorStatus,
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowMeta,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                if (conversation.unreadCount > 0) {
                    TextButton(onClick = onMarkRead) {
                        Text(strings.markAsRead)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.close)
            }
        }
    )
}

@Composable
private fun ChatInfoDialog(
    conversation: ConversationSummary,
    localNodeId: String,
    localAlias: String,
    canManageCollective: Boolean,
    contactsByNodeId: Map<String, MeshContact>,
    onUpdateAdmins: (List<String>) -> Boolean,
    onUpdateMembers: (List<String>) -> Boolean,
    onUpdateModerators: (List<String>) -> Boolean,
    onUpdateMemberPermissions: (Boolean, Boolean, Boolean) -> Boolean,
    onStatusMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val ownerId = remember(conversation.ownerNodeId, conversation.adminNodeIds, localNodeId) {
        conversation.ownerNodeId?.trim()?.ifBlank { null }
            ?: conversation.adminNodeIds.firstOrNull { it.isNotBlank() }
            ?: localNodeId
    }
    val adminSet = remember(conversation.adminNodeIds, ownerId) {
        conversation.adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .let { admins ->
                if (ownerId.isBlank()) admins else (admins + ownerId)
            }
            .toSet()
    }
    val moderatorSet = remember(conversation.moderatorNodeIds, adminSet) {
        conversation.moderatorNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() && !adminSet.contains(it) }
            .toSet()
    }
    val members = remember(conversation, contactsByNodeId, localNodeId, localAlias, ownerId) {
        val ids = linkedSetOf<String>().apply {
            addAll(conversation.memberNodeIds.map { it.trim() }.filter { it.isNotBlank() })
            if (conversation.type != ConversationType.DIRECT && ownerId.isNotBlank()) {
                add(ownerId)
            }
            add(localNodeId)
        }
        ids
            .map { nodeId ->
                contactsByNodeId[nodeId] ?: MeshContact(
                    nodeId = nodeId,
                    alias = if (nodeId == localNodeId) {
                        localAlias.ifBlank { "You" }
                    } else {
                        "Node-${nodeId.take(4)}"
                    },
                    fingerprintShort = null,
                    isOnline = false
                )
            }
            .sortedWith(
                compareByDescending<MeshContact> { it.nodeId == localNodeId }
                    .thenByDescending { it.nodeId == ownerId }
                    .thenByDescending { adminSet.contains(it.nodeId) }
                    .thenByDescending { moderatorSet.contains(it.nodeId) }
                    .thenByDescending { it.isOnline }
                    .thenBy { it.alias.lowercase() }
            )
    }
    var selectedMember by remember(conversation.id) { mutableStateOf<MeshContact?>(null) }
    var allowMemberReactions by remember(
        conversation.id,
        conversation.allowMemberReactions
    ) {
        mutableStateOf(conversation.allowMemberReactions)
    }
    var allowMemberEditOwn by remember(
        conversation.id,
        conversation.allowMemberEditOwnMessages,
        conversation.isBroadcastOnly
    ) {
        mutableStateOf(
            if (conversation.isBroadcastOnly) {
                false
            } else {
                conversation.allowMemberEditOwnMessages
            }
        )
    }
    var allowMemberDeleteOwn by remember(
        conversation.id,
        conversation.allowMemberDeleteOwnMessages,
        conversation.isBroadcastOnly
    ) {
        mutableStateOf(
            if (conversation.isBroadcastOnly) {
                false
            } else {
                conversation.allowMemberDeleteOwnMessages
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat info") },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = when (conversation.type) {
                        ConversationType.DIRECT -> "Direct chat"
                        ConversationType.GROUP -> "Group • ${conversation.memberNodeIds.size} members"
                        ConversationType.CHANNEL -> "Channel • ${conversation.memberNodeIds.size} subscribers"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TgDayPalette.rowMeta
                )
                if (conversation.type != ConversationType.DIRECT) {
                    Text(
                        text = if (conversation.isBroadcastOnly) {
                            "Posting mode: admins/moderators only"
                        } else {
                            "Posting mode: open"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TgDayPalette.rowMeta
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Member reactions: ${if (allowMemberReactions) "on" else "off"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgDayPalette.rowMeta
                    )
                    Text(
                        text = "Member edit own: ${if (allowMemberEditOwn) "on" else "off"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgDayPalette.rowMeta
                    )
                    Text(
                        text = "Member delete own: ${if (allowMemberDeleteOwn) "on" else "off"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgDayPalette.rowMeta
                    )
                    if (canManageCollective) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Permissions",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Members can react")
                                Text(
                                    text = "Allow reactions for regular members",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TgDayPalette.rowMeta
                                )
                            }
                            Switch(
                                checked = allowMemberReactions,
                                onCheckedChange = { nextValue ->
                                    val updated = onUpdateMemberPermissions(
                                        nextValue,
                                        allowMemberEditOwn,
                                        allowMemberDeleteOwn
                                    )
                                    onStatusMessage(
                                        if (updated) {
                                            allowMemberReactions = nextValue
                                            if (nextValue) {
                                                "Members can react now"
                                            } else {
                                                "Member reactions disabled"
                                            }
                                        } else {
                                            "Failed to update reaction permission"
                                        }
                                    )
                                }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Members can edit own")
                                Text(
                                    text = if (conversation.isBroadcastOnly) {
                                        "Disabled while admin-only posting is active"
                                    } else {
                                        "Allow editing own messages"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TgDayPalette.rowMeta
                                )
                            }
                            Switch(
                                checked = allowMemberEditOwn,
                                enabled = !conversation.isBroadcastOnly,
                                onCheckedChange = { nextValue ->
                                    val normalized = if (conversation.isBroadcastOnly) false else nextValue
                                    val updated = onUpdateMemberPermissions(
                                        allowMemberReactions,
                                        normalized,
                                        allowMemberDeleteOwn
                                    )
                                    onStatusMessage(
                                        if (updated) {
                                            allowMemberEditOwn = normalized
                                            if (normalized) {
                                                "Members can edit own messages now"
                                            } else {
                                                "Member editing disabled"
                                            }
                                        } else {
                                            "Failed to update edit permission"
                                        }
                                    )
                                }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Members can delete own")
                                Text(
                                    text = if (conversation.isBroadcastOnly) {
                                        "Disabled while admin-only posting is active"
                                    } else {
                                        "Allow deleting own messages"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TgDayPalette.rowMeta
                                )
                            }
                            Switch(
                                checked = allowMemberDeleteOwn,
                                enabled = !conversation.isBroadcastOnly,
                                onCheckedChange = { nextValue ->
                                    val normalized = if (conversation.isBroadcastOnly) false else nextValue
                                    val updated = onUpdateMemberPermissions(
                                        allowMemberReactions,
                                        allowMemberEditOwn,
                                        normalized
                                    )
                                    onStatusMessage(
                                        if (updated) {
                                            allowMemberDeleteOwn = normalized
                                            if (normalized) {
                                                "Members can delete own messages now"
                                            } else {
                                                "Member delete disabled"
                                            }
                                        } else {
                                            "Failed to update delete permission"
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
                HorizontalDivider()
                Text(
                    text = "Participants",
                    style = MaterialTheme.typography.labelLarge
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(members, key = { it.nodeId }) { member ->
                        val roleLabel = when {
                            member.nodeId == localNodeId -> "you"
                            conversation.type == ConversationType.DIRECT -> {
                                if (member.isOnline) "online" else "offline"
                            }
                            member.nodeId == ownerId -> "owner"
                            adminSet.contains(member.nodeId) -> "admin"
                            moderatorSet.contains(member.nodeId) -> "moderator"
                            member.isOnline -> "online"
                            else -> "member"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    enabled = conversation.type != ConversationType.DIRECT
                                ) {
                                    selectedMember = member
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(
                                label = member.alias,
                                seed = member.nodeId,
                                size = 34.dp,
                                online = member.isOnline
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(member.alias)
                                Text(
                                    text = "Node ${member.nodeId} • $roleLabel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TgDayPalette.rowMeta,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    )

    val selected = selectedMember
    if (selected != null && conversation.type != ConversationType.DIRECT) {
        val selectedNodeId = selected.nodeId
        val isOwner = selectedNodeId == ownerId
        val isAdmin = adminSet.contains(selectedNodeId)
        val isModerator = moderatorSet.contains(selectedNodeId)
        val isLocal = selectedNodeId == localNodeId
        val canManageTarget = canManageCollective && !isOwner
        val canRemoveTarget = canManageTarget && !isLocal

        ParticipantRoleDialog(
            member = selected,
            isOwner = isOwner,
            isAdmin = isAdmin,
            isModerator = isModerator,
            canManageTarget = canManageTarget,
            canRemoveTarget = canRemoveTarget,
            onDismiss = { selectedMember = null },
            onPromoteAdmin = {
                val nextAdmins = (conversation.adminNodeIds + selectedNodeId)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                val updated = onUpdateAdmins(nextAdmins)
                onStatusMessage(
                    if (updated) {
                        "${selected.alias} promoted to admin"
                    } else {
                        "Failed to update admin role"
                    }
                )
                if (updated) {
                    selectedMember = null
                }
            },
            onDemoteAdmin = {
                val nextAdmins = conversation.adminNodeIds
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != selectedNodeId }
                    .distinct()
                val updated = onUpdateAdmins(nextAdmins)
                onStatusMessage(
                    if (updated) {
                        "${selected.alias} removed from admins"
                    } else {
                        "Failed to update admin role"
                    }
                )
                if (updated) {
                    selectedMember = null
                }
            },
            onPromoteModerator = {
                val nextModerators = (conversation.moderatorNodeIds + selectedNodeId)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                val updated = onUpdateModerators(nextModerators)
                onStatusMessage(
                    if (updated) {
                        "${selected.alias} promoted to moderator"
                    } else {
                        "Failed to update moderator role"
                    }
                )
                if (updated) {
                    selectedMember = null
                }
            },
            onDemoteModerator = {
                val nextModerators = conversation.moderatorNodeIds
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != selectedNodeId }
                    .distinct()
                val updated = onUpdateModerators(nextModerators)
                onStatusMessage(
                    if (updated) {
                        "${selected.alias} removed from moderators"
                    } else {
                        "Failed to update moderator role"
                    }
                )
                if (updated) {
                    selectedMember = null
                }
            },
            onRemoveMember = {
                val nextMembers = conversation.memberNodeIds
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != selectedNodeId }
                    .distinct()
                val updated = onUpdateMembers(nextMembers)
                onStatusMessage(
                    if (updated) {
                        "${selected.alias} removed from chat"
                    } else {
                        "Failed to remove member"
                    }
                )
                if (updated) {
                    selectedMember = null
                }
            }
        )
    }
}

@Composable
private fun ParticipantRoleDialog(
    member: MeshContact,
    isOwner: Boolean,
    isAdmin: Boolean,
    isModerator: Boolean,
    canManageTarget: Boolean,
    canRemoveTarget: Boolean,
    onDismiss: () -> Unit,
    onPromoteAdmin: () -> Unit,
    onDemoteAdmin: () -> Unit,
    onPromoteModerator: () -> Unit,
    onDemoteModerator: () -> Unit,
    onRemoveMember: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(member.alias) },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        text = {
            Column {
                Text(
                    text = "Node ${member.nodeId}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TgDayPalette.rowMeta
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when {
                        isOwner -> "Role: owner"
                        isAdmin -> "Role: admin"
                        isModerator -> "Role: moderator"
                        else -> "Role: member"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!canManageTarget) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isOwner) {
                            "Owner role cannot be changed here"
                        } else {
                            "You do not have permission to change this role"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowMeta
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    if (!isAdmin) {
                        TextButton(onClick = onPromoteAdmin) {
                            Text("Promote to admin")
                        }
                    } else {
                        TextButton(onClick = onDemoteAdmin) {
                            Text("Remove admin role")
                        }
                    }
                    if (!isAdmin) {
                        if (!isModerator) {
                            TextButton(onClick = onPromoteModerator) {
                                Text("Promote to moderator")
                            }
                        } else {
                            TextButton(onClick = onDemoteModerator) {
                                Text("Remove moderator role")
                            }
                        }
                    }
                    if (canRemoveTarget) {
                        TextButton(onClick = onRemoveMember) {
                            Text("Remove from chat")
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun MediaGalleryDialog(
    messages: List<ChatMessage>,
    onDismiss: () -> Unit,
    onOpenAttachment: (ChatMessage) -> Unit
) {
    val strings = rememberMeshStrings()
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val photoMessages = remember(messages) {
        messages.filter { message -> message.attachmentKind() == AttachmentKind.IMAGE }
    }
    val videoMessages = remember(messages) {
        messages.filter { message -> message.attachmentKind() == AttachmentKind.VIDEO }
    }
    val voiceMessages = remember(messages) {
        messages.filter { message -> message.attachmentKind() == AttachmentKind.AUDIO }
    }
    val fileMessages = remember(messages) {
        messages.filter { message -> message.attachmentKind() == AttachmentKind.FILE }
    }
    val tabs = remember(strings, messages, photoMessages, videoMessages, voiceMessages, fileMessages) {
        listOf(
            strings.all to messages,
            strings.photos to photoMessages,
            strings.videos to videoMessages,
            strings.voice to voiceMessages,
            strings.files to fileMessages
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.close)
            }
        },
        title = { Text(strings.mediaAndFiles) },
        text = {
            if (messages.isEmpty()) {
                Text(strings.noMediaInChat)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    ScrollableTabRow(selectedTabIndex = selectedTab.coerceIn(0, tabs.lastIndex)) {
                        tabs.forEachIndexed { index, (label, tabMessages) ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = { Text("$label (${tabMessages.size})") }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val safeTab = selectedTab.coerceIn(0, tabs.lastIndex)
                    val selectedLabel = tabs[safeTab].first
                    val visibleMessages = tabs[safeTab].second
                    if (visibleMessages.isEmpty()) {
                        Text(
                            text = strings.noMediaInTab(selectedLabel),
                            color = TgDayPalette.rowMeta
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(visibleMessages, key = { it.id }) { message ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenAttachment(message) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = TgDayPalette.searchField
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        GalleryAttachmentThumb(
                                            message = message,
                                            size = if (message.attachmentKind() == AttachmentKind.IMAGE) 96.dp else 56.dp
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = message.attachment?.fileName ?: message.text,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = "${fileSizeShort(message.attachment?.sizeBytes ?: 0)} · ${formatChatTime(message.createdAtMs)}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = TgDayPalette.rowMeta
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ForwardMessageDialog(
    sourceMessages: List<ChatMessage>,
    conversations: List<ConversationSummary>,
    activeConversationId: String?,
    onDismiss: () -> Unit,
    onForward: (List<String>) -> Unit
) {
    val strings = rememberMeshStrings()
    val normalizedSources = remember(sourceMessages) {
        sourceMessages.filterNot { it.isDeleted }
    }
    val sourcePreview = remember(strings, normalizedSources) {
        when {
            normalizedSources.isEmpty() -> strings.noMessagesSelected
            normalizedSources.size == 1 -> {
                val single = normalizedSources.first()
                single.text.trim().ifBlank { replyPreview(single) }
            }
            else -> strings.selectedCount(normalizedSources.size)
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val allTargets = remember(conversations, activeConversationId) {
        conversations.filter { it.id != activeConversationId }
    }
    val targets = remember(allTargets, query) {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) {
            allTargets
        } else {
            allTargets.filter { conversation ->
                conversation.title.lowercase().contains(normalized) ||
                    conversation.subtitle.lowercase().contains(normalized)
            }
        }
    }
    var selectedTargets by remember(allTargets) {
        mutableStateOf(emptySet<String>())
    }
    LaunchedEffect(targets) {
        val availableIds = targets.map { it.id }.toSet()
        val trimmed = selectedTargets.filter { availableIds.contains(it) }.toSet()
        if (trimmed != selectedTargets) {
            selectedTargets = trimmed
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (normalizedSources.size <= 1) {
                        strings.forwardMessage
                    } else {
                        strings.forwardMessages
                    },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { searchOpen = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = strings.search
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedTargets.isNotEmpty(),
                onClick = { onForward(selectedTargets.toList()) }
            ) {
                val count = selectedTargets.size
                Text(
                    if (count <= 1) {
                        strings.forward
                    } else {
                        strings.forwardWithCount(count)
                    }
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = sourcePreview,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider()
                if (searchOpen) {
                    TextField(
                        value = query,
                        onValueChange = { query = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(strings.search) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = strings.search
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    searchOpen = false
                                    query = ""
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = strings.close
                                )
                            }
                        }
                    )
                }
                if (targets.isEmpty()) {
                    Text(
                        text = if (allTargets.isEmpty()) {
                            strings.noOtherChatsAvailable
                        } else {
                            strings.noChatsFound
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TgDayPalette.rowMeta
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(targets, key = { it.id }) { conversation ->
                            val checked = selectedTargets.contains(conversation.id)
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedTargets = if (checked) {
                                            selectedTargets - conversation.id
                                        } else {
                                            selectedTargets + conversation.id
                                        }
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFF4F8FC)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { isChecked ->
                                            selectedTargets = if (isChecked) {
                                                selectedTargets + conversation.id
                                            } else {
                                                selectedTargets - conversation.id
                                            }
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = conversation.title,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = conversation.subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TgDayPalette.rowMeta
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MainTopBar(
    tab: MeshTab,
    status: String,
    isRunning: Boolean
) {
    val strings = rememberMeshStrings()
    Column {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = TgDayPalette.actionBar,
                titleContentColor = TgDayPalette.actionBarTitle
            ),
            title = {
                Column {
                    Text(
                        text = when (tab) {
                            MeshTab.MAP -> strings.meshMapTitle
                            MeshTab.CHATS -> strings.meshHub
                            MeshTab.GROUPS -> strings.communities
                            MeshTab.PROFILE -> strings.profileTab
                            MeshTab.SETTINGS -> strings.settings
                        },
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (isRunning) localizedStatus(status) else strings.meshOffline,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = TgDayPalette.actionBarSubtitle
                    )
                }
            }
        )
        HorizontalDivider(color = TgDayPalette.divider)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatTopBar(
    title: String,
    subtitle: String,
    mediaCount: Int,
    onBack: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenSearch: () -> Unit
) {
    val strings = rememberMeshStrings()
    Column {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = TgDayPalette.actionBar,
                titleContentColor = TgDayPalette.actionBarTitle,
                navigationIconContentColor = TgDayPalette.actionBarIcon
            ),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.back)
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(
                        label = title,
                        seed = title,
                        size = 34.dp,
                        online = true
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = TgDayPalette.actionBarSubtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            actions = {
                IconButton(onClick = onOpenSearch) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = strings.searchInChat
                    )
                }
                IconButton(onClick = onOpenMedia) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = strings.media
                    )
                }
                IconButton(onClick = onOpenInfo) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = strings.chatInfo
                    )
                }
                if (mediaCount > 0) {
                    Text(
                        text = mediaCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowBlue,
                        modifier = Modifier.padding(end = 10.dp)
                    )
                }
            }
        )
        HorizontalDivider(color = TgDayPalette.divider)
    }
}

@Composable
private fun BottomTabs(
    selectedTab: MeshTab,
    unreadChatsCount: Int,
    onSelectTab: (MeshTab) -> Unit
) {
    val strings = rememberMeshStrings()
    val items = listOf(
        TabItem(MeshTab.MAP, strings.map, Icons.Rounded.Map),
        TabItem(MeshTab.CHATS, strings.chats, Icons.Rounded.ChatBubble),
        TabItem(MeshTab.PROFILE, strings.profileTab, Icons.Rounded.Person)
    )
    val selectedIndex = items.indexOfFirst { it.tab == selectedTab }.coerceAtLeast(0)
    val animatedSelectedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(
            stiffness = Spring.StiffnessLow,
            dampingRatio = Spring.DampingRatioNoBouncy
        ),
        label = "selected-tab-glow"
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TgDayPalette.actionBar,
        shadowElevation = 12.dp
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val tabWidth = size.width / items.size
                val center = Offset(
                    x = tabWidth * (animatedSelectedIndex + 0.5f),
                    y = size.height * 0.48f
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MeshUi.glow.copy(alpha = 0.24f),
                            MeshUi.glowAlt.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.height * 0.95f
                    ),
                    radius = size.height * 0.95f,
                    center = center
                )
                drawLine(
                    color = MeshUi.glow.copy(alpha = 0.90f),
                    start = Offset(tabWidth * animatedSelectedIndex + 10f, size.height - 3f),
                    end = Offset(tabWidth * (animatedSelectedIndex + 1f) - 10f, size.height - 3f),
                    strokeWidth = 3f
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items.forEach { item ->
                    val selected = selectedTab == item.tab
                    val showUnreadBadge = item.tab == MeshTab.CHATS && unreadChatsCount > 0
                    val tabBackground = if (selected) {
                        Modifier.background(
                            Brush.verticalGradient(
                                listOf(
                                    MeshUi.glow.copy(alpha = 0.22f),
                                    MeshUi.glowAlt.copy(alpha = 0.10f)
                                )
                            )
                        )
                    } else {
                        Modifier.background(Color.Transparent)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(62.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .then(tabBackground)
                            .border(
                                width = if (selected) 1.dp else 0.dp,
                                color = if (selected) MeshUi.glow.copy(alpha = 0.72f) else Color.Transparent,
                                shape = RoundedCornerShape(18.dp)
                            )
                            .clickable { onSelectTab(item.tab) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                    tint = if (selected) MeshUi.glow else TgDayPalette.rowMeta,
                                    modifier = Modifier.size(23.dp)
                                )
                                if (showUnreadBadge) {
                                    Surface(
                                        modifier = Modifier.align(Alignment.TopEnd),
                                        color = TgDayPalette.unreadPill,
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(
                                            text = unreadChatsCount.toString(),
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) TgDayPalette.actionBarTitle else TgDayPalette.rowMeta,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun PermissionBanner(onRequestPermissions: () -> Unit) {
    val strings = rememberMeshStrings()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = strings.permissionsRequired,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = TgDayPalette.actionBarTitle
            )
            TextButton(onClick = onRequestPermissions) {
                Text(strings.grant, color = TgDayPalette.rowBlue)
            }
        }
    }
}

@Composable
private fun ChatsHome(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    conversations: List<ConversationSummary>,
    globalMessageHits: List<GlobalSearchHit>,
    onOpenConversation: (String) -> Unit,
    onConversationLongPress: (ConversationSummary) -> Unit,
    onOpenDirectDialog: () -> Unit,
    onOpenGroupDialog: () -> Unit,
    onOpenChannelDialog: () -> Unit
) {
    val strings = rememberMeshStrings()
    var selectedFilter by rememberSaveable { mutableStateOf(ChatListFilter.ALL) }
    var searchOpen by rememberSaveable { mutableStateOf(searchQuery.isNotBlank()) }
    val hasGlobalSearch = searchQuery.trim().isNotBlank()
    val filterItems = remember {
        listOf(
            ChatListFilter.ALL,
            ChatListFilter.UNREAD,
            ChatListFilter.GROUPS,
            ChatListFilter.CHANNELS,
            ChatListFilter.ARCHIVED
        )
    }
    val filteredConversations = remember(conversations, selectedFilter) {
        conversations.filter { conversation ->
            when (selectedFilter) {
                ChatListFilter.ALL -> !conversation.isArchived
                ChatListFilter.UNREAD -> conversation.unreadCount > 0 && !conversation.isArchived
                ChatListFilter.GROUPS ->
                    conversation.type == ConversationType.GROUP && !conversation.isArchived
                ChatListFilter.CHANNELS ->
                    conversation.type == ConversationType.CHANNEL && !conversation.isArchived
                ChatListFilter.ARCHIVED -> conversation.isArchived
            }
        }
    }
    val pinnedConversations = remember(filteredConversations, selectedFilter) {
        if (selectedFilter == ChatListFilter.ARCHIVED) {
            emptyList()
        } else {
            filteredConversations.filter { it.isPinned }
        }
    }
    val regularConversations = remember(filteredConversations, selectedFilter) {
        if (selectedFilter == ChatListFilter.ARCHIVED) {
            filteredConversations
        } else {
            filteredConversations.filterNot { it.isPinned }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        MeshBrandHeader(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            subtitle = strings.meshHub,
            trailingContent = {
                IconButton(onClick = { searchOpen = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = strings.search,
                        tint = TgDayPalette.rowMeta
                    )
                }
            }
        )
        if (searchOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(strings.search, color = TgDayPalette.rowMeta) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = strings.search,
                            tint = TgDayPalette.rowMeta
                        )
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                searchOpen = false
                                onSearchQueryChange("")
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = strings.close,
                                tint = TgDayPalette.rowMeta
                            )
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = TgDayPalette.searchField,
                        unfocusedContainerColor = TgDayPalette.searchField,
                        focusedTextColor = TgDayPalette.actionBarTitle,
                        unfocusedTextColor = TgDayPalette.actionBarTitle,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        ScrollableTabRow(
            selectedTabIndex = filterItems.indexOfFirst { it == selectedFilter }
                .coerceAtLeast(0),
            modifier = Modifier.padding(horizontal = 12.dp),
            edgePadding = 8.dp,
            containerColor = Color.Transparent,
            contentColor = TgDayPalette.rowBlue,
            divider = {}
        ) {
            filterItems.forEach { filter ->
                Tab(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    text = { Text(labelForFilter(filter, strings)) }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (conversations.isEmpty() && !hasGlobalSearch) {
            EmptyChatsCard(
                onOpenDirectDialog = onOpenDirectDialog,
                onOpenGroupDialog = onOpenGroupDialog,
                onOpenChannelDialog = onOpenChannelDialog
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                if (hasGlobalSearch) {
                    item(key = "search_messages_header") {
                        SectionHeader(strings.messages)
                    }
                    if (globalMessageHits.isEmpty()) {
                        item(key = "search_messages_empty") {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = TgDayPalette.card)
                            ) {
                                Text(
                                    text = strings.noMessagesFound,
                                    modifier = Modifier.padding(16.dp),
                                    color = TgDayPalette.rowMeta
                                )
                            }
                        }
                    } else {
                        items(globalMessageHits, key = { it.key }) { hit ->
                            MessageSearchRow(
                                hit = hit,
                                onClick = { onOpenConversation(hit.conversationId) }
                            )
                        }
                    }
                }
                if (filteredConversations.isEmpty()) {
                    item(key = "empty_filter_state") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = TgDayPalette.card)
                        ) {
                            Text(
                                text = when (selectedFilter) {
                                    ChatListFilter.ALL -> strings.noChatsYet
                                    ChatListFilter.UNREAD -> strings.noUnreadChats
                                    ChatListFilter.GROUPS -> strings.noGroupChats
                                    ChatListFilter.CHANNELS -> strings.noChannels
                                    ChatListFilter.ARCHIVED -> strings.archiveEmpty
                                },
                                modifier = Modifier.padding(16.dp),
                                color = TgDayPalette.rowMeta
                            )
                        }
                    }
                }
                if (pinnedConversations.isNotEmpty()) {
                    item(key = "pinned_header") {
                        SectionHeader(strings.pinned)
                    }
                    items(pinnedConversations, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            strings = strings,
                            onClick = { onOpenConversation(conversation.id) },
                            onLongPress = { onConversationLongPress(conversation) }
                        )
                    }
                }
                if (regularConversations.isNotEmpty()) {
                    if (pinnedConversations.isNotEmpty()) {
                        item(key = "all_header") {
                            SectionHeader(
                                if (selectedFilter == ChatListFilter.ARCHIVED) {
                                    strings.archived
                                } else {
                                    strings.allChats
                                }
                            )
                        }
                    }
                    items(regularConversations, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            strings = strings,
                            onClick = { onOpenConversation(conversation.id) },
                            onLongPress = { onConversationLongPress(conversation) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupsHome(
    uiState: MeshUiState,
    onOpenConversation: (String) -> Unit,
    onOpenGroupDialog: () -> Unit,
    onOpenChannelDialog: () -> Unit,
    onOpenJoinInviteDialog: () -> Unit,
    onScanInviteQr: () -> Unit,
    inviteStatusMessage: String?
) {
    val strings = rememberMeshStrings()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                modifier = Modifier.weight(1f),
                onClick = onOpenGroupDialog
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(strings.createGroup)
            }
            FilledTonalButton(
                modifier = Modifier.weight(1f),
                onClick = onOpenChannelDialog
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(strings.createChannel)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                modifier = Modifier.weight(1f),
                onClick = onOpenJoinInviteDialog
            ) {
                Text(strings.joinByCode)
            }
            FilledTonalButton(
                modifier = Modifier.weight(1f),
                onClick = onScanInviteQr
            ) {
                Text(strings.scanInviteQr)
            }
        }
        if (!inviteStatusMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = inviteStatusMessage,
                style = MaterialTheme.typography.labelMedium,
                color = TgDayPalette.rowMeta
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        if (uiState.groups.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TgDayPalette.card)
            ) {
                Text(
                    text = strings.noCommunities,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            val channels = uiState.groups.filter { it.type == ConversationType.CHANNEL }
            val classicGroups = uiState.groups.filter { it.type != ConversationType.CHANNEL }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (channels.isNotEmpty()) {
                    item(key = "channels_header") {
                        SectionHeader(strings.channels)
                    }
                    items(channels, key = { it.id }) { group ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenConversation(group.id) },
                            shape = RoundedCornerShape(14.dp),
                            color = TgDayPalette.card
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(
                                    label = group.title,
                                    seed = group.id,
                                    size = 46.dp,
                                    online = false
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = group.title,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = buildString {
                                            append("${group.memberNodeIds.size} ${strings.subscribers}")
                                            if (group.isBroadcastOnly) {
                                                append(" • ${strings.adminOnly}")
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                if (classicGroups.isNotEmpty()) {
                    item(key = "groups_header") {
                        SectionHeader(strings.groups)
                    }
                    items(classicGroups, key = { it.id }) { group ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenConversation(group.id) },
                            shape = RoundedCornerShape(14.dp),
                            color = TgDayPalette.card
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(
                                    label = group.title,
                                    seed = group.id,
                                    size = 46.dp,
                                    online = false
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = group.title,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = buildString {
                                            append("${group.memberNodeIds.size} ${strings.members}")
                                            if (group.isBroadcastOnly) {
                                                append(" • ${strings.adminOnly}")
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshMapHome(
    uiState: MeshUiState
) {
    val strings = rememberMeshStrings()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            item {
                MeshBrandHeader(subtitle = strings.meshMapTitle)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonStatCard(
                        modifier = Modifier.weight(1f),
                        value = uiState.contacts.size.toString(),
                        label = strings.nodesNearby
                    )
                    NeonStatCard(
                        modifier = Modifier.weight(1f),
                        value = uiState.contacts.count { it.isOnline }.toString(),
                        label = strings.activeRoutes
                    )
                    NeonStatCard(
                        modifier = Modifier.weight(1f),
                        value = (uiState.activeFileTransfers.size + uiState.activeIncomingFileTransfers.size).toString(),
                        label = strings.fileQueue
                    )
                }
            }
            item {
                NeonGlassCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = strings.meshMapTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = TgDayPalette.actionBarTitle
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        MeshNodeMap(
                            contacts = uiState.contacts,
                            localAlias = uiState.nodeAlias.ifBlank { strings.localNode },
                            localNodeId = uiState.nodeId
                        )
                        if (uiState.contacts.isEmpty()) {
                            Text(
                                text = strings.routesWakeWhenPeersAppear,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TgDayPalette.rowMeta,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlowButton(
                        text = strings.centerMap,
                        modifier = Modifier.weight(1f),
                        onClick = {}
                    )
                    MeshStatusPill(
                        text = if (uiState.isRunning) strings.meshOn else strings.meshOff,
                        online = uiState.isRunning,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                Text(
                    text = strings.nodeList,
                    style = MaterialTheme.typography.titleMedium,
                    color = TgDayPalette.actionBarTitle
                )
            }
            if (uiState.contacts.isEmpty()) {
                item {
                    NeonGlassCard {
                        Text(
                            text = strings.noNodesYet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TgDayPalette.rowMeta,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(uiState.contacts, key = { it.nodeId }) { contact ->
                    NeonGlassCard {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NeonAvatar(
                                label = contact.alias,
                                seed = contact.nodeId,
                                size = 46.dp,
                                online = contact.isOnline,
                                avatarData = contact.avatarData
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = contact.alias,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TgDayPalette.actionBarTitle
                                )
                                Text(
                                    text = contact.fingerprintShort ?: contact.nodeId,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TgDayPalette.rowMeta,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = if (contact.isOnline) strings.online else strings.offline,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (contact.isOnline) MeshUi.glow else TgDayPalette.rowMeta
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshNodeMap(
    contacts: List<MeshContact>,
    localAlias: String,
    localNodeId: String
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mesh-node-map")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7200), RepeatMode.Restart),
        label = "mesh-node-phase"
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) * 0.36f
        drawCircle(MeshUi.glow.copy(alpha = 0.08f), radius * 1.35f, center)
        drawCircle(MeshUi.glow.copy(alpha = 0.22f), radius * 0.24f, center)
        drawCircle(MeshUi.glow, radius * 0.07f, center)
        contacts.take(10).forEachIndexed { index, contact ->
            val angle = phase * 6.28318f + index * (6.28318f / contacts.take(10).size.coerceAtLeast(1))
            val distance = radius * (0.72f + (index % 3) * 0.17f)
            val point = Offset(
                center.x + cos(angle) * distance,
                center.y + sin(angle) * distance
            )
            val color = if (contact.isOnline) MeshUi.glow else TgDayPalette.rowMeta
            drawLine(color.copy(alpha = 0.38f), center, point, strokeWidth = 2f)
            drawCircle(color.copy(alpha = 0.16f), 22f, point)
            drawCircle(color, 7f, point)
        }
        drawCircle(Color.Transparent, radius, center, style = Stroke(width = 1.2f))
    }
    Text(
        text = "$localAlias • ${localNodeId.take(10)}",
        style = MaterialTheme.typography.labelMedium,
        color = TgDayPalette.rowMeta,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun NeonStatCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    NeonGlassCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MeshUi.glow
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TgDayPalette.rowMeta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MeshProfileHome(
    uiState: MeshUiState,
    aliasDraft: String,
    onAliasDraftChange: (String) -> Unit,
    onSaveAlias: () -> Unit,
    onOpenSettings: (ProfileSettingsSection) -> Unit,
    onToggleMesh: () -> Unit,
    onPickAvatar: () -> Unit
) {
    val strings = rememberMeshStrings()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                MeshBrandHeader(subtitle = strings.profileTab)
            }
            item {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .clickable(onClick = onPickAvatar),
                    contentAlignment = Alignment.Center
                ) {
                    NeonAvatar(
                    label = uiState.nodeAlias.ifBlank { BRAND_NAME },
                    seed = uiState.nodeId,
                    size = 104.dp,
                    online = uiState.isRunning,
                    avatarData = uiState.nodeAvatarData
                    )
                }
                Text(
                    text = strings.changeAvatar,
                    style = MaterialTheme.typography.labelMedium,
                    color = MeshUi.glow,
                    modifier = Modifier.clickable(onClick = onPickAvatar)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = uiState.nodeAlias.ifBlank { strings.localNode },
                    style = MaterialTheme.typography.headlineSmall,
                    color = TgDayPalette.actionBarTitle
                )
                Text(
                    text = if (uiState.isRunning) strings.online else strings.offline,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (uiState.isRunning) MeshUi.glow else TgDayPalette.rowMeta
                )
                Spacer(modifier = Modifier.height(8.dp))
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    if (maxWidth < 380.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = aliasDraft,
                                onValueChange = onAliasDraftChange,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(strings.displayName) }
                            )
                            FilledTonalButton(
                                onClick = onSaveAlias,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(strings.save)
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = aliasDraft,
                                onValueChange = onAliasDraftChange,
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text(strings.displayName) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledTonalButton(onClick = onSaveAlias) {
                                Text(strings.save)
                            }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonStatCard(
                        modifier = Modifier.weight(1f),
                        value = uiState.conversations.size.toString(),
                        label = strings.chats
                    )
                    NeonStatCard(
                        modifier = Modifier.weight(1f),
                        value = uiState.messages.size.toString(),
                        label = strings.messagesStat
                    )
                    NeonStatCard(
                        modifier = Modifier.weight(1f),
                        value = uiState.groups.size.toString(),
                        label = strings.groupsStat
                    )
                }
            }
            item {
                NeonGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MeshStatusRow(
                            isRunning = uiState.isRunning,
                            peersCount = uiState.peers.count { it.isConnected },
                            strings = strings,
                            onToggleMesh = onToggleMesh
                        )
                        ProfileActionRow(Icons.Rounded.Settings, strings.networkSettings) {
                            onOpenSettings(ProfileSettingsSection.NETWORK)
                        }
                        ProfileActionRow(Icons.Rounded.Lock, strings.security) {
                            onOpenSettings(ProfileSettingsSection.SECURITY)
                        }
                        ProfileActionRow(Icons.Rounded.Notifications, strings.notifications) {
                            onOpenSettings(ProfileSettingsSection.NOTIFICATIONS)
                        }
                        ProfileActionRow(Icons.Rounded.Palette, strings.appearance) {
                            onOpenSettings(ProfileSettingsSection.APPEARANCE)
                        }
                        ProfileActionRow(Icons.Rounded.Group, strings.contacts) {
                            onOpenSettings(ProfileSettingsSection.CONTACTS)
                        }
                        ProfileActionRow(Icons.Rounded.Info, strings.dataUsage) {
                            onOpenSettings(ProfileSettingsSection.DATA)
                        }
                        ProfileActionRow(Icons.Rounded.Save, strings.backup) {
                            onOpenSettings(ProfileSettingsSection.BACKUP)
                        }
                        ProfileActionRow(Icons.Rounded.Bookmark, strings.projectSupport) {
                            onOpenSettings(ProfileSettingsSection.SUPPORT)
                        }
                        ProfileActionRow(Icons.Rounded.Info, strings.updates) {
                            onOpenSettings(ProfileSettingsSection.UPDATES)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MeshUi.glow, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = TgDayPalette.rowText
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = TgDayPalette.rowMeta,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ProjectSupportCard(strings: MeshStrings) {
    val context = LocalContext.current
    val supportUrl = BuildConfig.MESHGRAM_DONATION_URL.trim()
    val qrBitmap = remember(supportUrl) {
        supportUrl.takeIf { it.startsWith("https://", ignoreCase = true) }
            ?.let(::generateInviteQrBitmap)
    }

    SettingsCard(title = strings.projectSupport) {
        Text(strings.projectSupportDescription, color = TgDayPalette.rowMeta)
        Spacer(modifier = Modifier.height(12.dp))
        if (supportUrl.isBlank() || qrBitmap == null) {
            Text(strings.linkNotConfigured, color = TgDayPalette.rowAccent)
        } else {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = strings.projectSupport,
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(10.dp)
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = supportUrl,
                style = MaterialTheme.typography.labelSmall,
                color = TgDayPalette.rowMeta,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            FilledTonalButton(
                onClick = { openMeshExternalLink(context, supportUrl) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(strings.openLink)
            }
        }
    }
}

@Composable
private fun UpdateInfoCard(strings: MeshStrings) {
    val context = LocalContext.current
    val updateUrl = BuildConfig.MESHGRAM_UPDATE_MANIFEST_URL.trim()

    SettingsCard(title = strings.updates) {
        Text(strings.updatesDescription, color = TgDayPalette.rowMeta)
        Spacer(modifier = Modifier.height(12.dp))
        if (updateUrl.isBlank()) {
            Text(strings.linkNotConfigured, color = TgDayPalette.rowAccent)
        } else {
            Text(
                text = updateUrl,
                style = MaterialTheme.typography.labelSmall,
                color = TgDayPalette.rowMeta,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            FilledTonalButton(
                onClick = { openMeshExternalLink(context, updateUrl) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(strings.openLink)
            }
        }
    }
}

private fun openMeshExternalLink(context: Context, rawUrl: String) {
    val url = rawUrl.trim()
    if (!url.startsWith("https://", ignoreCase = true)) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure { error ->
        if (error is ActivityNotFoundException) return@onFailure
    }
}

@Composable
private fun NeonAvatar(
    label: String,
    seed: String,
    size: Dp,
    online: Boolean,
    avatarData: String = ""
) {
    val avatarBitmap = remember(avatarData) { decodeAvatarBitmap(avatarData) }
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(2.dp, MeshUi.glow.copy(alpha = 0.82f), CircleShape)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(MeshUi.glowAlt.copy(alpha = 0.85f), colorFromSeed(seed))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (avatarBitmap != null) {
                Image(
                    bitmap = avatarBitmap.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = label.take(1).uppercase(Locale.getDefault()).ifBlank { "M" },
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
        if (online) {
            Box(
                modifier = Modifier
                    .size((size.value * 0.22f).dp)
                    .clip(CircleShape)
                    .background(Color(0xFF31F58D))
                    .border(2.dp, Color(0xFF10131B), CircleShape)
            )
        }
    }
}

@Composable
private fun AppearanceCard(
    visualThemePreset: MeshVisualPreset,
    onVisualThemeChange: (MeshVisualPreset) -> Unit
) {
    val strings = rememberMeshStrings()
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = TgDayPalette.card)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(strings.appearance, style = MaterialTheme.typography.titleMedium, color = TgDayPalette.actionBarTitle)
            Text(
                text = strings.appearanceSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TgDayPalette.rowMeta
            )
            MeshVisualPreset.values().forEach { preset ->
                val selected = preset == visualThemePreset
                val palette = preset.palette()
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (selected) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = palette.rowBlue.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(16.dp)
                                )
                            } else {
                                Modifier
                            }
                        )
                        .clickable { onVisualThemeChange(preset) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) {
                        palette.rowBlue.copy(alpha = 0.10f)
                    } else {
                        TgDayPalette.searchField
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy((-7).dp)) {
                            ThemeDot(color = palette.bubbleOut)
                            ThemeDot(color = palette.chatGradientEnd)
                            ThemeDot(color = palette.rowAccent)
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strings.themeTitles[preset]?.first ?: preset.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = TgDayPalette.actionBarTitle
                            )
                            Text(
                                text = strings.themeTitles[preset]?.second ?: preset.subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = TgDayPalette.rowMeta
                            )
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Rounded.Done,
                                contentDescription = strings.selected,
                                tint = palette.rowBlue
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeDot(color: Color) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(color)
            .border(2.dp, TgDayPalette.card, CircleShape)
    )
}

@Composable
private fun MeshStatusRow(
    isRunning: Boolean,
    peersCount: Int,
    strings: MeshStrings,
    onToggleMesh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(TgDayPalette.searchField.copy(alpha = 0.68f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (isRunning) Color(0xFF31F58D) else TgDayPalette.rowMeta)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isRunning) strings.meshOn else strings.meshOff,
                style = MaterialTheme.typography.bodyMedium,
                color = TgDayPalette.rowText
            )
            Text(
                text = "$peersCount ${strings.peers}",
                style = MaterialTheme.typography.labelSmall,
                color = TgDayPalette.rowMeta
            )
        }
        Switch(checked = isRunning, onCheckedChange = { onToggleMesh() })
    }
}

@Composable
private fun MeshStatusPill(
    text: String,
    online: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(28.dp),
        color = TgDayPalette.searchField.copy(alpha = 0.82f),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = (if (online) MeshUi.glow else MeshUi.glowAlt).copy(alpha = 0.78f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (online) Color(0xFF31F58D) else TgDayPalette.rowMeta)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = TgDayPalette.rowText
            )
        }
    }
}

@Composable
private fun SettingsHome(
    section: ProfileSettingsSection,
    uiState: MeshUiState,
    aliasDraft: String,
    onAliasDraftChange: (String) -> Unit,
    onSaveAlias: () -> Unit,
    relayEnabledDraft: Boolean,
    onRelayEnabledChange: (Boolean) -> Unit,
    relayUrlDraft: String,
    onRelayUrlDraftChange: (String) -> Unit,
    onSaveRelay: () -> Unit,
    visualThemePreset: MeshVisualPreset,
    onVisualThemeChange: (MeshVisualPreset) -> Unit,
    relayStatusMessage: String?,
    backupStatusMessage: String?,
    appLockEnabled: Boolean,
    hasAppPasscode: Boolean,
    onEnableAppLock: (String) -> Boolean,
    onDisableAppLock: (String) -> Boolean,
    onChangeAppLockPin: (String, String) -> Boolean,
    onLockNow: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onBack: () -> Unit,
    onToggleMesh: () -> Unit,
    notificationSound: String,
    vibrationLevel: String,
    onNotificationSoundChange: (String) -> Unit,
    onVibrationLevelChange: (String) -> Unit
) {
    val context = LocalContext.current
    val strings = rememberMeshStrings()
    var transientCacheBytes by remember { mutableStateOf(transientCacheSizeBytes(context)) }
    var cacheStatusMessage by remember { mutableStateOf<String?>(null) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinDialogMode by remember { mutableStateOf("enable") }
    var pinDraft by rememberSaveable { mutableStateOf("") }
    var pinConfirmDraft by rememberSaveable { mutableStateOf("") }

    val sectionTitle = when (section) {
        ProfileSettingsSection.NETWORK -> strings.networkSettings
        ProfileSettingsSection.SECURITY -> strings.security
        ProfileSettingsSection.NOTIFICATIONS -> strings.notifications
        ProfileSettingsSection.APPEARANCE -> strings.appearance
        ProfileSettingsSection.CONTACTS -> strings.contacts
        ProfileSettingsSection.DATA -> strings.dataUsage
        ProfileSettingsSection.BACKUP -> strings.backup
        ProfileSettingsSection.SUPPORT -> strings.projectSupport
        ProfileSettingsSection.UPDATES -> strings.updates
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 18.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.back)
                }
                Column {
                    Text(sectionTitle, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = strings.profile,
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowMeta
                    )
                }
            }
        }

        item {
            when (section) {
                ProfileSettingsSection.NETWORK -> {
                    SettingsCard(title = strings.networkSettings) {
                        MeshStatusRow(
                            isRunning = uiState.isRunning,
                            peersCount = uiState.peers.count { it.isConnected },
                            strings = strings,
                            onToggleMesh = onToggleMesh
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = strings.offlineOnlyDescription,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TgDayPalette.rowMeta
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = strings.discoveryDescription,
                            style = MaterialTheme.typography.labelMedium,
                            color = TgDayPalette.rowBlue
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(strings.relayEnabledLabel, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = strings.relayEnabledHint,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TgDayPalette.rowMeta
                                )
                            }
                            Switch(
                                checked = relayEnabledDraft,
                                onCheckedChange = onRelayEnabledChange
                            )
                        }
                        OutlinedTextField(
                            value = relayUrlDraft,
                            onValueChange = onRelayUrlDraftChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(strings.relayUrlLabel) },
                            placeholder = { Text(strings.relayUrlPlaceholder) },
                            singleLine = true
                        )
                        FilledTonalButton(onClick = onSaveRelay) {
                            Text(strings.saveRelaySettings)
                        }
                        Text(
                            text = if (uiState.relayConnected) {
                                strings.relayConnectedStatus
                            } else if (uiState.relayEnabled && uiState.relayUrl.isNotBlank()) {
                                strings.relayWaitingStatus
                            } else {
                                strings.bleOnlyStatus
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = TgDayPalette.rowMeta
                        )
                        if (!relayStatusMessage.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(relayStatusMessage, color = TgDayPalette.rowAccent)
                        }
                    }
                }

                ProfileSettingsSection.SECURITY -> {
                    SettingsCard(title = strings.security) {
                        Text(
                            text = "${strings.encryptionLabel}: ${uiState.encryptionLabel}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${strings.fingerprint}: ${uiState.nodeFingerprint}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TgDayPalette.rowMeta
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(strings.securityHint, color = TgDayPalette.rowMeta)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (appLockEnabled && hasAppPasscode) {
                                strings.pinEnabled
                            } else {
                                strings.pinDisabled
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = TgDayPalette.rowMeta
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    pinDialogMode = if (appLockEnabled && hasAppPasscode) "disable" else "enable"
                                    pinDraft = ""
                                    pinConfirmDraft = ""
                                    showPinDialog = true
                                }
                            ) {
                                Text(if (appLockEnabled && hasAppPasscode) strings.disablePin else strings.enablePin)
                            }
                            FilledTonalButton(
                                onClick = onLockNow,
                                enabled = appLockEnabled && hasAppPasscode
                            ) {
                                Text(strings.lockNow)
                            }
                        }
                    }
                }

                ProfileSettingsSection.NOTIFICATIONS -> {
                    SettingsCard(title = strings.notifications) {
                        Text(strings.sound, style = MaterialTheme.typography.titleSmall)
                        NotificationChoice(
                            label = strings.systemDefault,
                            selected = notificationSound == "default",
                            onClick = { onNotificationSoundChange("default") }
                        )
                        NotificationChoice(
                            label = strings.silent,
                            selected = notificationSound == "silent",
                            onClick = { onNotificationSoundChange("silent") }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(strings.vibration, style = MaterialTheme.typography.titleSmall)
                        listOf(
                            "off" to strings.vibrationOff,
                            "soft" to strings.vibrationSoft,
                            "normal" to strings.vibrationNormal,
                            "strong" to strings.vibrationStrong
                        ).forEach { (value, label) ->
                            NotificationChoice(
                                label = label,
                                selected = vibrationLevel == value,
                                onClick = { onVibrationLevelChange(value) }
                            )
                        }
                    }
                }

                ProfileSettingsSection.APPEARANCE -> {
                    AppearanceCard(
                        visualThemePreset = visualThemePreset,
                        onVisualThemeChange = onVisualThemeChange
                    )
                }

                ProfileSettingsSection.CONTACTS -> {
                    SettingsCard(title = strings.recipients) {
                        Text(
                            text = strings.savedByNode,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TgDayPalette.rowMeta
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        if (uiState.contacts.isEmpty()) {
                            Text(strings.noRecipients, color = TgDayPalette.rowMeta)
                        } else {
                            uiState.contacts.take(24).forEach { contact ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Avatar(
                                        label = contact.alias,
                                        seed = contact.nodeId,
                                        size = 38.dp,
                                        online = contact.isOnline,
                                        avatarData = contact.avatarData
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(contact.alias)
                                        Text(
                                            text = "${strings.node} ${contact.nodeId}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TgDayPalette.rowMeta
                                        )
                                    }
                                    Text(
                                        text = if (contact.isOnline) strings.online else strings.saved,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (contact.isOnline) TgDayPalette.rowBlue else TgDayPalette.rowMeta
                                    )
                                }
                                HorizontalDivider(color = TgDayPalette.divider)
                            }
                        }
                    }
                }

                ProfileSettingsSection.DATA -> {
                    SettingsCard(title = strings.storagePrivacy) {
                        Text(
                            text = "${strings.tempPreviews}: ${fileSizeShort(transientCacheBytes)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(strings.encryptedHistoryKept, color = TgDayPalette.rowMeta)
                        Spacer(modifier = Modifier.height(10.dp))
                        FilledTonalButton(
                            onClick = {
                                val removed = clearTransientDecryptedCaches(context)
                                transientCacheBytes = transientCacheSizeBytes(context)
                                cacheStatusMessage = if (removed > 0) {
                                    strings.clearedTempFiles(removed)
                                } else {
                                    strings.tempCacheEmpty
                                }
                            }
                        ) {
                            Text(strings.clearTempCache)
                        }
                        if (!cacheStatusMessage.isNullOrBlank()) {
                            Text(cacheStatusMessage.orEmpty(), color = TgDayPalette.rowAccent)
                        }
                    }
                }

                ProfileSettingsSection.BACKUP -> {
                    SettingsCard(title = strings.backup) {
                        Text(strings.backupDescription, color = TgDayPalette.rowMeta)
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = onExportBackup) { Text(strings.export) }
                            FilledTonalButton(onClick = onImportBackup) { Text(strings.importText) }
                        }
                        if (!backupStatusMessage.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(backupStatusMessage, color = TgDayPalette.rowAccent)
                        }
                    }
                }

                ProfileSettingsSection.SUPPORT -> {
                    ProjectSupportCard(strings = strings)
                }

                ProfileSettingsSection.UPDATES -> {
                    UpdateInfoCard(strings = strings)
                }
            }
        }
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text(if (pinDialogMode == "enable") strings.enablePin else strings.disablePin) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pinDraft,
                        onValueChange = { pinDraft = it.filter(Char::isDigit).take(PASSCODE_MAX_LEN) },
                        singleLine = true,
                        label = { Text(strings.pinCode) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    if (pinDialogMode == "enable") {
                        OutlinedTextField(
                            value = pinConfirmDraft,
                            onValueChange = { pinConfirmDraft = it.filter(Char::isDigit).take(PASSCODE_MAX_LEN) },
                            singleLine = true,
                            label = { Text(strings.repeatPin) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val valid = pinDraft.length in PASSCODE_MIN_LEN..PASSCODE_MAX_LEN
                        val success = if (!valid) {
                            false
                        } else if (pinDialogMode == "enable") {
                            pinDraft == pinConfirmDraft && onEnableAppLock(pinDraft)
                        } else {
                            onDisableAppLock(pinDraft)
                        }
                        if (success) showPinDialog = false
                    },
                    enabled = pinDraft.length in PASSCODE_MIN_LEN..PASSCODE_MAX_LEN
                ) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) { Text(strings.cancel) }
            }
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = TgDayPalette.card.copy(alpha = 0.92f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleLarge)
                content()
            }
        )
    }
}

@Composable
private fun NotificationChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, color = TgDayPalette.rowText)
    }
}

@Composable
private fun LegacySettingsHome(
    section: ProfileSettingsSection,
    uiState: MeshUiState,
    aliasDraft: String,
    onAliasDraftChange: (String) -> Unit,
    onSaveAlias: () -> Unit,
    relayUrlDraft: String,
    onSaveRelay: () -> Unit,
    visualThemePreset: MeshVisualPreset,
    onVisualThemeChange: (MeshVisualPreset) -> Unit,
    relayStatusMessage: String?,
    backupStatusMessage: String?,
    appLockEnabled: Boolean,
    hasAppPasscode: Boolean,
    onEnableAppLock: (String) -> Boolean,
    onDisableAppLock: (String) -> Boolean,
    onChangeAppLockPin: (String, String) -> Boolean,
    onLockNow: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onBack: () -> Unit,
    onToggleMesh: () -> Unit,
    notificationSound: String,
    vibrationLevel: String,
    onNotificationSoundChange: (String) -> Unit,
    onVibrationLevelChange: (String) -> Unit
) {
    val context = LocalContext.current
    val strings = rememberMeshStrings()
    var showEnableLockDialog by remember { mutableStateOf(false) }
    var showDisableLockDialog by remember { mutableStateOf(false) }
    var showChangeLockDialog by remember { mutableStateOf(false) }
    var appLockStatusMessage by remember { mutableStateOf<String?>(null) }
    var transientCacheBytes by remember { mutableStateOf(transientCacheSizeBytes(context)) }
    var cacheStatusMessage by remember { mutableStateOf<String?>(null) }
    var legacyRelayEnabledDraft by rememberSaveable { mutableStateOf(uiState.relayEnabled) }
    var legacyRelayUrlDraft by rememberSaveable { mutableStateOf(relayUrlDraft) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = TgDayPalette.card)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(
                            label = uiState.nodeAlias,
                            seed = uiState.nodeId,
                            size = 52.dp,
                            online = uiState.isRunning
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(strings.profile, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Node ${uiState.nodeId}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = aliasDraft,
                            onValueChange = onAliasDraftChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text(strings.displayName) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(onClick = onSaveAlias) {
                            Icon(Icons.Rounded.Done, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.save)
                        }
                    }
                }
            }
        }

        item {
            AppearanceCard(
                visualThemePreset = visualThemePreset,
                onVisualThemeChange = onVisualThemeChange
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = TgDayPalette.card)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(strings.storagePrivacy, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${strings.tempPreviews}: ${fileSizeShort(transientCacheBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TgDayPalette.rowText
                    )
                    Text(
                        text = strings.encryptedHistoryKept,
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowMeta
                    )
                    FilledTonalButton(
                        onClick = {
                            val removed = clearTransientDecryptedCaches(context)
                            transientCacheBytes = transientCacheSizeBytes(context)
                            cacheStatusMessage = if (removed > 0) {
                                strings.clearedTempFiles(removed)
                            } else {
                                strings.tempCacheEmpty
                            }
                        }
                    ) {
                        Text(strings.clearTempCache)
                    }
                    if (!cacheStatusMessage.isNullOrBlank()) {
                        Text(
                            text = cacheStatusMessage.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = TgDayPalette.rowAccent
                        )
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = TgDayPalette.card)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.bluetoothMeshNetwork, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = strings.offlineOnlyDescription,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TgDayPalette.rowMeta
                            )
                        }
                        Switch(
                            checked = legacyRelayEnabledDraft,
                            onCheckedChange = { legacyRelayEnabledDraft = it },
                            enabled = true
                        )
                    }
                    Text(
                        text = strings.discoveryDescription,
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowBlue
                    )
                    Text(
                        text = strings.relayEnabledHint,
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowMeta
                    )
                    OutlinedTextField(
                        value = legacyRelayUrlDraft,
                        onValueChange = { legacyRelayUrlDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.relayUrlLabel) },
                        singleLine = true,
                        placeholder = { Text(strings.relayUrlPlaceholder) },
                        enabled = true
                    )
                    FilledTonalButton(
                        onClick = onSaveRelay,
                        enabled = true
                    ) {
                        Text(strings.saveRelaySettings)
                    }
                    if (!relayStatusMessage.isNullOrBlank()) {
                        Text(
                            text = relayStatusMessage,
                            style = MaterialTheme.typography.labelMedium,
                            color = TgDayPalette.rowAccent
                        )
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = TgDayPalette.card)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Known Recipients", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Saved by Node ID. Delivery is always addressed to exact recipient.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TgDayPalette.rowMeta
                    )
                    HorizontalDivider()
                    if (uiState.contacts.isEmpty()) {
                        Text(
                            text = "No recipients yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TgDayPalette.rowMeta
                        )
                    } else {
                        uiState.contacts.take(24).forEach { contact ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(
                                    label = contact.alias,
                                    seed = contact.nodeId,
                                    size = 36.dp,
                                    online = contact.isOnline
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = contact.alias,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = "Node ${contact.nodeId} • ${contact.fingerprintShort ?: "no-fp"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TgDayPalette.rowMeta
                                    )
                                }
                                Text(
                                    text = if (contact.isOnline) "online" else "saved",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (contact.isOnline) TgDayPalette.rowBlue else TgDayPalette.rowMeta
                                )
                            }
                            HorizontalDivider(color = TgDayPalette.divider)
                        }
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = TgDayPalette.card)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Text("Security", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "E2E active: ${uiState.encryptionLabel}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Fingerprint", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = uiState.nodeFingerprint,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Contacts: ${uiState.contacts.size} • Communities: ${uiState.groups.size}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("App Lock", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (appLockEnabled && hasAppPasscode) {
                            "PIN lock is enabled. App auto-locks when you leave it."
                        } else {
                            "PIN lock is disabled."
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowMeta
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                if (appLockEnabled && hasAppPasscode) {
                                    showDisableLockDialog = true
                                } else {
                                    showEnableLockDialog = true
                                }
                            }
                        ) {
                            Text(if (appLockEnabled && hasAppPasscode) "Disable PIN" else "Enable PIN")
                        }
                        FilledTonalButton(
                            onClick = { showChangeLockDialog = true },
                            enabled = hasAppPasscode
                        ) {
                            Text("Change PIN")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            onLockNow()
                            appLockStatusMessage = "App locked"
                        },
                        enabled = appLockEnabled && hasAppPasscode
                    ) {
                        Text("Lock now")
                    }
                    if (!appLockStatusMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = appLockStatusMessage ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            color = TgDayPalette.rowAccent
                        )
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = TgDayPalette.card)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Text("Backup", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Encrypted export/import for device migration",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TgDayPalette.rowMeta
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onExportBackup) {
                            Text("Export")
                        }
                        FilledTonalButton(onClick = onImportBackup) {
                            Text("Import")
                        }
                    }
                    if (!backupStatusMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = backupStatusMessage,
                            style = MaterialTheme.typography.labelMedium,
                            color = TgDayPalette.rowAccent
                        )
                    }
                }
            }
        }
    }

    if (showEnableLockDialog) {
        var pinDraft by rememberSaveable { mutableStateOf("") }
        var pinConfirmDraft by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showEnableLockDialog = false },
            title = { Text("Enable App PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "PIN length: $PASSCODE_MIN_LEN-$PASSCODE_MAX_LEN digits.",
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowMeta
                    )
                    OutlinedTextField(
                        value = pinDraft,
                        onValueChange = { next ->
                            pinDraft = next.filter { it.isDigit() }.take(PASSCODE_MAX_LEN)
                        },
                        singleLine = true,
                        label = { Text("New PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    OutlinedTextField(
                        value = pinConfirmDraft,
                        onValueChange = { next ->
                            pinConfirmDraft = next.filter { it.isDigit() }.take(PASSCODE_MAX_LEN)
                        },
                        singleLine = true,
                        label = { Text("Repeat PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
            },
            confirmButton = {
                val validLength = pinDraft.length in PASSCODE_MIN_LEN..PASSCODE_MAX_LEN
                TextButton(
                    onClick = {
                        if (pinDraft != pinConfirmDraft) {
                            appLockStatusMessage = "PIN mismatch"
                            return@TextButton
                        }
                        val enabled = onEnableAppLock(pinDraft)
                        appLockStatusMessage = if (enabled) {
                            "PIN lock enabled"
                        } else {
                            "Failed to enable PIN (check format)"
                        }
                        if (enabled) {
                            showEnableLockDialog = false
                            pinDraft = ""
                            pinConfirmDraft = ""
                        }
                    },
                    enabled = validLength
                ) {
                    Text("Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnableLockDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDisableLockDialog) {
        var pinDraft by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showDisableLockDialog = false },
            title = { Text("Disable App PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter current PIN to disable lock.",
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowMeta
                    )
                    OutlinedTextField(
                        value = pinDraft,
                        onValueChange = { next ->
                            pinDraft = next.filter { it.isDigit() }.take(PASSCODE_MAX_LEN)
                        },
                        singleLine = true,
                        label = { Text("Current PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val disabled = onDisableAppLock(pinDraft)
                        appLockStatusMessage = if (disabled) {
                            "PIN lock disabled"
                        } else {
                            "Wrong PIN"
                        }
                        if (disabled) {
                            showDisableLockDialog = false
                            pinDraft = ""
                        }
                    },
                    enabled = pinDraft.length in PASSCODE_MIN_LEN..PASSCODE_MAX_LEN
                ) {
                    Text("Disable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableLockDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showChangeLockDialog) {
        var currentPin by rememberSaveable { mutableStateOf("") }
        var nextPin by rememberSaveable { mutableStateOf("") }
        var nextPinConfirm by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showChangeLockDialog = false },
            title = { Text("Change PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { next ->
                            currentPin = next.filter { it.isDigit() }.take(PASSCODE_MAX_LEN)
                        },
                        singleLine = true,
                        label = { Text("Current PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    OutlinedTextField(
                        value = nextPin,
                        onValueChange = { next ->
                            nextPin = next.filter { it.isDigit() }.take(PASSCODE_MAX_LEN)
                        },
                        singleLine = true,
                        label = { Text("New PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    OutlinedTextField(
                        value = nextPinConfirm,
                        onValueChange = { next ->
                            nextPinConfirm = next.filter { it.isDigit() }.take(PASSCODE_MAX_LEN)
                        },
                        singleLine = true,
                        label = { Text("Repeat new PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
            },
            confirmButton = {
                val canSubmit = currentPin.length in PASSCODE_MIN_LEN..PASSCODE_MAX_LEN &&
                    nextPin.length in PASSCODE_MIN_LEN..PASSCODE_MAX_LEN
                TextButton(
                    onClick = {
                        if (nextPin != nextPinConfirm) {
                            appLockStatusMessage = "New PIN mismatch"
                            return@TextButton
                        }
                        val changed = onChangeAppLockPin(currentPin, nextPin)
                        appLockStatusMessage = if (changed) {
                            "PIN updated"
                        } else {
                            "Failed to update PIN"
                        }
                        if (changed) {
                            showChangeLockDialog = false
                            currentPin = ""
                            nextPin = ""
                            nextPinConfirm = ""
                        }
                    },
                    enabled = canSubmit
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangeLockDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ChatThread(
    uiState: MeshUiState,
    messages: List<ChatMessage>,
    messageDraft: String,
    searchQuery: String,
    searchOpen: Boolean,
    pinnedMessage: ChatMessage?,
    replyToMessage: ChatMessage?,
    editingMessage: ChatMessage?,
    selectedMessageIds: Set<String>,
    scheduledMessages: List<ScheduledMessageRecord>,
    fileTransfers: List<OutgoingFileTransferProgress>,
    incomingFileTransfers: List<IncomingFileTransferProgress>,
    savedTags: List<String>,
    selectedSavedTag: String?,
    onDraftChange: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onSavedTagSelected: (String?) -> Unit,
    onCancelScheduledMessage: (String) -> Boolean,
    onCancelFileTransfer: (String) -> Boolean,
    onRetryFileTransfer: (String) -> Boolean,
    onRetryIncomingFileTransfer: (String) -> Boolean,
    onCancelIncomingFileTransfer: (String) -> Boolean,
    onCancelReply: () -> Unit,
    onCancelEditing: () -> Unit,
    onPickFile: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onRecordVideoNote: () -> Unit,
    onOpenAttachment: (ChatMessage) -> Unit,
    onMessageLongPress: (ChatMessage) -> Unit,
    onToggleMessageSelection: (String) -> Unit,
    onClearMessageSelection: () -> Unit,
    onDeleteSelectedMessages: () -> Unit,
    onForwardSelectedMessages: () -> Unit,
    isVoiceRecording: Boolean,
    isVoiceRecordingPaused: Boolean,
    canPauseVoiceRecording: Boolean,
    voiceRecordingElapsedMs: Long,
    voiceAmplitudeSamples: List<Float>,
    onToggleVoiceRecording: () -> Unit,
    onPauseResumeVoiceRecording: () -> Unit,
    onCancelVoiceRecording: () -> Unit,
    onSchedule: (Long) -> Boolean,
    onSend: () -> Unit,
    onToggleMesh: () -> Unit
) {
    val strings = rememberMeshStrings()
    val canPost = uiState.activeConversationCanPost
    val isSelectionMode = selectedMessageIds.isNotEmpty()
    var showAttachmentTray by rememberSaveable(uiState.activeConversationId) { mutableStateOf(false) }
    var showFormattingHelp by rememberSaveable(uiState.activeConversationId) { mutableStateOf(false) }
    var showScheduleDialog by rememberSaveable(uiState.activeConversationId) { mutableStateOf(false) }
    val messageListState = remember(uiState.activeConversationId) { LazyListState() }
    LaunchedEffect(uiState.activeConversationId, messages.size) {
        if (messages.isNotEmpty()) {
            messageListState.scrollToItem(messages.lastIndex)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(TgDayPalette.chatBackground, TgDayPalette.chatGradientEnd)
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onToggleMesh) {
                Text(
                    text = if (uiState.isRunning) strings.meshOn else strings.meshOff,
                    color = TgDayPalette.rowMeta,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        if (searchOpen) {
            TextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                singleLine = true,
                placeholder = {
                    Text(
                        text = strings.searchInChat,
                        color = TgDayPalette.rowMeta
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = strings.searchInChat,
                        tint = TgDayPalette.rowMeta
                    )
                },
                trailingIcon = {
                    IconButton(onClick = onCloseSearch) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = strings.close,
                            tint = TgDayPalette.rowMeta
                        )
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = TgDayPalette.card,
                    unfocusedContainerColor = TgDayPalette.card,
                    focusedTextColor = TgDayPalette.actionBarTitle,
                    unfocusedTextColor = TgDayPalette.actionBarTitle,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(18.dp)
            )
        }

        if (isSavedMessagesConversation(uiState.activeConversationId.orEmpty()) && savedTags.isNotEmpty()) {
            val selectedIndex = savedTags.indexOfFirst { tag ->
                tag.equals(selectedSavedTag, ignoreCase = true)
            }.let { index -> if (index < 0) 0 else index + 1 }
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 8.dp,
                containerColor = TgDayPalette.card,
                contentColor = TgDayPalette.rowBlue,
                divider = {}
            ) {
                Tab(
                    selected = selectedSavedTag == null,
                    onClick = { onSavedTagSelected(null) },
                    text = { Text(strings.all) }
                )
                savedTags.forEach { tag ->
                    Tab(
                        selected = tag.equals(selectedSavedTag, ignoreCase = true),
                        onClick = { onSavedTagSelected(tag) },
                        text = { Text("#$tag") }
                    )
                }
            }
        }

        if (isSelectionMode) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = TgDayPalette.card
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = strings.selectedCount(selectedMessageIds.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = TgDayPalette.actionBarTitle,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onForwardSelectedMessages) {
                        Text(strings.forward)
                    }
                    TextButton(onClick = onDeleteSelectedMessages) {
                        Text(strings.delete)
                    }
                    TextButton(onClick = onClearMessageSelection) {
                        Text(strings.cancel)
                    }
                }
            }
        }

        if (pinnedMessage != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = TgDayPalette.card
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = strings.pinned,
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowBlue
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = replyPreview(pinnedMessage),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = TgDayPalette.rowText
                    )
                }
            }
        }

        if (scheduledMessages.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = TgDayPalette.card
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Text(
                        text = strings.scheduled,
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowBlue
                    )
                    scheduledMessages.take(3).forEach { scheduled ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${formatScheduledTime(scheduled.scheduledAtMs)} · ${scheduled.text}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = TgDayPalette.rowText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(onClick = { onCancelScheduledMessage(scheduled.id) }) {
                                Text(strings.cancel)
                            }
                        }
                    }
                    if (scheduledMessages.size > 3) {
                        Text(
                            text = "+${scheduledMessages.size - 3} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = TgDayPalette.rowMeta
                        )
                    }
                }
            }
        }

        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isBlank()) {
                        strings.startSecureConversation
                    } else {
                        strings.noMessagesFound
                    },
                    color = TgDayPalette.rowMeta
                )
            }
        } else {
            LazyColumn(
                state = messageListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        outgoingTransfer = fileTransfers.firstOrNull { transfer ->
                            transfer.transferId == message.attachment?.transferId
                        },
                        incomingTransfer = incomingFileTransfers.firstOrNull { transfer ->
                            transfer.transferId == message.attachment?.transferId
                        },
                        isSelectionMode = isSelectionMode,
                        isSelected = selectedMessageIds.contains(message.id),
                        onToggleSelection = { onToggleMessageSelection(it.id) },
                        onOpenAttachment = onOpenAttachment,
                        onLongPress = onMessageLongPress,
                        onRetryFileTransfer = onRetryFileTransfer,
                        onCancelFileTransfer = onCancelFileTransfer,
                        onRetryIncomingFileTransfer = onRetryIncomingFileTransfer,
                        onCancelIncomingFileTransfer = onCancelIncomingFileTransfer
                    )
                }
            }
        }

        if (editingMessage != null || replyToMessage != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
                color = TgDayPalette.card,
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(34.dp)
                            .background(TgDayPalette.rowBlue)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        if (editingMessage != null) {
                            Text(
                                text = "Editing message",
                                style = MaterialTheme.typography.labelMedium,
                                color = TgDayPalette.rowBlue
                            )
                            Text(
                                text = editingMessage.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else if (replyToMessage != null) {
                            Text(
                                text = "Replying to ${replyToMessage.senderAlias ?: "message"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = TgDayPalette.rowBlue
                            )
                            Text(
                                text = replyPreview(replyToMessage),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            if (editingMessage != null) onCancelEditing() else onCancelReply()
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }

        if (!canPost &&
            (uiState.activeConversationType == ConversationType.CHANNEL ||
                uiState.activeConversationType == ConversationType.GROUP)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = TgDayPalette.card
            ) {
                Text(
                    text = if (uiState.activeConversationType == ConversationType.CHANNEL) {
                        "Read-only channel: only admins/moderators can post"
                    } else {
                        "Read-only group: only admins/moderators can post"
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = TgDayPalette.rowMeta
                )
            }
        }

        if (isVoiceRecording) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = TgDayPalette.card
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isVoiceRecordingPaused) {
                            Icons.Rounded.Pause
                        } else {
                            Icons.Rounded.Mic
                        },
                        contentDescription = if (isVoiceRecordingPaused) "Paused" else "Recording",
                        tint = if (isVoiceRecordingPaused) TgDayPalette.rowMeta else Color(0xFFD84F62),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        VoiceWaveform(
                            samples = voiceAmplitudeSamples,
                            progress = 1f,
                            activeColor = Color(0xFFD84F62),
                            inactiveColor = TgDayPalette.rowMeta.copy(alpha = 0.25f)
                        )
                        Text(
                            text = if (isVoiceRecordingPaused) {
                                "Paused ${formatRecordingDuration(voiceRecordingElapsedMs)}"
                            } else {
                                "Recording ${formatRecordingDuration(voiceRecordingElapsedMs)}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = TgDayPalette.rowText
                        )
                    }
                    TextButton(onClick = onCancelVoiceRecording) {
                        Text("Cancel")
                    }
                    if (canPauseVoiceRecording) {
                        IconButton(onClick = onPauseResumeVoiceRecording) {
                            Icon(
                                imageVector = if (isVoiceRecordingPaused) {
                                    Icons.Rounded.PlayArrow
                                } else {
                                    Icons.Rounded.Pause
                                },
                                contentDescription = if (isVoiceRecordingPaused) {
                                    "Resume recording"
                                } else {
                                    "Pause recording"
                                },
                                tint = TgDayPalette.rowBlue
                            )
                        }
                    }
                    TextButton(onClick = onToggleVoiceRecording) {
                        Text("Send")
                    }
                }
            }
        }

        if (showAttachmentTray && canPost) {
            AttachmentTray(
                strings = strings,
                onPickPhoto = {
                    showAttachmentTray = false
                    onPickPhoto()
                },
                onPickVideo = {
                    showAttachmentTray = false
                    onPickVideo()
                },
                onPickAudio = {
                    showAttachmentTray = false
                    onPickAudio()
                },
                onPickFile = {
                    showAttachmentTray = false
                    onPickFile()
                },
                onRecordVideoNote = {
                    showAttachmentTray = false
                    onRecordVideoNote()
                }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                color = TgDayPalette.card,
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 2.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showAttachmentTray = !showAttachmentTray },
                        enabled = canPost,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AttachFile,
                            contentDescription = "Attach file",
                            tint = TgDayPalette.actionBarIcon
                        )
                    }
                    IconButton(
                        onClick = { showFormattingHelp = true },
                        enabled = canPost,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Text formatting",
                            tint = TgDayPalette.actionBarIcon,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onToggleVoiceRecording,
                        enabled = canPost,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = if (isVoiceRecording) {
                                Icons.Rounded.Stop
                            } else {
                                Icons.Rounded.Mic
                            },
                            contentDescription = if (isVoiceRecording) {
                                "Stop voice recording"
                            } else {
                                "Record voice message"
                            },
                            tint = if (isVoiceRecording) {
                                Color(0xFFD84F62)
                            } else {
                                TgDayPalette.actionBarIcon
                            }
                        )
                    }
                    TextField(
                        value = messageDraft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f),
                        maxLines = 6,
                        enabled = canPost,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                        placeholder = {
                            Text(
                                if (canPost) strings.messagePlaceholder else strings.onlyAdminsCanPost,
                                color = TgDayPalette.rowMeta
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = TgDayPalette.actionBarTitle,
                            unfocusedTextColor = TgDayPalette.actionBarTitle,
                            cursorColor = TgDayPalette.composerCursor,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    IconButton(
                        onClick = { showScheduleDialog = true },
                        enabled = canPost && messageDraft.isNotBlank() && editingMessage == null,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = "Schedule message",
                            tint = TgDayPalette.actionBarIcon,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = TgDayPalette.composerSend,
                shadowElevation = 1.dp
            ) {
                IconButton(onClick = onSend, enabled = canPost, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "Send",
                        tint = Color.White
                    )
                }
            }
        }

        if (showFormattingHelp) {
            FormattingHelpDialog(onDismiss = { showFormattingHelp = false })
        }
        if (showScheduleDialog) {
            ScheduleMessageDialog(
                strings = strings,
                onDismiss = { showScheduleDialog = false },
                onSchedule = { scheduledAtMs ->
                    if (onSchedule(scheduledAtMs)) {
                        showScheduleDialog = false
                    }
                }
            )
        }

    }
}

@Composable
private fun FormattingHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text formatting") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("**bold**")
                Text("_italic_")
                Text("__underline__")
                Text("~~strikethrough~~")
                Text("`monospace`")
                Text("[link title](https://example.org)")
                Text(
                    text = "Formatting is rendered on-device. The encrypted mesh payload remains compatible with older versions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgDayPalette.rowMeta
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ScheduleMessageDialog(
    strings: MeshStrings,
    onDismiss: () -> Unit,
    onSchedule: (Long) -> Unit
) {
    var delayMinutesDraft by rememberSaveable { mutableStateOf("15") }
    val delayMinutes = delayMinutesDraft.toLongOrNull()?.coerceIn(1L, 10_080L)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.scheduleMessage) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = strings.scheduleHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = TgDayPalette.rowMeta
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = { delayMinutesDraft = "15" }) {
                        Text("15 min")
                    }
                    FilledTonalButton(onClick = { delayMinutesDraft = "60" }) {
                        Text(strings.oneHour)
                    }
                    FilledTonalButton(onClick = { delayMinutesDraft = "1440" }) {
                        Text(strings.oneDay)
                    }
                }
                OutlinedTextField(
                    value = delayMinutesDraft,
                    onValueChange = { next ->
                        delayMinutesDraft = next.filter { it.isDigit() }.take(5)
                    },
                    singleLine = true,
                    label = { Text(strings.delayMinutes) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                if (delayMinutes != null) {
                    Text(
                        text = strings.sendAt(
                            formatScheduledTime(System.currentTimeMillis() + delayMinutes * 60_000L)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowBlue
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = delayMinutes != null,
                onClick = {
                    val minutes = delayMinutes ?: return@Button
                    onSchedule(System.currentTimeMillis() + minutes * 60_000L)
                }
            ) {
                Text(strings.schedule)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun AttachmentTray(
    strings: MeshStrings,
    onPickPhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
    onRecordVideoNote: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(22.dp),
        color = TgDayPalette.card,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AttachmentAction(
                    title = strings.photos,
                    subtitle = strings.media,
                    icon = Icons.Rounded.PhotoLibrary,
                    onClick = onPickPhoto,
                    modifier = Modifier.weight(1f)
                )
                AttachmentAction(
                    title = strings.videos,
                    subtitle = strings.media,
                    icon = Icons.Rounded.PhotoLibrary,
                    onClick = onPickVideo,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AttachmentAction(
                    title = strings.voice,
                    subtitle = strings.videos,
                    icon = Icons.Rounded.ChatBubble,
                    onClick = onRecordVideoNote,
                    modifier = Modifier.weight(1f)
                )
                AttachmentAction(
                    title = strings.voice,
                    subtitle = strings.files,
                    icon = Icons.Rounded.Mic,
                    onClick = onPickAudio,
                    modifier = Modifier.weight(1f)
                )
            }
            AttachmentAction(
                title = strings.files,
                subtitle = strings.mediaAndFiles,
                icon = Icons.Rounded.AttachFile,
                onClick = onPickFile,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Tip: BLE mesh is happiest with compact media. Everything is still end-to-end encrypted before routing.",
                style = MaterialTheme.typography.labelMedium,
                color = TgDayPalette.rowMeta
            )
        }
    }
}

@Composable
private fun AttachmentAction(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = TgDayPalette.searchField
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = TgDayPalette.rowBlue.copy(alpha = 0.14f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = TgDayPalette.rowBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TgDayPalette.actionBarTitle
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TgDayPalette.rowMeta
                )
            }
        }
    }
}

@Composable
private fun ShareToMeshDialog(
    payload: ExternalSharePayload,
    conversations: List<ConversationSummary>,
    onDismiss: () -> Unit,
    onSendToConversation: (ConversationSummary) -> Unit
) {
    val shareTitle = when {
        payload.uri != null && !payload.text.isNullOrBlank() -> "Share text + file"
        payload.uri != null -> "Share file"
        else -> "Share text"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(shareTitle) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!payload.text.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = TgDayPalette.searchField
                    ) {
                        Text(
                            text = payload.text.trim(),
                            modifier = Modifier.padding(10.dp),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TgDayPalette.rowText
                        )
                    }
                }
                if (payload.uri != null) {
                    AttachmentPreviewHero(
                        title = "Incoming Android share",
                        subtitle = payload.uri.toString().take(88),
                        icon = Icons.Rounded.AttachFile
                    )
                }
                Text(
                    text = "Choose a MeshGram chat. The content will be encrypted before Bluetooth mesh delivery.",
                    style = MaterialTheme.typography.labelMedium,
                    color = TgDayPalette.rowMeta
                )
                if (conversations.isEmpty()) {
                    Text(
                        text = "No chats yet. Create or discover a recipient first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TgDayPalette.rowMeta
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(conversations.filterNot { it.isArchived }, key = { it.id }) { conversation ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSendToConversation(conversation) },
                                shape = RoundedCornerShape(14.dp),
                                color = TgDayPalette.searchField
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Avatar(
                                        label = conversation.title,
                                        seed = conversation.id,
                                        size = 42.dp,
                                        online = conversation.isOnline
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = conversation.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = TgDayPalette.actionBarTitle,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = conversation.subtitle,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = TgDayPalette.rowMeta,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun MediaAlbumDraftDialog(
    uris: List<Uri>,
    caption: String,
    onCaptionChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val labels = remember(uris) {
        uris.mapIndexed { index, uri -> mediaUriLabel(context, uri, index) }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (uris.size > 1) "Send media album" else "Send media") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (uris.size > 1) {
                        "${uris.size} encrypted items"
                    } else {
                        "1 encrypted item"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = TgDayPalette.rowBlue
                )
                labels.forEachIndexed { index, label ->
                    Text(
                        text = "${index + 1}. $label",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgDayPalette.rowText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedTextField(
                    value = caption,
                    onValueChange = onCaptionChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Caption") },
                    supportingText = {
                        Text("${caption.length}/$MAX_MEDIA_CAPTION_LENGTH")
                    },
                    maxLines = 4
                )
                Text(
                    text = "Each item is encrypted separately and can resume independently over BLE.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgDayPalette.rowMeta
                )
            }
        },
        confirmButton = {
            Button(onClick = onSend) {
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}

private fun mediaUriLabel(context: Context, uri: Uri, index: Int): String {
    val displayName = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0) cursor.getString(column) else null
        }
    }.getOrNull()
    return displayName?.trim()?.ifBlank { null }
        ?: uri.lastPathSegment?.takeLast(48)
        ?: "media_${index + 1}"
}

@Composable
private fun VideoNoteDraftDialog(
    capture: PendingVideoCapture,
    canSend: Boolean,
    onSend: () -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit
) {
    var videoView by remember(capture.file.absolutePath) { mutableStateOf<VideoView?>(null) }
    DisposableEffect(capture.file.absolutePath) {
        onDispose {
            runCatching { videoView?.stopPlayback() }
            videoView = null
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Video note preview") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AndroidView(
                    modifier = Modifier
                        .size(250.dp)
                        .clip(CircleShape)
                        .background(Color.Black),
                    factory = { context ->
                        VideoView(context).apply {
                            videoView = this
                            tag = capture.file.absolutePath
                            setVideoPath(capture.file.absolutePath)
                            setOnPreparedListener { player ->
                                player.isLooping = true
                                start()
                            }
                        }
                    },
                    update = { view ->
                        if (view.tag != capture.file.absolutePath) {
                            view.tag = capture.file.absolutePath
                            view.setVideoPath(capture.file.absolutePath)
                            view.start()
                        }
                    }
                )
                Text(
                    text = "${fileSizeShort(capture.file.length())} · up to " +
                        "$MAX_VIDEO_NOTE_DURATION_SECONDS seconds",
                    style = MaterialTheme.typography.labelMedium,
                    color = TgDayPalette.rowMeta
                )
                Text(
                    text = if (canSend) {
                        "The temporary clip is encrypted only after you press Send."
                    } else {
                        "The clip exceeds the mesh limit. Retake a shorter video."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (canSend) TgDayPalette.rowText else Color(0xFFD84F62)
                )
            }
        },
        confirmButton = {
            Button(onClick = onSend, enabled = canSend) {
                Text("Send")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRetake) {
                    Text("Retake")
                }
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
private fun AttachmentPreviewDialog(
    message: ChatMessage,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit
) {
    val attachment = message.attachment ?: return
    val kind = message.attachmentKind()
    val canExport = !attachment.localUri.isNullOrBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (kind) {
                    AttachmentKind.IMAGE -> "Photo"
                    AttachmentKind.VIDEO -> "Video note"
                    AttachmentKind.AUDIO -> "Voice message"
                    AttachmentKind.FILE -> "Document"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (kind) {
                    AttachmentKind.IMAGE -> {
                        AttachmentThumb(
                            message = message,
                            size = 320.dp
                        )
                    }

                    AttachmentKind.VIDEO -> {
                        InlineVideoPlayer(message = message)
                    }

                    AttachmentKind.AUDIO -> {
                        InlineAudioPlayer(message = message)
                    }

                    AttachmentKind.FILE -> {
                        AttachmentPreviewHero(
                            title = "Encrypted document",
                            subtitle = "Open or share a temporary decrypted copy",
                            icon = Icons.Rounded.AttachFile
                        )
                    }
                }
                AttachmentMetaRow(label = "Name", value = attachment.fileName)
                AttachmentMetaRow(label = "Size", value = fileSizeShort(attachment.sizeBytes))
                AttachmentMetaRow(label = "Type", value = attachment.mimeType.ifBlank { "application/octet-stream" })
                Text(
                    text = "Stored encrypted on this device. Open/Share creates a temporary decrypted copy only when you choose it.",
                    style = MaterialTheme.typography.labelMedium,
                    color = TgDayPalette.rowMeta
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenExternal,
                enabled = canExport
            ) {
                Text(if (kind == AttachmentKind.AUDIO) "Play / Open" else "Open")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onShare,
                    enabled = canExport
                ) {
                    Text("Share")
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}

@Composable
private fun InlineVideoPlayer(message: ChatMessage) {
    val localContext = LocalContext.current
    var previewFile by remember(message.id, message.attachment?.localUri) { mutableStateOf<File?>(null) }
    LaunchedEffect(message.id, message.attachment?.localUri) {
        previewFile = createDecryptedPreviewFile(localContext, message, "preview_video")
    }
    DisposableEffect(previewFile) {
        onDispose {
            previewFile?.let { file ->
                if (file.exists()) runCatching { file.delete() }
            }
        }
    }

    val file = previewFile
    if (file == null || !file.exists()) {
        AttachmentPreviewHero(
            title = "Preparing encrypted video note",
            subtitle = "Decrypting temporary preview inside app cache",
            icon = Icons.Rounded.ChatBubble
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(18.dp)),
            factory = { context ->
                VideoView(context).apply {
                    val controller = MediaController(context)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    tag = file.absolutePath
                    setVideoPath(file.absolutePath)
                    setOnPreparedListener { mediaPlayer ->
                        mediaPlayer.isLooping = false
                        seekTo(1)
                    }
                }
            },
            update = { view ->
                if (view.tag != file.absolutePath) {
                    view.tag = file.absolutePath
                    view.setVideoPath(file.absolutePath)
                    view.seekTo(1)
                }
            }
        )
        Text(
            text = "Tap the preview to play. The decrypted copy is temporary.",
            style = MaterialTheme.typography.labelMedium,
            color = TgDayPalette.rowMeta
        )
    }
}

@Composable
private fun VoiceWaveform(
    samples: List<Float>,
    progress: Float,
    activeColor: Color,
    inactiveColor: Color
) {
    val visibleSamples = if (samples.isEmpty()) listOf(0.08f) else samples.takeLast(36)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        visibleSamples.forEachIndexed { index, rawAmplitude ->
            val normalized = rawAmplitude.coerceIn(0.04f, 1f)
            val played = (index + 1).toFloat() / visibleSamples.size.toFloat() <= progress.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((5f + normalized * 23f).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (played) activeColor else inactiveColor)
            )
        }
    }
}

@Composable
private fun InlineAudioPlayer(message: ChatMessage) {
    val localContext = LocalContext.current
    var previewFile by remember(message.id, message.attachment?.localUri) { mutableStateOf<File?>(null) }
    val playerState = remember(message.id) { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember(message.id) { mutableStateOf(false) }
    var positionMs by remember(message.id) { mutableStateOf(0) }
    var durationMs by remember(message.id) { mutableStateOf(0) }
    var playbackSpeed by remember(message.id) { mutableStateOf(1f) }
    val waveformSamples = remember(message.id) {
        List(36) { index ->
            val mixed = abs(message.id.hashCode() * 31 + index * 7919)
            0.12f + (mixed % 82) / 100f
        }
    }

    LaunchedEffect(message.id, message.attachment?.localUri) {
        previewFile = createDecryptedPreviewFile(localContext, message, "preview_audio")
    }
    DisposableEffect(previewFile) {
        onDispose {
            playerState.value?.let { player ->
                runCatching { player.stop() }
                runCatching { player.release() }
            }
            playerState.value = null
            previewFile?.let { file ->
                if (file.exists()) runCatching { file.delete() }
            }
        }
    }
    LaunchedEffect(isPlaying, playerState.value) {
        while (isPlaying) {
            val player = playerState.value
            if (player == null) {
                isPlaying = false
            } else {
                positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
                durationMs = runCatching { player.duration }.getOrDefault(durationMs).coerceAtLeast(0)
            }
            delay(350)
        }
    }

    val file = previewFile
    val togglePlayback = {
        if (file == null || !file.exists()) {
            Unit
        } else {
            val current = playerState.value
            if (current == null) {
                val created = runCatching {
                    MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnCompletionListener { completed ->
                            isPlaying = false
                            positionMs = 0
                            runCatching { completed.seekTo(0) }
                        }
                        prepare()
                        durationMs = duration.coerceAtLeast(0)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            playbackParams = PlaybackParams().setSpeed(playbackSpeed)
                        }
                        start()
                    }
                }.getOrNull()
                playerState.value = created
                isPlaying = created != null
            } else if (isPlaying) {
                runCatching { current.pause() }
                isPlaying = false
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    runCatching { current.playbackParams = PlaybackParams().setSpeed(playbackSpeed) }
                }
                runCatching { current.start() }
                isPlaying = true
            }
        }
    }
    val progress = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = TgDayPalette.searchField
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(54.dp),
                shape = CircleShape,
                color = TgDayPalette.rowBlue
            ) {
                IconButton(
                    onClick = togglePlayback,
                    enabled = file?.exists() == true
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause voice" else "Play voice",
                        tint = Color.White
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Encrypted voice note",
                    style = MaterialTheme.typography.titleMedium,
                    color = TgDayPalette.actionBarTitle
                )
                Spacer(modifier = Modifier.height(7.dp))
                VoiceWaveform(
                    samples = waveformSamples,
                    progress = progress,
                    activeColor = TgDayPalette.rowBlue,
                    inactiveColor = TgDayPalette.rowMeta.copy(alpha = 0.24f)
                )
                Slider(
                    value = positionMs.coerceIn(0, durationMs.coerceAtLeast(0)).toFloat(),
                    onValueChange = { value -> positionMs = value.toInt() },
                    onValueChangeFinished = {
                        playerState.value?.let { player ->
                            runCatching { player.seekTo(positionMs) }
                        }
                    },
                    enabled = file?.exists() == true && durationMs > 0,
                    valueRange = 0f..durationMs.coerceAtLeast(1).toFloat()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (file == null) {
                            "Preparing preview..."
                        } else {
                            "${formatRecordingDuration(positionMs.toLong())} / ${formatRecordingDuration(durationMs.toLong())}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowMeta,
                        modifier = Modifier.weight(1f)
                    )
                    listOf(1f, 1.5f, 2f).forEach { speed ->
                        TextButton(
                            onClick = {
                                playbackSpeed = speed
                                playerState.value?.let { player ->
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        runCatching { player.playbackParams = PlaybackParams().setSpeed(speed) }
                                    }
                                }
                            }
                        ) {
                            Text(
                                text = if (speed == 1f) "1x" else "${speed}x",
                                color = if (playbackSpeed == speed) TgDayPalette.rowBlue else TgDayPalette.rowMeta
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentPreviewHero(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = TgDayPalette.searchField
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(54.dp),
                shape = CircleShape,
                color = TgDayPalette.rowBlue.copy(alpha = 0.14f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = TgDayPalette.rowBlue,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TgDayPalette.actionBarTitle
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TgDayPalette.rowMeta
                )
            }
        }
    }
}

@Composable
private fun AttachmentMetaRow(
    label: String,
    value: String
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TgDayPalette.rowMeta,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TgDayPalette.rowText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ConversationRow(
    conversation: ConversationSummary,
    strings: MeshStrings,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val isSavedConversation = isSavedMessagesConversation(conversation.id)
    val localizedSubtitle = when (conversation.subtitle.lowercase(Locale.ROOT)) {
        "online" -> strings.online
        "offline" -> strings.offline
        "members" -> strings.members
        "subscribers" -> strings.subscribers
        else -> conversation.subtitle
    }
    val previewText = if (conversation.draftText.isNotBlank()) {
        "${strings.draft}: ${conversation.draftText.trim().replace("\n", " ").take(44)}"
    } else {
        conversation.lastMessagePreview.ifBlank {
            if (isSavedConversation) strings.savedMessagesSubtitle else localizedSubtitle
        }
    }
    val previewColor = if (conversation.draftText.isNotBlank()) {
        Color(0xFFD84F62)
    } else {
        TgDayPalette.rowText
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TgDayPalette.card)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(start = 10.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSavedConversation) {
                Surface(
                    modifier = Modifier.size(54.dp),
                    shape = CircleShape,
                    color = TgDayPalette.rowBlue
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Bookmark,
                            contentDescription = strings.savedMessages,
                            tint = Color.White
                        )
                    }
                }
            } else {
                Avatar(
                    label = conversation.title,
                    seed = conversation.id,
                    size = 54.dp,
                    online = conversation.isOnline,
                    avatarData = conversation.avatarData
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isSavedConversation) strings.savedMessages else conversation.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                    color = TgDayPalette.actionBarTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = previewColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatChatTime(conversation.lastMessageAtMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = TgDayPalette.rowMeta
                )
                if (isSavedConversation) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = strings.saved,
                        style = MaterialTheme.typography.labelSmall,
                        color = TgDayPalette.rowBlue
                    )
                } else if (conversation.isPinned) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = strings.pinned,
                        style = MaterialTheme.typography.labelSmall,
                        color = TgDayPalette.rowBlue
                    )
                }
                if (conversation.isMuted) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = strings.muted,
                        style = MaterialTheme.typography.labelSmall,
                        color = TgDayPalette.rowMeta
                    )
                }
                if (conversation.isArchived) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = strings.archived,
                        style = MaterialTheme.typography.labelSmall,
                        color = TgDayPalette.rowMeta
                    )
                }
                if (conversation.unreadCount > 0) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Surface(
                        color = TgDayPalette.unreadPill,
                        shape = RoundedCornerShape(11.dp)
                    ) {
                        Text(
                            text = conversation.unreadCount.toString(),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
                if (conversation.type == ConversationType.GROUP) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = strings.group,
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowAccent
                    )
                }
                if (conversation.type == ConversationType.CHANNEL) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = strings.channel,
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.rowBlue
                    )
                }
                if ((conversation.type == ConversationType.CHANNEL || conversation.type == ConversationType.GROUP) &&
                    conversation.isBroadcastOnly
                ) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = strings.adminOnly,
                        style = MaterialTheme.typography.labelSmall,
                        color = TgDayPalette.rowMeta
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 76.dp, end = 8.dp),
            color = TgDayPalette.divider
        )
    }
}

@Composable
private fun MessageSearchRow(
    hit: GlobalSearchHit,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TgDayPalette.card)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(
                label = hit.conversationTitle,
                seed = hit.conversationId,
                size = 44.dp,
                online = false
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hit.conversationTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = TgDayPalette.actionBarTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${hit.senderLabel}: ${hit.preview}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgDayPalette.rowText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatChatTime(hit.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = TgDayPalette.rowMeta
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 66.dp, end = 8.dp),
            color = TgDayPalette.divider
        )
    }
}

@Composable
private fun MeshStateStrip(
    isRunning: Boolean,
    peersCount: Int,
    totalPeers: Int,
    strings: MeshStrings,
    onToggleMesh: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = TgDayPalette.card
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(30.dp),
                shape = CircleShape,
                color = if (isRunning) Color(0xFFE5F8EF) else Color(0xFFFFECEB)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isRunning) Color(0xFF19B66A) else Color(0xFFEF5350))
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isRunning) strings.meshOnline else strings.meshOffline,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = TgDayPalette.actionBarTitle
                )
                Text(
                    text = "$peersCount/$totalPeers ${strings.peers}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TgDayPalette.rowMeta
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = onToggleMesh) {
                Text(
                    text = if (isRunning) strings.stop else strings.start,
                    color = TgDayPalette.rowBlue
                )
            }
        }
    }
}

@Composable
private fun EmptyChatsCard(
    onOpenDirectDialog: () -> Unit,
    onOpenGroupDialog: () -> Unit,
    onOpenChannelDialog: () -> Unit
) {
    val strings = rememberMeshStrings()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = TgDayPalette.card)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = strings.noChatsYet,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = strings.startDirectGroupChannel,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onOpenDirectDialog) {
                    Text(strings.newChat)
                }
                FilledTonalButton(onClick = onOpenGroupDialog) {
                    Text(strings.createGroup)
                }
                FilledTonalButton(onClick = onOpenChannelDialog) {
                    Text(strings.createChannel)
                }
            }
        }
    }
}

@Composable
private fun DirectChatDialog(
    contacts: List<MeshContact>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit
) {
    val strings = rememberMeshStrings()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val visibleContacts = remember(contacts, query) {
        val normalized = query.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank()) {
            contacts
        } else {
            contacts.filter { contact ->
                contact.alias.lowercase(Locale.ROOT).contains(normalized) ||
                    contact.nodeId.lowercase(Locale.ROOT).contains(normalized)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.close) } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = strings.startChat,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { searchOpen = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = strings.search
                    )
                }
            }
        },
        text = {
            if (contacts.isEmpty()) {
                Text(strings.noPeersDiscovered)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (searchOpen) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = query,
                                onValueChange = { query = it.take(64) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text(strings.search) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = strings.search
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            searchOpen = false
                                            query = ""
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = strings.close
                                        )
                                    }
                                }
                            )
                        }
                    }
                    if (visibleContacts.isEmpty()) {
                        Text(strings.noPeersDiscovered)
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visibleContacts, key = { it.nodeId }) { contact ->
                            Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(contact.nodeId) },
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFFF4F8FC)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(
                                    label = contact.alias,
                                    seed = contact.nodeId,
                                    size = 42.dp,
                                    online = contact.isOnline
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(contact.alias)
                                    Text(
                                        text = if (contact.isOnline) strings.online else strings.offline,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun GalleryAttachmentThumb(
    message: ChatMessage,
    size: Dp
) {
    val kind = message.attachmentKind()
    if (kind == AttachmentKind.IMAGE) {
        AttachmentThumb(message = message, size = size)
        return
    }
    val icon = when (kind) {
        AttachmentKind.VIDEO -> Icons.Rounded.ChatBubble
        AttachmentKind.AUDIO -> Icons.Rounded.Mic
        AttachmentKind.FILE -> Icons.Rounded.AttachFile
        AttachmentKind.IMAGE -> Icons.Rounded.PhotoLibrary
    }
    Surface(
        modifier = Modifier.size(size),
        shape = if (kind == AttachmentKind.VIDEO) CircleShape else RoundedCornerShape(14.dp),
        color = TgDayPalette.rowBlue.copy(alpha = 0.13f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = kind.label,
                tint = TgDayPalette.rowBlue,
                modifier = Modifier.size((size.value * 0.42f).dp)
            )
        }
    }
}

@Composable
private fun CreateGroupDialog(
    contacts: List<MeshContact>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>) -> Unit
) {
    val strings = rememberMeshStrings()
    var title by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                enabled = title.trim().isNotBlank() && selected.isNotEmpty(),
                onClick = { onCreate(title, selected.toList()) }
            ) {
                Text(strings.create)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
        title = { Text(strings.createGroup) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(36) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.groupName) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (contacts.isEmpty()) {
                    Text(strings.noContactsAvailable)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(contacts, key = { it.nodeId }) { contact ->
                            val checked = selected.contains(contact.nodeId)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (checked) {
                                            selected - contact.nodeId
                                        } else {
                                            selected + contact.nodeId
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        selected = if (isChecked) {
                                            selected + contact.nodeId
                                        } else {
                                            selected - contact.nodeId
                                        }
                                    }
                                )
                                Avatar(
                                    label = contact.alias,
                                    seed = contact.nodeId,
                                    size = 34.dp,
                                    online = contact.isOnline
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(contact.alias)
                                    Text(
                                        text = if (contact.isOnline) strings.online else strings.offline,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun CreateChannelDialog(
    contacts: List<MeshContact>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>) -> Unit
) {
    val strings = rememberMeshStrings()
    var title by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                enabled = title.trim().isNotBlank() && selected.isNotEmpty(),
                onClick = { onCreate(title, selected.toList()) }
            ) {
                Text(strings.create)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
        title = { Text(strings.createChannel) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(42) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.channelName) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = strings.broadcastAdminsOnly,
                    style = MaterialTheme.typography.labelMedium,
                    color = TgDayPalette.rowMeta
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (contacts.isEmpty()) {
                    Text(strings.noContactsAvailable)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(contacts, key = { it.nodeId }) { contact ->
                            val checked = selected.contains(contact.nodeId)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (checked) {
                                            selected - contact.nodeId
                                        } else {
                                            selected + contact.nodeId
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        selected = if (isChecked) {
                                            selected + contact.nodeId
                                        } else {
                                            selected - contact.nodeId
                                        }
                                    }
                                )
                                Avatar(
                                    label = contact.alias,
                                    seed = contact.nodeId,
                                    size = 34.dp,
                                    online = contact.isOnline
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(contact.alias)
                                    Text(
                                        text = if (contact.isOnline) strings.online else strings.offline,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ManageCollectiveMembersDialog(
    conversation: ConversationSummary,
    members: List<MeshContact>,
    localNodeId: String,
    localAlias: String,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val ownerId = remember(conversation.ownerNodeId, conversation.adminNodeIds, localNodeId) {
        conversation.ownerNodeId?.trim()?.ifBlank { null }
            ?: conversation.adminNodeIds.firstOrNull { it.isNotBlank() }
            ?: localNodeId
    }
    val availableMembers = remember(conversation, members, localNodeId, localAlias, ownerId) {
        val byId = linkedMapOf<String, MeshContact>()
        conversation.memberNodeIds.forEach { nodeId ->
            if (nodeId.isBlank()) return@forEach
            val known = members.firstOrNull { it.nodeId == nodeId }
            byId[nodeId] = known ?: MeshContact(
                nodeId = nodeId,
                alias = if (nodeId == localNodeId) {
                    localAlias.ifBlank { "You" }
                } else {
                    "Node-${nodeId.take(4)}"
                },
                fingerprintShort = null,
                isOnline = false
            )
        }
        members.forEach { contact ->
            if (contact.nodeId.isBlank()) return@forEach
            if (!byId.containsKey(contact.nodeId)) {
                byId[contact.nodeId] = contact
            }
        }
        if (!byId.containsKey(localNodeId)) {
            byId[localNodeId] = MeshContact(
                nodeId = localNodeId,
                alias = localAlias.ifBlank { "You" },
                fingerprintShort = null,
                isOnline = true
            )
        }
        byId.values.sortedWith(
            compareByDescending<MeshContact> { it.nodeId == localNodeId }
                .thenByDescending { it.nodeId == ownerId }
                .thenByDescending { it.isOnline }
                .thenBy { it.alias.lowercase() }
        )
    }
    var selected by remember(conversation.id, conversation.memberNodeIds, localNodeId, ownerId) {
        mutableStateOf(
            (conversation.memberNodeIds + localNodeId + ownerId)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                enabled = selected.size >= 2,
                onClick = { onSave(selected.toList()) }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = {
            Text(
                if (conversation.type == ConversationType.CHANNEL) {
                    "Channel members"
                } else {
                    "Group members"
                }
            )
        },
        text = {
            if (availableMembers.isEmpty()) {
                Text("No members found")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(availableMembers, key = { it.nodeId }) { member ->
                        val isLocal = member.nodeId == localNodeId
                        val isOwner = member.nodeId == ownerId
                        val checked = selected.contains(member.nodeId) || isLocal || isOwner
                        val isLocked = isLocal || isOwner
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isLocked) {
                                    selected = if (checked) {
                                        selected - member.nodeId
                                    } else {
                                        selected + member.nodeId
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                enabled = !isLocked,
                                onCheckedChange = { isChecked ->
                                    if (!isLocked) {
                                        selected = if (isChecked) {
                                            selected + member.nodeId
                                        } else {
                                            selected - member.nodeId
                                        }
                                    }
                                }
                            )
                            Avatar(
                                label = member.alias,
                                seed = member.nodeId,
                                size = 34.dp,
                                online = member.isOnline
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(member.alias)
                                Text(
                                    text = when {
                                        isLocal -> "you"
                                        isOwner -> "owner"
                                        member.isOnline -> "online"
                                        else -> "offline"
                                    },
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ManageChannelAdminsDialog(
    conversation: ConversationSummary,
    members: List<MeshContact>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var selected by remember(conversation.id, conversation.adminNodeIds) {
        mutableStateOf(conversation.adminNodeIds.toSet())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                enabled = selected.isNotEmpty(),
                onClick = { onSave(selected.toList()) }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = {
            Text(
                if (conversation.type == ConversationType.CHANNEL) {
                    "Channel admins"
                } else {
                    "Group admins"
                }
            )
        },
        text = {
            if (members.isEmpty()) {
                Text("No members found")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(members, key = { it.nodeId }) { member ->
                        val checked = selected.contains(member.nodeId)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (checked) {
                                        selected - member.nodeId
                                    } else {
                                        selected + member.nodeId
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selected = if (isChecked) {
                                        selected + member.nodeId
                                    } else {
                                        selected - member.nodeId
                                    }
                                }
                            )
                            Avatar(
                                label = member.alias,
                                seed = member.nodeId,
                                size = 34.dp,
                                online = member.isOnline
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(member.alias)
                                Text(
                                    text = if (member.isOnline) "online" else "offline",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ManageCollectiveModeratorsDialog(
    conversation: ConversationSummary,
    members: List<MeshContact>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val ownerId = remember(conversation.ownerNodeId, conversation.adminNodeIds) {
        conversation.ownerNodeId?.trim()?.ifBlank { null }
            ?: conversation.adminNodeIds.firstOrNull { it.isNotBlank() }
    }
    val adminSet = remember(conversation.adminNodeIds, ownerId) {
        val admins = conversation.adminNodeIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (ownerId.isNullOrBlank()) {
            admins.toSet()
        } else {
            (admins + ownerId).toSet()
        }
    }
    var selected by remember(conversation.id, conversation.moderatorNodeIds, adminSet) {
        mutableStateOf(
            conversation.moderatorNodeIds
                .filter { !adminSet.contains(it) }
                .toSet()
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onSave(selected.toList()) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = {
            Text(
                if (conversation.type == ConversationType.CHANNEL) {
                    "Channel moderators"
                } else {
                    "Group moderators"
                }
            )
        },
        text = {
            if (members.isEmpty()) {
                Text("No members found")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(members, key = { it.nodeId }) { member ->
                        val isAdmin = adminSet.contains(member.nodeId)
                        val checked = if (isAdmin) true else selected.contains(member.nodeId)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isAdmin) {
                                    selected = if (checked) {
                                        selected - member.nodeId
                                    } else {
                                        selected + member.nodeId
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                enabled = !isAdmin,
                                onCheckedChange = { isChecked ->
                                    if (!isAdmin) {
                                        selected = if (isChecked) {
                                            selected + member.nodeId
                                        } else {
                                            selected - member.nodeId
                                        }
                                    }
                                }
                            )
                            Avatar(
                                label = member.alias,
                                seed = member.nodeId,
                                size = 34.dp,
                                online = member.isOnline
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(member.alias)
                                Text(
                                    text = if (isAdmin) {
                                        "admin"
                                    } else if (member.isOnline) {
                                        "online"
                                    } else {
                                        "offline"
                                    },
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MessageBubble(
    message: ChatMessage,
    outgoingTransfer: OutgoingFileTransferProgress?,
    incomingTransfer: IncomingFileTransferProgress?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: (ChatMessage) -> Unit,
    onOpenAttachment: (ChatMessage) -> Unit,
    onLongPress: (ChatMessage) -> Unit,
    onRetryFileTransfer: (String) -> Boolean,
    onCancelFileTransfer: (String) -> Boolean,
    onRetryIncomingFileTransfer: (String) -> Boolean,
    onCancelIncomingFileTransfer: (String) -> Boolean
) {
    val strings = rememberMeshStrings()
    val time = remember(message.createdAtMs) {
        CHAT_TIME_FORMAT.format(Date(message.createdAtMs))
    }
    val bubbleColor = if (isSelected) {
        if (message.isLocal) TgDayPalette.bubbleOutSelected else TgDayPalette.bubbleInSelected
    } else {
        if (message.isLocal) TgDayPalette.bubbleOut else TgDayPalette.bubbleIn
    }
    val textColor = if (message.isLocal) TgDayPalette.bubbleOutText else TgDayPalette.bubbleInText
    val metaColor = if (message.isLocal) Color.White.copy(alpha = 0.72f) else TgDayPalette.rowMeta
    val arrangement = if (message.isLocal) Arrangement.End else Arrangement.Start
    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (message.isLocal) 18.dp else 4.dp,
        bottomEnd = if (message.isLocal) 4.dp else 18.dp
    )
    val reactionCounts = remember(message.reactions) {
        message.reactions
            .groupingBy { it.emoji }
            .eachCount()
            .toList()
            .sortedBy { it.first }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = arrangement
    ) {
        Surface(
            color = bubbleColor,
            shape = bubbleShape,
            tonalElevation = if (message.isLocal) 0.dp else 1.dp,
            shadowElevation = if (message.isLocal) 0.dp else 1.dp,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .then(
                    if (isSelected) {
                        Modifier.border(1.dp, TgDayPalette.rowBlue.copy(alpha = 0.6f), bubbleShape)
                    } else {
                        Modifier
                    }
                )
                .combinedClickable(
                    onClick = {
                        if (isSelectionMode) {
                            onToggleSelection(message)
                        } else {
                            if (message.contentType == ChatContentType.FILE &&
                                !message.attachment?.localUri.isNullOrBlank()
                            ) {
                                onOpenAttachment(message)
                            }
                        }
                    },
                    onLongClick = {
                        if (isSelectionMode) {
                            onToggleSelection(message)
                        } else {
                            onLongPress(message)
                        }
                    }
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                if (!message.isLocal) {
                    Text(
                        text = message.senderAlias?.ifBlank { null }
                            ?: "Node-${message.originNodeId.take(4)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TgDayPalette.messageLinkIn
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                if (!message.forwardedFromAlias.isNullOrBlank()) {
                    Text(
                        text = "Forwarded from ${message.forwardedFromAlias}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (message.isLocal) Color.White.copy(alpha = 0.92f) else TgDayPalette.rowBlue
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                if (!message.replyToPreview.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (message.isLocal) {
                            Color.White.copy(alpha = 0.14f)
                        } else {
                            TgDayPalette.bubbleInSelected
                        }
                    ) {
                        Text(
                            text = message.replyToPreview,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (message.isLocal) Color.White else TgDayPalette.rowText
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                if (message.isDeleted || message.contentType == ChatContentType.FILE) {
                    Text(
                        text = if (message.isDeleted) {
                            "Message deleted"
                        } else {
                            message.attachment?.fileName ?: message.text
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (message.isDeleted) metaColor else textColor
                    )
                } else {
                    RichMessageText(
                        text = message.text,
                        textColor = textColor,
                        linkColor = if (message.isLocal) Color.White else TgDayPalette.rowBlue,
                        codeBackground = if (message.isLocal) {
                            Color.Black.copy(alpha = 0.16f)
                        } else {
                            TgDayPalette.searchField
                        }
                    )
                }
                if (!message.isDeleted && message.savedTags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = message.savedTags.joinToString("  ") { tag -> "#$tag" },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (message.isLocal) Color.White.copy(alpha = 0.9f) else TgDayPalette.rowBlue,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!message.isDeleted && message.contentType == ChatContentType.FILE) {
                    val attachmentKind = message.attachmentKind()
                    if (attachmentKind == AttachmentKind.IMAGE) {
                        Spacer(modifier = Modifier.height(6.dp))
                        AttachmentThumb(
                            message = message,
                            size = 210.dp
                        )
                    } else if (attachmentKind == AttachmentKind.VIDEO) {
                        Spacer(modifier = Modifier.height(6.dp))
                        VideoAttachmentPreview(
                            message = message,
                            metaColor = metaColor
                        )
                    } else if (attachmentKind == AttachmentKind.AUDIO) {
                        Spacer(modifier = Modifier.height(6.dp))
                        InlineAudioPlayer(message = message)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = when (attachmentKind) {
                                AttachmentKind.AUDIO -> Icons.Rounded.Mic
                                AttachmentKind.VIDEO -> Icons.Rounded.ChatBubble
                                AttachmentKind.IMAGE -> Icons.Rounded.PhotoLibrary
                                AttachmentKind.FILE -> Icons.Rounded.AttachFile
                            },
                            contentDescription = attachmentKind.label,
                            modifier = Modifier.size(14.dp),
                            tint = metaColor
                        )
                        Text(
                            text = attachmentKind.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = metaColor
                        )
                        if (attachmentKind != AttachmentKind.FILE) {
                            Text(
                                text = message.attachment?.let { fileSizeShort(it.sizeBytes) } ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = metaColor
                            )
                        }
                        if (!message.attachment?.localUri.isNullOrBlank()) {
                            Text(
                                text = if (attachmentKind == AttachmentKind.FILE) {
                                    message.attachment?.let { fileSizeShort(it.sizeBytes) } ?: "tap to open"
                                } else {
                                    "tap to open"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = metaColor
                            )
                        }
                    }
                    val attachment = message.attachment
                    if (attachment != null && attachment.mediaAlbumCount > 1) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Album ${attachment.mediaAlbumIndex + 1}/${attachment.mediaAlbumCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor
                        )
                    }
                    val caption = message.text.trim()
                    if (caption.isNotBlank() && caption != attachment?.fileName) {
                        Spacer(modifier = Modifier.height(5.dp))
                        RichMessageText(
                            text = caption,
                            textColor = textColor,
                            linkColor = if (message.isLocal) Color.White else TgDayPalette.rowBlue,
                            codeBackground = if (message.isLocal) {
                                Color.Black.copy(alpha = 0.16f)
                            } else {
                                TgDayPalette.searchField
                            }
                        )
                    }
                }
                if (!message.isDeleted && reactionCounts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        reactionCounts.forEach { (emoji, count) ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (message.isLocal) {
                                    Color.White.copy(alpha = 0.18f)
                                } else {
                                    Color(0xFFE7EEF7)
                                }
                            ) {
                                Text(
                                    text = "$emoji $count",
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (message.isLocal) Color.White else TgDayPalette.rowText
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    if (message.pinnedAtMs != null && !message.isDeleted) {
                        Text(
                            text = "pinned",
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor
                        )
                    }
                    if (message.isEdited && !message.isDeleted) {
                        Text(
                            text = "edited",
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor
                        )
                    }
                    if (message.isLocal && !message.isDeleted) {
                        val deliveredCount = message.deliveredToNodeIds.size
                        val relayedCount = message.relayedByNodeIds.size
                        val deliveryLabel = when (message.deliveryState) {
                            MessageDeliveryState.DELIVERED -> {
                                if (deliveredCount > 1) "delivered $deliveredCount" else "delivered"
                            }
                            MessageDeliveryState.RELAYED -> {
                                if (relayedCount > 1) "relayed $relayedCount" else "relayed"
                            }
                            MessageDeliveryState.SENT -> "sent"
                            MessageDeliveryState.PENDING -> "pending"
                        }
                        Text(
                            text = deliveryLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor
                        )
                    }
                    outgoingTransfer?.let { transfer ->
                        val progressPercent = (transfer.progress.coerceIn(0f, 1f) * 100f).roundToInt()
                        Icon(
                            imageVector = Icons.Rounded.AttachFile,
                            contentDescription = strings.fileTransfers,
                            modifier = Modifier.size(12.dp),
                            tint = metaColor
                        )
                        Text(
                            text = "$progressPercent%",
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor
                        )
                        IconButton(
                            onClick = { onRetryFileTransfer(transfer.transferId) },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = strings.retry,
                                modifier = Modifier.size(14.dp),
                                tint = metaColor
                            )
                        }
                        IconButton(
                            onClick = { onCancelFileTransfer(transfer.transferId) },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Stop,
                                contentDescription = strings.stop,
                                modifier = Modifier.size(14.dp),
                                tint = metaColor
                            )
                        }
                    }
                    incomingTransfer?.let { transfer ->
                        val progressPercent = (transfer.progress.coerceIn(0f, 1f) * 100f).roundToInt()
                        Icon(
                            imageVector = Icons.Rounded.AttachFile,
                            contentDescription = strings.receivingFiles,
                            modifier = Modifier.size(12.dp),
                            tint = metaColor
                        )
                        Text(
                            text = "$progressPercent%",
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor
                        )
                        IconButton(
                            onClick = { onRetryIncomingFileTransfer(transfer.transferId) },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = strings.requestMissing,
                                modifier = Modifier.size(14.dp),
                                tint = metaColor
                            )
                        }
                        IconButton(
                            onClick = { onCancelIncomingFileTransfer(transfer.transferId) },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Stop,
                                contentDescription = strings.stop,
                                modifier = Modifier.size(14.dp),
                                tint = metaColor
                            )
                        }
                    }
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelMedium,
                        color = metaColor
                    )
                    if (message.isEncrypted) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "Encrypted",
                            modifier = Modifier.size(12.dp),
                            tint = metaColor
                        )
                    }
                }
            }
        }
    }
}

private enum class AttachmentKind(val label: String) {
    IMAGE("photo"),
    VIDEO("video note"),
    AUDIO("voice message"),
    FILE("file")
}

private fun ChatMessage.attachmentKind(): AttachmentKind {
    val mimeType = attachment?.mimeType.orEmpty().lowercase(Locale.ROOT)
    return when {
        mimeType.startsWith("image/") -> AttachmentKind.IMAGE
        mimeType.startsWith("video/") -> AttachmentKind.VIDEO
        mimeType.startsWith("audio/") -> AttachmentKind.AUDIO
        else -> AttachmentKind.FILE
    }
}

@Composable
private fun VideoAttachmentPreview(
    message: ChatMessage,
    metaColor: Color
) {
    val attachment = message.attachment
    Surface(
        modifier = Modifier.size(184.dp),
        shape = CircleShape,
        color = if (message.isLocal) {
            Color.White.copy(alpha = 0.16f)
        } else {
            TgDayPalette.searchField
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.size(70.dp),
                shape = CircleShape,
                color = TgDayPalette.composerSend.copy(alpha = if (message.isLocal) 0.92f else 0.18f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.ChatBubble,
                        contentDescription = "Video note",
                        tint = if (message.isLocal) Color.White else TgDayPalette.rowBlue,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (message.isLocal) {
                    Color.White.copy(alpha = 0.18f)
                } else {
                    TgDayPalette.card.copy(alpha = 0.88f)
                }
            ) {
                Text(
                    text = attachment?.let { fileSizeShort(it.sizeBytes) } ?: "video",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = metaColor
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = TgDayPalette.rowMeta
    )
}

@Composable
private fun AttachmentThumb(
    message: ChatMessage,
    size: Dp
) {
    val localContext = LocalContext.current
    val attachment = message.attachment
    val mimeType = attachment?.mimeType.orEmpty().lowercase(Locale.ROOT)
    val isImage = mimeType.startsWith("image/")
    val bitmap = remember(attachment?.localUri, mimeType, size) {
        if (!isImage) {
            null
        } else {
            loadAttachmentBitmap(
                context = localContext,
                localPath = attachment?.localUri,
                maxDimensionPx = if (size.value > 120f) 760 else 300
            )
        }
    }

    if (bitmap != null) {
        val imageModifier = if (size.value > 100f) {
            Modifier
                .fillMaxWidth()
                .heightIn(max = size)
        } else {
            Modifier.size(size)
        }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = attachment?.fileName ?: "Image",
            modifier = imageModifier
                .clip(RoundedCornerShape(10.dp)),
            contentScale = if (size.value > 100f) ContentScale.Fit else ContentScale.Crop
        )
    } else {
        Surface(
            modifier = Modifier.size(size),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFFE3ECF7)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.AttachFile,
                    contentDescription = "Attachment",
                    tint = TgDayPalette.rowBlue
                )
            }
        }
    }
}

private fun loadAttachmentBitmap(
    context: Context,
    localPath: String?,
    maxDimensionPx: Int
): Bitmap? {
    if (localPath.isNullOrBlank()) return null
    val store = SecureLocalStore(context.applicationContext)
    val bytes = store.readAttachment(localPath) ?: return null
    if (bytes.isEmpty()) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null

    var sample = 1
    var probeW = width
    var probeH = height
    while (probeW > maxDimensionPx || probeH > maxDimensionPx) {
        sample *= 2
        probeW /= 2
        probeH /= 2
    }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample.coerceAtLeast(1)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

private fun openAttachment(context: Context, message: ChatMessage) {
    val attachment = message.attachment ?: return
    val localPath = attachment.localUri ?: return
    val sourceFile = File(localPath)
    if (!sourceFile.exists()) return
    val store = SecureLocalStore(context.applicationContext)
    val clearBytes = store.readAttachment(localPath) ?: return
    if (clearBytes.isEmpty()) return

    val safeName = attachment.fileName
        .trim()
        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        .ifBlank { "attachment.bin" }
    val exportDir = File(context.cacheDir, "open_attachments").apply { mkdirs() }
    val file = File(exportDir, "${attachment.transferId}_$safeName")
    val writeOk = runCatching { file.writeBytes(clearBytes) }.isSuccess
    clearBytes.fill(0)
    if (!writeOk) return

    val authority = "${context.packageName}.fileprovider"
    val contentUri = runCatching {
        FileProvider.getUriForFile(context, authority, file)
    }.getOrNull() ?: return

    val mime = attachment.mimeType.ifBlank { "*/*" }
    val openIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(contentUri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(contentUri, "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(openIntent, "Open ${attachment.fileName}"))
    }.recoverCatching {
        context.startActivity(Intent.createChooser(fallbackIntent, "Open ${attachment.fileName}"))
    }.onFailure {
        if (it is ActivityNotFoundException) return@onFailure
    }
    scheduleTransientFileDelete(file, TEMP_EXPORT_FILE_TTL_MS)
}

private fun shareMessage(context: Context, message: ChatMessage) {
    if (message.contentType == ChatContentType.FILE) {
        val attachment = message.attachment ?: return
        val localPath = attachment.localUri ?: return
        val sourceFile = File(localPath)
        if (!sourceFile.exists()) return
        val store = SecureLocalStore(context.applicationContext)
        val clearBytes = store.readAttachment(localPath) ?: return
        if (clearBytes.isEmpty()) return

        val safeName = attachment.fileName
            .trim()
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "attachment.bin" }
        val exportDir = File(context.cacheDir, "share_attachments").apply { mkdirs() }
        val file = File(exportDir, "${attachment.transferId}_$safeName")
        val writeOk = runCatching { file.writeBytes(clearBytes) }.isSuccess
        clearBytes.fill(0)
        if (!writeOk) return

        val authority = "${context.packageName}.fileprovider"
        val contentUri = runCatching {
            FileProvider.getUriForFile(context, authority, file)
        }.getOrNull() ?: return
        val mime = attachment.mimeType.ifBlank { "*/*" }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(sendIntent, "Share ${attachment.fileName}"))
        }.onFailure {
            if (it is ActivityNotFoundException) return@onFailure
        }
        scheduleTransientFileDelete(file, TEMP_EXPORT_FILE_TTL_MS)
        return
    }

    val text = message.text.trim().ifBlank { return }
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(sendIntent, "Share message"))
    }.onFailure {
        if (it is ActivityNotFoundException) return@onFailure
    }
}

private fun createDecryptedPreviewFile(
    context: Context,
    message: ChatMessage,
    folderName: String
): File? {
    val attachment = message.attachment ?: return null
    val localPath = attachment.localUri ?: return null
    val sourceFile = File(localPath)
    if (!sourceFile.exists()) return null
    val clearBytes = SecureLocalStore(context.applicationContext).readAttachment(localPath) ?: return null
    if (clearBytes.isEmpty()) return null

    val safeName = attachment.fileName
        .trim()
        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        .ifBlank { "attachment.bin" }
    val previewDir = File(context.cacheDir, folderName).apply { mkdirs() }
    val file = File(previewDir, "${attachment.transferId}_$safeName")
    val created = runCatching {
        file.writeBytes(clearBytes)
        file
    }.getOrNull()
    clearBytes.fill(0)
    return created
}

private data class ActiveVoiceRecording(
    val recorder: MediaRecorder,
    val outputFile: File,
    val startedAtMs: Long,
    val accumulatedPausedMs: Long = 0L,
    val pausedAtMs: Long? = null
)

internal fun calculateVoiceRecordingElapsedMs(
    startedAtMs: Long,
    accumulatedPausedMs: Long,
    pausedAtMs: Long?,
    nowMs: Long
): Long {
    val effectiveNowMs = pausedAtMs ?: nowMs
    return (effectiveNowMs - startedAtMs - accumulatedPausedMs).coerceAtLeast(0L)
}

private data class PendingVideoCapture(
    val uri: Uri,
    val file: File
)

private fun createVideoNoteCapture(context: Context): PendingVideoCapture? {
    val captureDir = File(context.cacheDir, "video_notes").apply { mkdirs() }
    val file = File(captureDir, "video_note_${System.currentTimeMillis()}.mp4")
    val authority = "${context.packageName}.fileprovider"
    val uri = runCatching {
        FileProvider.getUriForFile(context, authority, file)
    }.getOrNull() ?: return null
    return PendingVideoCapture(uri = uri, file = file)
}

private fun startVoiceCapture(context: Context): ActiveVoiceRecording? {
    val recordingDir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
    val output = File(recordingDir, "voice_${System.currentTimeMillis()}.m4a")
    val recorder = runCatching {
        MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setOutputFile(output.absolutePath)
            prepare()
            start()
        }
    }.getOrNull() ?: return null
    return ActiveVoiceRecording(
        recorder = recorder,
        outputFile = output,
        startedAtMs = System.currentTimeMillis()
    )
}

private fun finishVoiceCapture(recording: ActiveVoiceRecording, keepFile: Boolean): File? {
    var stopped = true
    runCatching { recording.recorder.stop() }
        .onFailure { stopped = false }
    runCatching { recording.recorder.reset() }
    runCatching { recording.recorder.release() }

    val file = recording.outputFile
    if (!keepFile || !stopped || !file.exists() || file.length() <= 0L) {
        runCatching { file.delete() }
        return null
    }
    return file
}

private fun generateInviteQrBitmap(inviteCode: String): Bitmap? {
    val text = inviteCode.trim()
    if (text.isBlank()) return null
    return runCatching {
        val writer = com.google.zxing.qrcode.QRCodeWriter()
        val matrix = writer.encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 720, 720)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) {
                    0xFF111111.toInt()
                } else {
                    0xFFFFFFFF.toInt()
                }
            }
        }
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }.getOrNull()
}

private fun inviteQrScanOptions(): ScanOptions {
    return ScanOptions().apply {
        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        setPrompt("Scan Mesh invite QR")
        setBeepEnabled(false)
        setOrientationLocked(false)
    }
}

private fun retainReadPermission(context: Context, uri: Uri) {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
}

private fun encodeAvatarThumbnail(context: Context, uri: Uri): String? {
    return runCatching {
        val source = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: return null
        val maxSide = maxOf(source.width, source.height).coerceAtLeast(1)
        val scale = min(1f, 256f / maxSide.toFloat())
        val thumbnail = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            source
        }
        val output = java.io.ByteArrayOutputStream()
        thumbnail.compress(Bitmap.CompressFormat.JPEG, 72, output)
        if (thumbnail !== source) thumbnail.recycle()
        source.recycle()
        Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            .takeIf { it.length <= 24_000 }
    }.getOrNull()
}

private fun decodeAvatarBitmap(data: String): Bitmap? {
    if (data.isBlank()) return null
    return runCatching {
        BitmapFactory.decodeByteArray(
            Base64.decode(data, Base64.NO_WRAP),
            0,
            Base64.decode(data, Base64.NO_WRAP).size
        )
    }.getOrNull()
}

@Composable
private fun Avatar(
    label: String,
    seed: String,
    size: Dp,
    online: Boolean,
    avatarData: String = ""
) {
    val base = colorFromSeed(seed)
    val avatarBitmap = remember(avatarData) { decodeAvatarBitmap(avatarData) }
    val initials = remember(label) {
        label
            .split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "M" }
    }

    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.BottomEnd
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, MeshUi.glow.copy(alpha = 0.42f), CircleShape)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(MeshUi.glowAlt.copy(alpha = 0.70f), base)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (avatarBitmap != null) {
                Image(
                    bitmap = avatarBitmap.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = initials,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (online) {
            Box(
                modifier = Modifier
                    .size((size.value * 0.28f).dp)
                    .clip(CircleShape)
                    .background(Color(0xFF34C759))
                    .border(2.dp, Color.White, CircleShape)
            )
        }
    }
}

private data class TabItem(
    val tab: MeshTab,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private data class GlobalSearchHit(
    val key: String,
    val conversationId: String,
    val conversationTitle: String,
    val senderLabel: String,
    val preview: String,
    val timestamp: Long
)

private data class AppPasscodeVerifyResult(
    val success: Boolean,
    val attemptsLeft: Int,
    val lockoutRemainingMs: Long
)

private enum class BackupMode {
    EXPORT,
    IMPORT
}

private enum class ChatListFilter {
    ALL,
    UNREAD,
    GROUPS,
    CHANNELS,
    ARCHIVED
}

private val AVATAR_COLORS = listOf(
    Color(0xFF5D8ABF),
    Color(0xFF52A6D6),
    Color(0xFF50B5A8),
    Color(0xFF7CA45D),
    Color(0xFFD98F44),
    Color(0xFFC96C79),
    Color(0xFF9B6FD3),
    Color(0xFF4B7BEC)
)

private fun colorFromSeed(seed: String): Color {
    val hash = abs(seed.hashCode())
    return AVATAR_COLORS[hash % AVATAR_COLORS.size]
}

private fun formatChatTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return CHAT_LIST_TIME_FORMAT.format(Date(timestamp))
}

private fun labelForFilter(filter: ChatListFilter, strings: MeshStrings): String {
    return when (filter) {
        ChatListFilter.ALL -> strings.all
        ChatListFilter.UNREAD -> strings.unread
        ChatListFilter.GROUPS -> strings.groups
        ChatListFilter.CHANNELS -> strings.channels
        ChatListFilter.ARCHIVED -> strings.archived
    }
}

private fun localizedStatus(status: String): String {
    if (Locale.getDefault().language != "ru") return status
    return when {
        status.equals("Idle", ignoreCase = true) -> "Ожидание"
        status.equals("Mesh online", ignoreCase = true) -> "Mesh включен"
        status.startsWith("Mesh online", ignoreCase = true) -> status
            .replace("Mesh online", "Mesh включен")
            .replace("scan + advertise", "сканирование + вещание")
            .replace("scan-only", "только сканирование")
            .replace("advertise-only", "только вещание")
            .replace("transport degraded", "транспорт ограничен")
            .replace("reduced-advertise", "экономное вещание")
            .replace("wifi-lan", "локальная Wi-Fi сеть")
            .replace("wifi-direct", "Wi-Fi Direct")
            .replace("relay-connected", "relay подключён")
            .replace("relay-connecting", "relay подключается")
            .replace("BLE-priority", "приоритет BLE")
            .replace("internet-fallback", "резерв через интернет")
            .replace("courier-store", "очередь доставки")
            .replace("E2E enabled", "E2E включено")
        status.startsWith("Encrypted message from", ignoreCase = true) -> "Получено зашифрованное сообщение"
        status.startsWith("Encrypted file from", ignoreCase = true) -> status.replace("Encrypted file from", "Получен зашифрованный файл от")
        status.startsWith("File delivered", ignoreCase = true) -> "Файл доставлен"
        status.startsWith("Start mesh", ignoreCase = true) -> "Включите mesh перед отправкой"
        status.startsWith("Peer keys are not synced", ignoreCase = true) -> "Ключи получателя еще не синхронизированы"
        else -> status
    }
}

private fun formatScheduledTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return SCHEDULED_TIME_FORMAT.format(Date(timestamp))
}

private fun fileSizeShort(sizeBytes: Long): String {
    if (sizeBytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB")
    var value = sizeBytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${value.toInt()} ${units[unitIndex]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

private fun formatRecordingDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun formatLockoutDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) + 999L) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) {
        String.format(Locale.US, "%dm %02ds", minutes, seconds)
    } else {
        String.format(Locale.US, "%ds", seconds)
    }
}

private val CHAT_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
private val CHAT_LIST_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
private val SCHEDULED_TIME_FORMAT = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
private const val TEMP_EXPORT_FILE_TTL_MS = 120_000L
private const val TEMP_CACHE_MAX_AGE_MS = 15 * 60 * 1000L
private const val MAX_VIDEO_NOTE_DURATION_SECONDS = 20
private const val MAX_VIDEO_NOTE_BYTES = 3_700_000L
private const val MAX_MEDIA_ALBUM_ITEMS = 10
private const val MAX_MEDIA_CAPTION_LENGTH = 1_024
private val TRANSIENT_CACHE_DIRS = listOf(
    "open_attachments",
    "share_attachments",
    "preview_video",
    "preview_audio",
    "video_notes",
    "voice_notes"
)

private fun pruneTransientDecryptedCaches(context: Context) {
    val now = System.currentTimeMillis()
    TRANSIENT_CACHE_DIRS.forEach { folder ->
        val dir = File(context.cacheDir, folder)
        if (!dir.exists() || !dir.isDirectory) return@forEach
        dir.listFiles()?.forEach { entry ->
            val stale = now - entry.lastModified() > TEMP_CACHE_MAX_AGE_MS
            if (stale) {
                runCatching { entry.deleteRecursively() }
            }
        }
    }
}

private fun transientCacheSizeBytes(context: Context): Long {
    return TRANSIENT_CACHE_DIRS.sumOf { folder ->
        val dir = File(context.cacheDir, folder)
        if (!dir.exists() || !dir.isDirectory) {
            0L
        } else {
            dir.walkTopDown()
                .filter { entry -> entry.isFile }
                .sumOf { entry -> entry.length().coerceAtLeast(0L) }
        }
    }
}

private fun clearTransientDecryptedCaches(context: Context): Int {
    var removedFiles = 0
    TRANSIENT_CACHE_DIRS.forEach { folder ->
        val dir = File(context.cacheDir, folder)
        if (!dir.exists() || !dir.isDirectory) return@forEach
        dir.listFiles()?.forEach { entry ->
            val fileCount = if (entry.isDirectory) {
                entry.walkTopDown().count { child -> child.isFile }
            } else {
                1
            }
            if (runCatching { entry.deleteRecursively() }.getOrDefault(false)) {
                removedFiles += fileCount
            }
        }
    }
    return removedFiles
}

private fun scheduleTransientFileDelete(file: File, delayMs: Long) {
    thread(
        start = true,
        isDaemon = true,
        name = "meshgram-temp-cleaner"
    ) {
        runCatching { Thread.sleep(delayMs) }
        runCatching {
            if (file.exists()) {
                file.delete()
            }
        }
    }
}

private class AppPasscodeManager(context: Context) {
    private val prefs = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (error: Exception) {
        throw IllegalStateException(
            "Encrypted PIN storage is unavailable; refusing plaintext fallback",
            error
        )
    }
    private val random = SecureRandom()

    fun isLockEnabled(): Boolean {
        return prefs.getBoolean(KEY_LOCK_ENABLED, false) && hasConfiguredPasscode()
    }

    fun hasConfiguredPasscode(): Boolean {
        return !prefs.getString(KEY_PIN_HASH, null).isNullOrBlank() &&
            !prefs.getString(KEY_PIN_SALT, null).isNullOrBlank()
    }

    fun enableAppLockWithPasscode(pin: String): Boolean {
        val normalized = normalizePin(pin) ?: return false
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val hash = hashPin(normalized, salt)
        prefs.edit()
            .putString(KEY_PIN_SALT, b64Encode(salt))
            .putString(KEY_PIN_HASH, b64Encode(hash))
            .putBoolean(KEY_LOCK_ENABLED, true)
            .apply()
        return true
    }

    fun disableAppLockWithPasscode(pin: String): Boolean {
        if (!verifyPasscode(pin).success) return false
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SALT)
            .putBoolean(KEY_LOCK_ENABLED, false)
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LOCKOUT_UNTIL_MS)
            .remove(KEY_LOCKOUT_LEVEL)
            .apply()
        return true
    }

    fun verifyPasscode(pin: String): AppPasscodeVerifyResult {
        if (!isLockEnabled()) {
            return AppPasscodeVerifyResult(
                success = true,
                attemptsLeft = MAX_ATTEMPTS_BEFORE_LOCKOUT,
                lockoutRemainingMs = 0L
            )
        }
        val now = System.currentTimeMillis()
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL_MS, 0L)
        if (lockoutUntil > now) {
            return AppPasscodeVerifyResult(
                success = false,
                attemptsLeft = 0,
                lockoutRemainingMs = lockoutUntil - now
            )
        }
        val normalized = normalizePin(pin) ?: return AppPasscodeVerifyResult(
            success = false,
            attemptsLeft = MAX_ATTEMPTS_BEFORE_LOCKOUT,
            lockoutRemainingMs = 0L
        )
        val storedHash = prefs.getString(KEY_PIN_HASH, null)?.let { b64Decode(it) } ?: return AppPasscodeVerifyResult(
            success = false,
            attemptsLeft = MAX_ATTEMPTS_BEFORE_LOCKOUT,
            lockoutRemainingMs = 0L
        )
        val salt = prefs.getString(KEY_PIN_SALT, null)?.let { b64Decode(it) } ?: return AppPasscodeVerifyResult(
            success = false,
            attemptsLeft = MAX_ATTEMPTS_BEFORE_LOCKOUT,
            lockoutRemainingMs = 0L
        )
        val computed = hashPin(normalized, salt)
        val valid = MessageDigest.isEqual(storedHash, computed)
        if (valid) {
            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL_MS, 0L)
                .putInt(KEY_LOCKOUT_LEVEL, 0)
                .apply()
            return AppPasscodeVerifyResult(
                success = true,
                attemptsLeft = MAX_ATTEMPTS_BEFORE_LOCKOUT,
                lockoutRemainingMs = 0L
            )
        }
        val failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        if (failedAttempts >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
            val currentLevel = prefs.getInt(KEY_LOCKOUT_LEVEL, 0)
            val nextLevel = (currentLevel + 1).coerceAtMost(MAX_LOCKOUT_LEVEL)
            val lockSeconds = (BASE_LOCKOUT_SECONDS shl (nextLevel - 1))
                .coerceAtMost(MAX_LOCKOUT_SECONDS)
            val lockMs = lockSeconds.toLong() * 1000L
            val until = now + lockMs
            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL_MS, until)
                .putInt(KEY_LOCKOUT_LEVEL, nextLevel)
                .apply()
            return AppPasscodeVerifyResult(
                success = false,
                attemptsLeft = 0,
                lockoutRemainingMs = lockMs
            )
        }
        prefs.edit().putInt(KEY_FAILED_ATTEMPTS, failedAttempts).apply()
        return AppPasscodeVerifyResult(
            success = false,
            attemptsLeft = (MAX_ATTEMPTS_BEFORE_LOCKOUT - failedAttempts).coerceAtLeast(0),
            lockoutRemainingMs = 0L
        )
    }

    fun changePasscode(currentPin: String, newPin: String): Boolean {
        if (!verifyPasscode(currentPin).success) return false
        val normalizedNew = normalizePin(newPin) ?: return false
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val hash = hashPin(normalizedNew, salt)
        prefs.edit()
            .putString(KEY_PIN_SALT, b64Encode(salt))
            .putString(KEY_PIN_HASH, b64Encode(hash))
            .putBoolean(KEY_LOCK_ENABLED, true)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL_MS, 0L)
            .putInt(KEY_LOCKOUT_LEVEL, 0)
            .apply()
        return true
    }

    fun lockoutRemainingMs(now: Long = System.currentTimeMillis()): Long {
        val until = prefs.getLong(KEY_LOCKOUT_UNTIL_MS, 0L)
        return (until - now).coerceAtLeast(0L)
    }

    private fun normalizePin(pin: String): String? {
        val cleaned = pin.trim()
        if (cleaned.length !in PASSCODE_MIN_LEN..PASSCODE_MAX_LEN) return null
        if (!cleaned.all { it.isDigit() }) return null
        return cleaned
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(":meshgram_app_lock:".toByteArray(Charsets.UTF_8))
        digest.update(pin.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    private fun b64Encode(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun b64Decode(data: String): ByteArray {
        return Base64.decode(data, Base64.NO_WRAP)
    }

    companion object {
        private const val PREFS_NAME = "meshgram_app_lock"
        private const val KEY_LOCK_ENABLED = "lock_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL_MS = "lockout_until_ms"
        private const val KEY_LOCKOUT_LEVEL = "lockout_level"
        private const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 5
        private const val BASE_LOCKOUT_SECONDS = 30
        private const val MAX_LOCKOUT_SECONDS = 15 * 60
        private const val MAX_LOCKOUT_LEVEL = 6
    }
}

private fun hasAllPermissions(context: Context, permissions: Array<String>): Boolean {
    return permissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

private fun requiredPermissions(): Array<String> {
    val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
    permissions += Manifest.permission.RECORD_AUDIO
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions += Manifest.permission.BLUETOOTH_SCAN
        permissions += Manifest.permission.BLUETOOTH_CONNECT
        permissions += Manifest.permission.BLUETOOTH_ADVERTISE
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += Manifest.permission.POST_NOTIFICATIONS
    }
    return permissions.toTypedArray()
}

private fun extractConversationId(intent: Intent?): String? {
    val id = intent
        ?.getStringExtra(EXTRA_OPEN_CONVERSATION_ID)
        ?.trim()
        .orEmpty()
    return id.ifBlank { null }
}

private fun extractSharePayload(intent: Intent?): ExternalSharePayload? {
    if (intent?.action != Intent.ACTION_SEND) return null
    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        ?.trim()
        ?.ifBlank { null }
    val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
    if (text == null && streamUri == null) return null
    val token = listOf(
        System.currentTimeMillis().toString(),
        text.hashCode().toString(),
        streamUri?.toString().orEmpty()
    ).joinToString(":")
    return ExternalSharePayload(
        token = token,
        text = text,
        uri = streamUri
    )
}


