package com.lazydoglab.zisee.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lazydoglab.zisee.BuildConfig
import com.lazydoglab.zisee.R
import com.lazydoglab.zisee.auth.LocalIdentity
import com.lazydoglab.zisee.auth.remote.ConnectionState
import com.lazydoglab.zisee.ui.theme.ZiseeTheme

private enum class Page { HOME, SETTINGS }

/**
 * What the home screen can do with the call feature. Null in previews and before an identity
 * exists; the home screen then still lays out, with its call actions inert.
 */
class CallHomeActions(
    val activeCall: Boolean = false,
    val contacts: List<com.lazydoglab.zisee.call.Contact> = emptyList(),
    val contactsLoaded: Boolean = false,
    val pendingInvite: String = "",
    val notice: String = "",
    val contactsStatus: String = "",
    val onInvite: () -> Unit = {},
    val onJoin: (String) -> Unit = {},
    val onCallContact: (String) -> Unit = {},
    val onRemoveContact: (String) -> Unit = {},
    val onPermissionsDenied: () -> Unit = {},
)

/**
 * Welcome.dc.html / Main.dc.html / Settings.dc.html: unlike the call surface (always dark, a video
 * call looks like a video call regardless of system theme), these screens follow [ZiseeTheme]'s
 * light/dark [MaterialTheme.colorScheme] so they read correctly in both — colors here are always
 * semantic roles, never the call surface's fixed hex tokens.
 *
 * Main.dc.html and HomeLight.dc.html are one screen in two palettes, so this is the only home:
 * its call actions start a call directly rather than opening a second, dark copy of itself.
 */
