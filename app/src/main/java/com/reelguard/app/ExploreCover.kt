package com.reelguard.app

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A black panel laid over the Explore grid on Instagram's Search page.
 * It covers only the area between the search box and the bottom tab bar, so you can still
 * search for people and switch tabs. It swallows touches, so the grid can't be scrolled or tapped.
 *
 * The window is added once and kept; hiding makes it invisible and lets touches pass through.
 */
class ExploreCover(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var shownTop = -1
    private var shownBottom = -1

    var isShowing = false
        private set

    /** Covers the screen from [top] to [bottom] (screen pixels). */
    fun show(top: Int, bottom: Int) {
        if (bottom - top < dp(80f)) {
            hide()
            return
        }
        if (isShowing && top == shownTop && bottom == shownBottom) return
        handler.post {
            val v = view ?: attach() ?: return@post
            val p = params ?: return@post
            p.y = top
            p.height = bottom - top
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try {
                wm.updateViewLayout(v, p)
            } catch (_: Exception) {
                return@post
            }
            if (v.visibility != View.VISIBLE) {
                v.alpha = 0f
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(120).start()
            }
            shownTop = top
            shownBottom = bottom
            isShowing = true
        }
    }

    fun hide() {
        if (!isShowing && view?.visibility != View.VISIBLE) return
        handler.post {
            val v = view ?: return@post
            val p = params ?: return@post
            v.animate().cancel()
            v.visibility = View.INVISIBLE
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            p.height = 1
            try {
                wm.updateViewLayout(v, p)
            } catch (_: Exception) {
            }
            shownTop = -1
            shownBottom = -1
            isShowing = false
        }
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        val v = view ?: return
        view = null
        isShowing = false
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun attach(): View? {
        val v = buildView()
        v.visibility = View.INVISIBLE
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        return try {
            wm.addView(v, p)
            view = v
            params = p
            v
        } catch (_: Exception) {
            null
        }
    }

    private fun buildView(): View {
        val accent = context.getColor(R.color.accent)
        val semibold: Typeface? = try { context.resources.getFont(R.font.inter_semibold) } catch (_: Exception) { null }
        val regular: Typeface? = try { context.resources.getFont(R.font.inter_regular) } catch (_: Exception) { null }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(context.getColor(R.color.bg))
            isClickable = true // swallow taps and scrolls on the grid
            isFocusable = false

            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_shield)
                imageTintList = android.content.res.ColorStateList.valueOf(accent)
            }, LinearLayout.LayoutParams(dp(48f), dp(48f)))

            addView(TextView(context).apply {
                text = "Explore is hidden"
                setTextColor(context.getColor(R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                semibold?.let { typeface = it }
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16f) })

            addView(TextView(context).apply {
                text = "Search for people and accounts above"
                setTextColor(context.getColor(R.color.text_muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                regular?.let { typeface = it }
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6f) })
        }
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()
}
