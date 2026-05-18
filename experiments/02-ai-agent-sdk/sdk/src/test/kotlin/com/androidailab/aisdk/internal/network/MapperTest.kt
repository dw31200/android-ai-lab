package com.androidailab.aisdk.internal.network

import com.androidailab.aisdk.model.AiRequest
import com.androidailab.aisdk.model.FinishReason
import com.androidailab.aisdk.model.ProviderId
import com.androidailab.aisdk.provider.ProviderConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Mapper 단위 테스트 (F-001, P-CLAUDE).
 *
 * 사양 참조:
 * - data-model.md M-001 (AiRequest) → AnthropicMessagesRequest
 * - data-model.md M-002 (AiResponse) ← AnthropicMessagesResponse
 * - data-model.md M-009 (TokenUsage) ← AnthropicUsage
 * - features.md F-001 정상 흐름 4단계 (빈 응답은 호출 측 검증, Mapper는 통과)
 *
 * 검증:
 * 1. toAnthropicRequest — AiRequest 필드가 정확히 매핑됨
 * 2. toAiResponse — text 단일 블록 매핑
 * 3. toAiResponse — text 다중 블록은 결합
 * 4. toAiResponse — stop_reason 4종 매핑 (END_TURN/MAX_TOKENS/STOP_SEQUENCE/OTHER)
 * 5. toAiResponse — usage 누락 시 (0, 0)
 * 6. toAiResponse — 비텍스트 블록은 무시됨
 */
class MapperTest {

    // -----------------------------------------------------------------
    // toAnthropicRequest
    // -----------------------------------------------------------------

