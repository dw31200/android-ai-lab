# experiments/02-ai-agent-sdk

멀티 LLM/멀티모달을 지원하는 AI 에이전트 SDK 모듈. SDD(사양 중심 개발) 방식으로 진행한다.

## 디렉토리 구조

- `spec/` : 6종 SDD 사양 문서 (overview/features/api/data-model/error-handling/provider-spec)
- `sdk/` : Kotlin SDK 구현 (사양 합의 후 생성)
- `_workspace/` : 에이전트 중간 산출물 (검토·QA 보고서 등)
- `.claude/` : 본 실험 전용 하네스 (에이전트 4명 + 스킬 6개 + Hook 자동화)

## 하네스: AI 에이전트 SDK 개발

**목표:** SDD 사양 중심으로 멀티 LLM/멀티모달 AI 에이전트 SDK를 개발한다.

**트리거:**
- SDK 사양/구현/검증 관련 작업 → `sdk-development-orchestrator` 스킬 사용
- 토큰 한도/체크포인트/자동 재개 관련 → `token-limit-guardian` 스킬 사용
- 단순 질문(개념 설명, API 사용법 등)은 직접 응답 가능

**팀 구성:**
- spec-architect — 사양 작성
- spec-reviewer — 사양 검토
- android-implementer — Kotlin SDK 구현
- sdk-qa-validator — 사양-구현 정합성 검증

**보호 레이어 (Hook 자동화):**
- UserPromptSubmit hook — 매 사용자 메시지 시 토큰 사용량 체크. 80% 초과 시 systemMessage로 자동 경고
- PostToolUse hook — `_workspace/impl_summary_*.md` 또는 `_workspace/qa_report_*.md` 생성 시 자동 PROGRESS.md 갱신
- 한도 임박 시: `register_resume_task.ps1`로 Windows Task Scheduler에 자동 재개 등록 (보수적 모드 — `next_round` 명시값만 진행)

**환경변수 (token-limit-guardian, 선택 조정):**
- `CLAUDE_LIMIT_TOTAL_TOKENS` (기본 200000)
- `CLAUDE_LIMIT_THRESHOLD` (기본 0.8)
- `CLAUDE_LIMIT_RESET_BUFFER_MIN` (기본 5)
- `CLAUDE_LIMIT_WINDOW_HOURS` (기본 5)

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-04-28 | 초기 구성 (4명 에이전트 팀 + 5개 스킬) | 전체 | SDD 학습용 하네스 구축 |
| 2026-04-29 | 하네스 위치를 본 실험 디렉토리 하위로 이동 | `.claude/` | 다른 실험에 영향 없도록 격리 |
| 2026-05-11 | 토큰 한도 관리 레이어 추가 (token-limit-guardian 스킬 + UserPromptSubmit/PostToolUse hook + PROGRESS.md 자동 갱신 + Task Scheduler 자동 재개) | `.claude/skills/token-limit-guardian/`, `.claude/settings.json`, `sdk-development-orchestrator` Phase 0 | 5시간 사용 한도 도달 시 작업 손실 방지 + 한도 리셋 후 보수적 자동 재개 |
