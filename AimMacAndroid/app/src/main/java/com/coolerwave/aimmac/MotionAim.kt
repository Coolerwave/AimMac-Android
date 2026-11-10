package com.coolerwave.aimmac

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns the phone's motion into a pointing direction. Same maths as MotionAim
 * on the iPhone.
 *
 * The pointing edge is the top of the phone (device +Y).
 *   el (elevation) comes straight from gravity, so up/down never drifts.
 *   az (azimuth) adds up turns around the true vertical from the gyroscope,
 *   so it works however the phone is rolled in the hand.
 *
 * Android axes match iOS (x right, y toward the top edge, z out of the screen)
 * and the gyroscope uses the same right-hand rule in rad/s. Android's gravity
 * vector points up (opposite of CoreMotion's), so it is used here as "up".
 */
class MotionAim(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gameRot: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private var thread: HandlerThread? = null

    var isRunning = false
        private set

    val isAvailable: Boolean
        get() = gyro != null && (gameRot != null || gravitySensor != null || accel != null)

    /** (timestamp, azimuth, elevation) in seconds and degrees, ~100 Hz, on a background thread. */
    var onSample: ((Double, Double, Double) -> Unit)? = null

    private val rot = FloatArray(9)
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var haveGravity = false
    private var az = 0.0
    private var lastT = 0L

    fun start() {
        if (!isAvailable || isRunning) return
        val t = HandlerThread("AimMac.motion", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        t.start()
        thread = t
        val h = Handler(t.looper)
        lastT = 0L
        haveGravity = false
        sm.registerListener(this, gyro, SAMPLE_US, h)
        // Best source of "up": the fused game rotation vector (gyro + accelerometer, no
        // lag, like CoreMotion's gravity). Plain gravity and the raw accelerometer are fallbacks.
        val g = gameRot ?: gravitySensor ?: accel
        sm.registerListener(this, g, SAMPLE_US, h)
        isRunning = true
    }

    fun stop() {
        if (!isRunning) return
        sm.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        isRunning = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                // third row of the rotation matrix = world "up" in device coordinates
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                gx = rot[6].toDouble()
                gy = rot[7].toDouble()
                gz = rot[8].toDouble()
                haveGravity = true
            }
            Sensor.TYPE_GRAVITY -> {
                gx = e.values[0].toDouble()
                gy = e.values[1].toDouble()
                gz = e.values[2].toDouble()
                haveGravity = true
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // fallback when the phone has no fused gravity sensor: low-pass the accelerometer
                if (!haveGravity) {
                    gx = e.values[0].toDouble()
                    gy = e.values[1].toDouble()
                    gz = e.values[2].toDouble()
                    haveGravity = true
                } else {
                    gx += 0.1 * (e.values[0] - gx)
                    gy += 0.1 * (e.values[1] - gy)
                    gz += 0.1 * (e.values[2] - gz)
                }
            }
            Sensor.TYPE_GYROSCOPE -> gyroSample(e)
        }
    }

    private fun gyroSample(e: SensorEvent) {
        if (!haveGravity) return
        val gn = sqrt(gx * gx + gy * gy + gz * gz)
        if (gn < 0.5) return
        // unit "up" vector in the device frame
        val ux = gx / gn
        val uy = gy / gn
        val uz = gz / gn

        // up/down: how far +Y points above the horizon
        val el = asin(max(-1.0, min(1.0, uy))) * 180.0 / PI

        // left/right: spin around "up"; positive = turning left
        val wUp = e.values[0] * ux + e.values[1] * uy + e.values[2] * uz
        if (lastT != 0L) {
            val dt = min(max((e.timestamp - lastT) / 1e9, 0.0), 0.05)
            az += wUp * dt * 180.0 / PI
            if (az > 360.0 || az < -360.0) az %= 360.0
        }
        lastT = e.timestamp
        onSample?.invoke(e.timestamp / 1e9, az, el)
    }

    private companion object {
        const val SAMPLE_US = 10_000 // 100 Hz
    }
}
