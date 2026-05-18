package com.androidailab.aisdk.client

import android.content.Context
import com.androidailab.aisdk.AiAgentClient
import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.AiRequest
import com.androidailab.aisdk.model.AiResponse
import com.androidailab.aisdk.model.AiStreamEvent
import com.androidailab.aisdk.model.FinishReason
import com.androidailab.aisdk.model.ImageInput
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * F-003 (스트리밍 응답) AiAgentClient.askStream 단위 테스트.
 *
 * 사양 참조:
 * - features.md F-003 정상 흐름 / 예외 흐름 (E-301 / E-302 / E-303 / E-101~E-110)
 * - api.md A-003 시그니처: `fun askStream(request: AiRequest): Flow<AiStreamEvent>`
 * - data-model.md M-006 [AiStreamEvent] 방출 순서 (Delta 0+ → Done | Error 1)
 * - error-handling.md ERR-001 / ERR-004 / ERR-005 / ERR-006 매핑
 * - features.md F-005 R-007 (활성 Provider 캡쳐), F-008 R-020 케이스 B (close 후 호출)
 *
 * 검증 (스킬 SKILL.md "단위 테스트" 명명 규칙):
 * 1. F-003 정상 흐름 — Provider.stream 위임 후 그대로 흘려보냄
 * 2. F-003 R-020 케이스 B — close 후 collect 시 첫 emit으로 Error(Configuration("client closed")) + 종료
 * 3. F-003 E-303 — Provider.supportsStream=false 면 즉시 Error(Configuration) + Provider.stream 미호출
 * 4. F-003 E-302 — collect 도중 cancel 시 CancellationException 그대로 전파
 * 5. F-003 R-007 — collect 진행 중 useProvider 호출이 현재 stream 에 영향 없음
 * 6. F-003 / F-002 — 이미지 검증 재사용 (E-201/E-202/E-203/E-204/E-205) — 실패 시 Error 후 종료
 * 7. F-003 — Provider 가 throw 한 RuntimeException 은 Error(ServerError) 로 안전 변환
 * 8. F-003 — Provider 가 throw 한 AiException 은 Error 로 그대로 wrap
 * 9. F-003 회귀 — Builder 흐름과 무관 (테스트는 internal 생성자 사용)
 */
class AskStreamTest {

    private lateinit var context: Context
    private lateinit var appContext: Context

    /** P-CLAUDE Capabilities 샘플 (provider-spec.md). */
    private val claudeCapabilities = Capabilities(
        supportsImage = true,
        supportsVideo = false,
        supportsStream = true,
        supportsSession = true,
        maxImageSizeBytes = 5L * 1024L * 1024L,
        maxImagesPerRequest = 10,
        supportedImageMimeTypes = setOf("image/jpeg", "image/png", "image/webp", "image/gif"),
    )

    @Before
    fun setUp() {
        appContext = mockk(relaxed = true)
        context = mockk(relaxed = true) {
            every { applicationContext } returns appContext
        }
    }

    // -----------------------------------------------------------------
    // F-003 정상 흐름
    // -----------------------------------------------------------------

    @Test
    fun `F-003 정상 흐름 — Provider stream 의 Delta_Done 이벤트가 그대로 collect 에 도달`() = runTest {
        val response = AiResponse(
            text = "Hi friend",
            usage = TokenUsage(inputTokens = 5, outputTokens = 7),
            finishReason = FinishReason.END_TURN,
            providerId = ProviderId.CLAUDE,
        )
        val provider = FakeProvider(
            id = ProviderId.CLAUDE,
            capabilities = claudeCapabilities,
            streamBehavior = {
                flowOf(
                    AiStreamEvent.Delta("Hi"),
                    AiStreamEvent.Delta(" friend"),
                    AiStreamEvent.Done(response),
                )
            },
        )
        val client = newClient(provider)

        val events = client.askStream(AiRequest(prompt = "say hi")).toList()

        assertEquals(3, events.size)
        assertEquals("Hi", (events[0] as AiStreamEvent.Delta).text)
        assertEquals(" friend", (events[1] as AiStreamEvent.Delta).text)
        assertTrue(events[2] is AiStreamEvent.Done)
        assertEquals(response, (events[2] as AiStreamEvent.Done).response)
        assertEquals(1, provider.streamCallCount.get())
    }

