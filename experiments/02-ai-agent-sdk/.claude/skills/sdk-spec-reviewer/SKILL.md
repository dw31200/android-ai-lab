---
name: sdk-spec-reviewer
description: SDD 사양 문서를 비판적으로 검토하는 스킬. spec-architect가 작성한 overview/features/api/data-model/error-handling/provider-spec 문서의 누락·모순·모호함을 식별하고 Severity별 수정 제안을 제시한다. "사양 검토", "spec review", "리뷰해줘", "누락 찾아줘", "사양 점검" 요청 시 반드시 사용할 것.
---

# sdk-spec-reviewer

## 언제 이 스킬을 쓰는가

- spec-architect의 사양 초안 검토
- 검토 의견 반영 후 재검토
- 사양 변경 시 영향 분석
- 외부 입력으로 들어온 사양의 품질 점검

## 검토 원칙

### 1. 누락이 모순보다 비싸다

모순은 코드 작성 시 컴파일러가 잡아준다. 누락은 운영 중에 발견된다. 다음을 의심하라:

- F-XXX의 정상 흐름에서 "성공"으로 끝나는 단계가 실패할 수 있는 모든 경우 → E-XXX에 있는가?
- 외부 시스템 호출(LLM API)의 모든 실패 모드(타임아웃, 4xx, 5xx, 429, 502 게이트웨이 등) → ERR-XXX에 있는가?
- 사용자 코루틴 취소 → 어떻게 처리되는가?
- 동시 호출 → thread-safety, rate limit 처리?
- 큰 입력 (5MB 이미지, 100MB 영상) → 거부/리사이즈/실패?
- 만료된 API 키 → ERR로 정의되어 있는가?

### 2. 모호함은 측정 가능성으로 깨라

"빠르게", "충분히", "보통의" 같은 단어를 발견하면 즉시 "측정 가능한 기준?"으로 회신한다.

### 3. 일관성 검사

| 항목 | 검사 방법 |
|------|----------|
| 같은 개념 다른 이름 | grep으로 동의어 추적 (`prompt` vs `query` vs `input`) |
| ID 매핑 | 모든 E-XXX가 ERR-XXX와 매핑되는가? api.md의 함수가 F-XXX와 매핑되는가? |
| 시그니처 일치 | api.md의 시그니처가 data-model.md의 클래스를 정확히 참조? |
| In/Out scope 정합 | overview의 In Scope에 있는데 features에 빠진 항목? 반대로 features에는 있는데 overview에 없음? |

## 체크리스트 (필수)

각 항목을 표로 평가하여 검토 보고서에 기록한다.

### overview.md

- [ ] 목적이 한 문장으로 명료한가
- [ ] In Scope와 Out of Scope 둘 다 채워져 있는가
- [ ] Out of Scope에 "이유"가 적혀 있는가
- [ ] 기술 스택이 명시되어 있는가
- [ ] 의존성 정책이 있는가 (SDK는 의존성 충돌 회피가 중요)

### features.md

- [ ] 모든 F-XXX에 사전 조건이 있는가
- [ ] 모든 F-XXX에 정상 흐름 + 예외 흐름이 둘 다 있는가
- [ ] 외부 호출이 있는 F-XXX의 예외 흐름에 (네트워크, 타임아웃, 4xx, 5xx, 429, 401) 모두 다루는가
- [ ] NFR이 측정 가능한가
- [ ] 코루틴 취소 동작이 명시되어 있는가

### api.md

- [ ] 모든 public 함수의 시그니처가 토큰 단위로 명시되어 있는가
- [ ] suspend / Flow 사용이 명확한가
- [ ] 콜백 기반 API가 섞여 있지 않은가 (있다면 거부)
- [ ] 각 API가 어떤 F-XXX를 구현하는지 표시되어 있는가
- [ ] 사용 예가 1개 이상 있는가

### data-model.md

- [ ] 모든 필드의 타입과 필수 여부가 명시되어 있는가
- [ ] 멀티모달 입력이 sealed class로 정의되어 있는가
- [ ] 직렬화 방식(JSON, base64 등)이 명시되어 있는가
- [ ] 큰 입력(이미지, 영상)의 크기 제한이 명시되어 있는가

### error-handling.md

- [ ] 모든 ERR-XXX에 사용자 메시지(SDK 호출자에게 보일 메시지)가 있는가
- [ ] 모든 ERR-XXX에 복구 방법이 있는가
- [ ] AiException sealed class와 ERR-XXX의 매핑 표가 있는가
- [ ] features.md의 모든 E-XXX가 ERR-XXX와 매핑되는가 (역도 성립)

### provider-spec.md

- [ ] Provider 인터페이스가 정의되어 있는가
- [ ] 우선 구현할 Provider와 후속 Provider가 구분되어 있는가
- [ ] 신규 Provider 추가 절차가 단계별로 적혀 있는가
- [ ] 각 Provider의 멀티모달 지원 여부가 명시되어 있는가

## 검토 결과 작성 형식

```markdown
# Spec Review Report — {exp-id} v{n}

검토자: spec-reviewer
검토 일시: {YYYY-MM-DD}
대상 파일: {파일 목록}

## 요약
- Critical: {n}건
- Major: {n}건
- Minor: {n}건

## 항목별 검토

| ID | Severity | 위치 | 문제 | 제안 수정 |
|----|----------|------|------|----------|
| R-001 | Critical | features.md F-002 | 영상 입력 5MB 초과 시 동작 미정의 | E-007 추가: "영상 5MB 초과 시 AiException.InvalidInput throw" |
| R-002 | Major | api.md A-002 | 반환 타입이 Result vs throw 혼재 | Result로 통일 권장 |
...

## 미해결 결정 토론

(spec-architect가 "## 미해결 결정"에 남긴 항목에 대한 입장 표명)
```

## Severity 정의

| Severity | 정의 | 예시 |
|----------|------|------|
| **Critical** | 사양 누락으로 구현 불가 또는 운영 중 데이터 손실 가능 | 인증 실패 처리 미정의, 큰 입력 처리 미정의 |
| **Major** | 사양 모호함으로 잘못된 구현 가능, 또는 NFR 측정 불가 | "빠르게 응답", API 시그니처 미정 |
| **Minor** | 일관성/문서 품질 | 명명 불일치, 오타, 사용 예 누락 |

## 합의 종결 조건

- Critical: **0건**이 필수 종결 조건
- Major: 모두 (a) 반영 (b) 명시적 거부+이유 기록 중 하나로 처리
- Minor: 일괄 반영 또는 다음 라운드로 이월

## 핑퐁 방지

같은 항목으로 검토자 ↔ 작성자가 3회 이상 교환되면 즉시 오케스트레이터에 에스컬레이션. 이때 양쪽 입장을 정리한 미해결 결정 표를 첨부.
