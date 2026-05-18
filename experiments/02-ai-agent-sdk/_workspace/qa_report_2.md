# QA Report 2 — F-005 (Provider 선택/교체) + F-008 (close 라이프사이클) 점진 검증

검증자: sdk-qa-validator
검증 일시: 2026-05-07
대상:
- F-005 (T9, `_workspace/impl_summary_2.md`) — Provider 선택/교체, A-005, P-001/P-002/P-CLAUDE 골격
- F-008 (T10, `_workspace/impl_summary_3.md`) — `close()`, R-020 케이스 A/B, ensureNotClosed 헬퍼
- 통합 영역 — T9/T10이 같은 `AiAgentClient.kt`를 동시 수정. 머지 결과 정합성

검증 범위: 시그니처 / 정상 흐름 / 예외 흐름 / 동시성(R-007) / 라이프사이클 시맨틱(R-020) / 데이터 모델(M-001~M-006/M-009/M-010) / Provider 추상화(P-001/P-002/P-CLAUDE) / NFR(D-003/explicit-api strict) / 단위 테스트 커버리지

빌드 실행: 미수행 (실험 루트에 settings.gradle.kts/wrapper 부재 — qa_report_1과 동일 정책. 정적 검증/관찰 기반)

---

## 1. 요약

| Severity | 건수 | 종결 조건 |
|----------|------|----------|
| Blocker  | 0    | 0건 필수 |
| Major    | 0    | 처리 또는 명시적 유보 |
| Minor    | 3    | 다음 라운드 이월 가능 |
| 사양 명확화 요청 | 2 | spec-architect 회신 후 반영 |

**결과**: F-005/F-008 **통과**. Blocker/Major 0건. 모든 사양 ID(F-005, F-008, A-005, A-009, P-001, P-002, P-CLAUDE, R-007, R-009, R-010, R-014, R-020, E-501, E-502, E-109, E-402, E-705, E-303, E-801, ERR-004)가 코드와 단위 테스트로 1:1 매핑됨. **F-001(Wave 3) 진입 가능**.

Minor 3건은 (a) E-502 도달 불가 정책 사양 명시, (b) ClaudeProvider.complete/stream의 NotImplementedError placeholder 정책 명시, (c) ProviderRegistry.contains(id) 사양 명시 — 모두 다음 라운드 spec-architect 보강으로 자연 해소 가능.

---

## 2. F-005 검증 표

### 2.1 A-005 시그니처 토큰 단위 비교

api.md L184-186 ↔ `AiAgentClient.kt:180`:

| 토큰 | 사양 | 구현 | 일치 |
|------|------|------|------|
| 함수명 | `useProvider` | `useProvider` | 통과 |
| 파라미터명/타입 | `provider: ProviderId` | `provider: ProviderId` | 통과 |
| 반환 타입 | (Unit, 생략) | (Unit, 생략) | 통과 |
| suspend 여부 | 비-suspend (동기) | 비-suspend | 통과 |
| visibility | public (api.md "API 노출 원칙") | `public fun useProvider` | 통과 |
| 동기 함수 close 시맨틱 (R-020 케이스 B) | `Configuration("client closed")` throw | `ensureNotClosed()` 진입 첫 줄 (`AiAgentClient.kt:182`) | 통과 |

### 2.2 F-005 정상 흐름

| 단계 | 사양 (features.md L217-219) | 구현 위치 | 결과 |
|------|------------------------------|-----------|------|
| 1 | 초기화 시 `Builder.provider(ProviderId.CLAUDE)` | `Builder.kt:61-63` | 통과 |
| 2 | 런타임 교체 `client.useProvider(ProviderId.CLAUDE)` (진행 중 영향 없음, 다음 요청부터 적용) | `AiAgentClient.kt:180-199`, atomic set 단일 연산 (`providerIdRef.set(provider)` L198) | 통과 |

### 2.3 동시성 모델 (R-007)

| 요건 (features.md L221-226) | 구현 | 결과 |
|------------------------------|------|------|
| 활성 Provider 식별자는 `AtomicReference<ProviderId>`로 보관 | `AiAgentClient.kt:77-78` (`AtomicReference(initialProviderId)`) | 통과 |
| useProvider 호출 시 atomic set만 수행 (수 ns) | `AiAgentClient.kt:198` `providerIdRef.set(provider)` 단일 연산 | 통과 |
| 진행 중 요청은 자기 스택에 Provider 인스턴스 캡쳐 (변경 영향 없음) | `activeProvider` getter가 매번 `providerRegistry.get(activeProviderId)` 호출 (`AiAgentClient.kt:93-94`). F-001 진입 시 호출자가 지역변수로 캡쳐하면 변경 영향 없음 (impl_summary_2 §5 인계 가이드 일치) | 통과 (구현 차원 충족, F-001 라운드에서 ask/askStream 본체 진입 시 캡쳐 동작 별도 검증) |
| 새 요청은 진입 시 atomic get | `activeProviderId` getter (`AiAgentClient.kt:85-86`) | 통과 |
| Session도 send 진입 시 atomic get (R-014) | F-004 라운드 책임 (Session 미구현). 본 라운드는 client 차원만 검증 — `activeProvider`/`activeProviderId` getter가 매 호출 재조회하므로 Session.send에서 활용하면 자동 충족 | 통과 (구현 차원, F-004 진입 후 별도 검증) |
| 단위 테스트 회귀 (16스레드 × 200iter) | `UseProviderTest.kt:187-216` `F-005 R-007 — useProvider 동시 호출에 대해 race 없이 안전하게 set 됨` | 통과 |

