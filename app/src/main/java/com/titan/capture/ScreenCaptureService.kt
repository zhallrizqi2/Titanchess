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
        view.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        webView = view
        bridge = b
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
