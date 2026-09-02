package com.rm.mp3tomidi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rm.mp3tomidi.ui.MainScreen
import com.rm.mp3tomidi.ui.MainViewModel
import com.rm.mp3tomidi.ui.theme.MP3toMIDITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MP3toMIDITheme {
                val viewModel: MainViewModel = viewModel()
                MainScreen(viewModel)
            }
        }
    }
}
