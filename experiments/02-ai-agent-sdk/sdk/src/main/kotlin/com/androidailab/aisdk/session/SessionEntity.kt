package com.androidailab.aisdk.session

import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.ImageInput
import com.androidailab.aisdk.model.Message
import com.androidailab.aisdk.model.Role
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Base64

/**
 * Session 영속화 직렬화 엔티티 (M-011, F-007).
 *
 * 사양 참조:
 * - data-model.md M-011 (필드/제약/직렬화 정책)
 * - features.md F-007 정상 흐름 (저장/복원/삭제)
 * - features.md F-007 schemaVersion v0.1 정책 (R-018) — schemaVersion = 1만 인정, 그 외는 E-703
 * - features.md F-007 이미지 영속화 운영 가이드 (R-021)
 * - data-model.md M-011 "MessageEntity ↔ Message 변환" (Role.SYSTEM은 영속화 안 함)
 * - data-model.md M-011 "ImageInput.Url 영속화 보안 정책 (R-023)"
 *
 * 직렬화 정책:
 * - 형식: JSON (kotlinx.serialization, [SessionJson])
 * - 인코딩: UTF-8
 * - DataStore Preferences 키: `"session:{sessionId}"`
 * - 보안: API 키는 Session에 포함되지 않으므로 영속화 데이터에도 포함되지 않음 (D-003)
 * - 크기 제한: JSON 직렬화 결과 1MB 초과 시 E-704
 *
 * schemaVersion 정책 (R-018):
 * - v0.1은 [SCHEMA_VERSION_V1] (=1)만 인정.
 * - 로드 시 다른 값이면 즉시 E-703으로 거부 (`AiException.IOError("session schema unsupported: v={loaded}")`).
 * - v0.2 이상에서 마이그레이션을 도입할 때는 `internal object SessionMigrations`에 단계별 변환 함수를 등록.
 *
 * @property schemaVersion 직렬화 스키마 버전 (현재 1)
 * @property sessionId M-007.sessionId
 * @property systemPrompt M-007.systemPrompt
 * @property history 메시지 이력 ([MessageEntity] 리스트)
 * @property savedAt 저장 시점 epoch millis
 */
@Serializable
internal data class SessionEntity(
    val schemaVersion: Int = SCHEMA_VERSION_V1,
    val sessionId: String,
    val systemPrompt: String? = null,
    val history: List<MessageEntity>,
    val savedAt: Long,
) {
    internal companion object {
        /**
         * v0.1 schemaVersion (R-018).
         *
         * 로드 시 [SessionEntity.schemaVersion] != [SCHEMA_VERSION_V1] 이면 E-703으로 거부한다.
         * v0.2부터 v2, v3 등이 추가되며 [SessionMigrations] 패턴으로 단계 변환된다.
         */
        const val SCHEMA_VERSION_V1: Int = 1
    }
}

/**
 * Message 직렬화 엔티티 (M-011, M-008).
 *
 * - [role]은 [Role.name] 문자열로 보관 ("USER" / "ASSISTANT")
 * - [Role.SYSTEM]은 history에 들어가지 않으므로 영속화 대상이 아니다 (R-008)
 * - 변환은 [MessageEntity.toMessage] / [Message.toEntity] 헬퍼 사용
 */
@Serializable
internal data class MessageEntity(
    val role: String,
    val content: String,
    val images: List<ImageInputEntity> = emptyList(),
    val timestamp: Long,
)

/**
 * ImageInput 직렬화 엔티티 (M-011, M-003).
 *
 * 사양 참조:
 * - data-model.md M-011 ImageInputEntity 정의
 * - data-model.md M-003 직렬화 규칙
 * - data-model.md M-011 ImageInput.Url 영속화 보안 정책 (R-023) — URL은 그대로 보관, SSRF 방어 미수행
 * - features.md F-007 이미지 영속화 운영 가이드 (R-021) — Bytes는 base64로 보관되며 1MB 한계 주의
 *
 * Sealed 직렬화는 `@SerialName`으로 type discriminator 사용 (kotlinx-serialization 기본 정책).
 *
 * Variant:
 * - [Uri]: Uri 문자열만 보관. 복원 시 read 가능 여부는 호출자 책임.
 * - [Bytes]: base64 + mimeType. 단일 5MB → base64 ≈ 6.7MB → 1MB 한계 초과 가능.
 * - [Url]: URL 문자열 그대로 보관. SSRF 방어는 호출자 책임 (R-023).
 */
@Serializable
internal sealed class ImageInputEntity {
    @Serializable
    @SerialName("uri")
    internal data class Uri(val uri: String) : ImageInputEntity()

