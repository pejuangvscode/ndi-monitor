package com.example.myapplication

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.wifi.WifiManager
import android.util.Log
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

private const val TAG = "NDIScreen"
private const val CONTROLS_HIDE_DELAY = 3000L

@Composable
fun NDIMonitorScreen(
    isDarkMode: Boolean,
    onToggleTheme: () -> Unit,
    onBack: () -> Unit,
    onGetDevices: () -> List<NDIDevice>,
    onConnectToSource: (String, Any) -> Boolean
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // State perangkat diletakkan di sini agar tersedia seketika saat dialog dibuka
    var deviceList by remember { mutableStateOf<List<NDIDevice>>(emptyList()) }

    DisposableEffect(Unit) {
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose {
            activity?.requestedOrientation = originalOrientation
        }
    }

    // Pemindaian berkelanjutan di latar belakang untuk akurasi deteksi
    LaunchedEffect(Unit) {
        while(isActive) {
            val result = withContext(Dispatchers.IO) { onGetDevices() }
            if (result != deviceList) {
                deviceList = result
            }
            // Refresh setiap 1 detik untuk memastikan perangkat yang baru aktif segera terdeteksi
            delay(1000)
        }
    }

    var showControls by remember { mutableStateOf(true) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var selectedDevice by remember { mutableStateOf<NDIDevice?>(null) }
    var currentSourceName by remember { mutableStateOf("No Source Selected") }
    var hideControlsJob by remember { mutableStateOf<Job?>(null) }
    var surfaceRef by remember { mutableStateOf<Any?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedQuality by remember { mutableStateOf("720") }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var scale by remember { mutableStateOf(1f) }

    LaunchedEffect(showControls) {
        hideControlsJob?.cancel()
        if (showControls) {
            hideControlsJob = launch {
                delay(CONTROLS_HIDE_DELAY)
                showControls = false
            }
        }
    }

    val backgroundColor = if (isDarkMode) Color(0xFF2C2C2C) else Color(0xFFE0E0E0)
    val iconColor = if (isLandscape) Color.White else (if (isDarkMode) Color.White else Color.Black)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { showControls = true },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = maxOf(1f, scale),
                    scaleY = maxOf(1f, scale),
                )
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        scale *= zoom
                        scale = scale.coerceIn(1f, 5f)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                NDIMonitorView(
                    onSurfaceReady = { surface ->
                        surfaceRef = surface
                    }
                )

                if (showControls) {
                    Column(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                        Text(
                            text = currentSourceName,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    Text(
                        text = "${selectedQuality}p",
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(24.dp).size(48.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = iconColor, modifier = Modifier.size(32.dp))
                }

                IconButton(
                    onClick = {
                        showControls = true
                        showSettingsDialog = true
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(24.dp).size(48.dp)
                ) {
                    Icon(Icons.Default.Menu, "Menu", tint = iconColor, modifier = Modifier.size(32.dp))
                }
            }
        }

        if (showSettingsDialog) {
            SettingsDialog(
                isDarkMode = isDarkMode,
                devices = deviceList,
                selectedQuality = selectedQuality,
                onDeviceSelected = { device ->
                    selectedDevice = device
                    showSettingsDialog = false
                    showSourceDialog = true
                },
                onQualityChange = { quality ->
                    selectedQuality = quality
                },
                onDismiss = { showSettingsDialog = false }
            )
        }

        if (showSourceDialog && selectedDevice != null) {
            SourceListDialog(
                isDarkMode = isDarkMode,
                device = selectedDevice!!,
                onSourceSelected = { sourceName ->
                    showSourceDialog = false
                    surfaceRef?.let { surface ->
                        isLoading = true
                        val scope = (activity as? MainActivity)?.lifecycleScope
                        scope?.launch(Dispatchers.IO) {
                            try {
                                val success = onConnectToSource(sourceName, surface)
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    if (success) {
                                        val displayName = if (sourceName.contains("(") && sourceName.contains(")")) {
                                            sourceName.substring(sourceName.indexOf("(") + 1, sourceName.indexOf(")"))
                                        } else sourceName
                                        currentSourceName = displayName
                                    } else {
                                        currentSourceName = "Connection Failed"
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    currentSourceName = "Error"
                                }
                            }
                        }
                    }
                },
                onBack = {
                    showSourceDialog = false
                    showSettingsDialog = true
                },
                onDismiss = { showSourceDialog = false }
            )
        }
    }
}

@Composable
fun SettingsDialog(
    isDarkMode: Boolean,
    devices: List<NDIDevice>,
    selectedQuality: String,
    onDeviceSelected: (NDIDevice) -> Unit,
    onQualityChange: (String) -> Unit,
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
            modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (isDarkMode) Color.White else Color.Black)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null, tint = if (isDarkMode) Color.White else Color.Black)
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp)) {
                    item {
                        Text("Select Device", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (isDarkMode) Color.White else Color.Black, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    if (devices.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text("No devices found", color = if (isDarkMode) Color.Gray else Color.DarkGray)
                            }
                        }
                    }
                    items(devices) { device ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onDeviceSelected(device) },
                            colors = CardDefaults.cardColors(containerColor = if (isDarkMode) Color(0xFF2C2C2C) else Color(0xFFF5F5F5)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(device.deviceName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (isDarkMode) Color.White else Color.Black)
                                    Text("${device.sources.size} source(s) available", fontSize = 13.sp, color = if (isDarkMode) Color.LightGray else Color.Gray)
                                }
                                Icon(Icons.Default.ArrowBack, null, tint = Color.Gray, modifier = Modifier.graphicsLayer(rotationZ = 180f))
                            }
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Text("Quality", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (isDarkMode) Color.White else Color.Black, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            listOf("360", "720", "1080").forEach { quality ->
                                FilterChip(
                                    selected = (quality == selectedQuality),
                                    onClick = { onQualityChange(quality) },
                                    label = { Text("${quality}p") },
                                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SourceListDialog(
    isDarkMode: Boolean,
    device: NDIDevice,
    onSourceSelected: (String) -> Unit,
    onBack: () -> Unit,
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
            modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.8f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, null, tint = if (isDarkMode) Color.White else Color.Black)
                        }
                        Column {
                            Text("Select Source", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (isDarkMode) Color.White else Color.Black)
                            Text(device.deviceName, fontSize = 14.sp, color = if (isDarkMode) Color.LightGray else Color.Gray)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null, tint = if (isDarkMode) Color.White else Color.Black)
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp)) {
                    items(device.sources) { sourceFull ->
                        val displayName = if (sourceFull.contains("(") && sourceFull.contains(")")) {
                            sourceFull.substring(sourceFull.indexOf("(") + 1, sourceFull.indexOf(")"))
                        } else sourceFull

                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onSourceSelected(sourceFull) },
                            colors = CardDefaults.cardColors(containerColor = if (isDarkMode) Color(0xFF2C2C2C) else Color(0xFFF5F5F5))
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(displayName, fontSize = 16.sp, color = if (isDarkMode) Color.White else Color.Black)
                                Icon(Icons.Default.ArrowBack, null, tint = Color.Gray, modifier = Modifier.graphicsLayer(rotationZ = 180f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NDIMonitorView(onSurfaceReady: (Any) -> Unit) {
    AndroidView(
        factory = { ctx ->
            val frameLayout = FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) }
            val surfaceView = SurfaceView(ctx).apply { keepScreenOn = true }
            frameLayout.addView(surfaceView)
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    val lifecycleOwner = frameLayout.findViewTreeLifecycleOwner()
                    lifecycleOwner?.lifecycleScope?.launch(Dispatchers.Main) {
                        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        (ctx as? MainActivity)?.acquireMulticastLock(wifi)
                        onSurfaceReady(holder.surface)
                    }
                }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {
                    (ctx as? MainActivity)?.releaseMulticastLock()
                }
            })
            frameLayout
        },
        modifier = Modifier.fillMaxSize()
    )
}