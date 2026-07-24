import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// 서버 터널 URL을 BuildConfig로 주입한다. local.properties 의 DAVI_SERVER_URL 사용.
// 키(API key)는 절대 주입하지 않는다 — 키는 서버만 안다(SPEC.md §7, §2).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// 기본값 10.0.2.2 = 에뮬레이터에서 본 호스트 PC. 실기기는 터널 URL을 local.properties에 넣는다.
val serverUrl: String = localProps.getProperty("DAVI_SERVER_URL") ?: "http://10.0.2.2:8000"

android {
    namespace = "com.davi.helper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.davi.helper"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
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
    }

    buildFeatures {
        buildConfig = true
        // UI 컨벤션: 화면은 XML 레이아웃 + ViewBinding (app-android/CLAUDE.md)
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
