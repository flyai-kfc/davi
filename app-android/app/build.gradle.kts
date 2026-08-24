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

fun localSetting(name: String): String =
    localProps.getProperty(name)?.trim().orEmpty().ifEmpty {
        // 일회성 자동 검증에서는 파일에 토큰을 쓰지 않도록 같은 이름의 환경변수를 허용한다.
        providers.environmentVariable(name).orNull?.trim().orEmpty()
    }

// **debug 전용** 값. 읽는 곳은 localSetting과 같지만, release 빌드에 존재하면 안 되는 것들이라
// verifyReleaseConfiguration이 따로 막는다(#57). release가 정당하게 쓰는 값(서명 키 등)은
// localSetting을 그대로 쓴다 — 그 관문에 걸리면 안 되기 때문이다.
fun debugSetting(name: String): String = localSetting(name)

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

// 1차 마스킹 우회 옵트인(#203). masked/unmasked 가이드 품질을 같은 시나리오로 비교하기 위한
// **debug 전용 실험 플래그**다. 켜면 원문 화면 label이 서버를 거쳐 실제 Gemini까지 나간다.
// 합성 데이터만 넣은 전용 테스트 기기에서만 쓴다.
//
// 두 경로로 받는다. `local.properties`의 `DAVI_DISABLE_PRIVACY_MASKING`은 비교 실험을 여러 번
// 도는 동안 매번 타이핑하지 않게 하고, 일회성 Gradle 속성 `-PdaviDisablePrivacyMasking`은
// 그 파일 설정을 실행 하나만 덮는다. **속성이 있으면 속성이 이긴다** — 파일에 true를 적어 둔
// 머신에서도 `-PdaviDisablePrivacyMasking=false` 한 번으로 마스킹된 APK를 만들 수 있어야 한다.
//
// 어느 경로든 release 빌드에서는 존재만으로 실패한다(아래 verifyReleaseConfiguration).
fun requireBooleanFlag(name: String, value: String): String {
    if (value.isNotEmpty() && value !in setOf("true", "false")) {
        throw GradleException("$name 은 true 또는 false여야 합니다.")
    }
    return value
}

val disablePrivacyMaskingProperty = requireBooleanFlag(
    "daviDisablePrivacyMasking",
    providers.gradleProperty("daviDisablePrivacyMasking").orNull?.trim().orEmpty(),
)
val disablePrivacyMaskingSetting = requireBooleanFlag(
    "DAVI_DISABLE_PRIVACY_MASKING",
    debugSetting("DAVI_DISABLE_PRIVACY_MASKING"),
)
// 어느 한쪽이라도 설정돼 있으면 release 관문이 막는다. 값이 false여도 마찬가지다 —
// release 경로에 이 설정이 남아 있다는 것 자체가 의도를 오인한 실행이다.
val disablePrivacyMaskingConfigured =
    disablePrivacyMaskingProperty.isNotEmpty() || disablePrivacyMaskingSetting.isNotEmpty()
val disablePrivacyMasking =
    disablePrivacyMaskingProperty.ifEmpty { disablePrivacyMaskingSetting } == "true"

// 카카오 네이티브 앱 키(로그인). 빌드 타입마다 출처가 다르다.
// 키가 비면 SDK init을 건너뛰고 로그인 버튼을 숨긴다 — 빌드·실행은 깨지지 않는다.
fun requireAlphanumericKakaoKey(source: String, value: String) {
    if (value.isNotEmpty() && !value.all { it.isLetterOrDigit() }) {
        throw GradleException("$source 카카오 네이티브 앱 키는 영숫자여야 합니다.")
    }
}

// debug: 개발자별 테스트 앱 키를 쓸 수 있게 local.properties에서 읽는다. release로는 새지 않는다
// (verifyReleaseConfiguration이 값이 있으면 release 빌드를 막는다).
val debugKakaoNativeAppKey = debugSetting("DAVI_KAKAO_NATIVE_APP_KEY")
requireAlphanumericKakaoKey("debug(DAVI_KAKAO_NATIVE_APP_KEY)", debugKakaoNativeAppKey)

