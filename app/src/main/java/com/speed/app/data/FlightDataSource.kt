package com.speed.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.speed.app.settings.AltitudeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一次飞行数据采样。
 *
 * 可空字段表示"暂时没有有效读数"（无权限、无卫星、无气压传感器或刚启动），
 * 界面要能区分"0"和"没有数据"，所以用可空类型而不是拿 0 顶替。
 *
 * [altitudeSource] 是当前生效的高度来源（用户选择），
 * 高度与升降率都由它决定；[hasBarometer] 只表示设备是否具备气压传感器。
 */
data class FlightData(
    val speedMetersPerSecond: Float? = null,
    val altitudeMeters: Float? = null,
    val verticalSpeedMetersPerSecond: Float? = null,
    val hasBarometer: Boolean = false,
    val altitudeSource: AltitudeSource = AltitudeSource.BAROMETER,
)

/**
 * 飞行数据源：GPS 提供地速与高度，气压计（如果有）提供气压高度。
 *
 * **高度与升降率都跟随用户选择的来源**（[setAltitudeSource]），选择哪个就只用哪个：
 * - BAROMETER：高度 = 平滑气压高度；升降率 = 气压高度差分。无气压传感器 → 都是 "--"。
 * - GPS：高度 = GPS altitude；升降率 = GPS 高度差分。**不需要气压计**。
 * 绝不偷偷切换来源（GPS 无数据就是 "--"，不会退回气压）。
 *
 * 两个来源各自维护一份独立的滤波/差分历史（[VerticalSpeedTracker]），
 * 因此切换来源不会因为时间戳/高度同时跳变而产生假升降率。
 *
 * 定位用 [LocationManager] 而不是 FusedLocationProvider：
 * 后者要额外引入 play-services-location 依赖，而这里只需要 GPS 速度/高度，
 * 系统 API 完全够用，也少一个外部依赖。
 */
class FlightDataSource(private val context: Context) {

    private val _data = MutableStateFlow(FlightData())
    val data: StateFlow<FlightData> = _data.asStateFlow()

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var listening = false

    /** 用户选择的高度来源；切换时立即按新来源重发一帧。 */
    @Volatile
    private var altitudeSource: AltitudeSource = AltitudeSource.BAROMETER

    /** GPS 最近一次读数（与气压状态分开存放，供按源组合）。 */
    private var gpsSpeed: Float? = null
    private var gpsAltitude: Float? = null

    /** 气压高度的滤波状态（高度低通在 tracker 内；这里保留原始气压高度供诊断）。 */
    private var pressureAltitude: Float? = null

    /** 两条数据源各自的独立历史：气压 / GPS。 */
    private val barometerTracker = VerticalSpeedTracker()
    private val gpsTracker = VerticalSpeedTracker()

    private val barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    /**
     * 设置高度来源并立即按新来源重新发布当前缓存数据。
     * 高度与升降率**同时**切换为该来源（两条历史各自独立，不互相污染）。
     */
    fun setAltitudeSource(source: AltitudeSource) {
        altitudeSource = source
        republish()
    }

    private val barometerListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val pressureHpa = event.values[0]
            if (pressureHpa <= 0f) return

            // 国际标准大气压公式：h = 44330 * (1 - (p / p0)^0.1903)
            val altitude = 44330f * (1f - Math.pow((pressureHpa / SEA_LEVEL_HPA).toDouble(), 0.1903).toFloat())
            pressureAltitude = altitude
            barometerTracker.onSample(altitude, event.timestamp)
            republish()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            gpsSpeed = if (location.hasSpeed()) location.speed else null
            val altitude = if (location.hasAltitude()) location.altitude.toFloat() else null
            gpsAltitude = altitude
            // GPS 高度同样进自己的历史；没有高度读数就不喂样本（升降率保持上一状态）
            altitude?.let { gpsTracker.onSample(it, android.os.SystemClock.elapsedRealtimeNanos()) }
            republish()
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    /**
     * 按当前选择的高度来源组合出一帧 [FlightData]：
     * 高度与升降率**同源**（BAROMETER → 气压历史；GPS → GPS 历史）。
     * 该来源没有有效读数时对应字段为 null → 界面显示 "--"。
     */
    private fun republish() {
        val barometerAltitude = barometerTracker.smoothedAltitude
        val altitude = when (altitudeSource) {
            AltitudeSource.BAROMETER -> barometerAltitude
            AltitudeSource.GPS -> gpsAltitude
        }
        val verticalSpeed = when (altitudeSource) {
            AltitudeSource.BAROMETER -> barometerTracker.verticalSpeed
            AltitudeSource.GPS -> gpsTracker.verticalSpeed
        }
        _data.value = FlightData(
            speedMetersPerSecond = gpsSpeed,
            altitudeMeters = altitude,
            verticalSpeedMetersPerSecond = verticalSpeed,
            hasBarometer = barometer != null,
            altitudeSource = altitudeSource,
        )
    }

    val hasLocationPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 开始采集。没有定位权限时只启动气压计，速度保持为 null。 */
    @SuppressLint("MissingPermission")
    fun start() {
        if (listening) return
        listening = true

        // 气压计采样用 SENSOR_DELAY_NORMAL（约 20Hz）：比 UI 延迟更快拿到首帧，
        // 避免"打开 App 后高度长时间显示 --"（用户反馈的室内不显示现象之一）。
        barometer?.let {
            sensorManager.registerListener(barometerListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // **立即发布一帧**：即使气压首帧还没到，也先把 hasBarometer / 高度来源
        // 状态推到 UI（无气压计的设备立刻显示明确的 "--" 而不是一直空白）。
        republish()

        if (!hasLocationPermission) return
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        } ?: return

        runCatching {
            locationManager.requestLocationUpdates(
                provider,
                LOCATION_INTERVAL_MS,
                LOCATION_MIN_DISTANCE_M,
                locationListener,
                Looper.getMainLooper(),
            )
            // 先取一次最后已知位置，避免刚打开时长时间空着
            locationManager.getLastKnownLocation(provider)?.let(locationListener::onLocationChanged)
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        sensorManager.unregisterListener(barometerListener)
        runCatching { locationManager.removeUpdates(locationListener) }
        // 清空两条历史：下次启动重新建立基线，避免跨越长时间间隔的假升降率
        barometerTracker.reset()
        gpsTracker.reset()
    }

    private companion object {
        const val SEA_LEVEL_HPA = 1013.25
        const val LOCATION_INTERVAL_MS = 1000L
        const val LOCATION_MIN_DISTANCE_M = 0f
    }
}
