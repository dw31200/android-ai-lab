# QA Report 6 — F-006 (Hilt 모듈 노출) 점진 검증 + F-001/F-002/F-003 회귀

검증자: sdk-qa-validator
검증 일시: 2026-05-08
대상:
- F-006 본체 (T15, `_workspace/impl_summary_7.md`) — `di/SdkQualifiers.kt` 신규 + `di/SdkModule.kt` 신규 + `di/SdkModuleTest.kt` 신규 (16 단위 테스트)
- F-001/F-002/F-003 회귀 점검 (impl_summary_7 §1-B "수정 파일 0건" 주장 정합 검증)
- 회귀 점검 (이전 라운드 Blocker Q-T13-A1 `AnthropicMessagesRequest` import 누락 — `ClaudeProvider.kt:5` 에서 이미 해결됨 확인)

검증 범위:
- F-006 정상 흐름 1~3 / E-601 / E-602 / R-012 사용 예 / NFR (Hilt-Builder 양립)
- A-001 기본값 SOT (provider/model/timeout) ↔ `SdkModule.DEFAULT_PROVIDER_ID/DEFAULT_MODEL_ID/DEFAULT_TIMEOUT` ↔ `Builder.DEFAULT_*` 단일 SOT
- P-001 / P-002 (Provider 인터페이스 노출 + `Set<@JvmSuppressWildcards Provider>` 멀티바인딩)
- R-009 / R-010 (v0.1 단일 Provider, Capabilities는 Provider 측 단일 SOT)
- R-015 5단계 (`@IntoSet` 멀티바인딩 패턴 — 신규 Provider 추가 시 같은 패턴으로 합산)
- ERR-004 (E-602 → AiException.Configuration "api key is required" 매핑)
- D-001 (OkHttp 호출당 빌드 — v0.2 최적화 후보 인계) / D-003 (API 키 메모리 보관)
- S-001 (`"claude-opus-4-7"` 단일 SOT) / S-002 (30.seconds 단일 SOT)
- 16 단위 테스트 (의도/사양 ID 매핑/의미 있음 평가)
- F-001/F-002/F-003 회귀 (mtime 비교 + 파일별 변경 부재 검증)

빌드 실행: 미수행 (settings.gradle.kts/wrapper 부재 — 정적 검증 + 토큰 단위 비교)

---

## 1. 요약

| Severity | 건수 | 종결 조건 |
|----------|------|-----------|
| Blocker  | 0    | 0건 필수 — **충족** |
| Major    | 0    | 처리 또는 명시적 유보 — **충족** |
| Minor    | 2    | 다음 라운드 이월 가능 |
| 정보성   | 2    | 사양 보강 권장 |
| 사양 명확화 요청 | 3 (S-T15-1/2/3 = Q-T14-1/2/3 인계) | spec-architect 회신 후 반영 |

**결과**: F-006 **통과(종결 가능)**.

- 시그니처(A-001 기본값 SOT) / F-006 정상 흐름 1~3 / E-601(컴파일 타임 missing binding 메커니즘 보유) / E-602(빈/공백 ApiKey → ERR-004 throw) / R-012 사용 예 매핑 / NFR (Hilt-Builder 양립) / P-001 / P-002 / R-015 5단계 / R-009 / R-010 / D-003 모두 코드와 단위 테스트로 정합 확인.
- 16 단위 테스트는 모두 의미 있는 검증을 커버하며, 외부 네트워크/Hilt 컴포넌트 호출 0회.
- F-001/F-002/F-003 회귀 없음: 본 라운드 변경은 `di/` 패키지 신규 3개 파일에 한정 — `AiAgentClient.kt` / `Builder.kt` / `ClaudeProvider.kt` / `ProviderRegistry.kt` / `Mapper.kt` / `AnthropicSseParser.kt` / 테스트 파일들의 mtime이 본 라운드(2026-05-08, May 8) 이전이므로 본 라운드 비변경. 이전 라운드 통과 흐름은 그대로.
- 이전 라운드 Blocker Q-T13-A1(`AnthropicMessagesRequest` import 누락)은 `ClaudeProvider.kt:5`에 이미 정상 import 되어 있어 회귀 0건.

Minor 2건 / 정보성 2건은 (a) Q-T15-M1 — `SdkModule.provideAiAgentClient`가 `Builder.providers(internal hook)` 호출 시 같은 모듈 internal 접근 정합(`Builder.kt:91` `internal fun providers(...)`) 확인 필요 (정보성), (b) Q-T15-M2 — Hilt 그래프에 노출된 `ProviderRegistry`가 `provider-spec.md P-002` "internal class" 정의와 사실상 호출자 노출(public class) 차이 (Q-T14-1 인계, Minor), (c) Q-T15-I1 — OkHttpClient 호출당 빌드 비효율(F-002 단계에서 식별됨)이 본 라운드에서 미해결, v0.2 최적화 후보로 유보 (Q-T14-3 인계, 정보성), (d) Q-T15-M3 — `@ModelId`/`@TimeoutSeconds` qualifier 미노출 정책 사양 명시 부재 (Q-T14-2 인계, Minor).

---

## 2. F-006 검증 표

### 2.1 시그니처 일치 — A-001 기본값 SOT

api.md A-001 기본값 정책 표(L54-60) ↔ `SdkModule.kt` 기본값 상수 + `Builder.kt` companion object:

| A-001 기본값 항목 | 사양 값 | `SdkModule` 위치 | `Builder` SOT | 일치 |
|-----------------|---------|-----------------|---------------|------|
| `provider(...)` | `ProviderId.CLAUDE` (R-009) | `SdkModule.kt:186` `internal val DEFAULT_PROVIDER_ID: ProviderId = ProviderId.CLAUDE` | `Builder.kt:34` `private var providerId: ProviderId = ProviderId.CLAUDE` | 통과 |
| `model(...)` | `"claude-opus-4-7"` (S-001) | `SdkModule.kt:193` `internal const val DEFAULT_MODEL_ID: String = Builder.DEFAULT_MODEL_ID` | `Builder.kt:145` `public const val DEFAULT_MODEL_ID: String = "claude-opus-4-7"` | 통과 (단일 SOT — `Builder.DEFAULT_MODEL_ID`에 위임) |
| `timeout(...)` | `30.seconds` (S-002) | `SdkModule.kt:200` `internal val DEFAULT_TIMEOUT: Duration = Builder.DEFAULT_TIMEOUT` | `Builder.kt:148` `public val DEFAULT_TIMEOUT: Duration = 30.seconds` | 통과 (단일 SOT) |

`SdkModule.provideAiAgentClient` 내부에서 위 기본값 상수를 모두 적용 (`SdkModule.kt:159-161`):
```
.provider(DEFAULT_PROVIDER_ID)
.model(DEFAULT_MODEL_ID)
.timeout(DEFAULT_TIMEOUT)
```

