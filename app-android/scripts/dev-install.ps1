<#
.SYNOPSIS
  앱을 빌드해 연결된 기기에 설치하고, 접근성 서비스를 다시 켠다.

.DESCRIPTION
  Android Studio를 열지 않고 실기기 검증 루프를 돌리기 위한 스크립트다.

  세 가지 함정을 여기서 흡수한다.

  1) Gradle CLI가 "Unable to establish loopback connection"으로 죽는 문제
     원인은 TCP 루프백이 아니라 **AF_UNIX(유닉스 도메인 소켓)** 다.
     JDK 17+ 는 Windows에서 Selector.open() 에 AF_UNIX 소켓 쌍을 쓰는데,
     그 소켓 파일을 %TEMP% 에 만들면 이 PC에서 connect 가 "Invalid argument" 로 실패한다
     (bind 는 되는데 connect 만 실패한다). 소켓 파일 위치를 옮기면 정상 동작한다.
     -> JAVA_TOOL_OPTIONS 로 jdk.net.unixdomain.tmpdir 를 저장소 안 폴더로 지정한다.

  2) 재설치하면 접근성 권한이 꺼진다 (HANDOFF 12장)
     -> 설치 후 adb 로 다시 켠다. 로컬 테스트 전용이다.
     settings put 만으로는 목록에 값이 들어가도 서비스가 바인딩되지 않는 경우가 있어
     (dumpsys 의 "Bound services:{}") 껐다 켜는 토글 사이클로 복구하고 결과를 검증한다.

  3) 오버레이("다른 앱 위에 표시") 권한은 더 이상 쓰지 않는다 (#212)
     창 타입이 TYPE_ACCESSIBILITY_OVERLAY 라 그 권한 없이 그려지고(#56),
     남아 있던 유일한 쓰임인 안내 중 자동 복귀는 알림 탭이 대신한다.
     -> 앱이 선언 자체를 안 하므로 확인할 것이 없다. 확인 코드도 함께 걷었다.

.EXAMPLE
  # 빌드 + 설치 + 접근성 복구
  ./scripts/dev-install.ps1

.EXAMPLE
  # 아무것도 바꾸지 않고 기기·패키지·권한 상태만 본다.
  ./scripts/dev-install.ps1 -Status

.EXAMPLE
  # Android Studio로 빌드한 뒤 접근성 권한만 되살릴 때.
  # 재설치하면 권한이 꺼지는데(HANDOFF 12장) Studio는 그걸 되살려주지 않는다.
  ./scripts/dev-install.ps1 -A11yOnly
#>
[CmdletBinding()]
param(
    # 빌드·설치를 건너뛰고 권한만 복구한다. Android Studio로 빌드한 직후에 쓴다.
    [Alias('A11yOnly')]
    [switch]$SkipBuild,
    # 접근성 서비스를 자동으로 켜지 않는다.
    [switch]$SkipAccessibility,
    # 설치 후 logcat 버퍼를 비운다. 바로 로그를 볼 때 편하다.
    [switch]$ClearLog,
    # 아무것도 바꾸지 않고 상태만 출력하고 끝낸다. 다른 스위치는 무시된다.
    [switch]$Status,
    # 기기가 여러 대일 때 대상 serial. 없으면 ANDROID_SERIAL을 본다.
    [string]$Serial = '',
    # debug 패키지명을 직접 지정한다. 없으면 빌드 산출물에서 유도한다.
    [string]$PackageName = ''
)

$ErrorActionPreference = 'Stop'
# 실패는 throw로 알린다. scripts/dev-driver.ps1 호출자가 예외 전파를 계약으로 쓰므로 exit를 넣지 않는다.

# 한글 출력이 깨지지 않게.
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$appDir = Split-Path -Parent $PSScriptRoot          # app-android/
$repoRoot = Split-Path -Parent $appDir              # 저장소 루트

# 접근성 서비스 클래스는 namespace(com.davi.helper) 기준이라 applicationId에 접미어가 붙어도
# 바뀌지 않는다. 컴포넌트의 왼쪽(패키지)만 유도한다.
$serviceClass = 'com.davi.helper.GuideAccessibilityService'
# 이 스크립트는 **debug만** 다룬다. 산출물이 없을 때 떨어질 곳도 debug여야 한다 —
# release 이름으로 떨어지면 함께 깔린 제품 앱의 서비스를 켜려다 조용히 어긋난다(#58).
$fallbackPackage = 'com.davi.helper.debug'

# 네이티브 명령(gradlew, adb)은 stderr에 정상 메시지를 흘린다.
# PowerShell 5.1은 ErrorActionPreference=Stop 이면 그걸 오류로 보므로, 호출 동안만 완화하고
# 성공 여부는 $LASTEXITCODE 로 판단한다.
function Invoke-CheckedNative {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [Parameter(Mandatory = $true)][string]$What
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $FilePath @Arguments } finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0) { throw "$What 실패 (exit $LASTEXITCODE)" }
}

