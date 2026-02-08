//! JNI bindings for the Android app.
//!
//! Each public function here corresponds to an `external fun` declaration
//! in RustBridge.kt. The function names follow JNI naming conventions:
//! Java_<package>_<class>_<method> with dots replaced by underscores.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jdouble, jstring};

use crate::gpx::Point;

// -- Helpers --

fn json_result(env: &mut JNIEnv, result: Result<String, String>) -> jstring {
    let json = match result {
        Ok(json) => json,
        Err(e) => format!(r#"{{"error":"{}"}}"#, e.replace('"', "\\\"")),
    };
    env.new_string(&json)
        .expect("failed to create Java string")
        .into_raw()
}

// -- Version --

/// Returns the rust-core library version.
/// Maps to: RustBridge.version() -> String
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ndkarte_app_RustBridge_version(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    env.new_string(crate::VERSION)
        .expect("failed to create Java string")
        .into_raw()
}

// -- GPX Parsing --

/// Parse a GPX file from raw bytes and return JSON.
/// Maps to: RustBridge.parseGpx(data: ByteArray) -> String
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ndkarte_app_RustBridge_parseGpx(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
) -> jstring {
    let result = (|| {
        let bytes = env
            .convert_byte_array(&data)
            .map_err(|e| format!("JNI byte array conversion failed: {e}"))?;
        crate::gpx::parse_to_json(&bytes)
    })();
    json_result(&mut env, result)
}

// -- Navigation --

/// Project a position onto a track and return the nearest point info.
///
/// Maps to: RustBridge.projectOnTrack(lat, lon, trackJson) -> String
///
/// trackJson is a JSON array of {lat, lon, ele?} objects.
/// Returns JSON: { point: {}, segment_index, distance_m, distance_along_m }
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ndkarte_app_RustBridge_projectOnTrack(
    mut env: JNIEnv,
    _class: JClass,
    lat: jdouble,
    lon: jdouble,
    track_json: JString,
) -> jstring {
    let result = (|| {
        let json_str: String = env
            .get_string(&track_json)
            .map_err(|e| format!("JNI string conversion failed: {e}"))?
            .into();

        let points: Vec<Point> = serde_json::from_str(&json_str)
            .map_err(|e| format!("Track JSON parse failed: {e}"))?;

        let position = Point { lat, lon, ele: None };
        let proj = crate::nav::project_on_track(&position, &points)
            .ok_or_else(|| "Track has fewer than 2 points".to_string())?;

        serde_json::to_string(&proj)
            .map_err(|e| format!("JSON serialize failed: {e}"))
    })();
    json_result(&mut env, result)
}

// -- Conversion --

/// Simplify a track to a route using Ramer-Douglas-Peucker.
///
/// Maps to: RustBridge.trackToRoute(trackJson, toleranceM) -> String
///
/// trackJson: { name?, points: [{lat, lon, ele?}] }
/// Returns: { name?, points: [{lat, lon, ele?}] }
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ndkarte_app_RustBridge_trackToRoute(
    mut env: JNIEnv,
    _class: JClass,
    track_json: JString,
    tolerance_m: jdouble,
) -> jstring {
    let result = (|| {
        let json_str: String = env
            .get_string(&track_json)
            .map_err(|e| format!("JNI string conversion failed: {e}"))?
            .into();

        let track: crate::gpx::Track = serde_json::from_str(&json_str)
            .map_err(|e| format!("Track JSON parse failed: {e}"))?;

        let route = crate::convert::track_to_route(&track, tolerance_m);

        serde_json::to_string(&route)
            .map_err(|e| format!("JSON serialize failed: {e}"))
    })();
    json_result(&mut env, result)
}

/// Convert a route to a track (direct copy).
///
/// Maps to: RustBridge.routeToTrack(routeJson) -> String
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ndkarte_app_RustBridge_routeToTrack(
    mut env: JNIEnv,
    _class: JClass,
    route_json: JString,
) -> jstring {
    let result = (|| {
        let json_str: String = env
            .get_string(&route_json)
            .map_err(|e| format!("JNI string conversion failed: {e}"))?
            .into();

        let route: crate::gpx::Route = serde_json::from_str(&json_str)
            .map_err(|e| format!("Route JSON parse failed: {e}"))?;

        let track = crate::convert::route_to_track(&route);

        serde_json::to_string(&track)
            .map_err(|e| format!("JSON serialize failed: {e}"))
    })();
    json_result(&mut env, result)
}
