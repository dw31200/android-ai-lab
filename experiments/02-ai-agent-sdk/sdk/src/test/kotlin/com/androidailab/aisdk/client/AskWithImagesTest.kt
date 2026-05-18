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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * F-002 (멀티모달 이미지 질의) AiAgentClient.ask 검증 흐름 단위 테스트.
 *
 * 사양 참조:
 * - features.md F-002 정상 흐름 / 예외 흐름 (E-201~E-205)
 * - data-model.md M-001 ("최대 10장, 합계 20MB"), M-003 (ImageInput sealed)
 * - provider-spec.md P-CLAUDE Capabilities (5MB / 10장 / image/jpeg,png,webp,gif)
 * - error-handling.md ERR-005 (InvalidInput) / ERR-004 (Configuration)
 *
 * 검증:
 * 1. 정상 흐름 — 이미지 첨부 시 검증 통과 후 Provider.complete 호출 (Result.success)
 * 2. E-201 — 단일 이미지가 maxImageSizeBytes 초과 시 InvalidInput
 * 3. E-202 — images 합계가 20MB 초과 시 InvalidInput
 * 4. E-202 — Capabilities.maxImagesPerRequest 초과 시 InvalidInput (M-001 init 통과 후)
 * 5. E-204 — 지원하지 않는 mimeType 시 InvalidInput
 * 6. E-205 — Provider 가 이미지 미지원이면 Configuration
 * 7. E-203 — ImageInput.Uri 도달 시 InvalidInput("uri unreadable")
 * 8. 검증 실패는 Provider.complete 를 호출하지 않음 (회귀 방지)
 */
class AskWithImagesTest {

    private lateinit var context: Context
    private lateinit var appContext: Context

    /** P-CLAUDE Capabilities 샘플 (provider-spec.md P-CLAUDE 상세). */
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
    // 정상 흐름
    // -----------------------------------------------------------------

    @Test
    fun `F-002 정상 흐름 — 이미지 1장 첨부 시 검증 통과 후 Provider 호출 (Result_success)`() = runTest {
        val expected = AiResponse(
            text = "got it",
            usage = TokenUsage(0, 0),
            finishReason = FinishReason.END_TURN,
            providerId = ProviderId.CLAUDE,
        )
        val provider = FakeProvider(
            ProviderId.CLAUDE,
            capabilities = claudeCapabilities,
            completeBehavior = { _, _ -> expected },
        )
        val client = newClient(provider)
        // 1KB PNG-like ByteArray (검증은 size/mime만 본다 — 매직 넘버 검증은 Mapper.toAnthropicRequest)
        val imgBytes = ByteArray(1024) { 0 }
        val req = AiRequest(
            prompt = "what is this?",
            images = listOf(ImageInput.Bytes(imgBytes, "image/png")),
        )

        val result = client.ask(req)

        assertTrue("expected success but was $result", result.isSuccess)
        assertEquals(1, provider.completeCallCount.get())
    }

    // -----------------------------------------------------------------
    // E-201 — 단일 이미지 5MB 초과
    // -----------------------------------------------------------------

    @Test
    fun `F-002 E-201 — 단일 이미지가 maxImageSizeBytes 초과 시 InvalidInput`() = runTest {
        val provider = FakeProvider(ProviderId.CLAUDE, capabilities = claudeCapabilities)
        val client = newClient(provider)
        // 5MB + 1byte
        val tooBig = ByteArray((5L * 1024L * 1024L).toInt() + 1) { 0 }
        val req = AiRequest(
            prompt = "x",
            images = listOf(ImageInput.Bytes(tooBig, "image/png")),
        )

        val result = client.ask(req)

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("expected InvalidInput, got ${ex?.javaClass?.simpleName}", ex is AiException.InvalidInput)
        assertTrue(
            "메시지에 'too large' 포함",
            ex!!.message?.contains("too large", ignoreCase = true) == true,
        )
        // 검증 실패 시 Provider.complete 호출 안 됨 (회귀 방지)
        assertEquals(0, provider.completeCallCount.get())
    }

    // -----------------------------------------------------------------
    // E-202 — 합계 한계 / 개수 한계
    // -----------------------------------------------------------------

