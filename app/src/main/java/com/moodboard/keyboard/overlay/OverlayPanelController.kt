package com.moodboard.keyboard.overlay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.moodboard.keyboard.R
import com.moodboard.keyboard.camera.KeyboardCameraManager
import com.moodboard.keyboard.databinding.OverlayPanelBinding
import com.moodboard.keyboard.emotion.Emotion
import com.moodboard.keyboard.emotion.EmotionAnalyzer
import com.moodboard.keyboard.emotion.EmotionResult
import com.moodboard.keyboard.stickers.EmojiAdapter
import com.moodboard.keyboard.stickers.StickerAdapter
import com.moodboard.keyboard.stickers.StickerItem
import com.moodboard.keyboard.stickers.StickerRepository
import com.moodboard.keyboard.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide claim on the front camera for the overlay side (see the shared-camera note
 * on [OverlayPanelController]).
 *
 * CameraX's [androidx.camera.lifecycle.ProcessCameraProvider] is a *process* singleton and
 * [KeyboardCameraManager.start] calls `unbindAll()` before binding, so whoever starts last
 * silently steals the camera from whoever had it. The IME
 * ([com.moodboard.keyboard.ime.MoodBoardService]) is read-only for this workstream, so this
 * gate can only be enforced on the overlay half; it guarantees the overlay never opens a
 * second concurrent session with itself, and the panel's own lifecycle (see
 * [OverlayPanelController.release]) guarantees the overlay is holding nothing whenever the
 * keyboard can possibly be in use.
 */
internal object OverlayCameraGate {
    private val held = AtomicBoolean(false)

    /** @return true if the caller now owns the overlay camera claim. */
    fun acquire(): Boolean = held.compareAndSet(false, true)

    fun release() { held.set(false) }
}

/**
 * Camera + [EmotionAnalyzer] + [StickerRepository] wiring for the expanded overlay panel
 * (SPEC_V3 C.2). Deliberately mirrors [com.moodboard.keyboard.ime.MoodBoardService]'s scan
 * lifecycle beat for beat — lazy analyzer creation on the analysis thread, monotonic
 * timestamps, no auto-lock on NEUTRAL, `ExpressionClassifier` owns the hysteresis — and
 * reuses the shipped engine and repository **unchanged**. Nothing about detection is forked
 * or reimplemented here.
 *
 * ## Shared front camera (the real hazard)
 * The keyboard and the bubble are the same process and want the same physical camera. The
 * invariant this class enforces is: **the overlay holds the camera only while the expanded
 * panel is actively scanning, and for no other instant.**
 *  - The collapsed bubble never touches the camera or MediaPipe.
 *  - [lockAndFetch] releases both the moment a mood is locked, exactly as the IME does, so
 *    the camera is already free while results are on screen.
 *  - [release] is called from every teardown path in [FloatingBubbleService] — collapse,
 *    losing window focus (which is what happens the instant the user taps a text field and
 *    the keyboard comes up), the notification Stop action, overlay permission being
 *    revoked, and `onDestroy`.
 * A leaked `FaceLandmarker` or an unreleased camera here would break the keyboard's own
 * scan afterwards, which is why every exit path funnels through one method.
 */
