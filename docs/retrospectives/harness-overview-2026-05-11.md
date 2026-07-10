# Claude Code 하네스 발표자료 — experiments/02-ai-agent-sdk

작성일: 2026-05-11
발표자: dw31200@gmail.com (Android 앱 개발자)
실험 디렉토리: `experiments/02-ai-agent-sdk/`

> **[2026-07-10 업데이트]** 본 문서에 기술된 token-limit-guardian(토큰 한도 자동 체크포인트 + Task Scheduler 자동 재개) 레이어는 **제거되었다**. hook이 전역 `~/.claude/settings.json`에 등록되어 다른 프로젝트 세션에도 실행되는 부작용이 있었기 때문. 이 문서는 당시 설계 기록으로만 보존한다. SDD 코어 레이어(4명 에이전트 팀)는 계속 유효하다.

---

## 1. 한 줄 요약

> **SDD(Spec-Driven Development) 워크플로우를 4명 AI 에이전트 팀으로 운영하고,
> 5시간 토큰 한도가 작업을 끊지 않도록 자동 체크포인트와 자동 재개를 얹은 Claude Code 하네스.**

---

## 2. 왜 만들었나 — 배경

### 2-1. SDD를 손으로 굴리기 어렵다
- "사양 → 검토 → 합의 → 구현 → QA"의 흐름이 길고 사람 한 명이 모든 역할을 하면 누락이 생긴다.
- F-XXX, M-XXX, ERR-XXX 같은 사양 ID를 코드와 1:1 매핑하려면 일관된 추적이 필요한데, 한 사람이 다 보기 어렵다.

### 2-2. Claude Code의 5시간 한도가 SDD 라운드를 끊는다
- 한 SDD 라운드(F-001 ~ F-007)는 한 번에 수만 토큰을 소모.
- 한도 도달 → 작업 중단 → 컨텍스트 손실 → 다음 세션에서 어디서부터 이어야 할지 모름.
- "한도 임박 자동 감지 + 안전 체크포인트 + 자동 재개"가 필요.

---

## 3. 하네스의 두 레이어

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 1: SDD 코어 — 4명 에이전트 팀                        │
│  ─────────────────────────────────────────────────────────  │
│  spec-architect ←→ spec-reviewer  (사양 합의)              │
│         ↓                                                   │
│  android-implementer ←→ sdk-qa-validator  (구현 ↔ 검증)    │
│                                                             │
│  오케스트레이터: sdk-development-orchestrator               │
└─────────────────────────────────────────────────────────────┘
                          ⇧
