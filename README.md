# Speed — 飞行姿态仪 / HUD / EFIS

**版本 2.0**（versionCode 18）· 作者 **System_WinNT4.9 & Deepseek** · 联系 **petop_mine@outlook.com**

仿飞机姿态仪（人工地平仪 / HUD / EFIS）的 Android 应用：读取手机姿态传感器、
气压计与 GPS，把屏幕实时渲染成一台"透过手机看世界"的飞行仪表。

* 纯 Kotlin + Jetpack Compose，无第三方仪表 / 图表依赖
* 全部通过 Gradle 命令行开发（不需要 Android Studio）
* 圆柱面等距投影的有限视场 HUD、固定基准符号、可自由组合的面板体系
* 85 个单元测试覆盖姿态数学、HUD 投影、仪表几何与升降率数据链

| 竖屏双面板（姿态仪 + 航向带） | EFIS 综合显示 |
| --- | --- |
| <img width="1260" height="2750" alt="微信图片_20261005215911_50_7" src="https://github.com/user-attachments/assets/89e17652-7a19-463b-bc4e-71735fbc09f2" />
 | <img width="1260" height="2750" alt="微信图片_20261005215857_49_7" src="https://github.com/user-attachments/assets/6f31a0a1-adfe-419c-922f-cd428554c6cc" />
|

| 横屏（左 EFIS / 右自定义面板） | 设置界面 |
| --- | --- |
| <img width="2750" height="1260" alt="微信图片_20261005213810_45_7" src="https://github.com/user-attachments/assets/a26018de-db66-4408-9818-41477eca4516" />
 | <img width="1260" height="2750" alt="微信图片_20261005213812_46_7" src="https://github.com/user-attachments/assets/7fc142ad-cd31-431a-b7b1-9a648361c0cd" />
 |

---

## 1. 功能总览

### 两种仪表模式

| 模式 | 说明 |
| --- | --- |
| **模块模式**（双面板） | 屏幕严格均分两块，每块可从 6 种面板里任选（校准面板只在校准期间临时占用一侧）；横屏 / 竖屏的配置**各自独立保存** |
| **EFIS 综合显示** | 单块 EFIS：左速度带 + 姿态球 + 右高度带 + 底部航向窄条 + 底部读数行；横屏时 EFIS 本体占屏幕中线（≥50%），右侧留给自定义面板 |

模块模式的 6 种面板：

| 面板 | 内容 |
| --- | --- |
| 姿态仪 | 天地色块 + 地平线 + 俯仰梯尺（世界网格刻度/数字）+ 固定基准符号 + 滚转指示器 |
| 速度带 | 地速大字（km/h） |
| 高度带 | `[刻度][◀][海拔数字]`；海拔与升降率跟随所选高度源 |
| 航向带 | 直条罗盘，贴面板顶部、无底衬、中央固定指针、N/NE/E/SE/S/SW/W/NW 八方位 |
| 数字读数 | 俯仰 / 滚转 / 航向 / 地速 / 海拔 / 升降率 一屏列出 |
| 空白 | 只显示深色底 |

> 姿态仪选两块时会自动合并成一块大 HUD（横屏全屏、竖屏上半屏），
> 滚转刻度范围也随之从 ±30° 自动加宽到 ±45°。

### 设置项

| 分组 | 项目 |
| --- | --- |
| **常用** | 仪表模式（模块 / EFIS）· 屏幕方向（跟随系统 / 锁定竖屏 / 锁定横屏）· **保持屏幕常亮** · 校准（开始 / 清除 / 设为水平）· 显示操作按钮 |
| **仪表** | 高度数据源（气压计 / GPS）· 真北（磁偏角修正）· 面板配置（竖屏 / 横屏各自两块） |
| **HUD** | 基准线粗细（细 / 标准 / 粗）· 基准线长度（短 / 标准 / 长）· HUD 配色预设（经典蓝红 / 单色绿 / 军工琥珀 / 夜间红 / 高对比青）· 逐项自定义颜色 |
| **外观** | 明暗模式（暗色 / 浅色 / **AMOLED 纯黑**）· 主题色（蓝 / 青 / 绿 / 琥珀 / 红 / 紫 / **中性灰**） |
| **关于** | 版本 · **编译时间** · 署名 · 邮箱 |

