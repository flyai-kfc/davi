import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.davi.helper.e2edriver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.davi.helper.e2edriver"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "com.davi.helper.e2e.DaviE2eRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 별도 driver는 debug 서명 권한으로만 Davi debug 앱과 통신한다. 배포 가능한 release 변형은 만들지 않는다.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enable = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // 전송 차단·수신자 격리 판정부는 기기 없이 도는 JVM 테스트로 CI에서 매번 확인한다(#134).
    // 기존 하드 경계 테스트는 androidTest에 있어 실기기 instrumentation 때만 돌았다.
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
