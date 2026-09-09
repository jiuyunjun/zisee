package com.zisee.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
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
import com.zisee.app.BuildConfig
import com.zisee.app.R
import com.zisee.app.auth.LocalIdentity
import com.zisee.app.auth.remote.ConnectionState
import com.zisee.app.ui.theme.ZiseeTheme

private enum class Page { HOME, SETTINGS, START_CALL, JOIN_CALL }

/**
 * Welcome.dc.html / Main.dc.html / Settings.dc.html: unlike the call surface (always dark, a video
 * call looks like a video call regardless of system theme), these screens follow [ZiseeTheme]'s
 * light/dark [MaterialTheme.colorScheme] so they read correctly in both — colors here are always
 * semantic roles, never the call surface's fixed hex tokens.
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
    contacts: List<com.zisee.app.call.Contact> = emptyList(),
    onVideoCall: (() -> Unit)? = null,
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
                        Page.HOME -> HomeScreen(identityState.identity, contacts, onNavigate = {
                            if (onVideoCall != null && it in setOf(Page.START_CALL, Page.JOIN_CALL)) onVideoCall() else page = it
                        })
                        Page.SETTINGS -> Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
                            PageHeader(stringResource(R.string.settings)) { page = Page.HOME }
                            SettingsScreen(identityState.identity, saveState, onSaveName)
                            if (BuildConfig.DEBUG) BackendPanel(connection, onConnect, onDisconnect)
                        }
                        Page.START_CALL, Page.JOIN_CALL -> Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
                            PageHeader(stringResource(if (page == Page.START_CALL) R.string.start_call else R.string.join_call)) {
                                page = Page.HOME
                            }
                            InfoPanel(stringResource(R.string.call_unavailable_title), stringResource(R.string.call_unavailable_body))
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

/** Main.dc.html: no per-user greeting line in the design, so this omits the one the old layout had. */
@Composable
private fun HomeScreen(identity: LocalIdentity, contacts: List<com.zisee.app.call.Contact>, onNavigate: (Page) -> Unit) {
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Brand()
        RoundIcon("gear", stringResource(R.string.settings)) { onNavigate(Page.SETTINGS) }
    }
    Spacer(Modifier.height(46.dp))
    Text(stringResource(R.string.home_headline), fontSize = 26.sp, lineHeight = 38.sp, letterSpacing = (-0.2).sp)
    Spacer(Modifier.height(30.dp))
    PrimaryPill(stringResource(R.string.start_call), icon = "camera", height = 62.dp, onClick = { onNavigate(Page.START_CALL) })
    Spacer(Modifier.height(12.dp))
    SecondaryPill(stringResource(R.string.join_call), icon = "code", onClick = { onNavigate(Page.JOIN_CALL) })
    Spacer(Modifier.height(42.dp))
    if (contacts.isEmpty()) {
        SectionLabel(stringResource(R.string.recent))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.no_recent_calls), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        SectionLabel(stringResource(R.string.recent))
        Spacer(Modifier.height(12.dp))
        Column {
            contacts.forEach { contact ->
                Row(Modifier.fillMaxWidth().clickable { onNavigate(Page.START_CALL) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IdentityAvatar(contact.displayName, size = 48.dp)
                    Text(contact.displayName, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    RoundIcon("camera", stringResource(R.string.start_call),
                        tint = MaterialTheme.colorScheme.primary,
                        background = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) { onNavigate(Page.START_CALL) }
                }
            }
        }
    }
    Spacer(Modifier.height(42.dp))
    Text(stringResource(R.string.slogan), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
    background: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(background).clickable(onClick = onClick)
        .semantics { role = Role.Button; contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(19.dp)) { scale(size.width / 24f, size.width / 24f, Offset.Zero) { callIcon(icon, tint) } }
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
private fun SecondaryPill(label: String, icon: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).clip(PillShape)
        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f), PillShape)
        .clickable(onClick = onClick).semantics { role = Role.Button; contentDescription = label },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val ink = MaterialTheme.colorScheme.onSurfaceVariant
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
