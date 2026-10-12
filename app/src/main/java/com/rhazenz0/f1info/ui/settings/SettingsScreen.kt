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
