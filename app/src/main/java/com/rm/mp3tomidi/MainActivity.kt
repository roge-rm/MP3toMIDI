package com.rm.mp3tomidi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rm.mp3tomidi.ui.AppScreen
import com.rm.mp3tomidi.ui.MainScreen
import com.rm.mp3tomidi.ui.MainViewModel
import com.rm.mp3tomidi.ui.PlayScreen
import com.rm.mp3tomidi.ui.theme.MP3toMIDITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The header (see AppHeader) is a gradient that's meant to run edge-to-edge behind the
        // status bar, not sit below a system-colored bar -- see enableEdgeToEdge's default
        // light/dark status bar icon detection getting overridden in each screen for why that
        // still works once the header scrolls away.
        enableEdgeToEdge()
        setContent {
            MP3toMIDITheme {
                val viewModel: MainViewModel = viewModel()
                var screen by rememberSaveable { mutableStateOf(AppScreen.CONVERT) }
                when (screen) {
                    AppScreen.CONVERT -> MainScreen(viewModel, onSwitchScreen = { screen = AppScreen.PLAY })
                    AppScreen.PLAY -> PlayScreen(viewModel, onSwitchScreen = { screen = AppScreen.CONVERT })
                }
            }
        }
    }
}
