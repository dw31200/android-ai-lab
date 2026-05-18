# QA Report 3 — F-001 (텍스트 단발 질의) 점진 검증

검증자: sdk-qa-validator
검증 일시: 2026-05-07
대상:
- F-001 (T11 마무리, `_workspace/impl_summary_4.md`) — `AiAgentClient.ask` + `ClaudeProvider.complete` + `internal/network/*` 본체 + 단위 테스트 3종 (AskTest 14 / ErrorMapperTest 12 / MapperTest 12 = 38개)
- 직전 QA(qa_report_2): F-005/F-008 통과 — 본 라운드는 그 결과 위에 F-001만 검증

검증 범위: A-002 시그니처 / 정상 흐름 5단계 / 예외 흐름(E-101~E-110) / R-005 빈 응답 / R-007 캡쳐 / R-020 케이스 A·B / D-005 / ERR-XXX 매핑 / Mapper 토큰 매핑 / ErrorMapper 매핑 / 단위 테스트 의미·컴파일 가능성 / 모델(M-001~M-002, M-009, FinishReason) 일치

빌드 실행: 미수행 (qa_report_1·2와 동일 정책 — settings.gradle.kts/wrapper 부재. 정적 검증/관찰 기반)

---

## 1. 요약

| Severity | 건수 | 종결 조건 |
|----------|------|----------|
| Blocker  | 0    | 0건 필수 |
| Major    | 0    | 처리 또는 명시적 유보 |
| Minor    | 4    | 다음 라운드 이월 가능 |
| 사양 명확화 요청 | 1 | spec-architect 회신 후 반영 |

**결과**: F-001 **통과**. Blocker/Major 0건. F-001의 모든 사양 ID(A-002, E-101~E-110, R-005, R-007, R-020 케이스 A·B, D-005, ERR-001~ERR-006, M-001/M-002/M-009)가 코드와 단위 테스트로 1:1 매핑됨. **F-002/F-003/F-004/F-006 진입 가능**.

Minor 4건은 (a) `AiException` catch 분기가 `is RateLimit`도 무메시지 `class RateLimit` 그대로 통과시키는 점, (b) ClaudeProvider가 비-`AiException` Throwable을 한번 더 ErrorMapper로 변환 시 ErrorMapper의 generic catch가 ServerError(-1) "unexpected error..."를 만드는 이중 메시지 가능성, (c) Mapper.toAnthropicRequest가 `temperature`를 항상 직렬화(`encodeDefaults=false`+nullable로 우회되나 SDK의 기본값 0.7f가 항상 전송됨), (d) MapperTest 1건의 함수명 라벨이 M-002로 표기되었지만 의미상 `M-009 — usage null` 등 — 모두 사양 동작에 영향 없는 라벨/문서 수준이거나 도달 불가 분기. 자세한 근거는 §5 발견 이슈.

---

## 2. F-001 검증 표

### 2.1 A-002 시그니처 토큰 단위 비교

api.md L86-88 ↔ `AiAgentClient.kt:250`:

| 토큰 | 사양 | 구현 | 일치 |
|------|------|------|------|
| 함수명 | `ask` | `ask` | 통과 |
| 파라미터명/타입 | `request: AiRequest` | `request: AiRequest` | 통과 |
| 반환 타입 | `Result<AiResponse>` | `Result<AiResponse>` | 통과 |
| suspend 여부 | `suspend` | `public suspend fun` | 통과 |
| visibility | public | `public` | 통과 |

### 2.2 F-001 정상 흐름 (features.md L67-76)

| 단계 | 사양 | 구현 위치 | 결과 |
|------|------|-----------|------|
| 1 | `client.ask(AiRequest(prompt = "..."))` 호출 | `AiAgentClient.kt:250` 진입점 | 통과 |
| 2 | 활성 Provider를 통해 LLM API 요청 전송 | `AiAgentClient.kt:260` `provider.complete(request, config)` (R-007 — `activeProvider` 지역 캡쳐, L260) | 통과 |
| 3 | Provider 응답 수신 | `ClaudeProvider.kt:120-148` HTTP POST → 본문 파싱 → `Mapper.toAiResponse` | 통과 |
| 4 | 응답 검증 (4-a, 4-b) | `AiAgentClient.kt:266-277` `EMPTY_OK_FINISH_REASONS` 검사 + `EMPTY_RESPONSE_CODE` (-1) | 통과 |
| 4-a | `text==""` + `END_TURN`/`STOP_SEQUENCE` → 그대로 성공 | `EMPTY_OK_FINISH_REASONS = setOf(END_TURN, STOP_SEQUENCE)` (L395-398), 분기로 통과 | 통과 |
| 4-b | `text==""` + `MAX_TOKENS`/`OTHER` → `ServerError(-1, "empty response")` | L269-274 `Result.failure(AiException.ServerError(code=-1, message="empty response"))` 메시지 정확 일치 | 통과 |
| 5 | `AiResponse`로 변환 | `Mapper.kt:66-92` `toAiResponse` (`ClaudeProvider.kt:147`에서 호출) | 통과 |
| 6 | `Result.success(AiResponse)` 반환 | `AiAgentClient.kt:276` | 통과 |

### 2.3 F-001 예외 흐름 (features.md L80-91)

