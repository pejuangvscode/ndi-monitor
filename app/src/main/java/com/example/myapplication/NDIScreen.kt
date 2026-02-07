package com.example.myapplication

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.wifi.WifiManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*

private const val TAG = "NDIScreen"
private const val CONTROLS_HIDE_DELAY = 3000L

@Composable
fun NDIMonitorScreen(
    isDarkMode: Boolean,
    onToggleTheme: () -> Unit,
    onBack: () -> Unit,
    onGetDevices: () -> List<NDIDevice>,
    onConnectToSource: (String, Any, Int) -> Boolean
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()

    var deviceList by remember { mutableStateOf<List<NDIDevice>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    var isManualRefreshing by remember { mutableStateOf(false) }

    // State untuk tracking koneksi dan auto-reset saat device hilang
    var isConnected by remember { mutableStateOf(false) }
    var connectedSourceName by remember { mutableStateOf<String?>(null) }

    // Device cache untuk stabilitas - track terakhir terlihat
    var deviceLastSeen by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    val DEVICE_TIMEOUT = 3000L // 3 detik grace period sebelum dianggap hilang

    // UI state variables
    var showControls by remember { mutableStateOf(true) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var selectedDevice by remember { mutableStateOf<NDIDevice?>(null) }
    var currentSourceName by remember { mutableStateOf("No Source Selected") }
    var hideControlsJob by remember { mutableStateOf<Job?>(null) }
    var surfaceRef by remember { mutableStateOf<Any?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var selectedQuality by remember { mutableStateOf("720") }
    var currentResolution by remember { mutableStateOf("1280x720") }
    var isQualityChanging by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current

    // State untuk zoom dan pan
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    DisposableEffect(Unit) {
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose {
            activity?.requestedOrientation = originalOrientation
        }
    }

    // IMPROVED: Scanning sangat stabil dengan device cache dan grace period
    LaunchedEffect(Unit) {
        while(isActive) {
            val currentTime = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) { onGetDevices() }

            // Update last seen time untuk setiap device yang terdeteksi
            val updatedLastSeen = deviceLastSeen.toMutableMap()

            result.forEach { device ->
                device.sources.forEach { source ->
                    // Track setiap source dengan timestamp
                    updatedLastSeen[source] = currentTime
                }
            }

            // Bersihkan device yang sudah timeout (tidak terlihat > 3 detik)
            val validSources = updatedLastSeen.filter { (source, lastSeen) ->
                currentTime - lastSeen < DEVICE_TIMEOUT
            }

            deviceLastSeen = validSources

            // Build device list dari cache (bukan langsung dari result)
            // Ini membuat deteksi lebih stabil - device tidak langsung hilang jika sesaat tidak terdeteksi
            val stableDevices = result.mapNotNull { device ->
                val activeSources = device.sources.filter { source ->
                    validSources.containsKey(source)
                }

                if (activeSources.isNotEmpty()) {
                    NDIDevice(
                        deviceName = device.deviceName,
                        sources = activeSources,
                        lastSeen = device.lastSeen
                    )
                } else null
            }

            // CRITICAL: Cek apakah device yang sedang terkoneksi masih ada
            if (isConnected && connectedSourceName != null) {
                val connectedSourceStillActive = validSources.containsKey(connectedSourceName)

                // Jika source yang terkoneksi sudah timeout (benar-benar hilang), reset ke tampilan scanning
                if (!connectedSourceStillActive) {
                    isConnected = false
                    connectedSourceName = null
                    isScanning = true
                    // Reset surface untuk kembali ke tampilan hitam
                    surfaceRef = null
                    // Reset source name ke default
                    currentSourceName = "No Source Selected"
                }
            }

            // Update deviceList dengan stable devices
            if (stableDevices != deviceList) {
                deviceList = stableDevices

                // Update isScanning based on device availability
                // Hanya update jika tidak sedang terkoneksi
                if (!isConnected) {
                    if (stableDevices.isNotEmpty()) {
                        isScanning = false
                    } else {
                        // Jika devices kosong (setelah timeout), kembali ke scanning mode
                        isScanning = true
                    }
                }
            }

            // IMPROVED: Scanning interval stabil - 200ms untuk balance performa dan akurasi
            delay(200L)
        }
    }

    // Function untuk manual refresh
    fun manualRefresh() {
        coroutineScope.launch {
            isManualRefreshing = true

            // Reset cache untuk fresh scan
            deviceLastSeen = emptyMap()

            // Force immediate scan
            val currentTime = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) { onGetDevices() }

            // Build fresh device cache
            val freshLastSeen = mutableMapOf<String, Long>()
            result.forEach { device ->
                device.sources.forEach { source ->
                    freshLastSeen[source] = currentTime
                }
            }
            deviceLastSeen = freshLastSeen

            deviceList = result.filter { device ->
                device.sources.isNotEmpty()
            }

            // Tetap scanning jika tidak ada devices atau tidak terkoneksi
            if (deviceList.isNotEmpty() && !isConnected) {
                isScanning = false
            } else if (deviceList.isEmpty()) {
                isScanning = true
            }
            // Jika deviceList kosong, isScanning tetap true

            delay(500) // Small delay for user feedback
            isManualRefreshing = false
        }
    }

    LaunchedEffect(showControls) {
        hideControlsJob?.cancel()
        if (showControls) {
            hideControlsJob = launch {
                delay(CONTROLS_HIDE_DELAY)
                showControls = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { showControls = true },
        contentAlignment = Alignment.Center
    ) {
        // Canvas dengan pinch zoom dan pan - FIX untuk landscape
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, rotation ->
                        // Update scale
                        val oldScale = scale
                        scale = (scale * zoom).coerceIn(1f, 5f)

                        // Update offset dengan pan gesture
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y

                            // Batasi offset agar tidak keluar dari batas yang wajar
                            val maxOffsetX = (size.width * (scale - 1f)) / 2f
                            val maxOffsetY = (size.height * (scale - 1f)) / 2f

                            offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
                            offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
                        } else {
                            // Reset offset ketika zoom kembali ke 1x
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Canvas dengan aspect ratio 16:9 yang konsisten untuk semua orientasi
                Box(
                    modifier = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        // Landscape: gunakan tinggi penuh dengan aspect ratio 16:9
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(16f / 9f)
                    } else {
                        // Portrait: gunakan lebar penuh dengan aspect ratio 16:9
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    }
                ) {
                    NDIMonitorView(
                        quality = selectedQuality,
                        onSurfaceReady = { surface ->
                            surfaceRef = surface
                        },
                        onResolutionChanged = { width, height ->
                            currentResolution = "${width}x${height}"
                        }
                    )

                    if (showControls) {
                        Text(
                            text = currentSourceName,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            fontFamily = poppinsFontFamily,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                        )

                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = "${selectedQuality}p",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = poppinsFontFamily
                            )
                            Text(
                                text = currentResolution,
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                fontFamily = poppinsFontFamily
                            )
                        }
                    }

                    // Tampilkan "Scanning..." saat isScanning true (baik awal atau saat device off)
                    if (isScanning) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(8.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.6f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Text(
                                "Scanning...",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                fontFamily = poppinsFontFamily
                            )
                        }
                    }
                }
            }
        }

        if (isConnecting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = "Connecting...",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontFamily = poppinsFontFamily
                    )
                }
            }
        }

        if (isQualityChanging) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(24.dp).size(48.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(32.dp))
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(24.dp)
                ) {
                    IconButton(
                        onClick = {
                            showControls = true
                            showSettingsDialog = true
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Menu, "Menu", tint = Color.White, modifier = Modifier.size(32.dp))
                    }

                    if (deviceList.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .size(20.dp)
                                .background(Color.Red, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = deviceList.size.toString(),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = poppinsFontFamily
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            isDarkMode = isDarkMode,
            deviceList = deviceList,
            selectedQuality = selectedQuality,
            isConnecting = isConnecting,
            isScanning = isScanning,
            currentSourceName = currentSourceName,
            isManualRefreshing = isManualRefreshing,
            onDeviceSelected = { device ->
                selectedDevice = device
                showSourceDialog = true
            },
            onQualityChange = { quality ->
                isQualityChanging = true
                selectedQuality = quality

                surfaceRef?.let { surface ->
                    val currentSource = currentSourceName
                    if (currentSource != "No Source Selected") {
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val qualityInt = quality.toIntOrNull() ?: 720
                                onConnectToSource(currentSource, surface, qualityInt)
                                delay(300)
                                withContext(Dispatchers.Main) {
                                    isQualityChanging = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    isQualityChanging = false
                                }
                            }
                        }
                    } else {
                        isQualityChanging = false
                    }
                }
            },
            onRefresh = { manualRefresh() },
            onDismiss = { showSettingsDialog = false }
        )
    }

    if (showSourceDialog && selectedDevice != null) {
        SourceListDialog(
            isDarkMode = isDarkMode,
            device = selectedDevice!!,
            currentSourceName = currentSourceName,
            onSourceSelected = { source ->
                currentSourceName = source
                isConnecting = true
                showSourceDialog = false
                showSettingsDialog = false

                surfaceRef?.let { surface ->
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val qualityInt = selectedQuality.toIntOrNull() ?: 720
                            val connected = onConnectToSource(source, surface, qualityInt)

                            withContext(Dispatchers.Main) {
                                if (connected) {
                                    // Mark as connected dan simpan source name
                                    isConnected = true
                                    connectedSourceName = source
                                }
                                isConnecting = false
                            }
                            delay(500)
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                isConnecting = false
                            }
                        }
                    }
                }
            },
            onBack = { showSourceDialog = false },
            onDismiss = { showSourceDialog = false }
        )
    }
}

