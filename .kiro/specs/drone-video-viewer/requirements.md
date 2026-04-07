# Requirements: Drone Video Viewer Android App

## Overview

An Android app to view the live MJPEG video feed from a HASAKEE FPV drone over WiFi. The drone uses a proprietary UDP protocol (0x6363) on port 40000. The app is view-only — no flight control functionality (the drone model does not support app-based control).

## User Stories

### US-1: Auto-Connect to Drone WiFi

As a user, I want the app to automatically connect to the drone's WiFi network so I don't have to manually switch networks.

**Acceptance Criteria:**
- WHEN the app launches on Android 10+ (API 29+) THE SYSTEM SHALL use `WifiNetworkSpecifier` to request connection to a network matching SSID prefix `HASAKEE`
- WHEN the system shows the network selection dialog THE SYSTEM SHALL display the matching drone WiFi network for user approval
- WHEN the user has previously approved the network THE SYSTEM SHALL auto-connect without showing the dialog again
- WHEN the app launches on Android 8-9 (API 26-28) THE SYSTEM SHALL display a prompt directing the user to manually connect to the drone's WiFi (typically `HASAKEE-WiFi-XXXXX`)
- WHEN the device is connected to the drone WiFi THE SYSTEM SHALL bind the UDP socket to the drone network (required for `WifiNetworkSpecifier` local-only connections)
- WHEN the WiFi connection is established THE SYSTEM SHALL enable the "Start Stream" button

### US-2: Start Video Stream

As a user, I want to start the video stream so that I can see what the drone camera sees.

**Acceptance Criteria:**
- WHEN the user taps "Start Stream" THE SYSTEM SHALL send a UDP heartbeat packet (`63 63 01 00 00 00 00`) to `192.168.0.1:40000`
- WHEN the stream is initializing THE SYSTEM SHALL continue sending heartbeat packets every 1 second
- WHEN the drone responds with video packets THE SYSTEM SHALL display the live video feed
- WHEN the stream fails to start within 5 seconds THE SYSTEM SHALL display a timeout error with a retry option

### US-3: View Live Video Feed

As a user, I want to see a smooth, real-time video feed from the drone camera.

**Acceptance Criteria:**
- WHEN video packets are received THE SYSTEM SHALL assemble multi-packet JPEG frames using frame_id and sequence_id
- WHEN a complete JPEG frame is assembled THE SYSTEM SHALL decode and display it in the video view
- WHEN a frame is partially corrupted THE SYSTEM SHALL blend with the previous good frame to minimize visual artifacts
- WHEN the frame_type field is not 0x02 THE SYSTEM SHALL apply VGA deobfuscation before decoding

### US-4: Stop Video Stream

As a user, I want to stop the video stream when I'm done viewing.

**Acceptance Criteria:**
- WHEN the user taps "Stop Stream" THE SYSTEM SHALL stop sending heartbeat packets
- WHEN the stream is stopped THE SYSTEM SHALL close the UDP socket and release resources
- WHEN the app is backgrounded or destroyed THE SYSTEM SHALL automatically stop the stream

### US-5: Connection Status Feedback

As a user, I want to know the current connection state so I understand if the feed is working.

**Acceptance Criteria:**
- WHEN the stream is active THE SYSTEM SHALL display a "Connected" indicator
- WHEN no packets are received for 3 seconds THE SYSTEM SHALL display a "Connection Lost" warning
- WHEN connection is lost THE SYSTEM SHALL continue sending heartbeats to attempt reconnection

## Non-Functional Requirements

### NFR-1: Performance
- WHEN displaying video THE SYSTEM SHALL maintain a frame rate matching the drone's output (typically 15-20 fps)
- WHEN assembling frames THE SYSTEM SHALL use a receive buffer of at least 64KB to avoid packet loss

### NFR-2: Battery Efficiency
- WHEN the stream is stopped THE SYSTEM SHALL release all network resources to minimize battery drain

### NFR-3: Compatibility
- THE SYSTEM SHALL target Android API 26 (Android 8.0) as minimum SDK
- THE SYSTEM SHALL support Android API 35 as target SDK

## Protocol Reference

| Field | Offset | Description |
|-------|--------|-------------|
| Header | 0x00-0x01 | `0x6363` |
| Command type | 0x02 | `0x01`=heartbeat, `0x03`=video |
| Sequence ID | 0x03-0x04 | Packet sequence (little-endian) |
| Packet length | 0x05-0x06 | Payload length (little-endian) |
| Frame type | 0x07 | `0x02`=unobfuscated |
| Frame ID | 0x08-0x0B | Frame identifier (little-endian) |
| JPEG data | 0x36 (54) | Starts with `0xFFD8` for first packet in frame |

## Out of Scope

- Flight control (drone model does not support app-based control)
- Telemetry display (port 50000 is closed on this drone model)
- Video recording to file
- HD camera protocol support
