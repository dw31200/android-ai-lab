# Hans AI Assistant Harness

Hans 하네스는 **범용 AI assistant SDK**의 기능 계약, 테스트 시나리오, 스킬 운영 방식, 품질 기준을 Markdown으로 관리하는 작업 공간이다.

## 목표

- 앱 종류에 묶이지 않는 AI assistant 기능을 검증한다.
- 구현 전에도 request/response/error 계약을 합의할 수 있게 한다.
- 구현 후에는 같은 Markdown 시나리오로 regression을 반복할 수 있게 한다.
- Android UI가 결과를 후처리 없이 연결할 수 있는 구조화 출력을 기준으로 삼는다.

## P0 기능 범위

| ID | 기능 | 목적 | 핵심 출력 |
|----|------|------|----------|
| H-001 | Chat response | 일반 질문과 작업 요청에 안전하고 명확하게 응답 | `answer`, `tone`, `safety` |
| H-002 | Summary | 긴 텍스트를 짧은 요약과 핵심 항목으로 변환 | `summary`, `keyPoints`, `evidence` |
| H-003 | Action items | 회의록/메모에서 실행할 일을 추출 | `items`, `owner`, `dueDate`, `needsClarification` |
| H-004 | Reminder candidates | 자연어 일정 요청에서 리마인더 후보 생성 | `candidates`, `dateTime`, `confidence`, `warnings` |
| H-005 | Context interpretation | 메시지와 OCR/이미지 설명을 함께 해석 | `summary`, `signals`, `conflicts`, `evidence` |

## 공통 계약 원칙

모든 기능은 `contract.md`에 정의된 request, response, error 계약을 따른다. 단, 이 하네스는 별도 JSON fixture 파일을 두지 않는다. 샘플 입력, 기대 출력, 검증 기준은 `scenarios.md`에 Markdown으로 기록한다.

## 디렉토리 구조

```text
experiments/Hans/harness/
├── README.md
├── contract.md
├── runbook.md
├── scenarios.md
├── team.md
├── skills/
│   ├── README.md
│   ├── hans-assistant-orchestrator.md
│   ├── assistant-product-architect.md
│   ├── assistant-contract-designer.md
│   ├── assistant-scenario-curator.md
│   └── assistant-qa-validator.md
└── _workspace/              # 실행 중간 산출물. 필요 시 생성, git 추적 선택.
```

## 하네스 사용 방식

1. `scenarios.md`에서 검증할 기능 ID를 고른다.
2. `contract.md`에서 request/expected shape를 확인한다.
3. `skills/`에서 해당 작업에 필요한 역할과 절차를 확인한다.
4. `scenarios.md`의 샘플 입력을 SDK 또는 모델 호출 계층에 전달한다.
5. 각 시나리오의 검증 기준을 기준으로 pass/fail을 기록한다.
6. 실패한 항목은 계약 문제, 프롬프트 문제, 구현 문제, 시나리오 문제로 분류한다.

## 품질 기준

- 응답은 UI 연결 가능한 구조를 가져야 한다.
- 모호한 날짜/시간은 임의 확정하지 않는다.
- 근거가 필요한 출력에는 `evidence` 또는 `sourceRefs`를 포함한다.
- 빈 입력, 충돌하는 컨텍스트, 개인정보성 입력은 안전하게 처리한다.
- 외부 액션 실행은 후보 제안까지만 허용하고 사용자 확인을 요구한다.
