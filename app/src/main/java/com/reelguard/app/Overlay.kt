package com.reelguard.app

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * A short message pill shown over Instagram ("Reels are off").
 * Uses the accessibility overlay window type, so no "draw over other apps" permission is needed.
 */
class Overlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val hideRunnable = Runnable { hide() }

    fun show(message: String, durationMs: Long = 1800L) {
        handler.post {
            val existing = view
            if (existing != null) {
                existing.text = message
            } else {
                val tv = buildView(message)
                try {
                    wm.addView(tv, layoutParams())
                    view = tv
                } catch (e: Exception) {
                    // Window couldn't be added (e.g. service is being shut down). Ignore.
                    return@post
                }
            }
            handler.removeCallbacks(hideRunnable)
            handler.postDelayed(hideRunnable, durationMs)
        }
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        val v = view ?: return
        view = null
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun buildView(message: String): TextView {
        val pad = dp(14f).toInt()
        return TextView(context).apply {
            text = message
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(pad + pad / 2, pad, pad + pad / 2, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(24f)
                setColor(0xEE1C1633.toInt())
            }
            elevation = dp(6f)
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(96f).toInt()
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)
}
