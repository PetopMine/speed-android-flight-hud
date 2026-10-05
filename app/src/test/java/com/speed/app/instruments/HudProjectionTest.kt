package com.speed.app.instruments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * HUD 球面等距投影的单元测试（Phase 9，1.09）。
 *
 * 验证：
 *  - 当前俯仰刻度始终在屏幕中心
 *  - 可见范围 = pitch ± FOV/2
 *  - 地平线方向（pitch>0 时上移 = 透过手机看地面）
 *  - ±90° 及越过天顶（91/95/100）全程连续、单调、有限、无 NaN/Inf
 *  - 边缘不拉伸：等距投影相邻刻度间距恒定（tan 透视才会边缘拉伸）
 *  - 球面折返标签：90 → 80 → 70（而非 100/110）
 *  - 滚转指示器映射（右倾 → 指针向左）与宽版范围
 */
class HudProjectionTest {

    private val height = 1000f
    private val centerY = 500f
    private val focal = HudProjection.focalFor(height)

    private fun assertAngleEquals(expected: Float, actual: Float, tolerance: Float = 0.01f) {
        assertEquals("angle", expected, actual, tolerance)
    }

    @Test
    fun focal_matchesHalfHeightAtFovEdge() {
        // 等距投影：radians(FOV/2) × focal = h/2
        val halfFovRad = Math.toRadians(40.0).toFloat()
        assertEquals(height / 2f, halfFovRad * focal, 0.5f)
    }

    @Test
    fun centerTick_isAtCenterY_forAnyPitch() {
        // 屏幕中心刻度 = 当前 pitch 的对称值（−pitch），其标签 = |pitch|：
        // PITCH=−60 时中心刻度标签为 60。刻度线在 rel=0 → centerY。
        for (pitch in listOf(-90f, -45f, 0f, 30f, 45f, 90f)) {
            val y = HudProjection.screenY(centerY, -pitch, pitch, focal)
            assertAngleEquals(centerY, y, 0.01f)
        }
    }

    @Test
    fun visibleRange_isPitchPlusMinus40() {
        val rel = HudProjection.visibleRelativeAngles(30f)
        val min = rel.minOrNull()!!
        val max = rel.maxOrNull()!!
        assertTrue("min $min <= -40", min <= -40f)
        assertTrue("max $max >= 40", max >= 40f)
        assertTrue("min >= -50", min >= -50f)
        assertTrue("max <= 50", max <= 50f)
    }

    @Test
    fun horizonMovesUp_forNegativePitch() {
        // 本轮定案：PITCH 负（屏幕朝上）→ 地平线上移（y < centerY）→ 红色占多，
        // 指针位于红色区域并指向 |pitch| 刻度（PITCH=−60 → 红区 60°）。
        val yUp = HudProjection.screenY(centerY, 0f, -30f, focal)
        assertTrue("PITCH=−30 地平线应上移", yUp < centerY)
        val yDown = HudProjection.screenY(centerY, 0f, 30f, focal)
        assertTrue("PITCH=+30 地平线应下移", yDown > centerY)
        assertAngleEquals(centerY - yUp, yDown - centerY)
    }

    // ---------------------------------------------------------------
    // 地平线锁定映射（本轮定案：−60 → 红区；红蓝分界 / 刻度 / 指针同坐标）
    // ---------------------------------------------------------------

    @Test
    fun horizonScreenY_pitchMinus60_isAboveCenter() {
        // PITCH = −60 → horizonY < centerY（上移）→ 红色区域占多，
        // 屏幕中心的指针位于红色区域（验收："−60 → 指针指向红区 60° 刻度"）。
        val y = HudProjection.horizonScreenY(centerY, -60f, focal)
        assertTrue("−60 应上移: y=$y < $centerY", y < centerY)
    }

    @Test
    fun horizonScreenY_pitch0_isCentered() {
        assertEquals(centerY, HudProjection.horizonScreenY(centerY, 0f, focal), 0.01f)
    }

