package com.alibaba

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.alibaba.ui.SplashScreen
import com.alibaba.ui.theme.HackerColorScheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Bildirim izni sonucu
        if (isGranted) {
            requestBatteryOptimizationExemption()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Arka plan çalışma izinlerini iste
        requestBackgroundPermissions()
        
        setContent {
            AlibabaAppRoot()
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Bildirimden tıklandığında buraya gelir
        // Activity zaten var, sadece öne getirildi
        // State korunur, hiçbir şey yapmaya gerek yok
    }
    
    private fun requestBackgroundPermissions() {
        // 1. Bildirim izni (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        
        // 2. Pil optimizasyonu muafiyeti
        requestBatteryOptimizationExemption()
    }
    
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                // Pil optimizasyonundan muafiyet iste
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Bazı cihazlarda bu intent desteklenmiyor olabilir
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    } catch (_: Exception) {
                        // Ignore
                    }
                }
            }
        }
    }
}

@Composable
private fun AlibabaAppRoot() {
    var showSplash by remember { mutableStateOf(true) }
    
    MaterialTheme(colorScheme = HackerColorScheme) {
        Surface {
            // 🔥 ALİ BABA YAZILIM - HAVALI AÇILIŞ ANİMASYONU
            AnimatedContent(
                targetState = showSplash,
                transitionSpec = {
                    fadeIn(animationSpec = tween(500)) togetherWith 
                    fadeOut(animationSpec = tween(500))
                },
                label = "splash"
            ) { isSplash ->
                if (isSplash) {
                    SplashScreen(
                        onSplashComplete = { showSplash = false }
                    )
                } else {
                    AlibabaNavHost()
                }
            }
        }
    }
}

@Preview
@Composable
private fun AlibabaAppRootPreview() {
    AlibabaAppRoot()
}
