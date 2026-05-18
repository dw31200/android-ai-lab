package com.androidailab.aisdk.provider.claude

import com.androidailab.aisdk.internal.network.AnthropicHttpClient
import com.androidailab.aisdk.internal.network.AnthropicJson
import com.androidailab.aisdk.internal.network.AnthropicMessagesRequest
import com.androidailab.aisdk.internal.network.AnthropicMessagesResponse
import com.androidailab.aisdk.internal.network.AnthropicSseParser
import com.androidailab.aisdk.internal.network.ErrorMapper
import com.androidailab.aisdk.internal.network.Mapper
import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.AiRequest
import com.androidailab.aisdk.model.AiResponse
import com.androidailab.aisdk.model.AiStreamEvent
import com.androidailab.aisdk.model.Message
import com.androidailab.aisdk.model.ProviderId
import com.androidailab.aisdk.provider.Capabilities
import com.androidailab.aisdk.provider.Provider
import com.androidailab.aisdk.provider.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Anthropic Claude Provider (P-CLAUDE).
 *
 * 사양 참조:
 * - provider-spec.md P-CLAUDE 상세 섹션 (모델 목록, 이미지 한계, Capabilities 값, 에러 매핑)
 * - features.md F-001 (텍스트 단발 질의)
 * - data-model.md M-010 ([ProviderId.CLAUDE])
 *
 * 본 라운드(F-001)에서 [complete] 본체 구현 — Anthropic Messages API POST /v1/messages 호출:
 * 1. SDK 요청 → Anthropic 요청 JSON ([Mapper.toAnthropicRequest])
 * 2. OkHttp POST 호출 ([AnthropicHttpClient.execute] — 코루틴 취소 cooperative, E-106)
 * 3. HTTP status 검증 ([ErrorMapper.fromHttpStatus] — E-102/E-103/E-104)
 * 4. 응답 JSON → SDK 응답 변환 ([Mapper.toAiResponse])
 *    - 빈 응답 검증(E-110)은 [com.androidailab.aisdk.AiAgentClient.ask]가 정상 흐름 4단계에서 수행
 * 5. 외부 예외(IOException/SerializationException 등)는 [ErrorMapper.fromException]으로 변환
 *
 * F-002 (이미지)/F-003 (스트리밍)/F-004 (세션) 진입 시 본 클래스 또는 별도 Mapper에서 분기 추가.
 *
 * @param httpClientFactory [AnthropicHttpClient]를 [ProviderConfig.timeout] 기준으로 생성하는 팩토리.
 *                          기본값은 OkHttp 기본 빌더. 단위 테스트에서 MockWebServer 기반 클라이언트를 주입할 수 있다.
 */
