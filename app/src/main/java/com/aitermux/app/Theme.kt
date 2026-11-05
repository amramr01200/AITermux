package com.aitermux.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Gold = Color(0xFFD4AF37)
val Ink = Color(0xFF0A0A0A)
val Panel = Color(0xFF161616)
val Cream = Color(0xFFEDE3C0)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Gold, onPrimary = Ink,
            secondary = Gold, onSecondary = Ink,
            background = Ink, onBackground = Cream,
            surface = Panel, onSurface = Cream,
            surfaceVariant = Color(0xFF1F1B0E), onSurfaceVariant = Cream,
            outline = Color(0xFF8A7424),
            primaryContainer = Color(0xFF3A2F0B), onPrimaryContainer = Cream
        ),
        content = content
    )
}
