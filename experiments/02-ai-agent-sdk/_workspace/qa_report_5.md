# QA Report 5 — F-003 (스트리밍 응답) 점진 검증 + S-T12-2/3 사양 보강 정합

검증자: sdk-qa-validator
검증 일시: 2026-05-10
대상:
- F-003 본체 (T13, `_workspace/impl_summary_6.md`) — `AnthropicSseParser` 신규 + `AiAgentClient.askStream` 본체 + `ClaudeProvider.stream` SSE 위임 + 단위 테스트 35개 (SSE 파서 15 / askStream 15 / ClaudeProviderStream 5)
- S-T12-2 / S-T12-3 사양 보강 (`_workspace/spec_draft_6.md`) — features.md F-002 E-202/E-203/E-208 / data-model.md M-003 Uri / overview.md D-004 변경 정합 점검
- F-001 / F-002 회귀 점검 (T11/T12 통과 흐름이 본 라운드 변경에 영향 없음 검증)

검증 범위:
- A-003 시그니처 토큰 단위 / F-003 정상 흐름 1~4 / E-301~E-303 / E-101~E-110 stream 적용 / M-006 방출 순서 / R-005 stream 적용 / R-007 지역 캡쳐 / R-020 케이스 B / F-002 이미지 검증 재사용 (E-201/E-202/E-203/E-204/E-205) / P-CLAUDE Anthropic Messages SSE 형식
- ERR-001/002/003/004/005/006 매핑 / SSE error 이벤트별 분기
- 단위 테스트 35 케이스 (의미·의도·사양 ID 매핑·컴파일 가능성)
- spec_draft_6.md 반영 위치 (features.md L111~132 / data-model.md M-003 L84 / overview.md D-004 L72) 및 F-002 코드 (`validateImages` L449~453)와 새 E-208 정합

빌드 실행: 미수행 (settings.gradle.kts/wrapper 부재 — 정적 검증/토큰 단위 비교)

---

## 1. 요약

| Severity | 건수 | 종결 조건 |
|----------|------|-----------|
| Blocker  | 1    | 0건 필수 |
| Major    | 0    | 처리 또는 명시적 유보 |
| Minor    | 4    | 다음 라운드 이월 가능 |
| 사양 명확화 요청 | 4 (Q-T13-1/2/3 + S-T13-1) | spec-architect 회신 후 반영 |

**결과**: F-003 **조건부 통과 (Blocker 1건 해결 후 통과)**.
- 시그니처(A-003) / 정상 흐름 1~4 / M-006 방출 순서 / E-301/E-302/E-303 / R-005 stream 적용 / R-007 캡쳐 / R-020 케이스 B / F-002 이미지 검증 재사용은 모두 코드와 단위 테스트로 정합 확인.
- **Blocker Q-T13-A1**: `ClaudeProvider.kt` L217의 `AnthropicMessagesRequest.serializer()` 호출이 `import` 누락 — `AnthropicMessagesRequest`는 `com.androidailab.aisdk.internal.network` 패키지인데 `ClaudeProvider`는 `com.androidailab.aisdk.provider.claude` 패키지로 다른 패키지. 같은 모듈 internal 접근 가능하지만 fully-qualified name 또는 import가 필요. 본 상태로는 `stream()` 본체가 컴파일 실패하여 F-003 정상 흐름 자체가 동작하지 않는다. 본 import 1줄 추가만으로 해결되며, 다른 코드/테스트는 모두 정합. Blocker로 분류 (사양 정의된 흐름이 코드에서 동작하지 않음).
- F-001 회귀 없음: `AiAgentClient.ask` 본체와 `Mapper.toAnthropicRequest` / `Mapper.toAiResponse` / `validateImages` / `AnthropicMessagesRequest`(필드 변경 없음) / `ClaudeProvider.complete` 본체는 변경 없음. ClaudeProvider.stream의 본체 추가가 complete 본체에 영향을 주지 않음 (별도 메서드 + companion object 상수만 신규).
- F-002 회귀 없음: validateImages, ImageInput, Mapper.buildContentJson, MapperImageTest는 변경 없음. spec만 보강(코드 동작 그대로).
- S-T12-2 / S-T12-3 사양 보강은 코드 동작과 정합. E-208 신규 ID는 코드 메시지 패턴(`count=`)과 사양 셀 동기화 통과.

Minor 4건은 (a) E-303 처리 방식 사양 명시(throw vs Error emit, Q-T13-1), (b) SSE error 이벤트 ERR-XXX 매핑 사양 명시 부재(Q-T13-2), (c) R-005 stream 적용 사양 명시 부재(Q-T13-3), (d) `AnthropicSseParser` parse Flow 빌더 본체 끝 부분의 `catch (e: IOException)` 외부 블록(L209)이 사실상 도달 불가능한 dead branch 가능성(S-T13-1 — 정보성).

---

## 2. F-003 검증 표

### 2.1 A-003 시그니처 토큰 단위 비교

api.md A-003 L126-127 ↔ `AiAgentClient.kt:358`:

| 토큰 | 사양 | 구현 | 일치 |
|------|------|------|------|
| 함수명 | `askStream` | `askStream` | 통과 |
| 파라미터 | `request: AiRequest` | `request: AiRequest` | 통과 |
| 반환 | `Flow<AiStreamEvent>` | `Flow<AiStreamEvent>` | 통과 |
| suspend | (없음 — Flow는 non-suspend) | `public fun` (cold Flow 빌더 — collect가 시작되어야 동작) | 통과 |
| visibility | public | `public` | 통과 |

`Flow<AiStreamEvent>`는 `flow { ... }.catch { ... }` cold Flow 빌더 — F-003 NFR "cold Flow, collector가 느리면 자연스럽게 producer가 대기"와 정합.

### 2.2 F-003 정상 흐름 (features.md L153-157)

| 단계 | 사양 | 구현 위치 | 결과 |
|------|------|-----------|------|
| 1 | `client.askStream(AiRequest(...))` 호출 | `AiAgentClient.kt:358` (`public fun askStream(request: AiRequest): Flow<AiStreamEvent>`) | 통과 |
| 2 | SDK가 SSE/chunked 스트림 연결 | `ClaudeProvider.kt:230-237` POST /v1/messages with `Accept: text/event-stream` + `stream:true` 본문 | 통과 (Blocker Q-T13-A1 해결 후 동작) |
| 3 | 각 chunk를 `AiStreamEvent.Delta(text)`로 방출 | `AnthropicSseParser.kt:113-125` `EVT_CONTENT_BLOCK_DELTA` 분기 → `delta.type == "text_delta"`만 emit, input_json_delta 등 무시 | 통과 |
| 4 | 스트림 종료 시 `AiStreamEvent.Done(AiResponse)` 방출 | `AnthropicSseParser.kt:146-179` `EVT_MESSAGE_STOP` → 누적 텍스트 + finishReason + usage(inputTokens/outputTokens) + providerId 매핑 후 Done emit, terminated=true | 통과 |

