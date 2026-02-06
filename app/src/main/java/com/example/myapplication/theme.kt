package com.example.myapplication

import android.content.Context
import android.content.res.Configuration

object ThemePreferences {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_DARK_MODE = "is_dark_mode"
    private const val KEY_IS_FIRST_RUN = "is_first_run"

    fun isDarkMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Cek apakah ini pertama kali aplikasi dijalankan
        val isFirstRun = prefs.getBoolean(KEY_IS_FIRST_RUN, true)

        return if (isFirstRun) {
            // Jika pertama kali, ikuti setting sistem HP
            val systemMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val isSystemDark = systemMode == Configuration.UI_MODE_NIGHT_YES

            // Simpan agar selanjutnya tidak dianggap first run lagi
            saveDarkMode(context, isSystemDark)
            isSystemDark
        } else {
            // Jika sudah pernah diubah atau dibuka, ambil dari data yang tersimpan
            prefs.getBoolean(KEY_DARK_MODE, false)
        }
    }

    fun saveDarkMode(context: Context, isDark: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_DARK_MODE, isDark)
            putBoolean(KEY_IS_FIRST_RUN, false)
            apply()
        }
    }
}