所有设置都真实作用于运行时；配色覆写只保存被单独改过的项，切换预设时其余项自动跟随。

---

## 2. HUD 视觉模型

### 2.1 俯仰：球面等距投影（不是 tan 透视）

视场固定 **FOV 80°**，把俯仰值看作垂直大圆上的球面方向，用**等距投影**落到屏幕：

```kotlin
focal   = (height / 2) / radians(40°)      // FOV/2
screenY = centerY + radians(degrees + pitch) × focal
```

这样做的原因（踩过的坑）：早期的 `tan(rel) × focal` 在视线接近切平面（rel → ±90°）时
发散，屏幕边缘刻度被无限拉伸，只能靠 clamp 掩盖。等距投影的性质：

* 任意角度都有**有限坐标**，无 `NaN` / `Infinity`
* 边缘刻度**间距均匀、不被拉伸**（相邻 1° 的屏幕位移恒定）
* 超出视锥的刻度自然落到屏幕外，由 `clipToBounds()` **裁剪** —— 表现为"部分刻度进出屏幕"

**越过天顶后的刻度折返**：pitch 接近 ±90° 时视锥会越过极点，虚拟刻度角超过 ±90°。
位置用真实角度连续延展（无跳变），而**标签折返**：

```
… 80  90  80  70 …      （而不是 80 90 100 110）
```

由 `pitchLabel` 实现（|p| ≤ 90 显示 |p|，否则 180 − |p|）。canonical pitch 始终是 −90..+90。

### 2.2 红蓝天地

```kotlin
horizonScreenY = centerY + radians(pitch) × focal
```

* **PITCH 为负** → 地平线上移 → **地面（红）占多**
* **PITCH 为正** → 地平线下移 → **天空（蓝）占多**
* `pitch = 0` → 地平线居中

天地色块的覆盖半径由 `horizonReach = 半对角线 + radians(90°)×focal + 高度/2 + 余量` 保证：
即使地平线被推到屏幕外（±90°），色块仍铺满整屏、**不会露出背景黑**；
`roll` 旋转时也用对角线补偿。

### 2.3 固定基准符号（锁定地平线）

基准符号是**固定 HUD 飞行基准**，不是地平线：

* 位置恒为画布中心（`referenceSymbolCenterY(centerY)` —— 参数里**没有 pitch / roll**），
  所以 `pitch = 0 / roll = 0` 时它与 0° 俯仰刻度线**共线**；姿态变化后只有刻度移动
* 形状：左右横线 + 两端**向上**短竖臂（直角）+ 中央向上短竖线
* **单色实体**：只用 `palette.reference` 描一次边，**没有外描边**
* **无缝连接**：整条符号是**一个连续 Path**（Miter 拐角），竖臂底端与横线下缘齐平，
  几何上真实重叠，连接处不会出现缝隙
* 粗细 / 长度 / 颜色分别由设置里的「基准线粗细 / 长度 / HUD 自定义颜色 → 基准线」驱动
* 模块模式中基准线绘制在**数字层之上**（图层顺序：天地/梯尺 → 数字 → 基准线），
  数字经过基准线时会被真实覆盖

### 2.4 滚转指示器

```kotlin
pointerX = centerX − roll × span / range     // 右倾 → 指针向左
```

* 直线刻度 + 每 10° 小号数字 + 刻度下方**尖角向上**的浮动三角
* 角度范围按宽度自适应：普通面板 ±30°，双姿态合并的宽 HUD ±45°
* 模块模式与 EFIS 共用同一实现（`drawRollIndicator`）

### 2.5 航向带

* **直条式**（不再是弧形罗盘），模块模式贴面板顶部、无独立底衬（背景 = HUD 背景）
* 刻度严格**关于中央指针左右对称**：整数度偏移 `±62`，`x = centerX + offset × pxPerDegree`，
  左右可见范围、间距、裁剪边距完全一致
