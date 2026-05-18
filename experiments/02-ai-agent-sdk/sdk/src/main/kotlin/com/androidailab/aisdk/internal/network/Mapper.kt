package com.androidailab.aisdk.internal.network

import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.AiRequest
import com.androidailab.aisdk.model.AiResponse
import com.androidailab.aisdk.model.FinishReason
import com.androidailab.aisdk.model.ImageInput
import com.androidailab.aisdk.model.Message
import com.androidailab.aisdk.model.ProviderId
import com.androidailab.aisdk.model.Role
import com.androidailab.aisdk.model.TokenUsage
import com.androidailab.aisdk.provider.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SDK 모델 ↔ Anthropic API 모델 변환기 (P-CLAUDE, F-001/F-002).
 *
 * 사양 참조:
 * - data-model.md M-001 (AiRequest) → [AnthropicMessagesRequest]
 * - data-model.md M-002 (AiResponse) ← [AnthropicMessagesResponse]
 * - data-model.md M-003 (ImageInput) → [AnthropicContentRequestBlock.Image] (F-002)
 * - data-model.md M-009 (TokenUsage) ← [AnthropicUsage]
 * - features.md F-002 정상 흐름 3단계 — 이미지를 base64로 인코딩 후 Provider 형식 변환
 * - features.md F-002 E-206/E-207 (URL fetch 실패, 디코딩 실패)
 * - provider-spec.md "ImageInput.Url SSRF 방어 정책" R-023 — SDK는 형식 검증만 수행
 */
internal object Mapper {

    /**
     * SDK 요청 → Anthropic API 요청 변환 (F-001 + F-002).
     *
     * 매핑:
     * - [AiRequest.prompt] + [AiRequest.images] → [AnthropicMessage] role=`user` 단일 메시지의 content blocks
     * - [AiRequest.maxTokens] → `max_tokens`
     * - [AiRequest.temperature] → `temperature`
     * - [ProviderConfig.modelId] → `model`
     *
     * 이미지 처리 (F-002):
     * - [ImageInput.Bytes]: base64 인코딩 → `{type:"image", source:{type:"base64", media_type, data}}`
     * - [ImageInput.Url]: [httpClient]로 URL fetch → 응답 바이트를 base64 인코딩 → 위와 동일 형태
     *   - URL fetch 실패는 E-206 ([AiException.InvalidInput] 또는 [AiException.Network])
     * - [ImageInput.Uri]: v0.1에서 본 매퍼는 [ImageInput.Bytes]/[ImageInput.Url]만 인코딩한다.
     *   Uri는 호출자/Session 레이어에서 사전에 ContentResolver로 Bytes로 변환한 뒤 전달한다.
     *   (D-004: SDK는 Uri 자동 fetch 하지 않음 — Uri 도달 시 [AiException.InvalidInput]("uri unreadable") E-203)
     *
     * suspend로 변경된 사유: [ImageInput.Url] fetch가 OkHttp 호출이라 코루틴 컨텍스트 필요 (E-206).
     *
     * 컨텐츠 블록 정책 (P-CLAUDE):
     * - 텍스트만 있을 때: `[{type:text, text:"..."}]` 단일 블록 배열
     * - 이미지 첨부 시: `[{type:text, text:"..."}, {type:image, source:{...}}, ...]` (텍스트 먼저)
     *
     * @param httpClient [ImageInput.Url] fetch에 사용할 OkHttp 클라이언트. 이미지가 없으면 사용되지 않음.
     * @throws AiException.InvalidInput E-206 (URL invalid/HTTP 4xx) / E-207 (디코딩 실패) / E-203 (Uri 처리)
     * @throws AiException.Network E-206 (timeout/DNS/5xx)
     */
    suspend fun toAnthropicRequest(
        request: AiRequest,
        config: ProviderConfig,
        httpClient: OkHttpClient? = null,
    ): AnthropicMessagesRequest {
        val contentJson: JsonElement = buildContentJson(request, httpClient)

        return AnthropicMessagesRequest(
            model = config.modelId,
            maxTokens = request.maxTokens,
            messages = listOf(
                AnthropicMessage(role = ROLE_USER, content = contentJson),
            ),
            temperature = request.temperature,
        )
    }

