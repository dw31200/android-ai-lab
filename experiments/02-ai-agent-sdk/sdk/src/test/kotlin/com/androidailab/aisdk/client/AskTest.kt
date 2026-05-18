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
import com.androidailab.aisdk.provider.ProviderRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * F-001 (텍스트 단발 질의) AiAgentClient.ask 단위 테스트.
 *
 * 사양 참조:
 * - features.md F-001 정상 흐름 / 예외 흐름 (E-101~E-110)
 * - api.md A-002 시그니처: `suspend fun ask(request: AiRequest): Result<AiResponse>`
 * - error-handling.md ERR-001~ERR-006 매핑
 * - features.md F-005 R-007 (활성 Provider 캡쳐)
 * - features.md F-008 R-020 케이스 A/B (close 시맨틱)
 * - features.md D-005 (자동 재시도 없음)
 *
 * 테스트 함수명에 사양 ID를 박아 추적성 보장 (스킬 SKILL.md "단위 테스트").
 *
 * 본 테스트가 검증하는 것:
 * 1. F-001 정상 흐름 — Provider 정상 응답 → Result.success
 * 2. R-005 빈 응답 — END_TURN/STOP_SEQUENCE에서는 OK, MAX_TOKENS/OTHER에서는 E-110 ServerError
 * 3. R-020 케이스 B — close 후 ask → Result.failure(Configuration("client closed"))
 * 4. R-007 — ask 진행 중 useProvider 호출이 현 ask에 영향 없음 (지역 캡쳐)
 * 5. E-106 — 코루틴 취소는 그대로 전파 (Result로 감싸지 않음)
 * 6. AiException pass-through — Provider가 throw → Result.failure로 그대로 전달
 * 7. D-005 — RateLimit 시 즉시 실패 (Provider 호출 1회)
 * 8. IllegalArgumentException → InvalidInput 매핑
 * 9. 예상치 못한 예외 → ServerError 매핑
 */
class AskTest {

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
    // F-001 정상 흐름
    // -----------------------------------------------------------------

    @Test
    fun `F-001 정상 흐름 — Provider가 정상 응답을 반환하면 Result_success`() = runTest {
        val expected = AiResponse(
            text = "hello",
            usage = TokenUsage(inputTokens = 5, outputTokens = 7),
            finishReason = FinishReason.END_TURN,
            providerId = ProviderId.CLAUDE,
        )
        val provider = FakeProvider(ProviderId.CLAUDE, completeBehavior = { _, _ -> expected })
        val client = newClient(provider)

        val result = client.ask(AiRequest(prompt = "hi"))

        assertTrue("expected success but was $result", result.isSuccess)
        assertEquals(expected, result.getOrNull())
        assertEquals(1, provider.completeCallCount.get())
    }

    @Test
    fun `F-001 정상 흐름 — config 가 currentProviderConfig 와 같은 값으로 Provider 에 전달됨`() = runTest {
        var receivedConfig: ProviderConfig? = null
        val provider = FakeProvider(
            id = ProviderId.CLAUDE,
            completeBehavior = { _, config ->
                receivedConfig = config
                AiResponse(
                    text = "ok",
                    usage = TokenUsage(0, 0),
                    finishReason = FinishReason.END_TURN,
                    providerId = ProviderId.CLAUDE,
                )
            },
        )
        val client = newClient(provider, apiKey = "sk-test", modelId = "claude-opus-4-7", timeout = 30.seconds)

        val result = client.ask(AiRequest(prompt = "hi"))

        assertTrue(result.isSuccess)
        assertNotNull(receivedConfig)
        assertEquals("sk-test", receivedConfig!!.apiKey)
        assertEquals("claude-opus-4-7", receivedConfig!!.modelId)
        assertEquals(30.seconds, receivedConfig!!.timeout)
    }

    // -----------------------------------------------------------------
    // R-005 빈 응답 검증 (F-001 정상 흐름 4단계, E-110)
    // -----------------------------------------------------------------

    @Test
    fun `F-001 R-005 — 빈 텍스트 + END_TURN 이면 그대로 Result_success`() = runTest {
        val emptyOk = AiResponse(
            text = "",
            usage = TokenUsage(0, 0),
            finishReason = FinishReason.END_TURN,
            providerId = ProviderId.CLAUDE,
        )
        val client = newClient(FakeProvider(ProviderId.CLAUDE, completeBehavior = { _, _ -> emptyOk }))

        val result = client.ask(AiRequest(prompt = "hi"))

        assertTrue(result.isSuccess)
        assertEquals("", result.getOrNull()?.text)
        assertEquals(FinishReason.END_TURN, result.getOrNull()?.finishReason)
    }

    @Test
    fun `F-001 R-005 — 빈 텍스트 + STOP_SEQUENCE 이면 그대로 Result_success`() = runTest {
        val emptyOk = AiResponse(
            text = "",
            usage = TokenUsage(0, 0),
            finishReason = FinishReason.STOP_SEQUENCE,
            providerId = ProviderId.CLAUDE,
        )
        val client = newClient(FakeProvider(ProviderId.CLAUDE, completeBehavior = { _, _ -> emptyOk }))

        val result = client.ask(AiRequest(prompt = "hi"))

        assertTrue(result.isSuccess)
    }

