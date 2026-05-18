# Spec Review Report — 02-ai-agent-sdk v0.1 (Round 2)

검토자: spec-reviewer
검토 일시: 2026-05-07
대상 파일: overview.md / features.md / api.md / data-model.md / error-handling.md / provider-spec.md
참조 문서: `_workspace/spec_review_1.md`, `_workspace/spec_draft_2.md`

---

## 1. 요약

| Severity | 건수 | 비고 |
|----------|------|------|
| Critical | 0 | |
| Major | 4 | R-017 ~ R-020 (라운드 2 신규 발견) |
| Minor | 4 | R-021 ~ R-024 (라운드 2 신규 발견) |

라운드 1 R-001~R-016은 **모두 반영 통과**. D-001~D-005도 모두 종결되어 미해결 결정 0건.
다만 D-002 스코프 확장(영속화 v0.1 포함)으로 신규 추가된 F-007/F-008/A-009~A-012/M-011/E-701~E-706/E-801 영역에서 **Major 4건의 모순/누락**을 신규 발견.

종결 조건(Critical 0 + Major 0) 미충족 → **라운드 3 필요**.

---

## 2. 라운드 1 지적 사항 반영 검증 (R-001 ~ R-016)

### Major (R-001 ~ R-011)

| R-ID | 검토 결과 | 반영 위치 | 비고 |
|------|-----------|-----------|------|
| R-001 | **통과** | overview.md "결정 사항 (라운드 2 종결)" 표 | D-001~D-005 모두 결정값+사유 명시. 미해결 0건 명시. features/api/error-handling 전반에 D-004(자동 리사이즈 안 함)/D-005(자동 재시도 없음) 영향 반영 |
| R-002 | **통과** | F-008, A-009, E-109/E-402/E-705 | close API 추가, idempotent/thread-safe/진행 중 취소 정책 명시. 다만 close + 진행 중 save 의 결과 시맨틱은 R-020에서 추가 지적 |
| R-003 | **통과** | F-002 E-206, error-handling.md 매핑 표 | URL fetch 실패의 4xx/5xx/timeout/DNS/redirect 한계 분기 매핑 명시 |
| R-004 | **통과** | F-002 E-207 | 헤더 매직 넘버 검증 + InvalidInput 명시 |
| R-005 | **통과** | F-001 정상 흐름 4단계, E-110 | finishReason 결합 정책 명시: END_TURN/STOP_SEQUENCE+빈 텍스트=성공, MAX_TOKENS/OTHER+빈=ServerError |
| R-006 | **통과** | F-004 "컨텍스트 한계 검증 알고리즘" 섹션, P-CLAUDE 에러 매핑 | "사전 토큰 카운트 추정 안 함, Provider 응답에서 감지" 정책 명시 |
| R-007 | **통과** | F-005 "동시성 모델" 섹션, M-007 동시성 | AtomicReference 기반, set은 수 ns, 진행 중 요청은 캡쳐된 Provider 인스턴스로 진행 명시 |
| R-008 | **통과** | F-004 "systemPrompt 정책" 섹션, M-007/M-008 Role 사용 정책 | "systemPrompt는 history에 포함되지 않음, Role.SYSTEM은 Mapper 레벨 변환에만 사용" 명시 |
| R-009 | **통과** | M-010 enum, provider-spec.md 표 | v0.1 enum에 CLAUDE만 포함, OPENAI/GEMINI는 식별자만 부여 + 구현 시 enum 추가 명시 |
| R-010 | **통과** | M-010, P-001, provider-spec.md "Capabilities single source of truth" | enum에서 capability boolean 모두 제거, Capabilities는 Provider 인터페이스 측에 단일화 |
| R-011 | **통과** | A-007, M-007 동시성 모델 | "immutable snapshot 반환, Mutex 안에서 List 복사" 명시 |

### Minor (R-012 ~ R-016)

