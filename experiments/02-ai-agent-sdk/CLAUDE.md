# experiments/02-ai-agent-sdk

멀티 LLM/멀티모달을 지원하는 AI 에이전트 SDK 모듈. SDD(사양 중심 개발) 방식으로 진행한다.

## 디렉토리 구조

- `spec/` : 6종 SDD 사양 문서 (overview/features/api/data-model/error-handling/provider-spec)
- `sdk/` : Kotlin SDK 구현 (사양 합의 후 생성)
- `_workspace/` : 에이전트 중간 산출물 (검토·QA 보고서 등)
- `.claude/` : 본 실험 전용 하네스 (에이전트 4명 + 스킬 5개)

## 하네스: AI 에이전트 SDK 개발

**목표:** SDD 사양 중심으로 멀티 LLM/멀티모달 AI 에이전트 SDK를 개발한다.

**트리거:**
- SDK 사양/구현/검증 관련 작업 → `sdk-development-orchestrator` 스킬 사용
- 단순 질문(개념 설명, API 사용법 등)은 직접 응답 가능

**팀 구성:**
- spec-architect — 사양 작성
- spec-reviewer — 사양 검토
- android-implementer — Kotlin SDK 구현
- sdk-qa-validator — 사양-구현 정합성 검증

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-04-28 | 초기 구성 (4명 에이전트 팀 + 5개 스킬) | 전체 | SDD 학습용 하네스 구축 |
| 2026-04-29 | 하네스 위치를 본 실험 디렉토리 하위로 이동 | `.claude/` | 다른 실험에 영향 없도록 격리 |
| 2026-05-11 | 토큰 한도 관리 레이어 추가 (token-limit-guardian 스킬 + UserPromptSubmit/PostToolUse hook + PROGRESS.md 자동 갱신 + Task Scheduler 자동 재개) | `.claude/skills/token-limit-guardian/`, `.claude/settings.json`, `sdk-development-orchestrator` Phase 0 | 5시간 사용 한도 도달 시 작업 손실 방지 + 한도 리셋 후 보수적 자동 재개 |
| 2026-05-18 | token-limit-guardian (1) 측정 기준을 환경변수 → 스크립트 상수로 고정 (Claude Code `/usage`와 동일 원천 사용), (2) 임계값 80% → 90%, (3) 90% 도달 시 사용자 확인 없이 hook이 직접 자동 재시작 예약 | `.claude/skills/token-limit-guardian/scripts/check_token_usage.ps1`, `hook_user_prompt_submit.ps1`, `register_resume_task.ps1`, `SKILL.md`, `references/scripts-reference.md` | 수동 환경 설정 차단 + 무조건 자동 재시작 (사용자 요청) |
| 2026-05-19 | token-limit-guardian calibration 메커니즘 도입 (`recalibrate_limit.ps1` 신규, `_workspace/token_limit_calibration.json` scale_factor 적용). 초기 calibration: scale_factor=0.123739 (raw 187% → 표시 23%). 잘못 등록된 auto-resume task 해제. | `.claude/skills/token-limit-guardian/scripts/check_token_usage.ps1`, `recalibrate_limit.ps1` (신규), `SKILL.md`, `references/scripts-reference.md`, `_workspace/token_limit_calibration.json` (신규) | raw 계산이 실제 `/usage` 20% 대비 6배 부풀려져 false breach 발생 (cache_creation 가중치 비공개) → calibration 으로 보정 |
| 2026-07-10 | 토큰 한도 관리 레이어 전체 제거 (token-limit-guardian 스킬 삭제, 전역 UserPromptSubmit/PostToolUse hook 해제, Task Scheduler 자동 재개 task 해제, PROGRESS/calibration 상태 파일 삭제, orchestrator Phase 0 토큰 게이팅 제거) | `.claude/skills/token-limit-guardian/` (삭제), `~/.claude/settings.json` hooks, `sdk-development-orchestrator` Phase 0, `_workspace/PROGRESS.*` | hook이 전역 설정에 등록되어 다른 프로젝트 세션에도 실행되는 부작용 발생 → 자동화 기능 폐기 (사용자 요청) |
