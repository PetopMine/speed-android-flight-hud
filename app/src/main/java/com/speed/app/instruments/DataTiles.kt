package com.speed.app.instruments

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speed.app.data.FlightData
import com.speed.app.settings.AltitudeSource
import com.speed.app.settings.HudPalette
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val NO_DATA = "--"

/**
 * 面板底色。
 *
 * 姿态仪靠天地色块自己就能铺满，但速度带 / 高度带 / 航向带 / 数字读数没有背景，
 * 叠在纯色画布上会显得空，所以统一给一层深色底衬。
 * 刻意不跟随主题明暗：仪表区要保证在任何情况下都是高对比度的。
 */
private val PanelBackground = Color(0xFF0B1017)

/** 给非姿态仪的面板统一铺底。 */
@Composable
private fun InstrumentSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = PanelBackground, topLeft = Offset.Zero, size = size)
        }
        content()
    }
}

/**
 * 速度面板：把地速作为**大数字**显示，不画任何刻度。
 *
 * 半屏的面板里画刻度带其实很挤，而地速只需要看一个数，
 * 所以这里只留数字 + 单位，尺寸按面板短边自适应。
 */
@Composable
fun SpeedTile(
    speedMetersPerSecond: Float?,
    palette: HudPalette,
    modifier: Modifier = Modifier,
) {
    InstrumentSurface(modifier) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "地速",
                    color = palette.text.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = speedMetersPerSecond
                        ?.let { (it * 3.6f).roundToInt().toString() }
                        ?: NO_DATA,
                    color = palette.text,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 104.sp,
                )
                Text(
                    text = "km/h",
                    color = palette.text.copy(alpha = 0.75f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** 高度带：显示海拔与升降率。 */
@Composable
fun AltitudeTile(
    flightData: FlightData,
    palette: HudPalette,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val paints = remember(density) { InstrumentPaints(density) }
    val ticks = remember { buildTicks(minValue = -500, maxValue = 11000, step = 100, labelStep = 1000, majorStep = 500) }

    InstrumentSurface(modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@Canvas

            val altitude = flightData.altitudeMeters
            if (altitude == null) {
                drawNoData(paints, palette, width, height)
                return@Canvas
            }

            val rect = TapeRect(
                left = width * 0.06f,
                top = height * 0.08f,
                width = width * 0.40f,
                height = height * 0.78f,
                visibleRange = 900f,
                minValue = -500f,
                maxValue = 11000f,
            )
            drawTapeStrip(paints, palette, rect, ticks, altitude, "m")

            val readoutLeft = rect.right + width * 0.06f
            paints.setTextColor(paints.valueLarge, palette.text)
            paints.valueLarge.textAlign = android.graphics.Paint.Align.LEFT
            // 读数**明确带单位 m**（用户要求高度带显示单位，不是只在设置/说明里）
            drawContext.canvas.nativeCanvas.drawText(
                "${altitude.roundToInt()} m",
                readoutLeft,
                height * 0.40f,
                paints.valueLarge,
            )
            paints.setTextColor(paints.caption, palette.text.copy(alpha = 0.7f))
            drawContext.canvas.nativeCanvas.drawText(
                when (flightData.altitudeSource) {
                    AltitudeSource.BAROMETER -> "海拔 m · 气压"
                    AltitudeSource.GPS -> "海拔 m · GPS"
                },
                readoutLeft,
                height * 0.40f + paints.valueLarge.textSize * 0.95f,
                paints.caption,
            )

            // 升降率：正负号很关键，用箭头加数字一起表达。
            // 升降率**与高度来源同源**：BAROMETER → 气压高度差分；GPS → GPS 高度差分。
            val verticalSpeed = flightData.verticalSpeedMetersPerSecond
            val verticalText = if (verticalSpeed == null) {
                "$NO_DATA m/s"
            } else {
                val arrow = when {
                    verticalSpeed > 0.3f -> "▲"
                    verticalSpeed < -0.3f -> "▼"
                    else -> "—"
                }
                "$arrow ${String.format("%.1f", abs(verticalSpeed))} m/s"
            }
            val verticalBaseline = height * 0.40f + paints.valueLarge.textSize * 2.0f
            paints.setTextColor(paints.value, palette.pointer)
            drawContext.canvas.nativeCanvas.drawText(
                verticalText,
                readoutLeft,
                verticalBaseline,
                paints.value,
            )
            paints.setTextColor(paints.caption, palette.text.copy(alpha = 0.55f))
            drawContext.canvas.nativeCanvas.drawText(
                when (flightData.altitudeSource) {
                    AltitudeSource.BAROMETER -> if (flightData.hasBarometer) "升降率 气压" else "升降率 --"
                    AltitudeSource.GPS -> "升降率 GPS"
                },
                readoutLeft,
                verticalBaseline + paints.readoutLineHeight,
                paints.caption,
            )
            paints.valueLarge.textAlign = android.graphics.Paint.Align.CENTER
        }
    }
}

/**
 * 航向带：**直线刻度**的横向滚动罗盘条。
 *
 * 与 EFIS 模式共用同一绘制原语（[drawHeadingStrip]）与同一个 canonical heading：
 * 两个 renderer 显示同一个数据。布局（按用户要求）：
 *  - 刻度区域**贴近面板顶部**（动态按密度留 4dp 安全边距，不写死大片 padding）
 *  - **无独立底衬**：背景就是面板背景（HUD 背景），不允许色差
 *  - 条带横向覆盖 ±60°，每 30° 长刻度、每 10° 中刻度、每 5° 短刻度
 */
@Composable
fun HeadingTile(
    azimuth: Float,
    palette: HudPalette,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val paints = remember(density) { InstrumentPaints(density) }

    var panelWidth by remember { mutableStateOf(0f) }
    var panelHeight by remember { mutableStateOf(0f) }

    InstrumentSurface(modifier) {
        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onSizeChanged {
                        panelWidth = it.width.toFloat()
                        panelHeight = it.height.toFloat()
                    },
            ) {
                val width = size.width
                val height = size.height
                if (width <= 0f || height <= 0f) return@Canvas

                // 贴顶：只留 4dp 安全边距（按密度换算，适配刘海与不同屏幕比例）
                val stripTop = 4f * density
                val stripHeight = height * 0.30f
                val stripLeft = width * 0.02f
                val stripRight = width * 0.98f
                val centerX = width / 2f

                // 共享刻度条（无底衬）
                drawHeadingStrip(
                    paints = paints,
                    palette = palette,
                    left = stripLeft,
                    top = stripTop,
                    stripWidth = stripRight - stripLeft,
                    stripHeight = stripHeight,
                    azimuth = azimuth,
                )

                // 中央固定指针（共享原语），指向当前航向
                drawHeadingPointer(
                    paints = paints,
                    palette = palette,
                    centerX = centerX,
                    stripTop = stripTop,
                    stripHeight = stripHeight,
                )

                // 大字读数：位于**模块中部**（刻度在顶部，读数不与刻度重叠；
                // 用户要求当前航向数字在模块视觉中心，不再贴近底部）。
                val heading = azimuth.roundToInt().let { if (it >= 360) 0 else it }
                paints.setTextColor(paints.valueLarge, palette.text)
                drawContext.canvas.nativeCanvas.drawText(
                    "$heading°",
                    centerX,
                    height * 0.55f,
                    paints.valueLarge,
                )
            }

            // 方位标签：独立文本层，位置用连续角度算，与刻度同源
            if (panelWidth > 0f && panelHeight > 0f) {
                HeadingLabels(
                    azimuth = azimuth,
                    width = panelWidth,
                    height = panelHeight,
                    color = palette.tapeTick,
                )
            }
        }
    }
}

