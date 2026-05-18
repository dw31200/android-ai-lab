package com.androidailab.aisdk.client

import android.content.Context
import com.androidailab.aisdk.AiAgentClient
import com.androidailab.aisdk.internal.storage.DataStoreSessionStore
import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.ProviderId
import com.androidailab.aisdk.provider.Provider
import com.androidailab.aisdk.provider.ProviderRegistry
import com.androidailab.aisdk.provider.claude.ClaudeProvider
import com.androidailab.aisdk.session.SessionStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [AiAgentClient] Builder (F-000, A-001).
 *
 * 사양 참조:
 * - features.md F-000 정상 흐름 1~3
 * - api.md A-001 시그니처
 * - error-handling.md E-001~E-003
 *
 * 정책:
 * - thread-safety: 단일 스레드 사용을 가정 (F-000 NFR). 결과 client는 thread-safe.
 * - API 키는 build() 이후에도 메모리에서만 보관, 디스크/로그 평문 노출 금지 (D-003).
 *
 * @param context 호출자 Context. `applicationContext`만 보관 (메모리 누수 방지).
 */
public class Builder internal constructor(
    context: Context,
) {

    private val appContext: Context = context.applicationContext ?: context

    private var apiKey: String? = null
    private var providerId: ProviderId = ProviderId.CLAUDE
    private var modelId: String = DEFAULT_MODEL_ID
    private var timeout: Duration = DEFAULT_TIMEOUT

    /**
     * Provider 인스턴스 집합 오버라이드 (단위 테스트 전용, F-005).
     *
     * Hilt 미사용 진입 경로(Builder)에서는 [defaultProviders]를 사용하지만, 단위 테스트에서
     * Mock Provider를 주입하려면 본 hook으로 교체한다. F-006(Hilt) 진입 후에는
     * `Set<@JvmSuppressWildcards Provider>`로 모듈에서 자동 주입된다.
     */
    private var providersOverride: Set<Provider>? = null

    /**
     * SessionStore 오버라이드 (단위 테스트 전용, F-007).
     *
     * Builder 진입 경로(Hilt 미사용)에서는 [build]가 [DataStoreSessionStore]를 자동 생성하지만,
     * 단위 테스트에서 Fake SessionStore를 주입하려면 본 hook으로 교체한다.
     * F-006(Hilt) 진입 시에는 [SessionStore]가 모듈에서 주입된다 (provideSessionStore).
     *
     * 사양 참조:
     * - features.md F-007 (영속화)
     * - data-model.md M-011 (SessionEntity 저장)
     */
    private var sessionStoreOverride: SessionStore? = null

    /**
     * API 키 설정 (A-001). build() 시점에 비어있으면 E-001로 거부.
     */
    public fun apiKey(key: String): Builder = apply {
        this.apiKey = key
    }

    /**
     * Provider 선택 (A-001, F-005). 미설정 시 기본값 [ProviderId.CLAUDE]
     * (provider-spec.md "Provider 선택 정책 — 초기화 시").
     *
     * v0.1은 [ProviderId.CLAUDE]만 enum에 존재하므로 본 메서드의 인자는 사실상 CLAUDE 한 가지.
     * 호환되지 않는 식별자(미래 enum 값)가 들어오면 build() 시점에 E-002로 거부.
     */
    public fun provider(provider: ProviderId): Builder = apply {
        this.providerId = provider
    }

    /**
     * 모델 식별자 설정 (A-001). 미설정 시 기본값 [DEFAULT_MODEL_ID].
     */
    public fun model(modelId: String): Builder = apply {
        this.modelId = modelId
    }

    /**
     * 단일 요청 타임아웃 설정 (A-001).
     * build() 시점에 1초 미만이면 E-003으로 거부.
     */
    public fun timeout(duration: Duration): Builder = apply {
        this.timeout = duration
    }

    /**
     * Provider 인스턴스 직접 주입 (단위 테스트/F-006 Hilt 우회 진입용).
     *
     * 사양 참조: provider-spec.md P-002 (`internal class ProviderRegistry`),
     * F-005 단위 테스트 가이드 (작업 원칙 7: 테스트 가능성).
     *
     * 일반 호출자는 본 메서드를 사용하지 않는다. 미설정 시 [defaultProviders]가
     * 자동 적용되어 v0.1 시점 [ClaudeProvider]가 등록된다.
     *
     * 본 메서드는 internal — 같은 모듈/테스트 모듈에서만 호출 가능.
     */
    internal fun providers(providers: Set<Provider>): Builder = apply {
        this.providersOverride = providers
    }

    /**
     * SessionStore 직접 주입 (단위 테스트/F-006 Hilt 우회 진입용, F-007).
     *
     * 사양 참조:
     * - features.md F-007 (영속화)
     * - data-model.md M-011 (SessionEntity 저장)
     *
     * 일반 호출자는 본 메서드를 사용하지 않는다. 미설정 시 [build]가 [DataStoreSessionStore]를
     * 자동 생성한다 (Builder 진입 경로). Hilt 진입 시 SdkModule.provideSessionStore가 주입.
     *
     * 본 메서드는 internal — 같은 모듈/테스트 모듈에서만 호출 가능.
     */
    internal fun sessionStore(store: SessionStore?): Builder = apply {
        this.sessionStoreOverride = store
    }

    /**
     * 검증 후 [AiAgentClient] 인스턴스 반환 (F-000 정상 흐름 3, A-001 build).
     *
     * 검증 (E-001~E-003):
     * - apiKey null 또는 blank → [AiException.Configuration] (E-001)
     * - providerId가 v0.1에서 지원되지 않는 값 → [AiException.Configuration] (E-002)
     * - timeout < 1초 → [AiException.Configuration] (E-003)
     *
     * @throws AiException.Configuration 검증 실패 시
     */
    public fun build(): AiAgentClient {
        // E-001: API 키 미설정/공백
        val key = apiKey
        if (key.isNullOrBlank()) {
            throw AiException.Configuration("api key is required")
        }

        // E-002: 알 수 없는 Provider
        // v0.1은 enum에 CLAUDE만 존재 (R-009)하므로, 정의된 enum 값은 모두 지원.
        // 후속 라운드에서 enum이 확장되었을 때 SDK가 아직 구현 못한 값에 대비해
        // 명시적 화이트리스트를 사용한다.
        if (providerId !in SUPPORTED_PROVIDERS) {
            throw AiException.Configuration("unknown provider: $providerId")
        }

        // E-003: timeout < 1초
        if (timeout < MIN_TIMEOUT) {
            throw AiException.Configuration(
                "timeout must be at least 1 second, got $timeout",
            )
        }

        // F-005: ProviderRegistry 구성 (P-002).
        // 정상 진입 시 v0.1은 ClaudeProvider 단일 등록 (provider-spec.md P-CLAUDE).
        // 단위 테스트는 [providers] internal hook으로 Mock Provider를 주입.
        val providers: Set<Provider> = providersOverride ?: defaultProviders()
        val registry = ProviderRegistry(providers)

        // F-007: SessionStore 구성 (M-011 영속화).
        // 정상 진입 시 [DataStoreSessionStore]를 appContext로 자동 생성한다.
        // 단위 테스트는 [sessionStore] internal hook으로 Fake SessionStore를 주입.
        // Hilt 진입 시에는 SdkModule.provideSessionStore가 본 위치를 우회하여 직접 주입.
        val store: SessionStore = sessionStoreOverride ?: DataStoreSessionStore(appContext)

        return AiAgentClient(
            context = appContext,
            apiKey = key,
            initialProviderId = providerId,
            modelId = modelId,
            timeout = timeout,
            providerRegistry = registry,
            sessionStore = store,
        )
    }

    public companion object {
        /** 사양상 기본 모델은 미명시. 합리적 기본값으로 P-CLAUDE 첫 모델을 사용. */
        public const val DEFAULT_MODEL_ID: String = "claude-opus-4-7"

        /** 사양상 기본 timeout 미명시. api.md 사용 예에서 30초 사용 → 기본값 채택. */
        public val DEFAULT_TIMEOUT: Duration = 30.seconds

        /** E-003 경계값. */
        public val MIN_TIMEOUT: Duration = 1.seconds

        /**
         * v0.1에서 SDK가 실제 동작을 보장하는 Provider 화이트리스트.
         * R-009에 따라 enum에 추가되는 모든 값은 즉시 동작 가능해야 하므로,
         * 본 집합은 enum 확장 시 함께 갱신된다.
         *
         * 본 화이트리스트는 [defaultProviders] 와 동기화되어야 한다 (회귀 테스트 참조).
         */
        internal val SUPPORTED_PROVIDERS: Set<ProviderId> = setOf(ProviderId.CLAUDE)

        /**
         * v0.1 시점 Builder가 자동 등록하는 기본 Provider 인스턴스 집합 (F-005, P-CLAUDE).
         *
         * F-006 (Hilt 모듈) 진입 시 본 메서드는 그대로 두되 Hilt 측 `@Provides @IntoSet`이
         * 1순위 진입경로가 된다 (Hilt 미사용 호출자는 본 경로로 동작).
         *
         * 매 build() 호출마다 새 [ClaudeProvider] 인스턴스를 만든다 — Provider 본체가
         * 상태를 갖지 않으므로 인스턴스 재사용은 비요구이며, 단순성이 우선.
         */
        internal fun defaultProviders(): Set<Provider> = setOf(ClaudeProvider())
    }
}
