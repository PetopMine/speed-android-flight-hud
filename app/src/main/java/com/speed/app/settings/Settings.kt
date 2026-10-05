package com.speed.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 面板里可以放的仪表种类。 */
enum class TileType(val label: String) {
    ATTITUDE("姿态仪"),
    SPEED("速度带"),
    ALTITUDE("高度带"),
    HEADING("航向带"),
    READOUT("数字读数"),
    CALIBRATE("校准"),
    BLANK("空白"),
}

/** 屏幕方向模式。 */
enum class OrientationMode(val label: String) {
    FOLLOW("跟随系统"),
    PORTRAIT("锁定竖屏"),
    LANDSCAPE("锁定横屏"),
}

/** 界面明暗模式。 */
enum class AppearanceMode(val label: String) {
    DARK("暗色"),
    LIGHT("浅色"),
    AMOLED("AMOLED 暗色"),
}

/** HUD 基准线粗细。真实作用于 Canvas strokeWidth。 */
enum class ReferenceLineWidth(val label: String, val dp: Float) {
    THIN("细", 2f),
    STANDARD("标准", 4f),
    THICK("粗", 7f),
}

/** HUD 基准线两侧横线长度（相对面板短边的比例）。 */
enum class ReferenceLineLength(val label: String, val factor: Float) {
    SHORT("短", 0.07f),
    STANDARD("标准", 0.10f),
    LONG("长", 0.14f),
}

/**
 * 高度数据源（用户可选，真实贯穿传感器选择）。
 *
 *  - [BAROMETER]：气压计（TYPE_PRESSURE + 标准大气公式）。默认。
 *  - [GPS]：Location.hasAltitude()/altitude。
 *
 * 用户选择哪个，高度就只用哪个；所选来源无数据时显示 "--"，绝不偷偷切换。
 * 升降率与高度源**独立**：始终由气压计计算（无气压计则 "--"）。
 */
enum class AltitudeSource(val label: String) {
    BAROMETER("气压计"),
    GPS("GPS"),
}

/**
 * 仪表版式。
 *
 * 两种版式共用同一套传感器、校准、配色与手势，只是排布方式不同。
 */
enum class InstrumentStyle(val label: String) {
    /** 原版：屏幕均分两块，每块自选仪表。 */
    PANELS("双面板自由组合"),

    /** 航空电子风格：一整块 EFIS 综合显示器。 */
    EFIS("EFIS 综合显示"),
}

/**
 * HUD 配色方案。每一项都是**整套**颜色，作为自定义的起点；
 * 用户在设置里改动某个颜色后，只有那一项会被覆写，其余继续跟随方案。
 */
enum class HudPreset(val label: String, val palette: HudPalette) {
    CLASSIC(
        "经典蓝红",
        HudPalette(
            skyBottom = Color(0xFF5FB2F0),
            groundTop = Color(0xFFE14B3A),
            line = Color(0xFFFFFFFF),
            ladder = Color(0xFFFFFFFF),
            tapeBack = Color(0xCC10161F),
            tapeTick = Color(0xFFE8F1FF),
            pointer = Color(0xFFFFD200),
            text = Color(0xFFE8F1FF),
            reference = Color(0xFFFFFFFF),
        ),
    ),
    MONO_GREEN(
        "单色绿",
        HudPalette(
            skyBottom = Color(0xFF12602F),
            groundTop = Color(0xFF052012),
            line = Color(0xFF3DFF7A),
            ladder = Color(0xFF3DFF7A),
            tapeBack = Color(0xCC04140B),
            tapeTick = Color(0xFF7BFFAE),
            pointer = Color(0xFFB6FF3D),
            text = Color(0xFF7BFFAE),
            reference = Color(0xFFFFFFFF),
        ),
    ),
    AMBER(
        "军工琥珀",
        HudPalette(
            skyBottom = Color(0xFF6B4E00),
            groundTop = Color(0xFF241900),
            line = Color(0xFFFFB627),
            ladder = Color(0xFFFFB627),
            tapeBack = Color(0xCC1A1200),
            tapeTick = Color(0xFFFFD98A),
            pointer = Color(0xFFFFF2C4),
            text = Color(0xFFFFD98A),
            reference = Color(0xFFFFFFFF),
        ),
    ),
    NIGHT_RED(
        "夜间红",
        HudPalette(
            skyBottom = Color(0xFF4A1212),
            groundTop = Color(0xFF160404),
            line = Color(0xFFFF4B4B),
            ladder = Color(0xFFFF4B4B),
            tapeBack = Color(0xCC140303),
            tapeTick = Color(0xFFFF8A8A),
            pointer = Color(0xFFFFC4C4),
            text = Color(0xFFFF8A8A),
            reference = Color(0xFFFFFFFF),
        ),
    ),
    HIGH_CONTRAST(
        "高对比青",
        HudPalette(
            skyBottom = Color(0xFF00566B),
            groundTop = Color(0xFF1A1A1A),
            line = Color(0xFF00E5FF),
            ladder = Color(0xFF00E5FF),
            tapeBack = Color(0xCC000A0D),
            tapeTick = Color(0xFF9BF5FF),
            pointer = Color(0xFFFFFFFF),
            text = Color(0xFF9BF5FF),
            reference = Color(0xFFFFFFFF),
        ),
    ),
}

