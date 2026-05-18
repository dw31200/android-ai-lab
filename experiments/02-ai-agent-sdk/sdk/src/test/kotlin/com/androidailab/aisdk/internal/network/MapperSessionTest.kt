package com.androidailab.aisdk.internal.network

import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.AiRequest
import com.androidailab.aisdk.model.ImageInput
import com.androidailab.aisdk.model.Message
import com.androidailab.aisdk.model.Role
import com.androidailab.aisdk.provider.ProviderConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * F-004 Mapper.toAnthropicRequestForSession + ErrorMapper.mapContextOverflow 단위 테스트.
 *
 * 사양 참조:
 * - features.md F-004 정상 흐름 2 ("이전 메시지 + 현재 요청을 합쳐 Provider 전송")
 * - features.md F-004 systemPrompt 정책 (R-008) — Session.systemPrompt → top-level `system` 필드
 * - features.md F-004 컨텍스트 한계 검증 알고리즘 (E-401)
 * - provider-spec.md P-CLAUDE 에러 매핑 (context_length_exceeded → E-401)
 * - data-model.md M-007 (Session) / M-008 (Message)
 *
 * 검증:
 * 1. toAnthropicRequestForSession — 빈 history + 현재 request → 단일 USER 메시지 (F-001 회귀와 동등)
 * 2. systemPrompt → AnthropicMessagesRequest.system 필드
 * 3. systemPrompt null → system 필드 미설정
 * 4. history(USER, ASSISTANT) + 현재 request → 3개 메시지 (순서 보장)
 * 5. history에 Role.SYSTEM이 있어도 무시 (R-008 방어적)
 * 6. Message.images(Bytes/Url) → image 블록 인코딩 (F-002 정책 재사용)
 * 7. history.images에 Uri → InvalidInput (E-203 안전망)
 * 8. ErrorMapper.mapContextOverflow — context_length_exceeded 메시지 감지 → InvalidInput("context too large")
 * 9. ErrorMapper.mapContextOverflow — 그 외 invalid_request_error 메시지 → null
 * 10. ErrorMapper.mapContextOverflow — 빈/null/잘못된 JSON → null
 */
class MapperSessionTest {

    private lateinit var httpClient: OkHttpClient

    @Before
    fun setUp() {
        httpClient = OkHttpClient.Builder().build()
    }

    private val config = ProviderConfig(
        apiKey = "sk-test",
        modelId = "claude-opus-4-7",
        timeout = 30.seconds,
    )

    // -----------------------------------------------------------------
    // toAnthropicRequestForSession — 빈 history
    // -----------------------------------------------------------------

