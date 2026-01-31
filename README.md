# NDKarte
High Performance Navigation System

A native Android navigation application built with C/C++ using the Android NDK.

## Target Specifications

- **Platform**: Android 14+ (API 34)
- **Language**: C/C++ (NDK)
- **Resolution**: 1920x1200 (landscape)
- **Graphics**: OpenGL ES 3.0

## Project Structure

```
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

## Building

### Requirements

- Android Studio (or command line tools)
- Android SDK (API 34)
- Android NDK
- CMake 3.22.1+

### Build Command

```bash
./gradlew assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`

## Architecture

The app uses `NativeActivity` for a pure C/C++ implementation without Java code:

1. **main.c** - Entry point with EGL/OpenGL ES setup
2. **Event handling** - Touch input and lifecycle management
3. **Render loop** - Frame rendering with OpenGL ES 3.0

## Development Status

- [x] Project structure setup
- [x] NativeActivity configuration
- [x] EGL/OpenGL ES 3.0 context
- [x] Basic input handling
- [ ] Map rendering
- [ ] Navigation features