@Composable
fun SettingsDialog(
    isDarkMode: Boolean,
    deviceList: List<NDIDevice>,
    selectedQuality: String,
    isConnecting: Boolean,
    isScanning: Boolean,
    currentSourceName: String,
    isManualRefreshing: Boolean,
    onDeviceSelected: (NDIDevice) -> Unit,
    onQualityChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
            ),
            modifier = Modifier
                .widthIn(max = 500.dp)
                .fillMaxWidth(0.9f)
                .wrapContentHeight()
                .padding(vertical = 24.dp)
        ) {
            Column {
                // Header (fixed, tidak ikut scroll)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Settings",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDarkMode) Color.White else Color.Black,
                        fontFamily = poppinsFontFamily
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null, tint = if (isDarkMode) Color.White else Color.Black)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                // Scrollable content
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 600.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Available Devices Section
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Available Devices",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDarkMode) Color.White else Color.Black,
                            fontFamily = poppinsFontFamily
                        )

                        // Refresh Button
                        IconButton(
                            onClick = { onRefresh() },
                            enabled = !isManualRefreshing,
                            modifier = Modifier
                                .size(36.dp)
                        ) {
                            if (isManualRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = if (isDarkMode) Color.White else Color.Black
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Refresh Devices",
                                    tint = if (isDarkMode) Color.White else Color.Black,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 150.dp, max = 300.dp)
                            .padding(horizontal = 16.dp)
                    ) {
                        if (deviceList.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(40.dp),
                                    color = if (isDarkMode) Color.White else Color.Black
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Searching for devices...",
                                    color = Color.Gray,
                                    fontSize = 13.sp,
                                    fontFamily = poppinsFontFamily
                                )
                                Text(
                                    "Ensure devices are on the same network",
                                    color = Color.Gray.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                    fontFamily = poppinsFontFamily
                                )
                            }
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                deviceList.forEach { device ->
                                    // Cek apakah device ini sedang terhubung
                                    val isConnected = device.sources.any { source ->
                                        currentSourceName.contains(device.deviceName)
                                    }

                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFF5F5F5)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onDeviceSelected(device) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    device.deviceName,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = if (isDarkMode) Color.White else Color.Black,
                                                    fontFamily = poppinsFontFamily
                                                )
                                                Text(
                                                    "${device.sources.size} source(s)",
                                                    fontSize = 12.sp,
                                                    color = Color.Gray,
                                                    fontFamily = poppinsFontFamily
                                                )
                                            }

                                            // Icon centang jika device terhubung
                                            if (isConnected) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = "Connected",
                                                    tint = Color(0xFF4CAF50),
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Default.ArrowBack,
                                                    null,
                                                    tint = Color.Gray,
                                                    modifier = Modifier.size(16.dp).graphicsLayer(rotationZ = 180f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

                    // Stream Quality Section
                    Text(
                        "Stream Quality",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDarkMode) Color.White else Color.Black,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontFamily = poppinsFontFamily
                    )

                    // Semua opsi quality: 360p, 720p, 1080p
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("360", "720", "1080").forEach { quality ->
                            FilterChip(
                                selected = (quality == selectedQuality),
                                onClick = { onQualityChange(quality) },
                                label = {
                                    Text(
                                        "${quality}p",
                                        fontSize = 13.sp,
                                        fontWeight = if (quality == selectedQuality) FontWeight.Bold else FontWeight.Normal,
                                        fontFamily = poppinsFontFamily
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Teks keterangan di bawah button
                    Text(
                        text = when(selectedQuality) {
                            "360" -> "Low (640x360) - Fast, low bandwidth"
                            "720" -> "HD (1280x720) - Balanced quality"
                            "1080" -> "Full HD (1920x1080) - Best quality"
                            else -> "Select quality"
                        },
                        fontSize = 11.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        fontFamily = poppinsFontFamily
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun SourceListDialog(
    isDarkMode: Boolean,
    device: NDIDevice,
    currentSourceName: String,
    onSourceSelected: (String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
            ),
            modifier = Modifier
                .widthIn(max = 450.dp)
                .fillMaxWidth(0.9f)
                .wrapContentHeight()
                .padding(vertical = 24.dp)
        ) {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                // Header (fixed)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = if (isDarkMode) Color.White else Color.Black)
                    }
                    Column {
                        Text(
                            "Select Source",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDarkMode) Color.White else Color.Black,
                            fontFamily = poppinsFontFamily
                        )
                        Text(
                            device.deviceName,
                            fontSize = 12.sp,
                            color = Color.Gray,
                            fontFamily = poppinsFontFamily
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                // Scrollable source list
                Box(modifier = Modifier.weight(1f, fill = false).heightIn(max = 400.dp)) {
                    LazyColumn {
                        items(device.sources) { source ->
                            val displayName = if (source.contains("(") && source.contains(")")) {
                                source.substring(source.indexOf("(") + 1, source.indexOf(")"))
                            } else source

                            val isSelected = currentSourceName == source

                            ListItem(
                                headlineContent = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            displayName,
                                            fontSize = 14.sp,
                                            fontFamily = poppinsFontFamily,
                                            color = if (isDarkMode) Color.White else Color.Black,
                                            modifier = Modifier.weight(1f)
                                        )

                                        // Icon centang jika source sedang dipilih
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = Color(0xFF4CAF50),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.clickable { onSourceSelected(source) },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (isSelected && isDarkMode) {
                                        Color(0xFF2A2A2A)
                                    } else if (isSelected) {
                                        Color(0xFFF5F5F5)
                                    } else {
                                        Color.Transparent
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NDIMonitorView(
    quality: String,
    onSurfaceReady: (Any) -> Unit,
    onResolutionChanged: (Int, Int) -> Unit = { _, _ -> }
) {
    // Adaptive Resolution berdasarkan quality yang dipilih
    val targetResolution = remember(quality) {
        when(quality) {
            "360" -> Pair(640, 360)    // Low quality
            "720" -> Pair(1280, 720)   // HD quality
            "1080" -> Pair(1920, 1080) // Full HD quality
            else -> Pair(1280, 720)     // Default HD
        }
    }

    AndroidView(
        factory = { ctx ->
            val frameLayout = FrameLayout(ctx).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            val surfaceView = SurfaceView(ctx).apply {
                keepScreenOn = true
                holder.setFixedSize(targetResolution.first, targetResolution.second)
            }
            frameLayout.addView(surfaceView)
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    (ctx as? MainActivity)?.acquireMulticastLock(wifi)
                    onSurfaceReady(holder.surface)
                    onResolutionChanged(targetResolution.first, targetResolution.second)
                }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {
                    onResolutionChanged(w, h2)
                }
                override fun surfaceDestroyed(h: SurfaceHolder) {
                    (ctx as? MainActivity)?.releaseMulticastLock()
                }
            })
            frameLayout
        },
        update = { view ->
            // Update canvas size ketika quality berubah
            val surfaceView = (view as FrameLayout).getChildAt(0) as? SurfaceView
            surfaceView?.holder?.setFixedSize(targetResolution.first, targetResolution.second)
            onResolutionChanged(targetResolution.first, targetResolution.second)
        },
        modifier = Modifier.fillMaxSize()
    )
}