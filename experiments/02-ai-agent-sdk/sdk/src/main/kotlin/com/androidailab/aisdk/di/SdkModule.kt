package com.androidailab.aisdk.di

import android.content.Context
import com.androidailab.aisdk.AiAgentClient
import com.androidailab.aisdk.client.Builder
import com.androidailab.aisdk.internal.storage.DataStoreSessionStore
import com.androidailab.aisdk.model.ProviderId
import com.androidailab.aisdk.provider.Provider
import com.androidailab.aisdk.provider.ProviderRegistry
import com.androidailab.aisdk.provider.claude.ClaudeProvider
import com.androidailab.aisdk.session.SessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * SDK Hilt 모듈 (F-006, A-001/A-002 등 client 진입점 노출).
 *
 * 사양 참조:
 * - features.md F-006 정상 흐름 1~3 (Hilt 모듈 자동 적용 + `@Inject AiAgentClient`)
 * - features.md F-006 사용 예 (R-012) — `@HiltAndroidApp` + `@Inject` + ViewModel/Composable 진입
 * - features.md F-006 NFR — Hilt 미사용 호출자도 [Builder] 라우트로 사용 가능 (Hilt는 옵션)
 * - api.md A-001 기본값 정책 (model "claude-opus-4-7" / timeout 30.seconds)
 * - provider-spec.md P-002 ([ProviderRegistry] — `Set<@JvmSuppressWildcards Provider>`로 주입)
 * - provider-spec.md "신규 Provider 추가 절차 R-015" 5단계 (Hilt 등록 — `@Provides @IntoSet`)
 * - data-model.md M-010 [ProviderId.CLAUDE] (v0.1 단일 Provider — R-009)
 * - overview.md D-003 (API 키 메모리 보관) / D-001 (OkHttp)
 * - error-handling.md E-601 (Hilt 미설정 → 컴파일 에러) / E-602 (API 키 미설정 → ERR-004)
 *
 * 그래프 구조 (v0.1):
 *
 * ```
 *   호출자 앱 모듈                       SDK 모듈 (본 파일)
 *   ┌────────────────────────┐        ┌──────────────────────────────────┐
 *   │ @Provides @ApiKey      │        │ @Provides @IntoSet               │
 *   │   String (BuildConfig) │  ───►  │   ClaudeProvider : Provider      │
 *   └────────────────────────┘        │ @Provides @Singleton             │
 *           │                          │   ProviderRegistry(Set<Provider>)│
 *           ▼                          │ @Provides @Singleton             │
 *   AiAgentClient(@Singleton)  ◄─────  │   AiAgentClient(ApplicationCtx,  │
 *                                      │     ApiKey, ProviderRegistry)    │
 *                                      └──────────────────────────────────┘
 * ```
 *
 * 정책:
 * - [AiAgentClient]는 `@Singleton` (overview.md NFR "동시성 — thread-safe", F-006 사용 예의
 *   `ApplicationComponent` scope 명시).
 * - [ClaudeProvider]는 `@Singleton` 으로 단일 인스턴스 — 본체가 무상태(R-007 stateless: id/capabilities/
 *   httpClientFactory만 보유)이므로 안전하며, Provider 인스턴스가 매번 새로 만들어지면 OkHttp connection pool
 *   재사용 이점이 사라진다 (성능).
 * - [ClaudeProvider]는 두 번째 생성자 `public constructor()`를 사용한다. 이 생성자는 매 호출마다
 *   [ProviderConfig.timeout]에 맞춰 새 OkHttpClient를 빌드하지만, F-006 진입 시점에 모든 호출이
 *   동일한 timeout을 사용한다는 가정(Builder.build()에서 단일 timeout 결정) 하에서 호출당 1회
 *   정도의 빌드 비용은 v0.1 기준 허용 가능. v0.2에서 Singleton OkHttpClient를 본 모듈에 추가하고
 *   ClaudeProvider에 주입하는 형태로 최적화 예정 (impl_summary §5 / qa_report §8 인계).
 * - [ProviderRegistry]는 `@Singleton`. `Set<@JvmSuppressWildcards Provider>` 멀티바인딩으로 주입받아
 *   v0.1에서는 ClaudeProvider 1개, v0.2/v0.3에서 OpenAI/Gemini가 추가되면 같은 `Set`에 합류.
 * - [Builder]도 `@Provides`로 노출 — 호출자가 Hilt 그래프 안에서 동적으로 client를 빌드하고 싶을 때
 *   사용 가능. 단, 일반 진입은 `@Inject AiAgentClient` 권장.
 *
 * E-602 (BuildConfig API 키 미설정 → ERR-004) 매핑:
 * - 호출자 앱 모듈에서 `@Provides @ApiKey` 함수가 빈 문자열을 반환하면 [provideAiAgentClient]가
 *   내부에서 [Builder.build]를 호출 → E-001 매핑으로 [com.androidailab.aisdk.model.AiException.Configuration]
 *   throw. F-006 정상 흐름 진입 시(예: ViewModel 인스턴스화 시점) Hilt가 `AiAgentClient` 필드 주입을
 *   수행하다가 본 throw가 그대로 전파되어 호출자에게 보임.
 *
 * Hilt 미사용 호출자는 본 모듈을 무시하고 [com.androidailab.aisdk.AiAgentClient.builder]
 * 정적 팩토리로 진입하면 됨 (F-006 NFR).
 *
 * @see SdkQualifiers (ApiKey qualifier 정의)
 * @see Builder
 * @see ProviderRegistry
 */
