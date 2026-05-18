# auto_resume.ps1
# Windows Task Scheduler가 호출하는 자동 재개 진입점.
# PROGRESS.state.json을 읽고 next_round 필드 확인 후 Claude Code CLI를 실행한다.
#
# 보수적 모드: next_round가 null이면 status 보고만 하는 prompt를 전달한다.
#              next_round가 명시되면 next_round_prompt를 실행 prompt로 전달한다.

[CmdletBinding()]
param(
    [Parameter(Mandatory=$false)][string]$WorkingDir = "C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk",
    [switch]$DryRun
)

$ErrorActionPreference = "Continue"

$StateFile = Join-Path $WorkingDir "_workspace\PROGRESS.state.json"
$LogFile = Join-Path $WorkingDir "_workspace\auto_resume.log"

function Log {
    param([string]$Message)
    $line = "[$([DateTime]::UtcNow.ToString('o'))] $Message"
    Add-Content -Path $LogFile -Value $line -Encoding utf8
}

Log "auto_resume.ps1 started (WorkingDir=$WorkingDir)"

# state 파일 확인
$state = $null
if (Test-Path $StateFile) {
    try {
        $state = Get-Content $StateFile -Raw | ConvertFrom-Json
    } catch {
        Log "state file parse failed: $_"
    }
} else {
    Log "state file not found: $StateFile"
}

# 자동 재개 시각 기록
if ($state) {
    $state.last_auto_resume_at = [DateTime]::UtcNow.ToString("o")
    $state | ConvertTo-Json -Depth 8 | Set-Content $StateFile -Encoding utf8
}

# Decide resume prompt for Claude Code CLI
$resumePrompt = $null
if ($state -and $state.next_round -and $state.next_round_prompt) {
    $resumePrompt = $state.next_round_prompt
    Log "next_round=$($state.next_round) - using next_round_prompt"
} else {
    # Conservative mode: status report only
    $lastLabel = if ($state -and $state.completed_rounds.Count -gt 0) {
        ($state.completed_rounds | Select-Object -Last 1).label
    } else { "(none)" }
    $resumePrompt = "[token-limit-guardian auto-resume] Session resumed after 5h token-limit reset. Last completed round: $lastLabel. Please read _workspace/PROGRESS.md and report status only. The user decides which round to run next."
    Log "next_round not set - sending status-only prompt"
}

# Locate claude CLI
$claudeCmd = Get-Command claude -ErrorAction SilentlyContinue
$claudeExe = if ($claudeCmd) { $claudeCmd.Source } else { $null }
if (-not $claudeExe) {
    # PATH unset - try common install location
    $candidate = Join-Path $env:LOCALAPPDATA "Programs\claude\claude.exe"
    if (Test-Path $candidate) { $claudeExe = $candidate }
}

if (-not $claudeExe) {
    Log "claude CLI not found in PATH - manual resume required"
    exit 1
}

Log "launching claude CLI: $claudeExe (dryrun=$DryRun)"

if ($DryRun) {
    Log "DRYRUN: would launch claude with prompt: $resumePrompt"
    "DRYRUN_OK: would_launch=$claudeExe, working_dir=$WorkingDir, prompt_preview=$($resumePrompt.Substring(0, [Math]::Min(120, $resumePrompt.Length)))"
    exit 0
}

# Claude Code를 새 콘솔로 시작 (대화형 모드)
# 사용자가 직접 콘솔을 열어 prompt를 확인할 수 있도록 함
$startInfo = New-Object System.Diagnostics.ProcessStartInfo
$startInfo.FileName = $claudeExe
$startInfo.WorkingDirectory = $WorkingDir
$startInfo.UseShellExecute = $true
$startInfo.CreateNoWindow = $false

try {
    [System.Diagnostics.Process]::Start($startInfo) | Out-Null
    Log "claude launched"

    # 로그에 prompt 기록 (사용자 참고용)
    Log "RESUME_PROMPT: $resumePrompt"
} catch {
    Log "claude launch failed: $_"
    exit 1
}

# Task Scheduler가 1회 트리거이므로 별도 정리 불필요 (등록 시 ONCE)
exit 0
