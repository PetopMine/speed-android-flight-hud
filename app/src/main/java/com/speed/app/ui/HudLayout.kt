package com.speed.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speed.app.data.FlightData
import com.speed.app.instruments.AltitudeTile
import com.speed.app.instruments.AttitudeTile
import com.speed.app.instruments.CalibrateTile
import com.speed.app.instruments.HeadingTile
import com.speed.app.instruments.ReadoutTile
import com.speed.app.instruments.SpeedTile
import com.speed.app.settings.HudPalette
import com.speed.app.settings.InstrumentStyle
import com.speed.app.settings.ReferenceLineLength
import com.speed.app.settings.ReferenceLineWidth
import com.speed.app.settings.TileType

/** 仪表区背景色（EFIS 四周留白和双面板空白面板都用它）。 */
private val PanelBackground = Color(0xFF0B1017)

/**
 * HUD 主布局。两种版式：
 *
 *  - [InstrumentStyle.PANELS]：屏幕均分两块，每块自选仪表（竖屏上下分、横屏左右分）。
 *    特例：**两块都选了姿态仪**时不再独立显示两块，而是合并成同一块 HUD
 *    （横屏整块全屏，竖屏只保留上方一块、下方显示"待配置"占位）。
 *  - [InstrumentStyle.EFIS]：EFIS 综合显示。
 *    竖屏时按**方形**渲染在屏幕中间；横屏时左侧方形 EFIS、右侧自定义面板。
 *
 * 校准时（[calibrationVisible]）：校准面板显示在**没有姿态仪的那一侧**
 * （两块都是姿态仪时显示在右侧/下方），关闭后自动恢复原配置的仪表。
 */
