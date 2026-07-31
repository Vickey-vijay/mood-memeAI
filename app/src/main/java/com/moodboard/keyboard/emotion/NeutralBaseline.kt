package com.moodboard.keyboard.emotion

import kotlin.math.abs
import org.json.JSONObject

/**
 * Per-user neutral-face baseline: median blendshape weights captured while the
 * user holds a relaxed expression for ~3 s (see SPEC_V2 A.2). Every raw
 * blendshape weight is corrected against this baseline before AU projection,
 * so a user's own resting face reads as ~zero intensity.
 */
data class NeutralBaseline(val weights: Map<String, Float>, val capturedAt: Long) {

    /** delta_i = clamp01((r_i - b_i) / max(0.15, 1 - b_i)) for every raw blendshape weight. */
    fun correct(raw: Map<String, Float>): Map<String, Float> {
        val out = HashMap<String, Float>(raw.size)
        for ((name, r) in raw) {
            val b = weights[name] ?: 0f
            val denom = maxOf(0.15f, 1f - b)
            out[name] = ((r - b) / denom).coerceIn(0f, 1f)
        }
        return out
    }

    fun toJson(): String {
        val w = JSONObject()
        for ((k, v) in weights) w.put(k, v.toDouble())
        return JSONObject().put("weights", w).put("capturedAt", capturedAt).toString()
    }

    companion object {
        /** All-zeros baseline: the uncalibrated fallback. `correct()` becomes a passthrough. */
        val EMPTY = NeutralBaseline(emptyMap(), 0L)

        fun fromJson(json: String?): NeutralBaseline? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val w = obj.getJSONObject("weights")
                val map = HashMap<String, Float>()
                val keys = w.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = w.getDouble(k).toFloat()
                }
                NeutralBaseline(map, obj.optLong("capturedAt", 0L))
            } catch (_: Throwable) {
                null
            }
        }

        fun medianOf(values: List<Float>): Float {
            if (values.isEmpty()) return 0f
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
        }
    }
}

/** Outcome of a calibration capture (see [NeutralBaselineCapture]). */
sealed class CaptureResult {
    data class Success(val baseline: NeutralBaseline) : CaptureResult()
    data class Failure(val reason: String) : CaptureResult()
}

/**
 * Accumulates blendshape frames over a ~3 s calibration capture (see
 * CalibrationActivity), then validates and produces a [NeutralBaseline].
 *
 * Accept/reject rules (SPEC_V2 A.2):
 *  - a face must be present in >= 70% of frames;
 *  - the user must hold reasonably still: the median absolute deviation of each
 *    of mouthSmileLeft/Right, browDownLeft/Right and jawOpen must stay below 0.15.
 *
 * Also runs the `tongueOut` capability probe (A.3): [tongueSupported] reports
 * whether tongueOut was ever seen above the reliability threshold during this
 * session, independent of whether the baseline itself was accepted.
 */
class NeutralBaselineCapture {
    private val framesWithFace = ArrayList<Map<String, Float>>()
    private var totalFrames = 0
    private var maxTongueOut = 0f

    fun addFrame(hasFace: Boolean, blendshapes: Map<String, Float>?) {
        totalFrames++
        if (hasFace && blendshapes != null) {
            framesWithFace.add(blendshapes)
            val tongue = blendshapes["tongueOut"] ?: 0f
            if (tongue > maxTongueOut) maxTongueOut = tongue
        }
    }

    fun tongueSupported(): Boolean = maxTongueOut > TONGUE_PROBE_THRESHOLD

    fun finish(reasonOnFailure: String): CaptureResult {
        if (totalFrames == 0) return CaptureResult.Failure(reasonOnFailure)
        val presence = framesWithFace.size.toFloat() / totalFrames
        if (presence < 0.70f) return CaptureResult.Failure(reasonOnFailure)

        val medians = HashMap<String, Float>()
        for (name in ActionUnits.BLENDSHAPE_NAMES) {
            medians[name] = NeutralBaseline.medianOf(framesWithFace.map { it[name] ?: 0f })
        }

        val stabilityKeys = listOf(
            "mouthSmileLeft", "mouthSmileRight", "browDownLeft", "browDownRight", "jawOpen"
        )
        for (key in stabilityKeys) {
            val med = medians[key] ?: 0f
            val mad = NeutralBaseline.medianOf(framesWithFace.map { abs((it[key] ?: 0f) - med) })
            if (mad >= 0.15f) return CaptureResult.Failure(reasonOnFailure)
        }

        return CaptureResult.Success(NeutralBaseline(medians, System.currentTimeMillis()))
    }

    companion object {
        private const val TONGUE_PROBE_THRESHOLD = 0.15f
    }
}
