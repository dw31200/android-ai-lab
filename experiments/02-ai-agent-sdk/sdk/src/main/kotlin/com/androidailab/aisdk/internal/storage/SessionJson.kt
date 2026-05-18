package com.androidailab.aisdk.internal.storage

import kotlinx.serialization.json.Json

/**
 * Session 영속화 JSON 인코더 (F-007, M-011).
 *
 * 사양 참조:
 * - data-model.md M-011 "직렬화 정책 — 형식: JSON (kotlinx.serialization)"
 * - features.md F-007 NFR — 직렬화 형식: JSON
 *
 * 정책:
 * - `ignoreUnknownKeys = true` — 향후 schemaVersion 마이그레이션 시 알 수 없는 필드를 안전하게 무시
 *   (단, schemaVersion 검증은 별도로 수행하여 R-018을 지킨다)
 * - `encodeDefaults = true` — schemaVersion 같은 default 필드도 명시적으로 직렬화되도록 보장
 *   (역직렬화 측이 default 값을 못 받으면 R-018 검증 우회 위험)
 * - `explicitNulls = false` — null 값(systemPrompt 등)은 직렬화 제외 (M-011 systemPrompt: String?)
 * - `prettyPrint = false` — 디스크 효율 (1MB 한계, E-704)
 */
internal object SessionJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = false
        prettyPrint = false
    }
}
