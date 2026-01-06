package com.alibaba.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

object HackerColors {
    val Background = Color(0xFF0D0D0D)
    val Surface = Color(0xFF1A1A1A)
    val SurfaceVariant = Color(0xFF262626)
    val Primary = Color(0xFF00FF41)
    val PrimaryVariant = Color(0xFF00CC33)
    val Secondary = Color(0xFF00FFFF)
    val SecondaryVariant = Color(0xFF00CCCC)
    val Tertiary = Color(0xFF0066CC)
    val Error = Color(0xFFFF0055)
    val ErrorContainer = Color(0xFF330000)
    val OnPrimary = Color.Black
    val OnSecondary = Color.Black
    val OnBackground = Color.White
    val OnSurface = Color.White
    val OnError = Color.White
    val Outline = Color(0xFF404040)
    val OutlineVariant = Color(0xFF2A2A2A)
}

val HackerColorScheme = darkColorScheme(
    primary = HackerColors.Primary,
    onPrimary = HackerColors.OnPrimary,
    primaryContainer = HackerColors.PrimaryVariant,
    onPrimaryContainer = HackerColors.OnPrimary,
    secondary = HackerColors.Secondary,
    onSecondary = HackerColors.OnSecondary,
    secondaryContainer = HackerColors.SecondaryVariant,
    onSecondaryContainer = HackerColors.OnSecondary,
    tertiary = HackerColors.Tertiary,
    onTertiary = Color.White,
    error = HackerColors.Error,
    onError = HackerColors.OnError,
    errorContainer = HackerColors.ErrorContainer,
    onErrorContainer = HackerColors.OnError,
    background = HackerColors.Background,
    onBackground = HackerColors.OnBackground,
    surface = HackerColors.Surface,
    onSurface = HackerColors.OnSurface,
    surfaceVariant = HackerColors.SurfaceVariant,
    onSurfaceVariant = HackerColors.OnSurface,
    outline = HackerColors.Outline,
    outlineVariant = HackerColors.OutlineVariant
)