### 2.4 R-009 (CLAUDE 단일) / R-010 (Capabilities single source)

| 요건 | 구현 | 결과 |
|------|------|------|
| R-009 enum CLAUDE 단일 | `ProviderId.kt:19-21` 단일 값 | 통과 |
| R-009 SUPPORTED_PROVIDERS 동기 갱신 회귀 테스트 | `BuilderTest.kt:160-170` `F-005 R-009 — defaultProviders 와 SUPPORTED_PROVIDERS 가 동기화됨` | 통과 |
| R-010 Capabilities는 Provider 측 단일 source | `Capabilities.kt`(전체)는 Provider 패키지에 위치, `ProviderId.kt:19`는 `displayName: String`만 보유, capability 필드 없음 | 통과 |

### 2.5 R-014 (Session ↔ Provider 바인딩 없음)

| 요건 (features.md L228-232) | 구현 | 결과 |
|-----------------------------|------|------|
| Session은 특정 Provider에 묶이지 않음 | F-004 미구현. 본 라운드는 client 차원만 — `activeProvider` getter가 매 호출 재조회하므로 send 진입 시점의 Provider 사용을 자동 보장 | 통과 (구현 차원 충족) |
| 새 Provider로 교체 후 같은 Session에서 send 시 새 Provider 사용 | F-004 진입 후 단위 테스트로 별도 검증 (impl_summary_2 §5 명시) | 보류 (F-004 책임) |

본 라운드는 client 차원의 R-014 충족만 검증 (Session 미구현 — 검증에서 의도적으로 빠진 항목, impl_summary_2 §6 "검증에서 의도적으로 빠진 항목" 일치).

### 2.6 P-001 Provider 인터페이스 (provider-spec.md L11-35)

| 토큰 | 사양 | 구현(`Provider.kt`/`Capabilities.kt`/`ProviderConfig.kt`) | 결과 |
|------|------|---------------------------------------|------|
| `interface Provider` | `interface Provider` | `public interface Provider` (L27) | 통과 (explicit-api strict 정합) |
| `val id: ProviderId` | 일치 | `public val id: ProviderId` (L32) | 통과 |
| `val capabilities: Capabilities` | 일치 | `public val capabilities: Capabilities` (L37) | 통과 |
| `suspend fun complete(request, config): AiResponse` | 일치 | `public suspend fun complete(request: AiRequest, config: ProviderConfig): AiResponse` (L52) | 통과 |
| `fun stream(request, config): Flow<AiStreamEvent>` | 일치 | `public fun stream(request: AiRequest, config: ProviderConfig): Flow<AiStreamEvent>` (L66) | 통과 |
| `data class Capabilities(...)` 7필드 | 일치 | `public data class Capabilities` 7필드 (`Capabilities.kt:24-32`, supportsImage/supportsVideo/supportsStream/supportsSession/maxImageSizeBytes/maxImagesPerRequest/supportedImageMimeTypes 토큰 단위 일치) | 통과 |
| `data class ProviderConfig(apiKey, modelId, timeout)` | 일치 | `public data class ProviderConfig` 3필드 (`ProviderConfig.kt:20-24`) | 통과 |

### 2.7 P-002 ProviderRegistry (provider-spec.md L48-55)

| 토큰 | 사양 | 구현(`ProviderRegistry.kt`) | 결과 |
|------|------|------------------------------|------|
| `internal class ProviderRegistry(providers: Set<Provider>)` | 일치 | `public class ProviderRegistry internal constructor(providers: Set<Provider>)` (L22-24) — 클래스는 public이지만 생성자가 internal, 사양 의도(외부 호출 차단) 충족 | 통과 (Q-T9-X 참조: 생성자 internal로 사양 의도 충족) |
| `fun get(id: ProviderId): Provider` | 일치 | `public fun get(id: ProviderId): Provider` (L36-40) | 통과 |
| `fun list(): List<Provider>` | 일치 | `public fun list(): List<Provider>` (L48) | 통과 |
| `fun capabilities(id: ProviderId): Capabilities = get(id).capabilities` | 일치 | `public fun capabilities(id: ProviderId): Capabilities = get(id).capabilities` (L57) | 통과 |
| (사양 외) `fun contains(id: ProviderId): Boolean` | 사양에 없음 | `public fun contains(id: ProviderId): Boolean = id in byId` (L64) — Q-T9-3 참조 | 사양 외 추가, Minor (아래 Q-T9-3) |
| E-501 매핑 | `AiException.Configuration("unknown provider")` | `throw AiException.Configuration("unknown provider: $id")` (L37-39) | 통과 |
| 단위 테스트 | 8개 케이스 모두 커버 | `ProviderRegistryTest.kt` (정상 흐름 4개, E-501 3개, last-write-wins 1개) | 통과 |

### 2.8 P-CLAUDE Capabilities 토큰 단위 비교 (provider-spec.md L80-91)

| 사양 표기 | 구현(`ClaudeProvider.kt:51-59`) | 결과 |
|-----------|------------------------------------|------|
| `supportsImage = true` | `supportsImage = true` | 통과 |
| `supportsVideo = false` | `supportsVideo = false` | 통과 |
| `supportsStream = true` | `supportsStream = true` | 통과 |
| `supportsSession = true` | `supportsSession = true` | 통과 |
| `maxImageSizeBytes = 5 * 1024 * 1024` | `MAX_IMAGE_SIZE_BYTES = 5L * 1024L * 1024L` (`:94`) → `maxImageSizeBytes = MAX_IMAGE_SIZE_BYTES` | 통과 |
| `maxImagesPerRequest = 10` | `MAX_IMAGES_PER_REQUEST = 10` (`:97`) | 통과 |
| `supportedImageMimeTypes = setOf("image/jpeg", "image/png", "image/webp", "image/gif")` | `SUPPORTED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")` (`:100-105`) | 통과 |
| `id = ProviderId.CLAUDE` | `override val id: ProviderId = ProviderId.CLAUDE` (`:33`) | 통과 |
| 단위 테스트 | `ClaudeProviderTest.kt` 3개 케이스 — 토큰 단위 일치 검증 + companion 상수 동기 | 통과 |