`@Inject AiAgentClient` 라우트와 `AiAgentClient.builder(context)` 라우트가 동일한 단일 SOT(`Builder.DEFAULT_*`)를 공유하므로 R-012 사용 예의 ViewModel 진입과 Builder 라우트가 동일한 기본값으로 동작.

### 2.2 F-006 정상 흐름 1~3단계 매핑 (features.md L262-265)

| 단계 | 사양 | 구현 위치 | 결과 |
|------|------|-----------|------|
| 1 | 호출자가 SDK 모듈 의존성 추가 | (호출자 책임) `app/build.gradle.kts` | 본 SDK는 `sdk/build.gradle.kts:73-74` `implementation("com.google.dagger:hilt-android:2.51.1") + ksp("com.google.dagger:hilt-android-compiler:2.51.1")` 선언으로 정합 | 통과 |
| 2 | SDK가 제공하는 `AiSdkModule`이 자동 적용 | `SdkModule.kt:77-79` `@Module @InstallIn(SingletonComponent::class) public object SdkModule` — 호출자 앱에 `@HiltAndroidApp`이 있으면 자동 그래프 합산 | 통과 |
| 3 | 호출자가 `@Inject lateinit var client: AiAgentClient` 또는 생성자 주입 | `SdkModule.kt:146-164` `@Provides @Singleton public fun provideAiAgentClient(@ApplicationContext appContext, @ApiKey apiKey, providerRegistry): AiAgentClient` — Hilt가 호출자의 `@Inject AiAgentClient`/`@HiltViewModel @Inject` 생성자 주입에 본 함수를 호출 | 통과 |

추가 호환성: `SdkModule.kt:175-177` `@Provides public fun provideBuilder(@ApplicationContext appContext: Context): Builder = AiAgentClient.builder(appContext)` — 호출자가 Hilt 그래프 안에서 `@Inject Builder`로도 받아 동적으로 client 빌드 가능 (NFR — Hilt-Builder 양립).

### 2.3 E-601 / E-602 매핑

| ID | 사양 | ERR | 구현 위치 | 결과 |
|----|------|-----|-----------|------|
| E-601 | Hilt 미설정 | (컴파일 에러, ERR 매핑 없음) | `SdkModule.kt:148-152` `@Provides @Singleton fun provideAiAgentClient(... @ApiKey apiKey: String, providerRegistry: ProviderRegistry)` — 호출자가 `@Provides @ApiKey String` 바인딩을 자기 앱 모듈에서 제공하지 않으면 Hilt KSP가 missing binding 에러로 컴파일 거부. `SdkQualifiers.kt:48-50` `@Qualifier @Retention(BINARY) public annotation class ApiKey`로 식별자 명시. | 통과 (메커니즘 보유 — 컴파일 타임 강제) |
| E-602 | API 키 BuildConfig 미설정 | ERR-004 (F-000 E-001과 동일) | `SdkModule.kt:157-163` `provideAiAgentClient`가 빈 String을 받아 `Builder.build()` 호출 → `Builder.kt:107-110` `if (key.isNullOrBlank()) throw AiException.Configuration("api key is required")` (E-001 매핑이 그대로 작동, error-handling.md L70 ERR-004) | 통과 |

E-602 단위 테스트:
- `SdkModuleTest.kt:138-155` `F-006 E-602 — ApiKey 가 빈 문자열이면 Configuration ('api key is required') throw` — 빈 문자열 거부 + 메시지 prefix `"api key"` 검증
- `SdkModuleTest.kt:157-171` `F-006 E-602 — ApiKey 가 공백만 있어도 Configuration throw (Builder의 isNullOrBlank 검증)` — 공백 문자열 거부 (`isNullOrBlank` 정합)

E-601 컴파일 타임 정합은 unitTest 영역으로 직접 검증 불가 (사양 명시), 메커니즘 보유 확인으로 정합.

### 2.4 R-012 사용 예 매핑 (features.md L272-295)

| 사양 (R-012 사용 예) | 구현 위치 | 결과 |
|--------------------|-----------|------|
| `@HiltAndroidApp class MyApplication : Application()` | (호출자 책임) | (호출자 책임) — SDK 측에는 영향 없음 |
| "SDK가 제공하는 AiSdkModule이 자동으로 AiAgentClient를 @Provides로 노출" | `SdkModule.kt:146-164` `@Provides @Singleton fun provideAiAgentClient(...)` | 통과 (단, 사양상 모듈 이름 "AiSdkModule" vs 코드 "SdkModule" 표기 차이는 정보성 — 의미 동치) |
| `@HiltViewModel class ChatViewModel @Inject constructor(private val client: AiAgentClient)` | `SdkModule.provideAiAgentClient`가 `@Singleton AiAgentClient` 인스턴스를 그래프 노드로 노출 → ViewModel 생성자 주입 가능 | 통과 |
| `@Composable fun ChatScreen(viewModel: ChatViewModel = hiltViewModel())` | (호출자 책임 / Compose 통합) | (호출자 책임) |
| 호출자 측 `@Provides @ApiKey fun provideAiApiKey(): String = BuildConfig.AI_API_KEY` | `SdkQualifiers.kt:32-50` `@Qualifier @Retention(BINARY) public annotation class ApiKey` (식별자) + `SdkQualifiers.kt` KDoc L33-43에 호출자 측 모듈 사용 예 명시 (보강) | 통과 |

### 2.5 R-009 / R-010

| 사양 | 구현 위치 | 결과 |
|------|-----------|------|
| R-009 (v0.1 enum에 CLAUDE만) | `SdkModule.kt:186` `DEFAULT_PROVIDER_ID = ProviderId.CLAUDE` + `ProviderId.kt:19-21` enum CLAUDE 단일 값 | 통과 |
| R-010 (Capabilities는 Provider 측 단일 SOT) | `SdkModule.provideClaudeProvider()`가 `Provider` 인터페이스 타입으로 노출 — Capabilities는 `ClaudeProvider.kt:74-82`에만 존재. `ProviderRegistry`도 `provider.capabilities` 를 그대로 위임. ProviderId enum은 capability 필드 없음 (provider-spec.md L40 정합) | 통과 |

`SdkModuleTest.kt:73-87` `F-006 provideClaudeProvider 의 capabilities 는 P-CLAUDE 사양과 일치` — Capabilities 7개 필드(supportsImage/supportsVideo/supportsStream/supportsSession/maxImageSizeBytes/maxImagesPerRequest/supportedImageMimeTypes) 모두 provider-spec.md L80-91 토큰 단위 일치 검증.

### 2.6 R-015 5단계 (provider-spec.md L138-141)

| 사양 (R-015 5단계 "Hilt 등록") | 구현 위치 | 결과 |
|------------------------------|-----------|------|
| `di/AiSdkModule.kt`에 `@Provides @IntoSet` 추가 | `SdkModule.kt:93-96` `@Provides @Singleton @IntoSet public fun provideClaudeProvider(): Provider = ClaudeProvider()` | 통과 |
| `Set<Provider>`에 자동 합류 | `SdkModule.kt:107-111` `@Provides @Singleton public fun provideProviderRegistry(providers: Set<@JvmSuppressWildcards Provider>): ProviderRegistry = ProviderRegistry(providers)` | 통과 |

