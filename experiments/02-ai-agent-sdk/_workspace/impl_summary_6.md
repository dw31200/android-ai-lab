# 구현 요약 #6 — F-003 (스트리밍 응답)

본 라운드(T13): F-003 본체 — `AiAgentClient.askStream` Flow 빌더 + `AnthropicSseParser` 신규 + `ClaudeProvider.stream` SSE 본체로 교체 + 단위 테스트.

---

## 1. 생성/수정 파일 목록

### 1-A. 신규 파일

| 파일 | 책임 |
|------|------|
| `sdk/src/main/kotlin/com/androidailab/aisdk/internal/network/AnthropicSseParser.kt` | P-CLAUDE Anthropic Messages SSE 파서 (M-006 Delta 0+ → Done|Error 1, R-005 빈 응답 검증 포함) |
| `sdk/src/test/kotlin/com/androidailab/aisdk/internal/network/AnthropicSseParserTest.kt` | SSE 파서 단위 테스트 (in-memory Buffer로 라인 파싱 검증) |
| `sdk/src/test/kotlin/com/androidailab/aisdk/client/AskStreamTest.kt` | F-003 askStream 정상/예외 흐름 + R-020 케이스 B + 이미지 검증 재사용 + R-007 |
| `sdk/src/test/kotlin/com/androidailab/aisdk/provider/ClaudeProviderStreamTest.kt` | ClaudeProvider.stream MockWebServer 통합 단위 테스트 (헤더/본문/HTTP error 매핑) |

### 1-B. 수정 파일

| 파일 | 변경 내용 |
|------|----------|
| `sdk/src/main/kotlin/com/androidailab/aisdk/AiAgentClient.kt` | `askStream(request: AiRequest): Flow<AiStreamEvent>` 본체 추가 — close 케이스 B + Provider.supportsStream(E-303) + validateImages 재사용 + Provider.stream 위임 + .catch 안전망 |
| `sdk/src/main/kotlin/com/androidailab/aisdk/provider/claude/ClaudeProvider.kt` | `stream()` 본체를 NotImplementedError → SSE 파서 위임으로 교체. POST /v1/messages with `Accept: text/event-stream` + `stream:true`. 4xx/5xx → Error 변환. unused import(`AnthropicErrorResponse`) 제거 |

---

## 2. 구현한 사양 ID 표