/** 航向带上每 45° 一个的方位标签，位置与刻度线同源（连续角度）。 */
@Composable
private fun HeadingLabels(
    azimuth: Float,
    width: Float,
    height: Float,
    color: Color,
) {
    val density = LocalDensity.current
    val stripTop = 4f * density.density
    val stripHeight = height * 0.30f
    val centerX = width / 2f
    val pixelsPerDegree = width / 120f
    val labelY = stripTop + stripHeight * 0.76f

    // 把当前航向向下取整到 45° 的整数倍，作为标签枚举起点（8 方位：N/NE/E/SE/S/SW/W/NW）。
    // 与刻度线用同一套定位公式（(绝对度 - 航向) * 每度像素），保证标签压在对应刻度上。
    val base = kotlin.math.floor(azimuth / 45f).toInt() * 45

    for (step in -3..3) {
        val absolute = base + step * 45
        val x = centerX + (absolute - azimuth) * pixelsPerDegree
        if (x < 40f || x > width - 40f) continue

        val rounded = ((absolute % 360) + 360) % 360
        val text = azimuthLabel(rounded)

        var textWidthPx by remember(text) { mutableStateOf(0) }
        with(density) {
            Text(
                text = text,
                color = color,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                onTextLayout = { r -> if (r.size.width != textWidthPx) textWidthPx = r.size.width },
                modifier = Modifier.offset(
                    x = (x - textWidthPx / 2f).toDp(),
                    y = (labelY - 10f * density.density).toDp(),
                ),
            )
        }
    }
}


