package com.speed.app.instruments

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speed.app.settings.HudPalette
import com.speed.app.settings.HudPreset
import com.speed.app.settings.ReferenceLineLength
import com.speed.app.settings.ReferenceLineWidth
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 一次姿态采样（canonical 姿态状态）。
 *
 * 全部角度都以**屏幕**为基准（横屏时"俯仰"仍指屏幕上下方向的倾斜），
 * 由 [com.speed.app.attitude.AttitudeMath] 统一解算，UI 只读取、不再自行推导。
 *
 *  - [pitch] 俯仰角，-90..90。屏幕朝上 = 负（−90° 为屏幕完全朝上），
 *    背面朝上 = 正（+90°）。竖直 = 0°。
 *  - [roll]  滚转角，-180..180。**向右滚转为正**（手机右侧压低）。
 *  - [azimuth] canonical heading，0..360，0 = 北、90 = 东（机身背面参考轴，与显示方向无关）。
 */
data class Attitude(
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val azimuth: Float = 0f,
)

/**
 * 姿态仪面板（人工地平仪）—— 视锥（FOV）HUD 布局。
 *
 * 渲染数据契约（与 [com.speed.app.attitude.AttitudeMath] 的 canonical 定义一一对应）：
 *
 *  - 天地色块/地平线/梯尺整体旋转 **−roll**：左倾（roll<0）时地平线顺时针转、
 *    右端下压，与真实 PFD 一致。
 *  - 俯仰用 [HudProjection] 的**视锥投影**（FOV 80°）：屏幕中心 = 当前俯仰刻度，
 *    pitch>0（背面朝上）时地平线上移、铺地面色 —— 透过手机看世界；
 *    只有视锥内的刻度被生成（动态刻度，"进入/离开视野"），不再是 −90..+90 全平铺。
 *  - pitch 只平移（经投影）、绝不参与旋转；roll 是唯一控制地平线旋转的量。
 *
 * 层级（自上而下）：滚转刻度（直线+小数字）→ 浮动滚转指示 → 天地/梯尺 → 基准线。
 */
@Composable
fun AttitudeTile(
    pitch: Float,
    roll: Float,
    palette: HudPalette,
    referenceWidth: ReferenceLineWidth,
    referenceLength: ReferenceLineLength,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val paints = remember(density) { InstrumentPaints(density) }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val geometry = remember(canvasSize) { AttitudeGeometry.from(canvasSize) }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { if (it != canvasSize) canvasSize = it },
        ) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@Canvas

            val center = Offset(width / 2f, height / 2f)
            val reference = min(width, height)
            val focal = HudProjection.focalFor(height)

            // 地平线位置：**锁定地平线的唯一映射点**（HudProjection.horizonScreenY，
            // 与最终规格逐字一致：PITCH 负 → 下移 → 蓝多；PITCH 正 → 上移 → 红多）。
            val horizonY = HudProjection.horizonScreenY(center.y, pitch, focal)
            // 覆盖半径：**含 ±90° 最大投影位移 + roll 旋转对角线补偿**（horizonReach）。
            // 旧值 hypot/2 + height/2 在地平线出屏时覆盖不足，露出黑色背景。
            val reach = HudProjection.horizonReach(width, height, focal)
            val left = center.x - reach
            val blockWidth = reach * 2f

            withTransform({ rotate(degrees = -roll, pivot = center) }) {
                // 1：天地色块 + 地平线
                drawRect(
                    color = palette.skyBottom,
                    topLeft = Offset(left, horizonY - reach),
                    size = Size(blockWidth, reach),
                )
                drawRect(
                    color = palette.groundTop,
                    topLeft = Offset(left, horizonY),
                    size = Size(blockWidth, reach),
                )
                drawLine(
                    color = palette.line,
                    start = Offset(left, horizonY),
                    end = Offset(left + blockWidth, horizonY),
                    strokeWidth = paints.horizonStroke,
                )

                // 2：俯仰梯尺 —— 刻度锚定在**世界 5° 网格**（tickAngle = 5n，n 固定），
                //    位置随 pitch 连续平移（screenY 线性），不随 pitch 二次量化 ——
                //    根除旧实现"degrees 再 roundToInt"造成的刻度/数字抽动。
                //    10° 主刻度固定于 n 为偶数的网格线，长短属性不随 pitch 翻转。
                val centerDeg = -pitch
                val firstN = ceil((centerDeg - 55f) / 5f).toInt()
                val lastN = floor((centerDeg + 55f) / 5f).toInt()
                for (n in firstN..lastN) {
                    val tickAngle = n * 5f
                    val y = HudProjection.screenY(center.y, tickAngle, pitch, focal)
                    if (y < -height * 0.1f || y > height * 1.1f) continue

                    val isMajor = n % 2 == 0
                    val length = if (isMajor) LADDER_MAJOR_LENGTH else LADDER_MINOR_LENGTH
                    drawLine(
                        color = palette.ladder,
                        start = Offset(center.x - LADDER_GAP - length, y),
                        end = Offset(center.x - LADDER_GAP, y),
                        strokeWidth = if (isMajor) paints.mediumStroke else paints.minorStroke,
                    )
                    drawLine(
                        color = palette.ladder,
                        start = Offset(center.x + LADDER_GAP, y),
                        end = Offset(center.x + LADDER_GAP + length, y),
                        strokeWidth = if (isMajor) paints.mediumStroke else paints.minorStroke,
                    )
                }
            }

            // 3：滚转指示器 —— 共享绘制原语（与 EFIS 完全同一实现）。
            //    顶部直线刻度 + 小号数字 + 刻度下方浮动指示；
            //    角度范围按画布宽度动态决定（双姿态合并的宽 HUD 自动加宽到 ±45°）。
            drawRollIndicator(
                paints = paints,
                palette = palette,
                width = width,
                topAreaHeight = height * 0.16f,
                reference = reference,
                roll = roll,
                rangeDegrees = HudProjection.rollRangeFor(width, height),
            )
        }

        // 梯尺数字：跟着地平线一起倾斜、一起升降，但文字本身保持正立可读
        if (geometry.isValid) {
            LadderLabels(
                pitch = pitch,
                roll = roll,
                geometry = geometry,
                color = palette.ladder,
            )
        }

        // 基准线**绘制在数字层之上**（独立最上层 Canvas）：
        // 图层顺序 = 天地/梯尺 → 数字 → 基准线，因此数字经过基准线位置时
        // 会被基准线真实覆盖，不会"透过"基准线显示。
        Canvas(modifier = Modifier.fillMaxSize().clipToBounds()) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@Canvas
            drawReferenceMark(
                paints = paints,
                palette = palette,
                center = Offset(width / 2f, HudProjection.referenceSymbolCenterY(height / 2f)),
                reference = min(width, height),
                widthStyle = referenceWidth,
                lengthStyle = referenceLength,
                density = density,
            )
        }
    }
}

