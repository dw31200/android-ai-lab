# Hans Harness Team

Hans 하네스는 4개 역할을 기준으로 운영한다. 실제 에이전트로 실행하지 않더라도, 문서 검토와 구현 작업을 이 역할에 맞춰 나누면 된다.

## Roles

| Role | Responsibility | Main Outputs |
|------|----------------|--------------|
| assistant-product-architect | 기능 범위, 사용자 가치, 앱 통합 흐름 정리 | 기능 우선순위, UX 기준, 보류 결정 목록 |
| assistant-contract-designer | request/response/error 계약 설계 | `contract.md`, fixture shape, error code |
| assistant-scenario-curator | 실제 사용 시나리오와 fixture 작성 | `scenarios.md`, `fixtures/*.json` |
| assistant-qa-validator | 계약, 시나리오, fixture 정합성 검증 | QA report, regression checklist |

## Workflow

1. product architect가 기능 범위와 위험한 자동 액션 제한을 정한다.
2. contract designer가 기능별 구조화 출력과 실패 계약을 만든다.
3. scenario curator가 정상, 경계, 실패 fixture를 작성한다.
4. QA validator가 scenario ID, fixture shape, assertion, timezone, privacy 기준을 검증한다.
5. Blocker와 Major가 0이 되면 구현 또는 SDK 연결 단계로 넘어간다.

## Communication Rules

- 제품 범위 변경은 계약과 fixture를 함께 재검토한다.
- fixture 변경은 `scenarios.md`의 assertion과 함께 바꾼다.
- 계약 변경은 기존 fixture regression 결과를 확인한 뒤 반영한다.
- 날짜 해석, 외부 액션, 개인정보 처리는 QA가 항상 재검증한다.

## Escalation

| Situation | Owner | Required Action |
|-----------|-------|-----------------|
| 기능 범위가 너무 넓음 | assistant-product-architect | P0/P1/P2로 분리 |
| fixture가 계약과 맞지 않음 | assistant-contract-designer | 계약 또는 fixture shape 수정 |
| expectation이 검증 불가능함 | assistant-scenario-curator | assertion을 측정 가능하게 재작성 |
| 안전 기준이 빠짐 | assistant-qa-validator | Blocker 또는 Major로 기록 |
