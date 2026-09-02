package com.rm.mp3tomidi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rm.mp3tomidi.ui.MainScreen
import com.rm.mp3tomidi.ui.MainViewModel
import com.rm.mp3tomidi.ui.theme.MP3toMIDITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The header (see AppHeader in MainScreen) is a gradient that's meant to run edge-to-edge
        // behind the status bar, not sit below a system-colored bar -- see enableEdgeToEdge's
        // default light/dark status bar icon detection getting overridden in MainScreen for why
        // that still works once the header scrolls away.
        enableEdgeToEdge()
        setContent {
            MP3toMIDITheme {
                val viewModel: MainViewModel = viewModel()
                MainScreen(viewModel)
            }
        }
    }
}