function Invoke-CapturedNative {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [Parameter(Mandatory = $true)][string]$What
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = & $FilePath @Arguments 2>&1 } finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0) { throw "$What 실패 (exit $LASTEXITCODE)" }
    return @($output | ForEach-Object { "$_" })
}

# --- adb 찾기 ------------------------------------------------------------------
# run-device-e2e.ps1과 같은 순서로 찾는다(팀원마다 SDK 경로가 다르다).
function Resolve-Adb {
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($sdk) {
            $candidate = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path $candidate) { return $candidate }
        }
    }
    $localProps = Join-Path $appDir 'local.properties'
    if (Test-Path $localProps) {
        # sdk.dir=D\:\\Android\\Sdk  형태의 이스케이프를 되돌린다
        $line = Get-Content $localProps | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            $sdk = ($line -replace '^\s*sdk\.dir\s*=', '').Trim() -replace '\\\\', '\' -replace '\\:', ':'
            $candidate = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path $candidate) { return $candidate }
        }
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw 'adb를 찾지 못했다. ANDROID_HOME 또는 local.properties의 sdk.dir를 확인할 것.'
}

$adb = Resolve-Adb

# --- 대상 기기 -----------------------------------------------------------------
# 잘못된 기기에 설치하는 사고를 막는다. 0대와 복수를 구분해 안내한다.
function Get-ConnectedSerials {
    $lines = Invoke-CapturedNative $adb @('devices') 'adb devices'
    return @(
        $lines |
            Where-Object { $_ -match "`tdevice$" } |
            ForEach-Object { ($_ -split "`t")[0] }
    )
}

function Resolve-Serial {
    param([string[]]$Connected, [switch]$AllowNone)

    $requested = if ($Serial) { $Serial } elseif ($env:ANDROID_SERIAL) { $env:ANDROID_SERIAL } else { '' }
    if ($requested) {
        if ($Connected -notcontains $requested) {
            throw "지정한 기기($requested)가 연결 상태가 아니다. 연결된 기기: $(if ($Connected) { $Connected -join ', ' } else { '없음' })"
        }
        return $requested
    }
    if ($Connected.Count -eq 1) { return $Connected[0] }
    if ($Connected.Count -eq 0) {
        if ($AllowNone) { return '' }
        throw 'USB로 연결되고 adb 승인된 기기가 없다. adb devices로 확인할 것.'
    }
    throw "기기가 여러 대 연결되어 있다($($Connected -join ', ')). -Serial 또는 ANDROID_SERIAL로 대상을 지정할 것."
}

$connected = Get-ConnectedSerials
# -Status는 진단이므로 기기를 정하지 못해도 죽지 않는다. 이유를 문장으로 보고한다.
$serialProblem = ''
if ($Status) {
    try { $targetSerial = Resolve-Serial -Connected $connected -AllowNone }
    catch { $targetSerial = ''; $serialProblem = $_.Exception.Message }
}
else {
    $targetSerial = Resolve-Serial -Connected $connected
}
$adbDevice = if ($targetSerial) { @('-s', $targetSerial) } else { @() }

function Invoke-AdbCapture {
    param([string[]]$Arguments, [string]$What)
    return Invoke-CapturedNative $adb ($adbDevice + $Arguments) $What
}
function Invoke-AdbChecked {
    param([string[]]$Arguments, [string]$What)
    Invoke-CheckedNative $adb ($adbDevice + $Arguments) $What
}

