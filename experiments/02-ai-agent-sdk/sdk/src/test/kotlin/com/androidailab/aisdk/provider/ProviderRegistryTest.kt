package com.androidailab.aisdk.provider

import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.AiRequest
import com.androidailab.aisdk.model.AiResponse
import com.androidailab.aisdk.model.AiStreamEvent
import com.androidailab.aisdk.model.FinishReason
import com.androidailab.aisdk.model.ProviderId
import com.androidailab.aisdk.model.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * F-005 ProviderRegistry (P-002) 단위 테스트.
 *
 * 사양 참조:
 * - provider-spec.md P-002 (`get(id)`, `list()`, `capabilities(id)`)
 * - features.md F-005 E-501 (등록되지 않은 Provider → AiException.Configuration)
 */
class ProviderRegistryTest {

    @Test
    fun `P-002 정상 흐름 — 등록된 Provider 를 id 로 조회`() {
        val claude = FakeProvider(ProviderId.CLAUDE)
        val registry = ProviderRegistry(setOf(claude))

        val resolved = registry.get(ProviderId.CLAUDE)

        assertSame(claude, resolved)
        assertEquals(ProviderId.CLAUDE, resolved.id)
    }

    @Test
    fun `P-002 정상 흐름 — list 는 등록된 Provider 들을 반환`() {
        val claude = FakeProvider(ProviderId.CLAUDE)
        val registry = ProviderRegistry(setOf(claude))

        val list = registry.list()

        assertEquals(1, list.size)
        assertSame(claude, list[0])
    }

    @Test
    fun `P-002 정상 흐름 — capabilities 헬퍼는 Provider 의 capabilities 와 동일`() {
        val capabilities = Capabilities(
            supportsImage = true,
            supportsVideo = false,
            supportsStream = true,
            supportsSession = true,
            maxImageSizeBytes = 5L * 1024 * 1024,
            maxImagesPerRequest = 10,
            supportedImageMimeTypes = setOf("image/jpeg"),
        )
        val provider = FakeProvider(ProviderId.CLAUDE, capabilities)
        val registry = ProviderRegistry(setOf(provider))

        assertEquals(capabilities, registry.capabilities(ProviderId.CLAUDE))
    }

    @Test
    fun `P-002 정상 흐름 — contains 는 등록 여부를 boolean 으로 반환`() {
        val registry = ProviderRegistry(setOf(FakeProvider(ProviderId.CLAUDE)))

        assertTrue(registry.contains(ProviderId.CLAUDE))
    }

    // -----------------------------------------------------------------
    // E-501 / R-009 — 등록되지 않은 Provider
    // -----------------------------------------------------------------

    @Test
    fun `F-005 E-501 — 등록되지 않은 Provider get 시 Configuration throw`() {
        // v0.1 enum에 CLAUDE 만 존재하므로, registry 자체가 비어있는 상태로 "등록 안됨" 시나리오 재현
        val registry = ProviderRegistry(emptySet())

        try {
            registry.get(ProviderId.CLAUDE)
            fail("expected AiException.Configuration but no exception was thrown")
        } catch (e: AiException.Configuration) {
            assertTrue(
                "message should mention 'unknown provider', actual=${e.message}",
                e.message?.contains("unknown provider", ignoreCase = true) == true,
            )
        }
    }

    @Test
    fun `F-005 E-501 — capabilities 호출도 등록 안된 id 면 Configuration throw`() {
        val registry = ProviderRegistry(emptySet())

        try {
            registry.capabilities(ProviderId.CLAUDE)
            fail("expected AiException.Configuration but no exception was thrown")
        } catch (e: AiException.Configuration) {
            assertTrue(e.message?.contains("unknown provider", ignoreCase = true) == true)
        }
    }

    @Test
    fun `F-005 E-501 — contains 는 등록 안된 id 에 대해 false 를 반환`() {
        val registry = ProviderRegistry(emptySet())

        assertFalse(registry.contains(ProviderId.CLAUDE))
    }

    // -----------------------------------------------------------------
    // P-002 동시성 — Map.put last-write-wins
    // -----------------------------------------------------------------

    @Test
    fun `P-002 동일 id 가 둘 이상이면 마지막이 이긴다`() {
        val first = FakeProvider(ProviderId.CLAUDE)
        val second = FakeProvider(ProviderId.CLAUDE)
        // Set에서 동일 객체 두 개를 만들기 위해 별도 인스턴스를 사용
        val registry = ProviderRegistry(linkedSetOf(first, second))

        // associateBy의 last-write-wins로 second가 등록되어야 함
        assertSame(second, registry.get(ProviderId.CLAUDE))
    }

    /**
     * 테스트용 Fake Provider — F-005 검증에 충분한 최소 구현.
     * complete/stream은 호출되지 않으므로 dummy 반환만 둔다.
     */
    private class FakeProvider(
        override val id: ProviderId,
        override val capabilities: Capabilities = DEFAULT_CAPS,
    ) : Provider {
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

        companion object {
            val DEFAULT_CAPS = Capabilities(
                supportsImage = false,
                supportsVideo = false,
                supportsStream = false,
                supportsSession = false,
                maxImageSizeBytes = 0,
                maxImagesPerRequest = 0,
                supportedImageMimeTypes = emptySet(),
            )
        }
    }
}
