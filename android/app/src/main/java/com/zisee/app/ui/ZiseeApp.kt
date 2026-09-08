package com.zisee.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zisee.app.BuildConfig
import com.zisee.app.R
import com.zisee.app.auth.LocalIdentity
import com.zisee.app.ui.theme.ZiseeTheme

private enum class Page { HOME, SETTINGS, START_CALL, JOIN_CALL }

@Composable
fun ZiseeApp(
    identityState: IdentityState,
    saveState: SaveState,
    onSaveName: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    BackHandler(enabled = page != Page.HOME) { page = Page.HOME }
    Scaffold { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).imePadding()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when (identityState) {
                    IdentityState.Loading -> {
                        Text(stringResource(R.string.loading_identity))
                        CircularProgressIndicator()
                    }
                    IdentityState.ReadError -> {
                        Text(stringResource(R.string.identity_read_error))
                        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                    }
                    IdentityState.Welcome -> WelcomeScreen(saveState, onSaveName)
                    is IdentityState.Ready -> when (page) {
                        Page.HOME -> HomeScreen(identityState.identity, onNavigate = { page = it })
                        Page.SETTINGS -> {
                            PageTitle(stringResource(R.string.settings)) { page = Page.HOME }
                            SettingsScreen(identityState.identity, saveState, onSaveName)
                        }
                        Page.START_CALL, Page.JOIN_CALL -> {
                            PageTitle(stringResource(if (page == Page.START_CALL) R.string.start_call else R.string.join_call)) {
                                page = Page.HOME
                            }
                            InfoCard(stringResource(R.string.call_unavailable_title), stringResource(R.string.call_unavailable_body))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.WelcomeScreen(save: SaveState, onSaveName: (String) -> Unit) {
    Brand()
    Spacer(Modifier.height(32.dp))
    Text(stringResource(R.string.welcome), style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.name_prompt), color = MaterialTheme.colorScheme.onSurfaceVariant)
    NameEditor(initialName = "", save = save, action = stringResource(R.string.continue_action), onSave = onSaveName)
    Text(stringResource(R.string.onboarding_note), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.slogan), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColumnScope.HomeScreen(identity: LocalIdentity, onNavigate: (Page) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Brand()
        TextButton(onClick = { onNavigate(Page.SETTINGS) }) { Text(stringResource(R.string.settings)) }
    }
    Text(stringResource(R.string.greeting, identity.displayName), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.home_headline), style = MaterialTheme.typography.headlineMedium)
    Button(onClick = { onNavigate(Page.START_CALL) }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp)) {
        Text(stringResource(R.string.start_call))
    }
    OutlinedButton(onClick = { onNavigate(Page.JOIN_CALL) }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp)) {
        Text(stringResource(R.string.join_call))
    }
    InfoCard(stringResource(R.string.recent), stringResource(R.string.no_recent_calls))
    Text(stringResource(R.string.slogan), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColumnScope.SettingsScreen(identity: LocalIdentity, save: SaveState, onSave: (String) -> Unit) {
    InfoCard(stringResource(R.string.local_only), stringResource(R.string.local_identity_note))
    NameEditor(identity.displayName, save, stringResource(R.string.save_name), onSave)
    Text(stringResource(R.string.identity_label), style = MaterialTheme.typography.labelLarge)
    Text(identity.identityId, style = MaterialTheme.typography.bodySmall)
    InfoCard(stringResource(R.string.appearance), stringResource(R.string.appearance_system))
    InfoCard(stringResource(R.string.about), stringResource(R.string.about_body, BuildConfig.VERSION_NAME))
}

@Composable
private fun NameEditor(initialName: String, save: SaveState, action: String, onSave: (String) -> Unit) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.display_name)) }, singleLine = true,
            enabled = !save.busy, isError = save.error == SaveError.INVALID_NAME,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            supportingText = { Text(stringResource(R.string.name_hint)) },
        )
        if (save.error != null) {
            Text(stringResource(if (save.error == SaveError.INVALID_NAME) R.string.name_invalid else R.string.identity_write_error),
                color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = { onSave(name) }, enabled = !save.busy && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp),
        ) { Text(if (save.busy) stringResource(R.string.saving) else action) }
    }
}

@Composable
private fun Brand() {
    Column {
        Text("Zisee", style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PageTitle(title: String, onBack: () -> Unit) {
    Column {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun WelcomePreview() {
    ZiseeTheme(darkTheme = true) { ZiseeApp(IdentityState.Welcome, SaveState(), {}, {}) }
}
