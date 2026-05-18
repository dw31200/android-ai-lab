package com.androidailab.aisdk.internal.network

import com.androidailab.aisdk.model.AiException
import kotlinx.serialization.SerializationException
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * F-004 E-401: Anthropic 에러 응답의 컨텍스트 초과 감지 키워드.
 *
 * 사양 참조:
 * - features.md F-004 "컨텍스트 한계 검증 알고리즘" (E-401) — "Provider 응답에서 컨텍스트 초과 에러
 *   (예: Anthropic의 `invalid_request_error` with token count)를 감지"
 * - provider-spec.md P-CLAUDE 에러 매핑 — "context_length_exceeded → AiException.InvalidInput
 *   ('context too large') (E-401)"
 *
 * 감지 알고리즘 (소문자 비교):
 * - error.type == "invalid_request_error" AND
 * - error.message에 "context_length_exceeded", "context window", "context too long",
 *   "prompt is too long", "token" + "limit"/"exceed" 중 하나가 포함된 경우.
 *
 * v0.1은 키워드 매칭 — Anthropic가 표준 에러 코드를 노출하면 v0.2에서 명시적 코드 매칭으로 전환.
 */
internal val ANTHROPIC_CONTEXT_OVERFLOW_KEYWORDS: List<String> = listOf(
    "context_length_exceeded",
    "context window",
    "context too long",
    "prompt is too long",
    "input is too long",
    "too many tokens",
    "maximum context",
)

/**
 * HTTP/네트워크 예외 → [AiException] 매핑 (F-001, P-CLAUDE).
 *
 * 사양 참조:
 * - features.md F-001 E-101 (네트워크 없음) → ERR-001 (Network)
 * - features.md F-001 E-102 (401) → ERR-003 (Authentication)
 * - features.md F-001 E-103 (429) → ERR-002 (RateLimit)
 * - features.md F-001 E-104 (5xx) → ERR-006 (ServerError)
 * - features.md F-001 E-105 (파싱 실패) → ERR-006 (ServerError)
 * - features.md F-001 E-108 (타임아웃) → ERR-001 (Network, cause=SocketTimeoutException)
 * - error-handling.md ERR ↔ AiException 매핑 표
 * - provider-spec.md P-CLAUDE 에러 매핑 표
 *
 * 작업 원칙 6: "에러는 sealed class" — 외부 라이브러리 예외(IOException, SerializationException 등)는
 * 모두 본 매퍼를 통해 [AiException] sealed 계층으로 변환된다.
 */
internal object ErrorMapper {

    /**
     * HTTP 응답 status code → [AiException] 매핑.
     *
     * 사양 매핑:
     * - 401 → [AiException.Authentication] (E-102)
     * - 429 → [AiException.RateLimit] (E-103, retryAfter는 `Retry-After` 헤더 파싱)
     * - 4xx (그 외) → [AiException.InvalidInput] 또는 [AiException.ServerError] (사양 ERR-005/ERR-006)
     * - 5xx → [AiException.ServerError] (E-104)
     *
     * v0.1에서는 4xx 중 401/429를 제외한 응답을 `ServerError`로 분류한다 (사양 features.md F-001
     * 표에 4xx generic 매핑이 명시되어 있지 않으나, ERR 표상 4xx는 ERR-006 또는 ERR-005). 안전한
     * 기본값으로 [AiException.ServerError]를 사용 — 호출자가 코드로 분기 가능.
     *
     * @param response OkHttp Response. 본 함수는 [response.body]를 닫지 않는다 (호출자 책임).
     * @return null이면 정상 응답으로 처리(2xx). non-null이면 위쪽으로 throw 할 [AiException].
     */
    fun fromHttpStatus(response: Response): AiException? {
        val code = response.code
        if (response.isSuccessful) return null

        return when (code) {
            401 -> AiException.Authentication()
            429 -> AiException.RateLimit(parseRetryAfter(response.header("Retry-After")))
            in 500..599 -> AiException.ServerError(code, "server error: $code")
            else -> AiException.ServerError(code, "http error: $code")
        }
    }

