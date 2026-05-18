package com.androidailab.aisdk.internal.network

import com.androidailab.aisdk.model.AiException
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.seconds

/**
 * ErrorMapper 단위 테스트 (F-001, P-CLAUDE).
 *
 * 사양 참조:
 * - features.md F-001 E-101~E-110
 * - error-handling.md ERR-001~ERR-006
 * - provider-spec.md P-CLAUDE 에러 매핑 표
 *
 * 검증:
 * 1. fromHttpStatus — 401/429/4xx/5xx 매핑
 * 2. fromHttpStatus — 429 Retry-After 헤더 파싱
 * 3. fromHttpStatus — 2xx 는 null 반환
 * 4. fromException — SocketTimeout/IOException → Network
 * 5. fromException — SerializationException → ServerError(-1, ...)
 */
class ErrorMapperTest {

    // -----------------------------------------------------------------
    // fromHttpStatus
    // -----------------------------------------------------------------

    @Test
    fun `E-102 — HTTP 401 은 Authentication 으로 매핑`() {
        val response = httpResponse(code = 401, message = "Unauthorized")

        val mapped = ErrorMapper.fromHttpStatus(response)

        assertTrue("expected Authentication, got ${mapped?.javaClass?.simpleName}",
            mapped is AiException.Authentication)
    }

    @Test
    fun `E-103 — HTTP 429 는 RateLimit 으로 매핑되며 Retry-After 헤더가 초 단위로 파싱됨`() {
        val response = httpResponse(
            code = 429,
            message = "Too Many Requests",
            headers = mapOf("Retry-After" to "30"),
        )

        val mapped = ErrorMapper.fromHttpStatus(response)

        assertTrue(mapped is AiException.RateLimit)
        mapped as AiException.RateLimit
        assertEquals(30.seconds, mapped.retryAfter)
    }

    @Test
    fun `E-103 — HTTP 429 + Retry-After 헤더 없음 이면 retryAfter 가 null`() {
        val response = httpResponse(code = 429, message = "Too Many Requests")

        val mapped = ErrorMapper.fromHttpStatus(response)

        assertTrue(mapped is AiException.RateLimit)
        assertNull((mapped as AiException.RateLimit).retryAfter)
    }

    @Test
    fun `E-103 — HTTP 429 + Retry-After 헤더가 비숫자(HTTP-date 등) 이면 null (v0_1 정책)`() {
        // Anthropic은 HTTP-date 형식도 보낼 수 있으나 v0.1은 초 단위 정수만 지원.
        val response = httpResponse(
            code = 429,
            message = "Too Many Requests",
            headers = mapOf("Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT"),
        )

        val mapped = ErrorMapper.fromHttpStatus(response)

        assertTrue(mapped is AiException.RateLimit)
        assertNull((mapped as AiException.RateLimit).retryAfter)
    }

    @Test
    fun `E-104 — HTTP 500 은 ServerError 로 매핑 (code=500)`() {
        val response = httpResponse(code = 500, message = "Internal Server Error")

        val mapped = ErrorMapper.fromHttpStatus(response)

        assertTrue(mapped is AiException.ServerError)
        assertEquals(500, (mapped as AiException.ServerError).code)
        assertTrue("message should mention 'server error', actual=${mapped.message}",
            mapped.message?.contains("server error", ignoreCase = true) == true)
    }

    @Test
    fun `E-104 — HTTP 503 은 ServerError 로 매핑 (code=503)`() {
        val response = httpResponse(code = 503, message = "Service Unavailable")

        val mapped = ErrorMapper.fromHttpStatus(response)

        assertTrue(mapped is AiException.ServerError)
        assertEquals(503, (mapped as AiException.ServerError).code)
    }

    @Test
    fun `4xx 기타(400) — ServerError 로 매핑 (사양 ERR-006 기본값)`() {
        val response = httpResponse(code = 400, message = "Bad Request")

        val mapped = ErrorMapper.fromHttpStatus(response)

        assertTrue(mapped is AiException.ServerError)
        assertEquals(400, (mapped as AiException.ServerError).code)
        assertTrue("message should mention 'http error', actual=${mapped.message}",
            mapped.message?.contains("http error", ignoreCase = true) == true)
    }

    @Test
    fun `2xx 응답은 null 반환 (정상 처리 진입)`() {
        val response = httpResponse(code = 200, message = "OK")

        val mapped = ErrorMapper.fromHttpStatus(response)

        assertNull(mapped)
    }

    // -----------------------------------------------------------------
    // fromException
    // -----------------------------------------------------------------

    @Test
    fun `E-108 — SocketTimeoutException 은 Network 로 매핑 (cause 보존)`() {
        val cause = SocketTimeoutException("read timed out")

        val mapped = ErrorMapper.fromException(cause)

        assertTrue(mapped is AiException.Network)
        assertNotNull(mapped.cause)
        assertEquals(cause, mapped.cause)
    }

    @Test
    fun `E-101 — 일반 IOException 은 Network 로 매핑 (cause 보존)`() {
        val cause = IOException("connection reset")

        val mapped = ErrorMapper.fromException(cause)

        assertTrue(mapped is AiException.Network)
        assertEquals(cause, mapped.cause)
    }

    @Test
    fun `E-105 — SerializationException 은 ServerError(code=-1) 로 매핑`() {
        val cause = SerializationException("malformed json")

        val mapped = ErrorMapper.fromException(cause)

        assertTrue(mapped is AiException.ServerError)
        mapped as AiException.ServerError
        assertEquals(-1, mapped.code)
        assertTrue(
            "message should mention 'parse', actual=${mapped.message}",
            mapped.message?.contains("parse", ignoreCase = true) == true,
        )
    }

    @Test
    fun `예상치 못한 예외는 ServerError(code=-1) 로 매핑 (안전망)`() {
        val cause = RuntimeException("boom")

        val mapped = ErrorMapper.fromException(cause)

        assertTrue(mapped is AiException.ServerError)
        assertEquals(-1, (mapped as AiException.ServerError).code)
    }

    // -----------------------------------------------------------------
    // 헬퍼
    // -----------------------------------------------------------------

    /**
     * 단위 테스트용 OkHttp Response 빌더. 실제 네트워크 호출 없이 status/headers 검증만 한다.
     */
    private fun httpResponse(
        code: Int,
        message: String,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
    ): Response {
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .build()

        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body(body.toResponseBody("application/json".toMediaType()))

        headers.forEach { (k, v) -> builder.header(k, v) }

        return builder.build()
    }
}
