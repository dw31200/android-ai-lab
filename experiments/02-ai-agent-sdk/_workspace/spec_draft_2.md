# Spec Draft 2 — Round 2 반영 보고서

작성자: spec-architect
작성 일시: 2026-05-07
대상 검토 보고서: `_workspace/spec_review_1.md`
편집한 사양 파일:
- `spec/overview.md`
- `spec/features.md`
- `spec/api.md`
- `spec/data-model.md`
- `spec/error-handling.md`
- `spec/provider-spec.md`

---

## 1. 사용자 결정 (D-001~D-005) 확정

라운드 1에서 미해결이었던 5건을 본 라운드에서 모두 종결. 상세는 `spec/overview.md` "결정 사항 (라운드 2 종결)" 표 참조.

| ID | 결정 | reviewer 권장 대비 |
|----|------|-------------------|
| D-001 | OkHttp | reviewer 권장 채택 |
| D-002 | DataStore 영속화 v0.1 포함 | **reviewer 권장(메모리만)을 뒤집음 — 스코프 확장** |
| D-003 | 호출자 책임 (메모리 보관, 디스크 금지 NFR) | reviewer 권장 채택 |
| D-004 | 호출자 책임 (5MB 검증만) | reviewer 권장 채택 |
| D-005 | v0.1 자동 재시도 없음, v0.2 RetryPolicy 주입 | reviewer 권장의 후자 옵션 채택 |

미해결 결정: **0건** (overview.md "결정 사항" 표에 명시).

---

## 2. R-001 ~ R-016 항목별 반영 결과

### Major (R-001 ~ R-011)

| R-ID | 위치 | 반영 결과 | 변경 파일 |
|------|------|-----------|-----------|
| R-001 | overview.md 미해결 결정 5건 | overview.md "결정 사항" 표에 D-001~D-005 모두 종결, "미해결 결정" 섹션 제거(0건 명시). features/api/error-handling/data-model 전반에 결정 영향 반영 (D-005 자동 재시도 없음, D-004 자동 리사이즈 없음 등) | overview.md |
| R-002 | client lifecycle 누락 | F-008 신규 (close), A-009 close API 추가, E-109/E-402/E-705에 "client closed" 케이스 명시. close 정책(idempotent, thread-safe, 진행 중 요청 취소) 명시 | features.md F-008, api.md A-009, error-handling.md |
| R-003 | F-002 URL fetch 실패 누락 | E-206 신규 (URL 4xx/5xx/timeout/DNS/redirect 한계). HTTP 4xx/잘못된 URL → InvalidInput, 네트워크 → Network로 분기 매핑 | features.md F-002, error-handling.md |
| R-004 | 이미지 디코딩 실패 미정의 | E-207 신규 (손상된 파일, mimeType 위조 — 헤더 매직 넘버 검증) → InvalidInput | features.md F-002, error-handling.md |
| R-005 | 빈 응답 처리 미정의 | F-001 정상 흐름 4단계 "응답 검증" 추가. finishReason 결합 정책 명시: END_TURN/STOP_SEQUENCE + 빈 텍스트는 성공, MAX_TOKENS/OTHER + 빈 텍스트는 ServerError(E-110) | features.md F-001, error-handling.md |
| R-006 | E-401 컨텍스트 한계 알고리즘 불명 | F-004 "컨텍스트 한계 검증 알고리즘" 섹션 신규: SDK 사전 토큰 카운트 추정 안 함, Provider 응답에서 감지하여 InvalidInput 변환. 호출자가 clear 또는 history 자르기로 복구 | features.md F-004, provider-spec.md (P-CLAUDE 에러 매핑) |
| R-007 | useProvider 동시성 모델 미명세 | F-005 "동시성 모델" 섹션 신규: AtomicReference 기반, set은 수 ns. 진행 중 요청은 캡쳐된 Provider 인스턴스로 진행. Session도 send 진입 시 atomic get | features.md F-005, data-model.md M-007 |
| R-008 | Role.SYSTEM 사용 정책 불명 | F-004 "systemPrompt 정책" + M-007/M-008 "Role 사용 정책" 섹션 추가: systemPrompt는 history에 포함되지 않음. SYSTEM은 Provider 전송 내부 변환 시에만 사용 | features.md F-004, data-model.md M-007/M-008 |
| R-009 | ProviderId enum 미구현 Provider 포함 | M-010에서 v0.1은 `CLAUDE`만 포함. OPENAI/GEMINI는 구현 라운드(v0.2/v0.3)에서 enum 추가. provider-spec.md "지원 Provider 목록" 표에 P-XXX와 enum 매핑 분리 명시 | data-model.md M-010, provider-spec.md |
| R-010 | Capabilities 중복 정의 | ProviderId enum에서 supportsImage 등 boolean 모두 제거 (M-010). Capabilities는 Provider 인터페이스 측의 단일 source of truth 명시. provider-spec.md에 "Capabilities는 Provider 구현체에만 존재" 명시 | data-model.md M-010, provider-spec.md P-001 |
| R-011 | Session.history snapshot 정책 | A-007에 "immutable snapshot 반환, 호출 시점 history의 복사본" 명시. M-007의 동시성 모델 섹션에 Mutex 안에서 List 복사 반환으로 race 방지 명시 | api.md A-007, data-model.md M-007 |

