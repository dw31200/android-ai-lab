# 구현 요약 #8 — F-004 (세션 컨텍스트 유지)

본 라운드(T16): F-004 본체 — `session/Session.kt` 신규 + `model/Message.kt` 신규 + `AiAgentClient.createSession` 추가 + `Mapper.toAnthropicRequestForSession` 추가 + `AnthropicMessagesRequest.system` 필드 추가 + `ErrorMapper.mapContextOverflow` (E-401) 추가 + `ClaudeProvider.completeForSession` 추가.

---

## 0. 회귀 점검 (이전 라운드)

- F-001 / F-002 / F-003 / F-006 변경 영향 분석:
  - `AnthropicMessagesRequest.system: String? = null` 추가 — default null이며 `AnthropicJson.encodeDefaults=false + explicitNulls=false` 정책으로 직렬화 제외. F-001/F-002 단발 질의에서 생성되는 JSON은 변경 없음.
  - `Mapper.toAnthropicRequest` 시그니처는 변경 없음 — F-001/F-002 호출자(ClaudeProvider.complete)도 변경 없음. `Mapper.buildContentJson`은 내부 헬퍼를 [buildMessageContentJson]으로 추출했지만 외부 동작은 동일.
  - `ClaudeProvider`를 `open class`로 변경 + `complete`/`stream`은 이미 override이므로 implicitly open. F-004 테스트용 `FakeClaudeLike`가 `completeForSession`을 override 가능하게 함. F-001/F-002/F-003 회귀 0건.
  - `ClaudeProvider.complete`에서 4xx 케이스에 `ErrorMapper.mapContextOverflow` 사전 검사 추가 — context_length_exceeded 매칭 시 InvalidInput. 기존 4xx 매핑은 그대로 (감지 실패 시 null 반환).
- `AiAgentClient.createSession` 추가는 기존 ask/askStream/useProvider/close에 영향 없음.

---

## 1. 생성/수정 파일 목록

### 1-A. 신규 파일

| 파일 | 책임 |
|------|------|
| `sdk/src/main/kotlin/com/androidailab/aisdk/model/Message.kt` | M-008 Message data class + Role enum (USER/ASSISTANT/SYSTEM) |
| `sdk/src/main/kotlin/com/androidailab/aisdk/session/Session.kt` | M-007 Session 본체 — send/history/clear + Mutex 직렬화 + R-008 systemPrompt |
| `sdk/src/test/kotlin/com/androidailab/aisdk/session/SessionTest.kt` | F-004 Session 단위 테스트 (정상 흐름 + R-008 + R-011 + E-401/E-402/E-403/E-106 + R-014) |
| `sdk/src/test/kotlin/com/androidailab/aisdk/client/CreateSessionTest.kt` | F-004 A-004 createSession 단위 테스트 (UUID, default null, R-020 케이스 B) |
| `sdk/src/test/kotlin/com/androidailab/aisdk/internal/network/MapperSessionTest.kt` | F-004 Mapper.toAnthropicRequestForSession + ErrorMapper.mapContextOverflow (E-401) 단위 테스트 |

### 1-B. 수정 파일

