package com.androidailab.aisdk.provider

import kotlin.time.Duration

/**
 * Provider 호출 설정 (provider-spec.md P-001).
 *
 * 사양 참조:
 * - provider-spec.md P-001 (ProviderConfig data class 정의)
 * - data-model.md "보안 고려" — apiKey는 메모리에서만 보관 (D-003)
 *
 * `AiAgentClient`가 `Provider.complete()` / `Provider.stream()` 호출 시
 * 인스턴스를 매번 생성하여 전달한다. Provider 구현체는 자체 캐싱하지 않는다
 * (Provider 교체/키 변경이 즉시 반영되도록).
 *
 * @property apiKey 활성 Provider API 키 (D-003: 메모리 보관, 디스크/로그 노출 금지)
 * @property modelId 모델 식별자 (예: "claude-opus-4-7")
 * @property timeout 단일 요청 타임아웃
 */
public data class ProviderConfig(
    public val apiKey: String,
    public val modelId: String,
    public val timeout: Duration,
)
