package com.androidailab.aisdk.di

import android.content.Context
import com.androidailab.aisdk.AiAgentClient
import com.androidailab.aisdk.client.Builder
import com.androidailab.aisdk.internal.storage.DataStoreSessionStore
import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.AiRequest
import com.androidailab.aisdk.model.AiResponse
import com.androidailab.aisdk.model.AiStreamEvent
import com.androidailab.aisdk.model.FinishReason
import com.androidailab.aisdk.model.ProviderId
import com.androidailab.aisdk.model.TokenUsage
import com.androidailab.aisdk.provider.Capabilities
import com.androidailab.aisdk.provider.Provider
import com.androidailab.aisdk.provider.ProviderConfig
import com.androidailab.aisdk.provider.ProviderRegistry
import com.androidailab.aisdk.provider.claude.ClaudeProvider
import com.androidailab.aisdk.session.SessionEntity
import com.androidailab.aisdk.session.SessionStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * F-006 [SdkModule] Hilt 모듈 단위 테스트.
 *
 * 사양 참조:
 * - features.md F-006 정상 흐름 1~3 (Hilt 자동 적용 + `@Inject AiAgentClient`)
 * - features.md F-006 NFR (Hilt 미사용 호출자도 Builder 라우트로 사용 가능)
 * - features.md F-006 E-602 (BuildConfig API 키 미설정 → ERR-004)
 * - api.md A-001 기본값 (model="claude-opus-4-7" / timeout=30.seconds)
 * - provider-spec.md P-002 (`Set<@JvmSuppressWildcards Provider>` → ProviderRegistry)
 * - provider-spec.md "신규 Provider 추가 절차 R-015" 5단계 (`@Provides @IntoSet`)
 * - data-model.md M-010 [ProviderId.CLAUDE] (v0.1 단일 — R-009)
 *
 * 검증 전략:
 * - Hilt 컴포넌트 조립은 `@HiltAndroidTest`(androidTest 영역)에서 수행해야 하므로 본 라운드(unitTest)에서는
 *   [SdkModule]의 각 `@Provides` 함수를 직접 호출하여 그래프 노드의 조립이 사양에 맞는지 검증한다.
 * - 호출자 측 `@Provides @ApiKey String` 바인딩은 [provideAiAgentClient]의 인자 자리에 직접 주입(string 변수)으로 시뮬레이션.
 * - F-001/F-002/F-003 회귀 — SdkModule이 만드는 [AiAgentClient]가 기존 ask/askStream 흐름 그대로 사용 가능한지
 *   확인하기 위해 빌드 후 [AiAgentClient.activeProviderId] / [AiAgentClient.providerRegistry]를 비교.
 */
class SdkModuleTest {

    private val appContext: Context = mockk(relaxed = true)

    init {
        // AiAgentClient는 context.applicationContext만 보관하므로 mock에서 self-return.
        every { appContext.applicationContext } returns appContext
    }

    // -----------------------------------------------------------------
    // provideClaudeProvider — F-006 / R-015 5단계
    // -----------------------------------------------------------------

    @Test
    fun `F-006 provideClaudeProvider 는 ClaudeProvider 인스턴스를 Provider 로 반환`() {
        val provider: Provider = SdkModule.provideClaudeProvider()

        assertEquals(ProviderId.CLAUDE, provider.id)
        assertTrue(
            "expected ClaudeProvider, got ${provider::class.simpleName}",
            provider is ClaudeProvider,
        )
    }

    @Test
    fun `F-006 provideClaudeProvider 의 capabilities 는 P-CLAUDE 사양과 일치`() {
        val provider = SdkModule.provideClaudeProvider()

        // provider-spec.md P-CLAUDE 상세 — Capabilities 값 검증
        assertEquals(true, provider.capabilities.supportsImage)
        assertEquals(false, provider.capabilities.supportsVideo)
        assertEquals(true, provider.capabilities.supportsStream)
        assertEquals(true, provider.capabilities.supportsSession)
        assertEquals(5L * 1024 * 1024, provider.capabilities.maxImageSizeBytes)
        assertEquals(10, provider.capabilities.maxImagesPerRequest)
        assertEquals(
            setOf("image/jpeg", "image/png", "image/webp", "image/gif"),
            provider.capabilities.supportedImageMimeTypes,
        )
    }

    // -----------------------------------------------------------------
    // provideProviderRegistry — P-002
    // -----------------------------------------------------------------

    @Test
    fun `F-006 provideProviderRegistry 는 입력 Set 의 Provider 를 모두 등록`() {
        val claude = SdkModule.provideClaudeProvider()
        val registry: ProviderRegistry = SdkModule.provideProviderRegistry(setOf(claude))

        assertSame(claude, registry.get(ProviderId.CLAUDE))
        assertEquals(1, registry.list().size)
    }

