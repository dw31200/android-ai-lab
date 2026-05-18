package com.androidailab.aisdk.provider

import com.androidailab.aisdk.model.ProviderId
import com.androidailab.aisdk.provider.claude.ClaudeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-CLAUDE 골격 검증.
 *
 * 사양 참조:
 * - provider-spec.md "P-CLAUDE 상세" — Capabilities 표기
 * - F-005 R-010 — Capabilities는 Provider 측 단일 source of truth
 *
 * 본 라운드(F-005)는 인터페이스 골격 + Capabilities 명시까지만 검증한다.
 * complete/stream 본체는 F-001/F-003에서 채워진 후 별도 라운드에 검증.
 */
class ClaudeProviderTest {

    @Test
    fun `P-CLAUDE id 는 ProviderId_CLAUDE`() {
        val provider = ClaudeProvider()
        assertEquals(ProviderId.CLAUDE, provider.id)
    }

    @Test
    fun `P-CLAUDE Capabilities — 사양 표기와 정확히 일치`() {
        val capabilities = ClaudeProvider().capabilities

        // provider-spec.md "P-CLAUDE 상세 — Capabilities 값" 표기와 토큰 단위로 비교
        assertTrue("supportsImage", capabilities.supportsImage)
        assertFalse("supportsVideo", capabilities.supportsVideo)
        assertTrue("supportsStream", capabilities.supportsStream)
        assertTrue("supportsSession", capabilities.supportsSession)
        assertEquals(5L * 1024 * 1024, capabilities.maxImageSizeBytes)
        assertEquals(10, capabilities.maxImagesPerRequest)
        assertEquals(
            setOf("image/jpeg", "image/png", "image/webp", "image/gif"),
            capabilities.supportedImageMimeTypes,
        )
    }

    @Test
    fun `P-CLAUDE Capabilities — companion 상수와 동기화됨`() {
        val capabilities = ClaudeProvider().capabilities

        assertEquals(ClaudeProvider.MAX_IMAGE_SIZE_BYTES, capabilities.maxImageSizeBytes)
        assertEquals(ClaudeProvider.MAX_IMAGES_PER_REQUEST, capabilities.maxImagesPerRequest)
        assertEquals(ClaudeProvider.SUPPORTED_IMAGE_MIME_TYPES, capabilities.supportedImageMimeTypes)
    }
}
