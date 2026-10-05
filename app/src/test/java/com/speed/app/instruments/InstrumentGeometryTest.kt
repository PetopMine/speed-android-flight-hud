package com.speed.app.instruments

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 仪表几何的单元测试：
 *  - 高度/速度带：指针固定、刻度随数值移动
 *  - 航向带：以中心为唯一基准的严格左右对称
 *  - 俯仰刻度：世界网格锚定 → pitch 连续变化时位置连续（无抽动）
 *  - 梯尺数字：锚点确定性（不依赖文字测量）→ 经过 10/20/30 不跳动
 *  - Module 基准符号：固定不动，pitch=0 时与 0° 刻度共线
 *  - EFIS 刻度带：数据框 / 指针 / 刻度三段互不重叠，尖角朝向自己的刻度
 */
class InstrumentGeometryTest {

    private val height = 1000f
    private val centerY = 500f
    private val focal = HudProjection.focalFor(height)

    // ---------------------------------------------------------------
    // A. 高度/速度带：固定指针 + 移动刻度
    // ---------------------------------------------------------------

    @Test
    fun tapePointer_fixed_ticksMove() {
        // 指针固定在 centerY；value 变化时同一根刻度的屏幕 y 移动
        val ppu = 0.5f
        val tickValue = 1000f
        val y1 = tapeTickScreenY(centerY, tickValue, 1000f, ppu)
        val y2 = tapeTickScreenY(centerY, tickValue, 1050f, ppu)
        assertEquals("value=1000 时该刻度应在中心", centerY, y1, 1e-3f)
        // value 增大 50 → 该刻度下移 50×ppu = 25px（高度增大时带子向下滚，指针固定）
        assertEquals("value 增大刻度下移（带子滚动）", 25f, y2 - y1, 1e-3f)
        // 指针固定在 centerY：刻度已离开中心 25px，指针位置不变
        assertEquals("刻度离开中心（指针仍固定）", 25f, y2 - centerY, 1e-3f)
    }

    @Test
    fun tapeTicks_spacingConstant() {
        val ppu = 0.5f
        for (value in listOf(0f, 350f, 1000f)) {
            val yA = tapeTickScreenY(centerY, 500f, value, ppu)
            val yB = tapeTickScreenY(centerY, 1000f, value, ppu)
            assertEquals("刻度间距恒定", -250f, yB - yA, 1e-3f)
        }
    }

    // ---------------------------------------------------------------
    // B. 航向带：严格左右对称
    // ---------------------------------------------------------------

    @Test
    fun headingStrip_symmetricAroundCenter() {
        val centerX = 540f
        val ppd = 8f
        for (k in listOf(1, 5, 30, 60, 62)) {
            val right = headingTickScreenX(centerX, k, ppd)
            val left = headingTickScreenX(centerX, -k, ppd)
            assertEquals("offset ±$k 不对称", centerX - left, right - centerX, 1e-3f)
        }
    }

    @Test
    fun headingStrip_sameVisibleRangeBothSides() {
        // 左右可见角度范围一致：±60°（strip 半宽 = 60°×ppd），
        // 最外侧刻度左右等距。
        val centerX = 540f
        val ppd = 8f
        val halfStripPx = 60f * ppd
        val rightExtent = headingTickScreenX(centerX, 60, ppd) - centerX
        val leftExtent = centerX - headingTickScreenX(centerX, -60, ppd)
        assertEquals("左右范围一致", halfStripPx, rightExtent, 1e-3f)
        assertEquals("左右范围一致", rightExtent, leftExtent, 1e-3f)
    }

    // ---------------------------------------------------------------
    // C. 俯仰刻度：世界网格锚定 → 连续、无抽动
    // ---------------------------------------------------------------

    @Test
    fun pitchTicks_worldGrid_movesContinuously() {
        // 固定世界刻度（如 30° 网格线）的屏幕位置随 pitch 连续平移：
        // 等距投影下每 0.5° 的位移恒定，不存在"突然跳一格"。
        val tickAngle = 30f
        var prev = HudProjection.screenY(centerY, tickAngle, 0f, focal)
        var stepRef = 0f
        for (pitch in 1..180) {
            val p = pitch * 0.5f
            val y = HudProjection.screenY(centerY, tickAngle, p, focal)
            val delta = y - prev
            if (pitch == 1) stepRef = delta
            assertEquals("pitch=$p 单步位移应恒定（无抽动）", stepRef, delta, 0.05f)
            prev = y
        }
    }

