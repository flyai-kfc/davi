import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// debug 서버 URL·개발 접근 토큰만 local.properties(또는 일회성 환경변수)에서 읽는다(#57).
// Gemini API 키는 절대 주입하지 않는다 — 키는 서버만 안다(SPEC.md §7, §2).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun debugSetting(name: String): String =
    localProps.getProperty(name)?.trim().orEmpty().ifEmpty {
        // 일회성 자동 검증에서는 파일에 토큰을 쓰지 않도록 같은 이름의 환경변수를 허용한다.
        providers.environmentVariable(name).orNull?.trim().orEmpty()
    }

fun String.asBuildConfigString(): String = "\"" +
    replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r") +
    "\""

fun isPrivateOrLocalIpv4(host: String): Boolean {
    if (host == "localhost") return true
    val octets = host.split(".").mapNotNull { it.toIntOrNull() }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 10 ||
        octets[0] == 127 ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168)
}

val legacyServerUrl = debugSetting("DAVI_SERVER_URL")
if (legacyServerUrl.isNotEmpty()) {
    throw GradleException(
        "DAVI_SERVER_URL은 더 이상 사용하지 않습니다. debug 전용 DAVI_DEBUG_SERVER_URL로 옮기세요.",
    )
}

// 기본값 10.0.2.2 = 에뮬레이터에서 본 호스트 PC.
val debugServerProperty = debugSetting("DAVI_DEBUG_SERVER_URL")
val debugServerUrl = debugServerProperty.ifEmpty { "http://10.0.2.2:8000" }
val debugAccessToken = debugSetting("DAVI_DEBUG_ACCESS_TOKEN")
val parsedDebugUrl = runCatching { URI(debugServerUrl) }.getOrElse {
    throw GradleException("DAVI_DEBUG_SERVER_URL이 올바른 URL이 아닙니다.")
}
val debugScheme = parsedDebugUrl.scheme?.lowercase().orEmpty()
val debugHost = parsedDebugUrl.host?.lowercase().orEmpty()
if (debugScheme != "http" || debugHost.isEmpty()) {
    throw GradleException("DAVI_DEBUG_SERVER_URL은 사설 IPv4를 사용하는 http 절대 URL이어야 합니다.")
}
if (parsedDebugUrl.rawUserInfo != null || parsedDebugUrl.rawQuery != null || parsedDebugUrl.rawFragment != null) {
    throw GradleException("DAVI_DEBUG_SERVER_URL에는 사용자 정보·query·fragment를 넣을 수 없습니다.")
}
if (parsedDebugUrl.port != 8000 || parsedDebugUrl.rawPath !in setOf("", "/")) {
    throw GradleException("DAVI_DEBUG_SERVER_URL은 경로 없이 8000 포트를 사용해야 합니다.")
}
if (!isPrivateOrLocalIpv4(debugHost)) {
    throw GradleException("debug 평문 HTTP는 사설 IPv4·loopback 주소에만 사용할 수 있습니다.")
}
val localOnlyHosts = setOf("127.0.0.1", "localhost", "10.0.2.2")
if (debugHost !in localOnlyHosts && debugAccessToken.isEmpty()) {
    throw GradleException("사설 Wi-Fi debug 서버에는 DAVI_DEBUG_ACCESS_TOKEN이 필요합니다.")
}
if (debugAccessToken.isNotEmpty() && debugAccessToken.length < 32) {
    throw GradleException("DAVI_DEBUG_ACCESS_TOKEN은 32자 이상이어야 합니다.")
}

val releaseServerUrl = "https://example.invalid"

android {
    namespace = "com.davi.helper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.davi.helper"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // release의 안전한 기본값. 실제 제품 endpoint는 별도 배포 이슈에서 정한다.
        buildConfigField("String", "SERVER_URL", releaseServerUrl.asBuildConfigString())
        buildConfigField("String", "DEV_ACCESS_TOKEN", "\"\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "SERVER_URL", debugServerUrl.asBuildConfigString())
            buildConfigField("String", "DEV_ACCESS_TOKEN", debugAccessToken.asBuildConfigString())
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "SERVER_URL", releaseServerUrl.asBuildConfigString())
            buildConfigField("String", "DEV_ACCESS_TOKEN", "\"\"")
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

    buildFeatures {
        buildConfig = true
        // UI 컨벤션: 화면은 XML 레이아웃 + ViewBinding (app-android/CLAUDE.md)
        viewBinding = true
    }
}

// 개발 URL·토큰을 둔 작업 공간에서 release APK를 만들면 실수로 포함되지 않았더라도 실패시킨다.
// release 빌드는 개발 설정을 제거한 깨끗한 환경에서만 수행한다(#57).
val verifyReleaseConfiguration by tasks.registering {
    group = "verification"
    description = "release 빌드에 debug 전용 설정이 존재하지 않는지 확인"
    inputs.property("debugServerConfigured", debugServerProperty.isNotEmpty())
    inputs.property("debugAccessTokenConfigured", debugAccessToken.isNotEmpty())
    doLast {
        val debugServerConfigured = inputs.properties["debugServerConfigured"] as Boolean
        val debugAccessTokenConfigured = inputs.properties["debugAccessTokenConfigured"] as Boolean
        if (debugServerConfigured || debugAccessTokenConfigured) {
            throw GradleException(
                "release 빌드 전에 DAVI_DEBUG_SERVER_URL과 DAVI_DEBUG_ACCESS_TOKEN을 제거하세요.",
            )
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseConfiguration)
}

// AGP의 android { kotlinOptions } 는 구식이다. KGP 2.x 표준인 kotlin { compilerOptions } 를 쓴다.
// 새 DSL에서도 그대로 동작하므로 마이그레이션 부담이 줄어든다.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
