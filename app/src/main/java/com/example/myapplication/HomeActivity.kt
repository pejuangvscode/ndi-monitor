package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.example.myapplication.ui.theme.MyApplicationTheme

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // Mengambil state awal dari Preferences (Sistem jika baru, atau Pilihan Terakhir)
            var isDarkMode by remember {
                mutableStateOf(ThemePreferences.isDarkMode(this@HomeActivity))
            }

            MyApplicationTheme(darkTheme = isDarkMode) {
                HomeScreen(
                    isDarkMode = isDarkMode,
                    onToggleTheme = {
                        val newMode = !isDarkMode
                        isDarkMode = newMode
                        // Simpan pilihan ke SharedPreferences secara permanen
                        ThemePreferences.saveDarkMode(this@HomeActivity, newMode)
                    },
                    onStartMonitor = {
                        // Navigasi atau logika start monitor tetap sama
                    }
                )
            }
        }
    }
}