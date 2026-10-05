package com.speed.app.instruments

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.speed.app.settings.HudPalette
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 一套按屏幕密度缩放好的画笔。
 *
 * 每帧 new Paint 会拖垮帧率，所以整个绘制层共用这一份。
 * 三种文字各用一个固定字号的 Paint，避免"测量用一种字号、绘制用另一种"的错位。
 */
class InstrumentPaints(private val densityScale: Float) {

    private fun typeface(bold: Boolean) = Typeface.create(
        Typeface.MONOSPACE,
        if (bold) Typeface.BOLD else Typeface.NORMAL,
    )

    /**
     * 注意：android.graphics.Paint 自己有个 `density` 属性（Int），
     * 在 `Paint().apply { }` 里直接写 `density` 会被解析成 Paint 的 Int 属性而不是外层参数，
     * `Int * Float` 没有匹配的重载，报错信息还是"未解析"，很容易看错方向。
     * 所以这里不用 apply，改用显式赋值。
     */
    private fun textPaint(sizeSp: Float, bold: Boolean, align: Paint.Align): Paint {
        val scale = this@InstrumentPaints.densityScale
        val paint = Paint()
        paint.isAntiAlias = true
        paint.color = android.graphics.Color.WHITE
        paint.textSize = sizeSp * scale
        paint.typeface = typeface(bold)
        paint.textAlign = align
        return paint
    }

    val label = textPaint(15f, true, Paint.Align.LEFT)
    val caption = textPaint(11f, true, Paint.Align.LEFT)
    val value = textPaint(17f, true, Paint.Align.LEFT)
    val valueLarge = textPaint(44f, true, Paint.Align.CENTER)
    val unit = textPaint(13f, true, Paint.Align.CENTER)

    /** 刻度带上居中显示的标签。 */
    val tapeLabel = textPaint(14f, true, Paint.Align.RIGHT)
    val tapeLabelCentered = textPaint(14f, true, Paint.Align.CENTER)

    /** 刻度带 / 读数用的文字字号（像素）。在构造时就按屏幕密度算好，避免各处重复求值。 */
    val tapeTextSizePx: Float = 20f * densityScale

    val hairline = 1.2f * densityScale
    val minorStroke = 1.2f * densityScale
    val mediumStroke = 2.0f * densityScale
    val majorStroke = 2.4f * densityScale
    val horizonStroke = 2.5f * densityScale
    val referenceStroke = 4.0f * densityScale
    val borderStroke = 1.2f * densityScale
    val tapePointerHeight = 16f * densityScale

    val pointerSize = 7f * densityScale
    val labelPadding = 5f * densityScale
    val textBaselineOffset = 5f * densityScale
    val readoutLineHeight = 19f * densityScale

    fun setTextColor(paint: Paint, color: Color) {
        paint.color = color.toArgb()
    }
}

/**
 * 刻度带刻度与文字的颜色。固定高亮，不跟随调色板 ——
 * 调色板里的 tapeTick 在部分预设下压在深色底衬上对比度不够，读数会糊掉。
 */
internal val TapeLabelColor = Color(0xFFF2F7FF)

/** 等腰三角形：顶点在 [tip]，底边中点在 [base]，底边半宽 [halfWidth]。用于各种读数指针。 */
internal fun trianglePath(tip: Offset, base: Offset, halfWidth: Float): Path {
    val axisX = base.x - tip.x
    val axisY = base.y - tip.y
    val length = kotlin.math.sqrt(axisX * axisX + axisY * axisY)
    if (length < 1e-3f) {
        return Path().apply { moveTo(tip.x, tip.y); close() }
    }
    val perpX = -axisY / length * halfWidth
    val perpY = axisX / length * halfWidth
    return Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + perpX, base.y + perpY)
        lineTo(base.x - perpX, base.y - perpY)
        close()
    }
}

/**
 * 高度/速度带的刻度屏幕 y（纯函数，供测试）。
 * 读数指针**固定**在 [centerY]；刻度随当前 [value] 移动（value 增大 → 刻度整体上移）。
 */
