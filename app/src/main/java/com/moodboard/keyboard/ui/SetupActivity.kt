package com.moodboard.keyboard.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
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
    }
}