### 2.3 F-003 예외 흐름 (features.md L160-165)

| ID | 사양 조건 | ERR | 구현 위치 | AiException variant | 결과 |
|----|-----------|-----|-----------|----------------------|------|
| E-301 | 스트림 도중 연결 끊김 | ERR-001 | `AnthropicSseParser.kt:81-88` `IOException → AiException.Network(e)` + L199-208 `if (!terminated)` EOF 도달 시 `AiException.Network(IOException("stream ended without message_stop"))` + `ClaudeProvider.kt:240-250` execute 시 IOException → AiException.Network 변환 (AnthropicHttpClient.execute가 ErrorMapper.fromException으로 처리) | `Network` (또는 SSE error 이벤트인 경우 Authentication/RateLimit/InvalidInput/ServerError) | 통과 |
| E-302 | 스트림 도중 코루틴 취소 | (CancellationException) | `AiAgentClient.kt:393` `Flow.catch { e -> if (e is CancellationException) throw e }` + `ClaudeProvider.kt:242-243, 276-279` `catch (e: CancellationException) throw e` + `response.use { ... }`로 socket close + `AnthropicSseParser`는 별도 catch 없이 자연 전파 | (CancellationException) | 통과 |
| E-303 | 활성 Provider 스트리밍 미지원 | ERR-004 | `AiAgentClient.kt:372-379` `if (!provider.capabilities.supportsStream)` → `AiStreamEvent.Error(AiException.Configuration("provider does not support streaming"))` emit 후 `return@flow` | `Configuration` | 통과 — 단, 사양 "throw" vs 코드 "Error emit"은 Q-T13-1 / S-T13-X로 사양 보강 필요 |
| E-101~E-110 | F-001 동일 (스트림 시작 전) | ERR-001~006 | `ClaudeProvider.kt:255-261` 4xx/5xx → `ErrorMapper.fromHttpStatus` → emit AiStreamEvent.Error / `AnthropicHttpClient.execute` IOException → ErrorMapper.fromException → AiException.Network | 각 매핑 | 통과 |

### 2.4 M-006 방출 순서 (data-model.md L180-183)

| 요건 | 구현 | 결과 |
|------|------|------|
| Delta 0회 이상 방출 가능 | `AnthropicSseParser.kt:113-125` `EVT_CONTENT_BLOCK_DELTA`에서 매번 emit (text 비어있으면 skip) | 통과 |
| Done 또는 Error 정확히 1회로 종결 | L75 `terminated` 플래그 + L147,164,177,182,188 모든 종결 분기에서 `terminated = true` 설정 + `return@flow` 호출 | 통과 |
| Done 이후 Delta 방출 금지 | L114 `if (terminated) continue` (DELTA 분기) + L147 `if (terminated) return@flow` (MESSAGE_STOP) | 통과 (테스트 케이스 `F-003 M-006 — message_stop 이후 도착한 Delta 는 무시`로 검증) |

### 2.5 F-002 이미지 검증 재사용 (E-201/E-202/E-203/E-204/E-205)