@Module
@InstallIn(SingletonComponent::class)
public object SdkModule {

    /**
     * P-CLAUDE 인스턴스 등록 (provider-spec.md "신규 Provider 추가 절차 R-015" 5단계).
     *
     * `@IntoSet` 멀티바인딩으로 [Provider] 집합에 합류. v0.1은 ClaudeProvider 1개,
     * v0.2/v0.3에서 OpenAI/Gemini가 추가되면 같은 `Set<Provider>`에 합류해 자동으로
     * [ProviderRegistry]에 합산된다.
     *
     * `@Singleton` — 본체가 stateless이고, 두 번째 생성자가 매 호출마다 새 [OkHttpClient]를
     * 빌드하지만 ClaudeProvider 인스턴스 자체는 1회 생성으로 충분 (id/capabilities/httpClientFactory만 보유).
     *
     * @return [ClaudeProvider]를 [Provider] 인터페이스로 노출 (`@IntoSet`은 인터페이스 타입 기반 합산).
     */
    @Provides
    @Singleton
    @IntoSet
    public fun provideClaudeProvider(): Provider = ClaudeProvider()

    /**
     * Provider 식별자 → 인스턴스 매핑 ([ProviderRegistry], P-002).
     *
     * `Set<@JvmSuppressWildcards Provider>` 멀티바인딩으로 주입받음 — Kotlin generic의
     * Java 변환 시 `Set<? extends Provider>`가 되는 것을 방지하기 위해 `@JvmSuppressWildcards`
     * 필수 (provider-spec.md P-002 명시).
     *
     * `@Singleton` — Registry는 immutable Map만 보유 (생성 후 변경 없음), 동시성 안전.
     */
    @Provides
    @Singleton
    public fun provideProviderRegistry(
        providers: Set<@JvmSuppressWildcards Provider>,
    ): ProviderRegistry = ProviderRegistry(providers)

    /**
     * F-007 SessionStore 제공 (DataStoreSessionStore).
     *
     * 사양 참조:
     * - features.md F-007 정상 흐름 (DataStore Preferences 기반 영속화)
     * - overview.md "기술 스택": androidx.datastore (D-002)
     * - data-model.md M-011 (SessionEntity 직렬화)
     *
     * `@Singleton` — DataStore Preferences는 같은 파일 이름에 두 번 등록 시 IllegalStateException이며,
     * 본 SDK는 [DataStoreSessionStore.DATASTORE_FILE_NAME] 고정 파일을 사용하므로 단일 인스턴스가 필수.
     *
     * @param appContext Application context (`@ApplicationContext`).
     *                   DataStore는 [Context]가 필요하며 Application scope으로 안전.
     * @return [SessionStore] 인터페이스로 노출 — 호출자 (= AiAgentClient.loadSession 등)는 인터페이스에만 의존.
     */
    @Provides
    @Singleton
    public fun provideSessionStore(
        @ApplicationContext appContext: Context,
    ): SessionStore = DataStoreSessionStore(appContext)

