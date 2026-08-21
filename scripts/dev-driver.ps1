<#
davi 실기기 검증 드라이버 — 서버·앱·기기를 한 진입점에서 다룬다.
사용법과 측정값은 같은 폴더의 README.md. 여기 담긴 명령은 전부 실기기에서 검증된 것이다.

**Windows 전용이다** (Windows PowerShell 5.1). 맥에서는 안 돈다 — 손으로 치는 절차가
app-android/README.md에 그대로 남아 있고, 이식은 #72다.

기기·권한 판정은 여기서 다시 만들지 않고 app-android/scripts/dev-install.ps1 -Status에
위임한다. 사람·맥(#72)과 같은 근거를 쓰기 위해서다.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('env', 'adb-fix', 'server', 'server-stop', 'test', 'install', 'shot', 'shots', 'step', 'swipe', 'log', 'tap', 'key', 'url', 'worktree-init', 'e2e')]
    [string]$Command,

    [Parameter(Position = 1)] [string]$Arg1,
    [Parameter(Position = 2)] [string]$Arg2,
    [Parameter(Position = 3)] [string]$Arg3,
    [Parameter(Position = 4)] [string]$Arg4,

    # server: 실모델(LLM) 사용 — server/.env의 키 필요. 기본은 스텁.
    [switch]$Real,
    # server: 스텁을 몽키패치하는 프로브 파일(.py) 경로. 프로브 전용 env는 호출 전에 설정한다.
    [string]$Probe = '',
    # log: 버퍼 비우기 / install: 설치 후 logcat 비우기
    [switch]$Clear,
    # install: 빌드 없이 권한만 복구(dev-install의 -A11yOnly)
    [switch]$SkipBuild,
    # tap·key·step: 조작 직후 캡처를 백그라운드로 예약한다(기기에 쌓아 두고 shots로 회수).
    [switch]$Shot,
    # log·step: 델타가 이보다 길면 **뒤에서** N줄만 보여 준다(잘린 사실은 함께 찍는다).
    # 커서를 무시하는 옵션이 아니다 — 버퍼 전체는 log -Full이다.
    [int]$Lines = 200,
    [switch]$Full
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# scripts\ → 저장소 루트. **대상은 이 스크립트가 놓인 레포다** — 워크트리에 사본이
# 있으면 그 워크트리를 빌드·설치한다. 어느 코드가 기기에 올라가는지 먼저 확인할 것.
$root = Split-Path -Parent $PSScriptRoot
$appDir = Join-Path $root 'app-android'
$serverDir = Join-Path $root 'server'
$serverPy = Join-Path $serverDir '.venv\Scripts\python.exe'
$localProps = Join-Path $appDir 'local.properties'
$localPropsBak = "$localProps.bak"
$devInstall = Join-Path $appDir 'scripts\dev-install.ps1'
# 기기에 캡처를 쌓아 두는 자리. 전송은 회수(shots pull) 시점으로 미룬다.
$deviceShotDir = '/sdcard/davi_shots'
# log·step이 "지난번 이후"만 보여 주기 위한 커서(logcat -T에 넣을 시각).
$logCursor = Join-Path $appDir 'captures\.log-cursor'

# 네이티브 명령(adb, gradlew)은 stderr에 정상 메시지를 흘린다 — dev-install.ps1과 같은 우회.
function Invoke-Native {
    param([scriptblock]$Block, [string]$What)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Block } finally { $ErrorActionPreference = $prev }
    if ($LASTEXITCODE -ne 0) { throw "$What 실패 (exit $LASTEXITCODE)" }
}

