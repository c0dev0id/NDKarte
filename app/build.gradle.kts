plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ndkarte.app"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.ndkarte.app"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        resources {
            pickFirst("META-INF/INDEX.LIST")
        }
    }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:11.5.2")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.api-client:google-api-client-android:2.7.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20241027-2.0.0")
    implementation("com.google.http-client:google-http-client-gson:1.45.1")
}

/**
 * Custom Gradle task to compile Rust code for Android targets.
 *
 * Invokes cargo via cargo-ndk to cross-compile rust-core/ into shared
 * libraries, then copies the .so files into jniLibs/ for APK bundling.
 */
val rustTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android"
)

tasks.register("buildRust") {
    description = "Cross-compile rust-core for Android targets"
    group = "build"

    val rustDir = rootProject.file("rust-core")
    val jniLibsDir = file("src/main/jniLibs")
    val libName = "libndkarte.so"

    inputs.dir(rustDir.resolve("src"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    outputs.files(rustTargets.keys.map { jniLibsDir.resolve("$it/$libName") })

    doLast {
        rustTargets.forEach { (abi, target) ->
            exec {
                workingDir = rustDir
                commandLine(
                    "cargo", "ndk",
                    "--target", target,
                    "--platform", "34",
                    "--", "build", "--release"
                )
            }

            val soFile = rustDir.resolve(
                "target/$target/release/$libName"
            )
            val destDir = jniLibsDir.resolve(abi)
            copy {
                from(soFile)
                into(destDir)
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn("buildRust")
}
