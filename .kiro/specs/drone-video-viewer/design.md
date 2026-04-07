# Design: Drone Video Viewer Android App

## Architecture Overview

Native Android app (Kotlin) using a single-activity architecture. The app communicates with the drone over UDP using the proprietary 0x6363 protocol and renders assembled JPEG frames to a `SurfaceView`.

```
┌─────────────────────────────────────────┐
│              MainActivity               │
│  ┌─────────────┐  ┌──────────────────┐  │
│  │ SurfaceView │  │ Status Overlay   │  │
│  │ (video)     │  │ (connection/fps) │  │
│  └─────────────┘  └──────────────────┘  │
│  ┌──────────────────────────────────┐   │
│  │  Start/Stop Button               │   │
│  └──────────────────────────────────┘   │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│          DroneStreamViewModel           │
│  - Stream state (idle/connecting/live)  │
│  - Connection status                    │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│          DroneConnection                │
│  - UDP socket management                │
│  - Heartbeat sender (1s interval)       │
│  - Packet receiver                      │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│          FrameAssembler                 │
│  - Packet header parsing (0x6363)       │
│  - Multi-packet frame assembly          │
│  - VGA deobfuscation                    │
│  - JPEG decode → Bitmap                 │
└─────────────────────────────────────────┘
```

## Components

### 1. MainActivity

Single activity with a simple layout:
- `SurfaceView` for rendering video frames (efficient, hardware-accelerated)
- Start/Stop toggle button
- Status text overlay (connection state, FPS)

Uses `DroneStreamViewModel` to survive configuration changes.

### 2. DroneStreamViewModel

Manages stream lifecycle and exposes state via `StateFlow`:

```kotlin
enum class StreamState { IDLE, CONNECTING, STREAMING, ERROR }

class DroneStreamViewModel : ViewModel() {
    val streamState: StateFlow<StreamState>
    val currentFrame: StateFlow<Bitmap?>
    
    fun startStream()
    fun stopStream()
}
```

Launches coroutines for the connection and frame assembly on `Dispatchers.IO`.

### 3. DroneConnection

Handles all UDP communication with the drone:

```kotlin
class DroneConnection {
    companion object {
        const val DRONE_IP = "192.168.0.1"
        const val DRONE_PORT = 40000
        const val HEARTBEAT_INTERVAL_MS = 1000L
        val HEARTBEAT_PACKET = byteArrayOf(0x63, 0x63, 0x01, 0x00, 0x00, 0x00, 0x00)
    }
    
    suspend fun connect(): Flow<ByteArray>  // Emits raw UDP packets
    fun disconnect()
}
```

- Binds a `DatagramSocket` on an ephemeral port
- Sends heartbeat every 1 second via a coroutine
- Receives packets in a loop, emitting them as a `Flow<ByteArray>`
- Socket receive buffer set to 64KB

### 4. FrameAssembler

Parses 0x6363 packets and assembles complete JPEG frames:

```kotlin
class FrameAssembler {
    fun processPacket(data: ByteArray): Bitmap?
}
```

**Assembly logic** (mirrors the Python implementation):

1. Parse header: verify `0x6363` magic, extract `cmd_type`, `frame_id`, `sequence_id`, `frame_type`
2. Track current `frame_id` — when it changes, the previous frame is complete
3. For `sequence_id == 1`: start new frame buffer (payload starts at offset 54, must begin with `0xFFD8`)
4. For `sequence_id > 1`: append payload to current frame buffer
5. On frame completion:
   - If `frame_type != 0x02`: apply VGA deobfuscation (XOR bit-flip at calculated index)
   - Decode JPEG bytes to `Bitmap` via `BitmapFactory.decodeByteArray()`
6. If decode fails, return null (ViewModel falls back to previous frame)

### 5. VGA Deobfuscation

Direct port of the Python/native implementation:

