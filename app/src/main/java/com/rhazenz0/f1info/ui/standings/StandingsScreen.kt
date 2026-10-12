package com.rhazenz0.f1info.ui.standings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.rhazenz0.f1info.data.jolpica.StandingsList
import com.rhazenz0.f1info.ui.components.EmptyState
import com.rhazenz0.f1info.ui.components.Loadable
import com.rhazenz0.f1info.ui.components.LoadableContent
import com.rhazenz0.f1info.ui.components.Selector
import com.rhazenz0.f1info.ui.components.userMessage
import com.rhazenz0.f1info.ui.theme.F1Colors
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Year

data class Standings(val drivers: StandingsList?, val constructors: StandingsList?)

class StandingsViewModel : ViewModel() {
    val seasons: List<Int> = (Year.now().value downTo 1950).toList()
    private val _season = MutableStateFlow(Year.now().value)
    val season: StateFlow<Int> = _season
    private val _state = MutableStateFlow<Loadable<Standings>>(Loadable.Loading)
    val state: StateFlow<Loadable<Standings>> = _state

    init { load() }

    fun select(year: Int) { _season.value = year; load() }

    fun load() {
        _state.value = Loadable.Loading
        viewModelScope.launch {
            try {
                val s = _season.value.toString()
                val d = async { AppGraph.jolpica.driverStandings(s) }
                // Constructors' championship started in 1958.
                val c = async { if (_season.value >= 1958) AppGraph.jolpica.constructorStandings(s) else null }
                _state.value = Loadable.Ready(Standings(d.await(), c.await()))
            } catch (e: Exception) {
                _state.value = Loadable.Error(e.userMessage())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandingsScreen(vm: StandingsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val season by vm.season.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Standings") },
            actions = { Selector("Season", vm.seasons, season, { it.toString() }, vm::select, Modifier.padding(end = 8.dp)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Drivers") }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Constructors") }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LoadableContent(state, onRetry = vm::load) { s ->
            if (tab == 0) DriverTable(s.drivers) else ConstructorTable(s.constructors)
        }
    }
}

@Composable
private fun PointsBar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(F1Colors.Red, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun DriverTable(list: StandingsList?) {
    val rows = list?.drivers.orEmpty()
    if (rows.isEmpty()) { EmptyState("No standings for this season yet."); return }
    val leader = rows.first().points.toFloatOrNull() ?: 1f
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        item {
            Text("After round ${list?.round ?: "-"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
        }
        itemsIndexed(rows) { _, s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.positionText, Modifier.width(34.dp), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.driver.fullName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        s.driver.permanentNumber?.let {
                            Spacer(Modifier.width(6.dp))
                            Text("#$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "${s.constructors.joinToString(" / ") { it.name }} · ${s.driver.nationality}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    PointsBar((s.points.toFloatOrNull() ?: 0f) / leader)
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(s.points, fontWeight = FontWeight.Black)
                    Text("${s.wins} wins", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun ConstructorTable(list: StandingsList?) {
    val rows = list?.constructors.orEmpty()
    if (rows.isEmpty()) { EmptyState("No constructors' standings for this season."); return }
    val leader = rows.first().points.toFloatOrNull() ?: 1f
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        itemsIndexed(rows) { _, s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.positionText, Modifier.width(34.dp), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f)) {
                    Text(s.constructor.name, fontWeight = FontWeight.Bold)
                    Text(s.constructor.nationality, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    PointsBar((s.points.toFloatOrNull() ?: 0f) / leader)
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(s.points, fontWeight = FontWeight.Black)
                    Text("${s.wins} wins", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        }
    }
}
