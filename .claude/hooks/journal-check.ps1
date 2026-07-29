<#
Stop 훅: 직전 턴에서 발표 특기 마일스톤(방향 전환·아키텍처/계약 결정·뒤집힌 가정·데모 성과)이
확정됐는지 haiku 단발 세션으로 판정하고, 맞으면 세션에 팀 일지(davi-journal) 기록을 지시한다.
실패는 전부 조용히 통과(fail-open) — 일지 판정이 개발 턴을 막으면 안 된다.
PS 5.1 주의: 이 파일은 UTF-8 BOM으로 저장해야 한다(한글 주석 파스 에러).
Windows 전용이다(cmd /c·%TEMP% 의존, 훅 shell=powershell). macOS Claude 사용자가 생기면
그 환경에서 검증하며 bash로 이식할 것 — 검증 없는 크로스플랫폼화는 하지 않는다.
#>
$ErrorActionPreference = 'Stop'
try {
    # 판정용 자식 세션 재귀 가드
    if ($env:DAVI_JOURNAL_JUDGE -eq '1') { exit 0 }

    # PS 5.1의 네이티브 파이프 기본 인코딩은 ASCII다 — 한글 프롬프트가 ?로 깨진다(실측).
    $script:OutputEncoding = New-Object System.Text.UTF8Encoding $false
    try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

    # 실제 훅은 프로세스 stdin([Console]::In), 파이프 테스트는 파이프라인($input)으로 들어온다
    $raw = ($input | Out-String)
    if (-not $raw -or -not $raw.Trim()) { $raw = [Console]::In.ReadToEnd() }
    $inp = $raw | ConvertFrom-Json
    # 이미 이 훅 때문에 이어진 턴이면 다시 붙잡지 않는다(무한 루프 방지)
    if ($inp.stop_hook_active) { exit 0 }
    $tp = $inp.transcript_path
    if (-not $tp -or -not (Test-Path $tp)) { exit 0 }

    # 마지막 사용자/어시스턴트 텍스트 추출
    $lastUser = ''; $lastAsst = ''
    foreach ($line in (Get-Content $tp -Encoding UTF8 -Tail 400)) {
        if ($line.IndexOf('"message"') -lt 0) { continue }
        try { $o = $line | ConvertFrom-Json } catch { continue }
        $content = $o.message.content
        if ($null -eq $content) { continue }
        $text = ''
        if ($content -is [string]) { $text = $content }
        else { foreach ($c in $content) { if ($c.type -eq 'text') { $text += $c.text + "`n" } } }
        if (-not $text.Trim()) { continue }
        if ($o.type -eq 'user') { $lastUser = $text } elseif ($o.type -eq 'assistant') { $lastAsst = $text }
    }
    if (-not $lastAsst.Trim()) { exit 0 }
    if ($lastUser.Length -gt 4000) { $lastUser = $lastUser.Substring(0, 4000) }
    if ($lastAsst.Length -gt 6000) { $lastAsst = $lastAsst.Substring(0, 6000) }

    $judgePrompt = @"
다음은 davi 프로젝트 개발 세션의 마지막 교환이다. 이 턴에서 발표·회고에 특기할 마일스톤,
즉 방향 전환 / 아키텍처·계약 결정 / 실측으로 뒤집힌 가정 / 데모에 쓸 성과가 새로 확정되었거나
확정 소식이 공유되었는가? 단순 진행 보고, 질문-답변, 코드 설명, 아직 결론 없는 논의는 아니다.
이 턴에서 이미 팀 일지 기록이 수행되었다면 아니다. 정확히 한 단어로만 답하라: JOURNAL 또는 PASS

[사용자]
$lastUser

[어시스턴트]
$lastAsst
"@

    # 자식은 프로젝트 밖 cwd로 — 프로젝트 훅이 다시 걸리지 않는다.
    # 한글 프롬프트는 PS 파이프 인코딩이 깨뜨리므로(실측: 판정이 난수가 됨) UTF-8 파일 + cmd type로 바이트 그대로 전달.
    $tmp = Join-Path $env:TEMP ("davi-journal-judge-" + [Guid]::NewGuid().ToString('N') + ".txt")
    [System.IO.File]::WriteAllText($tmp, $judgePrompt, (New-Object System.Text.UTF8Encoding $false))
    $env:DAVI_JOURNAL_JUDGE = '1'
    Push-Location $env:TEMP
    try { $verdict = cmd /c "type `"$tmp`" | claude -p --model haiku" 2>$null | Out-String }
    finally { Pop-Location; $env:DAVI_JOURNAL_JUDGE = ''; Remove-Item $tmp -Force -ErrorAction SilentlyContinue }

    if ($verdict -match 'JOURNAL') {
        # asyncRewake 계약: 백그라운드 훅이 exit 2로 끝나면 하니스가 세션을 깨우고
        # 아래 출력을 지시문으로 전달한다. 동기(block JSON) 방식은 턴마다 +11~17초라 버렸다.
        [Console]::Error.Write('Stop 훅 판정: 이번 턴에 발표 특기 마일스톤이 확정·공유된 것으로 보인다. davi-journal 스킬 규칙대로 팀 일지(~/davi-journal)에 작업자 태그와 함께 기록하고 커밋·푸시하라. 이미 일지에 같은 항목이 있으면 기록하지 말고 그대로 종료하라.')
        exit 2
    }
    exit 0
}
catch { exit 0 }
