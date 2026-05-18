package com.androidailab.aisdk.internal.network

import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.AiRequest
import com.androidailab.aisdk.model.ImageInput
import com.androidailab.aisdk.provider.ProviderConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

/**
 * F-002 / Mapper 이미지 블록 변환 단위 테스트.
 *
 * 사양 참조:
 * - features.md F-002 정상 흐름 3단계 (이미지를 base64로 인코딩 후 Provider 형식 변환)
 * - features.md F-002 E-206 (URL fetch 실패) / E-207 (디코딩 실패)
 * - data-model.md M-003 (ImageInput sealed)
 * - provider-spec.md P-CLAUDE 이미지 형식 (base64 source)
 *
 * 검증:
 * 1. 텍스트 단발 → 단일 text 블록 배열 (F-001 회귀)
 * 2. ImageInput.Bytes (PNG 매직 넘버) → text + image(base64) 블록 배열
 * 3. ImageInput.Bytes (JPEG/WebP/GIF) 매직 넘버 검증 통과
 * 4. base64 인코딩이 round-trip 일치 (디코드 결과 == 원본 ByteArray)
 * 5. E-207 — 매직 넘버 위조(JPEG declared, 실제 PNG) → InvalidInput("image decode failed")
 * 6. E-206 — Url fetch HTTP 404 → InvalidInput("image url unreachable")
 * 7. E-206 — Url fetch HTTP 500 → Network
 * 8. Url fetch 정상 흐름 → image 블록 (응답 바이트의 매직 넘버로 mimeType 결정)
 * 9. ImageInput.Uri 도달 → InvalidInput("uri unreadable") (E-203 안전망)
 */
class MapperImageTest {

    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        httpClient = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val config = ProviderConfig(
        apiKey = "sk-test",
        modelId = "claude-opus-4-7",
        timeout = 30.seconds,
    )

    // -----------------------------------------------------------------
    // 1. 텍스트 단발 → 단일 text 블록 (F-001 회귀)
    // -----------------------------------------------------------------

    @Test
    fun `F-001 회귀 — 텍스트만 있을 때 content 는 단일 text 블록 배열`() = runTest {
        val mapped = Mapper.toAnthropicRequest(
            request = AiRequest(prompt = "hello"),
            config = config,
            httpClient = httpClient,
        )

        val arr: JsonArray = mapped.messages[0].content.jsonArray
        assertEquals(1, arr.size)
        val first: JsonObject = arr[0].jsonObject
        assertEquals("text", first["type"]?.jsonPrimitive?.content)
        assertEquals("hello", first["text"]?.jsonPrimitive?.content)
    }

    // -----------------------------------------------------------------
    // 2. ImageInput.Bytes (PNG) → text + image(base64) 블록
    // -----------------------------------------------------------------

    @Test
    fun `F-002 — Bytes(PNG) 첨부 시 content 는 text + image 블록`() = runTest {
        val pngBytes = pngHeader() + byteArrayOf(0, 1, 2, 3, 4)
        val mapped = Mapper.toAnthropicRequest(
            request = AiRequest(
                prompt = "what?",
                images = listOf(ImageInput.Bytes(pngBytes, "image/png")),
            ),
            config = config,
            httpClient = httpClient,
        )

        val arr = mapped.messages[0].content.jsonArray
        assertEquals(2, arr.size)
        // [0] text
        assertEquals("text", arr[0].jsonObject["type"]?.jsonPrimitive?.content)
        // [1] image
        val imageBlock = arr[1].jsonObject
        assertEquals("image", imageBlock["type"]?.jsonPrimitive?.content)
        val source = imageBlock["source"]!!.jsonObject
        assertEquals("base64", source["type"]?.jsonPrimitive?.content)
        assertEquals("image/png", source["media_type"]?.jsonPrimitive?.content)
        // base64 디코드가 원본 일치 (round-trip)
        val encoded = source["data"]?.jsonPrimitive?.content
        assertNotNull(encoded)
        val decoded = Base64.getDecoder().decode(encoded!!)
        assertTrue("base64 round-trip 일치", decoded.contentEquals(pngBytes))
    }