    @Test
    fun `F-001 E-110 — 빈 텍스트 + MAX_TOKENS 이면 ServerError(-1, empty response)`() = runTest {
        val emptyErr = AiResponse(
            text = "",
            usage = TokenUsage(0, 0),
            finishReason = FinishReason.MAX_TOKENS,
            providerId = ProviderId.CLAUDE,
        )
        val client = newClient(FakeProvider(ProviderId.CLAUDE, completeBehavior = { _, _ -> emptyErr }))

        val result = client.ask(AiRequest(prompt = "hi"))

        assertTrue("expected failure but was $result", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("expected ServerError, got ${ex?.javaClass?.simpleName}", ex is AiException.ServerError)
        ex as AiException.ServerError
        assertEquals(-1, ex.code)
        assertTrue(
            "message should mention 'empty response', actual=${ex.message}",
            ex.message?.contains("empty response", ignoreCase = true) == true,
        )
    }

    @Test
    fun `F-001 E-110 — 빈 텍스트 + OTHER 이면 ServerError(-1, empty response)`() = runTest {
        val emptyErr = AiResponse(
            text = "",
            usage = TokenUsage(0, 0),
            finishReason = FinishReason.OTHER,
            providerId = ProviderId.CLAUDE,
        )
        val client = newClient(FakeProvider(ProviderId.CLAUDE, completeBehavior = { _, _ -> emptyErr }))

        val result = client.ask(AiRequest(prompt = "hi"))

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as AiException.ServerError
        assertEquals(-1, ex.code)
    }

    // -----------------------------------------------------------------
    // R-020 케이스 B — close 후 ask
    // -----------------------------------------------------------------

    @Test
    fun `F-001 R-020 케이스 B — close 후 ask 호출 시 Result_failure(Configuration(client closed))`() = runTest {
        val client = newClient(FakeProvider(ProviderId.CLAUDE))
        client.close()

        val result = client.ask(AiRequest(prompt = "hi"))

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("expected Configuration, got ${ex?.javaClass?.simpleName}", ex is AiException.Configuration)
        ex as AiException.Configuration
        assertTrue(
            "message should be 'client closed', actual=${ex.message}",
            ex.message?.contains("client closed", ignoreCase = true) == true,
        )
    }

    // -----------------------------------------------------------------
    // R-007 — 진행 중 ask 는 진입 시점 Provider 인스턴스를 캡쳐 (useProvider 영향 없음)
    // -----------------------------------------------------------------

    @Test
    fun `F-001 R-007 — ask 진행 중 useProvider 호출은 현재 ask 의 Provider 에 영향 없음`() = runTest {
        // 진입 시점에 잡힌 Provider 가 응답할 때까지 대기 가능하도록 Deferred 로 차단
        val gate = CompletableDeferred<Unit>()
        val originalResponse = AiResponse(
            text = "from-original",
            usage = TokenUsage(0, 0),
            finishReason = FinishReason.END_TURN,
            providerId = ProviderId.CLAUDE,
        )
        val original = FakeProvider(
            ProviderId.CLAUDE,
            completeBehavior = { _, _ ->
                gate.await()
                originalResponse
            },
        )
        val client = newClient(original)

        // ask 시작 (suspended at gate.await())
        val deferred = async { client.ask(AiRequest(prompt = "hi")) }

        // 진행 중에 useProvider 재호출 — 동일 id 라 새 Provider 인스턴스가 등록되진 않지만,
        // useProvider 호출 자체가 진행 중 ask 에 영향(throw/취소) 주지 않는지 확인.
        client.useProvider(ProviderId.CLAUDE)

        // 진행 중 ask 풀어주기
        gate.complete(Unit)
        val result = deferred.await()

        assertTrue(result.isSuccess)
        // 원래 Provider 가 응답했음을 확인 (다른 Provider 인스턴스로 교체되지 않았다)
        assertEquals("from-original", result.getOrNull()?.text)
        assertEquals(1, original.completeCallCount.get())
    }

    // -----------------------------------------------------------------
    // E-106 — 코루틴 취소는 그대로 전파
    // -----------------------------------------------------------------

    @Test
    fun `F-001 E-106 — ask 도중 cancel 되면 CancellationException 그대로 전파 (Result 로 감싸지 않음)`() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val provider = FakeProvider(
            ProviderId.CLAUDE,
            completeBehavior = { _, _ ->
                started.complete(Unit)
                gate.await() // 취소 신호가 올 때까지 대기 (cooperative)
                throw IllegalStateException("must not reach here")
            },
        )
        val client = newClient(provider)

        val deferred = async { client.ask(AiRequest(prompt = "hi")) }
        started.await()

        // 진행 중 ask 코루틴 취소
        deferred.cancel(CancellationException("test cancel"))

        // await 가 CancellationException 을 throw — Result 로 감싸지지 않는다 (E-106)
        try {
            deferred.await()
            fail("E-106 — cancel 된 ask 의 await 는 CancellationException 을 throw 해야 함")
        } catch (e: CancellationException) {
            // 표준 코루틴 시맨틱: Result.failure 로 변환되지 않고 그대로 전파됨
        }
    }

