package com.speed.app.instruments

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speed.app.settings.HudPalette
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * EFIS 姿态仪：按航空电子综合显示器的样式绘制。
 *
 * 与独立面板版 [AttitudeTile] 的区别：
 *  - 天地色块被裁进一个**圆角矩形**，四周留出灰色仪表面板底
 *  - 顶部是**固定的滚转刻度 + 随姿态转动的三角指针**（不是半圆刻度盘）
 *  - 中央基准是**两侧带竖钩的短横线**（EFIS 的 aircraft symbol）
 *  - 俯仰梯尺每 10° 一条，0° 以下是虚线
 *
 * 天地色块**反向倾斜**（飞机往左倾、地平线右端下压），
 * 渲染符号与 [AttitudeTile] 一致：rotate(−roll)，指针 +sin(roll)。
 */
@Composable
fun EfisAttitude(
    pitch: Float,
    roll: Float,
    palette: HudPalette,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val densityScale = density
    val paints = remember(density) { InstrumentPaints(density) }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

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

            // 姿态球区域：四周留边，顶部空出一条给滚转刻度
            val sideMargin = width * 0.06f
            val topMargin = height * 0.16f
            val bottomMargin = height * 0.04f
            val ball = Rect(
                left = sideMargin,
                top = topMargin,
                right = width - sideMargin,
                bottom = height - bottomMargin,
            )
            if (ball.width <= 0f || ball.height <= 0f) return@Canvas

            val ballCenter = Offset(ball.center.x, ball.center.y)
            // 俯仰用与双面板**完全相同的视锥投影**（[HudProjection]，FOV 80°）；
            // 地平线走同一锁定映射 horizonScreenY —— Module 与 EFIS 共用同一
            // canonical pitch 与同一方向定义。
            val focal = HudProjection.focalFor(ball.height)
            val horizonY = HudProjection.horizonScreenY(ballCenter.y, pitch, focal)

            val ballClip = Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        rect = ball,
                        cornerRadius = CornerRadius(ball.width * 0.08f),
                    ),
                )
            }

            clipPath(ballClip) {
                // 天地色块**反向倾斜**（飞机左倾 → 地平线右端下压），与双面板同一符号链：
                // rotate(−roll)，证明见 AttitudeMath（roll = atan2(−R[6], R[7])，右倾为正）。
                // 覆盖半径用共享 horizonReach（±90° 最大位移 + 旋转补偿），
                // 避免地平线出球时球内露背景（与模块模式同一修复）。
                val reach = HudProjection.horizonReach(ball.width, ball.height, focal)
                val left = ballCenter.x - reach
                val blockWidth = reach * 2f

                withTransform({ rotate(degrees = -roll, pivot = ballCenter) }) {
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

                    // 地平线
                    drawLine(
                        color = palette.line,
                        start = Offset(left, horizonY),
                        end = Offset(left + blockWidth, horizonY),
                        strokeWidth = paints.horizonStroke,
                    )

                    // 俯仰梯尺：刻度锚定**世界 5° 网格**（tickAngle = 5n，n 固定），
                    // 位置随 pitch 连续平移（无二次量化 → 不抽动）。
                    // 10° 主刻度加长但**不与数字重叠**：数字画在刻度末端外侧
                    // 明确间距（labelGap = 半字宽 + 4px），长度按球宽比例。
                    val majorSpan = ball.width * 0.14f
                    val minorSpan = majorSpan * 0.55f
                    val labelGap = 12f * densityScale
                    val centerDeg = -pitch
                    val firstN = ceil((centerDeg - 55f) / 5f).toInt()
                    val lastN = floor((centerDeg + 55f) / 5f).toInt()
                    for (n in firstN..lastN) {
                        val tickAngle = n * 5f
                        if (tickAngle == 0f) continue
                        val y = HudProjection.screenY(ballCenter.y, tickAngle, pitch, focal)
                        if (y < ball.top - 20f || y > ball.bottom + 20f) continue

                        val isMajor = n % 2 == 0
                        val lineLength = if (isMajor) majorSpan else minorSpan
                        val isAbove = tickAngle > 0
                        drawLine(
                            color = palette.ladder,
                            start = Offset(ballCenter.x - lineLength, y),
                            end = Offset(ballCenter.x + lineLength, y),
                            strokeWidth = if (isMajor) paints.mediumStroke else paints.minorStroke,
                            pathEffect = if (isAbove) {
                                null
                            } else {
                                PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                            },
                        )

                        // 10° 数字标签：与刻度线同一 screenY、同一旋转变换；
                        // 画在刻度末端**外侧**（+labelGap），与大刻度线不重叠。
                        if (isMajor) {
                            val label = HudProjection.pitchLabel(n * 5).toString()
                            paints.setTextColor(paints.caption, palette.ladder)
                            paints.caption.textAlign = android.graphics.Paint.Align.CENTER
                            val textHalfWidth = paints.caption.measureText(label) / 2f + 2f
                            drawContext.canvas.nativeCanvas.drawText(
                                label,
                                ballCenter.x + lineLength + labelGap + textHalfWidth,
                                y + paints.caption.textSize * 0.36f,
                                paints.caption,
                            )
                            drawContext.canvas.nativeCanvas.drawText(
                                label,
                                ballCenter.x - lineLength - labelGap - textHalfWidth,
                                y + paints.caption.textSize * 0.36f,
                                paints.caption,
                            )
                            paints.caption.textAlign = android.graphics.Paint.Align.LEFT
                        }
                    }
                }

                // 中央机体基准符号：短横线 + 两侧竖钩（EFIS 样式），固定不动
                drawAircraftSymbol(paints, palette, ballCenter, ball.width)
            }

            // 姿态球外框
            drawRoundRect(
                color = palette.line.copy(alpha = 0.55f),
                topLeft = ball.topLeft,
                size = ball.size,
                cornerRadius = CornerRadius(ball.width * 0.08f),
                style = Stroke(width = paints.referenceStroke * 0.6f),
            )

            // 顶部滚转指示器 —— 与模块模式 AttitudeTile **完全同一共享实现**
            //（drawRollIndicator）：直线刻度 + 小号数字 + 刻度下方浮动倒三角。
            drawRollIndicator(
                paints = paints,
                palette = palette,
                width = width,
                topAreaHeight = topMargin,
                reference = min(width, height),
                roll = roll,
                rangeDegrees = HudProjection.rollRangeFor(width, height),
            )
        }
    }
}

/**
 * EFIS 机体基准符号：**固定不动**（机体/视轴参考，位置不随 pitch/roll 变化）。
 *
 * 几何委托共享原语 [drawReferenceSymbol] —— 与 Module HUD **同款**
 * （左右横线 + 外端向上短竖线 + 中央向上短竖线，黑色填充 + 白色描边），
 * EFIS 侧参数与改版前完全一致，因此 EFIS 视觉、位置、俯仰数字稳定性均不受影响。
 */
private fun DrawScope.drawAircraftSymbol(
    paints: InstrumentPaints,
    palette: HudPalette,
    center: Offset,
    ballWidth: Float,
) {
    drawReferenceSymbol(
        paints = paints,
        palette = palette,
        center = center,
        halfSpan = ballWidth * 0.20f,
        stemLength = ballWidth * 0.022f,
        strokeWidth = paints.referenceStroke,
    )
}
