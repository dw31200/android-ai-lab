# update_progress.ps1
# _workspace/PROGRESS.md를 갱신하여 체크포인트를 저장한다.
# 마지막 완료 라운드 정보, 토큰 사용량 스냅샷, last_checkpoint 시각을 기록한다.
#
# 호출 예:
#   pwsh -File update_progress.ps1 -RoundLabel "T17" -RoundDescription "F-004 통과"
#   pwsh -File update_progress.ps1 -RoundLabel "T17" -NextRound "T18" -NextRoundPrompt "F-007 진입"

[CmdletBinding()]
param(
    [Parameter(Mandatory=$false)][string]$RoundLabel = "",
    [Parameter(Mandatory=$false)][string]$RoundDescription = "",
    [Parameter(Mandatory=$false)][string]$NextRound = "",
    [Parameter(Mandatory=$false)][string]$NextRoundPrompt = "",
    [Parameter(Mandatory=$false)][string]$WorkspaceDir = "C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\_workspace",
    [switch]$SkipTokenCheck
)

$ErrorActionPreference = "Stop"

$ProgressFile = Join-Path $WorkspaceDir "PROGRESS.md"
$StateFile = Join-Path $WorkspaceDir "PROGRESS.state.json"

if (-not (Test-Path $WorkspaceDir)) {
    New-Item -ItemType Directory -Path $WorkspaceDir -Force | Out-Null
}

# 토큰 사용량 확인
$tokenInfo = $null
if (-not $SkipTokenCheck) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $checkScript = Join-Path $scriptDir "check_token_usage.ps1"
    if (Test-Path $checkScript) {
        try {
            $psExe = Get-Command pwsh -ErrorAction SilentlyContinue
            if (-not $psExe) { $psExe = Get-Command powershell -ErrorAction SilentlyContinue }
            if ($psExe) {
                $tokenJson = & $psExe.Source -NoProfile -File $checkScript 2>$null
                if ($tokenJson) {
                    $tokenInfo = $tokenJson | ConvertFrom-Json -ErrorAction SilentlyContinue
                }
            }
        } catch {
            # 토큰 체크 실패는 치명적 아님 — 계속 진행
        }
    }
}

# 기존 상태 파일 로드 (있으면)
$loaded = $null
if (Test-Path $StateFile) {
    try {
        $loaded = Get-Content $StateFile -Raw -Encoding utf8 | ConvertFrom-Json
    } catch { $loaded = $null }
}

# 표준 필드 정의 + 기존 값 머지 (누락 필드는 기본값으로 채움)
function Get-LoadedProp {
    param($obj, [string]$name, $default)
    if ($obj -and ($obj.PSObject.Properties.Name -contains $name)) { return $obj.$name }
    return $default
}
$state = [pscustomobject]@{
    completed_rounds    = Get-LoadedProp $loaded 'completed_rounds' @()
    next_round          = Get-LoadedProp $loaded 'next_round' $null
    next_round_prompt   = Get-LoadedProp $loaded 'next_round_prompt' $null
    last_checkpoint     = Get-LoadedProp $loaded 'last_checkpoint' $null
    last_token_usage    = Get-LoadedProp $loaded 'last_token_usage' $null
    last_auto_resume_at = Get-LoadedProp $loaded 'last_auto_resume_at' $null
}

# 신규 완료 라운드 추가 (RoundLabel 지정 시)
if ($RoundLabel) {
    $existing = $state.completed_rounds | Where-Object { $_.label -eq $RoundLabel }
    if (-not $existing) {
        $newEntry = [pscustomobject]@{
            label = $RoundLabel
            description = $RoundDescription
            checkpoint_at = [DateTime]::UtcNow.ToString("o")
        }
        $state.completed_rounds = @($state.completed_rounds) + @($newEntry)
    }
}

# 다음 라운드 정보 갱신
if ($NextRound) {
    $state.next_round = $NextRound
}
if ($NextRoundPrompt) {
    $state.next_round_prompt = $NextRoundPrompt
}

