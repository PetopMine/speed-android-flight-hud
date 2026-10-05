package com.speed.app.data

/**
 * 单一路径的高度/升降率滤波状态机（纯逻辑，无 Android 依赖，便于单元测试）。
 *
 * 为什么需要"每条数据源各自一份历史"：
 * 升降率必须与**当前高度来源**一致（GPS 模式用 GPS 高度差分、气压模式用气压高度差分），
 * 两个来源的采样率、噪声、时间戳完全不同，混用一份历史会在切换来源时
 * 产生巨大假升降率（时间戳跳变 + 高度跳变）。
 *
 * 处理流程：高度低通（[altitudeSmoothing]）→ 对平滑后高度做时间差分 →
 * 速率低通（[rateSmoothing]）→ 输出 m/s。
 */
class VerticalSpeedTracker(
    private val altitudeSmoothing: Float = DEFAULT_ALTITUDE_SMOOTHING,
    private val rateSmoothing: Float = DEFAULT_RATE_SMOOTHING,
) {

    /** 平滑后的高度（m）。首个样本直接作为初值，不做"从 0 拉上来"的假过渡。 */
    var smoothedAltitude: Float? = null
        private set

    /** 当前升降率（m/s）；样本不足（少于两帧或时间跨度过小）时为 null。 */
    var verticalSpeed: Float? = null
        private set

    private var lastTimestampNs = 0L

    /** 送入一帧高度样本（[timestampNs] 用 SensorEvent.timestamp / 单调时钟纳秒）。 */
    fun onSample(altitude: Float, timestampNs: Long) {
        // "有没有历史"用平滑高度是否为 null 判断，**不能用时间戳是否为 0** ——
        // 时间戳 0 是合法值（单调时钟起点 / 测试），用它当哨兵会吞掉第二帧的差分。
        val previousSmoothed = smoothedAltitude
        val previousTimestamp = lastTimestampNs
        val current = if (previousSmoothed == null) {
            altitude
        } else {
            previousSmoothed + altitudeSmoothing * (altitude - previousSmoothed)
        }
        smoothedAltitude = current
        lastTimestampNs = timestampNs

        if (previousSmoothed == null) return // 首帧（或 reset 后首帧）只建立基线
        val dt = (timestampNs - previousTimestamp) / 1_000_000_000f
        if (dt <= MIN_SAMPLE_INTERVAL_SECONDS) return

        val instant = (current - previousSmoothed) / dt
        val previousRate = verticalSpeed
        verticalSpeed = if (previousRate == null) {
            instant
        } else {
            previousRate + rateSmoothing * (instant - previousRate)
        }
    }

    /** 清空历史（停止采集 / 换源重建时调用，避免跨越长时间间隔的假升降率）。 */
    fun reset() {
        smoothedAltitude = null
        verticalSpeed = null
        lastTimestampNs = 0L
    }

    companion object {
        /** 高度低通系数：越小越平滑，但响应越慢。 */
        const val DEFAULT_ALTITUDE_SMOOTHING = 0.15f

        /** 升降率低通系数。 */
        const val DEFAULT_RATE_SMOOTHING = 0.12f

        /** 小于该时间跨度的两帧不做差分（避免除以极小 dt 放大噪声）。 */
        const val MIN_SAMPLE_INTERVAL_SECONDS = 0.05f
    }
}