    @Test
    fun `F-006 provideProviderRegistry 는 빈 Set 으로도 안전하게 인스턴스화 (E-501 방어)`() {
        // 호출자가 SdkModule 합산 도중 일시적으로 빈 set을 받을 가능성에 대비.
        // 본 케이스는 후속 ProviderRegistry.get 시 E-501이 발효될 뿐이며, Registry 자체 인스턴스화는 통과해야 한다.
        val registry = SdkModule.provideProviderRegistry(emptySet())

        assertEquals(0, registry.list().size)
    }

    // -----------------------------------------------------------------
    // provideAiAgentClient — F-006 정상 흐름 / E-602
    // -----------------------------------------------------------------

    @Test
    fun `F-006 정상 흐름 — provideAiAgentClient 는 ApiKey 와 ProviderRegistry 로 client 빌드`() {
        val claude = SdkModule.provideClaudeProvider()
        val registry = SdkModule.provideProviderRegistry(setOf(claude))
        val store = FakeSessionStore()

        val client = SdkModule.provideAiAgentClient(
            appContext = appContext,
            apiKey = "sk-test-1234",
            providerRegistry = registry,
            sessionStore = store,
        )

        // 활성 Provider는 v0.1 기본값 CLAUDE (api.md A-001)
        assertEquals(ProviderId.CLAUDE, client.activeProviderId)
        // ProviderRegistry는 SdkModule이 빌드한 것을 그대로 가짐
        assertSame(
            claude,
            client.providerRegistry.get(ProviderId.CLAUDE),
        )
        // F-007 — Hilt가 주입한 sessionStore 가 그대로 client.sessionStore 에 보관됨
        assertSame(store, client.sessionStore)
        // F-006 사용 예 — Hilt가 만든 client가 정상 작동 상태인지
        assertNotNull(client)
    }

    @Test
    fun `F-006 E-602 — ApiKey 가 빈 문자열이면 Configuration ('api key is required') throw`() {
        // 호출자가 BuildConfig.AI_API_KEY를 빈 문자열로 채운 상황(E-602 시나리오) — 빌더가 E-001로 거부한다.
        val registry = SdkModule.provideProviderRegistry(setOf(SdkModule.provideClaudeProvider()))
        val store = FakeSessionStore()

        try {
            SdkModule.provideAiAgentClient(
                appContext = appContext,
                apiKey = "",
                providerRegistry = registry,
                sessionStore = store,
            )
            fail("expected AiException.Configuration but no exception was thrown")
        } catch (e: AiException.Configuration) {
            assertTrue(
                "message should mention 'api key', actual=${e.message}",
                e.message?.contains("api key", ignoreCase = true) == true,
            )
        }
    }

    @Test
    fun `F-006 E-602 — ApiKey 가 공백만 있어도 Configuration throw (Builder의 isNullOrBlank 검증)`() {
        val registry = SdkModule.provideProviderRegistry(setOf(SdkModule.provideClaudeProvider()))
        val store = FakeSessionStore()

        try {
            SdkModule.provideAiAgentClient(
                appContext = appContext,
                apiKey = "   ",
                providerRegistry = registry,
                sessionStore = store,
            )
            fail("expected AiException.Configuration but no exception was thrown")
        } catch (_: AiException.Configuration) {
            // 정상 — Builder.build()의 isNullOrBlank 검증
        }
    }

    @Test
    fun `F-006 정상 흐름 — provideAiAgentClient 가 빌드한 client 의 ProviderRegistry 가 전달된 것을 그대로 사용`() {
        // 실제 ClaudeProvider 외에도 다양한 Mock Provider 를 합산해 그래프 합산이 정확한지 검증.
        val claude = SdkModule.provideClaudeProvider()
        val registry = SdkModule.provideProviderRegistry(setOf(claude))
        val store = FakeSessionStore()

        val client = SdkModule.provideAiAgentClient(
            appContext = appContext,
            apiKey = "sk-test",
            providerRegistry = registry,
            sessionStore = store,
        )

        // 빌드된 client의 internal providerRegistry가 SdkModule이 만든 인스턴스를 wrapping (Builder가
        // providerRegistry.list().toSet() 으로 다시 구성하므로 같은 ProviderId 매핑이 유지되는지만 검증).
        assertSame(claude, client.providerRegistry.get(ProviderId.CLAUDE))
    }

    // -----------------------------------------------------------------
    // provideSessionStore — F-007 / M-011 (T18 라운드 추가)
    // -----------------------------------------------------------------

