package com.reelguard.app

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.DateFormat
import java.util.Date

/**
 * The blocker. Android only sends it events from Instagram (see res/xml/reel_guard_service.xml).
 *
 * Rules:
 *  - Reels player opened from the Reels tab / feed / explore  → press Back.
 *  - Reels player opened from a DM (or a link from another app) → allowed,
 *    but as soon as you swipe to the next reel → press Back.
 */
class ReelGuardService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private lateinit var overlay: Overlay

    // ---- state ----
    private var inViewer = false            // Reels player currently on screen
    private var allowedSession = false      // this player session was opened from a DM
    private var cameFromDm = false          // last real screen before the player was a DM chat
    private var freshFromOutside = true     // Instagram just came to the front, no taps yet
    private var viewerEnteredAt = 0L
    private var startIndex = -1             // which item the pager was on when you arrived

    // ---- timing ----
    private var lastEventAt = 0L
    private var lastCheckAt = 0L
    private var lastBackAt = 0L
    private var backStreak = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        overlay = Overlay(this)
        resetState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::prefs.isInitialized) return
        if (event.packageName?.toString() != Rules.INSTAGRAM) return
        if (!prefs.enabled) {
            if (inViewer) resetState()
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastEventAt > FOREGROUND_GAP_MS) {
            // No Instagram events for a while → it was in the background and just came back
            // (e.g. you tapped a reel link in WhatsApp). Until you tap something, that counts as "outside".
            freshFromOutside = true
        }
        lastEventAt = now

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                freshFromOutside = false
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                handleScroll(event, now)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                checkScreen(now)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // These fire constantly while video plays, so only look a few times a second.
                if (now - lastCheckAt >= CONTENT_THROTTLE_MS) checkScreen(now)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.hide()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------

    private fun checkScreen(now: Long) {
        lastCheckAt = now
        val root = rootInActiveWindow ?: return
        val capture = prefs.captureRequested
        val screen = scan(root, capture)

        if (capture) {
            // Capture mode: blocking is paused, and we keep saving whatever Instagram screen is showing.
            // It stops when you go back to ReelGuard, so the last screen you looked at is what's saved.
            if (screen.ids != null && screen.ids.isNotEmpty()) {
                prefs.capturedIds = buildString {
                    append("Captured ").append(DateFormat.getTimeInstance().format(Date())).append('\n')
                    append("Detected: ").append(screen.describe()).append("\n\n")
                    screen.ids.forEach { append(it).append('\n') }
                }
            }
            resetState()
            return
        }

        if (screen.inViewer) {
            if (!inViewer) {
                // Just entered the Reels player – decide if this is allowed.
                inViewer = true
                viewerEnteredAt = now
                startIndex = -1
                backStreak = 0
                allowedSession = !screen.reelsTabSelected && (cameFromDm || freshFromOutside)
                if (allowedSession) {
                    prefs.incAllowed()
                    prefs.lastEvent = stamp(if (cameFromDm) "Allowed a reel from a DM" else "Allowed a reel from a link")
                } else {
                    block(now, "Reels are off", "Blocked the Reels tab / feed")
                }
            } else if (allowedSession && screen.reelsTabSelected) {
                // Jumped from a DM reel into the Reels tab.
                allowedSession = false
                block(now, "Reels are off", "Blocked the Reels tab")
            } else if (!allowedSession && now - lastBackAt > RETRY_BACK_MS) {
                // Still in the player after we pressed Back (maybe Back only closed a sheet). Try again.
                block(now, "Reels are off", "Blocked the Reels tab / feed")
            }
        } else {
            inViewer = false
            allowedSession = false
            backStreak = 0
            // Remember where you are so we know where the next reel came from.
            if (screen.inDm) {
                cameFromDm = true
            } else if (screen.tabBarVisible) {
                cameFromDm = false
            }
            // A normal Instagram screen means you're navigating inside the app now.
            freshFromOutside = false
        }
    }

    private fun handleScroll(event: AccessibilityEvent, now: Long) {
        if (!inViewer || !allowedSession) return

        val sourceId = event.source?.viewIdResourceName?.substringAfter(":id/")
        val fromPager = when {
            sourceId != null -> Rules.matches(sourceId, Rules.REELS_PAGER)
            else -> event.className?.toString()?.contains("ViewPager") == true
        }
        if (!fromPager) return // e.g. scrolling the comments – that's fine

        val index = event.fromIndex
        val movedToAnotherReel = if (index >= 0) {
            if (startIndex < 0) {
                startIndex = index
                false
            } else {
                index != startIndex
            }
        } else {
            // Instagram didn't say which item; any pager scroll after the opening animation counts.
            now - viewerEnteredAt > OPENING_GRACE_MS
        }

        if (movedToAnotherReel) {
            allowedSession = false
            block(now, "That's the one you were sent 👋", "Stopped a scroll after a DM reel")
        }
    }

    private fun block(now: Long, message: String, log: String) {
        if (now - lastBackAt < MIN_BACK_INTERVAL_MS) return
        if (backStreak >= MAX_BACK_STREAK) {
            // Something isn't being detected right – don't keep hammering Back.
            if (backStreak == MAX_BACK_STREAK) {
                overlay.show("ReelGuard couldn't leave Reels – open the app → Capture IDs", 3500L)
                prefs.lastEvent = stamp("Gave up after $MAX_BACK_STREAK tries – detection may need updating")
                backStreak++
            }
            return
        }
        lastBackAt = now
        backStreak++
        performGlobalAction(GLOBAL_ACTION_BACK)
        overlay.show(message)
        prefs.incBlocked()
        prefs.lastEvent = stamp(log)
    }

    private fun resetState() {
        inViewer = false
        allowedSession = false
        cameFromDm = false
        freshFromOutside = true
        startIndex = -1
        backStreak = 0
    }

    private fun stamp(text: String) = "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())} · $text"

    // ---------------------------------------------------------------------------------------------

    private class Screen(
        val inViewer: Boolean,
        val reelsTabSelected: Boolean,
        val inDm: Boolean,
        val tabBarVisible: Boolean,
        val ids: Set<String>?,
    ) {
        fun describe(): String = buildList {
            if (inViewer) add("Reels player")
            if (reelsTabSelected) add("Reels tab selected")
            if (inDm) add("DM chat")
            if (tabBarVisible) add("bottom bar")
        }.ifEmpty { listOf("nothing recognised") }.joinToString(", ")
    }

    /** Walks Instagram's on-screen views (capped, so it stays cheap) and looks for known IDs. */
    @Suppress("DEPRECATION")
    private fun scan(root: AccessibilityNodeInfo, collectIds: Boolean): Screen {
        var inViewer = false
        var tabSelected = false
        var inDm = false
        var tabBar = false
        val ids: MutableSet<String>? = if (collectIds) sortedSetOf() else null

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++

            val visible = node.isVisibleToUser
            val fullId = node.viewIdResourceName
            if (fullId != null) {
                val id = fullId.substringAfter(":id/")
                ids?.add(id)
                if (visible) {
                    if (Rules.matches(id, Rules.REELS_VIEWER)) inViewer = true
                    if (Rules.matches(id, Rules.DM_THREAD)) inDm = true
                    if (Rules.matches(id, Rules.TAB_BAR)) tabBar = true
                    if (node.isSelected && Rules.matches(id, Rules.REELS_TAB)) tabSelected = true
                }
            }
            if (visible && node.isSelected && !tabSelected) {
                val label = node.contentDescription?.toString()
                if (label != null && Rules.REELS_TAB_LABELS.any { label.equals(it, ignoreCase = true) }) {
                    tabSelected = true
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        // The Reels tab counts as the Reels player even if the player IDs changed.
        return Screen(inViewer || tabSelected, tabSelected, inDm, tabBar, ids)
    }

    companion object {
        private const val CONTENT_THROTTLE_MS = 350L
        private const val FOREGROUND_GAP_MS = 2500L
        private const val OPENING_GRACE_MS = 1200L
        private const val MIN_BACK_INTERVAL_MS = 700L
        private const val RETRY_BACK_MS = 1000L
        private const val MAX_BACK_STREAK = 3
        private const val MAX_NODES = 800
    }
}