fun tapeTickScreenY(centerY: Float, tickValue: Float, value: Float, pixelsPerUnit: Float): Float =
    centerY - (tickValue - value) * pixelsPerUnit

/**
 * 航向带刻度屏幕 x（纯函数，供测试）：以 [centerX] 为唯一中心，
 * 整数度偏移 [offsetDegrees]（−k 与 +k 严格对称），共用同一 pixelsPerDegree。
 */
fun headingTickScreenX(centerX: Float, offsetDegrees: Int, pixelsPerDegree: Float): Float =
    centerX + offsetDegrees * pixelsPerDegree

/** 基准符号的线段几何（纯函数产出，渲染与测试共用，保证"连接处无缝"可被断言）。 */
data class ReferenceSymbolGeometry(
    /** 横线所在 y（= 画布中心 y，固定不动）。 */
    val lineY: Float,
    /** 竖臂顶端 y（在横线**上方**）。 */
    val stemTop: Float,
    /** 竖臂底端 y：与横线下缘齐平 → 与横线在几何上真实重叠。 */
    val stemBottom: Float,
    /** 横线内侧终点到中心的距离。 */
    val innerStop: Float,
    /** 横线外端到中心的距离。 */
    val halfSpan: Float,
    /** 本体线宽（**只画一次**，不含任何外描边）。 */
    val bodyWidth: Float,
)

/**
 * 计算基准符号几何。竖臂底端 = 横线下缘（`lineY + bodyWidth/2`），
 * 且折线经过拐角点，因此横线与竖线必然重叠 —— 不存在"横线结束 → 空白 → 竖线"的缝。
 */
fun referenceSymbolGeometry(
    center: Offset,
    halfSpan: Float,
    stemLength: Float,
    strokeWidth: Float,
): ReferenceSymbolGeometry = ReferenceSymbolGeometry(
    lineY = center.y,
    stemTop = center.y - stemLength,
    stemBottom = center.y + strokeWidth * 0.5f,
    innerStop = halfSpan * 0.30f,
    halfSpan = halfSpan,
    bodyWidth = strokeWidth,
)

/** 一条刻度带的三段几何（纯函数产出）：刻度锚点 / 刻度生长方向 / 指针尖角与底边。 */
data class TapeStripLayout(
    /** 刻度线的锚定 x（贴 EFIS 或贴数据一侧）。 */
    val tickAnchorX: Float,
    /** 刻度生长方向：−1 向左、+1 向右。 */
    val tickGrow: Float,
    /** 指针尖角 x（朝向刻度）。 */
    val pointerApexX: Float,
    /** 指针底边 x（背离刻度）。 */
    val pointerBaseX: Float,
)

/**
 * 按侧别切分刻度带：`[刻度][◀/▶][数据]`。
 * - [pointerOnRight] = true（Module 高度带）：刻度锚在 `rect.right − 指针占位`、向左生长，
 *   指针尖角**向左**指向刻度，数据（由调用方画在 rect.right 之外）在最右。
 * - [pointerOnRight] = false：镜像。
 */
fun tapeStripLayout(
    rect: TapeRect,
    pointerOnRight: Boolean,
    pointerHeight: Float,
): TapeStripLayout {
    val pointerZone = pointerHeight * 1.5f
    return if (pointerOnRight) {
        val anchor = rect.right - pointerZone
        TapeStripLayout(
            tickAnchorX = anchor,
            tickGrow = -1f,
            pointerApexX = anchor,
            pointerBaseX = anchor + pointerZone * 0.9f,
        )
    } else {
        val anchor = rect.left + pointerZone
        TapeStripLayout(
            tickAnchorX = anchor,
            tickGrow = 1f,
            pointerApexX = anchor,
            pointerBaseX = anchor - pointerZone * 0.9f,
        )
    }
}