    @Test
    fun `F-003 정상 흐름 — config 가 currentProviderConfig 와 같은 값으로 Provider 에 전달됨`() = runTest {
        var receivedConfig: ProviderConfig? = null
        val provider = FakeProvider(
            id = ProviderId.CLAUDE,
            capabilities = claudeCapabilities,
            streamBehavior = { config ->
                receivedConfig = config
                flowOf(AiStreamEvent.Done(dummyResponse()))
            },
        )
        val client = newClient(provider, apiKey = "sk-test", modelId = "claude-opus-4-7", timeout = 30.seconds)

        client.askStream(AiRequest(prompt = "hi")).toList()

        assertNotNull(receivedConfig)
        assertEquals("sk-test", receivedConfig!!.apiKey)
        assertEquals("claude-opus-4-7", receivedConfig!!.modelId)
        assertEquals(30.seconds, receivedConfig!!.timeout)
    }

    // -----------------------------------------------------------------
    // F-003 R-020 케이스 B — close 후 collect
    // -----------------------------------------------------------------

    @Test
    fun `F-003 R-020 케이스 B — close 후 askStream 첫 collect 는 Error(Configuration(client closed)) 후 종료`() = runTest {
        val provider = FakeProvider(ProviderId.CLAUDE, capabilities = claudeCapabilities)
        val client = newClient(provider)
        client.close()

        val events = client.askStream(AiRequest(prompt = "hi")).toList()

        assertEquals(1, events.size)
        val err = events.single() as AiStreamEvent.Error
        assertTrue("expected Configuration, got ${err.cause::class.simpleName}", err.cause is AiException.Configuration)
        assertTrue(
            "메시지 'client closed' 일치",
            err.cause.message?.contains("client closed", ignoreCase = true) == true,
        )
        // close 케이스 B는 Provider.stream 호출하지 않음 (회귀 방지)
        assertEquals(0, provider.streamCallCount.get())
    }

    // -----------------------------------------------------------------
    // F-003 E-303 — Provider 가 스트리밍 미지원
    // -----------------------------------------------------------------

    @Test
    fun `F-003 E-303 — Provider 가 supportsStream=false 면 Error(Configuration) 후 종료, Provider stream 미호출`() = runTest {
        val noStreamCaps = claudeCapabilities.copy(supportsStream = false)
        val provider = FakeProvider(ProviderId.CLAUDE, capabilities = noStreamCaps)
        val client = newClient(provider)

        val events = client.askStream(AiRequest(prompt = "hi")).toList()

        assertEquals(1, events.size)
        val err = events.single() as AiStreamEvent.Error
        assertTrue(err.cause is AiException.Configuration)
        assertTrue(
            "'streaming' 또는 'stream' 키워드 포함",
            err.cause.message?.contains("stream", ignoreCase = true) == true,
        )
        // E-303 매핑 검증: Provider.stream 미호출
        assertEquals(0, provider.streamCallCount.get())
    }

    // -----------------------------------------------------------------
    // F-003 E-302 — 코루틴 취소
    // -----------------------------------------------------------------

    @Test
    fun `F-003 E-302 — collect 도중 cancel 시 CancellationException 전파 (cooperative)`() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val provider = FakeProvider(
            ProviderId.CLAUDE,
            capabilities = claudeCapabilities,
            streamBehavior = {
                flow {
                    started.complete(Unit)
                    gate.await() // 취소 신호가 올 때까지 대기 (cooperative)
                    emit(AiStreamEvent.Done(dummyResponse()))
                }
            },
        )
        val client = newClient(provider)

        val deferred = async {
            client.askStream(AiRequest(prompt = "hi")).toList()
        }
        started.await()

        deferred.cancel(CancellationException("test cancel"))

