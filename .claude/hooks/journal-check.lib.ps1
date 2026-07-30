<#
판정 훅의 순수 로직. journal-check.ps1(실행)과 journal-check.tests.ps1(회귀)이 공유한다.
PS 5.1 주의: UTF-8 BOM으로 저장할 것(한글 주석 파스 에러).
#>

# 트랜스크립트 JSONL 줄들에서 마지막 사용자/어시스턴트 텍스트를 뽑는다.
function Get-LastExchange {
    param([string[]]$Lines)
    $lastUser = ''
    $lastAsst = ''
    foreach ($line in $Lines) {
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
    @{ User = $lastUser; Assistant = $lastAsst }
}

# 판정 문자열 해석. 정규화(Trim·대문자) 후 허용 토큰 'JOURNAL'과 **정확 일치**만 기록으로
# 인정한다. 부분 일치(-match)는 "PASS — not JOURNAL" 같은 설명형 답변과 인젝션을 따라
# 붙인 문장까지 오판했다(PR #65 리뷰 P2).
function Resolve-Verdict {
    param([string]$Raw)
    "$Raw".Trim().ToUpperInvariant() -eq 'JOURNAL'
}
