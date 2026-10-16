package com.rhazenz0.f1info.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhazenz0.f1info.data.update.AppUpdates
import com.rhazenz0.f1info.data.update.UpdateManager
import com.rhazenz0.f1info.data.update.UpdateState
import com.rhazenz0.f1info.ui.components.UpdateCard
import com.rhazenz0.f1info.util.TimeFormat
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rhazenz0.f1info.AppGraph
import com.rhazenz0.f1info.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var token by remember { mutableStateOf(AppGraph.f1TvToken.orEmpty()) }
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Live car telemetry (optional)") {
                Text(
                    "During a live session, Formula 1 only sends car telemetry (speed, throttle, brake, RPM, gear) " +
                        "and car positions to F1 TV subscribers. Everything else works without an account, and " +
                        "telemetry for finished sessions is always available in Sessions.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "If you subscribe to F1 TV, paste your F1 TV subscription token here to unlock live telemetry. " +
                        "It is stored only on this phone and sent only to livetiming.formula1.com.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; saved = false },
                    label = { Text("F1 TV subscription token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { AppGraph.f1TvToken = token; saved = true }) { Text("Save") }
                    OutlinedButton(onClick = { token = ""; AppGraph.f1TvToken = null; saved = true }) { Text("Clear") }
                }
                if (saved) Text("Saved. Reopen the Live tab to reconnect.", color = MaterialTheme.colorScheme.primary)
            }
            AppUpdatesSection()
            SectionCard("Data sources") {
                Text(
                    "• Live timing, laps, telemetry, tyres, pit stops, team radio, weather, race control and " +
                        "championship projections come directly from Formula 1's own live timing service " +
                        "(livetiming.formula1.com).\n" +
                        "• Calendar, results and championship standings come from the Jolpica F1 API, " +
                        "the community-run successor of the Ergast API.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "F1 Info is an unofficial fan app and is not associated with Formula 1 companies. " +
                        "F1, FORMULA ONE and related marks are trademarks of Formula One Licensing B.V.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppUpdatesSection() {
    val state by UpdateManager.state.collectAsStateWithLifecycle()
    var auto by remember { mutableStateOf(UpdateManager.autoCheck) }
    var autoInstall by remember { mutableStateOf(UpdateManager.autoInstall) }
    val uri = LocalUriHandler.current
    SectionCard("App updates") {
        Text("F1 Info ${UpdateManager.currentVersion}", fontWeight = FontWeight.Bold)
        Text(
            when (val s = state) {
                UpdateState.Checking -> "Checking GitHub Releases…"
                is UpdateState.UpToDate -> "You have the latest version."
                is UpdateState.Available -> "Version ${s.release.version} is available."
                is UpdateState.Downloading -> "Downloading ${s.release.version}…"
                is UpdateState.Installing -> "Waiting for you to confirm the install."
                is UpdateState.Failed -> s.message
                UpdateState.Idle -> UpdateManager.lastCheckMs.takeIf { it > 0 }?.let {
                    "Last checked ${TimeFormat.localDate(java.time.Instant.ofEpochMilli(it))} ${TimeFormat.localTime(java.time.Instant.ofEpochMilli(it))}."
                } ?: "Not checked yet."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = UpdateManager::check, enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading) {
                Text("Check for updates")
            }
            OutlinedButton(onClick = { uri.openUri(AppUpdates.RELEASES_PAGE) }) { Text("Releases page") }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Check every time the app opens", Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = { auto = it; UpdateManager.autoCheck = it })
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Install updates automatically", Modifier.weight(1f), color = if (auto) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(checked = autoInstall, enabled = auto, onCheckedChange = { autoInstall = it; UpdateManager.autoInstall = it })
        }
        Text(
            "Updates come from this app's GitHub Releases. When an update is found as the app opens, it is downloaded and checked, then installed. " +
                "Android asks you to confirm the first time; on Android 12 and newer, later updates can install without asking. " +
                "Turn off automatic install to be asked first instead.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    UpdateCard(
        state = state,
        currentVersion = UpdateManager.currentVersion,
        dismissedVersion = null,
        onUpdate = UpdateManager::install,
        onLater = UpdateManager::dismiss,
        onCancel = UpdateManager::cancel,
        showWhenDismissed = true,
    )
}
