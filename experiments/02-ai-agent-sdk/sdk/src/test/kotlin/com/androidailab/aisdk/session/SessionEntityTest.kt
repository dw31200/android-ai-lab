package com.androidailab.aisdk.session

import com.androidailab.aisdk.internal.storage.SessionJson
import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.ImageInput
import com.androidailab.aisdk.model.Message
import com.androidailab.aisdk.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * F-007 (세션 영속화) SessionEntity 직렬화 단위 테스트.
 *
 * 사양 참조:
 * - data-model.md M-011 (SessionEntity / MessageEntity / ImageInputEntity)
 * - features.md F-007 정상 흐름 (저장/복원)
 * - features.md F-007 R-018 (schemaVersion=1 강제)
 * - features.md F-007 R-021 (이미지 영속화 운영 가이드 — Bytes는 base64로 보관)
 * - features.md F-007 R-023 (ImageInput.Url 영속화 보안 정책 — URL 그대로 보관)
 *
 * 본 테스트가 검증하는 것:
 * 1. SessionEntity round-trip — encode → decode 시 필드 동일
 * 2. R-018 — schemaVersion=1 강제, 다른 값은 검증 케이스(역직렬화는 통과하지만 SessionStore가 검출)
 * 3. systemPrompt null이면 직렬화 제외 (explicitNulls=false)
 * 4. ImageInputEntity sealed 직렬화 — Uri/Bytes/Url variants
 * 5. Message ↔ MessageEntity 변환 (R-008 Role.SYSTEM 차단)
 * 6. ImageInput ↔ ImageInputEntity 변환 (Bytes base64 round-trip)
 */
class SessionEntityTest {

    private val json = SessionJson.instance

    // -----------------------------------------------------------------
    // 1. SessionEntity round-trip — M-011
    // -----------------------------------------------------------------

    @Test
    fun `F-007 M-011 — SessionEntity 직렬화 round-trip 필드 동일`() {
        val entity = SessionEntity(
            schemaVersion = SessionEntity.SCHEMA_VERSION_V1,
            sessionId = "abc-123",
            systemPrompt = "be brief",
            history = listOf(
                MessageEntity(role = "USER", content = "hi", timestamp = 1000L),
                MessageEntity(role = "ASSISTANT", content = "hello", timestamp = 1100L),
            ),
            savedAt = 12345L,
        )

        val encoded = json.encodeToString(SessionEntity.serializer(), entity)
        val decoded = json.decodeFromString(SessionEntity.serializer(), encoded)

        assertEquals(entity, decoded)
        assertEquals(SessionEntity.SCHEMA_VERSION_V1, decoded.schemaVersion)
        assertEquals("abc-123", decoded.sessionId)
        assertEquals("be brief", decoded.systemPrompt)
        assertEquals(2, decoded.history.size)
        assertEquals(12345L, decoded.savedAt)
    }

    @Test
    fun `F-007 M-011 — 빈 history 도 round-trip 보존`() {
        val entity = SessionEntity(
            sessionId = "empty",
            systemPrompt = null,
            history = emptyList(),
            savedAt = 0L,
        )

        val encoded = json.encodeToString(SessionEntity.serializer(), entity)
        val decoded = json.decodeFromString(SessionEntity.serializer(), encoded)

        assertEquals(0, decoded.history.size)
        assertNull(decoded.systemPrompt)
    }

    // -----------------------------------------------------------------
    // 2. R-018 — schemaVersion 강제
    // -----------------------------------------------------------------

    @Test
    fun `F-007 R-018 — SessionEntity default schemaVersion 은 1`() {
        val entity = SessionEntity(
            sessionId = "s",
            history = emptyList(),
            savedAt = 0L,
        )
        assertEquals(1, entity.schemaVersion)
        assertEquals(SessionEntity.SCHEMA_VERSION_V1, entity.schemaVersion)
    }

    @Test
    fun `F-007 R-018 — schemaVersion 필드는 인코딩 시 명시적으로 포함됨 (encodeDefaults true)`() {
        val entity = SessionEntity(
            sessionId = "s",
            history = emptyList(),
            savedAt = 0L,
        )
        val encoded = json.encodeToString(SessionEntity.serializer(), entity)
        // 본 SessionJson 은 encodeDefaults=true 정책 (SessionJson 참조) — schemaVersion 필드가 JSON에 등장
        assertTrue(
            "expected schemaVersion in encoded JSON: $encoded",
            encoded.contains("\"schemaVersion\""),
        )
    }

