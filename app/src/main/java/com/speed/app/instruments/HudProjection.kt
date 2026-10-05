package com.speed.app.instruments

/**
 * HUD 俯仰的**球面等距（equirectangular）投影** —— 有限视锥 + 无奇异 + 视口裁剪。
 *
 * ## 为什么不用 tan 透视
 *
 * 旧实现 `screenY = centerY + tan(rel) × focal` 在 rel → ±90° 时 tan 发散：
 * 边缘刻度被无限拉伸、坐标爆炸，只能靠 clamp 掩盖。透视投影模型本身
 * 在"视线接近切平面"时奇异，这不是调 FOV 数值能解决的。
 *
 * ## 新模型（视线 → 球面方向 → 等距投影 → 裁剪）
 *
 * 把每个俯仰值 p 看作**垂直大圆上的球面方向**，其与视线（当前 pitch）
 * 的球面角距 = rel = p − pitch。用**等距投影**落回屏幕：
 *
 * ```
 * screenY(p) = centerY + radians(rel) × focal
 * focal      = (height / 2) / radians(FOV / 2)     // FOV 80° → ±40° 可见
 * ```
 *
 * 性质：
 * - rel 任意大都有**有限坐标**（球面大圆可无限绕），无 NaN/Infinity
 * - 边缘刻度**间距均匀、不被拉伸**（等距投影的特性）
 * - |rel| > FOV/2 的刻度自然落在屏幕外，由 `clipToBounds()` **裁剪**，
 *   表现为"部分刻度进入/离开屏幕"，而不是缩放变形
 * - 中心附近与旧模型一致：屏幕中心 = 当前 pitch 刻度、pitch>0 时地平线上移
 *
 * ## 越过天顶后的**球面折返**
 *
 * 产品 pitch 的物理域是 −90°..+90°（天/地为球面极点）。视线接近天顶时，
 * 视锥可以越过极点，看到"极点另一侧"的刻度（虚拟角度 > 90°）：
 * 位置用真实 rel 连续延展，**标签折返** —— 越过 90° 后数字递减：
 *
 * ```
 * … 80  90  80  70 …     （而非 80 90 100 110）
 * ```
 *
 * [pitchLabel] 实现该映射：|p| ≤ 90 显示 |p|，否则显示 180 − |p|。
 * 位置与标签分离：位置连续（rel 单调），标签折返（球面语义正确）。
 *
 * canonical pitch 定义**不变**（−90..+90，见 AttitudeMath）。
 */
object HudProjection {

    /** HUD 竖直视场（度）。80° → 可见球面角距 ±40°。 */
    const val VERTICAL_FOV_DEGREES = 80f

    /** 可见范围外的刻度再多生成一点，保证"进入视野"的边缘自然。 */
    const val EDGE_MARGIN_DEGREES = 10f

    /** 按屏幕高度算等距投影焦距（像素/弧度）。 */
    fun focalFor(height: Float): Float {
        if (height <= 0f) return 1f
        val halfFovRad = Math.toRadians(VERTICAL_FOV_DEGREES / 2.0)
        return (height / 2f) / halfFovRad.toFloat()
    }

    /**
     * 俯仰值 [degrees] 在当前俯仰 [pitch] 下的屏幕 y（y 向下为正）。
     * 球面角距 rel = degrees + pitch，等距投影：**任意 rel 均有限**。
     * 超出视锥的刻度落在屏幕外，由调用方的 clipToBounds 裁剪。
     *
     * 注：视觉映射采用 `rel = degrees + pitch`（即视觉方向与 canonical 符号相反），
     * 这是**本轮定案的最终显示语义**：PITCH = −60（屏幕朝上）时地平线**上移**、
     * 红色地面区域占多、当前指针指向红区的 60° 刻度。红蓝方向与旧版"−90→蓝"相反。
     * canonical pitch 与 Sensor 层完全未动（仍为 asin(−R[8])），
     * 这里是 HUD 视觉映射的**唯一**符号翻转点。
     */
    fun screenY(centerY: Float, degrees: Float, pitch: Float, focal: Float): Float {
        val rel = Math.toRadians((degrees + pitch).toDouble()).toFloat()
        return centerY + rel * focal
    }

    /**
     * 地平线（世界水平面，degrees = 0）在屏幕上的 y —— **锁定地平线的唯一映射点**。
     *
     * 最终产品规格（本轮定案）：
     * ```
     * PITCH = −60（屏幕朝上）→ horizonY < centerY（地平线上移）→ 红色占多，
     *                          指针指向红区 60° 刻度
     * PITCH =   0（竖直）      → horizonY = centerY（居中，指针 = 0°）
     * PITCH = +90              → horizonY > centerY（下移）→ 蓝色占多
     * ```
     * 即 `horizonY = centerY + radians(pitch) × focal`（与 [screenY] 同一视觉方向）。
     * Module（AttitudeTile）与 EFIS（EfisAttitude）地平线都必须调用它。
     */
    fun horizonScreenY(centerY: Float, pitch: Float, focal: Float): Float {
        return centerY + Math.toRadians(pitch.toDouble()).toFloat() * focal
    }