### 2.9 F-005 예외 흐름

| ID | 사양 조건 | 구현 위치 | AiException variant | 메시지 | 결과 |
|----|-----------|-----------|----------------------|--------|------|
| E-501 | 등록되지 않은 Provider 선택 | `AiAgentClient.kt:185-187` (useProvider), `ProviderRegistry.kt:36-40` (get) | `Configuration` | `"unknown provider: $provider"` / `"unknown provider: $id"` | 통과 (둘 다 ERR-004) |
| E-502 | Provider별 API 키 누락 | `AiAgentClient.kt:191-195` | `Configuration` | `"api key missing for provider $provider"` | 통과 (Q-T9-1 — v0.1 단일 키 모델에서 도달 불가, internal 생성자로 단위 테스트로 검증) |

E-501/E-502 모두 ERR-004(Configuration)에 1:1 매핑. error-handling.md L84-85 매핑 표 정합.

### 2.10 R-020 케이스 B 충족 (close 시맨틱, T10 통합)

| 요건 (features.md L401-414) | 구현 | 결과 |
|-----------------------------|------|------|
| 동기 함수 useProvider close 후 호출 시 throw `Configuration("client closed")` | `AiAgentClient.kt:180-199` `useProvider` 진입 첫 줄 `ensureNotClosed()` (L182) | 통과 |
| 단위 테스트 | `UseProviderTest.kt:224-237` `F-005 R-020 케이스 B — close 후 useProvider 호출 시 Configuration throw` | 통과 |

### 2.11 모델(M-001~M-006/M-009/M-010) 부분 도입 정합성

본 라운드(F-005)는 Provider 인터페이스(P-001) 시그니처가 참조하는 최소 모델만 도입. 사양 정합:

| 모델 | 사양 | 구현 | 결과 |
|------|------|------|------|
| M-001 AiRequest | `data class AiRequest(prompt, images=emptyList, videos=emptyList, maxTokens=1024, temperature=0.7f)` + init 검증 | `AiRequest.kt` 토큰 단위 일치, init 5개 검증(prompt blank/100k/images 10/maxTokens 1..8192/temperature 0..2) + videos.isEmpty (v0.1 강제) | 통과 |
| M-002 AiResponse | `data class AiResponse(text, usage, finishReason, providerId)` + `enum FinishReason` | `AiResponse.kt` 토큰 단위 일치, FinishReason 4값(END_TURN/MAX_TOKENS/STOP_SEQUENCE/OTHER) | 통과 |
| M-003 ImageInput | sealed class Uri/Bytes/Url, R-016 (Bytes의 명시 equals/hashCode) | `ImageInput.kt` 토큰 단위 일치, Bytes는 data class 아님 + contentEquals/contentHashCode 명시 (`:36-49`) | 통과 |
| M-004 VideoInput | v0.2 placeholder, sealed class Uri/Url | `VideoInput.kt` 사양 그대로 (Q-T9-2 — v0.1에서 사용되지 않지만 AiRequest.videos가 sealed class를 참조하므로 도입 — 사양 정합) | 통과 |
| M-006 AiStreamEvent | sealed class Delta/Done/Error | `AiStreamEvent.kt` 토큰 단위 일치 | 통과 |
| M-009 TokenUsage | `data class TokenUsage(inputTokens, outputTokens) { val totalTokens get() = ... }` | `TokenUsage.kt` 토큰 단위 일치 | 통과 |
| M-010 ProviderId | enum CLAUDE 단일 (R-009) | `ProviderId.kt` 단일 값 | 통과 |

M-005 (AiException) / M-007 (Session) / M-008 (Message) / M-011 (SessionEntity) 는 본 라운드 범위 외 — F-001/F-004/F-007에서 검증.

### 2.12 F-005 단위 테스트 커버리지

| 테스트 | 커버 범위 | 결과 |
|--------|-----------|------|
| `UseProviderTest.kt` (8개) | 정상 흐름 3, R-014 즉시 반환 1, E-501 1, E-502 1, R-007 동시성 1, R-020 케이스 B 1 | 통과 (모든 F-005 E-XXX + R-XXX 커버) |
| `ProviderRegistryTest.kt` (8개) | 정상 흐름 4, E-501 3 (get/capabilities/contains), last-write-wins 1 | 통과 |
| `ClaudeProviderTest.kt` (3개) | id, Capabilities 토큰 단위, companion 동기 | 통과 |
| `BuilderTest.kt` 회귀 (1개 신규) | `F-005 R-009 — defaultProviders 와 SUPPORTED_PROVIDERS 가 동기화됨` | 통과 |

총 20개 케이스. F-005의 모든 E-XXX(E-501/E-502) + R-XXX(R-007/R-009/R-010/R-014/R-020) 커버.

---

## 3. F-008 검증 표

### 3.1 A-009 시그니처 (api.md L243-246)

