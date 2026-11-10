package com.coolerwave.aimmac

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.concurrent.CopyOnWriteArrayList

/** Glue between the motion sensor and the Bluetooth link. Same job as PointerSession on iOS. */
class PointerSession(ctx: Context) {
    val link = MacLink(ctx)
    private val motion = MotionAim(ctx)
    private val main = Handler(Looper.getMainLooper())
    @Suppress("DEPRECATION")
    private val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    /** Called on the main thread when the link or the pressed state changes. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** When false the phone only sends buttons and moves (trackpad mode). */
    var aimEnabled = true
        set(v) {
            field = v
            update()
        }

    /** The sensors only run while the app is on screen. */
    var foreground = false
        set(v) {
            field = v
            update()
        }

    var pressing = false
        private set

    val motionAvailable: Boolean get() = motion.isAvailable

    init {
        // straight from the sensor thread: no hop through the main thread
        motion.onSample = { t, az, el -> link.sendAim(t, az, el) }
        link.listeners.add {
            update()
            listeners.forEach { it() }
        }
    }

    fun start() = link.start()

    /** Run the sensors only while they are useful, to save battery. */
    fun update() {
        if (foreground && link.state == MacLink.State.Ready && aimEnabled && !link.paused) {
            motion.start()
        } else {
            motion.stop()
        }
    }

    fun setPaused(on: Boolean) {
        link.setPaused(on)
        update()
    }

    private var holdToken = 0

    fun press() {
        if (pressing) return
        pressing = true
        holdToken += 1
        val token = holdToken
        haptic()
        link.press()
        notifyChanged()
        // Safety net: never stay "held" for more than 15 seconds.
        main.postDelayed({
            if (pressing && holdToken == token) release()
        }, 15_000)
    }

    fun release() {
        if (!pressing) return
        pressing = false
        link.release()
        notifyChanged()
    }

    fun rightClick() {
        haptic()
        link.rightClick()
    }

    fun center() {
        haptic()
        link.center()
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    private fun haptic() {
        vibrator?.vibrate(VibrationEffect.createOneShot(10, 90))
    }
}
