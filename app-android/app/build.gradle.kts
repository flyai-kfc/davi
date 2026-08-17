import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

abstract class GenerateDebugNetworkSecurityConfig : DefaultTask() {
    @get:Input
    abstract val cleartextHost: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.file("xml/network_security_config.xml").get().asFile
        output.parentFile.mkdirs()
        val lines = mutableListOf(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<!-- build.gradle.kts가 생성한다. 직접 수정하지 않는다. -->",
            "<network-security-config>",
            "    <base-config cleartextTrafficPermitted=\"false\" />",
        )
        cleartextHost.get().takeIf { it.isNotEmpty() }?.let { host ->
            lines += "    <domain-config cleartextTrafficPermitted=\"true\">"
            lines += "        <domain includeSubdomains=\"false\">$host</domain>"
            lines += "    </domain-config>"
        }
        lines += "</network-security-config>"
        output.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }
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
// 실기기 E2E 스크립트는 개발자의 local.properties를 덮어쓰지 않고 이 일회성 Gradle 속성으로
// adb reverse 대상만 고정한다. release 검증에서는 다른 debug URL과 똑같이 금지된다(#124).
val e2eServerUrl = providers.gradleProperty("daviE2eServerUrl").orNull?.trim().orEmpty()
val debugServerProperty = e2eServerUrl.ifEmpty { debugSetting("DAVI_DEBUG_SERVER_URL") }
val debugServerUrl = debugServerProperty.ifEmpty { "http://10.0.2.2:8000" }
val debugAccessToken = debugSetting("DAVI_DEBUG_ACCESS_TOKEN")
val parsedDebugUrl = runCatching { URI(debugServerUrl) }.getOrElse {
    throw GradleException("DAVI_DEBUG_SERVER_URL이 올바른 URL이 아닙니다.")
}
val debugScheme = parsedDebugUrl.scheme?.lowercase().orEmpty()
val debugServerHost = parsedDebugUrl.host?.lowercase().orEmpty()
if (debugScheme !in setOf("http", "https") || debugServerHost.isEmpty()) {
    throw GradleException("DAVI_DEBUG_SERVER_URL은 http 또는 https 절대 URL이어야 합니다.")
}
if (parsedDebugUrl.rawUserInfo != null || parsedDebugUrl.rawQuery != null || parsedDebugUrl.rawFragment != null) {
    throw GradleException("DAVI_DEBUG_SERVER_URL에는 사용자 정보·query·fragment를 넣을 수 없습니다.")
}
if (parsedDebugUrl.rawPath !in setOf("", "/")) {
    throw GradleException("DAVI_DEBUG_SERVER_URL에는 경로를 넣을 수 없습니다.")
}
if (debugScheme == "http") {
    if (parsedDebugUrl.port != 8000) {
        throw GradleException("debug 평문 HTTP는 8000 포트만 사용할 수 있습니다.")
    }
    if (!isPrivateOrLocalIpv4(debugServerHost)) {
        throw GradleException("debug 평문 HTTP는 사설 IPv4·loopback 주소에만 사용할 수 있습니다.")
    }
} else {
    if (parsedDebugUrl.port !in setOf(-1, 443)) {
        throw GradleException("Azure debug HTTPS는 기본 443 포트만 사용할 수 있습니다.")
    }
    if (!debugServerHost.endsWith(".azurecontainerapps.io")) {
        throw GradleException("debug HTTPS는 Azure Container Apps 기본 endpoint만 사용할 수 있습니다.")
    }
}
val localOnlyHosts = setOf("127.0.0.1", "localhost", "10.0.2.2")
if (debugServerHost !in localOnlyHosts && debugAccessToken.isEmpty()) {
    throw GradleException("원격 debug 서버에는 DAVI_DEBUG_ACCESS_TOKEN이 필요합니다.")
}
if (debugAccessToken.isNotEmpty() && debugAccessToken.length < 32) {
    throw GradleException("DAVI_DEBUG_ACCESS_TOKEN은 32자 이상이어야 합니다.")
}

// 카카오 네이티브 앱 키(로그인). APK에서 추출 가능한 공개 전제 식별자라 비밀 키 규칙과는
// 다르지만, 팀 카카오 앱이 확정되기 전엔 개발자별 테스트 앱 키를 쓸 수 있게 debug만
// local.properties에서 읽는다. release 실키는 배포 이슈에서 확정한다(SERVER_URL과 같은 결).
// 키가 비면 SDK init을 건너뛰고 로그인 버튼을 숨긴다 — 빌드·실행은 깨지지 않는다.
val kakaoNativeAppKey = debugSetting("DAVI_KAKAO_NATIVE_APP_KEY")
if (kakaoNativeAppKey.isNotEmpty() && !kakaoNativeAppKey.all { it.isLetterOrDigit() }) {
    throw GradleException("DAVI_KAKAO_NATIVE_APP_KEY는 영숫자여야 합니다.")
}

