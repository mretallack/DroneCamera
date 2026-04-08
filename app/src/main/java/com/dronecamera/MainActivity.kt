package com.dronecamera

import android.graphics.Bitmap
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: DroneStreamViewModel by viewModels()
    private lateinit var surfaceView: SurfaceView
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private var surfaceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }
        })

        toggleButton.setOnClickListener {
            when (viewModel.streamState.value) {
                StreamState.IDLE -> {
                    viewModel.connectWifi(this)
                }
                StreamState.STREAMING, StreamState.ERROR, StreamState.CONNECTING_WIFI, StreamState.CONNECTING_DRONE -> {
                    viewModel.stopStream()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.streamState.collect { state ->
                runOnUiThread { updateUI(state) }
            }
        }

        lifecycleScope.launch {
            viewModel.currentFrame.collect { bitmap ->
                bitmap?.let { if (surfaceReady) renderFrame(it) }
            }
        }

    }

    private fun updateUI(state: StreamState) {
        when (state) {
            StreamState.IDLE -> {
                statusText.setText(R.string.status_idle)
                toggleButton.setText(R.string.btn_start)
                toggleButton.isEnabled = true
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            StreamState.CONNECTING_WIFI -> {
                statusText.setText(R.string.status_connecting_wifi)
                toggleButton.setText(R.string.btn_stop)
                toggleButton.isEnabled = true
            }
            StreamState.CONNECTING_DRONE -> {
                statusText.setText(R.string.status_connecting_drone)
                toggleButton.setText(R.string.btn_stop)
                toggleButton.isEnabled = true
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            StreamState.STREAMING -> {
                statusText.setText(R.string.status_streaming)
                toggleButton.setText(R.string.btn_stop)
                toggleButton.isEnabled = true
            }
            StreamState.ERROR -> {
                val msg = viewModel.errorMessage.value ?: getString(R.string.status_error)
                statusText.text = msg
                toggleButton.setText(R.string.btn_stop)
                toggleButton.isEnabled = true
            }
        }
    }

    private fun renderFrame(bitmap: Bitmap) {
        val holder = surfaceView.holder
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawBitmap(bitmap, null, canvas.clipBounds, null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopStream()
    }
}
