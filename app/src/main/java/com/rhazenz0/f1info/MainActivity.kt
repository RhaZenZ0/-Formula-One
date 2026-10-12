package com.rhazenz0.f1info

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rhazenz0.f1info.ui.F1InfoNavHost
import com.rhazenz0.f1info.ui.theme.F1Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            F1Theme { F1InfoNavHost() }
        }
    }
}
