package com.androidailab.aisdk

import android.content.Context
import com.androidailab.aisdk.client.Builder
import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.AiRequest
import com.androidailab.aisdk.model.AiResponse
import com.androidailab.aisdk.model.AiStreamEvent
import com.androidailab.aisdk.model.FinishReason
import com.androidailab.aisdk.model.ImageInput
import com.androidailab.aisdk.model.ProviderId
import com.androidailab.aisdk.provider.Capabilities
import com.androidailab.aisdk.provider.Provider
import com.androidailab.aisdk.provider.ProviderConfig
import com.androidailab.aisdk.provider.ProviderRegistry
import com.androidailab.aisdk.session.Session
import com.androidailab.aisdk.session.SessionEntity
import com.androidailab.aisdk.session.SessionStore
import com.androidailab.aisdk.session.toMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * AI Agent SDK의 공개 진입점 (F-000, A-001~A-006, F-005, F-008).
 *
 * Builder 패턴으로 초기화한다 (F-000 정상 흐름 1~3, [Builder] 참조).
 * 본 라운드(F-007까지)에 다음이 구현됨:
 * - F-000: 초기화/검증
 * - F-001/F-002: `ask` 텍스트/멀티모달 단발 질의
 * - F-003: `askStream` 스트리밍
 * - F-004: `createSession` + [Session] 본체 (T16 라운드 추가)
 * - F-005: Provider 선택/교체 (`useProvider`, AtomicReference 기반)
 * - F-006: Hilt 모듈
 * - F-007: `loadSession` / `deleteSession` + [Session.save] (T18 라운드 추가)
 * - F-008: close
 *
 * 생성자는 internal — 호출자는 항상 [Builder]를 거쳐야 한다 (사양상 build() 검증을
 * 우회하는 경로를 막기 위함).
 *
 * thread-safety:
 * - 인스턴스는 thread-safe (NFR, overview.md "동시성")
 * - [providerIdRef] (M-010, F-005 R-007)는 [AtomicReference]로 보관되어 [useProvider]의
 *   atomic set과 ask/askStream 진입 시 atomic get이 race 없이 동작한다.
 * - 진행 중 요청은 호출 시점에 자기 스택에 [Provider] 인스턴스를 캡쳐하므로 교체 영향 없음 (R-007).
 *
 * 라이프사이클(F-008, A-009):
 * - [close]는 진행 중 호출 취소 + 새 호출 거절. idempotent + thread-safe.
 * - close 시맨틱은 R-020 케이스 A/B를 따른다 (A-009 close 시맨틱 표 참조).
 *
 * @property context Application context. SDK는 호출자 Activity 등을 보관하지 않는다
 *                   (메모리 누수 방지). [Builder]가 `context.applicationContext`를 추출해 전달.
 * @property apiKey 활성 Provider API 키. 메모리에서만 보관 (D-003 NFR).
 *                  디스크/로그에 평문 노출 금지.
 * @property modelId 활성 모델 식별자 (예: "claude-opus-4-7").
 * @property timeout 단일 요청 타임아웃.
 *
 * @see Builder
 * @see ProviderRegistry
 */