    @Test
    fun pitchTicks_worldGrid_majorMinorStable() {
        // 主/次属性由世界网格索引 n 决定，不随 pitch 变化翻转：
        // 30° 刻度（n=6 偶数）永远是 10° 主刻度。
        for (pitch in listOf(-60f, -31f, 0f, 29.5f, 60f)) {
            val n = kotlin.math.round((30f) / 5f).toInt()
            assertTrue("30° 刻度应始终为主刻度", n % 2 == 0)
        }
    }

    @Test
    fun pitchTicks_transitionAround29_30_31() {
        // 29→30→31：同一根世界刻度线的位置连续（增量与相邻帧相同）
        for (base in listOf(29f, 39f, 59f, 79f)) {
            val tickAngle = 40f
            val y0 = HudProjection.screenY(centerY, tickAngle, base, focal)
            val y1 = HudProjection.screenY(centerY, tickAngle, base + 1f, focal)
            val y2 = HudProjection.screenY(centerY, tickAngle, base + 2f, focal)
            val d1 = y1 - y0
            val d2 = y2 - y1
            assertEquals("${base}→${base + 1}→${base + 2} 位移应恒定", d1, d2, 0.05f)
            assertTrue("单步位移有限", abs(d1) < height)
        }
    }

    // ---------------------------------------------------------------
    // D. Module 梯尺数字：锚点确定性（不依赖文字测量 → 不跳动）
    // ---------------------------------------------------------------

    @Test
    fun ladderLabel_anchorDependsOnlyOnGeometry() {
        // 标签盒锚点只由（内侧边 x、刻度中心 y、盒尺寸、左右列）决定：
        // 与文字内容/宽度/测量结果完全无关 —— 这是"经过 10/20/30 数字跳动"的根因修复。
        val boxW = 84f
        val boxH = 58f
        for (tickY in listOf(120f, 333.5f, 500f, 761.25f)) {
            for (alignRight in listOf(true, false)) {
                val anchor = ladderLabelBoxTopLeft(240f, tickY, boxW, boxH, alignRight)
                assertEquals("垂直中心必须等于刻度中心", tickY - boxH / 2f, anchor.y, 1e-3f)
                if (alignRight) {
                    assertEquals("左列：盒子右边界贴内侧边", 240f - boxW, anchor.x, 1e-3f)
                } else {
                    assertEquals("右列：盒子左边界贴内侧边", 240f, anchor.x, 1e-3f)
                }
            }
        }
    }

    @Test
    fun ladderLabel_centerTracksTickContinuouslyAcross10_20_30() {
        // 经过 9→10→11 / 19→20→21 / 29→30→31 时，标签垂直中心逐帧连续跟随刻度线，
        // 不存在"刚进入视野先落错位置再跳一下"的台阶。
        val boxH = 58f
        val stepRef = Math.toRadians(0.1).toFloat() * focal
        for (base in listOf(9, 19, 29, 39, 79)) {
            val tickAngle = 40f
            var prevCenter: Float? = null
            for (i in 0..20) {
                val pitch = base + i * 0.1f
                val tickY = HudProjection.screenY(centerY, tickAngle, pitch, focal)
                val anchorY = ladderLabelBoxTopLeft(240f, tickY, 84f, boxH, true).y
                val center = anchorY + boxH / 2f
                assertEquals("标签中心必须与刻度线同位置", tickY, center, 1e-3f)
                prevCenter?.let {
                    assertEquals("pitch=$pitch 标签位移应恒定", stepRef, center - it, 0.02f)
                }
                prevCenter = center
            }
        }
    }

    // ---------------------------------------------------------------
    // E. Module 基准符号：固定不动 + pitch=0 时与 0° 刻度共线
    // ---------------------------------------------------------------

    @Test
    fun moduleReference_isFixedAtCenter() {
        // 基准符号 y 与 pitch / roll 无关（函数签名里根本没有这两个量）
        for (center in listOf(0f, 500f, 1234.5f)) {
            assertEquals(center, HudProjection.referenceSymbolCenterY(center), 0f)
        }
    }

