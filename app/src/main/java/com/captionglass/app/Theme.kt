package com.captionglass.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

private val Light = lightColorScheme(
    primary = Color(0xFF1B6B50), onPrimary = Color.White,
    primaryContainer = Color(0xFFD0EEDF), onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF4D6358), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0EEDF), onSecondaryContainer = Color(0xFF0A2019),
    tertiary = Color(0xFF855400), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB5), onTertiaryContainer = Color(0xFF2A1800),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6F8F6), onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF6F8F6), onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDDE5DF), onSurfaceVariant = Color(0xFF56615B),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3F0),
    surfaceContainer = Color(0xFFEAEFEB), surfaceContainerHigh = Color(0xFFE4EAE6),
    surfaceContainerHighest = Color(0xFFDEE4E0),
    outline = Color(0xFF87928C), outlineVariant = Color(0xFFD6DDD8),
    inverseSurface = Color(0xFF2B312E), inverseOnSurface = Color(0xFFEDF2EE), inversePrimary = Color(0xFF8BD5B5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8BD5B5), onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF0F513A), onPrimaryContainer = Color(0xFFA7F2D0),
    secondary = Color(0xFFB4CCBF), onSecondary = Color(0xFF1F352B),
    secondaryContainer = Color(0xFF23483A), onSecondaryContainer = Color(0xFFC4EBD7),
    tertiary = Color(0xFFF5BD6F), onTertiary = Color(0xFF472A00),
    tertiaryContainer = Color(0xFF653E00), onTertiaryContainer = Color(0xFFFFDDB5),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1311), onBackground = Color(0xFFDEE4E0),
    surface = Color(0xFF0E1311), onSurface = Color(0xFFDEE4E0),
    surfaceVariant = Color(0xFF3F4943), onSurfaceVariant = Color(0xFFBEC9C2),
    surfaceContainerLowest = Color(0xFF090D0B), surfaceContainerLow = Color(0xFF161C19),
    surfaceContainer = Color(0xFF1A201D), surfaceContainerHigh = Color(0xFF242B27),
    surfaceContainerHighest = Color(0xFF2F3632),
    outline = Color(0xFF88938C), outlineVariant = Color(0xFF3F4943),
    inverseSurface = Color(0xFFDEE4E0), inverseOnSurface = Color(0xFF2B312E), inversePrimary = Color(0xFF1B6B50),
)

private val Base = Typography()
private val Type = Typography(
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Medium),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.Medium),
)

/** The caption stage colour; it stays dark but lifts slightly off a dark window. */
internal val LocalStage = staticCompositionLocalOf { Color(CaptionPalette.STAGE) }

@Composable
internal fun CaptionGlassTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type) {
        CompositionLocalProvider(LocalStage provides Color(if (dark) CaptionPalette.STAGE_ON_DARK else CaptionPalette.STAGE),
            content = content)
    }
}
