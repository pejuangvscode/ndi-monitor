package com.example.myapplication

import android.app.Application
import android.content.Context
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
import kotlinx.coroutines.*

data class NDIDevice(
    val deviceName: String,
    val sources: List<String>,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * ULTRA-FAST NDI MANAGER
 * - Pre-warming scan untuk instant detection
 * - Persistent cache untuk instant reopen
 * - Adaptive scanning untuk optimal performance
 */
object NDIManager {
    private const val TAG = "NDIManager"
    private const val PREFS_NAME = "ndi_manager_prefs"
    private const val KEY_LAST_DEVICES = "last_devices"

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isWarmedUp = false

    private var multicastLock: WifiManager.MulticastLock? = null
    private var lastKnownDevices: List<NDIDevice> = emptyList()

    external fun initializeNDIFinder(): Boolean
    external fun startContinuousDiscovery()
    external fun getNDIDevicesAndSources(): Array<String>
    external fun stopContinuousDiscovery()
    external fun cleanupNDIFinder()
    external fun forceImmediateScan(): Int

    init {
        try {
            System.loadLibrary("native-lib")
            Log.d(TAG, "Native library loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load native library", e)
        }
    }

    fun initialize(application: Application) {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            if (!isWarmedUp) {
                CoroutineScope(Dispatchers.IO).launch { performWarmup() }
            }
            return
        }

        try {
            Log.d(TAG, "=== ULTRA-FAST INIT ===")

            // Restore cache (instant)
            restoreLastKnownDevices(application)

            // Acquire multicast
            val wifi = application.getSystemService(Application.WIFI_SERVICE) as WifiManager
            acquireMulticastLock(wifi)

            // Init finder
            if (!initializeNDIFinder()) {
                Log.e(TAG, "Init failed")
                return
            }

            // Start discovery
            startContinuousDiscovery()
            isInitialized = true

            Log.d(TAG, "✓ Initialized")

            // Warm-up di background
            CoroutineScope(Dispatchers.IO).launch {
                performWarmup()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Init error", e)
        }
    }

    /**
     * WARM-UP: 10 aggressive scans dalam 200ms
     */
    private suspend fun performWarmup() {
        if (isWarmedUp) return

        Log.d(TAG, "=== WARM-UP START ===")
        delay(50)

        repeat(10) { i ->
            val count = forceImmediateScan()
            Log.d(TAG, "Scan #${i+1}: $count sources")
            if (count > 0) {
                isWarmedUp = true
                Log.d(TAG, "✓ SOURCES FOUND EARLY!")
                return
            }
            delay(20) // Ultra-aggressive
        }

        isWarmedUp = true
        Log.d(TAG, "✓ Warm-up done")
    }

    fun getDevices(): List<NDIDevice> {
        if (!isInitialized) {
            return lastKnownDevices
        }

        try {
            val devicesData = getNDIDevicesAndSources()
            val currentTime = System.currentTimeMillis()

            val devices = devicesData.mapNotNull { deviceData ->
                try {
                    val parts = deviceData.split("|||")
                    if (parts.isEmpty()) return@mapNotNull null

                    val deviceName = parts[0]
                    val sources = parts.drop(1)

                    if (deviceName.isBlank() || sources.isEmpty()) {
                        return@mapNotNull null
                    }

                    NDIDevice(
                        deviceName = deviceName,
                        sources = sources,
                        lastSeen = currentTime
                    )
                } catch (e: Exception) {
                    null
                }
            }

            if (devices.isNotEmpty()) {
                lastKnownDevices = devices
            }

            return devices

        } catch (e: Exception) {
            Log.e(TAG, "Get error, using cache", e)
            return lastKnownDevices
        }
    }

    fun saveLastKnownDevices(context: Context, devices: List<NDIDevice>) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val deviceStrings = devices.map { device ->
                "${device.deviceName}|||${device.sources.joinToString("|||")}"
            }
            prefs.edit()
                .putStringSet(KEY_LAST_DEVICES, deviceStrings.toSet())
                .apply()
            Log.d(TAG, "Saved ${devices.size} devices")
        } catch (e: Exception) {
            Log.e(TAG, "Save error", e)
        }
    }

    private fun restoreLastKnownDevices(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val deviceStrings = prefs.getStringSet(KEY_LAST_DEVICES, emptySet()) ?: emptySet()

            lastKnownDevices = deviceStrings.mapNotNull { deviceString ->
                try {
                    val parts = deviceString.split("|||")
                    if (parts.size < 2) return@mapNotNull null

                    NDIDevice(
                        deviceName = parts[0],
                        sources = parts.drop(1),
                        lastSeen = System.currentTimeMillis()
                    )
                } catch (e: Exception) {
                    null
                }
            }

            if (lastKnownDevices.isNotEmpty()) {
                Log.d(TAG, "✓ Restored ${lastKnownDevices.size} from cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Restore error", e)
        }
    }

    fun acquireMulticastLock(wifi: WifiManager) {
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock("ndi_global_lock").apply {
                setReferenceCounted(true)
            }
        }
        multicastLock?.let {
            if (!it.isHeld) {
                it.acquire()
                Log.d(TAG, "Multicast acquired")
            }
        }
    }

    fun releaseMulticastLock() {
        try {
            multicastLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Log.d(TAG, "Multicast released")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Release error", e)
        }
    }

    /**
     * Resume - re-warm-up jika diperlukan
     */
    fun resume() {
        Log.d(TAG, "Resume - quick scan")
        if (isInitialized && !isWarmedUp) {
            CoroutineScope(Dispatchers.IO).launch {
                performWarmup()
            }
        }
    }

    fun shutdown() {
        if (!isInitialized) return

        try {
            Log.d(TAG, "Shutdown...")
            stopContinuousDiscovery()
            cleanupNDIFinder()
            releaseMulticastLock()
            isInitialized = false
            isWarmedUp = false
            Log.d(TAG, "Shutdown done")
        } catch (e: Exception) {
            Log.e(TAG, "Shutdown error", e)
        }
    }
}

