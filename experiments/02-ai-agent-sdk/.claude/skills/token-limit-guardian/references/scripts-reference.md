# Scripts Reference — token-limit-guardian

## check_token_usage.ps1

**역할:** Claude Code transcript JSONL을 파싱해 5시간 윈도우 내 누적 토큰 사용량을 계산하고 임계값 위반 여부를 JSON으로 반환.

**파라미터:**
- `-SessionId <uuid>` (선택): 특정 세션 강제 지정. 미지정 시 가장 최근 수정된 JSONL 자동 선택.
- `-ProjectPath <encoded>` (선택, 기본 `C--Users-82106`): `~/.claude/projects/<path>/` 디렉토리.

**환경변수:**
- `CLAUDE_LIMIT_TOTAL_TOKENS` (기본 200000)
- `CLAUDE_LIMIT_THRESHOLD` (기본 0.8)
- `CLAUDE_LIMIT_WINDOW_HOURS` (기본 5)

**출력 JSON 필드:**
```json
{
  "used": <int>,
  "limit": <int>,
  "threshold": <float>,
  "percentage": <float>,
  "window_hours": <int>,
  "window_start": "<ISO UTC | null>",
  "estimated_reset": "<ISO UTC | null>",
  "breach": <bool>,
  "transcript": "<file path>",
  "checked_at": "<ISO UTC>"
}
```

**합산 공식:** `used = sum(input_tokens + output_tokens + cache_creation_input_tokens)` for messages whose timestamp ∈ [now - 5h, now]. `cache_read_input_tokens`는 재사용이므로 한도 측정에서 제외되며, 응답 JSON의 `cache_read_tokens` 필드로 참고용 노출.

---

## update_progress.ps1

**역할:** `_workspace/PROGRESS.md`의 자동 관리 블록을 갱신하고, `_workspace/PROGRESS.state.json`에 구조화된 상태를 저장.

**파라미터:**
- `-RoundLabel <T17>` (선택): 신규 완료 라운드 라벨. 기존에 동일 라벨 있으면 중복 추가 안 함.
- `-RoundDescription <"F-004 통과">` (선택): 라운드 설명.
- `-NextRound <T18>` (선택): 다음 진행할 라운드. **명시되면 자동 재개 시 그 라운드를 진행**.
- `-NextRoundPrompt <"...">` (선택): 자동 재개 시 사용할 prompt 본문.
- `-WorkspaceDir <path>` (선택, 기본 `02-ai-agent-sdk\_workspace`)
- `-SkipTokenCheck` (선택): 토큰 체크 생략 (단위 테스트용).

**부수 효과:**
1. `_workspace/PROGRESS.state.json` 생성/갱신 (구조화 JSON)
2. `_workspace/PROGRESS.md` 머리말의 `AUTO-MANAGED-BEGIN/END` 블록 갱신 (사용자 작성 본문은 보존)
3. `check_token_usage.ps1`을 호출하여 사용량 스냅샷 포함

**state JSON 스키마:**
```json
{
  "completed_rounds": [
    {"label": "T17", "description": "F-004 통과", "checkpoint_at": "<ISO>"}
  ],
  "next_round": "T18" | null,
  "next_round_prompt": "F-007 진입 가이드..." | null,
  "last_checkpoint": "<ISO>",
  "last_token_usage": { ... check_token_usage.ps1 output ... },
  "last_auto_resume_at": "<ISO>" | null
}
```

---

## register_resume_task.ps1

**역할:** Windows Task Scheduler에 1회성(`/SC ONCE`) task 등록. 트리거 시각 = 한도 리셋 + 버퍼.

**파라미터:**
- `-ResetAtUtc <"2026-05-11T20:30:00Z">` (선택): 명시적 리셋 시각. 미지정 시 `check_token_usage.ps1`의 `estimated_reset` 사용.
- `-BufferMinutes <5>` (선택, 기본 환경변수 또는 5분).
- `-TaskName <name>` (선택, 기본 `ClaudeCode-AutoResume-02-ai-agent-sdk`)
- `-WorkingDir <path>` (선택, 기본 `02-ai-agent-sdk`)

**부수 효과:**
- 동명 task 존재 시 `schtasks /Delete /F`로 삭제 후 재등록
- `schtasks /Create /SC ONCE /ST <HH:mm> /SD <yyyy/MM/dd> /TR <pwsh ... auto_resume.ps1>` 실행

**삭제 명령** (자동 재개 무력화):
```
schtasks /Delete /TN ClaudeCode-AutoResume-02-ai-agent-sdk /F
```

**조회 명령**:
```
schtasks /Query /TN ClaudeCode-AutoResume-02-ai-agent-sdk
```

---

## auto_resume.ps1

**역할:** Task Scheduler가 호출하는 자동 재개 진입점. `PROGRESS.state.json`을 읽고 Claude Code CLI를 새 콘솔로 실행.

**파라미터:**
- `-WorkingDir <path>` (선택, 기본 `02-ai-agent-sdk`).

**동작:**
1. `PROGRESS.state.json` 읽기. 없으면 fallback 진행.
2. `last_auto_resume_at` 필드 갱신.
3. `next_round` 명시 시 `next_round_prompt`를 prompt로 사용. 미명시 시 보수적 status-only prompt 사용.
4. `claude` CLI 실행 (PATH 또는 `%LOCALAPPDATA%\Programs\claude\claude.exe` 검색).
5. `_workspace/auto_resume.log`에 동작 기록 (timestamp + prompt).

**보수적 모드 prompt 예시:**
```
자동 재개됐습니다. (token-limit-guardian) 마지막 완료 라운드: T17. 
PROGRESS.md를 읽고 status만 보고해주세요. 다음 라운드 진행 여부는 사용자가 결정합니다.
```

**주의:** Task Scheduler가 ONCE 트리거이므로 별도 정리 불필요. 단, 자동 재개 후에도 task 항목은 schtasks에 남으므로 사용자가 원하면 위 삭제 명령으로 제거.
