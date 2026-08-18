package com.moodboard.keyboard.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.moodboard.keyboard.R
import com.moodboard.keyboard.databinding.ActivitySetupBinding
import com.moodboard.keyboard.overlay.FloatingBubbleService
import com.moodboard.keyboard.stickers.GiphyGifsSource
import com.moodboard.keyboard.stickers.GiphyStickersSource
import com.moodboard.keyboard.stickers.GiphyTrendingSource
import com.moodboard.keyboard.stickers.ImgflipSource
import com.moodboard.keyboard.stickers.MemeCategory
import com.moodboard.keyboard.stickers.TenorSource
import com.moodboard.keyboard.util.Prefs

/** Onboarding: enable the keyboard and switch to it. API keys are built in. */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        setUpMemeSourcesCard()
        setUpOverlayCard()
        setUpAdvancedSection()
    }

    // ---------------- Presentation: collapsible "Advanced" section ----------------

    /** Purely visual grouping - meme sources / floating button start collapsed so the
     *  setup screen isn't a wall of identical cards. No effect on any of their behaviour. */
    private fun setUpAdvancedSection() {
        binding.advancedHeader.setOnClickListener {
            val expanding = binding.advancedContent.visibility != View.VISIBLE
            binding.advancedContent.visibility = if (expanding) View.VISIBLE else View.GONE
            binding.advancedChevron.animate()
                .rotation(if (expanding) 180f else 0f)
                .setDuration(150)
                .start()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCalibrationState()
        refreshOverlayState()
    }

    // ---------------- Provider-aggregator architecture — meme sources card ----------------

    /** Per-source on/off switches, the "Meme style" category picker, and the optional Tenor key field. */
    private fun setUpMemeSourcesCard() {
        val prefs = Prefs(this)

        setUpMemeCategoryPicker(prefs)

        binding.switchSourceGiphyGifs.isChecked = prefs.isMemeSourceEnabled(GiphyGifsSource.ID)
        binding.switchSourceGiphyStickers.isChecked = prefs.isMemeSourceEnabled(GiphyStickersSource.ID)
        binding.switchSourceGiphyTrending.isChecked = prefs.isMemeSourceEnabled(GiphyTrendingSource.ID)
        binding.switchSourceImgflip.isChecked = prefs.isMemeSourceEnabled(ImgflipSource.ID)
        binding.switchSourceTenor.isChecked = prefs.isMemeSourceEnabled(TenorSource.ID)
        binding.inputTenorKey.setText(prefs.tenorApiKey)

        binding.switchSourceGiphyGifs.setOnCheckedChangeListener { _, checked ->
            prefs.setMemeSourceEnabled(GiphyGifsSource.ID, checked)
        }
        binding.switchSourceGiphyStickers.setOnCheckedChangeListener { _, checked ->
            prefs.setMemeSourceEnabled(GiphyStickersSource.ID, checked)
        }
        binding.switchSourceGiphyTrending.setOnCheckedChangeListener { _, checked ->
            prefs.setMemeSourceEnabled(GiphyTrendingSource.ID, checked)
        }
        binding.switchSourceImgflip.setOnCheckedChangeListener { _, checked ->
            prefs.setMemeSourceEnabled(ImgflipSource.ID, checked)
        }
        binding.switchSourceTenor.setOnCheckedChangeListener { _, checked ->
            prefs.setMemeSourceEnabled(TenorSource.ID, checked)
        }

        binding.btnSaveTenorKey.setOnClickListener {
            val key = binding.inputTenorKey.text?.toString().orEmpty()
            prefs.tenorApiKey = key
            val messageRes = if (key.isBlank()) R.string.tenor_key_cleared else R.string.tenor_key_saved
            android.widget.Toast.makeText(this, messageRes, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * "Meme style" picker (MAJOR SIMPLIFICATION — client requirement: "give us some
     * options to type our fav! ... Or type your own"). Item order in
     * [R.array.meme_category_options] MUST stay in sync with [MemeCategory]'s declaration
     * order - the spinner maps position <-> enum by ordinal. The free-text field always
     * overrides the preset when non-blank (see
     * [com.moodboard.keyboard.stickers.MemeQueryBank.buildQuery]).
     */
    private fun setUpMemeCategoryPicker(prefs: Prefs) {
        val categories = MemeCategory.values()
        val adapter = ArrayAdapter.createFromResource(
            this, R.array.meme_category_options, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerMemeCategory.adapter = adapter
        binding.spinnerMemeCategory.setSelection(prefs.memeCategory.ordinal, false)

        binding.spinnerMemeCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                categories.getOrNull(position)?.let { prefs.memeCategory = it }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.inputMemeCategoryCustom.setText(prefs.memeCategoryCustom)
        binding.inputMemeCategoryCustom.doAfterTextChanged { editable ->
            prefs.memeCategoryCustom = editable?.toString().orEmpty()
        }
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
}