    /**
     * Session.send 진입 SDK 요청 → Anthropic 요청 변환 (F-004, A-006).
     *
     * 사양 참조:
     * - features.md F-004 정상 흐름 2단계 ("이전 메시지 + 현재 요청을 합쳐 Provider 전송")
     * - features.md F-004 systemPrompt 정책 (R-008) — Session.systemPrompt → top-level `system` 필드
     * - data-model.md M-007 (Session) / M-008 (Message)
     * - data-model.md M-008 Role 사용 정책 (R-008) — Role.SYSTEM은 history에 등장하지 않음
     *
     * 매핑:
     * - [systemPrompt] (M-007) → [AnthropicMessagesRequest.system] (R-008)
     * - [history] (M-008 List) → [AnthropicMessagesRequest.messages]의 앞쪽 메시지들 (role=`user`/`assistant`)
     * - 현재 [request] → [AnthropicMessagesRequest.messages]의 마지막 USER 메시지
     * - 각 history 메시지의 [Message.images]는 F-002와 동일하게 base64 이미지 블록으로 인코딩
     *
     * 메시지 직렬화 정책 (R-008):
     * - [Role.SYSTEM] 메시지가 [history]에 들어있다면 (방어적 — 정상 흐름에서는 없음) 무시한다.
     * - 본 라운드 v0.1에서 history에는 [Role.USER]/[Role.ASSISTANT]만 들어간다 (Session.send 흐름 강제).
     *
     * [maxTokens]/[temperature]는 현재 [request]에서만 가져온다. history의 옛 maxTokens/temperature는
     * Provider 호출에 영향 없음 (Anthropic API는 메시지 단위가 아닌 요청 단위로 받음).
     *
     * @param history Session.history() snapshot (M-008). USER/ASSISTANT만 포함되어야 함.
     * @param request 현재 USER 메시지 (M-001).
     * @param systemPrompt Session.systemPrompt (R-008). null이면 system 필드 미설정 (Anthropic 기본 동작).
     * @param config Provider 설정 (modelId, timeout 등).
     * @param httpClient ImageInput.Url fetch에 사용할 OkHttp 클라이언트 (F-002 동일 정책).
     * @throws AiException.InvalidInput E-203 / E-206 (URL invalid/HTTP 4xx) / E-207 (디코딩 실패)
     * @throws AiException.Network E-206 (timeout/DNS/5xx)
     */
    suspend fun toAnthropicRequestForSession(
        history: List<Message>,
        request: AiRequest,
        systemPrompt: String?,
        config: ProviderConfig,
        httpClient: OkHttpClient? = null,
    ): AnthropicMessagesRequest {
        val messages = mutableListOf<AnthropicMessage>()

        // history 변환 (USER/ASSISTANT만 — R-008: SYSTEM은 history에 등장하지 않음, 방어적 필터)
        for (msg in history) {
            if (msg.role == Role.SYSTEM) {
                // R-008: 방어적 — Session.history()는 SYSTEM을 포함하지 않으므로 정상 흐름에서는 도달 불가.
                continue
            }
            val role = when (msg.role) {
                Role.USER -> ROLE_USER
                Role.ASSISTANT -> ROLE_ASSISTANT
                Role.SYSTEM -> continue // unreachable (위에서 처리)
            }
            val contentJson = buildMessageContentJson(
                text = msg.content,
                images = msg.images,
                httpClient = httpClient,
            )
            messages.add(AnthropicMessage(role = role, content = contentJson))
        }

        // 현재 USER 메시지 (F-002 buildContentJson 재사용 — request.prompt + request.images)
        val currentContent = buildContentJson(request, httpClient)
        messages.add(AnthropicMessage(role = ROLE_USER, content = currentContent))

        return AnthropicMessagesRequest(
            model = config.modelId,
            maxTokens = request.maxTokens,
            messages = messages,
            temperature = request.temperature,
            // R-008 systemPrompt 정책: Session.systemPrompt → Anthropic top-level system 필드.
            // null이면 AnthropicJson.encodeDefaults=false + explicitNulls=false 정책으로 직렬화 제외.
            system = systemPrompt,
        )
    }

