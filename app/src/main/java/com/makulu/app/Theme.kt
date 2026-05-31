package com.makulu.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
// COLORS — Warm earthy tones inspired by African savanna / lion
// ─────────────────────────────────────────────────────────────────────────────

val Saffron = Color(0xFFF4A024)       // Primary — warm amber/saffron
val SaffronDark = Color(0xFFD4870C)   // Primary variant
val Espresso = Color(0xFF3E2723)      // Dark brown
val Cream = Color(0xFFFFF8E1)         // Light background
val Charcoal = Color(0xFF212121)      // Text on light
val Ivory = Color(0xFFFFFDE7)         // Surface
val Terracotta = Color(0xFFE64A19)    // Error / destructive
val Olive = Color(0xFF558B2F)         // Success / available
val Stone = Color(0xFF757575)         // Secondary text
val Sand = Color(0xFFD7CCC8)          // Borders / dividers

// Table state colors
val TableFree = Color(0xFF4CAF50)     // Green
val TableDraft = Color(0xFFFFC107)    // Amber/Yellow
val TablePlaced = Color(0xFFF44336)   // Red

// Printer status
val PrinterConnected = Color(0xFF4CAF50)
val PrinterDisconnected = Color(0xFFF44336)

private val LightColorScheme = lightColorScheme(
    primary = Saffron,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0B2),
    onPrimaryContainer = Espresso,
    secondary = Espresso,
    onSecondary = Color.White,
    secondaryContainer = Sand,
    onSecondaryContainer = Charcoal,
    tertiary = Olive,
    onTertiary = Color.White,
    background = Cream,
    onBackground = Charcoal,
    surface = Color.White,
    onSurface = Charcoal,
    surfaceVariant = Ivory,
    onSurfaceVariant = Stone,
    error = Terracotta,
    onError = Color.White,
    outline = Sand
)

private val DarkColorScheme = darkColorScheme(
    primary = Saffron,
    onPrimary = Espresso,
    primaryContainer = SaffronDark,
    onPrimaryContainer = Cream,
    secondary = Sand,
    onSecondary = Espresso,
    background = Color(0xFF1A1A1A),
    onBackground = Cream,
    surface = Color(0xFF262626),
    onSurface = Cream,
    surfaceVariant = Color(0xFF3A3A3A),
    onSurfaceVariant = Sand,
    error = Color(0xFFFF6E40),
    onError = Color.Black,
    outline = Color(0xFF5D4037)
)

val MakuluTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun MakuluTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MakuluTypography,
        content = content
    )
}

@Composable
fun makuluOutlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface
)
