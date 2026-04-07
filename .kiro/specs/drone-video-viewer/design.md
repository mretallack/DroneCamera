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
│  - WiFi connection state                │
│  - Connection status                    │
└──────────────┬──────────────────────────┘
               │
          ┌────┴────┐
          ▼         ▼
┌──────────────┐  ┌──────────────────────┐
│ WifiConnector│  │   DroneConnection    │
│ - Auto-conn  │  │   - UDP socket mgmt  │
│ - Network    │  │   - Heartbeat sender │
│   binding    │  │   - Packet receiver  │
└──────────────┘  └──────────┬───────────┘
                             │
                             ▼
                  ┌──────────────────────┐
                  │   FrameAssembler     │
                  │   - Header parsing   │
                  │   - Frame assembly   │
                  │   - Deobfuscation    │
                  │   - JPEG → Bitmap    │
                  └──────────────────────┘
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
enum class StreamState { IDLE, CONNECTING_WIFI, CONNECTING_DRONE, STREAMING, ERROR }

class DroneStreamViewModel : ViewModel() {
    val streamState: StateFlow<StreamState>
    val currentFrame: StateFlow<Bitmap?>
    
    fun connectWifi(context: Context)
    fun startStream()
    fun stopStream()
}
```

Launches coroutines for the connection and frame assembly on `Dispatchers.IO`.

### 3. WifiConnector

Handles automatic WiFi connection to the drone using `WifiNetworkSpecifier` (API 29+):

```kotlin
class WifiConnector(private val context: Context) {
    
