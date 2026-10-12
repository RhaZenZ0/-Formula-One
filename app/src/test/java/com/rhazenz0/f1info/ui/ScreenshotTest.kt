package com.rhazenz0.f1info.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhazenz0.f1info.data.timing.ArchiveClient
import com.rhazenz0.f1info.data.timing.LoadedSession
import com.rhazenz0.f1info.data.timing.SessionRef
import com.rhazenz0.f1info.data.timing.TelemetryData
import com.rhazenz0.f1info.ui.session.ArchiveTelemetryTab
import com.rhazenz0.f1info.ui.session.ReplayTrackTab
import com.rhazenz0.f1info.ui.session.RadioPlayer
import com.rhazenz0.f1info.ui.session.SessionTabsView
import com.rhazenz0.f1info.ui.session.TelemetryUi
import com.rhazenz0.f1info.ui.theme.F1Theme
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/**
 * Renders the session screens with real 2026 Singapore GP data and writes PNGs to
 * SCREENSHOT_DIR. Runs only when F1_FEED_DIR and SCREENSHOT_DIR are set.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val feedDir = System.getenv("F1_FEED_DIR")?.let(::File)
    private val outDir = System.getenv("SCREENSHOT_DIR")?.let(::File)

    private val ref = SessionRef(
        2026, 1296, "Singapore Grand Prix", "", "Marina Bay", "Singapore", "Singapore", 11388, "Race", "Race",
        Instant.parse("2026-10-11T12:00:00Z"), Instant.parse("2026-10-11T14:00:00Z"), "08:00:00",
        "2026/2026-10-11_Singapore_Grand_Prix/2026-10-11_Race/",
    )

    private fun archive() = ArchiveClient { url, _ -> File(feedDir, url.substringAfterLast('/')).takeIf { it.exists() }?.readText() }

    companion object {
        private var session: LoadedSession? = null
        private var telemetry: TelemetryData? = null
    }

    private fun load(): LoadedSession = session ?: runBlocking { archive().loadSession(ref) }.also { session = it }
    private fun loadTelemetry(): TelemetryData = telemetry ?: runBlocking { archive().telemetry(ref) }.also { telemetry = it }

    private fun assumeDataAvailable() =
        assumeTrue(feedDir != null && outDir != null && File(feedDir, "TimingData.jsonStream").exists())

    private fun shoot(name: String, content: @Composable () -> Unit, click: String? = null) {
        assumeDataAvailable()
        compose.setContent {
            F1Theme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
        }
        if (click != null) compose.onNodeWithText(click).performScrollTo().performClick()
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        outDir!!.mkdirs()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tabs(click: String?, name: String) {
        // Check before loading so the tests skip, rather than fail, when the archive files are absent.
        assumeDataAvailable()
        val s = load()
        shoot(name, {
            SessionTabsView(s.snapshot, isLive = false, radio = RadioPlayer(), trackTab = {
                ReplayTrackTab(
                    s.snapshot, TelemetryUi.Ready(loadTelemetry()), circuit(), onLoad = {},
                    initialTimeMs = Instant.parse("2026-10-11T12:52:00Z").toEpochMilli(),
                )
            }, telemetryTab = {
                ArchiveTelemetryTab(s.snapshot, TelemetryUi.Ready(loadTelemetry())) {}
            })
        }, click)
    }

    private fun circuit() = File(feedDir, "circuit_61.json").takeIf { it.exists() }
        ?.let { com.rhazenz0.f1info.data.timing.CircuitClient.parse(it.readText()) }

    @Test fun positions() = tabs(null, "01_positions")
    @Test fun track() = tabs("Track", "01b_track")
    @Test fun liveTrack() {
        assumeDataAvailable()
        val archive = archive()
        val streams = listOf("Heartbeat", "SessionStatus", "TrackStatus", "TimingData").associateWith {
            File(feedDir, "$it.jsonStream").takeIf { f -> f.exists() }?.readText()
        }
        val acc = com.rhazenz0.f1info.data.timing.SessionAccumulator()
        archive.replay(acc, ref, streams, until = Instant.parse("2026-10-11T12:52:00Z"))
        listOf("DriverList", "SessionInfo").forEach { t ->
            File(feedDir, "$t.json").takeIf { it.exists() }?.let { acc.setKeyframe(t, com.rhazenz0.f1info.data.timing.FeedCodec.parseJson(it.readText())) }
        }
        val snap = acc.snapshot()
        shoot("01c_live_track", {
            com.rhazenz0.f1info.ui.session.LiveTrackTab(snap, circuit(), com.rhazenz0.f1info.ui.session.LiveTelemetry(), false)
        })
    }

    @Test fun laps() = tabs("Lap times", "02_laps")
    @Test fun telemetry() = tabs("Telemetry", "03_telemetry")
    @Test fun pits() = tabs("Pit stops", "04_pits")
    @Test fun radio() = tabs("Team radio", "05_radio")
    @Test fun weather() = tabs("Weather", "06_weather")
    @Test fun raceControl() = tabs("Race control", "07_race_control")
    @Test fun championship() = tabs("Championship", "08_championship")
}
