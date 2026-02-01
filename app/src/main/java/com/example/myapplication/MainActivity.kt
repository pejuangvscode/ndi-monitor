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
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    external fun connectToNDISource(sourceFullName: String, surface: Any, quality: Int): Boolean
    external fun stopNDIReceiver()
    external fun initializeNDIFinder(): Boolean
    external fun startContinuousDiscovery()

    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        enableEdgeToEdge()

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // PRE-INITIALIZE NDI for instant detection
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("MainActivity", "Pre-initializing NDI finder...")
                val initialized = initializeNDIFinder()
                Log.d("MainActivity", "NDI Finder pre-initialized: $initialized")

                if (initialized) {
                    // Start continuous background discovery
                    startContinuousDiscovery()
                    Log.d("MainActivity", "Continuous discovery started")

                    // Pre-scan for devices
                    delay(200) // Small delay to let discovery start
                    val devices = getDevicesAndSources()
                    Log.d("MainActivity", "Pre-scan found ${devices.size} devices")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to pre-initialize NDI", e)
            }
        }

        val initialDarkMode = intent.getBooleanExtra("isDarkMode", false)

        setContent {
            var isDarkMode by remember { mutableStateOf(initialDarkMode) }
            MyApplicationTheme {
                NDIMonitorScreen(
                    isDarkMode = isDarkMode,
                    onToggleTheme = { isDarkMode = !isDarkMode },
                    onBack = { finish() },
                    onGetDevices = { getDevicesAndSources() },
                    onConnectToSource = { sourceName, surface, quality ->
                        connectToNDISource(sourceName, surface, quality)
                    }
                )
            }
        }
    }

    private fun getDevicesAndSources(): List<NDIDevice> {
        try {
            val devicesData = getNDIDevicesAndSources()

            return devicesData.map { deviceData ->
                val parts = deviceData.split("|||")
                if (parts.isEmpty()) {
                    return@map NDIDevice("Unknown", emptyList())
                }

                val deviceName = parts[0]
                val sources = parts.drop(1)

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
            Log.w("MainActivity", "Failed to release multicast lock", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNDIReceiver()
        releaseMulticastLock()
    }
}