    /**
     * F-004 E-401: Anthropic 에러 응답 본문을 검사하여 컨텍스트 초과 케이스를 감지한다.
     *
     * 사양 참조:
     * - features.md F-004 "컨텍스트 한계 검증 알고리즘" (E-401)
     * - provider-spec.md P-CLAUDE 에러 매핑 — context_length_exceeded → InvalidInput("context too large")
     *
     * 호출 위치 (ClaudeProvider.complete):
     * - HTTP status가 4xx (특히 400)인 경우, [fromHttpStatus]가 [AiException.ServerError]/[AiException.Authentication]
     *   등으로 매핑하기 직전에 본 함수로 본문을 한 번 더 검사한다.
     * - 본문이 Anthropic 표준 에러 형식([AnthropicErrorResponse])이고 type이 "invalid_request_error"이며
     *   메시지에 컨텍스트 초과 키워드가 포함되어 있으면 [AiException.InvalidInput]("context too large")로 변환.
     *
     * 감지 실패(메시지가 컨텍스트 초과 키워드와 무관한 invalid_request_error 등)는 null 반환 — 호출자는
     * [fromHttpStatus] 결과를 그대로 사용.
     *
     * @param errorBody Anthropic 에러 응답 본문(JSON 문자열). null 또는 빈 문자열이면 null 반환.
     * @return E-401 매핑이 필요하면 [AiException.InvalidInput], 아니면 null.
     */
    fun mapContextOverflow(errorBody: String?): AiException.InvalidInput? {
        if (errorBody.isNullOrBlank()) return null
        val parsed: AnthropicErrorResponse = try {
            AnthropicJson.instance.decodeFromString(
                AnthropicErrorResponse.serializer(),
                errorBody,
            )
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        val error = parsed.error ?: return null
        if (error.type != ANTHROPIC_ERROR_TYPE_INVALID_REQUEST) return null
        val message = error.message?.lowercase() ?: return null
        val matched = ANTHROPIC_CONTEXT_OVERFLOW_KEYWORDS.any { keyword ->
            message.contains(keyword.lowercase())
        }
        if (!matched) return null
        return AiException.InvalidInput("context too large")
    }

    /** Anthropic 표준 에러 type 값 — invalid_request_error. */
    internal const val ANTHROPIC_ERROR_TYPE_INVALID_REQUEST: String = "invalid_request_error"

    /**
     * 네트워크/IO 예외 → [AiException] 매핑.
     *
     * 사양 매핑:
     * - [SocketTimeoutException] → [AiException.Network] (E-108)
     * - [IOException] (그 외) → [AiException.Network] (E-101)
     * - [SerializationException] → [AiException.ServerError(code=-1)] (E-105 응답 파싱 실패)
     *
     * [kotlinx.coroutines.CancellationException]은 본 매퍼를 통과하지 않는다 (E-106 — 호출자가 별도 처리).
     */
    fun fromException(throwable: Throwable): AiException = when (throwable) {
        is SocketTimeoutException -> AiException.Network(throwable)
        is IOException -> AiException.Network(throwable)
        is SerializationException -> AiException.ServerError(
            code = PARSE_ERROR_CODE,
            message = "response parse failed: ${throwable.message}",
        )
        else -> AiException.ServerError(
            code = PARSE_ERROR_CODE,
            message = "unexpected error: ${throwable::class.simpleName}: ${throwable.message}",
        )
    }

    /**
     * `Retry-After` 헤더 파싱.
     *
     * Anthropic은 초 단위 정수 또는 HTTP-date 형식으로 응답할 수 있다 (RFC 7231 §7.1.3).
     * v0.1은 초 단위 정수만 지원한다. 파싱 실패 시 null 반환 (호출자가 자체 백오프 사용).
     *
     * @param raw `Retry-After` 헤더 값 (없으면 null)
     * @return 파싱된 [Duration] 또는 null
     */
    private fun parseRetryAfter(raw: String?): Duration? {
        if (raw.isNullOrBlank()) return null
        return raw.trim().toLongOrNull()?.takeIf { it >= 0 }?.seconds
    }

    /**
     * 응답 파싱 실패 시 사용하는 가상 status code (E-105 매핑).
     *
     * F-001 정상 흐름 4단계의 빈 응답 검증 실패(E-110)에서도 -1을 사용 (사양: `code=-1`).
     */
    const val PARSE_ERROR_CODE: Int = -1
}