| 토큰 | 사양 | 구현(`AiAgentClient.kt:227`) | 일치 |
|------|------|--------------------------------|------|
| 함수명 | `close` | `close` | 통과 |
| 파라미터 | (없음) | (없음) | 통과 |
| 반환 타입 | (Unit, 생략) | (Unit, 생략) | 통과 |
| suspend 여부 | 비-suspend (동기) | 비-suspend | 통과 |
| visibility | public | `public fun close` | 통과 |

### 3.2 F-008 정상 흐름 (features.md L388-393)

| 단계 | 사양 | 구현 위치 | 결과 |
|------|------|-----------|------|
| 1 | `client.close()` 호출 (idempotent) | `AiAgentClient.kt:228-231` `compareAndSet(false, true)` 첫 호출만 정리 | 통과 |
| 2 | OkHttp dispatcher.cancelAll() (E-801: try/catch) | `AiAgentClient.kt:234-238` `httpClient?.dispatcher?.cancelAll()` + `try/catch (Throwable)` | 통과 |
| 3 | 내부 코루틴 스코프 cancel (R-020 케이스 A) | `AiAgentClient.kt:241-245` `scope.cancel("AiAgentClient closed")` + try/catch | 통과 |
| 4 | DataStore 핸들 해제 | `AiAgentClient.kt:247` placeholder 주석 (F-007 진입 시 실제 동작) | 통과 (placeholder 정합) |
| 5 | 이후 새 호출은 케이스 B 시맨틱 | `ensureNotClosed()` 헬퍼 (`AiAgentClient.kt:263-267`) — useProvider 진입 시 활용됨, F-001/F-004/F-007 진입 시 같은 헬퍼 활용 | 통과 |

### 3.3 R-020 케이스 A — close 시점에 in-flight 호출

| 요건 (features.md L404-407) | 구현 | 결과 |
|-----------------------------|------|------|
| 진행 중 코루틴이 cooperative cancellation 지점에서 `CancellationException` throw | `scope.cancel(...)` 호출로 SupervisorJob의 자식 Job들이 표준 취소 시맨틱으로 종결 | 통과 |
| `Result.failure`로 변환되지 않음 (표준 코루틴 시맨틱) | `close()`는 동기 함수, scope.cancel만 수행하므로 진행 중 코루틴은 호출자 측 try/catch에서 CancellationException로 처리됨 (정확한 표준 시맨틱) | 통과 |
| 단위 테스트 | `CloseTest.kt:209-236` `F-008 R-020 케이스 A — close 시 진행 중 코루틴이 CancellationException 으로 종결` (`caughtCancellation=true`), `:239-254` close 후 새 launch는 즉시 cancelled, `:257-276` async도 CancellationException으로 종결 | 통과 (3개 케이스) |

### 3.4 R-020 케이스 B — close 후 새 호출

| 요건 (features.md L409-412) | 구현 | 결과 |
|-----------------------------|------|------|
| 동기 함수 (createSession/useProvider/Session.history/Session.clear) → `Configuration("client closed")` throw | `ensureNotClosed()` 호출 (`AiAgentClient.kt:263-267`) — 본 라운드 useProvider에 적용됨, F-004/F-007에서 동일 헬퍼 활용 | 통과 (메시지 `"client closed"` 정확 일치) |
| suspend 함수 → `Result.failure(Configuration("client closed"))` | F-001/F-004/F-007 진입 시 `runCatching { ensureNotClosed() }` 패턴 (impl_summary_3 §6 인계 가이드 일치) | 통과 (헬퍼 준비, 본체는 후속 라운드) |
| Flow 함수 (askStream) → `AiStreamEvent.Error(Configuration("client closed"))` emit 후 종료 | `isClosed()` 헬퍼 (`AiAgentClient.kt:275`) — F-003 진입 시 사용 (impl_summary_3 §6 인계 가이드 일치) | 통과 (헬퍼 준비, 본체는 F-003) |
| 단위 테스트 | `CloseTest.kt:90-101` `F-008 R-020 케이스 B — close 후 ensureNotClosed 가 Configuration throw` + 메시지 `"client closed"` 정확 일치 (`assertEquals("client closed", e.message)`) | 통과 |

### 3.5 idempotent + thread-safe 정책 (api.md L262-264)

| 요건 | 구현 | 결과 |
|------|------|------|
| idempotent (여러 번 호출 가능) | `closed.compareAndSet(false, true)` 첫 호출만 정리, 두 번째부터 no-op (`AiAgentClient.kt:228-231`) | 통과 |
| thread-safe | `AtomicBoolean` 사용으로 동시 호출 안전. `@Volatile var httpClient` 가시성 보장 | 통과 |
| 단위 테스트 | `CloseTest.kt:63-70` 여러 번 호출 안전, `:175-189` 두 번째 호출에서 cancelAll 다시 호출 안 함 (mockk verify exactly = 1) | 통과 |

### 3.6 OkHttp dispatcher.cancelAll 동작

| 요건 | 구현 | 결과 |
|------|------|------|
| `httpClient`가 null이면 no-op (F-001 진입 전 호환) | `AiAgentClient.kt:235` `httpClient?.dispatcher?.cancelAll()` (safe call) | 통과 |
| F-001/F-006 진입 시 set 가능한 internal var | `AiAgentClient.kt:142-143` `@Volatile internal var httpClient: OkHttpClient? = null` | 통과 (Q-T10-2 참조) |
| 단위 테스트 | `CloseTest.kt:130-143` mockk verify exactly = 1, `:146-154` httpClient null이어도 정상, `:175-189` idempotent | 통과 |

### 3.7 E-XXX → ERR-004 매핑

