# hook_user_prompt_submit.ps1
# UserPromptSubmit hook entry point.
# Calls check_token_usage.ps1; emits systemMessage JSON to stdout when breach is true.
# All exceptions are swallowed so a hook failure never blocks the user prompt.

$ErrorActionPreference = 'SilentlyContinue'

try {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $checkScript = Join-Path $scriptDir "check_token_usage.ps1"

    if (-not (Test-Path $checkScript)) { exit 0 }

    $psCmd = Get-Command pwsh -ErrorAction SilentlyContinue
    if (-not $psCmd) { $psCmd = Get-Command powershell -ErrorAction SilentlyContinue }
    if (-not $psCmd) { exit 0 }

    $raw = & $psCmd.Source -NoProfile -File $checkScript 2>$null
    if (-not $raw) { exit 0 }

    $j = $raw | ConvertFrom-Json -ErrorAction SilentlyContinue
    if (-not $j) { exit 0 }

    if ($j.breach) {
        $msg = "[token-limit-guardian] Claude Code token-limit imminent: $($j.percentage)% used ($($j.used) / $($j.limit)). Safe checkpoint recommended. Invoke token-limit-guardian skill to checkpoint and register auto-resume task. estimated_reset=$($j.estimated_reset)"
        $output = @{
            systemMessage = $msg
            hookSpecificOutput = @{
                hookEventName = "UserPromptSubmit"
                additionalContext = $msg
            }
        }
        $output | ConvertTo-Json -Compress -Depth 6
    }
} catch {
    # Swallow — hook failure must not block the user prompt
}

exit 0