/** 姿态仪几何参数。标签层用它换算数值位置，与绘制层同源（同一投影公式）。 */
private data class AttitudeGeometry(
    val width: Float,
    val height: Float,
    val centerX: Float,
    val centerY: Float,
    val reference: Float,
    val focal: Float,
) {
    val isValid: Boolean get() = width > 0f && height > 0f && focal > 0f

    /** 某个俯仰度数在屏幕上的 y（与绘制层同一视锥投影）。 */
    fun yFor(degrees: Int, pitch: Float): Float =
        HudProjection.screenY(centerY, degrees.toFloat(), pitch, focal)

    companion object {
        fun from(canvasSize: IntSize): AttitudeGeometry {
            val width = canvasSize.width.toFloat()
            val height = canvasSize.height.toFloat()
            if (width <= 0f || height <= 0f) {
                return AttitudeGeometry(0f, 0f, 0f, 0f, 0f, 0f)
            }
            return AttitudeGeometry(
                width = width,
                height = height,
                centerX = width / 2f,
                centerY = height / 2f,
                reference = min(width, height),
                focal = HudProjection.focalFor(height),
            )
        }
    }
}

/**
 * 梯尺上的数字（每 10° 一个）。位置跟着地平线一起倾斜、一起升降，
 * 用与绘制层**完全相同的投影公式**保证对齐；文字本身保持正立
 * （graphicsLayer 只做旋转定位，不用 Canvas 旋转文字）。
 */
@Composable
private fun LadderLabels(
    pitch: Float,
    roll: Float,
    geometry: AttitudeGeometry,
    color: Color,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // 与 Canvas 里天地色块同一个旋转变换（rotate(-roll)），保证数字和刻度线对齐。
                rotationZ = -roll
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            },
    ) {
        val gapPx = 6f * LocalDensity.current.density

        // 数字锚定**世界 10° 网格**（tickAngle = 10n，n 固定）：标签内容固定、
        // 位置随 pitch 连续平移，进出视野时自然出现/消失 —— 不随 pitch 重新量化，
        // 根除"29→30→31"时的抽动。
        val centerDeg = -pitch
        val firstN = ceil((centerDeg - 55f) / 10f).toInt()
        val lastN = floor((centerDeg + 55f) / 10f).toInt()
        for (n in firstN..lastN) {
            val tickAngle = n * 10
            if (tickAngle == 0) continue
            val y = geometry.yFor(tickAngle, pitch)
            if (y < -geometry.height * 0.05f || y > geometry.height * 1.05f) continue

            val text = HudProjection.pitchLabel(tickAngle).toString()
            val outerEdge = LADDER_GAP + LADDER_MAJOR_LENGTH + gapPx

            LadderLabel(
                text = text,
                color = color,
                innerEdgePx = geometry.centerX - outerEdge,
                centerYPx = y,
                alignRight = true,
            )
            LadderLabel(
                text = text,
                color = color,
                innerEdgePx = geometry.centerX + outerEdge,
                centerYPx = y,
                alignRight = false,
            )
        }
    }
}