public open class ClaudeProvider internal constructor(
    private val httpClientFactory: (ProviderConfig) -> AnthropicHttpClient,
) : Provider {

    /**
     * 호출자 진입 경로 (Builder.defaultProviders) 또는 Hilt 미사용용 기본 생성자.
     *
     * 본 생성자는 호출 시점에 [ProviderConfig.timeout]을 알 수 없으므로, 매 호출마다
     * 새 [AnthropicHttpClient]를 [AnthropicHttpClient.newOkHttpClient]로 빌드한다.
     * F-006 (Hilt) 진입 시 [AnthropicHttpClient]를 [@Singleton]으로 주입하는 형태로 변경 검토.
     */
    public constructor() : this(
        httpClientFactory = { config ->
            AnthropicHttpClient(AnthropicHttpClient.newOkHttpClient(config.timeout))
        },
    )

    override val id: ProviderId = ProviderId.CLAUDE

    /**
     * P-CLAUDE Capabilities 값 (provider-spec.md "P-CLAUDE 상세").
     */
    override val capabilities: Capabilities = Capabilities(
        supportsImage = true,
        supportsVideo = false,
        supportsStream = true,
        supportsSession = true,
        maxImageSizeBytes = MAX_IMAGE_SIZE_BYTES,
        maxImagesPerRequest = MAX_IMAGES_PER_REQUEST,
        supportedImageMimeTypes = SUPPORTED_IMAGE_MIME_TYPES,
    )

    /**
     * 단발 질의 (F-001, P-CLAUDE Anthropic Messages API POST /v1/messages).
     *
     * 사양 참조:
     * - features.md F-001 정상 흐름 1~5
     * - provider-spec.md P-CLAUDE 에러 매핑
     *
     * 동작:
     * 1. SDK 요청 → Anthropic 요청 JSON 직렬화
     * 2. POST /v1/messages 호출 (헤더: x-api-key + anthropic-version + content-type)
     * 3. HTTP status 검증 (4xx/5xx → [AiException])
     * 4. 응답 JSON 파싱 → [AiResponse] 변환
     *
     * 빈 응답 검증(E-110)은 본 메서드가 아닌 [com.androidailab.aisdk.AiAgentClient.ask]가 수행한다
     * (사양 F-001 정상 흐름 4단계 — SDK 차원 검증).
     *
     * @throws AiException 모든 외부 예외는 본 sealed 계층으로 변환됨
     * @throws CancellationException 코루틴 취소 시 그대로 전파 (E-106)
     */
    override suspend fun complete(
        request: AiRequest,
        config: ProviderConfig,
    ): AiResponse {
        val client = httpClientFactory(config)

        // F-002 (T12): Mapper가 ImageInput.Url을 fetch 할 때 OkHttpClient를 사용한다 (E-206).
        // 이미지가 없으면 httpClient는 사용되지 않는다.
        val anthropicRequest = Mapper.toAnthropicRequest(
            request = request,
            config = config,
            httpClient = client.httpClient,
        )
        val requestJson = AnthropicJson.instance.encodeToString(
            AnthropicJson.instance.serializersModule.serializer(),
            anthropicRequest,
        )

        val httpRequest = Request.Builder()
            .url(MESSAGES_URL)
            .header(HEADER_API_KEY, config.apiKey)
            .header(HEADER_ANTHROPIC_VERSION, ANTHROPIC_VERSION)
            .header("Content-Type", JSON_MEDIA_TYPE_VALUE)
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // 본 호출은 AnthropicHttpClient.execute 가 IOException → AiException.Network로 이미 변환한다.
        // 따라서 여기서는 status 코드 검증과 본문 파싱 예외만 처리하면 된다.
        val response: Response = client.execute(httpRequest)
        return response.use { resp ->
            // 4xx/5xx → AiException 매핑 (E-102/E-103/E-104)
            val httpError = ErrorMapper.fromHttpStatus(resp)
            if (httpError != null) {
                // F-004 E-401: 4xx인 경우 본문에서 컨텍스트 초과 케이스를 우선 감지.
                // (provider-spec.md P-CLAUDE "context_length_exceeded → InvalidInput('context too large')")
                // 본문은 한 번만 읽을 수 있으므로 errorBody를 캡쳐한 뒤 mapContextOverflow에 전달.
                val errorBody: String? = runCatching { resp.body?.string() }.getOrNull()
                val contextOverflow = ErrorMapper.mapContextOverflow(errorBody)
                if (contextOverflow != null) {
                    throw contextOverflow
                }
                throw httpError
            }

            // 정상 응답 (2xx) — 본문 파싱
            val body = resp.body?.string().orEmpty()
            val parsed: AnthropicMessagesResponse = try {
                AnthropicJson.instance.decodeFromString(
                    AnthropicJson.instance.serializersModule.serializer(),
                    body,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SerializationException) {
                throw ErrorMapper.fromException(e) // E-105: 응답 파싱 실패 → ServerError(code=-1)
            } catch (e: Throwable) {
                throw ErrorMapper.fromException(e)
            }

            Mapper.toAiResponse(parsed, id)
        }
    }

    /**
     * Session.send 진입용 단발 호출 (F-004, A-006, P-CLAUDE).
     *
     * 사양 참조:
     * - features.md F-004 정상 흐름 2~3 ("이전 메시지 + 현재 요청을 합쳐 Provider 전송")
     * - features.md F-004 systemPrompt 정책 (R-008)
     * - features.md F-004 컨텍스트 한계 검증 알고리즘 (E-401) — Provider 응답에서 감지
     * - provider-spec.md P-CLAUDE 에러 매핑 (context_length_exceeded → E-401)
     * - data-model.md M-007 (Session) / M-008 (Message)
     *
     * 동작:
     * 1. [Mapper.toAnthropicRequestForSession]로 history + 현재 request + systemPrompt → Anthropic 요청 변환
     * 2. POST /v1/messages 호출 ([complete]와 동일 흐름)
     * 3. 4xx인 경우 [ErrorMapper.mapContextOverflow]로 컨텍스트 초과 감지 → E-401
     * 4. 그 외 4xx/5xx → [complete]와 동일 매핑 (E-102/E-103/E-104)
     * 5. 정상 응답 → [Mapper.toAiResponse]로 [AiResponse] 변환
     *
     * 본 메서드는 [complete]와 거의 동일한 흐름이지만, 요청 변환만 [Mapper.toAnthropicRequestForSession]
     * (history + systemPrompt 포함)을 사용한다는 차이.
     *
     * @param history Session.history() snapshot (M-008). USER/ASSISTANT만 포함.
     * @param request 현재 USER 메시지 (M-001).
     * @param systemPrompt Session.systemPrompt (R-008). null이면 system 필드 미설정.
     * @param config Provider 설정.
     * @throws AiException 모든 외부 예외는 본 sealed 계층으로 변환됨. E-401 / E-102/E-103/E-104 등.
     * @throws CancellationException 코루틴 취소 시 그대로 전파 (E-106).
     */
    public open suspend fun completeForSession(
        history: List<Message>,
        request: AiRequest,
        systemPrompt: String?,
        config: ProviderConfig,
    ): AiResponse {
        val client = httpClientFactory(config)

        val anthropicRequest = Mapper.toAnthropicRequestForSession(
            history = history,
            request = request,
            systemPrompt = systemPrompt,
            config = config,
            httpClient = client.httpClient,
        )
        val requestJson = AnthropicJson.instance.encodeToString(
            AnthropicJson.instance.serializersModule.serializer(),
            anthropicRequest,
        )

        val httpRequest = Request.Builder()
            .url(MESSAGES_URL)
            .header(HEADER_API_KEY, config.apiKey)
            .header(HEADER_ANTHROPIC_VERSION, ANTHROPIC_VERSION)
            .header("Content-Type", JSON_MEDIA_TYPE_VALUE)
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response: Response = client.execute(httpRequest)
        return response.use { resp ->
            val httpError = ErrorMapper.fromHttpStatus(resp)
            if (httpError != null) {
                // F-004 E-401: 4xx인 경우 본문에서 컨텍스트 초과 케이스를 우선 감지.
                val errorBody: String? = runCatching { resp.body?.string() }.getOrNull()
                val contextOverflow = ErrorMapper.mapContextOverflow(errorBody)
                if (contextOverflow != null) {
                    throw contextOverflow
                }
                throw httpError
            }

            val body = resp.body?.string().orEmpty()
            val parsed: AnthropicMessagesResponse = try {
                AnthropicJson.instance.decodeFromString(
                    AnthropicJson.instance.serializersModule.serializer(),
                    body,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SerializationException) {
                throw ErrorMapper.fromException(e) // E-105
            } catch (e: Throwable) {
                throw ErrorMapper.fromException(e)
            }

            Mapper.toAiResponse(parsed, id)
        }
    }

    /**
     * 스트리밍 질의 (F-003, A-003, P-CLAUDE SSE).
     *
     * 사양 참조:
     * - features.md F-003 정상 흐름 1~4 / 예외 흐름 (E-301 / E-302 / E-303)
     * - api.md A-003 시그니처: `fun askStream(request: AiRequest): Flow<AiStreamEvent>`
     * - data-model.md M-006 [AiStreamEvent] 방출 순서: Delta 0+ → Done | Error 1
     * - provider-spec.md P-CLAUDE: 스트리밍 = SSE
     * - error-handling.md ERR-001 / ERR-002 / ERR-003 / ERR-006 / ERR-005 매핑
     *
     * 동작:
     * 1. SDK 요청 → Anthropic 요청 JSON (`Mapper.toAnthropicRequest`) — `stream=true` 추가
     * 2. POST /v1/messages with `accept: text/event-stream`
     * 3. HTTP status 검증 (4xx/5xx → [AiStreamEvent.Error] 방출 후 종료)
     *    - 401 → Authentication / 429 → RateLimit / 5xx → ServerError / 그 외 4xx → ServerError
     * 4. 본문을 [AnthropicSseParser]로 파싱 — Delta 0+ → Done | Error 1 방출
     * 5. Response.use 패턴으로 socket 정리. 코루틴 취소(E-302) 시 [Call.cancel]로 cooperative.
     *
     * 빈 응답 검증(R-005)은 [AnthropicSseParser]가 message_stop 시점에 수행한다 — F-001 일관성.
     *
     * E-303은 [com.androidailab.aisdk.AiAgentClient.askStream]이 진입 시점에 capabilities 검증으로
     * 차단하므로 본 메서드 도달 시 supportsStream=true 가정.
     *
     * 본 메서드는 요청 전송 자체를 Flow 빌더 내부에서 수행한다 (cold Flow). collect가 시작되어야
     * 네트워크 호출이 발생한다 (F-003 NFR backpressure 정합).
     *
     * @throws CancellationException 코루틴 취소 시 그대로 전파 (E-302)
     */
    override fun stream(
        request: AiRequest,
        config: ProviderConfig,
    ): Flow<AiStreamEvent> = flow {
        val client = httpClientFactory(config)

        // 1. Anthropic 요청 본체 빌드 (suspend) + stream=true 강제 추가
        // Mapper.toAnthropicRequest는 stream 플래그를 모르므로 직렬화 후 JsonObject에 stream:true 삽입.
        val anthropicRequest = try {
            Mapper.toAnthropicRequest(
                request = request,
                config = config,
                httpClient = client.httpClient,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            // F-002의 이미지 처리 예외(E-206/E-207/E-203) — Mapper에서 이미 변환됨
            emit(AiStreamEvent.Error(e))
            return@flow
        } catch (e: Throwable) {
            emit(AiStreamEvent.Error(ErrorMapper.fromException(e)))
            return@flow
        }

        // 2. JSON 직렬화 + stream=true 플래그 강제 추가 (P-CLAUDE SSE 활성화 조건)
        // AnthropicMessagesRequest에는 stream 필드가 없으므로 JsonObject에 toMutableMap 후 추가.
        val baseJson: JsonObject = AnthropicJson.instance.encodeToJsonElement(
            AnthropicMessagesRequest.serializer(),
            anthropicRequest,
        ) as JsonObject
        val streamJson = JsonObject(
            baseJson.toMutableMap().apply {
                put("stream", JsonPrimitive(true))
            },
        )
        val requestJson = AnthropicJson.instance.encodeToString(
            JsonElement.serializer(),
            streamJson,
        )

        val httpRequest = Request.Builder()
            .url(MESSAGES_URL)
            .header(HEADER_API_KEY, config.apiKey)
            .header(HEADER_ANTHROPIC_VERSION, ANTHROPIC_VERSION)
            .header("Content-Type", JSON_MEDIA_TYPE_VALUE)
            .header("Accept", SSE_MEDIA_TYPE_VALUE)
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // 3. HTTP 요청 — execute는 IOException → AiException.Network로 변환 (E-301 매핑 가능)
        val response: Response = try {
            client.execute(httpRequest)
        } catch (e: CancellationException) {
            throw e // E-302: cooperative 취소
        } catch (e: AiException) {
            emit(AiStreamEvent.Error(e))
            return@flow
        } catch (e: Throwable) {
            emit(AiStreamEvent.Error(ErrorMapper.fromException(e)))
            return@flow
        }

        // 4. response.use { ... } — 본 코루틴이 cancel 되면 OkHttp Call이 cancel되어 source가 닫힌다.
        try {
            response.use { resp ->
                // 4xx/5xx → 단일 Error 방출 후 종료 (E-301 사전 검증)
                val httpError = ErrorMapper.fromHttpStatus(resp)
                if (httpError != null) {
                    runCatching { resp.body?.string() }
                    emit(AiStreamEvent.Error(httpError))
                    return@flow
                }

                val body = resp.body
                if (body == null) {
                    emit(
                        AiStreamEvent.Error(
                            AiException.ServerError(code = -1, message = "empty stream body"),
                        ),
                    )
                    return@flow
                }

                // 5. SSE 파서로 위임 (Delta 0+ → Done | Error 1)
                emitAll(AnthropicSseParser.parse(body.source(), id))
            }
        } catch (e: CancellationException) {
            // E-302: 표준 코루틴 시맨틱 — 그대로 전파. response.use가 close를 보장.
            throw e
        }
    }

    public companion object {
        /** P-CLAUDE: 단일 이미지 한계 5MB. */
        public const val MAX_IMAGE_SIZE_BYTES: Long = 5L * 1024L * 1024L

        /** P-CLAUDE: 요청당 이미지 최대 10개 (Anthropic 한계 20, SDK 보수적으로 10). */
        public const val MAX_IMAGES_PER_REQUEST: Int = 10

        /** P-CLAUDE: 지원 mimeType — image/jpeg, image/png, image/webp, image/gif. */
        public val SUPPORTED_IMAGE_MIME_TYPES: Set<String> = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
        )

        /** Anthropic Messages API 엔드포인트 (P-CLAUDE). */
        internal const val MESSAGES_URL: String = "https://api.anthropic.com/v1/messages"

        /** Anthropic API 버전 헤더 값 (provider-spec.md P-CLAUDE 기준 합리적 기본값). */
        internal const val ANTHROPIC_VERSION: String = "2023-06-01"

        internal const val HEADER_API_KEY: String = "x-api-key"
        internal const val HEADER_ANTHROPIC_VERSION: String = "anthropic-version"
        internal const val JSON_MEDIA_TYPE_VALUE: String = "application/json; charset=utf-8"
        private val JSON_MEDIA_TYPE = JSON_MEDIA_TYPE_VALUE.toMediaType()

        /** F-003 / P-CLAUDE: SSE 응답 협상용 Accept 헤더 값. */
        internal const val SSE_MEDIA_TYPE_VALUE: String = "text/event-stream"
    }
}
