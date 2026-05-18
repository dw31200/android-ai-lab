package com.androidailab.aisdk.model

/**
 * 질의 응답 (M-002).
 *
 * 사양 참조: data-model.md M-002, features.md F-001 정상 흐름 4단계
 *
 * 빈 [text]는 허용되며, [finishReason]이 [FinishReason.END_TURN]/[FinishReason.STOP_SEQUENCE]
 * 인 경우 그대로 성공 반환된다 (F-001 정상 흐름 4단계). [FinishReason.MAX_TOKENS] 또는
 * [FinishReason.OTHER]에서 빈 응답이면 E-110 검증 실패.
 *
 * @property text 응답 텍스트 (빈 문자열 가능)
 * @property usage 토큰 사용량 (M-009)
 * @property finishReason 종료 사유
 * @property providerId 응답을 만든 Provider 식별자 (M-010)
 */
public data class AiResponse(
    public val text: String,
    public val usage: TokenUsage,
    public val finishReason: FinishReason,
    public val providerId: ProviderId,
)

/**
 * 응답 종료 사유 (M-002 enum).
 *
 * 사양 참조: data-model.md M-002
 */
public enum class FinishReason {
    END_TURN,
    MAX_TOKENS,
    STOP_SEQUENCE,
    OTHER,
}
