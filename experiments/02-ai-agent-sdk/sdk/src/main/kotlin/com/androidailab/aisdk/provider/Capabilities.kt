package com.androidailab.aisdk.provider

/**
 * Provider 능력 명세 (data-model 보강, provider-spec.md P-001).
 *
 * 사양 참조:
 * - provider-spec.md P-001 (Capabilities data class 정의)
 * - R-010: Capabilities는 Provider 인터페이스 측의 단일 source of truth.
 *   [com.androidailab.aisdk.model.ProviderId] enum은 식별자만 보유하고 capability 필드를 갖지 않는다.
 *
 * SDK는 본 데이터를 활용해 다음을 검증한다 (후속 라운드):
 * - F-002 E-205: Provider가 이미지 미지원이면 호출 거부
 * - F-003 E-303: Provider가 스트리밍 미지원이면 호출 거부
 * - F-002 E-201/E-204: 이미지 단일/총 크기, mime 타입 검증의 한계값으로 사용
 *
 * @property supportsImage 이미지 입력 지원 여부 (F-002)
 * @property supportsVideo 영상 입력 지원 여부 (v0.2 — v0.1은 false 강제)
 * @property supportsStream 스트리밍 응답 지원 여부 (F-003)
 * @property supportsSession 세션 컨텍스트 지원 여부 (F-004)
 * @property maxImageSizeBytes 단일 이미지 최대 크기 (E-201 검증 한계)
 * @property maxImagesPerRequest 단일 요청당 최대 이미지 개수
 * @property supportedImageMimeTypes 지원 mimeType 집합 (E-204 검증)
 */
public data class Capabilities(
    public val supportsImage: Boolean,
    public val supportsVideo: Boolean,
    public val supportsStream: Boolean,
    public val supportsSession: Boolean,
    public val maxImageSizeBytes: Long,
    public val maxImagesPerRequest: Int,
    public val supportedImageMimeTypes: Set<String>,
)
