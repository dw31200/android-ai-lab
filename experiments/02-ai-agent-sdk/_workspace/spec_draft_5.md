# Spec Draft 5 — F-000 QA 결과 후속 보강

작성자: spec-architect
작성 일시: 2026-05-07
입력: `_workspace/qa_report_1.md`
범위: Q-001 (Major) / S-001 (Minor) / S-002 (Minor) 한정

---

## 1. 반영 표

| QA-ID | Severity | 결정 방향 | 반영 위치 | 변경 요지 |
|-------|----------|-----------|-----------|-----------|
| Q-001 | Major | 사양 정정 (해결 방향 A) — `AiAgentClient.builder(context)` 정적 팩토리 채택 | `api.md` A-001 시그니처/사용 예/표(L14), `api.md` A-001 신규 "호출 형태" 섹션, `features.md` F-000 정상 흐름 1단계, `provider-spec.md` L193 v0.2 예시 | Builder 진입점을 정적 팩토리로 통일. `AiAgentClient.Builder(context)` 직접 호출 형태는 v0.1 API에 존재하지 않음을 명시. 현 implementer 구현 그대로 통과. |
| S-001 | Minor | `Builder.model()` 미설정 시 기본값 = `"claude-opus-4-7"` (provider-spec.md P-CLAUDE 첫 번째 모델) | `api.md` A-001 시그니처 주석, "기본값 정책" 표, 사용 예의 minimal 케이스 | 현 implementer 구현(`Builder.kt:111`)과 일치. F-000 정상 흐름 "apiKey만 설정해도 build 성공" 케이스를 사양에 명시적으로 정의. |
| S-002 | Minor | `Builder.timeout()` 미설정 시 기본값 = `30.seconds` | `api.md` A-001 시그니처 주석, "기본값 정책" 표, 사용 예의 minimal 케이스 | 현 implementer 구현(`Builder.kt:114`)과 일치. F-001 NFR p95 5초 + 안전 마진을 근거로 30초 채택. |

---

## 2. 종결 진술

- **Critical/Major 0건** (Q-001 Major는 본 라운드에서 사양 정정으로 종결)
- **신규 D-XXX 0건** (정책 변경 없이 결정값을 사양에 직접 명시하는 방식으로 처리, 운영 정책 갈래는 늘어나지 않음)
- 라운드 1~4 통과 부분(M-005 7-variant, M-010 단일 Provider, ERR-001~007 매핑, R-007/R-008/R-009/R-011/R-014/R-017~R-024, F-001~F-008 본문, A-002~A-012 시그니처, NFR/D-001~D-005, provider-spec P-CLAUDE 본문)은 **건드리지 않음** — diff는 `api.md` A-001 / `features.md` F-000 정상 흐름 1단계 / `provider-spec.md` L193 단일 라인 3곳에 한정.

---

## 3. 편집한 spec 파일 목록

| 파일 | 변경 라인 (대략) | 변경 요지 |
|------|------------------|-----------|
| `spec/api.md` | L14 (API 목록 표), L29~54 (A-001 전반) | A-001 이름·시그니처·"호출 형태" 신규 단락·"기본값 정책" 신규 표·사용 예 (full + minimal) 정정 |
| `spec/features.md` | L39 (F-000 정상 흐름 1단계) | `AiAgentClient.Builder(context) 생성` → `AiAgentClient.builder(context) 호출하여 Builder 획득 (정적 팩토리, A-001 참조)` |
| `spec/provider-spec.md` | L193 (v0.2 URL allowlist 예시) | 예시 코드를 `AiAgentClient.Builder.urlAllowlist(...)` → `AiAgentClient.builder(context).urlAllowlist(...)` (실제 API 변화 아닌 v0.2 예시 표기 통일) |

---

## 4. F-005/F-008 진입 시 implementer 인계 사항

### F-005 (Provider 선택/교체)
- A-001 변경은 F-005에 직접 영향 없음. `useProvider()`는 client 인스턴스 메서드이므로 Builder 진입점 변경과 무관.
- Q-002 (Minor, QA report) 인계: enum 확장 시 `Builder.SUPPORTED_PROVIDERS` 동기 갱신 + E-002 throw 단위 테스트 보강 필요. 본 라운드에서 사양 변경 없음(원래 라운드 1~4 결정 그대로 유지).

### F-008 (close)
- A-001 변경은 F-008에 직접 영향 없음. close 시맨틱(R-020) 그대로 유지.
- 단, `AiAgentClient.builder(context)` 호출 후 `.build()` 전 단계에서 close 호출이 발생하는 시나리오는 v0.1에 없음(Builder는 client 인스턴스가 아니므로 close 대상이 아님). 명시 불필요.

### F-006 (Hilt) — 라운드 5 진입 시 참고
- F-006은 본 보강에 직접 영향 없으나, Hilt 모듈에서 `AiAgentClient`를 `@Provides`로 노출할 때 Builder 진입점은 정적 팩토리(`AiAgentClient.builder(context)`)를 사용해야 한다. 다른 형태는 컴파일되지 않음.
- 또한 F-006의 `@Provides`에서 model/timeout을 호출자가 명시적으로 지정하지 않은 경우 본 보강의 기본값(`"claude-opus-4-7"` / `30.seconds`)이 그대로 적용된다 — Hilt가 추가 기본값을 정의할 필요 없음.

### 모든 후속 라운드 공통
- 기본값 변경 시 본 사양(`api.md` A-001 "기본값 정책" 표)을 단일 source of truth로 갱신. implementer는 코드에 임의의 다른 기본값을 사용해선 안 된다.
- `AiAgentClient.Builder(context)` 직접 호출 형태로 회귀하는 변경은 사양과 충돌하므로 별도 D-XXX 합의 없이 도입 금지.
