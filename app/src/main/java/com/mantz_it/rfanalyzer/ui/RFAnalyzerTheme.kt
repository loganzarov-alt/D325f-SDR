// D325f SDR changes, Logan Zarov, 2026-10-23. See docs/CHANGES.md.
package com.mantz_it.rfanalyzer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * <h1>RF Analyzer - Theme</h1>
 *
 * Module:      RFAnalyzerTheme.kt
 * Description: The App Theme (RFAnalyzerTheme)
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */


enum class ColorTheme(val displayName: String) {
    RFANALYZER_DARK("D325f SDR (dark)"),
    RFANALYZER_LIGHT("D325f SDR (light)"),
    RFANALYZER_AUTO("D325f SDR (system setting)"),
    SYSTEM_DARK("System Theme (dark)"),
    SYSTEM_LIGHT("System Theme (light)"),
    SYSTEM_AUTO("System Theme (system setting)"),
}

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF34E5C4),
    onPrimary = Color(0xFF032A23),
    primaryContainer = Color(0xFF164A44),
    onPrimaryContainer = Color(0xFFB6FFF0),
    secondary = Color(0xFF9381FF),
    onSecondary = Color(0xFF160A52),
    secondaryContainer = Color(0xFF31275F),
    onSecondaryContainer = Color(0xFFE7E0FF),
    tertiary = Color(0xFF9381FF),
    onTertiary = Color(0xFF160A52),
    tertiaryContainer = Color(0xFF302D36),
    onTertiaryContainer = Color(0xFFF4F0FA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF050A0F),
    onBackground = Color(0xFFE6EDF4),
    surface = Color(0xFF0C141C),
    onSurface = Color(0xFFE6EDF4),
    surfaceVariant = Color(0xFF141E28),
    onSurfaceVariant = Color(0xFFAFBECC),
    outline = Color(0xFF9381FF),
    inverseOnSurface = Color(0xFF17212A),
    inverseSurface = Color(0xFFE6EDF4),
    inversePrimary = Color(0xFF006B5C)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006B5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF76F8DD),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF5E4FC6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7E0FF),
    onSecondaryContainer = Color(0xFF19065F),
    tertiary = Color(0xFF6750A4),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEADDFF),
    onTertiaryContainer = Color(0xFF21005D),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4FBF8),
    onBackground = Color(0xFF151D1B),
    surface = Color(0xFFF4FBF8),
    onSurface = Color(0xFF151D1B),
    surfaceVariant = Color(0xFFE7EFEC),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F5FE0),
    inverseOnSurface = Color(0xFFECF2F0),
    inverseSurface = Color(0xFF2A3230),
    inversePrimary = Color(0xFF34E5C4)
)

@Composable
fun RFAnalyzerTheme(
    colorTheme: ColorTheme = ColorTheme.RFANALYZER_DARK,
    content: @Composable () -> Unit
) {
    // Dynamic color is available on Android 12+
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current

    val colorScheme = when (colorTheme) {
        ColorTheme.RFANALYZER_AUTO -> if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme
        ColorTheme.RFANALYZER_DARK -> DarkColorScheme
        ColorTheme.RFANALYZER_LIGHT -> LightColorScheme
        ColorTheme.SYSTEM_AUTO -> if (dynamicColorSupported) {
            if (isSystemInDarkTheme()) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        } else {
            if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme
        }
        ColorTheme.SYSTEM_DARK -> if (dynamicColorSupported) dynamicDarkColorScheme(context) else DarkColorScheme
        ColorTheme.SYSTEM_LIGHT -> if (dynamicColorSupported) dynamicLightColorScheme(context) else LightColorScheme
    }

    val typography = Typography(
        bodyLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.5.sp
        )
        //titleLarge = TextStyle(
        //    fontFamily = FontFamily.Default,
        //    fontWeight = FontWeight.Normal,
        //    fontSize = 22.sp,
        //    lineHeight = 28.sp,
        //    letterSpacing = 0.sp
        //),
        //labelSmall = TextStyle(
        //    fontFamily = FontFamily.Default,
        //    fontWeight = FontWeight.Medium,
        //    fontSize = 11.sp,
        //    lineHeight = 16.sp,
        //    letterSpacing = 0.5.sp
        //)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}