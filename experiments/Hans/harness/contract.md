# Hans Assistant Contract

이 문서는 Hans AI assistant SDK가 따라야 하는 request, response, error 계약을 정의한다. 계약은 Markdown으로 관리하며 별도 JSON fixture 파일을 두지 않는다.

## Common Request

| Field | Required | Description |
|-------|----------|-------------|
| `requestId` | Yes | 실행 단위 고유 ID. 예: `H-001-case-001` |
| `scenarioId` | Yes | `scenarios.md`의 시나리오 ID |
| `feature` | Yes | 기능 키. 예: `chat_response`, `summary` |
| `locale` | Yes | 언어와 표현 기준. 기본값은 `ko-KR` |
| `timezone` | Yes | 날짜와 시간 해석 기준. 기본값은 `Asia/Seoul` |
| `input` | Yes | 기능별 입력. 자연어 텍스트, OCR 텍스트, 기준 시각 등을 포함 |
| `options` | No | 출력 길이, 말투, 최대 항목 수 같은 실행 옵션 |

## Required Fields

| Field | Required | Description |
|-------|----------|-------------|
| `requestId` | Yes | 실행 단위 고유 ID |
| `scenarioId` | Yes | `scenarios.md`의 시나리오 ID |
| `feature` | Yes | 기능 키 |
| `locale` | Yes | 언어와 표현 기준 |
| `timezone` | Yes | 날짜/시간 해석 기준 |
| `input` | Yes | 기능별 입력 |
| `options` | No | 출력 길이, 말투, max item 같은 실행 옵션 |
| `expected` | No | 코드 내부 계약에는 포함하지 않는다. 하네스 검증 기준은 `scenarios.md`에 둔다 |

## Common Response

| Field | Required | Description |
|-------|----------|-------------|
| `requestId` | Yes | 요청과 같은 ID |
| `scenarioId` | Yes | 요청과 같은 시나리오 ID |
| `feature` | Yes | 요청과 같은 기능 키 |
| `status` | Yes | `success`, `needs_clarification`, `blocked`, `error` 중 하나 |
| `resultType` | Yes | 기능별 결과 타입 |
| `result` | Yes when success | 기능별 구조화 결과 |
| `warnings` | No | 모호함, 낮은 confidence, 제한 사항 |
| `error` | Yes when error | error code, message, recovery action |

## Feature Result Shapes

| Feature | Result Type | Required Result Fields | Notes |
|---------|-------------|------------------------|
| `chat_response` | `chat_response` | `answer`, `tone`, `safety` | 외부 액션은 실행하지 않고 확인을 요구 |
| `summary` | `summary` | `summary`, `keyPoints`, `evidence` | 원문 근거를 유지 |
| `action_items` | `action_items` | `items` | 담당자, 마감일, clarification 여부를 분리 |
| `reminder_candidates` | `reminder_candidates` | `candidates` | 확정 일정이 아니라 후보로 반환 |
| `context_interpretation` | `context_interpretation` | `summary`, `signals`, `evidence` | OCR과 사용자 메시지 충돌을 표시 |

## Error Shape

실패 응답은 `code`, `message`, `recoveryAction`을 포함해야 한다.

| Field | Required | Description |
|-------|----------|-------------|
| `code` | Yes | 안정적인 error code |
| `message` | Yes | 사용자 또는 개발자가 이해할 수 있는 설명 |
| `recoveryAction` | Yes | 재시도, 추가 입력 요청, 기능 제한 안내 등 복구 방법 |

## Error Codes

| Code | Meaning |
|------|---------|
| `INVALID_INPUT` | 입력이 비어 있거나 처리할 수 없음 |
| `AMBIGUOUS_TIME` | 날짜/시간 표현을 확정할 수 없음 |
| `CONTEXT_CONFLICT` | 사용자 메시지와 OCR/첨부 컨텍스트가 충돌함 |
| `UNSUPPORTED_INPUT` | 현재 기능에서 지원하지 않는 입력 유형 |
| `SAFETY_BLOCKED` | 안전 정책상 응답 또는 액션 후보 생성 불가 |

## Rules

- 날짜/시간은 `timezone` 또는 `input.referenceDateTime` 없이 확정하지 않는다.
- 외부 예약, 결제, 메시지 발송, 캘린더 저장은 완료 상태로 표현하지 않는다.
- 요약과 컨텍스트 해석에는 근거 필드를 둔다.
- 모호한 값은 임의 생성하지 않고 warning, clarification, 낮은 confidence로 표현한다.
