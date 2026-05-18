// AI Agent SDK — Android library module build configuration.
//
// 사양 참조:
// - overview.md "기술 스택": Kotlin, Coroutines+Flow, Hilt, OkHttp(D-001), DataStore(D-002), kotlinx.serialization
// - overview.md "최소 SDK": Android 7.0 (API 24)
// - overview.md "의존성 정책": 외부 라이브러리 최소화
//
// 본 라운드(F-000)에서는 Hilt/OkHttp/DataStore 의존성을 선언만 해두고
// 실제 사용은 후속 라운드(F-001 OkHttp, F-006 Hilt, F-007 DataStore)에서 진행.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("dagger.hilt.android.plugin")
}

android {
    namespace = "com.androidailab.aisdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        // 라이브러리 모듈은 targetSdk 무시되지만, lint를 위해 정렬값 유지
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // 공개 API는 모두 명시적으로 public/internal/private 선언 강제 (작업 원칙: 테스트 가능성 + 추적성)
        freeCompilerArgs = freeCompilerArgs + "-Xexplicit-api=strict"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Kotlin / Coroutines
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")

    // DataStore (D-002, F-007에서 본격 사용)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // OkHttp (D-001, F-001에서 본격 사용)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Hilt (F-006에서 본격 사용)
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")

    // 단위 테스트
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // F-001 라운드 추가: OkHttp MockWebServer (HTTP 통합 테스트)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
