package com.rhazenz0.f1info.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rhazenz0.f1info.AppGraph
import com.rhazenz0.f1info.data.timing.LoadedSession
import com.rhazenz0.f1info.data.timing.MeetingRef
import com.rhazenz0.f1info.data.timing.SessionRef
import com.rhazenz0.f1info.ui.components.EmptyState
import com.rhazenz0.f1info.ui.components.Loadable
import com.rhazenz0.f1info.ui.components.LoadableContent
import com.rhazenz0.f1info.ui.components.SectionCard
import com.rhazenz0.f1info.ui.components.Selector
import com.rhazenz0.f1info.ui.components.userMessage
import com.rhazenz0.f1info.ui.theme.F1Colors
import com.rhazenz0.f1info.util.TimeFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.Year

// ---------------------------------------------------------------- Browser

class SessionBrowserViewModel : ViewModel() {
    /** The official timing archive starts in 2018. */
    val years: List<Int> = (Year.now().value downTo 2018).toList()
    private val _year = MutableStateFlow(Year.now().value)
    val year: StateFlow<Int> = _year
    private val _state = MutableStateFlow<Loadable<List<MeetingRef>>>(Loadable.Loading)
    val state: StateFlow<Loadable<List<MeetingRef>>> = _state

    init { load() }

    fun select(y: Int) { _year.value = y; load() }

    fun load(force: Boolean = false) {
        _state.value = Loadable.Loading
        viewModelScope.launch {
            _state.value = try {
                Loadable.Ready(AppGraph.meetings(_year.value, force))
            } catch (e: Exception) {
                Loadable.Error(e.userMessage())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionBrowserScreen(onOpenSession: (SessionRef) -> Unit, vm: SessionBrowserViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val year by vm.year.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Sessions") },
            actions = {
                Selector("Season", vm.years, year, { it.toString() }, vm::select, Modifier.padding(end = 4.dp))
                IconButton(onClick = { vm.load(force = true) }) { Icon(Icons.Filled.Refresh, "Refresh") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LoadableContent(state, onRetry = { vm.load(true) }) { meetings ->
            val now = Instant.now()
            val started = meetings.filter { m -> m.sessions.any { it.start?.isBefore(now) == true } }.reversed()
            if (started.isEmpty()) {
                EmptyState("No sessions have run yet this season.")
                return@LoadableContent
            }
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        "Full timing from Formula 1's official archive: every lap, sector, tyre, pit stop, radio clip and telemetry trace.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(started, key = { it.key }) { m ->
                    SectionCard(m.name) {
                        Text("${m.circuit} · ${m.country}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        m.sessions.filter { it.start?.isBefore(now) == true }.reversed().forEach { s ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenSession(s) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.sessionName, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${TimeFormat.localDate(s.start)} · ${TimeFormat.localTime(s.start)}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (s.end?.isAfter(now) == true) {
                                    Text("IN PROGRESS", color = F1Colors.Red, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Detail

class SessionDetailViewModel(private val year: Int, private val path: String) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<LoadedSession>>(Loadable.Loading)
    val state: StateFlow<Loadable<LoadedSession>> = _state
    private val _telemetry = MutableStateFlow<TelemetryUi>(TelemetryUi.Idle)
    val telemetry: StateFlow<TelemetryUi> = _telemetry
    val radio = RadioPlayer()
    private var telemetryJob: Job? = null

    init { load() }

    fun load(force: Boolean = false) {
        _state.value = Loadable.Loading
        viewModelScope.launch {
            try {
                val ref = AppGraph.sessionRef(year, path) ?: throw IllegalStateException("Session not found in the timing archive.")
                val loaded = AppGraph.loadSession(ref, force) { msg -> _state.value = Loadable.Progress(msg) }
                _state.value = Loadable.Ready(loaded)
                AppGraph.cachedTelemetry(ref)?.let { _telemetry.value = TelemetryUi.Ready(it) }
            } catch (e: Exception) {
                _state.value = Loadable.Error(e.userMessage())
            }
        }
    }

    fun loadTelemetry() {
        val ref = (_state.value as? Loadable.Ready)?.value?.ref ?: return
        if (telemetryJob?.isActive == true) return
        _telemetry.value = TelemetryUi.Loading("Starting download…")
        telemetryJob = viewModelScope.launch {
            _telemetry.value = try {
                TelemetryUi.Ready(AppGraph.telemetry(ref) { msg -> _telemetry.value = TelemetryUi.Loading(msg) })
            } catch (e: OutOfMemoryError) {
                TelemetryUi.Error("Not enough memory to decode telemetry for this session on this device.")
            } catch (e: Exception) {
                TelemetryUi.Error(e.userMessage())
            }
        }
    }

    override fun onCleared() {
        radio.stop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    year: Int,
    path: String,
    onBack: () -> Unit,
    vm: SessionDetailViewModel = viewModel(key = "session-$path") { SessionDetailViewModel(year, path) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { vm.radio.stop() } }
    val ref = (state as? Loadable.Ready)?.value?.ref

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(ref?.sessionName ?: "Session", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (ref != null) {
                        Text(
                            "${ref.meetingName} ${ref.year}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { vm.load(force = true) }) { Icon(Icons.Filled.Refresh, "Reload") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LoadableContent(state, onRetry = { vm.load(true) }) { loaded ->
            SessionTabsView(
                snapshot = loaded.snapshot,
                isLive = false,
                radio = vm.radio,
                telemetryTab = { ArchiveTelemetryTab(loaded.snapshot, telemetry, vm::loadTelemetry) },
            )
        }
    }
}