    @Test
    fun horizonScreenY_pitchPlus60_isBelowCenter() {
        // PITCH = +60 → horizonY > centerY（下移）→ 蓝色区域占多
        val y = HudProjection.horizonScreenY(centerY, 60f, focal)
        assertTrue("+60 应下移: y=$y > $centerY", y > centerY)
    }

    @Test
    fun horizonScreenY_monotonicAndMatchesSpec() {
        // −90 → 0 → +90 连续单调：pitch 增大 → horizonY 单调增大
        var prev: Float? = null
        for (p in -90..90 step 15) {
            val y = HudProjection.horizonScreenY(centerY, p.toFloat(), focal)
            if (prev != null) assertTrue("pitch=$p 单调性破坏", y > prev!!)
            prev = y
        }
    }

    @Test
    fun horizonScreenY_consistentWithTicks() {
        // 地平线与梯尺同源：horizonScreenY(pitch) == screenY(0, pitch)
        for (p in listOf(-75f, -30f, 0f, 30f, 75f)) {
            assertEquals(
                HudProjection.screenY(centerY, 0f, p, focal),
                HudProjection.horizonScreenY(centerY, p, focal),
                0.01f,
            )
        }
    }

    // ---------------------------------------------------------------
    // 本轮核心：当前指针 / 刻度 / 红蓝分界共享同一角度坐标
    // ---------------------------------------------------------------

    @Test
    fun currentPitchIndicator_matchesCurrentPitchTick() {
        // indicatorScreenY(currentPitch) == screenY(当前刻度, pitch)。
        // 当前刻度 = −pitch（rel=0），标签 = |pitch|。
        for (p in listOf(-60f, -30f, 0f, 30f, 60f, 90f)) {
            val indicatorY = HudProjection.currentPitchIndicatorScreenY(centerY, p, focal)
            val tickY = HudProjection.screenY(centerY, -p, p, focal)
            assertEquals("pitch=$p 指针与刻度必须同位置", tickY, indicatorY, 0.01f)
            assertEquals("pitch=$p 指针应位于屏幕中心", centerY, indicatorY, 0.01f)
        }
    }

    @Test
    fun currentPitchIndicator_labelMatchesPitchMagnitude() {
        // PITCH=−60 → 指针指向的刻度标签 = pitchLabel(−(−60)) = 60
        assertEquals(60, HudProjection.pitchLabel(-(-60)))
        assertEquals(90, HudProjection.pitchLabel(-90))
        assertEquals(30, HudProjection.pitchLabel(30))
    }

    @Test
    fun redBlueAreaAndIndicatorShareSamePitchCoordinate() {
        // 红蓝分界（地平线）与指针用同一坐标：
        // PITCH=−60 → 地平线上移（红区覆盖中心）→ 指针（中心）在红区。
        for (p in listOf(-60f, -30f, 0f, 30f, 60f)) {
            val horizonY = HudProjection.horizonScreenY(centerY, p, focal)
            val indicatorY = HudProjection.currentPitchIndicatorScreenY(centerY, p, focal)
            if (p == 0f) {
                // 平衡点：指针与地平线重合（红蓝分界处）
                assertEquals(centerY, horizonY, 0.01f)
                assertEquals(centerY, indicatorY, 0.01f)
            } else {
                val indicatorInRed = indicatorY >= horizonY // 红区 = 地平线以下
                val expectedRed = p < 0f
                assertEquals("pitch=$p 指针与红区归属不符", expectedRed, indicatorInRed)
            }
        }
    }

    @Test
    fun pitchMinus60_pointerInRedAreaAt60Tick() {
        // 本轮明确验收：PITCH=−60 → 指针指向红色区域的 60° 刻度。
        val p = -60f
        val horizonY = HudProjection.horizonScreenY(centerY, p, focal)
        val indicatorY = HudProjection.currentPitchIndicatorScreenY(centerY, p, focal)
        assertTrue("−60 指针应在红区（indicatorY >= horizonY）", indicatorY >= horizonY)
        assertEquals(60, HudProjection.pitchLabel((-p).toInt()))
    }

