package com.example.myapplication

import android.content.Context
import android.content.res.Configuration

object ThemePreferences {
    private const val PREFS_NAME = "ndi_tools_prefs"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_USER_OVERRIDE = "user_override"

    /**
     * Mendapatkan status dark mode
     * Logika:
     * 1. Cek apakah user pernah override manual
     * 2. Jika pernah, gunakan preferensi user
     * 3. Jika belum, ikuti sistem
     */
    fun isDarkMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userOverride = prefs.getBoolean(KEY_USER_OVERRIDE, false)

        return if (userOverride) {
            // User pernah set manual, gunakan preferensi user
            prefs.getBoolean(KEY_DARK_MODE, false)
        } else {
            // Belum pernah set manual, ikuti sistem
            isSystemDarkMode(context)
        }
    }

    /**
     * Menyimpan preferensi dark mode dari user
     */
    fun saveDarkMode(context: Context, isDarkMode: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_DARK_MODE, isDarkMode)
            putBoolean(KEY_USER_OVERRIDE, true) // Tandai bahwa user sudah override
            apply()
        }
    }

    /**
     * Cek apakah sistem dalam dark mode
     */
    fun isSystemDarkMode(context: Context): Boolean {
        val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Reset ke default (ikuti sistem)
     */
    fun resetToSystem(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_USER_OVERRIDE, false)
            apply()
        }
    }
}