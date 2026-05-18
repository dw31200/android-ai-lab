package com.androidailab.aisdk.session

/**
 * Session 영속화 저장소 인터페이스 (F-007).
 *
 * 사양 참조:
 * - features.md F-007 정상 흐름 (저장/복원/삭제)
 * - features.md F-007 동시성 모델 (DataStore transactional update)
 * - features.md F-007 다중 인스턴스 정책 (R-019) — 단일 프로세스 권장, last-write-wins
 * - api.md A-010 / A-011 / A-012 — Session.save / loadSession / deleteSession 의 백엔드
 * - data-model.md M-011 [SessionEntity] — 본 저장소의 페이로드
 * - error-handling.md ERR-007 (IO 영속화)
 *
 * 구현체:
 * - 본 SDK의 기본 구현은 [com.androidailab.aisdk.internal.storage.DataStoreSessionStore] (DataStore Preferences + JSON).
 * - 테스트나 호출자 측 대안 구현은 본 인터페이스를 직접 구현 (예: 인메모리, 다른 저장소).
 *
 * 정책:
 * - 모든 메서드는 `suspend` — 디스크 IO는 `Dispatchers.IO`에서 수행 (F-007 NFR).
 * - 코루틴 취소는 cooperative — 진행 중 IO는 cancellation 지점에서 [kotlinx.coroutines.CancellationException] 발생 (E-706).
 * - 동시성: 동일 sessionId에 대한 save/load/delete는 DataStore의 transactional update로 직렬화됨.
 * - 다중 인스턴스 정책 (R-019): 같은 sessionId를 가진 여러 Session 인스턴스의 save는 last-write-wins.
 *
 * 예외 매핑:
 * - 디스크 IO 실패 → [com.androidailab.aisdk.model.AiException.IOError] (E-701)
 * - 손상 데이터 / schemaVersion 불일치 → [com.androidailab.aisdk.model.AiException.IOError] (E-703)
 * - 직렬화 1MB 초과 → [com.androidailab.aisdk.model.AiException.InvalidInput] (E-704)
 * - [load]에서 미존재 → null 반환 (Result.failure로 감싸는 책임은 호출자 = AiAgentClient.loadSession)
 *
 * 내부 가시성:
 * - `internal` 인터페이스로 외부 노출 금지 (api.md "API 노출 원칙": SessionStore는 internal).
 */
internal interface SessionStore {

    /**
     * [SessionEntity]를 디스크에 저장한다 (F-007 저장 흐름).
     *
     * 사양 참조:
     * - features.md F-007 "저장 흐름" 1~5
     * - features.md F-007 E-701 (저장 실패) / E-704 (1MB 초과) / E-706 (취소)
     * - api.md A-010 (Session.save 반환은 sessionId)
     * - data-model.md M-011 직렬화 정책 (DataStore 키 `"session:{sessionId}"`)
     *
     * 동작:
     * 1. [entity]를 JSON으로 직렬화
     * 2. 1MB 초과 검증 (E-704)
     * 3. DataStore Preferences에 `"session:{entity.sessionId}"` 키로 저장
     *
     * 예외:
     * - [com.androidailab.aisdk.model.AiException.InvalidInput]("session too large to persist") — E-704 (1MB 초과)
     * - [com.androidailab.aisdk.model.AiException.IOError]("save failed: ...") — E-701 (디스크 IO 실패)
     * - [com.androidailab.aisdk.model.AiException.InvalidInput]("...") — 직렬화 실패 시 (Role.SYSTEM 검출 등)
     * - [kotlinx.coroutines.CancellationException] — E-706 (cooperative cancel)
     *
     * @param entity 저장할 [SessionEntity]
     */
    suspend fun save(entity: SessionEntity)

    /**
     * sessionId로 [SessionEntity]를 복원한다 (F-007 복원 흐름).
     *
     * 사양 참조:
     * - features.md F-007 "복원 흐름" 1~5
     * - features.md F-007 E-703 (손상된 데이터 / schemaVersion 미지원)
     * - features.md F-007 E-706 (취소)
     * - features.md F-007 R-018 (schemaVersion=1만 인정)
     * - api.md A-011 (loadSession — null 반환 시 호출자는 E-702로 변환)
     *
     * 동작:
     * 1. DataStore에서 `"session:{sessionId}"` 키 조회
     * 2. 없으면 null 반환 (호출자 = AiAgentClient.loadSession이 E-702로 변환)
     * 3. JSON 역직렬화 → [SessionEntity]
     * 4. schemaVersion 검증 (R-018) — 1이 아니면 E-703 throw
     *
     * 예외:
     * - [com.androidailab.aisdk.model.AiException.IOError]("session data corrupted") — E-703 역직렬화 실패
     * - [com.androidailab.aisdk.model.AiException.IOError]("session schema unsupported: v=...") — E-703 schemaVersion 불일치
     * - [com.androidailab.aisdk.model.AiException.IOError]("load failed: ...") — 디스크 read 실패
     * - [kotlinx.coroutines.CancellationException] — E-706
     *
     * @param sessionId 복원할 세션 식별자
     * @return [SessionEntity] 또는 미존재 시 null
     */
    suspend fun load(sessionId: String): SessionEntity?

    /**
     * sessionId의 [SessionEntity]를 디스크에서 삭제한다 (F-007 삭제 흐름).
     *
     * 사양 참조:
     * - features.md F-007 "삭제 흐름" 1~3
     * - api.md A-012 (deleteSession — 미존재 sessionId 삭제는 idempotent 성공)
     *
     * 동작:
     * 1. DataStore에서 `"session:{sessionId}"` 키 제거
     * 2. 미존재여도 성공 (idempotent)
     *
     * 예외:
     * - [com.androidailab.aisdk.model.AiException.IOError]("delete failed: ...") — 디스크 IO 실패
     * - [kotlinx.coroutines.CancellationException] — E-706
     *
     * @param sessionId 삭제할 세션 식별자
     */
    suspend fun delete(sessionId: String)
}
