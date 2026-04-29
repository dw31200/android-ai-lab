---
name: sdk-development-orchestrator
description: SDD 사양 중심 SDK 개발 워크플로우를 오케스트레이션하는 메타 스킬. spec-architect, spec-reviewer, android-implementer, sdk-qa-validator 4명으로 에이전트 팀을 구성하여 사양 작성 → 검토 → 합의 → 구현 → QA 검증의 흐름을 조율한다. "AI SDK 개발", "SDK 만들어줘", "SDD 워크플로우 시작", "experiments/02-ai-agent-sdk", "사양 → 구현", "다시 실행", "재실행", "이전 결과 기반으로 개선", "F-XXX만 다시 구현" 같은 요청 시 반드시 사용할 것. 신규 실험 생성과 후속 부분 재실행을 모두 지원.
---

# sdk-development-orchestrator

## 언제 이 스킬을 쓰는가

- AI 에이전트 인터페이스 SDK 모듈 신규 개발 (예: `experiments/02-ai-agent-sdk/`)
- 기존 SDK 모듈에 새 기능 추가 (F-XXX 추가)
- 사양 변경 → 영향받는 코드 재구현
- QA 결함 발견 → 부분 재실행
- 후속 라운드(spec 검토 → 수정 반영 → 재검증)

## 팀 구성

**실행 모드: 에이전트 팀**

| 에이전트 | 역할 | 사용 스킬 |
|---------|------|----------|
| spec-architect | SDD 사양 작성 | sdk-spec-author |
| spec-reviewer | 사양 검토 | sdk-spec-reviewer |
| android-implementer | Kotlin SDK 구현 | android-sdk-implementer |
| sdk-qa-validator | 사양-구현 정합성 검증 | sdk-qa-validator |

**모델: 모든 에이전트는 `model: "opus"` 사용**

## 워크플로우

### Phase 0: 컨텍스트 확인 (필수 시작 단계)

워크플로우 시작 시 다음을 확인하여 실행 모드를 결정한다.

```
대상 디렉토리: experiments/{exp-id}/

존재 여부 점검:
- experiments/{exp-id}/spec/         → 사양 존재?
- experiments/{exp-id}/sdk/          → 구현 존재?
- experiments/{exp-id}/_workspace/   → 중간 산출물 존재?
```

| 상태 | 실행 모드 |
|------|----------|
| spec 없음, sdk 없음 | **초기 실행** — Phase 1부터 전체 |
| spec 있음, sdk 없음 | **이어쓰기** — Phase 2(검토) 또는 Phase 3(구현)부터 |
| spec 있음, sdk 있음, 사용자가 부분 수정 요청 | **부분 재실행** — 해당 F-XXX 관련 에이전트만 재호출 |
| spec/sdk 있음, 사용자가 새 입력 제공 | **새 라운드** — `_workspace/`를 `_workspace_prev/`로 백업 후 초기 실행 |

### Phase 1: 사양 초안 작성 (spec-architect 단독)

**목표**: 6종 사양 문서 초안 생성.

**진행**:
1. `experiments/{exp-id}/spec/` 디렉토리 생성
2. `experiments/01-first-app/spec/` 템플릿 참고 안내
3. spec-architect에게 작업 할당:
   - 사용자 요구사항 + 대상 디렉토리 전달
   - 산출물: overview/features/api/data-model/error-handling/provider-spec
4. spec-architect가 작성 완료 후 spec-reviewer에게 검토 요청 메시지 발신

**완료 조건**: 6개 spec 파일이 모두 생성되고, spec-architect가 spec-reviewer에게 검토 요청 메시지를 보낸 상태.

### Phase 2: 사양 검토 및 합의 (spec-architect ↔ spec-reviewer 토론)

**목표**: Critical 0건, Major 모두 처리된 합의 사양.

**진행**:
1. spec-reviewer가 6종 문서를 검토하고 `_workspace/spec_review_{n}.md` 작성
2. spec-reviewer → spec-architect SendMessage로 검토 의견 전달
3. spec-architect가 의견을 spec 파일에 반영하고 변경 요약 회신
4. spec-reviewer가 변경 사항을 재검토
5. 합의될 때까지 2-4 반복 (최대 3라운드)

**핑퐁 방지**: 같은 항목으로 3회 이상 교환 시 오케스트레이터가 사용자에게 결정 요청.