    // -----------------------------------------------------------------
    // AiException pass-through — Provider/Mapper/ErrorMapper 가 이미 변환한 예외 그대로 전달
    // -----------------------------------------------------------------

    @Test
    fun `F-001 — Provider 가 Network 를 throw 하면 Result_failure(Network) 그대로 전달`() = runTest {
        val cause = java.io.IOException("offline")
        val provider = FakeProvider(
            ProviderId.CLAUDE,
            completeBehavior = { _, _ -> throw AiException.Network(cause) },
        )
        val client = newClient(provider)

        val result = client.ask(AiRequest(prompt = "hi"))

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("expected Network, got ${ex?.javaClass?.simpleName}", ex is AiException.Network)
        assertSame(cause, ex!!.cause)
    }

    @Test
    fun `F-001 — Provider 가 Authentication 를 throw 하면 Result_failure(Authentication)`() = runTest {
        val provider = FakeProvider(
            ProviderId.CLAUDE,
            completeBehavior = { _, _ -> throw AiException.Authentication() },
        )
        val client = newClient(provider)

        val result = client.ask(AiRequest(prompt = "hi"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AiException.Authentication)
    }

    @Test
    fun `F-001 D-005 — Provider 가 RateLimit 를 throw 해도 자동 재시도 없이 즉시 Result_failure`() = runTest {
        val callCount = AtomicInteger(0)
        val provider = FakeProvider(
            ProviderId.CLAUDE,
            completeBehavior = { _, _ ->
                callCount.incrementAndGet()
                throw AiException.RateLimit(retryAfter = null)
            },
        )
        val client = newClient(provider)

        val result = client.ask(AiRequest(prompt = "hi"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AiException.RateLimit)
        // D-005: 자동 재시도 없음 — Provider.complete 는 정확히 1회만 호출되어야 함
        assertEquals("D-005: no auto retry, complete must be called exactly once", 1, callCount.get())
    }

    // -----------------------------------------------------------------
    // 예외 매핑 안전망 (사양 작업 원칙 6)
    // -----------------------------------------------------------------

    @Test
    fun `F-001 — Provider 가 IllegalArgumentException 을 throw 하면 InvalidInput 으로 매핑`() = runTest {
        val provider = FakeProvider(
            ProviderId.CLAUDE,
            completeBehavior = { _, _ -> throw IllegalArgumentException("bad arg") },
        )
        val client = newClient(provider)

        val result = client.ask(AiRequest(prompt = "hi"))

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("expected InvalidInput, got ${ex?.javaClass?.simpleName}", ex is AiException.InvalidInput)
        assertTrue(ex!!.message?.contains("bad arg") == true)
    }

    @Test
    fun `F-001 — Provider 가 예상치 못한 예외를 throw 하면 ServerError 로 매핑 (안전망)`() = runTest {
        val provider = FakeProvider(
            ProviderId.CLAUDE,
            completeBehavior = { _, _ -> throw RuntimeException("unexpected") },
        )
        val client = newClient(provider)

        val result = client.ask(AiRequest(prompt = "hi"))

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("expected ServerError, got ${ex?.javaClass?.simpleName}", ex is AiException.ServerError)
        ex as AiException.ServerError
        assertEquals(-1, ex.code)
        assertTrue(
            "message should mention 'unexpected', actual=${ex.message}",
            ex.message?.contains("unexpected", ignoreCase = true) == true,
        )
    }

    // -----------------------------------------------------------------
    // 헬퍼
    // -----------------------------------------------------------------

    private fun newClient(
        provider: Provider,
        apiKey: String = "sk-test",
        modelId: String = "claude-opus-4-7",
        timeout: Duration = 30.seconds,
    ): AiAgentClient = AiAgentClient(
        context = context,
        apiKey = apiKey,
        initialProviderId = provider.id,
        modelId = modelId,
        timeout = timeout,
        providerRegistry = ProviderRegistry(setOf(provider)),
    )

    /**
     * 테스트용 Fake Provider — F-001 ask 검증에 충분한 최소 구현.
     *
     * [completeBehavior] 람다로 호출별 응답/예외를 제어 가능. 호출 횟수는 [completeCallCount] 로 기록한다
     * (D-005 자동 재시도 없음 검증).
     */
    private class FakeProvider(
        override val id: ProviderId,
        private val completeBehavior: suspend (AiRequest, ProviderConfig) -> AiResponse = { _, _ ->
            AiResponse(
                text = "fake",
                usage = TokenUsage(0, 0),
                finishReason = FinishReason.END_TURN,
                providerId = id,
            )
        },
    ) : Provider {

        val completeCallCount: AtomicInteger = AtomicInteger(0)

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
        ): AiResponse {
            completeCallCount.incrementAndGet()
            return completeBehavior(request, config)
        }

        override fun stream(
            request: AiRequest,
            config: ProviderConfig,
        ): Flow<AiStreamEvent> = flowOf()
    }
}
