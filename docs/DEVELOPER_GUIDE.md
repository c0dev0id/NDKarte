# Developer Guide

## Technology Stack

- **Kotlin** -- Android UI layer ([Kotlin docs](https://kotlinlang.org/docs/home.html))
- **Rust** -- Core business logic ([Rust book](https://doc.rust-lang.org/book/))
- **MapLibre Android SDK** -- Map rendering ([MapLibre docs](https://maplibre.org/maplibre-native/android/))
- **Gradle (Kotlin DSL)** -- Build system ([Gradle docs](https://docs.gradle.org/current/userguide/userguide.html))
- **cargo-ndk** -- Rust to Android cross-compilation ([cargo-ndk](https://github.com/nickel-org/cargo-ndk))

## Prerequisites

- JDK 17
- Android SDK (API 34)
- Android NDK 26.1.10909125
- Rust toolchain (`rustup`)
- Android cross-compilation targets: `rustup target add aarch64-linux-android x86_64-linux-android`
- `cargo-ndk`: `cargo install cargo-ndk`

## Building

```sh
./gradlew assembleDebug
```

This runs the `buildRust` task automatically, then compiles the Kotlin
code and packages the APK.

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Project Structure

### app/

| File | Purpose |
|------|---------|
| `build.gradle.kts` | App module config, MapLibre dependency, Rust build task |
| `src/main/AndroidManifest.xml` | App config: landscape, fullscreen, permissions |
| `src/main/java/com/ndkarte/app/MainActivity.kt` | Activity lifecycle, hosts MapView |
| `src/main/java/com/ndkarte/app/MapManager.kt` | MapLibre setup, offline MBTiles, camera, layers |
| `src/main/java/com/ndkarte/app/RustBridge.kt` | JNI declarations for rust-core |
| `src/main/assets/styles/offline.json` | MapLibre style template for offline MBTiles rendering |
| `src/main/jniLibs/` | Compiled `.so` files from Rust (gitignored, built by Gradle) |
| `src/main/res/` | Android resources (layouts, strings, icons) |

### rust-core/

| File | Purpose |
|------|---------|
| `Cargo.toml` | Rust project config, dependencies |
| `src/lib.rs` | Library root, module declarations |
| `src/android_jni.rs` | JNI function implementations matching RustBridge.kt |

## Adding a New JNI Function

1. Declare `external fun` in `RustBridge.kt`
2. Implement the corresponding JNI function in `rust-core/src/android_jni.rs`
   following the naming convention `Java_com_ndkarte_app_RustBridge_<method>`
3. Rebuild with `./gradlew assembleDebug`

## Offline Map Tiles

The app uses MBTiles files for offline vector tile rendering. To load
map tiles on a device:

1. Place an OpenMapTiles-schema `.mbtiles` file into the app's internal
   storage at `files/maps/` (e.g. via `adb push`)
2. The app picks up the first `.mbtiles` file it finds on startup
3. The style template `assets/styles/offline.json` defines rendering
   layers for water, landcover, roads, buildings, and place labels

Without an MBTiles file, the app shows an empty background.

## Adding a New Rust Module

1. Create `rust-core/src/<module>.rs`
2. Add `pub mod <module>;` in `rust-core/src/lib.rs`
3. Expose functions to Android through `android_jni.rs` if needed
