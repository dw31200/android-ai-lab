# register_resume_task.ps1
# Windows Task Scheduler에 자동 재개 task를 등록한다.
# 한도 리셋 시각(첫 메시지 + 5시간) + 버퍼(기본 5분) 후 auto_resume.ps1을 실행하도록 1회성 트리거 등록.
#
# 호출 예:
#   pwsh -File register_resume_task.ps1
#   pwsh -File register_resume_task.ps1 -ResetAtUtc "2026-05-11T20:30:00Z"

[CmdletBinding()]
param(
    [Parameter(Mandatory=$false)][string]$ResetAtUtc = "",
    [Parameter(Mandatory=$false)][int]$BufferMinutes = 0,
    [Parameter(Mandatory=$false)][string]$TaskName = "ClaudeCode-AutoResume-02-ai-agent-sdk",
    [Parameter(Mandatory=$false)][string]$WorkingDir = "C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk"
)

$ErrorActionPreference = "Continue"

if ($BufferMinutes -le 0) {
    $envBuf = [int]($env:CLAUDE_LIMIT_RESET_BUFFER_MIN)
    $BufferMinutes = if ($envBuf -gt 0) { $envBuf } else { 5 }
}

# ResetAtUtc 미지정 시 check_token_usage 호출하여 estimated_reset 얻기
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$resetTimeUtc = $null

if ($ResetAtUtc) {
    try {
        $resetTimeUtc = [DateTime]::Parse($ResetAtUtc, $null, [System.Globalization.DateTimeStyles]::RoundtripKind).ToUniversalTime()
    } catch {
        Write-Host "ResetAtUtc 파싱 실패: $ResetAtUtc" -ForegroundColor Red
        exit 1
    }
} else {
    $checkScript = Join-Path $scriptDir "check_token_usage.ps1"
    if (Test-Path $checkScript) {
        try {
            $psExe = Get-Command pwsh -ErrorAction SilentlyContinue
            if (-not $psExe) { $psExe = Get-Command powershell -ErrorAction SilentlyContinue }
            if ($psExe) {
                $tokenJson = & $psExe.Source -NoProfile -File $checkScript 2>$null
                $tokenInfo = $tokenJson | ConvertFrom-Json -ErrorAction SilentlyContinue
                if ($tokenInfo -and $tokenInfo.estimated_reset) {
                    $resetTimeUtc = [DateTime]::Parse($tokenInfo.estimated_reset, $null, [System.Globalization.DateTimeStyles]::RoundtripKind).ToUniversalTime()
                }
            }
        } catch {}
    }
}

if (-not $resetTimeUtc) {
    # fallback: 지금 + 5시간
    $resetTimeUtc = [DateTime]::UtcNow.AddHours(5)
    Write-Host "estimated_reset 미측정 — fallback: now+5h ($($resetTimeUtc.ToString('o')))" -ForegroundColor Yellow
}

$triggerLocal = $resetTimeUtc.AddMinutes($BufferMinutes).ToLocalTime()

# 트리거가 과거면 1분 후로 설정 (즉시 트리거 의도)
if ($triggerLocal -lt [DateTime]::Now) {
    $triggerLocal = [DateTime]::Now.AddMinutes(1)
}

$resumeScript = Join-Path $scriptDir "auto_resume.ps1"
if (-not (Test-Path $resumeScript)) {
    Write-Host "auto_resume.ps1 not found at $resumeScript" -ForegroundColor Red
    exit 1
}

# PowerShell ScheduledTasks 모듈 사용 — schtasks 보다 quote escaping이 안전하고 silent failure 없음
$pwshCmd = Get-Command pwsh -ErrorAction SilentlyContinue
if (-not $pwshCmd) { $pwshCmd = Get-Command powershell -ErrorAction SilentlyContinue }
if (-not $pwshCmd) {
    Write-Host "pwsh/powershell executable not found." -ForegroundColor Red
    exit 1
}
$pwshExe = $pwshCmd.Source

# 기존 동명 task 삭제 (있으면)
try {
    $existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($existing) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    }
} catch {}

$result = $null
try {
    $taskArgs = "-NoProfile -ExecutionPolicy Bypass -File `"$resumeScript`" -WorkingDir `"$WorkingDir`""
    $action = New-ScheduledTaskAction -Execute $pwshExe -Argument $taskArgs
    $trigger = New-ScheduledTaskTrigger -Once -At $triggerLocal
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable

    # Register-ScheduledTask가 예외 없이 끝나면 등록 성공으로 본다.
    # Get-ScheduledTask로 즉시 verify는 timing 이슈가 있으므로 생략한다.
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Description "Claude Code auto-resume after token-limit reset (token-limit-guardian)" -Force | Out-Null

    $result = [pscustomobject]@{
        registered     = $true
        task_name      = $TaskName
        trigger_local  = $triggerLocal.ToString("o")
        trigger_utc    = $triggerLocal.ToUniversalTime().ToString("o")
        reset_at_utc   = $resetTimeUtc.ToString("o")
        buffer_minutes = $BufferMinutes
        resume_script  = $resumeScript
        working_dir    = $WorkingDir
        delete_command = "Unregister-ScheduledTask -TaskName $TaskName -Confirm:`$false"
    }
} catch {
    $result = [pscustomobject]@{
        registered = $false
        task_name  = $TaskName
        error      = "register_exception: $_"
    }
}

$result | ConvertTo-Json -Compress
