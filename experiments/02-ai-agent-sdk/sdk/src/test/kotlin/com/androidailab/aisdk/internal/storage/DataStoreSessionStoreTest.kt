package com.androidailab.aisdk.internal.storage

import com.androidailab.aisdk.session.MessageEntity
import com.androidailab.aisdk.session.SessionEntity
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * F-007 (세션 영속화) DataStoreSessionStore 단위 검증.
 *
 * 사양 참조:
 * - features.md F-007 정상 흐름 / 데이터 크기 정책 (E-704 1MB)
 * - features.md F-007 R-018 schemaVersion=1 강제
 * - data-model.md M-011 직렬화 정책 (`session:{sessionId}` 키, JSON, UTF-8)
 *
 * 본 테스트의 한계:
 * - 실제 DataStore Preferences는 Android `Context` + 디스크 IO + 파일 시스템이 필요하므로,
 *   완전한 round-trip 테스트는 `androidTest` 영역(instrumentation)에서 수행해야 한다.
 * - 본 라운드(unitTest)에서는 [DataStoreSessionStore]의 정적 상수 / 키 정책 / 직렬화 한계 (E-704) /
 *   schemaVersion 정책 (R-018) / SessionJson 정책의 정합성을 검증한다.
 * - 실제 DataStore IO 동작은 androidTest 라운드에서 [DataStoreSessionStore]를 본격 검증.
 *
 * 검증 사항:
 * 1. DATASTORE_FILE_NAME 상수 — SDK 고유 이름
 * 2. KEY_PREFIX / keyFor — `"session:{sessionId}"` 정책 (M-011)
 * 3. MAX_SESSION_BYTES — 1MB 상수 정합 (E-704)
 * 4. SessionJson encodeDefaults=true (R-018 schemaVersion 직렬화 보장)
 * 5. SessionJson explicitNulls=false (M-011 systemPrompt null 제외)
 * 6. SessionJson ignoreUnknownKeys=true (v0.2 마이그레이션 대비)
 * 7. SessionEntity round-trip + UTF-8 byte 크기 검증 (E-704 < 1MB는 통과해야 함)
 * 8. schemaVersion != 1 인 SessionEntity 도 직렬화 통과 (검증 책임은 SessionStore.load)
 */
class DataStoreSessionStoreTest {

    // -----------------------------------------------------------------
    // 1. 상수 / 키 정책 — M-011
    // -----------------------------------------------------------------

    @Test
    fun `F-007 — DATASTORE_FILE_NAME 은 SDK 고유 이름 (호출자 앱 충돌 방지)`() {
        assertEquals("ai_agent_sdk_sessions", DataStoreSessionStore.DATASTORE_FILE_NAME)
    }

    @Test
    fun `F-007 M-011 — KEY_PREFIX 는 'session_' (M-011 'session_{sessionId}' 정책)`() {
        assertEquals("session:", DataStoreSessionStore.KEY_PREFIX)
    }

    @Test
    fun `F-007 M-011 — keyFor(sessionId) 는 'session_{sessionId}' 반환`() {
        assertEquals(
            "session:abc-123",
            DataStoreSessionStore.keyFor("abc-123"),
        )
        assertEquals(
            "session:",
            DataStoreSessionStore.keyFor(""),
        )
        // UUID-style 도 동일
        assertEquals(
            "session:550e8400-e29b-41d4-a716-446655440000",
            DataStoreSessionStore.keyFor("550e8400-e29b-41d4-a716-446655440000"),
        )
    }

    // -----------------------------------------------------------------
    // 2. MAX_SESSION_BYTES — E-704 1MB
    // -----------------------------------------------------------------

    @Test
    fun `F-007 E-704 — MAX_SESSION_BYTES 는 정확히 1MB (1024 * 1024)`() {
        assertEquals(1024 * 1024, DataStoreSessionStore.MAX_SESSION_BYTES)
        assertEquals(1_048_576, DataStoreSessionStore.MAX_SESSION_BYTES)
    }

    // -----------------------------------------------------------------
    // 3. SessionJson 정책 — R-018 / M-011
    // -----------------------------------------------------------------

    @Test
    fun `F-007 R-018 — SessionJson_encodeDefaults true (schemaVersion 직렬화 보장)`() {
        // schemaVersion default 값 (1)이 JSON에 등장해야 한다 — load 측에서 검증할 수 있도록.
        val entity = SessionEntity(
            sessionId = "s",
            history = emptyList(),
            savedAt = 0L,
        )
        val encoded = SessionJson.instance.encodeToString(SessionEntity.serializer(), entity)
        assertTrue(
            "schemaVersion default 1 must be encoded: $encoded",
            encoded.contains("\"schemaVersion\""),
        )
    }

    @Test
    fun `F-007 M-011 — SessionJson_explicitNulls false (systemPrompt null 제외)`() {
        val entity = SessionEntity(
            sessionId = "s",
            systemPrompt = null,
            history = emptyList(),
            savedAt = 0L,
        )
        val encoded = SessionJson.instance.encodeToString(SessionEntity.serializer(), entity)
        assertFalse(
            "systemPrompt null must not appear: $encoded",
            encoded.contains("systemPrompt"),
        )
    }

