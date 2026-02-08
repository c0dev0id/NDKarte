//! JNI bindings for the Android app.
//!
//! Each public function here corresponds to an `external fun` declaration
//! in RustBridge.kt. The function names follow JNI naming conventions:
//! Java_<package>_<class>_<method> with dots replaced by underscores.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::jstring;

/// Returns the rust-core library version.
/// Maps to: RustBridge.version() -> String
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ndkarte_app_RustBridge_version(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let version = crate::VERSION;
    env.new_string(version)
        .expect("failed to create Java string")
        .into_raw()
}

/// Parse a GPX file from raw bytes and return JSON.
///
/// Maps to: RustBridge.parseGpx(data: ByteArray) -> String
///
/// Returns a JSON string with the structure:
/// { "tracks": [...], "routes": [...], "waypoints": [...] }
///
/// On parse failure, returns a JSON error object:
/// { "error": "description" }
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ndkarte_app_RustBridge_parseGpx(
    env: JNIEnv,
    _class: JClass,
    data: JByteArray,
) -> jstring {
    let result = parse_gpx_inner(&env, &data);

    let json = match result {
        Ok(json) => json,
        Err(e) => format!(r#"{{"error":"{}"}}"#, e.replace('"', "\\\"")),
    };

    env.new_string(&json)
        .expect("failed to create Java string")
        .into_raw()
}

fn parse_gpx_inner(env: &JNIEnv, data: &JByteArray) -> Result<String, String> {
    let bytes = env
        .convert_byte_array(data)
        .map_err(|e| format!("JNI byte array conversion failed: {e}"))?;

    crate::gpx::parse_to_json(&bytes)
}
