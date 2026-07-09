package com.titan.capture

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import java.io.File

class CalibrationActivity : Activity() {

    private lateinit var cropOverlay: CropOverlayView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        val imageView = findViewById<ImageView>(R.id.calibrationImage)
        cropOverlay = findViewById(R.id.cropOverlay)

        val file = File(filesDir, "calibration.png")
        if (!file.exists()) {
            Toast.makeText(this, "Belum ada gambar kalibrasi. Tap tombol kalibrasi (floating) dulu saat papan catur terlihat.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        imageView.setImageBitmap(bitmap)

        findViewById<Button>(R.id.btnSaveCrop).setOnClickListener {
            val rect = cropOverlay.getSelectedRectNormalized()
            if (rect == null) {
                Toast.makeText(this, "Gambar dulu kotak di sekitar papan catur (tap-geser)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val prefs = getSharedPreferences("titan_prefs", MODE_PRIVATE)
            prefs.edit()
                .putFloat("crop_left", rect[0])
                .putFloat("crop_top", rect[1])
                .putFloat("crop_right", rect[2])
                .putFloat("crop_bottom", rect[3])
                .putBoolean("crop_enabled", true)
                .apply()
            Toast.makeText(this, "Area papan disimpan!", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btnResetCrop).setOnClickListener {
            cropOverlay.reset()
        }

        findViewById<Button>(R.id.btnClearCrop).setOnClickListener {
            val prefs = getSharedPreferences("titan_prefs", MODE_PRIVATE)
            prefs.edit().putBoolean("crop_enabled", false).apply()
            Toast.makeText(this, "Crop dinonaktifkan, akan pakai layar penuh lagi", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
