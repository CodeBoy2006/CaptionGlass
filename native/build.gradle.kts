plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.captionglass.nativebridge"
    compileSdk = 36
    ndkVersion = "27.1.12297006"
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DCMAKE_BUILD_TYPE=Release") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    sourceSets["main"].apply {
        java.srcDir(rootProject.file("artifacts/deps/sherpa-kotlin"))
        jniLibs.srcDir(rootProject.file("artifacts/deps/sherpa-android/jniLibs"))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }
