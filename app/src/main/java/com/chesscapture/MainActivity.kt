package com.chesscapture

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->

            if (result.resultCode == RESULT_OK && result.data != null) {

                val intent = Intent(
                    this,
                    ScreenCaptureService::class.java
                )

                intent.putExtra(
                    ScreenCaptureService.EXTRA_RESULT_CODE,
                    result.resultCode
                )

                intent.putExtra(
                    ScreenCaptureService.EXTRA_DATA,
                    result.data
                )

                startForegroundService(intent)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager =
            getSystemService(MediaProjectionManager::class.java)

        setContent {

            MaterialTheme {

                MainScreen {

                    val captureIntent =
                        projectionManager.createScreenCaptureIntent()

                    screenCaptureLauncher.launch(captureIntent)

                }

            }

        }

    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartCapture: () -> Unit
) {

    Scaffold(

        topBar = {

            TopAppBar(

                title = {

                    Text("Chess Capture")

                }

            )

        }

    ) { padding ->

        Column(

            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),

            horizontalAlignment = Alignment.CenterHorizontally,

            verticalArrangement = Arrangement.Center

        ) {

            Text(
                text = "Realtime Screen Capture",
                style = MaterialTheme.typography.headlineMedium
            )

            Button(

                onClick = onStartCapture,

                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp)

            ) {

                Text("Start Capture")

            }

        }

    }

}