# --- 대상 패키지 ---------------------------------------------------------------
# 기기에 실제로 설치된 접근성 서비스에서 패키지를 찾는다.
# pm list packages로도 되지만 #58 전환기에 com.davi.helper와 com.davi.helper.debug가 함께 깔려
# 있으면 어느 쪽이 우리 서비스를 가졌는지 가릴 수 없다. 서비스를 가진 패키지를 직접 찾는다.
function Get-InstalledServicePackages {
    if (-not $targetSerial) { return @() }
    try {
        $state = (Invoke-AdbCapture @('shell', 'dumpsys', 'accessibility') '설치된 접근성 서비스 조회') -join "`n"
    }
    catch { return @() }

    # dumpsys는 컴포넌트를 축약형(com.davi.helper/.GuideAccessibilityService)으로도 찍는다.
    # 축약형 pkg/.Cls 는 pkg.Cls 를 뜻하므로 풀어서 비교해야 한다. 단순 클래스명만 맞춰 보면
    # 이전 PoC(com.davipoc.mvp/.GuideAccessibilityService)까지 후보로 걸린다(실측).
    $found = [ordered]@{}
    foreach ($m in [regex]::Matches($state, '([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*)/([A-Za-z0-9_.]+)')) {
        $pkg = $m.Groups[1].Value
        $class = $m.Groups[2].Value
        $qualified = if ($class.StartsWith('.')) { "$pkg$class" } else { $class }
        if ($qualified -eq $serviceClass) { $found[$pkg] = $true }
    }
    return @($found.Keys)
}

# 서비스 문자열을 박아 두면 #58의 applicationIdSuffix(".debug")가 들어올 때 조용히 깨진다.
# 빌드를 건너뛰는 경로(-A11yOnly·-Status)에서는 산출물이 없거나 낡을 수 있으므로 기기를 먼저 본다.
function Resolve-PackageName {
    if ($PackageName) { return $PackageName }

    $installed = @(Get-InstalledServicePackages)
    if ($installed.Count -eq 1) { return $installed[0] }
    # 빌드 경로에서는 경고하지 않는다(#58). applicationIdSuffix가 들어오면서 com.davi.helper와
    # com.davi.helper.debug가 함께 깔리는 것이 **정상**이 됐고, 그때마다 이 경고가 뜬다.
    # 그런데 빌드 경로는 바로 아래 output-metadata.json에서 applicationId를 다시 읽어 결과를
    # 교정하므로 사용자가 할 일이 없다 — 조치가 필요 없는데 조치를 요구하는 경고는 신호를 죽인다.
    # 산출물이 없거나 낡을 수 있는 경로(-SkipBuild·-A11yOnly·-Status)에서만 알린다.
    if ($installed.Count -gt 1 -and ($SkipBuild -or $Status)) {
        Write-Warning "다비 접근성 서비스를 가진 패키지가 여럿이다($($installed -join ', ')). -PackageName으로 지정할 것."
    }

    # AGP가 debug 빌드마다 갱신하는 산출물 메타데이터.
    $metadata = Join-Path $appDir 'app\build\outputs\apk\debug\output-metadata.json'
    if (Test-Path $metadata) {
        try {
            $parsed = Get-Content $metadata -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($parsed.applicationId) { return "$($parsed.applicationId)" }
        }
        catch {
            Write-Warning "output-metadata.json을 읽지 못했다($($_.Exception.Message)). 기본 패키지명을 쓴다."
        }
    }
    return $fallbackPackage
}

$package = Resolve-PackageName
$service = "$package/$serviceClass"

