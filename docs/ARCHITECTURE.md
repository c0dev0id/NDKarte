# Architecture

NDKarte is a native Android navigation app built with three layers:
a Kotlin UI shell, a Rust core library, and MapLibre for map rendering.

## Component Overview

```
+---------------------------------------------------+
|                  Android App                       |
|                                                    |
|  +-------------+   +------------+   +-----------+  |
|  | MainActivity|-->| MapManager |-->| MapLibre  |  |
|  +-------------+   +------------+   | MapView   |  |
|        |                            +-----------+  |
|        v                                           |
|  +-------------+                                   |
|  | RustBridge  |  (JNI)                            |
|  +------+------+                                   |
+---------|-----------------------------------------+
          v
+---------------------------------------------------+
|              rust-core (libndkarte.so)             |
|                                                    |
|  +-------------+  +----------+  +--------------+   |
|  | android_jni |  | gpx      |  | (future:     |   |
|  | (JNI entry) |  | (parser) |  |  routing,    |   |
|  +-------------+  +----------+  |  sync, map   |   |
|                                 |  matching)   |   |
|                                 +--------------+   |
+---------------------------------------------------+
```

## Layer Responsibilities

### Kotlin UI Layer

`MainActivity` owns the Android lifecycle and hosts the MapLibre `MapView`.
`MapManager` encapsulates all map interaction (camera, gestures, overlays).
`RustBridge` provides JNI declarations that load and call into `libndkarte.so`.

The Kotlin layer is intentionally thin. It handles what requires Android
SDK access: activity lifecycle, permissions, text-to-speech, and view
management.

### Rust Core (rust-core/)

All business logic lives here: GPX parsing, route calculations, sync
logic, and map matching. The library exposes a platform-agnostic API
through Rust traits and structs. The `android_jni` module wraps this
API for Android; a future OpenBSD companion app will use the same core
through a different frontend.

### MapLibre

Handles tile rendering, camera control, gestures, and OpenGL ES
integration. Configured for offline-only operation:

- Vector tiles loaded from local MBTiles files via `mbtiles://` URI scheme
- Style JSON template in `assets/styles/offline.json` with OpenMapTiles
  layer definitions (water, landcover, roads, buildings, places)
- MBTiles files are read from the app's `files/maps/` directory
- No network tile fetching — all sources are local
- Falls back to an empty background style when no tiles are available

## Data Flow

1. GPX files are parsed by `rust-core` and returned to Kotlin as
   structured data
2. Kotlin passes track/route geometries to `MapManager` for rendering
   as MapLibre layers
3. Navigation state (position on track, next turn) is computed in Rust
   and pushed to the UI via JNI callbacks

## Build Pipeline

Gradle orchestrates the full build:

1. A custom `buildRust` task invokes `cargo ndk` to cross-compile
   `rust-core` for `arm64-v8a` and `x86_64`
2. The resulting `.so` files are placed in `app/src/main/jniLibs/`
3. The standard Android build bundles them into the APK