### Minor (R-012 ~ R-016)

| R-ID | 위치 | 반영 결과 | 변경 파일 |
|------|------|-----------|-----------|
| R-012 | F-006 Hilt 사용 예 부재 | F-006에 @HiltAndroidApp, @HiltViewModel + @Inject constructor, hiltViewModel() 사용 예 추가 | features.md F-006 |
| R-013 | 에러 메시지 강제/권장 불명 | error-handling.md "에러 코드 정의" 위에 "**권장 예시**이며 호출자가 자체 i18n/UI 정책에 맞게 변환 가능" 명시 | error-handling.md |
| R-014 | useProvider 시 Session 동작 미명시 | F-005 "Session과 Provider의 관계" 섹션 + M-007 "Provider 바인딩 정책" 섹션 신규: Session은 Provider에 묶이지 않음, send 시점의 활성 Provider 사용 | features.md F-005, data-model.md M-007, api.md A-005 |
| R-015 | 신규 Provider 추가 절차 P-XXX 누락 | provider-spec.md "신규 Provider 추가 절차" 1단계로 "P-XXX ID 부여" 추가, 8단계 "사양 갱신"에 P-XXX 표 갱신 명시 | provider-spec.md |
| R-016 | M-003.Bytes equals/hashCode 미완 | data-model.md M-003.Bytes를 data class에서 일반 class로 변경, equals(contentEquals + mimeType), hashCode(31 * contentHashCode + mimeType.hashCode), toString 명시. 이유(data class 자동 ByteArray reference 비교 부적절)도 명시 | data-model.md M-003 |

---

## 3. D-002 스코프 확장으로 신규 추가된 ID

DataStore 영속화 v0.1 포함으로 인한 신규 사양 추가.

### 신규 F (Features)

| ID | 이름 | 설명 |
|----|------|------|
| F-007 | 세션 영속화 (DataStore) | 명시적 save/load/delete, Dispatchers.IO, JSON 직렬화 |
| F-008 | 클라이언트 라이프사이클 (close) | R-002 lifecycle 메서드. F-007과는 독립이지만 close 시 DataStore 핸들 해제 책임 포함하므로 함께 신설 |

### 신규 M (Data Models)

| ID | 이름 | 설명 |
|----|------|------|
| M-011 | SessionEntity | DataStore 직렬화 엔티티 (schemaVersion, sessionId, systemPrompt, history, savedAt). MessageEntity, ImageInputEntity 내부 모델 포함 |

기존 M-005 AiException에 `IOError` variant 추가 (별도 M ID 부여 안 함, sealed class 확장).

### 신규 ERR

| ID | 이름 | 설명 |
|----|------|------|
| ERR-007 | IO 에러 | 영속화 저장/복원 실패. AiException.IOError에 매핑 |

### 신규 E (기능별 예외)

F-001/F-002/F-004 추가:
- E-109 (client closed at ask)
- E-110 (응답 검증 실패: 빈 응답 + MAX_TOKENS/OTHER) — R-005
- E-206 (URL fetch 실패) — R-003
- E-207 (이미지 디코딩 실패) — R-004
- E-403 (동시 send Mutex 직렬화) — R-007 연계

F-007 신규:
- E-701 (저장 IO 실패)
- E-702 (sessionId 없음)
- E-703 (손상된 데이터)
- E-704 (직렬화 1MB 초과)
- E-705 (client closed at save/load/delete)
- E-706 (영속화 도중 취소)

F-008 신규:
- E-801 (close 도중 IO — 무시, 로그만)

### 신규 A (Public APIs)

| ID | 이름 | 시그니처 |
|----|------|---------|
| A-009 | AiAgentClient.close | `fun close()` |
| A-010 | Session.save | `suspend fun save(): Result<String>` |
| A-011 | AiAgentClient.loadSession | `suspend fun loadSession(sessionId: String): Result<Session>` |
| A-012 | AiAgentClient.deleteSession | `suspend fun deleteSession(sessionId: String): Result<Unit>` |

### P (Provider) — 변경 없음
P-001(Provider 인터페이스), P-002(ProviderRegistry)는 라운드 1과 동일. P-CLAUDE/P-OPENAI/P-GEMINI 식별자 표기 정책만 R-009/R-015로 정리.

---

## 4. F-007 영속화 세부 결정 (합리적 기본값 채택, 신규 D-XXX 미생성)

