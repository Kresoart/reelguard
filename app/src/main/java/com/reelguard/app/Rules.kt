package com.reelguard.app

/**
 * Everything that depends on Instagram's internal screen structure lives here.
 *
 * Instagram renames its views every few months. When detection stops working:
 *   1. Open ReelGuard → "Capture Instagram screen IDs".
 *   2. Open the screen that isn't detected (e.g. the Reels tab) in Instagram.
 *   3. Come back to ReelGuard and look at the captured IDs.
 *   4. Add the new ID (or a distinctive part of it) to the matching list below.
 *
 * Matching is by "contains", so "clips_viewer" also matches "clips_viewer_view_pager".
 */
object Rules {

    const val INSTAGRAM = "com.instagram.android"

    /** Any visible view with one of these in its ID means the full-screen Reels player is open. */
    val REELS_VIEWER = listOf(
        "clips_viewer",
    )

    /** The vertical pager inside the Reels player. Scrolling it = moving to another reel. */
    val REELS_PAGER = listOf(
        "clips_viewer_view_pager",
        "clips_viewer_pager",
    )

    /** The Reels button in the bottom bar. If it is selected, you are on the Reels tab. */
    val REELS_TAB = listOf(
        "clips_tab",
    )

    /** Fallback: a selected bottom-bar button whose label is one of these. */
    val REELS_TAB_LABELS = listOf(
        "Reels",
    )

    /** Views that only exist inside a DM conversation (the message box, the thread list). */
    val DM_THREAD = listOf(
        "row_thread_composer",
        "thread_composer",
        "direct_thread",
        "message_list",
    )

    /**
     * Instagram's bottom navigation bar (or its buttons). It is hidden inside DMs and shown on
     * Home, Search/Explore, Profile… Seeing it means "you're browsing Instagram, not a chat".
     */
    val TAB_BAR = listOf(
        "tab_bar",
        "feed_tab",
        "search_tab",
        "profile_tab",
    )

    /** The Search button in the bottom bar. If it is selected, you are on the Search/Explore page. */
    val SEARCH_TAB = listOf(
        "search_tab",
    )

    /** Fallback: a selected bottom-bar button whose label is one of these. */
    val SEARCH_TAB_LABELS = listOf(
        "Search and explore",
        "Search",
    )

    /** The search box at the top of the Search page. The grid below it gets covered. */
    val SEARCH_FIELD = listOf(
        "action_bar_search_edit_text",
        "search_edit_text",
        "search_bar",
    )

    fun matches(id: String, patterns: List<String>): Boolean =
        patterns.any { id.contains(it, ignoreCase = true) }
}