    @Test
    fun `F-007 R-018 — schemaVersion = 2 로 디스크에 저장된 데이터는 decode 는 통과 (검증은 SessionStore 책임)`() {
        // 본 라운드 SessionEntity 직렬화 자체는 schemaVersion 검증을 안 한다 — DataStoreSessionStore.load가
        // E-703으로 거부한다 (R-018 정책). 본 테스트는 직렬화 계층의 의도 분리를 명시한다.
        val payload = """{"schemaVersion":2,"sessionId":"s","history":[],"savedAt":0}"""
        val decoded = json.decodeFromString(SessionEntity.serializer(), payload)
        assertEquals(2, decoded.schemaVersion)
    }

    // -----------------------------------------------------------------
    // 3. systemPrompt null이면 직렬화 제외 (explicitNulls=false)
    // -----------------------------------------------------------------

    @Test
    fun `F-007 M-011 — systemPrompt null 이면 JSON 에 systemPrompt 필드 제외 (explicitNulls false)`() {
        val entity = SessionEntity(
            sessionId = "s",
            systemPrompt = null,
            history = emptyList(),
            savedAt = 0L,
        )
        val encoded = json.encodeToString(SessionEntity.serializer(), entity)
        assertTrue(
            "systemPrompt should not appear when null: $encoded",
            !encoded.contains("systemPrompt"),
        )
    }

    @Test
    fun `F-007 M-011 — systemPrompt 가 있으면 JSON 에 systemPrompt 필드 등장`() {
        val entity = SessionEntity(
            sessionId = "s",
            systemPrompt = "hi",
            history = emptyList(),
            savedAt = 0L,
        )
        val encoded = json.encodeToString(SessionEntity.serializer(), entity)
        assertTrue(
            "systemPrompt should appear: $encoded",
            encoded.contains("\"systemPrompt\""),
        )
    }

    // -----------------------------------------------------------------
    // 4. ImageInputEntity sealed 직렬화 — M-011 Variants
    // -----------------------------------------------------------------

    @Test
    fun `F-007 M-011 — ImageInputEntity_Uri round-trip`() {
        val entity = SessionEntity(
            sessionId = "s",
            history = listOf(
                MessageEntity(
                    role = "USER",
                    content = "x",
                    images = listOf(ImageInputEntity.Uri("content://test/1")),
                    timestamp = 0L,
                ),
            ),
            savedAt = 0L,
        )
        val encoded = json.encodeToString(SessionEntity.serializer(), entity)
        val decoded = json.decodeFromString(SessionEntity.serializer(), encoded)

        val img = decoded.history[0].images[0]
        assertTrue(img is ImageInputEntity.Uri)
        assertEquals("content://test/1", (img as ImageInputEntity.Uri).uri)
    }

    @Test
    fun `F-007 M-011 R-021 — ImageInputEntity_Bytes round-trip (base64 보존)`() {
        val raw = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0xFF.toByte())
        val base64 = Base64.getEncoder().encodeToString(raw)
        val entity = SessionEntity(
            sessionId = "s",
            history = listOf(
                MessageEntity(
                    role = "USER",
                    content = "img",
                    images = listOf(ImageInputEntity.Bytes(base64, "image/png")),
                    timestamp = 0L,
                ),
            ),
            savedAt = 0L,
        )
        val encoded = json.encodeToString(SessionEntity.serializer(), entity)
        val decoded = json.decodeFromString(SessionEntity.serializer(), encoded)