@Composable
fun ZiseeApp(
    identityState: IdentityState,
    saveState: SaveState,
    onSaveName: (String) -> Unit,
    onRetry: () -> Unit,
    connection: ConnectionState = ConnectionState.NOT_CONFIGURED,
    onConnect: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    call: CallHomeActions? = null,
) {
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    BackHandler(enabled = page != Page.HOME) { page = Page.HOME }
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 36.dp, bottom = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
                when (identityState) {
                    IdentityState.Loading -> Column(Modifier.fillMaxWidth().padding(top = 200.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.loading_identity), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IdentityState.ReadError -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.identity_read_error))
                        PrimaryPill(stringResource(R.string.retry), onClick = onRetry)
                    }
                    IdentityState.Welcome -> WelcomeScreen(saveState, onSaveName)
                    is IdentityState.Ready -> when (page) {
                        Page.HOME -> HomeScreen(call) { page = Page.SETTINGS }
                        Page.SETTINGS -> Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
                            PageHeader(stringResource(R.string.settings)) { page = Page.HOME }
                            SettingsScreen(identityState.identity, saveState, onSaveName)
                            if (BuildConfig.DEBUG) {
                                BackendPanel(connection, onConnect, onDisconnect)
                                DebugToolsPanel()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackendPanel(state: ConnectionState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val message = when (state) {
        ConnectionState.NOT_CONFIGURED -> R.string.backend_not_configured
        ConnectionState.DISCONNECTED -> R.string.backend_disconnected
        ConnectionState.AUTHENTICATING -> R.string.backend_authenticating
        ConnectionState.CONNECTED -> R.string.backend_connected
        ConnectionState.RECONNECTING -> R.string.backend_reconnecting
        ConnectionState.CONFLICT -> R.string.backend_conflict
        ConnectionState.KEY_UNAVAILABLE -> R.string.backend_key_unavailable
        ConnectionState.FAILED -> R.string.backend_failed
    }
    Column(Modifier.padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(stringResource(R.string.backend_title))
        SettingsGroup { Row(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
            Text(stringResource(message), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        } }
        val active = state in setOf(ConnectionState.AUTHENTICATING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)
        if (state != ConnectionState.NOT_CONFIGURED) {
            SecondaryPill(stringResource(if (active) R.string.backend_disconnect else R.string.backend_connect),
                onClick = if (active) onDisconnect else onConnect)
        }
    }
}

/**
 * Debug builds only. The call and AR surface is otherwise reachable only from a real call, so this
 * opens the same surface on synthetic frames — the on-device way to check its layout and AR states
 * without a peer, a camera or ARCore. The fixture lives in the debug source set, so it is launched
 * by class name and the row reports it instead of crashing if a build ever ships without it.
 */
@Composable
private fun DebugToolsPanel() {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(stringResource(R.string.debug_tools))
        SettingsGroup {
            val label = stringResource(R.string.debug_call_preview)
            Box(Modifier.fillMaxWidth().clickable {
                val intent = android.content.Intent()
                    .setClassName(context, "com.lazydoglab.zisee.ui.CallPreviewActivity")
                    .putExtra("interactive", true)
                missing = try {
                    context.startActivity(intent)
                    false
                } catch (error: android.content.ActivityNotFoundException) {
                    true
                }
            }.semantics { role = Role.Button; contentDescription = label }) {
                SettingsRow(label, stringResource(R.string.debug_call_preview_body))
            }
        }
        if (missing) Text(stringResource(R.string.debug_call_preview_missing),
            fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
    }
}

/** Welcome.dc.html */
@Composable
private fun WelcomeScreen(save: SaveState, onSaveName: (String) -> Unit) {
    Spacer(Modifier.height(24.dp))
    Brand()
    Spacer(Modifier.height(60.dp))
    Text(stringResource(R.string.welcome), fontSize = 27.sp, lineHeight = 38.sp, letterSpacing = (-0.2).sp)
    Spacer(Modifier.height(14.dp))
    Text(stringResource(R.string.name_prompt), fontSize = 16.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(28.dp))
    NameEditor(initialName = "", save = save, action = stringResource(R.string.continue_action), onSave = onSaveName)
    Spacer(Modifier.height(28.dp))
    InfoPanel(icon = "lock", body = stringResource(R.string.onboarding_note))
}

/**
 * Main.dc.html / HomeLight.dc.html: no per-user greeting line in the design, so this omits the one
 * the old layout had. Its actions place the call themselves — camera and microphone are asked for
 * here, at the tap, so nothing turns a camera on without one.
 *
 * Once any contact exists, the point of opening Zisee is almost always to call one of them, so
 * they lead the page in a grouped card; inviting someone new or entering a code drops to a small
 * secondary row below. With no contacts yet, starting a call is the only thing to do, so that
 * takes the lead instead — the original layout, unchanged.
 */
@Composable
private fun HomeScreen(call: CallHomeActions?, onSettings: () -> Unit) {
    val configured = BuildConfig.BACKEND_URL.isNotEmpty() && call != null
    var code by rememberSaveable { mutableStateOf("") }
    var showJoin by rememberSaveable { mutableStateOf(false) }
    val pending = call?.pendingInvite.orEmpty()
    // A code that arrived through a link replaces whatever was typed and opens the join field, so
    // the field matches the invitation the user just opened.
    var pendingDismissed by rememberSaveable(pending) { mutableStateOf(false) }
    LaunchedEffect(pending) { if (pending.isNotEmpty()) { code = pending; showJoin = true } }

    var granting by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val action = granting
        granting = null
        if (grants[Manifest.permission.CAMERA] == true && grants[Manifest.permission.RECORD_AUDIO] == true) action?.invoke()
        else call?.onPermissionsDenied?.invoke()
    }
    fun withCameraAndMic(action: () -> Unit) {
        granting = action
        permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    val busy = granting != null || call?.activeCall == true
    val contacts = call?.contacts.orEmpty()

    @Composable
    fun NewCallRow() {
        AnimatedVisibility(showJoin) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CodeField(code, onValueChange = { code = it })
                PrimaryPill(stringResource(R.string.call_peer), icon = "camera", height = 58.dp,
                    enabled = configured && !busy && code.isNotBlank(),
                    onClick = { withCameraAndMic { call?.onJoin(code.trim()) } })
            }
        }
        if (BuildConfig.BACKEND_URL.isEmpty()) Text(stringResource(R.string.backend_missing_call),
            Modifier.padding(top = 10.dp), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Brand()
        RoundIcon("gear", stringResource(R.string.settings), onClick = onSettings)
    }
    val pendingVisible = pending.isNotEmpty() && !pendingDismissed
    // Contacts start empty until the first fetch resolves; deciding the layout on that
    // not-yet-loaded emptiness would show the old hero layout and then jump to the contacts-first
    // one a moment later for anyone who actually has contacts. Holding here for a beat avoids it.
    val loadingContacts = call != null && !call.contactsLoaded
    Spacer(Modifier.height(if (!pendingVisible && !loadingContacts && contacts.isNotEmpty()) 32.dp else 46.dp))
    if (pendingVisible) {
        PendingInvite(busy = busy, onDismiss = { pendingDismissed = true },
            onOpen = { withCameraAndMic { call?.onJoin(pending) } })
    } else if (loadingContacts) {
        Box(Modifier.fillMaxWidth().padding(vertical = 80.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    } else if (contacts.isNotEmpty()) {
        SectionLabel(stringResource(R.string.contacts_section))
        Spacer(Modifier.height(12.dp))
        ContactsCard {
            contacts.forEachIndexed { index, contact ->
                if (index > 0) SettingsDivider()
                ContactRow(contact, enabled = configured && !busy,
                    onCall = { withCameraAndMic { call?.onCallContact(contact.identityId) } },
                    onRemove = { call?.onRemoveContact(contact.identityId) })
            }
        }
        Spacer(Modifier.height(28.dp))
        SectionLabel(stringResource(R.string.start_new_call))
        Spacer(Modifier.height(12.dp))
        // This publishes an invitation to be scanned or opened; it does not dial anyone, so it
        // says so rather than promising a call that nobody is on the other end of yet.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryPill(stringResource(R.string.invite_peer), icon = "camera", modifier = Modifier.weight(1f),
                enabled = configured && !busy, onClick = { withCameraAndMic { call?.onInvite() } })
            SecondaryPill(stringResource(R.string.join_call), icon = "code", modifier = Modifier.weight(1f),
                onClick = { showJoin = !showJoin })
        }
        NewCallRow()
    } else {
        Text(stringResource(R.string.home_headline), fontSize = 26.sp, lineHeight = 38.sp, letterSpacing = (-0.2).sp)
        Spacer(Modifier.height(30.dp))
        PrimaryPill(stringResource(R.string.invite_peer), icon = "camera", height = 62.dp,
            enabled = configured && !busy, onClick = { withCameraAndMic { call?.onInvite() } })
        Spacer(Modifier.height(12.dp))
        SecondaryPill(stringResource(R.string.join_call), icon = "code", onClick = { showJoin = !showJoin })
        NewCallRow()
        Spacer(Modifier.height(42.dp))
        SectionLabel(stringResource(R.string.contacts_section))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.no_recent_calls), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // Both "the call ended" and "permissions were refused" land here; neither is loud enough to
    // deserve the error colour, and the destructive-looking red would misread on the ordinary one.
    if (!call?.notice.isNullOrEmpty()) Text(call.notice, Modifier.padding(top = 14.dp),
        fontSize = 12.5.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!call?.contactsStatus.isNullOrEmpty()) Text(call.contactsStatus, Modifier.padding(top = 8.dp),
        fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(28.dp))
    Text(stringResource(R.string.call_permission_note), fontSize = 12.sp, lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.slogan), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
private fun ContactsCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(StandardCardShape).background(MaterialTheme.colorScheme.surface)
        .padding(horizontal = 18.dp)) { content() }
}

/** InviteOpen.dc.html, adapted: the caller's identity is unknown until the invitation is redeemed. */
@Composable
private fun PendingInvite(busy: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(StandardCardShape).background(MaterialTheme.colorScheme.surface)
        .padding(horizontal = 22.dp, vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center) {
            val ink = MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(Modifier.size(34.dp)) { scale(size.width / 24f, size.width / 24f, Offset.Zero) { callIcon("camera", ink) } }
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.invite_opened), fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(20.dp))
        PrimaryPill(stringResource(R.string.start_call), icon = "camera", height = 56.dp, enabled = !busy, onClick = onOpen)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.later), Modifier.clickable(enabled = !busy, onClick = onDismiss).padding(10.dp),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Main.dc.html contact row: avatar, name, one-tap call. Removal has no mock; kept small and muted. */