/**
 * 机体/世界基准符号 —— Module HUD 与 EFIS **共用同款**视觉：
 * 左右两段横线（中间留缺口）+ 每段**外端向上**的短竖臂（直角结构）+ 中央**向上**短竖线。
 *
 * **单色实体：只有基准线本体颜色 [HudPalette.reference]，没有任何外描边**
 * （旧实现用"白粗线 + 黑细线"两次 drawLine 模拟描边，本轮取消）。
 *
 * **无缝连接**：整条符号用**一个连续 Path**（折线 + Miter 拐角）描边一次 ——
 * 拐角由 Miter 直接填充，竖臂底端与横线下缘齐平并穿过横线，因此
 * 横线与竖线之间不会出现缝隙或抗锯齿断开。
 *
 * 该符号是**固定 HUD 飞行基准符号**，不代表地平线：调用方传入固定的 [center]，
 * 不随 pitch 上下移动、也不随 roll 旋转。
 *
 * @param halfSpan    横线外端到中心的水平距离
 * @param stemLength  短竖线在横线**上方**的长度
 * @param strokeWidth 线宽（基准线粗细设置；只影响本体，不再外扩描边）
 */
internal fun DrawScope.drawReferenceSymbol(
    paints: InstrumentPaints,
    palette: HudPalette,
    center: Offset,
    halfSpan: Float,
    stemLength: Float,
    strokeWidth: Float,
) {
    if (halfSpan <= 0f || strokeWidth <= 0f) return
    val geometry = referenceSymbolGeometry(center, halfSpan, stemLength, strokeWidth)
    val innerStop = geometry.innerStop
    val lineY = geometry.lineY
    val stemTop = geometry.stemTop
    val stemBottom = geometry.stemBottom

    val path = Path().apply {
        // 左：横线 → 外端向上折角（一个连续折线，拐角由 Miter 填充）
        moveTo(center.x - innerStop, lineY)
        lineTo(center.x - halfSpan, lineY)
        lineTo(center.x - halfSpan, stemTop)

        // 右：同理镜像
        moveTo(center.x + innerStop, lineY)
        lineTo(center.x + halfSpan, lineY)
        lineTo(center.x + halfSpan, stemTop)

        // 中央：从横线下缘向上（与左右横线同一基线，几何连续）
        moveTo(center.x, stemBottom)
        lineTo(center.x, stemTop)
    }

    drawPath(
        path = path,
        color = palette.reference,
        style = Stroke(
            width = geometry.bodyWidth,
            join = StrokeJoin.Miter,
            cap = StrokeCap.Butt,
        ),
    )
}

/**
 * 数字读数面板的一行基线（纯函数，供测试）。
 *
 * @param captionBaseline 说明文字基线
 * @param valueBaseline   数值文字基线
 */
data class ReadoutRowBaseline(
    val captionBaseline: Float,
    val valueBaseline: Float,
)

/** 读数面板顶部安全边距（占面板高度）。 */
const val READOUT_TOP_INSET_FRACTION = 0.06f

/** 读数面板底部安全边距（占面板高度）—— 手势区/圆角/导航条所在区域，文字不得进入。 */
const val READOUT_BOTTOM_SAFE_FRACTION = 0.10f

/** 数值行相对说明行的行距系数（× 数值字号）。 */
const val READOUT_VALUE_LINE_FACTOR = 1.15f

/**
 * 把 [rowsPerColumn] 行读数（每行"说明 + 数值"两行文字）整体排进**安全区**：
 * 上下各留安全边距、行槽等高、内容在槽内居中。
 *
 * 纯函数、按面板高度动态适配 —— 不用固定像素补丁，也不会出现最后一行被底部裁掉。
 */
fun readoutRowBaselines(
    height: Float,
    rowsPerColumn: Int,
    captionSize: Float,
    valueSize: Float,
): List<ReadoutRowBaseline> {
    if (rowsPerColumn <= 0 || height <= 0f) return emptyList()
    val safeTop = height * READOUT_TOP_INSET_FRACTION
    val safeBottom = (height * (1f - READOUT_BOTTOM_SAFE_FRACTION)).coerceAtLeast(safeTop + 1f)
    val slot = (safeBottom - safeTop) / rowsPerColumn
    val contentHeight = captionSize + valueSize * READOUT_VALUE_LINE_FACTOR
    val slotPadding = ((slot - contentHeight) / 2f).coerceAtLeast(0f)

    return (0 until rowsPerColumn).map { row ->
        val rowTop = safeTop + row * slot + slotPadding
        val captionBaseline = rowTop + captionSize
        ReadoutRowBaseline(
            captionBaseline = captionBaseline,
            valueBaseline = captionBaseline + valueSize * READOUT_VALUE_LINE_FACTOR,
        )
    }
}