        try {
            deferred.await()
            fail("E-302 — cancel 된 collect 의 await 는 CancellationException 을 throw 해야 함")
        } catch (e: CancellationException) {
            // 표준 Flow 시맨틱 — Result/Error 로 감싸지지 않고 그대로 전파됨
        }
    }

    // -----------------------------------------------------------------
    // F-003 R-007 — collect 진행 중 useProvider 영향 없음 (지역 캡쳐)
    // -----------------------------------------------------------------

    @Test
    fun `F-003 R-007 — askStream 진행 중 useProvider 호출은 현 stream 에 영향 없음`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val originalResponse = AiResponse(
            text = "from-original",
            usage = TokenUsage(0, 0),
            finishReason = FinishReason.END_TURN,
            providerId = ProviderId.CLAUDE,
        )
        val original = FakeProvider(
            ProviderId.CLAUDE,
            capabilities = claudeCapabilities,
            streamBehavior = {
                flow {
                    gate.await()
                    emit(AiStreamEvent.Done(originalResponse))
                }
            },
        )
        val client = newClient(original)

        val deferred = async {
            client.askStream(AiRequest(prompt = "hi")).toList()
        }

        // 진행 중에 useProvider 동일 id 재호출 — 본 stream 에 영향 없는지 확인
        client.useProvider(ProviderId.CLAUDE)

        gate.complete(Unit)
        val events = deferred.await()

        assertEquals(1, events.size)
        val done = events.single() as AiStreamEvent.Done
        assertEquals("from-original", done.response.text)
        assertEquals(1, original.streamCallCount.get())
    }

    // -----------------------------------------------------------------
    // F-003 / F-002 이미지 검증 재사용 (E-201/E-202/E-204/E-205/E-203)
    // -----------------------------------------------------------------

    @Test
    fun `F-003 E-201 — 단일 이미지 5MB 초과 시 Error(InvalidInput) 후 종료, Provider stream 미호출`() = runTest {
        val provider = FakeProvider(ProviderId.CLAUDE, capabilities = claudeCapabilities)
        val client = newClient(provider)
        val tooBig = ByteArray((5L * 1024L * 1024L).toInt() + 1) { 0 }
        val req = AiRequest(prompt = "x", images = listOf(ImageInput.Bytes(tooBig, "image/png")))

        val events = client.askStream(req).toList()

        assertEquals(1, events.size)
        val err = events.single() as AiStreamEvent.Error
        assertTrue(err.cause is AiException.InvalidInput)
        assertTrue(err.cause.message?.contains("too large", ignoreCase = true) == true)
        assertEquals(0, provider.streamCallCount.get())
    }

    @Test
    fun `F-003 E-204 — 지원하지 않는 mimeType 이미지 시 Error(InvalidInput) 후 종료`() = runTest {
        val provider = FakeProvider(ProviderId.CLAUDE, capabilities = claudeCapabilities)
        val client = newClient(provider)
        val req = AiRequest(
            prompt = "x",
            images = listOf(ImageInput.Bytes(byteArrayOf(1, 2, 3), "image/bmp")),
        )

        val events = client.askStream(req).toList()

        val err = events.single() as AiStreamEvent.Error
        assertTrue(err.cause is AiException.InvalidInput)
        assertTrue(err.cause.message?.contains("unsupported mime type", ignoreCase = true) == true)
        assertEquals(0, provider.streamCallCount.get())
    }

    @Test
    fun `F-003 E-205 — Provider 가 supportsImage=false 면 Configuration ('provider does not support images')`() = runTest {
        // supportsStream=true 이지만 supportsImage=false — F-003 진입 후 이미지 첨부면 E-205 매핑
        val noImageCaps = claudeCapabilities.copy(supportsImage = false)
        val provider = FakeProvider(ProviderId.CLAUDE, capabilities = noImageCaps)
        val client = newClient(provider)
        val req = AiRequest(
            prompt = "x",
            images = listOf(ImageInput.Bytes(byteArrayOf(1, 2, 3), "image/png")),
        )

        val events = client.askStream(req).toList()

        val err = events.single() as AiStreamEvent.Error
        assertTrue(err.cause is AiException.Configuration)
        assertTrue(
            err.cause.message?.contains("provider does not support images") == true,
        )
        assertEquals(0, provider.streamCallCount.get())
    }

    @Test
    fun `F-003 E-203 — ImageInput_Uri 도달 시 Error(InvalidInput uri unreadable) 후 종료`() = runTest {
        val provider = FakeProvider(ProviderId.CLAUDE, capabilities = claudeCapabilities)
        val client = newClient(provider)
        val mockUri = mockk<android.net.Uri>(relaxed = true) {
            every { toString() } returns "content://test/image.jpg"
        }
        val req = AiRequest(prompt = "x", images = listOf(ImageInput.Uri(mockUri)))

        val events = client.askStream(req).toList()

        val err = events.single() as AiStreamEvent.Error
        assertTrue(err.cause is AiException.InvalidInput)
        assertTrue(err.cause.message?.contains("uri unreadable", ignoreCase = true) == true)
    }

    // -----------------------------------------------------------------
    // Provider 측 예외 안전망
    // -----------------------------------------------------------------

    @Test
    fun `F-003 — Provider stream 이 RuntimeException 을 throw 하면 Error(ServerError) 로 wrap`() = runTest {
        val provider = FakeProvider(
            ProviderId.CLAUDE,
            capabilities = claudeCapabilities,
            streamBehavior = {
                flow {
                    throw RuntimeException("unexpected-stream")
                }
            },
        )
        val client = newClient(provider)

        val events = client.askStream(AiRequest(prompt = "hi")).toList()

        // Delta 0 + Error 1 (안전망)
        assertEquals(1, events.size)
        val err = events.single() as AiStreamEvent.Error
        assertTrue(err.cause is AiException.ServerError)
        val se = err.cause as AiException.ServerError
        assertEquals(-1, se.code)
        assertTrue(se.message?.contains("unexpected", ignoreCase = true) == true)
    }

    @Test
    fun `F-003 — Provider stream 이 Network 를 throw 하면 Error(Network) 그대로 전달`() = runTest {
        val cause = java.io.IOException("offline")
        val provider = FakeProvider(
            ProviderId.CLAUDE,
            capabilities = claudeCapabilities,
            streamBehavior = {
                flow {
                    throw AiException.Network(cause)
                }
            },
        )
        val client = newClient(provider)

        val events = client.askStream(AiRequest(prompt = "hi")).toList()

        assertEquals(1, events.size)
        val err = events.single() as AiStreamEvent.Error
        assertTrue(err.cause is AiException.Network)
        assertSame(cause, err.cause.cause)
    }

    @Test
    fun `F-003 — Provider stream 이 Delta 후 Error 를 emit 하면 그대로 collect 에 도달 (E-301 시퀀스)`() = runTest {
        val provider = FakeProvider(
            ProviderId.CLAUDE,
            capabilities = claudeCapabilities,
            streamBehavior = {
                flowOf(
                    AiStreamEvent.Delta("partial"),
                    AiStreamEvent.Error(AiException.Network(java.io.IOException("dropped"))),
                )
            },
        )
        val client = newClient(provider)

        val events = client.askStream(AiRequest(prompt = "hi")).toList()

        assertEquals(2, events.size)
        assertTrue(events[0] is AiStreamEvent.Delta)
        assertTrue(events[1] is AiStreamEvent.Error)
        assertTrue((events[1] as AiStreamEvent.Error).cause is AiException.Network)
    }

    // -----------------------------------------------------------------
    // 회귀 — 이미지 비어있을 때 정상 흐름 (F-001 회귀)
    // -----------------------------------------------------------------

    @Test
    fun `F-003 회귀 — images 빈 리스트 시 검증 통과 후 Provider stream 호출`() = runTest {
        val provider = FakeProvider(
            ProviderId.CLAUDE,
            capabilities = claudeCapabilities.copy(supportsImage = false),
            streamBehavior = { flowOf(AiStreamEvent.Done(dummyResponse())) },
        )
        val client = newClient(provider)

        val events = client.askStream(AiRequest(prompt = "hi")).toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is AiStreamEvent.Done)
        assertEquals(1, provider.streamCallCount.get())
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

    private fun dummyResponse(): AiResponse = AiResponse(
        text = "ok",
        usage = TokenUsage(0, 0),
        finishReason = FinishReason.END_TURN,
        providerId = ProviderId.CLAUDE,
    )

    /**
     * 본 테스트용 FakeProvider — capabilities + stream 동작을 주입 가능.
     * stream 호출 횟수는 회귀 검증 (검증 실패 시 미호출 보장)용.
     */
    private class FakeProvider(
        override val id: ProviderId,
        override val capabilities: Capabilities,
        private val streamBehavior: (ProviderConfig) -> Flow<AiStreamEvent> = {
            flowOf()
        },
    ) : Provider {

        val streamCallCount: AtomicInteger = AtomicInteger(0)

        override suspend fun complete(
            request: AiRequest,
            config: ProviderConfig,
        ): AiResponse {
            throw IllegalStateException("complete must not be called in askStream tests")
        }

        override fun stream(
            request: AiRequest,
            config: ProviderConfig,
        ): Flow<AiStreamEvent> {
            streamCallCount.incrementAndGet()
            return streamBehavior(config)
        }
    }
}
