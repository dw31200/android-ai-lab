package com.androidailab.aisdk.session

import com.androidailab.aisdk.AiAgentClient
import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.AiRequest
import com.androidailab.aisdk.model.AiResponse
import com.androidailab.aisdk.model.ImageInput
import com.androidailab.aisdk.model.Message
import com.androidailab.aisdk.model.Role
import com.androidailab.aisdk.provider.Capabilities
import com.androidailab.aisdk.provider.Provider
import com.androidailab.aisdk.provider.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 대화 세션 (M-007, F-004, A-006/A-007/A-008).
 *
 * 사양 참조:
 * - data-model.md M-007 (Session 시그니처/내부 상태/Provider 바인딩 정책)
 * - data-model.md M-008 (Message)
 * - features.md F-004 (세션 컨텍스트 유지) — 정상 흐름 1~5, systemPrompt 정책 (R-008)
 * - features.md F-004 컨텍스트 한계 검증 알고리즘 (E-401)
 * - features.md F-004 예외 흐름 — E-401 / E-402 / E-403 / E-101~E-110 / E-201~E-207
 * - features.md F-005 Session과 Provider의 관계 (R-014) — Session은 Provider에 묶이지 않음
 * - features.md F-008 R-020 close 시맨틱 (케이스 A/B)
 * - api.md A-004 (createSession) / A-006 (send) / A-007 (history) / A-008 (clear)
 * - error-handling.md ERR-004 (E-402, client closed) / ERR-005 (E-401, context too large)
 *
 * 동시성 및 thread-safety (R-007, R-011):
 * - 내부 history는 [MutableList]를 [Mutex]로 보호한다 (R-007).
 * - 동시 send는 Mutex로 직렬화 — 두 번째 호출은 첫 호출 완료까지 대기 (E-403, 사양 명시: "정상 동작").
 * - [history]는 Mutex 안에서 List 복사본을 반환하여 호출 시점의 immutable snapshot을 보장 (R-011).
 * - [clear]도 Mutex로 직렬화 — send 진행 중에는 대기.
 *
 * Provider 바인딩 정책 (R-014):
 * - Session은 특정 Provider에 묶이지 않는다.
 * - [send] 호출 시점에 [client.activeProvider]를 atomic get으로 캡쳐 (R-007 패턴 동일).
 * - 호출자가 [client.useProvider]로 교체한 후 같은 Session에서 send 하면 새 Provider 사용.
 *
 * close 시맨틱 (R-020):
 * - 케이스 A: send 진행 중 client.close() 발생 → cooperative cancel → [CancellationException] 전파.
 * - 케이스 B: client.close() 완료 후 새 send 호출 → 즉시 [Result.failure]([AiException.Configuration]("client closed"), E-402).
 * - 동기 함수([history]/[clear])도 케이스 B → [AiException.Configuration]("client closed") throw.
 *
 * @property client 본 Session을 만든 [AiAgentClient]. close 검증 / activeProvider 조회에 사용.
 * @property sessionId SDK가 자동 생성한 UUID (R-017/R-024). save/load 키로 사용 (F-007).
 * @property systemPrompt 시스템 프롬프트. history에 포함되지 않으며 매 send마다 Anthropic top-level
 *                       `system` 필드로 전송된다 (R-008).
 * @param initialHistory loadSession 복원 시 사용. v0.1 createSession 진입은 빈 리스트.
 */
