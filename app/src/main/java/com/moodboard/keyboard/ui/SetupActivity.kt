package com.moodboard.keyboard.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Constraints
import com.moodboard.keyboard.R
import com.moodboard.keyboard.databinding.ActivitySetupBinding
import com.moodboard.keyboard.overlay.FloatingBubbleService
import com.moodboard.keyboard.stickers.MemeCache
import com.moodboard.keyboard.stickers.MemePrefetchWorker
import com.moodboard.keyboard.util.Prefs
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Onboarding: enable the keyboard and switch to it. API keys are built in. */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keys are baked in; make sure the sticker provider matches the built-in GIPHY key.
        Prefs(this).provider = "giphy"

        binding.btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        binding.btnSwitch.setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
        }
        binding.btnManageStickers.setOnClickListener {
            startActivity(Intent(this, StickerManagerActivity::class.java))
        }
        binding.btnCalibrate.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        binding.btnEmotionLab.setOnClickListener {
            startActivity(Intent(this, EmotionLabActivity::class.java))
        }

        setUpOverlayCard()
        setUpMemeCacheCard()
        // SPEC_V3 B.3 - enqueue the periodic prefetch worker. KEEP so re-visiting Setup
        // never resets an already-scheduled run.
        enqueuePeriodicPrefetch(ExistingPeriodicWorkPolicy.KEEP)
    }

    override fun onResume() {
        super.onResume()
        refreshCalibrationState()
        refreshMemeCacheStats()
        refreshOverlayState()
    }

    // ---------------- SPEC_V3 C.6 — floating meme button card ----------------

    private fun setUpOverlayCard() {
        binding.btnOverlayStart.setOnClickListener {
            // Always route through OverlayPermissionActivity: it short-circuits when both
            // permissions are already granted, and it keeps the foreground-service start
            // originating from a user tap in a visible Activity (SPEC_V3 C.4).
            startActivity(Intent(this, OverlayPermissionActivity::class.java))
        }
        binding.btnOverlayStop.setOnClickListener {
            FloatingBubbleService.stop(this)
            Prefs(this).overlayBubbleEnabled = false
            android.widget.Toast
                .makeText(this, R.string.overlay_stopped, android.widget.Toast.LENGTH_SHORT).show()
            // The service tears down asynchronously; re-read the state on the next frame.
            binding.btnOverlayStop.post { refreshOverlayState() }
        }
    }

    /** Reflects both the permission and the live service state, re-checked on every resume. */
    private fun refreshOverlayState() {
        val canDraw = Settings.canDrawOverlays(this)
        val running = FloatingBubbleService.isRunning
        binding.overlayStateText.text = when {
            !canDraw -> getString(R.string.overlay_state_needs_permission)
            running -> getString(R.string.overlay_state_running)
            else -> getString(R.string.overlay_state_stopped)
        }
        binding.btnOverlayStart.isEnabled = !running
        binding.btnOverlayStop.isEnabled = running
    }

    private fun refreshCalibrationState() {
        val prefs = Prefs(this)
        val at = prefs.neutralBaselineAt
        binding.calibrationStateText.text = if (prefs.neutralBaseline.isNotBlank() && at > 0) {
            val date = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
                .format(java.util.Date(at))
            getString(R.string.calibration_state_calibrated, date)
        } else {
            getString(R.string.calibration_state_uncalibrated)
        }
        binding.btnCalibrate.text = if (prefs.neutralBaseline.isNotBlank())
            getString(R.string.btn_recalibrate) else getString(R.string.btn_calibrate)
    }

    // ---------------- SPEC_V3 B — meme cache card ----------------

    private fun setUpMemeCacheCard() {
        val prefs = Prefs(this)
        binding.switchPrefetchEnabled.isChecked = prefs.prefetchEnabled
        binding.switchPrefetchWifiOnly.isChecked = prefs.prefetchWifiOnly

        binding.switchPrefetchEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.prefetchEnabled = checked
        }
        binding.switchPrefetchWifiOnly.setOnCheckedChangeListener { _, checked ->
            prefs.prefetchWifiOnly = checked
            // Constraints changed - UPDATE the already-scheduled periodic work in place.
            enqueuePeriodicPrefetch(ExistingPeriodicWorkPolicy.UPDATE)
        }
        binding.btnRefreshMemeCache.setOnClickListener { refreshMemeCacheNow() }
    }

    private fun refreshMemeCacheNow() {
        binding.memeCacheStatsText.text = getString(R.string.meme_cache_refreshing)
        val request = OneTimeWorkRequestBuilder<MemePrefetchWorker>().build()
        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniqueWork(ONE_SHOT_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        toastQueued()
        workManager.getWorkInfoByIdLiveData(request.id).observe(this) { info ->
            if (info != null && info.state.isFinished) refreshMemeCacheStats()
        }
    }

    private fun toastQueued() {
        android.widget.Toast.makeText(this, R.string.meme_cache_refresh_queued, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun refreshMemeCacheStats() {
        val stats = MemeCache(this).stats()
        val sizeText = formatBytes(stats.totalBytes)
        val lastRefreshText = if (stats.lastRefreshAt > 0) {
            java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
                .format(java.util.Date(stats.lastRefreshAt))
        } else {
            getString(R.string.meme_cache_never)
        }
        binding.memeCacheStatsText.text =
            getString(R.string.meme_cache_stats, stats.itemCount, sizeText, lastRefreshText)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun enqueuePeriodicPrefetch(policy: ExistingPeriodicWorkPolicy) {
        val prefs = Prefs(this)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (prefs.prefetchWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<MemePrefetchWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, policy, request)
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "meme_prefetch_periodic"
        private const val ONE_SHOT_WORK_NAME = "meme_prefetch_oneshot"
    }
}