다음은 작업 범위 명세에 따라 합리적 기본값을 사양 자체에 명시한 결정사항. 새 미해결 결정(D-XXX)을 만들지 않았다.

| 결정 | 채택값 | 사유 (사양에 명시됨) |
|------|--------|---------------------|
| 트리거 | 명시적 save 모델 | 자동 저장은 매 send마다 disk IO → 성능 비용. 호출자 도메인 정책에 따라 시점 결정 필요. v0.2 자동 옵션 검토 |
| 동시성 | DataStore transactional + 동일 Session에 send 진행 중 save는 Mutex 대기 | history 무결성 보장 |
| 데이터 크기 한계 | 1MB (직렬화 결과) | DataStore Preferences는 단일 파일 전체를 한 번에 read/write. 1MB 초과는 E-704 |
| Dispatcher | Dispatchers.IO | NFR. 호출 스레드 블로킹 금지 |
| 직렬화 형식 | JSON + schemaVersion | kotlinx.serialization, 향후 마이그레이션 호환 |
| 보안 | API 키 절대 영속화 안 함 (D-003 NFR과 결합) | Session에 API 키 보관 안 하므로 자동 보장 |
| 손상 데이터 처리 | E-703 IOError, SDK 자동 삭제 안 함 | 호출자가 deleteSession 명시 호출 (사용자 동의 후) |
| 이미지 처리 | Bytes는 base64 직렬화, Uri는 문자열만 | URI 복원 후 read 실패는 호출자 책임 |
| F-008 close 정책 | idempotent, thread-safe, 진행 중 취소 (CancellationException) | 표준 코루틴 시맨틱 |

---

## 5. 미반영 항목

없음. R-001~R-016 모두 반영됨.

---

## 6. 신규 D-XXX 미해결 결정 0건 확인

본 라운드에서 새로운 D-XXX(미해결 결정)를 생성하지 않았다. 영속화 트리거·정책·동시성·크기 한계 등 세부 결정은 모두 사양에 직접 결정값과 사유를 명시했다.

`spec/overview.md` "결정 사항 (라운드 2 종결)" 섹션:
> 미해결 결정: 0건.

---

## 7. ID 충돌 및 일관성 자체 점검

- F-XXX: F-000~F-008 (라운드 1 F-000~F-006 + 신규 F-007/F-008). 충돌 없음.
- M-XXX: M-001~M-011 (신규 M-011). 충돌 없음.
- ERR-XXX: ERR-001~ERR-007 (신규 ERR-007). 충돌 없음.
- A-XXX: A-001~A-012 (신규 A-009~A-012). 충돌 없음.
- E-XXX: 기존 + 신규 (E-109, E-110, E-206, E-207, E-403, E-701~E-706, E-801). 기존 ID 변경 없음.
- P-XXX: P-001/P-002 유지. P-CLAUDE/P-OPENAI/P-GEMINI는 식별자 형태로 라운드 1 그대로.

자체 체크리스트:
- [x] 모든 F-XXX에 정상 + 예외 흐름이 채워져 있음 (F-007/F-008 포함)
- [x] 모든 NFR이 측정 가능한 기준 (응답 시간 p95, 동시 큐 8개, save p95 500ms 등)
- [x] api.md의 시그니처가 data-model의 클래스를 정확히 참조 (M-011 SessionEntity는 internal이므로 api.md 노출 없음)
- [x] error-handling의 모든 ERR-XXX가 features의 E-XXX와 매핑됨 (E-109/E-110/E-206/E-207/E-403/E-701~E-706/E-801 모두 매핑 표에 등재)
- [x] provider-spec의 인터페이스가 api.md와 모순 없음 (Capabilities single source of truth 통일)
- [x] In Scope / Out of Scope가 둘 다 채워져 있음 (영속화 In Scope 추가, Out of Scope에 자동 재시도/리사이즈/API 키 디스크 영속화 추가)

---

## 8. 다음 단계

**spec-reviewer 라운드 2 검토 요청.**

본 라운드 변경 사항을 spec-reviewer에게 알리고 검토 요청을 발신해야 함 (실제 SendMessage 호출은 오케스트레이터 담당). 메시지 본문 권장:

> 라운드 2 반영 완료, 검토 요청.
>
> 반영 항목: R-001~R-016 (Major 11 + Minor 5) 전체 반영. D-001~D-005 결정 종결(미해결 0건).
>
> D-002 결정으로 영속화 스코프 확장: F-007/F-008 신규, M-011 신규, A-009~A-012 신규, ERR-007 신규, E-109/E-110/E-206/E-207/E-403/E-701~E-706/E-801 신규.
>
> 편집 파일: spec/overview.md, features.md, api.md, data-model.md, error-handling.md, provider-spec.md.
> 변경 요약: _workspace/spec_draft_2.md.
