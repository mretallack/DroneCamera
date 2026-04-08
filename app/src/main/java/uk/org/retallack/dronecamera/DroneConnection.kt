package uk.org.retallack.dronecamera

import android.net.Network
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Manages UDP communication with the drone: heartbeat sending and packet receiving.
 */
class DroneConnection(private val network: Network? = null) {

    companion object {
        const val DRONE_IP = "192.168.0.1"
        const val DRONE_PORT = 40000
        const val HEARTBEAT_INTERVAL_MS = 1000L
        const val TIMEOUT_MS = 3000L
        const val RECV_BUFFER_SIZE = 65536
        val HEARTBEAT_PACKET = byteArrayOf(0x63, 0x63, 0x01, 0x00, 0x00, 0x00, 0x00)
    }

    private var socket: DatagramSocket? = null
    private var heartbeatJob: Job? = null

    /**
     * Connect and return a Flow of raw UDP packets from the drone.
     * Emits empty ByteArray on timeout (no packets for 3s).
     */
    fun connect(): Flow<ByteArray> = callbackFlow {
        val sock = DatagramSocket().apply {
            soTimeout = TIMEOUT_MS.toInt()
            receiveBufferSize = RECV_BUFFER_SIZE
        }
        socket = sock

        // Bind socket to drone WiFi network if available (API 29+)
        network?.bindSocket(sock)

        val droneAddr = InetAddress.getByName(DRONE_IP)

        // Start heartbeat coroutine
        heartbeatJob = launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val pkt = DatagramPacket(HEARTBEAT_PACKET, HEARTBEAT_PACKET.size, droneAddr, DRONE_PORT)
                    sock.send(pkt)
                } catch (_: Exception) { }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }

        // Receive loop
        launch(Dispatchers.IO) {
            val buf = ByteArray(2048)
            while (isActive) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    trySend(buf.copyOf(pkt.length))
                } catch (_: java.net.SocketTimeoutException) {
                    trySend(ByteArray(0)) // Timeout signal
                } catch (_: Exception) {
                    break
                }
            }
        }

        awaitClose { disconnect() }
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        socket?.close()
        socket = null
    }
}
