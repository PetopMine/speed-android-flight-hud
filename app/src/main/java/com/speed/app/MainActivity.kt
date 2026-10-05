package com.speed.app

import android.Manifest
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.speed.app.attitude.AttitudeSource
import com.speed.app.data.FlightData
import com.speed.app.data.FlightDataSource
import com.speed.app.instruments.Attitude
import com.speed.app.settings.AppSettings
import com.speed.app.settings.HudColorKey
import com.speed.app.settings.OrientationMode
import com.speed.app.settings.SettingsRepository
import com.speed.app.settings.SettingsScreen
import com.speed.app.settings.SpeedSettingsTheme
import com.speed.app.ui.HudLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var attitudeSource: AttitudeSource
    private lateinit var flightDataSource: FlightDataSource
    private lateinit var settingsRepository: SettingsRepository

    /**
     * **未经校准的原始姿态**。
     * 校准按下时要把"此刻的读数"存成零点，所以必须保留原始值 ——
     * 如果存显示值，第二次校准会把上一次的偏移量也算进去，越校越偏。
     */
    private val rawAttitude = MutableStateFlow(Attitude())

    /**
     * 校准面板是否可见（瞬时校准，无测量阶段）。
     * 设置里点「开始校准」打开；面板上点「设为水平」立即写入零点、
     * 点「关闭」恢复原面板。
     */
    private val calibrationVisible = MutableStateFlow(false)

    /**
     * 调试：通过 adb 广播注入姿态，用于在模拟器上复现特定角度下的渲染
     * （模拟器的旋转矢量传感器不受 `sensor set acceleration` 影响，无法用常规方式测大俯仰角）。
     *
     *     adb shell am broadcast -a com.speed.app.DEBUG_ATTITUDE --ef pitch 70 --ef roll 0
     *
     * 不传参数则关闭注入，恢复正常传感器数据。
     *
     * 用 StateFlow 而不是普通字段：普通字段写入后不会触发重组，
     * 而模拟器的旋转矢量传感器可能长时间不再产生新样值，界面就一直停在旧姿态。
     */
    private val debugAttitude = MutableStateFlow<Attitude?>(null)

    private val debugReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val hasPitch = intent?.hasExtra("pitch") == true
            val hasRoll = intent?.hasExtra("roll") == true
            debugAttitude.value = if (hasPitch || hasRoll) {
                Attitude(
                    pitch = intent?.getFloatExtra("pitch", 0f) ?: 0f,
                    roll = intent?.getFloatExtra("roll", 0f) ?: 0f,
                    azimuth = intent?.getFloatExtra("azimuth", 0f) ?: 0f,
                )
            } else {
                null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()

        attitudeSource = AttitudeSource(this)
        flightDataSource = FlightDataSource(this)
        settingsRepository = SettingsRepository(this)

        // 必须是 RECEIVER_EXPORTED：adb shell am broadcast 属于外部来源，
        // NOT_EXPORTED 会静默拦截（广播返回 result=0 但接收器从不被调用）。
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            debugReceiver,
            android.content.IntentFilter(DEBUG_ACTION),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )

        setContent {
            val settings by settingsRepository.settings.collectAsState()
            val raw by rawAttitude.collectAsState()
            val flightData by flightDataSource.data.collectAsState()
            val calibrationOpen by calibrationVisible.collectAsState()

            // 显示用的姿态始终由「原始值 − 校准零点」推导出来。
            // 不要在 collect 里发布已校准的值：校准零点变化时那些值不会重算，
            // 按下校准后界面会一直停在旧偏移上。
            val attitudeSample = remember(raw, settings.zeroPitch, settings.zeroRoll) {
                applyCalibration(raw, settings)
            }

            // 高度来源设置 → FlightDataSource（切换立即按新来源重发数据）
            LaunchedEffect(settings.altitudeSource) {
                flightDataSource.setAltitudeSource(settings.altitudeSource)
            }

            // 真北设置 → 磁偏角。无位置/无权限时按 0 处理（保持磁北，不伪造真北）。
            LaunchedEffect(settings.trueNorth) {
                attitudeSource.magneticDeclination =
                    if (settings.trueNorth) computeMagneticDeclination() else 0f
            }

            // 保持屏幕常亮 → Window FLAG_KEEP_SCREEN_ON（真实窗口行为）。
            LaunchedEffect(settings.keepScreenOn) {
                applyKeepScreenOn(settings.keepScreenOn)
            }

            SpeedSettingsTheme(settings = settings) {
                AppRoot(
                    settings = settings,
                    attitude = attitudeSample,
                    rawAttitude = raw,
                    flightData = flightData,
                    onSettingsChange = settingsRepository::update,
                    onOrientationChange = ::applyOrientation,
                    onColorOverride = settingsRepository::setPaletteColor,
                    calibrationVisible = calibrationOpen,
                    onStartCalibration = { calibrationVisible.value = true },
                    onApplyCalibration = {
                        // 瞬时校准：把当前原始姿态直接写入零点
                        settingsRepository.update {
                            it.copy(zeroPitch = raw.pitch, zeroRoll = raw.roll)
                        }
                        calibrationVisible.value = false
                    },
                    onResetCalibration = {
                        settingsRepository.update { it.copy(zeroPitch = 0f, zeroRoll = 0f) }
                        attitudeSource.clearBiasCalibration()
                    },
                    onCloseCalibration = { calibrationVisible.value = false },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Activity 重新回到前台时按当前设置保持一致（重建后 LaunchedEffect 也会执行，这里双保险）
        applyKeepScreenOn(settingsRepository.settings.value.keepScreenOn)
    }

    override fun onStop() {
        super.onStop()
        // 后台时停止 GPS/气压采集（省电）；回到前台时 repeatOnLifecycle(STARTED)
        // 会再次调用 start()，listening 标志由 stop() 重置，保证可重新注册。
        if (::flightDataSource.isInitialized) flightDataSource.stop()
    }

    /** 把"保持屏幕常亮"设置应用到窗口标志。 */
    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * 把设置里的方向模式应用到窗口。
     */
    private fun applyOrientation(mode: OrientationMode) {
        settingsRepository.update { it.copy(orientation = mode) }
        requestedOrientation = when (mode) {
            OrientationMode.FOLLOW -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /**
     * 用最后已知位置计算磁偏角（度，东偏为正）。
     * 无定位权限、无位置或计算失败时返回 0 —— 真北模式退化为磁北，绝不伪造。
     */
    private fun computeMagneticDeclination(): Float = try {
        val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return 0f
        val location = runCatching {
            locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
        }.getOrNull() ?: return 0f
        @Suppress("DEPRECATION")
        val field = android.hardware.GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            System.currentTimeMillis(),
        )
        field.declination
    } catch (e: Exception) {
        0f
    }

    /**
     * 按生命周期启停传感器与定位。
     * 陀螺仪和 GPS 都很耗电，界面不可见时必须停掉，否则退到后台会持续耗电。
     */
    private suspend fun collectSensors(owner: LifecycleOwner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // 每次采样都实时读取屏幕方向：旋转时 Activity 不会重建，
            // 缓存成字段会让横屏后的姿态解算一直用竖屏的坐标约定。
            attitudeSource.rotationProvider = { display?.rotation ?: Surface.ROTATION_0 }
            flightDataSource.start()
            // 两个独立的收集器：
            // 不能用 combine —— 模拟器的旋转矢量传感器可能一个样值都不产生，
            // 而 combine 要等两边都有值才发射，调试注入就一直不生效。
            launch {
                attitudeSource.stream.collect { if (debugAttitude.value == null) rawAttitude.value = it }
            }
            launch {
                debugAttitude.collect { injected -> if (injected != null) rawAttitude.value = injected }
            }
        }
    }

    /** 把校准零点应用到原始姿态上：显示值 = 原始值 − 零点。 */
    private fun applyCalibration(raw: Attitude, settings: AppSettings): Attitude = Attitude(
        pitch = raw.pitch - settings.zeroPitch,
        roll = normalizeRoll(raw.roll - settings.zeroRoll),
        azimuth = raw.azimuth,
    )

    private fun normalizeRoll(degrees: Float): Float {
        var r = degrees % 360f
        if (r > 180f) r -= 360f
        if (r < -180f) r += 360f
        return r
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(debugReceiver) }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @Composable
    private fun AppRoot(
        settings: AppSettings,
        attitude: Attitude,
        rawAttitude: Attitude,
        flightData: FlightData,
        onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
        onOrientationChange: (OrientationMode) -> Unit,
        onColorOverride: (HudColorKey, Int?) -> Unit,
        calibrationVisible: Boolean,
        onStartCalibration: () -> Unit,
        onApplyCalibration: () -> Unit,
        onResetCalibration: () -> Unit,
        onCloseCalibration: () -> Unit,
    ) {
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(Unit) { collectSensors(lifecycleOwner) }

        // 有定位权限才有速度数据；被拒也不影响姿态显示，所以不阻塞、不弹二次提示。
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { }
        LaunchedEffect(Unit) {
            if (!flightDataSource.hasLocationPermission) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
        }

        var showSettings by rememberSaveable { mutableStateOf(false) }
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
        val tiles = settings.tilesFor(isLandscape)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1017)),
        ) {
            // 版式选择（EFIS 综合显示 / 双面板自由组合）在 HudLayout 内部处理，
            // 两种版式共用校准、手势等基础设施。
            HudLayout(
                isLandscape = isLandscape,
                firstTile = tiles.first,
                secondTile = tiles.second,
                instrumentStyle = settings.instrumentStyle,
                pitch = attitude.pitch,
                roll = attitude.roll,
                azimuth = attitude.azimuth,
                flightData = flightData,
                palette = settings.palette,
                zeroPitch = settings.zeroPitch,
                zeroRoll = settings.zeroRoll,
                referenceLineWidth = settings.referenceLineWidth,
                referenceLineLength = settings.referenceLineLength,
                calibrationVisible = calibrationVisible,
                onApplyCalibration = onApplyCalibration,
                onResetCalibration = onResetCalibration,
                onCloseCalibration = onCloseCalibration,
                modifier = Modifier.fillMaxSize(),
            )

            if (settings.showControls && !showSettings) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PillButton(text = "设置", onClick = { showSettings = true })
                }
            }

            // 长按兜底入口：只在"设置按钮隐藏"时才启用。
            //
            // 之前这里无条件渲染一层手势层，把"设置"按钮的点击也吃掉了 ——
            // 按钮点了没反应、只能长按进设置。现在按钮显示时不再盖手势层，
            // 按钮点击恢复；按钮隐藏时才靠长按兜底。
            if (!showSettings && !settings.showControls) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { showSettings = true },
                                onTap = { },
                            )
                        },
                )
            }

            if (showSettings) {
                SettingsScreen(
                    settings = settings,
                    isLandscape = isLandscape,
                    onOrientationChange = onOrientationChange,
                    onStyleChange = { style -> onSettingsChange { it.copy(instrumentStyle = style) } },
                    onAppearanceChange = { mode -> onSettingsChange { it.copy(appearance = mode) } },
                    onAccentChange = { accent -> onSettingsChange { it.copy(accent = accent) } },
                    onAltitudeSourceChange = { source -> onSettingsChange { it.copy(altitudeSource = source) } },
                    onTrueNorthChange = { enabled -> onSettingsChange { it.copy(trueNorth = enabled) } },
                    onReferenceWidthChange = { width -> onSettingsChange { it.copy(referenceLineWidth = width) } },
                    onReferenceLengthChange = { length -> onSettingsChange { it.copy(referenceLineLength = length) } },
                    onPresetChange = { preset ->
                        // 换预设时清掉全部覆写，否则旧的自定义会把新预设压住，看不出效果
                        onSettingsChange {
                            it.copy(hudPreset = preset, paletteOverrides = emptyMap())
                        }
                    },
                    onColorOverride = onColorOverride,
                    onTileChange = { slotIndex, tileType ->
                        onSettingsChange { current ->
                            val assignment = current.tilesFor(isLandscape)
                            val updated = if (slotIndex == 0) {
                                assignment.copy(first = tileType)
                            } else {
                                assignment.copy(second = tileType)
                            }
                            if (isLandscape) {
                                current.copy(landscapeTiles = updated)
                            } else {
                                current.copy(portraitTiles = updated)
                            }
                        }
                    },
                    onShowControlsChange = { enabled ->
                        onSettingsChange { it.copy(showControls = enabled) }
                    },
                    onKeepScreenOnChange = { enabled ->
                        onSettingsChange { it.copy(keepScreenOn = enabled) }
                    },
                    onStartCalibration = {
                        // 关掉设置、回到仪表界面，在无姿态仪的那一侧显示校准面板
                        showSettings = false
                        onStartCalibration()
                    },
                    onResetCalibration = onResetCalibration,
                    onClose = { showSettings = false },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private companion object {
        /** 调试注入姿态用的广播 action，见 [debugAttitude]。 */
        const val DEBUG_ACTION = "com.speed.app.DEBUG_ATTITUDE"
    }
}

/** HUD 上的常驻控件：只留一个设置入口，尽量不遮挡仪表。 */
@Composable
private fun PillButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0x99000000), RoundedCornerShape(50))
            .border(1.dp, Color(0x59FFFFFF), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text = text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