// release: 팀 카카오 앱 키를 저장소 상수로 둔다(#215). 네이티브 앱 키는 APK에서 추출 가능한
// 공개 전제 식별자이고 등록된 패키지명·서명 해시와 함께여야만 동작하므로 비밀이 아니다 —
// 저장소가 private이라 CI secret으로 옮길 이득이 없다.
// debug와 같은 카카오 앱(콘솔 앱 ID 1547399)을 쓴다. 소유자가 개인 계정이어도 카카오는 앱
// 소유자를 Play 개발자 계정과 대조하지 않고, 우리 로그인은 동의항목을 요청하지 않아(서버가
// id·app_id만 읽는다) 비즈 앱 전환도 필요 없다 — 콘솔 접근은 팀원 초대로 연다.
// 같은 앱이라 서버 DAVI_KAKAO_APP_ID는 그대로 맞는다.
// 남은 관문은 서명 해시다. 서명 주체마다 지문이 달라 등록할 해시가 셋이고(debug·업로드 키·
// Play 앱 서명 키), 어느 것이 어느 배포 경로에 쓰이는지는 README "카카오 키 해시" 절에 모아
// 두었다 — 여기에 다시 적으면 두 곳이 어긋난다.
val releaseKakaoNativeAppKey = "96ec16afc31462078e4d7daefa9cf0b2"
requireAlphanumericKakaoKey("release(releaseKakaoNativeAppKey)", releaseKakaoNativeAppKey)

// 도그푸딩 옵트인(#41 리뷰). true인 debug 빌드만 기기 해시를 계산·로그·전송한다.
// 켜는 곳: 공기계(SM-S921N)용 빌드를 만드는 머신의 local.properties. 기본은 꺼짐 —
// 서버 화이트리스트는 저장만 거를 뿐 전송 자체는 여기서만 막을 수 있다.
val dogfoodOptIn = debugSetting("DAVI_DOGFOOD") == "true"

