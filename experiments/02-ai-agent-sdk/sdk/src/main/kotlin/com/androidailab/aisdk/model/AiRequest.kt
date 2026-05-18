package com.androidailab.aisdk.model

/**
 * 질의 요청 (M-001).
 *
 * 사양 참조:
 * - data-model.md M-001 (필드/제약 조건)
 * - features.md F-001/F-002
 *
 * 검증 규칙 (M-001 init 블록):
 * - prompt 비어있지 않을 것 (E-107 매핑)
 * - prompt 100,000자 이하
 * - images 최대 10장 (E-202와 별개로 개수 한계)
 * - maxTokens 1..8192
 * - temperature 0.0..2.0
 *
 * v0.1에서는 [videos]가 비어있어야 한다 (overview.md "Out of Scope"). M-001 표는 본 제약을
 * `v0.1에서는 빈 리스트 강제`로 명시.
 *
 * 본 라운드(F-005)에서는 Provider 인터페이스(P-001) 시그니처에서 본 모델을 참조하기 위해 도입.
 * F-001/F-002의 호출 본체는 후속 라운드에서 추가.
 *
 * @property prompt 텍스트 프롬프트 (1..100,000자)
 * @property images 첨부 이미지 (M-003), 최대 10장
 * @property videos 첨부 영상 (M-004), v0.1은 빈 리스트 강제
 * @property maxTokens 최대 응답 토큰, 기본 1024
 * @property temperature 샘플링 온도, 기본 0.7
 */
public data class AiRequest(
    val prompt: String,
    val images: List<ImageInput> = emptyList(),
    val videos: List<VideoInput> = emptyList(),
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f,
) {
    init {
        require(prompt.isNotBlank()) { "prompt must not be blank" }
        require(prompt.length <= 100_000) { "prompt too long" }
        require(images.size <= 10) { "too many images" }
        require(maxTokens in 1..8192) { "maxTokens out of range" }
        require(temperature in 0f..2f) { "temperature out of range" }
        // v0.1에서 video는 빈 리스트 강제 (overview.md Out of Scope, M-001)
        require(videos.isEmpty()) { "videos are not supported in v0.1" }
    }
}
