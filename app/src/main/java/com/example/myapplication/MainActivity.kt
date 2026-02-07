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
 * STUDIO MONITOR STYLE NDI MANAGER
 *
 * Mengapa Studio Monitor lebih cepat? Karena lebih "berisik" di network!
 *
 * COLD START (App ditutup dan dibuka lagi):
 * ✅ INSTANT MEGA BURST: 10x native findNDISources() dalam 500ms pertama
 *    - Setiap call = 3-phase discovery (immediate + quick burst + patient scan)
 *    - Total: Ultra-aggressive network discovery
 * ✅ EARLY CHECK: Cek sources setiap 50ms untuk instant detection
 * ✅ AGGRESSIVE SCAN: Fallback 25x scan dalam 500ms berikutnya
 * ✅ PERIODIC STIMULUS: Final 5x scan dalam 500ms terakhir
 *
 * Target: Detection < 2 detik (biasanya < 500ms di mega burst)
 *
 * HOT RESUME (App dari background):
 * ✅ Quick scan burst (3x native findNDISources())
 * ✅ Fast scanning
 *
 * Saat Studio Monitor dibuka:
 * ✅ Membuat NDI finder aktif (native)
 * ✅ Melakukan aggressive wait_for_sources(0) - network stimulus
 * ✅ Mengirim burst discovery packets ke network
 * ✅ Memicu NDI sources untuk:
 *    • Re-announce presence
 *    • Refresh metadata
 *    • Re-broadcast state
 *
 * Implementasi:
 * - Persistent cache untuk instant UI response
 * - Native findNDISources() dengan 3-phase discovery
 * - Super network stimulus saat cold start
 * - Normal stimulus saat resume
 * - Auto-stimulus saat resume
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

    // Native function - menggunakan findNDISources dari native-lib.cpp
    external fun findNDISources(): Array<String>

    // Native cleanup function
    external fun cleanup()

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
            Log.d(TAG, "Already initialized - re-triggering stimulus")
            // Re-warm up dengan aggressive stimulus
            CoroutineScope(Dispatchers.IO).launch {
                performImmediateSuperStimulus()
            }
            return
        }

        try {
            Log.d(TAG, "=== STUDIO MONITOR COLD START ===")

            // Restore cache (instant untuk UI)
            restoreLastKnownDevices(application)

            // Acquire multicast IMMEDIATELY
            val wifi = application.getSystemService(Application.WIFI_SERVICE) as WifiManager
            acquireMulticastLock(wifi)

            isInitialized = true

            Log.d(TAG, "✓ Initialized - starting SUPER STIMULUS")

            // IMMEDIATE SUPER AGGRESSIVE STIMULUS
            // Seperti Studio Monitor: LANGSUNG berisik di network
            CoroutineScope(Dispatchers.IO).launch {
                performImmediateSuperStimulus()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Init error", e)
        }
    }

    /**
     * IMMEDIATE SUPER AGGRESSIVE STIMULUS
     * Seperti Studio Monitor saat PERTAMA dibuka atau setelah ditutup
     *
     * Target: Detection < 2 detik (biasanya < 500ms)
     *
     * Strategy:
     * 1. INSTANT MEGA BURST: 10x native findNDISources() dalam 500ms pertama
     * 2. CHECK EARLY: Cek setiap burst untuk early exit
     * 3. CONTINUOUS PROBE: Jika belum ketemu, lanjut aggressive scan
     */
    private suspend fun performImmediateSuperStimulus() {
        Log.d(TAG, "🚀 === SUPER STIMULUS START (Cold Start) ===")

        // NO DELAY - langsung berisik!
        var found = false

        // PHASE 0: INSTANT MEGA BURST (500ms)
        // Seperti Studio Monitor: LANGSUNG banjir network dengan packets
        Log.d(TAG, "⚡⚡⚡ INSTANT MEGA BURST - 10x native findNDISources()")
        repeat(10) { round ->
            // Native findNDISources() = 3-phase discovery
            val sources = withContext(Dispatchers.IO) {
                try {
                    findNDISources()
                } catch (e: Exception) {
                    Log.e(TAG, "findNDISources error", e)
                    emptyArray()
                }
            }

            val count = sources.size
            Log.d(TAG, "Super burst ${round+1}/10: $count sources")

            if (count > 0) {
                found = true
                isWarmedUp = true
                Log.d(TAG, "🎯 FOUND in mega burst round ${round+1}!")
                return
            }

            delay(50)
        }

        if (!found) {
            Log.d(TAG, "⚡ Mega burst done, continuing aggressive scan...")

            // PHASE 1: Aggressive Scanning (500ms)
            repeat(25) { i ->
                val sources = withContext(Dispatchers.IO) {
                    try {
                        findNDISources()
                    } catch (e: Exception) {
                        emptyArray()
                    }
                }

                val count = sources.size
                Log.d(TAG, "Aggressive scan #${i+1}: $count sources")

                if (count > 0) {
                    found = true
                    isWarmedUp = true
                    Log.d(TAG, "✓ FOUND in aggressive scan!")
                    return
                }
                delay(20)
            }
        }

        if (!found) {
            Log.d(TAG, "⚡ Continuing with periodic stimulus...")

            // PHASE 2: Periodic Stimulus (500ms)
            repeat(5) { i ->
                val sources = withContext(Dispatchers.IO) {
                    try {
                        findNDISources()
                    } catch (e: Exception) {
                        emptyArray()
                    }
                }

                val count = sources.size
                Log.d(TAG, "Periodic stimulus #${i+1}: $count sources")

                if (count > 0) {
                    found = true
                    isWarmedUp = true
                    Log.d(TAG, "✓ FOUND in periodic stimulus!")
                    return
                }
                delay(100)
            }
        }

        isWarmedUp = true
        if (found) {
            Log.d(TAG, "✅ SUPER STIMULUS SUCCESS")
        } else {
            Log.d(TAG, "⚠️ Super stimulus complete (~1.5s) - no sources found yet (will continue monitoring)")
        }
    }

    /**
     * STUDIO MONITOR STYLE WARM-UP
     * Digunakan untuk re-warm setelah pause/resume
     */
    private suspend fun performWarmup() {
        if (isWarmedUp) return

        Log.d(TAG, "=== WARM-UP (Resume Mode) ===")
        delay(50)

        // Quick burst untuk resume
        repeat(3) { i ->
            val sources = withContext(Dispatchers.IO) {
                try {
                    findNDISources()
                } catch (e: Exception) {
                    emptyArray()
                }
            }

            val count = sources.size
            Log.d(TAG, "Resume stimulus #${i+1}: $count sources")

            if (count > 0) {
                isWarmedUp = true
                Log.d(TAG, "✓ SOURCES FOUND on resume!")
                return
            }

            delay(100)
        }

        // Fallback aggressive scan
        repeat(10) { i ->
            val sources = withContext(Dispatchers.IO) {
                try {
                    findNDISources()
                } catch (e: Exception) {
                    emptyArray()
                }
            }

            if (sources.isNotEmpty()) {
                isWarmedUp = true
                return
            }
            delay(20)
        }

        isWarmedUp = true
    }

    fun getDevices(): List<NDIDevice> {
        if (!isInitialized) {
            return lastKnownDevices
        }

        return runBlocking(Dispatchers.IO) {
            try {
                val currentTime = System.currentTimeMillis()

                // Call native findNDISources()
                val sourcesArray = try {
                    findNDISources()
                } catch (e: Exception) {
                    Log.e(TAG, "findNDISources error", e)
                    emptyArray()
                }

                // Group sources by device name
                val deviceMap = mutableMapOf<String, MutableList<String>>()

                sourcesArray.forEach { fullSource ->
                    // Format: "MACHINE-NAME (Source Name)"
                    val deviceName = if (fullSource.contains("(")) {
                        fullSource.substringBefore("(").trim()
                    } else {
                        fullSource.trim()
                    }

                    if (!deviceMap.containsKey(deviceName)) {
                        deviceMap[deviceName] = mutableListOf()
                    }
                    deviceMap[deviceName]?.add(fullSource)
                }

                // Convert to NDIDevice list
                val devices = deviceMap.mapNotNull { (deviceName, sources) ->
                    try {
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

                devices

            } catch (e: Exception) {
                Log.e(TAG, "Get error, using cache", e)
                lastKnownDevices
            }
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
     * Resume - trigger network stimulus untuk wake up sources
     */
    fun resume() {
        Log.d(TAG, "Resume - triggering network stimulus")
        if (isInitialized) {
            // Quick scan untuk wake up sources
            CoroutineScope(Dispatchers.IO).launch {
                delay(50)

                // Quick scan setelah resume
                if (!isWarmedUp) {
                    performWarmup()
                } else {
                    val sources = try {
                        findNDISources()
                    } catch (e: Exception) {
                        emptyArray()
                    }
                    Log.d(TAG, "Quick scan after resume: ${sources.size} sources")
                }
            }
        }
    }

    fun shutdown() {
        if (!isInitialized) return

        try {
            Log.d(TAG, "Shutdown...")

            // Call native cleanup
            try {
                cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Native cleanup error", e)
            }

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