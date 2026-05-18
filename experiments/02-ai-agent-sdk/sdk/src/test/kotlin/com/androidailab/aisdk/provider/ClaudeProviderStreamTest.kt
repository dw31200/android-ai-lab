package com.androidailab.aisdk.provider

import com.androidailab.aisdk.internal.network.AnthropicHttpClient
import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.AiRequest
import com.androidailab.aisdk.model.AiStreamEvent
import com.androidailab.aisdk.model.FinishReason
import com.androidailab.aisdk.provider.claude.ClaudeProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * F-003 [ClaudeProvider.stream] 통합 단위 테스트.
 *
 * 사양 참조:
 * - features.md F-003 정상 흐름 / 예외 흐름 (E-301 / E-303)
 * - provider-spec.md P-CLAUDE Anthropic Messages SSE
 * - data-model.md M-006 [AiStreamEvent] 방출 순서
 *
 * [MockWebServer]로 SSE chunked 응답을 시뮬레이트하여, [ClaudeProvider.stream]이
 * 1. 적절한 헤더(x-api-key, anthropic-version, accept=text/event-stream, stream=true 본문)를 보내는지
 * 2. SSE 응답을 [com.androidailab.aisdk.internal.network.AnthropicSseParser]로 변환하여 Flow로 흘리는지
 * 3. HTTP 401/429/5xx → AiException 매핑이 Error 이벤트로 들어가는지
 * 검증한다. 외부 네트워크 호출 0회 (MockWebServer 만 사용).
 *
 * 본 테스트는 [ClaudeProvider]의 internal `httpClientFactory` 생성자를 사용하여
 * MockWebServer로 향하는 [AnthropicHttpClient]를 주입한다.
 */
class ClaudeProviderStreamTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Throwable) {
            // best-effort
        }
    }

    @Test
    fun `F-003 정상 — Anthropic SSE chunked 응답을 Delta 들 + Done 으로 변환`() = runTest {
        val sseBody = listOf(
            "event: message_start",
            """data: {"type":"message_start","message":{"id":"msg_01","usage":{"input_tokens":3,"output_tokens":0}}}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" there"}}""",
            "",
            "event: message_delta",
            """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}""",
            "",
            "event: message_stop",
            """data: {"type":"message_stop"}""",
            "",
        ).joinToString(separator = "\n") + "\n"

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody),
        )

        val provider = newProvider()
        val events = provider.stream(
            AiRequest(prompt = "hi"),
            cfg(),
        ).toList()

        // Delta 2 + Done 1
        assertEquals(3, events.size)
        assertEquals("Hello", (events[0] as AiStreamEvent.Delta).text)
        assertEquals(" there", (events[1] as AiStreamEvent.Delta).text)
        val done = events[2] as AiStreamEvent.Done
        assertEquals("Hello there", done.response.text)
        assertEquals(FinishReason.END_TURN, done.response.finishReason)
        // M-002.providerId 는 ClaudeProvider 의 id (CLAUDE)
        assertEquals(provider.id, done.response.providerId)

        // 전송된 요청 헤더/본문 검증 (P-CLAUDE)
        val recorded: RecordedRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("test-api-key", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        // 본 SDK는 SSE 협상용 Accept 헤더를 명시 — provider-spec.md P-CLAUDE
        val accept = recorded.getHeader("Accept")
        assertNotNull(accept)
        assertTrue(
            "Accept: text/event-stream 포함, actual=$accept",
            accept!!.contains("text/event-stream"),
        )
        // 본문에 stream:true 포함
        val bodyStr = recorded.body.readUtf8()
        assertTrue(
            "본문에 \"stream\":true 포함, actual=$bodyStr",
            bodyStr.contains("\"stream\":true"),
        )
    }

    @Test
    fun `F-003 — 401 응답이면 Authentication 이 Error 로 emit 되고 종료`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"x"}}"""),
        )

        val provider = newProvider()
        val events = provider.stream(AiRequest(prompt = "hi"), cfg()).toList()

        assertEquals(1, events.size)
        val err = events.single() as AiStreamEvent.Error
        assertTrue("expected Authentication, got ${err.cause::class.simpleName}", err.cause is AiException.Authentication)
    }

    @Test
    fun `F-003 — 429 응답이면 RateLimit 이 Error 로 emit 되고 종료`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "10")
                .setBody("""{"type":"error","error":{"type":"rate_limit_error"}}"""),
        )

        val provider = newProvider()
        val events = provider.stream(AiRequest(prompt = "hi"), cfg()).toList()

        assertEquals(1, events.size)
        val err = events.single() as AiStreamEvent.Error
        assertTrue(err.cause is AiException.RateLimit)
        // ErrorMapper.fromHttpStatus 는 Retry-After 를 파싱
        assertEquals(10.seconds, (err.cause as AiException.RateLimit).retryAfter)
    }

    @Test
    fun `F-003 — 5xx 응답이면 ServerError 가 Error 로 emit 되고 종료`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("""{"type":"error","error":{"type":"overloaded_error"}}"""),
        )

        val provider = newProvider()
        val events = provider.stream(AiRequest(prompt = "hi"), cfg()).toList()

        assertEquals(1, events.size)
        val err = events.single() as AiStreamEvent.Error
        assertTrue("expected ServerError, got ${err.cause::class.simpleName}", err.cause is AiException.ServerError)
        assertEquals(503, (err.cause as AiException.ServerError).code)
    }

    @Test
    fun `F-003 E-301 — SSE 도중 error 이벤트가 오면 그대로 Error 로 emit 후 종료`() = runTest {
        val sseBody = listOf(
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"par"}}""",
            "",
            "event: error",
            """data: {"type":"error","error":{"type":"overloaded_error","message":"server overloaded"}}""",
            "",
        ).joinToString(separator = "\n") + "\n"

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody),
        )

        val provider = newProvider()
        val events = provider.stream(AiRequest(prompt = "hi"), cfg()).toList()

        assertEquals(2, events.size)
        assertTrue(events[0] is AiStreamEvent.Delta)
        val err = events[1] as AiStreamEvent.Error
        assertTrue(err.cause is AiException.ServerError)
        assertEquals(-1, (err.cause as AiException.ServerError).code)
    }

    // -----------------------------------------------------------------
    // 헬퍼
    // -----------------------------------------------------------------

    private fun newProvider(): ClaudeProvider {
        // MockWebServer 로 향하는 OkHttpClient — 짧은 타임아웃으로 테스트 안정성 확보
        val ok = OkHttpClient.Builder()
            .connectTimeout(2.seconds.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .readTimeout(2.seconds.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .writeTimeout(2.seconds.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .callTimeout(5.seconds.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .build()
        val anth = AnthropicHttpClient(ok)
        // ClaudeProvider 의 MESSAGES_URL 은 absolute https — 테스트에서는 ProviderConfig 의 URL 을 직접
        // 변경할 수 없으므로 reflection 대신 MockWebServer 로 향하는 OkHttp 인터셉터를 사용해야 한다.
        // 본 SDK는 그런 추상화를 두지 않았으므로, 본 테스트는 ClaudeProvider companion 의 MESSAGES_URL 을
        // 그대로 사용하는 대신 동일한 클래스를 새 URL 로 호출하는 인터셉터(rewrite)로 우회한다.
        val rewriter = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val rewritten = original.newBuilder()
                    .url(server.url("/v1/messages"))
                    .build()
                chain.proceed(rewritten)
            }
            .connectTimeout(2.seconds.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .readTimeout(2.seconds.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .writeTimeout(2.seconds.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .callTimeout(5.seconds.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .build()
        return ClaudeProvider(httpClientFactory = { _ -> AnthropicHttpClient(rewriter) })
    }

    private fun cfg(): ProviderConfig = ProviderConfig(
        apiKey = "test-api-key",
        modelId = "claude-opus-4-7",
        timeout = 5.seconds,
    )
}
