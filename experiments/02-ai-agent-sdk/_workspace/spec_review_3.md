# Spec Review Report — 02-ai-agent-sdk v0.1 (Round 3)

검토자: spec-reviewer
검토 일시: 2026-05-07
대상 파일: overview.md / features.md / api.md / data-model.md / error-handling.md / provider-spec.md
참조 문서: `_workspace/spec_review_1.md`, `_workspace/spec_review_2.md`, `_workspace/spec_draft_2.md`, `_workspace/spec_draft_3.md`

---

## 1. 요약

| Severity | 건수 | 비고 |
|----------|------|------|
| Critical | 0 | |
| Major    | 0 | R-017~R-020 모두 반영 통과 |
| Minor    | 1 | R-025 (라운드 3 신규, A-007/A-008 close 시맨틱 부기 누락) |

라운드 1 R-001~R-016, 라운드 2 R-017~R-024 **8건 모두 반영 통과**. D-001~D-005 종결 유지. 신규 D-XXX 0건. 미해결 결정 0건.

라운드 3에서 **새로 발견된 결함은 Minor 1건뿐**이며, A-009 표가 모든 케이스를 망라하므로 사양 차원의 모순/누락은 없음. R-025는 일관성/문서 품질 문제로 **합의 종결을 막지 않는다**.

→ **종결 조건 충족 (Critical 0 + Major 0). Phase 3 진입 가능**.

---

## 2. R-017 ~ R-024 라운드 2 지적 사항 반영 검증

### Major (R-017 ~ R-020)

| R-ID | 검토 결과 | 반영 위치 | 비고 |
|------|-----------|-----------|------|
| R-017 | **통과** | features.md F-007 저장 흐름 5단계, api.md A-010 동작 | "Session 생성 시 자동 부여된 UUID(M-007.sessionId)"로 통일됨. A-010 시그니처(`save(): Result<String>`, 인자 없음)와 일치. "save() 인자로 전달된 식별자" 표현 제거 확인. P-1/P-2 정책 적용 |
| R-018 | **통과** | data-model.md M-011 "schemaVersion 정책" 섹션, features.md F-007 NFR + E-703, error-handling.md ERR-007 + 매핑표, overview.md Out of Scope | v0.1은 schemaVersion=1만 인정, 그 외 즉시 E-703으로 거부 정책 5개 위치에 일관 명시. E-703 발생 조건 표기에 "schemaVersion 미지원" 추가 확인. P-3 정책 적용 |
| R-019 | **통과** | features.md F-007 "다중 인스턴스 정책" 섹션, data-model.md M-007 "다중 인스턴스 정책" 섹션, overview.md NFR + Out of Scope, api.md A-011 동작 | last-write-wins, SDK 충돌 자동 검출 없음, 호출자 책임 4개 위치에 일관 명시. 다중 프로세스 Out of Scope 2개 위치에 명시. P-4/P-5 정책 적용 |
| R-020 | **통과** | features.md F-008 "close ↔ 진행 중/이후 호출 시맨틱" 섹션, api.md A-009 close 시맨틱 케이스 표, A-002/A-003/A-006/A-010/A-011/A-012 동작 부기, A-004 동기 throw 부기 | 케이스 A(in-flight: CancellationException) / 케이스 B(close 후: Result.failure 또는 Flow Error emit 또는 throw) 분리 명시. A-009 표에 모든 함수 카테고리(suspend, Flow, 동기) 망라. P-6 정책 적용. (단 A-007/A-008은 R-025 참조 — 표에 포함되었으나 각 항목 본문에 부기 누락) |

### Minor (R-021 ~ R-024)

