package com.rhazenz0.f1info.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhazenz0.f1info.data.update.AppRelease
import com.rhazenz0.f1info.data.update.UpdateState
import com.rhazenz0.f1info.ui.theme.F1Colors
import java.util.Locale

/** The release an update card should show for [state], or null when there is nothing to offer. */
fun UpdateState.release(): AppRelease? = when (this) {
    is UpdateState.Available -> release
    is UpdateState.Downloading -> release
    is UpdateState.Installing -> release
    is UpdateState.Failed -> release
    else -> null
}

/**
 * Offers a newer version of the app and shows download and install progress.
 * Shows nothing when there is no update, or when the user chose "Later" for this version.
 */
@Composable
fun UpdateCard(
    state: UpdateState,
    currentVersion: String,
    dismissedVersion: String?,
    onUpdate: (AppRelease) -> Unit,
    onLater: (AppRelease) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    showWhenDismissed: Boolean = false,
) {
    val release = state.release() ?: return
    if (!showWhenDismissed && state is UpdateState.Available && dismissedVersion == release.version) return
    var expanded by rememberSaveable { mutableStateOf(false) }

    SectionCard("Update available", modifier) {
        Text("F1 Info ${release.version}", style = MaterialTheme.typography.titleLarge)
        Text(
            "You have $currentVersion${if (release.apkSize > 0) " · download ${formatSize(release.apkSize)}" else ""}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (release.notes.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                release.notes,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (release.notes.lines().size > 4 || release.notes.length > 200) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "What's new") }
            }
        }
        Spacer(Modifier.height(8.dp))
        when (state) {
            is UpdateState.Downloading -> {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Downloading… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
            is UpdateState.Installing -> Column {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Confirm the install in the Android prompt. If Android asks, allow F1 Info to install apps, then come back.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { onUpdate(release) }) { Text("Show the install prompt again") }
            }
            else -> {
                if (state is UpdateState.Failed) {
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onUpdate(release) }) {
                        Text(if (state is UpdateState.Failed) "Try again" else "Update", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { onLater(release) }) { Text("Later") }
                }
            }
        }
        Text(
            "Downloaded from GitHub Releases and checked against its published SHA-256.",
            style = MaterialTheme.typography.labelSmall, color = F1Colors.TextSecondary,
        )
    }
}

private fun formatSize(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
