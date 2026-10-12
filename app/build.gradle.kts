plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.soby.jx11keymapper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.soby.jx11keymapper"
        minSdk = 26
        targetSdk = 34
        ndk { abiFilters += "arm64-v8a" }
        versionCode = 7
        versionName = "0.4.0"
    }

    // 빌드마다 서명이 바뀌지 않도록 저장소에 포함된 debug.keystore 사용 (덮어 설치 가능)
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    packaging {
        jniLibs { useLegacyPackaging = true }   // .so 를 파일로 추출 → shell 프로세스에서도 로드 가능
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // 무선 ADB (Kadb): 페어링/연결. Kotlin 2.1 로 빌드된 1.2.1 사용
    implementation("com.flyfishxu:kadb:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