* 每 30° 长刻度 / 每 10° 中刻度 / 每 5° 短刻度；八方位字母标签
* 中央固定黄色指针（`drawHeadingPointer`），模块与 EFIS 共用
* 0/360 环绕由 canonical heading 归一化保证，不跳变

---

## 3. 数据链

### 3.1 姿态：传感器 → canonical attitude → 校准 → 渲染

```
SensorManager
  ├─ TYPE_ROTATION_VECTOR（优先）/ TYPE_GAME_ROTATION_VECTOR / TYPE_ORIENTATION（回退）
  ↓
AttitudeMath（纯函数，唯一物理定义）
  pitch   = −asin(R[8])              // 屏幕法线的天分量；屏幕朝上 = −90，竖直 = 0，朝下 = +90
  roll    = atan2(−R[6], R[7])       // 右倾为正
  heading = atan2(−R[2], −R[5])      // 机身背面参考轴；接近铅垂时回退顶部轴 atan2(R[1], R[4])
  ↓
applyCalibration（显示值 = 原始值 − 校准零点）
  ↓
AttitudeTile / EfisAttitude（只负责把物理量映射成屏幕几何）
```

姿态融合：陀螺仪积分 + 旋转矢量绝对姿态的互补滤波，陀螺零偏在静止时标定。
`display.rotation` 把机身坐标换算到当前屏幕坐标系，横竖屏都以"手机水平方向"为基准。
`android:configChanges` 已声明方向变化，旋转时不重建 Activity。

> 设计原则：**canonical 状态是唯一事实，Canvas 里不重算传感器物理量**；
> 渲染层的符号翻转只允许发生在 HUD 视觉映射这一处（`HudProjection`）。

### 3.2 高度与升降率

* **地速**：`Location.getSpeed()`，界面换算 km/h
* **海拔**：来源由设置决定，**选哪个就只用哪个**，该来源无数据时显示 `--`，绝不自动回退
  * 气压计：`TYPE_PRESSURE` + 国际标准大气公式 `h = 44330 × (1 − (p/p₀)^0.1903)`
  * GPS：`Location.hasAltitude() / altitude`
* **升降率**：**跟随同一个高度源**
  * 气压模式 → 气压高度差分；GPS 模式 → GPS 高度差分（**不需要气压计**）
  * 两条来源各自维护独立的滤波 / 差分历史（`VerticalSpeedTracker`：高度低通 → 时间差分 → 速率低通），
    切换来源不会因时间戳或高度跳变产生假升降率；采样停止时清空历史
* 定位用系统 `LocationManager`，不引入 play-services 依赖

---

## 4. 构建与运行

### 4.1 工具链

| 组件 | 版本 |
| --- | --- |
| JDK | Oracle JDK 21（源码/目标级别 17） |
| Gradle | 9.8.0（wrapper 自动管理） |
| AGP | 9.4.1（内置 Kotlin 支持） |
| Kotlin | 2.4.20 |
| Compose BOM | 2026.09.00 |
| compileSdk / targetSdk | 37 |
| minSdk | 24 |

未安装 Android Studio，全部通过 Gradle 命令行开发。

### 4.2 环境变量

```
JAVA_HOME        = C:\Program Files\Java\jdk-21
ANDROID_HOME     = F:\Android\Sdk
ANDROID_SDK_ROOT = F:\Android\Sdk
PATH            += F:\Android\Sdk\platform-tools
PATH            += F:\Android\Sdk\emulator
```

> 已经打开的终端 / 编辑器需要重启才能读到这些值。

### 4.3 常用命令

```powershell
.\gradlew.bat assembleDebug      # debug 包 → app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat assembleRelease    # release 包（有 keystore.properties 时自动签名）
.\gradlew.bat testDebugUnitTest  # 单元测试（85 个）
.\gradlew.bat installDebug       # 构建并安装到已连接设备
.\gradlew.bat clean              # 清理
```

### 4.4 签名与版本

* 签名信息从 `keystore.properties` 读取（该文件不进版本库）；文件不存在时跳过签名配置，
  debug 构建不受影响，release 产出未签名包
