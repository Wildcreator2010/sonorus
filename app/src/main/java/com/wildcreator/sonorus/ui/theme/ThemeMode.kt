package com.wildcreator.sonorus.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 主题模式：跟随系统 / 多套预设主题。
 *
 * 设计原则：
 * - SYSTEM 档位保留，使用 Material3 动态取色（Android 12+）或默认配色；
 * - 预设主题提供不同配色风格（经典、护眼、赛博、紫霞），每套都有独立的明暗配色，
 *   明暗变体跟随系统深色模式切换；
 * - 存字符串枚举名而不是布尔，未来加档位不用做数据迁移。
 */
enum class ThemeMode(
    val label: String,
    /** 自定义浅色配色；null = 使用 Material3 默认/动态取色 */
    val lightColors: ThemeColors? = null,
    /** 自定义深色配色；null = 使用 Material3 默认/动态取色 */
    val darkColors: ThemeColors? = null
) {
    // ---------- 系统跟随（动态取色）----------
    SYSTEM("跟随系统"),

    // ---------- 经典配色（Material 标准紫）----------
    CLASSIC(
        label = "经典紫",
        lightColors = ThemeColors(
            primary = Color(0xFF6200EE),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFBB86FC),
            onPrimaryContainer = Color(0xFF311B92),
            secondary = Color(0xFF03DAC6),
            onSecondary = Color.Black,
            secondaryContainer = Color(0xFFC6F6F1),
            onSecondaryContainer = Color(0xFF004D40),
            tertiary = Color(0xFFFDBE03),
            onTertiary = Color.Black,
            error = Color(0xFFBA1A1A),
            onError = Color.White,
            background = Color(0xFFFBFDFD),
            onBackground = Color(0xFF1A1C1E),
            surface = Color(0xFFFAFDFF),
            onSurface = Color(0xFF1A1C1E),
            surfaceVariant = Color(0xFFE0E2EC)
        ),
        darkColors = ThemeColors(
            primary = Color(0xFFBB86FC),
            onPrimary = Color(0xFF311B92),
            primaryContainer = Color(0xFF4A148C),
            onPrimaryContainer = Color(0xFFEADDFF),
            secondary = Color(0xFF03DAC6),
            onSecondary = Color(0xFF00382F),
            secondaryContainer = Color(0xFF00504C),
            onSecondaryContainer = Color(0xFFB2EBF2),
            tertiary = Color(0xFFFDBE03),
            onTertiary = Color(0xFF3E2C00),
            error = Color(0xFFCF6671),
            onError = Color(0xFF690005),
            background = Color(0xFF1A1C1E),
            onBackground = Color(0xFFE3E3E3),
            surface = Color(0xFF1A1C1E),
            onSurface = Color(0xFFE3E3E3),
            surfaceVariant = Color(0xFF444750)
        )
    ),

    // ---------- 护眼模式（低蓝光暖色）----------
    EYE_COMFORT(
        label = "护眼模式",
        lightColors = ThemeColors(
            primary = Color(0xFF8B5E3C),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFD8B0),
            onPrimaryContainer = Color(0xFF3D2610),
            secondary = Color(0xFF6B8E6B),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFC3DBC3),
            onSecondaryContainer = Color(0xFF263826),
            tertiary = Color(0xFF9C7B4F),
            onTertiary = Color(0xFFFFFFFF),
            error = Color(0xFFB34848),
            onError = Color(0xFFFFFFFF),
            background = Color(0xFFFFF8E8),
            onBackground = Color(0xFF2A2820),
            surface = Color(0xFFFFF8E8),
            onSurface = Color(0xFF2A2820),
            surfaceVariant = Color(0xFFEEE0D0)
        ),
        darkColors = ThemeColors(
            primary = Color(0xFFD4A57A),
            onPrimary = Color(0xFF3D2610),
            primaryContainer = Color(0xFF5C3E22),
            onPrimaryContainer = Color(0xFFFFD8B0),
            secondary = Color(0xFF9AB39A),
            onSecondary = Color(0xFF263826),
            secondaryContainer = Color(0xFF4A604A),
            onSecondaryContainer = Color(0xFFC3DBC3),
            tertiary = Color(0xFFD0A070),
            onTertiary = Color(0xFF3D2610),
            error = Color(0xFFE08080),
            onError = Color(0xFF3D0000),
            background = Color(0xFF1A1810),
            onBackground = Color(0xFFE8E0D0),
            surface = Color(0xFF1A1810),
            onSurface = Color(0xFFE8E0D0),
            surfaceVariant = Color(0xFF3A3830)
        )
    ),

    // ---------- 赛博朋克（霓虹蓝紫）----------
    CYBERPUNK(
        label = "赛博朋克",
        lightColors = ThemeColors(
            primary = Color(0xFF007A8A),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFB2EBF2),
            onPrimaryContainer = Color(0xFF00343D),
            secondary = Color(0xFF9C27B0),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFF3D5F9),
            onSecondaryContainer = Color(0xFF3E0A47),
            tertiary = Color(0xFF00875A),
            onTertiary = Color(0xFFFFFFFF),
            error = Color(0xFFD50032),
            onError = Color(0xFFFFFFFF),
            background = Color(0xFFF0F8FF),
            onBackground = Color(0xFF0A0E14),
            surface = Color(0xFFF0F8FF),
            onSurface = Color(0xFF0A0E14),
            surfaceVariant = Color(0xFFC0DCE8)
        ),
        darkColors = ThemeColors(
            primary = Color(0xFF00F0FF),
            onPrimary = Color(0xFF001E23),
            primaryContainer = Color(0xFF005662),
            onPrimaryContainer = Color(0xFF00F0FF),
            secondary = Color(0xFFE040FB),
            onSecondary = Color(0xFF200028),
            secondaryContainer = Color(0xFF6A1B9A),
            onSecondaryContainer = Color(0xFFFF7BFF),
            tertiary = Color(0xFF00E676),
            onTertiary = Color(0xFF00290F),
            error = Color(0xFFFF003C),
            onError = Color(0xFF3D000F),
            background = Color(0xFF0A0E14),
            onBackground = Color(0xFFE0F0FF),
            surface = Color(0xFF0A0E14),
            onSurface = Color(0xFFE0F0FF),
            surfaceVariant = Color(0xFF1A2030)
        )
    ),

    // ---------- 紫霞（温柔粉紫）----------
    PURPLE_DAWN(
        label = "紫霞",
        lightColors = ThemeColors(
            primary = Color(0xFF9B59B6),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE8DAEF),
            onPrimaryContainer = Color(0xFF3D1A52),
            secondary = Color(0xFFE67E22),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFFE5CC),
            onSecondaryContainer = Color(0xFF683E00),
            tertiary = Color(0xFF3498DB),
            onTertiary = Color(0xFFFFFFFF),
            error = Color(0xFFE74C3C),
            onError = Color(0xFFFFFFFF),
            background = Color(0xFFFDF8FF),
            onBackground = Color(0xFF2A1A2E),
            surface = Color(0xFFFDF8FF),
            onSurface = Color(0xFF2A1A2E),
            surfaceVariant = Color(0xFFE8DCEF)
        ),
        darkColors = ThemeColors(
            primary = Color(0xFFD2B4DE),
            onPrimary = Color(0xFF3D1A52),
            primaryContainer = Color(0xFF5B2C66),
            onPrimaryContainer = Color(0xFFE8DAEF),
            secondary = Color(0xFFF5C27E),
            onSecondary = Color(0xFF3D2200),
            secondaryContainer = Color(0xFF683E00),
            onSecondaryContainer = Color(0xFFFFE5CC),
            tertiary = Color(0xFF7FB3E8),
            onTertiary = Color(0xFF00325B),
            error = Color(0xFFF1998F),
            onError = Color(0xFF68140B),
            background = Color(0xFF1A1020),
            onBackground = Color(0xFFE8DCEF),
            surface = Color(0xFF1A1020),
            onSurface = Color(0xFFE8DCEF),
            surfaceVariant = Color(0xFF3A2A3E)
        )
    );
}

