<#
.SYNOPSIS
  앱을 빌드해 연결된 기기에 설치하고, 접근성 서비스를 다시 켠다.

.DESCRIPTION
  Android Studio를 열지 않고 실기기 검증 루프를 돌리기 위한 스크립트다.

  두 가지 함정을 여기서 흡수한다.

  1) Gradle CLI가 "Unable to establish loopback connection"으로 죽는 문제
     원인은 TCP 루프백이 아니라 **AF_UNIX(유닉스 도메인 소켓)** 다.
     JDK 17+ 는 Windows에서 Selector.open() 에 AF_UNIX 소켓 쌍을 쓰는데,
     그 소켓 파일을 %TEMP% 에 만들면 이 PC에서 connect 가 "Invalid argument" 로 실패한다
     (bind 는 되는데 connect 만 실패한다). 소켓 파일 위치를 옮기면 정상 동작한다.
     -> JAVA_TOOL_OPTIONS 로 jdk.net.unixdomain.tmpdir 를 저장소 안 폴더로 지정한다.

  2) 재설치하면 접근성 권한이 꺼진다 (HANDOFF 12장)
     -> 설치 후 adb 로 다시 켠다. 로컬 테스트 전용이다.

.EXAMPLE
  ./scripts/dev-install.ps1
  ./scripts/dev-install.ps1 -SkipAccessibility
#>
[CmdletBinding()]
param(
    # 접근성 서비스를 자동으로 켜지 않는다.
    [switch]$SkipAccessibility,
    # 설치 후 logcat 버퍼를 비운다. 바로 로그를 볼 때 편하다.
    [switch]$ClearLog
)

$ErrorActionPreference = 'Stop'

# 한글 출력이 깨지지 않게.
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# 네이티브 명령(gradlew, adb)은 stderr에 정상 메시지를 흘린다.
# PowerShell 5.1은 ErrorActionPreference=Stop 이면 그걸 오류로 보므로, 호출 동안만 완화하고
# 성공 여부는 $LASTEXITCODE 로 판단한다.
function Invoke-Native {
    param([scriptblock]$Command, [string]$What)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Command } finally { $ErrorActionPreference = $prev }
    if ($LASTEXITCODE -ne 0) { throw "$What 실패 (exit $LASTEXITCODE)" }
}

$appDir = Split-Path -Parent $PSScriptRoot          # app-android/
$repoRoot = Split-Path -Parent $appDir              # 저장소 루트
$service = 'com.davi.helper/com.davi.helper.GuideAccessibilityService'

# --- 1) AF_UNIX 소켓 임시 폴더 -------------------------------------------------
# %TEMP% 에서 connect 가 막히므로 저장소 옆에 둔다. gitignore 되어 있다.
$afUnixTmp = Join-Path $repoRoot '.gradle-afunix-tmp'
New-Item -ItemType Directory -Force $afUnixTmp | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$afUnixTmp"

# --- 2) adb 찾기 ---------------------------------------------------------------
function Resolve-Adb {
    $localProps = Join-Path $appDir 'local.properties'
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match '^\s*sdk\.dir\s*=' }
        if ($line) {
            # sdk.dir=D\:\\Android\\Sdk  형태의 이스케이프를 되돌린다
            $sdk = ($line -replace '^\s*sdk\.dir\s*=', '').Trim() -replace '\\\\', '\' -replace '\\:', ':'
            $candidate = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path $candidate) { return $candidate }
        }
    }
    $fallback = Get-Command adb -ErrorAction SilentlyContinue
    if ($fallback) { return $fallback.Source }
    throw 'adb를 찾지 못했다. local.properties 의 sdk.dir 를 확인할 것.'
}

$adb = Resolve-Adb

# --- 3) 빌드 + 설치 ------------------------------------------------------------
Write-Host '== 빌드 + 설치 ==' -ForegroundColor Cyan
$gradlew = Join-Path $appDir 'gradlew.bat'
Push-Location $appDir
try { Invoke-Native { & $gradlew installDebug } '빌드/설치' }
finally { Pop-Location }

# --- 4) 접근성 서비스 다시 켜기 -------------------------------------------------
if (-not $SkipAccessibility) {
    Write-Host '== 접근성 서비스 활성화 ==' -ForegroundColor Cyan
    $current = ''
    Invoke-Native { $script:raw = & $adb shell settings get secure enabled_accessibility_services } 'adb settings get'
    if ($script:raw) { $current = "$script:raw".Trim() }
    if ($current -eq 'null') { $current = '' }

    if ($current -split ':' -contains $service) {
        Write-Host '  이미 켜져 있음'
    }
    else {
        # 다른 서비스를 끄지 않도록 덧붙인다.
        $next = if ($current) { "$current`:$service" } else { $service }
        Invoke-Native { & $adb shell settings put secure enabled_accessibility_services $next } 'adb settings put'
        Invoke-Native { & $adb shell settings put secure accessibility_enabled 1 } 'adb settings put'
        Write-Host "  켬: $service"
    }
}

if ($ClearLog) {
    Invoke-Native { & $adb logcat -c } 'adb logcat -c'
    Write-Host '== logcat 버퍼 비움 ==' -ForegroundColor Cyan
}

Write-Host ''
Write-Host '완료. 로그: adb logcat -s GuideSvc' -ForegroundColor Green