    /**
     * F-002: [AiRequest]의 사용자 메시지 컨텐츠 블록을 [JsonElement] 배열로 빌드.
     *
     * - 텍스트 블록 1개를 항상 첫 위치에 둔다 (prompt가 비어있지 않음 — M-001 init 검증).
     * - 이미지가 있으면 텍스트 다음에 이미지 블록을 순서대로 추가.
     *
     * 본 함수는 현재 USER 메시지(F-001/F-002 또는 F-004의 마지막 메시지) 변환에 사용된다.
     * Session.history의 메시지 변환은 [buildMessageContentJson]을 직접 사용한다.
     */
    private suspend fun buildContentJson(
        request: AiRequest,
        httpClient: OkHttpClient?,
    ): JsonElement = buildMessageContentJson(
        text = request.prompt,
        images = request.images,
        httpClient = httpClient,
    )

    /**
     * F-002/F-004 공용: 텍스트 + 이미지 목록을 Anthropic 컨텐츠 블록 배열 [JsonElement]로 빌드.
     *
     * - 텍스트 블록 1개를 항상 첫 위치에 둔다 (USER/ASSISTANT 메시지 모두 텍스트 본문은 필수로 가정).
     * - 이미지가 있으면 텍스트 다음에 이미지 블록을 순서대로 추가.
     * - [ImageInput.Bytes]: 헤더 매직 넘버로 mimeType 실 검증 (E-207) → base64 인코딩.
     * - [ImageInput.Url]: HTTP fetch (E-206) → 응답 바이트 → 매직 넘버 검증 (E-207) → base64.
     * - [ImageInput.Uri]: v0.1 매퍼는 처리 안 함 → E-203 [AiException.InvalidInput]("uri unreadable").
     *
     * F-004 Session.history 메시지 변환 시에도 동일 정책을 적용한다 — history에 포함된
     * [ImageInput.Bytes]는 base64로 재인코딩되어 전송되며, [ImageInput.Url]은 매 send마다 fetch된다.
     */
    private suspend fun buildMessageContentJson(
        text: String,
        images: List<ImageInput>,
        httpClient: OkHttpClient?,
    ): JsonElement {
        val blocks = mutableListOf<AnthropicContentRequestBlock>()

        // 텍스트 블록 (항상 1개)
        blocks.add(AnthropicContentRequestBlock.Text(text = text))

        // 이미지 블록 (0개 이상)
        for (image in images) {
            val imageBlock: AnthropicContentRequestBlock.Image = when (image) {
                is ImageInput.Bytes -> {
                    // E-207: 헤더 매직 넘버로 mimeType 실 검증
                    verifyMagicNumber(image.data, image.mimeType)
                    AnthropicContentRequestBlock.Image(
                        source = AnthropicImageSource(
                            type = SOURCE_TYPE_BASE64,
                            mediaType = image.mimeType,
                            data = encodeBase64(image.data),
                        ),
                    )
                }
                is ImageInput.Url -> {
                    val fetched: ByteArray = fetchUrlBytes(image.url, httpClient)
                    // mimeType은 SDK가 제공한 것을 신뢰하지 않고 헤더에서 유추.
                    // R-023: SDK는 SSRF 방어 안 함, 형식 검증만. 응답 바이트의 매직 넘버로 mimeType 결정.
                    val derivedMime = detectMimeFromMagic(fetched)
                        ?: throw AiException.InvalidInput(
                            "image decode failed: cannot detect mime type from fetched bytes",
                        )
                    AnthropicContentRequestBlock.Image(
                        source = AnthropicImageSource(
                            type = SOURCE_TYPE_BASE64,
                            mediaType = derivedMime,
                            data = encodeBase64(fetched),
                        ),
                    )
                }
                is ImageInput.Uri -> {
                    // E-203: v0.1 매퍼는 Uri 직접 처리 안 함 (ContentResolver 책임은 호출자/Session 레이어).
                    // ask 진입 시 검증 단계에서 차단되도록 정책되어 있으나, 매퍼 도달 시 안전망으로 거부.
                    throw AiException.InvalidInput(
                        "uri unreadable: SDK does not auto-resolve Uri (call ContentResolver before ask)",
                    )
                }
            }
            blocks.add(imageBlock)
        }

        // sealed AnthropicContentRequestBlock 배열을 JsonElement로 직렬화
        // (kotlinx-serialization polymorphic — @SerialName "text"/"image"가 type discriminator)
        val serializer = ListSerializer(AnthropicContentRequestBlock.serializer())
        return AnthropicJson.instance.encodeToJsonElement(serializer, blocks)
    }

