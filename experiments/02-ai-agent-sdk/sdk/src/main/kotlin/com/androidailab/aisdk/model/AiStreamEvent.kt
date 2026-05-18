package com.androidailab.aisdk.model

/**
 * 스트리밍 응답 이벤트 (M-006).
 *
 * 사양 참조: data-model.md M-006, features.md F-003 정상 흐름
 *
 * 방출 순서 보장 (M-006):
 * - [Delta]는 0회 이상 방출 가능
 * - [Done] 또는 [Error]는 정확히 1회로 종결
 * - [Done] 이후 [Delta] 방출 금지
 *
 * 본 라운드(F-005)에서는 Provider 인터페이스(P-001) `stream()` 반환 타입으로 참조한다.
 * F-003 본체 구현은 후속 라운드에서 추가.
 */
public sealed class AiStreamEvent {

    /** 토큰 chunk (M-006 Delta variant). */
    public data class Delta(public val text: String) : AiStreamEvent()

    /** 스트림 정상 종료 + 누적 응답 (M-006 Done variant). */
    public data class Done(public val response: AiResponse) : AiStreamEvent()

    /** 스트림 오류 (M-006 Error variant). E-301 / close 케이스 B 등이 매핑. */
    public data class Error(public val cause: AiException) : AiStreamEvent()
}
