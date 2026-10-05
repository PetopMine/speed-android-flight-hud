package com.speed.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.speed.app.settings.AppearanceMode.DARK
import com.speed.app.settings.AppearanceMode.LIGHT

/** 设置界面的配色令牌。只作用于设置界面和控件，不影响仪表区。 */
data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val border: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
) {
    fun isLight(): Boolean = background.luminance() > 0.5f

    private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
}

private val DarkColors = AppColors(
    background = Color(0xFF0E1319),
    surface = Color(0xFF161D26),
    surfaceVariant = Color(0xFF1F2833),
    textPrimary = Color(0xFFE8EFF7),
    textSecondary = Color(0xFF93A3B8),
    border = Color(0xFF2B3644),
    accent = AccentColor.BLUE.value,
    onAccent = Color.White,
    danger = Color(0xFFE14B3A),
)

private val LightColors = AppColors(
    background = Color(0xFFF4F6F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8EDF3),
    textPrimary = Color(0xFF111820),
    textSecondary = Color(0xFF5B6B7F),
    border = Color(0xFFCBD5E1),
    accent = AccentColor.BLUE.value,
    onAccent = Color.White,
    danger = Color(0xFFC62828),
)

/**
 * AMOLED 暗色：背景接近纯黑（#000000），面向 OLED 屏省电与夜间 HUD 环境。
 * 与普通暗色的区别：background 纯黑、surface/surfaceVariant 更深、
 * 文本对比度更高，而不是简单把暗色再调暗一点。
 */
private val AmoledColors = AppColors(
    background = Color(0xFF000000),
    surface = Color(0xFF0A0D10),
    surfaceVariant = Color(0xFF12161C),
    textPrimary = Color(0xFFF2F6FA),
    textSecondary = Color(0xFF8B99AA),
    border = Color(0xFF1F2730),
    accent = AccentColor.BLUE.value,
    onAccent = Color.White,
    danger = Color(0xFFE14B3A),
)

/** 当前设置界面配色。 */
val LocalAppColors = staticCompositionLocalOf { DarkColors }

/** 当前 HUD 配色。仪表面板从这里取色。 */
val LocalHudPalette = staticCompositionLocalOf { HudPreset.CLASSIC.palette }

/** 当前设置，供各界面读取。 */
val LocalAppSettings = compositionLocalOf { AppSettings() }

/**
 * 设置界面主题。
 *
 * 刻意不套 Material3 的 MaterialTheme：整个 App 只有设置界面需要主题，
 * 为了它引入一整套 material3 依赖不划算。仪表区用 [LocalHudPalette]，与这里完全解耦。
 */
@Composable
fun SpeedSettingsTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val base = when (settings.appearance) {
        LIGHT -> LightColors
        AppearanceMode.AMOLED -> AmoledColors
        else -> DarkColors
    }
    val colors = remember(settings.appearance, settings.accent) {
        base.copy(accent = settings.accent.value)
    }

    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalHudPalette provides settings.palette,
        LocalAppSettings provides settings,
        content = content,
    )
}