        val img = decoded.history[0].images[0] as ImageInputEntity.Bytes
        assertEquals(base64, img.base64)
        assertEquals("image/png", img.mimeType)
        assertTrue(raw.contentEquals(Base64.getDecoder().decode(img.base64)))
    }

    @Test
    fun `F-007 M-011 R-023 — ImageInputEntity_Url round-trip (URL 그대로 보관)`() {
        val entity = SessionEntity(
            sessionId = "s",
            history = listOf(
                MessageEntity(
                    role = "USER",
                    content = "x",
                    images = listOf(ImageInputEntity.Url("https://example.com/a.png")),
                    timestamp = 0L,
                ),
            ),
            savedAt = 0L,
        )
        val encoded = json.encodeToString(SessionEntity.serializer(), entity)
        val decoded = json.decodeFromString(SessionEntity.serializer(), encoded)

        val img = decoded.history[0].images[0] as ImageInputEntity.Url
        assertEquals("https://example.com/a.png", img.url)
    }

    // -----------------------------------------------------------------
    // 5. Message ↔ MessageEntity 변환 (R-008 Role.SYSTEM 차단)
    // -----------------------------------------------------------------

    @Test
    fun `F-007 M-011 — Message_toEntity 와 MessageEntity_toMessage round-trip (USER)`() {
        val msg = Message(Role.USER, "hello", emptyList(), timestamp = 999L)
        val entity = msg.toEntity()
        assertEquals("USER", entity.role)
        assertEquals("hello", entity.content)
        assertEquals(999L, entity.timestamp)

        val back = entity.toMessage()
        assertEquals(msg.role, back.role)
        assertEquals(msg.content, back.content)
        assertEquals(msg.timestamp, back.timestamp)
    }

    @Test
    fun `F-007 R-008 — Message(Role_SYSTEM)_toEntity 는 InvalidInput throw (영속화 차단)`() {
        val msg = Message(Role.SYSTEM, "ignored", emptyList(), timestamp = 0L)
        try {
            msg.toEntity()
            fail("expected AiException.InvalidInput")
        } catch (e: AiException.InvalidInput) {
            assertTrue(e.message?.contains("SYSTEM") == true)
        }
    }

    @Test
    fun `F-007 E-703 — MessageEntity 의 알 수 없는 role 은 IOError (손상 데이터)`() {
        val entity = MessageEntity(role = "BOGUS", content = "x", timestamp = 0L)
        try {
            entity.toMessage()
            fail("expected AiException.IOError")
        } catch (e: AiException.IOError) {
            assertTrue(e.message?.contains("unknown role") == true)
        }
    }

    @Test
    fun `F-007 E-703 — MessageEntity_role = SYSTEM 도 안전망으로 IOError`() {
        // R-008 — SYSTEM은 영속화 대상이 아니므로 들어올 일이 없지만, 외부 손상 대비 안전망 검증.
        val entity = MessageEntity(role = "SYSTEM", content = "x", timestamp = 0L)
        try {
            entity.toMessage()
            fail("expected AiException.IOError")
        } catch (e: AiException.IOError) {
            assertNotNull(e.message)
        }
    }

    // -----------------------------------------------------------------
    // 6. ImageInput ↔ ImageInputEntity 변환 (R-021 Bytes base64)
    // -----------------------------------------------------------------

    @Test
    fun `F-007 R-021 — ImageInput_Bytes_toEntity 가 base64 인코딩 후 보관 (round-trip)`() {
        val raw = "hello-bytes".toByteArray(Charsets.UTF_8)
        val img = ImageInput.Bytes(raw, "image/jpeg")
        val entity = img.toEntity() as ImageInputEntity.Bytes

        assertEquals("image/jpeg", entity.mimeType)
        // base64 인코딩 결과는 결정적이어야 함
        assertEquals(Base64.getEncoder().encodeToString(raw), entity.base64)

        // 역변환 — Bytes 로 복원
        val back = entity.toImageInput() as ImageInput.Bytes
        assertTrue(raw.contentEquals(back.data))
        assertEquals("image/jpeg", back.mimeType)
    }

    @Test
    fun `F-007 R-023 — ImageInput_Url_toEntity 는 URL 문자열 그대로 보관 (SSRF 방어 미수행)`() {
        val img = ImageInput.Url("https://example.com/x.png")
        val entity = img.toEntity() as ImageInputEntity.Url
        assertEquals("https://example.com/x.png", entity.url)

        val back = entity.toImageInput() as ImageInput.Url
        assertEquals("https://example.com/x.png", back.url)
    }

    @Test
    fun `F-007 E-703 — ImageInputEntity_Bytes 의 손상된 base64 는 IOError`() {
        val entity = ImageInputEntity.Bytes("not-valid-base64!!!", "image/png")
        try {
            entity.toImageInput()
            fail("expected AiException.IOError")
        } catch (e: AiException.IOError) {
            assertTrue(e.message?.contains("base64") == true)
        }
    }
}
