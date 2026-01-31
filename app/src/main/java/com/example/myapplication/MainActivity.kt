package com.example.myapplication

import android.content.pm.ActivityInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.myapplication.ui.theme.MyApplicationTheme

data class NDIDevice(
    val deviceName: String,
    val sources: List<String>
)

class MainActivity : ComponentActivity() {

    init {
        System.loadLibrary("native-lib")
    }

    external fun startNDIReceiver(surface: Any)
    external fun getNDIDevicesAndSources(): Array<String>
    external fun connectToNDISource(sourceFullName: String, surface: Any): Boolean
    external fun stopNDIReceiver()

    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        enableEdgeToEdge()

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Get dark mode state from intent
        val initialDarkMode = intent.getBooleanExtra("isDarkMode", false)

        setContent {
            var isDarkMode by remember { mutableStateOf(initialDarkMode) }
            MyApplicationTheme {
                NDIMonitorScreen(
                    isDarkMode = isDarkMode,
                    onToggleTheme = { isDarkMode = !isDarkMode },
                    onBack = { finish() },
                    onGetDevices = { getDevicesAndSources() },
                    onConnectToSource = { sourceName, surface ->
                        connectToNDISource(sourceName, surface)
                    }
                )
            }
        }
    }

    private fun getDevicesAndSources(): List<NDIDevice> {
        try {
            val devicesData = getNDIDevicesAndSources()
            Log.d("MainActivity", "Got ${devicesData.size} devices from NDI")

            return devicesData.map { deviceData ->
                val parts = deviceData.split("|||")
                if (parts.isEmpty()) {
                    Log.w("MainActivity", "Empty device data")
                    return@map NDIDevice("Unknown", emptyList())
                }

                val deviceName = parts[0]
                val sources = parts.drop(1) // All parts after device name are sources

                Log.d("MainActivity", "Device: $deviceName with ${sources.size} sources")
                sources.forEachIndexed { index, source ->
                    Log.d("MainActivity", "  Source $index: $source")
                }

                NDIDevice(deviceName, sources)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting NDI devices", e)
            return emptyList()
        }
    }

    fun acquireMulticastLock(wifi: WifiManager) {
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock("ndi_lock").apply { setReferenceCounted(true) }
        }
        multicastLock?.let { if (!it.isHeld) it.acquire() }
    }

    fun releaseMulticastLock() {
        try {
            multicastLock?.let { lock -> if (lock.isHeld) lock.release() }
        } catch (e: Exception) {
            Log.w("MainActivity", "Gagal melepas multicast lock", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNDIReceiver()
        releaseMulticastLock()
    }
}