    @Serializable
    @SerialName("bytes")
    internal data class Bytes(val base64: String, val mimeType: String) : ImageInputEntity()

    @Serializable
    @SerialName("url")
    internal data class Url(val url: String) : ImageInputEntity()
}

// ----------------------------------------------------------------------
// 변환 헬퍼: 도메인 모델 ↔ 직렬화 모델 (M-011 "MessageEntity ↔ Message 변환")
// ----------------------------------------------------------------------

/**
 * [Message] → [MessageEntity] 변환 (F-007 save 흐름).
 *
 * 사양 참조:
 * - data-model.md M-011 "MessageEntity ↔ Message 변환"
 * - R-008: [Role.SYSTEM]은 영속화 대상이 아니다. 호출 측(Session.save)이 USER/ASSISTANT만 보낸다고 가정.
 *
 * @throws AiException.InvalidInput [Role.SYSTEM]이 들어오면 안전망 차단 (사양상 도달 불가)
 */
internal fun Message.toEntity(): MessageEntity {
    if (role == Role.SYSTEM) {
        // R-008 — SYSTEM은 history에 들어가지 않으므로 영속화 호출 측에서 사전 차단 (도달 불가).
        // 방어적으로 거부.
        throw AiException.InvalidInput("Role.SYSTEM cannot be persisted in history")
    }
    return MessageEntity(
        role = role.name,
        content = content,
        images = images.map { it.toEntity() },
        timestamp = timestamp,
    )
}

/**
 * [MessageEntity] → [Message] 복원 (F-007 load 흐름).
 *
 * 사양 참조:
 * - data-model.md M-011 "MessageEntity ↔ Message 변환" — role 문자열을 [Role] enum으로 변환
 * - R-008: SYSTEM은 history에 들어가지 않으므로 도달 시 안전망 차단
 *
 * @throws AiException.IOError role 문자열이 USER/ASSISTANT가 아닌 경우 (역직렬화 손상 — E-703 위치)
 */
internal fun MessageEntity.toMessage(): Message {
    val parsedRole = when (role) {
        Role.USER.name -> Role.USER
        Role.ASSISTANT.name -> Role.ASSISTANT
        // SYSTEM 또는 알 수 없는 값은 손상으로 간주 (E-703 후보)
        else -> throw AiException.IOError(
            "session data corrupted: unknown role '$role'",
        )
    }
    return Message(
        role = parsedRole,
        content = content,
        images = images.map { it.toImageInput() },
        timestamp = timestamp,
    )
}

/**
 * [ImageInput] → [ImageInputEntity] 변환 (M-011 ImageInputEntity ↔ ImageInput 변환).
 *
 * 사양 참조:
 * - data-model.md M-011 "ImageInputEntity ↔ ImageInput 변환"
 * - data-model.md M-003 영속화 규칙
 * - R-021 이미지 영속화 운영 가이드 — Bytes는 base64, 1MB 한계는 SessionStore 전체 검증에서 수행
 * - R-023 ImageInput.Url 영속화 보안 정책 — URL은 변환·필터링 없이 그대로 보관
 *
 * @return ImageInputEntity 변환 결과
 */
internal fun ImageInput.toEntity(): ImageInputEntity = when (this) {
    is ImageInput.Uri -> ImageInputEntity.Uri(uri = uri.toString())
    is ImageInput.Bytes -> ImageInputEntity.Bytes(
        base64 = Base64.getEncoder().encodeToString(data),
        mimeType = mimeType,
    )
    is ImageInput.Url -> ImageInputEntity.Url(url = url)
}

/**
 * [ImageInputEntity] → [ImageInput] 복원 (F-007 load 흐름).
 *
 * 사양 참조:
 * - data-model.md M-011 "ImageInputEntity ↔ ImageInput 변환"
 * - R-023 복원된 URL은 그대로 반환 — SSRF 방어는 호출자 책임
 *
 * @throws AiException.IOError base64 decode 실패 시 (E-703 손상 데이터)
 */
internal fun ImageInputEntity.toImageInput(): ImageInput = when (this) {
    is ImageInputEntity.Uri -> ImageInput.Uri(android.net.Uri.parse(uri))
    is ImageInputEntity.Bytes -> {
        val decoded = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            throw AiException.IOError(
                "session data corrupted: base64 decode failed",
                cause = e,
            )
        }
        ImageInput.Bytes(data = decoded, mimeType = mimeType)
    }
    is ImageInputEntity.Url -> ImageInput.Url(url = url)
}