| R-ID | 검토 결과 | 반영 위치 | 비고 |
|------|-----------|-----------|------|
| R-021 | **통과** | features.md F-007 "이미지 영속화 운영 가이드" 섹션, data-model.md M-003 영속화 규칙, M-011 "영속화 시 이미지 처리 가이드" 섹션 | 단일 5MB 이미지 → base64 6.7MB → 1MB 한계 초과 사실 명시. 호출자 권장 처리 3가지 명시. SDK 자동 제외/압축 안 함 명시. P-7 정책 적용 |
| R-022 | **통과** | data-model.md M-011 "schemaVersion 정책" 섹션 | `internal object SessionMigrations`, `from1_to2(json)` 함수 패턴 미리 부기. v0.2부터 실제 추가 명시. R-018과 통합 |
| R-023 | **통과** | provider-spec.md "ImageInput.Url SSRF 방어 정책" 섹션, data-model.md M-011 "ImageInput.Url 영속화 보안 정책" 섹션, M-003 영속화 규칙, features.md F-007 NFR | SDK는 URL 형식 검증만, SSRF 방어는 호출자 책임 4개 위치에 일관 명시. P-8 정책 적용 |
| R-024 | **통과** | overview.md Out of Scope, api.md A-004 "sessionId 부여 정책" 섹션, A-010 동작 | v0.1은 자동 UUID만 지원, 호출자 명시는 v0.2 검토 명시. R-017과 정합. P-1 정책 적용 |

**소결**: 라운드 2 신규 지적 사항 8건 모두 통과. 미반영/부분반영 0건.

---

## 3. 라운드 3 정책(P-1 ~ P-8) 정합성 검증

| 정책 | 정합성 | 검증 위치 (요약) | 비고 |
|------|--------|----------------|------|
| **P-1** sessionId 자동 UUID만 지원 | ✅ 통과 | overview.md Out of Scope (호출자 지정 sessionId), api.md A-004 sessionId 부여 정책 + A-010 동작 + A-011 시그니처(loadSession은 기존 ID만), features.md F-007 저장 5단계 | createSession에 인자 없음, save에 인자 없음, loadSession은 기존 ID만 받음. 4개 위치 시그니처/문구 모두 일치 |
| **P-2** F-007 정상 흐름 sessionId 표현 통일 | ✅ 통과 | features.md F-007 저장 5단계, api.md A-010 동작, M-007 sessionId 코멘트 | "save() 인자로 전달된 식별자" 표현 0건 (grep 확인). "Session 생성 시 자동 부여된 UUID(M-007.sessionId)" 표현 통일 |
| **P-3** schemaVersion v0.1 정책 | ✅ 통과 | data-model.md M-011 schemaVersion 정책 섹션, features.md F-007 E-703 + NFR, error-handling.md ERR-007 + E-703 매핑표, overview.md Out of Scope | v0.1 schemaVersion=1만 인정, 그 외 E-703(IOError) 거부, 자동 마이그레이션 v0.2부터, SessionMigrations 패턴 미리 부기. 6개 위치 일관 명시 |
| **P-4** 다중 Session 인스턴스 last-write-wins | ✅ 통과 | features.md F-007 다중 인스턴스 정책, data-model.md M-007 다중 인스턴스 정책, api.md A-010 동작 + A-011 동작 | last-write-wins 4개 위치 일관 명시. SDK 자동 충돌 검출 없음, 호출자 책임 명시 |
| **P-5** 다중 프로세스 Out of Scope | ✅ 통과 | overview.md Out of Scope + NFR, features.md F-007 다중 인스턴스 정책 마지막 단락, data-model.md M-007 마지막 단락 | "단일 프로세스 사용 가정", "WorkManager 등 별도 프로세스 미지원" 4개 위치 일관 |
| **P-6** close 시맨틱 케이스 A/B | ✅ 통과 (단 R-025 부기) | features.md F-008 "close ↔ 진행 중/이후 호출 시맨틱" 섹션, api.md A-009 close 시맨틱 표, A-002/A-003/A-004/A-006/A-010/A-011/A-012 동작 부기 | 케이스 A(CancellationException)와 B(Result.failure / Flow Error / throw) 분리 명시. A-009 표가 모든 함수 카테고리 (suspend, Flow, 동기) 망라. R-025: A-007/A-008 항목 본문에 close 시맨틱 부기 누락(Minor) — A-009 표에는 createSession/useProvider/Session.history/Session.clear가 모두 동기 throw로 포함되어 있으므로 사양 모순 아님 |
| **P-7** 영속화 시 이미지 처리 (호출자 책임) | ✅ 통과 | features.md F-007 이미지 영속화 운영 가이드, data-model.md M-003 영속화 규칙 + M-011 이미지 처리 가이드, error-handling.md E-704 매핑 | 5MB 이미지 → base64 1MB 초과 → E-704 거부 정책. 호출자 권장 처리 3가지(이미지 제거/텍스트 요약/Url 외부 호스팅) 명시. SDK 자동 가공 안 함 명시(D-004 연장) |
| **P-8** SSRF 방어 호출자 책임 | ✅ 통과 | provider-spec.md "ImageInput.Url SSRF 방어 정책" 섹션, data-model.md M-011 "ImageInput.Url 영속화 보안 정책" + M-003 영속화 규칙, features.md F-007 NFR | SDK는 URL 형식 검증(스킴 등)만, 내부망/loopback/메타데이터 IP 판별 안 함, 도메인 allowlist 검사 안 함, 호출자 책임 4개 위치 일관. v0.2 SDK 차원 allowlist 검토 명시 |

