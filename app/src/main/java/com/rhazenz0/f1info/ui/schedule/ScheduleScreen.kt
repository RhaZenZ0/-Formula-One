package com.rhazenz0.f1info.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rhazenz0.f1info.AppGraph
import com.rhazenz0.f1info.data.jolpica.RaceWeekend
import com.rhazenz0.f1info.ui.components.Loadable
import com.rhazenz0.f1info.ui.components.LoadableContent
import com.rhazenz0.f1info.ui.components.Pill
import com.rhazenz0.f1info.ui.components.Selector
import com.rhazenz0.f1info.ui.components.userMessage
import com.rhazenz0.f1info.ui.theme.F1Colors
import com.rhazenz0.f1info.util.TimeFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.Year

class ScheduleViewModel : ViewModel() {
    val seasons: List<Int> = (Year.now().value downTo 1950).toList()
    private val _season = MutableStateFlow(Year.now().value)
    val season: StateFlow<Int> = _season
    private val _state = MutableStateFlow<Loadable<List<RaceWeekend>>>(Loadable.Loading)
    val state: StateFlow<Loadable<List<RaceWeekend>>> = _state

    init { load() }

    fun select(year: Int) { _season.value = year; load() }

    fun load() {
        _state.value = Loadable.Loading
        viewModelScope.launch {
            _state.value = try {
                Loadable.Ready(AppGraph.jolpica.schedule(_season.value.toString()))
            } catch (e: Exception) {
                Loadable.Error(e.userMessage())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(onOpenRace: (Int, Int) -> Unit, vm: ScheduleViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val season by vm.season.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Schedule") },
            actions = { Selector("Season", vm.seasons, season, { it.toString() }, vm::select, Modifier.padding(end = 8.dp)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LoadableContent(state, onRetry = vm::load) { races ->
            val now = Instant.now()
            val listState = rememberLazyListState()
            val nextIndex = races.indexOfFirst { !it.isFinished(now) }
            LaunchedEffect(races) { if (nextIndex > 0) listState.scrollToItem((nextIndex - 1).coerceAtLeast(0)) }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(races, key = { it.round }) { race ->
                    RaceRow(race, isNext = races.getOrNull(nextIndex) == race, now) { onOpenRace(race.season, race.round) }
                }
            }
        }
    }
}

@Composable
private fun RaceRow(race: RaceWeekend, isNext: Boolean, now: Instant, onClick: () -> Unit) {
    val finished = race.isFinished(now)
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(
                    if (isNext) F1Colors.Red else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(10.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(race.round.toString(), fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    race.name, fontWeight = FontWeight.Bold,
                    color = if (finished) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text("${race.locality}, ${race.country}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${TimeFormat.localDate(race.raceStart, withYear = true)} · ${TimeFormat.localTime(race.raceStart)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                when {
                    isNext -> Pill("NEXT", F1Colors.Red, textColor = androidx.compose.ui.graphics.Color.White)
                    finished -> Pill("DONE", MaterialTheme.colorScheme.surfaceVariant, textColor = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (race.isSprintWeekend) {
                    Spacer(Modifier.size(4.dp))
                    Pill("SPRINT", F1Colors.Yellow)
                }
            }
        }
    }
}
