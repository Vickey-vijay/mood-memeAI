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

    companion object {
        private const val KEY_NVIDIA = "nvidia_key"
        private const val KEY_STICKER = "sticker_key"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "nvidia_model"
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
