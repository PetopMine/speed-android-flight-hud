package com.speed.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 升降率数据源的单元测试（本轮要求：
 * **升降率必须跟随当前高度数据源**，GPS 模式不需要气压计）。
 *
 * [VerticalSpeedTracker] 是纯逻辑（无 Android / 传感器依赖），
 * 气压与 GPS 各持一份实例 —— 因此这里既能验证 GPS 路径（无气压计也能算出升降率），
 * 也能验证两条历史互不污染。
 */
class VerticalSpeedTrackerTest {

    private val tracker = VerticalSpeedTracker()

    private fun feed(vararg samples: Pair<Float, Long>) {
        samples.forEach { (altitude, timestampNs) -> tracker.onSample(altitude, timestampNs) }
    }

    /** GPS 高度上升 → 升降率 > 0（纯 GPS 路径，不涉及 TYPE_PRESSURE）。 */
    @Test
    fun risingAltitude_producesPositiveVerticalSpeed() {
        feed(
            100f to 0L,
            101f to 1_000_000_000L,
            102f to 2_000_000_000L,
            103f to 3_000_000_000L,
        )
        val vs = tracker.verticalSpeed
        assertNotNull("三帧之后应有升降率", vs)
        assertTrue("GPS 高度上升应得到正升降率，实际 $vs", vs!! > 0f)
    }

    /** GPS 高度下降 → 升降率 < 0。 */
    @Test
    fun fallingAltitude_producesNegativeVerticalSpeed() {
        feed(
            200f to 0L,
            199f to 1_000_000_000L,
            198f to 2_000_000_000L,
            197f to 3_000_000_000L,
        )
        val vs = tracker.verticalSpeed
        assertNotNull(vs)
        assertTrue("GPS 高度下降应得到负升降率，实际 $vs", vs!! < 0f)
    }

    /** 第一帧只建立基线（没有历史可差分）→ 升降率为 null，而不是伪造 0。 */
    @Test
    fun firstSample_onlyEstablishesBaseline() {
        feed(500f to 0L)
        assertNull("首帧没有升降率", tracker.verticalSpeed)
        assertEquals("首帧平滑高度 = 原始高度", 500f, tracker.smoothedAltitude!!, 1e-3f)
    }

    /** 时间跨度过小的两帧不做差分（避免除以极小 dt 放大噪声）。 */
    @Test
    fun tooShortInterval_isIgnored() {
        feed(
            100f to 0L,
            140f to 10_000_000L, // 10ms
        )
        assertNull("10ms 间隔不做差分", tracker.verticalSpeed)
    }

    /** 同样的高度序列在两条独立实例上互不影响（气压 / GPS 各自 history）。 */
    @Test
    fun independentTrackers_doNotShareHistory() {
        val barometer = VerticalSpeedTracker()
        val gps = VerticalSpeedTracker()

        // 气压在下降，GPS 在上升，时间戳与历史完全不同
        barometer.onSample(100f, 0L)
        barometer.onSample(99f, 1_000_000_000L)
        gps.onSample(1000f, 0L)
        gps.onSample(1002f, 1_000_000_000L)
        gps.onSample(1004f, 2_000_000_000L)

        assertTrue("气压侧应为负", barometer.verticalSpeed!! < 0f)
        assertTrue("GPS 侧应为正", gps.verticalSpeed!! > 0f)
        assertEquals("GPS 侧基线不受气压侧影响", 1000f, gps.smoothedAltitude!!, 20f)
    }

    /** reset 后历史清空：下一帧重新建立基线，不会因为长时间间隔产生假升降率。 */
    @Test
    fun reset_clearsHistory() {
        feed(
            100f to 0L,
            120f to 1_000_000_000L,
        )
        assertNotNull(tracker.verticalSpeed)
        tracker.reset()
        assertNull("reset 后升降率清空", tracker.verticalSpeed)
        assertNull("reset 后高度基线清空", tracker.smoothedAltitude)
        tracker.onSample(5000f, 0L)
        assertNull("reset 后首帧只建立基线", tracker.verticalSpeed)
        assertEquals(5000f, tracker.smoothedAltitude!!, 1e-3f)
    }
}