┌─────────────────────────────────────────────────────────────┐
│  Layer 2: token-limit-guardian — 보호 레이어                │
│  ─────────────────────────────────────────────────────────  │
│  · UserPromptSubmit hook → 매 메시지마다 한도 체크           │
│  · PostToolUse hook → 라운드 종료 시 PROGRESS.md 자동 갱신   │
│  · 한도 임박 → Task Scheduler에 자동 재개 등록 → 안전 종료   │
│  · 자동 재개 시 보수적 모드 (next_round 명시값만 진행)        │
└─────────────────────────────────────────────────────────────┘
```

핵심: 두 레이어가 **독립적**이다. SDD 코어는 한도 관리를 모르고, 한도 관리는 SDD 내부를 모름. 접점은 `_workspace/PROGRESS.md` 한 파일.

---

## 4. SDD 코어 — 4명 에이전트 + 5개 스킬

| 에이전트 | 사용 스킬 | 책임 |
|---|---|---|
| spec-architect | sdk-spec-author | overview/features/api/data-model/error-handling/provider-spec 6종 작성 |
| spec-reviewer | sdk-spec-reviewer | 사양 비판적 검토 (누락·모순·모호) |
| android-implementer | android-sdk-implementer | Kotlin SDK 코드 작성 + 단위 테스트 |
| sdk-qa-validator | sdk-qa-validator | 사양 ID ↔ 코드 ↔ 테스트의 토큰 단위 교차 비교 |

**오케스트레이터**: `sdk-development-orchestrator` 스킬이 Phase 0 (컨텍스트 확인) → Phase 1 (사양 작성) → Phase 2 (검토·합의) → Phase 3 (구현·점진 QA) → Phase 4 (통합 검증) → Phase 5 (피드백) 흐름을 조율.

**산출물 컨벤션** (`_workspace/`):
- `spec_draft_N.md` / `spec_review_N.md` (사양 라운드)
- `impl_summary_N.md` (구현 라운드)
- `qa_report_N.md` (QA 라운드)

---

## 5. SDD 코어로 만든 것 (현재까지)

| 라운드 | 기능 | 결과 |
|---|---|---|
| T11 | F-001 텍스트 단발 질의 | 통과 |
| T12 | F-002 멀티모달 이미지 | 통과 (Minor 5건) |
| T13 | F-003 스트리밍 + 사양 보강 | 통과 |
| T15 | F-006 Hilt 모듈 | 통과 |
| T17 | F-004 Session 컨텍스트 | 통과 |
| T18 | F-007 영속화 | 한도 도달 → 본 하네스 발동 → 다음 세션 재개 예정 |

5개 F-XXX 모두 Blocker 0, Major 0으로 통과. 단위 테스트 누적 200+개.

---

## 6. token-limit-guardian — 보호 레이어 디테일

### 6-1. 구성 (스킬 1 + 스크립트 7 + Hook 2)

```
.claude/skills/token-limit-guardian/
├── SKILL.md                 ← 트리거: "한도 체크", "체크포인트", "안전 종료"
├── references/scripts-reference.md
└── scripts/
    ├── check_token_usage.ps1        ← transcript JSONL 파싱 → 5h 누적 토큰
    ├── update_progress.ps1          ← PROGRESS.md 자동 관리 블록 + state.json
    ├── register_resume_task.ps1     ← Windows Task Scheduler에 1회성 등록
    ├── auto_resume.ps1              ← Task Scheduler가 호출하는 진입점
    ├── hook_user_prompt_submit.ps1  ← UserPromptSubmit hook 본체
    └── hook_post_tool_use.ps1       ← PostToolUse hook 본체
```

### 6-2. 동작 원리 — 토큰 추정

Claude는 자기 토큰 잔여량을 직접 알 수 없음 → **transcript JSONL을 파싱하여 추정**.

- 위치: `~/.claude/projects/<encoded-path>/<sessionId>.jsonl`
- 합산 공식: `input_tokens + output_tokens + cache_creation_input_tokens`
- `cache_read_input_tokens`는 별도 보고 (재사용분이라 한도 측정에서 제외)
- 5시간 윈도우: `[now - 5h, now]` 범위의 메시지만 합산
- 환경변수로 조정: `CLAUDE_LIMIT_TOTAL_TOKENS` (기본 200K), `CLAUDE_LIMIT_THRESHOLD` (기본 0.8)

---

## 7. 동작 흐름 — 3가지 시나리오

### 시나리오 A: 정상 SDD 라운드 (한도 여유)

```
사용자: "F-007 구현해줘"
  └─ [UserPromptSubmit hook] check_token_usage.ps1 → breach=false → 조용히 통과
  └─ Claude: sdk-development-orchestrator 스킬 호출
        └─ Phase 0-A: 토큰 게이팅 통과
        └─ Phase 0-B: 컨텍스트 점검
        └─ Phase 3 진입 → android-implementer → impl_summary_9.md 작성
              └─ [PostToolUse hook] update_progress.ps1 자동 호출
              └─ PROGRESS.md / PROGRESS.state.json 자동 갱신
        └─ sdk-qa-validator → qa_report_8.md 작성
              └─ 또 다시 PostToolUse hook → PROGRESS.md 갱신
```

### 시나리오 B: 라운드 중 한도 임박 (80% 도달)

```
사용자: "다음 라운드 진행"
  └─ [UserPromptSubmit hook] check_token_usage.ps1 → breach=true
  └─ stdout으로 systemMessage 출력 (Claude에게 자동 보임):
       "[token-limit-guardian] Token limit imminent: 82% used.
        Safe checkpoint recommended..."
  └─ Claude: token-limit-guardian 스킬 호출
        └─ 사용자에게 두 옵션 제시:
              A. 즉시 안전 종료 (권장)
              B. 사용자 책임 하에 계속
        └─ A 선택 시:
              ├─ update_progress.ps1 → PROGRESS.md 갱신
              ├─ git auto-commit (사용자 확인 후)
              └─ register_resume_task.ps1
                    └─ schtasks /Create /SC ONCE /ST <리셋 + 5분>
                    └─ task 이름: ClaudeCode-AutoResume-02-ai-agent-sdk