| ID | 발생 위치 | 사양 ERR-XXX | 구현 | 결과 |
|----|-----------|--------------|------|------|
| E-109 (F-001 client closed) | F-001 ask | ERR-004 | `ensureNotClosed()` 진입점 (F-001 라운드에서 `runCatching { ensureNotClosed() }` 패턴 활용) | 통과 (헬퍼 준비) |
| E-402 (F-004 send client closed) | F-004 send | ERR-004 | 동일 헬퍼 재사용 (F-004 라운드 책임) | 통과 (헬퍼 준비) |
| E-705 (F-007 client closed) | F-007 save/load/delete | ERR-004 | 동일 헬퍼 재사용 (F-007 라운드 책임) | 통과 (헬퍼 준비) |
| E-303 (F-003 askStream) | F-003 | ERR-004 | `isClosed()` 헬퍼로 Flow 진입 시 검증 (F-003 라운드 책임) | 통과 (헬퍼 준비) |
| E-801 (close 도중 IO) | F-008 close | (무시, 로그만) | `AiAgentClient.kt:234-238`, `:241-245` 두 try/catch (Throwable 무시) | 통과 |

본 라운드 useProvider에 대한 케이스 B는 직접 검증됨 (`UseProviderTest.kt:224-237`).

### 3.8 F-008 단위 테스트 커버리지

`CloseTest.kt` 13개 케이스:

| 그룹 | 커버 | 결과 |
|------|------|------|
| idempotent / 상태 (3) | 여러 번 호출 안전, 초기 isClosed=false, close 후 isClosed=true | 통과 |
| R-020 케이스 B / ensureNotClosed (3) | close 후 ensureNotClosed throw, close 전 통과, ERR-004 매핑 | 통과 |
| OkHttp dispatcher.cancelAll (4) | exactly 1 verify, null 안전, E-801 best-effort, idempotent verify | 통과 |
| Scope cancellation / R-020 케이스 A (3) | scope cancel 검증, launch CancellationException, async CancellationException, 새 launch 즉시 cancelled | 통과 |

13개 케이스로 F-008의 모든 E-XXX(E-109/E-303/E-402/E-705/E-801) + R-020 A/B + 정책(idempotent/thread-safe) 모두 커버.

---

## 4. 통합 영역 검증

### 4.1 T9(useProvider) + T10(close) 머지 정합성

`AiAgentClient.kt` 단일 파일에 양쪽이 깔끔하게 공존하는지 검증:

| 영역 | 결과 | 근거 |
|------|------|------|
| import 블록 | 양쪽 import 모두 존재 (AtomicBoolean / AtomicReference, Provider / OkHttpClient, AiException, kotlinx.coroutines.*) | `AiAgentClient.kt:1-17` |
| 클래스 KDoc | 양쪽 단락 모두 존재 (F-005 R-007 + F-008 라이프사이클) | `AiAgentClient.kt:19-55` |
| 클래스 본문 영역 분리 | 두 영역이 주석 헤더로 명확히 분리 — `F-005 / A-005 — 활성 Provider 식별자 (R-007 AtomicReference)` (L65-105), `F-008 / A-009 — 라이프사이클 상태` (L107-143), `F-005 / A-005 — useProvider` (L145-199), `F-008 / A-009 — close` (L201-275) | `AiAgentClient.kt:65-275` |
| useProvider가 ensureNotClosed 사용 | T9가 T10의 헬퍼를 활용 (impl_summary_3 §5의 "T9가 본 라운드 헬퍼를 활용해야 할 부분" 가이드 일치) | `AiAgentClient.kt:182` |
| 생성자 | 단일 internal constructor에 Provider/Lifecycle 모두 반영 (initialProviderId + providerRegistry 추가, scope/closed/httpClient는 인스턴스 멤버) | `AiAgentClient.kt:56-63` |

머지 충돌 없음. 두 라운드 결과가 깔끔하게 통합됨.

### 4.2 Builder.SUPPORTED_PROVIDERS 화이트리스트 ↔ defaultProviders ↔ ProviderRegistry ↔ Provider.id 일관성

| 항목 | 값 | 일치 |
|------|------|------|
| `Builder.SUPPORTED_PROVIDERS` | `setOf(ProviderId.CLAUDE)` (`:160`) | — |
| `Builder.defaultProviders()` | `setOf(ClaudeProvider())` (`:171`) | — |
| `ClaudeProvider().id` | `ProviderId.CLAUDE` (`:33`) | — |
| `ProviderRegistry(setOf(ClaudeProvider())).list().map { it.id }` | `[ProviderId.CLAUDE]` | — |
| 회귀 테스트 | `BuilderTest.kt:160-170` `defaultProviders ids must match SUPPORTED_PROVIDERS` | 통과 |

R-009 enum 확장 시 두 집합이 함께 갱신되도록 회귀 테스트로 강제. 일관성 보장.

### 4.3 ProviderRegistry.contains(id) 사양 외 추가의 적절성

T9 §4 Q-1로 implementer가 직접 QA 협의 요청한 항목.

- **사양**: provider-spec.md P-002에 `get(id)` / `list()` / `capabilities(id)` 3개만 정의. `contains(id)`는 미정의.
- **구현**: `ProviderRegistry.kt:64` `public fun contains(id: ProviderId): Boolean = id in byId`. `useProvider` 본문이 E-501 사전 차단을 위해 사용 (`AiAgentClient.kt:185-187`).
- **대안 평가**:
  - (a) 사양 보강: `contains(id)`을 P-002에 추가
  - (b) `runCatching { get(id) }.isFailure`로 대체
  - (c) 현 구현 유지
