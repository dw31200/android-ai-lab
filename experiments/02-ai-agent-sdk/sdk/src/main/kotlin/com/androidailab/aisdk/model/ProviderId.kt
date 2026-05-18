package com.androidailab.aisdk.model

/**
 * LLM Provider 식별자 (M-010).
 *
 * v0.1 정책 (R-009): 우선 구현된 Provider만 enum에 포함.
 * 미구현 Provider 식별자를 미리 두면 호출자가 build()에서 선택 가능해
 * 동작 미정의 위험이 발생하므로, 구현 라운드에서 enum 값을 추가한다.
 *
 * v0.1 시점 enum 값: CLAUDE 만 존재.
 * - OPENAI: v0.2 구현 시 추가
 * - GEMINI: v0.3 구현 시 추가
 *
 * Capabilities(supportsImage 등)는 [com.androidailab.aisdk.provider.Provider]
 * 인터페이스 측의 단일 source of truth (R-010, provider-spec.md 참조).
 *
 * @see com.androidailab.aisdk.AiAgentClient
 */
public enum class ProviderId(public val displayName: String) {
    CLAUDE("Anthropic Claude"),
}