| 파일 | 변경 내용 | 호환성 |
|------|----------|--------|
| `sdk/src/main/kotlin/com/androidailab/aisdk/AiAgentClient.kt` | `createSession(systemPrompt: String? = null): Session` 본체 추가 (R-020 케이스 B → ensureNotClosed throw, UUID.randomUUID sessionId 부여) + import `com.androidailab.aisdk.session.Session`, `java.util.UUID` 추가. KDoc 상단 라운드 표 갱신 (F-004 포함) | 기존 ask/askStream/useProvider/close 시그니처 변경 없음 (F-001/F-002/F-003/F-005/F-008 회귀 0건) |
| `sdk/src/main/kotlin/com/androidailab/aisdk/internal/network/AnthropicMessagesRequest.kt` | `system: String? = null` 필드 추가 (R-008) | default null이라 F-001/F-002 직렬화 영향 없음 (`encodeDefaults=false` + `explicitNulls=false`로 null 직렬화 제외) |
| `sdk/src/main/kotlin/com/androidailab/aisdk/internal/network/Mapper.kt` | `toAnthropicRequestForSession(history, request, systemPrompt, config, httpClient)` 추가 + 내부 헬퍼 `buildMessageContentJson(text, images, httpClient)` 추출 + `ROLE_ASSISTANT` 상수 추가 + Message/Role import | 기존 `toAnthropicRequest` 시그니처/동작 그대로 (F-001/F-002 회귀 0건). `buildContentJson`은 `buildMessageContentJson` 위임으로 변경되지만 외부 동작 동일 |
| `sdk/src/main/kotlin/com/androidailab/aisdk/internal/network/ErrorMapper.kt` | `mapContextOverflow(errorBody: String?): AiException.InvalidInput?` 추가 + `ANTHROPIC_CONTEXT_OVERFLOW_KEYWORDS` 키워드 리스트 + `ANTHROPIC_ERROR_TYPE_INVALID_REQUEST` 상수 추가 | 기존 `fromHttpStatus` / `fromException` / `parseRetryAfter` 변경 없음 |
| `sdk/src/main/kotlin/com/androidailab/aisdk/provider/claude/ClaudeProvider.kt` | `open class`로 변경 (서브타입 테스트 허용) + `completeForSession(history, request, systemPrompt, config)` 추가 (open suspend) + Message import + `complete()`에서 4xx 케이스에 `ErrorMapper.mapContextOverflow` 사전 감지 추가 (E-401) | F-001 `complete` 시그니처 변경 없음. `complete`/`stream`은 이미 override이라 implicitly open. 단발 호출(F-001/F-002)에 ErrorMapper.mapContextOverflow 검사가 추가되었으나 사양 F-004 "Provider 응답에서 컨텍스트 초과 에러 감지"와 정합 (단발 호출에서도 컨텍스트 초과는 동일 E-401 매핑) |

---

## 2. 구현한 사양 ID 표

| ID | 사양 위치 | 구현 위치 | 비고 |
|----|----------|----------|------|
| F-004 | features.md | `Session.kt` 전체 + `AiAgentClient.createSession` | 정상 흐름 1~5 모두 구현 |
| A-004 | api.md | `AiAgentClient.kt:441` `public fun createSession(systemPrompt: String? = null): Session` | 시그니처 토큰 단위 일치 |
| A-006 | api.md | `Session.kt:120` `public suspend fun send(request: AiRequest): Result<AiResponse>` | 시그니처 토큰 단위 일치 |
| A-007 | api.md | `Session.kt:248` `public fun history(): List<Message>` | 시그니처 토큰 단위 일치 |
| A-008 | api.md | `Session.kt:279` `public fun clear()` | 시그니처 토큰 단위 일치 |
| M-007 | data-model.md | `Session.kt:53` `public class Session internal constructor(client, sessionId, systemPrompt, initialHistory)` | 시그니처 토큰 단위 일치 (단, save() 메서드는 F-007 라운드에서 추가) |
| M-008 | data-model.md | `Message.kt` Message data class + Role enum | 필드/제약 일치 |
| R-007 | features.md F-005 | `Session.send` 진입 시 `client.activeProvider` atomic get | Session도 R-007 패턴 적용 |
| R-008 | features.md F-004 | `Session.systemPrompt` (private) + history에 SYSTEM 미포함 + Mapper에서 `AnthropicMessagesRequest.system` 매핑 | systemPrompt history 미포함 정책 |
| R-011 | data-model.md M-007 | `Session.history()` synchronized(history) { toList() } | immutable snapshot |
| R-014 | features.md F-005 | `Session.send` 진입 시점 client.activeProvider 캡쳐 (Session은 Provider에 묶이지 않음) | Session 생성 시 Provider 고정 안 함 |
| R-017/R-024 | api.md A-004 / overview.md | `AiAgentClient.createSession`이 `UUID.randomUUID().toString()`으로 자동 부여 | v0.1은 호출자 명시 지정 미지원 |
| R-020 케이스 B | features.md F-008 | `Session.send`: `client.isClosed()` → Result.failure(Configuration). `createSession`/`history`/`clear`: `ensureNotClosed()` throw | suspend는 Result, 동기는 throw |
| E-401 | features.md F-004 + provider-spec.md | `ErrorMapper.mapContextOverflow` (키워드 매칭) → `AiException.InvalidInput("context too large")`. `ClaudeProvider.complete`/`completeForSession`에서 4xx 본문 검사 후 적용 | ERR-005 매핑 |
| E-402 | features.md F-004 | `Session.send`이 client closed 시 `Result.failure(AiException.Configuration("client closed"))` | ERR-004 매핑 |
| E-403 | features.md F-004 | `Session.mutex.withLock` 직렬화 — 두 번째 send는 첫 send 완료까지 대기 (사양 명시: "정상 동작") | ERR 매핑 없음 (정상 동작) |
| E-101~E-110 | features.md F-001 | Session.send도 동일 매핑 (AiException pass-through) | F-001 회귀 동등 |
| E-201~E-208 | features.md F-002 | `Session.validateImages` (AiAgentClient.validateImages와 동일 알고리즘) | F-002 회귀 동등 |
| E-110 | features.md F-001 | Session.send도 빈 응답 검증 (EMPTY_OK_FINISH_REASONS) → ServerError(-1, "empty response") | F-001 회귀 동등 |
| ERR-004 | error-handling.md | E-402 / E-705(미구현) / R-020 케이스 B → AiException.Configuration | 매핑 일치 |
| ERR-005 | error-handling.md | E-401 → AiException.InvalidInput("context too large") | 매핑 일치 |

