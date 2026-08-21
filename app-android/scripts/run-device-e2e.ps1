<#
.SYNOPSIS
  실제 Gemini와 UIAutomator로 등록된 실기기 안내 시나리오를 실행하고 Markdown 보고서를 만든다.

.EXAMPLE
  .\scripts\run-device-e2e.ps1 -ListScenarios

.EXAMPLE
  .\scripts\run-device-e2e.ps1 -Scenario samsung-clock-create-alarm
#>
[CmdletBinding()]
param(
    [string]$Scenario = '',
    [switch]$ListScenarios,
    [switch]$NoDetailedReport,
    # 앱 1차 마스킹을 끈 debug APK로 설치해 실행한다(#203 비교 실험).
    # local.properties에 DAVI_DISABLE_PRIVACY_MASKING=true를 둔 머신은 이 스위치 없이도
    # 우회 APK가 설치된다. 서버도 DAVI_DISABLE_PRIVACY_MASKING=true로 띄워야 두 겹 모두 우회된다.
    # 원문 화면 label이 실제 Gemini까지 나가므로 합성 데이터만 넣은 전용 테스트 기기에서만 쓴다.
    [switch]$DisablePrivacyMasking
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$appDir = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $appDir
$registryPath = Join-Path $appDir 'device-e2e-scenarios.json'
$reportRenderer = Join-Path $PSScriptRoot 'render-device-e2e-report.py'
$privateReportRenderer = Join-Path $PSScriptRoot 'render-device-e2e-private-report.py'
$serverUrl = 'http://127.0.0.1:8000'
$runner = 'com.davi.helper.e2edriver.test/com.davi.helper.e2e.DaviE2eRunner'
# 앞은 applicationId, 뒤는 클래스의 FQN이다 — **둘의 접두어가 다른 게 정상이다**(#58).
# debug는 applicationIdSuffix로 com.davi.helper.debug가 되지만, 클래스 이름은 namespace
# (com.davi.helper) 기준이라 그대로다. E2E는 debug 빌드를 몰기 때문에 앞쪽에 .debug가 붙는다.
$service = 'com.davi.helper.debug/com.davi.helper.GuideAccessibilityService'

# dumpsys accessibility의 섹션 하나(`라벨:{...}`)를 통째로 잘라낸다. 섹션은 전부 `이름:{` 꼴이라
# 다음 섹션 머리 직전까지가 한 블록이다. dev-install.ps1에 같은 함수가 있다 — 여기 확인은
# dev-install을 부르지 않고 직접 하는 자리라(E2E는 설치를 건드리지 않는다) 한 벌 더 둔다.
# debug는 클래스가 applicationId(com.davi.helper.debug)로 시작하지 않아 dumpsys가 축약형으로
# 줄이지 못한다. 그래서 여기선 완전형 하나만 보면 된다(실측).
#
# **첫 섹션 하나만 돌려준다.** dumpsys는 `User state[...]`를 사용자별로 찍으므로 직장 프로필·
# 보조 사용자가 있으면 같은 라벨이 여러 번 나온다. 계측이 도는 현재 사용자(=첫 블록)만 봐야,
# 보조 사용자에만 붙은 서비스를 우리 것으로 오탐하지 않는다. sh 실행기도 같은 의미다.
#
# 돌려주는 것은 `:{` 뒤부터라 머리 줄이 빠져 있다. 첫 서비스가 머리 줄과 같은 줄에 찍히므로
# 판정에는 차이가 없지만, sh 쪽 같은 이름의 함수는 머리 줄을 포함해 돌려준다.
function Get-DumpsysSection {
    param([string]$Dump, [string]$Label)
    $pattern = "(?ms)^\s*$([regex]::Escape($Label))\s*:\{(.*?)(?=^\s*[A-Za-z][A-Za-z0-9 ]*:\{|\z)"
    $match = [regex]::Match($Dump, $pattern)
    if ($match.Success) { return $match.Groups[1].Value }
    return ''
}

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

function Resolve-Adb {
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($sdk) {
            $candidate = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path $candidate) { return $candidate }
        }
    }
    $localProps = Join-Path $appDir 'local.properties'
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            $sdk = ($line -replace '^\s*sdk\.dir\s*=', '').Trim() -replace '\\\\', '\' -replace '\\:', ':'
            $candidate = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path $candidate) { return $candidate }
        }
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw 'adb를 찾지 못했습니다. ANDROID_HOME 또는 local.properties의 sdk.dir를 확인하세요.'
}

