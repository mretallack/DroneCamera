package uk.org.retallack.dronecamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Parsed 0x6363 packet header.
 */
data class PacketHeader(
    val cmdType: Int,
    val seqId: Int,
    val pktLen: Int,
    val frameType: Int,
    val frameId: Int
)

/**
 * Parses 0x6363 protocol packets and assembles multi-packet JPEG frames.
 * Direct port of the reverse-engineered liblewei native library protocol.
 */
class FrameAssembler {

    companion object {
        const val HEADER_MAGIC = 0x6363
        const val MIN_HEADER_SIZE = 12
        const val PAYLOAD_OFFSET = 54
        const val CMD_VIDEO = 0x03
        private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    }

    private var currentFrameId: Int? = null
    private var frameBuffer: ByteArray? = null
    private var currentFrameType: Int = 0

    /**
     * Parse a 0x6363 packet header. Returns null if invalid.
     */
    fun parseHeader(data: ByteArray): PacketHeader? {
        if (data.size < MIN_HEADER_SIZE) return null
        val magic = (data[1].toInt() and 0xFF shl 8) or (data[0].toInt() and 0xFF)
        if (magic != HEADER_MAGIC) return null
        return PacketHeader(
            cmdType = data[2].toInt() and 0xFF,
            seqId = (data[4].toInt() and 0xFF shl 8) or (data[3].toInt() and 0xFF),
            pktLen = (data[6].toInt() and 0xFF shl 8) or (data[5].toInt() and 0xFF),
            frameType = data[7].toInt() and 0xFF,
            frameId = (data[11].toInt() and 0xFF shl 24) or
                    (data[10].toInt() and 0xFF shl 16) or
                    (data[9].toInt() and 0xFF shl 8) or
                    (data[8].toInt() and 0xFF)
        )
    }

    /**
     * Process a raw UDP packet. Returns a decoded Bitmap when a complete frame is ready.
     */
    fun processPacket(data: ByteArray): Bitmap? {
        if (data.size <= PAYLOAD_OFFSET) return null
        val header = parseHeader(data) ?: return null
        if (header.cmdType != CMD_VIDEO) return null

        val sequenceId = if (data.size > 48) data[48].toInt() and 0xFF else return null
        val payload = data.copyOfRange(PAYLOAD_OFFSET, data.size)
        var completedFrame: Bitmap? = null

        // Frame ID change means previous frame is complete
        if (currentFrameId != null && currentFrameId != header.frameId) {
            completedFrame = emitFrame()
        }

        currentFrameId = header.frameId
        currentFrameType = header.frameType

        if (sequenceId == 1) {
            if (payload.size >= 2 && payload[0] == JPEG_SOI[0] && payload[1] == JPEG_SOI[1]) {
                frameBuffer = payload
            } else {
                frameBuffer = null // Invalid first packet
            }
        } else if (sequenceId > 1 && frameBuffer != null) {
            frameBuffer = frameBuffer!! + payload
        }

        return completedFrame
    }

    /** Flush any pending frame (call when stream ends). */
    fun flush(): Bitmap? = emitFrame()

    private fun emitFrame(): Bitmap? {
        val buf = frameBuffer ?: return null
        frameBuffer = null
        val decoded = deobfuscate(buf, currentFrameId ?: 0, currentFrameType)
        return try {
            BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
        } catch (_: Exception) {
            null
        }
    }
}

/** Calculate obfuscation index (from liblewei line 8718). */
fun encodeIndex(frameId: Int, length: Int): Int {
    if (length == 0) return 0
    return if (length and 1 == 0) {
        (length + 1 + (length xor frameId) xor length) % length
    } else {
        ((length xor frameId) + length xor length) % length
    }
}

/** VGA deobfuscation — flips one byte when frame_type != 0x02. */
fun deobfuscate(data: ByteArray, frameId: Int, frameType: Int): ByteArray {
    if (frameType == 0x02 || data.isEmpty()) return data
    val result = data.copyOf()
    val index = encodeIndex(frameId, data.size)
    if (index in result.indices) result[index] = (result[index].toInt().inv() and 0xFF).toByte()
    return result
}
