package com.speed.app.attitude

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 姿态纯数学的单元测试（Phase 8）。
 *
 * 只验证数学关系（输入 → 输出的数值关系），不是"写死断言"：
 * 每个断言都对应 AttitudeMath 注释里给出的物理/几何证明中的一步。
 * 坐标系约定：device X=屏幕右、Y=屏幕上方、Z=屏幕外；
 * world X=东、Y=北、Z=天；R = device→world（列 = 设备轴在世界）。
 */
class AttitudeMathTest {

    private fun assertAngleEquals(expected: Float, actual: Float, tolerance: Float = 0.5f) {
        assertEquals("angle", expected, actual, tolerance)
    }

    // ---------------------------------------------------------------
    // Pitch：pitch = −asin(R[8])，屏幕朝上 = −90、竖直 = 0、屏幕朝下 = +90
    // ---------------------------------------------------------------

    @Test
    fun pitch_upright_isZero() {
        // 竖直：法线水平 → R[8] = 0
        assertAngleEquals(0f, AttitudeMath.pitchFromMatrix(0f))
    }

    @Test
    fun pitch_screenUp45_isNegative45() {
        // 屏幕朝上倾斜 45°：法线与天夹角 45° → R[8] = cos45° = √2/2
        val cos45 = 0.70710678f
        assertAngleEquals(-45f, AttitudeMath.pitchFromMatrix(cos45))
    }

    @Test
    fun pitch_screenUp_isNegative90() {
        // 屏幕完全朝上：法线 = 天 → R[8] = +1
        assertAngleEquals(-90f, AttitudeMath.pitchFromMatrix(1f))
    }

    @Test
    fun pitch_screenDown_isPositive90() {
        // 屏幕完全朝下：法线 = 地 → R[8] = −1
        assertAngleEquals(90f, AttitudeMath.pitchFromMatrix(-1f))
    }

    @Test
    fun pitch_clampsNoiseBeyondRange() {
        // R[8] 因噪声超出 ±1 时 coerceIn，不能输出 NaN/越界
        assertAngleEquals(-90f, AttitudeMath.pitchFromMatrix(1.2f))
        assertAngleEquals(90f, AttitudeMath.pitchFromMatrix(-1.2f))
    }

    // ---------------------------------------------------------------
    // Roll：roll = atan2(−R[6], R[7])，右倾为正
    // ---------------------------------------------------------------

    @Test
    fun roll_upright_isZero() {
        // 竖直（面向北）：设备 Y = 天 → 第 1 列 (R1,R4,R7)=(0,0,1)，R[7]=1；
        // 设备 X = 东 → R[6] = X 的天分量 = 0。atan2(0, 1) = 0。
        assertAngleEquals(0f, AttitudeMath.rollFromMatrix(r6 = 0f, r7 = 1f))
    }

    @Test
    fun roll_rightBank30_isPositive30() {
        // 右倾 30°（右侧压低）：重力在屏幕 x 分量 = +g·sin30，
        // 推导得 R[6] = −sin30、R[7] = cos30 → atan2(sin30, cos30) = +30
        val sin30 = 0.5f
        val cos30 = 0.8660254f
        assertAngleEquals(30f, AttitudeMath.rollFromMatrix(r6 = -sin30, r7 = cos30))
    }

    @Test
    fun roll_leftBank30_isNegative30() {
        // 左倾 30°：R[6] = +sin30、R[7] = cos30 → atan2(−sin30, cos30) = −30
        val sin30 = 0.5f
        val cos30 = 0.8660254f
        assertAngleEquals(-30f, AttitudeMath.rollFromMatrix(r6 = sin30, r7 = cos30))
    }

    @Test
    fun roll_rightBank45_isPositive45() {
        val sin45 = 0.70710678f
        assertAngleEquals(45f, AttitudeMath.rollFromMatrix(r6 = -sin45, r7 = sin45))
    }

    @Test
    fun rollDegenerate_nearHorizontal() {
        // 屏幕接近水平（R[8] → ±1）时 roll 数学上无定义，调用方应保持上一帧
        org.junit.Assert.assertTrue(AttitudeMath.isRollDegenerate(0.99f))
        org.junit.Assert.assertTrue(AttitudeMath.isRollDegenerate(-0.99f))
        org.junit.Assert.assertFalse(AttitudeMath.isRollDegenerate(0f))
        org.junit.Assert.assertFalse(AttitudeMath.isRollDegenerate(0.70f))
    }

    // ---------------------------------------------------------------
    // Heading：参考轴 = 机身 −Z（背面），fallback = 机身 +Y（顶部）
    // ---------------------------------------------------------------

    @Test
    fun heading_north() {
        // 竖持面向北：设备 X=东、Y=天、Z=南。背面 = −Z = 北。
        // R 行主序：col0=X=(1,0,0)，col1=Y=(0,0,1)，col2=Z=(0,−1,0)
        val r = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        assertAngleEquals(0f, AttitudeMath.headingFromMatrix(r))
    }

