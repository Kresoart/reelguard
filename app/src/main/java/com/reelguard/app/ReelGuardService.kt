package com.reelguard.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import java.text.DateFormat
import java.util.Date

/**
 * The blocker.
 *
 * Rules:
 *  - Reels player opened from the Reels tab / feed / Explore  → press Back.
 *  - Reels player opened from a DM, or straight from another app (a link in WhatsApp etc.)
 *    → allowed, but as soon as you swipe to the next reel → press Back.
 *
 * Battery: outside Instagram the service only listens for "an app/screen came to the front"
 * events (a handful per minute). The detailed Instagram events are switched on only while
 * Instagram is the app in front, and switched off again as soon as you leave it.
 */
class ReelGuardService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private lateinit var overlay: Overlay

    // ---- which app is in front ----
    private var instagramInFront = false
    private var lastOtherApp: String? = null   // the last real app that was in front before Instagram
    private var instagramEnteredAt = 0L
    private var launcherPackages: Set<String> = emptySet()
    private var keyboardPackages: Set<String> = emptySet()

    // ---- Reels state ----
    private var inViewer = false            // Reels player currently on screen
    private var allowedSession = false      // this player session was opened from a DM / link
    private var cameFromDm = false          // last real Instagram screen before the player was a DM chat
    private var freshFromOutside = false    // Instagram was opened from another app and you haven't moved yet
    private var viewerEnteredAt = 0L
    private var startIndex = -1             // which item the pager was on when you arrived

    // ---- timing ----
    private var lastCheckAt = 0L
    private var lastBackAt = 0L
    private var backStreak = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        overlay = Overlay(this)
        launcherPackages = findLaunchers()
        keyboardPackages = findKeyboards()
        resetReelsState()
        freshFromOutside = false

        val inFrontNow = rootInActiveWindow?.packageName?.toString()
        if (inFrontNow == Rules.INSTAGRAM) enterInstagram(fromOtherApp = false) else watchInstagramClosely(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::prefs.isInitialized) return
        val pkg = event.packageName?.toString() ?: return

        if (pkg != Rules.INSTAGRAM) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && isRealApp(pkg)) {
                // Another app came to the front.
                lastOtherApp = pkg
                if (instagramInFront) leaveInstagram()
            }
            return
        }

        val now = SystemClock.uptimeMillis()
        if (!instagramInFront) {
            val from = lastOtherApp
            enterInstagram(fromOtherApp = from != null && from !in launcherPackages && from != packageName)
        }

        if (!prefs.enabled) {
            if (inViewer) resetReelsState()
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleScroll(event, now)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> checkScreen(now)
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

    // ---- foreground tracking --------------------------------------------------------------------

    private fun enterInstagram(fromOtherApp: Boolean) {
        instagramInFront = true
        instagramEnteredAt = SystemClock.uptimeMillis()
        freshFromOutside = fromOtherApp
        resetReelsState()
        watchInstagramClosely(true)
    }

    private fun leaveInstagram() {
        instagramInFront = false
        freshFromOutside = false
        resetReelsState()
        watchInstagramClosely(false)
    }

    /** Switches between "everything from Instagram" and "only app switches" to save battery. */
    private fun watchInstagramClosely(on: Boolean) {
        val info = serviceInfo ?: return
        val wanted = if (on) EVENTS_IN_INSTAGRAM else EVENTS_OUTSIDE
        if (info.eventTypes != wanted) {
            info.eventTypes = wanted
            serviceInfo = info
        }
    }

    /** Pop-ups that sit on top of an app don't count as "switching apps". */
    private fun isRealApp(pkg: String): Boolean =
        pkg != "com.android.systemui" &&   // notification shade, volume panel
            pkg != "android" &&            // system dialogs and share sheets
            pkg !in keyboardPackages

    private fun findLaunchers(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    private fun findKeyboards(): Set<String> {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return emptySet()
        return imm.inputMethodList.map { it.packageName }.toSet()
    }

    // ---- screen checks --------------------------------------------------------------------------

    private fun checkScreen(now: Long) {
        lastCheckAt = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != Rules.INSTAGRAM) return
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
            resetReelsState()
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
                    block(now, "Reels are off", "Blocked Reels")
                }
            } else if (allowedSession && screen.reelsTabSelected) {
                // Jumped from a DM reel into the Reels tab.
                allowedSession = false
                block(now, "Reels are off", "Blocked the Reels tab")
            } else if (!allowedSession && now - lastBackAt > RETRY_BACK_MS) {
                // Still in the player after we pressed Back (maybe Back only closed a sheet). Try again.
                block(now, "Reels are off", "Blocked Reels")
            }
        } else {
            inViewer = false
            allowedSession = false
            backStreak = 0
            // Remember whether this screen is a DM chat, so we know where the next reel came from.
            cameFromDm = screen.inDm
            // Once you're looking at a normal Instagram screen, you're browsing Instagram itself –
            // a reel you open next is NOT "from another app". (Short grace period for cold starts,
            // where Instagram flashes its home screen before jumping to the linked reel.)
            if (now - instagramEnteredAt > LINK_GRACE_MS) freshFromOutside = false
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
            freshFromOutside = false
            block(now, "That's the one you were sent 👋", "Stopped a scroll after a shared reel")
        }
    }

    private fun block(now: Long, message: String, log: String) {
        if (now - lastBackAt < MIN_BACK_INTERVAL_MS) return
        if (backStreak >= MAX_BACK_STREAK) {
            // Something isn't being detected right – don't keep hammering Back.
            if (backStreak == MAX_BACK_STREAK) {
                overlay.show("Couldn't leave Reels – open ReelGuard → Fix detection", 3500L)
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

    private fun resetReelsState() {
        inViewer = false
        allowedSession = false
        cameFromDm = false
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
        private const val EVENTS_OUTSIDE = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        private const val EVENTS_IN_INSTAGRAM = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED

        private const val CONTENT_THROTTLE_MS = 350L
        private const val LINK_GRACE_MS = 2500L
        private const val OPENING_GRACE_MS = 1200L
        private const val MIN_BACK_INTERVAL_MS = 700L
        private const val RETRY_BACK_MS = 1000L
        private const val MAX_BACK_STREAK = 3
        private const val MAX_NODES = 800
    }
}