    @Test
    fun equidistant_noEdgeStretching() {
        // 等距投影：任意位置相邻 1° 的屏幕间距**恒定**（旧 tan 投影在边缘拉伸）
        val base = HudProjection.screenY(centerY, 0f, 0f, focal)
        val step1 = HudProjection.screenY(centerY, 1f, 0f, focal) - base
        val nearEdge = HudProjection.screenY(centerY, 39f, 0f, focal) -
            HudProjection.screenY(centerY, 38f, 0f, focal)
        assertEquals("边缘间距应等于中心间距（无拉伸）", step1, nearEdge, 0.5f)
    }

    @Test
    fun pitchContinuous_fromMinus90toPlus90() {
        var previous: Float? = null
        for (pitch in -90..90 step 1) {
            val y = HudProjection.screenY(centerY, 0f, pitch.toFloat(), focal)
            if (previous != null) {
                val delta = y - previous!!
                // 本轮定案：pitch 增大 → 地平线单调下移（delta ≥ 0），每度位移恒定有限
                assertTrue("单调性被破坏: pitch=$pitch delta=$delta", delta >= 0f)
                assertTrue("单步位移过大: $delta", abs(delta) < height)
            }
            previous = y
        }
    }

    @Test
    fun projection_finiteAcrossAndBeyondPoles() {
        // 越过天顶（91/95/100）与天底（-91/-95/-100）都必须有限、无 NaN/Inf，
        // 且位置连续（相邻度无跳变）
        val angles = listOf(-100f, -95f, -91f, -90f, -89f, -45f, 0f, 45f, 89f, 90f, 91f, 95f, 100f)
        var previous: Float? = null
        for (deg in angles) {
            val y = HudProjection.screenY(centerY, deg, 0f, focal)
            assertFalse("deg=$deg 产出 NaN", y.isNaN())
            assertFalse("deg=$deg 产出 Inf", y.isInfinite())
            assertTrue("deg=$deg 坐标应有限", abs(y) < 1e7f)
            if (previous != null) {
                val delta = abs(y - previous!!)
                assertTrue("deg=$deg 出现跳变: $delta", delta < height)
            }
            previous = y
        }
    }

    @Test
    fun pitchLabel_foldsBeyond90() {
        // 越过天顶后标签递减：90 → 80 → 70；负侧同理
        assertEquals(90, HudProjection.pitchLabel(90))
        assertEquals(80, HudProjection.pitchLabel(100))
        assertEquals(70, HudProjection.pitchLabel(110))
        assertEquals(60, HudProjection.pitchLabel(120))
        assertEquals(80, HudProjection.pitchLabel(-100))
        assertEquals(45, HudProjection.pitchLabel(45))
        assertEquals(0, HudProjection.pitchLabel(0))
        // 极点镜像对称：110 与 70 同标签（球面上是同一个俯仰圆）
        assertEquals(HudProjection.pitchLabel(70), HudProjection.pitchLabel(110))
    }

    @Test
    fun tenDegreeTicks_spacingSymmetricAroundCenter() {
        val up = HudProjection.screenY(centerY, 10f, 0f, focal)
        val down = HudProjection.screenY(centerY, -10f, 0f, focal)
        assertAngleEquals(centerY - up, down - centerY)
    }

    // ---------------------------------------------------------------
    // 左右倾斜指示器映射（用户验收：右倾 → 指针向左）
    // ---------------------------------------------------------------

    @Test
    fun rollIndicator_rightTilt_pointerMovesLeft() {
        for (deg in listOf(10f, 20f, 30f)) {
            val offset = HudProjection.rollPointerOffset(roll = deg, span = 300f, rangeDegrees = 30f)
            assertTrue("右倾 $deg° 应向左 (offset=${offset})", offset < 0f)
        }
    }

    @Test
    fun rollIndicator_leftTilt_pointerMovesRight() {
        for (deg in listOf(-10f, -20f, -30f)) {
            val offset = HudProjection.rollPointerOffset(roll = deg, span = 300f, rangeDegrees = 30f)
            assertTrue("左倾 $deg° 应向右 (offset=${offset})", offset > 0f)
        }
    }

