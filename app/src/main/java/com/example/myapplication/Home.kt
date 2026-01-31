package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Definisi Outfit Font Family
val outfitFontFamily = FontFamily(
    Font(R.font.outfit_light, FontWeight.Light),
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold)
)

@Composable
fun HomeScreen(
    isDarkMode: Boolean,
    onToggleTheme: () -> Unit,
    onStartMonitor: () -> Unit
) {
    val context = LocalContext.current

    // Pastikan saat di Home, orientasi kembali ke sensor normal atau portrait
    // agar transisinya bersih saat akan masuk ke monitor
    SideEffect {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    if (!isDarkMode) {
        // =========================
        // LIGHT MODE
        // =========================
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFB8C5D6),
                            Color(0xFFA8B5C6),
                            Color(0xFF8A99AA)
                        )
                    )
                )
        ) {
            // Logo NDI TOOLS
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 38.dp, y = 58.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Start
            ) {
                Text(
                    text = "NDI",
                    color = Color.Black,
                    style = TextStyle(
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = outfitFontFamily
                    )
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "TOOLS",
                    color = Color.Black,
                    style = TextStyle(
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = outfitFontFamily,
                        letterSpacing = 0.5.sp
                    ),
                    modifier = Modifier.offset(y = 0.dp)
                )
            }

            // Toggle Theme Button (Light Mode)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-38).dp, y = 54.dp)
                    .size(width = 62.dp, height = 34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(Color(0xFFE5E5E5))
                    .clickable { onToggleTheme() },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .offset(x = 5.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFFFFC107),
                                    Color(0xFFFF9800)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_sunny),
                        contentDescription = "Sun Icon",
                        colorFilter = ColorFilter.tint(Color.White),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            // Studio Monitor Button
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-100).dp)
                    .width(300.dp)
                    .height(70.dp)
                    .clip(RoundedCornerShape(35.dp))
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF2E3842),
                                Color(0xFF1F2831)
                            )
                        )
                    )
                    .clickable {
                        // Sebelum navigasi, set ke sensor agar saat masuk monitor langsung mengikuti posisi HP
                        val activity = context as? Activity
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

                        // Pass isDarkMode state to MainActivity
                        val intent = Intent(context, MainActivity::class.java)
                        intent.putExtra("isDarkMode", isDarkMode)
                        context.startActivity(intent)
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF3D4954)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_monitor_dark),
                            contentDescription = "Monitor Icon",
                            colorFilter = ColorFilter.tint(Color.White),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(18.dp))
                    Text(
                        text = "Studio Monitor",
                        color = Color.White,
                        style = TextStyle(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = outfitFontFamily,
                            letterSpacing = 0.3.sp
                        )
                    )
                }
            }
        }
    } else {
        // =========================
        // DARK MODE
        // =========================
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1A2332),
                            Color(0xFF0D1419),
                            Color(0xFF0A0E13)
                        )
                    )
                )
        ) {
            // Logo NDI TOOLS
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 38.dp, y = 58.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Start
            ) {
                Text(
                    text = "NDI",
                    color = Color.White,
                    style = TextStyle(
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = outfitFontFamily
                    )
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "TOOLS",
                    color = Color.White,
                    style = TextStyle(
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = outfitFontFamily,
                        letterSpacing = 0.5.sp
                    ),
                    modifier = Modifier.offset(y = 0.dp)
                )
            }

            // Toggle Theme Button (Dark Mode)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-38).dp, y = 54.dp)
                    .size(width = 62.dp, height = 34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(Color(0xFF3A4149))
                    .clickable { onToggleTheme() },
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    modifier = Modifier
                        .offset(x = (-5).dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF596576).copy(alpha = 0.9f),
                                    Color(0xFF394555).copy(alpha = 0.9f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_moon),
                        contentDescription = "Moon Icon",
                        colorFilter = ColorFilter.tint(Color.White),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            // Studio Monitor Button
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-100).dp)
                    .width(300.dp)
                    .height(70.dp)
                    .clip(RoundedCornerShape(35.dp))
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFFF5EFE0),
                                Color(0xFFE8DCC8)
                            )
                        )
                    )
                    .clickable {
                        // Sebelum navigasi, set ke sensor agar saat masuk monitor langsung mengikuti posisi HP
                        val activity = context as? Activity
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

                        // Pass isDarkMode state to MainActivity
                        val intent = Intent(context, MainActivity::class.java)
                        intent.putExtra("isDarkMode", isDarkMode)
                        context.startActivity(intent)
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF4A453E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_monitor_dark),
                            contentDescription = "Monitor Icon",
                            colorFilter = ColorFilter.tint(Color.White),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(18.dp))
                    Text(
                        text = "Studio Monitor",
                        color = Color(0xFF2B2824),
                        style = TextStyle(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = outfitFontFamily,
                            letterSpacing = 0.3.sp
                        )
                    )
                }
            }
        }
    }
}