# --- 상태 조회 -----------------------------------------------------------------
function Get-EnabledServices {
    $raw = (Invoke-AdbCapture @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services') '접근성 목록 조회') -join ''
    $value = "$raw".Trim()
    if ($value -eq 'null') { $value = '' }
    return @($value -split ':' | Where-Object { $_ })
}

# 전역 스위치. 이것이 0이면 목록·바인딩이 멀쩡해도 접근성이 꺼진 것으로 취급해야 한다.
# One UI 8.5 실측: accessibility_enabled=0으로 내려도 이미 붙은 서비스의 바인딩은 남아 있어,
# dumpsys만 보면 "살아 있음"으로 오독한다.
function Get-AccessibilityGlobal {
    $raw = (Invoke-AdbCapture @('shell', 'settings', 'get', 'secure', 'accessibility_enabled') '접근성 전역 상태 조회') -join ''
    return "$raw".Trim()
}

# 토글 사이클이 중간에 죽었을 때 진입 시점 상태로 되돌린다.
# accessibility_enabled는 전역이라 0으로 남으면 다비뿐 아니라 TalkBack 등 기기의 접근성 전체가
# 꺼진 채 방치된다. 사용자에겐 원인을 짐작할 단서가 없다.
# 여기서는 절대 throw하지 않는다 — 복구 실패가 원래 예외를 덮으면 진단이 더 어려워진다.
function Restore-AccessibilityState {
    param([string[]]$Original)

    $ok = $true
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($Original.Count -gt 0) {
            $cmdArgs = $adbDevice + @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ($Original -join ':'))
        }
        else {
            $cmdArgs = $adbDevice + @('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services')
        }
        & $adb @cmdArgs | Out-Null
        if ($LASTEXITCODE -ne 0) { $ok = $false }

        $cmdArgs = $adbDevice + @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
        & $adb @cmdArgs | Out-Null
        if ($LASTEXITCODE -ne 0) { $ok = $false }
    }
    catch { $ok = $false }
    finally { $ErrorActionPreference = $previous }

    # 전역 스위치는 진입 시점 값이 아니라 항상 켬으로 둔다. 이 스크립트를 부른 의도가
    # "접근성을 켜라"이고, 꺼진 채 남는 것이 막으려는 상태 그 자체다.
    if ($ok) { Write-Warning '접근성 복구가 중단됐다. 목록을 진입 시점으로 되돌리고 전역 스위치를 켰다.' }
    else { Write-Warning '접근성 복구가 중단됐고 되돌리기도 실패했다. 기기 설정에서 접근성을 직접 확인할 것.' }
}

# dumpsys accessibility의 섹션 하나(`라벨:{...}`)를 통째로 잘라낸다.
# **한 줄만 보면 안 된다** — 바인딩된 서비스가 둘 이상이면 두 번째부터 줄이 바뀌어서(#302 실측),
# 한 줄 정규식은 첫 서비스만 본다. debug/release가 함께 켜진 기기에서 우리 서비스가 둘째 자리에
# 붙으면 멀쩡히 바인딩된 것을 "안 붙었다"로 읽고, 토글 사이클을 몇 번 돌려도 순서가 바뀌지 않아
# 설정 화면만 계속 열린다.
# 섹션은 전부 `이름:{` 꼴이라 다음 섹션 머리 직전까지가 한 블록이다.
#
# **첫 섹션 하나만 돌려준다.** dumpsys는 `User state[...]`를 사용자별로 찍으므로 직장 프로필·
# 보조 사용자가 있으면 같은 라벨이 여러 번 나온다. 우리가 판정할 것은 이 스크립트가 설치·조작하는
# 현재 사용자(=userId 오름차순 첫 블록)뿐이라, 뒤 사용자 것을 이어 붙이면 보조 사용자에만 붙은
# 서비스를 우리 것으로 오탐한다. run-device-e2e의 두 실행기도 같은 의미로 맞춰 뒀다.
#
# 돌려주는 것은 `:{` **뒤**부터라 머리 줄이 빠져 있다. 첫 서비스가 머리 줄과 같은 줄에 찍히므로
# 판정에는 차이가 없지만, sh·Kotlin 쪽 같은 이름의 함수는 머리 줄을 포함해 돌려준다.
function Get-DumpsysSection {
    param([string]$Dump, [string]$Label)
    $pattern = "(?ms)^\s*$([regex]::Escape($Label))\s*:\{(.*?)(?=^\s*[A-Za-z][A-Za-z0-9 ]*:\{|\z)"
    $match = [regex]::Match($Dump, $pattern)
    if ($match.Success) { return $match.Groups[1].Value }
    return ''
}

