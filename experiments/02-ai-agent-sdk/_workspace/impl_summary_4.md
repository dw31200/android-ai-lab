# 구현 요약 #4 — F-001 (텍스트 단발 질의) 마무리

본 라운드(T11): F-001 본체 구현은 직전 라운드에서 완료, 본 라운드에서는 미완 단위 테스트 3종을 추가하여 마무리.

## 1. 산출물 목록

### 1-A. 본체 구현 (직전 라운드 산출, 본 라운드에서는 수정하지 않음)

| 파일 | 책임 |
|------|------|
| `sdk/src/main/kotlin/com/androidailab/aisdk/AiAgentClient.kt` | `ask()` 본체 (R-007/R-020 케이스 A·B/D-005/R-005 모두 반영) |
| `sdk/src/main/kotlin/com/androidailab/aisdk/provider/claude/ClaudeProvider.kt` | `complete()` 본체 (HTTP 호출 + 매핑). `stream()` 만 NotImplementedError로 F-003 보류 |
| `sdk/src/main/kotlin/com/androidailab/aisdk/internal/network/AnthropicHttpClient.kt` | OkHttp 래퍼, 코루틴 취소 cooperative |
| `sdk/src/main/kotlin/com/androidailab/aisdk/internal/network/AnthropicJson.kt` | kotlinx.serialization 인스턴스 (ignoreUnknownKeys) |
| `sdk/src/main/kotlin/com/androidailab/aisdk/internal/network/AnthropicMessagesRequest.kt` | 요청 직렬화 모델 |
| `sdk/src/main/kotlin/com/androidailab/aisdk/internal/network/AnthropicMessagesResponse.kt` | 응답 역직렬화 모델 |
| `sdk/src/main/kotlin/com/androidailab/aisdk/internal/network/ErrorMapper.kt` | HTTP/IO 예외 → AiException 매핑 |
| `sdk/src/main/kotlin/com/androidailab/aisdk/internal/network/Mapper.kt` | SDK 모델 ↔ Anthropic 모델 변환 |
| `sdk/src/main/kotlin/com/androidailab/aisdk/model/AiRequest.kt` | M-001 |
| `sdk/src/main/kotlin/com/androidailab/aisdk/model/AiResponse.kt` | M-002 + FinishReason |
| `sdk/src/main/kotlin/com/androidailab/aisdk/model/TokenUsage.kt` | M-009 |
| `sdk/src/main/kotlin/com/androidailab/aisdk/model/AiStreamEvent.kt` | M-006 (F-003에서 사용) |
| `sdk/src/main/kotlin/com/androidailab/aisdk/model/ImageInput.kt` | M-003 (F-002에서 사용) |
| `sdk/src/main/kotlin/com/androidailab/aisdk/model/VideoInput.kt` | M-004 (v0.1 unused) |

### 1-B. 본 라운드 신규 산출물 — 단위 테스트 3종

| 파일 | 케이스 수 |
|------|-----------|
| `sdk/src/test/kotlin/com/androidailab/aisdk/client/AskTest.kt` | 14 |
| `sdk/src/test/kotlin/com/androidailab/aisdk/internal/network/ErrorMapperTest.kt` | 12 |
| `sdk/src/test/kotlin/com/androidailab/aisdk/internal/network/MapperTest.kt` | 12 |

## 2. 구현한 사양 ID 표