| ID | 사양 조건 | ERR | 구현 위치 | AiException variant | 결과 |
|----|-----------|-----|-----------|----------------------|------|
| E-101 | 네트워크 연결 없음 | ERR-001 | `ErrorMapper.kt:69` `IOException → AiException.Network(throwable)` | `Network` (cause 보존) | 통과 |
| E-102 | API 인증 실패 (401) | ERR-003 | `ErrorMapper.kt:50` `401 → AiException.Authentication()` | `Authentication` | 통과 |
| E-103 | 레이트 리밋 (429) | ERR-002 | `ErrorMapper.kt:51` `429 → AiException.RateLimit(parseRetryAfter(...))` + `parseRetryAfter` (L89-92) 초 단위 정수만 인정 | `RateLimit(retryAfter)` | 통과 |
| E-104 | 서버 오류 (5xx) | ERR-006 | `ErrorMapper.kt:52` `500..599 → ServerError(code, "server error: $code")` | `ServerError(code, message)` | 통과 |
| E-105 | 응답 파싱 실패 | ERR-006 | `ErrorMapper.kt:70-73` `SerializationException → ServerError(code=-1, "response parse failed: ...")` + `ClaudeProvider.kt:141` 호출 | `ServerError(-1, message)` | 통과 |
| E-106 | 호출자 코루틴 취소 | (없음, 표준) | `AiAgentClient.kt:278-280` `catch (e: CancellationException) { throw e }` + `AnthropicHttpClient.kt:60-66` `cont.invokeOnCancellation { call.cancel() }` (cooperative) | (CancellationException 그대로 전파) | 통과 |
| E-107 | 빈 prompt | ERR-005 | `AiRequest.kt:37` `require(prompt.isNotBlank())` (M-001 init) → `IllegalArgumentException` → `AiAgentClient.kt:284-289` catch → `Result.failure(InvalidInput(...))` | `InvalidInput(message)` | 통과 (Q-1 참조 — ask() 진입 전 호출자 측에서 throw 되는 경우가 더 일반적이지만, 안전망 매핑이 정확) |
| E-108 | 타임아웃 | ERR-001 | `ErrorMapper.kt:68` `SocketTimeoutException → Network(throwable)` | `Network` (cause=SocketTimeoutException) | 통과 |
| E-109 | client closed | ERR-004 | `AiAgentClient.kt:253-257` `try { ensureNotClosed() } catch (e: AiException.Configuration) { return Result.failure(e) }` (R-020 케이스 B) | `Configuration("client closed")` 메시지 정확 일치 | 통과 |
| E-110 | 빈 응답 검증 실패 | ERR-006 | `AiAgentClient.kt:266-274` `text.isEmpty() && finishReason !in EMPTY_OK_FINISH_REASONS` → `ServerError(-1, "empty response")` | `ServerError(-1, "empty response")` | 통과 |

### 2.4 R-005 빈 응답 정책

| 입력 | 사양 결과 | 구현 결과 | 결과 |
|------|-----------|-----------|------|
| `text="" + END_TURN` | `Result.success` | `AiAgentClient.kt:266-277` `EMPTY_OK_FINISH_REASONS`에 포함 → success | 통과 |
| `text="" + STOP_SEQUENCE` | `Result.success` | 동일 | 통과 |
| `text="" + MAX_TOKENS` | `ServerError(-1, "empty response")` | E-110 분기 진입 | 통과 |
| `text="" + OTHER` | `ServerError(-1, "empty response")` | E-110 분기 진입 | 통과 |
| `text="hello" + END_TURN` (비-empty) | `Result.success` | `text.isEmpty()` 조건 미통과 → success | 통과 |

단위 테스트 (AskTest):
- `F-001 R-005 — 빈 텍스트 + END_TURN 이면 그대로 Result_success` (L125-139)
- `F-001 R-005 — 빈 텍스트 + STOP_SEQUENCE 이면 그대로 Result_success` (L142-154)
- `F-001 E-110 — 빈 텍스트 + MAX_TOKENS 이면 ServerError(-1, empty response)` (L157-177): `code==-1` + 메시지 `"empty response"` 모두 검증
- `F-001 E-110 — 빈 텍스트 + OTHER 이면 ServerError(-1, empty response)` (L180-194)

### 2.5 R-007 진입 시점 Provider 캡쳐 (useProvider 영향 없음)

| 요건 | 구현 | 결과 |
|------|------|------|
| ask 진입 시 활성 Provider를 지역 변수로 캡쳐 | `AiAgentClient.kt:259-261` `val provider: Provider = activeProvider; val config: ProviderConfig = currentProviderConfig()` (지역 변수 캡쳐) | 통과 |
| 진행 중 ask는 useProvider 영향 없음 | 캡쳐 후 `provider.complete(...)` 호출(L264) — 이후 `useProvider` 호출은 `providerIdRef`만 업데이트하고 캡쳐된 `provider`에 영향 없음 | 통과 |
| 단위 테스트 | `AskTest.kt:222-255` `F-001 R-007 — ask 진행 중 useProvider 호출은 현재 ask 의 Provider 에 영향 없음` — `CompletableDeferred`로 진행 중 ask 차단 후 `client.useProvider(ProviderId.CLAUDE)` 호출, gate 해제 후 `from-original` 응답 확인 | 통과 |

### 2.6 R-020 케이스 A — close 시점 in-flight ask는 CancellationException

| 요건 (features.md L404-407) | 구현 | 결과 |
|-----------------------------|------|------|
| 진행 중 ask는 cooperative cancel 지점에서 CancellationException throw | `AiAgentClient.kt:278-280` `catch (CancellationException) { throw e }` (Result로 감싸지 않음) + `AnthropicHttpClient.kt:60-66` `cont.invokeOnCancellation { call.cancel() }` cooperative | 통과 |
| Result.failure로 변환 안 됨 | 구현이 명시적으로 `throw e` (L280) — 하단 `catch (Throwable)` 안전망보다 먼저 매칭 | 통과 |
| 단위 테스트 | `AskTest.kt:262-288` `F-001 E-106 — ask 도중 cancel 되면 CancellationException 그대로 전파 (Result 로 감싸지 않음)` — `CompletableDeferred`로 차단 후 `deferred.cancel(...)` → `await()`가 `CancellationException` throw 검증 | 통과 |

### 2.7 R-020 케이스 B — close 후 새 ask는 Result.failure(Configuration("client closed"))