class OverlayPanelController(
    private val context: Context,
    private val binding: OverlayPanelBinding,
    /** Invoked when CAMERA is missing; the service routes this to the existing PermissionActivity. */
    private val onNeedCameraPermission: () -> Unit,
    /** Invoked when the user closes the panel from inside it. */
    private val onCollapseRequested: () -> Unit
) {

    private val prefs = Prefs(context)
    private val insertion = OverlayInsertion(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    // P0 stability: StickerRepository's constructor does real disk I/O (StickerLibrary index
    // load + legacy migration, MemeCache index load). OverlayPanelController is constructed
    // from FloatingBubbleService.expand(), i.e. on the bubble's main thread during a user tap
    // - building it synchronously would visibly hitch the panel opening. Deferred + built on
    // Dispatchers.IO instead; resolved by the time lockAndFetch actually needs it in the
    // overwhelming majority of cases (a full camera scan takes far longer).
    private val repoDeferred: Deferred<StickerRepository?> = scope.async(Dispatchers.IO) {
        try { StickerRepository(context, prefs) } catch (t: Throwable) { null }
    }
    @Volatile private var activeRepo: StickerRepository? = null

    private var camera: KeyboardCameraManager? = null
    @Volatile private var analyzer: EmotionAnalyzer? = null

    private enum class Mode { IDLE, CAMERA, RESULTS }
    @Volatile private var mode = Mode.IDLE
    @Volatile private var locked = false
    @Volatile private var released = false

    private var currentEmotion = Emotion.NEUTRAL
    private var lastResult: EmotionResult? = null
    private var lastTs = 0L
    private var lastPicked: StickerItem? = null

    private val stickerAdapter = StickerAdapter(
        onClick = { item -> copyToClipboard(item) },
        onLongClick = { item -> share(item) }
    )
    private val emojiAdapter = EmojiAdapter { emoji -> copyEmoji(emoji) }

    init {
        binding.btnOverlayClose.setOnClickListener { onCollapseRequested() }
        binding.btnOverlayRescan.setOnClickListener { startScan() }
        binding.btnOverlayUseMood.setOnClickListener { lockAndFetch(currentEmotion) }
        binding.btnOverlayShare.setOnClickListener {
            val item = lastPicked
            if (item == null) status(context.getString(R.string.overlay_pick_first))
            else share(item)
        }
    }

    // ---------------- Scan ----------------

    /** Entry point: called once the panel window is attached. */
    fun start() {
        if (!hasCameraPermission()) {
            status(context.getString(R.string.overlay_need_camera))
            onNeedCameraPermission()
            return
        }
        startScan()
    }

    private fun startScan() {
        if (released) return
        if (!hasCameraPermission()) {
            status(context.getString(R.string.overlay_need_camera))
            onNeedCameraPermission()
            return
        }
        // Never open a second session on top of a live one.
        stopCamera()
        if (!OverlayCameraGate.acquire()) {
            status(context.getString(R.string.overlay_camera_busy))
            return
        }

        mode = Mode.CAMERA
        locked = false
        lastTs = 0L
        currentEmotion = Emotion.NEUTRAL
        lastResult = null
        lastPicked = null

        binding.overlayCameraContainer.visibility = View.VISIBLE
        binding.overlayStickerGrid.visibility = View.GONE
        binding.overlayProgress.visibility = View.GONE
        binding.overlayAttribution.visibility = View.GONE
        binding.overlayHint.visibility = View.GONE
        binding.btnOverlayUseMood.visibility = View.VISIBLE
        binding.overlayWhyText.text = ""
        binding.overlayCameraHint.text = context.getString(R.string.overlay_starting_camera)
        status(context.getString(R.string.overlay_look_at_camera))

        // A PreviewView in a TYPE_APPLICATION_OVERLAY window must not use the default
        // SurfaceView path: a Z-ordered surface inside a non-activity overlay window renders
        // black on a number of OEM composers. TextureView (COMPATIBLE) is the safe mode here.
        binding.overlayCameraPreview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        camera = KeyboardCameraManager(context).also { cam ->
            cam.start(
                binding.overlayCameraPreview,
                onFrame = { bmp, ts -> onFrame(bmp, ts) },
                onError = { err ->
                    main.post {
                        stopCamera()
                        status(context.getString(R.string.overlay_camera_error, err))
                    }
                }
            )
        }
    }

    /** Runs on the CameraX analysis thread — same contract as the IME's onFrame. */
    private fun onFrame(bitmap: Bitmap, ts: Long) {
        if (released || mode != Mode.CAMERA || locked) return
        var a = analyzer
        if (a == null) {
            a = try {
                EmotionAnalyzer(context)
            } catch (t: Throwable) {
                main.post {
                    stopCamera()
                    status(context.getString(R.string.overlay_model_failed))
                }
                return
            }
            analyzer = a
        }
        val t = if (ts > lastTs) ts else lastTs + 1
        lastTs = t
        val result = try { a!!.analyze(bitmap, t) } catch (_: Throwable) { return }
        main.post { updateLive(result) }
    }

    private fun updateLive(result: EmotionResult) {
        if (released || mode != Mode.CAMERA || locked) return
        if (!result.hasFace) {
            binding.overlayCameraHint.text = context.getString(R.string.overlay_center_face)
            binding.overlayWhyText.text = ""
            return
        }
        currentEmotion = result.emotion
        lastResult = result

        val top3 = result.distribution.joinToString("   ") { (e, p) -> "${e.emoji}${(p * 100).toInt()}%" }
        binding.overlayCameraHint.text = "${result.emotion.emoji} ${result.emotion.label}  ·  $top3"
        binding.overlayWhyText.text = result.contributors.take(3).joinToString(" · ") { it.auLabel }

        if (currentEmotion == Emotion.NEUTRAL) {
            status(context.getString(R.string.overlay_make_a_face))
        } else {
            val pct = (result.confidence * 100).toInt()
            status(context.getString(R.string.overlay_detecting, result.emotion.label, pct))
            // ExpressionClassifier owns the 3-evaluation hysteresis (SPEC_V2 A.6.4).
            if (result.locked) lockAndFetch(currentEmotion)
        }
    }

    private fun lockAndFetch(emotion: Emotion) {
        if (released || locked || mode != Mode.CAMERA) return
        locked = true
        prefs.incrementMoodUsage(emotion.key) // SPEC_V3 B.1

        // Free the camera and MediaPipe *before* the network call, so the keyboard can scan
        // even while the overlay grid is still loading.
        stopCamera()

        binding.overlayCameraContainer.visibility = View.GONE
        binding.overlayProgress.visibility = View.VISIBLE
        binding.btnOverlayUseMood.visibility = View.GONE
        status(context.getString(R.string.overlay_finding, emotion.label))

        scope.launch {
            val repo = repoDeferred.await()
            if (released) return@launch
            if (repo == null) {
                binding.overlayProgress.visibility = View.GONE
                showEmojiFallback(emotion)
                return@launch
            }
            activeRepo = repo
            val res = repo.search(
                lastResult ?: EmotionResult(
                    hasFace = true,
                    emotion = emotion,
                    confidence = 1f,
                    distribution = listOf(emotion to 1f),
                    contributors = emptyList(),
                    intensity = 1f,
                    calibrated = false
                )
            )
            if (released) return@launch
            binding.overlayProgress.visibility = View.GONE
            res.onSuccess { list ->
                if (list.isNotEmpty()) showStickers(emotion, list)
                else showEmojiFallback(emotion)
            }.onFailure { showEmojiFallback(emotion) }
        }
    }

    // ---------------- Results ----------------

    private fun showStickers(emotion: Emotion, list: List<StickerItem>) {
        mode = Mode.RESULTS
        binding.overlayStickerGrid.layoutManager = GridLayoutManager(context, 3)
        binding.overlayStickerGrid.adapter = stickerAdapter
        stickerAdapter.submit(list)
        binding.overlayStickerGrid.visibility = View.VISIBLE
        binding.overlayHint.visibility = View.VISIBLE
        binding.overlayHint.text = context.getString(R.string.overlay_tap_to_copy)
        status(context.getString(R.string.overlay_mood_tap, emotion.label, emotion.emoji))
        updateAttribution()
    }

    private fun showEmojiFallback(emotion: Emotion) {
        mode = Mode.RESULTS
        binding.overlayStickerGrid.layoutManager = GridLayoutManager(context, 5)
        binding.overlayStickerGrid.adapter = emojiAdapter
        emojiAdapter.submit(emotion.emojis)
        binding.overlayStickerGrid.visibility = View.VISIBLE
        binding.overlayHint.visibility = View.VISIBLE
        binding.overlayHint.text = context.getString(R.string.overlay_tap_to_copy_emoji)
        binding.overlayAttribution.visibility = View.GONE
        status(context.getString(R.string.overlay_mood_emoji, emotion.label))
    }

    /** SPEC_V3 A.7 (multi-source) — attribution shown only when online results actually contributed. */
    private fun updateAttribution() {
        val repo = activeRepo
        if (repo == null || !repo.lastFetchHadOnlineResults) {
            binding.overlayAttribution.visibility = View.GONE
            return
        }
        val label = com.moodboard.keyboard.stickers.MemeAttribution.label(context, repo.lastFetchSources)
        if (label.isEmpty()) {
            binding.overlayAttribution.visibility = View.GONE
            return
        }
        binding.overlayAttribution.text = label
        binding.overlayAttribution.visibility = View.VISIBLE
    }

    // ---------------- Insertion (SPEC_V3 C.5) ----------------

    private fun copyToClipboard(item: StickerItem) {
        lastPicked = item
        status(context.getString(R.string.overlay_copying))
        scope.launch {
            when (val r = insertion.copyToClipboard(item)) {
                is OverlayInsertion.Result.Copied -> {
                    binding.overlayHint.visibility = View.VISIBLE
                    binding.overlayHint.text = context.getString(R.string.overlay_copied_hint)
                    status(context.getString(R.string.overlay_copied))
                }
                is OverlayInsertion.Result.Error -> toast(r.message)
                else -> Unit
            }
        }
    }

    private fun share(item: StickerItem) {
        lastPicked = item
        status(context.getString(R.string.overlay_sharing))
        scope.launch {
            when (val r = insertion.shareTo(item)) {
                is OverlayInsertion.Result.Shared -> {
                    // The chooser takes focus, so the service collapses the panel; releasing
                    // the camera on that path is already handled by release().
                    status(context.getString(R.string.overlay_shared))
                }
                is OverlayInsertion.Result.Error -> toast(r.message)
                else -> Unit
            }
        }
    }

    private fun copyEmoji(emoji: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("emoji", emoji))
        binding.overlayHint.visibility = View.VISIBLE
        binding.overlayHint.text = context.getString(R.string.overlay_copied_hint)
        status(context.getString(R.string.overlay_copied))
    }

    // ---------------- Teardown ----------------

    /** Releases the camera + MediaPipe but keeps the panel usable (Rescan re-acquires). */
    private fun stopCamera() {
        camera?.stop()
        camera = null
        analyzer?.close()
        analyzer = null
        OverlayCameraGate.release()
    }

    /**
     * Final teardown. Idempotent and safe to call from any thread's main-post; called from
     * every [FloatingBubbleService] exit path. After this the controller is inert.
     */
    fun release() {
        if (released) return
        released = true
        mode = Mode.IDLE
        locked = false
        stopCamera()
        scope.cancel()
    }

    // ---------------- Helpers ----------------

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun status(text: String) { binding.overlayStatusText.text = text }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