    @Test
    fun `F-002 E-202 — images 합계가 20MB 초과 시 InvalidInput`() = runTest {
        val provider = FakeProvider(ProviderId.CLAUDE, capabilities = claudeCapabilities)
        val client = newClient(provider)
        // 5MB 이미지 5장 = 25MB → 합계 한계(20MB) 초과 (개수는 10 이하)
        val each = ByteArray((5L * 1024L * 1024L).toInt()) { 0 }
        val req = AiRequest(
            prompt = "x",
            images = List(5) { ImageInput.Bytes(each, "image/png") },
        )

        val result = client.ask(req)

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AiException.InvalidInput)
        assertTrue(
            "메시지에 'total too large' 포함",
            ex!!.message?.contains("total too large", ignoreCase = true) == true,
        )
        assertEquals(0, provider.completeCallCount.get())
    }

    @Test
    fun `F-002 E-202 — Capabilities maxImagesPerRequest 초과 시 InvalidInput (Provider 한계 강제)`() = runTest {
        // M-001 init은 size <= 10을 강제. Provider가 5장 한계라면 본 검증이 차단해야 한다.
        val tightCaps = claudeCapabilities.copy(maxImagesPerRequest = 5)
        val provider = FakeProvider(ProviderId.CLAUDE, capabilities = tightCaps)
        val client = newClient(provider)
        val req = AiRequest(
            prompt = "x",
            images = List(6) { ImageInput.Bytes(byteArrayOf(1, 2, 3), "image/png") },
        )

        val result = client.ask(req)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AiException.InvalidInput)
        assertEquals(0, provider.completeCallCount.get())
    }

    // -----------------------------------------------------------------
    // E-204 — 지원하지 않는 mimeType
    // -----------------------------------------------------------------

    @Test
    fun `F-002 E-204 — 지원하지 않는 mimeType 시 InvalidInput`() = runTest {
        val provider = FakeProvider(ProviderId.CLAUDE, capabilities = claudeCapabilities)
        val client = newClient(provider)
        val req = AiRequest(
            prompt = "x",
            images = listOf(ImageInput.Bytes(byteArrayOf(1, 2, 3), "image/bmp")),
        )

        val result = client.ask(req)

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AiException.InvalidInput)
        assertTrue(
            "메시지에 'unsupported mime type' 포함",
            ex!!.message?.contains("unsupported mime type", ignoreCase = true) == true,
        )
        assertEquals(0, provider.completeCallCount.get())
    }

    // -----------------------------------------------------------------
    // E-205 — Provider 이미지 미지원
    // -----------------------------------------------------------------

    @Test
    fun `F-002 E-205 — Provider 가 supportsImage=false 면 Configuration("provider does not support images")`() = runTest {
        val noImageCaps = claudeCapabilities.copy(supportsImage = false)
        val provider = FakeProvider(ProviderId.CLAUDE, capabilities = noImageCaps)
        val client = newClient(provider)
        val req = AiRequest(
            prompt = "x",
            images = listOf(ImageInput.Bytes(byteArrayOf(1, 2, 3), "image/png")),
        )

        val result = client.ask(req)

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("expected Configuration, got ${ex?.javaClass?.simpleName}", ex is AiException.Configuration)
        assertTrue(
            "메시지 'provider does not support images' 정확 일치",
            ex!!.message?.contains("provider does not support images") == true,
        )
        assertEquals(0, provider.completeCallCount.get())
    }

    // -----------------------------------------------------------------
    // E-203 — ImageInput.Uri 도달 (D-004: SDK 자동 resolve 안 함)
    // -----------------------------------------------------------------

    @Test
    fun `F-002 E-203 — ImageInput_Uri 도달 시 InvalidInput("uri unreadable")`() = runTest {
        val provider = FakeProvider(ProviderId.CLAUDE, capabilities = claudeCapabilities)
        val client = newClient(provider)
        val mockUri = mockk<android.net.Uri>(relaxed = true) {
            every { toString() } returns "content://test/image.jpg"
        }
        val req = AiRequest(
            prompt = "x",
            images = listOf(ImageInput.Uri(mockUri)),
        )

        val result = client.ask(req)

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AiException.InvalidInput)
        assertTrue(
            "메시지에 'uri unreadable' 포함",
            ex!!.message?.contains("uri unreadable", ignoreCase = true) == true,
        )
        assertEquals(0, provider.completeCallCount.get())
    }

    // -----------------------------------------------------------------
    // 회귀 — 이미지 없으면 검증 통과 (F-001 텍스트 단발 흐름)
    // -----------------------------------------------------------------

    @Test
    fun `F-002 회귀 — images 빈 리스트 시 검증은 즉시 통과 (F-001 텍스트 흐름 영향 없음)`() = runTest {
        val expected = AiResponse(
            text = "ok",
            usage = TokenUsage(0, 0),
            finishReason = FinishReason.END_TURN,
            providerId = ProviderId.CLAUDE,
        )
        val provider = FakeProvider(
            ProviderId.CLAUDE,
            capabilities = claudeCapabilities.copy(supportsImage = false), // 이미지 미지원 Provider 라도
            completeBehavior = { _, _ -> expected },
        )
        val client = newClient(provider)

        val result = client.ask(AiRequest(prompt = "hi")) // images 비어있음

        // images가 비어있으면 supportsImage=false라도 통과 (F-001 회귀 방지)
        assertTrue(result.isSuccess)
        assertEquals(1, provider.completeCallCount.get())
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

    /** 본 테스트용 FakeProvider (capabilities 주입 가능). */
    private class FakeProvider(
        override val id: ProviderId,
        override val capabilities: Capabilities,
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
