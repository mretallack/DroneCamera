# Changelog

## [1.0.0] - 2026-04-08

### Added
- Real-time MJPEG video streaming from HASAKEE FPV drone
- Automatic WiFi connection to drone network (API 29+ WifiNetworkSpecifier, API 26-28 fallback)
- 0x6363 UDP protocol packet parser and multi-packet frame assembly
- VGA data deobfuscation support for older camera firmware
- Heartbeat keepalive to maintain video stream
- Connection loss detection (3 second timeout)
- Drone launcher icon
- GitHub Actions CI/CD pipeline (build, lint, test, signed release)
- Unit tests for FrameAssembler, DroneConnection, and ViewModel
- Integration tests for WifiConnector and MainActivity
