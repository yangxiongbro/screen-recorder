package com.screenrecorder.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.screenrecorder.app.ui.RecorderApp
import com.screenrecorder.app.ui.ScreenRecorderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScreenRecorderTheme {
                RecorderApp()
            }
        }
    }
}
