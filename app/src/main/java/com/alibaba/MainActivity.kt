package com.alibaba

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.alibaba.ui.theme.HackerColorScheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AlibabaAppRoot()
        }
    }
}

@Composable
private fun AlibabaAppRoot() {
    MaterialTheme(colorScheme = HackerColorScheme) {
        Surface {
            AlibabaNavHost()
        }
    }
}

@Preview
@Composable
private fun AlibabaAppRootPreview() {
    AlibabaAppRoot()
}
