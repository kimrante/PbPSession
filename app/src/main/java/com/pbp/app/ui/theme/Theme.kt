package com.pbp.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** 현재 테마의 디자인 토큰. 화면 코드에서 `Pbp.colors`로 접근한다. */
object Pbp {
    val colors: PbpColors
        @Composable @ReadOnlyComposable get() = LocalPbpColors.current
}

@Composable
fun PbpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tokens = if (darkTheme) PbpDarkColors else PbpLightColors
    val material = if (darkTheme) {
        darkColorScheme(
            primary = tokens.signature,
            onPrimary = androidx.compose.ui.graphics.Color(0xFF1A1A1A),
            secondary = tokens.themeDefault,
            background = tokens.bg,
            onBackground = tokens.ink,
            surface = tokens.panel,
            onSurface = tokens.ink,
            surfaceVariant = tokens.panel2,
            onSurfaceVariant = tokens.inkDim,
            outline = tokens.line,
            surfaceContainer = tokens.panel,
            surfaceContainerLow = tokens.panel,
            surfaceContainerHigh = tokens.panel2,
            surfaceContainerHighest = tokens.panel2,
        )
    } else {
        lightColorScheme(
            primary = tokens.signature,
            onPrimary = androidx.compose.ui.graphics.Color(0xFF1A1A1A),
            secondary = tokens.themeDefault,
            background = tokens.bg,
            onBackground = tokens.ink,
            surface = tokens.panel,
            onSurface = tokens.ink,
            surfaceVariant = tokens.panel2,
            onSurfaceVariant = tokens.inkDim,
            outline = tokens.line,
            surfaceContainer = tokens.panel,
            surfaceContainerLow = tokens.panel,
            surfaceContainerHigh = tokens.panel2,
            surfaceContainerHighest = tokens.panel2,
        )
    }
    CompositionLocalProvider(LocalPbpColors provides tokens) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