# 패키지 접두어로 비교하면 com.davi.helper가 com.davi.helper.debug에도 걸린다(#302).
# 완전 컴포넌트로 본다. 단 dumpsys는 클래스가 applicationId로 시작하면 축약형(`pkg/.Cls`)으로
# 찍으므로 두 형태를 모두 받는다 — 접미어가 없는 release는 축약형, 붙은 debug는 완전형이다(실측).
#
# 대상 패키지·클래스는 **파라미터로 받는다.** PowerShell은 호출 스택을 거슬러 변수를 찾으므로,
# 상위 스코프의 $package를 그냥 읽으면 나중에 누군가 동명의 지역 변수를 둔 함수에서 이걸 부를 때
# 조용히 다른 패키지를 판정한다.
function Test-SectionHasService {
    param([string]$Section, [string]$Package, [string]$ServiceClass)
    if ($Section.Contains("$Package/$ServiceClass")) { return $true }
    if ($ServiceClass.StartsWith("$Package.")) {
        $short = "$Package/" + $ServiceClass.Substring($Package.Length)
        if ($Section.Contains($short)) { return $true }
    }
    return $false
}

# 목록에 값이 있어도 실제로 바인딩되지 않는 경우가 있다. 그 둘을 구분해야 한다.
function Test-ServiceBound {
    $state = (Invoke-AdbCapture @('shell', 'dumpsys', 'accessibility') '접근성 서비스 상태 조회') -join "`n"
    if (Test-SectionHasService (Get-DumpsysSection $state 'Crashed services') $package $serviceClass) { return 'crashed' }
    if (Test-SectionHasService (Get-DumpsysSection $state 'Bound services') $package $serviceClass) { return 'bound' }
    return 'unbound'
}

function Test-PackageInstalled {
    $lines = Invoke-AdbCapture @('shell', 'pm', 'list', 'packages', $package) '설치 패키지 조회'
    # pm list packages는 부분 일치라 com.davi.helper.e2edriver 같은 것도 걸린다. 정확히 비교한다.
    return @($lines | ForEach-Object { ($_ -replace '^package:', '').Trim() }) -contains $package
}

function Get-InstalledVersionName {
    $dump = (Invoke-AdbCapture @('shell', 'dumpsys', 'package', $package) '패키지 정보 조회') -join "`n"
    if ($dump -match 'versionName=(\S+)') { return $Matches[1] }
    return ''
}

# --- -Status: 진단만 하고 끝낸다 ------------------------------------------------
if ($Status) {
    if (-not $targetSerial) {
        if ($serialProblem) { "기기      : $serialProblem" }
        else { '기기      : 없음 — USB 연결과 adb 승인을 확인할 것' }
        '패키지    : 확인 불가(대상 기기 없음)'
        '접근성    : 확인 불가(대상 기기 없음)'
        return
    }

    $model = ((Invoke-AdbCapture @('shell', 'getprop', 'ro.product.model') '기기 모델 조회') -join '').Trim()
    $extra = if ($connected.Count -gt 1) { " (연결 $($connected.Count)대 중 대상)" } else { '' }
    "기기      : $model [$targetSerial]$extra"

    if (Test-PackageInstalled) {
        $version = Get-InstalledVersionName
        "패키지    : $package $(if ($version) { $version } else { '(버전 확인 불가)' }) 설치됨"
    }
    else {
        "패키지    : $package 설치되지 않음 — dev-install.ps1로 설치할 것"
        '접근성    : 확인 생략(미설치)'
        return
    }

    $listed = (Get-EnabledServices) -contains $service
    $globalOn = (Get-AccessibilityGlobal) -eq '1'
    switch (Test-ServiceBound) {
        'bound' {
            if (-not $globalOn) { '접근성    : 바인딩은 있으나 전역 스위치가 꺼짐 — -A11yOnly로 복구' }
            elseif ($listed) { '접근성    : 목록 등록됨, 바인딩 살아 있음' }
            else { '접근성    : 바인딩은 있으나 목록에 없음 — 재설치 시 꺼진다' }
        }
        'crashed' { '접근성    : 서비스가 크래시 상태 — 로그(GuideSvc) 확인 후 -A11yOnly로 복구' }
        default {
            if ($listed) { '접근성    : 목록엔 있으나 바인딩 없음 — -A11yOnly로 복구' }
            else { '접근성    : 꺼져 있음 — -A11yOnly로 복구' }
        }
    }
    return
}