| ID | 사양 위치 | 구현 위치 | 비고 |
|----|----------|----------|------|
| F-001 | features.md | `AiAgentClient.ask` | 정상/예외 흐름 5단계 + R-005 빈 응답 검증 |
| A-002 | api.md | `AiAgentClient.ask` | suspend, Result<AiResponse> |
| M-001 | data-model.md | `AiRequest` | init 검증 포함 |
| M-002 | data-model.md | `AiResponse` + `FinishReason` | |
| M-009 | data-model.md | `TokenUsage` | |
| M-010 | data-model.md | `ProviderId` | F-005 라운드 도입, 본 라운드 사용 |
| E-101 | features.md F-001 | `ErrorMapper.fromException` | IOException → Network |
| E-102 | features.md F-001 | `ErrorMapper.fromHttpStatus` | 401 → Authentication |
| E-103 | features.md F-001 | `ErrorMapper.fromHttpStatus` | 429 → RateLimit (Retry-After 파싱) |
| E-104 | features.md F-001 | `ErrorMapper.fromHttpStatus` | 5xx → ServerError(code) |
| E-105 | features.md F-001 | `ErrorMapper.fromException` + `ClaudeProvider.complete` | SerializationException → ServerError(-1) |
| E-106 | features.md F-001 | `AiAgentClient.ask` (catch CancellationException → throw) | |
| E-107 | features.md F-001 | `AiRequest.init` (require) → IllegalArgumentException → ask catch → InvalidInput | |
| E-108 | features.md F-001 | `ErrorMapper.fromException` | SocketTimeoutException → Network |
| E-109 | features.md F-001 | `AiAgentClient.ask` 진입 ensureNotClosed → Configuration("client closed") | R-020 케이스 B |
| E-110 | features.md F-001 정상 흐름 4단계 | `AiAgentClient.ask` (EMPTY_OK_FINISH_REASONS 검사) | code=-1, "empty response" |
| ERR-001 | error-handling.md | `AiException.Network` | |
| ERR-002 | error-handling.md | `AiException.RateLimit` | |
| ERR-003 | error-handling.md | `AiException.Authentication` | |
| ERR-004 | error-handling.md | `AiException.Configuration` | |
| ERR-005 | error-handling.md | `AiException.InvalidInput` | |
| ERR-006 | error-handling.md | `AiException.ServerError` | |
| R-005 | features.md F-001 | `AiAgentClient.EMPTY_OK_FINISH_REASONS` | END_TURN/STOP_SEQUENCE 만 빈 응답 OK |
| R-007 | features.md F-005 | `AiAgentClient.ask` 진입 시 activeProvider 지역 캡쳐 | AtomicReference 기반 |
| R-020 케이스 A | features.md F-008 | scope.cancel() → CancellationException 그대로 전파 | |
| R-020 케이스 B | features.md F-008 | `AiAgentClient.ask` 진입 ensureNotClosed → Result.failure | |
| D-005 | features.md | `AiAgentClient.ask` 단일 호출 (재시도 없음) | 호출자 책임 |

## 3. 단위 테스트 케이스 목록

### AskTest.kt (14개)

```
F-001 정상 흐름 — Provider가 정상 응답을 반환하면 Result_success
F-001 정상 흐름 — config 가 currentProviderConfig 와 같은 값으로 Provider 에 전달됨
F-001 R-005 — 빈 텍스트 + END_TURN 이면 그대로 Result_success
F-001 R-005 — 빈 텍스트 + STOP_SEQUENCE 이면 그대로 Result_success
F-001 E-110 — 빈 텍스트 + MAX_TOKENS 이면 ServerError(-1, empty response)
F-001 E-110 — 빈 텍스트 + OTHER 이면 ServerError(-1, empty response)
F-001 R-020 케이스 B — close 후 ask 호출 시 Result_failure(Configuration(client closed))
F-001 R-007 — ask 진행 중 useProvider 호출은 현재 ask 의 Provider 에 영향 없음
F-001 E-106 — ask 도중 cancel 되면 CancellationException 그대로 전파 (Result 로 감싸지 않음)
F-001 — Provider 가 Network 를 throw 하면 Result_failure(Network) 그대로 전달
F-001 — Provider 가 Authentication 를 throw 하면 Result_failure(Authentication)
F-001 D-005 — Provider 가 RateLimit 를 throw 해도 자동 재시도 없이 즉시 Result_failure
F-001 — Provider 가 IllegalArgumentException 을 throw 하면 InvalidInput 으로 매핑
F-001 — Provider 가 예상치 못한 예외를 throw 하면 ServerError 로 매핑 (안전망)
```