    // -----------------------------------------------------------------
    // 3. JPEG/WebP/GIF 매직 넘버 통과
    // -----------------------------------------------------------------

    @Test
    fun `F-002 — JPEG 매직 넘버 통과`() = runTest {
        val data = jpegHeader() + byteArrayOf(0, 1, 2)
        val mapped = Mapper.toAnthropicRequest(
            request = AiRequest(
                prompt = "x",
                images = listOf(ImageInput.Bytes(data, "image/jpeg")),
            ),
            config = config,
            httpClient = httpClient,
        )
        val source = mapped.messages[0].content.jsonArray[1].jsonObject["source"]!!.jsonObject
        assertEquals("image/jpeg", source["media_type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `F-002 — WebP 매직 넘버 통과`() = runTest {
        val data = webpHeader() + byteArrayOf(0)
        val mapped = Mapper.toAnthropicRequest(
            request = AiRequest(
                prompt = "x",
                images = listOf(ImageInput.Bytes(data, "image/webp")),
            ),
            config = config,
            httpClient = httpClient,
        )
        val source = mapped.messages[0].content.jsonArray[1].jsonObject["source"]!!.jsonObject
        assertEquals("image/webp", source["media_type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `F-002 — GIF 매직 넘버 통과`() = runTest {
        val data = gifHeader89a() + byteArrayOf(0)
        val mapped = Mapper.toAnthropicRequest(
            request = AiRequest(
                prompt = "x",
                images = listOf(ImageInput.Bytes(data, "image/gif")),
            ),
            config = config,
            httpClient = httpClient,
        )
        val source = mapped.messages[0].content.jsonArray[1].jsonObject["source"]!!.jsonObject
        assertEquals("image/gif", source["media_type"]?.jsonPrimitive?.content)
    }

    // -----------------------------------------------------------------
    // 5. E-207 — 매직 넘버 위조
    // -----------------------------------------------------------------

    @Test
    fun `F-002 E-207 — declared mime 와 실제 매직 넘버 불일치 시 InvalidInput`() = runTest {
        // declared image/jpeg, 실제 PNG 헤더
        val mismatched = pngHeader() + byteArrayOf(0, 1)
        try {
            Mapper.toAnthropicRequest(
                request = AiRequest(
                    prompt = "x",
                    images = listOf(ImageInput.Bytes(mismatched, "image/jpeg")),
                ),
                config = config,
                httpClient = httpClient,
            )
            fail("E-207: 매직 넘버 불일치는 InvalidInput 으로 거부되어야 함")
        } catch (e: AiException.InvalidInput) {
            assertTrue(
                "메시지에 'image decode failed' 포함",
                e.message?.contains("image decode failed", ignoreCase = true) == true,
            )
        }
    }

    @Test
    fun `F-002 E-207 — 알 수 없는 매직 넘버 (random bytes) 면 InvalidInput`() = runTest {
        val random = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09)
        try {
            Mapper.toAnthropicRequest(
                request = AiRequest(
                    prompt = "x",
                    images = listOf(ImageInput.Bytes(random, "image/png")),
                ),
                config = config,
                httpClient = httpClient,
            )
            fail("E-207: 알 수 없는 매직 넘버는 InvalidInput 으로 거부되어야 함")
        } catch (e: AiException.InvalidInput) {
            assertTrue(e.message?.contains("image decode failed", ignoreCase = true) == true)
        }
    }

    // -----------------------------------------------------------------
    // 6. E-206 — Url fetch 실패 (Mapper.fetchUrlBytes 직접 검증)
    //
    // 사양 참조: ImageInput.Url 의 init 은 https:// 스킴을 강제 (R-023). MockWebServer 는 기본 http
    // 이므로 ImageInput.Url 인스턴스화 자체가 거부된다. 따라서 본 절은 [Mapper.fetchUrlBytes]
    // (internal) 를 직접 호출하여 fetch 시 매핑을 검증한다 (https 형식 검증은 ImageInputTest 에서 별도).
    // -----------------------------------------------------------------

    @Test
    fun `F-002 E-206 — fetchUrlBytes HTTP 404 → InvalidInput("image url unreachable")`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val url = server.url("/missing.png").toString()

        try {
            Mapper.fetchUrlBytes(url, httpClient)
            fail("E-206: 4xx 는 InvalidInput 으로 거부되어야 함")
        } catch (e: AiException.InvalidInput) {
            assertTrue(
                "메시지에 'image url unreachable' 포함",
                e.message?.contains("image url unreachable", ignoreCase = true) == true,
            )
            assertTrue("HTTP code 메시지 포함", e.message?.contains("404") == true)
        }
    }

    @Test
    fun `F-002 E-206 — fetchUrlBytes HTTP 503 → AiException_Network`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val url = server.url("/server-error.png").toString()

        try {
            Mapper.fetchUrlBytes(url, httpClient)
            fail("E-206: 5xx 는 Network 로 거부되어야 함")
        } catch (e: AiException.Network) {
            // 통과
            assertNotNull(e.cause)
        }
    }

    @Test
    fun `F-002 E-206 — fetchUrlBytes httpClient 가 null 이면 Configuration`() = runTest {
        try {
            Mapper.fetchUrlBytes("https://example.com/img.png", null)
            fail("httpClient null 은 Configuration 로 거부되어야 함")
        } catch (e: AiException.Configuration) {
            assertTrue(e.message?.contains("httpClient") == true)
        }
    }

    // -----------------------------------------------------------------
    // 8. fetchUrlBytes 정상 흐름
    // -----------------------------------------------------------------

    @Test
    fun `F-002 — fetchUrlBytes 정상 응답은 응답 바이트 그대로 반환 (이후 매직 넘버 검증)`() = runTest {
        val pngBody = pngHeader() + byteArrayOf(0xA, 0xB, 0xC, 0xD)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(pngBody)),
        )
        val url = server.url("/img.png").toString()

