package com.androidailab.aisdk.model

import kotlin.time.Duration

/**
 * SDK가 호출자에게 노출하는 sealed 예외 계층 (M-005).
 *
 * 내부 IOException/JsonParseException/HttpException 등은 모두 본 sealed class의
 * 하위 타입으로 변환되어 호출자에게 전달되며, RuntimeException이 그대로 throw 되지 않는다
 * (작업 원칙 6: "에러는 sealed class").
 *
 * ERR ↔ Variant 매핑 (error-handling.md 참조):
 * - ERR-001 → [Network]
 * - ERR-002 → [RateLimit]
 * - ERR-003 → [Authentication]
 * - ERR-004 → [Configuration]
 * - ERR-005 → [InvalidInput]
 * - ERR-006 → [ServerError]
 * - ERR-007 → [IOError]  (라운드 2 신규, F-007 영속화)
 *
 * `CancellationException`은 본 계층으로 변환되지 않고 그대로 전파된다 (E-106/E-302/E-706).
 */
public sealed class AiException(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * 네트워크 오류 (ERR-001).
     *
     * 발생 조건: 인터넷 연결 없음, DNS 실패, SocketTimeoutException, 일반 IOException.
     * E-101 / E-108 / E-206(일부) / E-301 매핑.
     */
    public class Network(cause: Throwable) : AiException(cause = cause)

    /**
     * 레이트 리밋 (ERR-002, HTTP 429).
     *
     * @property retryAfter `Retry-After` 헤더에서 파싱한 대기 시간. 미제공 시 null.
     * E-103 매핑.
     */
    public class RateLimit(public val retryAfter: Duration?) : AiException()

    /**
     * 인증 실패 (ERR-003, HTTP 401 또는 잘못된 API 키).
     *
     * E-102 매핑.
     */
    public class Authentication : AiException()

    /**
     * 설정 오류 (ERR-004).
     *
     * Builder 잘못된 인자, Provider 미지원 기능, client closed 등.
     * E-001 / E-002 / E-003 / E-109 / E-205 / E-303 / E-402 / E-501 / E-502 / E-602 / E-705 매핑.
     */
    public class Configuration(message: String) : AiException(message)

    /**
     * 입력 검증 실패 (ERR-005).
     *
     * prompt 빈 값, 이미지 5MB 초과, 지원하지 않는 mime, 컨텍스트 초과,
     * session not found, session too large 등.
     * E-107 / E-201~E-204 / E-206(URL invalid) / E-207 / E-401 / E-702 / E-704 매핑.
     */
    public class InvalidInput(message: String) : AiException(message)

    /**
     * 서버 오류 (ERR-006, HTTP 5xx 또는 응답 파싱 실패).
     *
     * @property code HTTP status code 또는 SDK 내부 코드(파싱 실패 시 -1 등).
     * E-104 / E-105 / E-110 매핑.
     */
    public class ServerError(
        public val code: Int,
        message: String? = null,
    ) : AiException(message)

    /**
     * 영속화/디스크 IO 오류 (ERR-007, 라운드 2 신규).
     *
     * 디스크 가득, IO 오류, 손상된 데이터, schemaVersion 미지원 등.
     * E-701 / E-703 매핑.
     */
    public class IOError(
        message: String,
        cause: Throwable? = null,
    ) : AiException(message, cause)
}
