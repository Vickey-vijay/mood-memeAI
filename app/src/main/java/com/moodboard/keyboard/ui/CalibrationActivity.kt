package com.moodboard.keyboard.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.moodboard.keyboard.R
import com.moodboard.keyboard.camera.KeyboardCameraManager
import com.moodboard.keyboard.databinding.ActivityCalibrationBinding
import com.moodboard.keyboard.emotion.CaptureResult
import com.moodboard.keyboard.emotion.NeutralBaselineCapture
import com.moodboard.keyboard.util.Prefs

/**
 * Neutral-face calibration (SPEC_V2 A.2 / A.8): a 3 s countdown + capture with a
 * live camera preview, then an accept/reject outcome. Runs its own MediaPipe
 * FaceLandmarker instance (raw blendshapes only — no baseline correction, since
 * this *is* the baseline being captured) rather than going through
 * EmotionAnalyzer, which assumes a baseline already exists.
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private lateinit var prefs: Prefs
    private var camera: KeyboardCameraManager? = null
    private var landmarker: FaceLandmarker? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var capture: NeutralBaselineCapture? = null
    @Volatile private var lastTs = 0L
    @Volatile private var capturing = false

    private val countdownRunnable = object : Runnable {
        var secondsLeft = 3
        override fun run() {
            if (secondsLeft > 0) {
                binding.countdownText.text = secondsLeft.toString()
                secondsLeft--
                main.postDelayed(this, 1000)
            } else {
                binding.countdownText.visibility = View.GONE
                beginCapture()
            }
        }
    }

    private val stopCaptureRunnable = Runnable { finishCapture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnStart.setOnClickListener { startFlow() }
        binding.btnRetry.setOnClickListener { startFlow() }
        binding.btnDone.setOnClickListener { finish() }

        showIdle()
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startFlow() {
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        showCountingDown()
        startCameraIfNeeded()
        countdownRunnable.secondsLeft = 3
        binding.countdownText.visibility = View.VISIBLE
        main.post(countdownRunnable)
    }

    private fun startCameraIfNeeded() {
        if (camera != null) return
        camera = KeyboardCameraManager(this).also { cam ->
            cam.start(
                binding.cameraPreview,
                onFrame = { bmp, ts -> onFrame(bmp, ts) },
                onError = { err -> main.post { binding.statusText.text = "Camera error: $err" } }
            )
        }
    }

    private fun beginCapture() {
        capture = NeutralBaselineCapture()
        capturing = true
        binding.statusText.text = getString(R.string.calibration_hold_still)
        binding.captureProgress.visibility = View.VISIBLE
        main.postDelayed(stopCaptureRunnable, CAPTURE_MS)
    }

    // Runs on the camera analysis thread.
    private fun onFrame(bitmap: Bitmap, ts: Long) {
        if (!capturing) return
        var lm = landmarker
        if (lm == null) {
            lm = try {
                FaceLandmarker.createFromOptions(
                    this,
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
            } catch (_: Throwable) {
                return
            }
            landmarker = lm
        }
        val t = if (ts > lastTs) ts else lastTs + 1
        lastTs = t
        val out = try { lm!!.detectForVideo(BitmapImageBuilder(bitmap).build(), t) } catch (_: Throwable) { return }
        val bs = out.faceBlendshapes()
        val map: Map<String, Float>? = if (bs.isPresent && bs.get().isNotEmpty()) {
            val m = HashMap<String, Float>()
            for (c in bs.get()[0]) m[c.categoryName()] = c.score()
            m
        } else null
        capture?.addFrame(map != null, map)
    }

    private fun finishCapture() {
        capturing = false
        binding.captureProgress.visibility = View.GONE
        val cap = capture
        val failMsg = getString(R.string.calibration_retry_msg)
        val result = cap?.finish(failMsg) ?: CaptureResult.Failure(failMsg)
        // Tongue-out capability probe (SPEC_V2 A.3) is independent of whether the
        // baseline itself was accepted.
        prefs.tongueSupported = cap?.tongueSupported() ?: prefs.tongueSupported
        when (result) {
            is CaptureResult.Success -> {
                prefs.neutralBaseline = result.baseline.toJson()
                prefs.neutralBaselineAt = result.baseline.capturedAt
                showSuccess()
            }
            is CaptureResult.Failure -> showRetry(result.reason)
        }
        stopCamera()
    }

    private fun stopCamera() {
        camera?.stop(); camera = null
        landmarker?.let { try { it.close() } catch (_: Throwable) {} }
        landmarker = null
    }

    private fun showIdle() {
        binding.countdownText.visibility = View.GONE
        binding.captureProgress.visibility = View.GONE
        binding.btnStart.visibility = View.VISIBLE
        binding.btnRetry.visibility = View.GONE
        binding.btnDone.visibility = View.GONE
        binding.statusText.text = getString(R.string.calibration_intro)
    }

    private fun showCountingDown() {
        binding.btnStart.visibility = View.GONE
        binding.btnRetry.visibility = View.GONE
        binding.btnDone.visibility = View.GONE
        binding.statusText.text = getString(R.string.calibration_relax)
    }

    private fun showSuccess() {
        binding.btnStart.visibility = View.GONE
        binding.btnRetry.visibility = View.GONE
        binding.btnDone.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.calibration_success)
    }

    private fun showRetry(reason: String) {
        binding.btnStart.visibility = View.GONE
        binding.btnRetry.visibility = View.VISIBLE
        binding.btnDone.visibility = View.GONE
        binding.statusText.text = reason
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopCamera()
        super.onDestroy()
    }

    companion object {
        private const val MODEL = "face_landmarker.task"
        private const val CAPTURE_MS = 3000L
        private const val REQ_CAMERA = 9231
    }
}
