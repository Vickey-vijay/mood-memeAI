package com.moodboard.keyboard.emotion

import kotlin.math.exp
import kotlin.math.sqrt

/** One AU's contribution to the winning emotion, e.g. "Brows lowered" -> 0.42. */
data class Contribution(val auLabel: String, val value: Float)

/**
 * The output of one apex evaluation (see SPEC_V2 A.7).
 *
 * [locked] and [auVector] are implementation additions beyond the literal A.7
 * type: [locked] lets the IME know a 3-in-a-row hysteresis has confirmed the
 * label (see A.6.4) without re-implementing that counter itself, and
 * [auVector] exposes the raw 26-dim apex vector for the Emotion Lab AU bars.
 */
data class EmotionResult(
    val hasFace: Boolean,
    val emotion: Emotion,
    val confidence: Float,
    val distribution: List<Pair<Emotion, Float>>,
    val contributors: List<Contribution>,
    val intensity: Float,
    val calibrated: Boolean,
    val locked: Boolean = false,
    val auVector: FloatArray = FloatArray(ActionUnits.COUNT)
)

/**
 * Pipeline stages [3]-[6] from SPEC_V2 A.1: a 24-frame EMA ring buffer, apex
 * selection, cosine x coverage prototype matching, softmax distribution, blend
 * arbitration and 3-evaluation hysteresis. Fed one AU vector per video frame by
 * [EmotionAnalyzer]; owns no camera/MediaPipe state itself.
 */
