package com.reelguard.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** The settings screen: on/off, setup help, counters and the "capture IDs" tool. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var openAccessibility: Button
    private lateinit var enabledSwitch: Switch
    private lateinit var stats: TextView
    private lateinit var lastEvent: TextView
    private lateinit var capture: Button
    private lateinit var capturedIds: TextView

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        statusTitle = findViewById(R.id.statusTitle)
        statusBody = findViewById(R.id.statusBody)
        openAccessibility = findViewById(R.id.openAccessibility)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        stats = findViewById(R.id.stats)
        lastEvent = findViewById(R.id.lastEvent)
        capture = findViewById(R.id.capture)
        capturedIds = findViewById(R.id.capturedIds)

        openAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find ReelGuard in the list and turn it on", Toast.LENGTH_LONG).show()
        }

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (prefs.enabled != checked) prefs.enabled = checked
        }

        findViewById<Button>(R.id.resetCounters).setOnClickListener {
            prefs.resetCounters()
        }

        findViewById<Button>(R.id.openAppInfo).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }

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

    private fun refresh() {
        val serviceOn = isServiceEnabled()
        val blocking = prefs.enabled

        statusTitle.text = when {
            !serviceOn -> "ReelGuard is off"
            !blocking -> "ReelGuard is paused"
            else -> "ReelGuard is on"
        }
        statusBody.text = when {
            !serviceOn -> "Turn on the ReelGuard accessibility service so it can see when Instagram opens Reels."
            !blocking -> "Reels are not being blocked right now."
            else -> "The Reels tab is blocked. Reels people send you in DMs still play — but only that one."
        }
        openAccessibility.visibility = if (serviceOn) View.GONE else View.VISIBLE

        if (enabledSwitch.isChecked != blocking) enabledSwitch.isChecked = blocking

        stats.text = "Scrolls stopped: ${prefs.blockedCount}\nDM reels allowed: ${prefs.allowedCount}"
        val last = prefs.lastEvent
        lastEvent.text = if (last.isEmpty()) "Nothing yet" else "Last: $last"

        capture.text = if (prefs.captureRequested) "Waiting for Instagram… (tap to cancel)" else "Capture Instagram screen IDs"
        capturedIds.text = prefs.capturedIds
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, ReelGuardService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}
