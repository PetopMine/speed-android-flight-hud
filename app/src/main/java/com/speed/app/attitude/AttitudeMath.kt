package com.speed.app.attitude

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 姿态纯数学。所有公式集中在这里，便于单元测试与推导验证。
 *
 * ## 坐标系约定（本文件所有公式的证明基础）
 *
 * ### Device（Android 机身坐标，SensorEvent 约定）
 * - +X 指向屏幕**右侧**
 * - +Y 指向屏幕**上方**（设备顶部）
 * - +Z 指向**屏幕外**（朝向用户）
 * 右手系：X × Y = Z。
 *
 * ### World（getRotationMatrixFromVector 的世界坐标）
 * - +X 指向**东**
 * - +Y 指向**北**（磁北）
 * - +Z 指向**天**
 * 重力 = (0, 0, −g)。
 *
 * ### 旋转矩阵 R（行主序 9 元素数组）
 * `getRotationMatrixFromVector` 输出的 R 满足 `world = R × device`，
 * 即 **第 i 列 = 设备第 i 轴在世界坐标的分量**：
 * - 第 0 列 (R[0],R[3],R[6]) = 设备 X 轴在世界
 * - 第 1 列 (R[1],R[4],R[7]) = 设备 Y 轴在世界
 * - 第 2 列 (R[2],R[5],R[8]) = 设备 Z 轴在世界
 *
 * ## Canonical 产品语义
 * - **pitch**：屏幕法线（设备/屏幕 Z 轴）相对地平面的仰角。
 *   屏幕朝上 = −90°，竖直 = 0°，屏幕朝下 = +90°（屏幕朝上倾斜 45° → −45°）。
 * - **roll**：绕屏幕法线的滚转。**向右倾斜（右侧压低）为正**。
 * - **heading**：设备 Y 轴（手机顶部）的水平磁方位角。
 *   0° = 北，90° = 东，180° = 南，270° = 西。
 *   **用机身矩阵计算**，因此与 display rotation 无关 —— 屏幕横竖切换不会凭空 ±90°。
 */
object AttitudeMath {

    const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()

    /**
     * pitch = −asin(R[8])。
     *
     * 证明：R[8] = 屏幕 Z 轴（法线，指向屏幕外）在世界 Z（天）的分量。
     * - 屏幕朝上：法线 = 天 → R[8] = +1 → pitch = −90°
     * - 竖直：法线水平 → R[8] = 0 → pitch = 0°
     * - 屏幕朝下：法线 = 地 → R[8] = −1 → pitch = +90°
     * - 屏幕朝上倾斜 45°：R[8] = cos45° → pitch = −45°
     *
     * 公式只依赖法线方向，对任意滚转角成立（绕法线的旋转不改变 R[8]）。
     * 使用 asin 而非 atan：asin 是球面角距，在任何滚转角下都保持线性。
     */
    fun pitchFromMatrix(r8: Float): Float {
        val sine = (-r8).coerceIn(-1f, 1f)
        return asin(sine) * RAD_TO_DEG
    }

    /**
     * roll = atan2(−R[6], R[7])。
     *
     * 证明：重力在设备坐标 = Rᵀ·(0,0,−1) = −(R 第 2 行) = (−R[6], −R[7], −R[8])。
     * 重力在屏幕平面（XY）的投影 = (−R[6], −R[7])。
     *
     * 右倾 θ（右侧压低）：重力拉向屏幕右 → 屏幕系重力 g = (g·sinθ, −g·cosθ)
     *   → atan2(g.x, −g.y) = atan2(−R[6], R[7]) = θ（右倾为正）。
     * 左倾 θ 对称得到 −θ。
     *
     * 退化：屏幕接近水平（R[8] → ±1，即 R[6]、R[7] 都很小）时重力投影消失，
     * roll 在数学上无定义。调用方应保持上一帧（由陀螺积分维持）。
     */
    fun rollFromMatrix(r6: Float, r7: Float): Float =
        Math.toDegrees(atan2(-r6, r7).toDouble()).toFloat()

    /**
     * 屏幕是否接近水平（重力投影退化、roll 失去定义的阈值）。
     * |R[8]| > 0.985 等价于 |pitch| > 80°。
     */
    fun isRollDegenerate(r8: Float): Boolean = kotlin.math.abs(r8) > 0.985f