class ExpressionClassifier(
    private val calibrated: Boolean,
    tongueSupported: Boolean
) {
    private data class Frame(val vector: FloatArray, val timestampMs: Long)

    private val prototypes = EmotionPrototypes.active(tongueSupported)
    private val buffer = ArrayList<Frame>(BUFFER_SIZE)
    private var lastEma: FloatArray? = null

    private var hysteresisCandidate: Emotion? = null
    private var hysteresisStreak: Int = 0

    /**
     * Feed one frame. [rawAu] is null when MediaPipe reports no face for this
     * frame — such frames are discarded and must not reset the buffer or the
     * hysteresis streak (SPEC_V2 A.4).
     */
    fun addFrame(hasFace: Boolean, rawAu: FloatArray?, timestampMs: Long): EmotionResult {
        if (!hasFace || rawAu == null) {
            return EmotionResult(
                hasFace = false,
                emotion = hysteresisCandidate ?: Emotion.NEUTRAL,
                confidence = 0f,
                distribution = emptyList(),
                contributors = emptyList(),
                intensity = 0f,
                calibrated = calibrated,
                locked = false,
                auVector = lastEma ?: FloatArray(ActionUnits.COUNT)
            )
        }

        val prevEma = lastEma
        val ema = if (prevEma == null) {
            rawAu.copyOf()
        } else {
            FloatArray(ActionUnits.COUNT) { i -> EMA_ALPHA * rawAu[i] + (1f - EMA_ALPHA) * prevEma[i] }
        }
        lastEma = ema

        if (buffer.size >= BUFFER_SIZE) buffer.removeAt(0)
        buffer.add(Frame(ema, timestampMs))

        // Apex = frame with max ||a||_2 within the last 1.5s of the buffer.
        val cutoff = timestampMs - APEX_WINDOW_MS
        val apexFrame = buffer.filter { it.timestampMs >= cutoff }.maxByOrNull { l2(it.vector) }
            ?: buffer.last()
        val apex = apexFrame.vector
        val intensity = l2(apex)

        val result: EmotionResult
        if (intensity < INTENSITY_GATE) {
            val confidence = (1f - intensity / INTENSITY_GATE).coerceIn(0f, 1f)
            val locked = updateHysteresis(Emotion.NEUTRAL)
            result = EmotionResult(
                hasFace = true,
                emotion = Emotion.NEUTRAL,
                confidence = confidence,
                distribution = listOf(Emotion.NEUTRAL to confidence),
                contributors = emptyList(),
                intensity = intensity,
                calibrated = calibrated,
                locked = locked,
                auVector = apex
            )
        } else {
            val normApex = normalize(apex)
            val scored = prototypes.map { (emotion, proto) ->
                val normProto = normalize(proto)
                val similarity = dot(normApex, normProto).coerceAtLeast(0f)
                var minSum = 0f
                var protoSum = 0f
                for (i in normProto.indices) {
                    minSum += minOf(normApex[i], normProto[i])
                    protoSum += normProto[i]
                }
                val coverage = if (protoSum > EPS) (minSum / protoSum).coerceAtLeast(0f) else 0f
                val score = sqrt((similarity * coverage).coerceAtLeast(0f))
                emotion to score
            }

            val maxScore = scored.maxOf { it.second }
            val weights = scored.map { (e, s) -> e to exp((TEMPERATURE * (s - maxScore)).toDouble()).toFloat() }
            val sumWeights = weights.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(EPS)
            val probs = weights.map { (e, w) -> e to w / sumWeights }.sortedByDescending { it.second }

            val top1 = probs[0]
            val top2 = probs.getOrNull(1)
            var winner = top1.first
            var blended = false
            if (top2 != null && (top1.second - top2.second) < BLEND_GAP) {
                COMPOSITES[setOf(top1.first, top2.first)]?.let { winner = it; blended = true }
            }

            // A composite resolved by arbitration carries the evidence of BOTH
            // parents. Reporting its own standalone probability would understate it
            // and would contradict the parent bars shown next to it ("Annoyed 8%"
            // beside "Happy 30% / Angry 28%"), so report the combined weight and
            // lead the distribution with the composite.
            val winnerConfidence =
                if (blended) (top1.second + (top2?.second ?: 0f)).coerceAtMost(1f)
                else top1.second
            val distribution =
                if (blended) (listOf(winner to winnerConfidence) + probs.filter { it.first != winner }).take(3)
                else probs.take(3)
            val winnerProto = prototypes[winner]?.let { normalize(it) }
            val contributors = if (winnerProto != null) {
                (0 until ActionUnits.COUNT)
                    .map { i -> Contribution(ActionUnits.LABELS[i], normApex[i] * winnerProto[i]) }
                    .sortedByDescending { it.value }
                    .take(4)
            } else emptyList()

            val locked = updateHysteresis(winner)
            result = EmotionResult(
                hasFace = true,
                emotion = winner,
                confidence = winnerConfidence,
                distribution = distribution,
                contributors = contributors,
                intensity = intensity,
                calibrated = calibrated,
                locked = locked,
                auVector = apex
            )
        }
        return result
    }

    /** True once [winner] has been the resolved label for 3 consecutive apex evaluations. */
    private fun updateHysteresis(winner: Emotion): Boolean {
        if (winner == hysteresisCandidate) {
            hysteresisStreak++
        } else {
            hysteresisCandidate = winner
            hysteresisStreak = 1
        }
        return hysteresisStreak >= HYSTERESIS_STREAK
    }

    private fun l2(v: FloatArray): Float {
        var s = 0f
        for (x in v) s += x * x
        return sqrt(s)
    }

    private fun normalize(v: FloatArray): FloatArray {
        val n = l2(v) + EPS
        return FloatArray(v.size) { v[it] / n }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    companion object {
        private const val BUFFER_SIZE = 24
        private const val EMA_ALPHA = 0.35f
        private const val APEX_WINDOW_MS = 1500L
        private const val INTENSITY_GATE = 0.12f
        private const val TEMPERATURE = 8.0f
        private const val BLEND_GAP = 0.12f
        private const val HYSTERESIS_STREAK = 3
        private const val EPS = 1e-6f

        /** Unordered blend-arbitration pairs -> composite label (SPEC_V2 A.6.3). */
        private val COMPOSITES: Map<Set<Emotion>, Emotion> = mapOf(
            setOf(Emotion.HAPPY, Emotion.ANGRY) to Emotion.ANNOYED,
            setOf(Emotion.HAPPY, Emotion.SKEPTICAL) to Emotion.CONTEMPT,
            setOf(Emotion.SAD, Emotion.ANGRY) to Emotion.FRUSTRATED,
            setOf(Emotion.SURPRISED, Emotion.HAPPY) to Emotion.EXCITED,
            setOf(Emotion.SURPRISED, Emotion.SAD) to Emotion.SHOCKED,
            setOf(Emotion.SURPRISED, Emotion.ANGRY) to Emotion.FEARFUL,
            setOf(Emotion.DISGUST, Emotion.ANGRY) to Emotion.CONTEMPT,
            setOf(Emotion.SLEEPY, Emotion.ANNOYED) to Emotion.SLEEPY
        )
    }
}