# 캡처를 백그라운드로 던진다. 호출자는 기동분(실측 16~102ms)만 부담하고 바로 다음으로 간다.
# 동기 캡처가 1.3초인 이유는 전송이 아니라 **기기의 PNG 인코딩**이다(실측: raw 263ms + PNG 1336ms).
# 그 1.3초를 조작 흐름에서 걷어내는 게 이 함수의 전부다. 파일은 기기에 남고 shots로 회수한다.
function Start-BackgroundShot {
    param([string]$Adb, [string]$Tag = '')
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    # 태그에 공백이 섞이면 파일명이 쪼개진다. Start-Process가 인자를 인용하지 않고 공백으로
    # 잇기만 하기 때문이다(adb도 shell 뒤를 다시 공백으로 이어 기기 셸이 재파싱한다 —
    # 무이스케이프가 두 번 상쇄돼 `;`가 살아 도는 것이지 인용이 되는 게 아니다).
    $safeTag = ($Tag -replace '[^\w\-]', '')
    $name = if ($safeTag) { "$stamp-$safeTag.png" } else { "$stamp.png" }
    # **임시 이름으로 찍고 다 되면 옮긴다.** screencap은 대상 파일에 점진적으로 쓰기 때문에,
    # 부모가 먼저 끝나 프로세스가 회수되면 크기가 0이 아닌 **깨진 PNG**가 남는다.
    # 원자화하면 목록에 아예 안 보이므로 "찍혔다는데 없다"가 정직한 신호가 된다.
    Start-Process -FilePath $Adb -WindowStyle Hidden -ArgumentList `
        'shell', "mkdir -p $deviceShotDir; screencap -p $deviceShotDir/.$name.part && mv $deviceShotDir/.$name.part $deviceShotDir/$name" | Out-Null
    "$deviceShotDir/$name"
}

# 지난 호출 이후의 GuideSvc 로그만. 버퍼 전체를 훑는 `logcat -d -s`는 쌓이면 13초까지 간다.
#
# **`-t N`을 쓰지 말 것.** logcat은 태그 필터보다 **먼저** 버퍼 마지막 N줄을 자른다 —
# `-t 400 -s GuideSvc`가 실측 0줄이었다(2026-08-21, 같은 버퍼에서 `-s GuideSvc`는 182줄).
# 빨라 보이는 게 아니라 **아무것도 안 준다.** 구간을 자를 때는 반드시 시간(`-T`)으로 자른다.
# `-T`는 'MM-DD HH:MM:SS.mmm'과 epoch('<초>.000') 둘 다 받는다(둘 다 실측 확인).
# 태그에 레벨을 안 붙이면 D까지 들어온다(`-s GuideSvc` = `-s GuideSvc:D` = 182줄) —
# 화면 요소 상세(GuideScreenLog)가 Log.d라 이 기본값을 낮추면 안 된다.
function Get-LogDelta {
    param([string]$Adb, [int]$Tail)
    $cursor = if (Test-Path $logCursor) { (Get-Content $logCursor -Raw).Trim() } else { '' }
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # 커서가 없으면(첫 호출·-Clear 직후) 기기 시계 기준 최근 5분으로 창을 연다.
        if (-not $cursor) {
            $epoch = ("$(& $Adb shell date +%s)").Trim()
            if ($epoch -match '^\d+$') { $cursor = "$([int64]$epoch - 300).000" }
        }
        $lines = if ($cursor) { & $Adb logcat -d -T $cursor -s GuideSvc } else { & $Adb logcat -d -s GuideSvc }
        $code = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $prev }
    $lines = @($lines | Where-Object { $_ -notmatch '^-+ beginning of' })
    # -T는 그 시각을 **포함**해 되돌려준다. 같은 밀리초에 여러 줄이 남는 일이 흔하므로
    # (직렬화 루프) 한 줄만 걷어내면 나머지가 다음 호출에 중복으로 다시 나온다 — 연속 구간을 다 건다.
    if ($cursor) { while ($lines.Count -gt 0 -and $lines[0].StartsWith($cursor)) { $lines = @($lines | Select-Object -Skip 1) } }
    $last = $lines | Where-Object { $_ -match '^\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d' } | Select-Object -Last 1
    if ($last) {
        New-Item -ItemType Directory -Force (Split-Path -Parent $logCursor) | Out-Null
        Set-Content -Path $logCursor -Value $last.Substring(0, 18) -Encoding UTF8
    }
    else {
        # 0줄은 "새 로그 없음"과 "adb 실패"와 "커서가 미래"(연말 롤오버·NTP 되감김·기기 교체)를
        # 다 뜻한다. 셋 다 조용하면 `-t N`과 같은 실패가 된다 — 무엇 때문인지 한 줄로 남긴다.
        "(델타 0줄 — 커서='$cursor', adb exit=$code. 계속 0줄이면 log -Clear로 커서를 버린다)"
    }
    # 줄 수 상한은 호스트에서 자른다(위 이유로 기기 쪽 -t는 못 쓴다).
    # **자른 사실을 반드시 알린다** — 커서는 이미 마지막 줄로 갔으므로 잘린 앞부분은 다시 안 나온다.
    if ($Tail -gt 0 -and $lines.Count -gt $Tail) {
        $dropped = $lines.Count - $Tail
        $lines = @("(앞 $($dropped)줄 생략 — -Lines를 올려야 보인다. 이 줄들은 다시 안 나온다)") + @($lines | Select-Object -Last $Tail)
    }
    $lines
}

# git은 경로를 슬래시로, PowerShell은 백슬래시로 준다. 비교 전에 한 모양으로 맞춘다.
function Format-PathKey { param([string]$P) ($P -replace '/', '\').TrimEnd('\').ToLowerInvariant() }

# adb는 local.properties의 sdk.dir에서 찾는다(팀원마다 경로가 다르다).
function Resolve-Adb {
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($sdk) {
            $candidate = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path $candidate) { return $candidate }
        }
    }
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            $sdk = ($line -replace '^\s*sdk\.dir\s*=', '').Trim() -replace '\\\\', '\' -replace '\\:', ':'
            $candidate = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path $candidate) { return $candidate }
        }
    }
    $fallback = Get-Command adb -ErrorAction SilentlyContinue
    if ($fallback) { return $fallback.Source }
    throw 'adb를 찾지 못했다. app-android/local.properties의 sdk.dir 확인(워크트리면 worktree-init 먼저).'
}

# 기기가 둘 이상인데 대상을 안 정하면 adb가 `more than one device`를 stderr로 뱉는다.
# driver의 adb 호출은 -s를 안 붙이므로(dev-install은 -Serial·ANDROID_SERIAL을 지원한다)
# 여기서 미리 끊어 준다 — 안 그러면 명령마다 다른 모양으로 깨져 원인이 안 보인다.
function Assert-SingleDevice {
    param([string]$Adb)
    if ($env:ANDROID_SERIAL) { return }
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $lines = & $Adb devices } finally { $ErrorActionPreference = $prev }
    $devices = @($lines | Where-Object { $_ -match '^\S+\s+device$' })
    if ($devices.Count -gt 1) {
        throw "기기가 $($devices.Count)대다. `$env:ANDROID_SERIAL로 대상을 정할 것: $(($devices | ForEach-Object { ($_ -split '\s+')[0] }) -join ', ')"
    }
}