/** 面板内容分配：[topOrLeft] 是竖屏上半 / 横屏左半，[bottomOrRight] 是竖屏下半 / 横屏右半。 */
data class TileAssignment(
    val first: TileType = TileType.ATTITUDE,
    val second: TileType = TileType.SPEED,
) {
    fun forSlot(slotIndex: Int): TileType = if (slotIndex == 0) first else second
}

/** 主题色候选（用于设置界面与按钮，不影响仪表配色）。 */
enum class AccentColor(val label: String, val value: Color) {
    BLUE("蓝", Color(0xFF2E6BE6)),
    CYAN("青", Color(0xFF00B8D4)),
    GREEN("绿", Color(0xFF2FA84F)),
    AMBER("琥珀", Color(0xFFE08B00)),
    RED("红", Color(0xFFE14B3A)),
    PURPLE("紫", Color(0xFF7C4DFF)),
    GRAY("中性灰", Color(0xFF8A93A3)),
}
/**
 * 全部可持久化的设置。
 *
 * [paletteOverrides] 只存被用户改动过的颜色项，键为 [HudColorKey] 的 name。
 * 这样切换预设方案时，没被改过的项会自动跟随新方案，改过的项保持不变。
 */
data class AppSettings(
    val orientation: OrientationMode = OrientationMode.FOLLOW,
    val instrumentStyle: InstrumentStyle = InstrumentStyle.PANELS,
    val appearance: AppearanceMode = AppearanceMode.DARK,
    val accent: AccentColor = AccentColor.BLUE,
    val altitudeSource: AltitudeSource = AltitudeSource.BAROMETER,
    val hudPreset: HudPreset = HudPreset.CLASSIC,
    val paletteOverrides: Map<String, Int> = emptyMap(),
    val portraitTiles: TileAssignment = TileAssignment(TileType.ATTITUDE, TileType.SPEED),
    val landscapeTiles: TileAssignment = TileAssignment(TileType.ATTITUDE, TileType.SPEED),
    val showControls: Boolean = true,
    /** 保持屏幕常亮：运行时对应 Window FLAG_KEEP_SCREEN_ON。 */
    val keepScreenOn: Boolean = false,
    /**
     * 校准基准：按下"校准"那一刻的**原始**俯仰 / 滚转读数。
     * 显示值 = 原始值 − 基准值，所以想恢复出厂零点把两者置 0 即可。
     * 保存的是原始值而不是偏移量，避免"校准两次叠加"的歧义。
     */
    val zeroPitch: Float = 0f,
    val zeroRoll: Float = 0f,
    /** 真北模式：开启后 heading = 磁航向 + 磁偏角。无位置/权限时磁偏角按 0 处理（保持磁北）。 */
    val trueNorth: Boolean = false,
    /** HUD 基准线粗细。 */
    val referenceLineWidth: ReferenceLineWidth = ReferenceLineWidth.STANDARD,
    /** HUD 基准线两侧横线长度。 */
    val referenceLineLength: ReferenceLineLength = ReferenceLineLength.STANDARD,
) {
    /** 是否做过校准（用于界面提示）。 */
    val isCalibrated: Boolean
        get() = zeroPitch != 0f || zeroRoll != 0f

    /** 解析出最终生效的配色：预设打底，逐项覆写。 */
    val palette: HudPalette
        get() = hudPreset.palette.withOverrides(paletteOverrides)

    /** 按当前屏幕方向取对应的面板配置。 */
    fun tilesFor(isLandscape: Boolean): TileAssignment =
        if (isLandscape) landscapeTiles else portraitTiles
}

