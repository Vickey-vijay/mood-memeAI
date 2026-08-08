package com.moodboard.keyboard.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.moodboard.keyboard.R
import com.moodboard.keyboard.camera.KeyboardCameraManager
import com.moodboard.keyboard.databinding.ActivityEmotionLabBinding
import com.moodboard.keyboard.emotion.ActionUnits
import com.moodboard.keyboard.emotion.EmotionAnalyzer
import com.moodboard.keyboard.emotion.EmotionResult
import com.moodboard.keyboard.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live view of all 26 AU bars, the full emotion distribution, intensity and
 * calibration state (SPEC_V2 A.8). Primary purpose is producing report/viva
 * screenshots and letting the user verify a mood is reachable. Runs the same
 * EmotionAnalyzer pipeline the keyboard uses.
 */
class EmotionLabActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmotionLabBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: AuBarAdapter
    private var camera: KeyboardCameraManager? = null
    @Volatile private var analyzer: EmotionAnalyzer? = null
    @Volatile private var lastTs = 0L
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmotionLabBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        adapter = AuBarAdapter()
        binding.auList.layoutManager = LinearLayoutManager(this)
        binding.auList.adapter = adapter
        adapter.submit(ActionUnits.LABELS.map { AuBarAdapter.Row(it, 0f) })

        updateCalibrationText()

        if (hasCameraPermission()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && hasCameraPermission()) startCamera()
        else if (requestCode == REQ_CAMERA) binding.statusText.text = getString(R.string.need_camera)
    }

    private fun startCamera() {
        if (camera != null) return
        camera = KeyboardCameraManager(this).also { cam ->
            cam.start(
                binding.cameraPreview,
                onFrame = { bmp, ts -> onFrame(bmp, ts) },
                onError = { err -> main.post { binding.statusText.text = "Camera error: $err" } }
            )
        }
    }

    // Runs on the camera analysis thread.
    private fun onFrame(bitmap: Bitmap, ts: Long) {
        var a = analyzer
        if (a == null) {
            a = try { EmotionAnalyzer(this) } catch (_: Throwable) { return }
            analyzer = a
        }
        val t = if (ts > lastTs) ts else lastTs + 1
        lastTs = t
        val result = try { a!!.analyze(bitmap, t) } catch (_: Throwable) { return }
        main.post { render(result) }
    }

    private fun render(result: EmotionResult) {
        val rows = ActionUnits.LABELS.mapIndexed { i, label ->
            AuBarAdapter.Row(label, result.auVector.getOrElse(i) { 0f })
        }
        adapter.submit(rows)
        binding.emotionText.text = "${result.emotion.emoji} ${result.emotion.label}"
        binding.distributionText.text = if (result.distribution.isEmpty()) "—" else
            result.distribution.joinToString("   ") { (e, p) -> "${e.emoji} ${e.label} ${(p * 100).toInt()}%" }
        binding.intensityText.text =
            "Intensity: %.2f   Face: %s".format(result.intensity, if (result.hasFace) "yes" else "no")
        updateCalibrationText()
    }

    private fun updateCalibrationText() {
        binding.calibrationText.text = if (prefs.neutralBaseline.isNotBlank() && prefs.neutralBaselineAt > 0) {
            val date = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(prefs.neutralBaselineAt))
            getString(R.string.lab_calibrated, date)
        } else {
            getString(R.string.lab_uncalibrated)
        }
    }

    override fun onDestroy() {
        camera?.stop(); camera = null
        analyzer?.close(); analyzer = null
        super.onDestroy()
    }

    companion object { private const val REQ_CAMERA = 9232 }
}

/** Plain RecyclerView adapter for the 26 labelled AU progress bars. */
private class AuBarAdapter : RecyclerView.Adapter<AuBarAdapter.VH>() {
    data class Row(val label: String, val value: Float)

    private val rows = ArrayList<Row>()

    fun submit(newRows: List<Row>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_au_bar, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.label.text = row.label
        holder.progress.progress = (row.value * 100).toInt().coerceIn(0, 100)
        holder.value.text = "%.2f".format(row.value)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.auLabel)
        val progress: LinearProgressIndicator = v.findViewById(R.id.auProgress)
        val value: TextView = v.findViewById(R.id.auValue)
    }
}