    /**
     * Anthropic API 응답 → SDK 응답 변환 (F-001).
     *
     * 사양 참조:
     * - data-model.md M-002 (AiResponse 필드)
     * - features.md F-001 정상 흐름 4단계 — 빈 응답 검증은 호출 측이 수행
     */
    fun toAiResponse(
        response: AnthropicMessagesResponse,
        providerId: ProviderId,
    ): AiResponse {
        val text = response.content
            .filter { it.type == BLOCK_TYPE_TEXT }
            .joinToString(separator = "") { it.text.orEmpty() }

        val finishReason = when (response.stopReason) {
            STOP_END_TURN -> FinishReason.END_TURN
            STOP_MAX_TOKENS -> FinishReason.MAX_TOKENS
            STOP_STOP_SEQUENCE -> FinishReason.STOP_SEQUENCE
            else -> FinishReason.OTHER
        }

        val usage = TokenUsage(
            inputTokens = response.usage?.inputTokens ?: 0,
            outputTokens = response.usage?.outputTokens ?: 0,
        )

        return AiResponse(
            text = text,
            usage = usage,
            finishReason = finishReason,
            providerId = providerId,
        )
    }

    // -----------------------------------------------------------------
    // 이미지 인코딩 헬퍼 (F-002)
    // -----------------------------------------------------------------

    /** ByteArray → base64 (no-wrap). java.util.Base64 사용 (Android API 26+, minSdk 24인데 desugar로 제공). */
    internal fun encodeBase64(data: ByteArray): String =
        Base64.getEncoder().encodeToString(data)

    /**
     * E-207: 헤더 매직 넘버로 [mimeType] 위조 검증.
     *
     * 화이트리스트(P-CLAUDE):
     * - image/jpeg: FF D8 FF ...
     * - image/png:  89 50 4E 47 0D 0A 1A 0A
     * - image/webp: "RIFF" .... "WEBP"
     * - image/gif:  "GIF87a" 또는 "GIF89a"
     *
     * 매직 넘버 미일치 시 [AiException.InvalidInput] (E-207) throw.
     */
    private fun verifyMagicNumber(data: ByteArray, mimeType: String) {
        val detected = detectMimeFromMagic(data)
        if (detected == null) {
            throw AiException.InvalidInput(
                "image decode failed: unknown magic number for declared mime '$mimeType'",
            )
        }
        if (detected != mimeType) {
            throw AiException.InvalidInput(
                "image decode failed: declared mime '$mimeType' does not match actual '$detected'",
            )
        }
    }

