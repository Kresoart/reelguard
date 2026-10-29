package com.reelguard.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs

/**
 * The blocker.
 *
 * Rules:
 *  - Reels player opened from the Reels tab / feed / Explore  → press Back.
 *  - Reels player opened from a DM, or straight from another app (a link in WhatsApp etc.)
 *    → allowed, but as soon as you swipe to the next reel → press Back.
 *
 * Battery: outside Instagram the service only listens for "an app came to the front"
 * events. The detailed Instagram events are switched on only while Instagram is in front.
 */
class ReelGuardService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private lateinit var overlay: Overlay
    private lateinit var exploreCover: ExploreCover

    // ---- which app is in front ----
    private var instagramInFront = false
    private var lastOtherApp: String? = null   // the last real app that was in front before Instagram
    private var instagramEnteredAt = 0L
    private var launcherPackages: Set<String> = emptySet()
    private var keyboardPackages: Set<String> = emptySet()

    // ---- where you are inside Instagram ----
    // Sticky: only changes on a screen we recognise for sure (a DM chat, or a screen with the
    // bottom tab bar). In-between screens and transition frames leave it alone.
    private var cameFromDm = false
    private var lastScreen = "unknown"
    private var freshFromOutside = false    // Instagram was opened from another app and you haven't moved yet

    // ---- Reels player session ----
    private var inViewer = false
    private var allowedSession = false
    private var viewerEnteredAt = 0L
    private var baselineIndex = -1          // which reel you were on when the session started
    private var scrolledPx = 0              // fallback when Instagram doesn't report reel numbers

    // ---- timing ----
    private var lastCheckAt = 0L
    private var lastBackAt = 0L
    private var backStreak = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        overlay = Overlay(this)
        exploreCover = ExploreCover(this)
        launcherPackages = findLaunchers()
        keyboardPackages = findKeyboards()

        val inFrontNow = rootInActiveWindow?.packageName?.toString()
        if (inFrontNow == Rules.INSTAGRAM) enterInstagram(fromOtherApp = false) else watchInstagramClosely(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::prefs.isInitialized) return
        val pkg = event.packageName?.toString() ?: return

        if (pkg != Rules.INSTAGRAM) {
            // Normally an app switch arrives as a "window state changed" event. If we somehow missed
            // it, any event from another app while we think Instagram is in front triggers the check too.
            val maybeSwitch = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || instagramInFront
            if (maybeSwitch && isRealAppSwitch(pkg)) {
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
            if (inViewer) endViewerSession()
            exploreCover.hide()
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
        if (::overlay.isInitialized) overlay.destroy()
        if (::exploreCover.isInitialized) exploreCover.destroy()
        super.onDestroy()
    }

    // ---- foreground tracking --------------------------------------------------------------------

    private fun enterInstagram(fromOtherApp: Boolean) {
        instagramInFront = true
        instagramEnteredAt = SystemClock.uptimeMillis()
        freshFromOutside = fromOtherApp
        cameFromDm = false
        lastScreen = if (fromOtherApp) "another app ($lastOtherApp)" else "unknown"
        endViewerSession()
        watchInstagramClosely(true)
    }

    private fun leaveInstagram() {
        instagramInFront = false
        exploreCover.hide()
        freshFromOutside = false
        cameFromDm = false
        endViewerSession()
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

    /**
     * True only if this really is a different app taking over the screen – not a notification,
     * the keyboard, a floating bubble or some other app's overlay drawn on top of Instagram.
     */
    private fun isRealAppSwitch(pkg: String): Boolean {
        if (pkg == "com.android.systemui" || pkg == "android" || pkg in keyboardPackages) return false
        val active = rootInActiveWindow?.packageName?.toString() ?: return false
        return active == pkg
    }

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
                    append("Captured ").append(Prefs.timeNow()).append('\n')
                    append("Detected: ").append(screen.describe()).append("\n\n")
                    screen.ids.forEach { append(it).append('\n') }
                }
            }
            endViewerSession()
            exploreCover.hide()
            return
        }

        if (screen.inViewer) {
            exploreCover.hide()
            if (!inViewer) {
                startViewerSession(now, screen)
            } else if (allowedSession && screen.reelsTabSelected) {
                // Jumped from a shared reel into the Reels tab.
                allowedSession = false
                block(now, MSG_OFF, "Blocked the Reels tab")
            } else if (!allowedSession && now - lastBackAt > RETRY_BACK_MS) {
                // Still in the player after we pressed Back (maybe Back only closed a sheet). Try again.
                block(now, MSG_OFF, "Still in Reels – pressed Back again")
            }
        } else {
            if (inViewer) endViewerSession()
            when {
                screen.inDm -> {
                    cameFromDm = true
                    lastScreen = "DM chat"
                }
                screen.tabBarVisible -> {
                    cameFromDm = false
                    lastScreen = "feed / Explore / profile"
                }
                // Anything else (DM inbox, a loading frame, a sheet…) keeps the previous answer.
            }
            // Once you're on a normal Instagram screen you're browsing Instagram itself, so a reel
            // you open next is not "from another app". (Grace period for cold starts, where Instagram
            // flashes its home screen before jumping to the linked reel.)
            if (screen.tabBarVisible && now - instagramEnteredAt > LINK_GRACE_MS) freshFromOutside = false

            updateExploreCover(screen)
        }
    }

    /**
     * On the Search page, while the search box is idle, cover the Explore grid between the search
     * box and the bottom bar. As soon as you tap the search box (or there's text in it), the cover
     * goes away so you can see your search results.
     */
    private fun updateExploreCover(screen: Screen) {
        val field = screen.searchField
        val coverIt = prefs.hideExplore && screen.searchTabSelected && field != null && !screen.searchActive
        if (!coverIt || field == null) {
            exploreCover.hide()
            return
        }
        // If we can't find the bottom bar, stop ~64dp above the bottom so the tabs stay tappable.
        val fallbackBottom = resources.displayMetrics.heightPixels - (64 * resources.displayMetrics.density).toInt()
        val bottom = screen.tabBarTop?.takeIf { it > field.bottom } ?: fallbackBottom
        exploreCover.show(field.bottom + 1, bottom)
        if (!exploreCover.isShowing) prefs.log("Hid the Explore grid")
    }

    private fun startViewerSession(now: Long, screen: Screen) {
        inViewer = true
        viewerEnteredAt = now
        baselineIndex = -1
        scrolledPx = 0
        backStreak = 0
        allowedSession = !screen.reelsTabSelected && (cameFromDm || freshFromOutside)

        if (allowedSession) {
            prefs.incAllowed()
            prefs.log(if (cameFromDm) "Allowed a reel from a DM" else "Allowed a reel from a link")
        } else {
            val why = if (screen.reelsTabSelected) "Reels tab" else "opened from $lastScreen"
            block(now, MSG_OFF, "Blocked Reels ($why)")
        }
    }

    private fun endViewerSession() {
        inViewer = false
        allowedSession = false
        baselineIndex = -1
        scrolledPx = 0
        backStreak = 0
    }

    /**
     * Decides whether you actually moved to a different reel. Instagram also fires scroll events
     * while a reel loads or when its layout shifts, so we only count a *settled* change of reel.
     */
    private fun handleScroll(event: AccessibilityEvent, now: Long) {
        if (!inViewer || !allowedSession) return
        if (now - viewerEnteredAt < SETTLE_MS) return // opening animation / preloading

        val sourceId = event.source?.viewIdResourceName?.substringAfter(":id/")
        val fromPager = when {
            sourceId != null -> Rules.matches(sourceId, Rules.REELS_PAGER)
            else -> event.className?.toString()?.contains("ViewPager") == true
        }
        if (!fromPager) return // e.g. scrolling the comments – that's fine

        val from = event.fromIndex
        val to = event.toIndex
        val moved: Boolean
        val detail: String
        if (from >= 0) {
            if (baselineIndex < 0) baselineIndex = from
            // While a swipe is still in motion two reels are visible (from != to). Only judge once
            // a single reel fills the screen again.
            val settled = to < 0 || to == from
            moved = settled && from != baselineIndex
            detail = "reel $baselineIndex → $from"
        } else if (Build.VERSION.SDK_INT >= 28) {
            scrolledPx += abs(event.scrollDeltaY)
            moved = scrolledPx > resources.displayMetrics.heightPixels / 2
            detail = "scrolled ${scrolledPx}px"
        } else {
            moved = true
            detail = "scroll"
        }

        if (moved) {
            allowedSession = false
            freshFromOutside = false
            block(now, MSG_SENT, "Stopped a swipe after a shared reel ($detail)")
        }
    }

    private fun block(now: Long, message: String, log: String) {
        // The pop-up always shows, even when we skip pressing Back again a moment later.
        if (backStreak >= MAX_BACK_STREAK) {
            if (backStreak == MAX_BACK_STREAK) {
                overlay.show("Couldn't leave Reels – open ReelGuard → Fix detection", 3500L)
                prefs.log("Gave up after $MAX_BACK_STREAK tries – detection may need updating")
                backStreak++
            }
            return
        }
        overlay.show(message)
        if (now - lastBackAt < MIN_BACK_INTERVAL_MS) return
        lastBackAt = now
        backStreak++
        performGlobalAction(GLOBAL_ACTION_BACK)
        prefs.incBlocked()
        prefs.log(log)
    }

    // ---------------------------------------------------------------------------------------------

    private class Screen(
        val inViewer: Boolean,
        val reelsTabSelected: Boolean,
        val inDm: Boolean,
        val tabBarVisible: Boolean,
        val ids: Set<String>?,
        val searchTabSelected: Boolean = false,
        val searchField: Rect? = null,      // where the search box is on screen
        val searchActive: Boolean = false,  // search box focused or has text → showing results
        val tabBarTop: Int? = null,         // top edge of the bottom bar
    ) {
        fun describe(): String = buildList {
            if (inViewer) add("Reels player")
            if (reelsTabSelected) add("Reels tab selected")
            if (inDm) add("DM chat")
            if (tabBarVisible) add("bottom bar")
            if (searchTabSelected) add(if (searchActive) "Search (typing)" else "Search page")
        }.ifEmpty { listOf("nothing recognised") }.joinToString(", ")
    }

    /** Walks Instagram's on-screen views (capped, so it stays cheap) and looks for known IDs. */
    @Suppress("DEPRECATION")
    private fun scan(root: AccessibilityNodeInfo, collectIds: Boolean): Screen {
        var inViewer = false
        var tabSelected = false
        var inDm = false
        var tabBar = false
        var searchTab = false
        var searchField: Rect? = null
        var searchActive = false
        var tabBarTop: Int? = null
        var anyTabTop: Int? = null
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
                    if (node.isSelected && Rules.matches(id, Rules.SEARCH_TAB)) searchTab = true
                    if (id == "tab_bar") {
                        tabBarTop = Rect().also { node.getBoundsInScreen(it) }.top
                    } else if (Rules.matches(id, Rules.TAB_BAR) || Rules.matches(id, Rules.REELS_TAB)) {
                        // One of the bottom-bar buttons (in case the bar itself has a different ID).
                        val top = Rect().also { node.getBoundsInScreen(it) }.top
                        anyTabTop = minOf(anyTabTop ?: top, top)
                    }
                    if (searchField == null && Rules.matches(id, Rules.SEARCH_FIELD)) {
                        searchField = Rect().also { node.getBoundsInScreen(it) }
                        searchActive = isSearchActive(node)
                    }
                }
            }
            if (visible && searchField == null && node.className?.toString()?.endsWith("EditText") == true) {
                // Fallback if the search box ID changed: the first text box on screen.
                searchField = Rect().also { node.getBoundsInScreen(it) }
                searchActive = isSearchActive(node)
            }
            if (visible && node.isSelected) {
                val label = node.contentDescription?.toString()
                if (label != null) {
                    if (Rules.REELS_TAB_LABELS.any { label.equals(it, ignoreCase = true) }) tabSelected = true
                    if (Rules.SEARCH_TAB_LABELS.any { label.equals(it, ignoreCase = true) }) searchTab = true
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        // The Reels tab counts as the Reels player even if the player IDs changed.
        // A DM chat never has the bottom bar, so if both match, trust the bar.
        return Screen(
            inViewer = inViewer || tabSelected,
            reelsTabSelected = tabSelected,
            inDm = inDm && !tabBar,
            tabBarVisible = tabBar,
            ids = ids,
            searchTabSelected = searchTab,
            searchField = searchField,
            searchActive = searchActive,
            tabBarTop = tabBarTop ?: anyTabTop,
        )
    }

    /** The search box is "in use" when it has focus or contains typed text (not just the hint). */
    private fun isSearchActive(node: AccessibilityNodeInfo): Boolean {
        if (node.isFocused) return true
        if (node.className?.toString()?.endsWith("EditText") != true) return false
        val text = node.text
        return !text.isNullOrBlank() && !node.isShowingHintText
    }

    companion object {
        private const val EVENTS_OUTSIDE = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        private const val EVENTS_IN_INSTAGRAM = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED

        private const val MSG_OFF = "Reels are off"
        private const val MSG_SENT = "That's the one you were sent 👋"

        private const val CONTENT_THROTTLE_MS = 300L
        private const val LINK_GRACE_MS = 2500L
        private const val SETTLE_MS = 700L
        private const val MIN_BACK_INTERVAL_MS = 700L
        private const val RETRY_BACK_MS = 1000L
        private const val MAX_BACK_STREAK = 3
        private const val MAX_NODES = 800
    }
}
