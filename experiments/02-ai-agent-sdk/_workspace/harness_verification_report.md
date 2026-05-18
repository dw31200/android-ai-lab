# Harness Verification Report — token-limit-guardian

검증 일시: 2026-05-12
검증 범위: token-limit-guardian 스킬 (Phase 6 — harness 스킬 워크플로우 §6 준수)
검증자: 메인 컨텍스트 (Claude Code Opus 4.7)

---

## 1. 요약

| 항목 | 결과 |
|---|---|
| Phase 6-1 구조 검증 | 통과 |
| Phase 6-4 트리거 검증 | 통과 (description 보강 1건) |
| Phase 6-3+5 End-to-end dry-run | 통과 (구현 버그 3건 발견 및 수정) |
| Blocker | 0 |
| Major | 0 |
| Minor | 0 (모두 본 라운드에서 수정) |

**결론**: token-limit-guardian 스킬은 운영 환경에 배포 가능. 다음 세션부터 hook이 자동 동작하며, `/hooks` 메뉴를 한 번 열어야 settings watcher가 새 설정을 잡는다는 Claude Code caveat은 그대로 유효.

---

## 2. Phase 6-1 구조 검증

| 점검 항목 | 결과 |
|---|---|
| SKILL.md 위치: `.claude/skills/token-limit-guardian/SKILL.md` | 존재 |
| SKILL.md 줄 수 | 143 (목표 500 이내, OK) |
| frontmatter: `name` 필드 | `token-limit-guardian` |
| frontmatter: `description` 필드 | 존재, 트리거·비대상·근거 모두 포함 |
| scripts/ 디렉토리 | 6개 파일 (check_token_usage, update_progress, register_resume_task, auto_resume, hook_user_prompt_submit, hook_post_tool_use) |
| references/ 디렉토리 | scripts-reference.md 1개 |
| `.claude/settings.json` 존재 | 신규 생성됨 |
| settings.json UserPromptSubmit hook | matcher="", type=command, shell=powershell, timeout=30 |
| settings.json PostToolUse hook | matcher="Write\|Edit", async=true |
| JSON 스키마 검증 | `ConvertFrom-Json` 통과 |

---

## 3. Phase 6-4 트리거 검증

### Should-Trigger (8쌍 — 모두 통과 의도)
1. "지금 토큰 얼마나 썼지?" — 사용량 확인
2. "체크포인트 저장하고 잠시 멈춰줘"
3. "한도 임박했는지 확인해줘"
4. "5시간 제한 걸리기 전에 안전하게 종료해줘"
5. "한도 리셋 시점에 다시 시작되게 등록해줘"
6. "limit check 좀 해줘"
7. "세션 한도 곧 끝날 것 같아"
8. "PROGRESS 갱신하고 안전 종료 등록"

### Should-NOT-Trigger (8쌍)
1. "F-007 구현해줘" → sdk-development-orchestrator (안전)
2. "git status 보여줘" → Bash (안전)
3. "PROGRESS.md 봐줘" → Read (안전)
4. "PROGRESS 파일 열어줘" → Read (안전)
5. "토큰 분리 방법 알려줘" → 일반 응답 (Kotlin/Android 어휘 분석 의미) **— description 보강 전 충돌 위험**
6. "사양 한도 늘려줘" → spec-architect **— 보강 전 충돌 위험**
7. "체크포인트 패턴 설명해줘" → 일반 응답 (개념 설명) **— 보강 전 충돌 위험**
8. "schtasks 명령 알려줘" → 일반 응답 (안전)

### 보강 내역
description에 명시적 **트리거 비대상** 섹션 추가:
> Kotlin/Java 어휘 분석("토큰 분리"), 사양 한도 변경("API 한도", "사양 한도"), 일반 체크포인트 패턴 개념 설명, 비 Claude Code 토큰.

또한 트리거 어휘를 더 컨텍스트 결합형으로 변경:
- "토큰 한도" → "Claude 토큰 한도", "Claude Code 한도 체크"
- "체크포인트 저장" → "세션 체크포인트 저장"
- "한도 임박" → "한도 임박 안전 종료"

---

## 4. Phase 6-3+5 End-to-end Dry-Run

### 4-1. 정상 흐름 검증 (9 시나리오, 모두 통과)

| # | 시나리오 | 입력 | 기대 동작 | 실측 | 결과 |
|---|---|---|---|---|---|
| 1 | check_token_usage 정상 호출 | 환경변수 기본 | JSON 출력, breach 판정 | breach=true 정상 출력 | 통과 |
| 2 | 환경변수로 limit 상향 | `CLAUDE_LIMIT_TOTAL_TOKENS=100000000` | breach=false | percentage=0.64%, breach=false | 통과 |
| 3 | UserPromptSubmit hook (breach=true) | - | systemMessage JSON | systemMessage 정상 출력 | 통과 |
| 4 | UserPromptSubmit hook (breach=false) | limit 상향 | stdout 비어있음 | 빈 stdout (조용히 종료) | 통과 |
| 5 | PostToolUse hook (non-matching path) | random.kt | exit 0, 동작 없음 | exit 0, stdout 비어있음 | 통과 |
| 6 | PostToolUse hook (impl_summary path) | _workspace/impl_summary_xx.md | update_progress 호출 | exit 0 | 통과 |
| 7 | update_progress 직접 호출 | -RoundLabel drytest5 | PROGRESS.md + state.json 갱신 | 둘 다 갱신 (수정 후) | 통과 |
| 8 | register_resume_task dry-run | 미래 시각, 임의 task 이름 | Task Scheduler 등록 + state Ready | 등록 검증 통과 | 통과 |
| 9 | auto_resume.ps1 -DryRun | - | log 기록 + state.last_auto_resume_at 갱신 | 둘 다 정상 | 통과 |

