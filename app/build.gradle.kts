plugins {
    id("com.android.application")
}

android {
    namespace = "com.ndkarte.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ndkarte.app"
        minSdk = 34          // Android 14 minimum
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Target common Android architectures
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isDebuggable = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
