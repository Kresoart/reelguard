package com.reelguard.app

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * The little message pill shown over Instagram ("Reels are off").
 * Black with a blue shield icon; fades in and out.
 * Uses the accessibility overlay window type, so no "draw over other apps" permission is needed.
 */
class Overlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val hideRunnable = Runnable { hide() }

    private val typeface: Typeface? by lazy {
        try {
            context.resources.getFont(R.font.inter_semibold)
        } catch (_: Exception) {
            null
        }
    }

    fun show(message: String, durationMs: Long = 1800L) {
        handler.post {
            val existing = view
            if (existing != null) {
                existing.text = message
                existing.animate().cancel()
                existing.alpha = 1f
            } else {
                val tv = buildView(message)
                try {
                    wm.addView(tv, layoutParams())
                    view = tv
                } catch (e: Exception) {
                    // Window couldn't be added (e.g. service is being shut down). Ignore.
                    return@post
                }
                tv.alpha = 0f
                tv.translationY = -dp(8f)
                tv.animate().alpha(1f).translationY(0f).setDuration(FADE_MS).start()
            }
            handler.removeCallbacks(hideRunnable)
            handler.postDelayed(hideRunnable, durationMs)
        }
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        val v = view ?: return
        view = null
        v.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun buildView(message: String): TextView {
        val padV = dp(12f).toInt()
        val padH = dp(18f).toInt()
        val accent = context.getColor(R.color.accent)
        return TextView(context).apply {
            text = message
            setTextColor(context.getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface?.let { setTypeface(it) }
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH + dp(2f).toInt(), padV)

            context.getDrawable(R.drawable.ic_shield)?.mutate()?.let { icon ->
                icon.setTint(accent)
                val size = dp(18f).toInt()
                icon.setBounds(0, 0, size, size)
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = dp(10f).toInt()
            }

            background = GradientDrawable().apply {
                cornerRadius = dp(24f)
                setColor(0xF20E1117.toInt())
            }
            elevation = dp(8f)
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

    companion object {
        private const val FADE_MS = 150L
    }
}
