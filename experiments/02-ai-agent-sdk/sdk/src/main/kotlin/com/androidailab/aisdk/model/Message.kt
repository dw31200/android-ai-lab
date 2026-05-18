package com.androidailab.aisdk.model

/**
 * 세션 내 메시지 단위 (M-008).
 *
 * 사양 참조:
 * - data-model.md M-008 (필드/제약)
 * - features.md F-004 (세션 컨텍스트 유지)
 * - R-008 systemPrompt 정책 — [Role.SYSTEM]은 Session.history()에 포함되지 않음 (Mapper 레벨 내부 변환에서만 사용)
 *
 * Role 사용 정책 (R-008):
 * - [Role.USER] / [Role.ASSISTANT]만 Session.history()에 등장한다.
 * - [Role.SYSTEM]은 enum 값으로 정의되어 있지만 Session에서는 systemPrompt 별도 필드로 관리되며
 *   Message로 history에 추가되지 않는다.
 * - [Role.SYSTEM]은 Provider 전송용 내부 변환 단계에서만 사용될 수 있다 (Mapper 레벨).
 *
 * @property role [Role.USER] 또는 [Role.ASSISTANT] (Session.history()에 들어가는 메시지)
 * @property content 텍스트 본문
 * @property images 첨부 이미지 (USER 메시지에만 의미 있음, M-003)
 * @property timestamp epoch millis
 */
public data class Message(
    public val role: Role,
    public val content: String,
    public val images: List<ImageInput> = emptyList(),
    public val timestamp: Long = System.currentTimeMillis(),
)

/**
 * 메시지 발화자 역할 (M-008).
 *
 * 사양 참조: data-model.md M-008, R-008
 *
 * - [USER]: 호출자가 작성한 메시지
 * - [ASSISTANT]: LLM이 생성한 응답
 * - [SYSTEM]: Provider 전송용 내부 변환 단계에서만 사용 (Session.history()에는 등장하지 않음, R-008)
 */
public enum class Role {
    USER,
    ASSISTANT,
    SYSTEM,
}