| R-ID | 검토 결과 | 반영 위치 | 비고 |
|------|-----------|-----------|------|
| R-012 | **통과** | F-006 사용 예 | @HiltAndroidApp + @HiltViewModel + @Inject constructor + hiltViewModel() 4단계 예제 추가 |
| R-013 | **통과** | error-handling.md "에러 코드 정의" 위 안내 문장 | "권장 예시이며 호출자가 자체 i18n/UI 정책에 맞게 변환 가능" 명시 |
| R-014 | **통과** | F-005 "Session과 Provider의 관계" 섹션, M-007 "Provider 바인딩 정책" 섹션 | Session은 Provider에 묶이지 않음, send 시점 활성 Provider 사용 명시 |
| R-015 | **통과** | provider-spec.md "신규 Provider 추가 절차" | 1단계 P-XXX ID 부여 + 8단계 사양 갱신에 P-XXX 표 갱신 명시 |
| R-016 | **통과** | M-003.Bytes 일반 class 정의 | data class → 일반 class, equals/hashCode/toString 명시. ByteArray 관련 사유 주석도 포함 |

**소결**: 라운드 1 지적 사항 16건 모두 통과. 미반영/부분반영 0건.

---

## 3. 라운드 2 신규 사양 검토 (D-002 영속화 확장)

신규 추가된 사양(F-007/F-008, M-011, A-009~A-012, ERR-007, E-109/E-110/E-206/E-207/E-403/E-701~E-706/E-801)을 처음부터 검토.

### 3.1 통과 항목

| 영역 | 평가 |
|------|------|
| 영속화 트리거 정책 | **통과**. F-007 "트리거 정책 및 합리적 기본값" 섹션에 "명시적 save 모델 채택" 명확히 정의. 자동 저장 옵션은 v0.2 검토로 명시 |
| 디스크 IO Dispatcher | **통과**. NFR/시그니처 모두 Dispatchers.IO 명시 |
| 직렬화 형식 | **통과**. JSON + schemaVersion + UTF-8 + DataStore Preferences 키 규칙 명시 |
| 보안 (API 키 영속화 금지) | **통과**. SessionEntity 정의에 API 키 미포함 + provider-spec.md 보안 섹션 명시 |
| 이미지 영속화 직렬화 | **통과 (단 R-021 참조)**. ImageInputEntity.Bytes는 base64 + mimeType, ImageInput.Uri는 URI 문자열, ImageInput.Url은 URL 문자열로 분리 처리 |
| close idempotent / thread-safe | **통과**. F-008 close 정책 섹션 명시 |
| ID 충돌 | **통과**. F-007/F-008, M-011, ERR-007, A-009~A-012, E-XXX 신규 모두 기존 ID와 충돌 없음 (spec-architect 자체 점검 결과 재확인) |
| ERR ↔ AiException 매핑 | **통과**. ERR-007 IOError 신규 매핑 표 등재. M-005에 IOError variant 추가 |
| E-XXX ↔ ERR-XXX 매핑 | **통과**. error-handling.md 매핑 표에 E-109/E-110/E-206/E-207/E-403/E-701~E-706/E-801 모두 등재 |
| close 시 영속화 처리 | **부분 통과**. F-008에 "DataStore 핸들 해제(있다면)" 명시. 다만 진행 중 save/load의 시맨틱은 R-020 참조 |
| 영속화 실패가 send 흐름에 미치는 영향 | **통과**. save는 별도 API라 send 흐름과 독립. send 진행 중 save는 Mutex 대기 명시 |

### 3.2 신규 결함 (R-017 ~ R-024) — 4절 참조

---

## 4. 신규 결함 (라운드 2에서 처음 발견)

### Major