미래 Provider(OpenAI/Gemini) 추가 시: 같은 모듈에 `@Provides @IntoSet provideOpenAiProvider(): Provider = OpenAiProvider()` 추가만 하면 `Set<Provider>`에 자동 합산되어 `ProviderRegistry`에 등록됨. `SdkModuleTest.kt:223-235` `F-006 멀티 Provider — 호출자가 추가 Provider 를 IntoSet 으로 합산하면 ProviderRegistry 에 모두 등록` 테스트가 last-write-wins 회귀 보호.

### 2.7 R-012 / R-009 / R-010 / R-015 — 표 형태 통합 매핑

(2.4 / 2.5 / 2.6 통합 — 각 R-XXX는 위 섹션 참조)

### 2.8 ERR-004 매핑 (E-602 → Configuration variant)

| 흐름 | 사양 | 구현 | 결과 |
|------|------|------|------|
| `@Provides @ApiKey fun = ""` | E-602 → ERR-004 → `AiException.Configuration` | `SdkModule.provideAiAgentClient` 호출 → `Builder.build()` → `Builder.kt:108-110` `if (key.isNullOrBlank()) throw AiException.Configuration("api key is required")` (E-001 매핑이 동일 코드 경로) | 통과 |
| 호출자에게 보일 메시지 (error-handling.md L34) "Configuration error: {detail}" | `Builder.build()`의 `"api key is required"` 메시지가 호출자 catch 시점에 `AiException.Configuration.message`로 전달 | 통과 (호출자가 i18n 변환 가능 — R-013) |

### 2.9 P-001 / P-002

| 사양 | 구현 | 결과 |
|------|------|------|
| P-001 `interface Provider` (id/capabilities/complete/stream) | `Provider.kt` (변경 없음) + `SdkModule.provideClaudeProvider() : Provider` 인터페이스 타입 노출 | 통과 |
| P-002 `internal class ProviderRegistry(providers: Set<Provider>)` | `ProviderRegistry.kt:22-23` `public class ProviderRegistry internal constructor(providers: Set<Provider>)` — 클래스 자체는 public이나 생성자가 internal이라 호출자 직접 인스턴스화 불가. Hilt 그래프 노드로는 노출됨 (`SdkModule.kt:107-111` `@Provides @Singleton fun provideProviderRegistry(...) : ProviderRegistry`) | 통과 (사양 표기 "internal class"와 코드 "public class internal constructor" 차이는 정보성 보강 권장 — S-T15-1) |
| P-002 "Hilt를 통해 `Set<@JvmSuppressWildcards Provider>`로 주입" | `SdkModule.kt:109-110` `providers: Set<@JvmSuppressWildcards Provider>` — `@JvmSuppressWildcards` 명시 (Kotlin generic의 Java 변환 시 `Set<? extends Provider>` 방지) | 통과 (provider-spec.md L58 토큰 단위 일치) |

`SdkModuleTest.kt:93-100` `F-006 provideProviderRegistry 는 입력 Set 의 Provider 를 모두 등록` 테스트가 `Set<Provider>` → `ProviderRegistry.get(id)` 매핑 정합 검증.

### 2.10 D-001 / D-003

| 사양 | 구현 | 결과 |
|------|------|------|
| D-001 (OkHttp HTTP 클라이언트) | `SdkModule.provideClaudeProvider()`가 `ClaudeProvider()` 두 번째 생성자 호출 → `ClaudeProvider.kt:63-67` 매 호출당 `AnthropicHttpClient.newOkHttpClient(timeout)`로 새 OkHttpClient 빌드 | 통과 (단, 호출당 빌드는 v0.2 최적화 후보 — S-T15-3 = Q-T14-3 인계, 정보성) |
| D-003 (API 키 메모리 보관) | `SdkQualifiers.kt:33-50` KDoc에 명시 ("호출자가 BuildConfig에서 평문 String을 받아 그대로 메모리 전달, 디스크 영속화 없음") + `SdkModule.provideAiAgentClient`가 받은 `apiKey: String`을 `Builder.apiKey(...)`에 그대로 전달 — 디스크/로그 영속화 코드 없음 | 통과 |

### 2.11 S-001 / S-002 (사양 명시된 보안 정책 — 본 라운드 영향 여부)

> 주: 본 라운드 검증 의뢰 표기 §B-2의 "S-001 / S-002"는 api.md A-001 기본값(model/timeout 단일 SOT)을 가리킨다. provider-spec.md "보안 고려" 섹션의 "API 키는 SDK 메모리에서만 유지" / "Provider 호출 시 키는 HTTPS 헤더로만 전송, 로깅 금지" / "디버그 빌드에서도 API 키는 마스킹" / "영속화된 SessionEntity에는 API 키가 포함되지 않음" 항목은 D-003에 해당하며 본 라운드는 SDK 모듈 노출만 다루므로 직접 영향 없음 (제2.10 D-003 행 참조).

api.md A-001 기본값 SOT (S-001/S-002) 매핑:

| ID | 사양 | 구현 | 결과 |
|----|------|------|------|
| S-001 (model "claude-opus-4-7") | `SdkModule.kt:193` `DEFAULT_MODEL_ID = Builder.DEFAULT_MODEL_ID` ← `Builder.kt:145` `"claude-opus-4-7"` (단일 SOT) | 단위 테스트 `SdkModuleTest.kt:259-262` 정확 값 검증 | 통과 |
| S-002 (timeout 30.seconds) | `SdkModule.kt:200` `DEFAULT_TIMEOUT = Builder.DEFAULT_TIMEOUT` ← `Builder.kt:148` `30.seconds` (단일 SOT) | 단위 테스트 `SdkModuleTest.kt:264-267` Builder.DEFAULT_TIMEOUT 동치 검증 | 통과 |

### 2.12 AiAgentClient @Inject vs Builder 라우트 둘 다 가능한지 (NFR — Hilt 옵션, Builder 양립)

| 라우트 | 사양 | 구현 위치 | 결과 |
|--------|------|-----------|------|
| Hilt 라우트 (`@Inject AiAgentClient`) | features.md F-006 정상 흐름 3단계 + 사용 예 ChatViewModel | `SdkModule.kt:146-164` `@Provides @Singleton fun provideAiAgentClient(...): AiAgentClient` | 통과 |
| Builder 라우트 (`AiAgentClient.builder(context)`) | features.md F-006 NFR L298 "Hilt 미사용 호출자도 Builder 패턴(F-000)으로 사용 가능" | `AiAgentClient.kt:583-584` `public companion object { @JvmStatic public fun builder(context: Context): Builder = Builder(context) }` (변경 없음 — F-000 그대로) | 통과 |
| 동일 그래프 안에서 `@Inject Builder` (보조) | (NFR 보강 — 사양 명시 부재이나 양립 정신과 정합) | `SdkModule.kt:175-177` `@Provides public fun provideBuilder(@ApplicationContext appContext: Context): Builder = AiAgentClient.builder(appContext)` | 통과 (단위 테스트 `SdkModuleTest.kt:194-216` `provideBuilder` 매 호출 새 인스턴스 + Builder→client 빌드 정합 검증) |