    @Test
    fun `M-001 — AiRequest 가 AnthropicMessagesRequest 로 정확히 매핑됨`() = runTest {
        val request = AiRequest(
            prompt = "안녕",
            maxTokens = 512,
            temperature = 0.5f,
        )
        val config = ProviderConfig(
            apiKey = "sk-test",
            modelId = "claude-opus-4-7",
            timeout = 30.seconds,
        )

        val mapped = Mapper.toAnthropicRequest(request, config)

        assertEquals("claude-opus-4-7", mapped.model)
        assertEquals(512, mapped.maxTokens)
        assertEquals(0.5f, mapped.temperature)
        assertEquals(1, mapped.messages.size)
        assertEquals("user", mapped.messages[0].role)
        // F-002 (T12): content는 이제 JsonElement (블록 배열). 텍스트 단발은 단일 text 블록.
        val contentArray: JsonArray = mapped.messages[0].content.jsonArray
        assertEquals(1, contentArray.size)
        val firstBlock: JsonObject = contentArray[0].jsonObject
        assertEquals("text", firstBlock["type"]?.jsonPrimitive?.content)
        assertEquals("안녕", firstBlock["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `M-001 — modelId 가 ProviderConfig 에서만 가져옴 (AiRequest 가 아닌 config 가 단일 출처)`() = runTest {
        val request = AiRequest(prompt = "hi")
        val config = ProviderConfig(
            apiKey = "sk-test",
            modelId = "claude-haiku-3-5",
            timeout = 30.seconds,
        )

        val mapped = Mapper.toAnthropicRequest(request, config)

        assertEquals("claude-haiku-3-5", mapped.model)
    }

    // -----------------------------------------------------------------
    // toAiResponse — 정상 케이스
    // -----------------------------------------------------------------

    @Test
    fun `M-002 — 단일 text 블록 응답이 AiResponse 로 매핑됨`() {
        val response = AnthropicMessagesResponse(
            id = "msg_01",
            type = "message",
            role = "assistant",
            model = "claude-opus-4-7",
            content = listOf(AnthropicContentBlock(type = "text", text = "hello")),
            stopReason = "end_turn",
            usage = AnthropicUsage(inputTokens = 10, outputTokens = 5),
        )

        val mapped = Mapper.toAiResponse(response, ProviderId.CLAUDE)

        assertEquals("hello", mapped.text)
        assertEquals(FinishReason.END_TURN, mapped.finishReason)
        assertEquals(10, mapped.usage.inputTokens)
        assertEquals(5, mapped.usage.outputTokens)
        assertEquals(15, mapped.usage.totalTokens)
        assertEquals(ProviderId.CLAUDE, mapped.providerId)
    }

    @Test
    fun `M-002 — text 블록이 여러 개면 순서대로 결합됨`() {
        val response = AnthropicMessagesResponse(
            content = listOf(
                AnthropicContentBlock(type = "text", text = "part1 "),
                AnthropicContentBlock(type = "text", text = "part2"),
            ),
            stopReason = "end_turn",
            usage = AnthropicUsage(0, 0),
        )

        val mapped = Mapper.toAiResponse(response, ProviderId.CLAUDE)

        assertEquals("part1 part2", mapped.text)
    }

    @Test
    fun `M-002 — 비 text 블록(tool_use 등) 은 무시되고 text 만 결합`() {
        val response = AnthropicMessagesResponse(
            content = listOf(
                AnthropicContentBlock(type = "text", text = "hello"),
                AnthropicContentBlock(type = "tool_use", text = null),
                AnthropicContentBlock(type = "text", text = " world"),
            ),
            stopReason = "end_turn",
            usage = AnthropicUsage(0, 0),
        )

        val mapped = Mapper.toAiResponse(response, ProviderId.CLAUDE)

        assertEquals("hello world", mapped.text)
    }

    // -----------------------------------------------------------------
    // toAiResponse — stop_reason 매핑
    // -----------------------------------------------------------------

    @Test
    fun `M-002 — stop_reason 'end_turn' → FinishReason_END_TURN`() {
        val mapped = Mapper.toAiResponse(responseWithStop("end_turn"), ProviderId.CLAUDE)
        assertEquals(FinishReason.END_TURN, mapped.finishReason)
    }

    @Test
    fun `M-002 — stop_reason 'max_tokens' → FinishReason_MAX_TOKENS`() {
        val mapped = Mapper.toAiResponse(responseWithStop("max_tokens"), ProviderId.CLAUDE)
        assertEquals(FinishReason.MAX_TOKENS, mapped.finishReason)
    }

    @Test
    fun `M-002 — stop_reason 'stop_sequence' → FinishReason_STOP_SEQUENCE`() {
        val mapped = Mapper.toAiResponse(responseWithStop("stop_sequence"), ProviderId.CLAUDE)
        assertEquals(FinishReason.STOP_SEQUENCE, mapped.finishReason)
    }

    @Test
    fun `M-002 — stop_reason 'unknown' → FinishReason_OTHER`() {
        val mapped = Mapper.toAiResponse(responseWithStop("unknown_future_reason"), ProviderId.CLAUDE)
        assertEquals(FinishReason.OTHER, mapped.finishReason)
    }

    @Test
    fun `M-002 — stop_reason null → FinishReason_OTHER`() {
        val mapped = Mapper.toAiResponse(responseWithStop(null), ProviderId.CLAUDE)
        assertEquals(FinishReason.OTHER, mapped.finishReason)
    }

    // -----------------------------------------------------------------
    // toAiResponse — usage 누락 방어
    // -----------------------------------------------------------------

    @Test
    fun `M-009 — usage null 이면 TokenUsage(0, 0) 으로 매핑`() {
        val response = AnthropicMessagesResponse(
            content = listOf(AnthropicContentBlock(type = "text", text = "hi")),
            stopReason = "end_turn",
            usage = null,
        )

        val mapped = Mapper.toAiResponse(response, ProviderId.CLAUDE)

        assertEquals(0, mapped.usage.inputTokens)
        assertEquals(0, mapped.usage.outputTokens)
    }

    @Test
    fun `M-002 — content 가 비어있으면 text 가 빈 문자열 (호출 측 E-110 검증)`() {
        // F-001 정상 흐름 4단계: Mapper 는 빈 text 도 그대로 통과시킨다 (호출 측에서 검증).
        val response = AnthropicMessagesResponse(
            content = emptyList(),
            stopReason = "end_turn",
            usage = AnthropicUsage(0, 0),
        )

        val mapped = Mapper.toAiResponse(response, ProviderId.CLAUDE)

        assertEquals("", mapped.text)
    }

    // -----------------------------------------------------------------
    // 헬퍼
    // -----------------------------------------------------------------

    private fun responseWithStop(stopReason: String?): AnthropicMessagesResponse =
        AnthropicMessagesResponse(
            content = listOf(AnthropicContentBlock(type = "text", text = "x")),
            stopReason = stopReason,
            usage = AnthropicUsage(0, 0),
        )
}
