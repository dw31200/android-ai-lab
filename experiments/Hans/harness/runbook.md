# Hans Harness Runbook

## 목적

이 문서는 Hans AI assistant SDK의 계약과 품질을 반복 검증하는 절차를 정의한다.

## 1. 준비

1. `README.md`의 P0 기능 범위를 확인한다.
2. `scenarios.md`에서 실행할 시나리오 ID를 선택한다.
3. `fixtures/{feature}.json`의 `scenarioId`가 선택한 시나리오와 일치하는지 확인한다.
4. 테스트 기준 시각이 필요한 경우 runner 또는 테스트 코드에서 명시한다.

기본값:

- `locale`: `ko-KR`
- `timezone`: `Asia/Seoul`
- 날짜 해석 기준: 테스트 실행 시각 또는 fixture의 `input.referenceDateTime`

## 2. 실행

각 fixture를 SDK 또는 모델 호출 계층에 전달한다.

검증 대상은 다음 두 가지다.

- **계약 검증**: 필수 필드, 타입, error code, warning, evidence shape.
- **의미 검증**: 요약 누락, 액션 과추출, 날짜 임의 추정, 충돌 컨텍스트 처리.

## 3. 판정

| 결과 | 기준 |
|------|------|
| Pass | 모든 `expected.assertions`를 만족한다 |
| Warning | 주요 기능은 동작하지만 Minor 개선이 필요하다 |
| Fail | 필수 필드 누락, 잘못된 날짜 확정, 안전 기준 위반, UI 연결 불가 |

## 4. 결함 분류

| 유형 | 설명 | 담당 |
|------|------|------|
| Contract | request/response/error shape가 불충분하거나 모순됨 | assistant-contract-designer |
| Scenario | 기대 결과가 모호하거나 검증 불가능함 | assistant-scenario-curator |
| Product | 기능 범위 또는 UX 기준이 위험하거나 과도함 | assistant-product-architect |
| QA | assertion이 누락되었거나 회귀 기준이 약함 | assistant-qa-validator |
| Implementation | SDK 코드가 계약을 지키지 않음 | 구현 담당 |

## 5. Regression 절차

1. 기능 추가 또는 계약 변경 전 현재 fixture 전체를 실행한다.
2. 변경 후 같은 fixture를 다시 실행한다.
3. 실패가 새로 생기면 regression으로 기록한다.
4. 의도된 계약 변경이면 fixture와 scenario를 같은 커밋에서 갱신한다.

## 6. 종료 기준

- H-001부터 H-005까지 최소 정상 케이스 1개가 Pass.
- 각 기능에 최소 1개 이상의 경계/실패 assertion이 존재.
- Blocker 0, Major 0.
- 남은 Minor는 후속 개선 목록에 기록.

## 7. 로그 규칙

- API 키, 토큰, 실제 연락처, 실제 계정 정보는 로그나 fixture에 남기지 않는다.
- 사용자 원문이 민감할 수 있으면 요약된 재현 입력으로 대체한다.
- 모델 응답 전문 저장이 필요하면 `_workspace/`에 보관하고 민감정보를 마스킹한다.