/**
 * 一枚正立的梯尺数字。
 *
 * **确定性定位**：固定尺寸标签盒 + 盒内对齐，位置只由 [innerEdgePx] / [centerYPx]
 * 算出（[ladderLabelBoxTopLeft]），**不依赖文字测量结果** ——
 * 旧实现用 `onTextLayout` 拿高度做垂直居中，新标签第一帧高度为 0、
 * 下一帧才跳到正确位置，用户看到的就是"经过 10/20/30 时数字跳一下"。
 * 盒内按文字自身宽度贴刻度侧对齐，1 位与 2 位数字的锚点都不会变。
 */
@Composable
private fun LadderLabel(
    text: String,
    color: Color,
    innerEdgePx: Float,
    centerYPx: Float,
    alignRight: Boolean,
) {
    val density = LocalDensity.current
    with(density) {
        val boxWidthPx = LABEL_BOX_WIDTH.toPx()
        val boxHeightPx = LABEL_BOX_HEIGHT.toPx()
        val topLeft = ladderLabelBoxTopLeft(innerEdgePx, centerYPx, boxWidthPx, boxHeightPx, alignRight)
        Box(
            modifier = Modifier
                .offset(x = topLeft.x.toDp(), y = topLeft.y.toDp())
                .size(boxWidthPx.toDp(), boxHeightPx.toDp()),
            contentAlignment = if (alignRight) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Text(
                text = text,
                color = color,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/**
 * 机体基准线（Module HUD）—— 与 EFIS **同款**：单色实体（无白色描边），
 * 左右横线 + 外端向上短竖臂（直角）+ 中央向上短竖线，整条符号一个连续 Path
 * （Miter 拐角 + 竖线穿过横线），因此连接处**无缝**。
 *
 * **固定不动**：位置恒为画布中心，不随 pitch 平移、不随 roll 旋转。
 * 因此 pitch=0 / roll=0 时 0° 俯仰刻度线正好与它共线；pitch 改变后
 * 只有刻度层移动，基准线保持不动。
 *
 * 尺寸/颜色仍由现有设置驱动：粗细 = [ReferenceLineWidth]、
 * 长度 = [ReferenceLineLength]（半跨 = 参考边长 ×(0.10 + factor)）、
 * 颜色 = [HudPalette.reference]。
 */
private fun DrawScope.drawReferenceMark(
    paints: InstrumentPaints,
    palette: HudPalette,
    center: Offset,
    reference: Float,
    widthStyle: ReferenceLineWidth,
    lengthStyle: ReferenceLineLength,
    density: Float,
) {
    drawReferenceSymbol(
        paints = paints,
        palette = palette,
        center = center,
        halfSpan = reference * (0.10f + lengthStyle.factor),
        stemLength = reference * 0.022f,
        strokeWidth = widthStyle.dp * density,
    )
}

/** 供预览 / 默认配色使用。 */
val DefaultHudPalette: HudPalette = HudPreset.CLASSIC.palette

// ---------------------------------------------------------------------------
// 俯仰梯尺的刻度线几何。
//
// 定义成常量而不是散落在两处现算：绘制层和数字层必须用完全相同的数值，
// 否则 rotate 之后两套偏移会被旋转放大，数字与刻度线整体错位。
// ---------------------------------------------------------------------------

/** 短刻度线长度（px，面板 1080 宽时）。 */
private const val LADDER_MINOR_LENGTH = 14f

/** 长刻度线长度（px，每 10°）。 */
private const val LADDER_MAJOR_LENGTH = 30f

/** 刻度线内侧与中心的间隙（px）。 */
private const val LADDER_GAP = 8f

/**
 * 梯尺数字标签盒的固定尺寸：位置**不依赖**文字测量（1 位/2 位数字共用同一锚点），
 * 盒内按刻度侧对齐，因此数字宽度变化不会造成位置跳动。
 */
private val LABEL_BOX_WIDTH = 32.dp
private val LABEL_BOX_HEIGHT = 22.dp
