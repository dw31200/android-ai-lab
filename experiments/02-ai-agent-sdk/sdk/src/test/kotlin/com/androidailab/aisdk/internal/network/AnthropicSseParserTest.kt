package com.androidailab.aisdk.internal.network

import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.AiStreamEvent
import com.androidailab.aisdk.model.FinishReason
import com.androidailab.aisdk.model.ProviderId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-003 [AnthropicSseParser] 단위 테스트.
 *
 * 사양 참조:
 * - features.md F-003 정상 흐름 / 예외 흐름 (E-301)
 * - data-model.md M-006 [AiStreamEvent] 방출 순서 (Delta 0+ → Done | Error 1)
 * - provider-spec.md P-CLAUDE Anthropic Messages SSE 형식
 * - features.md F-001 R-005 — 빈 응답 검증 (END_TURN/STOP_SEQUENCE OK, MAX_TOKENS/OTHER 거부)
 *
 * 본 테스트는 SSE 라인 → [AiStreamEvent] 변환만 검증한다 (네트워크 호출 0회).
 * SSE 본문은 [Buffer]로 in-memory에서 만들어 [parse]에 전달.
 */
class AnthropicSseParserTest {

    /** Anthropic Messages SSE 정상 시퀀스 1개 — 텍스트 "Hello world" 분할 stream. */
    @Test
    fun `F-003 정상 — Delta 여러개 후 message_stop 이면 Done 으로 종결`() = runTest {
        val sse = listOf(
            sseEvent("message_start", """{"type":"message_start","message":{"id":"msg_01","usage":{"input_tokens":10,"output_tokens":0}}}"""),
            sseEvent("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""),
            sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}"""),
            sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" world"}}"""),
            sseEvent("content_block_stop", """{"type":"content_block_stop","index":0}"""),
            sseEvent("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}}"""),
            sseEvent("message_stop", """{"type":"message_stop"}"""),
        ).joinToString(separator = "")

        val events = parseAll(sse)

