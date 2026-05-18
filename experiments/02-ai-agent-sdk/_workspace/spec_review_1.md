# Spec Review Report — 02-ai-agent-sdk v0.1 (Round 1)

검토자: spec-reviewer
검토 일시: 2026-04-29
대상 파일: overview.md / features.md / api.md / data-model.md / error-handling.md / provider-spec.md

## 요약

| Severity | 건수 |
|----------|------|
| Critical | 0 |
| Major | 11 |
| Minor | 5 |

종결 조건(Critical 0)은 만족하지만, **Major 11건 중 D-001~D-005 미해결 결정과 lifecycle 메서드 누락은 구현 진입 전 반드시 해결**해야 함. 합의 필요.

---

## 항목별 검토

### Critical

없음.

### Major

| ID | 위치 | 문제 | 제안 수정 |
|----|------|------|----------|
| R-001 | overview.md | D-001 (HTTP 클라이언트), D-002 (세션 영속화), D-003 (API 키 저장), D-004 (이미지 리사이즈), D-005 (재시도) 5건 모두 미해결 — 구현 진입 시 implementer가 임의 결정 위험 | Phase 2 합의 종결 전 결정 필수. 본 검토 보고서 말미에 reviewer 입장 첨부함 |
| R-002 | api.md / features.md | `AiAgentClient`의 lifecycle 메서드 (close/release/shutdown) 미정의. F-004 E-402에서 "client 닫힘"을 참조하지만 닫는 방법이 없음 | A-009 `close()` 추가 또는 client는 application-singleton으로 닫지 않는 정책 명시. close 시 진행 중 요청 처리 방침도 필요 |
| R-003 | features.md F-002 | `ImageInput.Url`의 fetch 실패 케이스 누락 (404, timeout, 잘못된 URL, redirect 한계). E-201~E-205에 없음 | E-206 추가: "URL 이미지 fetch 실패 → AiException.Network 또는 InvalidInput" |
| R-004 | features.md F-002 | 이미지 디코딩 실패 (손상된 파일, mimeType 위조) 케이스 미정의 | E-207 추가: "이미지 디코딩 실패 → AiException.InvalidInput" |
| R-005 | features.md F-001 / data-model.md | Provider가 빈 응답 본문(`text=""`)을 반환할 때 처리 미정의. 성공? 실패? | 정상 흐름의 4단계에 "응답 검증" 단계 추가 또는 finishReason과 결합한 정책 명시 |
| R-006 | features.md F-004 | E-401 "누적 토큰이 모델 한계 초과"의 측정 방법 불명. SDK가 토큰 카운트 추정? Provider 응답 후 알게 됨? | Provider별 컨텍스트 한계를 ProviderConfig 또는 Capabilities에 추가하고, Session.send 진입 시 추정값으로 사전 검증하는 알고리즘 명시. 또는 사전 검증 없이 Provider 에러로 받기로 결정 후 명시 |
| R-007 | api.md A-005 / features.md F-005 | "thread-safe + 진행 중 요청 영향 없음"이 NFR인데 구현 메커니즘 미명세 (atomic reference / mutex / volatile). QA 검증 기준 모호 | data-model.md 또는 별도 동시성 섹션에 동시성 모델 명시 (예: "AtomicReference 기반, 변경은 다음 요청부터 적용") |
| R-008 | data-model.md M-008 / M-007 | `Role.SYSTEM`이 Message enum에 있지만, Session의 `systemPrompt`가 Message로 history에 들어가는지 별도 보관인지 불명. `Session.history()` 반환에 SYSTEM 메시지 포함 여부 미정 | M-007 또는 M-008에 명시: "systemPrompt는 history에 포함되지 않음" 또는 "첫 SYSTEM 메시지로 포함됨" 중 택일 |
| R-009 | data-model.md M-002 / M-010 | `ProviderId` enum에 v0.2/v0.3 Provider(OPENAI, GEMINI)가 이미 정의되어 있어 호출자가 Builder에서 선택 가능. 미구현 Provider 선택 시 동작 미정의 | (a) v0.1 enum에서 미구현 Provider 제거, 구현 시 추가 / (b) 선택 시 즉시 Configuration throw로 명시 — 둘 중 택일 |
| R-010 | provider-spec.md | `Capabilities`가 Provider 인터페이스 속성과 ProviderId enum 속성에 둘 다 정의됨 (`supportsImage` 등 중복). Single source of truth 모호 | Capabilities는 Provider 구현 측에만 두고, ProviderId enum은 식별자만 갖도록 정리. enum의 displayName도 별도 함수로 분리 검토 |
| R-011 | api.md A-007 / data-model.md M-007 | `Session.history()` 반환 `List<Message>`가 immutable copy인지 live view인지 미명시. 동시 send와의 race 동작 불명 | "immutable snapshot 반환, 호출 시점 history의 복사본"으로 명시 |

### Minor