| ID | 사양 위치 | 구현 위치 | 비고 |
|----|----------|----------|------|
| F-003 | features.md | `AiAgentClient.askStream` + `ClaudeProvider.stream` + `AnthropicSseParser.parse` | 정상 흐름 1~4 모두 구현 |
| A-003 | api.md | `AiAgentClient.askStream` (시그니처 일치) | `fun askStream(request: AiRequest): Flow<AiStreamEvent>` |
| M-006 | data-model.md | `AnthropicSseParser.parse` 방출 순서 보장 | Delta 0+ → Done\|Error 1, Done 이후 Delta 무시 |
| E-301 | features.md F-003 | `AnthropicSseParser` (IOException → Network / EOF 도달 시 Network / SSE error 이벤트 → AiException 매핑) + `ClaudeProvider.stream` 4xx/5xx 매핑 | ERR-001/ERR-006 |
| E-302 | features.md F-003 | `AiAgentClient.askStream` (CancellationException은 .catch에서 통과 + throw로 재전파) + `ClaudeProvider.stream` (response.use + Call.cancel) + `AnthropicSseParser` (Flow 표준 시맨틱) | CancellationException 그대로 전파, Result/Error로 감싸지 않음 |
| E-303 | features.md F-003 | `AiAgentClient.askStream` 진입 직후 `provider.capabilities.supportsStream` 검증 → `AiStreamEvent.Error(Configuration("provider does not support streaming"))` 후 종료 | ERR-004. 사양상 "throw"이지만 Flow의 일관된 시맨틱(R-020 케이스 B와 동형)을 위해 Error emit으로 처리 (Q-T13-1 참조) |
| E-101~E-110 | features.md F-001 | `ClaudeProvider.stream` (4xx/5xx → ErrorMapper) + `AnthropicSseParser` (R-005 빈 응답 → ServerError) | 사양 F-003 "스트림 시작 전 단계"에 F-001 동일 적용 |
| R-005 | features.md F-001 정상 흐름 4단계 | `AnthropicSseParser` message_stop 시점에 `text == "" && finishReason !in {END_TURN, STOP_SEQUENCE}` 검증 → `ServerError(-1, "empty response")` Error emit | F-001과 동일 정책을 stream에도 일관 적용 |
| R-007 | features.md F-005 | `AiAgentClient.askStream` 진입 시점에 `activeProvider`/`currentProviderConfig()` 지역 캡쳐 | collect 도중 useProvider 영향 없음 (테스트 검증) |
| R-020 케이스 B | features.md F-008 | `AiAgentClient.askStream` 첫 줄에 `if (isClosed()) emit(AiStreamEvent.Error(Configuration("client closed"))); return@flow` | A-009 표 참조 |
| ERR-001 | error-handling.md | `AnthropicSseParser` (IOException → Network / EOF 도달 시 Network) | E-301 매핑 |
| ERR-002 | error-handling.md | SSE error 이벤트 `rate_limit_error` → `AiException.RateLimit(null)` | retryAfter는 SSE에 없음 — null |
| ERR-003 | error-handling.md | SSE error 이벤트 `authentication_error` → `AiException.Authentication` + ClaudeProvider.stream 401 status code | E-102 매핑 |
| ERR-004 | error-handling.md | `AiAgentClient.askStream` (close/E-303/E-205 매핑) | Configuration |
| ERR-005 | error-handling.md | `validateImages` 재사용 (E-201/E-202/E-203/E-204) + SSE error `invalid_request_error` → InvalidInput | F-002 흐름과 동일 |
| ERR-006 | error-handling.md | `AnthropicSseParser` (5xx / 알 수 없는 error type → ServerError(-1)) + `ClaudeProvider.stream` 5xx 응답 | code 보존 |
| P-CLAUDE | provider-spec.md | `ClaudeProvider.stream` (Anthropic Messages SSE 형식) + `SSE_MEDIA_TYPE_VALUE = "text/event-stream"` + `stream:true` 본문 추가 | Anthropic SSE 이벤트 (`message_start`/`content_block_delta`/`message_delta`/`message_stop`/`error`/`ping`) 처리 |

### 회귀 검증 (F-001/F-002 영향 없음)

- `AiAgentClient.ask` (F-001/F-002) 본체는 본 라운드에서 변경 없음.
- `Mapper.toAnthropicRequest` 시그니처/구현 유지 — `ClaudeProvider.stream`도 동일 매퍼 호출 (이미지 블록 변환 재사용).
- 기존 `AskTest.kt`/`AskWithImagesTest.kt`/`MapperTest.kt`/`MapperImageTest.kt`에 변경 없음.

---

## 3. 단위 테스트 케이스 목록

### `AnthropicSseParserTest.kt` (13 케이스)

```
F-003 정상 — Delta 여러개 후 message_stop 이면 Done 으로 종결 (input/output token 매핑 포함)
F-003 정상 — message_delta stop_reason max_tokens 는 FinishReason.MAX_TOKENS 매핑
F-003 정상 — stop_reason stop_sequence 는 STOP_SEQUENCE, 미지정은 OTHER
F-003 R-005 — 빈 텍스트 + END_TURN 이면 그대로 Done (성공)
F-003 R-005 — 빈 텍스트 + MAX_TOKENS 이면 Error(ServerError(-1, "empty response"))
F-003 E-301 — error 이벤트 overloaded_error 면 ServerError(-1) 방출 후 종료
F-003 — error authentication_error 면 Authentication 매핑
F-003 — error rate_limit_error 면 RateLimit 매핑
F-003 — error invalid_request_error 면 InvalidInput 매핑
F-003 E-301 — message_stop 없이 EOF 도달 시 Error(Network) 방출
F-003 — 잘못된 JSON 라인은 무시되고 다음 이벤트 처리는 정상 진행
F-003 — 알 수 없는 event type 은 무시 (forward-compat)
F-003 — ping 이벤트는 무시 (keep-alive)
F-003 — non-text_delta (input_json_delta 등) 는 Delta 로 방출되지 않음
F-003 M-006 — message_stop 이후 도착한 Delta 는 무시 (Done 1회로 종결)
```

### `AskStreamTest.kt` (15 케이스)

