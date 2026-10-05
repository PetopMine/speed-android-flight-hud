package com.speed.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speed.app.BuildConfig
import kotlin.math.roundToInt

/**
 * 设置界面 —— 现代 Android 开源应用风格：分组标题 + 卡片列表行
 * （标题 + supporting text + 右侧单选/Segmented/Switch 控件）。
 *
 * 分组：外观（明暗/AMOLED/主题色/HUD 配色）· 仪表（高度源/真北/基准线）·
 * 布局（方向/版式/面板）· 校准 · 关于。
 * [isLandscape] 决定"面板分配"里两个槽位的叫法（左/右 还是 上/下）。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    isLandscape: Boolean,
    onOrientationChange: (OrientationMode) -> Unit,
    onStyleChange: (InstrumentStyle) -> Unit,
    onAppearanceChange: (AppearanceMode) -> Unit,
    onAccentChange: (AccentColor) -> Unit,
    onAltitudeSourceChange: (AltitudeSource) -> Unit,
    onTrueNorthChange: (Boolean) -> Unit,
    onReferenceWidthChange: (ReferenceLineWidth) -> Unit,
    onReferenceLengthChange: (ReferenceLineLength) -> Unit,
    onPresetChange: (HudPreset) -> Unit,
    onColorOverride: (HudColorKey, Int?) -> Unit,
    onTileChange: (slotIndex: Int, TileType) -> Unit,
    onShowControlsChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onStartCalibration: () -> Unit,
    onResetCalibration: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("设置", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Chip(text = "完成", selected = true, onClick = onClose)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ---------------- 常用（高频设置在上） ----------------
            Section("常用") {
                ChoiceRow(
                    label = "仪表模式",
                    supporting = when (settings.instrumentStyle) {
                        InstrumentStyle.EFIS ->
                            "航空电子综合显示：中央姿态仪、左右空速/高度带、下方直条航向带。"
                        InstrumentStyle.PANELS ->
                            "屏幕均分两块，每块可自由选择要显示的仪表。"
                    },
                    options = InstrumentStyle.entries.map { it to it.label },
                    selected = settings.instrumentStyle,
                    onSelect = onStyleChange,
                )
                Divider()
                ChoiceRow(
                    label = "屏幕方向",
                    supporting = "「跟随系统」会随设备转动自动切换横竖屏。",
                    options = OrientationMode.entries.map { it to it.label },
                    selected = settings.orientation,
                    onSelect = onOrientationChange,
                )
                Divider()
                SwitchRow(
                    label = "保持屏幕常亮",
                    supporting = "开启后 HUD 显示期间屏幕不会自动熄灭（Window FLAG_KEEP_SCREEN_ON）。",
                    checked = settings.keepScreenOn,
                    onCheckedChange = onKeepScreenOnChange,
                )
                Divider()
                Text(
                    if (settings.isCalibrated) {
                        "当前零点：俯仰 ${settings.zeroPitch.roundToInt()}°，滚转 ${settings.zeroRoll.roundToInt()}°"
                    } else {
                        "当前未校准。"
                    },
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(
                        text = "开始校准",
                        selected = true,
                        onClick = onStartCalibration,
                    )
                    Chip(
                        text = "清除校准",
                        selected = false,
                        onClick = onResetCalibration,
                    )
                }
                Hint("点击「开始校准」会回到仪表界面，在没放姿态仪的那一侧显示校准面板；点「设为水平」立即把当前姿态设为零点。")
                Divider()
                SwitchRow(
                    label = "显示操作按钮",
                    supporting = "关闭后仪表全屏无遮挡，长按屏幕任意位置可重新打开设置。",
                    checked = settings.showControls,
                    onCheckedChange = onShowControlsChange,
                )
            }

            // ---------------- 仪表 ----------------
            Section("仪表") {
                ChoiceRow(
                    label = "高度数据源",
                    supporting = "选择哪个就只用哪个；该来源无数据时显示 --。升降率始终由气压计计算。",
                    options = AltitudeSource.entries.map { it to it.label },
                    selected = settings.altitudeSource,
                    onSelect = onAltitudeSourceChange,
                )
                Divider()
                SwitchRow(
                    label = "真北",
                    supporting = "开启后航向叠加磁偏角（东偏为正）。无定位权限或位置时保持磁北，不伪造真北。",
                    checked = settings.trueNorth,
                    onCheckedChange = onTrueNorthChange,
                )
                Divider()
                val slotLabels = if (isLandscape) listOf("左半屏", "右半屏") else listOf("上半屏", "下半屏")
                slotLabels.forEachIndexed { index, label ->
                    SubLabel("$label 面板")
                    val current = settings.tilesFor(isLandscape).forSlot(index)
                    ChoiceRowWrap(
                        // 校准是设置里的独立功能，不再作为可选的仪表出现
                        options = TileType.entries
                            .filter { it != TileType.CALIBRATE }
                            .map { it to it.label },
                        selected = current,
                        onSelect = { onTileChange(index, it) },
                    )
                    if (index == 0) Spacer(Modifier.height(6.dp))
                }
                Hint("横屏和竖屏的面板配置是各自独立的，切换方向时会分别生效。")
            }

            // ---------------- HUD ----------------
            Section("HUD") {
                ChoiceRow(
                    label = "基准线粗细",
                    supporting = "作用于 HUD 中央基准线的笔画宽度。",
                    options = ReferenceLineWidth.entries.map { it to it.label },
                    selected = settings.referenceLineWidth,
                    onSelect = onReferenceWidthChange,
                )
                Divider()
                ChoiceRow(
                    label = "基准线长度",
                    supporting = "作用于 HUD 中央基准线两侧横线的长度。",
                    options = ReferenceLineLength.entries.map { it to it.label },
                    selected = settings.referenceLineLength,
                    onSelect = onReferenceLengthChange,
                )
                Divider()
                SubLabel("HUD 配色预设")
                ChoiceRowWrap(
                    options = HudPreset.entries.map { it to it.label },
                    selected = settings.hudPreset,
                    onSelect = onPresetChange,
                )
                Hint("切换预设后，未被单独改过的颜色项会跟随新预设。")

                Divider()
                SubLabel("自定义颜色")
                // 基准线颜色只在这里（HudColorKey.REFERENCE）出现 —— 唯一入口。
                var expandedKey by remember { mutableStateOf<HudColorKey?>(null) }
                HudColorKey.entries.forEach { key ->
                    ColorOverrideRow(
                        key = key,
                        currentColor = settings.palette.colorFor(key),
                        isOverridden = settings.paletteOverrides.containsKey(key.name),
                        expanded = expandedKey == key,
                        onToggle = { expandedKey = if (expandedKey == key) null else key },
                        onPick = { onColorOverride(key, it) },
                        onReset = { onColorOverride(key, null) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Chip(
                    text = "恢复全部自定义",
                    selected = false,
                    onClick = { HudColorKey.entries.forEach { onColorOverride(it, null) } },
                )
            }

            // ---------------- 外观（低频设置在下） ----------------
            Section("外观") {
                ChoiceRow(
                    label = "明暗模式",
                    supporting = "AMOLED 暗色使用纯黑背景，适合 OLED 屏幕与夜间 HUD。",
                    options = AppearanceMode.entries.map { it to it.label },
                    selected = settings.appearance,
                    onSelect = onAppearanceChange,
                )
                Divider()
                SubLabel("主题色")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccentColor.entries.forEach { accent ->
                        AccentSwatch(
                            accent = accent,
                            selected = settings.accent == accent,
                            onClick = { onAccentChange(accent) },
                        )
                    }
                }
                Hint("主题色只影响设置界面与控件，不会改变仪表的天地配色。")
            }

            // ---------------- 关于 ----------------
            Section("关于") {
                Text("Speed 姿态仪表", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Hint("版本 " + BuildConfig.VERSION_NAME)
                Hint("编译时间 " + BuildConfig.BUILD_TIME)
                Hint("署名 System_WinNT4.9 & Deepseek")
                Hint("邮箱 petop_mine@outlook.com")
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// 基础控件。全部手写，避免为一个设置页引入整套 Material 依赖。
// ---------------------------------------------------------------------------

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            title,
            color = colors.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Divider() {
    val colors = LocalAppColors.current
    Spacer(Modifier.height(10.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.border),
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun SubLabel(text: String) {
    val colors = LocalAppColors.current
    Text(text, color = colors.textSecondary, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Hint(text: String) {
    val colors = LocalAppColors.current
    Spacer(Modifier.height(8.dp))
    Text(text, color = colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .background(
                color = if (selected) colors.accent else colors.surfaceVariant,
                shape = RoundedCornerShape(50),
            )
            .border(
                width = 1.dp,
                color = if (selected) colors.accent else colors.border,
                shape = RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = if (selected) colors.onAccent else colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * 列表行：左侧标题 + supporting text，右侧单选按钮组（Segmented）。
 * 现代开源 App 设置页的标准行样式。
 */
