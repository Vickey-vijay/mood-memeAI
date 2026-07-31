package com.moodboard.keyboard.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.moodboard.keyboard.R
import com.moodboard.keyboard.databinding.ActivitySetupBinding
import com.moodboard.keyboard.util.Prefs

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
    }

    override fun onResume() {
        super.onResume()
        refreshCalibrationState()
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
}