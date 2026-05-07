# Skill: assistant-qa-validator

## Purpose

Hans 하네스 문서가 실제 검증 기준으로 작동하는지 확인한다. 단순 맞춤법 검사가 아니라 계약, 시나리오, 스킬 문서 간 불일치를 찾는 역할이다.

## Responsibilities

- `README.md`의 기능 범위와 `scenarios.md`의 시나리오 ID를 비교한다.
- `contract.md`의 result shape와 각 시나리오의 기대 결과를 비교한다.
- `skills/` 문서가 현재 운영 방식과 맞는지 확인한다.
- 날짜, 개인정보, 외부 액션, OCR 근거 처리 기준을 검증한다.

## Validation Process

### 1. File Structure

확인할 항목:

- `README.md`, `contract.md`, `runbook.md`, `scenarios.md`, `team.md`가 존재한다.
- `skills/` 디렉토리에 역할별 스킬 문서가 존재한다.
- JSON fixture 파일이 존재하지 않는다.

### 2. Scenario Coverage

확인할 항목:

- H-001부터 H-005까지 모두 있다.
- 각 시나리오에 목적, 샘플 입력, 기대 결과, 검증 기준, 경계 케이스가 있다.
- 샘플 입력은 민감정보 없이 재현 가능하다.

### 3. Contract Alignment

확인할 항목:

- 시나리오가 요구하는 결과 필드가 `contract.md`의 result shape에 있다.
- error code가 경계 케이스를 처리할 수 있다.
- `status`와 `warnings`의 책임이 분리되어 있다.

### 4. Safety Review

확인할 항목:

- 외부 액션은 완료 상태로 표현하지 않는다.
- 상대 날짜는 기준 시각과 timezone 없이는 확정하지 않는다.
- OCR 또는 이미지 설명은 근거와 한계를 함께 표현한다.
- 개인정보를 fixture나 시나리오에 넣지 않는다.

## Severity

| Severity | Definition |
|----------|------------|
| Blocker | 하네스를 실행할 기준이 없거나 JSON fixture가 다시 추가됨 |
| Major | 계약과 시나리오가 충돌하거나 안전 기준이 빠짐 |
| Minor | 표현 개선, 추가 케이스, 문서 가독성 문제 |

## QA Report Template

```text
# Hans Harness QA Report

## Summary
Blocker:
Major:
Minor:

## Findings
Severity:
File:
Issue:
Required Fix:

## Regression Checklist
- H-001 Chat response
- H-002 Summary
- H-003 Action items
- H-004 Reminder candidates
- H-005 Context interpretation
```

## Review Checklist

- [ ] JSON fixture 파일이 없다.
- [ ] 모든 시나리오가 Markdown으로 작성되어 있다.
- [ ] 계약과 시나리오 결과 필드가 맞다.
- [ ] 날짜와 timezone 기준이 명확하다.
- [ ] 외부 액션 안전 기준이 유지된다.
