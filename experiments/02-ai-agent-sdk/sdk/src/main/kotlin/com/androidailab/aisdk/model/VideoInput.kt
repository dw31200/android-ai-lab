package com.androidailab.aisdk.model

/**
 * 영상 입력 sealed class (M-004) — v0.2 사양만 미리 정의.
 *
 * 사양 참조:
 * - data-model.md M-004
 * - overview.md "Out of Scope": v0.1에서 영상 입력은 미지원
 *
 * v0.1에서는 [AiRequest.videos] 가 `emptyList()` 강제이며, 본 sealed class의 인스턴스는
 * 실제로 사용되지 않는다. 사양에 정의된 형태만 미리 두어 후속 v0.2 진입 시 변경 영향을 줄인다.
 */
public sealed class VideoInput {

    /** content:// 또는 file:// URI 형태의 영상 입력 (M-004 Uri variant, v0.2). */
    public data class Uri(public val uri: android.net.Uri) : VideoInput()

    /** https URL 형태의 영상 입력 (M-004 Url variant, v0.2). */
    public data class Url(public val url: String) : VideoInput()
}
