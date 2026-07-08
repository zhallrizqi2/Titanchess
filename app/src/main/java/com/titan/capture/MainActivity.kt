package com.titan.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var projectionManager: MediaProjectionManager

    companion object {
        const val REQUEST_CODE_SCREEN_CAPTURE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            requestScreenCapturePermission()
        }
    }

    private fun requestScreenCapturePermission() {
        val captureIntent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, REQUEST_CODE_SCREEN_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                }
                startForegroundService(serviceIntent)
                Toast.makeText(this, "Capture service dimulai", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Izin screen capture ditolak", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
