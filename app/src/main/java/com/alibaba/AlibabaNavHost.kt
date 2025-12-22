package com.alibaba

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.alibaba.feature.auto.AutoRoute
import com.alibaba.feature.home.HomeScreen
import com.alibaba.feature.manual.ManualRoute
import com.alibaba.settings.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val MANUAL = "manual"
    const val AUTO = "auto"
    const val SETTINGS = "settings"
}

@Composable
fun AlibabaNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onManualClick = { navController.navigate(Routes.MANUAL) },
                onAutoClick = { navController.navigate(Routes.AUTO) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(Routes.MANUAL) {
            ManualRoute()
        }

        composable(Routes.AUTO) {
            AutoRoute()
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