function Resolve-Python {
    $command = Get-Command python -ErrorAction SilentlyContinue
    if ($command) { return @{ Path = $command.Source; Prefix = @() } }
    $launcher = Get-Command py -ErrorAction SilentlyContinue
    if ($launcher) { return @{ Path = $launcher.Source; Prefix = @('-3') } }
    throw 'Python 3를 찾지 못했습니다. python 또는 py launcher를 PATH에 추가하세요.'
}

$registry = Get-Content $registryPath -Raw -Encoding UTF8 | ConvertFrom-Json
$scenarios = @($registry.scenarios)
if ($ListScenarios) {
    $scenarios | ForEach-Object { "{0}`t{1}" -f $_.id, $_.description }
    exit 0
}
if (-not $Scenario) { $Scenario = $registry.default_scenario }
$selected = $scenarios | Where-Object { $_.id -eq $Scenario } | Select-Object -First 1
if (-not $selected) {
    throw "등록되지 않은 시나리오 '$Scenario'입니다. -ListScenarios로 목록을 확인하세요."
}
if ($Scenario -notmatch '^[a-z0-9]+(?:-[a-z0-9]+)*$') {
    throw '시나리오 ID는 소문자·숫자·하이픈만 사용할 수 있습니다.'
}

$python = Resolve-Python
$adb = Resolve-Adb
$gradlew = Join-Path $appDir 'gradlew.bat'
$health = Invoke-RestMethod -Uri "$serverUrl/health" -Method Get -TimeoutSec 10
if ($health.stub_llm -ne $false) {
    throw '127.0.0.1:8000에 DAVI_USE_STUB_LLM=false인 서버가 필요합니다.'
}
$model = if ($health.model) { "$($health.model)" } else { 'unknown' }
# 서버 2차 마스킹을 우회한 실행은 상세 보고서를 만들지 않는다(#203). 상세 보고서는 화면 label과
# 모델 문장을 그대로 담으므로, 우회 실행에서는 그 파일이 원문 화면을 디스크에 남기게 된다.
$serverMaskingDisabled = [bool]$health.privacy_masking_disabled

$deviceLines = Invoke-CapturedNative $adb @('devices') 'adb devices'
$connected = @(
    $deviceLines |
        Where-Object { $_ -match "`tdevice$" } |
        ForEach-Object { ($_ -split "`t")[0] }
)
if ($env:ANDROID_SERIAL) {
    $serial = $env:ANDROID_SERIAL
    if ($connected -notcontains $serial) { throw 'ANDROID_SERIAL에 지정한 기기가 연결 상태가 아닙니다.' }
}
elseif ($connected.Count -eq 1) {
    $serial = $connected[0]
}
else {
    throw '기기가 정확히 하나 연결되어야 합니다. 여러 대면 ANDROID_SERIAL을 지정하세요.'
}
$adbDevice = @('-s', $serial)

function Invoke-AdbCapture {
    param([string[]]$Arguments, [string]$What)
    $result = Invoke-CapturedNative $adb ($adbDevice + $Arguments) $What
    return $result
}
function Invoke-AdbChecked {
    param([string[]]$Arguments, [string]$What)
    Invoke-CheckedNative $adb ($adbDevice + $Arguments) $What
}

$instrumentationResult = [System.IO.Path]::GetTempFileName()
$safeLogResult = [System.IO.Path]::GetTempFileName()
$privateTraceResult = [System.IO.Path]::GetTempFileName()
$detailedReport = -not $NoDetailedReport
if ($serverMaskingDisabled) {
    if ($detailedReport) {
        Write-Warning '서버 2차 마스킹 우회라 상세 보고서 생성을 끕니다(#203).'
    }
    $detailedReport = $false
}
$privateTraceAvailable = $false
$runStartedAt = (Get-Date).ToString('o')
$reportStamp = (Get-Date).ToString("yyyyMMdd'T'HHmmssfffzzz").Replace(':', '')
$reportDir = Join-Path (Join-Path $appDir 'build\reports\device-e2e') $Scenario
$reportPath = Join-Path $reportDir "$reportStamp.md"
$latestReport = Join-Path $reportDir 'latest.md'
$privateReportDir = Join-Path (Join-Path $appDir 'build\reports\device-e2e-private') $Scenario
$privateReportPath = Join-Path $privateReportDir "$reportStamp.md"
$latestPrivateReport = Join-Path $privateReportDir 'latest.md'
$devicePrivateTrace = '/sdcard/Android/data/com.davi.helper.e2edriver/files/davi-e2e-private-trace.jsonl'
$deviceModel = ((Invoke-AdbCapture @('shell', 'getprop', 'ro.product.model') '기기 모델 조회') -join '').Trim()
$androidVersion = ((Invoke-AdbCapture @('shell', 'getprop', 'ro.build.version.release') 'Android 버전 조회') -join '').Trim()
$gitCommit = ((Invoke-CapturedNative 'git' @('-C', $repoRoot, 'rev-parse', '--short', 'HEAD') 'Git commit 조회') -join '').Trim()

