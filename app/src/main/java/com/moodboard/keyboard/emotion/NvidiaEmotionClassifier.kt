package com.moodboard.keyboard.emotion

import android.graphics.Bitmap
import android.util.Base64
import com.moodboard.keyboard.util.BuildDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Emotion detection via NVIDIA NIM vision models (OpenAI-compatible endpoint).
 *
 * We offload inference to the cloud to keep the APK tiny and avoid bundling a
 * neural net (see docs/03_Emotion_Detection_API.md). The image is downscaled and
 * base64-encoded inline. Get a free key at https://build.nvidia.com .
 */
class NvidiaEmotionClassifier(
    private val apiKey: String,
    private val model: String
) : EmotionClassifier {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun classify(face: Bitmap): Result<Emotion> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No NVIDIA API key set"))
        }
        try {
            val b64 = encode(downscale(face))
            val prompt = "You are an emotion classifier. Look at the person's face in the image " +
                "and reply with ONLY ONE word from this list that best matches their expression: " +
                "Happy, Laughing, Sad, Angry, Surprised, Neutral. Reply with the single word only."

            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$b64")
                    )
                )
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 16)
                .put("temperature", 0.2)
                .put("stream", false)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject().put("role", "user").put("content", content)
                    )
                )

            val request = Request.Builder()
                .url(BuildDefaults.NVIDIA_BASE_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        RuntimeException("NVIDIA ${resp.code}: ${text.take(200)}")
                    )
                }
                val answer = org.json.JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                Result.success(Emotion.fromText(answer))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun downscale(src: Bitmap, max: Int = 512): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= max && h <= max) return src
        val scale = max.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    private fun encode(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
