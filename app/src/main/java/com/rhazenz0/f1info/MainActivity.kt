package com.rhazenz0.f1info

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rhazenz0.f1info.ui.F1InfoNavHost
import com.rhazenz0.f1info.ui.theme.F1Theme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        com.rhazenz0.f1info.data.update.UpdateManager.checkIfDue()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Check for an update every time the app is launched (not on rotation or other recreations).
        if (savedInstanceState == null) com.rhazenz0.f1info.data.update.UpdateManager.onLaunch()
        setContent {
            F1Theme { F1InfoNavHost() }
        }
    }
}