| 요건 (features.md L409-410) | 구현 | 결과 |
|-----------------------------|------|------|
| suspend 함수 진입 시 close 검증 → Result.failure | `AiAgentClient.kt:253-257` `try { ensureNotClosed() } catch (e: AiException.Configuration) { return Result.failure(e) }` (suspend 시맨틱 — throw 아닌 Result.failure) | 통과 |
| 메시지 `"client closed"` 정확 일치 | `AiAgentClient.kt:365` `throw AiException.Configuration("client closed")` (qa_report_2 검증 시 정확 일치 확인) | 통과 |
| 단위 테스트 | `AskTest.kt:201-215` `F-001 R-020 케이스 B — close 후 ask 호출 시 Result_failure(Configuration(client closed))` — `client.close()` → `client.ask(...)` → `result.exceptionOrNull() is AiException.Configuration` + 메시지 검증 | 통과 |

### 2.8 D-005 자동 재시도 없음 (즉시 실패)

| 요건 | 구현 | 결과 |
|------|------|------|
| RateLimit/Network 시 SDK는 자동 재시도 안 함 (호출자 책임) | `AiAgentClient.kt:264` `provider.complete(...)` 단일 호출, 재시도 루프 없음 | 통과 |
| 단위 테스트 | `AskTest.kt:326-343` `F-001 D-005 — Provider 가 RateLimit 를 throw 해도 자동 재시도 없이 즉시 Result_failure` — `AtomicInteger callCount`로 정확히 1회 호출 검증 (`assertEquals(1, callCount.get())`) | 통과 (D-005 명시 검증) |

### 2.9 ClaudeProvider.complete 본체 (P-CLAUDE)

| 요건 (provider-spec.md P-CLAUDE 상세) | 구현 위치 | 결과 |
|-------------------------------------|-----------|------|
| HTTP POST `/v1/messages` 호출 | `ClaudeProvider.kt:108-114` `Request.Builder().url(MESSAGES_URL).post(...)`. `MESSAGES_URL = "https://api.anthropic.com/v1/messages"` (L181) | 통과 |
| 헤더 `x-api-key` | `ClaudeProvider.kt:110` `header(HEADER_API_KEY = "x-api-key", config.apiKey)` (L186) | 통과 |
| 헤더 `anthropic-version` | `ClaudeProvider.kt:111` `header(HEADER_ANTHROPIC_VERSION = "anthropic-version", "2023-06-01")` (L184/187) | 통과 |
| 헤더 `Content-Type: application/json; charset=utf-8` | `ClaudeProvider.kt:112` `header("Content-Type", JSON_MEDIA_TYPE_VALUE)` (L188) — 명시 헤더 + RequestBody 둘 다 적용 | 통과 |
| 코루틴 취소 cooperative (suspendCancellableCoroutine + Call.cancel) | `AnthropicHttpClient.kt:56-79` `suspendCancellableCoroutine { cont -> ... cont.invokeOnCancellation { call.cancel() } }` | 통과 |
| 코루틴 취소 시 onFailure에서 resumeWithException 회피 | `AnthropicHttpClient.kt:74-77` `if (cont.isCancelled) return` — CancellationException이 우선 | 통과 |
| HTTP status 검증 (4xx/5xx → ErrorMapper) | `ClaudeProvider.kt:122-130` `ErrorMapper.fromHttpStatus(resp)` 호출 후 non-null이면 `throw httpError` | 통과 |
| 본문 파싱 실패 → SerializationException → ServerError(-1) | `ClaudeProvider.kt:140-142` `catch (e: SerializationException) { throw ErrorMapper.fromException(e) }` → `ErrorMapper.kt:70` `ServerError(-1, "response parse failed: ...")` (E-105) | 통과 |
| 4xx/5xx 본문 소비 (response leak 방지) | `ClaudeProvider.kt:128` `runCatching { resp.body?.string() }` (실패 무시) | 통과 |
| `response.use { ... }` close 보장 | `ClaudeProvider.kt:121` `response.use { resp -> ... }` | 통과 |

### 2.10 단위 테스트 — F-001 사양 ID ↔ 테스트 매트릭스

| 사양 ID | AskTest 케이스 | ErrorMapperTest 케이스 | MapperTest 케이스 | 결과 |
|---------|----------------|------------------------|-------------------|------|
| 정상 흐름 1~6 | "F-001 정상 흐름 — Provider가 정상 응답..." (L77-92), "config가 currentProviderConfig..." (L95-118) | (해당 없음) | "M-001 — AiRequest가 AnthropicMessagesRequest..." + "M-002 단일 text 블록..." | 통과 (정상 흐름 5단계 모두 단위 테스트로 보증) |
| R-005 빈 응답 OK | END_TURN/STOP_SEQUENCE 2개 (L125-154) | — | — | 통과 |
| E-101 네트워크 | "Provider 가 Network 를 throw 하면..." (L295-309) — pass-through | "E-101 — 일반 IOException은 Network로 매핑" (L149-156) cause 보존 | — | 통과 |
| E-102 401 | "Provider 가 Authentication을..." (L312-323) | "E-102 — HTTP 401은 Authentication" (L41-48) | — | 통과 |
| E-103 429 | (Provider pass-through 동일 검증) | 3개: 30초 파싱 (L51-63) / 헤더 없음 (L66-73) / HTTP-date null (L76-88) | — | 통과 (Retry-After 정책 명확 검증) |
| E-104 5xx | (Provider pass-through 동일) | 500 (L91-100) + 503 (L103-110) | — | 통과 |
| E-105 파싱 실패 | (Provider pass-through 동일) | "E-105 — SerializationException은 ServerError(code=-1)" (L159-171) | — | 통과 |
| E-106 취소 | "F-001 E-106 — ask 도중 cancel..." (L262-288) | — | — | 통과 |
| E-107 빈 prompt | "Provider가 IllegalArgumentException을... InvalidInput으로 매핑" (L350-363) (안전망) + AiRequest.init이 require로 사전 차단 (`AiRequest.kt:37`) | — | — | 통과 (Q-1 참조 — ask() 진입 시점에는 호출자가 이미 AiRequest를 만든 상태가 보통이지만, 안전망 매핑 검증) |
| E-108 타임아웃 | (pass-through) | "E-108 — SocketTimeoutException은 Network로 매핑" (L138-146) | — | 통과 |
| E-109 close | "F-001 R-020 케이스 B — close 후 ask..." (L201-215) | — | — | 통과 |
| E-110 빈 응답 | MAX_TOKENS/OTHER 2개 (L157-194) | — | — | 통과 |
| D-005 자동 재시도 없음 | "F-001 D-005 — RateLimit를 throw해도..." (L326-343) | — | — | 통과 (1회 호출 명시 검증) |
| R-007 캡쳐 | "F-001 R-007 — ask 진행 중 useProvider..." (L222-255) | — | — | 통과 |
| 안전망 (Throwable) | "예상치 못한 예외를 throw하면 ServerError로 매핑" (L366-384) | "예상치 못한 예외는 ServerError(code=-1)" (L174-181) | — | 통과 |
| 4xx 기타 (400) | — | "4xx 기타(400) — ServerError로 매핑" (L113-122) | — | 통과 (사양 외 4xx 일반 매핑 정책 명시) |
| 2xx → null | — | "2xx 응답은 null 반환" (L125-131) | — | 통과 |
| Mapper toAnthropicRequest | — | — | "M-001 정확히 매핑" (L35-55) + "modelId가 ProviderConfig에서만" (L58-69) | 통과 |
| Mapper toAiResponse text 블록 | — | — | 단일 (L76-95) + 다중 결합 (L98-111) + 비-text 블록 무시 (L114-128) | 통과 |
| Mapper finishReason 매핑 | — | — | end_turn/max_tokens/stop_sequence/unknown/null 5개 (L135-162) | 통과 (4개 enum 값 + null fallback) |
| Mapper usage null → (0,0) | — | — | "M-009 — usage null 이면 TokenUsage(0, 0)" (L169-180) | 통과 |
| Mapper 빈 content | — | — | "content가 비어있으면 text가 빈 문자열 (호출 측 E-110 검증)" (L183-194) | 통과 |