| ID | 사양 | 구현 위치 | 결과 |
|----|------|-----------|------|
| E-201 | 단일 5MB 초과 | `AiAgentClient.kt:382-386` `validateImages` 재사용 → `emit(AiStreamEvent.Error(imageValidation))` | 통과 |
| E-202 합계 | 합계 20MB 초과 | 동일 (`validateImages` L489-493) | 통과 |
| E-208 (구 E-202') | Provider maxImagesPerRequest 초과 | 동일 (`validateImages` L449-453, message "count=$size > $maxImagesPerRequest") | 통과 (S-T12-2 보강과 정합) |
| E-203 | ImageInput.Uri 도달 | 동일 (`validateImages` L477-483) | 통과 (S-T12-3 보강과 정합) |
| E-204 | 지원하지 않는 mimeType | 동일 (`validateImages` L466-470) | 통과 |
| E-205 | Provider 이미지 미지원 | 동일 (`validateImages` L444-446) | 통과 |

호출 위치는 `askStream` 진입 직후 R-007 캡쳐 후 E-303 검증 다음 단계 (`AiAgentClient.kt:382`) — 즉 검증 실패 시 Provider.stream **호출 전**에 Error emit + `return@flow`. AskStreamTest의 케이스 (E-201/E-204/E-205/E-203 4건)이 `provider.streamCallCount == 0`을 함께 assert하여 회귀 방지.

### 2.6 R-007 Provider 지역 캡쳐 (features.md F-005 동시성 모델)

| 요건 | 구현 위치 | 결과 |
|------|-----------|------|
| 진입 시점에 activeProvider/currentProviderConfig 캡쳐 | `AiAgentClient.kt:366-367` `val provider: Provider = activeProvider; val config: ProviderConfig = currentProviderConfig()` (collect가 시작되어 flow 빌더 본체에 들어가는 시점) | 통과 |
| collect 도중 useProvider 호출이 현 stream에 영향 없음 | provider/config가 지역 변수로 캡쳐되므로 그 이후 useProvider가 atomicReference set만 해도 본 코루틴 스택의 provider 인스턴스 참조는 그대로 | 통과 (테스트 `F-003 R-007 — askStream 진행 중 useProvider 호출은 현 stream 에 영향 없음`) |

### 2.7 R-020 케이스 B (close 후 askStream 첫 collect)

| 요건 (api.md A-009 표) | 구현 위치 | 결과 |
|------------------------|-----------|------|
| close 완료 후 새로 호출된 askStream (Flow) — 첫 collect 시 `AiStreamEvent.Error(AiException.Configuration("client closed"))` emit 후 Flow 종료 | `AiAgentClient.kt:360-363` `if (isClosed())` → `emit(AiStreamEvent.Error(AiException.Configuration("client closed"))); return@flow` | 통과 (테스트 `F-003 R-020 케이스 B — close 후 askStream 첫 collect 는 Error(Configuration("client closed")) 후 종료` + provider.streamCallCount == 0 회귀 방지) |

### 2.8 R-005 빈 응답 검증 stream 적용 (Q-T13-3 사양 명시 부재)

| 요건 | 구현 위치 | 결과 |
|------|-----------|------|
| 빈 텍스트 + END_TURN/STOP_SEQUENCE → 그대로 Done | `AnthropicSseParser.kt:153-178` `if (accText.isEmpty() && finishReason !in EMPTY_OK_FINISH_REASONS) emit Error else emit Done` (EMPTY_OK_FINISH_REASONS = {END_TURN, STOP_SEQUENCE}) | 통과 |
| 빈 텍스트 + MAX_TOKENS/OTHER → ServerError(-1, "empty response") Error emit | 동일 (L156-165) | 통과 |

코드는 F-001 R-005와 동일 정책을 stream에도 일관 적용 (`EMPTY_OK_FINISH_REASONS` 상수도 별도 정의, F-001 측 `AiAgentClient.EMPTY_OK_FINISH_REASONS`와 의미 동치). 사양에는 F-003 정상 흐름 4단계가 "스트림 종료 시 Done 방출"만 명시되어 있어 빈 응답 검증 정책이 명시 부재 — 사양 보강 권장 (S-T13-X로 후속 라운드 처리).

---

## 3. 단위 테스트 검증 표

### 3.1 AnthropicSseParserTest.kt 15 케이스

| # | 테스트 함수명 | 검증 의도 | 사양 ID | 의미 있음 |
|---|---------------|-----------|---------|-----------|
| 1 | F-003 정상 — Delta 여러개 후 message_stop 이면 Done 으로 종결 | 정상 흐름 1~4 + Done 누적 + usage 매핑 + providerId 보존 | F-003 정상 흐름 / M-006 / M-002 | 의미 있음 |
| 2 | F-003 정상 — message_delta stop_reason max_tokens 는 FinishReason_MAX_TOKENS 매핑 | finishReason MAX_TOKENS 매핑 | M-002 FinishReason | 의미 있음 |
| 3 | F-003 정상 — stop_reason stop_sequence 는 STOP_SEQUENCE, 미지정은 OTHER | stop_sequence + 미지정 매핑 | M-002 FinishReason | 의미 있음 (2 케이스 in 1 test) |
| 4 | F-003 R-005 — 빈 텍스트 + END_TURN 이면 그대로 Done (성공) | R-005 stream 적용 + END_TURN 정상 | R-005 / F-001 동형 | 의미 있음 |
| 5 | F-003 R-005 — 빈 텍스트 + MAX_TOKENS 이면 Error(ServerError(-1, empty response)) | R-005 stream 적용 + 거부 분기 | R-005 / E-110 동형 | 의미 있음 |
| 6 | F-003 E-301 — error 이벤트 overloaded_error 면 ServerError(-1) 방출 후 종료 | SSE error 이벤트 → ServerError 매핑 | E-301 / Q-T13-2 (provider-spec.md P-CLAUDE) | 의미 있음 |
| 7 | F-003 — error authentication_error 면 Authentication 매핑 | SSE error → Authentication | ERR-003 / Q-T13-2 | 의미 있음 |
| 8 | F-003 — error rate_limit_error 면 RateLimit 매핑 | SSE error → RateLimit + retryAfter null | ERR-002 / Q-T13-2 | 의미 있음 |
| 9 | F-003 — error invalid_request_error 면 InvalidInput 매핑 | SSE error → InvalidInput | ERR-005 / Q-T13-2 | 의미 있음 |
| 10 | F-003 E-301 — message_stop 없이 EOF 도달 시 Error(Network) 방출 | EOF 끊김 매핑 | E-301 / ERR-001 | 의미 있음 |
| 11 | F-003 — 잘못된 JSON 라인은 무시되고 다음 이벤트 처리는 정상 진행 | partial chunk 견고성 (forward-compat) | (사양 외 — 방어적) | 의미 있음 |
| 12 | F-003 — 알 수 없는 event type 은 무시 (forward-compat) | Anthropic 신규 event 추가 시 견고성 | (사양 외 — 방어적) | 의미 있음 |
| 13 | F-003 — ping 이벤트는 무시 (keep-alive) | EVT_PING 분기 | P-CLAUDE SSE | 의미 있음 |
| 14 | F-003 — non-text_delta (input_json_delta 등) 는 Delta 로 방출되지 않음 | text_delta만 emit (tool use 등 미지원 무시) | F-003 정상 흐름 3 | 의미 있음 |
| 15 | F-003 M-006 — message_stop 이후 도착한 Delta 는 무시 (Done 1회로 종결) | M-006 종결 보장 | M-006 | 의미 있음 (M-006 핵심) |

**15개 모두 의미 있음.** SSE 파서의 모든 이벤트 분기 + R-005 + M-006 종결 + 잘못된 입력 견고성을 빠짐없이 커버.

### 3.2 AskStreamTest.kt 15 케이스

| # | 테스트 함수명 | 검증 의도 | 사양 ID | 의미 있음 |
|---|---------------|-----------|---------|-----------|
| 1 | F-003 정상 흐름 — Provider stream 의 Delta_Done 이벤트가 그대로 collect 에 도달 | Flow 위임 + streamCallCount=1 | F-003 정상 흐름 1~4 | 의미 있음 |
| 2 | F-003 정상 흐름 — config 가 currentProviderConfig 와 같은 값으로 Provider 에 전달됨 | apiKey/modelId/timeout 매핑 | A-003 / ProviderConfig | 의미 있음 |
| 3 | F-003 R-020 케이스 B — close 후 askStream 첫 collect 는 Error(Configuration(client closed)) 후 종료 | R-020 케이스 B + streamCallCount=0 | R-020 / A-009 표 | 의미 있음 |
| 4 | F-003 E-303 — Provider 가 supportsStream=false 면 Error(Configuration) 후 종료, Provider stream 미호출 | E-303 + streamCallCount=0 | E-303 / ERR-004 | 의미 있음 |
| 5 | F-003 E-302 — collect 도중 cancel 시 CancellationException 전파 (cooperative) | E-302 표준 코루틴 시맨틱 | E-302 / R-020 케이스 A | 의미 있음 |
| 6 | F-003 R-007 — askStream 진행 중 useProvider 호출은 현 stream 에 영향 없음 | 지역 캡쳐 | R-007 / F-005 | 의미 있음 |
| 7 | F-003 E-201 — 단일 이미지 5MB 초과 시 Error(InvalidInput) 후 종료, Provider stream 미호출 | E-201 + streamCallCount=0 | E-201 | 의미 있음 |
| 8 | F-003 E-204 — 지원하지 않는 mimeType 이미지 시 Error(InvalidInput) 후 종료 | E-204 | E-204 | 의미 있음 |
| 9 | F-003 E-205 — Provider 가 supportsImage=false 면 Configuration ('provider does not support images') | E-205 정확 메시지 | E-205 / ERR-004 | 의미 있음 |
| 10 | F-003 E-203 — ImageInput_Uri 도달 시 Error(InvalidInput uri unreadable) 후 종료 | E-203 (D-004 연장 / S-T12-3) | E-203 | 의미 있음 |
| 11 | F-003 — Provider stream 이 RuntimeException 을 throw 하면 Error(ServerError) 로 wrap | .catch 안전망 (RuntimeException → ServerError) | (사양 외 — 안전망) | 의미 있음 |
| 12 | F-003 — Provider stream 이 Network 를 throw 하면 Error(Network) 그대로 전달 | AiException은 그대로 wrap | ERR-001 / .catch | 의미 있음 |
| 13 | F-003 — Provider stream 이 Delta 후 Error 를 emit 하면 그대로 collect 에 도달 (E-301 시퀀스) | Delta 0+ → Error 1 시퀀스 | M-006 / E-301 | 의미 있음 |
| 14 | F-003 회귀 — images 빈 리스트 시 검증 통과 후 Provider stream 호출 | images 빈 + supportsImage=false 회귀 | F-001 회귀 보호 | 의미 있음 |

**참고**: impl_summary_6.md §3은 "15 케이스"라 표기하나 본 파일에는 14개의 `@Test` 함수가 있음 — 의미 있는 모든 검증을 커버하므로 합계 차이는 정보성 (테스트 함수명 카운트 차이).

### 3.3 ClaudeProviderStreamTest.kt 5 케이스

| # | 테스트 함수명 | 검증 의도 | 사양 ID | 의미 있음 |
|---|---------------|-----------|---------|-----------|
| 1 | F-003 정상 — Anthropic SSE chunked 응답을 Delta 들 + Done 으로 변환 | end-to-end + 헤더(`x-api-key`/`anthropic-version`/`Accept=text/event-stream`) + 본문(`stream:true`) 검증 | P-CLAUDE / A-003 | 의미 있음 (P-CLAUDE 핵심) |
| 2 | F-003 — 401 응답이면 Authentication 이 Error 로 emit 되고 종료 | HTTP 401 → ErrorMapper → Error emit | E-102 / ERR-003 | 의미 있음 |
| 3 | F-003 — 429 응답이면 RateLimit 이 Error 로 emit 되고 종료 | 429 + Retry-After 헤더 파싱 (10s) | E-103 / ERR-002 | 의미 있음 |
| 4 | F-003 — 5xx 응답이면 ServerError 가 Error 로 emit 되고 종료 | 503 → ServerError(503) | E-104 / ERR-006 | 의미 있음 |
| 5 | F-003 E-301 — SSE 도중 error 이벤트가 오면 그대로 Error 로 emit 후 종료 | 200 + SSE 본문 안 error 이벤트 → Error emit (Delta 1 + Error 1) | E-301 / Q-T13-2 | 의미 있음 |

**5개 모두 의미 있음.** P-CLAUDE 헤더 검증 + HTTP error 매핑 + SSE 본문 error 이벤트 매핑까지 빠짐없이 커버. MockWebServer를 OkHttp interceptor로 rewrite하여 https URL을 http로 우회하는 테크닉(L220-227)으로 통합 단위 테스트 가능 — S-T12-1(MockWebServer https 강제) 우회로 적절.

### 3.4 컴파일 가능성 점검

| 항목 | 검증 | 결과 |
|------|------|------|
| AnthropicSseParserTest.kt import | okio.Buffer / okio.BufferedSource / kotlinx.coroutines.flow.toList / kotlinx.coroutines.test.runTest / junit — 모두 build.gradle 의존성 보유 | 통과 |
| AskStreamTest.kt import | mockk / kotlinx.coroutines (CompletableDeferred/async/flow/flowOf/toList) / runTest / junit — 모두 의존성 보유 | 통과 |
| ClaudeProviderStreamTest.kt import | okhttp3.mockwebserver / okhttp3 / runTest — 모두 의존성 보유 | 통과 |
| FakeProvider 람다 시그니처 (`(ProviderConfig) -> Flow<AiStreamEvent>`) | `streamBehavior = { flowOf(...) }` (인자 무시 람다) 형태도 Kotlin 컴파일 OK — `it` 사용 안 하면 인자 무시 람다로 컴파일 | 통과 |
| `AnthropicMessagesRequest.serializer()` 직접 참조 (ClaudeProvider.kt:217) | **실패 — import 누락 (Q-T13-A1, Blocker)** | **실패** |
| `AnthropicHttpClient(httpClient = ...)` 명명 인자 호출 (ClaudeProviderStreamTest.kt) | 같은 모듈 internal class, 같은 모듈 내 호출 가능 | 통과 |
| `ClaudeProvider(httpClientFactory = ...)` internal constructor 호출 (ClaudeProviderStreamTest.kt:233) | 같은 모듈 internal constructor — 같은 모듈 내 호출 가능 | 통과 |
| 실제 네트워크 호출 0회 | AnthropicSseParserTest는 Buffer in-memory, AskStreamTest는 FakeProvider, ClaudeProviderStreamTest는 MockWebServer + interceptor rewrite | 통과 |

---

## 4. F-001 / F-002 회귀 점검

### 4.1 F-001 핵심 회귀 항목 (T11에서 통과한 흐름)

| 항목 | 변경 영향 가능성 | 회귀 점검 | 결과 |
|------|------------------|-----------|------|
| `AiAgentClient.ask` 본체 | T13에서 변경 없음 (askStream 본체만 추가) | `AiAgentClient.kt:259-316` 그대로 | 회귀 없음 |
| `AiAgentClient.ask`의 catch 체인 (CancellationException → AiException → IllegalArgumentException → Throwable) | T13 변경 없음 | L295-315 그대로 | 회귀 없음 |
| `AiAgentClient.EMPTY_OK_FINISH_REASONS` / `EMPTY_RESPONSE_CODE` / `IMAGES_TOTAL_MAX_BYTES` companion | T13 변경 없음 | L592-610 그대로 | 회귀 없음 |
| `Mapper.toAnthropicRequest` (F-001/F-002 매핑 + httpClient 인자) | T13 변경 없음 | mtime 2025-11-03 (T12 이전) — 본 라운드 미수정 | 회귀 없음 |
| `Mapper.toAiResponse` | T13 변경 없음 | 미수정 | 회귀 없음 |
| `ClaudeProvider.complete` 본체 | T13에서 stream 본체 추가, complete은 변경 없음 | ClaudeProvider.kt:102-159 — T11/T12 그대로 + Mapper 호출 시그니처 동일 | 회귀 없음 |
| `AnthropicMessagesRequest` (model/maxTokens/messages/temperature 필드) | T13에서 stream 강제 추가 위해 JsonObject 후처리(L220-224)로 직렬화 수행 — 클래스 자체 필드 변경 없음 | AnthropicMessagesRequest.kt — 이미 T12에서 content=JsonElement 변경 후 그대로 | 회귀 없음 |
| F-001 정상 흐름 (`ask` → `complete` → `toAiResponse`) | 변경 없음 | AskTest 14개 그대로 통과 가정 | 회귀 없음 |
| F-001 R-005 빈 응답 검증 | `AiAgentClient.kt:283-294` 그대로 | 회귀 없음 |
| F-001 R-007 (활성 Provider 캡쳐) | `AiAgentClient.kt:269-270` 그대로 | 회귀 없음 |
| F-001 R-020 케이스 A/B | `AiAgentClient.ensureNotClosed` / `close` 그대로 | 회귀 없음 |
| F-001 E-101~E-110 매핑 | `ClaudeProvider.complete` + `ErrorMapper` 변경 없음 | 회귀 없음 |

### 4.2 F-002 회귀 점검

| 항목 | 회귀 점검 | 결과 |
|------|-----------|------|
| `AiAgentClient.validateImages` 본체 | T13 변경 없음 (askStream에서 동일 함수 재사용) | 회귀 없음 |
| `ImageInput.Uri/Bytes/Url` (M-003) | T13 변경 없음 | 회귀 없음 |
| `Mapper.buildContentJson` / `verifyMagicNumber` / `fetchUrlBytes` | T13 변경 없음 (Mapper.kt mtime 변경 없음) | 회귀 없음 |
| `MapperImageTest`/`AskWithImagesTest`/`ImageInputTest`/`MapperTest` | T13에서 미수정 | 회귀 없음 |
| AnthropicMessagesRequest content (JsonElement) + sealed AnthropicContentRequestBlock | 변경 없음 (T12 결과 그대로) | 회귀 없음 |
| `ClaudeProvider.complete`의 `Mapper.toAnthropicRequest` 호출 (httpClient 인자) | 변경 없음 | 회귀 없음 |

### 4.3 ClaudeProvider.kt stream 본체 변경이 complete 본체에 영향 없는지

- `complete` (L102-159)와 `stream` (L189-280)은 별개 메서드, 공유 상태 없음.
- companion object에 신규 추가된 `SSE_MEDIA_TYPE_VALUE = "text/event-stream"`(L309)은 stream 전용 — complete은 사용 안 함.
- 기존 `MESSAGES_URL`/`HEADER_API_KEY`/`HEADER_ANTHROPIC_VERSION`/`JSON_MEDIA_TYPE_VALUE`/`JSON_MEDIA_TYPE`은 complete에서 그대로 사용 — 변경 없음.
- impl_summary_6.md §1-B "unused import(`AnthropicErrorResponse`) 제거"라 했으나 본 ClaudeProvider.kt의 import 목록(L3-28)에는 `AnthropicErrorResponse`가 없음 — 이미 제거된 상태로 정합 (추가 후속 영향 없음).

**ClaudeProvider.complete 회귀 없음** — Blocker Q-T13-A1을 해결한 뒤에도 complete 본체에 영향 없음.

---

## 5. 발견된 이슈

본 라운드 Blocker 1건. Major 0건. Minor 4건.

### Q-T13-A1 [Blocker] `AnthropicMessagesRequest` import 누락 — `ClaudeProvider.stream` 컴파일 실패

- **위치**: `sdk/src/main/kotlin/com/androidailab/aisdk/provider/claude/ClaudeProvider.kt:217`
- **현상**: `stream()` 본체 L217에서 `AnthropicMessagesRequest.serializer()`를 직접 참조하지만 파일 상단 import 목록(L3-28)에 `import com.androidailab.aisdk.internal.network.AnthropicMessagesRequest`가 없음. 같은 모듈 내 internal class라 접근 권한은 있지만, 다른 패키지(`com.androidailab.aisdk.internal.network`)에 있으므로 fully-qualified name 또는 import가 반드시 필요. 본 상태로는 Kotlin 컴파일러가 unresolved reference 에러를 낸다.
- **사양**: features.md F-003 정상 흐름 1~4 / provider-spec.md P-CLAUDE Anthropic Messages SSE 형식
- **현 구현 의도**: `AnthropicMessagesRequest`를 직렬화하여 JsonObject로 변환 후 `stream:true` 플래그 강제 추가 (P-CLAUDE SSE 활성화 조건).
- **재현**: `./gradlew :sdk:compileKotlin` 시 `Unresolved reference: AnthropicMessagesRequest` (L217). MockWebServer 통합 테스트 `ClaudeProviderStreamTest`도 stream 본체 호출 시 같은 컴파일 오류로 실패.
- **권장 수정 (1줄)**:
  ```kotlin
  // ClaudeProvider.kt 상단 import 블록에 다음 한 줄 추가
  import com.androidailab.aisdk.internal.network.AnthropicMessagesRequest
  ```
- **Severity 사유**: 사양 정의된 F-003 정상 흐름 1~4가 동작하지 않음. 단위 테스트(`ClaudeProviderStreamTest` 5건 + `AskStreamTest` 정상 흐름 케이스의 ClaudeProvider 위임 시나리오)도 컴파일 실패. 단, 수정 비용은 1줄로 작고 다른 영역에 부수효과 없음.

### Q-T13-1 [Minor] E-303 처리 시맨틱 — "throw" vs Flow Error emit (impl_summary 인계 그대로)

- **위치**: features.md F-003 E-303 L164 "호출 즉시 `AiException.Configuration` throw" / `AiAgentClient.kt:372-379` `emit(AiStreamEvent.Error(...)); return@flow`
- **현상**: 사양은 "호출 즉시 throw"이지만 코드는 cold Flow 빌더 안에서 collect 시점에 Error emit 후 종료. R-020 케이스 B(close 후 askStream)와 동형 처리 — askStream() 호출 자체는 collect 전이라 throw할 적절한 시점이 없음. A-009 표 R-020 케이스 B의 askStream 처리도 "첫 collect 시 Error emit"이므로 일관성 차원에서 본 처리가 합리적.
- **사양 정합**: api.md A-003 close 시맨틱 표(L144)는 "첫 collect 시 Error emit"으로 정합하지만 features.md F-003 E-303(L164)은 "throw"로 표기 — 사양 내부 표기 불일치.
- **권장**: 사양 보강 — F-003 E-303을 "(R-020 케이스 B와 동형) 첫 collect 시 `AiStreamEvent.Error(AiException.Configuration("provider does not support streaming"))` emit 후 Flow 종료"로 정정. 본 라운드 코드 정합.
- **Severity 사유**: 동작은 사양의 의도(호출자가 종결 사유를 받음)와 정합. 표기 차원의 명시 부재. (Q-T13-1과 동일 — impl_summary가 명시.)

### Q-T13-2 [Minor] SSE error 이벤트의 ERR-XXX 매핑 사양 명시 부재

- **위치**: features.md F-003 E-301 L162 "스트림 도중 연결 끊김 → Error(Network)" / `AnthropicSseParser.kt:240-245` `toAiException` switch
- **현상**: 사양은 E-301을 "연결 끊김"만 다루고, Anthropic SSE 본문 안에 들어오는 `event: error` 이벤트의 분기 매핑은 사양에 명시되어 있지 않다. 코드는 `error.type` 문자열에 따라:
  - `authentication_error` → ERR-003 Authentication
  - `rate_limit_error` → ERR-002 RateLimit(retryAfter=null)
  - `invalid_request_error` → ERR-005 InvalidInput
  - 그 외 (`overloaded_error` / `api_error` / `null`) → ERR-006 ServerError(-1, message)
  로 매핑. provider-spec.md P-CLAUDE 에러 매핑 표(L92-97)는 HTTP status 기반 매핑만 다루므로 SSE 본문 안 error 이벤트는 사양 갈래가 없다.
- **권장**: 사양 보강 — features.md F-003 예외 흐름에 "E-301a: SSE 본문 안 error 이벤트 → Anthropic error.type별 분기 매핑 (P-CLAUDE 표와 동일)" 행 추가. provider-spec.md P-CLAUDE 섹션에 SSE error 이벤트 매핑 표 부기.
- **Severity 사유**: 코드는 P-CLAUDE의 HTTP 매핑 정신을 SSE 본문에도 일관 적용 — 정합 이지만 사양 갈래 없음. 호출자 코드 작성에 영향 없음 (호출자는 AiException variant로 분기).

### Q-T13-3 [Minor] R-005 stream 적용의 사양 명시 부재

- **위치**: features.md F-001 정상 흐름 4단계(빈 응답 검증)는 ask에 한정 / `AnthropicSseParser.kt:153-165` message_stop 시점에 동일 정책 적용
- **현상**: F-003 정상 흐름 4단계(L157)는 "스트림 종료 시 `AiStreamEvent.Done` 방출"만 명시하며 빈 응답 검증을 명시하지 않는다. 코드는 F-001 R-005를 stream에도 일관 적용 (END_TURN/STOP_SEQUENCE에서 빈 응답은 Done, MAX_TOKENS/OTHER에서 빈 응답은 ServerError(-1, "empty response") Error emit).
- **권장**: 사양 보강 — F-003 정상 흐름 4단계에 "F-001 R-005와 동일한 빈 응답 검증을 적용 (END_TURN/STOP_SEQUENCE에서 빈 응답은 Done, MAX_TOKENS/OTHER에서 빈 응답은 ServerError(-1, "empty response") Error emit)" 한 줄 추가.
- **Severity 사유**: 코드 동작은 F-001과 일관 — 정보성. 호출자가 stream 응답에서 빈 텍스트 + finishReason으로 분기하는 코드와 ask 응답에서 동일하게 분기하는 코드를 작성 가능.

### S-T13-1 [정보성] AnthropicSseParser.parse 외부 catch (e: IOException) 도달 가능성 미문서화

- **위치**: `AnthropicSseParser.kt:209-214` flow 빌더 외부 try의 catch (e: IOException)
- **현상**: while 루프 내부의 `source.readUtf8Line()` IOException은 inline catch(L81-88)에서 잡혀 emit 후 return 됨. 다른 IOException 발생 가능 코드는 보이지 않음 (`source.exhausted()`도 IOException 가능하지만 inline 외부에 있음). 따라서 외부 try-catch는 일부 코너 케이스(`source.exhausted()`의 IOException)만 잡는 미세한 가드.
- **권장**: 사양/코드 수정 없음. 문서화 — KDoc에 "외부 catch는 source.exhausted() 등에서 발생할 수 있는 IOException 가드"임을 명시하면 후속 유지보수 시 의미 명확. 본 라운드 통과 차원에서 문제 없음.
- **Severity 사유**: 정보성. 동작 영향 없음.

---

## 6. 사양 명확화 필요 항목

### S-T13-1 [Minor] E-303 처리 시맨틱 사양 명시 (Q-T13-1 인계)

- 본 라운드 코드는 cold Flow 시맨틱에 따라 "첫 collect 시 Error emit + 종료"로 처리. F-003 E-303 표기를 "호출 즉시 throw" → "첫 collect 시 `AiStreamEvent.Error(AiException.Configuration("provider does not support streaming"))` emit 후 Flow 종료"로 정정 권장. R-020 케이스 B와 동형, A-009 표와 정합.
- **다음 라운드 진입 차단**: 차단 안 함.

### S-T13-2 [Minor] SSE error 이벤트 ERR-XXX 매핑 명시 (Q-T13-2 인계)

- features.md F-003 예외 흐름에 "E-301a: SSE 본문 내 error 이벤트 → Anthropic error.type별 분기 (authentication_error/rate_limit_error/invalid_request_error/overloaded_error/api_error)" 추가 권장. provider-spec.md P-CLAUDE에 SSE error 이벤트 매핑 표 부기.
- **다음 라운드 진입 차단**: 차단 안 함.

### S-T13-3 [Minor] R-005 stream 적용 명시 (Q-T13-3 인계)

- features.md F-003 정상 흐름 4단계에 "F-001 R-005와 동일한 빈 응답 검증을 적용" 한 줄 추가 권장.
- **다음 라운드 진입 차단**: 차단 안 함.

### S-T13-4 [정보성] AnthropicSseParser 외부 catch 문서화

- KDoc 문서화 권장 (코드/사양 수정 불필요).
- **다음 라운드 진입 차단**: 차단 안 함.

### 이월 항목 (이전 라운드 미해결)

- **S-T11-1** (F-001 E-107 발생 위치 명확화) — 본 라운드 영향 없음, 이월.
- **S-T12-1** (ImageInput.Url https 강제 + MockWebServer 통합) — 본 라운드 ClaudeProviderStreamTest는 OkHttp interceptor rewrite로 우회하여 영향 없음. 이월.

---

## 7. S-T12-2 / S-T12-3 사양 보강 정합 점검

spec_draft_6.md가 반영한 변경이 실제 spec/ 파일에 적용되어 있고 코드와 정합한지 점검.

### 7.1 features.md F-002 변경 (S-T12-2 + S-T12-3)

| 변경 위치 | 사양 (현 spec) | 코드 | 정합 |
|-----------|---------------|------|------|
| L111~112 정상 흐름 2~3단계 (Uri 즉시 거부 / v0.1 도달 불가) | "Uri는 본 단계에서 즉시 거부" + "v0.1에서 Uri는 도달 불가" | `validateImages` L477-483 무조건 거부 + `Mapper.buildContentJson` 안전망 (Mapper.kt:135) | 통과 |
| L116~120 신규 "v0.1 ImageInput.Uri 정책" 섹션 | 호출자가 ContentResolver로 Bytes 변환 후 전달 명시 + v0.2 검토 | `validateImages` 메시지 "uri unreadable: SDK does not auto-resolve Uri (call ContentResolver before ask)"가 호출자에게 동일 안내 | 통과 |
| L126 E-202 — "M-001의 20MB 한계 초과", 메시지 "images total too large: ..." | 합계 케이스 명시 + 메시지 패턴 | `validateImages.kt:489-493` `if (totalBytes > IMAGES_TOTAL_MAX_BYTES) InvalidInput("images total too large: $totalBytes > $IMAGES_TOTAL_MAX_BYTES")` | 통과 (메시지 prefix "images total too large:" 일치) |
| L127 E-203 — "v0.1 정책 — D-004 연장" | 권한·존재 X 표현 제거, Uri 도달 시 거부 | `validateImages` L477-483 무조건 거부 + 메시지 "uri unreadable: ..." | 통과 |
| L132 E-208 신규 — Capabilities.maxImagesPerRequest 초과, 메시지 패턴 "count=" | 개수 케이스 분리 + 토큰 "count=" 명시 | `validateImages.kt:449-453` `if (images.size > capabilities.maxImagesPerRequest) InvalidInput("images total too large: count=${images.size} > ${capabilities.maxImagesPerRequest}")` — 메시지에 `count=` 토큰 포함 | 통과 |

### 7.2 data-model.md M-003 Uri Variant (S-T12-3)

| 변경 위치 | 사양 | 코드 | 정합 |
|-----------|------|------|------|
| L84 Uri 행 — v0.1 거부 정책 한 줄 + v0.2 검토 | "v0.1: SDK는 자동 resolve 안 함 — ask/askStream/session.send 진입 시 즉시 E-203(InvalidInput("uri unreadable"))으로 거부" | 위 7.1 E-203 항목과 동일 코드 매핑 | 통과 |

### 7.3 overview.md D-004 사유 셀 (S-T12-3)

| 변경 위치 | 사양 | 코드 | 정합 |
|-----------|------|------|------|
| L72 D-004 — "ImageInput.Uri 자동 resolve도 SDK가 수행하지 않는다" 한 줄 | D-004 본문 연장 명시 | 위와 동일 코드 매핑 | 통과 |

### 7.4 새 E-208과 코드 메시지 패턴 정합 점검 (검증 의뢰 핵심 항목)

**핵심 점검 항목 (작업 지시 §B-2)**:
1. 코드 메시지 "images total too large: count=..."(`AiAgentClient.kt:451`)가 새 E-208 패턴 "count="와 일치하는가? — **일치함**. 코드 메시지 prefix "images total too large:"는 E-202(합계)와 동일하고, 그 뒤 `count=` 토큰이 E-208의 분기 식별자.
2. 새 사양상 개수 초과는 E-202가 아닌 E-208인데, 코드는 같은 메시지 prefix("images total too large")/같은 InvalidInput으로 던지므로 ID-구분이 호출자에게 보이지 않음 — 사양상 두 ID 분리는 정합한가?
   - **사양상 정합**: spec_draft_6.md §1.2 "ERR 매핑은 동일(ERR-005)"라 명시 — 두 ID 모두 InvalidInput → ERR-005. 호출자는 variant + 메시지 prefix만으로 충분 (메시지 텍스트는 안정 API가 아님 — spec_draft_6 §1.2 동일 명시).
   - **호출자 분기 가능성**: variant(`AiException.InvalidInput`)는 동일하지만, 메시지 내 `count=` 토큰 유무로 합계 vs 개수 케이스 분기 가능. 단, 이 분기는 사양상 안정 API가 아니므로 호출자가 의존하지 않는 것이 권장.
   - **결론**: 사양 ID 분리는 SDK 내부 추적성(로그·테스트·디버깅 ID 부여) 차원에서 정합. 호출자 노출 동작은 동일 (InvalidInput).
3. F-002 회귀 영향: F-002 코드 변경 없음 — 사양만 변경. 회귀 영향은 의미 매핑 차원만 — **회귀 없음**.

### 7.5 S-T12-2 / S-T12-3 종합 결과

**사양 보강 모두 정합 통과**. 코드 동작 그대로 유지하면서 사양 표기를 코드와 일치시킨 형태. 신규 ID 1건(E-208)은 코드 메시지 패턴(`count=`)과 일치. F-002 회귀 영향 없음.

---

## 8. 종결 권고

### F-003 종결

**조건부 통과 — Blocker 1건(Q-T13-A1, import 1줄 누락) 해결 후 종결**.

- A-003 시그니처(`fun askStream(request: AiRequest): Flow<AiStreamEvent>`)는 api.md 토큰 단위 일치.
- F-003 정상 흐름 1~4단계가 코드(`AiAgentClient.askStream` Flow 빌더 → `ClaudeProvider.stream` → `AnthropicSseParser.parse`)에 차례로 매핑.
- E-301(연결 끊김 + EOF + SSE error 이벤트) / E-302(CancellationException) / E-303(supportsStream=false) / E-101~E-110(stream 시작 전 단계의 4xx/5xx 매핑) 모든 예외 흐름이 코드와 단위 테스트로 매핑.
- M-006 방출 순서(Delta 0+ → Done|Error 1) 보장이 `terminated` 플래그 + 모든 종결 분기의 `return@flow`로 코드와 단위 테스트(M-006 테스트 케이스)로 보증.
- R-005 stream 적용 + R-007 지역 캡쳐 + R-020 케이스 B 모두 코드와 테스트로 정합 (사양 명시 보강 권장 S-T13-1/3).
- F-002 이미지 검증(E-201/E-202/E-203/E-204/E-205) askStream 진입 직후 재사용으로 ask와 일관.
- P-CLAUDE Anthropic Messages SSE 형식(헤더 `Accept: text/event-stream` + 본문 `stream:true` + SSE 이벤트 분기 + error 이벤트 매핑)이 단위 테스트(ClaudeProviderStreamTest 정상 흐름 케이스)로 검증.
- 단위 테스트 35개 가까이(SSE 파서 15 + askStream 14 + ClaudeProviderStream 5)가 의미 있는 검증, 외부 네트워크 호출 0회.
- F-001/F-002 회귀 없음 (Mapper/Errormapper/AiAgentClient.ask/ImageInput/MapperTest 등 변경 없음).
- S-T12-2 / S-T12-3 사양 보강이 코드 동작과 정합 — 신규 E-208 ID 분리 정합.

Blocker 해결 후 본 라운드 종결 가능.

### 다음 wave 진입 권고

| 라운드 | 의존성 | 진입 가능 | 비고 |
|--------|--------|-----------|------|
| **F-004 (Session)** | F-001/F-002/F-003 + F-005 R-014 | **진입 가능** (Blocker 해결 후) | impl_summary_6 §5 인계. Session.send도 validateImages + Mapper.toAnthropicRequest 재사용. history 다중 메시지 변환 함수 + systemPrompt 매핑(Anthropic top-level `system` 필드) + Mutex 직렬화 + R-020 케이스 B 적용 + R-014 atomic Provider get. F-003 측면에서 session.sendStream 시그니처 검토 권장 (impl_summary §5.4). |
| **F-006 (Hilt)** | F-001/F-002 (httpClient holder set 흐름) + F-003 미영향 | **진입 가능** (Blocker 해결 후) | impl_summary §5.6 그대로. AnthropicSseParser는 internal object stateless로 Hilt 등록 불필요. ClaudeProvider 두 번째 생성자(`public constructor()`) 또는 `@Inject` 생성자(F-006 라운드에서 추가)를 사용. F-003 진입한 stream 본체는 httpClientFactory 패턴이 그대로 적용. |
| **F-007 (영속화)** | F-004 + M-011 + DataStore | F-004 후 진입 | 이미지 영속화 1MB 한계(E-704), schemaVersion=1 강제(R-018), 다중 인스턴스 last-write-wins(R-019), URL SSRF 방어 미수행(R-023). |

본 라운드 Blocker(import 1줄)는 다음 wave 진입 차단 사항이지만, 수정이 자명하고 비용이 낮으므로 android-implementer가 즉시 수정 후 다음 wave 진입 가능.

### 다음 액션 (오케스트레이터에게)

1. **android-implementer**: Blocker Q-T13-A1 즉시 수정 — `ClaudeProvider.kt` 상단 import에 `import com.androidailab.aisdk.internal.network.AnthropicMessagesRequest` 1줄 추가. 컴파일 통과 후 본 라운드 종결.
2. **spec-architect**: S-T13-1/2/3 보강 검토 — 모두 Minor, 다음 라운드 진입 차단하지 않음. 묶음으로 처리 권장.
3. **F-003 자체**: Blocker 해결 후 종결. F-004 / F-006 wave 진입 가능.

---

## 9. 자체 체크리스트

- [x] 4쌍 경계면 교차 비교 완료
  - api.md A-003 ↔ AiAgentClient.askStream (토큰 단위)
  - data-model.md M-006 ↔ AnthropicSseParser 방출 순서 + AiAgentClient.askStream catch 안전망
  - error-handling.md ERR-001/002/003/004/005/006 ↔ AiException variant + AnthropicSseParser.toAiException + ErrorMapper.fromHttpStatus + ClaudeProvider.stream + AskStreamTest
  - features.md F-003 E-301/302/303 + E-101~E-110 ↔ AnthropicSseParser/ClaudeProvider/AiAgentClient/AskStreamTest/ClaudeProviderStreamTest
- [x] F-003 정상 흐름 1~4단계 모두 코드에 매핑 + 단위 테스트로 보증
- [x] F-003 모든 E-XXX (E-301/E-302/E-303 + E-101~E-110 stream 적용) → 코드 매핑 + 단위 테스트 커버
- [x] api.md A-003 시그니처 토큰 단위 비교 (close 시맨틱 R-020 케이스 B 포함)
- [x] AiException variant ↔ ERR-XXX 매핑 (ERR-001 Network / ERR-002 RateLimit / ERR-003 Authentication / ERR-004 Configuration / ERR-005 InvalidInput / ERR-006 ServerError)
- [x] M-006 [AiStreamEvent] 방출 순서(Delta 0+ → Done|Error 1) 토큰 단위 일치 + terminated 플래그 검증
- [x] R-005 빈 응답 검증 stream 적용 (사양 명시 부재 보강 권장)
- [x] R-007 지역 캡쳐 — askStream 진입 시점에 activeProvider/currentProviderConfig 캡쳐
- [x] R-020 케이스 B — close 후 askStream 첫 collect 시 Error emit + 종료 + Provider.stream 미호출
- [x] F-002 이미지 검증 재사용 — E-201/E-202/E-203/E-204/E-205 askStream에서 ask와 일관 매핑
- [x] P-CLAUDE Anthropic Messages SSE 형식 — Accept 헤더 + stream:true 본문 + SSE 이벤트 분기 + error.type 매핑 (단위 테스트 검증)
- [x] 단위 테스트 35개 가까이 의미 있는 검증 + 컴파일 가능 형태(Blocker 1건 제외) + 실제 네트워크 호출 0회
- [x] F-001 회귀 점검 — AiAgentClient.ask / Mapper / ClaudeProvider.complete / AnthropicMessagesRequest / AskTest 모두 회귀 없음
- [x] F-002 회귀 점검 — validateImages / ImageInput / Mapper.buildContentJson / MapperImageTest 모두 회귀 없음
- [x] S-T12-2 / S-T12-3 사양 보강 정합 점검 — features.md L111~132, data-model.md M-003, overview.md D-004
- [x] 새 E-208 신규 ID와 코드 메시지 패턴(`count=`) 정합 점검 — 통과
- [x] 모든 이슈에 (file:line) 근거 명시
- [x] 사양 모호함 항목 별도 섹션(6번)으로 spec-architect 회신 요청 (S-T13-1/2/3 + S-T13-4)
- [x] 코드/사양 직접 수정하지 않음
- [x] Severity 분류 일관 (Blocker = 사양 정의된 흐름 동작 불가 / Minor = 정책 명시 차원)
- [x] 다음 wave (F-004/F-006/F-007) 진입 가능/차단 명시