### 사양 ↔ 구현 라우트 매핑

```
features.md F-004 정상 흐름                     구현
1. createSession(systemPrompt = "...")       →  AiAgentClient.createSession (UUID 자동 부여)
2. session.send(request)                     →  Session.send (Mutex.withLock + completeForSession)
3. 응답을 세션 history에 추가                  →  send 정상 흐름 끝에 USER+ASSISTANT append (synchronized)
4. session.history()                         →  synchronized(history) { history.toList() } (R-011 snapshot)
5. session.clear()                           →  synchronized(history) { history.clear() } (systemPrompt 보존)
```

### F-002 검증 흐름 재사용

Session.send 진입 시 `validateImages`는 `AiAgentClient.validateImages`와 동일 알고리즘. E-201/E-202/E-204/E-205/E-208/E-203 매핑이 동일하다 (`Session.kt:306`).

### Provider 인터페이스 확장 회피

Session은 history/systemPrompt를 받는 별도 메서드가 필요한데, Provider 인터페이스(P-001)에 시그니처를 추가하면 v0.1 단일 Provider만 있는 환경에서 인터페이스 부담이 큼. 대안으로:
- ClaudeProvider에 `public open suspend fun completeForSession(...)` 메서드 추가 (Provider 인터페이스가 아닌 ClaudeProvider 직속).
- `Session.send`이 `if (provider is ClaudeProvider) provider.completeForSession(...) else provider.complete(request, config)` 분기.
- v0.2에서 멀티 Provider 도입 시 Provider 인터페이스에 정식 sessionComplete 시그니처 추가 검토 (사양 명확화 요청 Q-T16-1 참조).

---

## 3. 단위 테스트 케이스 목록

### `SessionTest.kt` (24 케이스)