### 2.11 단위 테스트 컴파일 가능성 점검 (이론)

| 항목 | 검증 | 결과 |
|------|------|------|
| import 일관성 | `AskTest.kt:1-33` mockk + kotlinx.coroutines.test + junit + AtomicInteger 모두 build.gradle.kts:77-79 의존성 포함 | 통과 |
| `runTest` 사용 | 모든 suspend 테스트에서 `kotlinx.coroutines.test.runTest` 적용 (`AskTest.kt:23,77,...`) | 통과 |
| Provider Fake — `Provider` 인터페이스 시그니처 일치 | `AskTest.kt:410-446` `FakeProvider` — `id`, `capabilities`, `complete(request, config)`, `stream(...)` 모두 구현 | 통과 |
| `AiAgentClient` internal 생성자 호출 가능 | `AskTest.kt:395-402` 같은 모듈에서 internal 생성자 직접 호출 | 통과 (테스트 모듈은 같은 모듈에 위치) |
| `ProviderRegistry` internal 생성자 호출 가능 | `AskTest.kt:401` `ProviderRegistry(setOf(provider))` | 통과 |
| `Response.Builder` 사용 정합성 | `ErrorMapperTest.kt:188-210` `httpResponse(...)` 헬퍼 — Protocol/code/message/body/headers 모두 OkHttp `Response.Builder` 표준 | 통과 |
| `AnthropicMessagesResponse` 직접 인스턴스화 | `MapperTest.kt:77-86` 등 — `internal data class`이므로 같은 모듈 테스트에서 호출 가능 | 통과 |
| `ProviderConfig` 직접 인스턴스화 | `MapperTest.kt:41-45` `ProviderConfig(apiKey, modelId, timeout)` — `data class`이며 public | 통과 |
| Mock/Fake 사용의 적절성 — 실제 네트워크 호출 0회 | AskTest는 FakeProvider, ErrorMapperTest는 OkHttp Response.Builder 빌더(네트워크 없음), MapperTest는 SDK 모델 직접 — 38개 케이스 모두 실제 HTTP 호출 없음 | 통과 |

---

## 3. 단위 테스트 검증 표

### 3.1 AskTest.kt 14개 케이스 의미 검증

| # | 테스트 함수명 | 검증 의도 | 의미 있는 검증인가 | 결과 |
|---|---------------|-----------|-------------------|------|
| 1 | F-001 정상 흐름 — Provider가 정상 응답 | 정상 응답 → Result.success + completeCallCount=1 | Yes (정상 흐름 1~6 + Provider 호출 1회 보증) | 의미 있음 |
| 2 | F-001 정상 흐름 — config가 currentProviderConfig와 같은 값 | apiKey/modelId/timeout가 ProviderConfig로 정확히 전달 | Yes (Provider 추상화 — config 단일 source) | 의미 있음 |
| 3 | F-001 R-005 — 빈 텍스트 + END_TURN | EMPTY_OK_FINISH_REASONS 분기 | Yes | 의미 있음 |
| 4 | F-001 R-005 — 빈 텍스트 + STOP_SEQUENCE | 동일 | Yes (END_TURN과 STOP_SEQUENCE 분리 검증) | 의미 있음 |
| 5 | F-001 E-110 — 빈 텍스트 + MAX_TOKENS | code=-1 + 메시지 "empty response" | Yes (E-110 핵심 매핑) | 의미 있음 |
| 6 | F-001 E-110 — 빈 텍스트 + OTHER | code=-1 | Yes | 의미 있음 |
| 7 | F-001 R-020 케이스 B — close 후 ask | Configuration("client closed") + 메시지 정확 | Yes (R-020 케이스 B suspend 시맨틱) | 의미 있음 |
| 8 | F-001 R-007 — 진행 중 useProvider | 진행 중 ask가 캡쳐된 Provider로 응답, useProvider 영향 없음 | Yes (R-007 핵심) | 의미 있음 |
| 9 | F-001 E-106 — cancel | CancellationException 전파 (Result 미변환) | Yes (R-020 케이스 A) | 의미 있음 |
| 10 | F-001 — Provider Network throw | pass-through + cause 보존 (assertSame) | Yes | 의미 있음 |
| 11 | F-001 — Provider Authentication throw | pass-through | Yes | 의미 있음 |
| 12 | F-001 D-005 — RateLimit 1회 호출 | callCount==1 검증 | Yes (D-005 명시) | 의미 있음 |
| 13 | F-001 — IllegalArgumentException → InvalidInput | 안전망 매핑 + 메시지 보존 | Yes (E-107 안전망) | 의미 있음 |
| 14 | F-001 — 예상치 못한 예외 → ServerError | 안전망 매핑 (code=-1, "unexpected") | Yes (작업 원칙 6) | 의미 있음 |

