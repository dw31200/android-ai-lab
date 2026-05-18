---
name: token-limit-guardian
description: Claude Code 자체의 5시간 사용 한도(rolling window)를 transcript JSONL로 추정·관리하는 운영 스킬. 80% 임계값 도달 시 _workspace/PROGRESS.md 체크포인트 + git 자동 커밋 + Windows Task Scheduler에 자동 재개 등록 후 안전 종료를 수행한다. 자동 재개 시 PROGRESS.md의 next_round 필드 명시값만 진행하는 보수적 모드. **트리거 대상(Claude Code 사용량/세션 한도 운영 맥락)**: "Claude 토큰 한도", "5시간 사용 한도", "Claude Code 한도 체크", "세션 체크포인트 저장", "한도 임박 안전 종료", "자동 재개 등록", "PROGRESS.md 갱신", "한도 리셋 시점", "limit guardian", "limit check", "토큰 사용량 추정". **트리거 비대상(혼동 주의)**: Kotlin/Java 어휘 분석("토큰 분리"), 사양 한도 변경("API 한도", "사양 한도"), 일반 체크포인트 패턴 개념 설명, 비 Claude Code 토큰. 본 스킬은 experiments/02-ai-agent-sdk 하네스의 보호 레이어로 SDD 워크플로우(spec-architect/spec-reviewer/android-implementer/sdk-qa-validator)와 독립적으로 동작.
---

# token-limit-guardian

## 핵심 역할

Claude Code의 5시간 rolling window 토큰 한도를 시간 기반·사용량 기반으로 추정하고, 임계값 도달 시 **체크포인트 자동화 + 안전 종료 + 자동 재개 트리거**를 수행한다. SDD 워크플로우의 라운드 단위 작업(impl_summary_N / qa_report_N) 손실을 방지한다.

## 핵심 제약 (먼저 알아두기)

1. **Claude는 자신의 토큰 잔여량을 직접 인지하지 못한다.** 본 스킬은 Claude Code의 **transcript JSONL 파일을 파싱**(`~/.claude/projects/<encoded-path>/<sessionId>.jsonl`)하여 5시간 윈도우 내 사용량을 추정한다. 합산은 `input_tokens + output_tokens + cache_creation_input_tokens` **3종**이며, `cache_read_input_tokens`는 재사용분이라 제외한다(별도 `cache_read_tokens` 필드로 참고용 노출).
2. **한도 도달 후엔 Claude가 응답할 수 없다.** 따라서 "자동 재개"는 Windows Task Scheduler가 한도 리셋 시점 이후 Claude Code CLI를 외부에서 호출하는 형태다.
3. **추정치는 정확한 한도가 아니다.** Anthropic이 정확한 한도 토큰 수를 공개하지 않으므로, 환경변수 `CLAUDE_LIMIT_TOTAL_TOKENS`(기본 200000)로 조정 가능하다.

## 작업 원칙

1. **결정적 자동화 우선** — 본 스킬의 동작은 PowerShell 스크립트로 외화되어 있다. Claude의 추론 의존도를 최소화하여 한도 임박 상황에서도 안정 동작.
2. **보수적 자동 재개** — 자동 재개 시 PROGRESS.md의 `next_round` 필드가 명시된 경우에만 그 라운드만 진행. 미명시 시 status 보고만 하고 사용자 입력 대기.
3. **체크포인트는 모든 라운드 종료에서** — `_workspace/impl_summary_N.md` 또는 `_workspace/qa_report_N.md` 생성 직후 PROGRESS.md 갱신 + git auto-commit.
4. **한도 추정은 보수적** — 5시간 윈도우 내 누적 토큰(input + output + cache_creation **3종**, cache_read 제외)이 80% 도달 시 즉시 안전 종료.
5. **사용자 가시성** — 모든 자동 동작은 사용자에게 보고. 자동 재개 시각, 등록된 Task Scheduler 항목, 다음 라운드 계획 명시.

## 환경변수 (선택적 조정)

| 변수 | 기본값 | 설명 |
|---|---|---|
| `CLAUDE_LIMIT_TOTAL_TOKENS` | 200000 | 5시간 한도 토큰 총량 추정치. 플랜별 조정. |
| `CLAUDE_LIMIT_THRESHOLD` | 0.8 | 임계값 비율 (80%). |
| `CLAUDE_LIMIT_RESET_BUFFER_MIN` | 5 | 한도 리셋 후 자동 재개까지 대기 시간(분). |
| `CLAUDE_LIMIT_WINDOW_HOURS` | 5 | rolling window 크기(시간). |

PowerShell 세션에서 `$env:CLAUDE_LIMIT_TOTAL_TOKENS = "500000"` 형태로 설정 가능. 영구 설정은 `[Environment]::SetEnvironmentVariable("CLAUDE_LIMIT_TOTAL_TOKENS", "500000", "User")`.

## 입출력 프로토콜