# gradlew 전제: JAVA_HOME(JDK 17+) + AF_UNIX 소켓 임시 폴더(%TEMP%면 connect가 죽는다).
function Set-JavaEnv {
    if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
        # 버전을 박지 않는다 — 팀원마다 JDK 패치 버전이 다르다. 흔한 설치 위치만 훑는다.
        # **이름 정렬은 안 된다** — 사전순이라 jdk-8.0.x가 jdk-21.x를 이긴다. 버전으로 정렬한다.
        # Android Studio 번들 JBR만 가진 팀원도 있어 그 경로를 후보에 넣는다(app-android/README.md §Gradle).
        $jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium\jdk-*', 'C:\Program Files\Java\jdk-*' `
            -Directory -ErrorAction SilentlyContinue |
            Sort-Object { [version](($_.Name -replace '^jdk-', '') -replace '[^\d.].*$', '').Trim('.') } -Descending |
            Select-Object -First 1
        $jbr = 'C:\Program Files\Android\Android Studio\jbr'
        if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
        elseif (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
        else { throw 'JAVA_HOME이 없다. JDK 17+ 설치 경로를 JAVA_HOME으로 지정할 것.' }
    }
    $afunix = Join-Path $root '.gradle-afunix-tmp'
    New-Item -ItemType Directory -Force $afunix | Out-Null
    $env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$afunix"
}

function Get-ServerListener {
    Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
}

# --- 서버 URL --------------------------------------------------------------------
# URL은 빌드에 박힌다. 값이 바뀌면 재빌드·재설치까지 해야 실제로 바뀐 것이다.
function Get-UrlKind {
    param([string]$Url)
    if (-not $Url) { return 'none' }
    if ($Url -match '127\.0\.0\.1|localhost|10\.0\.2\.2') { return 'local' }
    if ($Url -match 'azurecontainerapps\.io') { return 'azure' }
    if ($Url -match '://(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)') { return 'wifi' }
    return 'other'
}

function Get-CurrentUrl {
    if (-not (Test-Path $localProps)) { return '' }
    $lines = [System.IO.File]::ReadAllLines($localProps, [System.Text.Encoding]::UTF8)
    $line = $lines | Where-Object { $_ -match '^\s*DAVI_DEBUG_SERVER_URL\s*=' } | Select-Object -First 1
    if (-not $line) { return '' }
    return ($line -replace '^\s*DAVI_DEBUG_SERVER_URL\s*=', '').Trim()
}

# 주석으로 남겨 둔 후보까지 긁어 종류별로 하나씩 모은다. url set local 같은 축약을 쓰기 위해서다.
function Get-UrlCandidates {
    $found = [ordered]@{}
    if (-not (Test-Path $localProps)) { return $found }
    foreach ($line in [System.IO.File]::ReadAllLines($localProps, [System.Text.Encoding]::UTF8)) {
        foreach ($m in [regex]::Matches($line, 'DAVI_DEBUG_SERVER_URL\s*=\s*(\S+)')) {
            $url = $m.Groups[1].Value
            $kind = Get-UrlKind $url
            if (-not $found.Contains($kind)) { $found[$kind] = $url }
        }
    }
    return $found
}

