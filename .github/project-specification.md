# NDKarte Project Specification

High Performance Navigation System

**Target Audience**
- Motocycle Riders

**Target Features**
- Track Navigation (with drag-line support)
- Route Navigation (with Turn By Turn Instructions)
- Route Editor
- Route to Track conversion
- Track to Route conversion
- Custom Waypoint Icons (GPX 1.1 extension)

**Target Qualities**
- Fluid "Game-Like" Interface
- Maintain high frame rates
- Handle UI asynchronously (maintain fluid UI movements on slow devices)
- Use GPU capabilities where possible

## Target Device Specifications

- **Platform**: Android 14+ (API 34)
- **Resolution**: 1920x1200 (landscape)
- **Graphics**: OpenGL ES 3.0
- **Performance**: Low End Device
- **Memory**: 4GB

## Target Technology Stack

- Map Rendering:
  - MapLibre Android SDK (Java/Kotlin bindings)
  - Handles: Tile rendering, gestures, camera, OpenGL ES integration
- Core Business Logic:
  - Rust (via Android NDK)
  - Handles: GPX parsing, route calculations, sync logic, map matching
- UI Layer:
  - Kotlin
  - Handles: Activity lifecycle, permissions, MapView integration, TTS
- Map Data Storage:
  - MBTiles (vector tiles)
  - Location: Internal storage
  - Format: SQLite database with tile blobs
  - Access: Direct file I/O from Rust or SDK's offline tile source
- Route/Track Storage:
  - GPX files
  - Format: GPX 1.1 (without extensions, except defined in this document)
  - Location: App-private storage
  - Parsing: gpx crate in Rust
- Sync Layer:
  - Google Drive REST API
  - Auth: OAuth 2.0 via Google Sign-In SDK
  - Transport: Upload/Download GPX files
  - Metadata: JSON file tracking sync state
- Build System
  - Gradle (Kotlin DSL)
    - Rust integration via Custom Task
    - Compiles: .so libraries from Rust -> bundles into APK
    - Dependencies: Maven Central for MapLibre, Google APIs

Note:
- The project will be built with Github Actions on Ubuntu
- A companion Application will be developed for OpenBSD (not in scope)
  - When choosing frameworks and libraries, prefer OpenBSD compatible options
  - The companion app should be able to reuse rust code in respect to:
    - Map Provider
    - Map Engine
    - Routing logic

## Target File Structure

```
NDKarte/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/yourapp/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── RustBridge.kt
│   │   │   │   └── MapManager.kt
│   │   │   ├── jniLibs/          # Rust .so files land here
│   │   │   │   ├── arm64-v8a/
│   │   │   │   │   └── libmyapp.so
│   │   │   │   └── x86_64/
│   │   │   │       └── libmyapp.so
│   │   │   └── AndroidManifest.xml
│   │   └── rust/                 # Rust source (optional location)
│   └── build.gradle.kts
└── rust-core/                    # Shared Rust library
    ├── src/
    │   ├── lib.rs
    │   ├── gpx.rs
    │   └── android_jni.rs        # Android JNI bindings
    └── Cargo.toml
NDKarte/
├── app/
│   └── src/main/
│       ├── cpp/
│       │   ├── CMakeLists.txt    # Native build configuration
│       │   └── main.c            # Application entry point
│       ├── res/                  # Android resources
│       └── AndroidManifest.xml   # App configuration
├── build.gradle.kts              # Root build script
└── settings.gradle.kts           # Gradle settings
```
