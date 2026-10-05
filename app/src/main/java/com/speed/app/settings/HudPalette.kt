package com.speed.app.settings

import androidx.compose.ui.graphics.Color

/**
 * HUD 配色。
 *
 * 每一项都有明确用途，改哪一项就只影响哪一部分：
 *  - [skyBottom]    天空色（地平线以上，纯色填充）
 *  - [groundTop]    地面色（地平线以下，纯色填充）
 *  - [line]      地平线
 *  - [ladder]    俯仰梯尺的刻度线与数字
 *  - [tapeBack]  刻度带底衬
 *  - [tapeTick]  刻度带的刻度线与标签
 *  - [pointer]   各种指针（滚转指针、俯仰带指针、速度/高度带指针）
 *  - [text]      读数文字
 *  - [reference] 机体基准线（贯穿白线 + 中间半圆）
 *
 * 注意：曾有过 skyTop / groundBottom 两个"渐变端点"字段，但天地色块实际
 * 是纯色绘制、Canvas 从不读取它们 —— 属于"设置里能改但仪表不用"的假设置，
 * 已从 [HudColorKey]、预设和持久化链路上整体移除。
 */
data class HudPalette(
    val skyBottom: Color,
    val groundTop: Color,
    val line: Color,
    val ladder: Color,
    val tapeBack: Color,
    val tapeTick: Color,
    val pointer: Color,
    val text: Color,
    val reference: Color,
) {
    fun colorFor(key: HudColorKey): Color = when (key) {
        HudColorKey.SKY -> skyBottom
        HudColorKey.GROUND -> groundTop
        HudColorKey.LINE -> line
        HudColorKey.LADDER -> ladder
        HudColorKey.TAPE_BACK -> tapeBack
        HudColorKey.TAPE_TICK -> tapeTick
        HudColorKey.POINTER -> pointer
        HudColorKey.TEXT -> text
        HudColorKey.REFERENCE -> reference
    }

    fun with(key: HudColorKey, color: Color): HudPalette = when (key) {
        HudColorKey.SKY -> copy(skyBottom = color)
        HudColorKey.GROUND -> copy(groundTop = color)
        HudColorKey.LINE -> copy(line = color)
        HudColorKey.LADDER -> copy(ladder = color)
        HudColorKey.TAPE_BACK -> copy(tapeBack = color)
        HudColorKey.TAPE_TICK -> copy(tapeTick = color)
        HudColorKey.POINTER -> copy(pointer = color)
        HudColorKey.TEXT -> copy(text = color)
        HudColorKey.REFERENCE -> copy(reference = color)
    }

    /** 逐项覆写：键是 [HudColorKey] 的名字，值是 ARGB。 */
    fun withOverrides(overrides: Map<String, Int>): HudPalette {
        if (overrides.isEmpty()) return this
        var result = this
        for (key in HudColorKey.entries) {
            val argb = overrides[key.name] ?: continue
            result = result.with(key, Color(argb))
        }
        return result
    }
}

/** 可单独自定义的颜色项。顺序即设置界面里的显示顺序。 */
enum class HudColorKey(val label: String) {
    SKY("天空"),
    GROUND("地面"),
    LINE("地平线"),
    LADDER("梯尺刻度"),
    TAPE_BACK("刻度带底衬"),
    TAPE_TICK("刻度带刻度"),
    POINTER("指针"),
    TEXT("读数文字"),
    REFERENCE("基准线"),
}

/**
 * 自定义取色用的候选色板。
 *
 * 刻意不做完整的 HSV 取色器：仪表配色的可用区间其实很窄
 * （天地色对比度不足会直接导致姿态读不出来），给一组调好的颜色比
 * 让用户自由拉到一片灰更有用。想更自由的话再补取色器也不迟。
 */
val HUD_COLOR_CHOICES: List<Color> = listOf(
    Color(0xFFFFFFFF), Color(0xFFE8F1FF), Color(0xFF9FB3CC), Color(0xFF5C6B80),
    Color(0xFF1E7FD6), Color(0xFF5FB2F0), Color(0xFF00E5FF), Color(0xFF00303D),
    Color(0xFFE14B3A), Color(0xFF8E1F13), Color(0xFFFF4B4B), Color(0xFF6B0000),
    Color(0xFFFFD200), Color(0xFFFFB627), Color(0xFFFFF2C4), Color(0xFFE08B00),
    Color(0xFF3DFF7A), Color(0xFF12602F), Color(0xFF0B3D1E), Color(0xFF04140B),
    Color(0xFFB6FF3D), Color(0xFF7C4DFF), Color(0xFF10161F), Color(0xFF000000),
)