**소결**: P-1~P-8 모두 spec 전반에 일관 반영됨. 정책 간 모순 0건.

---

## 4. 신규 결함 (라운드 3에서 처음 발견)

### Critical / Major
없음.

### Minor

| ID | Severity | 위치 | 문제 | 제안 수정 |
|----|----------|------|------|----------|
| R-025 | Minor | api.md A-007 (Session.history), A-008 (Session.clear) | 두 동기 함수의 동작 섹션에 close 시맨틱 부기가 누락. F-008 케이스 B 정책에 따르면 두 함수도 close 후 호출 시 `AiException.Configuration("client closed")` throw 대상이며, A-009 표에는 "Session.history/Session.clear (동기)"가 포함되어 있다. 그러나 A-007/A-008 자체 항목에는 close 시맨틱 한 줄이 없어 호출자가 해당 항목만 읽을 때 누락됨. A-002/A-003/A-004/A-006/A-010/A-011/A-012는 모두 부기되어 있으므로 일관성 누락 | A-007 동작 섹션에 "close 시맨틱: A-009 표 참조 (close 후 호출 시 `AiException.Configuration("client closed")` throw)" 한 줄 추가. A-008에 동일하게 추가. 사양상 모순은 없으므로 Minor — 다만 호출자 가이드 일관성 차원에서 보강 권장 |

**근거 보강**: F-008 본문 5단계에 "이후 ask/askStream/createSession/save/load/delete 새 호출은…"이라고 명시되어 있지만 `Session.history`/`Session.clear`도 close 후 호출 가능한 메서드이며 A-009 표에는 명시되어 있다. 두 함수가 사실은 메모리 캐시만 건드리는 동기 함수이므로 호출자가 close된 client와 무관한 Session 인스턴스에서도 호출할 수 있는지 모호할 수 있어 부기가 필요하다.

---

## 5. close 시맨틱 케이스 A/B 적용 일관성 정밀 점검

P-6 정책 적용 정밀 점검 결과:

| API | 케이스 A (in-flight) | 케이스 B (close 후 신규) | 부기 위치 | 일관성 |
|-----|--------------------|----------------------|---------|------|
| A-002 ask (suspend) | CancellationException | Result.failure(Configuration) | A-002 + A-009 표 | ✅ |
| A-003 askStream (Flow) | CancellationException | AiStreamEvent.Error emit | A-003 + A-009 표 | ✅ |
| A-004 createSession (동기) | (해당 없음 — 즉시 반환) | throw Configuration | A-004 + A-009 표 | ✅ |
| A-005 useProvider (동기) | (해당 없음) | throw Configuration | A-009 표만 (A-005 본문 누락 — 단 R-025로 묶지 않은 이유: A-005는 동기 즉시 반환이라 in-flight 개념이 없고 useProvider는 close 후 호출이 도메인적으로 드물다. 그러나 일관성 측면에서 보강 가능) | △ (Minor 후보) |
| A-006 Session.send (suspend) | CancellationException | Result.failure(Configuration) | A-006 + A-009 표 | ✅ |
| A-007 Session.history (동기) | (해당 없음) | throw Configuration | A-009 표만 (R-025) | △ |
| A-008 Session.clear (동기) | (해당 없음) | throw Configuration | A-009 표만 (R-025) | △ |
| A-009 close (동기) | (해당 없음 — idempotent) | no-op | A-009 정책 섹션 | ✅ |
| A-010 Session.save (suspend) | CancellationException | Result.failure(Configuration) | A-010 + A-009 표 | ✅ |
| A-011 loadSession (suspend) | CancellationException | Result.failure(Configuration) | A-011 + A-009 표 | ✅ |
| A-012 deleteSession (suspend) | CancellationException | Result.failure(Configuration) | A-012 + A-009 표 | ✅ |

