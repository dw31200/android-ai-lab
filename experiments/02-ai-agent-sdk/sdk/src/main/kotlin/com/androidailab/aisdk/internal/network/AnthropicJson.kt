package com.androidailab.aisdk.internal.network

import kotlinx.serialization.json.Json

/**
 * Anthropic API JSON 인코더 (P-CLAUDE, F-001).
 *
 * 사양 참조:
 * - provider-spec.md P-CLAUDE: API = `POST /v1/messages`
 * - overview.md "기술 스택": kotlinx.serialization
 *
 * 정책:
 * - `ignoreUnknownKeys = true`: Anthropic이 새 필드를 추가해도 SDK 호환성 유지 (forward-compat)
 * - `encodeDefaults = false`: 명시적으로 설정된 필드만 직렬화 (예: nullable temperature 미설정 시 직렬화 제외)
 * - `explicitNulls = false`: null 값은 직렬화하지 않음 (Anthropic API의 optional 필드 컨벤션 준수)
 */
internal object AnthropicJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        isLenient = false
    }
}
