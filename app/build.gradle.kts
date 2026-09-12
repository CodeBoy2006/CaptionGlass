plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.captionglass.app"
    compileSdk = 36
    ndkVersion = "27.1.12297006"
    defaultConfig {
        applicationId = "com.captionglass.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "com.captionglass.app.DeviceChecks"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    // Compress native libraries in standalone APKs; Android extracts them at install time.
    packaging { jniLibs.useLegacyPackaging = true }
    sourceSets["main"].assets.srcDir(rootProject.file("models"))
    sourceSets["main"].assets.srcDir(rootProject.file("third_party"))
    sourceSets["androidTest"].assets.srcDir(rootProject.file("artifacts/fixtures/wav"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { warningsAsErrors = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":engine"))
    implementation(project(":native"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
}