$reverseCreated = $false
$daviDisabled = $false
$withoutDavi = ''
$originalAndroidSerial = $env:ANDROID_SERIAL
$originalJavaToolOptions = $env:JAVA_TOOL_OPTIONS

function Restore-DaviAccessibility {
    if (-not $script:daviDisabled) { return }
    $withDavi = if ($script:withoutDavi) { "$($script:withoutDavi):$service" } else { $service }
    Invoke-AdbChecked @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', $withDavi) 'Davi 접근성 복구'
    Invoke-AdbChecked @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1') '접근성 전역 상태 복구'
    $script:daviDisabled = $false
}

try {
    $env:ANDROID_SERIAL = $serial
    # dev-install.ps1과 같은 Windows Gradle AF_UNIX 우회. 기존 옵션은 뒤에 보존한다.
    $afUnixTmp = Join-Path $repoRoot '.gradle-afunix-tmp'
    New-Item -ItemType Directory -Force $afUnixTmp | Out-Null
    $env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$afUnixTmp"
    if ($originalJavaToolOptions) { $env:JAVA_TOOL_OPTIONS += " $originalJavaToolOptions" }

    $reverseLines = Invoke-AdbCapture @('reverse', '--list') 'adb reverse 목록 조회'
    $reverseWasPresent = [bool]($reverseLines | Where-Object { $_ -match 'tcp:8000\s+tcp:8000' })
    if (-not $reverseWasPresent) {
        Invoke-AdbChecked @('reverse', 'tcp:8000', 'tcp:8000') 'adb reverse 설정'
        $reverseCreated = $true
    }

    $enabled = ((Invoke-AdbCapture @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services') '접근성 설정 조회') -join '').Trim()
    if ($enabled -eq 'null') { $enabled = '' }
    $accessibilityEnabled = ((Invoke-AdbCapture @('shell', 'settings', 'get', 'secure', 'accessibility_enabled') '접근성 전역 상태 조회') -join '').Trim()
    $parts = @($enabled -split ':' | Where-Object { $_ })
    if ($parts -notcontains $service -or $accessibilityEnabled -ne '1') {
        throw '실행 전에 기기 설정에서 Davi 접근성 서비스를 직접 켜세요.'
    }
    $withoutDavi = (@($parts | Where-Object { $_ -ne $service }) -join ':')
    if ($withoutDavi) {
        Invoke-AdbChecked @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', $withoutDavi) 'Davi 접근성 임시 중지'
    }
    else {
        Invoke-AdbChecked @('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services') 'Davi 접근성 임시 중지'
    }
    $daviDisabled = $true

    Push-Location $appDir
    try {
        # 우회 속성은 켠 실행에서만 넘긴다. 안 넘기면 local.properties의
        # DAVI_DISABLE_PRIVACY_MASKING이 그대로 적용되므로, 그 파일로 켜 둔 머신에서는
        # 이 설치도 우회 APK가 된다(#203). 실제로 무엇이 설치됐는지는 아래에서 앱이 보고한 값으로 본다.
        $appInstallArgs = @("-PdaviE2eServerUrl=$serverUrl")
        if ($DisablePrivacyMasking) { $appInstallArgs += '-PdaviDisablePrivacyMasking=true' }
        Invoke-CheckedNative $gradlew ($appInstallArgs + ':app:installDebug') 'Davi debug 앱 설치'
        Invoke-CheckedNative $gradlew @(':e2e-driver:installDebug', ':e2e-driver:installDebugAndroidTest') 'E2E driver 설치'
    }
    finally { Pop-Location }

    # 갓 설치된 앱은 첫 실행에서 알림 권한 다이얼로그를 띄운다. 그게 대화 화면을 덮으면
    # 드라이버가 마이크 화면을 못 찾아 davi_conversation_not_ready로 0단계 종료된다
    # (2026-08-21 실측). 접근성·오버레이와 달리 평범한 런타임 권한이라 실행기가 미리 부여한다.
    # Android 12 이하에는 이 권한이 없어 실패하므로 결과를 보지 않는다.
    $appPackage = $service.Split('/')[0]
    try {
        Invoke-AdbCapture @('shell', 'pm', 'grant', $appPackage, 'android.permission.POST_NOTIFICATIONS') '알림 권한 부여' | Out-Null
    }
    catch {
        # 권한이 없는 버전에서는 grant가 실패한다. 다이얼로그도 뜨지 않으므로 그대로 진행한다.
    }

    Invoke-AdbChecked @('logcat', '-c') 'logcat 초기화'
    Restore-DaviAccessibility

    $connectedCallback = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $logs = Invoke-AdbCapture @('logcat', '-d', '-s', 'GuideSvc:I', '*:S') '접근성 연결 로그 조회'
        if (($logs -join "`n") -match 'connected server=') { $connectedCallback = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $connectedCallback) { throw 'Davi 접근성 서비스의 실제 연결 callback을 30초 안에 확인하지 못했습니다.' }

    # 앱 1차 상태는 **설치된 앱이 스스로 보고한 값**으로 읽는다(#203). 실행기 스위치로 짐작하면
    # local.properties의 DAVI_DISABLE_PRIVACY_MASKING으로 켠 경우를 "적용"으로 잘못 알린다.
    $appMaskingDisabled = ((Invoke-AdbCapture @('logcat', '-d', '-s', 'GuideSvc:I', '*:S') `
        '앱 마스킹 상태 조회') -join "`n") -match 'privacyMasking=DISABLED'
    if ($appMaskingDisabled -and $detailedReport) {
        Write-Warning '앱 1차 마스킹 우회라 상세 보고서 생성을 끕니다(#203).'
        $detailedReport = $false
    }
    # 두 겹의 상태를 각각 알린다. 한쪽만 우회한 실행도 유효한 비교지만, 어느 겹이 켜져 있었는지
    # 모른 채 결과를 읽으면 안 된다(#203 안전 경계).
    $appMaskingLabel = if ($appMaskingDisabled) { '우회' } else { '적용' }
    $serverMaskingLabel = if ($serverMaskingDisabled) { '우회' } else { '적용' }
    Write-Host "개인정보 마스킹 — 앱 1차: $appMaskingLabel / 서버 2차: $serverMaskingLabel"

    # 오버레이 권한(SYSTEM_ALERT_WINDOW) 점검은 없앴다 — #240이 매니페스트에서 걷어냈고 안내
    # 오버레이는 TYPE_ACCESSIBILITY_OVERLAY라 그 권한이 없어도 그려진다(#56). sh 실행기와 같다.
    $accessibilityState = (Invoke-AdbCapture @('shell', 'dumpsys', 'accessibility') '접근성 서비스 상태 조회') -join "`n"
    # 컴포넌트 전체로 본다(#58·#302). 패키지 접두어만 보면 release가 bound된 기기에서 debug가
    # 죽어 있어도 통과한다 — 두 앱이 함께 깔릴 수 있게 된 뒤로 실재하는 조합이다.
    # 섹션은 블록으로 자른다. 바인딩된 서비스가 둘 이상이면 두 번째부터 줄이 바뀌어서, 한 줄
    # 정규식은 첫 서비스만 본다(#302 실측).
    if (-not (Get-DumpsysSection $accessibilityState 'Bound services').Contains($service) -or
        (Get-DumpsysSection $accessibilityState 'Crashed services').Contains($service)) {
        throw 'Davi 접근성 서비스가 bound 상태가 아닙니다. 설정에서 껐다 켠 뒤 다시 실행하세요.'
    }
    $instrumentationArgs = $adbDevice + @(
        'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'davi.liveGemini', 'true'
    )
    if (-not $detailedReport) {
        $instrumentationArgs += @('-e', 'davi.detailedReport', 'false')
    }
    $instrumentationArgs += @('-e', 'class', "$($selected.test_class)", $runner)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    $writer = [System.IO.StreamWriter]::new($instrumentationResult, $false, $utf8NoBom)
    try {
        & $adb @instrumentationArgs 2>&1 | ForEach-Object {
            $line = "$_"
            Write-Host $line
            $writer.WriteLine($line)
        }
        $instrumentationStatus = $LASTEXITCODE
    }
    finally {
        $writer.Dispose()
        $ErrorActionPreference = $previous
    }

    $safeLogs = @(
        Invoke-AdbCapture @('logcat', '-d', '-s', 'GuideSvc:I', 'DaviE2e:I', '*:S') '비식별 검증 로그 조회' |
            ForEach-Object { $_ -replace '(dogfoodDeviceId|device_id)=\S+', '$1=[redacted]' }
    )
    [System.IO.File]::WriteAllLines($safeLogResult, [string[]]$safeLogs, $utf8NoBom)
    Write-Host ''
    Write-Host '비식별 실기기 검증 로그:' -ForegroundColor Cyan
    $safeLogs | Where-Object { $_ -match 'GuideSvc|DaviE2e' } | ForEach-Object { Write-Host $_ }

    if ($detailedReport) {
        try {
            Invoke-AdbChecked @('pull', $devicePrivateTrace, $privateTraceResult) '상세 분석 trace 가져오기'
            $privateTraceAvailable = $true
        }
        catch {
            Write-Warning '상세 분석 trace를 가져오지 못했습니다. 기본 비식별 보고서는 계속 생성합니다.'
        }
    }

    New-Item -ItemType Directory -Force $reportDir | Out-Null
    $renderArgs = $python.Prefix + @(
        $reportRenderer,
        '--instrumentation-result', $instrumentationResult,
        '--safe-log', $safeLogResult,
        '--output', $reportPath,
        '--model', $model,
        '--device-model', $deviceModel,
        '--android-version', $androidVersion,
        '--commit', $gitCommit,
        '--started-at', $runStartedAt,
        '--scenario', $Scenario,
        '--scenario-description', "$($selected.description)",
        '--test-class', "$($selected.test_class)",
        '--shell-status', "$instrumentationStatus"
    )
    Invoke-CheckedNative $python.Path $renderArgs '결과 문서 생성'
    Copy-Item $reportPath $latestReport -Force
    Write-Host ''
    Write-Host "결과 문서: $reportPath" -ForegroundColor Green
    Write-Host "최신 결과: $latestReport" -ForegroundColor Green
    if ($detailedReport -and $privateTraceAvailable) {
        $privateRenderArgs = $python.Prefix + @(
            $privateReportRenderer,
            '--trace', $privateTraceResult,
            '--output', $privateReportPath,
            '--model', $model,
            '--device-model', $deviceModel,
            '--android-version', $androidVersion,
            '--commit', $gitCommit,
            '--started-at', $runStartedAt,
            '--scenario', $Scenario
        )
        Invoke-CheckedNative $python.Path $privateRenderArgs '상세 분석 문서 생성'
        Copy-Item $privateReportPath $latestPrivateReport -Force
        Write-Host "상세 분석 문서(로컬 전용): $privateReportPath" -ForegroundColor Yellow
        Write-Host "최신 상세 분석(로컬 전용): $latestPrivateReport" -ForegroundColor Yellow
    }

    $instrumentationText = Get-Content $instrumentationResult -Raw
    if ($instrumentationStatus -ne 0 -or
        $instrumentationText -notmatch '(?m)^INSTRUMENTATION_CODE: -1\r?$' -or
        $instrumentationText -notmatch '(?m)^OK \(\d+ tests?\)\r?$' -or
        $instrumentationText -match '(?m)^FAILURES!!!') {
        throw '실기기 instrumentation이 실패했습니다. 화면/안내 원문은 출력하지 않았습니다.'
    }
}
finally {
    try { Restore-DaviAccessibility } catch { Write-Warning $_ }
    # Davi 앱은 유지하고 launcher가 없는 테스트 전용 helper/test 패키지만 치운다.
    $uninstallTestArgs = $adbDevice + @('uninstall', 'com.davi.helper.e2edriver.test')
    $uninstallDriverArgs = $adbDevice + @('uninstall', 'com.davi.helper.e2edriver')
    try { & $adb @uninstallTestArgs *> $null } catch { }
    try { & $adb @uninstallDriverArgs *> $null } catch { }
    if ($reverseCreated) {
        $removeReverseArgs = $adbDevice + @('reverse', '--remove', 'tcp:8000')
        try { & $adb @removeReverseArgs *> $null } catch { }
    }
    Remove-Item $instrumentationResult, $safeLogResult, $privateTraceResult -Force -ErrorAction SilentlyContinue
    if ($null -eq $originalAndroidSerial) { Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue }
    else { $env:ANDROID_SERIAL = $originalAndroidSerial }
    if ($null -eq $originalJavaToolOptions) { Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue }
    else { $env:JAVA_TOOL_OPTIONS = $originalJavaToolOptions }
}