        val fetched = Mapper.fetchUrlBytes(url, httpClient)
        assertTrue("응답 바이트 일치", fetched.contentEquals(pngBody))
        // 매직 넘버 검출도 기대대로
        assertEquals("image/png", Mapper.detectMimeFromMagic(fetched))
    }

    // -----------------------------------------------------------------
    // 9. ImageInput.Uri 도달 안전망
    // -----------------------------------------------------------------

    @Test
    fun `F-002 E-203 — ImageInput_Uri 도달 시 InvalidInput("uri unreadable") (Mapper 안전망)`() = runTest {
        val mockUri = io.mockk.mockk<android.net.Uri>(relaxed = true)
        try {
            Mapper.toAnthropicRequest(
                request = AiRequest(
                    prompt = "x",
                    images = listOf(ImageInput.Uri(mockUri)),
                ),
                config = config,
                httpClient = httpClient,
            )
            fail("E-203: ImageInput.Uri 는 Mapper 에서 거부되어야 함")
        } catch (e: AiException.InvalidInput) {
            assertTrue(
                "메시지에 'uri unreadable' 포함",
                e.message?.contains("uri unreadable", ignoreCase = true) == true,
            )
        }
    }

    // -----------------------------------------------------------------
    // 헬퍼 — 매직 넘버 헤더
    // -----------------------------------------------------------------

    private fun pngHeader(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private fun jpegHeader(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
    )

    /** WebP: "RIFF" + 4 bytes filesize + "WEBP" */
    private fun webpHeader(): ByteArray = "RIFF".toByteArray() +
        byteArrayOf(0x10, 0x00, 0x00, 0x00) +
        "WEBP".toByteArray()

    /** GIF89a header. */
    private fun gifHeader89a(): ByteArray = "GIF89a".toByteArray()
}