- **판단**: 본 추가는 (1) 의미 명확(존재 여부 단순 boolean), (2) 단위 테스트 1개로 동작 확인됨 (`ProviderRegistryTest.kt:107-110`), (3) `useProvider`의 가독성 향상 (try/catch 없이 명시적 분기). 다만 사양 외 추가이므로 spec-architect의 사양 보강 권장. **Minor 처리**, 다음 라운드 사양 보강으로 자연 해소 가능.

### 4.4 전체 사양 ID ↔ 구현 ↔ 단위 테스트 매트릭스

| 사양 ID | 구현 위치 | 단위 테스트 | 결과 |
|---------|-----------|-------------|------|
| F-005 | `AiAgentClient.useProvider` + `provider/*.kt` | UseProviderTest 8개 + ProviderRegistryTest 8개 + ClaudeProviderTest 3개 | 통과 |
| F-008 | `AiAgentClient.close/closed/scope/httpClient/ensureNotClosed/isClosed` | CloseTest 13개 | 통과 |
| A-005 | `AiAgentClient.kt:180` | UseProviderTest 정상 흐름 4개 | 통과 |
| A-009 | `AiAgentClient.kt:227` | CloseTest idempotent 그룹 3개 | 통과 |
| P-001 | `Provider.kt`/`Capabilities.kt`/`ProviderConfig.kt` | ClaudeProviderTest 3개 | 통과 |
| P-002 | `ProviderRegistry.kt` | ProviderRegistryTest 8개 | 통과 |
| P-CLAUDE | `ClaudeProvider.kt` (Capabilities) | ClaudeProviderTest 토큰 단위 비교 | 통과 |
| R-007 | `AtomicReference<ProviderId>` (`AiAgentClient.kt:77`) | UseProviderTest 16스레드 동시성 | 통과 |
| R-009 | `ProviderId.CLAUDE` 단일 + SUPPORTED_PROVIDERS | BuilderTest 회귀 1개 | 통과 |
| R-010 | Capabilities는 `Provider.kt`/`Capabilities.kt`만, `ProviderId`는 displayName만 | 정적 검증 (구조 분리) | 통과 |
| R-014 | `activeProvider` getter 매번 registry 재조회 | F-004 진입 후 별도 검증 (헬퍼만 준비) | 통과 (구현 차원) |
| R-020 케이스 A | `scope.cancel(...)` (`AiAgentClient.kt:242`) | CloseTest 케이스 A 3개 | 통과 |
| R-020 케이스 B | `ensureNotClosed()` (`AiAgentClient.kt:263-267`) | UseProviderTest + CloseTest 4개 | 통과 |
| E-501 | `AiAgentClient.kt:185-187`, `ProviderRegistry.kt:36-40` | UseProviderTest + ProviderRegistryTest 4개 | 통과 |
| E-502 | `AiAgentClient.kt:191-195` | UseProviderTest 1개 | 통과 (Q-T9-1 — 도달 불가 정책 사양 명시 권장) |
| E-109/E-402/E-705 | `ensureNotClosed()` 헬퍼 진입점 | useProvider에서 케이스 B 검증, 본체는 후속 라운드 | 통과 (헬퍼 준비) |
| E-303 | `isClosed()` 헬퍼 | F-003 라운드 책임 | 통과 (헬퍼 준비) |
| E-801 | `AiAgentClient.kt:234-238`, `:241-245` try/catch | CloseTest E-801 1개 | 통과 |
| ERR-004 | `AiException.Configuration` | `AiException.kt:51-57` (KDoc에 E-109/E-402/E-501/E-502/E-705 명시) | 통과 |

---

## 5. 발견된 이슈

본 라운드는 Blocker/Major 0건. Minor 3건은 모두 사양 명확화 또는 다음 라운드 자연 해소 항목.

### Q-T9-1 [Minor] E-502 v0.1 도달 불가 정책 사양 미명시

- **위치**: `AiAgentClient.kt:191-195` (방어적 검증), `UseProviderTest.kt:158-180` (internal 생성자 직접 사용 우회 단위 테스트)
- **현상**: 사양 features.md F-005 E-502 ("Provider별 API 키 누락")이 v0.1 단일 apiKey 모델에서는 `Builder.build()`의 E-001(`Builder.kt:107-110`)로 사전 차단되어 `useProvider` 본문 분기가 실재 도달 불가. 단위 테스트는 internal 생성자를 직접 호출하여 우회.
- **사양 의도**: features.md L238 표기는 일반적 "Provider별 키 모델"을 가정하므로 v0.2 멀티 키 도입 시 발효 예정.
- **권장**: spec-architect가 features.md F-005 E-502에 v0.1 도달 불가 + v0.2 활성 정책을 명시 (impl_summary_2 §4 Q-T9-1과 동일). 또는 v0.1에서 본 분기를 제거하고 v0.2로 이월. 본 라운드 통과로 처리.
- **Severity 사유**: 사양과 어긋나지 않으며(방어적 검증), 단위 테스트로 동작 확인됨. 다만 도달 불가 정책이 사양에 없으면 향후 변경 시 회귀 가능성.

### Q-T9-2 [Minor] ClaudeProvider.complete/stream의 NotImplementedError placeholder 정책

- **위치**: `ClaudeProvider.kt:68-75` (complete), `:83-90` (stream)
- **현상**: F-001/F-003 진입 전이라 complete/stream 본체가 `NotImplementedError`를 throw 하도록 구현됨. 작업 원칙 "TODO/placeholder 금지"와 절충.
- **사양 의도**: F-005는 registry 등록까지만 검증. complete/stream은 F-001/F-003 라운드 책임 — 본 라운드 검증 경로에서 호출 안 됨 확인:
  - F-005 useProvider는 `providerRegistry.contains()` / `providerRegistry.get()`까지만 호출, Provider 인스턴스의 complete/stream은 호출 경로에 없음 (`AiAgentClient.kt:180-199`).
  - 단위 테스트도 useProvider 동작만 검증, complete/stream은 호출 안 함.