**14개 모두 의미 있는 검증.** 사양 ID(F-001 정상/예외, R-005, R-007, R-020, D-005)를 빠짐없이 커버.

### 3.2 ErrorMapperTest.kt 12개 케이스 — HTTP/IO 예외 매핑 누락 점검

| # | 검증 대상 | 사양 ID | 누락 여부 |
|---|-----------|---------|----------|
| 1 | HTTP 401 → Authentication | E-102 | 커버 |
| 2 | HTTP 429 → RateLimit + Retry-After 30초 | E-103 | 커버 |
| 3 | HTTP 429 + Retry-After 없음 → null | E-103 정책 | 커버 |
| 4 | HTTP 429 + HTTP-date → null (v0.1 정책) | E-103 정책 | 커버 (v0.1 정책 명시 검증) |
| 5 | HTTP 500 → ServerError(500) | E-104 | 커버 |
| 6 | HTTP 503 → ServerError(503) | E-104 | 커버 |
| 7 | HTTP 400 → ServerError(400) | (4xx 일반) | 커버 (사양 외 정책 명시) |
| 8 | HTTP 200 → null | (정상) | 커버 |
| 9 | SocketTimeoutException → Network | E-108 | 커버 (cause 보존) |
| 10 | IOException → Network | E-101 | 커버 (cause 보존) |
| 11 | SerializationException → ServerError(-1) | E-105 | 커버 (메시지 "parse" 검증) |
| 12 | 예상치 못한 예외 → ServerError(-1) | (안전망) | 커버 |

**12개 모두 사양 ID와 1:1 또는 보강 매핑.** F-001의 HTTP/IO 예외 6종(E-101/E-102/E-103/E-104/E-105/E-108) 빠짐없이 커버.

### 3.3 MapperTest.kt 12개 케이스 — toAnthropicRequest/toAiResponse 토큰 매핑 정확성

| # | 검증 대상 | 사양 ID | 정확성 |
|---|-----------|---------|--------|
| 1 | toAnthropicRequest 4필드 매핑 (model/maxTokens/temperature/messages) | M-001 → API 요청 | 정확 (model="claude-opus-4-7", maxTokens=512, temperature=0.5f, role="user", content="안녕") |
| 2 | modelId 단일 출처 (ProviderConfig) | (R-014 인접 정책) | 정확 (claude-haiku-3-5 case로 검증) |
| 3 | 단일 text 블록 매핑 | M-002 | 정확 (text + finishReason + usage + providerId 모두) |
| 4 | 다중 text 블록 결합 | M-002 | 정확 ("part1 part2") |
| 5 | 비 text 블록(tool_use) 무시 | M-002 | 정확 (filter type=="text") |
| 6 | end_turn → END_TURN | M-002 enum | 정확 |
| 7 | max_tokens → MAX_TOKENS | M-002 enum | 정확 |
| 8 | stop_sequence → STOP_SEQUENCE | M-002 enum | 정확 |
| 9 | unknown → OTHER | M-002 enum fallback | 정확 |
| 10 | null → OTHER | M-002 enum fallback | 정확 (Mapper.kt:74-79 when else) |
| 11 | usage null → TokenUsage(0, 0) | M-009 방어 | 정확 |
| 12 | 빈 content → text="" | F-001 정상 흐름 4단계 (호출 측 E-110 검증) | 정확 (Mapper는 통과, 검증은 ask가 수행) |

**12개 모두 의미 있는 검증.** M-001/M-002/M-009/FinishReason 4종 enum 모두 커버. 빈 content 케이스가 F-001 4단계와의 책임 경계(Mapper는 통과, ask가 검증)를 명확히 함.

---

## 4. 모델 점검

### 4.1 M-001 AiRequest

| 필드 | 사양 (data-model.md L29-54) | 구현 (`AiRequest.kt:29-45`) | 일치 |
|------|------------------------------|------------------------------|------|
| prompt | String, 1자 이상 100,000자 이하 | `val prompt: String` + `require(prompt.isNotBlank())` + `require(prompt.length <= 100_000)` | 통과 |
| images | List\<ImageInput\>, 최대 10장 | `val images: List<ImageInput> = emptyList()` + `require(images.size <= 10)` | 통과 |
| videos | List\<VideoInput\>, v0.1 빈 리스트 강제 | `val videos: List<VideoInput> = emptyList()` + `require(videos.isEmpty())` | 통과 (사양보다 엄격) |
| maxTokens | Int, 기본 1024, 1~8192 | `val maxTokens: Int = 1024` + `require(maxTokens in 1..8192)` | 통과 |
| temperature | Float, 기본 0.7, 0.0~2.0 | `val temperature: Float = 0.7f` + `require(temperature in 0f..2f)` | 통과 |

추가 검증: 사양 init 블록 5개 require + v0.1 videos 강제 1개 = 6개 모두 구현. **사양 정합**.

### 4.2 M-002 AiResponse + FinishReason

| 필드 | 사양 (data-model.md L60-76) | 구현 (`AiResponse.kt:17-22`) | 일치 |
|------|------------------------------|------------------------------|------|
| text | String (빈 문자열 가능) | `public val text: String` | 통과 |
| usage | TokenUsage | `public val usage: TokenUsage` | 통과 |
| finishReason | FinishReason | `public val finishReason: FinishReason` | 통과 |
| providerId | ProviderId | `public val providerId: ProviderId` | 통과 |
| FinishReason enum | END_TURN, MAX_TOKENS, STOP_SEQUENCE, OTHER | `enum class FinishReason { END_TURN, MAX_TOKENS, STOP_SEQUENCE, OTHER }` (`AiResponse.kt:29-34`) | 통과 (4값 정확) |

