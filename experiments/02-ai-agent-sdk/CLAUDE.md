# experiments/02-ai-agent-sdk

멀티 LLM/멀티모달을 지원하는 AI 에이전트 SDK 모듈. SDD(사양 중심 개발) 방식으로 진행한다.

## 디렉토리 구조

- `spec/` : 6종 SDD 사양 문서 (overview/features/api/data-model/error-handling/provider-spec)
- `sdk/` : Kotlin SDK 구현 (사양 합의 후 생성)
- `_workspace/` : 에이전트 중간 산출물 (검토·QA 보고서 등)
- `.claude/` : 본 실험 전용 하네스 (에이전트 4명 + 스킬 5개)

## 하네스: AI 에이전트 SDK 개발

**목표:** SDD 사양 중심으로 멀티 LLM/멀티모달 AI 에이전트 SDK를 개발한다.

**트리거:** SDK 사양/구현/검증 관련 작업 요청 시 `sdk-development-orchestrator` 스킬을 사용하라. 단순 질문(개념 설명, API 사용법 등)은 직접 응답 가능.

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