/**
 * 梯尺数字的**确定性锚点**（纯函数，供测试）：固定尺寸标签盒的左上角坐标。
 *
 * 位置只由几何量决定，**不依赖文字测量结果** —— 因此新标签刚进入视野时
 * 不会先落在错误位置、下一帧再跳到位（旧 `onTextLayout` 实现的跳动根因）。
 *
 * @param innerEdgeX 标签盒靠近梯尺刻度的那条边的 x（左列取右边界、右列取左边界）
 * @param alignRight 左列标签（文字靠盒子右侧对齐）为 true；右列为 false
 */
fun ladderLabelBoxTopLeft(
    innerEdgeX: Float,
    centerYPx: Float,
    boxWidth: Float,
    boxHeight: Float,
    alignRight: Boolean,
): Offset = Offset(
    x = if (alignRight) innerEdgeX - boxWidth else innerEdgeX,
    y = centerYPx - boxHeight / 2f,
)

/** EFIS 左右刻度带的排布侧：决定"数据框 / 指针 / 刻度"三段顺序。 */
enum class EfisTapeSide {
    /** 速度带：数据框在左、指针尖角向右、刻度贴 EFIS 本体（右）。 */
    LEFT,

    /** 高度带：刻度贴 EFIS 本体（左）、指针尖角向左、数据框在右。 */
    RIGHT,
}

/**
 * EFIS 单侧刻度带的三段几何（**互不重叠**）：数据框 / 指针 / 刻度。
 *
 * - LEFT：`[数据框][▶][刻度]`（右侧贴 EFIS 本体）
 * - RIGHT：`[刻度][◀][数据框]`（左侧贴 EFIS 本体）
 *
 * [apexX] 与 [baseX] 供渲染与测试共用：尖角始终朝向自己的刻度。
 */
data class EfisTapeLayout(
    val dataRect: Rect,
    val pointerRect: Rect,
    val tapeRect: Rect,
    val apexX: Float,
    val baseX: Float,
    val tickAnchorX: Float,
)

/** 数据框宽度占整条带的比例。 */
const val EFIS_TAPE_DATA_FRACTION = 0.46f

/** 指针宽度占整条带的比例。 */
const val EFIS_TAPE_POINTER_FRACTION = 0.14f

/** 按侧别切分一条 EFIS 刻度带（纯函数，供渲染与测试共用）。 */
fun efisTapeLayout(rect: Rect, side: EfisTapeSide): EfisTapeLayout {
    val dataWidth = rect.width * EFIS_TAPE_DATA_FRACTION
    val pointerWidth = rect.width * EFIS_TAPE_POINTER_FRACTION
    val tapeWidth = rect.width - dataWidth - pointerWidth
    return when (side) {
        EfisTapeSide.LEFT -> {
            val data = Rect(rect.left, rect.top, rect.left + dataWidth, rect.bottom)
            val pointer = Rect(data.right, rect.top, data.right + pointerWidth, rect.bottom)
            val tape = Rect(pointer.right, rect.top, rect.right, rect.bottom)
            EfisTapeLayout(
                dataRect = data,
                pointerRect = pointer,
                tapeRect = tape,
                apexX = tape.left, // 尖角向右，指向刻度
                baseX = pointer.left,
                tickAnchorX = tape.right, // 刻度贴 EFIS 本体一侧
            )
        }

        EfisTapeSide.RIGHT -> {
            val tape = Rect(rect.left, rect.top, rect.left + tapeWidth, rect.bottom)
            val pointer = Rect(tape.right, rect.top, tape.right + pointerWidth, rect.bottom)
            val data = Rect(pointer.right, rect.top, rect.right, rect.bottom)
            EfisTapeLayout(
                dataRect = data,
                pointerRect = pointer,
                tapeRect = tape,
                apexX = tape.right, // 尖角向左，指向刻度
                baseX = pointer.right,
                tickAnchorX = tape.left,
            )
        }
    }
}

