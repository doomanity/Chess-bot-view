plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.chesshelper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.chesshelper"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // stockfish is shipped as libstockfish.so and run as a program, so it must be extracted to disk
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("com.github.bhlangonijr:chesslib:1.3.3")
}