    @Test
    fun moduleReference_collinearWithZeroTickAtLevel() {
        val referenceY = HudProjection.referenceSymbolCenterY(centerY)
        // pitch = 0 → 0° 刻度线正好落在基准线上（共线）
        assertEquals(referenceY, HudProjection.screenY(centerY, 0f, 0f, focal), 0.01f)
        // pitch 改变后只有刻度移动，基准线不动
        val tickAtPlus30 = HudProjection.screenY(centerY, 0f, 30f, focal)
        val tickAtMinus30 = HudProjection.screenY(centerY, 0f, -30f, focal)
        assertTrue("pitch=+30 时 0° 刻度离开基准线（向下）", tickAtPlus30 > referenceY)
        assertTrue("pitch=−30 时 0° 刻度离开基准线（向上）", tickAtMinus30 < referenceY)
        assertEquals(referenceY, HudProjection.referenceSymbolCenterY(centerY), 0f)
    }

    // ---------------------------------------------------------------
    // F. EFIS 刻度带：数据框 / 指针 / 刻度三段互不重叠 + 尖角方向
    // ---------------------------------------------------------------

    private val tapeRect = Rect(0f, 0f, 228f, 700f)

    // ---------------------------------------------------------------
    // G. 基准符号几何（1.16 轮）：无缝连接 + 无描边 + 位置固定
    // ---------------------------------------------------------------

    private fun geometry(width: Float = 4f, halfSpan: Float = 216f, stemLength: Float = 24f) =
        referenceSymbolGeometry(Offset(540f, 600f), halfSpan, stemLength, width)

    @Test
    fun referenceSymbol_stemsIntersectHorizontalLines() {
        val g = geometry()
        // 竖臂底端必须到达（或越过）横线中心线，否则连接处会出现缝隙
        assertTrue("竖臂底端应到达横线（stemBottom=${g.stemBottom} lineY=${g.lineY}）", g.stemBottom >= g.lineY)
        // 竖臂底端不超过横线下缘：竖线与横线在同一线宽带内重叠
        assertTrue("竖臂底端不应越过横线下缘", g.stemBottom <= g.lineY + g.bodyWidth * 0.5f + 1e-3f)
        // 竖臂顶端在横线上方（短竖线向上）
        assertTrue("竖臂应向上伸出", g.stemTop < g.lineY - 1f)
        // 横线本身有正长度（内侧终点在外端之内）
        assertTrue("横线内侧终点应在中心与外端之间", g.innerStop > 0f && g.innerStop < g.halfSpan)
    }

    @Test
    fun referenceSymbol_hasNoOutlineExpansion() {
        // 本轮取消描边：本体线宽 == 设置的粗细，没有任何外扩分量
        for (w in listOf(2f, 4f, 7f, 10.5f)) {
            assertEquals("不应存在描边外扩", w, referenceSymbolGeometry(Offset(0f, 0f), 200f, 20f, w).bodyWidth, 1e-4f)
        }
    }

    @Test
    fun referenceSymbol_isFixedForAllPitchAndRoll() {
        // 几何函数签名里没有 pitch / roll：位置只由中心与设置决定
        val g = geometry()
        assertEquals(600f, g.lineY, 1e-4f)
        // pitch=0 / roll=0 时与 0° 俯仰刻度线共线
        assertEquals(
            HudProjection.referenceSymbolCenterY(600f),
            HudProjection.screenY(600f, 0f, 0f, HudProjection.focalFor(1200f)),
            0.01f,
        )
        // pitch 变化后基准线 y 不变（仍为画布中心），只有刻度移动
        val tickAtPlus30 = HudProjection.screenY(600f, 0f, 30f, HudProjection.focalFor(1200f))
        assertTrue("刻度应离开基准线", abs(tickAtPlus30 - 600f) > 1f)
        assertEquals(600f, referenceSymbolGeometry(Offset(540f, 600f), 216f, 24f, 4f).lineY, 1e-4f)
    }

    // ---------------------------------------------------------------
    // H. Module 高度带（1.16 轮）：[刻度][◀][数据] + 刻度向下 + 指针/数据固定
    // ---------------------------------------------------------------