### 입력
- `~/.claude/projects/C--Users-82106/<sessionId>.jsonl` — 현재 세션 transcript (5시간 윈도우 사용량 추출 대상)
- 환경변수 (위 표)
- 사용자 요청: "한도 체크해줘" / "체크포인트 저장" / "안전 종료 등록" / "자동 재개 트리거 점검"
- (자동) PostToolUse hook이 `impl_summary_*.md` 또는 `qa_report_*.md` 생성 감지 시 자동 호출

### 출력
- `_workspace/PROGRESS.md` — 갱신된 체크포인트 (다음 세션 재개 정보 포함)
- Windows Task Scheduler 항목: `ClaudeCode-AutoResume-02-ai-agent-sdk` (한도 리셋 + 버퍼 시간 후 trigger)
- git commit (선택, 사용자 확인 후) — `_workspace/` 와 `sdk/` 변경 사항 보존
- 사용자 보고: 사용량 %, 잔여 추정 시간, 안전 종료 권고 또는 진행 가능 여부

## 워크플로우

### 모드 1: 정기 사용량 체크 (UserPromptSubmit hook이 호출)

1. `scripts/check_token_usage.ps1` 실행 — 현재 세션 transcript 파싱 → 5시간 윈도우 누적 토큰 계산 → 백분율 반환
2. **80% 미만**: 정상 진행, 표시 없음
3. **80% 이상**: 즉시 사용자에게 system reminder 형태로 경고. 다음 결정 요구:
   - "지금 즉시 안전 종료" → 모드 2 진입
   - "한 번 더 진행" → 사용자 책임 하에 계속

### 모드 2: 안전 종료 (수동 또는 자동 트리거)

1. **PROGRESS.md 갱신** (`scripts/update_progress.ps1`):
   - 마지막 완료 라운드 (예: T17 F-004 통과)
   - 다음 예정 라운드 (예: T18 F-007 진입 직전)
   - `next_round: null` (자동 재개 시 사용자 확인 대기 — 보수적 모드)
   - `token_usage_at_checkpoint`, `last_checkpoint` ISO timestamp
   - 누적 사양 명확화 항목 목록
2. **git auto-commit** (선택, 사용자 확인 후):
   - `experiments/02-ai-agent-sdk/_workspace/` + `experiments/02-ai-agent-sdk/sdk/` + `experiments/02-ai-agent-sdk/spec/` 스테이징
   - commit message: `chore: checkpoint at <round> (token limit guardian)`
3. **Task Scheduler 등록** (`scripts/register_resume_task.ps1`):
   - 등록 task 이름: `ClaudeCode-AutoResume-02-ai-agent-sdk`
   - 트리거: 첫 메시지 시각 + `CLAUDE_LIMIT_WINDOW_HOURS` + `CLAUDE_LIMIT_RESET_BUFFER_MIN`
   - 실행: `scripts/auto_resume.ps1` (다음 세션 시작)
4. **사용자 보고**:
   - 등록 완료 메시지 (자동 재개 시각, 등록된 task 이름)
   - 자동 재개 무력화 방법 (`schtasks /Delete /TN ClaudeCode-AutoResume-02-ai-agent-sdk /F`)
   - 한도 리셋 전에 재개하고 싶으면 사용자가 수동으로 `claude` 실행 가능

### 모드 3: 라운드 종료 체크포인트 (PostToolUse hook이 호출)

`impl_summary_*.md` 또는 `qa_report_*.md` 파일 쓰기가 감지되면 자동 호출:
1. `scripts/update_progress.ps1` — PROGRESS.md 갱신
2. 토큰 사용량 체크 (모드 1과 동일) — 80% 임박 시 사용자에게 경고
3. 사용자에게 "체크포인트 저장 완료" 짧은 보고 (어떤 라운드 추가, 사용량 %)

### 모드 4: 자동 재개 진입 (Task Scheduler가 호출)

본 모드는 새 Claude Code 세션의 SessionStart 단계에서 진입:
1. PROGRESS.md 읽기
2. `next_round` 필드 확인:
   - **명시 (예: T18)**: `next_round_prompt`에 정의된 의도대로 해당 라운드 진행 (sdk-development-orchestrator 호출)
   - **null (보수적 기본값)**: status 보고만 하고 사용자 입력 대기. "자동 재개됐습니다. 마지막 완료 라운드: <X>. 다음 진행할 라운드를 알려주세요."
3. 자동 재개 결과를 PROGRESS.md `last_auto_resume_at` 필드에 기록

## 스크립트 상세

본 스킬의 PowerShell 스크립트는 `scripts/` 하위에 있다. 각 스크립트는 독립 실행 가능하며, hook에서 또는 수동으로 호출.

| 스크립트 | 호출 시점 | 입력 | 출력 |
|---|---|---|---|
| `check_token_usage.ps1` | UserPromptSubmit hook / 수동 | 환경변수, transcript 파일 | JSON: `{used, limit, percentage, window_start, estimated_reset}` |
| `update_progress.ps1` | PostToolUse hook / 수동 | 라운드 ID, 산출물 경로 | 갱신된 PROGRESS.md |
| `register_resume_task.ps1` | 안전 종료 시 / 수동 | 재개 시각, 작업 디렉토리 | Task Scheduler 항목 생성 |
| `auto_resume.ps1` | Task Scheduler 트리거 | (없음 — PROGRESS.md 자동 읽기) | Claude Code CLI 실행 |