### 4.3 M-009 TokenUsage

| 필드 | 사양 (data-model.md L252-259) | 구현 (`TokenUsage.kt:8-13`) | 일치 |
|------|--------------------------------|------------------------------|------|
| inputTokens | Int | `public val inputTokens: Int` | 통과 |
| outputTokens | Int | `public val outputTokens: Int` | 통과 |
| totalTokens | get() = inputTokens + outputTokens | `public val totalTokens: Int get() = inputTokens + outputTokens` | 통과 |

### 4.4 M-003 ImageInput / M-004 VideoInput — 본 라운드 확인

| 모델 | 본 라운드 변경 | 사양 정합 | 결과 |
|------|---------------|-----------|------|
| M-003 ImageInput | F-005 라운드에서 도입됨 (qa_report_2 §2.11). 본 라운드(F-001)에서 변경 없음 | 사양 그대로 (Uri/Bytes/Url, R-016 명시 equals/hashCode) | 통과 (사양 외 추가 없음) |
| M-004 VideoInput | F-005 라운드에서 도입됨. 본 라운드(F-001)에서 변경 없음 | 사양 그대로 (v0.2 placeholder) | 통과 |

본 라운드(F-001)에서는 ImageInput/VideoInput을 직접 사용하지 않고 AiRequest.images/videos 타입으로만 참조 (실제 사용은 F-002에서). **사양 외 추가 없음**.

### 4.5 ERR-XXX KDoc 매핑 (AiException)

| Variant | 사양 ERR | KDoc 매핑 (`AiException.kt`) | 결과 |
|---------|---------|-------------------------------|------|
| Network | ERR-001 | L33 "ERR-001 → Network", L34 "E-101 / E-108 / E-206(일부) / E-301 매핑" | 통과 |
| RateLimit | ERR-002 | L41 "E-103 매핑" | 통과 |
| Authentication | ERR-003 | L46 "ERR-003, HTTP 401" L47 "E-102 매핑" | 통과 |
| Configuration | ERR-004 | L52 "ERR-004", L54 "E-001 / E-002 / E-003 / E-109 / E-205 / E-303 / E-402 / E-501 / E-502 / E-602 / E-705 매핑" | 통과 |
| InvalidInput | ERR-005 | L61 "ERR-005", L63 "E-107 / E-201~E-204 / E-206(URL invalid) / E-207 / E-401 / E-702 / E-704 매핑" | 통과 |
| ServerError | ERR-006 | L69 "ERR-006", L72 "E-104 / E-105 / E-110 매핑" | 통과 |
| IOError | ERR-007 | L80 "ERR-007", L82 "E-701 / E-703 매핑" | 통과 |

**7개 variant 모두 ERR-XXX 1:1 매핑 + 본문에서 발생하는 E-XXX KDoc에 정확 명시**. error-handling.md L41-50 매핑 표와 정합.

---

## 5. 발견된 이슈

본 라운드 Blocker/Major 0건. Minor 4건은 모두 사양 동작에 영향 없거나 도달 불가 분기/문서 라벨 수준.

### Q-T11-1 [Minor] AskTest L350의 IllegalArgumentException → InvalidInput 안전망의 도달 의미

- **위치**: `AiAgentClient.kt:284-289` (catch IllegalArgumentException → `Result.failure(InvalidInput(...))`)
- **현상**: `AiRequest`의 init 블록(`AiRequest.kt:36-44`)이 require로 사전 검증하므로, 호출자가 `AiRequest("")`로 인스턴스를 만들 때 IllegalArgumentException이 ask() 진입 전에 throw 된다. 즉, 본 catch는 (a) 호출자가 try/catch로 감싸지 않고 ask() 호출 직전에 인스턴스를 만든 경우 ask() 코루틴 컨텍스트로 IAE가 흘러들어오는 케이스, 또는 (b) Provider 내부에서 IAE가 throw 되는 케이스에 매칭됨.
- **테스트 검증**: AskTest L350-363는 (b) 케이스(Provider가 IAE throw)를 검증 — 의미 있음.
- **사양 정합**: features.md F-001 E-107 ("빈 prompt → InvalidInput") 매핑은 정확. ask() 진입 시점의 빈 prompt는 사실상 (a) 케이스로 도달하기 어려우나 안전망 보존은 작업 원칙 6 "에러는 sealed class"와 부합.
- **권장**: 사양 명확화 — features.md F-001 E-107의 "발생 위치"를 "AiRequest.init 검증 (ask 진입 전 또는 Provider 내부)"으로 명시. 본 라운드 통과로 처리.
- **Severity 사유**: 안전망 catch 자체는 사양과 어긋나지 않음. 사양 표기가 "F-001 ask()"로 되어 있어 정확한 발생 위치 모호함만 존재.

### Q-T11-2 [Minor] AiAgentClient.ask catch 순서 — `AiException` 매칭이 RateLimit cause 검사를 가림

- **위치**: `AiAgentClient.kt:281-298` catch 블록 순서 — `CancellationException` → `AiException` → `IllegalArgumentException` → `Throwable`
- **현상**: 정상적인 동작이며, AskTest의 12번 케이스("F-001 D-005 RateLimit")가 `is RateLimit`을 검증하므로 정합. 다만 `AiException.RateLimit`은 `class RateLimit(val retryAfter: Duration?) : AiException()`이라 message가 비어있어 `result.exceptionOrNull()?.message`가 null. 이는 사양상 정합(M-005 표 RateLimit는 `retryAfter`만 보유)이지만 호출자가 메시지로 분기하면 안 된다는 점은 사양 명시가 부족.
- **사양 정합**: `data-model.md L143` "Variant | 필드"의 RateLimit는 `retryAfter: Duration?` 만 — 사양과 일치. error-handling.md L31 "Rate limit exceeded. Retry after {duration}." 권장 메시지는 호출자가 자체 i18n으로 만들 것을 사양에 명시(L26).
- **권장**: 사양 보완 불필요. KDoc(`AiException.RateLimit`)에 "message 없음, retryAfter만 사용" 한 줄 추가 권장. 본 라운드 통과로 처리.
- **Severity 사유**: 코드/단위 테스트 모두 사양 정합. 호출자 개발자 경험(DX) 차원의 KDoc 보완 수준.

