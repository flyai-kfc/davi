<#
호환용 shim. **실제 드라이버는 저장소의 `scripts/dev-driver.ps1`이다**(#344로 커밋됨).

옛 경로(.claude\skills\run-davi\driver.ps1)를 부르던 자리가 남아 있어 그대로 넘겨준다.
새로 쓰는 자리는 `.\scripts\dev-driver.ps1`을 직접 부를 것.

**본 레포 사본으로 폴백하지 않는다.** 드라이버는 "스크립트가 놓인 레포"를 빌드·설치하므로,
워크트리에서 본 레포 사본을 부르면 **워크트리 작업 중에 본 레포 코드가 기기에 올라간다.**
이 브랜치에 아직 파일이 없으면 조용히 우회하지 말고 멈춘다.
#>
$ErrorActionPreference = 'Stop'

# .claude\skills\run-davi → 저장소(또는 워크트리) 루트
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$real = Join-Path $root 'scripts\dev-driver.ps1'

if (-not (Test-Path $real)) {
    throw @"
이 레포에는 아직 scripts\dev-driver.ps1이 없다: $root
  · 이 워크트리의 브랜치가 #344 머지 이전이다. main을 머지(또는 리베이스)하면 들어온다.
  · **본 레포 사본을 대신 부르지 말 것** — 그러면 이 워크트리가 아니라 본 레포가 빌드·설치된다.
"@
}

& $real @args
exit $LASTEXITCODE