/**
 * Application class - initialize NDI saat app start
 */
class NDIApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        Log.d("NDIApp", "=== APP START ===")

        // Init ASAP untuk instant detection
        NDIManager.initialize(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        NDIManager.shutdown()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Save cache saat low memory
        val devices = NDIManager.getDevices()
        if (devices.isNotEmpty()) {
            NDIManager.saveLastKnownDevices(this, devices)
        }
    }
}

class MainActivity : ComponentActivity() {

    external fun connectToNDISource(sourceFullName: String, surface: Any, quality: Int): Boolean
    external fun stopNDIReceiver()

    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        enableEdgeToEdge()

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            var isDarkMode by remember { mutableStateOf(ThemePreferences.isDarkMode(this)) }

            MyApplicationTheme {
                NDIMonitorScreen(
                    isDarkMode = isDarkMode,
                    onToggleTheme = {
                        isDarkMode = !isDarkMode
                        ThemePreferences.saveDarkMode(this, isDarkMode)
                    },
                    onBack = {
                        // Save cache before exit
                        val devices = NDIManager.getDevices()
                        if (devices.isNotEmpty()) {
                            NDIManager.saveLastKnownDevices(this, devices)
                        }
                        finish()
                    },
                    onGetDevices = {
                        NDIManager.getDevices()
                    },
                    onConnectToSource = { sourceName, surface, quality ->
                        connectToNDISource(sourceName, surface, quality)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Quick scan saat activity resume
        NDIManager.resume()
    }

    override fun onPause() {
        super.onPause()
        // Save cache saat pause
        val devices = NDIManager.getDevices()
        if (devices.isNotEmpty()) {
            NDIManager.saveLastKnownDevices(this, devices)
        }
    }

    fun acquireMulticastLock(wifi: WifiManager) {
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock("ndi_lock").apply {
                setReferenceCounted(true)
            }
        }
        multicastLock?.let {
            if (!it.isHeld) it.acquire()
        }
    }

    fun releaseMulticastLock() {
        try {
            multicastLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Release error", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNDIReceiver()
        releaseMulticastLock()
    }
}