각 스크립트의 자세한 인터페이스는 `references/scripts-reference.md` 참조.

## 사양 명확화 정책

본 스킬은 **결정적 자동화**가 목표이므로 사양 모호함이 거의 없어야 한다. 만약 다음 케이스 발생 시 사용자 결정 요구:
- 환경변수가 비현실적 값 (예: `CLAUDE_LIMIT_TOTAL_TOKENS=0`)
- transcript 파일을 찾을 수 없음
- Task Scheduler 등록 실패 (권한 부족 등)
- PROGRESS.md가 다른 구조로 존재 (스키마 충돌)

## 트리거 가이드 (Should-Trigger / Should-NOT-Trigger)

**Should-Trigger 예시:**
- "지금 토큰 얼마나 썼지?"
- "체크포인트 저장하고 잠시 멈춰줘"
- "한도 임박했는지 확인해줘"
- "자동 재개 설정 잘 됐는지 봐줘"
- "한도 리셋 시점에 다시 시작되게 등록해줘"
- "5시간 제한 걸리기 전에 안전하게 종료해줘"

**Should-NOT-Trigger 예시 (다른 스킬/도구가 적합):**
- "F-007 구현해줘" → sdk-development-orchestrator
- "PROGRESS.md를 봐줘" → 단순 Read
- "git status 보여줘" → 단순 Bash
- "오늘 작업 회고" → 일반 응답

## 협업

본 스킬은 **단일 스킬**로, 별도 에이전트가 없다. SDD 4명 에이전트와는 **간접 협업** — 라운드 종료 시 PROGRESS.md 갱신을 통해.

## 테스트 시나리오

### 정상 흐름: 한도 임박 → 안전 종료 → 자동 재개

1. 사용자가 SDD 라운드 진행 중 한도 80% 도달
2. UserPromptSubmit hook이 `check_token_usage.ps1` 호출 → `breach=true` → systemMessage JSON을 stdout으로 출력
3. Claude가 systemMessage를 system reminder로 감지하고 본 스킬을 자동 호출
4. 스킬이 사용자에게 옵션 제시 (즉시 안전 종료 / 책임 하에 계속)
5. "즉시 안전 종료" 선택 시:
   - `update_progress.ps1 -RoundLabel "X"` → PROGRESS.md + state.json 갱신
   - 사용자 동의 시 git auto-commit
   - `register_resume_task.ps1` → Task Scheduler 등록 (트리거 = `estimated_reset + buffer`)
   - 사용자에게 등록 완료 + 무력화 명령(`Unregister-ScheduledTask`) 보고
6. 5시간 윈도우 리셋 + 버퍼 후 Task Scheduler가 `auto_resume.ps1` 실행
7. `auto_resume.ps1`이 PROGRESS.state.json 읽고 `next_round` 확인:
   - 명시 시: `next_round_prompt`로 Claude CLI 실행
   - null (보수적 기본): status-only prompt로 실행 (사용자 확인 대기)

### 에러 흐름 A: hook 실패

- `check_token_usage.ps1`이 transcript 파일을 찾지 못함 (예: 권한, 경로 변경)
- hook은 fail-open 정책: 빈 JSON 또는 error 필드 채운 JSON 반환, exit 0
- Claude 측에서는 systemMessage가 안 보이므로 정상 진행 (한도 보호 안 됨)
- 사용자가 수동으로 "한도 체크해줘" 호출하면 그 시점에 transcript 발견 → 재진입

### 에러 흐름 B: Task Scheduler 등록 실패

- 사용자 권한 부족 또는 ScheduledTasks 모듈 미설치
- `register_resume_task.ps1`이 `{"registered":false, "error":"..."}` 반환
- 스킬은 사용자에게 등록 실패를 보고하고, 수동 재개 가이드 제시 (5시간 후 `claude` 실행)
- 안전 종료(PROGRESS.md 갱신)는 그대로 수행 — 자동 재개만 무력화

### 에러 흐름 C: 자동 재개 시 Claude CLI 못 찾음

- `auto_resume.ps1`이 `Get-Command claude`와 `%LOCALAPPDATA%\Programs\claude\claude.exe` 모두 실패
- log에 `claude CLI not found in PATH - manual resume required` 기록 후 exit 1
- 사용자가 다음 시점에 수동 진입 시 PROGRESS.state.json `last_auto_resume_at` 미갱신 상태 → 재개 누락 명확히 식별 가능

---

## 변경 이력

| 날짜 | 변경 | 사유 |
|---|---|---|
| 2026-05-11 | 초기 구성 | 5시간 토큰 한도 보호 레이어 추가 (사용자 요청) |
| 2026-05-12 | description 보강 (negative cue) + 구현 버그 3건 수정 (state.json silent fail / schtasks silent fail / PowerShell 5.1 인코딩) + 테스트 시나리오 섹션 추가 | Phase 6 검증 (harness 스킬 워크플로우 §6) 결과 반영 |