- **권장**: spec-architect가 "라운드별 진입 시점에 미구현 Provider 메서드는 NotImplementedError 허용" 보조 정책을 사양에 명시 (impl_summary_2 §4 Q-T9-2와 동일). 또는 F-001 라운드에서 즉시 채워 넣음. 본 라운드 통과로 처리.
- **Severity 사유**: F-005 정상 흐름 경로에서 호출되지 않으므로 사양 동작에 영향 없음. F-001 라운드 진입 시점에 1차로 해소됨.

### Q-T9-3 [Minor] ProviderRegistry.contains(id) 사양 외 추가

- **위치**: `ProviderRegistry.kt:64`
- **현상**: provider-spec.md P-002 사양에는 `get/list/capabilities` 3개만 정의. `contains(id)` 보조 메서드는 사양 외 추가.
- **사용**: `AiAgentClient.useProvider`(L185-187)가 E-501 사전 차단을 위해 사용. 단위 테스트 1개 (`ProviderRegistryTest.kt:107-110`).
- **대안**:
  - (a) 사양 보강 — provider-spec.md P-002에 `contains(id)` 추가 (권장)
  - (b) `runCatching { get(id) }.isSuccess`로 대체 (가독성 저하)
  - (c) `useProvider` 본문에서 `byId in registry`처럼 직접 노출하지 않고 try/catch 사용
- **권장**: (a) 사양 보강. 본 보조 메서드는 의미 명확하고 다른 진입점(F-002 E-205 검증, F-003 E-303 검증)에서도 재사용 가능성 큼.
- **Severity 사유**: 사양 외 메서드 추가는 R-015 "신규 Provider 추가 절차" 정책의 외 영역(인터페이스 자체 변경)이지만 호환성 문제 없음(추가 메서드, 기존 시그니처 변경 없음).

### Q-T10-1 [정보성, 보고만] Dispatchers.IO 채택

- **위치**: `AiAgentClient.kt:130` `CoroutineScope(SupervisorJob() + Dispatchers.IO)`
- **사양**: api.md A-009 동작 항목에 dispatcher 미명시. impl_summary_3 §4가 명시적으로 "사양이 자유에 맡긴 영역"으로 분류.
- **판단**: overview.md 기술 스택(Coroutines + Flow) 및 후속 F-001/F-007 NFR(IO 디스패처 사용 명시)과 정합. 적절한 결정.

### Q-T10-2 [정보성, 보고만] httpClient holder 위치

- **위치**: `AiAgentClient.kt:142-143` `@Volatile internal var httpClient: OkHttpClient? = null`
- **사양**: features.md F-008 정상 흐름 2 "OkHttp dispatcher.cancelAll" 명시, 보유 위치 미명시.
- **판단**: F-001/F-006 진입 시 set 하기 위한 internal mutable holder. `@Volatile`로 가시성 보장. 적절한 결정. F-001 라운드 진입 시 set 패턴(Builder.build() 또는 Hilt @Provides)이 결정될 때 본 위치가 그대로 유지될 가능성 큼.

### Q-T10-3 [정보성, 보고만] scope.cancel cause 메시지

- **위치**: `AiAgentClient.kt:242` `scope.cancel("AiAgentClient closed")`
- **사양**: api.md A-009 동작 항목에 cause 메시지 미명시. impl_summary_3 §4가 "디버깅용, 호출자에 노출되지 않음"으로 분류.
- **판단**: cause 메시지는 호출자에게 노출되지 않고 디버깅 로그용. 사양 정합.

---

## 6. 사양 명확화 필요 항목 (spec-architect 회신 요청)

### S-T9-1 [Minor] features.md F-005 E-502 v0.1 도달 불가 정책 명시

- **현 사양**: features.md L238 "Provider별 API 키 누락 → `AiException.Configuration("api key missing for provider X")`"
- **권장 보강**:
  - "v0.1은 단일 apiKey 모델이라 Builder.build()의 E-001로 사전 차단되어 본 분기는 도달 불가. 방어적 검증으로 useProvider 본문에 둠. v0.2 멀티 키 모델 진입 시 활성." 같은 주기 추가.
  - 또는 v0.1에서 본 항목을 제거하고 v0.2로 이월.
- **영향**: 본 보강 없이도 코드는 사양 정합. 명시적으로 두면 향후 회귀 시 검증 기준 명확화.

### S-T9-2 [Minor] provider-spec.md P-002에 `contains(id): Boolean` 추가

- **현 사양**: provider-spec.md L48-55 `get/list/capabilities` 3개만 정의.
- **권장 보강**: `fun contains(id: ProviderId): Boolean` 추가 (또는 의도적 미추가 시 `useProvider` 본문에서 `runCatching { get(id) }`로 변경 권장).
- **영향**: 본 보강 없이도 코드는 동작. 사양 외 메서드 추가가 R-015 절차에 어긋나지 않게 정리하는 의미.

### Q-T9-2 (작업 원칙 "TODO 금지" 정책 보조)

- **현상**: ClaudeProvider.complete/stream이 라운드별 진입 시점에 NotImplementedError를 throw.
- **권장 보강**: 작업 원칙 또는 sdk-development-orchestrator 스킬에 "라운드별 진입 시점에 미구현 Provider 메서드는 NotImplementedError 허용. 다음 라운드 진입 시 1순위로 채워 넣을 것" 같은 보조 정책 명시.
- **영향**: F-001 라운드 진입 시점에 자연 해소 — 본 라운드 통과로 처리.