# --- 빌드 + 설치 ---------------------------------------------------------------
if ($SkipBuild) {
    Write-Host '== 빌드 건너뜀 (-SkipBuild / -A11yOnly) ==' -ForegroundColor DarkGray
}
else {
    Write-Host "== 빌드 + 설치 ($targetSerial) ==" -ForegroundColor Cyan

    # %TEMP% 에서 connect 가 막히므로 저장소 옆에 둔다. gitignore 되어 있다.
    $afUnixTmp = Join-Path $repoRoot '.gradle-afunix-tmp'
    New-Item -ItemType Directory -Force $afUnixTmp | Out-Null
    $env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$afUnixTmp"

    # installDebug는 ANDROID_SERIAL을 본다. 대상 기기를 명시적으로 넘긴다.
    $previousSerial = $env:ANDROID_SERIAL
    $env:ANDROID_SERIAL = $targetSerial
    $gradlew = Join-Path $appDir 'gradlew.bat'
    Push-Location $appDir
    try { Invoke-CheckedNative $gradlew @('installDebug') '빌드/설치' }
    finally {
        Pop-Location
        $env:ANDROID_SERIAL = $previousSerial
    }

    # 설치 후에는 방금 만든 산출물에서 패키지명을 다시 읽는다(첫 빌드면 메타데이터가 없었다).
    $resolved = Resolve-PackageName
    if ($resolved -ne $package) {
        $package = $resolved
        $service = "$package/$serviceClass"
        Write-Host "  대상 패키지: $package"
    }
}

# --- 접근성 서비스 다시 켜기 ----------------------------------------------------
if (-not $SkipAccessibility) {
    Write-Host '== 접근성 서비스 활성화 ==' -ForegroundColor Cyan

    $current = Get-EnabledServices
    # 전역 스위치까지 봐야 한다. 0인 채로 바인딩만 남은 상태를 "이미 켜짐"으로 지나치면
    # 사용자는 켰다고 믿는데 접근성은 꺼져 있다(One UI 8.5 실측).
    if (($current -contains $service) -and ((Test-ServiceBound) -eq 'bound') -and ((Get-AccessibilityGlobal) -eq '1')) {
        Write-Host '  이미 켜져 있음'
    }
    else {
        # 다른 접근성 서비스는 끄지 않는다.
        $without = @($current | Where-Object { $_ -ne $service })
        $next = @($without + $service) -join ':'

        # settings put 만으로는 목록에 값이 들어가도 바인딩이 살아나지 않는 경우가 있다.
        # 전역 스위치를 껐다 켜는 사이클로 서비스를 다시 붙인다.
        #
        # 전역을 내린 뒤 중간에 죽으면 기기의 접근성 전체가 꺼진 채 남는다(adb 호출은 exit≠0에
        # throw한다). run-device-e2e.sh의 restore_davi_accessibility/cleanup과 같은 보호를 건다.
        $restoreNeeded = $true
        try {
            Invoke-AdbChecked @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '0') '접근성 전역 끄기'
            if ($without.Count -gt 0) {
                Invoke-AdbChecked @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ($without -join ':')) '접근성 목록 비우기'
            }
            else {
                Invoke-AdbChecked @('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services') '접근성 목록 비우기'
            }
            Invoke-AdbChecked @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', $next) '접근성 목록 설정'
            Invoke-AdbChecked @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1') '접근성 전역 켜기'
            $restoreNeeded = $false
        }
        finally {
            if ($restoreNeeded) { Restore-AccessibilityState -Original $current }
        }

        # 되읽어 확인한다. put 이 조용히 무시되는 경우(제조사 정책 등)를 잡기 위해서다.
        Start-Sleep -Milliseconds 800
        $bound = Test-ServiceBound
        if ($bound -eq 'bound') {
            Write-Host "  켬: $service"
        }
        else {
            Write-Warning "접근성 서비스가 바인딩되지 않았다(상태: $bound). 설정 앱에서 직접 켤 것."
            try { Invoke-AdbChecked @('shell', 'am', 'start', '-a', 'android.settings.ACCESSIBILITY_SETTINGS') '접근성 설정 화면 열기' }
            catch { Write-Warning "설정 화면을 열지 못했다: $($_.Exception.Message)" }
        }
    }
}

if ($ClearLog) {
    Invoke-AdbChecked @('logcat', '-c') 'adb logcat -c'
    Write-Host '== logcat 버퍼 비움 ==' -ForegroundColor Cyan
}

Write-Host ''
Write-Host '완료. 로그: adb logcat -s GuideSvc' -ForegroundColor Green
