package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.myapplication.ui.theme.MyApplicationTheme
import android.view.SurfaceView
import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceHolder
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    init {
        System.loadLibrary("native-lib")
    }

    external fun startNDIReceiver(surface: Any)

    var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        setContent {
            MyApplicationTheme {
                NDIMonitor()
            }
        }
    }
}

// Custom FrameLayout that maintains 16:9 aspect ratio
class AspectRatioFrameLayout(context: Context) : FrameLayout(context) {
    private val targetAspectRatio = 16f / 9f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val originalWidth = MeasureSpec.getSize(widthMeasureSpec)
        val originalHeight = MeasureSpec.getSize(heightMeasureSpec)

        val containerAspectRatio = originalWidth.toFloat() / originalHeight.toFloat()

        val finalWidth: Int
        val finalHeight: Int

        if (containerAspectRatio > targetAspectRatio) {
            // Container is wider than target - fit by height
            finalHeight = originalHeight
            finalWidth = (originalHeight * targetAspectRatio).toInt()
        } else {
            // Container is taller than target - fit by width
            finalWidth = originalWidth
            finalHeight = (originalWidth / targetAspectRatio).toInt()
        }

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(finalWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.EXACTLY)
        )
    }
}

@Composable
fun NDIMonitor() {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // NDI Video Surface - fit screen with aspect ratio maintained
        AndroidView(
            factory = { context ->
                // Wrap SurfaceView in AspectRatioFrameLayout
                val aspectRatioLayout = AspectRatioFrameLayout(context).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                }

                val surfaceView = SurfaceView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }

                aspectRatioLayout.addView(surfaceView)

                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        // Acquire multicast lock
                        try {
                            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                            (context as MainActivity).multicastLock = wifi.createMulticastLock("ndi_multicast_lock")
                            (context as MainActivity).multicastLock?.setReferenceCounted(true)
                            (context as MainActivity).multicastLock?.acquire()
                            Log.i(TAG, "Multicast lock acquired successfully")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to acquire multicast lock", e)
                        }

                        // Start NDI receiver
                        val activity = context as MainActivity
                        activity.lifecycleScope.launch {
                            activity.startNDIReceiver(holder.surface)
                        }
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        // Aspect ratio maintained by parent layout
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        // Release multicast lock
                        try {
                            (context as MainActivity).multicastLock?.let {
                                if (it.isHeld) it.release()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to release multicast lock", e)
                        }
                    }
                })

                aspectRatioLayout
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme {
        Greeting("Android")
    }
}