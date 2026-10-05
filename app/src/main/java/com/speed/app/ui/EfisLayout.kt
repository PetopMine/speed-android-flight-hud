package com.speed.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speed.app.data.FlightData
import com.speed.app.instruments.EfisAttitude
import com.speed.app.instruments.EfisTapeSide
import com.speed.app.instruments.InstrumentPaints
import com.speed.app.instruments.TickWeight
import com.speed.app.instruments.azimuthLabel
import com.speed.app.instruments.drawHeadingPointer
import com.speed.app.instruments.drawHeadingStrip
import com.speed.app.instruments.efisTapeLayout
import com.speed.app.instruments.tapeTickScreenY
import com.speed.app.instruments.trianglePath
import com.speed.app.settings.HudPalette
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * EFIS 综合仪表布局：一整块航空电子风格的显示器。
 *
 * 版式（与原双面板版式并列，可在设置里切换）：
 *
 * ```
 * ┌────┬──────────────────────┬────┬───┐
 * │空速│   姿态仪（圆角矩形）  │高度│升 │
 * │ 带 │  顶部滚转刻度        │ 带 │降 │
 * │    │                      │    │率 │
 * ├────┴──────────────────────┴────┴───┤
 * │        罗盘弧（半圆刻度带）          │
 * ├─────────────────────────────────────┤
 * │           数字读数行                │
 * └─────────────────────────────────────┘
 * ```
 *
 * 与"两块面板各自选仪表"的版式互不干扰：两者共用手势、校准、配色等基础设施。
 */
@Composable
fun EfisLayout(
    pitch: Float,
    roll: Float,
    azimuth: Float,
    flightData: FlightData,
    palette: HudPalette,
    modifier: Modifier = Modifier,
) {
    val densityScale = LocalDensity.current.density
    val paints = remember(densityScale) { InstrumentPaints(densityScale) }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { if (it != canvasSize) canvasSize = it },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@Canvas

            // 版式比例：左右各一条刻度带（速度 / 高度），中央是姿态球，
            // 下方是航向窄条，最底是数字读数行。
            //
            // 高度分配（动态）：航向窄条 26% + 底部读数 9%，其余全部给中央姿态 HUD。
            // 单侧带宽度由 EFIS_SIDE_TAPE_FRACTION 统一决定（数据框+指针+刻度三段都要放得下）。
            val leftTapeW = width * EFIS_SIDE_TAPE_FRACTION
            val altTapeW = width * EFIS_SIDE_TAPE_FRACTION
            val headingH = height * 0.26f
            val readoutH = height * 0.09f
            val mainH = height - headingH - readoutH

            val leftTape = Rect(0f, 0f, leftTapeW, mainH)
            val rightTape = Rect(width - altTapeW, 0f, width, mainH)
            val attitude = Rect(leftTapeW, 0f, width - altTapeW, mainH)
            val headingRect = Rect(leftTapeW, mainH, width - altTapeW, height - readoutH)
            val readout = Rect(0f, height - readoutH, width, height)

            // 1：空速带（左）—— 结构：[数据框][▶][刻度]（刻度贴 EFIS 本体）
            drawEfisTape(
                paints = paints,
                palette = palette,
                rect = leftTape,
                side = EfisTapeSide.LEFT,
                value = flightData.speedMetersPerSecond?.let { it * 3.6f },
                unit = "km/h",
                range = 40f,
                stepMinor = 5f,
                stepMajor = 10f,
            )

            // 2：高度带（右）—— 结构：[刻度][◀][数据框]（刻度贴 EFIS 本体）
            drawEfisTape(
                paints = paints,
                palette = palette,
                rect = rightTape,
                side = EfisTapeSide.RIGHT,
                value = flightData.altitudeMeters,
                unit = "m",
                range = 300f,
                stepMinor = 25f,
                stepMajor = 50f,
            )

            // 3：航向带（下方中央）—— **直条式**，与模块模式共用同一刻度原语
            //    （drawHeadingStrip）与同一个 canonical heading；不再使用弧形罗盘。
            drawEfisHeadingStrip(paints, palette, headingRect, azimuth)

            // 5：底部分隔线
            drawLine(
                color = palette.line.copy(alpha = 0.30f),
                start = Offset(0f, readout.top),
                end = Offset(width, readout.top),
                strokeWidth = 2f,
            )
        }

        // 6：姿态仪作为独立图层叠在中间（需要自己的圆角裁剪与旋转）。
        //    尺寸用上一步测得的画布像素值换算成 dp —— px 转 dp 需要 Density 接收者。
        if (canvasSize.width > 0) {
            val width = canvasSize.width.toFloat()
            val height = canvasSize.height.toFloat()
            val leftTapeW = width * EFIS_SIDE_TAPE_FRACTION
            val rightW = width * EFIS_SIDE_TAPE_FRACTION
            val headingH = height * 0.26f
            val readoutH = height * 0.09f

            // px -> dp 用密度直接换算。
            // 不用 `Density.toDp()`：它是 Density 接口的成员扩展，导入路径很容易踩空。
            val pxToDp: (Float) -> Dp = { px -> (px / densityScale).dp }

            EfisAttitude(
                pitch = pitch,
                roll = roll,
                palette = palette,
                modifier = Modifier
                    .offset(x = pxToDp(leftTapeW), y = 0.dp)
                    .width(pxToDp(width - leftTapeW - rightW))
                    .height(pxToDp(height - headingH - readoutH)),
            )

            EfisReadoutRow(
                flightData = flightData,
                palette = palette,
                modifier = Modifier
                    .offset(y = pxToDp(height - readoutH))
                    .fillMaxWidth()
                    .height(pxToDp(readoutH)),
            )
        }
    }
}

