package com.moodboard.keyboard.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.moodboard.keyboard.R
import com.moodboard.keyboard.camera.KeyboardCameraManager
import com.moodboard.keyboard.camera.PermissionActivity
import com.moodboard.keyboard.databinding.KeyboardViewBinding
import com.moodboard.keyboard.emotion.Emotion
import com.moodboard.keyboard.emotion.EmotionAnalyzer
import com.moodboard.keyboard.stickers.EmojiAdapter
import com.moodboard.keyboard.stickers.StickerAdapter
import com.moodboard.keyboard.stickers.StickerItem
import com.moodboard.keyboard.stickers.StickerRepository
import com.moodboard.keyboard.ui.SetupActivity
import com.moodboard.keyboard.util.Prefs
import com.moodboard.keyboard.util.RichContentSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * MoodBoard keyboard: QWERTY typing plus a live mood scan.
 * Tap Mood -> front camera streams -> MediaPipe reads your expression live ->
 * once stable it pulls GIPHY stickers for that mood -> tap a sticker to send.
 */
class MoodBoardService : InputMethodService(), QwertyKeyboardView.Listener {

    private lateinit var binding: KeyboardViewBinding
    private lateinit var prefs: Prefs
    private lateinit var stickerRepo: StickerRepository
    private lateinit var sender: RichContentSender
    private lateinit var stickerAdapter: StickerAdapter
    private lateinit var emojiAdapter: EmojiAdapter

    private var camera: KeyboardCameraManager? = null
    @Volatile private var analyzer: EmotionAnalyzer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    private enum class Mode { KEYS, CAMERA, RESULTS }
    @Volatile private var mode = Mode.KEYS
    @Volatile private var locked = false

    private var currentEmotion = Emotion.NEUTRAL
    private var stableCount = 0
    private var lastTs = 0L

    override fun onCreateInputView(): View {
        binding = KeyboardViewBinding.inflate(layoutInflater)
        prefs = Prefs(this)
        stickerRepo = StickerRepository(this, prefs)
        sender = RichContentSender(this)
        binding.qwerty.listener = this

        stickerAdapter = StickerAdapter(onClick = { sendSticker(it) })
        emojiAdapter = EmojiAdapter { emoji ->
            currentInputConnection?.commitText(emoji, 1)
            updateStatus("Inserted $emoji")
        }

        binding.btnMood.setOnClickListener { onMoodTapped() }
        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnBackToKeys.setOnClickListener { showKeys() }

        showKeys()
        return binding.root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (mode != Mode.KEYS) showKeys()
    }

