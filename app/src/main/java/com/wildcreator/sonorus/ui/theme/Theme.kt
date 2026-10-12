package com.wildcreator.sonorus.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

/**
 * 应用主题：接受用户偏好（主题模式 + 字号缩放）而不是直接读系统。
 *
 * 之所以把这两个偏好显式传参：
 * - Composable 内直接读 DataStore 会让首帧渲染阻塞在 IO 上（观察 flow）；
 * - 由外层（MainActivity 的 RootViewModel）统一订阅后传入，首帧就能拿到默认值。
 *
 * 配色优先级：预设主题配色（跟随系统深浅色选变体） > Android 12+ 动态取色 > 静态默认。
 */
@Composable
fun SonorusTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    fontScale: FontScale = FontScale.DEFAULT,
    content: @Composable () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        // 预设主题：按系统深浅色选择对应变体
        themeMode != ThemeMode.SYSTEM -> {
            val preset = if (darkTheme) themeMode.darkColors else themeMode.lightColors
            preset?.toMaterial3(darkTheme)
                ?: if (darkTheme) DarkColors else LightColors
        }
        // Android 12+ 动态取色；低版本（含 Android 8）回退到静态配色
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = scaledTypography(fontScale),
        content = content
    )
}

/**
 * 按档位缩放整套 typography。
 *
 * 从 Material3 默认 Typography() 开始缩放而不是读 MaterialTheme.typography：
 * - 后者会造成"用当前主题构造下一个主题"的鸡生蛋循环（在同一个 MaterialTheme 内部读到的是外层默认值）；
 * - 只缩放 fontSize，不动 lineHeight/letterSpacing——后者一旦跟着缩放，
 *   "特大"档位的行距看起来会松散，反而降低可读性。
 */
private fun scaledTypography(scale: FontScale): Typography {
    val base = Typography()
    if (scale == FontScale.DEFAULT) return base
    val factor = scale.value
    fun TextStyle.scaled(): TextStyle =
        if (fontSize == TextUnit.Unspecified) this
        else copy(fontSize = (fontSize.value * factor).sp)

    return Typography(
        displayLarge = base.displayLarge.scaled(),
        displayMedium = base.displayMedium.scaled(),
        displaySmall = base.displaySmall.scaled(),
        headlineLarge = base.headlineLarge.scaled(),
        headlineMedium = base.headlineMedium.scaled(),
        headlineSmall = base.headlineSmall.scaled(),
        titleLarge = base.titleLarge.scaled(),
        titleMedium = base.titleMedium.scaled(),
        titleSmall = base.titleSmall.scaled(),
        bodyLarge = base.bodyLarge.scaled(),
        bodyMedium = base.bodyMedium.scaled(),
        bodySmall = base.bodySmall.scaled(),
        labelLarge = base.labelLarge.scaled(),
        labelMedium = base.labelMedium.scaled(),
        labelSmall = base.labelSmall.scaled()
    )
}
