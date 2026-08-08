package com.moodboard.keyboard.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.moodboard.keyboard.R
import com.moodboard.keyboard.overlay.FloatingBubbleService

/**
 * Transparent gatekeeper for the floating bubble (SPEC_V3 C.4).
 *
 * Runs a small three-step chain and starts the service only at the end:
 *  1. **POST_NOTIFICATIONS** on API 33+, *before* the service starts — a foreground service
 *     whose notification the user never sees is a bad experience and, on some OEM builds, a
 *     candidate for silent termination.
 *  2. **SYSTEM_ALERT_WINDOW** via `ACTION_MANAGE_OVERLAY_PERMISSION` with the `package:` URI.
 *     It is checked with [Settings.canDrawOverlays] both before and after — the settings
 *     screen returns `RESULT_CANCELED` even when the user granted it, so the result code is
 *     worthless and only a fresh check is trustworthy.
 *  3. Start [FloatingBubbleService].
 *
 * Why an Activity at all: Android 12+ forbids background foreground-service starts. Routing
 * every start through this (user-initiated, visible) Activity is what keeps the service
 * start legal. Nothing else in the app is allowed to start the service.
 */
class OverlayPermissionActivity : AppCompatActivity() {

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Denied notifications is not fatal — the service still runs, the user just won't
            // see the Stop action. Carry on to the overlay step either way.
            requestOverlayStep()
        }

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            finishStep()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !FloatingBubbleService.hasNotificationPermission(this)
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestOverlayStep()
        }
    }

    private fun requestOverlayStep() {
        if (Settings.canDrawOverlays(this)) {
            finishStep()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            overlayLauncher.launch(intent)
        } catch (t: Throwable) {
            // A handful of OEM builds have no per-package overlay screen; fall back to the
            // global list rather than crashing.
            try {
                overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (t2: Throwable) {
                toast(getString(R.string.overlay_permission_unavailable))
                finish()
            }
        }
    }

    private fun finishStep() {
        if (Settings.canDrawOverlays(this)) {
            // Legal FGS start: we are a visible, user-initiated Activity right now.
            FloatingBubbleService.start(this)
            toast(getString(R.string.overlay_started))
        } else {
            toast(getString(R.string.overlay_permission_needed))
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}
