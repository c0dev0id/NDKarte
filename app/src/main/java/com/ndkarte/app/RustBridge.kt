package com.ndkarte.app

/**
 * JNI bridge to the Rust core library (libndkarte.so).
 *
 * All native method declarations live here. The Rust side implements
 * these via the jni crate in rust-core/src/android_jni.rs.
 */
object RustBridge {

    init {
        System.loadLibrary("ndkarte")
    }

    /** Returns the rust-core library version string. */
    external fun version(): String

    /**
     * Parse a GPX file from raw bytes.
     *
     * Returns a JSON string with the structure:
     * { "tracks": [...], "routes": [...], "waypoints": [...] }
     *
     * On failure returns: { "error": "description" }
     */
    external fun parseGpx(data: ByteArray): String
}