    /**
     * canonical heading：**手机背面（机身观察方向）**的水平磁方位角。
     * 用**机身矩阵**（未 remap，R 的列 = 设备轴在世界坐标）。
     *
     * ## Primary：机身 −Z（手机背面）
     * 产品语义是"透过手机看世界"（屏幕朝下时看到地面），所以观察方向 =
     * 屏幕法线的反方向 = 机身 −Z。其在世界的方向 = −(R 第 2 列) = (−R[2], −R[5], −R[8])。
     * heading = atan2(东分量, 北分量) = atan2(−R[2], −R[5])。
     * - 背面朝北：(0, +) → 0°；朝东 → 90°；朝南 → 180°；朝西 → 270°
     *
     * ## Fallback：机身 +Y（手机顶部）
     * 当背面接近竖直（手机接近水平，背面投影 magnitude < 阈值）时 primary 退化，
     * 改用顶部方向的水平投影 atan2(R[1], R[4]) —— 屏幕朝上/朝下平放时绕竖直轴
     * 旋转手机仍然连续变化（用户验收：屏幕朝上旋转手机 heading 必须变）。
     * 两轴在机身内正交，**不可能同时退化**，因此不需要"保持上一帧"之类的补丁：
     * primary 退化 ⟺ secondary 良好。
     *
     * 用机身矩阵而不是屏幕重映射后的矩阵：display rotation 不改变物理参考轴，
     * 横屏/竖屏切换不会凭空 ±90°（历史 bug 的根因）。
     */
    fun headingFromMatrix(r: FloatArray): Float {
        // 背面方向在世界坐标的水平投影：backWorld = −(第 2 列) = (−R2, −R5, −R8)
        val backEast = -r[2]
        val backNorth = -r[5]
        val backHorizontal = sqrt(backEast * backEast + backNorth * backNorth)
        return if (backHorizontal >= HEADING_PRIMARY_MIN_PROJECTION) {
            normalizeHeading(Math.toDegrees(atan2(backEast, backNorth).toDouble()).toFloat())
        } else {
            // 顶部方向的水平投影（设备 Y = 第 1 列）
            normalizeHeading(Math.toDegrees(atan2(r[1], r[4]).toDouble()).toFloat())
        }
    }

    /**
     * primary 航向轴（机身 −Z）水平投影退化的阈值。
     * 0.35 ≈ 背面与竖直方向夹角 < 20°（|pitch| > 70°）时切换参考轴。
     * 只处理真正的数学奇异区域，正常姿态不会进入 fallback。
     */
    const val HEADING_PRIMARY_MIN_PROJECTION = 0.35f

    /** 归一化到 [0, 360)。 */
    fun normalizeHeading(degrees: Float): Float {
        var d = degrees % 360f
        if (d < 0f) d += 360f
        return d
    }

    /** 归一化到 (−180, 180]。 */
    fun normalizeRoll(roll: Float): Float {
        var value = roll % 360f
        if (value > 180f) value -= 360f
        if (value < -180f) value += 360f
        return value
    }

    /**
     * 最短角差，结果在 (−180, 180]。
     * 359° → 0° 得 +1°；0° → 359° 得 −1°（而非 +359° / −359°）。
     * 所有角度低通必须基于此函数，否则 0/360 环绕会被当成大跳变。
     */
    fun shortestAngularDelta(from: Float, to: Float): Float =
        normalizeRoll(to - from)

    /** 环绕感知的角度低通：prev + shortestDelta × alpha，输出归一化到 [0, 360)。 */
    fun smoothHeading(previous: Float, target: Float, alpha: Float): Float {
        val delta = shortestAngularDelta(previous, target)
        return normalizeHeading(previous + delta * alpha)
    }

    /** 环绕感知的角度低通（输出在 −180..180，用于 roll）。 */
    fun smoothAngle(previous: Float, target: Float, alpha: Float): Float =
        normalizeRoll(previous + shortestAngularDelta(previous, target) * alpha)

    /**
     * display rotation → 屏幕坐标轴在机身坐标的映射表（纯数据，便于单测四种旋转）。
     *
     * 轴代号：0 = X，1 = Y，2 = −X，3 = −Y。
     * 返回 (newX, newY)：屏幕 X 轴对应机身的 newX 轴，屏幕 Y 轴对应机身的 newY 轴。
     *
     * 例如 ROTATION_90（设备逆时针转 90°，屏幕右 = 机身下）：
     * 屏幕 X = 机身 +Y，屏幕 Y = 机身 −X → (1, 2)。
     * 调用方再翻译成 SensorManager.AXIS_* 常量执行 remapCoordinateSystem。
     */
    fun screenAxesForRotation(rotation: Int): Pair<Int, Int> = when (rotation) {
        1 -> Pair(1, 2)    // ROTATION_90
        2 -> Pair(2, 3)    // ROTATION_180
        3 -> Pair(3, 0)    // ROTATION_270
        else -> Pair(0, 1) // ROTATION_0
    }
}
