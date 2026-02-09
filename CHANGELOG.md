# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com),
and this project adheres to [Semantic Versioning](https://semver.org).

## [Unreleased]

### Added

- Offline MBTiles vector tile rendering via MapLibre
- Map style with road hierarchy, water, landcover, buildings, and place labels
- MBTiles file discovery from app-private storage
- Immersive fullscreen mode with keep-screen-on for navigation use
- Map gesture configuration (pan, zoom, rotate, tilt)
- GPX 1.1 file parsing in Rust (tracks, routes, waypoints)
- GPX track/route/waypoint rendering as MapLibre overlay layers
- Automatic GPX file loading from app-private storage
- GPS location provider with runtime permission handling
- Track navigation with nearest-point projection (Rust nav module)
- Drag-line rendering from rider position to nearest track point
- Route/track conversion with Ramer-Douglas-Peucker simplification
- Text-to-speech guidance (off-track warnings, arrival announcements)
- JNI bindings for projectOnTrack, trackToRoute, routeToTrack
- Google Drive sync for GPX files (OAuth 2.0 via Google Sign-In)
- Sync state metadata tracking (JSON file, upload/download delta sync)

## [0.1.0] - 2026-02-08

### Added

- Android project scaffold with Kotlin UI layer
- MapLibre Android SDK integration for map rendering
- Rust core library with JNI bridge to Kotlin
- Gradle custom task for Rust cross-compilation (arm64-v8a, x86_64)
- GitHub Actions CI pipeline with Rust and Android toolchains
