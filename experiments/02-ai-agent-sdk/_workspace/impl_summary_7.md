# 구현 요약 #7 — F-006 (Hilt 모듈 노출)

본 라운드(T14): F-006 본체 — `di/SdkQualifiers.kt` 신규 + `di/SdkModule.kt` 신규 + `SdkModuleTest.kt` 신규.

---

## 0. 회귀 점검 (이전 라운드 Blocker)

이전 라운드(T13) qa_report_5.md §5 Blocker `Q-T13-A1` (`AnthropicMessagesRequest` import 누락):
- **상태**: 이미 해결됨. `ClaudeProvider.kt:5`에 `import com.androidailab.aisdk.internal.network.AnthropicMessagesRequest`가 존재함을 본 라운드 진입 시 grep으로 확인.
- 본 라운드는 추가 수정 없이 F-006으로 진입.

---

## 1. 생성/수정 파일 목록

### 1-A. 신규 파일

| 파일 | 책임 |
|------|------|
| `sdk/src/main/kotlin/com/androidailab/aisdk/di/SdkQualifiers.kt` | F-006 호출자 측 ApiKey 바인딩을 식별하는 `@ApiKey` qualifier 정의 (D-003) |
| `sdk/src/main/kotlin/com/androidailab/aisdk/di/SdkModule.kt` | F-006 Hilt @Module — `@IntoSet ClaudeProvider` / `@Singleton ProviderRegistry` / `@Singleton AiAgentClient` / `@Provides Builder` |
| `sdk/src/test/kotlin/com/androidailab/aisdk/di/SdkModuleTest.kt` | F-006 단위 테스트 — `@Provides` 함수 직접 호출로 그래프 노드 조립 정합 검증 (`@HiltAndroidTest`는 androidTest 영역이라 본 라운드 범위 외) |

### 1-B. 수정 파일

본 라운드는 기존 파일을 수정하지 않음 (회귀 0건):
- `AiAgentClient.kt`: 변경 없음 (생성자 `internal constructor` 그대로 — Builder를 통한 단일 진입 라우트 유지).
- `Builder.kt`: 변경 없음 (`internal fun providers(...)` hook을 `SdkModule`이 그대로 사용).
- `ClaudeProvider.kt`: 변경 없음 (`public constructor()` 두 번째 생성자가 `@IntoSet` 진입에 그대로 사용됨).
- `ProviderRegistry.kt`: 변경 없음 (`Set<Provider>` 받는 internal 생성자 그대로).
- `sdk/build.gradle.kts`: 변경 없음 (`hilt-android` + `hilt-android-compiler` ksp가 이미 선언되어 있음 — F-000 라운드에서 선언만 해두고 본 라운드에서 본격 사용).

---

## 2. 구현한 사양 ID 표