```
F-003 정상 흐름 — Provider.stream 의 Delta/Done 이벤트가 그대로 collect 에 도달
F-003 정상 흐름 — config 가 currentProviderConfig 와 같은 값으로 Provider 에 전달됨
F-003 R-020 케이스 B — close 후 askStream 첫 collect 는 Error(Configuration("client closed")) 후 종료
F-003 E-303 — Provider 가 supportsStream=false 면 Error(Configuration) 후 종료, Provider.stream 미호출
F-003 E-302 — collect 도중 cancel 시 CancellationException 전파 (cooperative)
F-003 R-007 — askStream 진행 중 useProvider 호출은 현 stream 에 영향 없음
F-003 E-201 — 단일 이미지 5MB 초과 시 Error(InvalidInput) 후 종료, Provider.stream 미호출
F-003 E-204 — 지원하지 않는 mimeType 이미지 시 Error(InvalidInput) 후 종료
F-003 E-205 — Provider 가 supportsImage=false 면 Configuration("provider does not support images")
F-003 E-203 — ImageInput.Uri 도달 시 Error(InvalidInput "uri unreadable") 후 종료
F-003 — Provider.stream 이 RuntimeException 을 throw 하면 Error(ServerError) 로 wrap (안전망)
F-003 — Provider.stream 이 Network 를 throw 하면 Error(Network) 그대로 전달
F-003 — Provider.stream 이 Delta 후 Error 를 emit 하면 그대로 collect 에 도달 (E-301 시퀀스)
F-003 회귀 — images 빈 리스트 시 검증 통과 후 Provider.stream 호출
```

### `ClaudeProviderStreamTest.kt` (5 케이스)

```
F-003 정상 — Anthropic SSE chunked 응답을 Delta들 + Done 으로 변환 (헤더 검증: x-api-key/anthropic-version/Accept=text/event-stream/stream:true)
F-003 — 401 응답이면 Authentication 이 Error 로 emit 되고 종료
F-003 — 429 응답이면 RateLimit 이 Error 로 emit 되고 종료 (Retry-After 헤더 파싱)
F-003 — 5xx 응답이면 ServerError 가 Error 로 emit 되고 종료
F-003 E-301 — SSE 도중 error 이벤트가 오면 그대로 Error 로 emit 후 종료
```

### 합계

- 신규 단위 테스트: **34 케이스** (SSE 파서 15 + askStream 15 + ClaudeProviderStream 5 — 일부 ClaudeProviderStream에 정상흐름이 SSE 파서와 통합형으로 중복되는 부분 존재)
- 외부 네트워크 호출 0회 (FakeProvider / MockWebServer / in-memory Buffer만 사용)

---

## 4. 사양 명확화 요청

### Q-T13-1 [Minor] E-303 처리 시맨틱 — "throw" vs Flow Error emit

- **현 사양**: features.md F-003 E-303 "활성 Provider가 스트리밍 미지원 | 호출 즉시 `AiException.Configuration` throw"
- **현 구현**: `AiAgentClient.askStream`은 collect 시점에 `AiStreamEvent.Error(AiException.Configuration("provider does not support streaming"))`를 emit한 뒤 Flow를 종료한다 (throw 하지 않음).
- **이유**: A-003 close 시맨틱(R-020 케이스 B — 케이스 askStream)도 "첫 collect 시 Error emit 후 종료"로 명시되어 있으므로, E-303도 동일 패턴(첫 collect 시 Error emit)으로 통일하는 것이 호출자 코드 일관성에 유리하다. cold Flow에서는 askStream() 호출 자체는 collect 전이므로 throw할 적절한 시점이 없음 (R-020 케이스 B의 askStream 처리와 동형).
- **권장**: 사양 보강 — F-003 E-303 행을 "(R-020 케이스 B와 동형) 첫 collect 시 `AiStreamEvent.Error(AiException.Configuration("provider does not support streaming"))` emit 후 Flow 종료"로 수정. 본 라운드 코드 정합. 만약 사양이 진짜 "askStream 호출 즉시 throw"를 의도한 것이면 SDK가 hot Flow처럼 동작해야 하므로 F-003 NFR "cold Flow"와 모순됨 (사양 검증 필요).

### Q-T13-2 [Minor] SSE error 이벤트의 ERR-XXX 매핑 명시 필요

