package com.reelguard.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** The home screen: status hero, on/off, counters, setup help and the "capture IDs" tool. */
class MainActivity : Activity() {

    private enum class State { ON, PAUSED, NOT_ACTIVE }

    private lateinit var prefs: Prefs

    private lateinit var heroGlow: View
    private lateinit var heroShield: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var openAccessibility: Button
    private lateinit var toggleCard: View
    private lateinit var toggleHint: TextView
    private lateinit var enabledSwitch: Switch
    private lateinit var statToday: TextView
    private lateinit var statTotal: TextView
    private lateinit var lastDot: View
    private lateinit var lastEvent: TextView
    private lateinit var allowedStat: TextView
    private lateinit var capture: Button
    private lateinit var capturedIds: TextView
    private lateinit var historyView: TextView

    private var shownState: State? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        heroGlow = findViewById(R.id.heroGlow)
        heroShield = findViewById(R.id.heroShield)
        statusTitle = findViewById(R.id.statusTitle)
        statusBody = findViewById(R.id.statusBody)
        openAccessibility = findViewById(R.id.openAccessibility)
        toggleCard = findViewById(R.id.toggleCard)
        toggleHint = findViewById(R.id.toggleHint)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        statToday = findViewById(R.id.statToday)
        statTotal = findViewById(R.id.statTotal)
        lastDot = findViewById(R.id.lastDot)
        lastEvent = findViewById(R.id.lastEvent)
        allowedStat = findViewById(R.id.allowedStat)
        capture = findViewById(R.id.capture)
        capturedIds = findViewById(R.id.capturedIds)
        historyView = findViewById(R.id.history)

        val openA11y = View.OnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find ReelGuard in the list and turn it on", Toast.LENGTH_LONG).show()
        }
        openAccessibility.setOnClickListener(openA11y)
        findViewById<Button>(R.id.openAccessibility2).setOnClickListener(openA11y)

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (prefs.enabled != checked) prefs.enabled = checked
        }
        // Tapping anywhere on the card flips the switch too.
        toggleCard.setOnClickListener { enabledSwitch.toggle() }

        findViewById<View>(R.id.resetCounters).setOnClickListener {
            prefs.resetCounters()
            Toast.makeText(this, "Counters reset", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.openAppInfo).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }

        setupExpandable(R.id.setupHeader, R.id.setupBody, R.id.setupChevron)
        setupExpandable(R.id.fixHeader, R.id.fixBody, R.id.fixChevron)

        capture.setOnClickListener {
            if (prefs.captureRequested) {
                prefs.captureRequested = false
            } else {
                prefs.capturedIds = ""
                prefs.captureRequested = true
                val launch = packageManager.getLaunchIntentForPackage(Rules.INSTAGRAM)
                if (launch != null) {
                    Toast.makeText(this, "Go to the screen that isn't detected, then come back", Toast.LENGTH_LONG).show()
                    startActivity(launch)
                } else {
                    Toast.makeText(this, "Instagram isn't installed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Instagram ends a capture; the last screen you were on is kept.
        if (prefs.captureRequested) prefs.captureRequested = false
        prefs.registerListener(prefListener)
        refresh()
    }

    override fun onPause() {
        prefs.unregisterListener(prefListener)
        super.onPause()
    }

    private fun setupExpandable(headerId: Int, bodyId: Int, chevronId: Int) {
        val body = findViewById<View>(bodyId)
        val chevron = findViewById<View>(chevronId)
        findViewById<View>(headerId).setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (open) 90f else 0f).setDuration(180).start()
        }
    }

    private fun refresh() {
        val state = when {
            !isServiceEnabled() -> State.NOT_ACTIVE
            !prefs.enabled -> State.PAUSED
            else -> State.ON
        }
        renderHero(state)

        val blocking = prefs.enabled
        if (enabledSwitch.isChecked != blocking) enabledSwitch.isChecked = blocking
        toggleHint.text = if (blocking) "On" else "Paused · Reels work normally"

        statToday.text = prefs.todayBlocked.toString()
        statTotal.text = prefs.blockedCount.toString()
        val allowed = prefs.allowedCount
        allowedStat.text = if (allowed == 1) "1 shared reel allowed" else "$allowed shared reels allowed"
        val last = prefs.lastEvent
        lastEvent.text = if (last.isEmpty()) "Nothing blocked yet" else last
        lastDot.alpha = if (last.isEmpty()) 0.3f else 1f

        capture.text = if (prefs.captureRequested) "Waiting for Instagram… (tap to cancel)" else "Capture Instagram screen IDs"
        val history = prefs.history
        historyView.text = if (history.isEmpty()) "Nothing yet" else history

        val ids = prefs.capturedIds
        capturedIds.text = ids
        capturedIds.visibility = if (ids.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderHero(state: State) {
        val accent = getColor(R.color.accent)
        val muted = getColor(R.color.text_muted)

        statusTitle.text = when (state) {
            State.ON -> "Reels blocked"
            State.PAUSED -> "Paused"
            State.NOT_ACTIVE -> "Not active"
        }
        statusBody.text = when (state) {
            State.ON -> "Only reels people send you can play — and only that one."
            State.PAUSED -> "Reels aren't being blocked right now. Flip the switch to turn it back on."
            State.NOT_ACTIVE -> "Turn on the ReelGuard accessibility service so it can see when Instagram opens Reels."
        }
        openAccessibility.visibility = if (state == State.NOT_ACTIVE) View.VISIBLE else View.GONE
        toggleCard.visibility = if (state == State.NOT_ACTIVE) View.GONE else View.VISIBLE

        val on = state == State.ON
        heroShield.imageTintList = ColorStateList.valueOf(if (on) accent else muted)
        heroGlow.setBackgroundResource(if (on) R.drawable.glow_on else R.drawable.glow_off)

        if (shownState != null && shownState != state) {
            // Little "pulse" when the state changes.
            heroShield.scaleX = 0.85f
            heroShield.scaleY = 0.85f
            heroShield.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
            heroGlow.alpha = 0f
            heroGlow.animate().alpha(1f).setDuration(260).start()
        }
        shownState = state
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, ReelGuardService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}