```kotlin
fun deobfuscate(data: ByteArray, frameId: Int, frameType: Int): ByteArray {
    if (frameType == 0x02 || data.isEmpty()) return data
    val result = data.copyOf()
    val index = encodeIndex(frameId, data.size)
    if (index in data.indices) result[index] = result[index].inv()
    return result
}

fun encodeIndex(frameId: Int, length: Int): Int {
    if (length == 0) return 0
    return if (length and 1 == 0) {
        (length + 1 + (length xor frameId) xor length) % length
    } else {
        ((length xor frameId) + length xor length) % length
    }
}
```

## Sequence Diagram: Start Stream

```
User          MainActivity    ViewModel      DroneConnection    FrameAssembler    Drone
 │                │               │               │                  │              │
 │  Tap Start     │               │               │                  │              │
 │───────────────>│               │               │                  │              │
 │                │ startStream() │               │                  │              │
 │                │──────────────>│               │                  │              │
 │                │               │  connect()    │                  │              │
 │                │               │──────────────>│                  │              │
 │                │               │               │  Heartbeat (UDP) │              │
 │                │               │               │─────────────────────────────────>│
 │                │               │               │                  │              │
 │                │               │               │  Video packets   │              │
 │                │               │               │<─────────────────────────────────│
 │                │               │               │                  │              │
 │                │               │  Flow<ByteArray>                 │              │
 │                │               │<──────────────│                  │              │
 │                │               │               │                  │              │
 │                │               │  processPacket()                 │              │
 │                │               │─────────────────────────────────>│              │
 │                │               │                    Bitmap?       │              │
 │                │               │<─────────────────────────────────│              │
 │                │               │               │                  │              │
 │                │  StateFlow<Bitmap>            │                  │              │
 │                │<──────────────│               │                  │              │
 │                │               │               │                  │              │
 │  Render frame  │               │               │                  │              │
 │<───────────────│               │               │                  │              │
```

## Rendering Strategy

Frames are rendered to `SurfaceView` using `Canvas`:

```kotlin
// In MainActivity, collecting frames from ViewModel
lifecycleScope.launch {
    viewModel.currentFrame.collect { bitmap ->
        bitmap?.let { renderFrame(it) }
    }
}

fun renderFrame(bitmap: Bitmap) {
    val holder = surfaceView.holder
    val canvas = holder.lockCanvas() ?: return
    canvas.drawBitmap(bitmap, null, canvas.clipBounds, null)
    holder.unlockCanvasAndPost(canvas)
}
```

## Connection Loss Detection

- `DroneConnection` tracks timestamp of last received packet
- If no packet received for 3 seconds, emits a timeout signal
- ViewModel transitions to `ERROR` state, UI shows "Connection Lost"
- Heartbeats continue — if drone responds again, stream resumes automatically

## Error Handling

| Scenario | Handling |
|----------|----------|
| Not on WiFi | Show prompt to connect to drone WiFi |
| No response from drone | Timeout after 5s, show retry button |
| Corrupt JPEG frame | Skip frame, keep displaying previous good frame |
| Socket error | Transition to ERROR state, allow retry |
| App backgrounded | Stop stream, release socket |

## Project Structure

```
app/
├── src/main/
│   ├── java/com/dronecamera/
│   │   ├── MainActivity.kt
│   │   ├── DroneStreamViewModel.kt
│   │   ├── DroneConnection.kt
│   │   └── FrameAssembler.kt
│   ├── res/
│   │   ├── layout/activity_main.xml
│   │   └── values/strings.xml
│   └── AndroidManifest.xml
├── build.gradle.kts
└── gradle/
```

## Dependencies

- Kotlin Coroutines (for async UDP I/O)
- AndroidX Lifecycle (ViewModel, lifecycleScope)
- No third-party networking or image libraries needed — standard `DatagramSocket` and `BitmapFactory` suffice

## Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Build Configuration

- Language: Kotlin
- Min SDK: 26 (Android 8.0)
- Target SDK: 35
- Build system: Gradle with Kotlin DSL