**중요**: A-009 표에 모든 동기 함수("createSession/useProvider/Session.history/Session.clear")가 묶여서 명시되어 있으므로 **사양 차원의 모순은 없다**. 다만 A-007/A-008(/A-005) 본문 부기 누락은 호출자 가이드 일관성 측면에서 R-025 Minor로 분류한다.

**Flow → Error 이벤트 emit 정책의 errorflow contract 일치**:
- A-003 askStream의 close 시맨틱(B)은 "AiStreamEvent.Error emit 후 종료". M-006 AiStreamEvent의 방출 순서 보장(Done 또는 Error 정확히 1회 종결)과 일치. ✅
- F-003 E-301/E-303 처리 방식과도 일치 (Error로 종결).

---

## 6. 라운드 1/2 통과분 Regression 점검

spec_draft_3.md "라운드 1 지적(R-001~R-016)은 이미 통과 상태이므로 본 라운드에서 일절 손대지 않았다 (regression 방지)" 명시. 자체 검토 결과:

| 라운드 | 항목 | regression 검토 | 결과 |
|--------|------|----------------|------|
| 라운드 1 | R-001 D-001~D-005 종결 | overview.md "결정 사항" 표 그대로 유지 | ✅ |
| 라운드 1 | R-002 close API | A-009 추가 + 라운드 3에서 표/케이스 정밀화. 기존 정책 변경 없음 | ✅ |
| 라운드 1 | R-003~R-004 이미지 fetch/decode 실패 | E-206/E-207 그대로 | ✅ |
| 라운드 1 | R-005 빈 응답 검증 | F-001 정상 흐름 4단계 + E-110 그대로 | ✅ |
| 라운드 1 | R-006 컨텍스트 한계 | F-004 알고리즘 섹션 그대로 | ✅ |
| 라운드 1 | R-007 동시성 모델 | F-005/M-007 그대로 | ✅ |
| 라운드 1 | R-008 systemPrompt 정책 | F-004/M-007/M-008 그대로 | ✅ |
| 라운드 1 | R-009 ProviderId enum 단일화 | M-010 그대로 | ✅ |
| 라운드 1 | R-010 Capabilities single source | provider-spec.md 그대로 | ✅ |
| 라운드 1 | R-011 history immutable snapshot | A-007 + M-007 그대로 | ✅ |
| 라운드 1 | R-012 Hilt 사용 예 | F-006 그대로 | ✅ |
| 라운드 1 | R-013 메시지 권장 예시 안내 | error-handling.md 그대로 | ✅ |
| 라운드 1 | R-014 Session-Provider 관계 | F-005 + M-007 그대로 | ✅ |
| 라운드 1 | R-015 Provider 추가 절차 | provider-spec.md 그대로 | ✅ |
| 라운드 1 | R-016 ImageInput.Bytes equals/hashCode | M-003 그대로 | ✅ |

**regression 0건 확인**.

---

## 7. 합의 종결 조건 평가

