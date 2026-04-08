package uk.org.retallack.dronecamera

import org.junit.Assert.*
import org.junit.Test

class DroneConnectionTest {

    @Test
    fun `heartbeat packet has correct bytes`() {
        val expected = byteArrayOf(0x63, 0x63, 0x01, 0x00, 0x00, 0x00, 0x00)
        assertArrayEquals(expected, DroneConnection.HEARTBEAT_PACKET)
    }

    @Test
    fun `heartbeat packet starts with 0x6363 magic`() {
        assertEquals(0x63.toByte(), DroneConnection.HEARTBEAT_PACKET[0])
        assertEquals(0x63.toByte(), DroneConnection.HEARTBEAT_PACKET[1])
    }

    @Test
    fun `heartbeat packet length is 7`() {
        assertEquals(7, DroneConnection.HEARTBEAT_PACKET.size)
    }

    @Test
    fun `drone IP is correct`() {
        assertEquals("192.168.0.1", DroneConnection.DRONE_IP)
    }

    @Test
    fun `drone port is 40000`() {
        assertEquals(40000, DroneConnection.DRONE_PORT)
    }

    @Test
    fun `timeout is 3 seconds`() {
        assertEquals(3000L, DroneConnection.TIMEOUT_MS)
    }

    @Test
    fun `heartbeat interval is 1 second`() {
        assertEquals(1000L, DroneConnection.HEARTBEAT_INTERVAL_MS)
    }

    @Test
    fun `receive buffer is 64KB`() {
        assertEquals(65536, DroneConnection.RECV_BUFFER_SIZE)
    }

    @Test
    fun `disconnect on fresh instance does not throw`() {
        val conn = DroneConnection()
        conn.disconnect() // Should not throw
    }

    @Test
    fun `disconnect can be called multiple times`() {
        val conn = DroneConnection()
        conn.disconnect()
        conn.disconnect() // Should not throw
    }
}
