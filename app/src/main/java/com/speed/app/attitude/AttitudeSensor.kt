package com.speed.app.attitude

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import com.speed.app.instruments.Attitude
import kotlinx.coroutines.Dispatchers
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 姿态数据源。
 *
 * 数据来源按以下优先级自动降级：
 *  1. `TYPE_ROTATION_VECTOR`     —— 陀螺仪 + 加速度计 + 磁力计融合，绝对姿态，无漂移
 *  2. `TYPE_GAME_ROTATION_VECTOR` —— 陀螺仪 + 加速度计融合（不含磁力计），无磁干扰
 *  3. `TYPE_ORIENTATION`          —— 老设备的兼容回退
 *
 * 拿到绝对姿态之后，再用 `TYPE_GYROSCOPE` 的角速率做**互补滤波**：
 * 陀螺仪响应快但会漂移，旋转矢量不漂移但刷新率与平滑度略逊，
 * 两者按 [ALPHA] 的比例融合，兼顾响应速度与长期稳定。
 */
class AttitudeSource(context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val hasGyroscope: Boolean =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    /** 是否存在可用的绝对姿态传感器（旋转矢量 / 游戏旋转矢量 / 方向传感器）。 */
    val hasAbsoluteSource: Boolean =
        ABSOLUTE_SENSOR_TYPES.any { sensorManager.getDefaultSensor(it) != null }

    /**
     * 当前屏幕旋转方向的**实时**提供者。
     *
     * 必须每次采样都重新取值，不能缓存成字段：
     * 清单里声明了 `configChanges=orientation|...`，屏幕旋转时 Activity **不会重建**，
     * 任何在 onCreate 里赋一次的字段都会一直停在初始方向 ——
     * 表现就是横屏后滚转算错、地平线不跟着设备转（这个坑真实踩过）。
     */
    @Volatile
    var rotationProvider: () -> Int = { Surface.ROTATION_0 }

    /** 当前屏幕方向，每次读取都取最新值。 */
    private val displayRotation: Int get() = rotationProvider()

    /** 互补滤波后的角度。首次收到绝对姿态时直接采用，之后由陀螺仪外推、绝对姿态回拉。 */
    private var fusedPitch: Float? = null
    private var fusedRoll: Float? = null
    private var lastGyroTimestamp: Long = 0L

    /**
     * canonical heading 的平滑状态（度，0..360）。
     * 单一事实来源：只由绝对姿态传感器（旋转矢量 / 方向传感器）更新，
     * 只经过一层环绕感知低通（[AttitudeMath.smoothHeading]）。
     * 不再有俯仰冻结、大跳变拒绝、方向一致性计数等历史补丁。
     */
    private var lastAzimuth: Float = 0f

    /**
     * 磁偏角（度，东偏为正）。真北模式开启且有位置时由
     * `GeomagneticField` 计算后写入；否则为 0（保持磁北，不伪造真北）。
     */
    @Volatile
    var magneticDeclination: Float = 0f

    /**
     * 陀螺仪零偏（度/秒，屏幕坐标系）。校准时测量并写入。
     * 不减去零偏的话，积分会以恒定速率漂移 —— 这是"敲一下桌子画面就飘走"的主因之一。
     */
    @Volatile
    private var gyroBiasX: Float = 0f

    @Volatile
    private var gyroBiasY: Float = 0f

    /**
     * 静止时对残余零偏做慢速估计（度/秒）。
     * 有了 [gyroBiasX]/[gyroBiasY] 之后它只负责吃掉温漂，所以范围限得很小，
     * 避免把"真的在缓慢转动"误判成零偏。
     */
    private var residualBiasX: Float = 0f
    private var residualBiasY: Float = 0f

    /** 机身坐标系下的加速度计读数（+Z 指向天空）。 */
    private val gravity = FloatArray(3)

    /**
     * 屏幕坐标系下的加速度计读数。
     *
     * **这是横屏能正确工作的关键**：加速度计给出的是机身坐标系的分量，
     * 而"屏幕向上"在横屏时对应的是机身的 ±X 轴而不是 +Y 轴。
     * 不重映射的话，横屏下算出来的滚转是错的（地平线不会跟着设备横过来）。
     */
    private val gravityScreen = FloatArray(3)
    private var hasGravity = false

    // Reused scratch buffers: allocating in a 200 Hz sensor callback would thrash the GC.
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    /**
     * 清除零偏标定，回到未标定状态。
     */
    fun clearBiasCalibration() {
        gyroBiasX = 0f
        gyroBiasY = 0f
        residualBiasX = 0f
        residualBiasY = 0f
    }

    val stream: Flow<Attitude> = callbackFlow {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val attitude = when (event.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> onGyroscope(event)
                    Sensor.TYPE_ROTATION_VECTOR,
                    Sensor.TYPE_GAME_ROTATION_VECTOR,
                    Sensor.TYPE_ORIENTATION,
                    -> onAbsolute(event)
                    Sensor.TYPE_ACCELEROMETER -> onAccelerometer(event)
                    else -> null
                }
                if (attitude != null) trySend(attitude)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        // 陀螺仪与加速度计始终注册；绝对姿态传感器只在没有更好选择时才用方向传感器。
        val wanted = buildList {
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let(::add)
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let(::add)
            if (sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null) {
                sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let(::add)
            } else if (sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null) {
                sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let(::add)
            } else {
                sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)?.let(::add)
            }
        }

        if (wanted.isEmpty()) {
            trySend(Attitude())
            close()
        } else {
            wanted.forEach { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
            awaitClose { sensorManager.unregisterListener(listener) }
        }
    }
        .conflate()
        .flowOn(Dispatchers.Default)

    private fun onAccelerometer(event: SensorEvent): Attitude? {
        gravity[0] = event.values[0]
        gravity[1] = event.values[1]
        gravity[2] = event.values[2]
        remapVectorToScreenFrame(gravity, gravityScreen, displayRotation)
        hasGravity = true
        return null
    }

    private fun onGyroscope(event: SensorEvent): Attitude? {
        val previous = lastGyroTimestamp
        lastGyroTimestamp = event.timestamp
        if (previous == 0L) return null

        val dt = ((event.timestamp - previous) / 1_000_000_000.0).toFloat()
        if (dt <= 0f || dt > 0.2f) return null

        // 陀螺仪原始数据是机身坐标系下的，先按屏幕方向映射到屏幕坐标系。
        val raw = screenFrameGyro(event.values)

        // 减去标定零偏 + 残余估计，再做积分。
        val pitchRate = raw[0] - gyroBiasX - residualBiasX
        val rollRate = raw[1] - gyroBiasY - residualBiasY

        val previousPitch = fusedPitch ?: return null
        val previousRoll = fusedRoll ?: return null

        // 加速度计提供的绝对姿态。它噪声大但长期无漂移，用来给积分"锚定"。
        val absolutePitch = computePitch()
        val absoluteRoll = computeRoll()

        // **互补滤波**：陀螺积分做高频、绝对姿态做低频修正。
        // 关键是最后那一项 (absolute - predicted) * (1 - ALPHA) 构成负反馈：
        // 预测值跑偏时会被拉回绝对姿态，所以零偏不会累积成漂移。
        //
        // 早先的实现写成 `fused = absolute + blend * rate * dt`，
        // 每帧都被绝对姿态重置、完全没有反馈 —— 那不是互补滤波，
        // 陀螺零偏会线性累积，敲一下桌子画面就飘走（实测确认过）。
        val predictedPitch = previousPitch + pitchRate * dt
        val predictedRoll = previousRoll + rollRate * dt

        fusedPitch = predictedPitch + (absolutePitch - predictedPitch) * (1f - ALPHA)
        fusedRoll = AttitudeMath.normalizeRoll(
            predictedRoll + (AttitudeMath.shortestAngularDelta(predictedRoll, absoluteRoll)) * (1f - ALPHA),
        )

        updateResidualBias(pitchRate, rollRate, absolutePitch, absoluteRoll, dt)

        return Attitude(fusedPitch!!, fusedRoll!!, lastAzimuth)
    }

    /**
     * 慢速估计残余零偏。
     *
     * 只有在"陀螺读数本身就很小、且预测值与绝对姿态基本吻合"时才认为设备是静止的，
     * 此时把残余读数当作零偏往估计里积分。两条判据都满足才更新，
     * 避免用户在缓慢转动时被误当成零偏而把真实动作吃掉。
     */
    private fun updateResidualBias(
        pitchRate: Float,
        rollRate: Float,
        absolutePitch: Float,
        absoluteRoll: Float,
        dt: Float,
    ) {
        val nearStationary = kotlin.math.abs(pitchRate) < STILL_RATE &&
            kotlin.math.abs(rollRate) < STILL_RATE
        if (!nearStationary) return

        val pitchError = kotlin.math.abs(
            AttitudeMath.shortestAngularDelta(fusedPitch ?: absolutePitch, absolutePitch),
        )
        val rollError = kotlin.math.abs(
            AttitudeMath.shortestAngularDelta(fusedRoll ?: absoluteRoll, absoluteRoll),
        )
        if (pitchError > STILL_ERROR || rollError > STILL_ERROR) return

        residualBiasX = (residualBiasX + pitchRate * BIAS_TRACK * dt)
            .coerceIn(-BIAS_LIMIT, BIAS_LIMIT)
        residualBiasY = (residualBiasY + rollRate * BIAS_TRACK * dt)
            .coerceIn(-BIAS_LIMIT, BIAS_LIMIT)
    }

    private fun onAbsolute(event: SensorEvent): Attitude? {
        val sensorType = event.sensor.type
        if (sensorType == Sensor.TYPE_ORIENTATION) {
            // TYPE_ORIENTATION 回退（老设备）：values 单位度，顺序 azimuth / pitch / roll。
            // values[0] = 磁方位角（0 = 北，参考轴为设备 Y）。老传感器没有旋转矩阵，
            // 无法精确取"背面方向"，沿用其方位角并叠加磁偏角。
            val rawHeading = AttitudeMath.normalizeHeading(event.values[0] + magneticDeclination)
            lastAzimuth = AttitudeMath.smoothHeading(lastAzimuth, rawHeading, HEADING_SMOOTHING)
            // values[1] = pitch：正面朝上 −90、竖直 0、背面朝上 +90，与产品定义一致，直接使用。
            fusedPitch = event.values[1]
            // roll 走加速度计回退（computeRoll 内部处理）。
            fusedRoll = computeRoll()
            // 标记该分支没有旋转矩阵；pitch 回退时读 orientationAngles[1]。
            orientationAngles[1] = Math.toRadians(event.values[1].toDouble()).toFloat()
            remappedMatrix[0] = 0f
            return Attitude(fusedPitch!!, fusedRoll!!, lastAzimuth)
        }

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // **canonical heading**：用**机身矩阵**（remap 前）计算，参考轴 = 机身 −Z（手机背面，
        // "透过手机看世界"的观察方向），接近水平时回退机身 +Y（顶部）——
        // 定义与证明见 [AttitudeMath.headingFromMatrix]。与 display rotation 解耦，
        // 屏幕横竖切换不改变 heading 语义。磁偏角在加偏前叠加（真北模式）。
        val rawHeading = AttitudeMath.normalizeHeading(
            AttitudeMath.headingFromMatrix(rotationMatrix) + magneticDeclination,
        )
        lastAzimuth = AttitudeMath.smoothHeading(lastAzimuth, rawHeading, HEADING_SMOOTHING)

        // pitch / roll 用**屏幕坐标系**（remap 后）矩阵。
        remapToScreenFrame(rotationMatrix, remappedMatrix, displayRotation)

        val pitch = computePitch()

        // roll：重力在屏幕平面上的投影。接近水平时投影退化，保持上一帧（陀螺积分维持）；
        // 最短弧跟随把翻越水平面时的 ±180° 表示假象折回，地平线保持连续。
        val rawRoll = computeRoll()
        val roll = fusedRoll?.let { AttitudeMath.smoothAngle(it, rawRoll, 1f) } ?: rawRoll

        fusedPitch = pitch
        fusedRoll = roll

        return Attitude(pitch, roll, lastAzimuth)
    }

    /**
     * 滚转取自重力在**屏幕平面**上的投影方向。
     *
     * 关键点一：`TYPE_ACCELEROMETER` 测的是**支持力**而非重力，设备水平静止时读数为 (0, 0, +9.81)，
     * 也就是 +g 指向天空。所以"屏幕上方"在世界坐标系里的方向就是 **+g 的归一化向量**，
     * 滚转角即屏幕 y 轴转到 +g 所需的角度：`atan2(gx, gy)`。
     * 常见错误是写成 `atan2(-gx, -gy)`，那算出来是反方向，竖直握持时会得到 ±180°。
     *
     * 关键点二：必须用**屏幕坐标系**的重力分量（[gravityScreen]），不能用机身坐标系的原始值。
     * 加速度计给出的是机身坐标系的分量，"屏幕向上"在横屏时对应的是机身的 ±X 轴。
     * 不重映射的话横屏下滚转是错的 —— 表现就是横过来拿手机时地平线不跟着转。
     */
    private fun computeRoll(): Float {
        val hasMatrix = !(remappedMatrix[0] == 0f && remappedMatrix[4] == 0f && remappedMatrix[8] == 0f)
        if (hasMatrix) {
            // 屏幕坐标系旋转矩阵直接推导（[AttitudeMath.rollFromMatrix]）：
            // 旋转矢量由陀螺仪融合而来，不受线性加速度影响 ——
            // 水平移动手机时加速度计会把平移加速度误当成重力方向，
            // 导致姿态仪"有惯性"地倾斜（用户实测反馈）。
            if (AttitudeMath.isRollDegenerate(remappedMatrix[8])) return fusedRoll ?: 0f
            return AttitudeMath.rollFromMatrix(remappedMatrix[6], remappedMatrix[7])
        }

        // 回退分支（TYPE_ORIENTATION 或没有旋转矩阵时）：用加速度计重力投影推导。
        if (!hasGravity) return fusedRoll ?: 0f
        val horizontal = sqrt(gravityScreen[0] * gravityScreen[0] + gravityScreen[1] * gravityScreen[1])
        // 设备接近水平（屏幕朝上/朝下）时投影退化，此时滚转本来也无意义，保持上一帧。
        if (horizontal < 0.35f) return fusedRoll ?: 0f
        return AttitudeMath.normalizeRoll(
            Math.toDegrees(atan2(gravityScreen[0], gravityScreen[1]).toDouble()).toFloat(),
        )
    }

    /**
     * 俯仰取屏幕法线（屏幕坐标系矩阵 R[8]）相对地平面的仰角。
     *
     * 产品定义：屏幕朝上 −90°、竖直 0°、屏幕朝下 +90°、屏幕朝上倾斜 45° → −45°。
     * 公式与证明见 [AttitudeMath.pitchFromMatrix]。
     */
    private fun computePitch(): Float {
        if (remappedMatrix[0] == 0f && remappedMatrix[4] == 0f && remappedMatrix[8] == 0f) {
            // TYPE_ORIENTATION 回退分支：它的 pitch 定义与产品语义一致
            //（正面朝上 −90、竖直 0、背面朝上 +90），直接用，不要取负。
            return Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
        }
        return AttitudeMath.pitchFromMatrix(remappedMatrix[8])
    }

    /**
     * 把机身坐标系下的陀螺仪读数映射到屏幕坐标系。
     *
     * 陀螺仪只在两个方向上有意义：[0] 是屏幕左右轴的角速率（俯仰变化率），
     * [1] 是屏幕上下轴的角速率（滚转变化率），[2] 绕屏幕法线（偏航），这里不用。
     */
    private fun screenFrameGyro(values: FloatArray): FloatArray = when (displayRotation) {
        Surface.ROTATION_90 -> floatArrayOf(-values[1], -values[0], values[2])
        Surface.ROTATION_180 -> floatArrayOf(-values[0], -values[1], values[2])
        Surface.ROTATION_270 -> floatArrayOf(values[1], values[0], values[2])
        else -> floatArrayOf(values[0], values[1], values[2])
    }

    /**
     * 把旋转矩阵重映射到屏幕坐标系；结果矩阵各列分别是屏幕 x/y/z 轴在世界坐标系中的分量。
     *
     * 轴映射表来自 [AttitudeMath.screenAxesForRotation]（单一事实来源，有单元测试），
     * 这里只负责把轴代号翻译成 SensorManager 常量并调用 remapCoordinateSystem。
     */
    private fun remapToScreenFrame(source: FloatArray, dest: FloatArray, rotation: Int) {
        val (axisX, axisY) = AttitudeMath.screenAxesForRotation(rotation)
        if (axisX == 0 && axisY == 1) {
            System.arraycopy(source, 0, dest, 0, 9)
            return
        }
        SensorManager.remapCoordinateSystem(source, toSensorAxis(axisX), toSensorAxis(axisY), dest)
    }

    /** 轴代号（见 [AttitudeMath.screenAxesForRotation]）→ SensorManager.AXIS_* 常量。 */
    private fun toSensorAxis(code: Int): Int = when (code) {
        0 -> SensorManager.AXIS_X
        1 -> SensorManager.AXIS_Y
        2 -> SensorManager.AXIS_MINUS_X
        else -> SensorManager.AXIS_MINUS_Y
    }

    /**
     * 把一个机身坐标系的三维向量（加速度计读数）旋转到屏幕坐标系。
     *
     * 做法：构造一个"以该向量为 x 轴"的旋转矩阵，走一遍与旋转矩阵完全相同的
     * [remapToScreenFrame]，再取回变换后的 x 轴。
     *
     * 为什么不直接手写分量公式：`remapCoordinateSystem` 内部会做**右手系修正**
     * （当指定的两个轴叉乘方向相反时会取反），手写公式很难与它保持一致 ——
     * 实测在 ROTATION_270 下差一个负号，导致横屏滚转显示 ±180°。
     * 复用它就不存在不一致的可能。
     */
    private val vectorScratchMatrix = FloatArray(9)
    private val vectorScratchRemapped = FloatArray(9)

    private fun remapVectorToScreenFrame(source: FloatArray, dest: FloatArray, rotation: Int) {
        val x = source[0]
        val y = source[1]
        val z = source[2]
        val norm = sqrt(x * x + y * y + z * z)
        if (norm < 1e-3f) {
            dest[0] = x
            dest[1] = y
            dest[2] = z
            return
        }

        val ux = x / norm
        val uy = y / norm
        val uz = z / norm

        // 任取一个与 u 不平行的向量，叉乘得到一组正交基（列向量即基向量）
        val helperX = if (kotlin.math.abs(ux) < 0.9f) 1f else 0f
        val helperY = if (kotlin.math.abs(ux) < 0.9f) 0f else 1f

        var tx = uy * 0f - uz * helperY
        var ty = uz * helperX - ux * 0f
        var tz = ux * helperY - uy * helperX
        val tNorm = sqrt(tx * tx + ty * ty + tz * tz)
        if (tNorm < 1e-4f) {
            dest[0] = x
            dest[1] = y
            dest[2] = z
            return
        }
        tx /= tNorm
        ty /= tNorm
        tz /= tNorm

        // 第三轴 = u × t，构成右手正交基
        val vx = uy * tz - uz * ty
        val vy = uz * tx - ux * tz
        val vz = ux * ty - uy * tx

        // 矩阵按列存放：第 0 列是 u（向量本身）
        vectorScratchMatrix[0] = ux
        vectorScratchMatrix[1] = uy
        vectorScratchMatrix[2] = uz
        vectorScratchMatrix[3] = tx
        vectorScratchMatrix[4] = ty
        vectorScratchMatrix[5] = tz
        vectorScratchMatrix[6] = vx
        vectorScratchMatrix[7] = vy
        vectorScratchMatrix[8] = vz

        remapToScreenFrame(vectorScratchMatrix, vectorScratchRemapped, rotation)

        // 变换后第 0 列就是原来的向量在屏幕坐标系中的表示
        dest[0] = vectorScratchRemapped[0] * norm
        dest[1] = vectorScratchRemapped[1] * norm
        dest[2] = vectorScratchRemapped[2] * norm
    }

    private companion object {
        val ABSOLUTE_SENSOR_TYPES = intArrayOf(
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ORIENTATION,
        )

        /**
         * 互补滤波系数：每一帧保留多少陀螺积分。
         *
         * 0.90 表示"90% 信任陀螺积分、10% 拉回加速度计"，
         * 时间常数约 `dt / (1 - ALPHA)` ≈ 0.1s。这个值刻意压得比较紧：
         * 实测手感是敲一下桌子画面只会轻微晃一下、随即被加速度计拉回，
         * 而不是像早先那样飘出去。（早先等效于 ALPHA = 1，完全没有修正。）
         *
         * 调大更跟手但更容易被抖动带偏；调小更稳但快速转动时会显得迟钝。
         */
        const val ALPHA = 0.90f

        /** 判定"设备静止"的陀螺读数阈值（度/秒）。 */
        const val STILL_RATE = 1.5f

        /** 判定"设备静止"时，预测值与绝对姿态允许的最大偏差（度）。 */
        const val STILL_ERROR = 2.0f

        /** 残余零偏的跟踪速度（1/秒）。越小越慢、越不容易吃掉真实动作。 */
        const val BIAS_TRACK = 0.02f

        /** 残余零偏的限幅（度/秒）。标定已经处理掉主要零偏，这里只兜温漂。 */
        const val BIAS_LIMIT = 2.0f

        /**
         * heading 低通系数：每帧朝目标角度靠拢的比例（环绕感知，见
         * [AttitudeMath.smoothHeading]）。0.2 在 50Hz 下时间常数约 100ms：
         * 压住磁力计 ±2° 噪声的同时，快速转动也只有约 10° 滞后。
         * 这是 heading 链上**唯一**的平滑机制。
         */
        const val HEADING_SMOOTHING = 0.2f
    }
}