### ErrorMapperTest.kt (12개)

```
E-102 — HTTP 401 은 Authentication 으로 매핑
E-103 — HTTP 429 는 RateLimit 으로 매핑되며 Retry-After 헤더가 초 단위로 파싱됨
E-103 — HTTP 429 + Retry-After 헤더 없음 이면 retryAfter 가 null
E-103 — HTTP 429 + Retry-After 헤더가 비숫자(HTTP-date 등) 이면 null (v0_1 정책)
E-104 — HTTP 500 은 ServerError 로 매핑 (code=500)
E-104 — HTTP 503 은 ServerError 로 매핑 (code=503)
4xx 기타(400) — ServerError 로 매핑 (사양 ERR-006 기본값)
2xx 응답은 null 반환 (정상 처리 진입)
E-108 — SocketTimeoutException 은 Network 로 매핑 (cause 보존)
E-101 — 일반 IOException 은 Network 로 매핑 (cause 보존)
E-105 — SerializationException 은 ServerError(code=-1) 로 매핑
예상치 못한 예외는 ServerError(code=-1) 로 매핑 (안전망)
```

### MapperTest.kt (12개)

```
M-001 — AiRequest 가 AnthropicMessagesRequest 로 정확히 매핑됨
M-001 — modelId 가 ProviderConfig 에서만 가져옴 (AiRequest 가 아닌 config 가 단일 출처)
M-002 — 단일 text 블록 응답이 AiResponse 로 매핑됨
M-002 — text 블록이 여러 개면 순서대로 결합됨
M-002 — 비 text 블록(tool_use 등) 은 무시되고 text 만 결합
M-002 — stop_reason 'end_turn' → FinishReason_END_TURN
M-002 — stop_reason 'max_tokens' → FinishReason_MAX_TOKENS
M-002 — stop_reason 'stop_sequence' → FinishReason_STOP_SEQUENCE
M-002 — stop_reason 'unknown' → FinishReason_OTHER
M-002 — stop_reason null → FinishReason_OTHER
M-009 — usage null 이면 TokenUsage(0, 0) 으로 매핑
M-002 — content 가 비어있으면 text 가 빈 문자열 (호출 측 E-110 검증)
```

## 4. 사양 명확화 요청

본 라운드에서 새로 발견된 사양 모호성은 없음. 사양에 따라 다음을 정책 결정으로 채웠음 (이전 라운드 `impl_summary_3.md`에 기록되었거나 코드 KDoc에 명시):

- ErrorMapper.fromHttpStatus의 4xx (401/429 제외) → ServerError(code, "http error: $code") 로 매핑.
  사양 features.md F-001 표에는 4xx generic 매핑이 명시되지 않으나 ERR 표상 ServerError(ERR-006) 또는 InvalidInput(ERR-005) 후보 중 보수적으로 ServerError 사용 (호출자가 code로 분기 가능).
  → **F-002(이미지) 진입 시 413(payload too large) 등을 InvalidInput으로 분기할지 사양 확정 필요.**
- Retry-After HTTP-date 형식은 v0.1에서 미지원(null 반환). RFC 7231 §7.1.3.
- Mapper.toAiResponse의 빈 content 배열 → text="" 그대로 통과 (E-110 검증은 호출 측 책임).

## 5. 후속 라운드 진입 가이드

### F-002 (이미지 입력)

- **ImageInput 정책 검증 위치**: `AiAgentClient.ask` 진입 직전 또는 `AiRequest.init`에 추가. R-001 (5MB 한계, mime 화이트리스트)는 `Capabilities`와 매칭 검증 필요.
- **Mapper에 이미지 블록 변환 추가 위치**: `Mapper.toAnthropicRequest` 내부의 `AnthropicMessage(role, content)` content를 단일 String → `List<ContentBlock>`으로 확장. 텍스트 블록 + base64 image 블록 조합.
  - `AnthropicMessage.content` 타입을 `String` → `JsonElement` 또는 sealed `ContentBlock` 다형 직렬화로 변경 필요. **이때 P-001 인터페이스는 변경 없이 SDK 모델만 확장한다 (Provider 추상화 유지).**
