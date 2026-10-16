package com.rhazenz0.f1info.data.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UpdateManagerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val installed = mutableListOf<String>()

    /** The real 1.1.0 release response, relabelled as a newer version. */
    private val newer = javaClass.classLoader!!.getResource("github_latest_release.json")!!.readText()
        .replace("v1.1.0", "v99.0.0")

    @Before
    fun setUp() {
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().clear().commit()
        UpdateManager.reset(context)
        UpdateManager.fetchLatest = { newer }
        UpdateManager.installOverride = { installed.add(it.version) }
    }

    @After
    fun tearDown() {
        UpdateManager.installOverride = null
    }

    private fun launch() = runBlocking {
        UpdateManager.onLaunch()
        UpdateManager.awaitCheck()
    }

    @Test
    fun installsAutomaticallyOnLaunch() {
        launch()
        assertEquals(listOf("99.0.0"), installed)
        assertNull(UpdateManager.launchPrompt.value)
    }

    @Test
    fun asksWhenAutomaticInstallIsOff() {
        UpdateManager.autoInstall = false
        launch()
        assertTrue(installed.isEmpty())
        assertEquals("99.0.0", UpdateManager.launchPrompt.value?.version)
    }

    @Test
    fun leavesSkippedVersionAlone() {
        UpdateManager.dismiss(AppUpdates.parseRelease(newer)!!)
        launch()
        assertTrue(installed.isEmpty())
        assertNull(UpdateManager.launchPrompt.value)
        assertTrue(UpdateManager.state.value is UpdateState.Available)
    }

    @Test
    fun asksInsteadOfRetryingAFailedInstall() {
        // An earlier automatic install of this version failed or was cancelled.
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .putString("update_no_auto_retry_version", "99.0.0").commit()
        UpdateManager.reset(context)
        launch()
        assertTrue(installed.isEmpty())
        assertEquals("99.0.0", UpdateManager.launchPrompt.value?.version)
    }

    @Test
    fun failedInstallIsNotRetriedAutomatically() {
        val release = AppUpdates.parseRelease(newer)!!
        // Android's installer reports a failure for an install in progress.
        UpdateManager.setStateForTest(UpdateState.Installing(release))
        UpdateManager.onInstallFailed("blocked")
        assertTrue(UpdateManager.state.value is UpdateState.Failed)
        // The next launch asks instead of downloading and failing again.
        launch()
        assertTrue(installed.isEmpty())
        assertEquals("99.0.0", UpdateManager.launchPrompt.value?.version)
    }

    @Test
    fun cancellingTheAndroidPromptKeepsTheUpdateOnOffer() {
        val release = AppUpdates.parseRelease(newer)!!
        UpdateManager.setStateForTest(UpdateState.Installing(release))
        UpdateManager.onInstallFailed(null) // STATUS_FAILURE_ABORTED
        assertEquals(UpdateState.Available(release), UpdateManager.state.value)
    }

    @Test
    fun doesNothingWhenUpToDateOrChecksAreOff() {
        UpdateManager.fetchLatest = { newer.replace("v99.0.0", "v0.0.1") }
        launch()
        assertTrue(UpdateManager.state.value is UpdateState.UpToDate)
        UpdateManager.fetchLatest = { newer }
        UpdateManager.autoCheck = false
        launch()
        assertTrue(installed.isEmpty())
    }
}
