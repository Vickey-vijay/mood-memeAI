package com.moodboard.keyboard.emotion

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.moodboard.keyboard.util.Prefs

/**
 * On-device facial emotion, v2 (FACS-EBS, see SPEC_V2 Part A).
 *
 * Thin adapter over MediaPipe Face Landmarker: raw blendshapes -> per-user
 * baseline correction -> 26-dim AU projection -> [ExpressionClassifier]. All the
 * actual classification logic (temporal buffering, apex selection, prototype
 * matching, hysteresis) lives in [ExpressionClassifier]; this class only owns
 * the MediaPipe model and the per-session baseline/capability flags.
 */
class EmotionAnalyzer(context: Context) {

    private val prefs = Prefs(context)
    private val baseline: NeutralBaseline =
        NeutralBaseline.fromJson(prefs.neutralBaseline) ?: NeutralBaseline.EMPTY
    private val classifier = ExpressionClassifier(
        calibrated = baseline.weights.isNotEmpty(),
        tongueSupported = prefs.tongueSupported
    )

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

    fun analyze(bitmap: Bitmap, timestampMs: Long): EmotionResult {
        val mp = BitmapImageBuilder(bitmap).build()
        val out = landmarker.detectForVideo(mp, timestampMs)
        val bs = out.faceBlendshapes()
        if (!bs.isPresent || bs.get().isEmpty()) {
            return classifier.addFrame(false, null, timestampMs)
        }
        val raw = HashMap<String, Float>()
        for (c in bs.get()[0]) raw[c.categoryName()] = c.score()
        val corrected = baseline.correct(raw)
        val auVector = ActionUnits.project { name -> corrected[name] ?: 0f }
        return classifier.addFrame(true, auVector, timestampMs)
    }

    fun close() { try { landmarker.close() } catch (_: Throwable) {} }

    companion object {
        private const val MODEL = "face_landmarker.task"
    }
}