### Q-T11-3 [Minor] Mapper.toAnthropicRequest의 temperature 직렬화 정책

- **위치**: `Mapper.kt:34-44` `toAnthropicRequest` + `AnthropicMessagesRequest.kt:22-28` (temperature는 `Float? = null`)
- **현상**: `AiRequest.temperature`는 기본값 0.7f이고 nullable이 아니다. Mapper는 그대로 `temperature = request.temperature`로 전달하여, AnthropicJson(`encodeDefaults=false, explicitNulls=false`)에 의해 null 회피는 가능하나 0.7f는 항상 명시값이라 직렬화에 항상 포함된다. Anthropic API는 temperature 미설정 시 모델 기본값을 사용하므로, 사용자가 설정 안 한 0.7f가 항상 전송되는 것과 사용자가 명시적으로 0.7f를 설정한 경우를 구분할 수 없다.
- **사양 정합**: data-model.md M-001 "기본 0.7" — 명시적 기본값을 정의했으므로 항상 0.7 전송도 정합. 사양 위반 아님.
- **권장**: 본 라운드 통과로 처리. v0.2에서 Optional<Float> 또는 null 기본값으로 변경 검토 가능 (사양 변경 필요).
- **Severity 사유**: 사양 정합. 호출자 측 동작 변경 없음 — 0.7f가 기본값이라는 것은 사양·구현·문서에서 일관됨.

### Q-T11-4 [Minor] MapperTest L182의 함수명 라벨 ("M-002") vs 의미 ("usage null → 0,0" = M-009)

- **위치**: `MapperTest.kt:159-180` 일부 라벨 점검
  - L169 `M-009 — usage null 이면 TokenUsage(0, 0) 으로 매핑` ✓ 정확
  - L182 `M-002 — content 가 비어있으면 text 가 빈 문자열 (호출 측 E-110 검증)` ✓ 정확 (M-002 text 필드 검증)
- **현상**: 점검 결과 라벨링은 모두 정확. 본 항목은 검토 결과 **이슈 아님**으로 처리. (impl_summary_4 §3 케이스 목록에서도 라벨 정확.)
- **권장**: 변경 없음. 본 항목은 검증을 위해 명시적으로 점검했음을 기록하는 정보성 항목.

### Q-T11-5 [정보성, 보고만] ClaudeProvider.complete의 catch (Throwable) → ErrorMapper.fromException 이중 안전망

- **위치**: `ClaudeProvider.kt:143-145` `catch (e: Throwable) { throw ErrorMapper.fromException(e) }`
- **현상**: SerializationException은 명시 catch(L141)에서, IOException은 AnthropicHttpClient.execute에서 이미 변환됨. `catch (Throwable)`는 안전망으로 ServerError(-1) "unexpected error..."를 만든다. 동시에 `AiAgentClient.ask` L290-298도 `catch (Throwable)`로 ServerError(-1) "unexpected error..."를 만들기에, 이중 안전망. 둘 다 같은 결과를 만들지만 메시지에 차이가 있을 수 있음(ClaudeProvider는 ErrorMapper의 메시지, ask는 자체 메시지).
- **사양 정합**: 사양은 외부 예외를 AiException으로 변환하라고 요구할 뿐 어디서 변환하는지는 미명시. 정합 여부 영향 없음.
- **판단**: 적절한 결정. defense in depth.

---

## 6. 사양 명확화 필요 항목 (spec-architect 회신 요청)

### S-T11-1 [Minor] features.md F-001 E-107 발생 위치 명확화

- **현 사양**: features.md L87 "E-107 빈 prompt → Result.failure(AiException.InvalidInput)" / error-handling.md L67 "E-107 (빈 prompt) | F-001 ask() | ERR-005"
- **권장 보강**: E-107의 발생 위치를 "AiRequest.init 검증 (ask 진입 전 호출자 측 throw 가능 / Provider 내부 IAE의 SDK 레벨 안전망)"으로 명시.
- **영향**: 본 보강 없이도 코드는 사양 정합. AskTest의 안전망 검증 의미가 명확해짐.

(Q-T11-2/Q-T11-3/Q-T11-4는 사양 변경 불필요 — KDoc/판단/라벨 점검 항목.)

---

## 7. F-001 종결 권고

### F-001 종결

**통과 — Blocker 0건, Major 0건**.

- A-002 시그니처 (`suspend fun ask(request: AiRequest): Result<AiResponse>`)가 api.md와 토큰 단위 일치.
- F-001 정상 흐름 1~6단계가 코드(AiAgentClient.ask + ClaudeProvider.complete + Mapper)에 차례로 매핑.
- E-101~E-110 모든 예외 흐름이 ErrorMapper / AnthropicHttpClient / ClaudeProvider / AiAgentClient.ask catch 체인에 정확히 매핑.
- R-005 빈 응답 정책 (END_TURN/STOP_SEQUENCE OK, MAX_TOKENS/OTHER 거부)이 EMPTY_OK_FINISH_REASONS로 구현, 단위 테스트 4개로 검증.
- R-007 진행 중 useProvider 영향 없음 — 지역 변수 캡쳐로 보증, CompletableDeferred 단위 테스트 검증.
- R-020 케이스 A (CancellationException 전파) + 케이스 B (Result.failure(Configuration("client closed"))) 단위 테스트로 모두 검증, 메시지 정확 일치.
- D-005 자동 재시도 없음 — 단위 테스트가 callCount==1로 명시 검증.
- ERR-001~ERR-006 6종 variant + ERR-007(F-007) KDoc 매핑이 error-handling.md 표와 1:1 정합.
- ClaudeProvider.complete가 P-CLAUDE 사양(POST /v1/messages, 3개 헤더, 코루틴 cooperative cancel, status 검증, 본문 파싱) 모두 충족.
- AnthropicHttpClient의 suspendCancellableCoroutine + Call.cancel() 패턴이 E-106 cooperative cancellation 보장.
- 단위 테스트 38개 모두 의미 있는 검증, 컴파일 가능 형태(이론), 실제 네트워크 호출 0회 (FakeProvider/Response.Builder/SDK 모델 직접).
- M-001/M-002/M-009 + FinishReason 모두 data-model.md와 토큰 단위 일치.
- Minor 4건은 모두 사양 동작 영향 없음 (안전망 catch 의미, KDoc 보완, 정책 명시 권장).