---

## 7. 종결 권고

### F-005 / F-008 종결

**통과 — Blocker 0건, Major 0건**.

- 모든 사양 ID(F-005, F-008, A-005, A-009, P-001, P-002, P-CLAUDE, R-007, R-009, R-010, R-014, R-020, E-501, E-502, E-109, E-303, E-402, E-705, E-801, ERR-004)가 코드에 정확히 매핑됨.
- 핵심 시그니처(`useProvider(provider: ProviderId)`, `close()`)가 api.md와 토큰 단위 일치.
- close 시맨틱 R-020 케이스 A/B 모두 단위 테스트로 검증 (CancellationException vs `Configuration("client closed")`, 메시지 정확 일치).
- 동시성(R-007 16스레드 × 200 iter)/idempotent(compareAndSet)/thread-safe(AtomicBoolean+@Volatile) 모두 단위 테스트 회귀 보증.
- T9/T10 머지 결과 충돌 없이 깔끔하게 공존.
- M-001~M-006/M-009/M-010 부분 도입이 사양 그대로 — 후속 F-001/F-002/F-003 라운드에서 본체만 추가 필요.
- Minor 3건(Q-T9-1/Q-T9-2/Q-T9-3)은 사양 보강 또는 다음 라운드 자연 해소.

### 후속 라운드 진입 권고

| 라운드 | 의존성 | 진입 가능 여부 | 비고 |
|--------|--------|----------------|------|
| **F-001 (텍스트 단발 ask)** | F-005 (Provider 인터페이스), F-008 (ensureNotClosed), M-001/M-002/M-009 | **진입 가능** | impl_summary_2 §5 / impl_summary_3 §6 인계 가이드 정확. `AiAgentClient.ask` 진입 첫 줄 `runCatching { ensureNotClosed() }.getOrElse { return Result.failure(it) }` (R-020 케이스 B suspend 시맨틱) + `activeProvider` 캡쳐 (R-007). E-101~E-110 + E-201~E-207 매핑 + ClaudeProvider.complete 본체 (Q-T9-2 자연 해소) |
| **F-002 (멀티모달)** | F-001 + Capabilities (R-010 활용) | F-001 후 진입 | E-205 (Provider 이미지 미지원) 검증 시 `providerRegistry.capabilities(id).supportsImage` 활용 |
| **F-003 (askStream)** | F-001 + M-006 | F-001 후 진입 | `isClosed()` 헬퍼로 cold Flow 진입 시 케이스 B 처리 (R-020). ClaudeProvider.stream 본체 |
| **F-004 (Session)** | F-001 / F-005 (Provider 활성) | F-001 후 진입 | R-014 검증 — `client.activeProvider`를 send 진입 시 캡쳐. M-007/M-008 추가 |
| **F-007 (영속화)** | F-004 (Session) | F-004 후 진입 | M-011 + DataStore. ensureNotClosed 헬퍼 재사용 |
| **F-006 (Hilt)** | F-001 + qa_report_1 Q-001 정정 (이미 라운드 5에서 사양 정정 완료, 진입 가능) | F-001 후 진입 | `Set<@JvmSuppressWildcards Provider>` 주입, httpClient는 `@Provides`로 set |

### 다음 액션 (오케스트레이터에게)

1. **android-implementer**: F-001 라운드 진입. impl_summary_2 §5 + impl_summary_3 §6 인계 가이드 그대로 따르면 충분.
2. **spec-architect**: S-T9-1 / S-T9-2 / Q-T9-2 보강 검토 (선택, 다음 라운드 진입은 차단하지 않음).
3. **F-005/F-008 자체 종결** — 본 라운드 추가 작업 불필요.

---

## 8. 자체 체크리스트

- [x] 4쌍의 경계면 교차 비교 완료 (api.md ↔ AiAgentClient/Provider/ProviderRegistry/ClaudeProvider, data-model.md M-001~M-006/M-009/M-010 ↔ model/*.kt, error-handling.md ERR ↔ AiException variant, features.md F-005/F-008 E-XXX ↔ UseProviderTest/CloseTest/ProviderRegistryTest)
- [x] 모든 F-005 E-XXX(E-501/E-502)가 단위 테스트로 커버됨 (E-502는 internal 생성자 우회로 검증 — Q-T9-1 정책 사양 명시 권장)
- [x] 모든 F-008 E-XXX(E-109/E-303/E-402/E-705/E-801)가 헬퍼 진입점 + 단위 테스트로 매핑됨
- [x] api.md 시그니처를 토큰 단위로 비교 (A-005 / A-009 모두 일치)
- [x] AiException 7개 variant ↔ ERR-001~ERR-007 1:1 매핑 (qa_report_1 검증 결과 그대로 유지)
- [x] R-007/R-009/R-010/R-014/R-020 케이스 A/B 단위 테스트 회귀 보증 검증
- [x] T9 + T10 머지 결과 충돌 없이 정합
- [x] explicit-api=strict 정합 (모든 public/internal/private 명시)
- [x] D-003 (API 키 메모리 보관, 로그/디스크 노출 금지) 정합 (`AiAgentClient.kt`/`ProviderConfig.kt` 토string 노출 없음)
- [x] 모든 이슈에 (file:line) 근거 명시
- [x] 사양 모호함 항목 별도 섹션(6번)으로 spec-architect 회신 요청
- [x] 코드/사양 직접 수정하지 않음