* 版本号在 `app/build.gradle.kts`：`versionCode` / `versionName`，唯一版本源
* 「关于」页显示的版本与**编译时间**来自 `BuildConfig.VERSION_NAME` / `BuildConfig.BUILD_TIME`
  （Gradle 在配置阶段写入，配置缓存命中时沿用上次的值；`clean` 或 `--no-configuration-cache` 可刷新）

### 4.5 模拟器调试：注入姿态

```powershell
# 注入任意姿态（俯仰 / 滚转 / 方位角，单位度）
adb shell am broadcast -a com.speed.app.DEBUG_ATTITUDE --ef pitch 10 --ef roll -5 --ef azimuth 97

# 清除注入，回到真实传感器
adb shell am broadcast -a com.speed.app.DEBUG_ATTITUDE
```

注入走 `rawAttitude`（绕过传感器，直接写成显示值之前的原始姿态），
用于在不具备真实运动条件时验证渲染与几何。

---

## 5. 测试

`.\gradlew.bat testDebugUnitTest` —— **85 个单元测试全部通过**：

| 测试类 | 数量 | 覆盖内容 |
| --- | --- | --- |
| `AttitudeMathTest` | 30 | pitch/roll/heading 数值关系、0/360 环绕、四种 ROTATION 映射、主副航向轴切换 |
| `HudProjectionTest` | 27 | 等距投影焦距、中心刻度恒等、可见范围、地平线方向、无边缘拉伸、−90..+90 连续、越过极点无 NaN、折返标签、滚转映射与宽版范围、天地色块覆盖（±90 与 roll 旋转） |
| `InstrumentGeometryTest` | 22 | 固定指针/移动刻度、航向带左右对称、世界网格刻度连续、梯尺数字锚点确定性、基准符号几何/无描边/固定位置、Module 高度带指向与运动、读数面板安全区自适应 |
| `VerticalSpeedTrackerTest` | 6 | 上升为正/下降为负、首帧只建基线、过短间隔忽略、两条来源历史互不干扰、reset 清空 |

---

## 6. 项目结构

```
app/src/main/java/com/speed/app/
├── MainActivity.kt                  # 全屏沉浸、生命周期、传感器/数据源接线、调试注入
├── attitude/
│   ├── AttitudeMath.kt              # canonical 姿态定义（纯函数，可单测）
│   └── AttitudeSensor.kt            # 传感器采集 + 互补滤波 + 陀螺零偏
├── data/
│   ├── FlightDataSource.kt          # 地速 / 海拔（按源）/ 升降率（跟随高度源）
│   └── VerticalSpeedTracker.kt      # 单来源升降率状态机（高度低通 → 差分 → 速率低通）
├── instruments/
│   ├── HudProjection.kt             # 等距投影、地平线映射、折返标签、滚转映射、覆盖半径
│   ├── InstrumentPrimitives.kt      # 共享原语：基准符号 / 航向带 / 滚转指示器 / 刻度带 / 读数行
│   ├── AttitudeTile.kt              # 模块模式姿态仪（含数字层与最上层基准线）
│   ├── EfisAttitude.kt              # EFIS 姿态球（俯仰梯尺 + 数字 + 机体符号 + 滚转指示）
│   └── DataTiles.kt                 # 航向带 / 高度带 / 速度面板 / 数字读数
├── settings/
│   ├── Settings.kt                  # 设置模型 + 枚举 + SharedPreferences 持久化
│   ├── HudPalette.kt                # HUD 配色预设与逐项覆写
│   ├── SettingsScreen.kt            # 设置界面（手写控件）
│   └── Theme.kt                     # 设置界面主题（暗 / 浅 / AMOLED + 主题色）
└── ui/
    ├── HudLayout.kt                 # 面板分区、双姿态合并、EFIS 模式分栏
    └── EfisLayout.kt                # EFIS 版式：左右刻度带 / 航向窄条 / 读数行

app/src/test/java/com/speed/app/     # 85 个单元测试
docs/                                # 展示截图
tools/start-emulator.ps1             # 启动 AVD（含非默认 AVD 路径处理）
gradle/libs.versions.toml            # 版本目录（AGP / Kotlin / Compose BOM）
```