    /**
     * 当前俯仰指针的屏幕 y —— 指针**必须**指向当前 PITCH 对应的刻度。
     * 本视觉模型下屏幕中心刻度值 = −pitch（rel = degrees + pitch = 0），
     * 其标签 = pitchLabel(−pitch) = |pitch|。指针位置 = 该刻度的位置 = 屏幕中心。
     * 与 [screenY] 同源：indicatorY(pitch) == screenY(−pitch, pitch) == centerY。
     * 红蓝分界（[horizonScreenY]）、每条刻度（[screenY]）、指针（本函数）
     * 三者共用同一角度坐标，保证视觉上完全一致。
     */
    fun currentPitchIndicatorScreenY(centerY: Float, pitch: Float, focal: Float): Float =
        screenY(centerY, -pitch, pitch, focal)

    /**
     * **固定 HUD 基准符号**的屏幕 y —— 恒为画布中心。
     *
     * 参数里刻意**没有 pitch / roll**：基准符号不代表地平线，它是机体/视轴参考，
     * 位置永不随姿态变化。因此 `pitch = 0 / roll = 0` 时它与 0° 俯仰刻度线共线；
     * 姿态改变后只有刻度层移动，基准线保持不动。
     */
    fun referenceSymbolCenterY(centerY: Float): Float = centerY

    /** [degrees] 相对当前视线的球面角距（度）。 */
    fun relative(degrees: Float, pitch: Float): Float = degrees - pitch

    /**
     * 当前视锥内的刻度相对角序列（度，[stepDegrees] 步长）。
     * 可见范围 = ±(FOV/2 + 边缘余量)；越过 ±90 的虚拟刻度也在范围内时
     * 会被生成（其标签由 [pitchLabel] 折返）。
     */
    fun visibleRelativeAngles(pitch: Float, stepDegrees: Int = 5): List<Float> {
        val half = VERTICAL_FOV_DEGREES / 2f + EDGE_MARGIN_DEGREES
        val start = (kotlin.math.floor((-half) / stepDegrees) * stepDegrees).toInt()
        val end = (kotlin.math.ceil(half / stepDegrees) * stepDegrees).toInt()
        return (start..end step stepDegrees).map { it.toFloat() }
    }

    /**
     * 球面折返后的显示标签（正数，按 [degrees] 的绝对值）：
     * |p| ≤ 90 → |p|；否则 → 180 − |p|（越过天顶/天底后递减）。
     * 例：100 → 80、110 → 70、−100 → 80。
     */
    fun pitchLabel(degrees: Int): Int {
        val a = kotlin.math.abs(degrees)
        return if (a <= 90) a else 180 - a
    }

    /**
     * 左右倾斜指示器的角度范围（度）：
     * 宽屏（宽度 ≥ 1.6×高度，即双姿态合并的横屏大 HUD）用 ±45°，
     * 普通面板（竖屏、横屏单面板、EFIS 方形）用 ±30°。
     */
    fun rollRangeFor(width: Float, height: Float): Float =
        if (height > 0f && width >= height * 1.6f) 45f else 30f

    /**
     * 天地色块的覆盖半径（px）：保证地平线处于**任意**投影位置（含 ±90° 出屏）
     * 且随 roll 旋转时，天空/地面色块仍能覆盖整个屏幕、不露背景。
     *
     * 推导（历史 bug：reach 只加 height/2，等距投影下地平线最大位移
     * = radians(90°)×focal ≈ 1.57×focal > height/2，导致 pitch 接近 ±90° 时
     * 地面/天空色块覆盖不足，屏幕露出黑色背景 —— 用户实测"上半红、下半黑"）：
     *
     * - 地平线最大位移 maxShift = radians(90°) × focal（pitch=±90 时地平线出屏）
     * - 地面块必须从 horizonY_min 覆盖到屏幕底：需要 ≥ maxShift + height/2
     * - roll 旋转绕屏幕中心：用 halfDiag（屏幕对角线/2）补偿旋转后块角覆盖
     * - 附加少量余量吸收抗锯齿与取整
     */
    fun horizonReach(width: Float, height: Float, focal: Float): Float {
        val maxShift = Math.toRadians(90.0).toFloat() * focal
        val halfDiag = kotlin.math.hypot(width, height) / 2f
        return halfDiag + maxShift + height / 2f + 64f
    }

    /**
     * 左右倾斜指示器指针相对屏幕中心的偏移（px）。
     * canonical roll 物理定义不变（右倾为正），这里只做 UI 映射：
     * 手机向右倾（roll > 0）→ 指针向左（offset < 0）；左倾 → 右；0° → 居中。
     */
    fun rollPointerOffset(roll: Float, span: Float, rangeDegrees: Float): Float {
        val clamped = roll.coerceIn(-rangeDegrees, rangeDegrees)
        return -clamped * (span / rangeDegrees)
    }
}