function Set-ServerUrl {
    param([string]$Url)
    $lines = [System.IO.File]::ReadAllLines($localProps, [System.Text.Encoding]::UTF8)
    $hasKey = @($lines | Where-Object { $_ -match '^\s*DAVI_DEBUG_SERVER_URL\s*=' }).Count -gt 0
    $updated = $lines | ForEach-Object {
        if ($_ -match '^\s*DAVI_DEBUG_SERVER_URL\s*=') { "DAVI_DEBUG_SERVER_URL=$Url" } else { $_ }
    }
    if (-not $hasKey) { $updated = @($updated) + "DAVI_DEBUG_SERVER_URL=$Url" }
    # BOM 없는 UTF-8. gradle이 읽는 properties 파일이라 BOM을 붙이면 첫 키가 깨진다.
    [System.IO.File]::WriteAllLines($localProps, [string[]]$updated, (New-Object System.Text.UTF8Encoding($false)))
}

# 파일만 되돌리면 APK에는 옛 URL이 남는다. 교체·원복 뒤에는 항상 이걸 태운다.
function Invoke-Reinstall {
    Set-JavaEnv
    # 실패를 삼키면 안 된다. 빌드가 죽었는데 조용히 넘어가면 local.properties는 새 URL,
    # APK는 옛 URL인 상태가 되고 env는 새 URL을 보여 준다 — 진단이 정확히 반대로 나온다.
    # **dev-install은 exit code가 아니라 throw로 실패를 알린다**(EAP=Stop). 그래서 Invoke-Native로
    # 감싸지 않는다 — 남아 있던 $LASTEXITCODE를 읽어 성공을 실패로 뒤집을 수 있다. 예외는 그대로 올린다.
    & $devInstall
}

function Get-MainWorktree {
    $line = (& git -C $root worktree list --porcelain) | Where-Object { $_ -like 'worktree *' } | Select-Object -First 1
    if (-not $line) { return '' }
    return ($line -replace '^worktree ', '').Trim()
}