    @Test
    fun `F-007 provideSessionStore 는 DataStoreSessionStore 를 SessionStore 로 반환`() {
        val store: SessionStore = SdkModule.provideSessionStore(appContext)
        assertTrue(
            "expected DataStoreSessionStore, got ${store::class.simpleName}",
            store is DataStoreSessionStore,
        )
    }

    @Test
    fun `F-007 provideSessionStore 매 호출마다 새 인스턴스 (Singleton 보장은 Hilt 측 책임)`() {
        // 본 unitTest는 Hilt 컴포넌트 조립을 하지 않으므로 매 호출 새 인스턴스가 만들어진다.
        // 실제 Hilt 진입에서는 @Singleton 어노테이션으로 단일 인스턴스 보장 (DataStore Preferences 충돌 방지).
        val s1 = SdkModule.provideSessionStore(appContext)
        val s2 = SdkModule.provideSessionStore(appContext)
        assertNotNull(s1)
        assertNotNull(s2)
        // @Singleton은 Hilt 컴포넌트 영역의 정책 — 직접 호출 시 매번 새 인스턴스가 나오는 것이 정상.
        assertNotSame(s1, s2)
    }

    @Test
    fun `F-007 provideAiAgentClient 에 주입된 SessionStore 가 client_sessionStore 에 그대로 보관`() {
        val registry = SdkModule.provideProviderRegistry(setOf(SdkModule.provideClaudeProvider()))
        val store = FakeSessionStore()

        val client = SdkModule.provideAiAgentClient(
            appContext = appContext,
            apiKey = "sk-test",
            providerRegistry = registry,
            sessionStore = store,
        )

        // SessionStore 가 Hilt 주입 → Builder.sessionStore() → AiAgentClient.sessionStore 경로로 전달됨.
        assertSame(store, client.sessionStore)
    }

    // -----------------------------------------------------------------
    // provideBuilder — F-006 보조 진입점
    // -----------------------------------------------------------------

    @Test
    fun `F-006 provideBuilder 는 매 호출마다 새 Builder 인스턴스 반환 (Singleton 아님)`() {
        val builder1: Builder = SdkModule.provideBuilder(appContext)
        val builder2: Builder = SdkModule.provideBuilder(appContext)

        // Builder는 mutable state를 갖고 단일 스레드 사용을 가정 (F-000 NFR) — 매번 새 인스턴스
        assertNotNull(builder1)
        assertNotNull(builder2)
        // Same instance 여부는 강제하지 않지만, Builder는 @Singleton이 아니므로 둘은 별개 인스턴스가 되어야 한다.
        // Hilt unscoped @Provides는 매 주입마다 새 인스턴스를 만들지만, 직접 호출에서는 매 호출 새로 생성됨.
        assertTrue(builder1 !== builder2)
    }

    @Test
    fun `F-006 provideBuilder 가 반환한 Builder 로 client 빌드가 가능 (NFR — Hilt 옵션, Builder 양립)`() {
        val builder = SdkModule.provideBuilder(appContext)

        val client = builder
            .apiKey("sk-test")
            .build()

        assertEquals(ProviderId.CLAUDE, client.activeProviderId)
    }

    // -----------------------------------------------------------------
    // 멀티 Provider 등록 시나리오 — provider-spec.md "신규 Provider 추가 절차 R-015"
    // -----------------------------------------------------------------

    @Test
    fun `F-006 멀티 Provider — 호출자가 추가 Provider 를 IntoSet 으로 합산하면 ProviderRegistry 에 모두 등록`() {
        // v0.1은 enum에 CLAUDE만 존재(R-009)하나, 미래 v0.2/v0.3에서 OpenAI/Gemini가 추가되었을 때
        // 그래프 합산 정합을 미리 검증. FakeProvider로 시뮬레이션.
        val claude = SdkModule.provideClaudeProvider()
        val fake = FakeAdditionalProvider(ProviderId.CLAUDE) // v0.1 enum 한계로 동일 id

        // Set 은 동일 인스턴스 중복 제거하지만 ProviderId 같은 두 인스턴스는 ProviderRegistry의
        // associateBy last-write-wins로 처리됨 (P-002 정책). 본 케이스는 last-write-wins 회귀 보호.
        val registry = SdkModule.provideProviderRegistry(linkedSetOf(claude, fake))

        // last-write-wins
        assertSame(fake, registry.get(ProviderId.CLAUDE))
    }

