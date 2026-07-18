plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.satir.nozzlealigner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.satir.nozzlealigner"
        minSdk = 24            // Android 7.0 — çoğu OTG/UVC telefon
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-mvp"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }

    // Bazı UVC/OpenCV .so'ları 16 KB hizalama uyarısı verebilir; sorun değil.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // --- UVC (USB kamera) kütüphanesi: jiangdg AndroidUSBCamera / libausbc ---
    implementation("com.github.jiangdg.AndroidUSBCamera:libausbc:3.3.3")

    // --- CameraX (telefonun kendi kamerası) ---
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // --- OpenCV (Maven Central, native .so gömülü; OpenCVLoader.initLocal ile yüklenir) ---
    implementation("org.opencv:opencv:4.11.0")
}
