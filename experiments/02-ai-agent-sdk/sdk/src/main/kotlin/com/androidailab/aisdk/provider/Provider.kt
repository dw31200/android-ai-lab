package com.androidailab.aisdk.provider

import com.androidailab.aisdk.model.AiRequest
import com.androidailab.aisdk.model.AiResponse
import com.androidailab.aisdk.model.AiStreamEvent
import com.androidailab.aisdk.model.ProviderId
import kotlinx.coroutines.flow.Flow

/**
 * LLM Provider 추상화 인터페이스 (P-001).
 *
 * 사양 참조:
 * - provider-spec.md P-001 (인터페이스 정의)
 * - features.md F-005 (Provider 선택/교체)
 * - R-010: Capabilities는 Provider 측 단일 source of truth.
 *
 * 호출자는 본 인터페이스를 직접 사용하지 않는다 (provider-spec.md "호출자는 Provider
 * 인터페이스를 직접 사용하지 않는다"). [com.androidailab.aisdk.AiAgentClient] 가
 * [ProviderRegistry]를 통해 등록된 Provider를 내부에서 호출한다.
 *
 * 본 라운드(F-005)에서는 인터페이스/Capabilities/등록 골격만 도입한다.
 * `complete()` / `stream()` 본체는 F-001/F-003 라운드에서 OkHttp + Mapper로 채운다.
 *
 * 구현체 위치: `provider/{providerName}/{ProviderName}Provider.kt` (provider-spec.md
 * "신규 Provider 추가 절차 R-015").
 */
public interface Provider {

    /**
     * Provider 식별자 (M-010). [ProviderRegistry.get] 키로 사용된다.
     */
    public val id: ProviderId

    /**
     * Provider 능력 명세 (R-010 단일 source of truth).
     */
    public val capabilities: Capabilities

    /**
     * 단발 질의 (F-001/F-002). suspend 함수.
     *
     * 사양 참조:
     * - provider-spec.md P-001
     * - features.md F-001/F-002 정상/예외 흐름
     *
     * 구현체는 다음을 보장해야 한다 (후속 F-001/F-002 라운드 책임):
     * - 코루틴 취소 시 OkHttp call cancel 연동 (E-106)
     * - 외부 예외(IOException, HttpException 등)를 [com.androidailab.aisdk.model.AiException]
     *   으로 변환 (작업 원칙 6: "에러는 sealed class")
     * - [config.apiKey]는 HTTPS 헤더로만 전송, 로그/평문 노출 금지 (D-003)
     */
    public suspend fun complete(request: AiRequest, config: ProviderConfig): AiResponse

    /**
     * 스트리밍 질의 (F-003). cold [Flow] 반환.
     *
     * 사양 참조:
     * - provider-spec.md P-001
     * - features.md F-003 정상/예외 흐름 (E-301~E-303)
     * - data-model.md M-006 ([AiStreamEvent] 방출 순서: Delta 0+ → Done|Error 1)
     *
     * 구현체 책임 (후속 F-003 라운드):
     * - SSE 연결 cancel을 코루틴 취소에 연동 (E-302)
     * - [Capabilities.supportsStream]이 false인 Provider는 호출 즉시 throw (E-303)
     */
    public fun stream(request: AiRequest, config: ProviderConfig): Flow<AiStreamEvent>
}