---

## 7. 排错备忘（真实的坑）

### AGP 9 相关

1. **不要应用 `org.jetbrains.kotlin.android` 插件**：AGP 9 起内置 Kotlin 编译，
   再加会直接报错；Compose 编译器插件 `org.jetbrains.kotlin.plugin.compose` 仍需单独应用。
2. **`compileSdk` 不能低于 37**：Compose BOM 2026.09.00 的 AAR 元数据声明了 `minCompileSdk=37`。
3. **API 37 平台包名带小版本号**：`platforms;android-37.0` → 目录 `platforms\android-37.0`。
4. **内置 Kotlin 下构造参数要写 `val` / `private val`** 才能在成员函数里访问。
5. **`android.graphics.Paint` 自带 `density` 属性**：在 `Paint().apply { }` 里写 `density`
   会解析成 Paint 的属性，`Int * Float` 无匹配重载，报错却是"未解析"，容易看错方向。
6. **XML 注释里不能出现 `--`**，否则 manifest 合并失败。
7. **release 与 debug 签名不同，不能互相覆盖安装**：
   `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；切换变体前先 `adb uninstall com.speed.app`，
   否则会出现"改了代码但界面没变"的假象。

### 平台 / 调试相关

8. **AVD 路径非默认**，需要 `ANDROID_AVD_HOME`（用 `tools/start-emulator.ps1`）。
9. **`TYPE_ACCELEROMETER` 测的是支持力不是重力**，符号搞反会让滚转显示 ±180°。
10. **`adb shell sed` 的引号会被吃掉**：改设备上的文件用
    `adb exec-out run-as <pkg> cat` 取出 → 本地改写 → `adb push` + `run-as cp` 写回。
11. **`adb shell screencap` 始终按设备自然方向存储**（即使当前横屏），像素坐标按存储方向算。
12. **`am broadcast` 属于外部来源**：接收器必须注册为 `RECEIVER_EXPORTED`，
    否则广播返回 `result=0` 但接收器从不被调用。
13. **Compose 普通字段写入不触发重组**：旁路数据要用 `MutableStateFlow`。
14. **`combine` 要等所有上游都发过值**：一路长期无数据会让另一路一起哑掉，改用两个独立 `collect`。

### 渲染相关

15. **天地色块覆盖范围要同时算旋转与俯仰平移**：只按半对角线算，大俯仰角会露出背景
    （这就是 `horizonReach` 的由来）。
16. **被旋转坐标系里的 `Brush.verticalGradient` 不可靠**：横屏会失效把面板糊成纯色，天地色块用纯色。
17. **梯尺刻度循环起点要对齐步长**，否则漏掉 ±5/±15 次刻度、数字变成 2/12/22。
18. **`Modifier.weight(1f)` 之后不要再叠 `fillMaxSize()`**：后者会撑满父容器、覆盖 weight 分配。
19. **横向刻度带必须用连续角度**：`for (offset in -60..60 step 5)` 会把刻度量化到 5° 网格，
    中心指针与实际刻度对不上、滚动跳格；正确做法是按整数度偏移算像素位置。
20. **刻度分级要留间隙**：每个整数度都画会糊成实心块，按 30°/10°/5° 权重分级。
21. **用 `onTextLayout` 的测量结果做文字居中会跳**：新标签第一帧高度为 0、下一帧才跳到位；
    改成固定尺寸标签盒 + 盒内对齐，锚点只由几何量决定（这就是"数字跳一下"的根因修复）。

---

## 8. 版本记录

完整历史见 [CHANGELOG.md](CHANGELOG.md)。当前版本 **2.0**（versionCode 18）。

---

## 9. 许可与联系

* 作者：**System_WinNT4.9 & Deepseek**
* 邮箱：**petop_mine@outlook.com**
* 本项目为个人飞行仪表实验项目，姿态数据来自手机内置传感器，**不得用于任何真实航空导航用途**。