    @Test
    fun `F-004 — 빈 history + 현재 request 는 단일 USER 메시지로 변환 (F-001 회귀 동등)`() = runTest {
        val mapped = Mapper.toAnthropicRequestForSession(
            history = emptyList(),
            request = AiRequest(prompt = "hi"),
            systemPrompt = null,
            config = config,
            httpClient = httpClient,
        )

        assertEquals(1, mapped.messages.size)
        assertEquals("user", mapped.messages[0].role)
        val arr = mapped.messages[0].content.jsonArray
        assertEquals(1, arr.size)
        assertEquals("text", arr[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("hi", arr[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    // -----------------------------------------------------------------
    // R-008: systemPrompt → Anthropic system 필드
    // -----------------------------------------------------------------

    @Test
    fun `F-004 R-008 — systemPrompt 가 AnthropicMessagesRequest_system 으로 매핑됨`() = runTest {
        val mapped = Mapper.toAnthropicRequestForSession(
            history = emptyList(),
            request = AiRequest(prompt = "hi"),
            systemPrompt = "You are helpful.",
            config = config,
            httpClient = httpClient,
        )

        assertEquals("You are helpful.", mapped.system)
    }

    @Test
    fun `F-004 R-008 — systemPrompt null 이면 system 필드 미설정`() = runTest {
        val mapped = Mapper.toAnthropicRequestForSession(
            history = emptyList(),
            request = AiRequest(prompt = "hi"),
            systemPrompt = null,
            config = config,
            httpClient = httpClient,
        )

        assertNull(mapped.system)
    }

    @Test
    fun `F-004 R-008 — JSON 직렬화 시 systemPrompt null 이면 system 필드 제외됨 (encodeDefaults false)`() = runTest {
        val mapped = Mapper.toAnthropicRequestForSession(
            history = emptyList(),
            request = AiRequest(prompt = "hi"),
            systemPrompt = null,
            config = config,
            httpClient = httpClient,
        )

        val json = AnthropicJson.instance.encodeToString(
            AnthropicMessagesRequest.serializer(),
            mapped,
        )
        // explicitNulls=false + null → 직렬화 제외
        assertTrue(
            "system field should not appear in JSON when null, got: $json",
            !json.contains("\"system\""),
        )
    }

    @Test
    fun `F-004 R-008 — JSON 직렬화 시 systemPrompt 가 system 필드로 출력됨`() = runTest {
        val mapped = Mapper.toAnthropicRequestForSession(
            history = emptyList(),
            request = AiRequest(prompt = "hi"),
            systemPrompt = "be brief",
            config = config,
            httpClient = httpClient,
        )

        val json = AnthropicJson.instance.encodeToString(
            AnthropicMessagesRequest.serializer(),
            mapped,
        )
        assertTrue(
            "system field should appear, got: $json",
            json.contains("\"system\":\"be brief\""),
        )
    }

    // -----------------------------------------------------------------
    // history 다중 메시지 변환
    // -----------------------------------------------------------------

    @Test
    fun `F-004 — history(USER, ASSISTANT) + 현재 request 는 3 메시지 순서 보장`() = runTest {
        val history = listOf(
            Message(role = Role.USER, content = "first question"),
            Message(role = Role.ASSISTANT, content = "first answer"),
        )

        val mapped = Mapper.toAnthropicRequestForSession(
            history = history,
            request = AiRequest(prompt = "second question"),
            systemPrompt = null,
            config = config,
            httpClient = httpClient,
        )

        assertEquals(3, mapped.messages.size)
        assertEquals("user", mapped.messages[0].role)
        assertEquals("first question", mapped.messages[0].content.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("assistant", mapped.messages[1].role)
        assertEquals("first answer", mapped.messages[1].content.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("user", mapped.messages[2].role)
        assertEquals("second question", mapped.messages[2].content.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `F-004 R-008 — history 에 Role_SYSTEM 메시지가 있어도 (방어적) 무시되어 messages 에서 제외`() = runTest {
        val history = listOf(
            Message(role = Role.SYSTEM, content = "system noise"),
            Message(role = Role.USER, content = "real question"),
        )

        val mapped = Mapper.toAnthropicRequestForSession(
            history = history,
            request = AiRequest(prompt = "current"),
            systemPrompt = null,
            config = config,
            httpClient = httpClient,
        )

        // SYSTEM은 제외되어 USER(history) + USER(current) = 2개 메시지
        assertEquals(2, mapped.messages.size)
        assertEquals("user", mapped.messages[0].role)
        assertEquals("real question", mapped.messages[0].content.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("user", mapped.messages[1].role)
        assertEquals("current", mapped.messages[1].content.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    // -----------------------------------------------------------------
    // Message.images 변환 (F-002 정책 재사용)
    // -----------------------------------------------------------------

    @Test
    fun `F-004 + F-002 — history Message_images(Bytes PNG) 가 image 블록으로 인코딩됨`() = runTest {
        val pngBytes = pngHeader() + byteArrayOf(0, 1, 2, 3, 4)
        val history = listOf(
            Message(
                role = Role.USER,
                content = "what's this?",
                images = listOf(ImageInput.Bytes(pngBytes, "image/png")),
            ),
            Message(role = Role.ASSISTANT, content = "I see a PNG."),
        )

        val mapped = Mapper.toAnthropicRequestForSession(
            history = history,
            request = AiRequest(prompt = "and now?"),
            systemPrompt = null,
            config = config,
            httpClient = httpClient,
        )

        assertEquals(3, mapped.messages.size)
        val firstUserContent: JsonArray = mapped.messages[0].content.jsonArray
        assertEquals(2, firstUserContent.size) // text + image
        assertEquals("text", firstUserContent[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("image", firstUserContent[1].jsonObject["type"]?.jsonPrimitive?.content)
        val source: JsonObject = firstUserContent[1].jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]?.jsonPrimitive?.content)
        assertEquals("image/png", source["media_type"]?.jsonPrimitive?.content)
        assertNotNull(source["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `F-004 + E-203 — history Message_images(Uri) 도달 시 InvalidInput`() = runTest {
        // 단위 테스트 환경에서는 android.net.Uri를 mockk로 대체 (MapperImageTest와 동일 패턴).
        val mockUri = io.mockk.mockk<android.net.Uri>(relaxed = true)
        val history = listOf(
            Message(
                role = Role.USER,
                content = "this image",
                images = listOf(ImageInput.Uri(mockUri)),
            ),
        )

        try {
            Mapper.toAnthropicRequestForSession(
                history = history,
                request = AiRequest(prompt = "next"),
                systemPrompt = null,
                config = config,
                httpClient = httpClient,
            )
            fail("expected InvalidInput E-203")
        } catch (e: AiException.InvalidInput) {
            assertTrue(
                "message should contain 'uri unreadable', actual=${e.message}",
                e.message?.contains("uri unreadable") == true,
            )
        }
    }

    // -----------------------------------------------------------------
    // maxTokens / temperature 매핑 (현재 request에서만)
    // -----------------------------------------------------------------

    @Test
    fun `F-004 — maxTokens 와 temperature 는 현재 request 에서 가져옴`() = runTest {
        val mapped = Mapper.toAnthropicRequestForSession(
            history = listOf(Message(Role.USER, "x"), Message(Role.ASSISTANT, "y")),
            request = AiRequest(prompt = "hi", maxTokens = 999, temperature = 0.1f),
            systemPrompt = null,
            config = config,
            httpClient = httpClient,
        )

        assertEquals(999, mapped.maxTokens)
        assertEquals(0.1f, mapped.temperature)
    }

    // -----------------------------------------------------------------
    // ErrorMapper.mapContextOverflow — E-401 감지
    // -----------------------------------------------------------------

    @Test
    fun `E-401 — context_length_exceeded 키워드가 포함된 invalid_request_error 는 InvalidInput(context too large)`() {
        val body = """
            {"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long for the context_length_exceeded model"}}
        """.trimIndent()

        val mapped = ErrorMapper.mapContextOverflow(body)

        assertNotNull("expected non-null mapping", mapped)
        assertEquals("context too large", mapped!!.message)
    }

    @Test
    fun `E-401 — 'context window' 표현도 감지됨`() {
        val body = """
            {"type":"error","error":{"type":"invalid_request_error","message":"The request exceeds the context window of this model."}}
        """.trimIndent()

        val mapped = ErrorMapper.mapContextOverflow(body)

        assertNotNull(mapped)
        assertEquals("context too large", mapped!!.message)
    }

    @Test
    fun `E-401 — 'prompt is too long' 표현도 감지됨`() {
        val body = """
            {"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 250000 tokens > 200000 maximum"}}
        """.trimIndent()

        val mapped = ErrorMapper.mapContextOverflow(body)

        assertNotNull(mapped)
        assertEquals("context too large", mapped!!.message)
    }

    @Test
    fun `E-401 — 컨텍스트 초과 키워드가 없는 invalid_request_error 는 null (다른 매핑에 위임)`() {
        val body = """
            {"type":"error","error":{"type":"invalid_request_error","message":"unsupported mime type"}}
        """.trimIndent()

        val mapped = ErrorMapper.mapContextOverflow(body)

        assertNull(mapped)
    }

    @Test
    fun `E-401 — invalid_request_error 가 아닌 error_type 은 null`() {
        val body = """
            {"type":"error","error":{"type":"authentication_error","message":"invalid api key (context_length_exceeded text in message)"}}
        """.trimIndent()

        val mapped = ErrorMapper.mapContextOverflow(body)

        // type이 invalid_request_error가 아니므로 null
        assertNull(mapped)
    }

    @Test
    fun `E-401 — null 본문은 null 반환`() {
        val mapped = ErrorMapper.mapContextOverflow(null)

        assertNull(mapped)
    }

    @Test
    fun `E-401 — 빈 본문은 null 반환`() {
        val mapped = ErrorMapper.mapContextOverflow("")

        assertNull(mapped)
    }

    @Test
    fun `E-401 — 잘못된 JSON 본문은 null 반환 (NumberFormatException 안전망)`() {
        val mapped = ErrorMapper.mapContextOverflow("not a json at all")

        assertNull(mapped)
    }

    @Test
    fun `E-401 — error 필드 없는 응답은 null 반환`() {
        val body = """{"type":"error"}"""

        val mapped = ErrorMapper.mapContextOverflow(body)

        assertNull(mapped)
    }

    @Test
    fun `E-401 — error_message 없는 응답은 null 반환`() {
        val body = """{"type":"error","error":{"type":"invalid_request_error"}}"""

        val mapped = ErrorMapper.mapContextOverflow(body)

        assertNull(mapped)
    }

    // -----------------------------------------------------------------
    // 헬퍼
    // -----------------------------------------------------------------

    private fun pngHeader(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
    )
}
