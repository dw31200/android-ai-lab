package com.androidailab.aisdk.client

import android.content.Context
import com.androidailab.aisdk.AiAgentClient
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * F-005 (Provider 선택/교체) AiAgentClient.useProvider 단위 테스트.
 *
 * 사양 참조:
 * - features.md F-005 정상 흐름 / 동시성 모델 (R-007) / Session과 Provider의 관계 (R-014)
 * - api.md A-005 시그니처: `fun useProvider(provider: ProviderId)`
 * - error-handling.md E-501 (등록되지 않은 Provider) / E-502 (API 키 누락)
 *
 * 본 라운드(F-005)에서 검증하는 것:
 * 1. useProvider 정상 흐름 — atomic set, activeProvider/activeProviderId 즉시 갱신
 * 2. E-501 — 등록 안된 Provider → AiException.Configuration("unknown provider")
 * 3. E-502 — API 키 누락 → AiException.Configuration("api key missing for provider X")
 *    (v0.1 단일 키 모델에서는 Builder 검증으로 도달 불가하므로 internal 생성자 직접 사용)
 * 4. R-007 동시성 — AtomicReference set이 race 없이 동작
 * 5. close 케이스 B — close 후 useProvider 호출 시 throw (T10이 close()를 추가했으므로 검증 가능)
 *
 * 검증에서 의도적으로 빠진 항목 (후속 라운드 책임):
 * - Session과의 R-014 — F-004 (Session) 진입 후 별도 검증
 * - 진행 중 ask 가 있을 때 useProvider가 영향 없음 — F-001 (ask) 진입 후 별도 검증
 */
class UseProviderTest {