| ID | 위치 | 문제 | 제안 수정 |
|----|------|------|----------|
| R-012 | features.md F-006 | Hilt 사용 예시 코드 부재 (E-601 컴파일 에러만 언급) | application 클래스에 `@HiltAndroidApp`, ViewModel에 `@Inject` 사용 예 1개 추가 |
| R-013 | error-handling.md | "사용자 메시지"가 영문으로만 작성됨. SDK는 표시하지 않으므로 무방하지만, 메시지가 권장 예시인지 강제인지 불명 | "권장 메시지 예시이며 호출자가 자체 변환 가능"임을 명시 |
| R-014 | api.md | A-005 useProvider 시 사용 중인 Session에 어떤 Provider가 적용되는지 미명시 (Session 생성 시 Provider 고정 vs 매 send마다 client의 활성 Provider 사용) | M-007 또는 A-006에 명시 |
| R-015 | provider-spec.md | "신규 Provider 추가 절차"의 8단계 중 "사양 갱신" 단계가 누락된 ID 결합(P-XXX 부여) 절차 없음 | P-XXX ID 부여 + provider-spec.md "지원 Provider 목록" 갱신 명시 |
| R-016 | data-model.md M-003 | `ImageInput.Bytes`의 equals/hashCode 구현 명세가 "..."로 비어 있음 | data class 기본 equals를 사용하지 않고 `data.contentEquals` + `data.contentHashCode + mimeType.hashCode` 명시 |

---

## 미해결 결정 (D-001~D-005) 토론 — Reviewer 입장

| ID | 항목 | 권장 | 근거 |
|----|------|------|------|
| D-001 | HTTP 클라이언트 | **OkHttp** | Android 표준, kotlinx.serialization과 통합 부담 작음, 코루틴 취소를 `call.cancel()`로 cooperative 변환하는 패턴이 검증됨. Ktor는 multiplatform 장점 있으나 v0.1 범위 외 |
| D-002 | 세션 영속화 | **메모리만 (v0.1)** | F-004 NFR이 "thread-safe"만 요구하고 process death 복원 요구가 없음. v0.2에서 DataStore 기반 옵트인 추가 |
| D-003 | API 키 저장 | **호출자 책임** (SDK는 받기만) | SDK가 EncryptedSharedPreferences를 강제하면 의존성 증가. SDK는 Builder.apiKey()로 받고 메모리에서만 보관, 디스크 영속화 금지를 NFR로 명시 |
| D-004 | 이미지 자동 리사이즈 | **호출자 책임** | SDK는 5MB 검증만 수행. 자동 리사이즈는 품질 손실 위험과 정책 결정이 호출자 도메인 |
| D-005 | 재시도 정책 | **SDK 기본값 + 주입 가능** | Network/RateLimit 한정으로 SDK가 exp backoff 기본 제공 (max 3회). 호출자가 RetryPolicy 주입 시 override. 다만 v0.1에서는 자동 재시도 없이 호출자에게 throw 후 호출자가 재호출하는 단순 정책으로 시작하고 v0.2에 정책 주입 추가하는 것도 합리적 — spec-architect와 토론 필요 |

---

## 검토 통계 (체크리스트)

### overview.md
- [x] 목적 한 문장 명료
- [x] In/Out Scope 둘 다 채워짐
- [x] Out of Scope 이유 있음
- [x] 기술 스택 명시
- [x] 의존성 정책 있음
- [ ] **D-001 등 미해결 5건**

### features.md
- [x] 모든 F-XXX 사전 조건 있음
- [x] 정상 흐름 + 예외 흐름 둘 다 있음
- [~] 외부 호출 예외 흐름: F-001은 충분, F-002는 R-003/R-004 누락, F-003은 일부
- [x] NFR 측정 가능 (대체로)
- [x] 코루틴 취소 동작 명시 (E-106, E-302)

### api.md
- [x] 모든 public 함수 시그니처 토큰 단위
- [x] suspend / Flow 명확
- [x] 콜백 없음
- [x] F-XXX 참조
- [x] 사용 예 있음
- [ ] **A-009 lifecycle 누락 (R-002)**

### data-model.md
- [x] 필드 타입/필수 명시
- [x] sealed class 정의
- [x] 직렬화 규칙
- [x] 크기 제한 (5MB / 20MB / 10장)
- [ ] **M-003 equals/hashCode 미완 (R-016)**
- [ ] **Role.SYSTEM 사용 정책 (R-008)**

### error-handling.md
- [x] ERR-XXX 사용자 메시지 (영어)
- [x] ERR-XXX 복구 방법
- [x] AiException 매핑 표
- [x] E-XXX ↔ ERR-XXX 매핑 (꼼꼼함)
- [x] 로깅 정책 (debug/release 분리)

### provider-spec.md
- [x] Provider 인터페이스 정의
- [x] 우선/후속 구분
- [x] 신규 추가 절차 (R-015 부분 보완 필요)
- [x] Capabilities 명시
- [ ] **Capabilities 중복 정의 (R-010)**

---

## 합의 종결 조건

- Critical: 0건 ✅
- Major 11건: 모두 (a) 반영 (b) 명시적 거부+이유 기록 중 하나로 처리 — **현재 미처리**
- Minor 5건: 일괄 반영 또는 다음 라운드로 이월 가능

→ **현재 라운드 1 미종결**. spec-architect의 반영 라운드 2 필요.

## 권장 다음 작업

1. spec-architect가 R-001~R-011 반영
   - 우선 D-001~D-005 결정 (사용자 또는 reviewer 입장 채택)
   - lifecycle 메서드 / Capabilities 단일화 / Session.history 정책 등 구조적 결정
2. 반영 후 라운드 2 검토 진행
3. Critical 0 + Major 0 만족 시 Phase 3(구현) 진입
