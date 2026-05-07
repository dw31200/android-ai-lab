# Skill: hans-assistant-orchestrator

## Purpose

Hans AI assistant SDK 하네스의 전체 작업 흐름을 조율한다. 제품 범위, 계약, 시나리오, QA 문서가 서로 어긋나지 않게 순서를 정하고 결과를 통합한다.

## Trigger

다음 요청이 들어오면 이 스킬을 사용한다.

- Hans 하네스를 만들거나 보완해달라는 요청.
- AI assistant SDK 기능을 추가하거나 수정하는 요청.
- 요약, 액션 아이템, 리마인더, 컨텍스트 해석 기준을 바꾸는 요청.
- 이전 하네스 결과를 바탕으로 재실행하거나 개선하는 요청.

## Inputs

- 사용자 요구사항.
- `README.md`의 기능 범위.
- `contract.md`의 request/response/error 계약.
- `scenarios.md`의 기능별 검증 기준.
- `team.md`의 역할 분담.

## Outputs

- 업데이트된 하네스 문서.
- 기능별 변경 요약.
- QA에서 확인해야 할 regression 목록.
- 사용자에게 확인이 필요한 미해결 결정.

## Workflow

### Phase 0. Context Scan

1. `experiments/Hans/harness` 아래 문서 목록을 확인한다.
2. 요청이 제품 범위, 계약, 시나리오, QA 중 어디에 해당하는지 분류한다.
3. JSON fixture 파일이 새로 생기지 않았는지 확인한다.
4. 변경 범위가 Hans 폴더 밖으로 나가지 않는지 확인한다.

### Phase 1. Product Scope

`assistant-product-architect` 기준으로 기능 범위를 정리한다.

- 새 기능이 P0인지 P1인지 판단한다.
- 자동 실행하면 위험한 행동이 있는지 확인한다.
- Android UI에서 어떤 화면 또는 상태로 연결될지 적는다.

### Phase 2. Contract Alignment

`assistant-contract-designer` 기준으로 계약을 정리한다.

- request 필드가 충분한지 확인한다.
- response result shape가 UI에서 바로 쓰일 수 있는지 확인한다.
- error code와 recovery action이 빠지지 않았는지 확인한다.

### Phase 3. Scenario Authoring

`assistant-scenario-curator` 기준으로 시나리오를 작성한다.

- 정상 케이스, 경계 케이스, 실패 케이스를 Markdown으로 작성한다.
- 샘플 입력은 표 또는 짧은 문단으로 둔다.
- 검증 기준은 Pass/Fail이 명확하게 갈리도록 쓴다.

### Phase 4. QA

`assistant-qa-validator` 기준으로 검증한다.

- 기능 ID가 문서 간 일치하는지 확인한다.
- 계약에 없는 필드를 시나리오가 요구하지 않는지 확인한다.
- 날짜, 개인정보, 외부 액션 기준이 빠지지 않았는지 확인한다.

### Phase 5. Report

최종 보고에는 다음을 포함한다.

- 변경한 문서 목록.
- 삭제하거나 유지한 파일.
- 남은 결함 또는 후속 결정.

## Guardrails

- JSON fixture 파일을 만들지 않는다.
- Hans 폴더 밖의 파일은 요청이 명시되지 않으면 수정하지 않는다.
- 외부 액션은 항상 후보 또는 제안으로만 표현한다.
- 상대 날짜는 기준 시각과 timezone 없이 확정하지 않는다.

## Checklist

- [ ] `README.md`의 디렉토리 구조가 실제 파일과 맞다.
- [ ] `contract.md`에 기능별 result shape가 있다.
- [ ] `scenarios.md`에 샘플 입력과 검증 기준이 있다.
- [ ] `skills/`에 역할별 운영 문서가 있다.
- [ ] JSON fixture 파일이 없다.
