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
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

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

    private var calibrationButton: Button? = null
    private var calibrationRequested = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mainHandler.post { setupWebView() }
        mainHandler.post { setupCalibrationButton() }
    }

    private fun setupCalibrationButton() {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        try {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val button = Button(this)
            button.text = "Kalibrasi"
            button.setBackgroundColor(android.graphics.Color.parseColor("#CC2196F3"))
            button.setTextColor(android.graphics.Color.WHITE)

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 200

            var initialX = 0
            var initialY = 0
            var touchStartX = 0f
            var touchStartY = 0f

            button.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - touchStartX).toInt()
                        params.y = initialY + (event.rawY - touchStartY).toInt()
                        windowManager.updateViewLayout(v, params)
                        false
                    }
                    else -> false
                }
            }

            button.setOnClickListener {
                calibrationRequested = true
                Toast.makeText(this, "Menunggu frame berikutnya untuk kalibrasi...", Toast.LENGTH_SHORT).show()
            }

            windowManager.addView(button, params)
            calibrationButton = button
        } catch (e: Exception) {
            Log.e(tag, "Gagal buat tombol kalibrasi", e)
        }
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
        b.onLogMessage = { msg -> updateNotification("Log: $msg") }
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
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                1, 1,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
        try {
            virtualDisplay?.release()
            imageReader?.close()
        } catch (e: Exception) {
            Log.e(tag, "Gagal cleanup display lama", e)
        }
        captureCounter = 0
        isAnalyzing = false

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
            if (captureCounter % 100 == 0) {
                Log.d(tag, "onImageAvailable terpanggil, hitungan ke-$captureCounter")
            }
            try {
                val now = System.currentTimeMillis()
                val image = reader.acquireLatestImage()
                if (image == null) {
                    return@setOnImageAvailableListener
                }

                val bitmap = imageToBitmap(image, width, height)
                image.close()

                if (calibrationRequested) {
                    calibrationRequested = false
                    saveCalibrationImage(bitmap)
                }

                if (isAnalyzing || (now - lastAnalysisTime) < analysisIntervalMs) {
                    return@setOnImageAvailableListener
                }

                lastAnalysisTime = now
                isAnalyzing = true
                updateNotification("Capture #$captureCounter -> mulai analisis...")
                sendBitmapToAnalyzer(applyCropIfEnabled(bitmap))

            } catch (e: Exception) {
                Log.e(tag, "Error saat proses image", e)
                updateNotification("ERROR proses image: ${e.message}")
                isAnalyzing = false
            }
        }, mainHandler)
    }

    private fun saveCalibrationImage(bitmap: Bitmap) {
        try {
            val file = File(filesDir, "calibration.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            mainHandler.post {
                Toast.makeText(this, "Kalibrasi disimpan! Buka app, tap 'Atur Area Papan'", Toast.LENGTH_LONG).show()
            }
            Log.i(tag, "Gambar kalibrasi disimpan ke ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(tag, "Gagal simpan gambar kalibrasi", e)
        }
    }

    private fun applyCropIfEnabled(bitmap: Bitmap): Bitmap {
        // --- MODE OTOMATIS: MEMOTONG PERSEGI TENGAH LAYAR HP SECARA MANDIRI ---
        val width = bitmap.width
        val height = bitmap.height
        val boardSize = width 
        val startY = if (height > width) (height - width) / 2 else 0

        return try {
            Log.d(tag, "Auto-crop aktif: Mengambil kotak $boardSize x $boardSize dari Y=$startY")
            Bitmap.createBitmap(bitmap, 0, startY, boardSize, boardSize)
        } catch (e: Exception) {
            Log.e(tag, "Gagal melakukan auto-crop", e)
            bitmap
        }
    }

    private fun sendBitmapToAnalyzer(bitmap: Bitmap) {
        mainHandler.post {
            try {
                val currentWebView = webView
                if (currentWebView == null) {
                    updateNotification("WEBVIEW MASIH NULL, belum siap!")
                    isAnalyzing = false
                    return@post
                }
                updateNotification("Mengirim ke WebView analyzer...")
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, outputStream)
                val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                val dataUrl = "data:image/png;base64,$base64"
                currentWebView.evaluateJavascript("analyzeImage('$dataUrl')") { result ->
                    Log.d(tag, "evaluateJavascript callback: $result")
                }
            } catch (e: Exception) {
                Log.e(tag, "Gagal kirim bitmap ke analyzer", e)
                mainHandler.post {
                    updateNotification("EXCEPTION kirim ke analyzer: ${e.message}")
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

        // PERBAIKAN: Membuat alokasi ukuran baris yang presisi agar data pixel tidak miring terdistorsi rowPadding
        val bitmap = Bitmap.createBitmap(rowStride / pixelStride, height, Bitmap.Config.ARGB_8888)
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
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            webView?.let { windowManager.removeView(it) }
            calibrationButton?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(tag, "Gagal remove view dari window", e)
        }
        webView?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