@Composable
private fun ContactRow(contact: com.lazydoglab.zisee.call.Contact, enabled: Boolean, onCall: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        IdentityAvatar(contact.displayName, size = 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(contact.displayName, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.contact_call_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RoundIcon("camera", stringResource(R.string.call_contact, contact.displayName), size = 52.dp, iconSize = 21.dp,
            tint = MaterialTheme.colorScheme.primary,
            background = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            enabled = enabled, onClick = onCall)
        val remove = stringResource(R.string.remove_contact, contact.displayName)
        val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        Box(Modifier.size(28.dp).clickable(enabled = enabled, onClick = onRemove)
            .semantics { role = Role.Button; contentDescription = remove }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(13.dp)) { scale(size.width / 24f, size.width / 24f, Offset.Zero) { callIcon("close", ink) } }
        }
    }
}

@Composable
private fun CodeField(value: String, onValueChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().height(58.dp).clip(PillShape).background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f), PillShape)
        .padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(stringResource(R.string.invite_code_hint), fontSize = 14.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(value = value, onValueChange = { onValueChange(it.take(80)) }, singleLine = true,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.5.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
    }
}

/** Settings.dc.html: identity card, then grouped rows for what actually exists (no fabricated
 * quality/network/device toggles the ViewModel has no state for). */
