package com.alibaba.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 🔥 ALİ BABA YAZILIM - HAVALI AÇILIŞ ANİMASYONU
 * 
 * Matrix tarzı yeşil kod yağmuru + Logo animasyonu
 */
@Composable
fun SplashScreen(
    onSplashComplete: () -> Unit
) {
    var showContent by remember { mutableStateOf(false) }
    var showLogo by remember { mutableStateOf(false) }
    var showTagline by remember { mutableStateOf(false) }
    var showVersion by remember { mutableStateOf(false) }
    
    // Animasyon değerleri
    val infiniteTransition = rememberInfiniteTransition(label = "splash")
    
    // Logo pulse animasyonu
    val logoScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logoScale"
    )
    
    // Glow animasyonu
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    
    // Rotation animasyonu
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    // Animasyon sıralaması
    LaunchedEffect(Unit) {
        delay(300)
        showContent = true
        delay(500)
        showLogo = true
        delay(400)
        showTagline = true
        delay(300)
        showVersion = true
        delay(1500)
        onSplashComplete()
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0D0D1A),
                        Color(0xFF1A0A2E),
                        Color(0xFF0D0D1A)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Matrix benzeri arka plan partikülleri
        MatrixBackground(showContent)
        
        // Dönen halka efekti
        Canvas(
            modifier = Modifier
                .size(280.dp)
                .alpha(glowAlpha * 0.5f)
        ) {
            rotate(rotation) {
                for (i in 0 until 12) {
                    val angle = (i * 30f) * (Math.PI / 180f).toFloat()
                    val x = center.x + cos(angle) * size.width * 0.4f
                    val y = center.y + sin(angle) * size.height * 0.4f
                    drawCircle(
                        color = Color(0xFF00FF41),
                        radius = 4.dp.toPx(),
                        center = Offset(x, y),
                        alpha = if (i % 2 == 0) 1f else 0.5f
                    )
                }
            }
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo - Modern IPTV ikonu
            if (showLogo) {
                Box(
                    modifier = Modifier
                        .scale(logoScale)
                        .padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Glow arka plan
                    Canvas(
                        modifier = Modifier
                            .size(120.dp)
                            .blur(25.dp)
                            .alpha(glowAlpha)
                    ) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF00FF41),
                                    Color(0xFF00FFFF),
                                    Color.Transparent
                                )
                            ),
                            radius = size.minDimension / 2
                        )
                    }
                    
                    // Ana logo - TV + Signal ikonu
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "📡",
                            fontSize = 60.sp
                        )
                        Text(
                            text = "�",
                            fontSize = 50.sp,
                            modifier = Modifier.offset(y = (-8).dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Firma adı - Gradient efektli
                Text(
                    text = "ALİ BABA",
                    style = TextStyle(
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 8.sp,
                        shadow = Shadow(
                            color = Color(0xFF00FF41),
                            offset = Offset(0f, 0f),
                            blurRadius = 20f
                        ),
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF00FF41),
                                Color(0xFF00FFFF),
                                Color(0xFF00FF41)
                            )
                        )
                    )
                )
                
                Text(
                    text = "YAZILIM",
                    style = TextStyle(
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 12.sp,
                        color = Color(0xFF00FF41).copy(alpha = 0.8f)
                    )
                )
            }
            
            // Tagline
            if (showTagline) {
                Spacer(modifier = Modifier.height(32.dp))
                
                Text(
                    text = "⚡ IPTV POWER TOOLS ⚡",
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp,
                        color = Color(0xFFFFD700)
                    )
                )
            }
            
            // Version
            if (showVersion) {
                Spacer(modifier = Modifier.height(48.dp))
                
                Text(
                    text = "v2.0 ULTRA",
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 2.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                )
            }
        }
        
        // Alt bilgi
        if (showContent) {
            Text(
                text = "🔥 Powered by Advanced Technology 🔥",
                style = TextStyle(
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.3f)
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
            )
        }
    }
}

/**
 * Matrix tarzı arka plan animasyonu
 */
@Composable
private fun MatrixBackground(visible: Boolean) {
    if (!visible) return
    
    val particles = remember {
        List(50) {
            Particle(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                speed = Random.nextFloat() * 0.02f + 0.005f,
                size = Random.nextFloat() * 3f + 1f,
                alpha = Random.nextFloat() * 0.5f + 0.1f
            )
        }
    }
    
    var time by remember { mutableFloatStateOf(0f) }
    
    LaunchedEffect(Unit) {
        while (true) {
            delay(16) // ~60fps
            time += 0.016f
        }
    }
    
    Canvas(modifier = Modifier.fillMaxSize()) {
        particles.forEach { particle ->
            val y = (particle.y + time * particle.speed) % 1f
            drawCircle(
                color = Color(0xFF00FF41),
                radius = particle.size.dp.toPx(),
                center = Offset(
                    x = particle.x * size.width,
                    y = y * size.height
                ),
                alpha = particle.alpha * (1f - y) // Aşağı indikçe soluk
            )
        }
    }
}

private data class Particle(
    val x: Float,
    val y: Float,
    val speed: Float,
    val size: Float,
    val alpha: Float
)

private val EaseInOutCubic = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private val EaseInOutSine = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
