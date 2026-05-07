# Skill: assistant-contract-designer

## Purpose

Hans AI assistant SDK의 request, response, error 계약을 설계한다. 목표는 모델 응답을 자유 텍스트로 방치하지 않고 앱 코드가 안정적으로 다룰 수 있는 구조로 고정하는 것이다.

## Responsibilities

- `contract.md`의 공통 요청과 응답 필드를 관리한다.
- 기능별 result shape를 정의한다.
- error code와 recovery action을 유지한다.
- `scenarios.md`가 계약에 없는 결과를 요구하지 않도록 조정한다.

## Contract Principles

- request에는 `requestId`, `scenarioId`, `feature`, `locale`, `timezone`, `input`이 있어야 한다.
- response에는 `status`, `resultType`, `result`, `warnings`, `error`의 책임이 분리되어야 한다.
- 날짜/시간 필드는 timezone 없이 확정하지 않는다.
- 실패 응답은 사용자 또는 개발자가 다음 행동을 알 수 있게 `recoveryAction`을 가진다.

## Feature Result Guidance

### Chat Response

필수 결과:

- `answer`: 사용자에게 보여줄 답변.
- `tone`: `concise`, `friendly`, `professional` 같은 분류값.
- `safety`: 외부 액션 제한 또는 안전 상태.

주의:

- 실제 예약, 구매, 메시지 발송 완료처럼 표현하지 않는다.
- 사용자의 확인이 필요한 경우 answer와 safety에 모두 반영한다.

### Summary

필수 결과:

- `summary`: 짧은 요약.
- `keyPoints`: 핵심 항목 목록.
- `evidence`: 원문 근거.

주의:

- 원문에 없는 결론을 만들지 않는다.
- 근거 없는 날짜와 담당자를 추가하지 않는다.

### Action Items

필수 결과:

- `items`: 작업 후보 목록.
- 각 item의 `title`, `assignee`, `dueDate`, `confidence`, `needsClarification`.

주의:

- 담당자와 마감일이 없으면 비워두거나 clarification으로 둔다.
- 배경 설명을 작업으로 과추출하지 않는다.

### Reminder Candidates

필수 결과:

- `candidates`: 리마인더 후보 목록.
- 각 candidate의 제목, 날짜/시간, confidence, 확인 필요 여부.

주의:

- 저장 완료가 아니라 후보 생성으로 표현한다.
- date-only와 date-time을 구분한다.

### Context Interpretation

필수 결과:

- `summary`: 통합 요약.
- `signals`: 일정, 할 일, 중요 정보 후보.
- `conflicts`: 사용자 메시지와 OCR 정보 충돌.
- `evidence`: 출처 근거.

주의:

- OCR만 있는 경우 이미지 원본 부재 한계를 표시한다.
- 충돌하는 정보는 임의 선택하지 않는다.

## Error Codes

| Code | Use When |
|------|----------|
| `INVALID_INPUT` | 입력이 비어 있거나 처리 불가 |
| `AMBIGUOUS_TIME` | 날짜/시간을 확정할 수 없음 |
| `CONTEXT_CONFLICT` | 입력 소스 간 정보 충돌 |
| `UNSUPPORTED_INPUT` | 지원하지 않는 입력 유형 |
| `SAFETY_BLOCKED` | 안전 기준 때문에 처리 불가 |

## Review Checklist

- [ ] 새 시나리오가 기존 계약으로 표현 가능하다.
- [ ] result shape가 UI 연결 가능한 단위로 나뉜다.
- [ ] error code가 복구 행동을 포함한다.
- [ ] 날짜/시간 해석 기준이 명확하다.
- [ ] JSON fixture 파일을 새로 만들지 않았다.