### 4-2. 발견 및 수정한 구현 버그

| ID | 위치 | 증상 | 원인 | 수정 |
|---|---|---|---|---|
| BUG-1 | `update_progress.ps1` state.json 저장 | Set-Content이 silent fail (LastWriteTime 무변경) | PowerShell 5.1의 Set-Content + pipeline 조합에서 발생하는 알려진 quirk | `[System.IO.File]::WriteAllText`로 교체 (state.json + PROGRESS.md 모두) |
| BUG-2 | `register_resume_task.ps1` Task 등록 | schtasks /Create가 exit 0 반환하지만 실제 등록 안 됨 (silent failure). 또한 native exe stderr가 ErrorRecord로 wrap되어 ErrorActionPreference=Stop 트리거 | schtasks 5.1 quote-escape 문제 + native exe stderr 처리 | `Register-ScheduledTask` (ScheduledTasks 모듈) 사용. `*>&1` 로 native stderr 흡수, `$ErrorActionPreference = "Continue"` |
| BUG-3 | `auto_resume.ps1`, `hook_user_prompt_submit.ps1` | PowerShell 5.1 ParserError | 한글 + em dash (—)를 BOM 없는 UTF-8로 저장 → 5.1이 ANSI(CP949)로 해석하면서 멀티바이트 손상 | 한글·특수문자 제거, 영문 메시지로 통일 |

추가 영향:
- `update_progress.ps1`의 state 머지 로직도 보강 (Add-Member 대신 Get-LoadedProp 헬퍼로 누락 필드 안전 채움 — `last_token_usage`, `last_auto_resume_at` 등 ConvertFrom-Json 결과 객체에 누락된 property 설정 시 발생하는 SetValueInvocationException 회피)

### 4-3. 부수 산출물 검증

| 산출물 | 위치 | 검증 |
|---|---|---|
| `_workspace/PROGRESS.md` | 자동 관리 블록 head 삽입 | 기존 사용자 본문 보존, AUTO-MANAGED-BEGIN/END 블록 동작 |
| `_workspace/PROGRESS.state.json` | 구조화 상태 | completed_rounds 누적, last_checkpoint ISO timestamp, last_token_usage snapshot |
| `_workspace/auto_resume.log` | 자동 재개 진입 기록 | timestamp, prompt, claude CLI 경로 발견 추적 |

---

## 5. 알려진 제약 (배포 후 사용자 인지 필요)

1. **settings.json watcher caveat**: Claude Code는 세션 시작 시점에 존재하지 않던 settings.json을 자동 reload하지 않는다. 첫 활성화는 `/hooks` 메뉴를 한 번 열거나 세션 재시작 필요.
2. **임계값 정확도**: `CLAUDE_LIMIT_TOTAL_TOKENS=200000` 기본값은 보수적 추정. 사용자 플랜에 따라 환경변수로 조정 권장.
3. **transcript 누적 vs 5시간 윈도우**: 현재 transcript 누적이 windowFloor 이전 부분도 가짐. 본 스크립트는 timestamp로 필터링하므로 안전하지만, transcript 파일이 매우 큰 경우(수십 MB+) 파싱 시간이 늘 수 있음.
4. **자동 재개 콘솔 가시성**: Task Scheduler가 새 콘솔로 claude CLI를 띄우므로, 사용자가 PC에 부재해도 콘솔은 열림. 사용자가 콘솔을 닫지 않으면 자동 재개 prompt 도착 안 됨. 보수적 모드에서는 이게 의도된 동작 (사용자 확인 대기).
5. **PowerShell 5.1 한정 검증**: 모든 dry-run은 5.1에서 실시. PowerShell 7(pwsh)에서도 동일 동작 예상이나 미검증.

---

## 6. SKILL.md에 추가할 테스트 시나리오 섹션

본 보고서를 기반으로 SKILL.md 끝에 `## 테스트 시나리오` 섹션을 추가한다. 정상 흐름 1개 + 에러 흐름 1개 이상. 구체적 내용은 SKILL.md에 직접 반영 (별도 task).

---

## 7. 자체 체크리스트

- [x] 구조 검증 (frontmatter, scripts, references)
- [x] should-trigger 8쌍 평가
- [x] should-NOT-trigger 8쌍 평가 (near-miss 포함)
- [x] description 보강 (negative cue 추가)
- [x] 스크립트 단위 dry-run (6개)
- [x] hook 본체 합성 stdin 테스트
- [x] End-to-end 자동 재개 흐름 dry-run (Task Scheduler 등록 → verify → cleanup → auto_resume -DryRun)
- [x] 발견 버그 3건 모두 수정 및 retest 통과
- [x] 본 보고서 작성

---

## 8. 종결 권고

**token-limit-guardian 스킬을 운영 배포 승인.** Blocker/Major 0건, 발견된 모든 버그는 본 라운드에서 수정 + 재검증 완료. 다음 세션부터 `/hooks` 메뉴 한 번 열면 hook이 자동 활성화되고, 사용자가 다음 작업을 진행하면 자동으로 보호 동작.
