package com.rhazenz0.f1info.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rhazenz0.f1info.data.timing.SessionRef
import com.rhazenz0.f1info.ui.home.HomeScreen
import com.rhazenz0.f1info.ui.live.LiveScreen
import com.rhazenz0.f1info.ui.schedule.RaceDetailScreen
import com.rhazenz0.f1info.ui.schedule.ScheduleScreen
import com.rhazenz0.f1info.ui.session.SessionBrowserScreen
import com.rhazenz0.f1info.ui.session.SessionDetailScreen
import com.rhazenz0.f1info.ui.settings.SettingsScreen
import com.rhazenz0.f1info.ui.standings.StandingsScreen
import com.rhazenz0.f1info.ui.theme.F1Colors

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("schedule", "Schedule", Icons.Filled.CalendarMonth),
    Tab("standings", "Standings", Icons.Filled.EmojiEvents),
    Tab("sessions", "Sessions", Icons.Filled.Timer),
    Tab("live", "Live", Icons.Filled.Sensors),
)

object Routes {
    fun race(season: Int, round: Int) = "race/$season/$round"
    fun session(ref: SessionRef) = "session/${ref.year}?path=${Uri.encode(ref.path)}"
    const val SETTINGS = "settings"
}

@Composable
fun F1InfoNavHost() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = { nav.navigateToTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = F1Colors.Red.copy(alpha = 0.25f)),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") {
                HomeScreen(
                    onOpenRace = { s, r -> nav.navigate(Routes.race(s, r)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenLive = { nav.navigateToTab("live") },
                )
            }
            composable("schedule") {
                ScheduleScreen(onOpenRace = { s, r -> nav.navigate(Routes.race(s, r)) })
            }
            composable(
                "race/{season}/{round}",
                arguments = listOf(navArgument("season") { type = NavType.IntType }, navArgument("round") { type = NavType.IntType }),
            ) { entry ->
                RaceDetailScreen(
                    season = entry.arguments?.getInt("season") ?: 0,
                    round = entry.arguments?.getInt("round") ?: 0,
                    onBack = { nav.popBackStack() },
                    onOpenSession = { nav.navigate(Routes.session(it)) },
                )
            }
            composable("standings") { StandingsScreen() }
            composable("sessions") {
                SessionBrowserScreen(onOpenSession = { nav.navigate(Routes.session(it)) })
            }
            composable(
                "session/{year}?path={path}",
                arguments = listOf(
                    navArgument("year") { type = NavType.IntType },
                    navArgument("path") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                SessionDetailScreen(
                    year = entry.arguments?.getInt("year") ?: 0,
                    path = entry.arguments?.getString("path").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable("live") { LiveScreen(onOpenSettings = { nav.navigate(Routes.SETTINGS) }) }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