public class AiAgentClient internal constructor(
    internal val context: Context,
    internal val apiKey: String,
    initialProviderId: ProviderId,
    internal val modelId: String,
    internal val timeout: Duration,
    internal val providerRegistry: ProviderRegistry,
    internal val sessionStore: SessionStore? = null,
) {

    // -----------------------------------------------------------------
    // F-005 / A-005 — 활성 Provider 식별자 (R-007 AtomicReference)
    // -----------------------------------------------------------------

    /**
     * 활성 Provider 식별자 (M-010, F-005 R-007).
     *
     * `AtomicReference` 기반 — [useProvider]가 atomic set, ask/askStream/session.send 진입 시
     * atomic get으로 그 시점의 Provider를 사용 (R-007).
     *
     * 진행 중 요청은 호출 시점에 이미 Provider 인스턴스를 자기 스택에 캡쳐했으므로 교체 영향 없음.
     */
    private val providerIdRef: AtomicReference<ProviderId> =
        AtomicReference(initialProviderId)

    /**
     * 현재 활성 Provider 식별자 (M-010).
     *
     * F-005 R-007: ask/askStream/session.send 진입 시 본 값을 atomic get으로 조회.
     */
    internal val activeProviderId: ProviderId
        get() = providerIdRef.get()

    /**
     * 현재 활성 Provider 인스턴스 ([ProviderRegistry]에서 조회).
     *
     * 후속 라운드에서 ask/askStream/session.send가 호출 진입 시점에 본 값을 캡쳐한다 (R-007).
     */
    internal val activeProvider: Provider
        get() = providerRegistry.get(activeProviderId)

    /**
     * 현재 활성 Provider 호출 설정 ([ProviderConfig], P-001).
     *
     * Provider 호출 시 매번 새로 만들어 전달 (Provider 구현체가 캐싱하지 않도록).
     */
    internal fun currentProviderConfig(): ProviderConfig = ProviderConfig(
        apiKey = apiKey,
        modelId = modelId,
        timeout = timeout,
    )

    // -----------------------------------------------------------------
    // F-008 / A-009 — 라이프사이클 상태
    // -----------------------------------------------------------------

    /**
     * 닫힘 상태 플래그 (F-008, A-009).
     *
     * `compareAndSet(false, true)`로 첫 호출만 실제 정리를 수행하여 close idempotent 보장.
     * 외부에서 직접 mutate 금지 (close()만 사용).
     */
    private val closed: AtomicBoolean = AtomicBoolean(false)

    /**
     * SDK 내부 작업용 코루틴 스코프 (F-008).
     *
     * - SupervisorJob: 자식 실패가 형제/부모로 전파되지 않도록 격리
     * - Dispatchers.IO: 네트워크/디스크 작업 기본 디스패처 (F-001/F-007에서 사용)
     *
     * close()에서 [scope].cancel()을 호출하면 진행 중 코루틴이 모두
     * `CancellationException`으로 종결된다 (R-020 케이스 A).
     *
     * F-001/F-003/F-007 진입 시 본 스코프에서 launch/async를 사용한다.
     */
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 활성 OkHttpClient 핸들 (F-001/F-006 진입 시 채워짐).
     *
     * 본 라운드(F-008)에서는 close()에서 nullable 검사 후 `dispatcher.cancelAll()`을
     * 호출한다. F-001/F-006이 set 한 뒤부터 실제 동작.
     *
     * - F-001 (텍스트 단발 질의) 또는 F-006 (Hilt 모듈) 진입 시 1회 set.
     * - 이후 close()에서 cancelAll → 모든 in-flight HTTP 호출이 IOException로 실패하고
     *   그 코루틴은 CancellationException으로 종료 (scope.cancel과 결합).
     */
    @Volatile
    internal var httpClient: OkHttpClient? = null

    // -----------------------------------------------------------------
    // F-005 / A-005 — useProvider
    // -----------------------------------------------------------------

    /**
     * 활성 Provider 변경 (A-005, F-005).
     *
     * 사양 참조:
     * - api.md A-005 시그니처: `fun useProvider(provider: ProviderId)`
     * - features.md F-005 정상 흐름 2단계 / 동시성 모델 (R-007) / Session과 Provider의 관계 (R-014)
     * - error-handling.md E-501 / E-502 → ERR-004 (`AiException.Configuration`)
     *
     * 동작:
     * 1. close 검증 (R-020 케이스 B, 동기 함수 → throw `AiException.Configuration("client closed")`)
     * 2. [providerRegistry]에 [provider]가 등록되어 있는지 확인 (E-501)
     * 3. v0.1 단일 apiKey 모델에서 키 비어있지 않음 검증 (E-502)
     * 4. [providerIdRef.set] 으로 atomic 교체 (수 ns)
     * 5. 진행 중 요청은 호출 시점에 자기 스택에 Provider 인스턴스를 이미 캡쳐했으므로 영향 없음
     * 6. 새 요청(ask/askStream/session.send) 진입 시 atomic get으로 새 Provider 사용
     *
     * Session 영향 (R-014): 본 client에서 생성된 모든 Session도 다음 send부터 새 Provider 사용
     * (Session은 Provider에 묶이지 않음).
     *
     * 예외 (모두 [AiException.Configuration] = ERR-004):
     * - close 후 호출 (R-020 케이스 B): "client closed"
     * - E-501: 등록되지 않은 [provider]: "unknown provider: $provider"
     * - E-502: 단일 apiKey가 비어있음: "api key missing for provider $provider"
     *   (현재 v0.1은 단일 apiKey 모델이므로 [Builder.build]에서 E-001로 사전 차단됨.
     *   본 검증은 방어적/멀티 키 모델 v0.2 진입 대비.)
     *
     * thread-safe (AtomicReference set 단일 연산).
     *
     * @param provider 교체할 Provider 식별자
     * @throws AiException.Configuration E-501 / E-502 매핑 (또는 close 후 호출 시)
     */
    public fun useProvider(provider: ProviderId) {
        // R-020 케이스 B: close 후 호출 → throw (동기 함수)
        ensureNotClosed()

        // E-501: 등록되지 않은 Provider
        if (!providerRegistry.contains(provider)) {
            throw AiException.Configuration("unknown provider: $provider")
        }
        // E-502: Provider별 API 키 누락
        // v0.1 단일 apiKey 모델에서는 [Builder.build]가 E-001로 이미 차단하므로 본 분기는
        // 사실상 도달 불가하나, 방어적 검증으로 둔다 (멀티 키 모델 v0.2 진입 시 본 위치에 키 검증 확장).
        if (apiKey.isBlank()) {
            throw AiException.Configuration(
                "api key missing for provider $provider",
            )
        }

        // R-007: AtomicReference set — 수 ns. 진행 중 요청 영향 없음, 다음 요청부터 적용.
        providerIdRef.set(provider)
    }

    // -----------------------------------------------------------------
    // F-001 / A-002 — ask (텍스트 단발 질의)
    // -----------------------------------------------------------------

    /**
     * 텍스트/멀티모달 질의 (F-001 + F-002, A-002).
     *
     * 사양 참조:
     * - features.md F-001 정상 흐름 / 예외 흐름 (E-101~E-110)
     * - features.md F-002 정상 흐름 / 예외 흐름 (E-201~E-207)
     * - api.md A-002 시그니처: `suspend fun ask(request: AiRequest): Result<AiResponse>`
     * - error-handling.md ERR-001~ERR-006 매핑
     * - features.md F-005 R-007 (활성 Provider 캡쳐), F-008 R-020 케이스 B (close 후 호출)
     *
     * 동작:
     * 1. close 검증 (R-020 케이스 B, suspend 함수 → `Result.failure(Configuration("client closed"))`)
     * 2. 진입 시점에 [activeProvider]를 지역 변수로 캡쳐 (R-007 — 진행 중 호출은 useProvider 영향 없음)
     * 3. F-002 이미지 검증 ([validateImages]) — E-201/E-202/E-204/E-205 매핑
     * 4. [Provider.complete] 호출 → [AiResponse]
     * 5. 빈 응답 검증 (정상 흐름 4단계, F-001):
     *    - text가 빈 문자열이고 finishReason이 END_TURN/STOP_SEQUENCE → 그대로 성공 반환
     *    - text가 빈 문자열이고 finishReason이 MAX_TOKENS/OTHER → E-110 ServerError(code=-1, "empty response")
     *    - 그 외 정상 텍스트는 그대로 통과
     * 5. `Result.success(response)` 반환
     *
     * 예외 흐름 ([Result.failure]로 반환):
     * - E-101: 네트워크 없음 → [AiException.Network]
     * - E-102: 401 → [AiException.Authentication]
     * - E-103: 429 → [AiException.RateLimit]
     * - E-104: 5xx → [AiException.ServerError]
     * - E-105: 응답 파싱 실패 → [AiException.ServerError(code=-1)]
     * - E-107: 빈 prompt → [AiException.InvalidInput] ([AiRequest] init 검증의 [IllegalArgumentException]을 변환)
     * - E-108: 타임아웃 → [AiException.Network] (cause=SocketTimeoutException)
     * - E-109: client closed → [AiException.Configuration("client closed")]
     * - E-110: 빈 응답 검증 실패 → [AiException.ServerError(-1, "empty response")]
     *
     * 코루틴 취소 (E-106): [CancellationException]은 본 메서드에서 별도 처리 없이 그대로 전파 한다
     * (R-020 케이스 A 시맨틱 — `Result.failure`로 변환되지 않음). [Provider.complete] 구현체가
     * `suspendCancellableCoroutine` + `Call.cancel()`로 cooperative cancellation을 보장한다.
     *
     * 자동 재시도 없음 (D-005). E-101/E-103 발생 시 호출자가 재호출 책임.
     *
     * thread-safe: 동시 호출 가능 (NFR — 최대 큐 8개는 OkHttp dispatcher 기본값 정합).
     *
     * @param request 질의 요청 (M-001, [AiRequest])
     * @return 성공 시 [Result.success]<[AiResponse]>, 실패 시 [Result.failure]<[AiException]>
     */
    public suspend fun ask(request: AiRequest): Result<AiResponse> {
        // R-020 케이스 B: close 후 호출 → Result.failure(Configuration("client closed"))
        // (suspend 시맨틱 — throw가 아닌 Result로 감싸 반환, A-002 표 참조)
        try {
            ensureNotClosed()
        } catch (e: AiException.Configuration) {
            return Result.failure(e)
        }

        // R-007: 진입 시점에 Provider/Config를 지역 변수로 캡쳐 — 진행 중 호출은 useProvider 영향 없음
        val provider: Provider = activeProvider
        val config: ProviderConfig = currentProviderConfig()

        // F-002: 이미지 검증 (Capabilities 매칭)
        // 사양 features.md F-002 정상 흐름 2단계 ("SDK가 각 ImageInput을 검증")
        // E-201/E-202/E-204/E-205 — ask 진입 직후, Provider.complete 호출 전에 수행
        val imageValidation = validateImages(request, provider.capabilities)
        if (imageValidation != null) {
            return Result.failure(imageValidation)
        }

        return try {
            val response: AiResponse = provider.complete(request, config)
            // F-001 정상 흐름 4단계: 빈 응답 검증 (E-110)
            if (response.text.isEmpty() &&
                response.finishReason !in EMPTY_OK_FINISH_REASONS
            ) {
                Result.failure(
                    AiException.ServerError(
                        code = EMPTY_RESPONSE_CODE,
                        message = "empty response",
                    ),
                )
            } else {
                Result.success(response)
            }
        } catch (e: CancellationException) {
            // E-106: 코루틴 취소는 그대로 전파 (R-020 케이스 A — Result.failure로 감싸지 않음)
            throw e
        } catch (e: AiException) {
            // E-101~E-110: Provider/Mapper/ErrorMapper에서 이미 변환된 AiException 그대로 사용
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            // M-001 init 검증 실패 (E-107 — 빈 prompt 등) → InvalidInput으로 변환.
            // 단, ask() 시점에는 호출자가 이미 AiRequest 인스턴스를 만든 상태이므로
            // require()는 생성 시점에 throw 된 IllegalArgumentException이 위로 전파된 케이스다.
            // 일반적으로 호출자 코드에서 throw 되지만, 안전망으로 매핑.
            Result.failure(AiException.InvalidInput(e.message ?: "invalid input"))
        } catch (e: Throwable) {
            // 그 외 예상치 못한 예외 → ServerError로 안전하게 변환 (작업 원칙 6)
            Result.failure(
                AiException.ServerError(
                    code = EMPTY_RESPONSE_CODE,
                    message = "unexpected error: ${e::class.simpleName}: ${e.message}",
                ),
            )
        }
    }

    // -----------------------------------------------------------------
    // F-003 / A-003 — askStream (스트리밍 응답)
    // -----------------------------------------------------------------

    /**
     * 스트리밍 질의 (F-003, A-003).
     *
     * 사양 참조:
     * - features.md F-003 정상 흐름 1~4 / 예외 흐름 (E-301 / E-302 / E-303 / E-101~E-110)
     * - api.md A-003 시그니처: `fun askStream(request: AiRequest): Flow<AiStreamEvent>`
     * - data-model.md M-006 [AiStreamEvent] 방출 순서: Delta 0+ → Done | Error 1
     * - error-handling.md ERR-001 / ERR-004 / ERR-005 / ERR-006 매핑
     * - features.md F-005 R-007 (활성 Provider 캡쳐), F-008 R-020 케이스 B (close 후 호출)
     *
     * 동작 (cold Flow — collect가 시작되어야 네트워크 호출):
     * 1. R-020 케이스 B: [isClosed]가 true면 첫 emit으로
     *    `AiStreamEvent.Error(AiException.Configuration("client closed"))` 후 Flow 종료.
     * 2. 진입 시점에 [activeProvider] 캡쳐 (R-007 — collect 도중 useProvider 영향 없음).
     * 3. E-303 검증: [Capabilities.supportsStream]이 false면
     *    `AiStreamEvent.Error(AiException.Configuration("provider does not support streaming"))` 후 종료.
     * 4. F-002 이미지 검증 ([validateImages]) — 실패 시 그 [AiException]을 [AiStreamEvent.Error]로
     *    방출 후 종료 (E-201/E-202/E-204/E-205/E-203). 본 검증은 ask와 동일.
     * 5. [Provider.stream]에 위임 ([emitAll]). Provider는 SSE를 파싱하여 Delta 0+ → Done | Error 1로 종결.
     * 6. CancellationException은 그대로 전파 (E-302). 표준 Flow 시맨틱.
     * 7. 그 외 예외는 [AiStreamEvent.Error]로 변환 후 emit (호출자에게 RuntimeException이 새는 일 방지).
     *
     * 방출 순서 보장 (M-006):
     * - 본 메서드는 검증 단계에서 Error를 emit하면 즉시 return (Delta/Done 미발생).
     * - Provider.stream으로 위임 후에는 Provider 책임 (M-006 Delta 0+ → Done|Error 1 보장은
     *   [com.androidailab.aisdk.internal.network.AnthropicSseParser]에서 처리).
     *
     * 코루틴 취소 (E-302):
     * - collector 측에서 코루틴 cancel 시 Flow의 표준 시맨틱에 따라 CancellationException 전파.
     * - Provider.stream(SSE)이 OkHttp Call.cancel을 invokeOnCancellation에 연결하여 cooperative.
     *
     * 자동 재시도 없음 (D-005). 호출자가 catch 후 재호출.
     *
     * @param request 질의 요청 (M-001, [AiRequest])
     * @return cold [Flow]. collect 시작 시점에 검증 → Provider 호출 → SSE 파싱이 진행된다.
     */
    public fun askStream(request: AiRequest): Flow<AiStreamEvent> = flow {
        // R-020 케이스 B: close 후 askStream → 첫 emit으로 Error 후 종료 (A-009 표 참조)
        if (isClosed()) {
            emit(AiStreamEvent.Error(AiException.Configuration("client closed")))
            return@flow
        }

        // R-007: 진입 시점에 Provider/Config 캡쳐 — collect 도중 useProvider 영향 없음
        val provider: Provider = activeProvider
        val config: ProviderConfig = currentProviderConfig()

        // E-303: Provider가 스트리밍 미지원이면 Error 후 종료
        // 사양상 "호출 즉시 throw"이지만 본 SDK는 Flow 시맨틱(콜백 변환)을 일관되게 유지하기 위해
        // 첫 emit으로 Error를 흘려 collect가 종결 사유를 받도록 한다 (A-003 close 시맨틱 표와 동형).
        if (!provider.capabilities.supportsStream) {
            emit(
                AiStreamEvent.Error(
                    AiException.Configuration("provider does not support streaming"),
                ),
            )
            return@flow
        }

        // F-002 이미지 검증 (E-201/E-202/E-204/E-205/E-203) — ask와 동일 흐름 재사용
        val imageValidation = validateImages(request, provider.capabilities)
        if (imageValidation != null) {
            emit(AiStreamEvent.Error(imageValidation))
            return@flow
        }

        // Provider.stream에 위임. Provider 측에서 발생하는 비-AiException 예외는 catch로 변환 (안전망).
        emitAll(provider.stream(request, config))
    }.catch { e ->
        // CancellationException은 Flow.catch가 자동으로 통과시킨다 (kotlinx-coroutines 1.8.x 사양).
        // 그러나 Throwable 가드: AiException은 그대로 Error로 wrap, 그 외는 ServerError로 wrap.
        if (e is CancellationException) throw e
        when (e) {
            is AiException -> emit(AiStreamEvent.Error(e))
            else -> emit(
                AiStreamEvent.Error(
                    AiException.ServerError(
                        code = EMPTY_RESPONSE_CODE,
                        message = "unexpected error: ${e::class.simpleName}: ${e.message}",
                    ),
                ),
            )
        }
    }

    // -----------------------------------------------------------------
    // F-004 / A-004 — createSession
    // -----------------------------------------------------------------

    /**
     * 신규 [Session] 생성 (F-004, A-004).
     *
     * 사양 참조:
     * - api.md A-004 시그니처: `fun createSession(systemPrompt: String? = null): Session`
     * - api.md A-004 sessionId 부여 정책 (R-017/R-024) — v0.1은 SDK 자동 UUID만 지원
     * - features.md F-004 정상 흐름 1단계 ("호출자가 `val session = client.createSession(systemPrompt = "...")` 호출")
     * - features.md F-004 systemPrompt 정책 (R-008) — history에 포함되지 않음
     * - data-model.md M-007 (Session 시그니처)
     * - features.md F-008 R-020 케이스 B (close 후 호출 시 throw — 동기 함수)
     *
     * 동작:
     * 1. R-020 케이스 B: close 후 호출 → [AiException.Configuration]("client closed") throw (동기 함수)
     * 2. sessionId를 [UUID.randomUUID]로 자동 부여 (R-017/R-024)
     * 3. 새 [Session] 인스턴스 반환 — initialHistory는 빈 리스트
     *
     * systemPrompt 정책 (R-008):
     * - 본 시점에 [systemPrompt]가 보관되며, 매 [Session.send] 호출 시 Anthropic top-level system 필드로 전송됨.
     * - [Session.history]()에는 등장하지 않는다.
     *
     * thread-safe: createSession 자체는 단순 인스턴스 생성이라 race 없음.
     *
     * @param systemPrompt 시스템 프롬프트 (선택). null이면 system 필드 미설정.
     * @return 새 [Session] 인스턴스
     * @throws AiException.Configuration close된 상태일 때 ("client closed")
     */
    public fun createSession(systemPrompt: String? = null): Session {
        // R-020 케이스 B: close 후 createSession 호출 → throw (동기 함수)
        ensureNotClosed()
        // R-017/R-024: SDK 자동 UUID 부여 (v0.1은 호출자 명시 지정 미지원)
        val sessionId = UUID.randomUUID().toString()
        return Session(
            client = this,
            sessionId = sessionId,
            systemPrompt = systemPrompt,
            initialHistory = emptyList(),
        )
    }

    // -----------------------------------------------------------------
    // F-007 / A-011, A-012 — loadSession / deleteSession
    // -----------------------------------------------------------------

    /**
     * sessionId로 [Session]을 복원한다 (F-007, A-011).
     *
     * 사양 참조:
     * - api.md A-011 시그니처: `suspend fun loadSession(sessionId: String): Result<Session>`
     * - features.md F-007 "복원 흐름" 1~5
     * - features.md F-007 R-018 (schemaVersion=1만 인정 → E-703)
     * - features.md F-007 R-019 (다중 인스턴스 정책 — 두 번 호출 시 두 인스턴스, last-write-wins)
     * - features.md F-008 R-020 케이스 A/B (close 시맨틱)
     * - error-handling.md ERR-005 (E-702 sessionId 없음) / ERR-007 (E-703 손상/schemaVersion) / ERR-004 (E-705 closed)
     *
     * 동작:
     * 1. R-020 케이스 B: closed면 즉시 `Result.failure(Configuration("client closed"))` 반환 (E-705)
     * 2. [sessionStore]가 null이면 (Builder 미설정) → IOError("session store not configured") 반환
     * 3. [SessionStore.load]로 [SessionEntity] 조회 — `Dispatchers.IO` (SessionStore 구현 책임)
     * 4. 미존재 (null) → `Result.failure(InvalidInput("session not found: {sessionId}"))` (E-702)
     * 5. schemaVersion 검증은 [SessionStore.load]가 수행 (R-018, E-703)
     * 6. [SessionEntity] → [Session] 인스턴스 복원 — sessionStore 참조 재주입, history 복원
     *
     * 다중 인스턴스 정책 (R-019): 같은 sessionId로 두 번 호출하면 두 개의 독립 Session 인스턴스가 생성됨.
     * 두 인스턴스의 save는 last-write-wins. 호출자가 단일 인스턴스 캐시 책임.
     *
     * 코루틴 취소 (E-706 / R-020 케이스 A): CancellationException은 Result.failure로 감싸지 않고 그대로 전파.
     *
     * @param sessionId 복원할 세션 식별자
     * @return 성공 시 [Result.success]([Session]), 실패 시 [Result.failure]([AiException])
     */
    public suspend fun loadSession(sessionId: String): Result<Session> {
        // R-020 케이스 B: close 후 호출 → Result.failure(Configuration("client closed")) (E-705)
        try {
            ensureNotClosed()
        } catch (e: AiException.Configuration) {
            return Result.failure(e)
        }

        // sessionStore 미설정 안전망 (Builder 미설정 경로 — F-007 미사용 호출자 대비)
        val store = sessionStore ?: return Result.failure(
            AiException.IOError("session store not configured"),
        )

        return try {
            val entity = store.load(sessionId)
                ?: return Result.failure(
                    // E-702: 미존재 → ERR-005 InvalidInput
                    AiException.InvalidInput("session not found: $sessionId"),
                )

            // SessionEntity → Session 복원 (history MessageEntity → Message 변환)
            // 변환 실패(role 불명 등)는 toMessage()가 AiException.IOError로 throw → E-703 catch
            val history = entity.history.map { it.toMessage() }

            Result.success(
                Session(
                    client = this,
                    sessionId = entity.sessionId,
                    systemPrompt = entity.systemPrompt,
                    initialHistory = history,
                ),
            )
        } catch (e: CancellationException) {
            // E-706: 그대로 전파 (R-020 케이스 A)
            throw e
        } catch (e: AiException) {
            // E-703(역직렬화/schemaVersion) / E-701(IO) / 그 외 SessionStore가 throw한 AiException
            Result.failure(e)
        } catch (e: Throwable) {
            // 안전망: 예상치 못한 예외는 IOError로 변환
            Result.failure(
                AiException.IOError(
                    "load failed: ${e::class.simpleName}: ${e.message ?: "unknown"}",
                    cause = e,
                ),
            )
        }
    }

    /**
     * sessionId의 [Session]을 디스크에서 삭제한다 (F-007, A-012).
     *
     * 사양 참조:
     * - api.md A-012 시그니처: `suspend fun deleteSession(sessionId: String): Result<Unit>`
     * - features.md F-007 "삭제 흐름" 1~3
     * - api.md A-012 ("존재하지 않는 sessionId 삭제 시도 시 성공으로 처리 — idempotent")
     * - features.md F-008 R-020 케이스 A/B
     * - error-handling.md ERR-004 (E-705 closed)
     *
     * 동작:
     * 1. R-020 케이스 B: closed면 즉시 `Result.failure(Configuration("client closed"))` (E-705)
     * 2. [sessionStore]가 null이면 IOError("session store not configured") 반환
     * 3. [SessionStore.delete] 호출 — `Dispatchers.IO`
     * 4. 미존재 sessionId도 idempotent로 성공 (DataStore Preferences의 remove 동작과 정합)
     *
     * 코루틴 취소 (E-706): CancellationException은 그대로 전파.
     *
     * @param sessionId 삭제할 세션 식별자
     * @return 성공 시 [Result.success]([Unit]), 실패 시 [Result.failure]([AiException])
     */
    public suspend fun deleteSession(sessionId: String): Result<Unit> {
        // R-020 케이스 B: close 후 호출 → Result.failure(Configuration("client closed")) (E-705)
        try {
            ensureNotClosed()
        } catch (e: AiException.Configuration) {
            return Result.failure(e)
        }

        val store = sessionStore ?: return Result.failure(
            AiException.IOError("session store not configured"),
        )

        return try {
            store.delete(sessionId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            // E-706: 그대로 전파 (R-020 케이스 A)
            throw e
        } catch (e: AiException) {
            Result.failure(e)
        } catch (e: Throwable) {
            Result.failure(
                AiException.IOError(
                    "delete failed: ${e::class.simpleName}: ${e.message ?: "unknown"}",
                    cause = e,
                ),
            )
        }
    }

    /**
     * F-002 이미지 검증 (정상 흐름 2단계, [Capabilities] 매칭).
     *
     * 사양 참조:
     * - features.md F-002 정상 흐름 2 ("SDK가 각 ImageInput을 검증 (크기/mimeType)")
     * - features.md F-002 E-201 (단일 5MB 초과) → InvalidInput
     * - features.md F-002 E-202 (합계 20MB 초과 / 최대 개수 초과) → InvalidInput
     * - features.md F-002 E-204 (지원하지 않는 mime) → InvalidInput
     * - features.md F-002 E-205 (Provider 이미지 미지원) → Configuration
     * - data-model.md M-001 — `images.size <= 10`은 [AiRequest.init]가 사전 차단 (E-202와 별개로 개수 한계)
     *
     * 합계 한계 (M-001 "최대 10장, 합계 20MB")는 본 함수에서 검증한다.
     * Provider별 한계 ([Capabilities.maxImageSizeBytes] / [Capabilities.maxImagesPerRequest] /
     * [Capabilities.supportedImageMimeTypes])도 본 함수에서 검증한다 — Capabilities는 P-001의
     * 단일 source of truth (R-010).
     *
     * 검증 순서 (실패 우선):
     * 1. images 비어있으면 즉시 null (정상)
     * 2. Provider.capabilities.supportsImage == false → E-205 [AiException.Configuration]
     * 3. images.size > Capabilities.maxImagesPerRequest → E-202 [AiException.InvalidInput]
     *    (M-001 init의 size <= 10은 이미 통과했으므로 본 분기는 Provider 한계가 10보다 작은 경우)
     * 4. 각 ImageInput.Bytes.data.size > maxImageSizeBytes → E-201 InvalidInput("image too large")
     * 5. 각 ImageInput.Bytes.mimeType 또는 Url의 추정 mime ∈ supportedImageMimeTypes:
     *    - Bytes의 mimeType은 화이트리스트 매칭 (E-204 InvalidInput("unsupported mime type"))
     *    - Url은 SDK가 fetch 전이라 mimeType 미상 → 본 단계에서 검증 안 하고 Mapper에서 fetch 후 매직 넘버로 검증.
     * 6. 합계 바이트 (Bytes만 합산) > 20MB → E-202 InvalidInput("images total too large")
     *
     * @return 실패 시 [AiException], 정상 시 null
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

        // E-202: 개수 한계 (M-001 init 통과 후 Provider별 추가 제한)
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
                    // URL은 fetch 전이라 size/mime 미상 — Mapper.toAnthropicRequest에서 fetch 후 매직 넘버로 검증.
                    // 본 단계에서는 형식 검증(ImageInput.Url.init)에 의존.
                }
                is ImageInput.Uri -> {
                    // E-203: v0.1은 Uri 자동 resolve 안 함 (D-004 — 호출자 책임).
                    // 호출자가 ContentResolver로 Bytes/Url로 변환해서 넘겨야 한다.
                    return AiException.InvalidInput(
                        "uri unreadable: SDK does not auto-resolve Uri (call ContentResolver before ask)",
                    )
                }
            }
        }

        // E-202: 합계 한계 (M-001 "합계 20MB")
        // SDK 차원의 hard cap. Capabilities에는 합계 필드가 없으므로 M-001 명시값을 상수로 사용.
        if (totalBytes > IMAGES_TOTAL_MAX_BYTES) {
            return AiException.InvalidInput(
                "images total too large: $totalBytes > $IMAGES_TOTAL_MAX_BYTES",
            )
        }

        return null
    }

    // -----------------------------------------------------------------
    // F-008 / A-009 — close
    // -----------------------------------------------------------------

    /**
     * 클라이언트 종료 (F-008, A-009).
     *
     * 동작 (features.md F-008 정상 흐름):
     * 1. `closed` 플래그를 atomically true로 전환. 두 번째 호출부터 no-op (idempotent).
     * 2. OkHttp dispatcher.cancelAll() — 진행 중 HTTP 호출 즉시 중단.
     * 3. 내부 [scope].cancel() — 진행 중 코루틴은 케이스 A에 따라 [kotlinx.coroutines.CancellationException]으로 종결.
     * 4. DataStore 핸들 해제(F-007 진입 시 추가).
     *
     * close 시맨틱 (R-020):
     * - 케이스 A (in-flight): 진행 중 코루틴은 cooperative 취소 지점에서 CancellationException throw.
     *   `Result.failure`로 변환되지 않는다(표준 코루틴 시맨틱).
     * - 케이스 B (close 후 새 호출): 각 함수 진입 시 [ensureNotClosed]로 차단.
     *   - suspend 함수 → `Result.failure(AiException.Configuration("client closed"))` 반환
     *   - Flow 함수 → 첫 emit으로 `AiStreamEvent.Error(...)` 후 종료
     *   - 동기 함수 → `AiException.Configuration("client closed")` throw ([useProvider]도 포함)
     *
     * 정책 (api.md A-009):
     * - idempotent (여러 번 호출 안전)
     * - thread-safe
     * - close 도중 IO 예외(E-801)는 무시하고 best-effort 진행 (로그만)
     */
    public fun close() {
        if (!closed.compareAndSet(false, true)) {
            // 이미 close 됨 — 두 번째 호출부터 no-op (idempotent)
            return
        }

        // 1. OkHttp dispatcher cancel — 진행 중 모든 HTTP 호출 중단 (E-801: IO 예외는 무시)
        try {
            httpClient?.dispatcher?.cancelAll()
        } catch (_: Throwable) {
            // E-801: close 도중 IO 예외 → 무시 (best-effort, 로그만 — 로깅 인프라는 후속 라운드)
        }

        // 2. 코루틴 스코프 cancel — 진행 중 코루틴은 CancellationException으로 종결 (케이스 A)
        try {
            scope.cancel("AiAgentClient closed")
        } catch (_: Throwable) {
            // E-801: 무시 (best-effort)
        }

        // 3. DataStore 핸들 해제 — F-007 진입 시 추가 (현재는 placeholder)
    }

    /**
     * close 여부 확인. close된 상태면 [AiException.Configuration]을 throw 한다 (F-008 케이스 B, R-020).
     *
     * 사용처:
     * - 동기 함수 (createSession/useProvider/Session.history/Session.clear): 진입 첫 줄에서 호출.
     * - suspend 함수 (ask/send/save/loadSession/deleteSession): `try { ensureNotClosed() } catch ...
     *   { return Result.failure(it) }` 또는 `runCatching { ensureNotClosed() }.getOrElse { return Result.failure(it) }` 패턴.
     * - Flow 함수 (askStream): `if (closed.get()) emit(AiStreamEvent.Error(...))` 패턴 (F-003에서 [isClosed] 사용).
     *
     * 메시지 문자열은 사양과 정확히 일치 ("client closed").
     *
     * @throws AiException.Configuration close된 상태일 때
     */
    internal fun ensureNotClosed() {
        if (closed.get()) {
            throw AiException.Configuration("client closed")
        }
    }

    /**
     * close 여부 조회 (F-008 보조 헬퍼).
     *
     * Flow 함수(askStream — F-003)가 첫 collect 시 닫힘 상태를 확인하는 데 사용.
     * suspend/동기 함수는 [ensureNotClosed]를 우선 사용.
     */
    internal fun isClosed(): Boolean = closed.get()

    // -----------------------------------------------------------------

    public companion object {
        /**
         * Builder 생성 헬퍼 (F-000 정상 흐름 1단계, A-001).
         *
         * @param context 호출자 Context (Activity/Application 모두 가능).
         *                내부에서 `applicationContext`를 추출한다.
         */
        @JvmStatic
        public fun builder(context: Context): Builder = Builder(context)

        /**
         * F-001 정상 흐름 4단계 — 빈 텍스트가 허용되는 [FinishReason] 집합.
         *
         * `END_TURN` / `STOP_SEQUENCE`인 경우 빈 응답을 그대로 성공 반환 (호출자가 finishReason으로 판별 가능).
         * `MAX_TOKENS` / `OTHER`에서 빈 응답이면 E-110으로 거부.
         */
        internal val EMPTY_OK_FINISH_REASONS: Set<FinishReason> = setOf(
            FinishReason.END_TURN,
            FinishReason.STOP_SEQUENCE,
        )

        /**
         * F-001 E-110 / E-105 — 응답 파싱 실패 또는 빈 응답 검증 실패에 사용하는 가상 status code.
         *
         * 사양 features.md F-001 E-110: `AiException.ServerError(code=-1, message="empty response")`.
         */
        internal const val EMPTY_RESPONSE_CODE: Int = -1

        /**
         * F-002 E-202 합계 한계 — data-model.md M-001 "이미지 합계 20MB".
         *
         * SDK 차원의 hard cap. Capabilities에는 합계 필드가 없으므로 M-001 명시값을 상수로 사용한다
         * (Provider별 변경이 발생하면 v0.2에서 [Capabilities] 확장 검토).
         */
        internal const val IMAGES_TOTAL_MAX_BYTES: Long = 20L * 1024L * 1024L
    }
}