**완료 조건**: spec-reviewer가 오케스트레이터에게 "Critical 0, Major 모두 처리" 보고. spec/*.md가 최종 확정 상태.

### Phase 3: 구현 (android-implementer 주도, sdk-qa-validator 점진 검증)

**목표**: 모든 F-XXX가 코드로 구현되고 단위 테스트 통과.

**작업 분해**:
- F-XXX 단위로 `TaskCreate`. F-001부터 우선순위 P0 → P1 → P2 순서.
- 각 task의 owner = android-implementer.

**진행**:
1. android-implementer가 F-001 구현 → 단위 테스트 작성 → 통과 확인
2. android-implementer → sdk-qa-validator SendMessage: "F-001 구현 완료, 검증 요청"
3. sdk-qa-validator가 즉시 F-001 검증 (점진적 QA)
4. 결함 발견 시 sdk-qa-validator → android-implementer SendMessage
5. 결함 0건 확인 후 다음 F-XXX로 진행

**병렬화**: 의존성 없는 F-XXX는 동시에 진행 가능. 단, 한 에이전트당 한 시점에 한 F-XXX만 처리.

**완료 조건**: 모든 F-XXX의 QA 보고서 Blocker 0, Major 0건. android-implementer가 오케스트레이터에게 전체 완료 보고.

### Phase 4: 통합 검증 (sdk-qa-validator)

**목표**: F-XXX간 상호작용·NFR 통합 검증.

**진행**:
1. sdk-qa-validator가 전체 통합 QA 보고서 작성
2. NFR 검증: 동시성, 취소, 타임아웃, 큰 입력 거부
3. 미해결 결함이 있으면 Phase 3로 복귀

**완료 조건**: 통합 QA 보고서가 모든 NFR 통과. 사용자에게 최종 보고.

### Phase 5: 사용자 피드백 수집 (Phase 7 진화 트리거)

워크플로우 종료 시 사용자에게 묻는다:
- 결과에서 개선할 부분이 있나요?
- 사양이나 구현에 추가 요구사항이 있나요?

피드백 유형별 라우팅:
- 사양 문제 → spec-architect에게 부분 재실행
- 구현 문제 → android-implementer에게 부분 재실행
- 워크플로우 문제 → 본 오케스트레이터 수정

## 데이터 전달 프로토콜

| 전략 | 사용처 |
|------|-------|
| **메시지 기반** (`SendMessage`) | 에이전트 간 실시간 피드백 (작성↔검토, 구현↔QA) |
| **태스크 기반** (`TaskCreate`/`TaskUpdate`) | F-XXX 단위 작업 분배·진행 추적 |
| **파일 기반** | 최종 사양은 `spec/`, 코드는 `sdk/`, 중간 산출물은 `_workspace/` |

### `_workspace/` 파일 컨벤션

```
experiments/{exp-id}/_workspace/
├── spec_draft_{n}.md        ← spec-architect 중간 산출물
├── spec_review_{n}.md       ← spec-reviewer 검토 결과
├── impl_summary_{n}.md      ← android-implementer 진행 요약
└── qa_report_{n}.md         ← sdk-qa-validator 검증 보고서
```

`{n}`은 라운드 번호 (1부터). 이전 라운드는 보존하여 감사 추적 가능.

## 에러 핸들링

| 상황 | 대응 |
|------|------|
| 에이전트 작업 실패 (1회) | 1회 재시도 |
| 재시도도 실패 | 해당 결과 없이 진행, 최종 보고서에 누락 명시 |
| 사양↔검토 핑퐁 3회+ | 사용자에게 결정 요청, 미해결 결정 표 첨부 |
| 사양 ↔ 구현 모두 잘못됨 | spec-architect → 사양 수정 → implementer 재실행 |
| 외부 라이브러리 결정 미정 | 오케스트레이터가 사용자에게 직접 질문 |

## 팀 크기

본 하네스는 4명. 작업 규모가 커지면 다음을 고려:
- 모듈이 5개 이상 분할 → spec-architect를 모듈별로 2명으로 확장
- 구현 작업이 20개 F-XXX 이상 → android-implementer 2명으로 확장 (도메인별)

## 후속 작업 트리거

다음 표현은 본 오케스트레이터를 트리거한다 (description 키워드와 일치):
- "다시 실행", "재실행", "업데이트", "수정", "보완"
- "F-XXX만 다시 구현"
- "이전 결과 기반으로 개선"

## 테스트 시나리오

### 정상 흐름
1. 사용자: "experiments/02-ai-agent-sdk/에 LLM 호출 SDK 만들어줘"
2. 오케스트레이터: Phase 0 → 신규 실험 → Phase 1 시작
3. spec-architect: 6종 spec 작성
4. spec-reviewer: 검토 → Major 3건 → spec-architect 반영 → 합의
5. android-implementer: F-001~F-004 순차 구현, 각 완료 시 sdk-qa-validator 검증
6. sdk-qa-validator: 통합 검증 통과
7. 사용자에게 최종 보고

### 에러 흐름
1. spec-architect ↔ spec-reviewer 같은 항목 3회 핑퐁
2. 오케스트레이터: 양측 입장 정리 후 사용자에게 결정 요청
3. 사용자 결정 반영 후 Phase 2 종결

## 자체 체크리스트

- [ ] Phase 0에서 컨텍스트(spec/sdk/_workspace 존재) 확인 완료
- [ ] 모든 에이전트 호출에 `model: "opus"` 명시
- [ ] `_workspace/` 디렉토리 생성됨
- [ ] 각 Phase 종결 조건 만족 확인 후 다음 Phase 진행
- [ ] 최종 단계에서 사용자 피드백 수집 (Phase 7 진화)
