//! JNI bindings for the Android app.
//!
//! Each public function here corresponds to a `external fun` declaration
//! in RustBridge.kt. The function names follow JNI naming conventions:
//! Java_<package>_<class>_<method> with dots replaced by underscores.

use jni::JNIEnv;
use jni::objects::JClass;
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