두 라우트가 충돌 없이 양립: Hilt 라우트는 `@Singleton`으로 단일 인스턴스 캐싱, Builder 라우트는 호출자가 매번 빌드 가능. 둘 다 `Builder.DEFAULT_*` 단일 SOT를 공유.

### 2.13 OkHttpClient 호출당 빌드 여부 (F-002 단계 식별 비효율 본 라운드 해결 여부)

| 항목 | 사양 | 구현 | 결과 |
|------|------|------|------|
| OkHttpClient 호출당 빌드 (F-002에서 식별, qa_report_4 §8 인계) | (사양 명시 없음 — D-001 OkHttp만 명시) | `ClaudeProvider.kt:63-67` 두 번째 생성자가 매 `complete`/`stream` 호출마다 `AnthropicHttpClient.newOkHttpClient(config.timeout)` 빌드 — 본 라운드 변경 없음 (`SdkModule.provideClaudeProvider`가 두 번째 생성자 그대로 사용) | **본 라운드 미해결** — v0.2 최적화 후보로 명시적 유보 (impl_summary_7 §4 Q-T14-3, S-T15-3 인계) |

영향 분석: F-006 진입 시점에 호출자가 단일 timeout만 사용한다는 가정 하에서, Singleton ClaudeProvider + 호출당 새 OkHttpClient 빌드는 v0.1 기준 허용 가능 (SdkModule KDoc L52-58 명시). 단, F-008 close 시 `httpClient` holder set 흐름이 본 라운드에서 추가되지 않았으므로 close 후 진행 중 호출이 자기 dispatcher.cancelAll로 종료되지 않을 가능성 — 표준 코루틴 cancel(scope.cancel)에 의존 (qa_report_5 §8 / impl_summary_7 §4 Q-T14-3 명시). 본 라운드 사양 변경 없이 통과 가능, v0.2에서 `@Provides @Singleton OkHttpClient` 추가 + ClaudeProvider internal 생성자에 주입.

---

## 3. 단위 테스트 검증 표 (`SdkModuleTest.kt` 16 케이스)

