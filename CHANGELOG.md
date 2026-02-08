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

## [0.1.0] - 2026-02-08

### Added

- Android project scaffold with Kotlin UI layer
- MapLibre Android SDK integration for map rendering
- Rust core library with JNI bridge to Kotlin
- Gradle custom task for Rust cross-compilation (arm64-v8a, x86_64)
- GitHub Actions CI pipeline with Rust and Android toolchains
