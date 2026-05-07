# Skill: assistant-product-architect

## Purpose

Hans AI assistant SDK가 어떤 사용자 문제를 해결할지 정의한다. 기능을 넓히는 역할이 아니라, 앱에서 실제로 안정적으로 쓸 수 있는 범위로 줄이고 우선순위를 정하는 역할이다.

## Responsibilities

- 기능 범위를 P0, P1, P2로 나눈다.
- 각 기능의 사용자 가치와 UI 연결 지점을 설명한다.
- 외부 액션, 개인정보, 모호한 날짜처럼 위험한 영역을 제한한다.
- 구현 전에 반드시 합의해야 할 제품 결정을 기록한다.

## Product Principles

- Hans는 특정 앱 전용 assistant가 아니라 범용 SDK다.
- 모델 응답은 앱 UI가 바로 사용할 수 있는 구조를 가져야 한다.
- 사용자가 확인하지 않은 예약, 결제, 메시지 발송, 캘린더 저장은 하지 않는다.
- 모호한 정보는 확정하지 않고 clarification, warning, 낮은 confidence로 표현한다.

## P0 Scope

| ID | Feature | User Value | UI Surface |
|----|---------|------------|------------|
| H-001 | Chat response | 일반 요청을 안전하게 정리하고 답변 | Chat bubble, task suggestion |
| H-002 | Summary | 긴 문서를 빠르게 이해 | Summary panel, note preview |
| H-003 | Action items | 해야 할 일을 놓치지 않음 | Task list, checklist |
| H-004 | Reminder candidates | 일정 후보를 빠르게 만들기 | Reminder draft, calendar preview |
| H-005 | Context interpretation | 메시지와 OCR 맥락을 함께 이해 | Context card, extracted signal list |

## Decision Template

새 기능을 추가할 때는 다음 질문에 답한다.

| Question | Required Answer |
|----------|-----------------|
| 사용자가 얻는 직접 이득은 무엇인가 | 한 문장으로 설명 |
| SDK가 반환해야 하는 구조화 결과는 무엇인가 | 필드 목록 |
| 자동 실행하면 위험한 행동이 있는가 | Yes 또는 No, 있으면 제한 방식 |
| 모호한 입력은 어떻게 처리하는가 | warning, clarification, blocked 중 선택 |
| Android UI는 어디에 연결하는가 | 화면 또는 컴포넌트 |

## Output Format

제품 범위 변경은 다음 형식으로 문서에 반영한다.

```text
Feature ID:
Feature name:
User value:
In scope:
Out of scope:
Required result:
Safety constraints:
Open decisions:
```

## Review Checklist

- [ ] 기능이 특정 앱에 과하게 묶이지 않는다.
- [ ] 외부 액션 실행 여부가 명확하다.
- [ ] UI 연결 지점이 있다.
- [ ] 모호한 입력 처리 방식이 있다.
- [ ] 계약과 시나리오에 반영할 내용이 분리되어 있다.