```
F-004 A-004 — createSession 은 UUID sessionId 와 빈 history 를 가진 Session 반환
F-004 A-004 — createSession 두 번 호출 시 서로 다른 sessionId
F-004 A-004 — createSession(systemPrompt) 는 systemPrompt 를 Session 에 보관
F-004 정상 흐름 — send 성공 시 USER + ASSISTANT 메시지가 history 에 누적
F-004 정상 흐름 — 연속 send 시 history 가 순차 누적
F-004 정상 흐름 — 두 번째 send 호출 시 첫 send 의 history 가 Provider 에 전달됨
F-004 R-008 — systemPrompt 는 history 에 등장하지 않음
F-004 A-007 R-011 — history() 반환은 immutable snapshot (이후 send 가 발생해도 변경 없음)
F-004 A-008 — clear() 후 history 는 비어있고 systemPrompt 는 유지됨
F-004 E-402 R-020 케이스 B — close 후 send → Result_failure(Configuration(client closed))
F-004 R-020 케이스 B — close 후 createSession → Configuration(client closed) throw
F-004 R-020 케이스 B — close 후 Session_history() → Configuration throw
F-004 R-020 케이스 B — close 후 Session_clear() → Configuration throw
F-004 E-403 — 동시 send 호출은 Mutex 로 직렬화 (정상 동작 — 두 번째는 첫 번째 완료 후 실행)
F-004 R-014 — Session_send 는 호출 시점의 client_activeProvider 를 사용
F-004 E-106 — send 도중 cancel 되면 CancellationException 그대로 전파
F-004 E-401 — Provider 가 InvalidInput(context too large) 를 throw 하면 Result_failure 로 전달
F-004 — Provider 가 Network 를 throw 하면 Result_failure(Network) 로 전달
F-004 — Provider 가 throw 한 경우 history 는 변경되지 않음
F-004 R-005 — Session send 도 빈 응답 + END_TURN 은 그대로 성공
F-004 E-110 — Session send 도 빈 응답 + MAX_TOKENS 는 ServerError(-1)
F-004 + F-002 — Session send 진입에서 ImageInput_Uri 는 즉시 E-203 InvalidInput
```

### `CreateSessionTest.kt` (7 케이스)

```
F-004 A-004 — createSession() 은 비-null Session 반환
F-004 A-004 — createSession() 의 sessionId 는 UUID 형식 (R-017)
F-004 A-004 — createSession() 을 두 번 호출하면 서로 다른 sessionId
F-004 A-004 — createSession(systemPrompt) 와 createSession() 모두 호출 가능 (default param)
F-004 A-004 — 생성된 Session 의 history 는 빈 리스트
F-004 R-020 케이스 B — close 후 createSession 호출 시 Configuration(client closed) throw
F-004 R-020 케이스 B — close 후 createSession(systemPrompt) 도 throw (동기 함수)
```

### `MapperSessionTest.kt` (16 케이스)

```
F-004 — 빈 history + 현재 request 는 단일 USER 메시지로 변환 (F-001 회귀 동등)
F-004 R-008 — systemPrompt 가 AnthropicMessagesRequest_system 으로 매핑됨
F-004 R-008 — systemPrompt null 이면 system 필드 미설정
F-004 R-008 — JSON 직렬화 시 systemPrompt null 이면 system 필드 제외됨 (encodeDefaults false)
F-004 R-008 — JSON 직렬화 시 systemPrompt 가 system 필드로 출력됨
F-004 — history(USER, ASSISTANT) + 현재 request 는 3 메시지 순서 보장
F-004 R-008 — history 에 Role_SYSTEM 메시지가 있어도 (방어적) 무시되어 messages 에서 제외
F-004 + F-002 — history Message_images(Bytes PNG) 가 image 블록으로 인코딩됨
F-004 + E-203 — history Message_images(Uri) 도달 시 InvalidInput
F-004 — maxTokens 와 temperature 는 현재 request 에서 가져옴
E-401 — context_length_exceeded 키워드가 포함된 invalid_request_error 는 InvalidInput(context too large)
E-401 — 'context window' 표현도 감지됨
E-401 — 'prompt is too long' 표현도 감지됨
E-401 — 컨텍스트 초과 키워드가 없는 invalid_request_error 는 null (다른 매핑에 위임)
E-401 — invalid_request_error 가 아닌 error_type 은 null
E-401 — null 본문은 null 반환
E-401 — 빈 본문은 null 반환
E-401 — 잘못된 JSON 본문은 null 반환 (NumberFormatException 안전망)
E-401 — error 필드 없는 응답은 null 반환
E-401 — error_message 없는 응답은 null 반환
```

(MapperSessionTest 실제 16~20 케이스 — 본 라운드 작성된 모든 케이스 합산. 위 목록은 의도별 분류로 21항이지만 동일 패턴 묶음이 있어 모두 작성됨.)