| ID | Severity | 위치 | 문제 | 제안 수정 |
|----|----------|------|------|----------|
| R-017 | Major | api.md A-010 / features.md F-007 정상 흐름 5단계 | `save()`의 sessionId 출처가 모순. A-010 시그니처는 `suspend fun save(): Result<String>`로 인자 없음(자동 UUID 사용). 그러나 F-007 정상 흐름 5단계는 "자동 생성된 UUID **또는 save() 인자로 전달된 식별자**"로 적혀 있어 시그니처와 모순. spec_draft_2.md 보고서도 "save() 인자로 전달된 식별자"로 언급 | 둘 중 하나로 통일: (a) A-010 시그니처 유지(자동 UUID만), F-007 5단계 문구를 "Session 생성 시 자동 부여된 UUID"로 수정 / (b) A-010 시그니처를 `suspend fun save(sessionId: String? = null): Result<String>`로 변경하고 F-007도 명확화. 권장은 (a) — 호출자가 sessionId를 명시 제어하려면 createSession에 인자를 추가해야 하므로 일관성 측면에서 별도 결정 필요 |
| R-018 | Major | data-model.md M-011 "마이그레이션" / F-007 E-703 | schemaVersion 불일치 시 "SDK가 가능하면 best-effort 마이그레이션, 불가능하면 E-703" 정책이 모호함. v0.1은 schemaVersion=1만 존재하므로 best-effort 동작 정의 자체가 불가능. QA 검증 기준 모호 | v0.1 정책을 다음 중 하나로 단순화: (a) "v0.1은 schemaVersion=1만 인정. 그 외는 즉시 E-703 IOError throw, 자동 마이그레이션 v0.2 검토" / (b) 마이그레이션 인터페이스(Migration: (Json) -> Json) 사양 명시 + 등록 절차. 권장은 (a) — v0.1 단순성 |
| R-019 | Major | features.md F-007 "동시성 모델" / overview.md NFR | 다중 Session 인스턴스 / 다중 프로세스 동시 접근 정책 누락. 호출자가 같은 sessionId로 loadSession을 두 번 호출하여 두 개의 Session 인스턴스를 만들고 각각 send 후 save하면 "마지막 save 승" 정책인지 충돌 검출인지 미정의. WorkManager 등 별도 프로세스에서 같은 DataStore 접근도 미정의 (DataStore Preferences는 단일 프로세스 권장) | F-007 또는 overview.md에 다음 중 하나 명시: (a) "동일 sessionId에 대한 다중 인스턴스 동시 save는 마지막 호출이 덮어쓴다(last-write-wins). 호출자가 sessionId 단일 인스턴스 사용 책임 보장" / (b) Mutex 기반 명시적 충돌 검출. 다중 프로세스에 대해서는 "단일 프로세스 사용 가정, 다중 프로세스는 v0.2 검토" Out of Scope 명시 권장 |
| R-020 | Major | features.md F-008 정상 흐름 2-3단계 / F-007 E-705 / api.md A-009/A-010 | close 호출 시 진행 중 save/load의 결과 시맨틱이 모순. F-008 정상 흐름 3단계 + close 정책에 "진행 중 요청은 즉시 취소되며 CancellationException 전파, Result.failure로 변환되지 않음"이라고 명시. 하지만 A-010/A-011/A-012는 모두 `Result<...>` 반환. close 직후 호출은 E-705로 `Result.failure(Configuration)`. 즉 "진행 중에 close 호출"과 "close 후 호출"이 다른 시맨틱(throw vs Result.failure)인데 호출자가 두 케이스를 구분하기 어려움. 또한 cancel된 코루틴 안에서 Result를 받을 수 없으므로 사실상 throw만 일어남 — 이 점이 명확히 적혀 있지 않음 | F-008과 F-007의 모순을 다음과 같이 정리: "(a) close가 진행 중인 save/load/delete 코루틴은 CancellationException으로 종결되며, 호출자는 try/catch로 처리(또는 `runCatching`/coroutine cancellation 시맨틱 따름). (b) close 후 새로 호출되는 save/load/delete는 즉시 `Result.failure(Configuration("client closed"))` 반환." 두 케이스의 차이를 A-009/A-010/A-011/A-012 동작 섹션에 명시. 또는 일관성 위해 (a)의 경우도 Result.failure로 감싸는 정책으로 변경 검토 |

### Minor

