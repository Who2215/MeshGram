package com.meshchat.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.meshchat.app.R
import com.meshchat.app.mesh.FriendRecord
import com.meshchat.app.mesh.FriendDirectory
import com.meshchat.app.mesh.HelloPacket
import com.meshchat.app.mesh.MeshUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FriendActions(
    val setDiscoverable: (Boolean) -> Unit,
    val createInvite: () -> String,
    val previewInvite: (String) -> HelloPacket?,
    val requestInvite: (String) -> Boolean,
    val requestNearby: (String) -> Boolean,
    val accept: (String) -> Boolean,
    val decline: (String) -> Unit,
    val revokeInvite: () -> Unit
)

@Composable
fun FriendsScreen(state: MeshUiState, actions: FriendActions, onBack: () -> Unit, onChat: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var invite by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<HelloPacket?>(null) }
    var previewCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }
    val runAction: (() -> Boolean) -> Unit = { block ->
        if (!busy) scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { runCatching(block).getOrDefault(false) }
            notice = if (ok) R.string.friends_saved else R.string.friends_failed
            busy = false
        }
    }
    val checkCode: (String) -> Unit = { code ->
        if (!busy) scope.launch {
            busy = true
            preview = withContext(Dispatchers.IO) { actions.previewInvite(code) }
            previewCode = code
            if (preview == null) notice = R.string.friends_invalid_invite
            showImport = false
            busy = false
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(checkCode)
    }
    val scanPrompt = stringResource(R.string.friends_scan)
    val incoming = state.friendState.records.filter { !it.accepted && !it.blocked && it.incomingId != null }
    val outgoing = state.friendState.records.filter { !it.accepted && !it.blocked && it.incomingId == null && it.outgoingId != null }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.friends_back))
                }
                Text(stringResource(R.string.friends_title), style = MaterialTheme.typography.headlineSmall)
            }
        }
        item {
            FriendCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.friends_discoverable), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(if (state.friendState.discoverable) R.string.friends_visible else R.string.friends_private),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = state.friendState.discoverable,
                        enabled = !busy,
                        onCheckedChange = { enabled -> runAction { actions.setDiscoverable(enabled); true } })
                }
                Text(stringResource(R.string.friends_network_note), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            FriendCard {
                Text(stringResource(R.string.friends_add), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.friends_invite_help))
                Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        invite = withContext(Dispatchers.IO) { runCatching { actions.createInvite() }.getOrNull() }
                        if (invite == null) notice = R.string.friends_failed
                        busy = false
                    }
                }) { Text(stringResource(R.string.friends_my_qr)) }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                    scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt(scanPrompt).setBeepEnabled(false).setOrientationLocked(false))
                }) { Text(scanPrompt) }
                TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { showImport = true }) {
                    Text(stringResource(R.string.friends_enter_code))
                }
                if (state.friendState.inviteToken.isNotBlank()) {
                    TextButton(onClick = { invite = null; runAction { actions.revokeInvite(); true } }, enabled = !busy) {
                        Text(stringResource(R.string.friends_revoke))
                    }
                }
            }
        }
        if (!state.isRunning) item { Text(stringResource(R.string.friends_mesh_off)) }
        notice?.let { id -> item { Text(stringResource(id), color = MaterialTheme.colorScheme.primary) } }
        if (incoming.isNotEmpty()) {
            item { Text(stringResource(R.string.friends_incoming), style = MaterialTheme.typography.titleLarge) }
            items(incoming, key = { "incoming:${it.nodeId}" }) { record ->
                FriendCard {
                    FriendIdentity(record)
                    Text(stringResource(R.string.friends_accept_help))
                    Button(onClick = { runAction { actions.accept(record.nodeId) } }, enabled = !busy) {
                        Text(stringResource(R.string.friends_accept))
                    }
                    TextButton(onClick = { runAction { actions.decline(record.nodeId); true } }, enabled = !busy) {
                        Text(stringResource(R.string.friends_decline))
                    }
                }
            }
        }
        if (outgoing.isNotEmpty()) {
            item { Text(stringResource(R.string.friends_outgoing), style = MaterialTheme.typography.titleLarge) }
            items(outgoing, key = { "outgoing:${it.nodeId}" }) { record ->
                FriendCard {
                    FriendIdentity(record)
                    Text(stringResource(R.string.friends_pending))
                    TextButton(onClick = { runAction { actions.decline(record.nodeId); true } }, enabled = !busy) {
                        Text(stringResource(R.string.friends_cancel_request))
                    }
                }
            }
        }
        if (state.friendState.discoverable) {
            item { Text(stringResource(R.string.friends_nearby), style = MaterialTheme.typography.titleLarge) }
            if (state.nearbyPeople.isEmpty()) item { Text(stringResource(R.string.friends_no_nearby)) }
            items(state.nearbyPeople.filter { state.friendState.record(it.nodeId) == null }, key = { "near:${it.nodeId}" }) { person ->
                FriendCard {
                    Text(person.alias, style = MaterialTheme.typography.titleMedium)
                    Text(person.fingerprintShort.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy, onClick = { runAction { actions.requestNearby(person.nodeId) } }) {
                        Text(stringResource(R.string.friends_request))
                    }
                }
            }
        }
        item { Text(stringResource(R.string.friends_confirmed), style = MaterialTheme.typography.titleLarge) }
        if (state.contacts.isEmpty()) item { Text(stringResource(R.string.friends_empty)) }
        items(state.contacts, key = { "friend:${it.nodeId}" }) { person ->
            FriendCard {
                Text(person.alias, style = MaterialTheme.typography.titleMedium)
                Button(onClick = { onChat(person.nodeId) }) { Text(stringResource(R.string.friends_chat)) }
            }
        }
    }

    if (showImport) AlertDialog(onDismissRequest = { showImport = false },
        title = { Text(stringResource(R.string.friends_enter_code)) },
        text = { OutlinedTextField(draft, { draft = it.take(4000) }, maxLines = 5,
            label = { Text(stringResource(R.string.friends_code)) }) },
        confirmButton = { TextButton(onClick = { checkCode(draft) }, enabled = draft.isNotBlank() && !busy) {
            Text(stringResource(R.string.friends_check))
        } }, dismissButton = { TextButton(onClick = { showImport = false }) { Text(stringResource(R.string.friends_close)) } })

    preview?.let { person -> AlertDialog(onDismissRequest = { preview = null },
        title = { Text(person.alias) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.friends_fingerprint))
            Text(person.fingerprint.chunked(8).joinToString(" "), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.friends_request_help))
        } },
        confirmButton = { TextButton(onClick = {
            val code = previewCode
            preview = null
            runAction { actions.requestInvite(code) }
        }) { Text(stringResource(R.string.friends_request)) } },
        dismissButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.friends_close)) } }) }

    invite?.let { code ->
        val shareUrl = remember(code) { FriendDirectory.toShareUrl(code) }
        val bitmap = remember(code) { runCatching {
            val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 720, 720)
            Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888).apply {
                val pixels = IntArray(720 * 720) { i -> if (matrix[i % 720, i / 720]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                setPixels(pixels, 0, 720, 0, 0, 720, 720)
            }
        }.getOrNull() }
        AlertDialog(onDismissRequest = { invite = null }, title = { Text(stringResource(R.string.friends_my_qr)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.friends_my_qr), Modifier.fillMaxWidth().aspectRatio(1f)) }
                Text(stringResource(R.string.friends_qr_help))
                Text(stringResource(R.string.friends_link_help), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareUrl)
                    }
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.friends_share_link)))
                }) { Text(stringResource(R.string.friends_share_link)) }
            } }, confirmButton = { TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("MeshGram invitation", shareUrl))
                notice = R.string.friends_link_copied
            }) { Text(stringResource(R.string.friends_copy_link)) } },
            dismissButton = { TextButton(onClick = { invite = null }) { Text(stringResource(R.string.friends_close)) } })
    }
}

@Composable
private fun FriendCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .92f)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun FriendIdentity(record: FriendRecord) {
    Text(record.alias, style = MaterialTheme.typography.titleMedium)
    Text(record.fingerprint.chunked(8).joinToString(" "), style = MaterialTheme.typography.bodySmall)
}