// 도그푸딩 옵트인(#41 리뷰). true인 debug 빌드만 기기 해시를 계산·로그·전송한다.
// 켜는 곳: 공기계(SM-S921N)용 빌드를 만드는 머신의 local.properties. 기본은 꺼짐 —
// 서버 화이트리스트는 저장만 거를 뿐 전송 자체는 여기서만 막을 수 있다.
val dogfoodOptIn = debugSetting("DAVI_DOGFOOD") == "true"

val releaseServerUrl = "https://example.invalid"
val generatedDebugNetworkSecurityDir =
    layout.buildDirectory.dir("generated/res/network-security/debug")

// HTTP일 때만 검증된 host 하나에 평문을 연다. Azure HTTPS에는 예외가 필요 없으며
// network security XML은 base-config에서 평문을 계속 거부한다.
val generateDebugNetworkSecurityConfig =
    tasks.register<GenerateDebugNetworkSecurityConfig>("generateDebugNetworkSecurityConfig") {
        group = "build setup"
        description = "debug HTTP 서버 host만 허용하는 network security config 생성"
        cleartextHost.set(if (debugScheme == "http") debugServerHost else "")
        outputDirectory.set(generatedDebugNetworkSecurityDir)
    }

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
        buildConfigField("boolean", "DOGFOOD_OPT_IN", "false")
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"\"")
        // 커스텀 scheme은 값이 무엇이든 Android가 정상 매칭한다(#168 리뷰 P1 — "죽은 scheme"은
        // 없다). 그래서 키 없는 빌드는 scheme을 바꾸는 게 아니라 handler activity 자체를
        // kakaoLoginEnabled placeholder로 끈다. SDK도 init되지 않으므로 남는 진입점이 없다.
        manifestPlaceholders["kakaoOauthScheme"] = "kakao.unconfigured"
        manifestPlaceholders["kakaoLoginEnabled"] = "false"
    }

    buildTypes {
        debug {
            buildConfigField("String", "SERVER_URL", debugServerUrl.asBuildConfigString())
            buildConfigField("String", "DEV_ACCESS_TOKEN", debugAccessToken.asBuildConfigString())
            buildConfigField("boolean", "DOGFOOD_OPT_IN", dogfoodOptIn.toString())
            buildConfigField(
                "String",
                "KAKAO_NATIVE_APP_KEY",
                kakaoNativeAppKey.asBuildConfigString(),
            )
            if (kakaoNativeAppKey.isNotEmpty()) {
                manifestPlaceholders["kakaoOauthScheme"] = "kakao$kakaoNativeAppKey"
                manifestPlaceholders["kakaoLoginEnabled"] = "true"
            }
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

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateDebugNetworkSecurityConfig,
            GenerateDebugNetworkSecurityConfig::outputDirectory,
        )
    }
}

// 개발 URL·토큰을 둔 작업 공간에서 release APK를 만들면 실수로 포함되지 않았더라도 실패시킨다.
// release 빌드는 개발 설정을 제거한 깨끗한 환경에서만 수행한다(#57).
val verifyReleaseConfiguration by tasks.registering {
    group = "verification"
    description = "release 빌드에 debug 전용 설정이 존재하지 않는지 확인"
    inputs.property("debugServerConfigured", debugServerProperty.isNotEmpty())
    inputs.property("debugAccessTokenConfigured", debugAccessToken.isNotEmpty())
    inputs.property("kakaoNativeAppKeyConfigured", kakaoNativeAppKey.isNotEmpty())
    doLast {
        val debugServerConfigured = inputs.properties["debugServerConfigured"] as Boolean
        val debugAccessTokenConfigured = inputs.properties["debugAccessTokenConfigured"] as Boolean
        val kakaoKeyConfigured = inputs.properties["kakaoNativeAppKeyConfigured"] as Boolean
        if (debugServerConfigured || debugAccessTokenConfigured || kakaoKeyConfigured) {
            throw GradleException(
                "release 빌드 전에 DAVI_DEBUG_SERVER_URL·DAVI_DEBUG_ACCESS_TOKEN·" +
                    "DAVI_KAKAO_NATIVE_APP_KEY를 제거하세요.",
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
    implementation(libs.kakao.user)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
