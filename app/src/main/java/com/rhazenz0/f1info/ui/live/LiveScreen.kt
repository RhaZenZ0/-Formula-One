package com.rhazenz0.f1info.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rhazenz0.f1info.AppGraph
import com.rhazenz0.f1info.data.timing.ConnectionState
import com.rhazenz0.f1info.data.timing.LiveEvent
import com.rhazenz0.f1info.data.timing.SessionAccumulator
import com.rhazenz0.f1info.data.timing.SessionSnapshot
import com.rhazenz0.f1info.ui.components.CenteredProgress
import com.rhazenz0.f1info.ui.session.LiveCar
import com.rhazenz0.f1info.ui.session.LiveTelemetry
import com.rhazenz0.f1info.ui.session.LiveTelemetryTab
import com.rhazenz0.f1info.ui.session.RadioPlayer
import com.rhazenz0.f1info.ui.session.SessionTabsView
import com.rhazenz0.f1info.ui.theme.F1Colors
import com.rhazenz0.f1info.util.TimeFormat
import com.rhazenz0.f1info.util.at
import com.rhazenz0.f1info.util.elements
import com.rhazenz0.f1info.util.entries
import com.rhazenz0.f1info.util.int
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.time.Instant

data class LiveStatus(val state: ConnectionState, val message: String?, val transport: String?)

class LiveViewModel : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.Default.limitedParallelism(1)

    private val _status = MutableStateFlow(LiveStatus(ConnectionState.Disconnected, null, null))
    val status: StateFlow<LiveStatus> = _status
    private val _snapshot = MutableStateFlow<SessionSnapshot?>(null)
    val snapshot: StateFlow<SessionSnapshot?> = _snapshot
    private val _telemetry = MutableStateFlow(LiveTelemetry())
    val telemetry: StateFlow<LiveTelemetry> = _telemetry
    val radio = RadioPlayer()

    private var accumulator = SessionAccumulator()
    private var dirty = false
    private var job: Job? = null

    // Live car state (touched only on [worker]).
    private val cars = HashMap<String, LiveCar>()
    private val positions = HashMap<String, Pair<Float, Float>>()
    private var outlineDriver: String? = null
    private val outlineX = ArrayList<Float>()
    private val outlineY = ArrayList<Float>()
    private var telemetryDirty = false

    val hasToken: Boolean get() = !AppGraph.f1TvToken.isNullOrBlank()

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch(worker) {
            launch {
                while (isActive) {
                    delay(1000)
                    publish()
                }
            }
            try {
                AppGraph.live.events().collect { event -> handle(event) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = LiveStatus(ConnectionState.Disconnected, e.message, null)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _status.value = _status.value.copy(state = ConnectionState.Disconnected)
        radio.stop()
    }

    private fun handle(event: LiveEvent) {
        when (event) {
            is LiveEvent.Status -> _status.value = LiveStatus(event.state, event.message, event.transport ?: _status.value.transport)
            is LiveEvent.Initial -> {
                accumulator = SessionAccumulator()
                cars.clear(); positions.clear(); outlineX.clear(); outlineY.clear(); outlineDriver = null
                for ((topic, value) in event.topics) {
                    when (topic) {
                        "CarData.z" -> onCarData(value)
                        "Position.z" -> onPosition(value)
                        else -> accumulator.applyInitial(topic, value, event.at)
                    }
                }
                dirty = true
                publish()
            }
            is LiveEvent.Feed -> {
                when (event.topic) {
                    "CarData.z" -> onCarData(event.data)
                    "Position.z" -> onPosition(event.data)
                    else -> { accumulator.apply(event.topic, event.data, event.at); dirty = true }
                }
            }
        }
    }

    private fun onCarData(data: JsonElement) {
        val entry = data.at("Entries").elements().lastOrNull() ?: return
        for ((n, car) in entry.at("Cars").entries()) {
            val ch = car.at("Channels")
            cars[n] = LiveCar(
                speed = ch.at("2").int() ?: 0,
                gear = ch.at("3").int() ?: 0,
                throttle = (ch.at("4").int() ?: 0).coerceIn(0, 100),
                brake = (ch.at("5").int() ?: 0) > 0,
                rpm = ch.at("0").int() ?: 0,
            )
        }
        telemetryDirty = true
    }

    private fun onPosition(data: JsonElement) {
        for (sample in data.at("Position").elements()) {
            for ((n, p) in sample.at("Entries").entries()) {
                val x = p.at("X").int() ?: continue
                val y = p.at("Y").int() ?: continue
                if (x == 0 && y == 0) continue
                positions[n] = x.toFloat() to y.toFloat()
                if (outlineDriver == null) outlineDriver = n
                if (n == outlineDriver && outlineX.size < 4000) {
                    outlineX.add(x.toFloat()); outlineY.add(y.toFloat())
                }
            }
        }
        telemetryDirty = true
    }

    private fun publish() {
        if (dirty) {
            dirty = false
            _snapshot.value = accumulator.snapshot(Instant.now())
        } else if (_snapshot.value != null) {
            // Keep the session clock ticking.
            _snapshot.value = _snapshot.value?.copy(remaining = accumulator.snapshot(Instant.now()).remaining)
        }
        if (telemetryDirty) {
            telemetryDirty = false
            _telemetry.value = LiveTelemetry(HashMap(cars), HashMap(positions), outlineX.toFloatArray(), outlineY.toFloatArray())
        }
    }

    override fun onCleared() {
        stop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(onOpenSettings: () -> Unit, vm: LiveViewModel = viewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> vm.start()
                Lifecycle.Event.ON_STOP -> vm.stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.start()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            vm.stop()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        snapshot?.let { "${it.meetingName} · ${it.sessionName}".trim(' ', '·') }?.ifBlank { null } ?: "Live timing",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    ConnectionLine(status, snapshot)
                }
            },
            actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        val snap = snapshot
        if (snap == null) {
            CenteredProgress(
                when (status.state) {
                    ConnectionState.Reconnecting -> "Reconnecting… ${status.message.orEmpty()}"
                    else -> "Connecting to F1 live timing…"
                },
            )
            return@Column
        }
        val running = snap.sessionStatus in setOf("Started", "Aborted", "Inactive") && snap.timing.isNotEmpty()
        if (!running) {
            Text(
                "No session is running right now. Showing the most recent session; this page updates by itself when the next one starts.",
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SessionTabsView(
            snapshot = snap,
            isLive = true,
            radio = vm.radio,
            telemetryTab = { LiveTelemetryTab(snap, telemetry, vm.hasToken, onOpenSettings) },
        )
    }
}

@Composable
private fun ConnectionLine(status: LiveStatus, snapshot: SessionSnapshot?) {
    val (color, label) = when (status.state) {
        ConnectionState.Connected -> F1Colors.Green to "Connected"
        ConnectionState.Connecting -> F1Colors.Yellow to "Connecting"
        ConnectionState.Reconnecting -> F1Colors.Yellow to "Reconnecting"
        ConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant to "Offline"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        val updated = snapshot?.updatedAt?.let { " · updated ${TimeFormat.localTime(it, seconds = true)}" }.orEmpty()
        Text("$label$updated", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