@Composable
fun HudLayout(
    isLandscape: Boolean,
    firstTile: TileType,
    secondTile: TileType,
    instrumentStyle: InstrumentStyle,
    pitch: Float,
    roll: Float,
    azimuth: Float,
    flightData: FlightData,
    palette: HudPalette,
    zeroPitch: Float,
    zeroRoll: Float,
    referenceLineWidth: ReferenceLineWidth,
    referenceLineLength: ReferenceLineLength,
    calibrationVisible: Boolean,
    onApplyCalibration: () -> Unit,
    onResetCalibration: () -> Unit,
    onCloseCalibration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 两块之间的细分隔线，颜色跟地平线一致，视觉上不突兀
    val divider = palette.line.copy(alpha = 0.25f)

    @Composable
    fun tileContent(tileType: TileType, slotModifier: Modifier) {
        TileContent(
            type = tileType,
            pitch = pitch,
            roll = roll,
            azimuth = azimuth,
            flightData = flightData,
            palette = palette,
            zeroPitch = zeroPitch,
            zeroRoll = zeroRoll,
            referenceLineWidth = referenceLineWidth,
            referenceLineLength = referenceLineLength,
            onApplyCalibration = onApplyCalibration,
            onResetCalibration = onResetCalibration,
            onCloseCalibration = onCloseCalibration,
            modifier = slotModifier,
        )
    }

    if (instrumentStyle == InstrumentStyle.EFIS) {
        if (isLandscape) {
            // 横屏：左侧 EFIS 本体**至少占屏幕中线**（50% 宽度），右侧自定义功能面板
            // 拿剩余空间。EFIS 内部比例动态自适应（不再锁方形），
            // 姿态球/航向带/读数行按实际宽高分配，不重叠不变形。
            Row(modifier = modifier.fillMaxSize().background(PanelBackground)) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    EfisLayout(
                        pitch = pitch,
                        roll = roll,
                        azimuth = azimuth,
                        flightData = flightData,
                        palette = palette,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(Modifier.width(1.dp).fillMaxSize().background(divider)) {}
                val rightTile = if (calibrationVisible) TileType.CALIBRATE else secondTile
                tileContent(rightTile, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // 竖屏：方形 EFIS 居中，校准面板占下方剩余区域
            if (calibrationVisible) {
                Column(modifier = modifier.fillMaxSize().background(PanelBackground)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                    ) {
                        EfisLayout(
                            pitch = pitch,
                            roll = roll,
                            azimuth = azimuth,
                            flightData = flightData,
                            palette = palette,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Row(Modifier.height(1.dp).fillMaxWidth().background(divider)) {}
                    tileContent(TileType.CALIBRATE, Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                Box(
                    modifier = modifier.fillMaxSize().background(PanelBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        EfisLayout(
                            pitch = pitch,
                            roll = roll,
                            azimuth = azimuth,
                            flightData = flightData,
                            palette = palette,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        return
    }

    // ------------------------------------------------------------------
    // 双面板版式。
    // ------------------------------------------------------------------

    // 两块都选了姿态仪：合并成同一块 HUD
    val bothAttitude = firstTile == TileType.ATTITUDE && secondTile == TileType.ATTITUDE

    if (bothAttitude && !calibrationVisible) {
        if (isLandscape) {
            // 横屏：整块合并显示一个姿态仪（比例由面板自适应，刻度/文字不拉伸）
            tileContent(TileType.ATTITUDE, modifier.fillMaxSize())
        } else {
            // 竖屏：上方显示姿态仪，下方显示"待配置"占位
            Column(modifier = modifier.fillMaxSize()) {
                tileContent(TileType.ATTITUDE, Modifier.weight(1f).fillMaxWidth())
                Row(Modifier.height(1.dp).fillMaxWidth().background(divider)) {}
                UnconfiguredPanel(
                    palette = palette,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
        return
    }

    // 校准面板占据没有姿态仪的那一侧；两块都是姿态仪时显示在右侧/下方。
    val calSlot = when {
        firstTile != TileType.ATTITUDE -> 0
        secondTile != TileType.ATTITUDE -> 1
        else -> 1
    }
    val displayFirst = if (calibrationVisible && calSlot == 0) TileType.CALIBRATE else firstTile
    val displaySecond = if (calibrationVisible && calSlot == 1) TileType.CALIBRATE else secondTile

    if (isLandscape) {
        Row(modifier = modifier.fillMaxSize()) {
            // 只给 weight，不要再叠 fillMaxSize()：
            // weight 已经隐含"填满分配到的空间"，而 fillMaxSize() 会把子项撑满
            // **父容器的全部尺寸**，覆盖掉 weight 分到的那一半，
            // 导致面板内部坐标系按整屏计算、实际只显示一半（刻度比例整体错 2 倍）。
            tileContent(displayFirst, Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(1.dp).fillMaxSize().background(divider)) {}
            tileContent(displaySecond, Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            tileContent(displayFirst, Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.height(1.dp).fillMaxWidth().background(divider)) {}
            tileContent(displaySecond, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** "待配置"占位面板：竖屏且两块都选了姿态仪时，下方显示这个提示。 */
@Composable
private fun UnconfiguredPanel(palette: HudPalette, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(PanelBackground),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "待配置",
            color = palette.text.copy(alpha = 0.35f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 按类型渲染单块面板。 */
@Composable
private fun TileContent(
    type: TileType,
    pitch: Float,
    roll: Float,
    azimuth: Float,
    flightData: FlightData,
    palette: HudPalette,
    zeroPitch: Float,
    zeroRoll: Float,
    referenceLineWidth: ReferenceLineWidth,
    referenceLineLength: ReferenceLineLength,
    onApplyCalibration: () -> Unit,
    onResetCalibration: () -> Unit,
    onCloseCalibration: () -> Unit,
    modifier: Modifier,
) {
    when (type) {
        TileType.ATTITUDE -> AttitudeTile(
            pitch = pitch,
            roll = roll,
            palette = palette,
            referenceWidth = referenceLineWidth,
            referenceLength = referenceLineLength,
            modifier = modifier,
        )

        TileType.SPEED -> SpeedTile(
            speedMetersPerSecond = flightData.speedMetersPerSecond,
            palette = palette,
            modifier = modifier,
        )

        TileType.ALTITUDE -> AltitudeTile(
            flightData = flightData,
            palette = palette,
            modifier = modifier,
        )

        TileType.HEADING -> HeadingTile(
            azimuth = azimuth,
            palette = palette,
            modifier = modifier,
        )

        TileType.READOUT -> ReadoutTile(
            pitch = pitch,
            roll = roll,
            azimuth = azimuth,
            flightData = flightData,
            palette = palette,
            modifier = modifier,
        )

        TileType.CALIBRATE -> CalibrateTile(
            zeroPitch = zeroPitch,
            zeroRoll = zeroRoll,
            onApply = onApplyCalibration,
            onReset = onResetCalibration,
            onClose = onCloseCalibration,
            palette = palette,
            modifier = modifier,
        )

        TileType.BLANK -> Box(modifier = modifier.background(PanelBackground))
    }
}
