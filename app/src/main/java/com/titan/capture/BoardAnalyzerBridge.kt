package com.titan.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast

class BoardAnalyzerBridge(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tag = "TitanCapture"

    var onFenReady: ((String) -> Unit)? = null

    @JavascriptInterface
    fun onFenResult(fen: String) {
        Log.i(tag, "FEN hasil analisis: $fen")
        mainHandler.post {
            Toast.makeText(context, "FEN: $fen", Toast.LENGTH_LONG).show()
            onFenReady?.invoke(fen)
        }
    }

    @JavascriptInterface
    fun onError(message: String) {
        Log.e(tag, "Error dari WebView: $message")
        mainHandler.post {
            Toast.makeText(context, "Error WebView: $message", Toast.LENGTH_LONG).show()
            onFenReady?.invoke("ERROR")
        }
    }

    @JavascriptInterface
    fun onLog(message: String) {
        Log.d(tag, "WebView log: $message")
        mainHandler.post {
            Toast.makeText(context, "Log: $message", Toast.LENGTH_SHORT).show()
        }
    }
}