    @Test
    fun heading_east() {
        // 竖持背面朝东：Z=西 → 背面 = 东
        val r = floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f)
        assertAngleEquals(90f, AttitudeMath.headingFromMatrix(r))
    }

    @Test
    fun heading_south() {
        val r = floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f)
        assertAngleEquals(180f, AttitudeMath.headingFromMatrix(r))
    }

    @Test
    fun heading_west_is270() {
        val r = floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f)
        assertAngleEquals(270f, AttitudeMath.headingFromMatrix(r))
    }

    @Test
    fun heading_flatFaceUp_usesTopFallback() {
        // 屏幕朝上平放（背面朝下，primary 退化）：顶部朝东 → 90°
        // 设备 X=南、Y=东、Z=天
        val r = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        assertAngleEquals(90f, AttitudeMath.headingFromMatrix(r))
    }

    @Test
    fun heading_primaryAndFallback_neverBothDegenerate() {
        // 背面投影退化 ⟺ 顶部投影良好（两轴正交）：构造背面垂直（手机水平）时
        // fallback 分支必然有非零投影。此处验证数值路径不产生 NaN。
        val r = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        val h = AttitudeMath.headingFromMatrix(r)
        org.junit.Assert.assertFalse(h.isNaN())
    }

    @Test
    fun heading_diagonal_northEast() {
        // 竖持背面朝东北：Z = 西南 → 背面 = 东北
        val s = 0.70710678f
        // X = Y×Z，Z = (−s, −s, 0)，Y = (0,0,1) → X = (s·1−... ) 用正交构造：
        // X = Y×Z = (0,0,1)×(−s,−s,0) = (0·0−1·(−s), 1·(−s)−0·0, 0·(−s)−0·(−s)) = (s, −s, 0)
        val r = floatArrayOf(s, 0f, -s, -s, 0f, -s, 0f, 1f, 0f)
        assertAngleEquals(45f, AttitudeMath.headingFromMatrix(r))
    }

    // ---------------------------------------------------------------
    // 归一化与最短弧（0/360 环绕的核心）
    // ---------------------------------------------------------------

    @Test
    fun normalizeHeading_wraps() {
        assertEquals(270f, AttitudeMath.normalizeHeading(-90f), 1e-4f)
        assertEquals(0f, AttitudeMath.normalizeHeading(360f), 1e-4f)
        assertEquals(359f, AttitudeMath.normalizeHeading(-1f), 1e-4f)
        assertEquals(0f, AttitudeMath.normalizeHeading(720f), 1e-4f)
    }

    @Test
    fun shortestDelta_359to0_isPlus1() {
        // 359 → 0 是 +1°，不是 −359°
        assertEquals(1f, AttitudeMath.shortestAngularDelta(359f, 0f), 1e-4f)
    }

    @Test
    fun shortestDelta_0to359_isMinus1() {
        assertEquals(-1f, AttitudeMath.shortestAngularDelta(0f, 359f), 1e-4f)
    }

    @Test
    fun shortestDelta_1to359_isMinus2() {
        assertEquals(-2f, AttitudeMath.shortestAngularDelta(1f, 359f), 1e-4f)
    }

    @Test
    fun shortestDelta_358to2_isPlus4() {
        assertEquals(4f, AttitudeMath.shortestAngularDelta(358f, 2f), 1e-4f)
    }

    @Test
    fun shortestDelta_samePoint180() {
        // 180 与 −180 是同一点，delta = 0
        assertEquals(0f, AttitudeMath.shortestAngularDelta(180f, -180f), 1e-4f)
    }

    @Test
    fun smoothHeading_crossesNorthContinuously() {
        // 359 起步、目标 1：全量跟随时应连续穿过 0°（+2°），不绕整圈
        val s1 = AttitudeMath.smoothHeading(359f, 1f, alpha = 1f)
        assertEquals(1f, s1, 1e-4f)

        // 半量平滑：359 + (+2)×0.5 = 360 → 归一化 0
        val s2 = AttitudeMath.smoothHeading(359f, 1f, alpha = 0.5f)
        assertEquals(0f, s2, 1e-4f)
    }

    @Test
    fun smoothHeading_partialConvergence() {
        // 正常收敛：10 → 30，alpha 0.5 → 20
        assertEquals(20f, AttitudeMath.smoothHeading(10f, 30f, 0.5f), 1e-4f)
    }

    @Test
    fun smoothAngle_wrapsRoll() {
        // 179 → −179（+2° 跨 180）全量跟随 = −179
        assertEquals(-179f, AttitudeMath.smoothAngle(179f, -179f, 1f), 1e-4f)
    }

    // ---------------------------------------------------------------
    // display rotation 轴映射表（ROTATION_0/90/180/270）
    // ---------------------------------------------------------------

    @Test
    fun screenAxes_rotation0_identity() {
        assertEquals(Pair(0, 1), AttitudeMath.screenAxesForRotation(0))
    }

    @Test
    fun screenAxes_rotation90() {
        // 屏幕 X = 机身 +Y，屏幕 Y = 机身 −X
        assertEquals(Pair(1, 2), AttitudeMath.screenAxesForRotation(1))
    }

    @Test
    fun screenAxes_rotation180() {
        assertEquals(Pair(2, 3), AttitudeMath.screenAxesForRotation(2))
    }

    @Test
    fun screenAxes_rotation270() {
        assertEquals(Pair(3, 0), AttitudeMath.screenAxesForRotation(3))
    }
}