/** 内部用的矩形（避免与 Compose 的 Rect 名字冲突）。 */
private data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * 单侧刻度带占 EFIS 本体的宽度比例。
 *
 * 0.19 是"数据框 + 指针 + 刻度"三段都能放下的最小值（数据框要能容纳 4 位数字），
 * 左右各 0.19 后中间姿态区仍占 62%，同时保证横屏 EFIS 本体 ≥ 屏幕中线。
 */
private const val EFIS_SIDE_TAPE_FRACTION = 0.19f

/**
 * EFIS 样式的竖直刻度带 —— 三段结构，**互不重叠**：
 *
 * - 速度带（[EfisTapeSide.LEFT]）：`[数据框][▶][刻度]`（刻度贴 EFIS 本体）
 * - 高度带（[EfisTapeSide.RIGHT]）：`[刻度][◀][数据框]`（刻度贴 EFIS 本体）
 *
 * 运动规则：**数据数字与黄色指针固定不动**（都锚在带子垂直中心），只有刻度线随
 * 数值移动；数值增大 → 刻度向下（[tapeTickScreenY]）。
 * 指针尖角始终朝向自己的刻度，且位于数据框**之外**。
 */
private fun DrawScope.drawEfisTape(
    paints: InstrumentPaints,
    palette: HudPalette,
    rect: Rect,
    side: EfisTapeSide,
    value: Float?,
    unit: String,
    range: Float,
    stepMinor: Float,
    stepMajor: Float,
) {
    if (rect.width <= 0f || rect.height <= 0f) return

    val layout = efisTapeLayout(
        androidx.compose.ui.geometry.Rect(rect.left, rect.top, rect.right, rect.bottom),
        side,
    )
    val centerY = rect.top + rect.height / 2f
    val tape = layout.tapeRect

    // 1：刻度线 —— 贴 EFIS 本体一侧生长，随数值移动（数值增大 → 向下）
    val pixelsPerUnit = rect.height / (range * 2f)
    val safeValue = value ?: 0f
    val first = floor((safeValue - range) / stepMinor).toInt() * stepMinor.toInt()
    val tickGrow = if (side == EfisTapeSide.LEFT) -1f else 1f
    var tickValue = first.toFloat()
    while (tickValue <= safeValue + range) {
        val y = tapeTickScreenY(centerY, tickValue, safeValue, pixelsPerUnit)
        if (y >= rect.top && y <= rect.bottom) {
            val isMajor = abs(tickValue % stepMajor) < 0.001f ||
                abs(abs(tickValue % stepMajor) - stepMajor) < 0.001f
            val tickLength = tape.width * (if (isMajor) 0.55f else 0.30f)
            drawLine(
                color = palette.tapeTick,
                start = Offset(layout.tickAnchorX, y),
                end = Offset(layout.tickAnchorX + tickGrow * tickLength, y),
                strokeWidth = if (isMajor) paints.majorStroke else paints.minorStroke,
            )
        }
        tickValue += stepMinor
    }

    // 2：固定黄色指针 —— 尖角朝向自己的刻度，位置固定在 centerY（不随数值移动）
    val pointerHalf = min(
        layout.pointerRect.width * 0.45f,
        paints.tapePointerHeight * 0.55f,
    )
    drawPath(
        trianglePath(
            tip = Offset(layout.apexX, centerY),
            base = Offset(layout.baseX, centerY),
            halfWidth = pointerHalf,
        ),
        color = palette.pointer,
    )

    // 3：数据框（**只包数字**，不含指针）+ 读数文字（固定位置，不随刻度移动）
    val data = layout.dataRect
    val boxWidth = data.width * 0.86f
    val boxHeight = min(data.height * 0.16f, paints.value.textSize * 2.6f)
    val boxLeft = data.left + (data.width - boxWidth) / 2f
    val boxTop = centerY - boxHeight / 2f
    drawRect(
        color = palette.line,
        topLeft = Offset(boxLeft, boxTop),
        size = Size(boxWidth, boxHeight),
        style = Stroke(width = paints.borderStroke),
    )

    val text = value?.let { it.roundToInt().toString() } ?: "--"
    paints.setTextColor(paints.value, palette.text)
    paints.value.textAlign = android.graphics.Paint.Align.CENTER
    // 按框宽自动收缩字号，避免位数变多时溢出数据框（不写死像素）
    val baseSize = paints.value.textSize
    val fit = min(1f, (boxWidth * 0.88f) / max(baseSize * 0.3f, paints.value.measureText(text)))
    paints.value.textSize = baseSize * fit
    drawContext.canvas.nativeCanvas.drawText(
        text,
        boxLeft + boxWidth / 2f,
        centerY + paints.value.textSize * 0.36f,
        paints.value,
    )
    paints.value.textSize = baseSize
    paints.value.textAlign = android.graphics.Paint.Align.LEFT

    // 单位：数据框下方（框内只有数字）
    paints.setTextColor(paints.caption, palette.tapeTick)
    paints.caption.textAlign = android.graphics.Paint.Align.CENTER
    drawContext.canvas.nativeCanvas.drawText(
        unit,
        boxLeft + boxWidth / 2f,
        boxTop + boxHeight + paints.caption.textSize * 1.2f,
        paints.caption,
    )
    paints.caption.textAlign = android.graphics.Paint.Align.LEFT
}