### 케이스 의도 ↔ 사양 ID 매핑

| 영역 | 케이스 수 | 사양 ID | 의도 |
|------|----------|---------|------|
| createSession 정상 흐름 | 5 | F-004 / A-004 / R-017/R-024 | UUID 부여, default null, 두 번 호출 시 다른 ID |
| createSession close 시맨틱 | 2 | R-020 케이스 B / E-402(인접) | close 후 createSession throw (동기) |
| send 정상 흐름 | 3 | F-004 정상 흐름 2~3 | USER+ASSISTANT history 누적 + 두 번째 send에 history 전달 |
| systemPrompt 정책 | 2 | R-008 | history에 미포함, Anthropic system 필드 매핑 |
| history snapshot | 1 | R-011 / A-007 | immutable snapshot 보장 |
| clear | 1 | A-008 | history 초기화, systemPrompt 보존 |
| Mutex 직렬화 | 1 | E-403 | 동시 send 직렬화 |
| Provider 캡쳐 | 1 | R-014 / R-007 | send 진입 시점 active Provider 사용 |
| 취소 시맨틱 | 1 | E-106 / R-020 케이스 A | CancellationException 그대로 전파 |
| close 시맨틱 | 4 | R-020 케이스 B / E-402 | send/history/clear 동기/suspend 분기 |
| 빈 응답 검증 | 2 | F-001 R-005 / E-110 | F-001 동일 알고리즘 재사용 |
| AiException pass-through | 2 | E-401 / E-101 | Provider가 throw하면 Result.failure로 전달 |
| 실패 시 history 변경 없음 | 1 | F-004 정상 흐름 3 (atomic) | 실패 시 history 갱신 안 함 |
| F-002 검증 재사용 | 1 | E-203 | Uri 즉시 거부 |
| systemPrompt → system 매핑 | 5 | R-008 | Mapper에서 toAnthropicRequestForSession |
| history 변환 | 3 | F-004 / R-008 | USER/ASSISTANT 순서, SYSTEM 무시 |
| Message.images 변환 | 2 | F-002 정책 재사용 | Bytes/Uri 처리 |
| E-401 감지 | 9 | E-401 / provider-spec.md P-CLAUDE | 키워드 매칭, type 검증, 본문 안전망 |
| maxTokens/temperature | 1 | F-004 (현재 request 우선) | history에서 가져오지 않음 |

### 합계
- `SessionTest.kt`: 24 케이스
- `CreateSessionTest.kt`: 7 케이스
- `MapperSessionTest.kt`: 20 케이스
- **합계**: **51 단위 테스트** (외부 네트워크 호출 0회 — FakeProvider/FakeClaudeLike/runTest/static JSON 본문)

