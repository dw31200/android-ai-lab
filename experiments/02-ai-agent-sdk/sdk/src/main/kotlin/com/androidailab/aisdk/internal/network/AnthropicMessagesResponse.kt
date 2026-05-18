package com.androidailab.aisdk.internal.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Anthropic Messages API 응답 본체 (P-CLAUDE, F-001).
 *
 * 사양 참조:
 * - provider-spec.md P-CLAUDE
 * - data-model.md M-002 / M-009 (AiResponse / TokenUsage 매핑 — [Mapper] 책임)
 *
 * 본 모델은 Anthropic 응답 JSON의 v0.1 텍스트 단발 질의에 사용되는 필드만 포함.
 * 멀티 블록 응답 / tool_use 등 v0.1 범위 외 필드는 무시된다 (kotlinx.serialization
 * `ignoreUnknownKeys` 정책 — [com.androidailab.aisdk.internal.network.AnthropicJson] 참조).
 *
 * @property id Anthropic 응답 식별자 (디버깅/로깅 용)
 * @property type 응답 타입 (`"message"` 등)
 * @property role 메시지 발화자 (`"assistant"` 고정)
 * @property model 응답을 만든 모델 식별자
 * @property content 응답 컨텐츠 블록 리스트 (v0.1은 텍스트 블록만 사용)
 * @property stopReason 종료 사유 (`"end_turn"` / `"max_tokens"` / `"stop_sequence"` 등)
 * @property usage 토큰 사용량
 */
@Serializable
internal data class AnthropicMessagesResponse(
    @SerialName("id") val id: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("role") val role: String? = null,
    @SerialName("model") val model: String? = null,
    @SerialName("content") val content: List<AnthropicContentBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_sequence") val stopSequence: String? = null,
    @SerialName("usage") val usage: AnthropicUsage? = null,
)

/**
 * Anthropic 응답의 컨텐츠 블록 (P-CLAUDE).
 *
 * v0.1 텍스트 단발 질의에서는 [type] = `"text"`인 블록만 사용. 다른 타입(`tool_use` 등)은
 * 무시되어 빈 텍스트로 매핑된다 ([Mapper] 책임).
 *
 * @property type 블록 타입 (`"text"` 등)
 * @property text 텍스트 본문 (type=`text`인 경우만 의미 있음)
 */
@Serializable
internal data class AnthropicContentBlock(
    @SerialName("type") val type: String,
    @SerialName("text") val text: String? = null,
)

/**
 * Anthropic 응답의 토큰 사용량 (P-CLAUDE → M-009 매핑).
 *
 * @property inputTokens 입력 토큰 수
 * @property outputTokens 출력 토큰 수
 */
@Serializable
internal data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
)

/**
 * Anthropic 에러 응답 (4xx/5xx 본문).
 *
 * 사양 참조: provider-spec.md P-CLAUDE 에러 매핑.
 *
 * Anthropic 표준 에러 형식:
 * ```json
 * {"type": "error", "error": {"type": "invalid_request_error", "message": "..."}}
 * ```
 */
@Serializable
internal data class AnthropicErrorResponse(
    @SerialName("type") val type: String? = null,
    @SerialName("error") val error: AnthropicErrorBody? = null,
)

@Serializable
internal data class AnthropicErrorBody(
    @SerialName("type") val type: String? = null,
    @SerialName("message") val message: String? = null,
)