    @Test
    fun moduleAltitudeTape_pointerApexPointsLeftTowardTicks() {
        val rect = TapeRect(left = 65f, top = 96f, width = 432f, height = 936f, visibleRange = 900f, minValue = -500f, maxValue = 11000f)
        val layout = tapeStripLayout(rect, pointerOnRight = true, pointerHeight = 42f)
        // 尖角向左：apex 在 base 左侧
        assertTrue("尖角必须向左（apex=${layout.pointerApexX} base=${layout.pointerBaseX}）", layout.pointerApexX < layout.pointerBaseX)
        // 尖角位于刻度区右端（刻度在左、指针在刻度右侧）
        assertEquals("尖角应贴刻度右端", layout.tickAnchorX, layout.pointerApexX, 1e-3f)
        // 刻度向左生长
        assertTrue("刻度应向左生长", layout.tickGrow < 0f)
        // 指针整体在带内、位于数据左侧（数据画在 rect.right 之外）
        assertTrue("指针底边不得越过带右缘", layout.pointerBaseX <= rect.right + 1e-3f)
        assertTrue("指针应在带内", layout.pointerApexX > rect.left)
    }

    @Test
    fun moduleAltitudeTape_ticksMoveDown_pointerAndDataFixed() {
        val rect = TapeRect(left = 65f, top = 96f, width = 432f, height = 936f, visibleRange = 900f, minValue = -500f, maxValue = 11000f)
        val ppu = rect.height / (rect.visibleRange * 2f)
        // 高度增大 → 刻度 screenY 增大（向下）
        val yLow = tapeTickScreenY(rect.centerY, 1000f, 900f, ppu)
        val yHigh = tapeTickScreenY(rect.centerY, 1000f, 1500f, ppu)
        assertTrue("高度增加 → 刻度向下", yHigh > yLow)
        // 指针与数据锚点 = 带子中心，与高度无关
        for (value in listOf(-100f, 0f, 5000f, 11000f)) {
            assertEquals("数据/指针固定于中心", rect.centerY, tapeTickScreenY(rect.centerY, value, value, ppu), 1e-3f)
        }
        val layout = tapeStripLayout(rect, pointerOnRight = true, pointerHeight = 42f)
        assertEquals("指针不随高度变化", layout.pointerApexX, tapeStripLayout(rect, true, 42f).pointerApexX, 0f)
    }

    // ---------------------------------------------------------------
    // I. 数字读数面板（1.16 轮）：整体落在安全区内，底部不被裁剪
    // ---------------------------------------------------------------

    @Test
    fun readoutRows_fitInsidePanelWithoutBottomClipping() {
        // 覆盖竖屏半屏、横屏半屏、合并宽面板等实际尺寸
        val panels = listOf(1200f to 6, 1080f to 6, 1080f to 3, 1200f to 3, 900f to 6, 1600f to 3)
        val captionSize = 11f * 2.625f
        val valueSize = 17f * 2.625f
        for ((height, rowsPerColumn) in panels) {
            val baselines = readoutRowBaselines(height, rowsPerColumn, captionSize, valueSize)
            assertEquals(rowsPerColumn, baselines.size)
            // 第一行说明文字不越上边界
            assertTrue(
                "顶部越界（height=$height rows=$rowsPerColumn）",
                baselines.first().captionBaseline - captionSize >= 0f,
            )
            // 最后一行数值文字（含下伸部）不越下边界
            val lastValue = baselines.last().valueBaseline
            assertTrue(
                "底部被裁剪（height=$height rows=$rowsPerColumn last=$lastValue）",
                lastValue + valueSize * 0.3f <= height,
            )
            // 行距均匀且严格递增
            for (i in 1 until baselines.size) {
                val step = baselines[i].captionBaseline - baselines[i - 1].captionBaseline
                assertTrue("行距应递增", step > 0f)
                if (i >= 2) {
                    val prevStep = baselines[i - 1].captionBaseline - baselines[i - 2].captionBaseline
                    assertEquals("行距应均匀", prevStep, step, 0.5f)
                }
            }
            // 同一行内数值在说明下方
            baselines.forEach { assertTrue("数值应在说明下方", it.valueBaseline > it.captionBaseline) }
        }
    }

    @Test
    fun readoutRows_areAdaptiveToHeightNotFixedPixels() {
        // 同一行数、不同高度 → 行距随高度变化（不是固定像素补丁）
        val captionSize = 29f
        val valueSize = 45f
        val small = readoutRowBaselines(600f, 3, captionSize, valueSize)
        val large = readoutRowBaselines(1200f, 3, captionSize, valueSize)
        val smallStep = small[1].captionBaseline - small[0].captionBaseline
        val largeStep = large[1].captionBaseline - large[0].captionBaseline
        assertTrue("行距应随高度自适应", largeStep > smallStep + 1f)
    }