    /**
     * SDK 진입점 [AiAgentClient] 제공 (F-006 정상 흐름 3단계, A-001/A-002).
     *
     * 사양 참조:
     * - features.md F-006 정상 흐름 (Hilt 자동 적용 + `@Inject AiAgentClient`)
     * - features.md F-006 사용 예 (R-012) — Application/ViewModel/Composable 진입
     * - features.md F-007 (영속화 — sessionStore 주입)
     * - api.md A-001 기본값 정책 (model="claude-opus-4-7" / timeout=30.seconds)
     * - error-handling.md E-602 (API 키 미설정 → ERR-004)
     *
     * 동작:
     * 1. 호출자가 `@Provides @ApiKey String`를 자기 앱 모듈에서 제공해야 한다.
     *    미제공 시 Hilt 컴파일 시 missing binding 에러 (E-601 컴파일 타임 정합).
     * 2. 본 함수는 [Builder]를 통해 client를 빌드 — 사양 검증(E-001/E-002/E-003)을 거친다.
     *    api.md A-001 기본값 (provider=CLAUDE, model="claude-opus-4-7", timeout=30.seconds) 적용.
     * 3. [Builder.providers]로 Hilt가 합산한 [providerRegistry]의 [Provider] 인스턴스 집합을
     *    Builder에 주입 — Builder의 `defaultProviders()` 경로(매번 새 ClaudeProvider 인스턴스화)
     *    대신 Hilt 멀티바인딩이 제공하는 `@Singleton` 인스턴스를 그대로 사용.
     * 4. [Builder.sessionStore]로 Hilt가 제공하는 [provideSessionStore]를 주입 — F-007 영속화 진입.
     *    Builder의 자동 DataStoreSessionStore 생성 경로(Hilt 미사용)는 본 경로에 우선 (singleton 보장).
     * 5. Builder.build()가 E-001(빈 ApiKey)/E-002(unknown provider)/E-003(timeout < 1초)를
     *    검증하고 [AiAgentClient]를 반환.
     *
     * `@Singleton` — overview.md NFR "동시성 thread-safe", F-006 사용 예 "ApplicationComponent scope".
     *
     * E-602 정합: 호출자가 빈 문자열을 [ApiKey] String으로 제공하면 [Builder.build]에서 E-001로
     * `AiException.Configuration("api key is required")` throw. Hilt가 인스턴스 생성 시 본 throw를
     * 그대로 호출자에게 전파한다 (F-006 사용 예의 ViewModel 생성자 주입 시점).
     *
     * @param appContext Application context (`@ApplicationContext` qualifier — Hilt 표준).
     * @param apiKey 호출자가 제공한 활성 Provider API 키 (D-003 — 메모리 보관, 디스크 영속화 금지).
     * @param providerRegistry SDK가 빌드한 ProviderRegistry. 그 안의 [Provider] 인스턴스 집합을
     *                        Builder에 그대로 전달하기 위해 사용 (`Set<Provider>` 노출).
     * @param sessionStore F-007 영속화 백엔드 — Hilt가 [provideSessionStore]로 합산한 단일 인스턴스.
     * @return [AiAgentClient] `@Singleton` 인스턴스 — Hilt가 캐싱하여 호출자 앱 전역에서 재사용.
     * @throws com.androidailab.aisdk.model.AiException.Configuration E-001/E-002/E-003 (E-602 ERR-004 매핑)
     */
    @Provides
    @Singleton
    public fun provideAiAgentClient(
        @ApplicationContext appContext: Context,
        @ApiKey apiKey: String,
        providerRegistry: ProviderRegistry,
        sessionStore: SessionStore,
    ): AiAgentClient {
        // F-006 NFR: Hilt 진입점도 Builder 검증을 그대로 통과하도록 Builder 라우트를 재사용.
        // 별도 진입점을 두면 E-001/E-002/E-003 검증이 중복되거나 누락될 위험이 있으므로 단일 라우트.
        // Builder.providers(internal hook)는 Hilt 멀티바인딩이 제공하는 ProviderRegistry의 Provider 집합을
        // 그대로 주입하여 ClaudeProvider 인스턴스 중복 생성을 방지 (Builder.defaultProviders는 Hilt 미사용 경로).
        // Builder.sessionStore(internal hook)는 Hilt가 제공하는 @Singleton SessionStore를 그대로 주입
        // (Builder의 자동 DataStoreSessionStore 생성 경로보다 우선 — 단일 인스턴스 보장).
        return AiAgentClient.builder(appContext)
            .apiKey(apiKey)
            .provider(DEFAULT_PROVIDER_ID)
            .model(DEFAULT_MODEL_ID)
            .timeout(DEFAULT_TIMEOUT)
            .providers(providerRegistry.list().toSet())
            .sessionStore(sessionStore)
            .build()
    }

    /**
     * F-006 보조 — 호출자가 Hilt 그래프 안에서 [Builder]를 직접 사용하고 싶은 경우 진입점.
     *
     * 일반적인 호출자는 `@Inject AiAgentClient`로 충분하지만, 동적으로 다른 model/timeout을
     * 적용해 별도 client 인스턴스를 만들고 싶다면 본 [Builder] 인스턴스를 받아 체이닝하면 된다.
     *
     * `@Singleton` 으로 두지 않는 이유: Builder는 단일 스레드 사용 가정 (F-000 NFR) + 빌드 시점에
     * apiKey/model 등 mutable 상태를 가짐. 호출 시점마다 새 인스턴스를 받는 것이 안전.
     */
    @Provides
    public fun provideBuilder(@ApplicationContext appContext: Context): Builder =
        AiAgentClient.builder(appContext)

    // -----------------------------------------------------------------
    // 기본값 (api.md A-001 / S-001 / S-002)
    // -----------------------------------------------------------------

    /**
     * F-006 기본 Provider — v0.1 단일 [ProviderId.CLAUDE] (R-009 / api.md A-001).
     */
    internal val DEFAULT_PROVIDER_ID: ProviderId = ProviderId.CLAUDE

    /**
     * F-006 기본 모델 식별자 — api.md A-001 S-001 ("claude-opus-4-7").
     *
     * [Builder.DEFAULT_MODEL_ID]와 동일 값을 본 위치에도 명시 — Hilt 모듈이 의도하는 기본값임을 KDoc 추적성으로 강조.
     */
    internal const val DEFAULT_MODEL_ID: String = Builder.DEFAULT_MODEL_ID

    /**
     * F-006 기본 timeout — api.md A-001 S-002 (30.seconds).
     *
     * [Builder.DEFAULT_TIMEOUT]을 그대로 위임 — 단일 SOT (single source of truth).
     */
    internal val DEFAULT_TIMEOUT: Duration = Builder.DEFAULT_TIMEOUT
}
