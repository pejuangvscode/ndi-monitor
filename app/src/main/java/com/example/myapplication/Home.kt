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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(
    isDarkMode: Boolean,
    onToggleTheme: () -> Unit,
    onStartMonitor: () -> Unit
) {
    val context = LocalContext.current
    SideEffect {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    if (!isDarkMode) {
        // LIGHT MODE
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFA7B3C4),
                            Color(0xFFC9C9C9),
                            Color(0xFFC9C9C9),
                            Color(0xFF616D7D)
                        )
                    )
                )
        ) {
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
                        fontFamily = poppinsFontFamily
                    )
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "TOOLS",
                    color = Color.Black,
                    style = TextStyle(
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = poppinsFontFamily,
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
                                    Color(0xFFEEBC60),
                                    Color(0xFFB76C00)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_sunny),
                        contentDescription = "Sun Icon",
                        colorFilter = ColorFilter.tint(Color.White),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Studio Monitor Button
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-100).dp)
                    .width(240.dp)
                    .height(65.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF1B1F26),
                                Color(0xFF3F4959),
                                Color(0xFF1B1F26)
                            )
                        )
                    )
                    .clickable {
                        val activity = context as? Activity
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
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
                            .size(40.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xE5D1CED3)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_monitor_light),
                            contentDescription = "Monitor Icon",
                            colorFilter = ColorFilter.tint(Color.Black),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(18.dp))
                    Text(
                        text = "Studio Monitor",
                        color = Color.White,
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = poppinsFontFamily,
                            letterSpacing = 0.sp
                        )
                    )
                }
            }
        }
    } else {
        // DARK MODE
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF141920),
                            Color(0xFF323232),
                            Color(0xFF323232),
                            Color(0xFF21252E)
                        )
                    )
                )
        ) {
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
                        fontFamily = poppinsFontFamily
                    )
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "TOOLS",
                    color = Color.White,
                    style = TextStyle(
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = poppinsFontFamily,
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
                    .background(Color(0xFF535353))
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
                                    Color(0xB3596576),
                                    Color(0xCC213148)
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
                    .width(240.dp)
                    .height(65.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xB3FFFFFF),
                                Color(0xE5F4E6C8)
                            )
                        )
                    )
                    .clickable {
                        val activity = context as? Activity
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
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
                            .size(40.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF605855)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_monitor_dark),
                            contentDescription = "Monitor Icon",
                            colorFilter = ColorFilter.tint(Color.White),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(18.dp))
                    Text(
                        text = "Studio Monitor",
                        color = Color(0xFF2B2824),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = poppinsFontFamily,
                            letterSpacing = 0.3.sp
                        )
                    )
                }
            }
        }
    }
}