| # | 테스트 함수명 (요약) | 검증 의도 | 사양 ID | 의미 있음 |
|---|---|---|---|---|
| 1 | `provideClaudeProvider 는 ClaudeProvider 인스턴스를 Provider 로 반환` (L62-70) | `@IntoSet`이 `Provider` 인터페이스 타입으로 노출됨 + 인스턴스 ClaudeProvider 검증 | F-006 / R-015 5단계 / P-001 | 의미 있음 (P-001 인터페이스 노출 + R-015 핵심) |
| 2 | `provideClaudeProvider 의 capabilities 는 P-CLAUDE 사양과 일치` (L72-87) | Capabilities 7개 필드 모두 provider-spec.md L80-91 토큰 단위 일치 | P-CLAUDE 상세 / R-010 / R-015 5단계 | 의미 있음 (R-010 Capabilities SOT 보호) |
| 3 | `provideProviderRegistry 는 입력 Set 의 Provider 를 모두 등록` (L93-100) | `Set<Provider>` → ProviderRegistry.get(id) 매핑 정합 + list().size 정합 | P-002 | 의미 있음 |
| 4 | `provideProviderRegistry 는 빈 Set 으로도 안전하게 인스턴스화 (E-501 방어)` (L102-109) | 부분 모듈 대체 시 빈 Set이 와도 Registry 인스턴스화 통과 (이후 get 시 E-501) | P-002 방어 | 의미 있음 (호출자 SdkModule 부분 대체 시나리오) |
| 5 | `provideAiAgentClient 는 ApiKey 와 ProviderRegistry 로 client 빌드` (L116-135) | F-006 정상 흐름 3 — Hilt 진입 client의 activeProviderId/providerRegistry 정합 | F-006 정상 흐름 / A-001 기본값 / R-009 | 의미 있음 (정상 흐름 핵심) |
| 6 | `E-602 — ApiKey 가 빈 문자열이면 Configuration ('api key is required') throw` (L138-155) | E-602 → E-001 (ERR-004) 매핑 + 메시지 prefix `"api key"` 검증 | E-602 / E-001 / ERR-004 | 의미 있음 (E-602 핵심) |
| 7 | `E-602 — ApiKey 가 공백만 있어도 Configuration throw (Builder의 isNullOrBlank 검증)` (L157-171) | 공백 문자열도 isNullOrBlank로 거부 — `"   "` 케이스 | E-602 / E-001 / ERR-004 | 의미 있음 (isNullOrBlank 정합) |
| 8 | `provideAiAgentClient 가 빌드한 client 의 ProviderRegistry 가 전달된 것을 그대로 사용` (L174-188) | Hilt가 합산한 registry 안의 Provider가 client에서 그대로 활용 | P-002 / F-006 정상 흐름 | 의미 있음 (그래프 합산 무결성) |
| 9 | `provideBuilder 는 매 호출마다 새 Builder 인스턴스 반환 (Singleton 아님)` (L195-205) | Builder는 mutable state + 단일 스레드 가정(F-000 NFR) — `@Singleton`이 아님 | F-000 NFR / F-006 NFR | 의미 있음 (Builder 라이프사이클) |
| 10 | `provideBuilder 가 반환한 Builder 로 client 빌드가 가능 (NFR — Hilt 옵션, Builder 양립)` (L208-216) | Builder 라우트와 Hilt 라우트 양립 보장 | F-006 NFR | 의미 있음 (NFR 핵심) |
| 11 | `멀티 Provider — 호출자가 추가 Provider 를 IntoSet 으로 합산하면 ProviderRegistry 에 모두 등록` (L223-235) | 미래 Provider(OpenAI/Gemini) 추가 시 last-write-wins 회귀 보호. v0.1 enum 한계로 동일 ProviderId 시뮬레이션 | R-015 5단계 / P-002 last-write-wins | 의미 있음 (last-write-wins 정책 회귀 보호) |
| 12 | `멀티 Provider — 빈 Set 그래프도 인스턴스화는 통과 (E-602 와 별도)` (L238-247) | 호출자가 SdkModule 부분 대체 시 ClaudeProvider @Provides 누락 시 빈 Set 그래프 안전성 | P-002 방어 | 의미 있음 (#4와 시나리오 보강 — 빈 Set 그래프 ↔ 후속 useProvider E-501 분리) |
| 13 | `DEFAULT_PROVIDER_ID 는 ProviderId_CLAUDE (api_md A-001 R-009)` (L253-256) | `SdkModule.DEFAULT_PROVIDER_ID == ProviderId.CLAUDE` 단일 SOT 회귀 | R-009 / A-001 | 의미 있음 (단일 SOT 회귀 보호) |
| 14 | `DEFAULT_MODEL_ID 는 Builder_DEFAULT_MODEL_ID 와 동일 (S-001)` (L258-262) | `SdkModule.DEFAULT_MODEL_ID == Builder.DEFAULT_MODEL_ID == "claude-opus-4-7"` | S-001 / A-001 | 의미 있음 (단일 SOT — Builder 측 위임 정합) |
| 15 | `DEFAULT_TIMEOUT 은 Builder_DEFAULT_TIMEOUT 과 동일 (S-002)` (L264-267) | `SdkModule.DEFAULT_TIMEOUT == Builder.DEFAULT_TIMEOUT (30.seconds)` | S-002 / A-001 | 의미 있음 (단일 SOT — Builder 측 위임 정합) |
| 16 | `회귀 — Hilt 가 만든 client 의 ProviderRegistry 는 ClaudeProvider 를 보유 (F-001 진입 보호)` (L274-291) | Hilt 진입 client가 F-001 ask 호출 시 activeProvider → ClaudeProvider 검증 | F-001/F-002/F-003 진입 보호 | 의미 있음 (회귀 보호 핵심) |

**16개 모두 의미 있음.** F-006 정상 흐름 + E-602 + R-009/R-010/R-012/R-015 + A-001 단일 SOT + Hilt-Builder 양립 + F-001/F-002/F-003 회귀 보호까지 빠짐없이 커버.

### 3.1 컴파일 가능성 점검

| 항목 | 검증 | 결과 |
|------|------|------|
| `SdkQualifiers.kt` import (`javax.inject.Qualifier` / `kotlin.annotation.AnnotationRetention.BINARY`) | `javax.inject` 는 Hilt 의존성 transitive로 포함됨 (`hilt-android:2.51.1`) | 통과 |
| `SdkModule.kt` import (`dagger.Module` / `dagger.Provides` / `dagger.hilt.InstallIn` / `dagger.hilt.android.qualifiers.ApplicationContext` / `dagger.hilt.components.SingletonComponent` / `dagger.multibindings.IntoSet` / `javax.inject.Singleton`) | 모두 `hilt-android:2.51.1` 의존성 보유 (sdk/build.gradle.kts:73-74) | 통과 |
| `SdkModuleTest.kt` import (`io.mockk.every` / `io.mockk.mockk` / `kotlinx.coroutines.flow.Flow/flowOf` / `org.junit.Assert.*` / `org.junit.Test`) | mockk 1.13.12 / kotlinx-coroutines-test 1.8.1 / junit 4.13.2 모두 testImplementation 보유 (sdk/build.gradle.kts:77-79) | 통과 |
| `SdkModule.provideAiAgentClient`가 `Builder.providers(internal hook)` 호출 (`SdkModule.kt:162` `.providers(providerRegistry.list().toSet())`) | `Builder.kt:91` `internal fun providers(providers: Set<Provider>): Builder` — 같은 모듈 internal 접근 OK (`com.androidailab.aisdk` 모듈 내) | 통과 |
| `SdkModule.provideClaudeProvider()`가 `ClaudeProvider()` 두 번째 생성자 호출 | `ClaudeProvider.kt:63` `public constructor()` (두 번째 생성자) — public 접근 가능 | 통과 |
| `ProviderRegistry`가 internal constructor지만 같은 모듈 내 `SdkModule`에서 `ProviderRegistry(providers)` 호출 (`SdkModule.kt:111`) | `ProviderRegistry.kt:22-23` `public class ProviderRegistry internal constructor(...)` — 같은 모듈 internal 접근 OK | 통과 |
| `@Provides @Singleton @IntoSet`이 같은 함수에 함께 적용 가능한지 | Dagger/Hilt 표준 — `@IntoSet` + `@Singleton` 조합 가능 (멀티바인딩 + 단일 인스턴스) | 통과 |
| `mockk` 으로 `Context.applicationContext` self-return 설정 (`SdkModuleTest.kt:50-55`) | `mockk(relaxed = true)` + `every { appContext.applicationContext } returns appContext` — 표준 mockk 패턴 | 통과 |
| 실제 Hilt 컴포넌트/네트워크 호출 0회 | unitTest 영역에서 `@Provides` 함수 직접 호출 — Hilt 그래프 시뮬레이션 없음 | 통과 (impl_summary_7 §3 명시 — `@HiltAndroidTest`는 androidTest 영역으로 v0.1.1/별도 라운드) |

---

## 4. F-001 / F-002 / F-003 회귀 점검

### 4.1 본 라운드 변경 파일 목록 (impl_summary_7 §1 정합 검증)

| 파일 | 본 라운드 변경 | 실제 mtime | 정합 |
|------|---------------|-----------|------|
| `sdk/src/main/kotlin/com/androidailab/aisdk/di/SdkQualifiers.kt` (신규) | 신규 | 2026-05-10 23:36 | 통과 (본 라운드 신규) |
| `sdk/src/main/kotlin/com/androidailab/aisdk/di/SdkModule.kt` (신규) | 신규 | 2026-05-10 23:37 | 통과 (본 라운드 신규) |
| `sdk/src/test/kotlin/com/androidailab/aisdk/di/SdkModuleTest.kt` (신규) | 신규 | 2026-05-10 23:38 | 통과 (본 라운드 신규) |
| `AiAgentClient.kt` | 변경 없음 | 2026-05-09 10:02 | 통과 (이전 라운드 mtime 유지) |
| `Builder.kt` | 변경 없음 | 2026-05-07 14:12 | 통과 |
| `ProviderRegistry.kt` | 변경 없음 | 2026-05-07 14:08 | 통과 |
| `ClaudeProvider.kt` | 변경 없음 | 2026-05-10 22:27 | 통과 (이전 라운드 T13 Blocker 해결 mtime — 본 라운드 진입 전 시점) |
| `sdk/build.gradle.kts` | 변경 없음 (impl_summary §1-B 명시) | F-000 라운드 시점 그대로, 본 라운드 변경 없음 | 통과 |

### 4.2 F-001 회귀 점검

| 항목 | 변경 영향 가능성 | 검증 | 결과 |
|------|------------------|------|------|
| `AiAgentClient.ask` 본체 (L259-316) | 본 라운드 변경 없음 | mtime 2026-05-09 (T13 시점) — 본 라운드 비변경 | 회귀 없음 |
| `AiAgentClient.activeProvider` / `currentProviderConfig` (R-007) | 변경 없음 | L104-116 그대로 | 회귀 없음 |
| `AiAgentClient.EMPTY_OK_FINISH_REASONS` / `EMPTY_RESPONSE_CODE` / `IMAGES_TOTAL_MAX_BYTES` | 변경 없음 | L592-610 그대로 | 회귀 없음 |
| `Mapper.toAnthropicRequest` / `Mapper.toAiResponse` | 변경 없음 | mtime 변경 없음 (T12 그대로) | 회귀 없음 |
| `ClaudeProvider.complete` 본체 (L102-159) | 본 라운드 변경 없음 | mtime 2026-05-10 22:27 (T13 직후) — 본 라운드 비변경 | 회귀 없음 |
| `AnthropicMessagesRequest` import (Q-T13-A1 Blocker 회귀) | T13 종결 시 해결, 본 라운드 회귀 가능성 | `ClaudeProvider.kt:5` `import com.androidailab.aisdk.internal.network.AnthropicMessagesRequest` 정상 존재 | **회귀 없음** (Blocker 해결 유지) |
| `AnthropicSseParser` / `ErrorMapper` / `AnthropicHttpClient` | 변경 없음 | mtime 변경 없음 | 회귀 없음 |
| `AskTest` 14건 / `MapperTest` / `ImageInputTest` / `AskWithImagesTest` / `MapperImageTest` / `BuilderTest` / `CloseTest` / `UseProviderTest` / `ProviderRegistryTest` / `ClaudeProviderTest` | 변경 없음 | 본 라운드 신규 테스트는 `SdkModuleTest.kt`만 | 회귀 없음 |

Hilt가 만든 client가 F-001 ask를 호출할 수 있는지 회귀 보호: `SdkModuleTest.kt:274-291` `F-006 회귀 — Hilt 가 만든 client 의 ProviderRegistry 는 ClaudeProvider 를 보유 (F-001 진입 보호)` 가 active resolution → ClaudeProvider 인스턴스 반환을 검증.

### 4.3 F-002 회귀 점검

| 항목 | 검증 | 결과 |
|------|------|------|
| `AiAgentClient.validateImages` (L436-496) | 본 라운드 변경 없음 | 회귀 없음 |
| `ImageInput.Uri/Bytes/Url` | 변경 없음 | 회귀 없음 |
| `Mapper.buildContentJson` / `verifyMagicNumber` / `fetchUrlBytes` | 변경 없음 | 회귀 없음 |
| `ClaudeProvider.complete`의 Mapper 호출 (httpClient 인자) | 변경 없음 | 회귀 없음 |
| F-002 이미지 검증(E-201/E-202/E-203/E-204/E-205/E-206/E-207/E-208) | 변경 없음 | 회귀 없음 |

### 4.4 F-003 회귀 점검

| 항목 | 검증 | 결과 |
|------|------|------|
| `AiAgentClient.askStream` 본체 (L358-405) | 본 라운드 변경 없음 | 회귀 없음 |
| `ClaudeProvider.stream` 본체 (L190-281) | 본 라운드 변경 없음 | 회귀 없음 |
| `AnthropicSseParser.parse` | 변경 없음 | 회귀 없음 |
| F-003 정상 흐름 1~4 / E-301/E-302/E-303 / M-006 방출 순서 / R-005 stream 적용 / R-007 캡쳐 / R-020 케이스 B | 변경 없음 | 회귀 없음 |
| `AskStreamTest` 14건 / `AnthropicSseParserTest` 15건 / `ClaudeProviderStreamTest` 5건 | 변경 없음 | 회귀 없음 |

### 4.5 SdkModule이 만든 client의 F-001/F-002/F-003 진입 가능성

`SdkModuleTest.kt:274-291`의 회귀 테스트가 `client.providerRegistry.get(ProviderId.CLAUDE)` → ClaudeProvider 인스턴스 검증으로 F-001 ask / F-002 ask(images) / F-003 askStream 진입 시 `activeProvider`가 정상적으로 ClaudeProvider로 풀림을 보장.

추가로 `SdkModule.provideAiAgentClient`가 `Builder.providers(providerRegistry.list().toSet())` (`SdkModule.kt:162`)를 호출하여 Hilt 멀티바인딩 인스턴스를 그대로 client에 전달 — Builder의 `defaultProviders()` 경로(매번 새 ClaudeProvider)와 분리되어 ClaudeProvider 인스턴스 중복 생성 방지.

**F-001/F-002/F-003 회귀 0건** — impl_summary_7 §1-B "수정 파일 0건" 주장 정합 확인.

---

## 5. 발견된 이슈

본 라운드 Blocker 0건. Major 0건. Minor 2건. 정보성 2건.

### Q-T15-M1 [Minor] `SdkModule.provideAiAgentClient`가 Builder.providers(internal hook) 호출 — 같은 모듈 internal 접근 정합

- **위치**: `sdk/src/main/kotlin/com/androidailab/aisdk/di/SdkModule.kt:162` `.providers(providerRegistry.list().toSet())`
- **현상**: `Builder.providers(...)`는 `Builder.kt:91` `internal fun providers(providers: Set<Provider>): Builder` — 같은 모듈 `com.androidailab.aisdk` 내 internal 접근 OK. `SdkModule`도 같은 모듈이므로 컴파일 통과.
- **사양**: provider-spec.md P-002 + features.md F-006 NFR (Hilt 미사용 호출자도 Builder 라우트로 사용 가능) — `Builder.providers` 의 internal hook이 호출자에 직접 노출되지 않으면서 SdkModule이 사용 가능하다는 설계 의도 정합.
- **권장**: 사양 보강 — `provider-spec.md` 또는 `features.md F-006`에 "SdkModule.provideAiAgentClient가 Builder.providers(internal hook)을 사용해 Hilt 멀티바인딩이 제공한 Provider 집합을 그대로 client에 전달함을 명시" 한 줄 추가. 본 라운드 코드는 정합.
- **Severity 사유**: 동작은 정합. internal 접근 정책의 사양 가시성만 부족 (정보성 차원).

### Q-T15-M2 [Minor] `ProviderRegistry`의 internal class vs Hilt 그래프 노드 노출 차이 (Q-T14-1 인계)

- **위치**: `provider-spec.md P-002` "internal class ProviderRegistry" / `ProviderRegistry.kt:22-23` `public class ProviderRegistry internal constructor(...)`
- **현상**: 사양은 "internal class"로 표기되지만 코드는 클래스 자체가 `public`(생성자만 internal). 사유: `SdkModule.provideProviderRegistry`가 `public fun ... : ProviderRegistry` 를 반환하므로 ProviderRegistry가 호출자(Hilt 그래프)에게 노출되어야 함. 호출자는 직접 인스턴스화 불가(생성자 internal)이지만 Hilt 주입은 가능 — 사양 의도("호출자가 ProviderRegistry를 직접 사용하지 않음")와 코드 의도(Hilt 그래프 노드로서 노출 필요)가 양립.
- **권장**: 사양 보강 — `provider-spec.md P-002`에 "Hilt 그래프 노드로 노출하기 위해 클래스는 public이지만 생성자는 internal — 호출자가 직접 인스턴스화는 불가, 다만 Hilt가 주입 받을 수 있다" 명시. 본 라운드 코드는 이미 정합.
- **Severity 사유**: 동작은 정합. 표기 차원의 불일치 (impl_summary_7 §4 Q-T14-1 인계).

### Q-T15-I1 [정보성] OkHttpClient 호출당 빌드 비효율 — F-002 단계 식별, 본 라운드 미해결 (Q-T14-3 인계)

- **위치**: `ClaudeProvider.kt:63-67` 두 번째 생성자가 매 `complete`/`stream` 호출마다 `AnthropicHttpClient.newOkHttpClient(config.timeout)` 빌드 / `SdkModule.provideClaudeProvider()` (L93-96)이 두 번째 생성자 그대로 사용
- **현상**: `@Singleton ClaudeProvider`로 인스턴스 1개이지만 OkHttpClient는 호출당 새로 빌드됨 — connection pool 재사용 불가 + close 시 dispatcher.cancelAll로 정리되지 않을 가능성. F-008 close 시 `AiAgentClient.httpClient` holder가 nullable이라 cancelAll이 동작하지 않을 수 있음 — qa_report_5 §8 / impl_summary_7 §4 Q-T14-3 명시. 표준 코루틴 cancel(scope.cancel)에 의존.
- **권장**: 사양 보강 — `features.md F-008` close 시맨틱 표에 "v0.1: 매 호출마다 새 OkHttpClient를 빌드하는 모드는 close 후 진행 중 호출이 자기 dispatcher.cancelAll로 종료되지 않을 수 있음 — 표준 코루틴 cancel(AiAgentClient.scope.cancel)에 의존"을 명시. v0.2에서 `@Provides @Singleton OkHttpClient` 추가 + ClaudeProvider internal 생성자에 주입 + `AiAgentClient.httpClient` holder set 흐름 명시.
- **Severity 사유**: 정보성. 본 라운드 코드 동작에 영향 없음 (F-006 진입 시 호출자가 단일 timeout만 사용한다는 가정 하에서 v0.1 허용 가능). v0.2 최적화 후보로 명시적 유보.

### Q-T15-M3 [Minor] `@ModelId` / `@TimeoutSeconds` qualifier 미노출 정책 사양 명시 부재 (Q-T14-2 인계)

- **위치**: `SdkQualifiers.kt:48-50` `@ApiKey` 단일 qualifier만 노출 / features.md F-006 사용 예 R-012는 `BuildConfig.AI_API_KEY`만 명시
- **현상**: 본 라운드는 `@ApiKey` 단일 qualifier만 노출하고 model/timeout은 SDK 기본값(`SdkModule.DEFAULT_MODEL_ID/DEFAULT_TIMEOUT`)을 사용 — 호출자가 다른 값을 사용하려면 `provideBuilder` 진입점 또는 `AiAgentClient.builder(context)` 정적 팩토리로 직접 빌드. 사양상 Hilt 진입 시 호출자가 model/timeout 오버라이드 가능 여부가 명시되지 않음.
- **권장**: 사양 보강 — features.md F-006 NFR에 "Hilt 진입 호출자는 model/timeout을 오버라이드하려면 Builder 라우트(F-000) 사용. v0.1은 단일 model/timeout 그래프만 지원" 한 줄 추가. v0.2에서 `@ModelId`/`@TimeoutSeconds` qualifier 또는 `@BindsOptionalOf` 패턴 검토. 본 라운드 코드는 이미 정합.
- **Severity 사유**: 동작은 정합. 정책 명시 차원의 부재 (impl_summary_7 §4 Q-T14-2 인계).

---

## 6. 사양 명확화 필요 항목

### S-T15-1 [Minor] `ProviderRegistry` public class + internal constructor (Q-T14-1 인계)

- 본 라운드 코드는 `public class ProviderRegistry internal constructor(...)`. provider-spec.md P-002의 "internal class" 표기를 "Hilt 그래프 노드로서 노출하기 위해 public class이지만 생성자는 internal — 호출자가 직접 인스턴스화는 불가, Hilt가 주입 받을 수 있다"로 정정 권장.
- **다음 라운드 진입 차단**: 차단 안 함.

### S-T15-2 [Minor] `@ModelId` / `@TimeoutSeconds` qualifier 추가 정책 명시 (Q-T14-2 인계)

- features.md F-006 NFR에 "Hilt 진입 호출자는 model/timeout 오버라이드를 위해 Builder 라우트(F-000) 사용. v0.1은 단일 model/timeout 그래프만 지원" 한 줄 추가 권장. v0.2 검토 사항 명시.
- **다음 라운드 진입 차단**: 차단 안 함.

### S-T15-3 [정보성] `ClaudeProvider` `@Singleton` vs OkHttpClient 호출당 빌드 — v0.2 최적화 후보 (Q-T14-3 인계)

- features.md F-008 close 시맨틱에 "v0.1: 매 호출 새 OkHttpClient 빌드 → close 후 진행 중 호출은 표준 코루틴 cancel에 의존" 명시 + v0.2 `@Provides @Singleton OkHttpClient` 추가 약속.
- **다음 라운드 진입 차단**: 차단 안 함.

### 이월 항목 (이전 라운드 미해결)

- **S-T11-1** (F-001 E-107 발생 위치 명확화) — 본 라운드 영향 없음, 이월.
- **S-T12-1** (ImageInput.Url https 강제 + MockWebServer 통합) — 본 라운드 영향 없음, 이월.
- **S-T13-1/2/3/4** (F-003 E-303 처리 시맨틱 / SSE error 이벤트 ERR 매핑 / R-005 stream 적용 / SseParser 외부 catch 문서화) — 본 라운드 영향 없음, 이월.

---

## 7. 종결 권고

### F-006 종결

**통과 — 본 라운드 종결 가능**.

- 시그니처(A-001 기본값 SOT — provider/model/timeout)가 `SdkModule.DEFAULT_*` ↔ `Builder.DEFAULT_*` 단일 SOT로 일치 (model="claude-opus-4-7", timeout=30.seconds, provider=CLAUDE).
- F-006 정상 흐름 1~3단계 모두 코드(`SdkModule` `@Module @InstallIn` + `@Provides @IntoSet ClaudeProvider` + `@Provides @Singleton ProviderRegistry` + `@Provides @Singleton AiAgentClient`)에 차례로 매핑.
- E-601 (Hilt 미설정 컴파일 타임 에러 — `@ApiKey` qualifier 메커니즘으로 missing binding 강제) / E-602 (빈/공백 ApiKey → ERR-004 `AiException.Configuration("api key is required")`) 모두 코드와 단위 테스트로 매핑.
- R-009 (v0.1 enum CLAUDE 단일) / R-010 (Capabilities Provider 측 단일 SOT) / R-012 사용 예 매핑 / R-015 5단계 (`@IntoSet` 멀티바인딩 패턴) 모두 정합.
- P-001 (Provider 인터페이스 노출) / P-002 (`Set<@JvmSuppressWildcards Provider>` 멀티바인딩 + ProviderRegistry 합산 + last-write-wins) 모두 정합.
- ERR-004 매핑 (E-602 → AiException.Configuration) / D-001 (OkHttp — v0.2 최적화 후보 유보) / D-003 (API 키 메모리 보관) 모두 정합.
- AiAgentClient @Inject 라우트와 Builder 라우트(`AiAgentClient.builder`)가 양립 (F-006 NFR — Hilt 옵션, Builder 양립).
- 16 단위 테스트가 의미 있는 검증을 빠짐없이 커버 + 외부 네트워크/Hilt 컴포넌트 호출 0회.
- F-001/F-002/F-003 회귀 0건 — `AiAgentClient.kt` / `Builder.kt` / `ClaudeProvider.kt` / `ProviderRegistry.kt` / `Mapper.kt` / `AnthropicSseParser.kt` 등 본 라운드 변경 없음(mtime 검증 통과).

### 다음 wave 진입 권고

| 라운드 | 의존성 | 진입 가능 | 비고 |
|--------|--------|-----------|------|
| **F-004 (Session)** | F-001/F-002/F-003 + F-005 R-014 | **진입 가능** | impl_summary_7 §5.1 인계. Session은 Hilt 그래프에 직접 등록 안 함 (호출자가 동시에 여러 Session 가질 수 있음 — F-007 다중 인스턴스 정책 R-019). Session.send 진입 시 client.activeProvider 를 그대로 사용. SdkModule 변경 불필요. systemPrompt → AnthropicMessagesRequest top-level `system` 필드 추가 + Mapper.toAnthropicRequestForSession 추가 필요 (F-004 라운드에서 본체 작성). |
| **F-007 (영속화)** | F-004 + M-011 + DataStore | F-004 후 진입 | impl_summary_7 §5.2 인계. `SdkModule`에 `@Provides @Singleton fun provideSessionStore(@ApplicationContext ctx): SessionStore` 추가 패턴. AiAgentClient.internal 생성자에 sessionStore? 추가 시점에 `SdkModule.provideAiAgentClient` 시그니처 변경 필요. M-011 schemaVersion=1 강제(R-018), 다중 인스턴스 last-write-wins(R-019), URL SSRF 방어 미수행(R-023). |

본 라운드 Blocker 0건 / Major 0건이므로 다음 wave (F-004 / F-007) 즉시 진입 가능.

### 다음 액션 (오케스트레이터에게)

1. **F-006 종결**: Blocker/Major 0건 — 본 라운드 종결 통과.
2. **spec-architect**: S-T15-1/2/3 보강 검토 — 모두 Minor/정보성, 다음 라운드 진입 차단하지 않음. 묶음으로 처리 권장 (Q-T14-1/2/3 인계 항목과 동일).
3. **android-implementer**: 다음 wave 진입 가능 (F-004 Session 또는 F-007 영속화). F-004는 systemPrompt → Anthropic top-level `system` 필드 매핑 + Mutex 직렬화(E-403) + R-014 atomic Provider get 진입.

---

## 8. 자체 체크리스트

- [x] 4쌍 경계면 교차 비교 완료
  - api.md A-001 기본값 SOT ↔ SdkModule.DEFAULT_* ↔ Builder.DEFAULT_* (토큰 단위)
  - features.md F-006 정상 흐름 1~3 + E-601/E-602 + 사용 예 R-012 ↔ SdkModule + SdkQualifiers + SdkModuleTest
  - provider-spec.md P-001/P-002 + R-015 5단계 ↔ SdkModule.provideClaudeProvider + provideProviderRegistry + ProviderRegistry
  - error-handling.md ERR-004 (E-602 매핑) ↔ Builder.build() E-001 throw 경로 ↔ SdkModuleTest E-602 테스트
- [x] F-006 정상 흐름 1~3단계 모두 코드에 매핑 + 단위 테스트로 보증
- [x] F-006 모든 E-XXX (E-601 컴파일 타임 메커니즘 + E-602 런타임 throw) → 코드 매핑 + 단위 테스트 커버
- [x] api.md A-001 기본값 SOT 토큰 단위 비교 (model="claude-opus-4-7" / timeout=30.seconds / provider=CLAUDE)
- [x] AiException.Configuration variant ↔ ERR-004 매핑 (E-602 — F-000 E-001과 동일)
- [x] P-001 Provider 인터페이스 노출 / P-002 ProviderRegistry `Set<@JvmSuppressWildcards Provider>` 주입
- [x] R-009 (v0.1 enum CLAUDE 단일) / R-010 (Capabilities Provider 측 단일 SOT) 회귀 보호
- [x] R-012 사용 예 매핑 (`@HiltAndroidApp` + `@Provides @ApiKey String` + `@HiltViewModel @Inject`)
- [x] R-015 5단계 (`@IntoSet` 멀티바인딩 패턴 — 미래 Provider 추가 시 같은 패턴으로 합산)
- [x] D-001 (OkHttp — 호출당 빌드는 v0.2 최적화 후보로 명시적 유보) / D-003 (API 키 메모리 보관) 정합
- [x] AiAgentClient @Inject 라우트 vs Builder 라우트 양립 (F-006 NFR — Hilt 옵션)
- [x] OkHttpClient 호출당 빌드 여부 — F-002 단계 식별, 본 라운드 미해결, v0.2 최적화 후보 유보 (Q-T14-3 = S-T15-3 인계)
- [x] 단위 테스트 16개 의미 있는 검증 + 컴파일 가능 형태 + 실제 네트워크/Hilt 컴포넌트 호출 0회
- [x] F-001 회귀 점검 — AiAgentClient.ask / Mapper / ClaudeProvider.complete / AnthropicMessagesRequest import / AskTest 모두 회귀 없음
- [x] F-002 회귀 점검 — validateImages / ImageInput / Mapper.buildContentJson / MapperImageTest 모두 회귀 없음
- [x] F-003 회귀 점검 — askStream / ClaudeProvider.stream / AnthropicSseParser / AskStreamTest 모두 회귀 없음
- [x] 이전 라운드 Blocker Q-T13-A1 (`AnthropicMessagesRequest` import 누락) — 본 라운드 진입 시점에 이미 해결되어 있음 확인 (`ClaudeProvider.kt:5`)
- [x] 모든 이슈에 (file:line) 근거 명시
- [x] 사양 모호함 항목 별도 섹션(6번)으로 spec-architect 회신 요청 (S-T15-1/2/3 — Q-T14-1/2/3 인계)
- [x] 코드/사양 직접 수정하지 않음
- [x] Severity 분류 일관 (Blocker = 사양 정의된 흐름 동작 불가 / Major = 동작은 하지만 사양 불일치 / Minor = 정책 명시·메시지 차원 / 정보성 = 사양 보강 권장)
- [x] 다음 wave (F-004/F-007) 진입 가능/차단 명시