| 조건 | 충족 여부 | 비고 |
|------|-----------|------|
| Critical 0건 | ✅ 충족 | 라운드 3 신규 0건 |
| Major 0건 | ✅ 충족 | 라운드 2의 R-017~R-020 모두 통과, 라운드 3 신규 0건 |
| Minor 0 또는 종결 가능 수준 | ✅ **종결 가능 수준** | R-025(Minor 1건)은 일관성/문서 품질 사항으로 사양 모순 아님. A-009 표가 정책을 망라하므로 종결 가능 |
| 미해결 결정(D-XXX) 0건 | ✅ 충족 | overview.md "미해결 결정: 0건" 유지, 신규 D-XXX 0건 (grep 확인: D-006~D-099 매칭 없음) |
| 모순 0건 | ✅ 충족 | A-010 시그니처 ↔ F-007(R-017), F-008 ↔ A-009 표(R-020), schemaVersion 정책(R-018), 다중 인스턴스(R-019) 모두 통일 |
| 누락 0건 (사양 차원) | ✅ 충족 | R-025는 본문 부기 누락이며 A-009 표가 누락 본문을 보완함 |
| 모든 라운드 1/2 지적 반영 | ✅ 충족 | R-001~R-024 24건 모두 통과 |
| ID 충돌 0건 | ✅ 충족 | 라운드 3 신규 ID 0건이므로 충돌 가능성 없음 |
| 정책 P-1~P-8 일관성 | ✅ 충족 | 8개 정책 모두 spec 전반에 모순 없이 반영 |

→ **종결 조건 충족**. **Phase 3(구현) 진입 가능**.

---

## 8. 권장 다음 작업

### 8.1 Phase 3 진입 권고

- 본 검토 결과를 오케스트레이터에게 합의 완료 보고.
- `android-implementer` 에이전트가 6종 spec 파일을 입력으로 SDK 구현 시작 (`sdk/` 디렉토리).
- 우선 구현 대상: F-000~F-006, F-008 (P0/P1) → F-007 (P1) → P-CLAUDE Provider.

### 8.2 R-025 처리 권고 (선택)

R-025는 종결 조건을 막지 않으므로 다음 중 하나로 처리:

- (a) Phase 3 진입과 병행하여 spec-architect가 A-007/A-008(/A-005) 동작 섹션에 close 시맨틱 한 줄 추가 (10분 작업).
- (b) Phase 3에서 implementer가 코드 주석에 명시하고 사양은 그대로 둠 (A-009 표가 정책의 single source of truth).

권장: (a). 이후 라운드 추가 검토는 불필요(이미 정책이 명확하므로 추가 핑퐁 위험 없음).

### 8.3 사용자 결정 필요 여부

**없음**. R-017~R-024 8건 모두 spec-architect가 합리적 기본값으로 결정 완료(P-1~P-8). 라운드 2 권장에 있던 "다중 프로세스 지원 여부 사용자 확인"은 P-5로 Out of Scope 명시. 사용자가 v0.1에서 다중 프로세스 지원이 필수라고 별도 판단하면 그 시점에 라운드 4 트리거.

---

## 9. 검토 통계 (체크리스트 — 라운드 3 시점)

### overview.md
- [x] 목적 한 문장 명료
- [x] In/Out Scope 둘 다 채워짐 (호출자 지정 sessionId / 다중 프로세스 / 자동 마이그레이션 라운드 3 신규 추가)
- [x] Out of Scope 이유 있음 (D-XXX, R-XXX 결정 ID 명시)
- [x] 기술 스택 명시
- [x] 의존성 정책 있음
- [x] 미해결 결정 0건 (D-001~D-005 종결, 신규 0건)
- [x] 단일 프로세스 NFR 명시 (P-5)

### features.md
- [x] 모든 F-XXX (F-000~F-008) 사전 조건 있음
- [x] 정상 흐름 + 예외 흐름 둘 다 있음
- [x] 외부 호출 예외 흐름 충분
- [x] NFR 측정 가능 (p95)
- [x] 코루틴 취소 동작 명시 (E-106, E-302, E-706)
- [x] F-007 sessionId 통일 (R-017)
- [x] F-007 다중 인스턴스 정책 (R-019)
- [x] F-007 이미지 영속화 가이드 (R-021)
- [x] F-007 schemaVersion NFR + URL SSRF NFR (R-018, R-023)
- [x] F-008 close 케이스 A/B 시맨틱 (R-020)