/**
 * EFIS 直条航向带（下方中央，窄条）—— 替代原弧形罗盘。
 *
 * 与模块模式 [com.speed.app.instruments.HeadingTile] 共用：
 *  - 同一刻度原语 [com.speed.app.instruments.drawHeadingStrip]（30° 长 / 10° 中 / 5° 短）
 *  - 同一中央指针原语 [com.speed.app.instruments.drawHeadingPointer]
 *  - 同一 canonical heading 与方位标签（8 方位）
 *  - 同一背景语义：**无独立底衬**，背景 = EFIS 面板背景（无色差）
 *
 * 几何对称性：条带占据**整个 rect 宽度**（左右各只留 1% 边距），
 * 刻度范围与标签裁剪的边距左右一致 —— 左侧显示多少角度、右侧就有
 * 等量的显示空间（历史问题：右端被读数框挤压、标签裁剪不对称）。
 *
 * 布局：直条刻度在区域上部、方位标签压在刻度上、中央指针固定、
 * 下方是当前航向读数框 + 大字。
 */
private fun DrawScope.drawEfisHeadingStrip(
    paints: InstrumentPaints,
    palette: HudPalette,
    rect: Rect,
    azimuth: Float,
) {
    if (rect.width <= 0f || rect.height <= 0f) return

    val stripTop = rect.top + rect.height * 0.08f
    val stripHeight = rect.height * 0.38f
    // 左右对称：整个条带只留 1% 边距
    val stripLeft = rect.left + rect.width * 0.01f
    val stripWidth = rect.width * 0.98f

    // 共享刻度条（无底衬）
    drawHeadingStrip(
        paints = paints,
        palette = palette,
        left = stripLeft,
        top = stripTop,
        stripWidth = stripWidth,
        stripHeight = stripHeight,
        azimuth = azimuth,
    )

    // 方位标签：每 45° 一个（8 方位），Canvas 直接绘制，位置与刻度同源
    val centerX = rect.left + rect.width / 2f
    val pixelsPerDegree = stripWidth / 120f
    val labelY = stripTop + stripHeight * 0.90f
    paints.setTextColor(paints.label, palette.tapeTick)
    paints.label.textAlign = android.graphics.Paint.Align.CENTER
    val base = floor(azimuth / 45f).toInt() * 45
    for (step in -3..3) {
        val absolute = base + step * 45
        val x = centerX + (absolute - azimuth) * pixelsPerDegree
        // 左右对称的标签裁剪边距
        if (x < stripLeft + 20f || x > stripLeft + stripWidth - 20f) continue
        val rounded = ((absolute % 360) + 360) % 360
        drawContext.canvas.nativeCanvas.drawText(
            azimuthLabel(rounded),
            x,
            labelY,
            paints.label,
        )
    }

    // 中央固定指针（共享原语）
    drawHeadingPointer(
        paints = paints,
        palette = palette,
        centerX = centerX,
        stripTop = stripTop,
        stripHeight = stripHeight,
    )

    // 当前航向读数框 + 大字：压在刻度下方正中
    val boxW = rect.width * 0.13f
    val boxH = rect.height * 0.30f
    val boxLeft = centerX - boxW / 2f
    val boxTop = rect.top + rect.height * 0.56f
    drawRect(
        color = palette.line,
        topLeft = Offset(boxLeft, boxTop),
        size = Size(boxW, boxH),
        style = Stroke(width = 2f),
    )
    paints.setTextColor(paints.value, palette.text)
    paints.value.textAlign = android.graphics.Paint.Align.CENTER
    val heading = azimuth.roundToInt().let { if (it >= 360) 0 else it }
    drawContext.canvas.nativeCanvas.drawText(
        "$heading°",
        centerX,
        boxTop + boxH * 0.76f,
        paints.value,
    )
    paints.value.textAlign = android.graphics.Paint.Align.LEFT
}

/** 底部数字读数行（EFIS 左下/右下角的小字）。 */
@Composable
private fun EfisReadoutRow(
    flightData: FlightData,
    palette: HudPalette,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val items = listOf(
            "地速" to (flightData.speedMetersPerSecond?.let { "${(it * 3.6f).roundToInt()}" } ?: "--"),
            "海拔" to (flightData.altitudeMeters?.let { "${it.roundToInt()}" } ?: "--"),
            "升降率" to (flightData.verticalSpeedMetersPerSecond?.let {
                String.format("%.1f", it)
            } ?: "--"),
        )
        items.forEachIndexed { index, (label, value) ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = label,
                        color = palette.text.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                        modifier = Modifier.offset(y = (-3).dp),
                    )
                    Text(
                        text = " " + value,
                        color = palette.text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