    /**
     * 헤더 매직 넘버로 mimeType 추정. 미인식 시 null.
     *
     * 본 함수는 verifyMagicNumber와 [ImageInput.Url] fetch 후 mimeType 결정에서 모두 사용된다.
     */
    internal fun detectMimeFromMagic(data: ByteArray): String? {
        // JPEG: FF D8 FF
        if (data.size >= 3 &&
            data[0] == 0xFF.toByte() &&
            data[1] == 0xD8.toByte() &&
            data[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (data.size >= 8 &&
            data[0] == 0x89.toByte() &&
            data[1] == 0x50.toByte() &&
            data[2] == 0x4E.toByte() &&
            data[3] == 0x47.toByte() &&
            data[4] == 0x0D.toByte() &&
            data[5] == 0x0A.toByte() &&
            data[6] == 0x1A.toByte() &&
            data[7] == 0x0A.toByte()
        ) {
            return "image/png"
        }
        // GIF: "GIF87a" 또는 "GIF89a"
        if (data.size >= 6 &&
            data[0] == 'G'.code.toByte() &&
            data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() &&
            data[3] == '8'.code.toByte() &&
            (data[4] == '7'.code.toByte() || data[4] == '9'.code.toByte()) &&
            data[5] == 'a'.code.toByte()
        ) {
            return "image/gif"
        }
        // WebP: "RIFF" + 4 bytes size + "WEBP"
        if (data.size >= 12 &&
            data[0] == 'R'.code.toByte() &&
            data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() &&
            data[3] == 'F'.code.toByte() &&
            data[8] == 'W'.code.toByte() &&
            data[9] == 'E'.code.toByte() &&
            data[10] == 'B'.code.toByte() &&
            data[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }
        return null
    }

    /**
     * E-206: [ImageInput.Url] HTTP fetch.
     *
     * - 4xx → [AiException.InvalidInput]("image url unreachable: HTTP $code")
     * - 5xx / timeout / DNS → [AiException.Network] (cause 보존)
     * - redirect는 OkHttp 기본 정책(최대 20개, 기능상 redirect 한계는 사양 F-002 NFR로 별도 명시되지만
     *   v0.1은 OkHttp 기본값에 위임)
     *
     * @param httpClient 호출자 [com.androidailab.aisdk.AiAgentClient.httpClient]. null이면 매퍼는
     *   URL fetch를 거부하고 [AiException.Configuration]을 throw 한다 (테스트/Hilt 미초기화 방어).
     */
    internal suspend fun fetchUrlBytes(
        url: String,
        httpClient: OkHttpClient?,
    ): ByteArray {
        if (httpClient == null) {
            throw AiException.Configuration("httpClient is not initialized for ImageInput.Url fetch")
        }

        val request = Request.Builder().url(url).get().build()
        val response: Response = try {
            executeForBytes(httpClient, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            // 5xx / timeout / DNS → Network (사양 E-206)
            throw AiException.Network(e)
        } catch (e: IOException) {
            // DNS 실패 등 → Network
            throw AiException.Network(e)
        }

        return response.use { resp ->
            if (!resp.isSuccessful) {
                val code = resp.code
                if (code in 500..599) {
                    throw AiException.Network(IOException("server error: $code"))
                }
                // 4xx → InvalidInput
                throw AiException.InvalidInput(
                    "image url unreachable: HTTP $code",
                )
            }
            val body = resp.body
                ?: throw AiException.InvalidInput("image url unreachable: empty body")
            body.bytes()
        }
    }

    /** OkHttp 호출을 코루틴-친화적으로 실행 (취소 cooperative). */
    private suspend fun executeForBytes(
        httpClient: OkHttpClient,
        request: Request,
    ): Response = suspendCancellableCoroutine { cont ->
        val call: Call = httpClient.newCall(request)
        cont.invokeOnCancellation {
            try {
                call.cancel()
            } catch (_: Throwable) {
                // best-effort
            }
        }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(e)
            }
        })
    }

    /** Anthropic API 의 user role 문자열. */
    private const val ROLE_USER: String = "user"

    /** Anthropic API 의 assistant role 문자열 (F-004 history 변환에 사용). */
    private const val ROLE_ASSISTANT: String = "assistant"

    private const val BLOCK_TYPE_TEXT: String = "text"
    private const val STOP_END_TURN: String = "end_turn"
    private const val STOP_MAX_TOKENS: String = "max_tokens"
    private const val STOP_STOP_SEQUENCE: String = "stop_sequence"

    /** F-002: Anthropic 이미지 source.type 값 — v0.1은 base64만 사용 (R-021/R-023). */
    private const val SOURCE_TYPE_BASE64: String = "base64"
}