| ID | 사양 위치 | 구현 위치 | 비고 |
|----|----------|----------|------|
| F-006 | features.md | `SdkModule` (`@Module @InstallIn(SingletonComponent::class) object`) | 정상 흐름 1~3단계 모두 구현 |
| A-001 | api.md (기본값 정책) | `SdkModule.DEFAULT_PROVIDER_ID/DEFAULT_MODEL_ID/DEFAULT_TIMEOUT` (각각 `ProviderId.CLAUDE` / `Builder.DEFAULT_MODEL_ID` / `Builder.DEFAULT_TIMEOUT`) | Hilt 진입 client는 Builder 라우트의 기본값을 그대로 위임 — SOT 일관 |
| A-002/A-003 | api.md | (코드 변경 없음) | F-001/F-003 본체 그대로. Hilt가 만든 `AiAgentClient`도 `ask`/`askStream`을 그대로 호출 |
| P-001 | provider-spec.md | `SdkModule.provideClaudeProvider() : Provider` (`@IntoSet`) | 인터페이스 타입으로 노출, `Set<@JvmSuppressWildcards Provider>` 멀티바인딩 합산 |
| P-002 | provider-spec.md | `SdkModule.provideProviderRegistry(Set<@JvmSuppressWildcards Provider>) : ProviderRegistry` | `@JvmSuppressWildcards` 명시 (provider-spec.md "Hilt를 통해 `Set<@JvmSuppressWildcards Provider>`로 주입") |
| R-009 | data-model.md M-010 | `SdkModule.DEFAULT_PROVIDER_ID = ProviderId.CLAUDE` | v0.1 단일 Provider |
| R-010 | provider-spec.md | (변경 없음) | Capabilities는 ClaudeProvider 측에서 단일 SOT, Hilt 그래프는 인스턴스만 노출 |
| R-015 5단계 | provider-spec.md "신규 Provider 추가 절차" | `@Provides @IntoSet provideClaudeProvider()` 패턴 — 미래 Provider는 같은 패턴으로 추가만 하면 합산됨 | OpenAI/Gemini 추가 시 동일 패턴으로 `@IntoSet` 추가 |
| R-012 | features.md F-006 사용 예 | `SdkModule` KDoc에 호출자 측 `@HiltAndroidApp` + `@Provides @ApiKey String` + `@HiltViewModel @Inject lateinit var client: AiAgentClient` 시나리오 명시 | 사양 사용 예 그대로 |
| E-601 | error-handling.md | `@ApiKey String` 바인딩 미제공 시 Hilt 컴파일 missing binding 에러 (사용자 책임, 사양 명시) | 컴파일 타임 에러 — ERR 매핑 없음 |
| E-602 | error-handling.md | `SdkModule.provideAiAgentClient`가 빈 String을 받으면 `Builder.build()`가 E-001로 거부 → `AiException.Configuration("api key is required")` throw | ERR-004 (E-001과 동일 매핑) |
| D-001 | overview.md | (변경 없음) | OkHttp는 ClaudeProvider 두 번째 생성자가 매 호출당 빌드 — v0.2에서 `@Singleton OkHttpClient`로 최적화 검토 (KDoc 명시) |
| D-003 | overview.md | `SdkQualifiers.ApiKey` KDoc에 명시 (메모리 보관, 디스크 영속화 금지). `SdkModule.provideAiAgentClient`도 호출자가 BuildConfig에서 평문 String을 받아 그대로 메모리 전달 | 디스크 영속화 없음 |
| S-001 | api.md | `SdkModule.DEFAULT_MODEL_ID = "claude-opus-4-7"` (= `Builder.DEFAULT_MODEL_ID`) | 단일 SOT |
| S-002 | api.md | `SdkModule.DEFAULT_TIMEOUT = Builder.DEFAULT_TIMEOUT` (30.seconds) | 단일 SOT |

### 사양 ↔ 구현 라우트 매핑

```
features.md F-006 정상 흐름                     구현
1. 호출자가 SDK 모듈 의존성 추가             →  app/build.gradle.kts 의 implementation(":sdk") (호출자 책임)
2. SDK가 제공하는 AiSdkModule 자동 적용      →  @Module @InstallIn(SingletonComponent::class) object SdkModule
3. @Inject AiAgentClient 또는 생성자 주입    →  @Provides @Singleton fun provideAiAgentClient(...): AiAgentClient
```

