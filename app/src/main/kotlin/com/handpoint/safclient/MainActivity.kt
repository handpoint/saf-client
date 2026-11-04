package com.handpoint.safclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.handpoint.safclient.ui.navigation.SafNavHost
import com.handpoint.safclient.ui.theme.SafTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SafTheme { SafNavHost() }
        }
    }

    // The payment SDK can hide the system bars on the terminal; show Back and Home again.
    override fun onResume() {
        super.onResume()
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }
}
