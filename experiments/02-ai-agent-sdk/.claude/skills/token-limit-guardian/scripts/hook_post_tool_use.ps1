# hook_post_tool_use.ps1
# PostToolUse hook 본체.
# Write/Edit 도구가 _workspace/impl_summary_*.md 또는 _workspace/qa_report_*.md를 만든 경우에만
# update_progress.ps1을 호출하여 PROGRESS.md를 갱신한다.

$ErrorActionPreference = 'SilentlyContinue'

try {
    $raw = [Console]::In.ReadToEnd()
    if (-not $raw) { exit 0 }

    $in = $raw | ConvertFrom-Json -ErrorAction SilentlyContinue
    if (-not $in) { exit 0 }

    $fp = $null
    if ($in.tool_response -and $in.tool_response.filePath) {
        $fp = $in.tool_response.filePath
    } elseif ($in.tool_input -and $in.tool_input.file_path) {
        $fp = $in.tool_input.file_path
    }

    if (-not $fp) { exit 0 }

    # 대상 패턴: _workspace 하위 + impl_summary_* 또는 qa_report_*
    $isTarget = ($fp -match '_workspace') -and (($fp -match 'impl_summary_') -or ($fp -match 'qa_report_'))
    if (-not $isTarget) { exit 0 }

    # 라운드 라벨 추출 (impl_summary_8.md → "T17 (impl_summary_8)" 또는 qa_report_7.md → "qa_report_7")
    $fileName = Split-Path -Leaf $fp
    $roundLabel = ""
    $roundDesc = ""
    if ($fileName -match 'impl_summary_(\d+)\.md') {
        $roundLabel = "impl_$($Matches[1])"
        $roundDesc = "구현 라운드 #$($Matches[1]) 완료 ($fileName)"
    } elseif ($fileName -match 'qa_report_(\d+)\.md') {
        $roundLabel = "qa_$($Matches[1])"
        $roundDesc = "QA 라운드 #$($Matches[1]) 완료 ($fileName)"
    }

    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $updateScript = Join-Path $scriptDir "update_progress.ps1"
    if (-not (Test-Path $updateScript)) { exit 0 }

    $argList = @("-NoProfile", "-File", $updateScript)
    if ($roundLabel) {
        $argList += "-RoundLabel"; $argList += $roundLabel
        $argList += "-RoundDescription"; $argList += $roundDesc
    }

    $psCmd = Get-Command pwsh -ErrorAction SilentlyContinue
    if (-not $psCmd) { $psCmd = Get-Command powershell -ErrorAction SilentlyContinue }
    if (-not $psCmd) { exit 0 }

    # 동기 실행 — 백그라운드로 빼면 매 hook마다 leak
    & $psCmd.Source @argList 2>$null | Out-Null
} catch {
    # 침묵
}

exit 0
