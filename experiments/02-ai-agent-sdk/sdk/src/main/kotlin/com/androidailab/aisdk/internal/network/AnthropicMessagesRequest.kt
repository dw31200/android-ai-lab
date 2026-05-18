package com.androidailab.aisdk.internal.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Anthropic Messages API 요청 본체 (P-CLAUDE, F-001/F-002/F-004).
 *
 * 사양 참조:
 * - provider-spec.md P-CLAUDE: API = `POST /v1/messages`
 * - features.md F-001 정상 흐름 1~3
 * - features.md F-002 정상 흐름 1~5 (이미지 첨부)
 * - features.md F-004 systemPrompt 정책 (R-008) — Session.systemPrompt → Anthropic top-level `system` 필드
 * - data-model.md M-001 (AiRequest 필드 매핑)
 *
 * F-002(T12)에 [AnthropicMessage.content]가 단일 String → `JsonElement`로 변경된다.
 * 텍스트 단발 질의는 단일 텍스트 블록 배열로 직렬화되며, 이미지 첨부 시 텍스트 블록 + 이미지 블록의
 * 다중 블록 배열로 직렬화된다. Anthropic API는 둘 다 허용한다 ("content" can be string or array).
 *
 * F-004(T16): [system] 필드 추가 — Session.systemPrompt를 Anthropic API의 top-level `system` 필드로
 * 매핑한다 (R-008 systemPrompt 정책). default null이라 F-001/F-002 직렬화에 영향 없음
 * ([AnthropicJson]의 `encodeDefaults = false` + `explicitNulls = false`로 null 필드는 직렬화 제외).
 *
 * @property model 모델 식별자 (예: `claude-opus-4-7`). [com.androidailab.aisdk.provider.ProviderConfig.modelId] 매핑.
 * @property maxTokens 최대 응답 토큰 ([com.androidailab.aisdk.model.AiRequest.maxTokens] 매핑)
 * @property messages 메시지 시퀀스 (v0.1 단발 질의는 USER 메시지 1개, Session.send는 history + USER 메시지)
 * @property temperature 샘플링 온도 ([com.androidailab.aisdk.model.AiRequest.temperature] 매핑)
 * @property system Session.systemPrompt를 Anthropic top-level system 필드로 매핑 (R-008, F-004).
 *                  null이면 직렬화 제외 (F-001/F-002 호환성).
 */
@Serializable
internal data class AnthropicMessagesRequest(
    @SerialName("model") val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    @SerialName("messages") val messages: List<AnthropicMessage>,
    @SerialName("temperature") val temperature: Float? = null,
    @SerialName("system") val system: String? = null,
)

/**
 * Anthropic 메시지 단위 (P-CLAUDE).
 *
 * F-002(T12)에 [content] 타입을 String → [JsonElement]로 변경:
 * - 텍스트 단발: `[{"type":"text","text":"..."}]` 단일 블록 배열 (Anthropic API는 단일 string도 허용하나,
 *   본 SDK는 이미지 첨부 시와 표현을 통일하기 위해 항상 블록 배열로 직렬화)
 * - 이미지 첨부: `[{"type":"text","text":"..."},{"type":"image","source":{...}}]` 다중 블록 배열
 *
 * @property role `"user"` 또는 `"assistant"` (Anthropic API 표기)
 * @property content 컨텐츠 블록 배열을 표현하는 [JsonElement]. [Mapper]에서 [AnthropicContentRequestBlock]
 *                  리스트를 [JsonElement]로 직렬화하여 채운다.
 */
@Serializable
internal data class AnthropicMessage(
    @SerialName("role") val role: String,
    @SerialName("content") val content: JsonElement,
)

/**
 * Anthropic 요청용 컨텐츠 블록 (sealed, F-002).
 *
 * Anthropic Messages API의 user content 블록 타입:
 * - text: `{"type":"text","text":"..."}`
 * - image: `{"type":"image","source":{"type":"base64","media_type":"image/...","data":"<base64>"}}`
 *   또는 `{"type":"image","source":{"type":"url","url":"https://..."}}` (Anthropic이 URL fetch 지원)
 *
 * v0.1에서는 base64 source만 사용 (R-021/R-023 — SDK 측에서 이미지 처리 책임을 명확히 하기 위함).
 *
 * 본 모델은 [JsonElement]로 직렬화되어 [AnthropicMessage.content]에 들어간다.
 */
@Serializable
internal sealed class AnthropicContentRequestBlock {

    @Serializable
    @SerialName("text")
    internal data class Text(
        @SerialName("text") val text: String,
    ) : AnthropicContentRequestBlock()

    @Serializable
    @SerialName("image")
    internal data class Image(
        @SerialName("source") val source: AnthropicImageSource,
    ) : AnthropicContentRequestBlock()
}

/**
 * Anthropic 이미지 source (F-002).
 *
 * v0.1은 `type = "base64"`만 사용한다 (SDK가 URL fetch 후 base64로 변환).
 *
 * @property type 항상 `"base64"` (v0.1)
 * @property mediaType `"image/jpeg"` / `"image/png"` / `"image/webp"` / `"image/gif"`
 * @property data base64로 인코딩된 이미지 바이트 (no-wrap)
 */
@Serializable
internal data class AnthropicImageSource(
    @SerialName("type") val type: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("data") val data: String,
)
