package com.coolerwave.aimmac

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Tap to click, hold to drag. The Mac clicks where you were aiming when the finger touched down. */
@SuppressLint("ClickableViewAccessibility")
class ClickPad(ctx: Context, private val session: PointerSession) : LinearLayout(ctx) {
    private val icon = Ui.label(ctx, "👆", 34f)
    private val title = Ui.label(ctx, "", 16f, Ui.secondary)
    private val hint = Ui.label(ctx, "", 12f, Ui.tertiary)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        addView(icon)
        addView(title)
        addView(hint)
        setOn(session.pressing)
    }

    fun setOn(on: Boolean) {
        background = if (on) {
            Ui.rounded(context, Color.argb(89, 92, 242, 184), 28f, Ui.mint)
        } else {
            Ui.rounded(context, Ui.panel, 28f, Ui.panelStroke)
        }
        title.text = if (on) "Holding" else "Tap to click · hold to drag"
        hint.text = if (session.motionAvailable) {
            "Point the top edge at the screen"
        } else {
            "This phone has no gyroscope, use Trackpad"
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                session.press()
                setOn(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                session.release()
                setOn(false)
            }
        }
        return true
    }
}

/**
 * Phone flat on the table: slide to move, tap to click, the Hold button keeps
 * the left button down for dragging.
 */
@SuppressLint("ClickableViewAccessibility")
class TrackSurface(ctx: Context, private val session: PointerSession) : FrameLayout(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private var lastX = 0f
    private var lastY = 0f
    private var touching = false
    private var startTime = 0L
    private var travelled = 0f
    private var dragLock = false
    private val lockButton = Ui.label(ctx, "", 12f, Color.WHITE, bold = true)

    init {
        background = Ui.rounded(ctx, Ui.panel, 28f)

        val hintCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(Ui.label(ctx, "☝", 34f))
            addView(Ui.label(ctx, "Slide to move · tap to click", 16f, Ui.secondary))
        }
        addView(hintCol, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        lockButton.setPadding(Ui.dpi(ctx, 12), Ui.dpi(ctx, 8), Ui.dpi(ctx, 12), Ui.dpi(ctx, 8))
        lockButton.setOnClickListener {
            dragLock = !dragLock
            if (dragLock) session.press() else session.release()
            styleLock()
        }
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        lp.setMargins(0, 0, Ui.dpi(ctx, 12), Ui.dpi(ctx, 12))
        addView(lockButton, lp)
        styleLock()
    }

    private fun styleLock() {
        lockButton.text = if (dragLock) "🔒 Release" else "🔓 Hold"
        lockButton.setTextColor(if (dragLock) Color.BLACK else Color.WHITE)
        lockButton.background = Ui.rounded(
            context,
            if (dragLock) Ui.mint else Color.argb(31, 255, 255, 255),
            100f
        )
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = e.x
                lastY = e.y
                touching = true
                startTime = e.eventTime
                travelled = 0f
            }
            MotionEvent.ACTION_MOVE -> if (touching) {
                val dx = (e.x - lastX) / density
                val dy = (e.y - lastY) / density
                travelled += abs(dx) + abs(dy)
                // a little acceleration: slow = precise, fast = far
                val speed = hypot(dx, dy)
                val gain = 1.4f + min(speed / 12f, 2.2f)
                session.link.move((dx * gain).toDouble(), (dy * gain).toDouble())
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (touching && travelled < 6f && e.eventTime - startTime < 250) {
                    session.press()
                    session.release()
                }
                touching = false
            }
            MotionEvent.ACTION_CANCEL -> touching = false
        }
        return true
    }
}

/** Slide up and down to scroll, like two fingers on a trackpad. */
@SuppressLint("ClickableViewAccessibility")
class ScrollStrip(ctx: Context, private val session: PointerSession) : LinearLayout(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private var lastY = 0f
    private var touching = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = Ui.rounded(ctx, Ui.panel, 22f)
        setPadding(0, Ui.dpi(ctx, 18), 0, Ui.dpi(ctx, 18))

        addView(Ui.label(ctx, "▲", 14f, Ui.secondary))
        addView(spacer(ctx))
        addView(Ui.label(ctx, "↕", 20f, Ui.secondary))
        addView(Ui.label(ctx, "Scroll", 11f, Ui.secondary))
        addView(spacer(ctx))
        addView(Ui.label(ctx, "▼", 14f, Ui.secondary))
    }

    private fun spacer(ctx: Context) = android.view.View(ctx).also {
        it.layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastY = e.y
                touching = true
            }
            MotionEvent.ACTION_MOVE -> if (touching) {
                session.link.scroll(0.0, ((e.y - lastY) / density * 2.2f).toDouble())
                lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        return true
    }
}
