# Hans Assistant Contract

이 문서는 Hans AI assistant SDK fixture가 따르는 공통 request/expected 계약을 정의한다.

## Common Request

```json
{
  "requestId": "H-001-sample-001",
  "scenarioId": "H-001",
  "feature": "chat_response",
  "locale": "ko-KR",
  "timezone": "Asia/Seoul",
  "input": {},
  "options": {},
  "expected": {}
}
```

## Required Fields

| Field | Required | Description |
|-------|----------|-------------|
| `requestId` | Yes | fixture 단위 고유 ID |
| `scenarioId` | Yes | `scenarios.md`의 시나리오 ID |
| `feature` | Yes | 기능 키 |
| `locale` | Yes | 언어와 표현 기준 |
| `timezone` | Yes | 날짜/시간 해석 기준 |
| `input` | Yes | 기능별 입력 |
| `options` | No | 출력 길이, 말투, max item 같은 실행 옵션 |
| `expected` | Yes | pass/fail 검증 기준 |

## Common Expected

```json
{
  "status": "success",
  "resultType": "summary",
  "requiredFields": [
    "summary",
    "keyPoints",
    "evidence"
  ],
  "assertions": [
    "summary는 원문보다 짧다"
  ]
}
```

## Feature Result Shapes

| Feature | Result Type | Required Result Fields |
|---------|-------------|------------------------|
| `chat_response` | `chat_response` | `answer`, `tone`, `safety` |
| `summary` | `summary` | `summary`, `keyPoints`, `evidence` |
| `action_items` | `action_items` | `items` |
| `reminder_candidates` | `reminder_candidates` | `candidates` |
| `context_interpretation` | `context_interpretation` | `summary`, `signals`, `evidence` |

## Error Shape

실패 응답은 다음 구조를 따라야 한다.

```json
{
  "status": "error",
  "error": {
    "code": "INVALID_INPUT",
    "message": "Input text is empty.",
    "recoveryAction": "Ask the user to provide a non-empty prompt."
  }
}
```

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