    fun connect(onNetworkAvailable: (Network) -> Unit, onUnavailable: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectWithSpecifier(onNetworkAvailable, onUnavailable)
        } else {
            // API 26-28: check if already on drone WiFi, otherwise prompt user
            checkExistingConnection(onNetworkAvailable, onUnavailable)
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectWithSpecifier(
        onNetworkAvailable: (Network) -> Unit,
        onUnavailable: () -> Unit
    ) {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher("HASAKEE", PatternMatcher.PATTERN_PREFIX))
            .build()
        
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        
        connectivityManager.requestNetwork(request, object : NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkAvailable(network)
            override fun onUnavailable() = onUnavailable()
        })
    }
    
    fun disconnect() { /* unregister callback */ }
}
```

Key details:
- `removeCapability(NET_CAPABILITY_INTERNET)` — drone WiFi has no internet, this prevents Android from rejecting it
- The returned `Network` object must be used to bind the UDP socket: `network.bindSocket(socket)` — this ensures traffic goes over the drone WiFi even if the device has mobile data
- On API 26-28, falls back to checking if current WiFi SSID matches `HASAKEE*` pattern

### 4. DroneConnection

Handles all UDP communication with the drone:

```kotlin
class DroneConnection(private val network: Network?) {
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
- If `network` is provided (API 29+), calls `network.bindSocket(socket)` to route traffic over drone WiFi
- Sends heartbeat every 1 second via a coroutine
- Receives packets in a loop, emitting them as a `Flow<ByteArray>`
- Socket receive buffer set to 64KB

### 5. FrameAssembler

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

### 6. VGA Deobfuscation

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

## Sequence Diagram: Connect and Start Stream

```
User        MainActivity    ViewModel     WifiConnector   DroneConnection  FrameAssembler  Drone
 │               │              │              │               │               │            │
 │  App Launch   │              │              │               │               │            │
 │──────────────>│              │              │               │               │            │
 │               │ connectWifi()│              │               │               │            │
 │               │─────────────>│              │               │               │            │
 │               │              │  connect()   │               │               │            │
 │               │              │─────────────>│               │               │            │
 │               │              │              │ WifiNetworkSpecifier           │            │
 │               │              │              │ (system dialog on first use)   │            │
 │               │              │              │──────────┐    │               │            │
 │               │              │              │          │    │               │            │
 │               │              │              │<─────────┘    │               │            │
 │               │              │  onAvailable(network)        │               │            │
 │               │              │<─────────────│               │               │            │
 │               │              │              │               │               │            │
 │  Tap Start    │              │              │               │               │            │
 │──────────────>│ startStream()│              │               │               │            │
 │               │─────────────>│              │               │               │            │
 │               │              │  connect(network)            │               │            │
 │               │              │─────────────────────────────>│               │            │
 │               │              │              │               │  Heartbeat     │            │
 │               │              │              │               │───────────────────────────>│
 │               │              │              │               │  Video packets │            │
 │               │              │              │               │<──────────────────────────│
 │               │              │  Flow<ByteArray>             │               │            │
 │               │              │<─────────────────────────────│               │            │
 │               │              │  processPacket()             │               │            │
 │               │              │─────────────────────────────────────────────>│            │
 │               │              │                              │    Bitmap?    │            │
 │               │              │<─────────────────────────────────────────────│            │
 │               │  StateFlow   │              │               │               │            │
 │               │<─────────────│              │               │               │            │
 │  Render frame │              │              │               │               │            │
 │<──────────────│              │              │               │               │            │
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
| API 29+, no drone WiFi found | Show "No drone network found" with retry |
| API 29+, user denies network | Show prompt explaining why drone WiFi is needed |
| API 26-28, not on drone WiFi | Show manual connection instructions |
| No response from drone | Timeout after 5s, show retry button |
| Corrupt JPEG frame | Skip frame, keep displaying previous good frame |
| Socket error | Transition to ERROR state, allow retry |
| App backgrounded | Stop stream, release socket, release WiFi network |

## Project Structure

```
app/
├── src/main/
│   ├── java/com/dronecamera/
│   │   ├── MainActivity.kt
│   │   ├── DroneStreamViewModel.kt
│   │   ├── WifiConnector.kt
│   │   ├── DroneConnection.kt
│   │   └── FrameAssembler.kt
│   ├── res/
│   │   ├── layout/activity_main.xml
│   │   └── values/strings.xml
│   └── AndroidManifest.xml
├── src/test/
│   └── java/com/dronecamera/
│       ├── FrameAssemblerTest.kt
│       ├── DroneConnectionTest.kt
│       └── DroneStreamViewModelTest.kt
├── src/androidTest/
│   └── java/com/dronecamera/
│       ├── WifiConnectorTest.kt
│       └── MainActivityTest.kt
├── build.gradle.kts
└── gradle/
```

## Testing Strategy

### Unit Tests (`src/test/`) — JUnit 5 + MockK

Unit tests run on the JVM without an Android device. All components with Android dependencies are mocked.

#### FrameAssemblerTest

The core protocol logic — most critical to test since it's a direct port of the reverse-engineered protocol.

- Parse valid 0x6363 packet header and extract all fields correctly
- Reject packets with invalid magic bytes (not `0x6363`)
- Reject packets shorter than minimum header length (12 bytes)
- Assemble a complete frame from multiple sequential packets
- Start new frame when `frame_id` changes (previous frame emitted)
- First packet (`sequence_id == 1`) must start with JPEG SOI (`0xFFD8`)
- Reject first packet that doesn't start with JPEG SOI
- Append subsequent packets (`sequence_id > 1`) to current frame
- Apply VGA deobfuscation when `frame_type != 0x02`
- Skip deobfuscation when `frame_type == 0x02`
- `encodeIndex` returns correct index for even-length data
- `encodeIndex` returns correct index for odd-length data
- `encodeIndex` returns 0 for zero-length data
- Return null for corrupt JPEG data that fails decode

#### DroneConnectionTest

- Heartbeat packet matches expected bytes (`63 63 01 00 00 00 00`)
- Emits timeout signal when no packets received for 3 seconds
- Disconnect closes socket and stops heartbeat coroutine

#### DroneStreamViewModelTest

- State transitions: IDLE → CONNECTING_WIFI → CONNECTING_DRONE → STREAMING
- State transitions to ERROR on WiFi connection failure
- State transitions to ERROR on drone connection timeout
- stopStream returns state to IDLE
- currentFrame updates when FrameAssembler produces a Bitmap
- currentFrame retains previous frame when assembler returns null

### Integration Tests (`src/androidTest/`) — AndroidX Test + Espresso

Integration tests run on a real device or emulator and verify Android-specific behaviour.

#### WifiConnectorTest

- On API 29+: `WifiNetworkSpecifier` is built with correct SSID prefix pattern
- On API 29+: `NetworkRequest` removes `NET_CAPABILITY_INTERNET`
- On API 26-28: falls back to checking current WiFi SSID
- Disconnect unregisters the network callback

#### MainActivityTest

- Start button is disabled before WiFi connection
- Start button is enabled after WiFi connection
- Tapping Start transitions UI to streaming state
- Tapping Stop transitions UI back to idle state
- Connection lost warning appears when stream times out
- Screen stays on while streaming (keep screen on flag)

### Test Resources

- Android phone available for on-device testing (unit tests, integration tests, manual testing)
- HASAKEE FPV drone is not currently available but can be requested for end-to-end testing
- Unit tests and integration tests can run without the drone (mocked protocol data)
- Full end-to-end testing (WiFi auto-connect → video stream) requires the physical drone

### Test Dependencies

```kotlin
// build.gradle.kts
testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
testImplementation("io.mockk:mockk:1.13.10")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
androidTestImplementation("androidx.test.ext:junit:1.1.5")
androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
androidTestImplementation("androidx.test:runner:1.5.2")
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
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
```

## Build Configuration

- Language: Kotlin
- Min SDK: 26 (Android 8.0)
- Target SDK: 35
- Build system: Gradle with Kotlin DSL