$state.last_checkpoint = [DateTime]::UtcNow.ToString("o")
if ($tokenInfo) {
    $state.last_token_usage = $tokenInfo
}

# state JSON 저장 — Set-Content이 5.1 환경에서 silent fail하는 경우가 있어 .NET API 직접 사용
$stateJson = $state | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText($StateFile, $stateJson, [System.Text.UTF8Encoding]::new($false))

# Update PROGRESS.md (preserve user body; replace only the auto-managed block)
$autoBlockStart = "<!-- AUTO-MANAGED-BEGIN: token-limit-guardian -->"
$autoBlockEnd = "<!-- AUTO-MANAGED-END: token-limit-guardian -->"

# Pre-compute display values to avoid Korean inside here-string subexpressions
$nextRoundDisplay = if ($state.next_round) { $state.next_round } else { "null (conservative mode - awaiting user confirmation)" }
$nextRoundPromptDisplay = if ($state.next_round_prompt) { $state.next_round_prompt } else { "(unset)" }
$tokenUsageDisplay = if ($tokenInfo) { "$($tokenInfo.percentage)% ($($tokenInfo.used) / $($tokenInfo.limit))" } else { "(not measured)" }
$estimatedResetDisplay = if ($tokenInfo -and $tokenInfo.estimated_reset) { $tokenInfo.estimated_reset } else { "(not measured)" }
$lastResumeDisplay = if ($state.last_auto_resume_at) { $state.last_auto_resume_at } else { "(none)" }
$completedRoundsDisplay = if ($state.completed_rounds.Count -gt 0) {
    ($state.completed_rounds | ForEach-Object { "- ``$($_.label)`` - $($_.description) (at $($_.checkpoint_at))" }) -join "`r`n"
} else { "- (none)" }

$autoBlock = @"
$autoBlockStart
## Auto-resume control (managed by token-limit-guardian)

| Field | Value |
|-------|-------|
| last_checkpoint | $($state.last_checkpoint) |
| next_round | $nextRoundDisplay |
| next_round_prompt | $nextRoundPromptDisplay |
| token_usage_percentage | $tokenUsageDisplay |
| estimated_reset | $estimatedResetDisplay |
| last_auto_resume_at | $lastResumeDisplay |

### Completed rounds (auto-logged)
$completedRoundsDisplay
$autoBlockEnd
"@

if (Test-Path $ProgressFile) {
    $content = Get-Content $ProgressFile -Raw -Encoding utf8
    if ($content -match [regex]::Escape($autoBlockStart)) {
        # Replace existing auto-managed block
        $parts = $content -split [regex]::Escape($autoBlockStart), 2
        $tail = ($parts[1] -split [regex]::Escape($autoBlockEnd), 2)
        if ($tail.Count -eq 2) {
            $newContent = $parts[0] + $autoBlock + $tail[1]
        } else {
            $newContent = $parts[0] + $autoBlock
        }
        [System.IO.File]::WriteAllText($ProgressFile, $newContent, [System.Text.UTF8Encoding]::new($false))
    } else {
        # Prepend auto block to existing file
        $newContent = $autoBlock + "`r`n`r`n" + $content
        [System.IO.File]::WriteAllText($ProgressFile, $newContent, [System.Text.UTF8Encoding]::new($false))
    }
} else {
    # Create fresh PROGRESS.md
    $newContent = "# SDD Workflow Progress Snapshot`r`n`r`n" + $autoBlock + "`r`n`r`n(This file is auto-managed by token-limit-guardian.)`r`n"
    [System.IO.File]::WriteAllText($ProgressFile, $newContent, [System.Text.UTF8Encoding]::new($false))
}

# 결과 보고 (JSON 한 줄)
$result = [pscustomobject]@{
    updated = $true
    progress_file = $ProgressFile
    state_file = $StateFile
    last_checkpoint = $state.last_checkpoint
    next_round = $state.next_round
    token_percentage = if ($tokenInfo) { $tokenInfo.percentage } else { $null }
    breach = if ($tokenInfo) { $tokenInfo.breach } else { $false }
}
$result | ConvertTo-Json -Compress
