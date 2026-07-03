package com.moodboard.keyboard.emotion

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

/**
 * On-device facial emotion using MediaPipe Face Landmarker blendshapes.
 *
 * Instead of strict pass/fail thresholds (which collapsed everything to Neutral),
 * we compute a weighted score for every emotion from the 52 blendshape weights and
 * pick the strongest. Only when no expression is clear do we fall back to Neutral.
 */
class EmotionAnalyzer(context: Context) {

    data class Result(val hasFace: Boolean, val emotion: Emotion, val confidence: Float)

    private val landmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .build()
    )

    fun analyze(bitmap: Bitmap, timestampMs: Long): Result {
        val mp = BitmapImageBuilder(bitmap).build()
        val out = landmarker.detectForVideo(mp, timestampMs)
        val bs = out.faceBlendshapes()
        if (!bs.isPresent || bs.get().isEmpty()) {
            return Result(false, Emotion.NEUTRAL, 0f)
        }
        val s = HashMap<String, Float>()
        for (c in bs.get()[0]) s[c.categoryName()] = c.score()
        return classify(s)
    }

    fun close() { try { landmarker.close() } catch (_: Throwable) {} }

    private fun g(s: Map<String, Float>, vararg keys: String): Float {
        var sum = 0f; for (k in keys) sum += s[k] ?: 0f
        return sum / keys.size
    }

    private fun classify(s: Map<String, Float>): Result {
        val smile = g(s, "mouthSmileLeft", "mouthSmileRight")
        val frown = g(s, "mouthFrownLeft", "mouthFrownRight")
        val browDown = g(s, "browDownLeft", "browDownRight")
        val browInner = g(s, "browInnerUp")
        val browOuter = g(s, "browOuterUpLeft", "browOuterUpRight")
        val jaw = g(s, "jawOpen")
        val eyeWide = g(s, "eyeWideLeft", "eyeWideRight")
        val eyeSquint = g(s, "eyeSquintLeft", "eyeSquintRight")
        val eyeBlink = g(s, "eyeBlinkLeft", "eyeBlinkRight")
        val sneer = g(s, "noseSneerLeft", "noseSneerRight")
        val upperLip = g(s, "mouthUpperUpLeft", "mouthUpperUpRight")
        val pucker = g(s, "mouthPucker")
        val press = g(s, "mouthPressLeft", "mouthPressRight")
        val shrug = g(s, "mouthShrugLower")
        val funnel = g(s, "mouthFunnel")

        fun c(v: Float) = v.coerceAtLeast(0f)
        val scores = linkedMapOf(
            Emotion.LAUGHING to c(smile * 0.85f * (0.45f + jaw)),
            Emotion.HAPPY to c(smile * (1f - 0.4f * jaw)),
            Emotion.EXCITED to c(smile * 0.5f + eyeWide * 0.6f - jaw * 0.2f),
            Emotion.KISS to c(pucker * 1.1f + funnel * 0.4f - smile * 0.4f),
            Emotion.SHOCKED to c(jaw * 0.5f + eyeWide * 0.85f + browInner * 0.3f - smile * 0.5f),
            Emotion.SURPRISED to c(jaw * 0.6f + browOuter * 0.5f + browInner * 0.35f - smile * 0.5f - eyeWide * 0.2f),
            Emotion.SAD to c(frown * 0.85f + browInner * 0.5f + shrug * 0.4f - smile * 0.7f),
            Emotion.ANGRY to c(browDown * 0.85f + sneer * 0.35f - smile * 0.6f - browInner * 0.3f),
            Emotion.DISGUST to c(sneer * 0.6f + upperLip * 0.7f - smile * 0.3f),
            Emotion.ANNOYED to c(eyeSquint * 0.45f + press * 0.5f + browDown * 0.3f - smile * 0.4f - jaw * 0.3f),
            Emotion.SKEPTICAL to c(browOuter * 0.7f + browInner * 0.2f - jaw * 0.6f - smile * 0.4f - browDown * 0.3f),
            Emotion.SLEEPY to c(eyeSquint * 0.5f + eyeBlink * 0.55f - jaw * 0.3f - smile * 0.5f - eyeWide * 0.5f)
        )
        val best = scores.maxByOrNull { it.value }
        return if (best == null || best.value < NEUTRAL_FLOOR) {
            Result(true, Emotion.NEUTRAL, (1f - (best?.value ?: 0f)).coerceIn(0.4f, 0.95f))
        } else {
            Result(true, best.key, best.value.coerceIn(0f, 1f))
        }
    }

    companion object {
        private const val MODEL = "face_landmarker.task"
        // Below this strongest-score, the expression is too subtle -> Neutral.
        private const val NEUTRAL_FLOOR = 0.18f
    }
}
