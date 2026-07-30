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
            onPrimary = tokens.onSignature,
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
            onPrimary = tokens.onSignature,
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
    // Material3의 typography는 컴포넌트별로만 적용돼 일반 Text에는 닿지 않는다.
    // 앱 글꼴이 모든 텍스트(말풍선·이름·시간)에 반영되도록 LocalTextStyle도 함께 제공한다.
    val baseTextStyle = androidx.compose.material3.LocalTextStyle.current
        .merge(androidx.compose.ui.text.TextStyle(fontFamily = AppFonts.fontFamily))
    CompositionLocalProvider(
        LocalPbpColors provides tokens,
        androidx.compose.material3.LocalTextStyle provides baseTextStyle,
    ) {
        MaterialTheme(
            colorScheme = material,
            typography = typographyWith(AppFonts.fontFamily),
            // 다이얼로그·시트가 M3 기본 28 대신 rSheet(20)를 쓰도록 — 커스텀 Dialog와
            // 스톡 AlertDialog가 한 가족이 된다 (ui-guidelines 5장)
            shapes = androidx.compose.material3.Shapes(
                extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(PbpDimens.rSheet),
            ),
            content = content,
        )
    }
}
