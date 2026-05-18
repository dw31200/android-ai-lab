package com.androidailab.aisdk.model

/**
 * 토큰 사용량 (M-009).
 *
 * 사양 참조: data-model.md M-009
 */
public data class TokenUsage(
    public val inputTokens: Int,
    public val outputTokens: Int,
) {
    public val totalTokens: Int get() = inputTokens + outputTokens
}
