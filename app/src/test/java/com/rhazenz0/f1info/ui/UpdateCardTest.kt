package com.rhazenz0.f1info.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhazenz0.f1info.data.update.AppUpdates
import com.rhazenz0.f1info.data.update.UpdateState
import com.rhazenz0.f1info.ui.components.UpdateCard
import com.rhazenz0.f1info.ui.theme.F1Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class UpdateCardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val release = AppUpdates.parseRelease(
        javaClass.classLoader!!.getResource("github_latest_release.json")!!.readText(),
    )!!.copy(version = "1.2.0", tag = "v1.2.0")

    @Test
    fun offersUpdateAndHidesWhenDismissed() {
        var requested: String? = null
        compose.setContent {
            F1Theme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(12.dp)) {
                        UpdateCard(UpdateState.Available(release), "1.1.0", null, { requested = it.version }, {}, {})
                        UpdateCard(UpdateState.Downloading(release, 0.42f), "1.1.0", null, {}, {}, {})
                        // Dismissed versions are hidden on Home.
                        UpdateCard(UpdateState.Available(release), "1.1.0", "1.2.0", {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("Downloading… 42%").assertIsDisplayed()
        compose.onNodeWithText("Update").performClick()
        assertEquals("1.2.0", requested)
        System.getenv("SCREENSHOT_DIR")?.let { dir ->
            val view = compose.activity.window.decorView
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bmp))
            File(dir).mkdirs()
            File(dir, "09_update.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
