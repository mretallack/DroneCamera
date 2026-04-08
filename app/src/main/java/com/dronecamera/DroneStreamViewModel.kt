package com.dronecamera

import android.content.Context
import android.graphics.Bitmap
import android.net.Network
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class StreamState { IDLE, CONNECTING_WIFI, CONNECTING_DRONE, STREAMING, ERROR }

class DroneStreamViewModel : ViewModel() {

    private val _streamState = MutableStateFlow(StreamState.IDLE)
    val streamState: StateFlow<StreamState> = _streamState

    private val _currentFrame = MutableStateFlow<Bitmap?>(null)
    val currentFrame: StateFlow<Bitmap?> = _currentFrame

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private var wifiConnector: WifiConnector? = null
    private var droneConnection: DroneConnection? = null
    private var streamJob: Job? = null
    private var network: Network? = null

    fun connectWifi(context: Context) {
        _streamState.value = StreamState.CONNECTING_WIFI
        val connector = WifiConnector(context)
        wifiConnector = connector
        connector.connect(
            onNetworkAvailable = { net ->
                network = net
                startStream() // Auto-start stream once WiFi is ready
            },
            onUnavailable = {
                _streamState.value = StreamState.ERROR
                _errorMessage.value = "No drone network found"
            }
        )
    }

    fun startStream() {
        if (streamJob?.isActive == true) return
        _streamState.value = StreamState.CONNECTING_DRONE

        val connection = DroneConnection(network)
        droneConnection = connection
        val assembler = FrameAssembler()

        streamJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                connection.connect().collect { data ->
                    if (data.isEmpty()) {
                        // Timeout signal
                        _streamState.value = StreamState.ERROR
                        _errorMessage.value = "Connection lost"
                        return@collect
                    }
                    if (_streamState.value == StreamState.CONNECTING_DRONE) {
                        _streamState.value = StreamState.STREAMING
                    }
                    assembler.processPacket(data)?.let { bitmap ->
                        _currentFrame.value = bitmap
                    }
                }
            } catch (_: Exception) {
                _streamState.value = StreamState.ERROR
                _errorMessage.value = "Stream error"
            }
        }
    }

    fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        droneConnection?.disconnect()
        droneConnection = null
        wifiConnector?.disconnect()
        wifiConnector = null
        network = null
        _streamState.value = StreamState.IDLE
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopStream()
    }
}
