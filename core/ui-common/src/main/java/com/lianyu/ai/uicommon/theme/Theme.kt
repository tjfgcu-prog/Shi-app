package com.lianyu.ai.uicommon.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = PinkPrimary,
    onPrimary = Color(0xFF2D1F24),
    primaryContainer = PinkPrimary.copy(alpha = 0.2f),
    onPrimaryContainer = Color(0xFF2D1F24),
    secondary = Color(0xFF9B6B7A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9B6B7A).copy(alpha = 0.1f),
    onSecondaryContainer = Color(0xFF9B6B7A),
    tertiary = PinkDark,
    onTertiary = Color.White,
    background = WeChatLightBackground,
    onBackground = WeChatLightTextPrimary,
    surface = WeChatLightSurface,
    onSurface = WeChatLightTextPrimary,
    surfaceVariant = WeChatLightCard,
    onSurfaceVariant = WeChatLightTextSecondary,
    outline = WeChatLightDivider,
    error = ErrorRed,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = PinkPrimary,
    onPrimary = Color(0xFF2D1F24),
    primaryContainer = PinkPrimary.copy(alpha = 0.15f),
    onPrimaryContainer = Color(0xFFF5E6EB),
    secondary = PinkLight,
    onSecondary = Color(0xFF2D2C24),
    secondaryContainer = PinkLight.copy(alpha = 0.1f),
    onSecondaryContainer = PinkLight,
    tertiary = PinkDark,
    onTertiary = Color.White,
    background = WeChatDarkBackground,
    onBackground = WeChatDarkTextPrimary,
    surface = WeChatDarkSurface,
    onSurface = WeChatDarkTextPrimary,
    surfaceVariant = WeChatDarkCard,
    onSurfaceVariant = WeChatDarkTextSecondary,
    outline = WeChatDarkDivider,
    error = ErrorRed,
    onError = Color.White
)

/** 点击时什么都不画：去掉按下变暗 / 水波纹效果。 */
private object NoPressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun hashCode(): Int = -1
    override fun equals(other: Any?): Boolean = other === this
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LianYuTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemInDarkTheme = isSystemInDarkTheme()

    val effectiveDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemInDarkTheme
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (effectiveDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        effectiveDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(effectiveDarkTheme) {
            val window = (view.context as Activity).window
            val navScrim = if (effectiveDarkTheme) {
                Color(0xFF000000).copy(alpha = 0.25f)
            } else {
                Color(0xFFFFFFFF).copy(alpha = 0.55f)
            }.toArgb()

            // 状态栏使用不透明纯色，浅色主题用纯白避免显粉；深色主题用背景色保持一致。
            window.statusBarColor = if (effectiveDarkTheme) {
                colorScheme.background.toArgb()
            } else {
                Color(0xFFFFFFFF).toArgb()
            }
            window.navigationBarColor = navScrim
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !effectiveDarkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !effectiveDarkTheme
            onDispose { }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography
    ) {
        // 全 App 去掉点击时的变暗效果：
        // LocalIndication 管 Modifier.clickable 的按下效果，LocalRippleConfiguration = null 关掉 Material 组件
        // （按钮、芯片、图标按钮、开关等）自带的水波纹。弹窗里的内容也会继承。
        CompositionLocalProvider(
            LocalIndication provides NoPressIndication,
            LocalRippleConfiguration provides null,
            content = content
        )
    }
}
