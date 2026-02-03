package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.myapplication.ui.theme.MyApplicationTheme

class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Fullscreen mode
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            // Load dark mode dari preferences (auto-detect sistem atau user preference)
            var isDarkMode by remember { mutableStateOf(ThemePreferences.isDarkMode(this)) }

            MyApplicationTheme {
                HomeScreen(
                    isDarkMode = isDarkMode,
                    onToggleTheme = {
                        isDarkMode = !isDarkMode
                        // Simpan preferensi user
                        ThemePreferences.saveDarkMode(this, isDarkMode)
                    },
                    onStartMonitor = {
                        // Buka MainActivity saat tombol diklik
                        val intent = Intent(this@HomeActivity, MainActivity::class.java)
                        startActivity(intent)
                    }
                )
            }
        }
    }
}