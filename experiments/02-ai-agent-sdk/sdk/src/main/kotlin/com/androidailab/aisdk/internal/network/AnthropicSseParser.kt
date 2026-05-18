package com.androidailab.aisdk.internal.network

import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.AiResponse
import com.androidailab.aisdk.model.AiStreamEvent
import com.androidailab.aisdk.model.FinishReason
import com.androidailab.aisdk.model.ProviderId
import com.androidailab.aisdk.model.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.BufferedSource
import java.io.IOException

/**
 * Anthropic Messages API Server-Sent Events (SSE) 파서 (P-CLAUDE, F-003).
 *
 * 사양 참조:
 * - provider-spec.md P-CLAUDE: 스트리밍 = SSE
 * - features.md F-003 정상 흐름 1~4 / 예외 흐름 (E-301 / E-302 / E-303)
 * - data-model.md M-006 [AiStreamEvent] 방출 순서: Delta 0+ → Done | Error 1
 * - error-handling.md ERR-001 (Network) / ERR-006 (ServerError) / ERR-002 (RateLimit) / ERR-003 (Authentication)
 *
 * SSE 이벤트 형식 (Anthropic Messages SSE):
 * - `event: message_start` — 메시지 시작 (parse 단계에서 무시, 사양상 정보적)
 * - `event: content_block_start` — 컨텐츠 블록 시작
 * - `event: content_block_delta` — `data: {"type":"content_block_delta","index":...,"delta":{"type":"text_delta","text":"..."}}`
 *   → 본 SDK는 `delta.type == "text_delta"`인 경우만 [AiStreamEvent.Delta] 방출
 * - `event: content_block_stop` — 컨텐츠 블록 종료
 * - `event: message_delta` — `data: {"type":"message_delta","delta":{"stop_reason":"end_turn",...},"usage":{...}}`
 *   → `stop_reason`을 [FinishReason]으로 매핑하여 누적
 * - `event: message_stop` — 스트림 정상 종료. 본 시점에 [AiStreamEvent.Done] 방출
 * - `event: error` — `data: {"type":"error","error":{"type":"...","message":"..."}}`
 *   → [AiStreamEvent.Error] 방출 후 종료 (E-301 — connection 끊김 등 도중 오류 통합 매핑)
 * - `event: ping` — keep-alive (본 SDK는 무시)
 *
 * 빈 응답 검증 (R-005, F-001 정상 흐름 4단계):
 * - 누적된 텍스트가 비어있고 finishReason이 MAX_TOKENS/OTHER이면 [AiException.ServerError(-1, "empty response")]를
 *   [AiStreamEvent.Error]로 방출 (E-110와 같은 시맨틱을 stream에도 적용 — F-001 일관성 유지).
 * - END_TURN/STOP_SEQUENCE에서 빈 응답이면 그대로 [AiStreamEvent.Done] 방출 (호출자가 finishReason으로 판별).
 *
 * 방출 순서 보장 (M-006):
 * - parse는 Delta를 0회 이상 emit한 뒤 정확히 1회의 Done 또는 Error로 종결한다.
 * - Done 이후 Delta 방출 금지.
 * - Error는 도중 또는 종결 어느 시점에서나 1회만 emit하고 stream을 종료한다.
 *
 * 본 함수는 [BufferedSource]로부터 라인 단위로 읽어 cold Flow를 만든다.
 * 호출자(Provider.stream)는 OkHttp Response.body!!.source()를 전달하면 된다.
 * Source 닫힘은 호출자(Provider) 책임 (Response.use 패턴).
 */
internal object AnthropicSseParser {