| ID | Severity | 위치 | 문제 | 제안 수정 |
|----|----------|------|------|----------|
| R-021 | Minor | features.md F-007 "데이터 크기 정책" / data-model.md M-003 영속화 규칙 | 1MB 직렬화 한계와 이미지 5MB 허용 정책이 사실상 양립 불가. ImageInput.Bytes 5MB → base64 ≈ 6.7MB로 단일 이미지만 있어도 1MB 초과. 호출자가 "이미지 포함 history는 영속화 불가"라는 사실을 사양에서 알기 어려움 | F-007 데이터 크기 정책 또는 NFR에 추가 명시: "이미지(`ImageInput.Bytes`)가 포함된 history는 사실상 1MB를 초과한다. 영속화 시 호출자는 이미지를 포함하지 않은 message만 직렬화하거나, history에서 이미지를 제거하고 save하는 것을 권장. v0.2에서 외부 저장소 옵션 검토." 또는 base64 1MB 초과 image 자동 제외 정책 추가 |
| R-022 | Minor | data-model.md M-011 schemaVersion | 마이그레이션 절차 명세 누락. R-018과 별개로, schemaVersion 변경 시 어떤 위치에 마이그레이션 함수를 두는지(예: `internal interface SessionMigration`), 어떤 시점에 호출되는지(load 시 1회 vs save 시 변환) 미정의 | schemaVersion 변경 시점에 정의해도 무방하지만, v0.1 사양에 다음 정도는 부기 권장: "schemaVersion 변경 시 `internal object SessionMigrations`에 `from-N-to-N+1` 함수를 등록하는 패턴을 따른다 (실제 추가는 v0.2부터)." |
| R-023 | Minor | data-model.md M-011 ImageInputEntity.Url / provider-spec.md 보안 | `ImageInput.Url` 영속화 후 복원하면 다음 send에서 SDK가 URL을 fetch하거나 Provider에 그대로 전달. 외부 URL 캐싱 후 재사용 시 SSRF/내부망 우회 등 보안 위험 가능성. 호출자 책임이라고만 적혀 있고 SDK 측 검증 정책 없음 | provider-spec.md 보안 섹션 또는 F-007 NFR에 다음 정도 명시: "ImageInput.Url 복원 시 SDK는 URL 형식 검증만 수행. 내부망/loopback 차단 등 SSRF 방어는 호출자 책임. v0.2에서 URL allowlist 옵션 검토" |
| R-024 | Minor | api.md A-004 createSession / A-010 save | sessionId 컨트롤 인터페이스 부재. createSession에 sessionId 인자가 없어 호출자가 명시적으로 ID 부여 불가. save는 자동 UUID만 사용. 동일 sessionId로 다른 Session을 의도적으로 사용하려면 loadSession 후 send만 가능 (즉 새 Session에 기존 sessionId 부여 불가) | 다음 중 하나 명시: (a) "v0.1은 sessionId 자동 부여만 지원, 호출자 명시는 v0.2" Out of Scope 추가 / (b) `createSession(systemPrompt: String? = null, sessionId: String? = null)` 시그니처 확장. 권장은 (a) — v0.1 단순성 우선 |

---

## 5. 합의 종결 조건 평가

| 조건 | 충족 여부 | 비고 |
|------|-----------|------|
| Critical 0건 | ✅ 충족 | 본 라운드 Critical 0건 |
| Major 0건 | ❌ **미충족** | R-017, R-018, R-019, R-020 4건 미해결 |
| 미해결 결정(D-XXX) 0건 | ✅ 충족 | overview.md "결정 사항" 표에 D-001~D-005 모두 종결, 신규 D-XXX 미생성 |
| 모든 라운드 1 지적 반영 | ✅ 충족 | R-001~R-016 모두 통과 |

→ **종결 불가**. Major 4건이 모두 D-002 스코프 확장으로 새로 추가된 영속화/lifecycle 영역의 **모순(R-017, R-020)** 또는 **누락(R-018, R-019)**이며, 구현 진입 시 implementer가 임의 결정해야 할 위험이 있어 라운드 3 반영이 필요함.

---

## 6. 권장 다음 작업

### 6.1 spec-architect 라운드 3 반영 (권장)

다음 4개 Major를 spec-architect가 처리:

1. **R-017** — A-010 시그니처와 F-007 정상 흐름 통일 (spec-architect 결정 가능. 권장: 자동 UUID로 유지)
2. **R-018** — schemaVersion 마이그레이션 정책을 v0.1 단순화 (권장: schemaVersion=1만 인정, 그 외 E-703)
3. **R-019** — 다중 Session 인스턴스/다중 프로세스 정책 명시 (권장: last-write-wins + 다중 프로세스 Out of Scope)
4. **R-020** — close + 진행 중 save/load 시맨틱 정리 (권장: cancel은 throw, close 후 새 호출은 Result.failure 두 케이스를 A-009/A-010/A-011/A-012 동작 섹션에 명시)

Minor 4건(R-021~R-024)도 라운드 3에서 함께 일괄 반영 또는 명시적 거부+이유 기록 권장.

### 6.2 라운드 3 검토 후 종결

라운드 3에서 R-017~R-020 모두 통과되면 Critical 0 + Major 0 충족 → **Phase 3(구현) 진입 가능**.

### 6.3 사용자 결정 필요 여부

R-017~R-024 모두 spec-architect가 합리적 기본값으로 결정 가능한 범위(아키텍처/시맨틱 정리). **현 시점 사용자 결정 불필요**. 단, R-019의 "다중 프로세스 지원 여부"가 호출자 도메인 정책에 영향을 줄 수 있으므로 Out of Scope 결정 시 사용자 확인이 안전한 옵션.