switch ($Command) {

    # 전제조건 일람. 리뷰 세션은 이것부터 한 번 돌리면 환경 탐색이 끝난다.
    'env' {
        if (-not (Test-Path $localProps)) {
            '기기      : 확인 불가(local.properties 없음 — worktree-init 먼저)'
        }
        elseif (Select-String -Path $devInstall -Pattern '\[switch\]\$Status' -Quiet) {
            # 기기·패키지·접근성은 dev-install이 판정한다(오버레이 판정은 #212로 걷혔다).
            & $devInstall -Status
        }
        else {
            # 워크트리에 이 파일만 남은 채 #157 이전 코드를 체크아웃한 경우다. 최소한만 보여준다.
            $adbEarly = Resolve-Adb
            $devices = @((& $adbEarly devices) | Where-Object { $_ -match "`tdevice$" })
            "기기      : $($devices.Count)대 (이 브랜치의 dev-install엔 -Status가 없다 — #157 이후 브랜치에서 상세)"
        }

        $url = Get-CurrentUrl
        $kind = Get-UrlKind $url
        "서버 URL  : $(if ($url) { "$url [$kind]" } else { '설정 없음' })"

        $adb = Resolve-Adb
        # 세션을 여는 명령이라 여기서 한 번만 본다(step·log에 매번 붙이면 그 비용이 아깝다).
        # adb는 ANDROID_SERIAL을 스스로 읽으므로 그것만 정해 두면 driver 전체가 그 기기를 쓴다.
        Assert-SingleDevice -Adb $adb
        # PS 5.1은 EAP=Stop에서 네이티브 stderr 리다이렉트(`2>&1`)를 종료 에러로 만든다.
        # 여기서 그걸 하면 기기 미연결·unauthorized·v40/v41 충돌처럼 **env가 가장 필요한 순간**에
        # 이 줄부터 아래(포트·venv·.env)가 통째로 안 나오고 예외로 끝난다. EAP를 낮춰 부른다.
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { $reverse = & $adb reverse --list } finally { $ErrorActionPreference = $prevEap }
        $hasReverse = @($reverse) -match 'tcp:8000\s+tcp:8000'
        "reverse   : $(if ($hasReverse) { 'tcp:8000 연결됨' } elseif ($kind -eq 'local') { '없음 — 로컬 서버인데 adb-fix 필요' } else { '없음(로컬 서버 아니면 정상)' })"

        $listener = Get-ServerListener
        "포트 8000 : $(if ($listener) { "listening (pid $($listener.OwningProcess))" } else { 'idle' })"
        # venv가 없어도 막히지 않는다 — server가 본 레포 venv를 빌려 쓴다. 그 사실까지 한 줄에 적는다.
        $venvNote = if (Test-Path $serverPy) { '있음' }
        else {
            $m = Get-MainWorktree
            if ($m -and (Test-Path (Join-Path $m 'server\.venv\Scripts\python.exe'))) { '없음 — server가 본 레포 venv를 빌려 쓴다(코드는 이 레포)' }
            else { '없음, 본 레포에도 없음 — server\ 에서 python -m venv .venv' }
        }
        "server venv: $venvNote / .env: $(Test-Path (Join-Path $serverDir '.env'))"
    }

    # adb v40/v41 충돌 복구. 충돌 후 reverse가 날아가 앱이 조용히 서버에 못 붙으므로 항상 재설정.
    'adb-fix' {
        # PS 5.1: EAP=Stop에서 네이티브 stderr 리다이렉트는 종료 에러가 된다(실측) — try로 감싼다.
        try { taskkill /F /IM adb.exe 2>$null | Out-Null } catch { }
        Start-Sleep -Milliseconds 500
        $adb = Resolve-Adb
        Invoke-Native { & $adb start-server } 'adb start-server'
        Invoke-Native { & $adb reverse tcp:8000 tcp:8000 } 'adb reverse'
        Invoke-Native { & $adb devices } 'adb devices'
    }

    # 서버 기동(백그라운드). 기본 스텁(키 불필요) / -Real 실모델 / -Probe 프로브 모듈.
    'server' {
        if (Get-ServerListener) { throw '8000 포트에 이미 리스너가 있다. server-stop 먼저.' }
        # 워크트리에는 server\.venv가 없다(worktree-init은 local.properties만 복사한다).
        # 인터프리터는 본 레포 venv를 빌려 쓰되 **작업 디렉터리는 이 레포**로 둔다 —
        # 그래서 도는 코드는 언제나 이 레포의 server\app이다. 아래 "code=" 줄이 그걸 찍는다.
        $py = $serverPy
        if (-not (Test-Path $py)) {
            $main = Get-MainWorktree
            $mainPy = if ($main) { Join-Path $main 'server\.venv\Scripts\python.exe' } else { $null }
            if ($mainPy -and (Test-Path $mainPy)) {
                $py = $mainPy
                "venv 없음 → 본 레포 venv를 빌려 쓴다: $py"
            }
            else { throw "venv 없음: $serverPy (본 레포에도 없다 — server에서 python -m venv .venv)" }
        }
        $env:PYTHONUTF8 = '1'
        # config.py의 env_file은 ".env"라 **cwd 기준**이다. 워크트리에는 .env가 없어서
        # -Real이 키 없이 조용히 뜬다. 파일을 복사하지 않고(비밀값 사본을 늘리지 않는다)
        # 본 레포 .env를 읽어 자식 프로세스 환경변수로만 넘긴다 — 값은 찍지 않는다.
        #
        # **driver가 직접 정하는 키는 임포트에서 뺀다.** .env.example에 DAVI_USE_STUB_LLM이
        # 표준 키로 들어 있어서, 그냥 임포트하면 `-Real`인데 스텁으로 뜨는(또는 그 반대)
        # 조용한 뒤집힘이 생긴다 — 환경변수가 .env보다 우선이라 임포트 값이 최종 승자다.
        # 그래서 임포트를 **먼저** 하고, 아래 driver 결정이 그 위에 덮어쓰게 둔다.
        $ownedKeys = @('DAVI_USE_STUB_LLM', 'PYTHONPATH', 'PYTHONUTF8')
        if (-not (Test-Path (Join-Path $serverDir '.env'))) {
            $m = Get-MainWorktree
            $mainEnv = if ($m) { Join-Path $m 'server\.env' } else { $null }
            if ($mainEnv -and (Test-Path $mainEnv)) {
                $n = 0; $skipped = 0
                Get-Content $mainEnv | ForEach-Object {
                    # `export KEY=` 형식도 받고, 인라인 주석은 잘라내고, **감싼 따옴표만** 벗긴다.
                    if ($_ -match '^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
                        $k = $Matches[1]; $v = $Matches[2].Trim()
                        if ($v -match '^"(.*)"$' -or $v -match "^'(.*)'$") { $v = $Matches[1] }
                        else { $v = ($v -replace '\s+#.*$', '').Trim() }
                        if ($ownedKeys -contains $k) { $skipped++ }
                        else { Set-Item -Path "env:$k" -Value $v; $n++ }
                    }
                }
                ".env 없음 → 본 레포 .env에서 $($n)개 키를 환경변수로 넘긴다(파일 복사 안 함, driver가 정하는 $($skipped)개 제외)"
            }
            elseif ($Real) { throw '-Real인데 .env가 이 레포에도 본 레포에도 없다. 키 없이 뜨면 조용히 실패한다.' }
        }
        $module = 'app.main:app'
        if ($Probe) {
            $env:PYTHONPATH = Split-Path -Parent (Resolve-Path $Probe)
            $module = [IO.Path]::GetFileNameWithoutExtension($Probe) + ':app'
        }
        if ($Real) { $env:DAVI_USE_STUB_LLM = 'false' } else { $env:DAVI_USE_STUB_LLM = 'true' }
        $out = Join-Path $env:TEMP 'davi-server.out'
        $err = Join-Path $env:TEMP 'davi-server.err'
        # #84 로그 정책과 동일하게: 기본 access log(IP/port) 끄고 JSON Lines 설정 사용.
        # logging.json은 #84 머지 전 브랜치에는 없으므로 있을 때만 붙인다.
        $uvicornArgs = @('-m', 'uvicorn', $module, '--host', '127.0.0.1', '--port', '8000', '--no-access-log')
        if (Test-Path (Join-Path $serverDir 'app\logging.json')) {
            $uvicornArgs += @('--log-config', 'app/logging.json')
        }
        Start-Process -FilePath $py -ArgumentList $uvicornArgs `
            -WorkingDirectory $serverDir -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err
        $ok = $false
        foreach ($i in 1..30) {
            Start-Sleep -Milliseconds 500
            try { if ((Invoke-WebRequest -Uri http://127.0.0.1:8000/docs -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) { $ok = $true; break } } catch { }
        }
        if (-not $ok) { Get-Content $err -Tail 20; throw '서버가 15초 안에 뜨지 않았다. 위 로그 확인.' }
        # **어느 레포의 코드가 도는지**를 매번 찍는다. 인터프리터를 빌려 쓸 수 있게 되면서
        # "python 경로 = 코드 경로"가 더는 성립하지 않는다 — 헷갈리면 여기를 본다.
        "up: module=$module stub=$env:DAVI_USE_STUB_LLM"
        "    code=$serverDir"
        "    python=$py"
        "    로그: $err"
    }

    'server-stop' {
        $listener = Get-ServerListener
        if ($listener) {
            # 8000은 흔한 포트다. 우리 uvicorn인지 보고 죽인다 — 남의 dev 서버를 조용히 끄지 않는다.
            $proc = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
            if ($proc -and $proc.ProcessName -notmatch '^(python|pythonw)$') {
                throw "8000을 잡은 것은 우리 서버가 아니다: pid $($proc.Id) $($proc.ProcessName). 직접 확인할 것."
            }
            Stop-Process -Id $listener.OwningProcess -Force
            "stopped pid $($listener.OwningProcess)"
        }
        else { 'no listener' }
    }

    # 앱 JVM 단위 테스트 (기기 불필요)
    'test' {
        Set-JavaEnv
        Push-Location $appDir
        try { Invoke-Native { & .\gradlew.bat :app:testDebugUnitTest } 'JVM 테스트' }
        finally { Pop-Location }
    }

    # 빌드+설치+권한 복구. 재설치는 접근성 권한을 끄므로 반드시 이 경로로.
    'install' {
        Set-JavaEnv
        $args2 = @{}
        if ($Clear) { $args2['ClearLog'] = $true }
        if ($SkipBuild) { $args2['SkipBuild'] = $true }
        # 위와 같은 이유로 감싸지 않는다 — dev-install은 실패를 throw로 알린다.
        & $devInstall @args2
    }

    # 앱이 붙을 서버를 바꾼다. URL은 빌드에 박히므로 교체·원복 모두 재설치까지 한다.
    #   url                 현재값과 후보
    #   url set <local|azure|wifi|URL>
    #   url restore         최초 백업으로 되돌린다
    'url' {
        switch ($Arg1) {
            '' {
                $url = Get-CurrentUrl
                "현재: $(if ($url) { "$url [$(Get-UrlKind $url)]" } else { '설정 없음' })"
                if (Test-Path $localPropsBak) { "백업: 있음($localPropsBak) — url restore로 되돌릴 수 있다" }
                $cands = Get-UrlCandidates
                if ($cands.Count -gt 0) {
                    '후보:'
                    foreach ($k in $cands.Keys) { "  $k : $($cands[$k])" }
                }
            }
            'set' {
                if (-not $Arg2) { throw 'url set <local|azure|wifi|전체URL>' }
                if ($Arg2 -match '^https?://') { $newUrl = $Arg2 }
                else {
                    $cands = Get-UrlCandidates
                    if (-not $cands.Contains($Arg2)) {
                        throw "후보에 '$Arg2'가 없다(가능: $($cands.Keys -join ', ')). 전체 URL을 직접 줄 것."
                    }
                    $newUrl = $cands[$Arg2]
                }
                $current = Get-CurrentUrl
                if ($current -eq $newUrl) { "이미 $newUrl 이다. 재설치 생략."; break }
                if (-not (Test-Path $localPropsBak)) { Copy-Item $localProps $localPropsBak }
                Set-ServerUrl $newUrl
                "URL: $current → $newUrl [$(Get-UrlKind $newUrl)]"
                Invoke-Reinstall
            }
            'restore' {
                if (-not (Test-Path $localPropsBak)) { throw '백업이 없다. url set을 한 적이 없거나 이미 되돌렸다.' }
                Copy-Item $localPropsBak $localProps -Force
                Remove-Item $localPropsBak
                "복원: $(Get-CurrentUrl)"
                Invoke-Reinstall
            }
            default { throw "url <없음|set|restore>. 받은 값: $Arg1" }
        }
    }

    # 워크트리에는 gitignore된 local.properties가 없어 gradle이 SDK를 못 찾는다.
    'worktree-init' {
        $main = Get-MainWorktree
        if (-not $main) { throw 'git worktree list를 읽지 못했다.' }
        # git은 슬래시로, PowerShell은 백슬래시로 준다. 정규화하지 않으면 절대 같지 않아
        # 본 레포에서도 "복사" 분기로 들어가 자기 자신을 덮어쓰다 IOException으로 죽는다.
        if ((Format-PathKey $main) -eq (Format-PathKey $root)) {
            '여기가 본 레포다. 할 일 없음.'
            if (-not (Test-Path $localProps)) {
                '다만 local.properties가 없다 — sdk.dir=<Android SDK 경로>를 직접 쓰거나 Android Studio로 한 번 열 것.'
            }
            break
        }
        $src = Join-Path $main 'app-android\local.properties'
        if (-not (Test-Path $src)) { throw "본 레포에 local.properties가 없다: $src" }
        Copy-Item $src $localProps -Force
        "복사: $src → $localProps"
        "서버 URL: $(Get-CurrentUrl) [$(Get-UrlKind (Get-CurrentUrl))]"
    }

    # 실기기 E2E 러너 위임. e2e list로 시나리오 목록.
    'e2e' {
        $runner = Join-Path $appDir 'scripts\run-device-e2e.ps1'
        if (-not (Test-Path $runner)) { throw "E2E 러너가 없다: $runner" }
        Set-JavaEnv
        if ($Arg1 -eq 'list') { & $runner -ListScenarios }
        elseif ($Arg1) { & $runner -Scenario $Arg1 }
        else { & $runner }
    }

    # 화면 캡처. 오버레이 안내도 함께 찍힌다. PS `>` 리다이렉트는 바이너리를 깨뜨리므로 pull 경유.
    'shot' {
        $adb = Resolve-Adb
        $dest = if ($Arg1) { $Arg1 } else { Join-Path $appDir 'captures\run-shot.png' }
        New-Item -ItemType Directory -Force (Split-Path -Parent $dest) | Out-Null
        # 기기 쪽 파일명에 PID를 넣는다. 고정 이름이면 세션 둘이 같은 기기를 쓸 때
        # 남이 찍은 **멀쩡한 PNG**를 받아 온다 — 실패로 보이지 않아 제일 나쁜 종류다.
        $tmp = "/sdcard/davi_run_shot_$PID.png"
        Invoke-Native { & $adb shell screencap -p $tmp } 'screencap'
        Invoke-Native { & $adb pull $tmp $dest } 'pull'
        Invoke-Native { & $adb shell rm $tmp } 'rm'
        $dest
    }

    # GuideSvc 로그. 앱 검증의 1차 창구다.
    # 기본은 **지난 호출 이후의 델타**다. -Full이면 버퍼 전체(느리다 — 실측 13초).
    'log' {
        $adb = Resolve-Adb
        if ($Clear) {
            Invoke-Native { & $adb logcat -c } 'logcat -c'
            Remove-Item $logCursor -ErrorAction SilentlyContinue
            'cleared'
        }
        elseif ($Full) {
            $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
            try { & $adb logcat -s GuideSvc -d } finally { $ErrorActionPreference = $prev }
        }
        else { Get-LogDelta -Adb $adb -Tail $Lines }
    }

    # 기기에 쌓인 캡처 관리. 조작마다 찍어 두고 **필요할 때만** 회수한다.
    #   shots        — 기기에 쌓인 목록
    #   shots pull [폴더] — 전부 회수(기본 app-android\captures\shots). 회수 후에도 기기에 남는다
    #   shots clear  — 기기에서 지운다
    'shots' {
        $adb = Resolve-Adb
        switch ($Arg1) {
            'pull' {
                $dest = if ($Arg2) { $Arg2 } else { Join-Path $appDir 'captures\shots' }
                New-Item -ItemType Directory -Force $dest | Out-Null
                Invoke-Native { & $adb pull $deviceShotDir $dest } 'shots pull'
                Get-ChildItem (Join-Path $dest 'davi_shots') -ErrorAction SilentlyContinue |
                    Select-Object Name, @{n = 'KB'; e = { [math]::Round($_.Length / 1KB) } }
            }
            'clear' {
                Invoke-Native { & $adb shell "rm -rf $deviceShotDir" } 'shots clear'
                '기기 캡처 삭제'
            }
            default {
                $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
                try { & $adb shell "ls -la $deviceShotDir 2>/dev/null || echo '(없음)'" } finally { $ErrorActionPreference = $prev }
            }
        }
    }

    # 조작 한 번 = 도구 호출 한 번. 조작 → 캡처 예약(백그라운드) → 로그 델타를 한 프로세스에서.
    # PowerShell 기동이 콜당 ~1.1초라 tap/shot/log를 따로 부르면 그 값이 세 번 붙는다 —
    # 세션 중 A/B 교대 8스텝에서 3콜 2753ms 대 step 1콜 896ms였다(둘 다 대기 ms 제외).
    #   step 538 1503 3000 — 좌표 탭 후 3초 대기
    #   step key 3 3000    — 키 이벤트(3=HOME) 후 대기
    #   step wait 3000     — 조작 없이 관찰만(캡처 예약 + 대기 + 로그 델타)
    'step' {
        $adb = Resolve-Adb
        $tag = ''
        $waitMs = $Arg3
        if ($Arg1 -eq 'key') {
            Invoke-Native { & $adb shell input keyevent $Arg2 } 'input keyevent'
            $tag = "key$Arg2"
        }
        elseif ($Arg1 -eq 'wait') { $waitMs = $Arg2 }
        elseif ($Arg1 -or $Arg2) {
            # 좌표가 숫자가 아니면 기기까지 가서 실패한다. 여기서 막고 무엇이 틀렸는지 알려 준다.
            if ($Arg1 -notmatch '^\d+$' -or $Arg2 -notmatch '^\d+$') {
                throw "step 좌표는 숫자 두 개다: 'step <x> <y> [대기ms]' / 'step key <코드>' / 'step wait <대기ms>' (받은 값: '$Arg1' '$Arg2')"
            }
            Invoke-Native { & $adb shell input tap $Arg1 $Arg2 } 'input tap'
            $tag = "tap$Arg1-$Arg2"
        }
        # **대기 → 캡처 → 로그** 순서다. 조작 직후에 캡처를 걸면 인코딩이 1.3초라 저장되는
        # 프레임이 전이 전/전이 중 화면이 된다 — 조용히 한 스텝씩 밀린 증거가 쌓인다.
        # 캡처를 로그보다 앞에 두는 이유는 로그 델타(0.4~0.6초)가 인코딩 시간을 덮어 주기 때문이다.
        if ($waitMs -match '^\d+$') { Start-Sleep -Milliseconds ([int]$waitMs) }
        elseif ($tag) {
            # 대기를 안 주면 바로 위 주석이 말하는 그 상태가 된다. 조용히 넘기지 않는다.
            '(대기 없음 — 이 캡처는 전이 전 프레임일 수 있다. 세 번째 인자로 대기 ms를 줄 것)'
        }
        $path = Start-BackgroundShot -Adb $adb -Tag $tag
        "capture: $path"
        Get-LogDelta -Adb $adb -Tail $Lines
    }

    # 입력 헬퍼. 좌표는 shot으로 찍어 확인한다(검증 기기 SM-S921N은 1080x2340 — 기기마다 다르다).
    # -Shot을 주면 조작 직후 캡처를 백그라운드로 예약한다(대기 없음, 회수는 shots pull).
    'tap' {
        $adb = Resolve-Adb
        # 오타는 기기까지 가서 조용히 아무 일도 안 하는 쪽으로 끝난다. 여기서 막는다.
        if ($Arg1 -notmatch '^\d+$' -or $Arg2 -notmatch '^\d+$') { throw "tap 좌표는 숫자 둘이다 (받은 값: '$Arg1' '$Arg2')" }
        Invoke-Native { & $adb shell input tap $Arg1 $Arg2 } 'input tap'
        if ($Shot) { "capture: $(Start-BackgroundShot -Adb $adb -Tag "tap$Arg1-$Arg2")" }
    }
    'key' {
        $adb = Resolve-Adb
        if ($Arg1 -notmatch '^\d+$') { throw "key는 키코드 숫자 하나다 (받은 값: '$Arg1'). 3=HOME, 4=BACK" }
        Invoke-Native { & $adb shell input keyevent $Arg1 } 'input keyevent'
        if ($Shot) { "capture: $(Start-BackgroundShot -Adb $adb -Tag "key$Arg1")" }
    }
    # 스크롤 안내를 따라 하려면 스와이프가 필요하다. 이게 없어서 생 adb로 빠지곤 했다.
    # 관찰은 이어서 `step wait <ms>` 한 콜로 한다.
    'swipe' {
        $adb = Resolve-Adb
        foreach ($a in @($Arg1, $Arg2, $Arg3, $Arg4)) {
            if ($a -notmatch '^\d+$') { throw "swipe 좌표는 숫자 넷이다: 'swipe <x1> <y1> <x2> <y2>' (받은 값: '$Arg1' '$Arg2' '$Arg3' '$Arg4')" }
        }
        # 지속 시간을 주지 않으면 기기가 플링으로 읽어 목록이 과하게 넘어간다.
        Invoke-Native { & $adb shell input swipe $Arg1 $Arg2 $Arg3 $Arg4 300 } 'input swipe'
        if ($Shot) { "capture: $(Start-BackgroundShot -Adb $adb -Tag "swipe$Arg1-$Arg2")" }
    }
}