    @Test
    fun `F-007 R-022 — SessionJson_ignoreUnknownKeys true (v0_2 마이그레이션 대비)`() {
        // 알 수 없는 필드("extraField")가 있어도 decode 통과 — 향후 마이그레이션 시 안전망.
        val payload = """{"schemaVersion":1,"sessionId":"s","history":[],"savedAt":0,"extraField":"future"}"""
        val decoded = SessionJson.instance.decodeFromString(SessionEntity.serializer(), payload)
        assertEquals("s", decoded.sessionId)
    }

    // -----------------------------------------------------------------
    // 4. round-trip + UTF-8 byte 크기
    // -----------------------------------------------------------------

    @Test
    fun `F-007 — 일반 크기 SessionEntity 의 JSON UTF-8 byte 크기 는 1MB 이하`() {
        val entity = SessionEntity(
            sessionId = "session-1",
            systemPrompt = "be brief",
            history = listOf(
                MessageEntity(role = "USER", content = "Hello, world!", timestamp = 1L),
                MessageEntity(role = "ASSISTANT", content = "Hi there!", timestamp = 2L),
            ),
            savedAt = 1234567890L,
        )
        val encoded = SessionJson.instance.encodeToString(SessionEntity.serializer(), entity)
        val bytes = encoded.toByteArray(Charsets.UTF_8).size

        assertTrue(
            "small SessionEntity must fit under 1MB: $bytes bytes",
            bytes < DataStoreSessionStore.MAX_SESSION_BYTES,
        )
    }

    @Test
    fun `F-007 E-704 — 매우 큰 history (각 메시지 100KB x 12) 는 1MB 초과 가능성`() {
        // E-704 시나리오 — 단위 테스트에서 직접 1MB 초과 데이터를 만들어 SessionJson 인코딩 후 크기 검증.
        val bigContent = "A".repeat(100_000) // 100KB
        val manyMessages = (1..12).map { i ->
            MessageEntity(role = "USER", content = bigContent, timestamp = i.toLong())
        }
        val entity = SessionEntity(
            sessionId = "big",
            history = manyMessages,
            savedAt = 0L,
        )
        val encoded = SessionJson.instance.encodeToString(SessionEntity.serializer(), entity)
        val bytes = encoded.toByteArray(Charsets.UTF_8).size

        // 100KB * 12 = 1.2MB + 오버헤드 → 1MB 초과 보장
        assertTrue(
            "large history must exceed 1MB: $bytes bytes",
            bytes > DataStoreSessionStore.MAX_SESSION_BYTES,
        )
        // 본 시나리오에서 DataStoreSessionStore.save 가 호출되면 E-704 InvalidInput throw 예정.
    }

    // -----------------------------------------------------------------
    // 5. schemaVersion 직렬화 — R-018 검증 책임 분리
    // -----------------------------------------------------------------

    @Test
    fun `F-007 R-018 — schemaVersion = 2 인 entity 도 직렬화 통과 (검증은 SessionStore_load 책임)`() {
        // SessionEntity 자체는 schemaVersion 검증을 하지 않는다 — DataStoreSessionStore.load가 R-018을 강제.
        // 본 테스트는 직렬화 계층의 의도 분리(serialization vs validation)를 명시한다.
        val entity = SessionEntity(
            schemaVersion = 2,
            sessionId = "future",
            history = emptyList(),
            savedAt = 0L,
        )
        val encoded = SessionJson.instance.encodeToString(SessionEntity.serializer(), entity)
        assertTrue(
            "schemaVersion=2 should be encoded explicitly: $encoded",
            encoded.contains("\"schemaVersion\":2"),
        )
    }

    // -----------------------------------------------------------------
    // 6. 잘못된 JSON 역직렬화 — E-703 후보
    // -----------------------------------------------------------------

    @Test
    fun `F-007 E-703 — 잘못된 JSON 본문은 SerializationException throw (SessionStore 가 IOError 로 변환)`() {
        // SessionStore.load 가 IOError("session data corrupted")로 변환하는 케이스. 본 테스트는 그 전 단계 검증.
        try {
            SessionJson.instance.decodeFromString(SessionEntity.serializer(), "{ not json }")
            fail("expected SerializationException")
        } catch (e: SerializationException) {
            assertNotNull(e.message)
        } catch (e: IllegalArgumentException) {
            // kotlinx-serialization 일부 구조 오류는 IllegalArgumentException
            assertNotNull(e.message)
        }
    }

    @Test
    fun `F-007 E-703 — 필수 필드 누락 (sessionId 없음) 도 SerializationException`() {
        try {
            // history / savedAt 만 있고 sessionId 누락
            SessionJson.instance.decodeFromString(
                SessionEntity.serializer(),
                """{"schemaVersion":1,"history":[],"savedAt":0}""",
            )
            fail("expected SerializationException")
        } catch (e: SerializationException) {
            assertNotNull(e.message)
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }
}
