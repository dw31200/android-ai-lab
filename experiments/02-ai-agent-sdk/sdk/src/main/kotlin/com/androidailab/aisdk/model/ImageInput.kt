package com.androidailab.aisdk.model

/**
 * 이미지 입력 sealed class (M-003).
 *
 * 사양 참조:
 * - data-model.md M-003 (Variant 정의 및 직렬화 규칙)
 * - features.md F-002 (멀티모달 질의)
 * - R-016 (Bytes의 equals/hashCode 명시 정의)
 *
 * Variant:
 * - [Uri]: content:// 또는 file:// URI
 * - [Bytes]: 메모리 바이트 + mimeType (data class 아님, R-016)
 * - [Url]: https URL (Provider가 fetch 또는 SDK가 fetch 후 Bytes 변환)
 *
 * F-002 라운드(T12)에 init 검증을 추가:
 * - [Bytes]: data가 빈 배열 아님, mimeType이 빈 문자열 아님 (E-204 사전 차단의 한 형태)
 * - [Url]: url이 빈 문자열 아님, https 스킴 (provider-spec.md "ImageInput.Url SSRF 방어 정책",
 *   R-023에 따라 SDK는 형식 검증만 수행)
 *
 * Provider 한계(이미지 5MB / 10장 / mimeType 화이트리스트) 검증은 [com.androidailab.aisdk.AiAgentClient.ask]
 * 진입 시점에 [com.androidailab.aisdk.provider.Capabilities] 와 매칭하여 수행 (F-002 정상 흐름 2단계).
 */
public sealed class ImageInput {

    /** content:// 또는 file:// URI 형태의 이미지 입력 (M-003 Uri variant). */
    public data class Uri(public val uri: android.net.Uri) : ImageInput()

    /**
     * 메모리 바이트 + mimeType 형태의 이미지 입력 (M-003 Bytes variant).
     *
     * R-016에 따라 data class 자동 equals/hashCode가 `ByteArray`를 reference 비교하는 문제를
     * 회피하기 위해 명시 정의. `contentEquals` + `contentHashCode` + `mimeType` 조합 사용.
     *
     * init 검증 (F-002 T12 신규):
     * - [data] 비어있지 않음 (이미지 본체가 없으면 인코딩 단계에서 디코드 실패가 자명)
     * - [mimeType] 비어있지 않음 (E-204의 사전 차단 — 실제 매핑 매칭은 ask 진입 시점)
     */
    public class Bytes(
        public val data: ByteArray,
        public val mimeType: String,
    ) : ImageInput() {

        init {
            require(data.isNotEmpty()) { "image data must not be empty" }
            require(mimeType.isNotBlank()) { "mimeType must not be blank" }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            if (mimeType != other.mimeType) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            return 31 * data.contentHashCode() + mimeType.hashCode()
        }

        override fun toString(): String =
            "ImageInput.Bytes(mimeType=$mimeType, size=${data.size})"
    }

    /**
     * https URL 형태의 이미지 입력 (M-003 Url variant).
     *
     * SDK는 형식 검증(https 스킴, 비어있지 않음)만 수행한다. SSRF 방어는 호출자 책임 (R-023).
     * fetch 실패는 ask 시점에 E-206으로 매핑된다.
     *
     * init 검증 (F-002 T12 신규):
     * - [url] 비어있지 않음
     * - [url] `https://` 스킴으로 시작 (provider-spec.md "URL 형식 검증 (`https://` 스킴)")
     */
    public data class Url(public val url: String) : ImageInput() {
        init {
            require(url.isNotBlank()) { "url must not be blank" }
            // 검증 메시지에는 URL 전체를 노출하지 않고 접두 16자만 포함 (전체 노출 위험 회피).
            require(url.startsWith("https://")) {
                "url must start with https:// (got: ${url.take(16)})"
            }
        }
    }
}