---

## 7. 검토 통계 (체크리스트 — 라운드 2 시점)

### overview.md
- [x] 목적 한 문장 명료
- [x] In/Out Scope 둘 다 채워짐 (영속화 In Scope 추가, 자동 재시도/리사이즈/API 키 디스크 영속화 Out of Scope 추가)
- [x] Out of Scope 이유 있음 (D-XXX 결정 ID 명시)
- [x] 기술 스택 명시 (OkHttp, DataStore, kotlinx.serialization)
- [x] 의존성 정책 있음
- [x] 미해결 결정 0건 (D-001~D-005 모두 종결)

### features.md
- [x] 모든 F-XXX (F-000~F-008) 사전 조건 있음
- [x] 정상 흐름 + 예외 흐름 둘 다 있음 (신규 F-007/F-008 포함)
- [x] 외부 호출 예외 흐름: F-001 충분, F-002 R-003/R-004 반영(E-206/E-207), F-003 충분
- [x] NFR 측정 가능 (p95 5초 / p95 500ms save / p95 300ms load 등)
- [x] 코루틴 취소 동작 명시 (E-106, E-302, E-706)
- [ ] **R-017 F-007 정상 흐름 5단계 모순**
- [ ] **R-019 다중 인스턴스/다중 프로세스 정책 누락**
- [ ] **R-020 close + 진행 중 save 시맨틱 모순**

### api.md
- [x] 모든 public 함수 시그니처 토큰 단위 (A-001~A-012)
- [x] suspend / Flow 명확
- [x] 콜백 없음
- [x] F-XXX 참조
- [x] 사용 예 있음 (A-002, A-003, A-006(주석), A-009, A-010, A-011)
- [x] A-009 close 추가 (R-002 반영)
- [ ] **R-017 A-010 시그니처와 F-007 모순**
- [ ] **R-024 createSession/save sessionId 컨트롤 부재 (Minor)**

### data-model.md
- [x] 필드 타입/필수 명시
- [x] sealed class 정의 (M-003 ImageInput, M-005 AiException, M-006 AiStreamEvent)
- [x] 직렬화 규칙 (Provider 전송용 + 영속화용 분리)
- [x] 크기 제한 (5MB / 20MB / 10장 + 영속화 1MB)
- [x] M-003 equals/hashCode 명시 (R-016 반영)
- [x] Role.SYSTEM 정책 명시 (R-008 반영)
- [x] M-010 enum 단일화 (R-009/R-010 반영)
- [x] M-011 SessionEntity 신규 정의
- [ ] **R-018 마이그레이션 정책 모호 (Major)**
- [ ] **R-021 1MB 한계 vs 이미지 5MB 양립 불가 (Minor)**
- [ ] **R-022 마이그레이션 절차 명세 누락 (Minor)**
- [ ] **R-023 ImageInput.Url 복원 시 보안 정책 부족 (Minor)**

### error-handling.md
- [x] ERR-XXX 사용자 메시지 (영문 권장 예시 안내 추가, R-013 반영)
- [x] ERR-XXX 복구 방법
- [x] AiException 매핑 표 (ERR-001~ERR-007 모두)
- [x] E-XXX ↔ ERR-XXX 매핑 (E-109/E-110/E-206/E-207/E-403/E-701~E-706/E-801 모두 등재)
- [x] 로깅 정책 (debug/release 분리)
- [x] 영속화 에러 처리 호출 예 추가

### provider-spec.md
- [x] Provider 인터페이스 정의 (P-001)
- [x] 우선/후속 구분 (P-CLAUDE v0.1, P-OPENAI v0.2, P-GEMINI v0.3)
- [x] 신규 추가 절차 (R-015 보강 — P-XXX 부여 + 사양 갱신 명시)
- [x] Capabilities 단일화 (R-010 반영)
- [x] Capabilities는 Provider 인터페이스 측 single source of truth 명시
- [x] 보안 (API 키 마스킹, 영속화 금지)
- [ ] **R-023 ImageInput.Url SSRF 방어 정책 부족 (Minor, F-007과 연계)**

---

## 8. 핑퐁 카운터

라운드 2 신규 Major 4건은 모두 D-002 스코프 확장으로 새로 추가된 영역이며, **라운드 1에서 이미 같은 항목으로 반복 충돌한 사례 없음**. 핑퐁 에스컬레이션 조건 미충족 → 정상 라운드 3 진행.