/**
 * 画一条刻度带：刻度 + 标签 + 固定读数指针（**无底衬、无边框**，背景 = 面板背景）。
 *
 * 版式（本轮 Module 高度带定版）：`[刻度标签][刻度线][◀/▶][数据]`
 * - 指针位于刻度与数据之间，**尖角朝向刻度**（pointerOnRight → 尖角向左）
 * - 指针与数据都固定（锚在带子垂直中心），只有刻度随 [value] 移动
 * - 数值增大 → 刻度 screenY 增大 → 刻度向下
 */
fun DrawScope.drawTapeStrip(
    paints: InstrumentPaints,
    palette: HudPalette,
    rect: TapeRect,
    ticks: List<TapeTick>,
    value: Float,
    unitLabel: String,
    pointerOnRight: Boolean = true,
) {
    val centerY = rect.centerY
    val pixelsPerUnit = rect.height / (rect.visibleRange * 2f)
    val nativeCanvas = drawContext.canvas.nativeCanvas
    // 刻度与标签用固定高亮色而不是 palette.tapeTick：
    // 后者在"经典蓝红"里接近纯白、在"单色绿"里又偏暗，压在深色底衬上对比度都不够。
    paints.setTextColor(paints.tapeLabel, TapeLabelColor)

    // 指针占位：刻度线整体让出这一段，保证"刻度 / 指针 / 数据"三段不重叠
    val layout = tapeStripLayout(rect, pointerOnRight, paints.tapePointerHeight)
    val tickAnchorX = layout.tickAnchorX
    val tickGrow = layout.tickGrow
    // 标签贴刻度外侧端：右侧指针时右对齐在刻度左端，左侧指针时左对齐在刻度右端
    paints.tapeLabel.textAlign = if (pointerOnRight) Paint.Align.RIGHT else Paint.Align.LEFT

    for (tick in ticks) {
        if (tick.value < rect.minValue || tick.value > rect.maxValue) continue

        val y = tapeTickScreenY(centerY, tick.value, value, pixelsPerUnit)
        if (y < rect.top - 1f || y > rect.bottom + 1f) continue

        val tickLength = rect.width * when (tick.weight) {
            TickWeight.MAJOR -> 0.30f
            TickWeight.MEDIUM -> 0.20f
            else -> 0.12f
        }
        val stroke = when (tick.weight) {
            TickWeight.MAJOR -> paints.majorStroke
            TickWeight.MEDIUM -> paints.mediumStroke
            else -> paints.minorStroke
        }

        drawLine(
            color = TapeLabelColor,
            start = Offset(tickAnchorX, y),
            end = Offset(tickAnchorX + tickGrow * tickLength, y),
            strokeWidth = stroke,
        )

        // 数字紧挨刻度线的外侧端（靠标签一侧），不是远在带子另一侧
        tick.label?.let { text ->
            nativeCanvas.drawText(
                text,
                tickAnchorX + tickGrow * tickLength + tickGrow * paints.labelPadding,
                y + paints.textBaselineOffset,
                paints.tapeLabel,
            )
        }
    }

    // 固定读数指针：尖角**朝向刻度**（位于刻度与数据之间）
    val halfWidth = paints.tapePointerHeight * 0.5f
    drawPath(
        trianglePath(
            tip = Offset(layout.pointerApexX, centerY),
            base = Offset(layout.pointerBaseX, centerY),
            halfWidth = halfWidth,
        ),
        color = palette.pointer,
    )

    // 单位标注放在带下方
    paints.setTextColor(paints.unit, palette.tapeTick)
    nativeCanvas.drawText(
        unitLabel,
        rect.centerX,
        rect.bottom + paints.unit.textSize + paints.labelPadding,
        paints.unit,
    )
}

