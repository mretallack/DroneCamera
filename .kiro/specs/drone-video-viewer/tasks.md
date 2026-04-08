# Tasks: Drone Video Viewer Android App

## Task 1: Android Project Scaffolding

Set up the Gradle project structure with all dependencies and configuration.

- [x] 1.1 Create Android project with `app/build.gradle.kts` (Kotlin, min SDK 26, target SDK 35)
- [x] 1.2 Create root `build.gradle.kts` and `settings.gradle.kts`
- [x] 1.3 Add Gradle wrapper files (`gradle/wrapper/`)
- [x] 1.4 Add dependencies: Kotlin Coroutines, AndroidX Lifecycle, JUnit 5, MockK, Espresso
- [x] 1.5 Create `AndroidManifest.xml` with permissions (INTERNET, ACCESS_WIFI_STATE, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE)
- [x] 1.6 Verify project builds with `./gradlew assembleDebug`

## Task 2: FrameAssembler — Protocol Core

Implement the 0x6363 packet parser, frame assembler, and VGA deobfuscation. This is pure Kotlin with no Android dependencies.

- [x] 2.1 Create `FrameAssembler.kt` with packet header parsing (magic bytes, cmd_type, seq_id, pkt_len, frame_type, frame_id)
- [x] 2.2 Implement multi-packet frame assembly (track frame_id, accumulate payloads from offset 54, emit on frame_id change)
- [x] 2.3 Implement `encodeIndex()` and `deobfuscate()` for VGA deobfuscation (frame_type != 0x02)
- [x] 2.4 Add JPEG decode via `BitmapFactory.decodeByteArray()`, return null on failure

## Task 3: FrameAssembler Unit Tests

- [x] 3.1 Create `FrameAssemblerTest.kt` with tests for header parsing (valid packet, invalid magic, short packet)
- [x] 3.2 Add tests for frame assembly (multi-packet assembly, frame_id change emits frame, JPEG SOI validation)
- [x] 3.3 Add tests for deobfuscation (`encodeIndex` even/odd/zero, deobfuscate skips frame_type 0x02, applies for other types)
- [x] 3.4 Verify tests pass with `./gradlew test`

## Task 4: DroneConnection — UDP Communication

- [x] 4.1 Create `DroneConnection.kt` with `DatagramSocket` setup (ephemeral port, 64KB receive buffer)
- [x] 4.2 Implement `network.bindSocket(socket)` when Network is provided (API 29+)
- [x] 4.3 Implement heartbeat sender coroutine (sends `63 63 01 00 00 00 00` to `192.168.0.1:40000` every 1s)
- [x] 4.4 Implement packet receiver loop emitting `Flow<ByteArray>`
- [x] 4.5 Implement `disconnect()` — close socket, cancel coroutines
- [x] 4.6 Implement connection loss detection (no packets for 3s → timeout signal)

## Task 5: DroneConnection Unit Tests

- [x] 5.1 Create `DroneConnectionTest.kt` — verify heartbeat packet bytes, timeout emission, disconnect cleanup
- [x] 5.2 Verify tests pass with `./gradlew test`

## Task 6: WifiConnector — Auto-Connect

- [x] 6.1 Create `WifiConnector.kt` with API 29+ path using `WifiNetworkSpecifier` (SSID prefix `HASAKEE`, remove NET_CAPABILITY_INTERNET)
- [x] 6.2 Implement API 26-28 fallback (check current WiFi SSID, prompt user if not on drone network)
- [x] 6.3 Implement `disconnect()` to unregister network callback

## Task 7: WifiConnector Integration Tests

- [x] 7.1 Create `WifiConnectorTest.kt` (androidTest) — verify specifier SSID pattern, NetworkRequest capabilities, fallback behaviour, disconnect cleanup
- [ ] 7.2 Verify tests pass with `./gradlew connectedAndroidTest`

## Task 8: DroneStreamViewModel

- [x] 8.1 Create `DroneStreamViewModel.kt` with `StreamState` enum (IDLE, CONNECTING_WIFI, CONNECTING_DRONE, STREAMING, ERROR)
- [x] 8.2 Implement `connectWifi()` — uses WifiConnector, transitions IDLE → CONNECTING_WIFI → ready
- [x] 8.3 Implement `startStream()` — creates DroneConnection with Network, collects packets through FrameAssembler, updates `currentFrame` StateFlow
- [x] 8.4 Implement `stopStream()` — disconnects DroneConnection and WifiConnector, transitions to IDLE
- [x] 8.5 Handle errors — WiFi failure, drone timeout, socket errors → ERROR state

## Task 9: ViewModel Unit Tests

- [x] 9.1 Create `DroneStreamViewModelTest.kt` — state transitions, error handling, frame updates, previous frame retention on null
- [x] 9.2 Verify tests pass with `./gradlew test`

## Task 10: MainActivity UI

- [x] 10.1 Create `activity_main.xml` layout — SurfaceView (fullscreen), Start/Stop button, status text overlay
- [x] 10.2 Create `strings.xml` with UI strings
- [x] 10.3 Create `MainActivity.kt` — bind to ViewModel, observe StreamState to update UI, observe currentFrame to render to SurfaceView
- [x] 10.4 Implement `renderFrame()` using `SurfaceHolder.lockCanvas()` / `unlockCanvasAndPost()`
- [x] 10.5 Wire Start/Stop button — disabled until WiFi connected, toggles stream
- [x] 10.6 Display connection status overlay (Connecting WiFi / Connecting Drone / Connected / Connection Lost)
- [x] 10.7 Keep screen on while streaming, stop stream on `onStop()`

## Task 11: MainActivity Integration Tests

- [x] 11.1 Create `MainActivityTest.kt` (androidTest) — button state, UI transitions, screen-on flag
- [ ] 11.2 Verify tests pass with `./gradlew connectedAndroidTest`

## Task 12: Build, Test, and Push

- [x] 12.1 Run full test suite (`./gradlew test connectedAndroidTest`)
- [x] 12.2 Build release APK (`./gradlew assembleRelease`)
- [x] 12.3 Install on test phone and verify app launches, WiFi auto-connect dialog appears
- [x] 12.4 (When drone available) End-to-end test: WiFi connect → start stream → view video → stop stream