/** 简化的预设配色数据类（命名区别 material3 的 ColorScheme，避免歧义） */
data class ThemeColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val error: Color,
    val onError: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color
)

/**
 * 转换为 Material3 ColorScheme。
 *
 * 预设只给了核心色板，其余衍生色（outline / inverseSurface 等）
 * 用 surfaceVariant / onSurface 等合理兜底，保证组件不拿到奇怪的默认值。
 */
fun ThemeColors.toMaterial3(dark: Boolean): ColorScheme = ColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = secondaryContainer,
    onTertiaryContainer = onSecondaryContainer,
    error = error,
    onError = onError,
    errorContainer = error,
    onErrorContainer = onError,
    background = background,
    onBackground = onBackground,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = if (dark) onBackground.copy(alpha = 0.7f) else onBackground.copy(alpha = 0.7f),
    outline = surfaceVariant,
    outlineVariant = surfaceVariant,
    scrim = Color.Black,
    inverseSurface = if (dark) background else onBackground,
    inverseOnSurface = if (dark) onBackground else background,
    inversePrimary = primary,
    surfaceTint = primary,
    surfaceBright = surface,
    surfaceDim = surface,
    surfaceContainer = surface,
    surfaceContainerHigh = surfaceVariant,
    surfaceContainerHighest = surfaceVariant,
    surfaceContainerLow = surface,
    surfaceContainerLowest = surface
)

/**
 * 字号缩放档位。
 *
 * 用固定档位而不是滑杆：
 * - 滑杆容易停在 1.07 这种非整档位，让不同会话的字号看起来"有点怪"；
 * - 固定档位可与 Material3 typography 的 body/title 尺寸保持整数倍关系。
 */
enum class FontScale(val label: String, val value: Float) {
    SMALL("小", 0.9f),
    DEFAULT("标准", 1.0f),
    LARGE("大", 1.15f),
    LARGEST("特大", 1.3f)
}