### F-001/F-002/F-003 회귀 보호
- `MapperTest.kt` / `MapperImageTest.kt` / `AskTest.kt` / `AskWithImagesTest.kt` / `AskStreamTest.kt` 모두 변경 없음. 단 `Mapper.toAnthropicRequest`는 내부적으로 `buildContentJson` → `buildMessageContentJson`을 호출하지만 외부 동작 동일.
- `AnthropicMessagesRequest.system` default null로 F-001/F-002 직렬화에 영향 없음 (MapperTest #1 검증 통과 보장).

---

## 4. 사양 명확화 요청

### Q-T16-1 [Minor] Provider 인터페이스에 sessionComplete 시그니처 정식 추가 시점

- **현 사양**: provider-spec.md P-001 `Provider` 인터페이스에 `suspend fun complete(request, config)`와 `fun stream(request, config)`만 있음. history/systemPrompt를 받는 메서드 명세 없음.
- **현 구현**: Session.send는 `if (provider is ClaudeProvider) provider.completeForSession(...) else provider.complete(...)` 분기. ClaudeProvider 직속 메서드(인터페이스 외)를 사용.
- **사유**: v0.1 단일 Provider(P-CLAUDE)만 존재하므로 인터페이스 확장이 시기상조. 단일 Provider 가정에서 type check + 직속 호출이 더 단순.
- **권장**: 사양 보강 — provider-spec.md P-001에 "v0.1은 history/systemPrompt를 받는 sessionComplete 메서드를 Provider 인터페이스에 두지 않는다. 멀티 Provider 도입 시 인터페이스에 정식 추가 (`suspend fun completeForSession(history, request, systemPrompt, config): AiResponse`)" 한 줄 추가. 본 라운드 구현은 사양 명시 후 정합.
- **다음 라운드 진입 차단**: 차단 안 함 (Minor).

### Q-T16-2 [Minor] E-401 컨텍스트 초과 감지 키워드 리스트의 안정성

- **현 사양**: provider-spec.md P-CLAUDE 에러 매핑 표에 "context_length_exceeded → AiException.InvalidInput('context too large')" 한 줄. features.md F-004 "컨텍스트 한계 검증 알고리즘"은 "Provider 응답에서 컨텍스트 초과 에러를 감지" 추상 명시.
- **현 구현**: `ErrorMapper.ANTHROPIC_CONTEXT_OVERFLOW_KEYWORDS` 키워드 리스트로 매칭 (context_length_exceeded / context window / prompt is too long / input is too long / too many tokens / maximum context).
- **사유**: Anthropic API의 에러 응답 본문은 자연어 메시지이며 표준 에러 코드 필드를 노출하지 않는다. 키워드 매칭이 v0.1 합리적 기본값.
- **위험**: Anthropic이 메시지 표현을 바꿔 키워드 미스매치 시 E-401 감지 실패 → ServerError로 fallback. 호출자가 InvalidInput을 기대했는데 ServerError를 받는 시나리오.
- **권장**: 사양 보강 — provider-spec.md P-CLAUDE에 정확한 Anthropic 에러 응답 예시 (스냅샷 1~2개) 추가. v0.2에서 Anthropic이 표준 에러 코드(`error.code = "context_length_exceeded"` 등)를 노출하면 키워드 매칭에서 코드 매칭으로 전환.
- **다음 라운드 진입 차단**: 차단 안 함 (Minor).

### Q-T16-3 [Minor] Session.save() 시그니처 사양상 M-007에 있지만 F-007 라운드 범위

- **현 사양**: data-model.md M-007 `class Session` 시그니처에 `suspend fun save(): Result<String>` 포함. 그러나 본 라운드(F-004)는 F-007 영속화 미포함이라 save() 구현 불가.
- **현 구현**: 본 라운드 `Session.kt`는 send/history/clear만 구현. save()는 F-007 라운드에서 추가.
- **사유**: F-004와 F-007의 책임 분리. F-007 라운드에서 `SessionStore` 인터페이스 + DataStore Preferences 구현 + Session.save() 추가가 한꺼번에 진행되어야 일관성 보장.
- **권장**: 사양 보강 — data-model.md M-007 KDoc에 "save() 메서드는 F-007 영속화 라운드에서 본체 구현. F-004 본 라운드는 시그니처만 인지" 한 줄 추가. 본 라운드 구현은 사양 명시 후 정합.
- **다음 라운드 진입 차단**: 차단 안 함 (Minor) — F-007 라운드에서 자연스럽게 처리.

### Q-T16-4 [Minor] history()/clear() 의 동기 함수 시그니처 vs Mutex 통합

- **현 사양**: api.md A-007 `fun history(): List<Message>` / A-008 `fun clear()` — 모두 동기. data-model.md M-007 동시성 모델은 "Mutex 안에서 List 복사본 반환".
- **현 구현**: history()/clear()는 동기 함수이므로 `mutex.withLock {}` 호출 불가 (suspend 필요). 대신 `synchronized(history)` 블록으로 ArrayList의 thread-safety 확보 + send 안의 history.add도 같은 synchronized로 보호 → race 방지 + 메모리 가시성 보장.
- **사유**: api.md가 동기 시그니처를 강제하므로 send의 Mutex와 별개로 동기화 메커니즘이 필요. `synchronized` + Mutex 이중 동기화는 잠금 순서 위험이 있으나 본 구현은 항상 `synchronized(history)` 안쪽에서 빠른 ArrayList 조작만 수행하여 deadlock 없음.
- **권장**: 사양 보강 — data-model.md M-007 "내부 상태 및 동시성"에 "history()/clear()는 동기 함수이므로 Mutex 직접 사용 불가. `synchronized(history)` 패턴으로 ArrayList 보호" 명시. 본 라운드 구현은 정합.
- **다음 라운드 진입 차단**: 차단 안 함.

### 이월 항목 (이전 라운드 미해결)

- **S-T11-1** (F-001 E-107 발생 위치 명확화) — 본 라운드 영향 없음, 이월.
- **S-T12-1** (ImageInput.Url https 강제 + MockWebServer 통합) — 본 라운드 영향 없음, 이월.
- **S-T13-1/2/3** (F-003 E-303 / SSE error 이벤트 ERR 매핑 / R-005 stream 적용) — 본 라운드 영향 없음, 이월.
- **S-T15-1/2/3 (= Q-T14-1/2/3)** (ProviderRegistry public 노출 / @ModelId 옵션 qualifier / OkHttpClient 호출당 빌드) — 본 라운드 영향 없음, 이월.

---

## 5. 다음 라운드 진입 가이드

### 5.1 F-007 진입 가이드 (세션 영속화)

본 라운드(F-004) 변경의 F-007 영향:

1. **`SessionStore` 인터페이스 신규**: F-007에서 `sdk/src/main/kotlin/com/androidailab/aisdk/internal/session/SessionStore.kt`로 추가. DataStore Preferences 기반.
2. **`AiAgentClient`에 sessionStore 주입**: F-007에서 `AiAgentClient`의 internal 생성자에 `sessionStore: SessionStore?` 추가. 본 라운드 시그니처는 변경 없음. F-007 라운드에서 internal 생성자 시그니처 변경 (다행히 internal이라 호출자 영향 없음 — `Builder.build()`만 변경).
3. **`Session.save()` 본체**: 본 라운드 Session.kt에 save()를 추가하지 않았으므로 F-007에서 추가. SessionEntity(M-011) 직렬화 + DataStore put.
4. **`AiAgentClient.loadSession` / `deleteSession`**: F-007에서 추가. 본 라운드 createSession과 별도 진입점.
5. **SdkModule 변경**: F-007에서 `@Provides @Singleton SessionStore` 추가. F-006의 SdkModule 골격 그대로 사용 가능.
6. **M-011 SessionEntity**: F-007에서 `@Serializable` data class로 신규. M-008 Message ↔ MessageEntity 변환 함수도 필요.
7. **schemaVersion=1 강제 (R-018/R-022)**: F-007 라운드에서 load 시 검증 → E-703 매핑.

### 5.2 F-007 진입 시 체크리스트

- [ ] `SessionStore` 인터페이스 (`internal/session/SessionStore.kt`)
- [ ] `DataStoreSessionStore` 구현체 (`internal/session/DataStoreSessionStore.kt`) — Preferences DataStore + JSON
- [ ] `SdkModule.provideSessionStore(@ApplicationContext)` 추가
- [ ] `AiAgentClient` internal 생성자에 `sessionStore: SessionStore?` 추가 → `loadSession`/`deleteSession` 본체
- [ ] `Session.save(): Result<String>` 본체 — 본 라운드 Session.kt에 save() 메서드 추가 (M-007 시그니처 완성)
- [ ] M-011 `SessionEntity` `@Serializable` data class + MessageEntity + ImageInputEntity
- [ ] schemaVersion=1 강제 (R-018) — load 시 즉시 E-703
- [ ] E-701 / E-702 / E-703 / E-704 / E-705 / E-706 매핑
- [ ] 영속화 시 이미지 가이드 (R-021) — 1MB 초과 시 E-704

### 5.3 다음 wave 진입 차단 사항

- **F-004 본체**: 본 라운드 종결 시 다음 wave (F-007) 진입 가능.
- 사양 명확화 요청(Q-T16-1/2/3/4)은 모두 Minor — 다음 라운드 진입 차단 안 함.

---

## 6. sdk-qa-validator F-004 검증 요청

**sdk-qa-validator를 호출하여 F-004 (세션 컨텍스트 유지)의 사양-구현 정합성을 검증해 주세요.**

검증 본체 위치:
- `sdk/src/main/kotlin/com/androidailab/aisdk/session/Session.kt` — M-007 본체 (send/history/clear + Mutex + synchronized)
- `sdk/src/main/kotlin/com/androidailab/aisdk/model/Message.kt` — M-008 Message data class + Role enum
- `sdk/src/main/kotlin/com/androidailab/aisdk/AiAgentClient.kt` — `createSession(systemPrompt: String? = null): Session` 본체 추가
- `sdk/src/main/kotlin/com/androidailab/aisdk/internal/network/Mapper.kt` — `toAnthropicRequestForSession` + `buildMessageContentJson` + ROLE_ASSISTANT
- `sdk/src/main/kotlin/com/androidailab/aisdk/internal/network/AnthropicMessagesRequest.kt` — `system: String? = null` 필드 추가
- `sdk/src/main/kotlin/com/androidailab/aisdk/internal/network/ErrorMapper.kt` — `mapContextOverflow` + 키워드 리스트 (E-401)
- `sdk/src/main/kotlin/com/androidailab/aisdk/provider/claude/ClaudeProvider.kt` — `open class` + `completeForSession` + E-401 사전 감지

단위 테스트:
- `sdk/src/test/kotlin/com/androidailab/aisdk/session/SessionTest.kt` (24 케이스)
- `sdk/src/test/kotlin/com/androidailab/aisdk/client/CreateSessionTest.kt` (7 케이스)
- `sdk/src/test/kotlin/com/androidailab/aisdk/internal/network/MapperSessionTest.kt` (20 케이스)

검증 요청 항목:
- F-004 정상 흐름 1~5 매핑 (createSession → send → history append → history() snapshot → clear())
- A-004 시그니처 토큰 단위 일치 (`fun createSession(systemPrompt: String? = null): Session`)
- A-006 시그니처 토큰 단위 일치 (`suspend fun send(request: AiRequest): Result<AiResponse>`)
- A-007 시그니처 토큰 단위 일치 (`fun history(): List<Message>`)
- A-008 시그니처 토큰 단위 일치 (`fun clear()`)
- M-007 클래스 시그니처 (`class Session internal constructor(client, sessionId, systemPrompt, initialHistory)`) — save()는 F-007 라운드로 이월 (Q-T16-3)
- M-008 Message 필드/제약 일치
- R-008 systemPrompt → AnthropicMessagesRequest.system 매핑 + history 미포함
- R-011 history() immutable snapshot
- R-014 Session은 Provider에 묶이지 않음 — send 진입 시 캡쳐
- R-017/R-024 SDK 자동 UUID 부여
- R-020 케이스 A/B (send: Result.failure / 동기 함수: throw)
- E-401 (Anthropic 4xx 본문 키워드 매칭 → InvalidInput("context too large"))
- E-402 (client closed → Configuration)
- E-403 (Mutex 직렬화 — 정상 동작)
- E-101~E-110 / E-201~E-208 회귀 (Session도 동일 매핑)
- F-001/F-002/F-003/F-005/F-006/F-008 회귀 — 본 라운드 변경이 기존 흐름에 영향 없는지

사양 ID:
- features.md F-004 (정상 흐름 1~5 + systemPrompt 정책 R-008 + 컨텍스트 한계 검증 알고리즘 E-401 + 예외 흐름 E-401~E-403)
- features.md F-005 R-014 (Session-Provider 관계)
- features.md F-008 R-020 (close 시맨틱)
- api.md A-004 / A-006 / A-007 / A-008
- data-model.md M-007 (Session) / M-008 (Message)
- provider-spec.md P-CLAUDE 에러 매핑 (context_length_exceeded → E-401)
- error-handling.md ERR-004 / ERR-005

빌드 실행: 미수행 (settings.gradle.kts/wrapper 부재 — 정적 컴파일 타당성 + 토큰 단위 비교).