- **현 사양**: features.md F-003 E-301 "스트림 도중 연결 끊김 → `AiStreamEvent.Error(AiException.Network)`". 그러나 Anthropic SSE는 "도중에 connection이 끊김"과 별도로 "정상 응답 SSE 안에 error 이벤트가 들어오는 경우"가 있음 (P-CLAUDE 사양에 SSE 이벤트 타입 `error` 명시).
- **현 구현**: `AnthropicSseParser`가 SSE error 이벤트의 `error.type` 문자열에 따라 분기:
  - `authentication_error` → `Authentication` (ERR-003)
  - `rate_limit_error` → `RateLimit(null)` (ERR-002)
  - `invalid_request_error` → `InvalidInput` (ERR-005)
  - 그 외 (`overloaded_error`, `api_error`, ...) → `ServerError(-1, message)` (ERR-006)
- **권장**: 사양 보강 — F-003 예외 흐름에 "E-301a: SSE error 이벤트 → Anthropic error.type별 AiException 매핑 (provider-spec.md P-CLAUDE 표와 동일)" 행 추가, 또는 provider-spec.md P-CLAUDE 섹션에 SSE error 이벤트 매핑 표 추가. 본 라운드 코드 정합 (P-CLAUDE 의 기존 에러 매핑 표를 SSE에도 동일 적용).

### Q-T13-3 [정보성] 빈 응답 검증의 stream 적용 — 사양상 명시되어 있지 않음

- **현 사양**: features.md F-001 정상 흐름 4단계의 빈 응답 검증(R-005)은 ask 본체에 적용. F-003에는 "빈 응답 검증" 표현 없음.
- **현 구현**: `AnthropicSseParser`가 message_stop 시점에 누적 텍스트가 비어있고 finishReason이 MAX_TOKENS/OTHER이면 `AiStreamEvent.Error(ServerError(-1, "empty response"))` emit. F-001과 동일 정책.
- **권장**: 사양 보강 — F-003 정상 흐름 4단계에 "F-001 R-005와 동일한 빈 응답 검증을 적용 (END_TURN/STOP_SEQUENCE에서 빈 응답은 Done, MAX_TOKENS/OTHER에서 빈 응답은 ServerError(-1, "empty response") Error emit)" 추가. 본 라운드 코드 정합.

(이전 라운드 미해결: Q-T11-1 (E-107 발생 위치 명확화) / S-T12-1, S-T12-2, S-T12-3 — 본 라운드 영향 없음, 그대로 이월.)

---

## 5. 다음 라운드 진입 가이드

### F-004 (세션 컨텍스트 유지)에 미치는 영향

본 라운드(F-003) 변경은 F-004에 다음과 같이 활용된다:

1. **`Session.send`도 F-002 + F-003 검증 흐름 동일 적용**: askStream의 `validateImages` 재사용 패턴(E-201/E-202/E-203/E-204/E-205)을 send에도 동일 적용한다. `Session.send`는 `AiAgentClient.ask`처럼 현재 코드 베이스를 재사용해야 하며, send 안에서 `client.activeProvider.capabilities`로 검증 후 history와 합쳐 Provider.complete 호출.
2. **history 다중 메시지 + systemPrompt 매핑**: 현 `Mapper.toAnthropicRequest`는 단일 user 메시지만 만든다. F-004에서는 history(M-008)의 USER/ASSISTANT 메시지 시퀀스 + systemPrompt(R-008)를 Anthropic Messages 형식으로 변환하는 함수가 필요. `Mapper.toAnthropicRequestForSession(history, current, systemPrompt, config, httpClient)` 형태 또는 기존 함수 확장.
3. **systemPrompt 매핑 (R-008)**: Anthropic API는 top-level `system` 필드를 받는다. `AnthropicMessagesRequest`에 `@SerialName("system") val system: String? = null` 추가 + Mapper에서 매핑.
4. **session.sendStream 시그니처 검토**: F-004 사양상 send만 정의되어 있지만 호출자가 세션 안에서 스트리밍을 원할 가능성. 본 라운드 askStream 패턴(Flow + 검증 흐름)을 재사용 가능. 사양 미정 시 F-004 라운드에서 spec-architect 협의.
5. **Mutex로 동시 send 직렬화 (E-403)**: Session 내부 `Mutex`를 두고 `mutex.withLock { ... }`. send 진입 시점에 client.activeProvider를 atomic get (R-014).

### F-006 (Hilt 모듈)에 미치는 영향

