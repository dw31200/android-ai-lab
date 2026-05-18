# Spec Draft — 02-ai-agent-sdk v0.1 (Round 4)

작성자: spec-architect
작성 일시: 2026-05-07
대상 파일: `spec/api.md`
참조 문서: `_workspace/spec_review_3.md` (R-025)

---

## 1. 라운드 4 작업 범위

라운드 3 검토에서 지적된 Minor 1건(R-025) 마무리 보강. A-009 표가 모든 동기 함수의 close 시맨틱을 망라하지만, 호출자가 개별 항목만 읽을 때의 일관성 차원에서 본문 한 줄 부기를 추가한다. 사양상 모순/누락은 없었으며, A-009 표 자체는 변경하지 않는다.

---

## 2. 변경 섹션 및 추가된 한 줄

| API ID | 섹션 | 추가 위치 | 추가된 한 줄 |
|--------|------|-----------|--------------|
| A-005 | AiAgentClient.useProvider | "동작" 섹션, "예외" 직전 | `- close 시맨틱: A-009 표 참조 (close 후 호출 시 AiException.Configuration("client closed") throw — 동기 함수)` |
| A-007 | Session.history | "반환" 섹션 마지막 줄 | `- close 시맨틱: A-009 표 참조 (close 후 호출 시 AiException.Configuration("client closed") throw — 동기 함수)` |
| A-008 | Session.clear | "동작" 섹션 마지막 | `- close 시맨틱: A-009 표 참조 (close 후 호출 시 AiException.Configuration("client closed") throw — 동기 함수)` |

A-006(send) 등 기존 부기 스타일을 따랐으며, A-009 표는 single source of truth로서 변경하지 않았다.

---

## 3. 종결 진술

- **Critical: 0건**
- **Major: 0건**
- **R-025 종결**: A-005/A-007/A-008 본문 close 시맨틱 부기 추가로 호출자 가이드 일관성 확보. A-009 표와의 불일치 0건.
- **신규 D-XXX: 0건** (미해결 결정 추가 없음)
- **신규 R-XXX: 0건** (라운드 4에서 새로 발견된 사양 결함 없음)
- 기타 사양 ID 카운트(F-/E-/M-/ERR-/A-/P-/D-) 라운드 3 종결 시점과 동일 유지.

→ **R-025 종결, Phase 3 진입 조건 유지**.