    private lateinit var context: Context
    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = mockk(relaxed = true)
        context = mockk(relaxed = true) {
            every { applicationContext } returns appContext
        }
    }

    // -----------------------------------------------------------------
    // 정상 흐름
    // -----------------------------------------------------------------

    @Test
    fun `F-005 정상 흐름 — useProvider 호출 즉시 activeProviderId 가 갱신됨`() {
        // R-009에 따라 v0.1 enum은 CLAUDE 단일이므로 동일 ProviderId.CLAUDE 로의 재설정만 가능.
        // 본 케이스는 atomic set이 호출되는지(예외 없이 통과)를 확인한다.
        val client = newClientWithFakeProvider(ProviderId.CLAUDE)

        client.useProvider(ProviderId.CLAUDE)

        assertEquals(ProviderId.CLAUDE, client.activeProviderId)
    }

    @Test
    fun `F-005 정상 흐름 — useProvider 후 activeProvider 가 새 Provider 인스턴스를 반환`() {
        val claude = FakeProvider(ProviderId.CLAUDE)
        val client = Builder(context)
            .apiKey("sk-test")
            .providers(setOf(claude))
            .build()

        client.useProvider(ProviderId.CLAUDE)

        assertSame(claude, client.activeProvider)
    }

    @Test
    fun `F-005 정상 흐름 — 두 Provider 가 등록되었을 때 useProvider 가 인스턴스 교체에 반영됨`() {
        // v0.1 enum이 CLAUDE 단일이지만, ProviderRegistry 차원에서 다른 id 객체가
        // 등록된 시나리오는 회귀 방지 차원에서 의미 없음 (enum 동일 값으로는 last-write-wins).
        // 대신, Provider 인스턴스가 교체된 경우 activeProvider getter가 registry를 매번 재조회하는지 확인.
        val original = FakeProvider(ProviderId.CLAUDE)
        val client = Builder(context)
            .apiKey("sk-test")
            .providers(setOf(original))
            .build()

        // 동일 id로 useProvider 호출해도 registry의 매번 재조회로 같은 인스턴스 반환
        client.useProvider(ProviderId.CLAUDE)
        val first = client.activeProvider
        client.useProvider(ProviderId.CLAUDE)
        val second = client.activeProvider

        assertSame(first, second)
        assertSame(original, first)
    }

    @Test
    fun `F-005 R-014 — useProvider 는 동기 함수이며 즉시 반환 (수 ns)`() {
        val client = newClientWithFakeProvider(ProviderId.CLAUDE)

        val start = System.nanoTime()
        client.useProvider(ProviderId.CLAUDE)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        // 사양 NFR: "Provider 교체는 즉시 적용 (atomic set, 수 ns)"
        // 단위 테스트의 환경 노이즈를 감안해 100ms로 헐겁게 잡음 (회귀 검출 목적).
        assertTrue("useProvider should be near-immediate, took ${elapsedMs}ms", elapsedMs < 100)
    }

    // -----------------------------------------------------------------
    // E-501 — 등록되지 않은 Provider
    // -----------------------------------------------------------------

    @Test
    fun `F-005 E-501 — registry 에 등록 안된 ProviderId 를 useProvider 호출 시 Configuration throw`() {
        // registry는 비어있는 상태로 client를 강제 구성 (Builder는 자동 등록하므로 internal 경로 사용)
        val client = AiAgentClient(
            context = context,
            apiKey = "sk-test",
            initialProviderId = ProviderId.CLAUDE,
            modelId = "claude-opus-4-7",
            timeout = 30.seconds,
            providerRegistry = com.androidailab.aisdk.provider.ProviderRegistry(emptySet()),
        )

        try {
            client.useProvider(ProviderId.CLAUDE)
            fail("expected AiException.Configuration but no exception was thrown")
        } catch (e: AiException.Configuration) {
            assertTrue(
                "message should mention 'unknown provider', actual=${e.message}",
                e.message?.contains("unknown provider", ignoreCase = true) == true,
            )
        }
    }

    // -----------------------------------------------------------------
    // E-502 — API 키 누락 (v0.1은 Builder.build로 사전 차단되므로 internal 생성자 직접 사용)
    // -----------------------------------------------------------------

    @Test
    fun `F-005 E-502 — apiKey 가 비어있을 때 useProvider 호출 시 Configuration throw`() {
        val claude = FakeProvider(ProviderId.CLAUDE)
        // 정상 진입 경로(Builder)에서는 E-001로 차단되므로 internal 생성자 직접 사용.
        // E-502 정책 검증 자체는 useProvider 본문의 방어적 검증 로직이 동작함을 확인.
        val client = AiAgentClient(
            context = context,
            apiKey = "",
            initialProviderId = ProviderId.CLAUDE,
            modelId = "claude-opus-4-7",
            timeout = 30.seconds,
            providerRegistry = com.androidailab.aisdk.provider.ProviderRegistry(setOf(claude)),
        )

        try {
            client.useProvider(ProviderId.CLAUDE)
            fail("expected AiException.Configuration but no exception was thrown")
        } catch (e: AiException.Configuration) {
            assertTrue(
                "message should mention 'api key missing', actual=${e.message}",
                e.message?.contains("api key missing", ignoreCase = true) == true,
            )
        }
    }

    // -----------------------------------------------------------------
    // R-007 — AtomicReference 동시성
    // -----------------------------------------------------------------

    @Test
    fun `F-005 R-007 — useProvider 동시 호출에 대해 race 없이 안전하게 set 됨`() {
        val client = newClientWithFakeProvider(ProviderId.CLAUDE)
        val threadCount = 16
        val iterPerThread = 200
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val errors = AtomicInteger(0)

        val workers = List(threadCount) {
            thread(start = false) {
                ready.countDown()
                start.await(5, TimeUnit.SECONDS)
                try {
                    repeat(iterPerThread) {
                        client.useProvider(ProviderId.CLAUDE)
                    }
                } catch (_: Throwable) {
                    errors.incrementAndGet()
                }
            }
        }

        workers.forEach { it.start() }
        ready.await(5, TimeUnit.SECONDS)
        start.countDown()
        workers.forEach { it.join(5_000) }

        assertEquals("no errors expected from atomic set", 0, errors.get())
        assertEquals(ProviderId.CLAUDE, client.activeProviderId)
    }

    // -----------------------------------------------------------------
    // close 케이스 B (R-020) — close 후 useProvider 호출 시 throw
    // T10이 close()를 추가했으므로 본 검증이 가능. close 동작은 F-008 라운드에서 별도 검증.
    // -----------------------------------------------------------------

    @Test
    fun `F-005 R-020 케이스 B — close 후 useProvider 호출 시 Configuration throw`() {
        val client = newClientWithFakeProvider(ProviderId.CLAUDE)
        client.close()

        try {
            client.useProvider(ProviderId.CLAUDE)
            fail("expected AiException.Configuration but no exception was thrown")
        } catch (e: AiException.Configuration) {
            assertTrue(
                "message should be 'client closed', actual=${e.message}",
                e.message?.contains("client closed", ignoreCase = true) == true,
            )
        }
    }

    // -----------------------------------------------------------------
    // 헬퍼
    // -----------------------------------------------------------------

    private fun newClientWithFakeProvider(id: ProviderId): AiAgentClient {
        val provider = FakeProvider(id)
        return Builder(context)
            .apiKey("sk-test")
            .providers(setOf(provider))
            .build()
    }

    /**
     * 테스트용 Fake Provider — F-005 useProvider 검증에 충분한 최소 구현.
     * complete/stream은 본 라운드에서 호출되지 않는다.
     */
    private class FakeProvider(
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
            text = "fake",
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
