package com.reelguard.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
 * Black panels laid over Instagram's Search page:
 *  - the big one covers the Explore grid, between the search box and the bottom tab bar;
 *  - a small one covers the buttons to the right of the search box (the ⋯ menu that leads to reels).
 * The search box itself and the tab bar stay usable. Both panels swallow touches.
 *
 * The big panel has an "Unlock for 5 minutes" button: tap it, wait 10 seconds, tap again.
 *
 * The windows are added once and kept; hiding makes them invisible and lets touches pass through.
 */
class ExploreCover(private val context: Context, private val onUnlock: () -> Unit) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var grid: Panel? = null
    private var corner: Panel? = null
    private var unlockButton: TextView? = null

    private var shownGrid: Rect? = null
    private var shownCorner: Rect? = null

    var isShowing = false
        private set

    /** Where the grid cover was last shown – used to cover instantly next time you tap Search. */
    var lastGrid: Rect? = null
        private set
    var lastCorner: Rect? = null
        private set

    // ---- unlock countdown ----
    private var countdown = -1 // -1 = not started, 0 = ready to unlock, >0 = seconds left
    private val tick = object : Runnable {
        override fun run() {
            if (countdown > 0) {
                countdown--
                renderUnlockButton()
                if (countdown > 0) handler.postDelayed(this, 1000)
            }
        }
    }

    private class Panel(val view: View, val params: WindowManager.LayoutParams)

    /** Covers [gridArea] (and [cornerArea], if any). Screen pixels. */
    fun show(gridArea: Rect, cornerArea: Rect?) {
        if (gridArea.height() < dp(80f)) {
            hide()
            return
        }
        lastGrid = Rect(gridArea)
        lastCorner = cornerArea?.let { Rect(it) }
        if (isShowing && gridArea == shownGrid && cornerArea == shownCorner) return
        isShowing = true
        shownGrid = Rect(gridArea)
        shownCorner = cornerArea?.let { Rect(it) }

        handler.post {
            val g = grid ?: attach(buildGridView())?.also { grid = it } ?: return@post
            place(g, gridArea, fade = true)

            if (cornerArea != null && cornerArea.width() >= dp(24f) && cornerArea.height() >= dp(24f)) {
                val c = corner ?: attach(buildCornerView())?.also { corner = it }
                if (c != null) place(c, cornerArea, fade = false)
            } else {
                corner?.let { conceal(it) }
            }
        }
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        shownGrid = null
        shownCorner = null
        handler.post {
            grid?.let { conceal(it) }
            corner?.let { conceal(it) }
            resetCountdown()
        }
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        isShowing = false
        listOfNotNull(grid, corner).forEach {
            try {
                wm.removeView(it.view)
            } catch (_: Exception) {
            }
        }
        grid = null
        corner = null
    }

    // ---- window plumbing ------------------------------------------------------------------------

    private fun place(p: Panel, area: Rect, fade: Boolean) {
        p.params.x = area.left
        p.params.y = area.top
        p.params.width = area.width()
        p.params.height = area.height()
        p.params.flags = p.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try {
            wm.updateViewLayout(p.view, p.params)
        } catch (_: Exception) {
            return
        }
        if (p.view.visibility != View.VISIBLE) {
            p.view.visibility = View.VISIBLE
            if (fade) {
                p.view.alpha = 0f
                p.view.animate().alpha(1f).setDuration(90).start()
            } else {
                p.view.alpha = 1f
            }
        }
    }

    private fun conceal(p: Panel) {
        p.view.animate().cancel()
        p.view.visibility = View.INVISIBLE
        p.params.flags = p.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        p.params.width = 1
        p.params.height = 1
        try {
            wm.updateViewLayout(p.view, p.params)
        } catch (_: Exception) {
        }
    }

    private fun attach(v: View): Panel? {
        v.visibility = View.INVISIBLE
        val p = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        return try {
            wm.addView(v, p)
            Panel(v, p)
        } catch (_: Exception) {
            null
        }
    }

    // ---- views ----------------------------------------------------------------------------------

    private fun buildCornerView(): View = View(context).apply {
        setBackgroundColor(context.getColor(R.color.bg))
        isClickable = true // swallow taps on the ⋯ menu
    }

    private fun buildGridView(): View {
        val accent = context.getColor(R.color.accent)
        val semibold = font(R.font.inter_semibold)
        val regular = font(R.font.inter_regular)

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(context.getColor(R.color.bg))
            isClickable = true // swallow taps and scrolls on the grid
            setPadding(dp(24f), 0, dp(24f), 0)

            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_shield)
                imageTintList = ColorStateList.valueOf(accent)
            }, LinearLayout.LayoutParams(dp(48f), dp(48f)))

            addView(TextView(context).apply {
                text = "Explore is hidden"
                setTextColor(context.getColor(R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                semibold?.let { typeface = it }
                gravity = Gravity.CENTER
            }, wrap().apply { topMargin = dp(16f) })

            addView(TextView(context).apply {
                text = "Search for people and accounts above"
                setTextColor(context.getColor(R.color.text_muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                regular?.let { typeface = it }
                gravity = Gravity.CENTER
            }, wrap().apply { topMargin = dp(6f) })

            val button = TextView(context).apply {
                setTextColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                semibold?.let { typeface = it }
                gravity = Gravity.CENTER
                minHeight = dp(44f)
                setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14f).toFloat()
                    setStroke(dp(1f).coerceAtLeast(1), context.getColor(R.color.accent_dim))
                }
                setOnClickListener { onUnlockTapped() }
            }
            unlockButton = button
            addView(button, wrap().apply { topMargin = dp(28f) })
            renderUnlockButton()
        }
    }

    // ---- unlock flow ----------------------------------------------------------------------------

    private fun onUnlockTapped() {
        when {
            countdown < 0 -> {
                countdown = UNLOCK_WAIT_S
                renderUnlockButton()
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, 1000)
            }
            countdown == 0 -> {
                resetCountdown()
                onUnlock()
            }
            else -> Unit // still counting down
        }
    }

    private fun resetCountdown() {
        handler.removeCallbacks(tick)
        countdown = -1
        renderUnlockButton()
    }

    private fun renderUnlockButton() {
        val b = unlockButton ?: return
        when {
            countdown < 0 -> {
                b.text = "Unlock for 5 minutes"
                b.alpha = 1f
            }
            countdown > 0 -> {
                b.text = "Wait $countdown s…"
                b.alpha = 0.6f
            }
            else -> {
                b.text = "Tap to unlock"
                b.alpha = 1f
            }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun font(id: Int): Typeface? = try {
        context.resources.getFont(id)
    } catch (_: Exception) {
        null
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    companion object {
        const val UNLOCK_WAIT_S = 10
        const val UNLOCK_MINUTES = 5
    }
}