```

### 시나리오 C: 한도 리셋 후 자동 재개

```
[5시간 후]
Windows Task Scheduler 트리거
  └─ auto_resume.ps1 실행
        └─ PROGRESS.state.json 읽기
        └─ last_auto_resume_at 갱신
        └─ next_round 확인:
              · 명시 (예: T18 + prompt): Claude CLI 실행, 그 prompt 전달
              · null (보수적 기본): "자동 재개됐습니다. 다음 라운드를 알려주세요" 만 보고
        └─ Claude CLI를 새 콘솔로 시작
```

---

## 8. Hook 등록 (settings.json 발췌)

```json
{
  "hooks": {
    "UserPromptSubmit": [{
      "matcher": "",
      "hooks": [{
        "type": "command",
        "shell": "powershell",
        "command": "powershell -NoProfile -ExecutionPolicy Bypass -File <hook_user_prompt_submit.ps1>",
        "timeout": 30
      }]
    }],
    "PostToolUse": [{
      "matcher": "Write|Edit",
      "hooks": [{
        "type": "command",
        "shell": "powershell",
        "command": "powershell -NoProfile -ExecutionPolicy Bypass -File <hook_post_tool_use.ps1>",
        "async": true
      }]
    }]
  }
}
```

- UserPromptSubmit: 매 사용자 메시지마다 동기 실행 (30초 timeout)
- PostToolUse: 비동기 실행 (`async: true`)으로 작업 흐름 차단 안 함

---

## 9. 핵심 설계 결정 — 왜 이렇게 만들었나

### 결정 1: 자동 재개를 OS 레벨 Task Scheduler에 위임
**대안**: Claude Code 내부 `CronCreate` / `schedule` 스킬, PowerShell 데몬
**선택**: Windows Task Scheduler
**이유**: Claude Code가 안 켜져 있어도 동작. OS 신뢰성 가장 높음. Windows 환경 정합.

### 결정 2: 보수적 자동 재개 (next_round 명시값만 진행)
**대안**: "다음 미완 라운드 자동 진행"
**선택**: `next_round=null` 기본값일 때 status 보고만
**이유**: 자동 재개 시 사용자 부재 → 의도와 다른 라운드 진행 시 토큰 낭비 위험. 비용보다 안전 우선.

### 결정 3: 시간 기반 추정 (정확한 한도 미공개)
**대안**: 정확한 한도 API 사용
**선택**: transcript JSONL 파싱 + 환경변수 조정
**이유**: Anthropic이 정확한 한도 미공개. 사용자가 자기 플랜에 맞게 환경변수로 보정.

### 결정 4: 두 레이어 분리
**대안**: SDD 오케스트레이터 내부에 한도 관리 통합
**선택**: 별도 스킬 + Hook으로 분리
**이유**: 한도 관리는 다른 실험에서도 재사용 가능. SDD 오케스트레이터 책임 비대화 방지.

---

## 10. 한계 + 추정

| 한계 | 영향 | 완화 |
|---|---|---|
| 토큰 한도 추정치는 실제 한도와 다를 수 있음 | 임계값 도달 시점 부정확 | 환경변수 `CLAUDE_LIMIT_TOTAL_TOKENS` 조정 |
| Claude는 자신의 한도 인지 못함 → hook이 외부 신호 | hook 실패 시 보호 안 됨 | hook은 fail-open (사용자 차단 안 함), 매 메시지마다 재시도 |
| Task Scheduler 등록은 사용자 권한 필요 | 권한 부족 시 등록 실패 | 명시적 사용자 확인 후 등록 |
| 자동 재개 시 Claude CLI를 새 콘솔로 열기만 함 | 사용자가 콘솔 못 보면 무의미 | 자동 재개는 보수적 모드 — 사용자 입력 대기 |
| Windows 한정 | 다른 OS 미지원 | macOS/Linux는 cron으로 대체 가능 (별도 구현) |

---

## 11. 산출물 총정리

### 파일 산출
- 에이전트 정의 4개 (`.claude/agents/`)
- 스킬 6개 (`.claude/skills/`)
- hook 본체 + 유틸 스크립트 7개 (`.claude/skills/token-limit-guardian/scripts/`)
- `.claude/settings.json` (hook 2개 등록)
- 변경 이력 + 트리거 규칙 (`CLAUDE.md`)
- 진행 상태 자동 관리 (`_workspace/PROGRESS.md` + `PROGRESS.state.json`)

### 누적 SDD 산출 (sdk/)
- Kotlin SDK 본체 (model / client / provider / session / internal / di)
- 단위 테스트 약 200개 (외부 네트워크 호출 0회)
- F-001 ~ F-006 (F-007 다음 세션)

---

## 12. 다음 진화

1. **F-007 영속화 라운드** — SessionStore + DataStore + schemaVersion 강제
2. **누적 Minor 12건 사양 보강** — spec-architect 단독 라운드
3. **token-limit-guardian 일반화** — 다른 experiments에서도 재사용 가능하도록 user-level 스킬로 승격 검토
4. **자동 재개 신뢰도 향상** — `next_round` 자동 채움 옵션 (사용자 명시 동의 시)
5. **macOS/Linux 지원** — Windows Task Scheduler 대신 cron + launchd

---

## 13. Q&A 대비 — 자주 받을 질문

**Q1. Claude API의 한도를 직접 보면 되지 않나?**
A. Claude Code의 5시간 한도는 API 응답에 토큰 단위로 노출되지 않습니다. 메시지 횟수·플랜·계정 상태 등 비공개 조합. 따라서 transcript 누적량으로 추정만 가능.

**Q2. hook이 매 메시지마다 실행되면 비싸지 않나?**
A. `check_token_usage.ps1`은 transcript JSONL을 1회 스트림 파싱. 대용량(수MB)에서도 1초 이내. PostToolUse hook은 `async: true`라 작업 흐름 차단 없음.

**Q3. 보수적 모드가 너무 답답하지 않나?**
A. 자동 재개 비용이 큽니다(한 라운드 = 수만 토큰). 잘못 시작하면 한도 다시 임박. 명시 진행 권장.

**Q4. 다른 프로젝트에 재사용 가능한가?**
A. `token-limit-guardian`만 떼서 `~/.claude/skills/`로 옮기면 사용자 레벨 스킬로 동작. PROGRESS.md 경로만 환경별로 조정.

**Q5. Hook이 실패하면 작업이 멈추나?**
A. 멈추지 않습니다. 모든 hook 스크립트는 fail-open으로 설계 (try/catch 흡수, exit 0). 한도 감지를 못 해도 작업은 진행.

---

## 14. 데모 시나리오 (발표 시 시연 흐름)

1. `02-ai-agent-sdk/` 디렉토리 진입
2. `powershell -File check_token_usage.ps1` 실행 → JSON 출력 보여줌 (현재 사용량)
3. `update_progress.ps1 -RoundLabel "demo" -RoundDescription "발표용 시연"` 실행 → PROGRESS.md 변화 보여줌
4. `cat _workspace/PROGRESS.md` → 자동 관리 블록 head 확인
5. (선택) `register_resume_task.ps1 -ResetAtUtc "<10분 후>"` → schtasks 등록 확인
6. (선택) `schtasks /Query /TN ClaudeCode-AutoResume-02-ai-agent-sdk` → 등록 결과 확인
7. `schtasks /Delete /TN ClaudeCode-AutoResume-02-ai-agent-sdk /F` → 시연 후 정리

---

## 끝

핵심 메시지:

> **"SDD 라운드를 4명 AI 팀이 자체 조율로 굴리고, 토큰 한도가 끊으면 Task Scheduler가 자동 재개한다.
> 두 레이어가 PROGRESS.md 한 파일로 연결되어 — 작업 흐름이 절대 휘발되지 않는다."**