/** 刻度带占据的矩形区域。 */
data class TapeRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    /** 指针上下各能显示多少个单位。 */
    val visibleRange: Float,
    val minValue: Float,
    val maxValue: Float,
) {
    val topLeft: Offset get() = Offset(left, top)
    val size: Size get() = Size(width, height)
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

enum class TickWeight { MINOR, MEDIUM, MAJOR }

/** 一根刻度：[label] 为 null 表示不标数字。 */
data class TapeTick(
    val value: Float,
    val weight: TickWeight,
    val label: String?,
)

/** 生成等间距刻度：[step] 是间隔，[labelStep] 是标数字的间隔（必须是 step 的整数倍）。 */
fun buildTicks(
    minValue: Int,
    maxValue: Int,
    step: Int,
    labelStep: Int,
    majorStep: Int = labelStep,
    labelFormatter: (Int) -> String = { abs(it).toString() },
): List<TapeTick> = buildList {
    var value = minValue
    while (value <= maxValue) {
        val isLabel = value % labelStep == 0
        val weight = when {
            value % majorStep == 0 -> TickWeight.MAJOR
            isLabel -> TickWeight.MEDIUM
            else -> TickWeight.MINOR
        }
        add(TapeTick(value.toFloat(), weight, if (isLabel) labelFormatter(value) else null))
        value += step
    }
}

/**
 * 共享的航向带中央指针：固定在 [centerX] 的倒三角，顶点向下压向刻度带。
 * 模块模式与 EFIS 模式共用同一原语 —— 指针固定、刻度相对它滚动，
 * 颜色读取 [HudPalette.pointer]。
 */
internal fun DrawScope.drawHeadingPointer(
    paints: InstrumentPaints,
    palette: HudPalette,
    centerX: Float,
    stripTop: Float,
    stripHeight: Float,
) {
    drawPath(
        trianglePath(
            tip = Offset(centerX, stripTop + stripHeight * 0.02f),
            base = Offset(centerX, stripTop - paints.tapePointerHeight * 0.7f),
            halfWidth = paints.pointerSize * 0.8f,
        ),
        color = palette.pointer,
    )
}

// ---------------------------------------------------------------------------
// 航向带 / 滚转指示器的共享绘制原语。
//
// 模块模式（HeadingTile）与 EFIS 模式（EfisLayout）共用同一套刻度与指示器逻辑：
// "两个 renderer，不是两个航向系统；两个布局位置，不是两套滚转逻辑"。
// ---------------------------------------------------------------------------

/** 罗盘方位标签：八个主方位用字母，其余用度数。与 HeadingTile/EfisLayout 共用。 */
internal fun azimuthLabel(degrees: Int): String = when (degrees) {
    0 -> "N"
    45 -> "NE"
    90 -> "E"
    135 -> "SE"
    180 -> "S"
    225 -> "SW"
    270 -> "W"
    315 -> "NW"
    else -> degrees.toString()
}

/**
 * 共享的航向带刻度条：横向直线、**无底衬**（背景 = HUD 背景，无色差）。
 *
 * - 以整数度为锚点反推屏幕位置（每个整数度的位置唯一确定，
 *   按像素偏移会"滚动时整体跳一格"）
 * - 每 30° 长刻度、每 10° 中刻度、每 5° 短刻度
 * - 0/360 环绕由调用方传入的 canonical azimuth 保证（同一数据源）
 *
 * 只画刻度线本身；指针与大字读数由各 renderer 按自己的布局画。
 */
internal fun DrawScope.drawHeadingStrip(
    paints: InstrumentPaints,
    palette: HudPalette,
    left: Float,
    top: Float,
    stripWidth: Float,
    stripHeight: Float,
    azimuth: Float,
) {
    val centerX = left + stripWidth / 2f
    val pixelsPerDegree = stripWidth / 120f

    // 严格对称：以 centerX 为唯一中心，offset 用整数度（−k 与 +k 完全对称），
    // x = centerX + offset×ppd —— 左/右可见范围、间距、裁剪完全一致；
    // 不再用 toInt() 截断 azimuth（会导致左右错位、右侧少一段）。
    val halfDegrees = 62
    for (offset in -halfDegrees..halfDegrees) {
        val x = headingTickScreenX(centerX, offset, pixelsPerDegree)
        if (x < left || x > left + stripWidth) continue

        val offsetDegrees = (azimuth + offset).roundToInt()
        val heading = ((offsetDegrees % 360) + 360) % 360
        val weight = when {
            heading % 30 == 0 -> TickWeight.MAJOR
            heading % 10 == 0 -> TickWeight.MEDIUM
            heading % 5 == 0 -> TickWeight.MINOR
            else -> continue
        }
        val tickHeight = when (weight) {
            TickWeight.MAJOR -> stripHeight * 0.62f
            TickWeight.MEDIUM -> stripHeight * 0.40f
            TickWeight.MINOR -> stripHeight * 0.22f
        }
        drawLine(
            color = palette.tapeTick,
            start = Offset(x, top),
            end = Offset(x, top + tickHeight),
            strokeWidth = when (weight) {
                TickWeight.MAJOR -> paints.majorStroke
                TickWeight.MEDIUM -> paints.mediumStroke
                else -> paints.minorStroke
            },
        )
    }
}

/**
 * 共享的左右倾斜（滚转）指示器：直线刻度 + 小号数字 + 刻度下方的浮动倒三角。
 *
 * ## 指针方向（用户验收：右倾 → 指针向左）
 *
 * canonical roll 物理定义**不变**（右倾为正，见 AttitudeMath），
 * 这里只做 UI 映射：`pointerX = centerX − roll × span / range` ——
 * 手机向右倾（roll > 0）时指针向左移；左倾向右移；0° 居中。
 *
 * ## 动态宽度
 *
 * [rangeDegrees] 由调用方按可用宽度决定（普通面板 ±30°，双姿态合并的宽 HUD ±45°），
 * 刻度与数字按范围自动铺开，数字/刻度间距随 span 同步缩放，不会重叠。
 */
internal fun DrawScope.drawRollIndicator(
    paints: InstrumentPaints,
    palette: HudPalette,
    width: Float,
    topAreaHeight: Float,
    reference: Float,
    roll: Float,
    rangeDegrees: Float,
) {
    val centerX = width / 2f
    // span 随 range 扩展：范围越大占屏越宽（双姿态合并模式充分利用宽度）
    val span = minOf(width * 0.45f, reference * 0.42f * (rangeDegrees / 30f))
    val scaleTop = topAreaHeight * 0.10f
    val scaleBottom = topAreaHeight * 0.55f
    val labelY = topAreaHeight * 0.76f
    val indicatorY = topAreaHeight * 1.00f

    // 刻度线：step 5°，10° 长刻度，左右对称
    var degrees = -rangeDegrees.toInt()
    while (degrees <= rangeDegrees.toInt()) {
        val x = centerX + degrees * (span / rangeDegrees)
        val isMajor = degrees % 10 == 0
        val top = if (isMajor) scaleTop else scaleTop + topAreaHeight * 0.14f
        drawLine(
            color = palette.ladder,
            start = Offset(x, top),
            end = Offset(x, scaleBottom),
            strokeWidth = if (isMajor) paints.mediumStroke else paints.minorStroke,
        )
        degrees += 5
    }

    // 小号数字（每 10°）
    paints.setTextColor(paints.caption, palette.ladder)
    paints.caption.textAlign = android.graphics.Paint.Align.CENTER
    degrees = -rangeDegrees.toInt()
    while (degrees <= rangeDegrees.toInt()) {
        if (degrees % 10 == 0) {
            val x = centerX + degrees * (span / rangeDegrees)
            drawContext.canvas.nativeCanvas.drawText(
                abs(degrees).toString(),
                x,
                labelY,
                paints.caption,
            )
        }
        degrees += 10
    }

    // 浮动指示：**尖角向上**的三角形，位于刻度下方。
    // 方向映射不变：pointerX = centerX + rollPointerOffset(roll)（右倾 → 向左）。
    val pointerX = centerX + HudProjection.rollPointerOffset(roll, span, rangeDegrees)
    val size = topAreaHeight * 0.10f
    drawPath(
        Path().apply {
            moveTo(pointerX, indicatorY - size * 1.6f)
            lineTo(pointerX - size, indicatorY)
            lineTo(pointerX + size, indicatorY)
            close()
        },
        color = palette.pointer,
    )
    paints.caption.textAlign = android.graphics.Paint.Align.LEFT
}