### 다음 wave 진입 권고

| 라운드 | 의존성 | 진입 가능 여부 | 비고 |
|--------|--------|----------------|------|
| **F-002 (멀티모달 이미지)** | F-001 (ask 본체 + Mapper) + F-005 Capabilities | **진입 가능** | impl_summary_4 §5 인계 가이드 정확. ImageInput.Bytes의 base64 인코딩 위치는 `Mapper.toAnthropicRequest` 내부의 content 확장. AnthropicMessage.content 타입을 String → JsonElement 또는 sealed ContentBlock 다형 직렬화로 변경(P-001 인터페이스 변경 없이 SDK 모델 확장만). E-201/E-202/E-203/E-204/E-205/E-206/E-207 매핑 추가 필요. |
| **F-003 (askStream)** | F-001 (HTTP 클라이언트 패턴) + M-006 + R-020 케이스 A·B | **진입 가능** | impl_summary_4 §5 가이드. ClaudeProvider.stream의 NotImplementedError를 SSE 파서로 교체. internal/network/AnthropicSseParser.kt 신규. callbackFlow + awaitClose { call.cancel() } 또는 currentCoroutineContext().isActive 패턴. E-301/E-302/E-303 매핑. M-006 방출 순서 (Delta 0+ → Done|Error 1) 보증. |
| **F-004 (Session)** | F-001 (ask) + F-005 (R-014) | **진입 가능** | M-007/M-008 추가. session.send 진입 시 client.activeProvider atomic get(R-014). Mutex로 동시 send 직렬화(E-403). systemPrompt는 history 미포함(R-008). E-401(컨텍스트 초과)는 Anthropic 에러 응답에서 감지 → InvalidInput 매핑. |
| **F-006 (Hilt)** | F-001 (httpClient 사용 위치 확정) | **진입 가능** | sdk/src/main/kotlin/com/androidailab/aisdk/di/SdkModule.kt 신규. @Provides for AiAgentClient/ProviderRegistry/Set\<Provider\>(@IntoSet ClaudeProvider)/AnthropicHttpClient/OkHttpClient. AiAgentClient.builder(context) 정적 팩토리는 Hilt 미사용 진입경로로 유지. |
| **F-007 (영속화)** | F-004 | F-004 후 진입 | M-011 + DataStore. ensureNotClosed 헬퍼 재사용(E-705). schemaVersion=1 강제(R-018). 1MB 한계(E-704). |

### 다음 액션 (오케스트레이터에게)

1. **android-implementer**: F-002/F-003/F-004/F-006 중 우선순위(P0) 라운드부터 진입. 4개는 의존성상 병행 가능하나 F-002·F-003가 ClaudeProvider 본체에 직접 변경을 가하므로 순차 진행 권장 (예: F-002 → F-003 → F-004 → F-006).
2. **spec-architect**: S-T11-1 보강 검토 (선택, 다음 라운드 진입은 차단하지 않음).
3. **F-001 자체 종결** — 본 라운드 추가 작업 불필요.

---

## 8. 자체 체크리스트

- [x] 4쌍의 경계면 교차 비교 완료 (api.md A-002 ↔ AiAgentClient.ask, data-model.md M-001/M-002/M-009 ↔ model/*.kt, error-handling.md ERR-001~ERR-006 ↔ AiException variant + KDoc 매핑, features.md F-001 E-101~E-110 ↔ AskTest/ErrorMapperTest/MapperTest 38개)
- [x] F-001 정상 흐름 1~6단계 모두 코드에 매핑 + 단위 테스트로 보증
- [x] F-001 모든 E-XXX(E-101~E-110) → ErrorMapper / AnthropicHttpClient.execute / AiAgentClient.ask catch 체인 / AiRequest.init / EMPTY_OK_FINISH_REASONS 로 매핑됨, 단위 테스트로 커버
- [x] api.md A-002 시그니처 토큰 단위 비교 (suspend / 파라미터 / 반환 타입 / visibility 모두 일치)
- [x] AiException 7개 variant ↔ ERR-001~ERR-007 1:1 매핑 + ERR-XXX KDoc 매핑 검증
- [x] R-005 빈 응답 정책 단위 테스트 4개 (END_TURN/STOP_SEQUENCE OK, MAX_TOKENS/OTHER 거부)
- [x] R-007 진행 중 useProvider 영향 없음 — CompletableDeferred 기반 단위 테스트 검증
- [x] R-020 케이스 A (CancellationException 전파) + 케이스 B (Result.failure(Configuration("client closed"))) 모두 검증
- [x] D-005 자동 재시도 없음 — callCount==1 명시 검증
- [x] ClaudeProvider.complete의 HTTP POST + 3개 헤더 + suspendCancellableCoroutine + Call.cancel + status 검증 + 본문 파싱 모두 검증
- [x] 단위 테스트 38개 의미 있는 검증 + 컴파일 가능 형태 + 실제 네트워크 호출 0회 (Mock/Fake 사용 적절)
- [x] M-001/M-002/M-009/FinishReason/M-003/M-004 모델이 사양과 토큰 단위 일치 (사양 외 추가 없음)
- [x] 모든 이슈에 (file:line) 근거 명시
- [x] 사양 모호함 항목 별도 섹션(6번)으로 spec-architect 회신 요청
- [x] 코드/사양 직접 수정하지 않음