    /**
     * SSE 스트림 → [AiStreamEvent] cold Flow 변환 (F-003).
     *
     * 본 Flow는 [parseLines]에서 emit되는 이벤트를 그대로 방출한다.
     * 본 빌더 자체는 source close를 수행하지 않는다 — 호출자가 Response.use { ... } 안에서 사용해야 한다.
     *
     * @param source SSE 스트림 (Anthropic Messages SSE 형식)
     * @param providerId 응답에 부여될 [ProviderId] (M-002 매핑)
     * @return Delta 0+ → Done | Error 1로 종결되는 cold [Flow]
     */
    fun parse(source: BufferedSource, providerId: ProviderId): Flow<AiStreamEvent> = flow {
        // 누적 상태 (단일 코루틴 내에서 진행되므로 동기화 불필요)
        val textBuffer = StringBuilder()
        var stopReason: String? = null
        var inputTokens = 0
        var outputTokens = 0
        var terminated = false // Done/Error 방출 후에는 추가 Delta 무시

        try {
            while (!source.exhausted()) {
                val rawLine: String = try {
                    source.readUtf8Line() ?: break
                } catch (e: IOException) {
                    // E-301: 스트림 도중 연결 끊김 → Error 방출 후 종료
                    if (!terminated) {
                        emit(AiStreamEvent.Error(AiException.Network(e)))
                        terminated = true
                    }
                    return@flow
                }

                if (rawLine.isEmpty()) continue
                // SSE 사양상 `event:` 라인은 정보적 — 본 파서는 `data:` 라인의 type 필드로 분기.
                // 사용자 데이터에 `:`로 시작하는 코멘트 라인이 들어오면 무시.
                if (rawLine.startsWith(":")) continue
                if (!rawLine.startsWith("data:")) continue

                val payload = rawLine.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                // SSE [DONE] sentinel은 OpenAI 호환이며 Anthropic 사양에는 없으나 방어적으로 처리.
                if (payload == "[DONE]") continue

                val obj: JsonObject = try {
                    AnthropicJson.instance.parseToJsonElement(payload).jsonObject
                } catch (e: SerializationException) {
                    // 잘못된 JSON 라인은 사양상 한 줄 무시 후 다음 라인을 시도. (Anthropic이 partial chunk를
                    // 보낼 가능성을 고려) 대신 누적 한도/반복 처리는 v0.1 범위 외.
                    continue
                } catch (e: IllegalArgumentException) {
                    // jsonObject 변환 실패 — 같은 처리
                    continue
                }

                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    EVT_CONTENT_BLOCK_DELTA -> {
                        if (terminated) continue
                        val delta = obj["delta"] as? JsonObject ?: continue
                        val deltaType = delta["type"]?.jsonPrimitive?.contentOrNull
                        if (deltaType == DELTA_TEXT) {
                            val text = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (text.isNotEmpty()) {
                                textBuffer.append(text)
                                emit(AiStreamEvent.Delta(text))
                            }
                        }
                        // input_json_delta, signature_delta 등 다른 delta 타입은 v0.1 무시.
                    }
                    EVT_MESSAGE_DELTA -> {
                        // {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":15}}
                        val delta = obj["delta"] as? JsonObject
                        delta?.get("stop_reason")?.let { sr ->
                            (sr as? JsonPrimitive)?.contentOrNull?.let { stopReason = it }
                        }
                        // usage는 누적이 아니라 이번까지의 outputTokens 갱신값
                        (obj["usage"] as? JsonObject)?.let { u ->
                            u["input_tokens"]?.jsonPrimitive?.intOrNull?.let { inputTokens = it }
                            u["output_tokens"]?.jsonPrimitive?.intOrNull?.let { outputTokens = it }
                        }
                    }
                    EVT_MESSAGE_START -> {
                        // {"type":"message_start","message":{"id":"...","usage":{"input_tokens":N,"output_tokens":M}}}
                        // 초기 input_tokens는 message_start에서 1회 도착할 수 있음 — 보존.
                        ((obj["message"] as? JsonObject)?.get("usage") as? JsonObject)?.let { u ->
                            u["input_tokens"]?.jsonPrimitive?.intOrNull?.let { inputTokens = it }
                            u["output_tokens"]?.jsonPrimitive?.intOrNull?.let { outputTokens = it }
                        }
                    }
                    EVT_MESSAGE_STOP -> {
                        if (terminated) return@flow
                        // 정상 종결: Done 방출 (R-005 빈 응답 검증 동시 적용)
                        val accText = textBuffer.toString()
                        val finishReason = mapStopReason(stopReason)
                        // R-005: 빈 텍스트 + (END_TURN | STOP_SEQUENCE) → 그대로 Done
                        //         빈 텍스트 + (MAX_TOKENS | OTHER) → ServerError(-1, "empty response") 방출
                        if (accText.isEmpty() &&
                            finishReason !in EMPTY_OK_FINISH_REASONS
                        ) {
                            emit(
                                AiStreamEvent.Error(
                                    AiException.ServerError(
                                        code = -1,
                                        message = "empty response",
                                    ),
                                ),
                            )
                            terminated = true
                            return@flow
                        }
                        emit(
                            AiStreamEvent.Done(
                                response = AiResponse(
                                    text = accText,
                                    usage = TokenUsage(inputTokens, outputTokens),
                                    finishReason = finishReason,
                                    providerId = providerId,
                                ),
                            ),
                        )
                        terminated = true
                        return@flow
                    }
                    EVT_ERROR -> {
                        // {"type":"error","error":{"type":"overloaded_error","message":"..."}}
                        if (terminated) return@flow
                        val err = obj["error"] as? JsonObject
                        val errType = err?.get("type")?.jsonPrimitive?.contentOrNull
                        val errMessage = err?.get("message")?.jsonPrimitive?.contentOrNull
                            ?: "stream error"
                        emit(AiStreamEvent.Error(toAiException(errType, errMessage)))
                        terminated = true
                        return@flow
                    }
                    EVT_PING -> { /* keep-alive — 무시 */ }
                    EVT_CONTENT_BLOCK_START,
                    EVT_CONTENT_BLOCK_STOP,
                    -> { /* 정보적 — 무시 */ }
                    else -> { /* 알 수 없는 type — forward-compat을 위해 무시 */ }
                }
            }

            // source가 message_stop 없이 EOF 도달 — E-301 (스트림 끊김) 매핑.
            if (!terminated) {
                emit(
                    AiStreamEvent.Error(
                        AiException.Network(
                            IOException("stream ended without message_stop"),
                        ),
                    ),
                )
            }
        } catch (e: IOException) {
            // E-301: source 읽기 도중 IO 오류 → Error
            if (!terminated) {
                emit(AiStreamEvent.Error(AiException.Network(e)))
            }
        }
        // CancellationException은 Flow의 표준 시맨틱에 따라 별도 catch 없이 그대로 전파 (E-302).
    }

    /**
     * Anthropic `stop_reason` 문자열 → [FinishReason] enum 매핑.
     *
     * 사양 참조: data-model.md M-002 [FinishReason] enum (END_TURN/MAX_TOKENS/STOP_SEQUENCE/OTHER)
     */
    private fun mapStopReason(raw: String?): FinishReason = when (raw) {
        "end_turn" -> FinishReason.END_TURN
        "max_tokens" -> FinishReason.MAX_TOKENS
        "stop_sequence" -> FinishReason.STOP_SEQUENCE
        else -> FinishReason.OTHER
    }

    /**
     * SSE `error` 이벤트의 Anthropic error.type → [AiException] 매핑.
     *
     * provider-spec.md P-CLAUDE 에러 매핑:
     * - authentication_error → [AiException.Authentication]
     * - rate_limit_error → [AiException.RateLimit] (retryAfter 미상 → null)
     * - overloaded_error / api_error / invalid_request_error / 그 외 → [AiException.ServerError(-1, message)]
     *
     * stream 도중 도착하는 error는 ERR-006 또는 위와 같이 매핑하며, 본 SDK는 [AiStreamEvent.Error]로 한 번 방출 후 stream을 종료한다.
     */
    private fun toAiException(errType: String?, message: String): AiException = when (errType) {
        "authentication_error" -> AiException.Authentication()
        "rate_limit_error" -> AiException.RateLimit(retryAfter = null)
        "invalid_request_error" -> AiException.InvalidInput(message)
        else -> AiException.ServerError(code = -1, message = message)
    }

    /** F-003 / R-005 — 빈 텍스트가 허용되는 [FinishReason]. F-001과 동일한 정책 (일관성). */
    private val EMPTY_OK_FINISH_REASONS: Set<FinishReason> = setOf(
        FinishReason.END_TURN,
        FinishReason.STOP_SEQUENCE,
    )

    // SSE event type 문자열 (Anthropic Messages SSE)
    private const val EVT_MESSAGE_START: String = "message_start"
    private const val EVT_MESSAGE_DELTA: String = "message_delta"
    private const val EVT_MESSAGE_STOP: String = "message_stop"
    private const val EVT_CONTENT_BLOCK_START: String = "content_block_start"
    private const val EVT_CONTENT_BLOCK_DELTA: String = "content_block_delta"
    private const val EVT_CONTENT_BLOCK_STOP: String = "content_block_stop"
    private const val EVT_ERROR: String = "error"
    private const val EVT_PING: String = "ping"

    private const val DELTA_TEXT: String = "text_delta"
}