    @Test
    fun `F-006 멀티 Provider — 빈 Set 그래프도 인스턴스화는 통과 (E-602 와 별도)`() {
        // Hilt가 ClaudeProvider @Provides를 누락(예: 호출자가 SdkModule을 부분 대체)했을 때
        // ProviderRegistry는 빈 Set으로 인스턴스화되어야 한다. 후속 useProvider 호출 시 E-501.
        val registry = SdkModule.provideProviderRegistry(emptySet())

        assertEquals(0, registry.list().size)
        // Builder.build 시점에서 Builder.SUPPORTED_PROVIDERS 화이트리스트에 CLAUDE가 있어 E-002는 통과,
        // 그러나 useProvider 호출 시점에 registry.contains(CLAUDE) 가 false라 E-501 발효된다.
        // 본 시나리오는 호출자가 SDK 모듈을 부분 대체했을 때의 그래프 안전성 검증 — 인스턴스화 자체는 통과해야 함.
    }

    // -----------------------------------------------------------------
    // 기본값 상수 회귀 — api.md A-001 / S-001 / S-002
    // -----------------------------------------------------------------

    @Test
    fun `F-006 기본값 상수 — DEFAULT_PROVIDER_ID 는 ProviderId_CLAUDE (api_md A-001 R-009)`() {
        assertEquals(ProviderId.CLAUDE, SdkModule.DEFAULT_PROVIDER_ID)
    }

    @Test
    fun `F-006 기본값 상수 — DEFAULT_MODEL_ID 는 Builder_DEFAULT_MODEL_ID 와 동일 (S-001)`() {
        assertEquals(Builder.DEFAULT_MODEL_ID, SdkModule.DEFAULT_MODEL_ID)
        assertEquals("claude-opus-4-7", SdkModule.DEFAULT_MODEL_ID)
    }

    @Test
    fun `F-006 기본값 상수 — DEFAULT_TIMEOUT 은 Builder_DEFAULT_TIMEOUT 과 동일 (S-002)`() {
        assertEquals(Builder.DEFAULT_TIMEOUT, SdkModule.DEFAULT_TIMEOUT)
    }

    // -----------------------------------------------------------------
    // F-001/F-002/F-003 회귀 보호 — provideAiAgentClient 가 만든 client 의 그래프 노드 검증
    // -----------------------------------------------------------------

    @Test
    fun `F-006 회귀 — Hilt 가 만든 client 의 ProviderRegistry 는 ClaudeProvider 를 보유 (F-001 진입 보호)`() {
        // Hilt 진입 client가 F-001 ask 호출 시 client.activeProvider 를 통해 ClaudeProvider 를 찾을 수 있어야 한다.
        val claude = SdkModule.provideClaudeProvider()
        val registry = SdkModule.provideProviderRegistry(setOf(claude))

        val client = SdkModule.provideAiAgentClient(
            appContext = appContext,
            apiKey = "sk-test",
            providerRegistry = registry,
            sessionStore = FakeSessionStore(),
        )

        // F-001/F-002/F-003 진입 시 사용하는 activeProvider 가 ClaudeProvider 로 풀려야 한다.
        val resolved = client.providerRegistry.get(ProviderId.CLAUDE)
        assertTrue(
            "expected ClaudeProvider in active resolution",
            resolved is ClaudeProvider,
        )
    }

    // -----------------------------------------------------------------
    // 테스트용 fake provider
    // -----------------------------------------------------------------

    /**
     * F-007 SessionStore 주입 시나리오용 fake. Hilt 단위 검증에서 호출 캡쳐 불필요 — no-op.
     */
    private class FakeSessionStore : SessionStore {
        override suspend fun save(entity: SessionEntity) { /* no-op */ }
        override suspend fun load(sessionId: String): SessionEntity? = null
        override suspend fun delete(sessionId: String) { /* no-op */ }
    }

    /**
     * 멀티 Provider 합산 시나리오용 fake. Capabilities 자체는 의미 없음.
     */
    private class FakeAdditionalProvider(
        override val id: ProviderId,
    ) : Provider {
        override val capabilities: Capabilities = Capabilities(
            supportsImage = false,
            supportsVideo = false,
            supportsStream = false,
            supportsSession = false,
            maxImageSizeBytes = 0,
            maxImagesPerRequest = 0,
            supportedImageMimeTypes = emptySet(),
        )

        override suspend fun complete(
            request: AiRequest,
            config: ProviderConfig,
        ): AiResponse = AiResponse(
            text = "fake-additional",
            usage = TokenUsage(0, 0),
            finishReason = FinishReason.END_TURN,
            providerId = id,
        )

        override fun stream(
            request: AiRequest,
            config: ProviderConfig,
        ): Flow<AiStreamEvent> = flowOf()
    }
}
