plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.soby.jx11keymapper"
    compileSdk = 35
    // 워크플로(build.yml)가 설치하는 NDK 와 같은 버전으로 고정 (AGP 기본값 27 을 자동 다운로드하다 실패했음)
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.soby.jx11keymapper"
        minSdk = 26
        targetSdk = 34
        ndk { abiFilters += "arm64-v8a" }
        versionCode = 16
        versionName = "0.5.6"
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
        resources {
            // BouncyCastle(Kadb 의존성) 등 라이브러리끼리 겹치는 메타 파일은 합치지 않고 제외
            excludes += setOf(
                "META-INF/versions/**/OSGI-INF/MANIFEST.MF",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
            pickFirsts += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
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