- **base64 인코딩 정책**: ImageInput.bytes가 ByteArray라면 `android.util.Base64.encodeToString(NO_WRAP)` 사용. 단위 테스트에서는 Robolectric 또는 java.util.Base64 fallback 검토.
- **에러 매핑**: E-201 (5MB 초과) → InvalidInput, E-202 (mime 미지원) → InvalidInput, E-203 (이미지 파싱 실패) → InvalidInput, E-205 (Provider 미지원) → Configuration.

### F-003 (스트리밍)

- **현재 상태**: `ClaudeProvider.stream()`는 NotImplementedError throw 중. Flow 빌더 안에서 throw 되므로 collect 시점에 발생.
- **SSE 파서 위치**: `internal/network/AnthropicSseParser.kt` 신규. OkHttp Response.body.source()를 한 줄씩 읽어 `data: {...}` JSON을 파싱.
- **본체 채울 위치**: `ClaudeProvider.stream()`의 `flow { ... }` 빌더 내부. POST /v1/messages + stream=true 헤더, Response 받은 뒤 파서로 이벤트 emit.
- **취소 처리**: `currentCoroutineContext().isActive` 체크 또는 `awaitClose { call.cancel() }` (Flow 빌더의 callbackFlow 사용 시). E-302 매핑.
- **방출 순서 (M-006)**: Delta 0+ → Done|Error 1.

### F-004 (세션)

- **Session.send vs ask 차이**: Session은 history(`List<Message>`)를 누적 후 `Mapper.toAnthropicRequest`에 전달. AiAgentClient.ask는 단발(history 없음).
- **Mutex 위치**: `Session` 클래스 내부. `send()`는 `mutex.withLock { ... }` 으로 동시 호출 직렬화 (R-013).
- **history 캡 (R-014/R-016)**: 토큰 또는 메시지 수 기준 trim. `ProviderConfig` 또는 별도 정책 객체에서 임계값.
- **AiAgentClient.useProvider 와의 관계 (R-014)**: Session은 Provider에 묶이지 않음. session.send() 진입 시점에 client.activeProvider를 atomic get.

### F-006 (Hilt)

- **@Provides 대상**:
  - `AiAgentClient` (singleton, Builder 우회 진입 경로)
  - `ProviderRegistry` (singleton, Set<@JvmSuppressWildcards Provider> 주입)
  - `Set<Provider>` 멀티바인딩 (`@IntoSet` ClaudeProvider, 추후 OpenAI 등)
  - `AnthropicHttpClient` (singleton, OkHttpClient 1개 공유)
  - `OkHttpClient` (singleton, baseTimeout 적용)
- **위치**: `sdk/src/main/kotlin/com/androidailab/aisdk/di/SdkModule.kt` 신규.

### F-007 (영속화)

- **SessionStore 위치**: `internal/storage/SessionStore.kt` 또는 `internal/persistence/`. DataStore Proto 사용.
- **internal/network 와의 관계**: 독립적. SessionStore는 디스크 IO만, network는 HTTP만. Session 클래스가 둘을 조합.
- **에러**: ERR-007 IOError 매핑 (E-701 디스크 가득, E-703 손상 데이터, E-705 client closed).
- **schemaVersion 정책**: M-007/M-008에 정의. 미지원 버전이면 IOError throw, 호출자가 deleteSession으로 복구.

## 6. sdk-qa-validator F-001 검증 요청

**sdk-qa-validator를 호출하여 F-001 (텍스트 단발 질의)의 사양-구현 정합성을 검증해 주세요. 본체는 `AiAgentClient.ask` / `ClaudeProvider.complete` / `internal/network/*` 8개 파일이며, 단위 테스트는 `AskTest.kt` / `ErrorMapperTest.kt` / `MapperTest.kt` 입니다.**