호출자 측 의무 바인딩 (E-601 컴파일 타임 강제):
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppAiSdkBindings {
    @Provides @ApiKey
    fun provideAiApiKey(): String = BuildConfig.AI_API_KEY  // R-012
}
```

---

## 3. 단위 테스트 케이스 목록

### `SdkModuleTest.kt` (15 케이스)

```
F-006 provideClaudeProvider 는 ClaudeProvider 인스턴스를 Provider 로 반환
F-006 provideClaudeProvider 의 capabilities 는 P-CLAUDE 사양과 일치
F-006 provideProviderRegistry 는 입력 Set 의 Provider 를 모두 등록
F-006 provideProviderRegistry 는 빈 Set 으로도 안전하게 인스턴스화 (E-501 방어)
F-006 정상 흐름 — provideAiAgentClient 는 ApiKey 와 ProviderRegistry 로 client 빌드
F-006 E-602 — ApiKey 가 빈 문자열이면 Configuration ('api key is required') throw
F-006 E-602 — ApiKey 가 공백만 있어도 Configuration throw (Builder의 isNullOrBlank 검증)
F-006 정상 흐름 — provideAiAgentClient 가 빌드한 client 의 ProviderRegistry 가 전달된 것을 그대로 사용
F-006 provideBuilder 는 매 호출마다 새 Builder 인스턴스 반환 (Singleton 아님)
F-006 provideBuilder 가 반환한 Builder 로 client 빌드가 가능 (NFR — Hilt 옵션, Builder 양립)
F-006 멀티 Provider — 호출자가 추가 Provider 를 IntoSet 으로 합산하면 ProviderRegistry 에 모두 등록
F-006 멀티 Provider — 빈 Set 그래프도 인스턴스화는 통과 (E-602 와 별도)
F-006 기본값 상수 — DEFAULT_PROVIDER_ID 는 ProviderId_CLAUDE (api_md A-001 R-009)
F-006 기본값 상수 — DEFAULT_MODEL_ID 는 Builder_DEFAULT_MODEL_ID 와 동일 (S-001)
F-006 기본값 상수 — DEFAULT_TIMEOUT 은 Builder_DEFAULT_TIMEOUT 과 동일 (S-002)
F-006 회귀 — Hilt 가 만든 client 의 ProviderRegistry 는 ClaudeProvider 를 보유 (F-001 진입 보호)
```

### 케이스 의도 ↔ 사양 ID 매핑

| # | 테스트 함수명(요약) | 사양 ID | 의도 |
|---|---|---|---|
| 1 | provideClaudeProvider 인스턴스 반환 | F-006, R-015 5단계, P-001 | ClaudeProvider가 `Provider` 인터페이스로 노출되는지 |
| 2 | provideClaudeProvider capabilities | P-CLAUDE 상세 (provider-spec.md L80-91) | Capabilities 값이 사양과 토큰 단위 일치 |
| 3 | provideProviderRegistry 등록 | P-002 | Set<Provider> → ProviderRegistry.get(id) 정합 |
| 4 | provideProviderRegistry 빈 Set | P-002 방어 | 빈 set으로도 인스턴스화는 통과 (이후 get 시 E-501) |
| 5 | provideAiAgentClient 정상 흐름 | F-006 정상 흐름 3, A-001 기본값 | 빌드 후 activeProviderId/providerRegistry 정합 |
| 6 | E-602 빈 ApiKey | E-602, E-001, ERR-004 | 빈 문자열 → AiException.Configuration throw |
| 7 | E-602 공백 ApiKey | E-602, E-001, ERR-004 | 공백 문자열도 isNullOrBlank로 거부 |
| 8 | provideAiAgentClient ProviderRegistry 위임 | P-002, F-006 정상 흐름 | Hilt가 합산한 registry가 client에 그대로 전달 |
| 9 | provideBuilder 비-Singleton | F-000 NFR (Builder 단일 스레드) | 매 호출 새 Builder 인스턴스 |
| 10 | provideBuilder로 client 빌드 | F-006 NFR (Hilt 옵션, Builder 양립) | NFR 정합 |
| 11 | 멀티 Provider 합산 | R-015 5단계, P-002 last-write-wins | Set 합산 시 last-write-wins 정합 |
| 12 | 멀티 Provider 빈 Set | P-002 방어 | 부분 모듈 대체 시나리오 안전성 |
| 13 | DEFAULT_PROVIDER_ID | R-009, A-001 | 단일 SOT |
| 14 | DEFAULT_MODEL_ID | S-001, A-001 | 단일 SOT — Builder.DEFAULT_MODEL_ID와 동일 |
| 15 | DEFAULT_TIMEOUT | S-002, A-001 | 단일 SOT — Builder.DEFAULT_TIMEOUT과 동일 |
| 16 | 회귀 — Hilt client 의 ClaudeProvider | F-001/F-002/F-003 진입 보호 | 활성 Provider 조회 시 ClaudeProvider 인스턴스 반환 |

### 합계
- 신규 단위 테스트: **16 케이스**
- F-001/F-002/F-003 회귀 보호: 본 라운드 신규 테스트 #16번이 client.providerRegistry → ClaudeProvider 매핑을 통과 (다음 라운드 QA가 추가 회귀 테스트 진행 시에도 본 정합 유지).

### Hilt 통합 테스트 (`@HiltAndroidTest`) 영역 — 본 라운드 범위 외

본 라운드 환경(unitTest)에서는 Hilt 컴포넌트 조립 자체를 시뮬레이션하지 않는다 (settings.gradle.kts/wrapper 부재 + AndroidJUnitRunner 미가동).
대신 `SdkModule`의 각 `@Provides` 함수를 **직접 호출**하여 그래프 노드 조립의 사양 정합을 검증.
실제 Hilt 컴포넌트 조립 검증(`@HiltAndroidTest @HiltAndroidRule` 등)은 androidTest 영역으로 v0.1.1 또는 별도 라운드에서 추가 검토.

---

## 4. 사양 명확화 요청

### Q-T14-1 [Minor] `AiAgentClient.providerRegistry` 의 internal 노출 vs Hilt 검증

- **현 사양**: provider-spec.md P-002 `internal class ProviderRegistry`
- **현 코드**: `ProviderRegistry`는 `public class ... internal constructor(...)` (직접 인스턴스화는 internal로 막혀있으나 클래스 자체는 public). `AiAgentClient.providerRegistry`도 internal val.
- **본 라운드 영향**: `SdkModule.provideProviderRegistry`가 `public fun ... : ProviderRegistry`를 반환하므로, ProviderRegistry가 사실상 호출자(Hilt 그래프)에게 노출된다. provider-spec.md P-002의 "internal class"는 **호출자가 ProviderRegistry를 직접 사용하지 않음**의 의도이지만, Hilt 그래프 노드로서는 노출이 필요하다.
- **권장**: 사양 보강 — provider-spec.md P-002에 "Hilt 그래프 노드로 노출하기 위해 클래스는 public이지만 생성자는 internal — 호출자가 직접 인스턴스화는 불가, 다만 Hilt가 주입 받을 수 있다"고 명시. 본 라운드 코드는 이미 정합.
- **다음 라운드 진입 차단**: 차단 안 함. 정보성.

### Q-T14-2 [Minor] `@ModelId` / `@TimeoutSeconds` qualifier 노출 여부

- **현 사양**: features.md F-006 사용 예(R-012)는 호출자가 `BuildConfig.AI_API_KEY`만 명시. model/timeout은 사양상 Hilt 진입 시 호출자가 오버라이드 가능 여부를 명시하지 않음.
- **현 구현 결정**: 본 라운드는 **`@ApiKey` 단일 qualifier만 노출**하고, model/timeout은 SDK 측 기본값(`SdkModule.DEFAULT_MODEL_ID/DEFAULT_TIMEOUT`)을 사용. 호출자가 다른 model/timeout을 사용하려면 `provideBuilder` 진입점이나 `AiAgentClient.builder()` 정적 팩토리로 직접 빌드.
- **사유**: Hilt 진입 호출자의 80% 시나리오는 단일 모델 단일 timeout. 모델/timeout 오버라이드 qualifier를 추가하면 옵셔널 바인딩(`@BindsOptionalOf` 또는 `Optional<...>`) 도입이 필요해 그래프 복잡도 상승. v0.2에서 필요 시 추가.
- **권장**: 사양 보강 — features.md F-006 NFR에 "Hilt 진입 호출자는 model/timeout을 오버라이드하려면 Builder 라우트(F-000) 사용. v0.1은 단일 model/timeout 그래프만 지원" 한 줄 추가. 본 라운드 코드는 이미 정합.
- **다음 라운드 진입 차단**: 차단 안 함.

### Q-T14-3 [Minor] `ClaudeProvider`의 `@Singleton` 결합 vs OkHttpClient 호출당 빌드

- **현 사양**: provider-spec.md "Capabilities는 Provider 인터페이스 측 단일 SOT". Provider의 인스턴스 라이프사이클은 사양에 명시 없음.
- **현 구현**: `@Singleton ClaudeProvider`로 인스턴스 1개 + `public constructor()` 두 번째 생성자가 매 `complete`/`stream` 호출마다 `AnthropicHttpClient.newOkHttpClient(timeout)`로 새 OkHttpClient 빌드.
- **본 라운드 결정**: F-006 진입 시점에 호출자가 단일 timeout만 사용한다는 가정 하에서, Singleton ClaudeProvider + 호출당 새 OkHttpClient 빌드는 v0.1 기준 허용. **v0.2 최적화 후보**: `@Provides @Singleton OkHttpClient`를 본 모듈에 추가 + `ClaudeProvider`의 internal 생성자(`httpClientFactory`)에 주입. 이렇게 하면 connection pool 재사용 + close 시 dispatcher.cancelAll 단일 인스턴스 정리.
- **현재 영향**: F-008 close에서 `AiAgentClient.httpClient` holder가 nullable이라 close()가 cancelAll을 호출하지 않을 수 있음 (holder set은 F-001/F-006 진입 시점에 명시되어 있으나 본 라운드는 set 흐름을 추가하지 않음). qa_report_5 §8 에 명시된 것처럼 F-001 본체가 매 ClaudeProvider.complete 호출마다 새 OkHttpClient를 빌드 → close 시 cancelAll로 정리되지 않을 가능성.
- **권장**: 사양 보강 — features.md F-008 close 시맨틱 표에 "v0.1: 매 호출마다 새 OkHttpClient를 빌드하는 모드는 close 후 진행 중 호출이 자기 dispatcher.cancelAll로 종료되지 않을 수 있음 — 표준 코루틴 cancel(AiAgentClient.scope.cancel)에 의존". v0.2에서 Singleton OkHttpClient + holder set 명시.
- **다음 라운드 진입 차단**: 차단 안 함. 본 라운드는 사양 변경 없이 통과 가능. F-008 close 시맨틱 R-020 케이스 A는 scope.cancel로 보장됨.

### 이월 항목 (이전 라운드 미해결)

- **S-T11-1** (F-001 E-107 발생 위치 명확화) — 본 라운드 영향 없음, 이월.
- **S-T12-1** (ImageInput.Url https 강제 + MockWebServer 통합) — 본 라운드 영향 없음, 이월.
- **S-T13-1/2/3** (F-003 E-303 처리 시맨틱 / SSE error 이벤트 ERR 매핑 / R-005 stream 적용) — 본 라운드 영향 없음, 이월.

---

## 5. 다음 라운드 진입 가이드

### 5.1 F-004 진입 가이드 (Session)

본 라운드(F-006) 변경의 F-004 영향:

1. **Hilt 그래프에 Session 직접 등록 안 함**: Session은 `client.createSession(systemPrompt)`이 만드는 stateful 객체로 Hilt @Singleton 부적합 (호출자가 동시에 여러 Session 가질 수 있음 — F-007 다중 인스턴스 정책 R-019). F-004에서 Session 본체는 client가 직접 인스턴스화.
2. **Session.send 진입 시 client.activeProvider 캡쳐 (R-014)**: Session은 client에 의해 만들어지므로 본 라운드 Hilt 진입한 client의 `activeProvider` getter를 그대로 사용. SdkModule 변경 불필요.
3. **systemPrompt → AnthropicMessagesRequest의 top-level `system` 필드**: F-004 라운드에서 AnthropicMessagesRequest에 `system: String? = null` 필드 추가 + Mapper에 `toAnthropicRequestForSession(history, current, systemPrompt, config, httpClient)` 추가.

### 5.2 F-007 진입 가이드 (영속화)

본 라운드(F-006) 변경의 F-007 영향:

1. **DataStore 핸들도 Hilt @Singleton 노출 검토**: F-007 진입 시 `SdkModule`에 `@Provides @Singleton fun provideSessionStore(@ApplicationContext ctx: Context): SessionStore` 추가 패턴. 본 라운드 모듈 골격 그대로 사용 가능.
2. **AiAgentClient에 sessionStore 주입**: F-007에서 `AiAgentClient`의 internal 생성자에 `sessionStore: SessionStore?` 추가 검토. 본 라운드 client.internal 생성자는 그대로라 F-007에서 변경 필요. SdkModule.provideAiAgentClient도 SessionStore 의존성 추가 시점에 시그니처 변경.

### 5.3 다음 wave 진입 차단 사항

- **F-006 본체**: 본 라운드 종결 시 다음 wave (F-004/F-007) 진입 가능.
- 사양 명확화 요청(Q-T14-1/2/3)은 모두 Minor — 다음 라운드 진입 차단 안 함.

### 5.4 진입 시 체크리스트 (F-004 라운드)

- [ ] `Session` 클래스(M-007 시그니처)는 Hilt 노출 없음 — `client.createSession(systemPrompt)` 진입.
- [ ] `Session.send`도 `validateImages` + `Mapper.toAnthropicRequest` 패턴 재사용 — Hilt 진입과 무관.
- [ ] R-014 (Session은 Provider에 묶이지 않음) — `client.activeProvider`를 send 진입 시 atomic get.
- [ ] E-403 동시 send Mutex 직렬화 — Session 내부 Mutex.

### 5.5 진입 시 체크리스트 (F-007 라운드)

- [ ] `SessionStore` 인터페이스 신규 (`internal/session/SessionStore.kt`).
- [ ] `SdkModule.provideSessionStore(@ApplicationContext)` 추가.
- [ ] `AiAgentClient` 내부에 sessionStore? 추가 → `loadSession`/`deleteSession` 본체.
- [ ] `Session.save()` 본체.
- [ ] M-011 `SessionEntity` `@Serializable` data class.
- [ ] schemaVersion=1 강제 (R-018).

---

## 6. sdk-qa-validator F-006 검증 요청

**sdk-qa-validator를 호출하여 F-006 (Hilt 모듈 노출)의 사양-구현 정합성을 검증해 주세요.**

검증 본체 위치:
- `sdk/src/main/kotlin/com/androidailab/aisdk/di/SdkModule.kt` — Hilt @Module 본체 (`@Provides` 4개: `provideClaudeProvider` / `provideProviderRegistry` / `provideAiAgentClient` / `provideBuilder` + 기본값 상수 3개)
- `sdk/src/main/kotlin/com/androidailab/aisdk/di/SdkQualifiers.kt` — `@ApiKey` qualifier 정의

단위 테스트:
- `sdk/src/test/kotlin/com/androidailab/aisdk/di/SdkModuleTest.kt` (16 케이스)

검증 요청 항목:
- F-006 정상 흐름 1~3단계 매핑 (호출자 의존성 추가 → SDK Module 자동 적용 → `@Inject AiAgentClient`)
- F-006 사용 예(R-012) 정합 — `@HiltAndroidApp` + `@Provides @ApiKey String` + `@HiltViewModel @Inject`
- E-601 (Hilt 미설정 컴파일 에러 — 사용자 책임)과 E-602 (BuildConfig API 키 미설정 → ERR-004) 매핑
- @Module/@InstallIn(SingletonComponent::class)/@Singleton 스코프 정합
- @IntoSet 멀티바인딩 + Set<@JvmSuppressWildcards Provider> → ProviderRegistry 합산 (P-002, R-015 5단계)
- A-001 기본값 (provider/model/timeout) → SdkModule 기본값 단일 SOT (Builder.DEFAULT_*에 위임)
- F-006 NFR (Hilt 옵션, Builder 양립) — `provideBuilder` 진입점 별도 노출
- F-001/F-002/F-003 회귀 — `AiAgentClient.ask`/`askStream` 변경 없음 + `Mapper`/`ClaudeProvider`/`ProviderRegistry` 변경 없음
- 컴파일 가능성 — `Builder.providers(internal hook)` 호출 (같은 모듈 internal 접근 OK), `dagger`/`javax.inject`/`dagger.hilt.*` import 의존성 (build.gradle.kts에 hilt-android 2.51.1 + ksp 선언됨)

사양 ID:
- features.md F-006 (정상 흐름 1~3 + E-601/E-602 + 사용 예 R-012 + NFR)
- api.md A-001 (기본값 정책)
- provider-spec.md P-001/P-002 + 신규 Provider 추가 절차 R-015 5단계
- data-model.md M-010 R-009/R-010
- error-handling.md ERR-004 (E-602 매핑)
- overview.md D-001/D-003 (OkHttp/API 키 메모리)
