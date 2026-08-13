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
    [switch]$NoDetailedReport
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
$service = 'com.davi.helper/com.davi.helper.GuideAccessibilityService'

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
        Invoke-CheckedNative $gradlew @("-PdaviE2eServerUrl=$serverUrl", ':app:installDebug') 'Davi debug 앱 설치'
        Invoke-CheckedNative $gradlew @(':e2e-driver:installDebug', ':e2e-driver:installDebugAndroidTest') 'E2E driver 설치'
    }
    finally { Pop-Location }

    Invoke-AdbChecked @('logcat', '-c') 'logcat 초기화'
    Restore-DaviAccessibility

    $connectedCallback = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $logs = Invoke-AdbCapture @('logcat', '-d', '-s', 'GuideSvc:I', '*:S') '접근성 연결 로그 조회'
        if (($logs -join "`n") -match 'connected server=') { $connectedCallback = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $connectedCallback) { throw 'Davi 접근성 서비스의 실제 연결 callback을 30초 안에 확인하지 못했습니다.' }

    $accessibilityState = (Invoke-AdbCapture @('shell', 'dumpsys', 'accessibility') '접근성 서비스 상태 조회') -join "`n"
    if ($accessibilityState -notmatch 'Bound services:.*com\.davi\.helper' -or
        $accessibilityState -match 'Crashed services:.*com\.davi\.helper') {
        throw 'Davi 접근성 서비스가 bound 상태가 아닙니다. 설정에서 껐다 켠 뒤 다시 실행하세요.'
    }
    $overlayState = (Invoke-AdbCapture @('shell', 'appops', 'get', 'com.davi.helper', 'SYSTEM_ALERT_WINDOW') '오버레이 권한 조회') -join "`n"
    if ($overlayState -notmatch 'allow') { throw 'Davi의 다른 앱 위에 표시 권한을 기기 설정에서 허용하세요.' }

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

    $safeLogs = Invoke-AdbCapture @('logcat', '-d', '-s', 'GuideSvc:I', 'DaviE2e:I', '*:S') '비식별 검증 로그 조회'
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