// release 서명(업로드 키). **값이 없으면 서명하지 않고 그대로 빌드한다** — CI와 키를 갖지
// 않은 팀원 PC에서 assembleRelease가 계속 돌아야 하고, 서명 키는 한 사람만 갖는다(#221).
// 서명되지 않은 release는 기기 설치·Play 업로드가 안 될 뿐 빌드는 성공한다.
//
// keystore 파일과 비밀번호는 **저장소에 넣지 않는다.** local.properties(gitignore됨)에 적거나
// 같은 이름의 환경변수로 준다. Windows 경로는 `\`가 .properties의 이스케이프 문자라
// `C:/...`처럼 `/`로 쓴다.
val releaseKeystorePath = localSetting("DAVI_RELEASE_KEYSTORE")
val releaseKeystorePassword = localSetting("DAVI_RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAliasSetting = localSetting("DAVI_RELEASE_KEY_ALIAS")
val releaseKeyPasswordSetting = localSetting("DAVI_RELEASE_KEY_PASSWORD")
val releaseKeyAlias = releaseKeyAliasSetting.ifEmpty { "davi-upload" }
// 키 비밀번호를 따로 두지 않은 keystore가 흔하다. 없으면 store 비밀번호를 쓴다.
val releaseKeyPassword = releaseKeyPasswordSetting.ifEmpty { releaseKeystorePassword }
val releaseKeystoreFile = releaseKeystorePath.takeIf { it.isNotEmpty() }?.let { rootProject.file(it) }

// 서명 설정을 반쯤 준 것은 설정 실수다 — 조용히 서명 없이 넘어가면 Play 업로드 단계에서야
// 알게 되므로 여기서 멈춘다. "아무것도 안 준 것"(CI·키 없는 팀원 PC)과는 구분한다.
if (releaseKeystoreFile == null) {
    // 반대 방향의 같은 실수다. DAVI_RELEASE_KEYSTORE 이름을 오타 내면 나머지를 제대로 넣고도
    // 이 블록이 통째로 꺼진 채 app-release-unsigned.apk가 나간다.
    val orphaned = listOf(
        "DAVI_RELEASE_KEYSTORE_PASSWORD" to releaseKeystorePassword,
        "DAVI_RELEASE_KEY_ALIAS" to releaseKeyAliasSetting,
        "DAVI_RELEASE_KEY_PASSWORD" to releaseKeyPasswordSetting,
    ).filter { it.second.isNotEmpty() }.map { it.first }
    if (orphaned.isNotEmpty()) {
        throw GradleException(
            "${orphaned.joinToString("·")}를 설정했으면 keystore 경로도 필요합니다. " +
                "DAVI_RELEASE_KEYSTORE 이름을 확인하세요 — 비워 두면 서명 없이 빌드됩니다.",
        )
    }
} else {
    if (!releaseKeystoreFile.isFile) {
        throw GradleException(
            "DAVI_RELEASE_KEYSTORE 경로에 파일이 없습니다: $releaseKeystorePath",
        )
    }
    if (releaseKeystorePassword.isEmpty()) {
        throw GradleException("DAVI_RELEASE_KEYSTORE를 설정했으면 DAVI_RELEASE_KEYSTORE_PASSWORD도 필요합니다.")
    }
}

// release가 바라볼 Azure Container Apps endpoint(#214에서 확정). 공개 식별자라 상수로 둔다 —
// APK에서 어차피 추출되고, releaseKakaoNativeAppKey와 같은 결이다.
val releaseServerUrl =
    "https://davi-server-app.greenbeach-0566d3be.koreacentral.azurecontainerapps.io"

// 서버가 development 프로필이라 원격 요청마다 접근 토큰을 요구한다(server/app/main.py의
// _development_access_error). 주소만 넣으면 붙기는 해도 모든 요청이 401로 끝난다 — 실측했다.
// **releaseKakaoNativeAppKey와 같은 결로 이것도 APK에서 추출된다 — 비밀이 아니다**(#248 리뷰).
// 상수로 두지 않는 이유는 기밀성이 아니라 **회전**이다: 팀 전체가 공유하는 Gemini 쿼터에
// 물려 있어 기간 제한 임시 capability로 다뤄야 하고(내부 테스트 시작 직전 서버에 적용, 종료
// 즉시 서버에서 무효화·회전 — local.properties 삭제는 폐기가 아니다), 상수면 회전마다 커밋과
// 재배포가 필요해진다. beta·production의 세션 접근 통제·분·일 단위 비용 상한·비식별 로그
// 정책은 #214의 서버 경계가 담당한다. 이 값은 development 공유 서버의 별도 임시 경계다.
// 값이 비어도 여기서 던지지 않는다 — debug 빌드까지 같이 깨진다. release에서만 아래
// verifyReleaseConfiguration이 실패로 만든다.
val releaseAccessToken = localSetting("DAVI_RELEASE_ACCESS_TOKEN")

// 로그인 버튼은 "키가 있다"가 아니라 **"로그인이 끝까지 갈 수 있다"**에 건다. 카카오 인증에
// 성공해도 세션을 교환할 상대가 없으면 매번 login_error_server로 끝나므로, 그 상태에서
// 버튼을 띄우면 첫 내부 테스트에서 테스터가 가장 먼저 누르는 자리가 항상 실패한다.
// RFC 2606이 .invalid를 절대 해석되지 않는 TLD로 예약했으니, placeholder 문자열과 비교하는
// 대신 그 성질로 판정한다. #214가 실주소를 넣으면 조건이 저절로 참이 된다.
val releaseServerConfigured =
    !URI(releaseServerUrl).host.orEmpty().endsWith(".invalid")
val releaseLoginEnabled = releaseKakaoNativeAppKey.isNotEmpty() && releaseServerConfigured
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

    lint {
        // assembleRelease가 이미 실행하는 lintVitalRelease에 구버전 API 경계를 태운다(#385).
        // 별도 lintDebug(+94초 실측)를 추가하지 않고 API 26~35의 즉시 크래시를 막는다.
        fatal += "NewApi"
    }

    // 키가 있는 머신에서만 만든다. 없으면 아예 만들지 않아 release가 서명 없이 나간다.
    signingConfigs {
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.davi.helper"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // release의 안전한 기본값. 실제 제품 endpoint는 별도 배포 이슈에서 정한다.
        buildConfigField("String", "SERVER_URL", releaseServerUrl.asBuildConfigString())
        buildConfigField("String", "DEV_ACCESS_TOKEN", "\"\"")
        buildConfigField("boolean", "DOGFOOD_OPT_IN", "false")
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"\"")
        buildConfigField("boolean", "LOGIN_ENABLED", "false")
        // 기본값은 모든 variant에서 마스킹 활성화다. debug만 아래에서 덮어쓴다(#203).
        buildConfigField("boolean", "PRIVACY_MASKING_DISABLED", "false")
        // 커스텀 scheme은 값이 무엇이든 Android가 정상 매칭한다(#168 리뷰 P1 — "죽은 scheme"은
        // 없다). 그래서 키 없는 빌드는 scheme을 바꾸는 게 아니라 handler activity 자체를
        // kakaoLoginEnabled placeholder로 끈다. SDK도 init되지 않으므로 남는 진입점이 없다.
        manifestPlaceholders["kakaoOauthScheme"] = "kakao.unconfigured"
        manifestPlaceholders["kakaoLoginEnabled"] = "false"
    }

    buildTypes {
        debug {
            // debug와 release를 한 기기에 함께 설치할 수 있게 한다(#58). 같은 applicationId면
            // 서명 주체가 달라 설치가 서로를 밀어내고, 접근성 서비스·권한 상태도 서로 덮어쓴다.
            // 첫 Play 내부 테스트에서 debug 앱이 깔린 기기가 실제로 설치 실패했다(2026-08-19).
            //
            // **namespace는 그대로 com.davi.helper다.** 접근성 서비스 클래스 이름은 namespace
            // 기준이라 suffix가 붙어도 바뀌지 않고, 그래서 dumpsys로 서비스를 찾는 스크립트가
            // 계속 동작한다(dev-install.ps1의 $serviceClass가 그 전제로 쓰여 있다).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "SERVER_URL", debugServerUrl.asBuildConfigString())
            buildConfigField("String", "DEV_ACCESS_TOKEN", debugAccessToken.asBuildConfigString())
            buildConfigField("boolean", "DOGFOOD_OPT_IN", dogfoodOptIn.toString())
            buildConfigField(
                "String",
                "KAKAO_NATIVE_APP_KEY",
                debugKakaoNativeAppKey.asBuildConfigString(),
            )
            buildConfigField(
                "boolean",
                "PRIVACY_MASKING_DISABLED",
                disablePrivacyMasking.toString(),
            )
            buildConfigField(
                "boolean",
                "LOGIN_ENABLED",
                debugKakaoNativeAppKey.isNotEmpty().toString(),
            )
            if (debugKakaoNativeAppKey.isNotEmpty()) {
                manifestPlaceholders["kakaoOauthScheme"] = "kakao$debugKakaoNativeAppKey"
                manifestPlaceholders["kakaoLoginEnabled"] = "true"
            }
        }
        release {
            isMinifyEnabled = false
            // 키가 없는 빌드는 서명되지 않은 채로 나온다. 기기 설치·Play 업로드가 안 될 뿐
            // 빌드는 성공해야 한다 — CI가 이 경로로 assembleRelease를 돈다.
            signingConfigs.findByName("release")?.let { signingConfig = it }
            buildConfigField("String", "SERVER_URL", releaseServerUrl.asBuildConfigString())
            buildConfigField(
                "String",
                "DEV_ACCESS_TOKEN",
                releaseAccessToken.asBuildConfigString(),
            )
            // release는 속성을 읽지 않는다. 상수 false를 다시 못박아 debug 값이 새지 않게 한다.
            buildConfigField("boolean", "PRIVACY_MASKING_DISABLED", "false")
            // 키와 placeholder는 함께 켜야 한다. 키만 넣으면 버튼은 뜨지만 handler activity가
            // 꺼진 채라 카카오톡·계정 웹에서 돌아오는 리다이렉트를 받을 진입점이 없다.
            buildConfigField(
                "String",
                "KAKAO_NATIVE_APP_KEY",
                releaseKakaoNativeAppKey.asBuildConfigString(),
            )
            // 진입점(scheme·handler activity)은 키와 함께 켜 둔다. 버튼만 releaseLoginEnabled로
            // 늦추면 #214는 주소 한 줄만 바꾸면 되고, 켜는 순간 리다이렉트가 이미 받아진다.
            buildConfigField("boolean", "LOGIN_ENABLED", releaseLoginEnabled.toString())
            if (releaseKakaoNativeAppKey.isNotEmpty()) {
                manifestPlaceholders["kakaoOauthScheme"] = "kakao$releaseKakaoNativeAppKey"
                manifestPlaceholders["kakaoLoginEnabled"] = "true"
            }
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
    inputs.property("debugKakaoKeyConfigured", debugKakaoNativeAppKey.isNotEmpty())
    // release 산출물의 BuildConfig는 이미 상수 false지만, 우회 설정을 둔 채로 release를 만들면
    // 의도를 오인한 실행이다. 값이 false여도 "설정이 존재하는 것" 자체를 실패로 본다(#203).
    inputs.property("privacyMaskingConfigured", disablePrivacyMaskingConfigured)
    inputs.property("releaseAccessTokenLength", releaseAccessToken.length)
    doLast {
        val debugServerConfigured = inputs.properties["debugServerConfigured"] as Boolean
        val debugAccessTokenConfigured = inputs.properties["debugAccessTokenConfigured"] as Boolean
        val debugKakaoKeyConfigured = inputs.properties["debugKakaoKeyConfigured"] as Boolean
        val privacyMaskingConfigured = inputs.properties["privacyMaskingConfigured"] as Boolean
        // 위반을 모아서 한 번에 알린다. 먼저 걸린 하나만 던지면 그것만 고치고 다시 실패하며,
        // 남은 관문이 실제로 도는지도 확인할 수 없다.
        val violations = buildList {
            if (debugServerConfigured || debugAccessTokenConfigured || debugKakaoKeyConfigured) {
                add(
                    "DAVI_DEBUG_SERVER_URL·DAVI_DEBUG_ACCESS_TOKEN·" +
                        "DAVI_KAKAO_NATIVE_APP_KEY를 제거하세요 — release 카카오 키는 " +
                        "이 경로가 아니라 releaseKakaoNativeAppKey 상수로 들어갑니다.",
                )
            }
            val releaseTokenLength = inputs.properties["releaseAccessTokenLength"] as Int
            if (releaseTokenLength == 0) {
                add(
                    "DAVI_RELEASE_ACCESS_TOKEN을 설정하세요 — 서버가 development 프로필이라 " +
                        "토큰 없이 나간 빌드는 설치는 되고 모든 요청이 401로 죽습니다.",
                )
            } else if (releaseTokenLength < 32) {
                add("DAVI_RELEASE_ACCESS_TOKEN은 32자 이상이어야 합니다.")
            }
            if (privacyMaskingConfigured) {
                add(
                    "DAVI_DISABLE_PRIVACY_MASKING과 daviDisablePrivacyMasking을 제거하세요 — " +
                        "debug 비교 실험 전용입니다(#203).",
                )
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                violations.joinToString(
                    separator = "\n  - ",
                    prefix = "release 빌드 전에 아래를 정리하세요.\n  - ",
                ),
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
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
