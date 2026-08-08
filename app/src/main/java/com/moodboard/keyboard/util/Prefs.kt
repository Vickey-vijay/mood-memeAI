package com.moodboard.keyboard.util

import android.content.Context
import com.moodboard.keyboard.stickers.MemeCulture
import org.json.JSONObject

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

    /** Which meme culture pack to search first (SPEC_V3 A.2). Default South Indian (R2). */
    var memeCulture: MemeCulture
        get() = if (sp.getString(KEY_MEME_CULTURE, VAL_SOUTH_INDIAN) == VAL_GENERIC) {
            MemeCulture.GENERIC
        } else {
            MemeCulture.SOUTH_INDIAN
        }
        set(v) = sp.edit()
            .putString(KEY_MEME_CULTURE, if (v == MemeCulture.GENERIC) VAL_GENERIC else VAL_SOUTH_INDIAN)
            .apply()

    /** Backing JSON store for [com.moodboard.keyboard.stickers.RecentlyShownStore] (SPEC_V3 A.5). */
    var recentlyShownJson: String
        get() = sp.getString(KEY_RECENTLY_SHOWN, "").orEmpty()
        set(v) = sp.edit().putString(KEY_RECENTLY_SHOWN, v).apply()

    /**
     * Map of [com.moodboard.keyboard.emotion.Emotion.key] to how many times a scan has
     * resolved to that mood (SPEC_V3 B.1). Drives [com.moodboard.keyboard.stickers.MemePrefetchWorker]'s
     * data-driven prefetch target list. JSON-backed, e.g. `{"happy":12,"sad":3}`.
     */
    var moodUsageCounts: Map<String, Int>
        get() {
            val raw = sp.getString(KEY_MOOD_USAGE_COUNTS, "").orEmpty()
            if (raw.isBlank()) return emptyMap()
            return try {
                val json = JSONObject(raw)
                val out = LinkedHashMap<String, Int>()
                json.keys().forEach { k -> out[k] = json.optInt(k, 0) }
                out
            } catch (t: Throwable) {
                emptyMap()
            }
        }
        set(v) {
            val json = JSONObject()
            v.forEach { (k, count) -> json.put(k, count) }
            sp.edit().putString(KEY_MOOD_USAGE_COUNTS, json.toString()).apply()
        }

    /** Bumps [moodUsageCounts] for [emotionKey] by one. */
    fun incrementMoodUsage(emotionKey: String) {
        val current = moodUsageCounts.toMutableMap()
        current[emotionKey] = (current[emotionKey] ?: 0) + 1
        moodUsageCounts = current
    }

    /** Master switch for the SPEC_V3 B.3 background pre-cache worker. Default on. */
    var prefetchEnabled: Boolean
        get() = sp.getBoolean(KEY_PREFETCH_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_PREFETCH_ENABLED, v).apply()

    /** Whether the pre-cache worker requires unmetered (Wi-Fi) connectivity. Default on. */
    var prefetchWifiOnly: Boolean
        get() = sp.getBoolean(KEY_PREFETCH_WIFI_ONLY, true)
        set(v) = sp.edit().putBoolean(KEY_PREFETCH_WIFI_ONLY, v).apply()

    /**
     * Last x position of the floating bubble in window coordinates (SPEC_V3 C.3).
     * Gravity is TOP|START, so 0 is the left edge.
     */
    var bubbleX: Int
        get() = sp.getInt(KEY_BUBBLE_X, 0)
        set(v) = sp.edit().putInt(KEY_BUBBLE_X, v).apply()

    /** Last y position of the floating bubble in window coordinates (SPEC_V3 C.3). */
    var bubbleY: Int
        get() = sp.getInt(KEY_BUBBLE_Y, 0)
        set(v) = sp.edit().putInt(KEY_BUBBLE_Y, v).apply()

    /**
     * Whether the user has asked for the floating bubble to be running (SPEC_V3 C.6).
     * This records *intent* only — the authoritative live state is
     * [com.moodboard.keyboard.overlay.FloatingBubbleService.isRunning], because the process
     * can be killed without this flag being cleared. Nothing auto-starts the service from
     * this flag: a foreground service may only be started from a user tap in an Activity.
     */
    var overlayBubbleEnabled: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_OVERLAY_ENABLED, v).apply()

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
        private const val KEY_MEME_CULTURE = "meme_culture"
        private const val KEY_RECENTLY_SHOWN = "recently_shown_v1"
        private const val KEY_MOOD_USAGE_COUNTS = "mood_usage_counts_v1"
        private const val KEY_PREFETCH_ENABLED = "prefetch_enabled"
        private const val KEY_PREFETCH_WIFI_ONLY = "prefetch_wifi_only"
        private const val KEY_BUBBLE_X = "overlay_bubble_x"
        private const val KEY_BUBBLE_Y = "overlay_bubble_y"
        private const val KEY_OVERLAY_ENABLED = "overlay_bubble_enabled"
        private const val VAL_SOUTH_INDIAN = "south_indian"
        private const val VAL_GENERIC = "generic"
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