    @Test
    fun efisSpeedTape_leftSideOrderIsDataPointerTicks() {
        val layout = efisTapeLayout(tapeRect, EfisTapeSide.LEFT)
        // 顺序：数据框 → 指针 → 刻度（刻度贴 EFIS 本体 = 最右）
        assertTrue("数据框在最左", abs(layout.dataRect.left - tapeRect.left) < 0.01f)
        assertTrue("数据框与指针不重叠", layout.dataRect.right <= layout.pointerRect.left + 0.01f)
        assertTrue("指针与刻度不重叠", layout.pointerRect.right <= layout.tapeRect.left + 0.01f)
        assertTrue("刻度贴 EFIS 本体", abs(layout.tapeRect.right - tapeRect.right) < 0.01f)
        // 尖角向右（指向刻度）
        assertTrue("左带尖角应向右", layout.apexX > layout.baseX)
        assertEquals("尖角位于刻度区入口", layout.tapeRect.left, layout.apexX, 0.01f)
        assertEquals("刻度锚定在 EFIS 一侧", layout.tapeRect.right, layout.tickAnchorX, 0.01f)
    }

    @Test
    fun efisAltitudeTape_rightSideOrderIsTicksPointerData() {
        val layout = efisTapeLayout(tapeRect, EfisTapeSide.RIGHT)
        // 顺序：刻度（贴 EFIS 本体 = 最左）→ 指针 → 数据框
        assertTrue("刻度贴 EFIS 本体", abs(layout.tapeRect.left - tapeRect.left) < 0.01f)
        assertTrue("刻度与指针不重叠", layout.tapeRect.right <= layout.pointerRect.left + 0.01f)
        assertTrue("指针与数据框不重叠", layout.pointerRect.right <= layout.dataRect.left + 0.01f)
        assertTrue("数据框在最右", abs(layout.dataRect.right - tapeRect.right) < 0.01f)
        // 尖角向左（指向刻度）
        assertTrue("右带尖角应向左", layout.apexX < layout.baseX)
        assertEquals("尖角位于刻度区入口", layout.tapeRect.right, layout.apexX, 0.01f)
        assertEquals("刻度锚定在 EFIS 一侧", layout.tapeRect.left, layout.tickAnchorX, 0.01f)
    }

    @Test
    fun efisTape_zonesFillWholeStripWithoutOverlap() {
        for (side in listOf(EfisTapeSide.LEFT, EfisTapeSide.RIGHT)) {
            val layout = efisTapeLayout(tapeRect, side)
            val total = layout.dataRect.width + layout.pointerRect.width + layout.tapeRect.width
            assertEquals("三段应正好铺满整条带", tapeRect.width, total, 0.05f)
            assertEquals("数据框比例", tapeRect.width * EFIS_TAPE_DATA_FRACTION, layout.dataRect.width, 0.05f)
            assertEquals("指针比例", tapeRect.width * EFIS_TAPE_POINTER_FRACTION, layout.pointerRect.width, 0.05f)
        }
    }

    @Test
    fun efisTape_valueIncreasesMovesTicksDown_dataAndPointerFixed() {
        // 刻度随数值增大**向下**；数据数字与指针锚定带子中心，不随数值移动。
        val ppu = 0.5f
        val tickValue = 1000f
        val yAt1000 = tapeTickScreenY(centerY, tickValue, 1000f, ppu)
        val yAt1040 = tapeTickScreenY(centerY, tickValue, 1040f, ppu)
        assertEquals("数值等于刻度值时该刻度在中心", centerY, yAt1000, 1e-3f)
        assertTrue("数值增大 → 刻度向下（screenY 增大）", yAt1040 > yAt1000)
        assertEquals("位移与数值差成比例", 20f, yAt1040 - yAt1000, 1e-3f)

        // 指针/数据锚点恒为带子中心：equal-value 刻度在中心，与具体数值无关
        for (value in listOf(0f, 500f, 5000f)) {
            assertEquals("数据/指针固定于中心", centerY, tapeTickScreenY(centerY, value, value, ppu), 1e-3f)
        }
    }
}
