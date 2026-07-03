package com.moodboard.keyboard.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Streams the front camera into an [ImageAnalysis] use case so we can run live,
 * frame-by-frame emotion analysis (see EmotionAnalyzer). An IME is a Service, not
 * a LifecycleOwner, so we drive our own lifecycle.
 */
class KeyboardCameraManager(private val context: Context) : LifecycleOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private var provider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null

    /**
     * @param onFrame called for each upright camera frame as (bitmap, timestampMs)
     *                on a background thread.
     */
    fun start(
        previewView: PreviewView,
        onFrame: (Bitmap, Long) -> Unit,
        onError: (String) -> Unit
    ) {
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        registry.currentState = Lifecycle.State.RESUMED
        val executor = Executors.newSingleThreadExecutor().also { analysisExecutor = it }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis.setAnalyzer(executor) { proxy -> handleFrame(proxy, onFrame) }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
                )
            } catch (t: Throwable) {
                onError(t.message ?: "Camera failed to start")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun handleFrame(proxy: ImageProxy, onFrame: (Bitmap, Long) -> Unit) {
        try {
            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val upright = if (rot == 0) raw else {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            }
            val tsMs = proxy.imageInfo.timestamp / 1_000_000L
            onFrame(upright, tsMs)
        } catch (_: Throwable) {
            // drop this frame
        } finally {
            proxy.close()
        }
    }

    fun stop() {
        try { provider?.unbindAll() } catch (_: Throwable) {}
        provider = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        registry.currentState = Lifecycle.State.CREATED
    }
}
