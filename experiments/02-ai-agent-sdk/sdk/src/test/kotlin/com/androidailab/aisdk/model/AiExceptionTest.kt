package com.androidailab.aisdk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * M-005 AiException sealed class 골격 검증.
 *
 * 사양 참조:
 * - data-model.md M-005 (Variant 정의)
 * - error-handling.md ERR-001~ERR-007 매핑
 *
 * 본 테스트는 sealed 계층의 7개 variant가 모두 올바르게 정의되어 있고,
 * 각 variant가 사양에 명시된 추가 필드(retryAfter / code / cause 등)를
 * 노출하는지 확인한다.
 */
class AiExceptionTest {

    // ERR-001
    @Test
    fun `Network 는 cause 를 보관한다`() {
        val cause = RuntimeException("boom")
        val ex = AiException.Network(cause)

        assertSame(cause, ex.cause)
        assertTrue(ex is AiException)
    }

    // ERR-002
    @Test
    fun `RateLimit 는 retryAfter 를 보관한다 (값 있음)`() {
        val ex = AiException.RateLimit(retryAfter = 30.seconds)
        assertEquals(30.seconds, ex.retryAfter)
    }

    @Test
    fun `RateLimit 의 retryAfter 는 null 가능`() {
        val ex = AiException.RateLimit(retryAfter = null)
        assertNull(ex.retryAfter)
    }

    // ERR-003
    @Test
    fun `Authentication 는 메시지 없이 생성 가능`() {
        val ex = AiException.Authentication()
        assertNull(ex.message)
        assertNull(ex.cause)
    }

    // ERR-004
    @Test
    fun `Configuration 은 message 를 보관한다`() {
        val ex = AiException.Configuration("api key is required")
        assertEquals("api key is required", ex.message)
    }

    // ERR-005
    @Test
    fun `InvalidInput 은 message 를 보관한다`() {
        val ex = AiException.InvalidInput("prompt must not be blank")
        assertEquals("prompt must not be blank", ex.message)
    }

    // ERR-006
    @Test
    fun `ServerError 는 code 와 message 를 보관한다`() {
        val ex = AiException.ServerError(code = 503, message = "service unavailable")
        assertEquals(503, ex.code)
        assertEquals("service unavailable", ex.message)
    }

    @Test
    fun `ServerError 의 message 는 null 가능`() {
        val ex = AiException.ServerError(code = 500)
        assertEquals(500, ex.code)
        assertNull(ex.message)
    }

    // ERR-007 (라운드 2 신규)
    @Test
    fun `IOError 는 message 와 cause 를 보관한다`() {
        val cause = RuntimeException("disk full")
        val ex = AiException.IOError(message = "save failed: disk full", cause = cause)

        assertEquals("save failed: disk full", ex.message)
        assertSame(cause, ex.cause)
    }

    @Test
    fun `IOError 의 cause 는 null 가능`() {
        val ex = AiException.IOError(message = "session schema unsupported: v=2")
        assertNotNull(ex.message)
        assertNull(ex.cause)
    }

    // sealed 계층 검증
    @Test
    fun `AiException 의 모든 variant 는 Exception 하위 타입`() {
        val variants: List<AiException> = listOf(
            AiException.Network(RuntimeException()),
            AiException.RateLimit(null),
            AiException.Authentication(),
            AiException.Configuration("x"),
            AiException.InvalidInput("x"),
            AiException.ServerError(500),
            AiException.IOError("x"),
        )
        variants.forEach { v ->
            assertTrue("${v::class.simpleName} should be Exception", v is Exception)
        }
    }
}