@Composable
private fun SettingsScreen(identity: LocalIdentity, save: SaveState, onSave: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
        SettingsGroup {
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IdentityAvatar(identity.displayName, size = 52.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(identity.displayName, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.local_only), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NameEditor(identity.displayName, save, stringResource(R.string.save_name), onSave)
            Text("${stringResource(R.string.identity_label)}  ${identity.identityId}",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        InfoPanel(icon = "lock", body = stringResource(R.string.local_identity_note))
        Column {
            SectionLabel(stringResource(R.string.appearance))
            Spacer(Modifier.height(10.dp))
            SettingsGroup {
                SettingsRow(stringResource(R.string.appearance_system))
                SettingsDivider()
                SettingsRow(stringResource(R.string.about), stringResource(R.string.about_body, BuildConfig.VERSION_NAME))
            }
        }
        Text("Zisee ${BuildConfig.VERSION_NAME}", fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        if (subtitle != null) Text(subtitle, fontSize = 12.5.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)))

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(StandardCardShape).background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp)) {
        content()
    }
}

@Composable
private fun NameEditor(initialName: String, save: SaveState, action: String, onSave: (String) -> Unit) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val focused = name.isNotEmpty()
        Row(Modifier.fillMaxWidth().height(62.dp).clip(PillShape).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f), PillShape)
            .padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value = name, onValueChange = { name = it }, enabled = !save.busy, singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f))
        }
        Text(stringResource(R.string.name_hint), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 22.dp))
        if (save.error != null) {
            Text(stringResource(if (save.error == SaveError.INVALID_NAME) R.string.name_invalid else R.string.identity_write_error),
                fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 22.dp))
        }
        PrimaryPill(if (save.busy) stringResource(R.string.saving) else action, height = 62.dp,
            enabled = !save.busy && name.isNotBlank(), onClick = { onSave(name) })
    }
}

@Composable
private fun Brand() {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Zisee", fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
        Text(stringResource(R.string.app_name), fontSize = 11.sp, letterSpacing = 5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val ink = MaterialTheme.colorScheme.onSurface
        val back = stringResource(R.string.back)
        Box(Modifier.size(24.dp).clickable(onClick = onBack).semantics { role = Role.Button; contentDescription = back }) {
            Canvas(Modifier.fillMaxSize()) { scale(size.width / 24f, size.width / 24f, Offset.Zero) { callIcon("back", ink) } }
        }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp)
    }
}

private val InfoPanelShape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)

/** Welcome.dc.html / Invite.dc.html: an icon-and-text explanation panel, not an emphasized card. */
@Composable
private fun InfoPanel(title: String? = null, body: String, icon: String? = null) {
    Row(Modifier.fillMaxWidth().clip(InfoPanelShape).background(MaterialTheme.colorScheme.surface)
        .padding(horizontal = 18.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Canvas(Modifier.size(19.dp).padding(top = 1.dp)) {
            scale(size.width / 24f, size.width / 24f, Offset.Zero) { callIcon(icon, CallAccent) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(body, fontSize = 12.5.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun IdentityAvatar(name: String, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(name.take(1), fontSize = (size.value * 0.35f).sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RoundIcon(icon: String, description: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    background: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 44.dp, iconSize: androidx.compose.ui.unit.Dp = 19.dp,
    onClick: () -> Unit) {
    val dim = if (enabled) 1f else 0.4f
    Box(Modifier.size(size).clip(CircleShape).background(background.copy(alpha = background.alpha * dim))
        .clickable(enabled = enabled, onClick = onClick)
        .semantics { role = Role.Button; contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(iconSize)) { scale(this.size.width / 24f, this.size.width / 24f, Offset.Zero) { callIcon(icon, tint.copy(alpha = tint.alpha * dim)) } }
    }
}

@Composable
private fun PrimaryPill(label: String, icon: String? = null, height: androidx.compose.ui.unit.Dp = 62.dp,
    enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(height).clip(PillShape)
        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.35f))
        .clickable(enabled = enabled, onClick = onClick).semantics { role = Role.Button; contentDescription = label },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val ink = MaterialTheme.colorScheme.onPrimary
        if (icon != null) {
            Canvas(Modifier.size(21.dp)) { scale(size.width / 24f, size.width / 24f, Offset.Zero) { callIcon(icon, ink) } }
            Spacer(Modifier.width(10.dp))
        }
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = ink)
    }
}

@Composable
private fun SecondaryPill(label: String, icon: String? = null, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: () -> Unit) {
    val dim = if (enabled) 1f else 0.4f
    Row(modifier.fillMaxWidth().height(52.dp).clip(PillShape)
        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f * dim), PillShape)
        .clickable(enabled = enabled, onClick = onClick).semantics { role = Role.Button; contentDescription = label },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim)
        if (icon != null) {
            Canvas(Modifier.size(18.dp)) { scale(size.width / 24f, size.width / 24f, Offset.Zero) { callIcon(icon, ink) } }
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontSize = 15.sp, color = ink)
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun WelcomePreview() {
    ZiseeTheme(darkTheme = true) { ZiseeApp(IdentityState.Welcome, SaveState(), {}, {}) }
}