/** 设置读写。用 SharedPreferences 而不是 DataStore，省一个依赖，量级也完全够用。 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        save(updated)
    }

    fun setPaletteColor(key: HudColorKey, color: Int?) {
        update { current ->
            val overrides = current.paletteOverrides.toMutableMap()
            if (color == null) {
                overrides.remove(key.name)
            } else {
                overrides[key.name] = color
            }
            current.copy(paletteOverrides = overrides)
        }
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            instrumentStyle = prefs.getString(KEY_STYLE, null)
                ?.let { name -> InstrumentStyle.entries.firstOrNull { it.name == name } }
                ?: defaults.instrumentStyle,
            orientation = prefs.getString(KEY_ORIENTATION, null)
                ?.let { name -> OrientationMode.entries.firstOrNull { it.name == name } }
                ?: defaults.orientation,
            appearance = prefs.getString(KEY_APPEARANCE, null)
                ?.let { name -> AppearanceMode.entries.firstOrNull { it.name == name } }
                ?: defaults.appearance,
            accent = prefs.getString(KEY_ACCENT, null)
                ?.let { name -> AccentColor.entries.firstOrNull { it.name == name } }
                ?: defaults.accent,
            altitudeSource = prefs.getString(KEY_ALTITUDE_SOURCE, null)
                ?.let { name -> AltitudeSource.entries.firstOrNull { it.name == name } }
                ?: defaults.altitudeSource,
            hudPreset = prefs.getString(KEY_HUD_PRESET, null)
                ?.let { name -> HudPreset.entries.firstOrNull { it.name == name } }
                ?: defaults.hudPreset,
            paletteOverrides = prefs.all
                .filterKeys { it.startsWith(PREFIX_OVERRIDE) }
                .mapNotNull { (key, value) ->
                    val color = (value as? Int) ?: return@mapNotNull null
                    key.removePrefix(PREFIX_OVERRIDE) to color
                }
                .toMap(),
            portraitTiles = TileAssignment(
                first = prefs.getString(KEY_PORTRAIT_FIRST, null).toTileType(defaults.portraitTiles.first),
                second = prefs.getString(KEY_PORTRAIT_SECOND, null).toTileType(defaults.portraitTiles.second),
            ),
            landscapeTiles = TileAssignment(
                first = prefs.getString(KEY_LANDSCAPE_FIRST, null).toTileType(defaults.landscapeTiles.first),
                second = prefs.getString(KEY_LANDSCAPE_SECOND, null).toTileType(defaults.landscapeTiles.second),
            ),
            showControls = prefs.getBoolean(KEY_SHOW_CONTROLS, defaults.showControls),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, defaults.keepScreenOn),
            zeroPitch = prefs.getFloat(KEY_ZERO_PITCH, defaults.zeroPitch),
            zeroRoll = prefs.getFloat(KEY_ZERO_ROLL, defaults.zeroRoll),
            trueNorth = prefs.getBoolean(KEY_TRUE_NORTH, defaults.trueNorth),
            referenceLineWidth = prefs.getString(KEY_REF_WIDTH, null)
                ?.let { name -> ReferenceLineWidth.entries.firstOrNull { it.name == name } }
                ?: defaults.referenceLineWidth,
            referenceLineLength = prefs.getString(KEY_REF_LENGTH, null)
                ?.let { name -> ReferenceLineLength.entries.firstOrNull { it.name == name } }
                ?: defaults.referenceLineLength,
        )
    }

    private fun save(settings: AppSettings) {
        prefs.edit {
            putString(KEY_ORIENTATION, settings.orientation.name)
            putString(KEY_STYLE, settings.instrumentStyle.name)
            putString(KEY_APPEARANCE, settings.appearance.name)
            putString(KEY_ACCENT, settings.accent.name)
            putString(KEY_ALTITUDE_SOURCE, settings.altitudeSource.name)
            putString(KEY_HUD_PRESET, settings.hudPreset.name)
            putString(KEY_PORTRAIT_FIRST, settings.portraitTiles.first.name)
            putString(KEY_PORTRAIT_SECOND, settings.portraitTiles.second.name)
            putString(KEY_LANDSCAPE_FIRST, settings.landscapeTiles.first.name)
            putString(KEY_LANDSCAPE_SECOND, settings.landscapeTiles.second.name)
            putBoolean(KEY_SHOW_CONTROLS, settings.showControls)
            putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            putFloat(KEY_ZERO_PITCH, settings.zeroPitch)
            putFloat(KEY_ZERO_ROLL, settings.zeroRoll)
            putBoolean(KEY_TRUE_NORTH, settings.trueNorth)
            putString(KEY_REF_WIDTH, settings.referenceLineWidth.name)
            putString(KEY_REF_LENGTH, settings.referenceLineLength.name)

            // 覆写项整批重写，避免残留已删除的键
            prefs.all.keys
                .filter { it.startsWith(PREFIX_OVERRIDE) }
                .forEach { remove(it) }
            settings.paletteOverrides.forEach { (key, color) ->
                putInt(PREFIX_OVERRIDE + key, color)
            }
        }
    }

    private fun String?.toTileType(fallback: TileType): TileType =
        this?.let { name -> TileType.entries.firstOrNull { it.name == name } } ?: fallback

    private companion object {
        const val PREFS_NAME = "speed_settings"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_STYLE = "instrument_style"
        const val KEY_APPEARANCE = "appearance"
        const val KEY_ACCENT = "accent"
        const val KEY_ALTITUDE_SOURCE = "altitude_source"
        const val KEY_HUD_PRESET = "hud_preset"
        const val KEY_PORTRAIT_FIRST = "portrait_first"
        const val KEY_PORTRAIT_SECOND = "portrait_second"
        const val KEY_LANDSCAPE_FIRST = "landscape_first"
        const val KEY_LANDSCAPE_SECOND = "landscape_second"
        const val KEY_SHOW_CONTROLS = "show_controls"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_ZERO_PITCH = "zero_pitch"
        const val KEY_ZERO_ROLL = "zero_roll"
        const val KEY_TRUE_NORTH = "true_north"
        const val KEY_REF_WIDTH = "reference_width"
        const val KEY_REF_LENGTH = "reference_length"
        const val PREFIX_OVERRIDE = "override_"
    }
}