    @Test
    fun rollIndicator_zero_isCentered() {
        assertEquals(0f, HudProjection.rollPointerOffset(0f, 300f, 30f), 1e-4f)
    }

    @Test
    fun rollIndicator_linearAt10Degrees() {
        assertEquals(-100f, HudProjection.rollPointerOffset(10f, 300f, 30f), 1e-4f)
        assertEquals(100f, HudProjection.rollPointerOffset(-10f, 300f, 30f), 1e-4f)
    }

    @Test
    fun rollIndicator_clampsBeyondRange() {
        val full = HudProjection.rollPointerOffset(30f, 300f, 30f)
        assertEquals(full, HudProjection.rollPointerOffset(60f, 300f, 30f), 1e-4f)
    }

    @Test
    fun rollRange_wideUses45_normalUses30() {
        assertEquals(45f, HudProjection.rollRangeFor(2400f, 1080f), 1e-4f)
        assertEquals(30f, HudProjection.rollRangeFor(1200f, 1080f), 1e-4f)
        assertEquals(30f, HudProjection.rollRangeFor(1080f, 2400f), 1e-4f)
    }

    // ---------------------------------------------------------------
    // 天地色块覆盖（历史 bug：pitch 接近 ±90° 时 reach 不足 → 黑色背景）
    // ---------------------------------------------------------------

    @Test
    fun horizonReach_coversGroundAtPitch90() {
        // 竖屏面板：pitch=+90 时地平线在 centerY − radians(90)×focal（屏幕上方很远），
        // 地面块 [horizonY, horizonY+reach] 必须覆盖到屏幕底（height）。
        val w = 1080f
        val h = 1200f
        val focal = HudProjection.focalFor(h)
        val reach = HudProjection.horizonReach(w, h, focal)
        val horizonY = HudProjection.screenY(h / 2f, 0f, 90f, focal)
        assertTrue("pitch=90 地面块底应覆盖屏幕底: horizonY=$horizonY reach=$reach",
            horizonY + reach >= h)
        // 天空块 [horizonY−reach, horizonY] 覆盖屏幕顶
        assertTrue("pitch=90 天空块顶应覆盖屏幕顶", horizonY - reach <= 0f)
    }

    @Test
    fun horizonReach_coversSkyAtPitchMinus90() {
        val w = 1080f
        val h = 1200f
        val focal = HudProjection.focalFor(h)
        val reach = HudProjection.horizonReach(w, h, focal)
        val horizonY = HudProjection.screenY(h / 2f, 0f, -90f, focal)
        // pitch=−90：地平线在下方很远，天空块 [horizonY−reach, horizonY] 覆盖屏幕顶
        assertTrue("pitch=−90 天空块顶应覆盖屏幕顶: horizonY=$horizonY",
            horizonY - reach <= 0f)
        assertTrue("pitch=−90 地面块底应覆盖屏幕底", horizonY + reach >= h)
    }

    @Test
    fun horizonReach_coversAfterRollRotation() {
        // roll 旋转绕屏幕中心：色块对角半径 − 块中心偏移必须超过屏幕对角线半径，
        // 否则旋转后角落露背景。验证最坏情况（pitch=±90）的不等式。
        val w = 1080f
        val h = 1200f
        val focal = HudProjection.focalFor(h)
        val reach = HudProjection.horizonReach(w, h, focal)
        val maxShift = Math.toRadians(90.0).toFloat() * focal
        val horizonY = h / 2f - maxShift // pitch=+90
        val blockCenterY = horizonY + reach / 2f
        val centerOffset = abs(blockCenterY - h / 2f)
        val blockCornerRadius = Math.sqrt(((2 * reach) * (2 * reach) + reach * reach).toDouble()).toFloat() / 2f
        val halfDiag = Math.sqrt((w * w + h * h).toDouble()).toFloat() / 2f
        assertTrue(
            "旋转后块角半径 − 偏移应覆盖屏幕对角线: $blockCornerRadius - $centerOffset >= $halfDiag",
            blockCornerRadius - centerOffset >= halfDiag,
        )
    }
}
