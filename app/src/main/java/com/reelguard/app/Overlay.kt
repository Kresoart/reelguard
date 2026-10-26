package com.reelguard.app

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * The little message pill shown over Instagram ("Reels are off").
 *
 * To make sure it shows every time, the pill's window is added once and then kept: showing and
 * hiding only fades it in and out. (Adding and removing a window for every message could fail
 * when two messages came close together, which is why it sometimes didn't appear.)
 * Uses the accessibility overlay window type, so no "draw over other apps" permission is needed.
 */
class Overlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val hideRunnable = Runnable { fadeOut() }

    private val typeface: Typeface? by lazy {
        try {
            context.resources.getFont(R.font.inter_semibold)
        } catch (_: Exception) {
            null
        }
    }

    fun show(message: String, durationMs: Long = 2000L) {
        handler.post {
            val v = view ?: attach() ?: return@post
            v.text = message
            v.animate().cancel()
            if (v.visibility != View.VISIBLE || v.alpha < 1f) {
                if (v.visibility != View.VISIBLE) {
                    v.alpha = 0f
                    v.translationY = -dp(8f)
                    v.visibility = View.VISIBLE
                }
                v.animate().alpha(1f).translationY(0f).setDuration(FADE_MS).start()
            }
            handler.removeCallbacks(hideRunnable)
            handler.postDelayed(hideRunnable, durationMs)
        }
    }

    /** Removes the window for good (service is shutting down). */
    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        val v = view ?: return
        view = null
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun fadeOut() {
        val v = view ?: return
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            v.visibility = View.INVISIBLE
        }.start()
    }

    /** Adds the (invisible) pill window. Returns null if Android refused, and tries again next time. */
    private fun attach(): TextView? {
        val tv = buildView()
        tv.visibility = View.INVISIBLE
        return try {
            wm.addView(tv, layoutParams())
            view = tv
            tv
        } catch (e: Exception) {
            null
        }
    }

    private fun buildView(): TextView {
        val padV = dp(12f).toInt()
        val padH = dp(18f).toInt()
        val accent = context.getColor(R.color.accent)
        return TextView(context).apply {
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