/** 数字读数面板：不需要看刻度时，一屏把关键数据全列出来。 */
@Composable
fun ReadoutTile(
    pitch: Float,
    roll: Float,
    azimuth: Float,
    flightData: FlightData,
    palette: HudPalette,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val paints = remember(density) { InstrumentPaints(density) }

    InstrumentSurface(modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@Canvas

            val rows = listOf(
                "俯仰 PITCH" to formatSigned(pitch, "°"),
                "滚转 ROLL" to formatSigned(roll, "°"),
                "航向 HDG" to "${azimuth.roundToInt().let { if (it >= 360) 0 else it }}°",
                "地速 GS" to (flightData.speedMetersPerSecond?.let { "${(it * 3.6f).roundToInt()} km/h" } ?: NO_DATA),
                "海拔 ALT" to (flightData.altitudeMeters?.let { "${it.roundToInt()} m" } ?: NO_DATA),
                "升降率 V/S" to (flightData.verticalSpeedMetersPerSecond?.let { formatSigned(it, " m/s") } ?: NO_DATA),
            )

            // 竖排两列，面板越扁列越多，尽量用满空间
            val columns = if (width > height * 1.4f) 2 else 1
            val rowsPerColumn = (rows.size + columns - 1) / columns
            val columnWidth = width / columns

            // 行基线由纯函数按面板高度动态排布：上下各留安全边距（底部手势区/圆角），
            // 行槽等高、内容居中 —— 整体落在安全区内，最底一行不会被屏幕裁掉。
            val baselines = readoutRowBaselines(
                height = height,
                rowsPerColumn = rowsPerColumn,
                captionSize = paints.caption.textSize,
                valueSize = paints.value.textSize,
            )

            val nativeCanvas = drawContext.canvas.nativeCanvas
            rows.forEachIndexed { index, (caption, value) ->
                val column = index / rowsPerColumn
                val row = index % rowsPerColumn
                val left = column * columnWidth + width * 0.06f
                val baseline = baselines.getOrNull(row) ?: return@forEachIndexed

                paints.setTextColor(paints.caption, palette.text.copy(alpha = 0.65f))
                nativeCanvas.drawText(caption, left, baseline.captionBaseline, paints.caption)

                paints.setTextColor(paints.value, palette.text)
                nativeCanvas.drawText(
                    value,
                    left,
                    baseline.valueBaseline,
                    paints.value,
                )
            }
        }
    }
}

/** 带符号的数值：正数显式加 +，读姿态时更直观。 */
private fun formatSigned(value: Float, suffix: String): String {
    val rounded = value.roundToInt()
    val sign = if (rounded > 0) "+" else ""
    return "$sign$rounded$suffix"
}

/**
 * 校准面板：**瞬时校准** —— 显示校准按钮和说明，不再有"按下后等待测量"的流程。
 *
 * 用法：把手机放到想要视为"水平"的姿态上，点「设为水平」，
 * 当前俯仰/滚转立即成为零点（持久化）。
 * 陀螺零偏由传感器里的连续残余估计持续修正，不再需要单独采样等待。
 *
 * 入口在设置里；面板显示在**没有姿态仪的那一侧**（两侧都是姿态仪时显示在右侧/下方），
 * 点「关闭」后该面板自动恢复显示原来配置的仪表。
 */
@Composable
fun CalibrateTile(
    zeroPitch: Float,
    zeroRoll: Float,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    palette: HudPalette,
    modifier: Modifier = Modifier,
) {
    InstrumentSurface(modifier) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "姿态校准",
                color = palette.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "把手机放在需要视为「水平」的姿态上，\n点击「设为水平」，当前姿态立即成为零点。",
                color = palette.text.copy(alpha = 0.78f),
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "当前零点：俯仰 ${formatSigned(zeroPitch, "°")}　滚转 ${formatSigned(zeroRoll, "°")}",
                color = palette.text.copy(alpha = 0.62f),
                fontSize = 13.sp,
            )

            Spacer(Modifier.height(22.dp))

            PillAction(
                label = "设为水平",
                onClick = onApply,
                background = palette.pointer,
                contentColor = Color(0xFF10141A),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillAction(
                    label = "清除校准",
                    onClick = onReset,
                    background = palette.tapeBack,
                    contentColor = palette.text,
                )
                PillAction(
                    label = "关闭",
                    onClick = onClose,
                    background = palette.tapeBack,
                    contentColor = palette.text,
                )
            }
        }
    }
}

/** 标定时长（秒）。太短零偏估计不准，太长用户等得烦。 */
const val CALIBRATION_SECONDS = 3

/** 校准面板里的按钮。手写样式，跟设置界面里的控件保持一致，不引入 Material 主题。 */
@Composable
private fun PillAction(
    label: String,
    onClick: () -> Unit,
    background: Color,
    contentColor: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = contentColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 没有数据时统一画一个占位提示。 */
private fun DrawScope.drawNoData(
    paints: InstrumentPaints,
    palette: HudPalette,
    width: Float,
    height: Float,
) {
    paints.setTextColor(paints.valueLarge, palette.text.copy(alpha = 0.5f))
    drawContext.canvas.nativeCanvas.drawText(
        NO_DATA,
        width / 2f,
        height / 2f,
        paints.valueLarge,
    )
}