@Composable
private fun <T> ChoiceRow(
    label: String,
    supporting: String? = null,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val colors = LocalAppColors.current
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, labelText) ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .background(
                            color = if (isSelected) colors.accent else colors.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) colors.accent else colors.border,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable { onSelect(value) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = labelText,
                        color = if (isSelected) colors.onAccent else colors.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        if (supporting != null) {
            Spacer(Modifier.height(4.dp))
            Text(supporting, color = colors.textSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

/** 会自动换行的选项组，选项多时用。 */
@Composable
private fun <T> ChoiceRowWrap(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(4).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { (value, label) ->
                    Chip(text = label, selected = value == selected, onClick = { onSelect(value) })
                }
            }
        }
    }
}

/** 开关行：左侧标题 + supporting text，右侧 Material3 Switch。 */
@Composable
private fun SwitchRow(
    label: String,
    supporting: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (supporting != null) {
                Spacer(Modifier.height(2.dp))
                Text(supporting, color = colors.textSecondary, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.textSecondary,
                uncheckedTrackColor = colors.surfaceVariant,
                uncheckedBorderColor = colors.border,
            ),
        )
    }
}

@Composable
private fun AccentSwatch(accent: AccentColor, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(accent.value, CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) colors.textPrimary else colors.border,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

/** 一项颜色的自定义行：左侧名称与当前色块，点开后展开候选色板。 */
@Composable
private fun ColorOverrideRow(
    key: HudColorKey,
    currentColor: Color,
    isOverridden: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPick: (Int) -> Unit,
    onReset: () -> Unit,
) {
    val colors = LocalAppColors.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(key.label, color = colors.textPrimary, fontSize = 14.sp)
            if (isOverridden) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "已改",
                    color = colors.accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(currentColor, RoundedCornerShape(6.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(6.dp)),
            )
        }

        if (expanded) {
            Column {
                for (row in HUD_COLOR_CHOICES.chunked(8)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { color ->
                            val isCurrent = currentColor.toArgb() == color.toArgb()
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(color, RoundedCornerShape(6.dp))
                                    .border(
                                        width = if (isCurrent) 3.dp else 1.dp,
                                        color = if (isCurrent) colors.accent else colors.border,
                                        shape = RoundedCornerShape(6.dp),
                                    )
                                    // 保存为 ARGB：Color.value 是 Compose 内部的 packed sRGB 编码，
                                    // 直接 toInt() 截断后不是 ARGB，恢复时 Color(argb) 会解出
                                    // 错乱/透明的颜色（历史 bug）。toArgb() 才是正确的序列化。
                                    .clickable { onPick(color.toArgb()) },
                            )
                        }
                    }
                }
                if (isOverridden) {
                    Chip(text = "恢复此项为预设", selected = false, onClick = onReset)
                }
            }
        }
    }
}
