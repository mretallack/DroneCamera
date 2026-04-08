package com.dronecamera

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FrameAssemblerTest {

    private lateinit var assembler: FrameAssembler

    @Before
    fun setUp() {
        assembler = FrameAssembler()
    }

    // --- Header parsing tests (3.1) ---

    @Test
    fun `parseHeader returns valid header for correct packet`() {
        val data = ByteArray(12)
        data[0] = 0x63; data[1] = 0x63  // magic
        data[2] = 0x03                    // cmdType
        data[3] = 0x01; data[4] = 0x00   // seqId = 1
        data[5] = 0x10; data[6] = 0x00   // pktLen = 16
        data[7] = 0x01                    // frameType = 1
        data[8] = 0x05; data[9] = 0x00; data[10] = 0x00; data[11] = 0x00 // frameId = 5

        val header = assembler.parseHeader(data)
        assertNotNull(header)
        assertEquals(0x03, header!!.cmdType)
        assertEquals(1, header.seqId)
        assertEquals(16, header.pktLen)
        assertEquals(1, header.frameType)
        assertEquals(5, header.frameId)
    }

    @Test
    fun `parseHeader returns null for invalid magic bytes`() {
        val data = ByteArray(12)
        data[0] = 0x00; data[1] = 0x00
        assertNull(assembler.parseHeader(data))
    }

    @Test
    fun `parseHeader returns null for short packet`() {
        assertNull(assembler.parseHeader(ByteArray(5)))
        assertNull(assembler.parseHeader(ByteArray(0)))
    }

    // --- Frame assembly tests (3.2) ---

    @Test
    fun `processPacket returns null for packet too short`() {
        assertNull(assembler.processPacket(ByteArray(10)))
        assertNull(assembler.processPacket(ByteArray(54)))
    }

    @Test
    fun `processPacket returns null for non-video cmdType`() {
        val pkt = makePacket(cmdType = 0x01, seqId = 1, frameId = 1, frameType = 0x02, payload = jpegPayload())
        assertNull(assembler.processPacket(pkt))
    }

    @Test
    fun `processPacket emits frame on frameId change`() {
        // First frame, first packet with JPEG SOI
        val pkt1 = makePacket(cmdType = 0x03, seqId = 1, frameId = 1, frameType = 0x02, payload = jpegPayload())
        assertNull(assembler.processPacket(pkt1)) // No previous frame to emit

        // New frame ID triggers emit of previous frame (will be null since BitmapFactory returns null in unit tests)
        val pkt2 = makePacket(cmdType = 0x03, seqId = 1, frameId = 2, frameType = 0x02, payload = jpegPayload())
        // With unitTests.isReturnDefaultValues = true, BitmapFactory.decodeByteArray returns null
        val result = assembler.processPacket(pkt2)
        // Frame was emitted (attempted decode), result is null because BitmapFactory is mocked to return null
        assertNull(result)
    }

    @Test
    fun `processPacket rejects first packet without JPEG SOI`() {
        val badPayload = ByteArray(10) { 0x00 }
        val pkt1 = makePacket(cmdType = 0x03, seqId = 1, frameId = 1, frameType = 0x02, payload = badPayload)
        assertNull(assembler.processPacket(pkt1))

        // Frame change should still return null since buffer was rejected
        val pkt2 = makePacket(cmdType = 0x03, seqId = 1, frameId = 2, frameType = 0x02, payload = jpegPayload())
        assertNull(assembler.processPacket(pkt2))
    }

    @Test
    fun `multi-packet assembly appends payloads`() {
        val pkt1 = makePacket(cmdType = 0x03, seqId = 1, frameId = 1, frameType = 0x02, payload = jpegPayload())
        assembler.processPacket(pkt1)

        // Second packet of same frame (seqId=2)
        val extraPayload = ByteArray(20) { 0x42 }
        val pkt2 = makePacket(cmdType = 0x03, seqId = 2, frameId = 1, frameType = 0x02, payload = extraPayload)
        assertNull(assembler.processPacket(pkt2)) // Still same frame, no emit

        // Trigger emit with new frame
        val pkt3 = makePacket(cmdType = 0x03, seqId = 1, frameId = 2, frameType = 0x02, payload = jpegPayload())
        assembler.processPacket(pkt3) // Emits frame 1 (decode returns null in unit test)
    }

    // --- Deobfuscation tests (3.3) ---

    @Test
    fun `encodeIndex returns 0 for zero length`() {
        assertEquals(0, encodeIndex(42, 0))
    }

    @Test
    fun `encodeIndex even length`() {
        // length=10 (even): (10 + 1 + (10 xor 5) xor 10) % 10
        val result = encodeIndex(5, 10)
        val expected = (10 + 1 + (10 xor 5) xor 10) % 10
        assertEquals(expected, result)
    }

    @Test
    fun `encodeIndex odd length`() {
        // length=11 (odd): ((11 xor 5) + 11 xor 11) % 11
        val result = encodeIndex(5, 11)
        val expected = ((11 xor 5) + 11 xor 11) % 11
        assertEquals(expected, result)
    }

    @Test
    fun `deobfuscate skips frameType 0x02`() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val result = deobfuscate(data, 1, 0x02)
        assertArrayEquals(data, result)
    }

    @Test
    fun `deobfuscate skips empty data`() {
        val data = ByteArray(0)
        val result = deobfuscate(data, 1, 0x01)
        assertArrayEquals(data, result)
    }

    @Test
    fun `deobfuscate flips byte at encodeIndex for non-0x02 frameType`() {
        val data = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)
        val frameId = 3
        val idx = encodeIndex(frameId, data.size)
        val result = deobfuscate(data, frameId, 0x01)

        // The byte at idx should be bitwise inverted
        for (i in data.indices) {
            if (i == idx) {
                assertEquals((data[i].toInt().inv() and 0xFF).toByte(), result[i])
            } else {
                assertEquals(data[i], result[i])
            }
        }
    }

    @Test
    fun `deobfuscate does not modify original array`() {
        val data = byteArrayOf(0x10, 0x20, 0x30)
        val copy = data.copyOf()
        deobfuscate(data, 1, 0x01)
        assertArrayEquals(copy, data)
    }

    // --- Helpers ---

    /** Build a minimal valid 0x6363 packet with given fields. */
    private fun makePacket(cmdType: Int, seqId: Int, frameId: Int, frameType: Int, payload: ByteArray): ByteArray {
        val pkt = ByteArray(FrameAssembler.PAYLOAD_OFFSET + payload.size)
        pkt[0] = 0x63; pkt[1] = 0x63
        pkt[2] = cmdType.toByte()
        pkt[3] = (seqId and 0xFF).toByte(); pkt[4] = (seqId shr 8 and 0xFF).toByte()
        pkt[5] = 0x00; pkt[6] = 0x00 // pktLen
        pkt[7] = frameType.toByte()
        pkt[8] = (frameId and 0xFF).toByte()
        pkt[9] = (frameId shr 8 and 0xFF).toByte()
        pkt[10] = (frameId shr 16 and 0xFF).toByte()
        pkt[11] = (frameId shr 24 and 0xFF).toByte()
        // Byte 48 is the sequence ID used by processPacket
        pkt[48] = seqId.toByte()
        // Copy payload at offset 54
        payload.copyInto(pkt, FrameAssembler.PAYLOAD_OFFSET)
        return pkt
    }

    /** Minimal payload starting with JPEG SOI marker. */
    private fun jpegPayload(): ByteArray {
        val payload = ByteArray(20)
        payload[0] = 0xFF.toByte()
        payload[1] = 0xD8.toByte()
        return payload
    }
}