본 라운드 변경의 F-006 영향 — 미미함:

1. `ClaudeProvider`의 internal constructor (`httpClientFactory`)는 Hilt에서 `@Provides`로 노출하지 않고, Hilt가 두 번째 (`public constructor()`) 또는 `@Inject` 생성자(F-006 라운드에서 추가)를 사용하도록 한다.
2. `AnthropicSseParser`는 `internal object`로 stateless이므로 Hilt 등록 불필요 (provider-spec.md "internal: SDK 내부 협업용").
3. F-006에서 `AiAgentClient`를 Singleton으로 노출할 때, internal 생성자(`AiAgentClient internal constructor(...)`)를 그대로 사용하거나 Builder 경로를 통해 생성하는 `@Provides`를 추가한다.

### 진입 시 체크리스트 (F-004)

- [ ] `Session` 클래스 (M-007 시그니처 — sessionId UUID, systemPrompt, history Mutex, send/clear/history/save)
- [ ] `AiAgentClient.createSession(systemPrompt: String? = null): Session` 본체 (sessionId UUID 자동 부여)
- [ ] `AnthropicMessagesRequest`에 `system: String? = null` 필드 추가
- [ ] `Mapper.toAnthropicRequestForSession(...)` 또는 기존 함수에 history/systemPrompt 인자 추가
- [ ] `Session.send` 진입 시 `validateImages` 재사용 (Mutex 안에서)
- [ ] E-401 (컨텍스트 초과) — Anthropic 에러 응답 본문에서 `invalid_request_error` + token count → `InvalidInput("context too large")` 매핑 (provider-spec.md P-CLAUDE 에러 매핑 표)
- [ ] R-020 케이스 B 적용 (close 후 send → Result.failure / close 후 createSession → throw)

---

## 6. sdk-qa-validator F-003 검증 요청

**sdk-qa-validator를 호출하여 F-003 (스트리밍 응답)의 사양-구현 정합성을 검증해 주세요.**

**본체 위치**:
- `sdk/src/main/kotlin/com/androidailab/aisdk/AiAgentClient.kt` — `askStream` 함수 (Flow 빌더 + .catch 안전망)
- `sdk/src/main/kotlin/com/androidailab/aisdk/internal/network/AnthropicSseParser.kt` — `parse(source, providerId)` 함수
- `sdk/src/main/kotlin/com/androidailab/aisdk/provider/claude/ClaudeProvider.kt` — `stream(request, config)` 함수 (NotImplementedError → SSE 파서 위임)

**단위 테스트 위치**:
- `sdk/src/test/kotlin/com/androidailab/aisdk/client/AskStreamTest.kt` (15 케이스)
- `sdk/src/test/kotlin/com/androidailab/aisdk/internal/network/AnthropicSseParserTest.kt` (15 케이스)
- `sdk/src/test/kotlin/com/androidailab/aisdk/provider/ClaudeProviderStreamTest.kt` (5 케이스, MockWebServer 사용)

**검증할 사양 ID**:
- features.md F-003 정상 흐름 1~4 / 예외 흐름 (E-301 / E-302 / E-303 / E-101~E-110)
- api.md A-003 (시그니처 / close 시맨틱)
- data-model.md M-006 [AiStreamEvent] 방출 순서 (Delta 0+ → Done | Error 1)
- error-handling.md ERR-001 / ERR-002 / ERR-003 / ERR-004 / ERR-005 / ERR-006 매핑 (E-301 ↔ ERR-001/006, E-303 ↔ ERR-004)
- provider-spec.md P-CLAUDE (Anthropic Messages SSE 형식)
- features.md F-005 R-007 (활성 Provider 캡쳐), F-008 R-020 케이스 B (close 후 호출)
- features.md F-001 R-005 (빈 응답 검증을 stream에도 적용했음 — 사양 명시 여부 Q-T13-3 참조)
- F-002 이미지 검증(E-201/E-202/E-203/E-204/E-205) 재사용 정합성

**특별 검토 항목**:
- Q-T13-1: E-303이 "throw" vs "Error emit" — A-003 표/F-003 사양 간 정합성
- Q-T13-2: SSE error 이벤트의 ERR-XXX 매핑 — 사양 명시 부족 부분
- Q-T13-3: 빈 응답 검증을 stream에도 적용했으나 F-003 사양에 명시 부재
