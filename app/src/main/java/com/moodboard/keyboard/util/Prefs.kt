package com.moodboard.keyboard.util

import android.content.Context

/** Tiny wrapper over SharedPreferences for API keys and provider choice. */
class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("moodboard_prefs", Context.MODE_PRIVATE)

    var nvidiaKey: String
        get() = sp.getString(KEY_NVIDIA, BuildDefaults.DEFAULT_NVIDIA_KEY).orEmpty()
        set(v) = sp.edit().putString(KEY_NVIDIA, v.trim()).apply()

    /** GIPHY or Tenor key, depending on [provider]. */
    var stickerKey: String
        get() = sp.getString(KEY_STICKER, BuildDefaults.DEFAULT_GIPHY_KEY).orEmpty()
        set(v) = sp.edit().putString(KEY_STICKER, v.trim()).apply()

    /** "giphy" or "tenor". */
    var provider: String
        get() = sp.getString(KEY_PROVIDER, "giphy").orEmpty()
        set(v) = sp.edit().putString(KEY_PROVIDER, v).apply()

    /** NVIDIA NIM vision model id (overridable in case NVIDIA changes model names). */
    var nvidiaModel: String
        get() = sp.getString(KEY_MODEL, BuildDefaults.DEFAULT_NVIDIA_MODEL).orEmpty()
        set(v) = sp.edit().putString(KEY_MODEL, v.trim()).apply()

    /** Per-user neutral-face baseline, JSON-serialised (see NeutralBaseline). Empty = uncalibrated. */
    var neutralBaseline: String
        get() = sp.getString(KEY_NEUTRAL_BASELINE, "").orEmpty()
        set(v) = sp.edit().putString(KEY_NEUTRAL_BASELINE, v).apply()

    /** Epoch ms when [neutralBaseline] was captured, for the "Calibrated on <date>" UI. */
    var neutralBaselineAt: Long
        get() = sp.getLong(KEY_NEUTRAL_BASELINE_AT, 0L)
        set(v) = sp.edit().putLong(KEY_NEUTRAL_BASELINE_AT, v).apply()

    /** False once a calibration session has shown the shipped model can't reliably see tongueOut. */
    var tongueSupported: Boolean
        get() = sp.getBoolean(KEY_TONGUE_SUPPORTED, true)
        set(v) = sp.edit().putBoolean(KEY_TONGUE_SUPPORTED, v).apply()

    /** Whether to fall back to GIPHY/Tenor when the user's own library is thin (SPEC_V2 B.5). */
    var onlineStickers: Boolean
        get() = sp.getBoolean(KEY_ONLINE_STICKERS, true)
        set(v) = sp.edit().putBoolean(KEY_ONLINE_STICKERS, v).apply()

    /** Whether the user's own stickers are shown ahead of online results (SPEC_V2 B.5). */
    var preferOwnStickers: Boolean
        get() = sp.getBoolean(KEY_PREFER_OWN_STICKERS, true)
        set(v) = sp.edit().putBoolean(KEY_PREFER_OWN_STICKERS, v).apply()

    companion object {
        private const val KEY_NVIDIA = "nvidia_key"
        private const val KEY_STICKER = "sticker_key"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "nvidia_model"
        private const val KEY_NEUTRAL_BASELINE = "neutral_baseline_v1"
        private const val KEY_NEUTRAL_BASELINE_AT = "neutral_baseline_at"
        private const val KEY_TONGUE_SUPPORTED = "tongue_supported"
        private const val KEY_ONLINE_STICKERS = "online_stickers"
        private const val KEY_PREFER_OWN_STICKERS = "prefer_own_stickers"
    }
}

/**
 * Compile-time defaults. The GIPHY public beta key works out of the box so the
 * keyboard shows stickers even before the user adds their own key. Replace with
 * your own keys in SetupActivity for production use / higher rate limits.
 */
object BuildDefaults {
    // GIPHY's well-known public beta/demo key (rate limited). Replace in Setup.
    const val DEFAULT_GIPHY_KEY = "GlVGYHkr3WSBnllca54iNt0yFbjz7L65"
    const val DEFAULT_NVIDIA_KEY = ""
    const val DEFAULT_NVIDIA_MODEL = "meta/llama-3.2-11b-vision-instruct"
    const val NVIDIA_BASE_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
}
