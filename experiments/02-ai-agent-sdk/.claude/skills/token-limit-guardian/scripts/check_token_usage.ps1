# check_token_usage.ps1
# Claude Code transcript JSONL을 파싱하여 5시간 윈도우 내 토큰 사용량을 추정한다.
# 출력: JSON 한 줄 — {used, limit, percentage, window_start, estimated_reset, breach}
#
# 호출 예:
#   pwsh -File check_token_usage.ps1
#   pwsh -File check_token_usage.ps1 -SessionId <uuid>
#   pwsh -File check_token_usage.ps1 -ProjectPath "C--Users-82106"

[CmdletBinding()]
param(
    [string]$SessionId = "",
    [string]$ProjectPath = "C--Users-82106"
)

$ErrorActionPreference = "Stop"

# 환경변수 기본값 — 빈 문자열 / null 안전 처리
function Get-IntOrDefault {
    param([string]$Value, [int]$Default)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $Default }
    $parsed = 0
    if ([int]::TryParse($Value, [ref]$parsed) -and $parsed -gt 0) { return $parsed }
    return $Default
}
function Get-DoubleOrDefault {
    param([string]$Value, [double]$Default)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $Default }
    $parsed = 0.0
    if ([double]::TryParse($Value, [ref]$parsed) -and $parsed -gt 0) { return $parsed }
    return $Default
}

$LimitTotal = Get-IntOrDefault $env:CLAUDE_LIMIT_TOTAL_TOKENS 200000
$Threshold = Get-DoubleOrDefault $env:CLAUDE_LIMIT_THRESHOLD 0.8
$WindowHours = Get-IntOrDefault $env:CLAUDE_LIMIT_WINDOW_HOURS 5

$ProjectsRoot = Join-Path $HOME ".claude\projects"
$ProjectDir = Join-Path $ProjectsRoot $ProjectPath

if (-not (Test-Path $ProjectDir)) {
    # 경로 추정 실패 → empty 응답
    $result = [pscustomobject]@{
        used = 0; limit = $LimitTotal; percentage = 0.0
        window_start = $null; estimated_reset = $null; breach = $false
        error = "project_dir_not_found: $ProjectDir"
    }
    $result | ConvertTo-Json -Compress
    exit 0
}

# 가장 최근 JSONL 파일 (또는 지정 SessionId)
$TranscriptFile = $null
if ($SessionId) {
    $candidate = Join-Path $ProjectDir "$SessionId.jsonl"
    if (Test-Path $candidate) { $TranscriptFile = $candidate }
} else {
    $TranscriptFile = Get-ChildItem $ProjectDir -Filter "*.jsonl" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}

if (-not $TranscriptFile -or -not (Test-Path $TranscriptFile)) {
    $result = [pscustomobject]@{
        used = 0; limit = $LimitTotal; percentage = 0.0
        window_start = $null; estimated_reset = $null; breach = $false
        error = "transcript_not_found"
    }
    $result | ConvertTo-Json -Compress
    exit 0
}

# 5시간 윈도우 계산: 윈도우 시작 = max(첫 메시지 시각, now - 5h)
$NowUtc = [DateTime]::UtcNow
$WindowFloor = $NowUtc.AddHours(-$WindowHours)

$script:totalTokens = 0
$script:cacheReadTotal = 0
$script:firstTimestampInWindow = $null

# JSONL을 한 줄씩 파싱 (대용량 대비 streaming)
Get-Content $TranscriptFile | ForEach-Object {
    if (-not $_) { return }
    try {
        $obj = $_ | ConvertFrom-Json -ErrorAction Stop
    } catch { return }

    # timestamp는 message wrapper에 존재
    $ts = $obj.timestamp
    if (-not $ts) { return }
    try {
        $msgTime = [DateTime]::Parse($ts, $null, [System.Globalization.DateTimeStyles]::RoundtripKind).ToUniversalTime()
    } catch { return }

    if ($msgTime -lt $WindowFloor) { return }

    if (-not $script:firstTimestampInWindow -or $msgTime -lt $script:firstTimestampInWindow) {
        $script:firstTimestampInWindow = $msgTime
    }

    # usage 필드는 assistant 메시지의 message.usage 에 위치
    $usage = $obj.message.usage
    if (-not $usage) { return }

    $inT = if ($usage.input_tokens) { [int]$usage.input_tokens } else { 0 }
    $outT = if ($usage.output_tokens) { [int]$usage.output_tokens } else { 0 }
    $ccT = if ($usage.cache_creation_input_tokens) { [int]$usage.cache_creation_input_tokens } else { 0 }
    $crT = if ($usage.cache_read_input_tokens) { [int]$usage.cache_read_input_tokens } else { 0 }
    # cache_read는 재사용이므로 한도 측정에서 제외 (input + output + cache_creation만 합산)
    $script:totalTokens += ($inT + $outT + $ccT)
    $script:cacheReadTotal += $crT
}

$totalTokens = $script:totalTokens
$firstTimestampInWindow = $script:firstTimestampInWindow
$cacheReadTotal = $script:cacheReadTotal

$percentage = if ($LimitTotal -gt 0) { [math]::Round(($totalTokens / $LimitTotal) * 100, 2) } else { 0 }
$breach = ($totalTokens -ge ($LimitTotal * $Threshold))

$estimatedReset = $null
if ($firstTimestampInWindow) {
    $estimatedReset = $firstTimestampInWindow.AddHours($WindowHours).ToString("o")
}

$result = [pscustomobject]@{
    used              = $totalTokens
    cache_read_tokens = $cacheReadTotal
    limit             = $LimitTotal
    threshold         = $Threshold
    percentage        = $percentage
    window_hours      = $WindowHours
    window_start      = if ($firstTimestampInWindow) { $firstTimestampInWindow.ToString("o") } else { $null }
    estimated_reset   = $estimatedReset
    breach            = $breach
    transcript        = $TranscriptFile
    checked_at        = $NowUtc.ToString("o")
}

$result | ConvertTo-Json -Compress
