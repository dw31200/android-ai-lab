# Hans Harness Skills

이 디렉토리는 Hans AI assistant SDK 하네스를 운영할 때 사용할 스킬 문서 모음이다. Codex 또는 Claude의 실제 `.claude/skills` 디렉토리에 설치하는 파일이 아니라, `experiments/Hans` 안에서만 추적되는 프로젝트 로컬 설계 문서다.

## Skill Index

| Skill | Purpose | Main Files |
|-------|---------|------------|
| `hans-assistant-orchestrator.md` | 전체 하네스 실행 순서와 역할 조율 | 전체 |
| `assistant-product-architect.md` | 기능 범위, UX 기준, 위험한 자동 액션 제한 | `README.md`, `team.md` |
| `assistant-contract-designer.md` | request, response, error 계약 설계 | `contract.md` |
| `assistant-scenario-curator.md` | Markdown 시나리오와 검증 기준 작성 | `scenarios.md` |
| `assistant-qa-validator.md` | 계약, 시나리오, 스킬 문서 정합성 검증 | `runbook.md`, QA report |

## Operating Rule

- 이 하네스는 JSON fixture 파일을 만들지 않는다.
- 샘플 입력은 `scenarios.md`에 Markdown 표로 작성한다.
- 계약은 `contract.md`의 필드 표와 규칙으로 관리한다.
- 스킬 문서는 작업자가 어떤 기준으로 판단해야 하는지 설명하는 운영 문서다.

## When To Update Skills

- 새 기능 ID가 추가될 때.
- request/response/error 계약이 바뀔 때.
- 날짜, 개인정보, 외부 액션 같은 안전 기준이 바뀔 때.
- QA에서 반복적으로 같은 결함이 발견될 때.
