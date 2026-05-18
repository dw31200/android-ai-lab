package com.androidailab.aisdk.provider

import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.ProviderId

/**
 * Provider 인스턴스 매핑/조회 (P-002).
 *
 * 사양 참조:
 * - provider-spec.md P-002 (`internal class ProviderRegistry`)
 * - features.md F-005 정상 흐름 / E-501 (등록되지 않은 Provider 선택)
 *
 * Hilt를 통해 `Set<@JvmSuppressWildcards Provider>`로 주입되는 것을 가정 (provider-spec.md).
 * F-006 진입 전이라 본 라운드에서는 [com.androidailab.aisdk.AiAgentClient.Builder]가 직접
 * 인스턴스화하여 client에 전달하거나, 단위 테스트에서 직접 주입한다.
 *
 * 동시성: 생성 후 immutable한 `Map<ProviderId, Provider>` 만 유지하므로 thread-safe.
 *
 * @param providers 등록할 Provider 인스턴스 집합. 동일 [ProviderId]를 가진 Provider가
 *                  둘 이상이면 마지막 등록이 이긴다 (Map.put 의 last-write-wins).
 */
public class ProviderRegistry internal constructor(
    providers: Set<Provider>,
) {

    private val byId: Map<ProviderId, Provider> = providers.associateBy { it.id }

    /**
     * 식별자로 Provider 조회 (P-002).
     *
     * 사양: features.md F-005 E-501 — 등록되지 않은 Provider 선택 시
     * `AiException.Configuration("unknown provider")` throw.
     *
     * @throws AiException.Configuration 등록되지 않은 [id]가 전달된 경우 (E-501)
     */
    public fun get(id: ProviderId): Provider {
        return byId[id] ?: throw AiException.Configuration(
            "unknown provider: $id",
        )
    }

    /**
     * 등록된 Provider 목록 조회 (P-002).
     *
     * 반환은 호출 시점의 immutable 스냅샷. Registry는 생성 후 변경되지 않으므로
     * 호출자가 결과를 수정해도 Registry 내부 상태에 영향 없음.
     */
    public fun list(): List<Provider> = byId.values.toList()

    /**
     * 식별자로 Capabilities 조회 (provider-spec.md P-002 helper).
     *
     * R-010에 따라 [Capabilities]는 Provider 인스턴스에서만 조회한다.
     *
     * @throws AiException.Configuration [id]가 등록되지 않은 경우 (E-501)
     */
    public fun capabilities(id: ProviderId): Capabilities = get(id).capabilities

    /**
     * 식별자가 Registry에 존재하는지 확인.
     *
     * `get(id)`을 호출하면 미등록 시 throw하므로, 사전 검증이 필요한 경로에서 사용.
     */
    public fun contains(id: ProviderId): Boolean = id in byId
}
