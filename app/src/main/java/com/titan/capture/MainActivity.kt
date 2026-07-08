package com.titan.capture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var webView: WebView
    private lateinit var bridge: BoardAnalyzerBridge

    companion object {
        const val REQUEST_CODE_SCREEN_CAPTURE = 1001
        const val REQUEST_CODE_NOTIFICATION_PERMISSION = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupWebView()

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            checkNotificationPermissionThenCapture()
        }

        findViewById<Button>(R.id.btnTestModel).setOnClickListener {
            runModelSmokeTest()
        }
    }

    private fun setupWebView() {
        webView = WebView(this)
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true

        bridge = BoardAnalyzerBridge(this)
        webView.addJavascriptInterface(bridge, "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun runModelSmokeTest() {
        Toast.makeText(this, "Menjalankan test model...", Toast.LENGTH_SHORT).show()

        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        val dataUrl = "data:image/png;base64,$base64"

        webView.evaluateJavascript("analyzeImage('$dataUrl')", null)
    }

    private fun checkNotificationPermissionThenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATION_PERMISSION
                )
                return
            }
        }
        requestScreenCapturePermission()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_NOTIFICATION_PERMISSION) {
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
