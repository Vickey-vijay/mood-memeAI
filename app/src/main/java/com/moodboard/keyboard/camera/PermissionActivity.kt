package com.moodboard.keyboard.camera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat

/**
 * A keyboard (Service) cannot show the runtime permission dialog itself, so the
 * IME launches this transparent activity to ask for CAMERA the first time the
 * user taps the Mood button (see docs/02_Camera_Integration.md).
 */
class PermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val granted = checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            finish()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        // Whatever the result, just close; the IME re-checks the permission state.
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQ = 4711
    }
}
