package com.moodboard.keyboard.emotion

import android.graphics.Bitmap

/** Detects an [Emotion] from a face bitmap. */
interface EmotionClassifier {
    /** Returns Result.success(emotion) or Result.failure(error). Runs off the UI thread. */
    suspend fun classify(face: Bitmap): Result<Emotion>
}
