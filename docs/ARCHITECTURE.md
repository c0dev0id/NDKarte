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
|  +-----------------+  +------------------+         |
|  | NavigationMgr   |  | LocationProvider |         |
|  | (drag-line, TTS)|  | (GPS updates)    |         |
|  +--------+--------+  +------------------+         |
|           |                                        |
|           v            +------------------+        |
|  +-------------+       | SyncManager      |        |
|  | RustBridge  | (JNI) | (Google Drive)   |        |
|  +------+------+       +------------------+        |
+---------|-----------------------------------------+
          v
+---------------------------------------------------+
|              rust-core (libndkarte.so)             |
|                                                    |
|  +-------------+  +----------+  +----------+       |
|  | android_jni |  | gpx      |  | nav      |       |
|  | (JNI entry) |  | (parser) |  | (project)|       |
|  +-------------+  +----------+  +----------+       |
|                   +----------+  +--------------+   |
|                   | convert  |  | (future:     |   |
|                   | (rdp)    |  |  sync, map   |   |
|                   +----------+  |  matching)   |   |
|                                 +--------------+   |
+---------------------------------------------------+
```

## Layer Responsibilities

### Kotlin UI Layer

`MainActivity` owns the Android lifecycle and hosts the MapLibre `MapView`.
`MapManager` encapsulates all map interaction (camera, gestures, overlays).
`NavigationManager` ties GPS positioning to track projection and renders
the drag-line overlay. It also manages TTS voice guidance for off-track
warnings and arrival announcements.
`LocationProvider` wraps Android `LocationManager` for GPS updates.
`SyncManager` handles Google Drive integration: OAuth 2.0 sign-in, GPX
file upload/download, and sync state tracking via a local JSON metadata file.
`RustBridge` provides JNI declarations that load and call into `libndkarte.so`.

The Kotlin layer is intentionally thin. It handles what requires Android
SDK access: activity lifecycle, permissions, text-to-speech, Google APIs,
and view management.

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

### GPX File Loading

1. `MainActivity` scans `files/gpx/` for `.gpx` files
2. File bytes are passed to `RustBridge.parseGpx()` (JNI call)
3. Rust `gpx` crate parses the XML and returns JSON via JNI
4. `GpxData.fromJson()` deserializes into Kotlin data classes
5. `MapManager.showGpxData()` converts to GeoJSON and adds MapLibre
   layers (blue polylines for tracks, orange dashed for routes,
   red circles for waypoints)
6. Camera is adjusted to fit the GPX bounds

### Track Navigation

1. `LocationProvider` delivers GPS position updates (1 Hz, 5 m filter)
2. `NavigationManager.onLocationUpdate()` serializes the active track
   points to JSON and calls `RustBridge.projectOnTrack()`
3. Rust `nav::project_on_track()` finds the nearest point on the track
   using segment projection with latitude-cosine planar approximation
4. The result (projected point, segment index, distance, distance along)
   is returned as JSON and parsed on the Kotlin side
5. `NavigationManager` updates three MapLibre layers:
   - Rider position (blue circle)
   - Drag-line (dashed red line from rider to projected point)
   - Projected point (white circle on track)
6. TTS announces off-track warnings (>100 m, >500 m) and arrival

### Route/Track Conversion

- `RustBridge.trackToRoute()` simplifies a track to sparse waypoints
  using Ramer-Douglas-Peucker with configurable tolerance
- `RustBridge.routeToTrack()` copies route waypoints as track points

### Google Drive Sync

1. `SyncManager.initialize()` sets up Google Sign-In client and attempts
   silent sign-in using the last signed-in account
2. When signed in, `sync()` runs on a background thread:
   - Creates/finds a `NDKarte` folder in the user's Drive
   - Lists remote `.gpx` files and compares with local `files/gpx/`
   - Downloads new/updated remote files; uploads new/updated local files
   - Updates `sync_state.json` with file IDs and modification timestamps
3. After sync, `MainActivity` reloads GPX files if any were downloaded

## Build Pipeline

Gradle orchestrates the full build:

1. A custom `buildRust` task invokes `cargo ndk` to cross-compile
   `rust-core` for `arm64-v8a` and `x86_64`
2. The resulting `.so` files are placed in `app/src/main/jniLibs/`
3. The standard Android build bundles them into the APK
