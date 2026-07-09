package com.titan.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val channelId = "titan_capture_channel"
    private val tag = "TitanCapture"

    private var webView: WebView? = null
    private var bridge: BoardAnalyzerBridge? = null
    private var isAnalyzing = false
    private var lastAnalysisTime = 0L
    private var captureCounter = 0
    private val analysisIntervalMs = 2500L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mainHandler.post { setupWebView() }
    }

    private fun setupWebView() {
        val view = WebView(this)
        val settings: WebSettings = view.settings
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        settings.domStorageEnabled = true

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                v: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        val b = BoardAnalyzerBridge(this)
        b.onFenReady = { fen -> handleFenResult(fen) }
        view.addJavascriptInterface(b, "AndroidBridge")

        attachWebViewToWindow(view)

        view.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        webView = view
        bridge = b
    }

    private fun attachWebViewToWindow(view: WebView) {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            logAndToast("Izin 'Tampil di atas aplikasi lain' belum diberikan! WebView tidak akan berfungsi.")
            return
        }

        try {
            val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                android.view.WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = android.view.WindowManager.LayoutParams(
                1, 1,
                overlayType,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            windowManager.addView(view, params)
            Log.i(tag, "WebView berhasil di-attach ke WindowManager")
        } catch (e: Exception) {
            logAndToast("Gagal attach WebView ke window: ${e.message}")
            Log.e(tag, "attachWebViewToWindow error", e)
        }
    }

    private fun handleFenResult(fen: String) {
        isAnalyzing = false
        Log.i(tag, "FEN baru: $fen")
        updateNotification("FEN: $fen")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(1, buildNotification("Menganalisis papan catur di layar"))
        } catch (e: Exception) {
            logAndToast("Gagal startForeground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val resultCode = intent?.getIntExtra("resultCode", Int.MIN_VALUE) ?: Int.MIN_VALUE
            val data = intent?.getParcelableExtra<Intent>("data")

            if (resultCode != android.app.Activity.RESULT_OK || data == null) {
                logAndToast("resultCode/data tidak valid (resultCode=$resultCode), service berhenti")
                stopSelf()
                return START_NOT_STICKY
            }

            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                logAndToast("MediaProjection null, gagal dibuat")
                stopSelf()
                return START_NOT_STICKY
            }

            setupVirtualDisplay()
            logAndToast("Virtual display berhasil dibuat")

        } catch (e: Exception) {
            logAndToast("ERROR di onStartCommand: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(tag, "onStartCommand error", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun setupVirtualDisplay() {
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.e(tag, "MediaProjection dihentikan oleh sistem")
                virtualDisplay?.release()
                imageReader?.close()
            }
        }, mainHandler)

        val metrics = DisplayMetrics()
        val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
        display.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "TitanCapture",
            width, height, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            captureCounter++
            if (captureCounter <= 5) {
                Log.d(tag, "onImageAvailable terpanggil, hitungan ke-$captureCounter")
                mainHandler.post {
                    Toast.makeText(this, "Frame masuk #$captureCounter", Toast.LENGTH_SHORT).show()
                }
            }
            try {
                val now = System.currentTimeMillis()
                val image = reader.acquireLatestImage()
                if (image == null) {
                    Log.d(tag, "acquireLatestImage() null")
                    return@setOnImageAvailableListener
                }

                if (isAnalyzing || (now - lastAnalysisTime) < analysisIntervalMs) {
                    image.close()
                    return@setOnImageAvailableListener
                }

                val bitmap = imageToBitmap(image, width, height)
                image.close()

                lastAnalysisTime = now
                isAnalyzing = true
                sendBitmapToAnalyzer(bitmap)

            } catch (e: Exception) {
                Log.e(tag, "Error saat proses image", e)
                isAnalyzing = false
            }
        }, mainHandler)
    }

    private fun sendBitmapToAnalyzer(bitmap: Bitmap) {
        mainHandler.post {
            try {
                val currentWebView = webView
                if (currentWebView == null) {
                    Toast.makeText(this, "WEBVIEW MASIH NULL, belum siap!", Toast.LENGTH_LONG).show()
                    isAnalyzing = false
                    return@post
                }
                Toast.makeText(this, "Mengirim capture ke analyzer...", Toast.LENGTH_SHORT).show()
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, outputStream)
                val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                val dataUrl = "data:image/png;base64,$base64"
                currentWebView.evaluateJavascript("analyzeImage('$dataUrl')") { result ->
                    Log.d(tag, "evaluateJavascript callback: $result")
                    mainHandler.post {
                        Toast.makeText(this, "JS callback: $result", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Gagal kirim bitmap ke analyzer", e)
                mainHandler.post {
                    Toast.makeText(this, "EXCEPTION kirim ke analyzer: ${e.message}", Toast.LENGTH_LONG).show()
                }
                isAnalyzing = false
            }
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Titan Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Titan Chess aktif")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, notification)
    }

    private fun logAndToast(message: String) {
        Log.e(tag, message)
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        try {
            webView?.let {
                val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
                windowManager.removeView(it)
            }
        } catch (e: Exception) {
            Log.e(tag, "Gagal remove WebView dari window", e)
        }
        webView?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}