        // M-006: Delta 2개 → Done 1개 (Error 0)
        assertEquals(3, events.size)
        assertTrue(events[0] is AiStreamEvent.Delta)
        assertTrue(events[1] is AiStreamEvent.Delta)
        assertEquals("Hello", (events[0] as AiStreamEvent.Delta).text)
        assertEquals(" world", (events[1] as AiStreamEvent.Delta).text)
        assertTrue(events[2] is AiStreamEvent.Done)
        val done = events[2] as AiStreamEvent.Done
        assertEquals("Hello world", done.response.text)
        assertEquals(FinishReason.END_TURN, done.response.finishReason)
        assertEquals(ProviderId.CLAUDE, done.response.providerId)
        assertEquals(10, done.response.usage.inputTokens)
        assertEquals(5, done.response.usage.outputTokens)
    }

    /** F-003 finishReason 매핑 - max_tokens. */
    @Test
    fun `F-003 정상 — message_delta stop_reason max_tokens 는 FinishReason_MAX_TOKENS 매핑`() = runTest {
        val sse = listOf(
            sseEvent("message_start", """{"type":"message_start","message":{"id":"msg_01","usage":{"input_tokens":1,"output_tokens":0}}}"""),
            sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"abc"}}"""),
            sseEvent("message_delta", """{"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":2}}"""),
            sseEvent("message_stop", """{"type":"message_stop"}"""),
        ).joinToString(separator = "")

        val events = parseAll(sse)

        val done = events.last() as AiStreamEvent.Done
        assertEquals(FinishReason.MAX_TOKENS, done.response.finishReason)
        assertEquals("abc", done.response.text)
    }

    /** F-003 finishReason 매핑 - stop_sequence / 알 수 없는 사유는 OTHER. */
    @Test
    fun `F-003 정상 — stop_reason stop_sequence 는 STOP_SEQUENCE, 미지정은 OTHER`() = runTest {
        val sseStopSeq = listOf(
            sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"x"}}"""),
            sseEvent("message_delta", """{"type":"message_delta","delta":{"stop_reason":"stop_sequence"}}"""),
            sseEvent("message_stop", """{"type":"message_stop"}"""),
        ).joinToString(separator = "")
        val doneStop = parseAll(sseStopSeq).last() as AiStreamEvent.Done
        assertEquals(FinishReason.STOP_SEQUENCE, doneStop.response.finishReason)

        val sseUnknown = listOf(
            sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"x"}}"""),
            sseEvent("message_delta", """{"type":"message_delta","delta":{"stop_reason":"refusal"}}"""),
            sseEvent("message_stop", """{"type":"message_stop"}"""),
        ).joinToString(separator = "")
        val doneOther = parseAll(sseUnknown).last() as AiStreamEvent.Done
        assertEquals(FinishReason.OTHER, doneOther.response.finishReason)
    }

    // -----------------------------------------------------------------
    // R-005 / F-001 빈 응답 검증 — stream에도 동일 적용
    // -----------------------------------------------------------------

    @Test
    fun `F-003 R-005 — 빈 텍스트 + END_TURN 이면 그대로 Done (성공)`() = runTest {
        val sse = listOf(
            sseEvent("message_start", """{"type":"message_start","message":{"usage":{"input_tokens":1,"output_tokens":0}}}"""),
            sseEvent("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":0}}"""),
            sseEvent("message_stop", """{"type":"message_stop"}"""),
        ).joinToString(separator = "")

        val events = parseAll(sse)

        // 본문 텍스트 없음 + END_TURN 은 정상 종결 (R-005, F-001 정상 흐름 4단계 동형)
        assertEquals(1, events.size)
        val done = events.single() as AiStreamEvent.Done
        assertEquals("", done.response.text)
        assertEquals(FinishReason.END_TURN, done.response.finishReason)
    }

    @Test
    fun `F-003 R-005 — 빈 텍스트 + MAX_TOKENS 이면 Error(ServerError(-1, empty response))`() = runTest {
        val sse = listOf(
            sseEvent("message_start", """{"type":"message_start","message":{"usage":{"input_tokens":1,"output_tokens":0}}}"""),
            sseEvent("message_delta", """{"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":0}}"""),
            sseEvent("message_stop", """{"type":"message_stop"}"""),
        ).joinToString(separator = "")

        val events = parseAll(sse)

        assertEquals(1, events.size)
        val err = events.single() as AiStreamEvent.Error
        assertTrue(err.cause is AiException.ServerError)
        val se = err.cause as AiException.ServerError
        assertEquals(-1, se.code)
        assertTrue(
            "메시지에 'empty response' 포함",
            se.message?.contains("empty response", ignoreCase = true) == true,
        )
    }

    // -----------------------------------------------------------------
    // SSE error 이벤트 (E-301 / 인증 / 레이트 리밋)
    // -----------------------------------------------------------------

    @Test
    fun `F-003 E-301 — error 이벤트 overloaded_error 면 ServerError(-1) 방출 후 종료`() = runTest {
        val sse = listOf(
            sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}"""),
            sseEvent("error", """{"type":"error","error":{"type":"overloaded_error","message":"server overloaded"}}"""),
        ).joinToString(separator = "")

        val events = parseAll(sse)

        // Delta 1 + Error 1 으로 종결
        assertEquals(2, events.size)
        assertTrue(events[0] is AiStreamEvent.Delta)
        assertTrue(events[1] is AiStreamEvent.Error)
        val err = events[1] as AiStreamEvent.Error
        assertTrue(err.cause is AiException.ServerError)
        val se = err.cause as AiException.ServerError
        assertEquals(-1, se.code)
        assertTrue(se.message?.contains("server overloaded") == true)
    }

    @Test
    fun `F-003 — error authentication_error 면 Authentication 매핑`() = runTest {
        val sse = sseEvent("error", """{"type":"error","error":{"type":"authentication_error","message":"x"}}""")

        val events = parseAll(sse)

        assertEquals(1, events.size)
        val err = events.single() as AiStreamEvent.Error
        assertTrue(err.cause is AiException.Authentication)
    }

    @Test
    fun `F-003 — error rate_limit_error 면 RateLimit 매핑`() = runTest {
        val sse = sseEvent("error", """{"type":"error","error":{"type":"rate_limit_error","message":"x"}}""")

        val events = parseAll(sse)

        val err = events.single() as AiStreamEvent.Error
        assertTrue(err.cause is AiException.RateLimit)
        assertEquals(null, (err.cause as AiException.RateLimit).retryAfter)
    }

    @Test
    fun `F-003 — error invalid_request_error 면 InvalidInput 매핑`() = runTest {
        val sse = sseEvent("error", """{"type":"error","error":{"type":"invalid_request_error","message":"prompt too long"}}""")

        val events = parseAll(sse)

        val err = events.single() as AiStreamEvent.Error
        assertTrue(err.cause is AiException.InvalidInput)
        assertTrue((err.cause as AiException.InvalidInput).message?.contains("prompt too long") == true)
    }

    // -----------------------------------------------------------------
    // 잘못된 형식 / 부분 데이터 / EOF
    // -----------------------------------------------------------------

    @Test
    fun `F-003 E-301 — message_stop 없이 EOF 도달 시 Error(Network) 방출`() = runTest {
        val sse = listOf(
            sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}"""),
            // 의도적으로 message_stop / error 없이 EOF
        ).joinToString(separator = "")

        val events = parseAll(sse)

        assertEquals(2, events.size)
        assertTrue(events[0] is AiStreamEvent.Delta)
        assertTrue(events[1] is AiStreamEvent.Error)
        assertTrue((events[1] as AiStreamEvent.Error).cause is AiException.Network)
    }

    @Test
    fun `F-003 — 잘못된 JSON 라인은 무시되고 다음 이벤트 처리는 정상 진행`() = runTest {
        val malformed = "data: {bad json without quotes\n\n"
        val sse = malformed + listOf(
            sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}"""),
            sseEvent("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""),
            sseEvent("message_stop", """{"type":"message_stop"}"""),
        ).joinToString(separator = "")

        val events = parseAll(sse)

        assertEquals(2, events.size)
        assertEquals("ok", (events[0] as AiStreamEvent.Delta).text)
        assertTrue(events[1] is AiStreamEvent.Done)
    }

    @Test
    fun `F-003 — 알 수 없는 event type 은 무시 (forward-compat)`() = runTest {
        val sse = listOf(
            sseEvent("future_unknown_event", """{"type":"future_unknown_event","data":"???"}"""),
            sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"x"}}"""),
            sseEvent("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""),
            sseEvent("message_stop", """{"type":"message_stop"}"""),
        ).joinToString(separator = "")

        val events = parseAll(sse)

        assertEquals(2, events.size)
        assertTrue(events[0] is AiStreamEvent.Delta)
        assertTrue(events[1] is AiStreamEvent.Done)
    }

    @Test
    fun `F-003 — ping 이벤트는 무시 (keep-alive)`() = runTest {
        val sse = listOf(
            sseEvent("ping", """{"type":"ping"}"""),
            sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}"""),
            sseEvent("ping", """{"type":"ping"}"""),
            sseEvent("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""),
            sseEvent("message_stop", """{"type":"message_stop"}"""),
        ).joinToString(separator = "")

        val events = parseAll(sse)

        assertEquals(2, events.size)
        assertTrue(events[0] is AiStreamEvent.Delta)
        assertTrue(events[1] is AiStreamEvent.Done)
    }

    @Test
    fun `F-003 — non-text_delta (input_json_delta 등) 는 Delta 로 방출되지 않음`() = runTest {
        val sse = listOf(
            sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{x"}}"""),
            sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"final"}}"""),
            sseEvent("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""),
            sseEvent("message_stop", """{"type":"message_stop"}"""),
        ).joinToString(separator = "")

        val events = parseAll(sse)

        // input_json_delta 무시 → Delta 1 + Done 1
        assertEquals(2, events.size)
        assertEquals("final", (events[0] as AiStreamEvent.Delta).text)
    }

    // -----------------------------------------------------------------
    // M-006 방출 순서: Done 이후 Delta 방출 금지
    // -----------------------------------------------------------------

    @Test
    fun `F-003 M-006 — message_stop 이후 도착한 Delta 는 무시 (Done 1회로 종결)`() = runTest {
        val sse = listOf(
            sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"a"}}"""),
            sseEvent("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""),
            sseEvent("message_stop", """{"type":"message_stop"}"""),
            // 사양 위반 — Done 이후 추가 Delta가 와도 무시되어야 함
            sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"after-done"}}"""),
        ).joinToString(separator = "")

        val events = parseAll(sse)

        // Delta 1 + Done 1 — 추가 Delta 없음 (M-006 종결 보장)
        assertEquals(2, events.size)
        assertTrue(events[0] is AiStreamEvent.Delta)
        assertTrue(events[1] is AiStreamEvent.Done)
        assertEquals("a", (events[1] as AiStreamEvent.Done).response.text)
    }

    // -----------------------------------------------------------------
    // 헬퍼
    // -----------------------------------------------------------------

    private suspend fun parseAll(sseRaw: String): List<AiStreamEvent> {
        val source: BufferedSource = Buffer().apply { writeUtf8(sseRaw) }
        return AnthropicSseParser.parse(source, ProviderId.CLAUDE).toList()
    }

    /** Anthropic SSE 한 이벤트 라인 만들기. event 라인 + data 라인 + 빈 줄. */
    private fun sseEvent(eventType: String, data: String): String {
        return "event: $eventType\ndata: $data\n\n"
    }
}