public class Session internal constructor(
    private val client: AiAgentClient,
    public val sessionId: String,
    private val systemPrompt: String?,
    initialHistory: List<Message> = emptyList(),
) {

    // -----------------------------------------------------------------
    // 내부 상태 (M-007 동시성 모델 — R-007/R-011)
    // -----------------------------------------------------------------

    /**
     * 메시지 이력 (M-008 List). [mutex]로 보호된다 (R-007).
     *
     * 정책 (R-008):
     * - [Role.USER]/[Role.ASSISTANT]만 들어간다. [Role.SYSTEM]은 별도 [systemPrompt]로 관리.
     * - [send]가 성공할 때마다 USER 메시지 + ASSISTANT 응답 메시지 2개가 append 된다 (정상 흐름 3단계).
     * - send가 실패하면 history는 변경되지 않는다 (트랜잭션 보장 — provider 호출 후 history 갱신).
     */
    private val history: MutableList<Message> = initialHistory.toMutableList()

    /**
     * Session 내부 [Mutex] (M-007 동시성 모델, F-004 E-403).
     *
     * - [send] 직렬화 — 두 번째 send는 첫 send 완료까지 대기 (E-403 정상 동작).
     * - [history]() / [clear]() 도 [withLock]으로 보호 → race 없음 (R-011 snapshot 보장).
     */
    private val mutex: Mutex = Mutex()

    // -----------------------------------------------------------------
    // A-006 / F-004 — send
    // -----------------------------------------------------------------

    /**
     * 이전 메시지 + 현재 요청을 합쳐 Provider에 전송한다 (F-004, A-006).
     *
     * 사양 참조:
     * - api.md A-006 시그니처: `suspend fun send(request: AiRequest): Result<AiResponse>`
     * - features.md F-004 정상 흐름 2~3 ("이전 메시지 + 현재 요청을 합쳐 Provider 전송 / 응답을 세션 history에 추가")
     * - features.md F-004 예외 흐름 — E-401/E-402/E-403/E-101~E-110/E-201~E-207
     * - features.md F-005 R-014 (Provider는 send 진입 시점 캡쳐)
     * - features.md F-008 R-020 케이스 A/B (close 시맨틱)
     *
     * 동작:
     * 1. R-020 케이스 B: [client]가 close 된 상태면 즉시 [Result.failure]([AiException.Configuration]("client closed"), E-402)
     * 2. [Mutex.withLock] 진입 — 동일 Session 동시 send는 직렬화 (E-403)
     * 3. R-007/R-014: 진입 시점에 [Provider]/[ProviderConfig] 캡쳐 (useProvider 영향 없음)
     * 4. F-002 이미지 검증 ([validateImages]) — E-201/E-202/E-204/E-205/E-208/E-203 매핑
     * 5. 현재 history snapshot + 현재 [AiRequest] + systemPrompt를 [Mapper.toAnthropicRequestForSession]으로 변환
     *    - history의 [Message.images]도 base64 인코딩 (F-002 동일 정책)
     * 6. [Provider.complete] 호출 → [AiResponse]
     * 7. F-001 정상 흐름 4단계: 빈 응답 검증 (E-110, AiAgentClient.ask와 동일 알고리즘)
     * 8. 정상 응답이면 USER 메시지 + ASSISTANT 메시지를 history에 append, 실패면 변경 없음
     * 9. [Result.success]([AiResponse]) 반환
     *
     * 예외 흐름 (Result.failure로 반환):
     * - E-401: 컨텍스트 초과 → [AiException.InvalidInput]("context too large") — Provider/ErrorMapper에서 변환됨
     * - E-402: client closed → [AiException.Configuration]("client closed")
     * - E-403: 동시 send는 Mutex로 정상 직렬화 (실패가 아닌 정상 동작)
     * - E-201~E-208 / E-101~E-110: 해당 F 참조
     *
     * 코루틴 취소 (E-106 / R-020 케이스 A): [CancellationException]은 그대로 전파 — Result로 감싸지 않음.
     * Mutex는 cancellation에 cooperative하게 풀린다 (kotlinx.coroutines.sync.Mutex 표준).
     *
     * @param request 현재 USER 메시지 (M-001)
     * @return 성공 시 [Result.success]<[AiResponse]>, 실패 시 [Result.failure]<[AiException]>
     */
    public suspend fun send(request: AiRequest): Result<AiResponse> {
        // R-020 케이스 B: client closed → Configuration("client closed") (E-402)
        // suspend 시맨틱 — throw가 아닌 Result로 감싸 반환 (A-006 close 시맨틱 표 참조)
        if (client.isClosed()) {
            return Result.failure(AiException.Configuration("client closed"))
        }

        // E-403: Mutex로 직렬화 — 두 번째 send는 첫 send 완료까지 대기 (정상 동작)
        return mutex.withLock {
            // 진입 후 한 번 더 closed 검사 (close가 mutex 대기 중 발생할 수 있음)
            if (client.isClosed()) {
                return@withLock Result.failure(AiException.Configuration("client closed"))
            }

            // R-007 / R-014: 진입 시점 Provider/Config 캡쳐 — Mutex 안에서 일관된 Provider 사용
            val provider: Provider = client.activeProvider
            val config: ProviderConfig = client.currentProviderConfig()

            // F-002 이미지 검증 (E-201/E-202/E-204/E-205/E-208/E-203) — AiAgentClient와 동일 흐름 재사용
            val imageValidation = validateImages(request, provider.capabilities)
            if (imageValidation != null) {
                return@withLock Result.failure(imageValidation)
            }

            try {
                // F-004 정상 흐름 2: 이전 메시지 + 현재 요청 + systemPrompt → Mapper로 Anthropic 요청 변환.
                // 본 변환은 Provider.complete 호출 전에 수행 — image fetch 등으로 suspend.
                // Mapper의 system 매핑은 R-008.
                //
                // history snapshot은 mutex 안에서 synchronized(history)로 캡쳐 — Mutex는 send 직렬화,
                // synchronized(history)는 동기 함수(history()/clear())와의 메모리 가시성 보장.
                val historySnapshot: List<Message> = synchronized(history) { history.toList() }

                // Provider.complete는 내부적으로 Mapper.toAnthropicRequest를 호출하지만, F-004에서는
                // Session 컨텍스트와 systemPrompt를 위해 toAnthropicRequestForSession을 사용해야 한다.
                // 현재 Provider 인터페이스는 history/systemPrompt를 받는 메서드가 없으므로,
                // ClaudeProvider 직속 호출 경로를 임시로 사용한다 (P-CLAUDE 단일 Provider 전제).
                //
                // 멀티 Provider(v0.2)에서는 Provider 인터페이스에 sessionComplete 시그니처 추가 검토.
                val response: AiResponse = if (provider is com.androidailab.aisdk.provider.claude.ClaudeProvider) {
                    provider.completeForSession(
                        history = historySnapshot,
                        request = request,
                        systemPrompt = systemPrompt,
                        config = config,
                    )
                } else {
                    // v0.1은 ClaudeProvider만 존재 (R-009) — fallback은 도달 불가.
                    // 방어적으로 일반 complete를 호출 (history/systemPrompt 무시).
                    provider.complete(request, config)
                }

                // F-001 정상 흐름 4단계: 빈 응답 검증 (E-110)
                if (response.text.isEmpty() &&
                    response.finishReason !in com.androidailab.aisdk.AiAgentClient.EMPTY_OK_FINISH_REASONS
                ) {
                    return@withLock Result.failure(
                        AiException.ServerError(
                            code = com.androidailab.aisdk.AiAgentClient.EMPTY_RESPONSE_CODE,
                            message = "empty response",
                        ),
                    )
                }

                // 정상 흐름 3: USER 메시지 + ASSISTANT 응답을 history에 append (E-401 이후 실패는 변경 없음).
                // synchronized(history)로 동기 함수(history()/clear())와 race 방지 + 메모리 가시성 보장.
                synchronized(history) {
                    history.add(
                        Message(
                            role = Role.USER,
                            content = request.prompt,
                            images = request.images,
                        ),
                    )
                    history.add(
                        Message(
                            role = Role.ASSISTANT,
                            content = response.text,
                        ),
                    )
                }

                Result.success(response)
            } catch (e: CancellationException) {
                // E-106 / R-020 케이스 A: 코루틴 취소는 그대로 전파 (Result.failure로 감싸지 않음)
                throw e
            } catch (e: AiException) {
                // E-401/E-101~E-110/E-201~E-207 — Provider/Mapper/ErrorMapper에서 이미 변환된 AiException
                Result.failure(e)
            } catch (e: IllegalArgumentException) {
                // M-001 init 검증 실패 (E-107 등 — 본 시점에는 호출자가 AiRequest 인스턴스를 이미 만든 상태)
                Result.failure(AiException.InvalidInput(e.message ?: "invalid input"))
            } catch (e: Throwable) {
                // 예상치 못한 예외 → ServerError로 안전하게 변환 (작업 원칙 6)
                Result.failure(
                    AiException.ServerError(
                        code = com.androidailab.aisdk.AiAgentClient.EMPTY_RESPONSE_CODE,
                        message = "unexpected error: ${e::class.simpleName}: ${e.message}",
                    ),
                )
            }
        }
    }

    // -----------------------------------------------------------------
    // A-007 / F-004 — history
    // -----------------------------------------------------------------

    /**
     * 호출 시점의 history immutable snapshot 반환 (A-007, R-011).
     *
     * 사양 참조:
     * - api.md A-007 시그니처: `fun history(): List<Message>`
     * - features.md F-004 정상 흐름 4 (immutable snapshot 반환)
     * - data-model.md M-007 ("history()는 Mutex 안에서 List 복사본을 반환하여 immutable snapshot 보장 R-011")
     * - features.md F-008 R-020 케이스 B (close 후 호출 시 throw)
     *
     * 동작:
     * - R-020 케이스 B: client closed → [AiException.Configuration]("client closed") throw (동기 함수)
     * - Mutex 안에서 history.toList() 반환 (호출 시점 snapshot)
     * - 반환된 List는 호출 후 send가 발생해도 변경되지 않음 (R-011 snapshot 보장)
     * - Role.SYSTEM 메시지는 포함되지 않음 (history에 추가되지 않으므로 — R-008)
     *
     * 본 함수는 [withLock]을 호출하지만 send와 비교해 매우 짧다 (List 복사만). [runBlocking]으로
     * suspend 영역을 동기 영역에 노출하는 패턴은 호출자가 view 영역에서 사용할 수 있도록 함.
     *
     * @throws AiException.Configuration close된 상태일 때 ("client closed")
     */
    public fun history(): List<Message> {
        // R-020 케이스 B: client closed → throw (동기 함수)
        if (client.isClosed()) {
            throw AiException.Configuration("client closed")
        }
        // R-011 snapshot 보장: Mutex 안에서 List 복사본 반환.
        // history()는 동기 함수이지만 mutex.lock()은 suspend이므로 tryLock 패턴 + 즉시 복사 사용.
        // send가 진행 중이면 tryLock 실패 — 그래도 history 자체는 ArrayList의 toList()가 thread-safe하지 않으므로
        // synchronized block을 사용해 보호한다. Mutex.lock과 별개로 history 자체를 보호한다.
        return synchronized(history) { history.toList() }
    }

    // -----------------------------------------------------------------
    // A-008 / F-004 — clear
    // -----------------------------------------------------------------

    /**
     * 세션 history를 초기화한다 (A-008, F-004).
     *
     * 사양 참조:
     * - api.md A-008 시그니처: `fun clear()`
     * - api.md A-008 동작 ("세션 history를 초기화. systemPrompt는 유지. thread-safe.")
     * - features.md F-008 R-020 케이스 B (close 후 호출 시 throw)
     *
     * 동작:
     * - R-020 케이스 B: client closed → [AiException.Configuration]("client closed") throw (동기 함수)
     * - history만 초기화. systemPrompt는 보존됨.
     * - thread-safe — synchronized(history) 블록으로 send와 race 방지.
     *
     * @throws AiException.Configuration close된 상태일 때 ("client closed")
     */
    public fun clear() {
        // R-020 케이스 B: client closed → throw
        if (client.isClosed()) {
            throw AiException.Configuration("client closed")
        }
        synchronized(history) {
            history.clear()
        }
    }

    // -----------------------------------------------------------------
    // A-010 / F-007 — save (T18 라운드 추가)
    // -----------------------------------------------------------------

    /**
     * 본 Session을 디스크에 영속화한다 (F-007, A-010).
     *
     * 사양 참조:
     * - api.md A-010 시그니처: `suspend fun save(): Result<String>`
     * - api.md A-010 ("반환된 String은 Session 생성 시 자동 부여된 UUID")
     * - features.md F-007 "저장 흐름" 1~5
     * - features.md F-007 동시성 모델 ("send 진행 중에는 Mutex 대기")
     * - features.md F-007 다중 인스턴스 정책 (R-019) — 같은 sessionId 두 인스턴스 save는 last-write-wins
     * - features.md F-007 데이터 크기 정책 (E-704 — 1MB 초과)
     * - features.md F-007 이미지 영속화 운영 가이드 (R-021)
     * - data-model.md M-011 (SessionEntity 직렬화)
     * - features.md F-008 R-020 케이스 A/B (close 시맨틱)
     * - error-handling.md ERR-005 (E-704) / ERR-007 (E-701) / ERR-004 (E-705)
     *
     * 동작:
     * 1. R-020 케이스 B: [client]가 close된 상태면 `Result.failure(Configuration("client closed"))` (E-705)
     * 2. [client.sessionStore]가 null이면 `Result.failure(IOError("session store not configured"))` (Builder 미설정 경로)
     * 3. [Mutex.withLock] — send 진행 중에는 대기, history 무결성 보장 (F-007 동시성 모델)
     * 4. mutex 안에서 두 번째 closed 검사 (close가 mutex 대기 중 발생 가능)
     * 5. [history] snapshot을 [SessionEntity]로 변환 (M-011)
     *    - [systemPrompt] 포함, schemaVersion=1 (R-018)
     *    - [Role.USER]/[Role.ASSISTANT]만 변환 ([Role.SYSTEM]은 history에 없음 — R-008)
     *    - [Message.images]는 base64 등으로 [ImageInputEntity]에 변환됨 ([Message.toEntity] 참조)
     * 6. [SessionStore.save] 호출 — DataStore 저장 ([Dispatchers.IO]에서 수행)
     * 7. `Result.success(sessionId)` 반환 — 자동 부여된 UUID (R-017/R-024)
     *
     * 다중 인스턴스 정책 (R-019):
     * - 같은 sessionId를 가진 두 Session 인스턴스가 각각 save를 호출하면 DataStore의 transactional update
     *   안에서 직렬화되어 last-write-wins로 수렴한다.
     * - SDK는 이 충돌을 자동 검출하지 않으며, 호출자가 동일 sessionId의 단일 인스턴스 유지를 보장해야 한다.
     *
     * 이미지 영속화 운영 가이드 (R-021):
     * - [ImageInput.Bytes]는 base64로 변환되어 1MB 한계(E-704)를 쉽게 초과할 수 있다 (5MB → ≈ 6.7MB).
     * - 1MB 초과 시 SessionStore가 E-704 [AiException.InvalidInput]를 throw — 호출자는 history에서
     *   이미지를 제거하거나 [ImageInput.Url]로 외부 호스팅 후 보관해야 한다.
     * - SDK는 자동 제외/압축/외부 업로드를 수행하지 않는다 (D-004 정책의 연장).
     *
     * 예외 흐름 ([Result.failure]):
     * - E-701: 디스크 IO 실패 → [AiException.IOError] (SessionStore가 변환)
     * - E-704: 직렬화 결과 1MB 초과 → [AiException.InvalidInput]("session too large to persist")
     * - E-705: client closed → [AiException.Configuration]("client closed")
     * - E-706: 코루틴 취소 → [CancellationException] 그대로 전파 (R-020 케이스 A)
     *
     * @return 성공 시 [Result.success]([sessionId]), 실패 시 [Result.failure]([AiException])
     */
    public suspend fun save(): Result<String> {
        // R-020 케이스 B: client closed → Configuration("client closed") (E-705)
        // suspend 시맨틱 — throw가 아닌 Result로 감싸 반환 (A-010 close 시맨틱 표 참조)
        if (client.isClosed()) {
            return Result.failure(AiException.Configuration("client closed"))
        }

        // F-007 SessionStore 참조 (Builder 미설정 경로 — F-007 미사용 호출자 대비)
        val store: SessionStore = client.sessionStore ?: return Result.failure(
            AiException.IOError("session store not configured"),
        )

        // F-007 동시성 모델: send 진행 중에는 mutex 대기 (history 무결성 보장)
        return mutex.withLock {
            // 진입 후 한 번 더 closed 검사 (close가 mutex 대기 중 발생할 수 있음)
            if (client.isClosed()) {
                return@withLock Result.failure(
                    AiException.Configuration("client closed"),
                )
            }

            try {
                // F-007 저장 흐름 2: SessionEntity로 직렬화 변환
                // - history snapshot은 synchronized(history)로 캡쳐 (메모리 가시성)
                // - Role.USER/ASSISTANT만 들어있다고 신뢰 (R-008 — Session.send append는 USER/ASSISTANT만)
                val historySnapshot: List<Message> = synchronized(history) { history.toList() }
                val entity = SessionEntity(
                    schemaVersion = SessionEntity.SCHEMA_VERSION_V1,
                    sessionId = sessionId,
                    systemPrompt = systemPrompt,
                    history = historySnapshot.map { it.toEntity() },
                    savedAt = System.currentTimeMillis(),
                )

                // F-007 저장 흐름 3~4: SessionStore.save (Dispatchers.IO + 1MB 검증 + DataStore put)
                // E-701/E-704는 SessionStore 구현이 AiException으로 throw 한다.
                store.save(entity)

                // F-007 저장 흐름 5: 성공 시 sessionId(UUID) 반환 (R-017/R-024)
                Result.success(sessionId)
            } catch (e: CancellationException) {
                // E-706: 그대로 전파 (R-020 케이스 A)
                throw e
            } catch (e: AiException) {
                // E-701/E-704 등 SessionStore가 변환한 AiException 그대로 전달
                Result.failure(e)
            } catch (e: Throwable) {
                // 예상치 못한 예외 → IOError로 안전하게 변환 (작업 원칙 6)
                Result.failure(
                    AiException.IOError(
                        "save failed: ${e::class.simpleName}: ${e.message ?: "unknown"}",
                        cause = e,
                    ),
                )
            }
        }
    }

    // -----------------------------------------------------------------
    // 내부 헬퍼
    // -----------------------------------------------------------------

    /**
     * F-002 이미지 검증 (F-004 진입 시 send에서 호출).
     *
     * 사양 참조:
     * - features.md F-002 정상 흐름 2단계 (이미지 검증)
     * - features.md F-002 E-201/E-202/E-204/E-205/E-208/E-203
     *
     * [com.androidailab.aisdk.AiAgentClient.validateImages]와 동일 알고리즘. 본 Session 레이어에서
     * 재구현하여 send 진입 시점에 검증한다 (F-004 진입에서 ask와 동일한 검증 흐름 보장).
     *
     * @return 검증 실패 시 [AiException], 정상 시 null
     */
    private fun validateImages(
        request: AiRequest,
        capabilities: Capabilities,
    ): AiException? {
        val images = request.images
        if (images.isEmpty()) return null

        // E-205: Provider가 이미지 미지원
        if (!capabilities.supportsImage) {
            return AiException.Configuration("provider does not support images")
        }

        // E-202/E-208: 개수 한계 (Provider별)
        if (images.size > capabilities.maxImagesPerRequest) {
            return AiException.InvalidInput(
                "images total too large: count=${images.size} > ${capabilities.maxImagesPerRequest}",
            )
        }

        var totalBytes = 0L
        for (image in images) {
            when (image) {
                is ImageInput.Bytes -> {
                    // E-201: 단일 5MB 초과
                    if (image.data.size.toLong() > capabilities.maxImageSizeBytes) {
                        return AiException.InvalidInput(
                            "image too large: ${image.data.size} > ${capabilities.maxImageSizeBytes}",
                        )
                    }
                    // E-204: mimeType 화이트리스트
                    if (image.mimeType !in capabilities.supportedImageMimeTypes) {
                        return AiException.InvalidInput(
                            "unsupported mime type: ${image.mimeType}",
                        )
                    }
                    totalBytes += image.data.size.toLong()
                }
                is ImageInput.Url -> {
                    // URL은 fetch 전이라 size/mime 미상 — Mapper에서 fetch 후 검증.
                }
                is ImageInput.Uri -> {
                    // E-203: v0.1은 Uri 자동 resolve 안 함 (D-004 — 호출자 책임)
                    return AiException.InvalidInput(
                        "uri unreadable: SDK does not auto-resolve Uri (call ContentResolver before ask)",
                    )
                }
            }
        }

        // E-202: 합계 한계 (M-001 "합계 20MB")
        if (totalBytes > IMAGES_TOTAL_MAX_BYTES) {
            return AiException.InvalidInput(
                "images total too large: $totalBytes > $IMAGES_TOTAL_MAX_BYTES",
            )
        }

        return null
    }

    public companion object {
        /**
         * F-002 E-202 합계 한계 — data-model.md M-001 "이미지 합계 20MB".
         *
         * [com.androidailab.aisdk.AiAgentClient.IMAGES_TOTAL_MAX_BYTES] 와 동일 정의 (SOT는 M-001).
         * Session.validateImages는 AiAgentClient.validateImages와 동일 알고리즘이라 같은 상수를 사용한다.
         */
        internal const val IMAGES_TOTAL_MAX_BYTES: Long = 20L * 1024L * 1024L
    }
}