### api.md
- [x] 모든 public 함수 시그니처 토큰 단위 (A-001~A-012)
- [x] suspend / Flow 명확
- [x] 콜백 없음
- [x] F-XXX 참조
- [x] 사용 예 있음
- [x] A-009 close 시맨틱 표 (R-020)
- [x] A-002/A-003/A-004/A-006/A-010/A-011/A-012 close 시맨틱 부기
- [ ] **R-025 A-007/A-008(/A-005) close 시맨틱 부기 누락 (Minor — 종결 가능)**
- [x] A-004 sessionId 부여 정책 (R-017/R-024)
- [x] A-010 sessionId 자동 UUID + last-write-wins 부기
- [x] A-011 schemaVersion + 다중 인스턴스 부기

### data-model.md
- [x] 필드 타입/필수 명시
- [x] sealed class 정의 (M-003 ImageInput, M-005 AiException, M-006 AiStreamEvent)
- [x] 직렬화 규칙 (Provider 전송용 + 영속화용 분리)
- [x] 크기 제한 (5MB / 20MB / 10장 + 영속화 1MB)
- [x] M-003 equals/hashCode 명시
- [x] Role.SYSTEM 정책 명시
- [x] M-010 enum 단일화
- [x] M-011 SessionEntity + schemaVersion 정책 (R-018/R-022)
- [x] M-011 ImageInput.Url 보안 정책 (R-023)
- [x] M-011 이미지 처리 가이드 (R-021)
- [x] M-007 다중 인스턴스 정책 (R-019)

### error-handling.md
- [x] ERR-XXX 사용자 메시지 (영문 권장 예시 안내)
- [x] ERR-XXX 복구 방법
- [x] AiException 매핑 표 (ERR-001~ERR-007)
- [x] E-XXX ↔ ERR-XXX 매핑 (E-109/E-110/E-206/E-207/E-403/E-701~E-706/E-801 + schemaVersion 추가)
- [x] 로깅 정책 (debug/release 분리)
- [x] 영속화 에러 처리 호출 예
- [x] ERR-007 schemaVersion 미지원 케이스 추가 (R-018)

### provider-spec.md
- [x] Provider 인터페이스 정의 (P-001)
- [x] 우선/후속 구분 (P-CLAUDE v0.1, P-OPENAI v0.2, P-GEMINI v0.3)
- [x] 신규 추가 절차 (R-015 보강)
- [x] Capabilities 단일화
- [x] 보안 (API 키 마스킹, 영속화 금지)
- [x] ImageInput.Url SSRF 방어 정책 (R-023)

---

## 10. 핑퐁 카운터

라운드 3 신규 결함 R-025는 **A-009 표의 정책 망라가 본문 부기 누락을 보완**하므로 사양 모순이 아니다. 라운드 1/2에서 같은 항목으로 반복 충돌한 사례 없음. **핑퐁 에스컬레이션 조건 미충족**.

R-017~R-020 4건은 라운드 2에서 처음 제기, 라운드 3에서 모두 반영 → 핑퐁 0회.

---

## 11. 종결 보고

| 항목 | 값 |
|------|-----|
| 종결 권고 | **Phase 3 진입 가능** |
| 사양 최종 카운트 (라운드 3 종결 시점) | F-: 9개 (F-000~F-008) / E-: 36개 (E-001~E-003, E-101~E-110, E-201~E-207, E-301~E-303, E-401~E-403, E-501~E-502, E-601~E-602, E-701~E-706, E-801) / M-: 11개 (M-001~M-011) / ERR-: 7개 (ERR-001~ERR-007) / A-: 12개 (A-001~A-012) / P-: 3개 (P-001 인터페이스, P-CLAUDE, P-OPENAI/P-GEMINI 식별자 사양상 부여 — 실제 enum은 v0.1 CLAUDE만) / D-: 5개 (D-001~D-005, 모두 종결) |
| 미해결 결정 | 0건 |
| Critical | 0건 |
| Major | 0건 |
| Minor | 1건 (R-025, 종결 가능 수준) |
| 사용자 결정 필요 | 없음 |

R-025는 Phase 3 진입과 병행하여 spec-architect가 미세 보강하거나 implementer 코드 주석으로 흡수해도 무방.