    // ---------------- Typing ----------------
    override fun onChar(text: CharSequence) { currentInputConnection?.commitText(text, 1) }
    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (sel.isNullOrEmpty()) ic.deleteSurroundingText(1, 0) else ic.commitText("", 1)
    }
    override fun onEnter() {
        val ic = currentInputConnection ?: return
        val a = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (a != EditorInfo.IME_ACTION_NONE && a != EditorInfo.IME_ACTION_UNSPECIFIED)
            ic.performEditorAction(a) else ic.commitText("\n", 1)
    }
    override fun onSpace() { currentInputConnection?.commitText(" ", 1) }

    // ---------------- Mood scan ----------------
    private fun onMoodTapped() {
        if (!hasCameraPermission()) {
            updateStatus(getString(R.string.need_camera))
            startActivity(Intent(this, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        when (mode) {
            Mode.CAMERA -> lockAndFetch(currentEmotion)   // "Use mood" - lock immediately
            else -> startScan()
        }
    }

    private fun startScan() {
        mode = Mode.CAMERA
        locked = false
        stableCount = 0
        lastTs = 0L
        currentEmotion = Emotion.NEUTRAL
        binding.qwerty.visibility = View.GONE
        binding.stickerGrid.visibility = View.GONE
        binding.cameraContainer.visibility = View.VISIBLE
        binding.btnBackToKeys.visibility = View.VISIBLE
        binding.progress.visibility = View.GONE
        binding.btnMood.text = "✓ Use mood"
        binding.cameraHint.text = "Starting camera…"
        updateStatus("Look at the camera…")

        camera = KeyboardCameraManager(this).also { cam ->
            cam.start(
                binding.cameraPreview,
                onFrame = { bmp, ts -> onFrame(bmp, ts) },
                onError = { err -> main.post { toast("Camera: $err"); showKeys() } }
            )
        }
    }

    // Runs on the camera analysis thread.
    private fun onFrame(bitmap: Bitmap, ts: Long) {
        if (mode != Mode.CAMERA || locked) return
        var a = analyzer
        if (a == null) {
            a = try { EmotionAnalyzer(this) } catch (t: Throwable) {
                main.post { toast("Model load failed: ${shortErr(t)}"); showKeys() }
                return
            }
            analyzer = a
        }
        val t = if (ts > lastTs) ts else lastTs + 1
        lastTs = t
        val result = try { a!!.analyze(bitmap, t) } catch (_: Throwable) { return }
        main.post { updateLive(result) }
    }

    private fun updateLive(result: EmotionAnalyzer.Result) {
        if (mode != Mode.CAMERA || locked) return
        if (!result.hasFace) {
            binding.cameraHint.text = "Center your face in the frame"
            updateStatus("Looking for your face…")
            stableCount = 0
            return
        }
        val pct = (result.confidence * 100).toInt()
        binding.cameraHint.text = "${result.emotion.emoji}  ${result.emotion.label}  $pct%"
        if (result.emotion == currentEmotion) stableCount++ else { currentEmotion = result.emotion; stableCount = 1 }
        // Wait for an actual expression: never auto-lock on Neutral.
        if (currentEmotion == Emotion.NEUTRAL) {
            updateStatus("Make a clear face — smile, frown, brows up… or tap ✓ Use mood")
        } else {
            updateStatus("Detecting… ${result.emotion.label} ($pct%)")
            if (stableCount >= STABLE_FRAMES) lockAndFetch(currentEmotion)
        }
    }

    private fun lockAndFetch(emotion: Emotion) {
        if (locked) return
        locked = true
        camera?.stop(); camera = null
        analyzer?.close(); analyzer = null
        binding.cameraContainer.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        binding.btnMood.text = "🙂 Mood"
        updateStatus("Mood: ${emotion.label} ${emotion.emoji} · finding stickers…")

        scope.launch {
            val res = stickerRepo.search(emotion)
            binding.progress.visibility = View.GONE
            res.onSuccess { list ->
                if (list.isNotEmpty()) showStickers(emotion, list)
                else showEmojiFallback(emotion, "No stickers found")
            }.onFailure { showEmojiFallback(emotion, shortErr(it)) }
        }
    }

    private fun showStickers(emotion: Emotion, list: List<StickerItem>) {
        mode = Mode.RESULTS
        binding.stickerGrid.layoutManager = GridLayoutManager(this, 3)
        binding.stickerGrid.adapter = stickerAdapter
        stickerAdapter.submit(list)
        updateStatus("Mood: ${emotion.label} ${emotion.emoji} · tap a sticker")
        binding.qwerty.visibility = View.GONE
        binding.cameraContainer.visibility = View.GONE
        binding.stickerGrid.visibility = View.VISIBLE
        binding.btnBackToKeys.visibility = View.VISIBLE
    }

    private fun showEmojiFallback(emotion: Emotion, why: String) {
        mode = Mode.RESULTS
        binding.stickerGrid.layoutManager = GridLayoutManager(this, 5)
        binding.stickerGrid.adapter = emojiAdapter
        emojiAdapter.submit(emotion.emojis)
        updateStatus("Mood: ${emotion.label} · stickers offline ($why) · emoji instead")
        binding.qwerty.visibility = View.GONE
        binding.cameraContainer.visibility = View.GONE
        binding.stickerGrid.visibility = View.VISIBLE
        binding.btnBackToKeys.visibility = View.VISIBLE
    }

    private fun sendSticker(item: StickerItem) {
        updateStatus("Sending…")
        scope.launch {
            when (val r = sender.send(item, currentInputConnection, currentInputEditorInfo)) {
                is RichContentSender.SendResult.Success -> updateStatus("Sent! Tap Mood for more.")
                is RichContentSender.SendResult.Unsupported -> toast("This app doesn't accept stickers here.")
                is RichContentSender.SendResult.Error -> toast("Send failed: ${r.message}")
            }
        }
    }

    // ---------------- View state ----------------
    private fun showKeys() {
        mode = Mode.KEYS
        locked = false
        camera?.stop(); camera = null
        analyzer?.close(); analyzer = null
        binding.qwerty.visibility = View.VISIBLE
        binding.stickerGrid.visibility = View.GONE
        binding.cameraContainer.visibility = View.GONE
        binding.progress.visibility = View.GONE
        binding.btnBackToKeys.visibility = View.GONE
        binding.btnMood.text = "🙂 Mood"
        updateStatus(getString(R.string.setup_intro))
    }

    private fun openSettings() {
        startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun updateStatus(text: String) { binding.statusText.text = text }
    private fun shortErr(t: Throwable): String = (t.message ?: "error").take(50)
    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        camera?.stop(); analyzer?.close(); scope.cancel()
        super.onDestroy()
    }

    companion object { private const val STABLE_FRAMES = 5 }
}
