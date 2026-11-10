package com.coolerwave.aimmac

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView

/** Colors and small view helpers, matching the iPhone app (black, mint accent, soft panels). */
object Ui {
    val mint: Int = Color.rgb(92, 242, 184)
    val panel: Int = Color.argb(18, 255, 255, 255)
    val panelStroke: Int = Color.argb(31, 255, 255, 255)
    val secondary: Int = Color.argb(150, 255, 255, 255)
    val tertiary: Int = Color.argb(95, 255, 255, 255)

    fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun dpi(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(
        ctx: Context,
        fill: Int,
        radius: Float,
        stroke: Int = 0,
        strokeDp: Float = 1.5f
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(ctx, radius)
        if (stroke != 0) setStroke(maxOf(1, dp(ctx, strokeDp).toInt()), stroke)
    }

    fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun label(
        ctx: Context,
        text: String,
        sp: Float,
        color: Int = Color.WHITE,
        bold: Boolean = false
    ): TextView = TextView(ctx).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
