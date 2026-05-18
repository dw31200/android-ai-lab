package com.androidailab.aisdk.client

import android.content.Context
import com.androidailab.aisdk.AiAgentClient
import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.AiRequest
import com.androidailab.aisdk.model.AiResponse
import com.androidailab.aisdk.model.AiStreamEvent
import com.androidailab.aisdk.model.FinishReason
import com.androidailab.aisdk.model.ProviderId
import com.androidailab.aisdk.model.Role
import com.androidailab.aisdk.model.TokenUsage
import com.androidailab.aisdk.provider.Capabilities
import com.androidailab.aisdk.provider.Provider
import com.androidailab.aisdk.provider.ProviderConfig
import com.androidailab.aisdk.provider.ProviderRegistry
import com.androidailab.aisdk.provider.claude.ClaudeProvider
import com.androidailab.aisdk.session.MessageEntity
import com.androidailab.aisdk.session.Session
import com.androidailab.aisdk.session.SessionEntity
import com.androidailab.aisdk.session.SessionStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * F-007 (세션 영속화) AiAgentClient.loadSession 단위 테스트.
 *
 * 사양 참조:
 * - api.md A-011 (loadSession 시그니처: `suspend fun loadSession(sessionId: String): Result<Session>`)
 * - features.md F-007 복원 흐름 1~5
 * - features.md F-007 R-018 (schemaVersion=1만 인정 → E-703)
 * - features.md F-007 R-019 (다중 인스턴스 정책 — 두 번 호출 시 두 인스턴스)
 * - features.md F-007 E-702 / E-703 / E-705 / E-706
 * - features.md F-008 R-020 케이스 A/B (close 시맨틱)
 * - error-handling.md ERR-004 (E-705) / ERR-005 (E-702) / ERR-007 (E-703)
 *
 * 검증 사항:
 * 1. 정상 흐름 — SessionStore.load → SessionEntity → Session 복원
 * 2. E-702 — sessionId 미존재 → Result.failure(InvalidInput("session not found"))
 * 3. E-703 — SessionStore가 IOError throw (손상/schemaVersion) → Result.failure(IOError)
 * 4. E-705 — close 후 loadSession → Result.failure(Configuration("client closed"))
 * 5. E-706 — load 도중 cancel → CancellationException 그대로 전파
 * 6. R-019 — 같은 sessionId로 두 번 호출하면 서로 다른 Session 인스턴스 반환
 * 7. 복원된 Session 의 history/systemPrompt 가 SessionEntity 와 일치
 * 8. sessionStore 미설정 → IOError("session store not configured")
 */
class LoadSessionTest {

    private lateinit var context: Context
    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = mockk(relaxed = true)
        context = mockk(relaxed = true) {
            every { applicationContext } returns appContext
        }
    }

    // -----------------------------------------------------------------
    // 1. 정상 흐름
    // -----------------------------------------------------------------

    @Test
    fun `F-007 A-011 정상 흐름 — loadSession 성공 시 Result_success(Session) 반환`() = runTest {
        val entity = SessionEntity(
            sessionId = "session-1",
            systemPrompt = "be brief",
            history = listOf(
                MessageEntity(role = "USER", content = "q1", timestamp = 1L),
                MessageEntity(role = "ASSISTANT", content = "a1", timestamp = 2L),
            ),
            savedAt = 100L,
        )
        val store = FakeSessionStore(onLoad = { id -> if (id == "session-1") entity else null })
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)

        val result = client.loadSession("session-1")

        assertTrue(result.isSuccess)
        val session = result.getOrThrow()
        assertEquals("session-1", session.sessionId)
        val history = session.history()
        assertEquals(2, history.size)
        assertEquals(Role.USER, history[0].role)
        assertEquals("q1", history[0].content)
        assertEquals(Role.ASSISTANT, history[1].role)
        assertEquals("a1", history[1].content)
    }

    @Test
    fun `F-007 A-011 정상 흐름 — 빈 history 의 SessionEntity 도 정상 복원`() = runTest {
        val entity = SessionEntity(
            sessionId = "empty",
            systemPrompt = null,
            history = emptyList(),
            savedAt = 0L,
        )
        val store = FakeSessionStore(onLoad = { entity })
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)

        val result = client.loadSession("empty")
        assertTrue(result.isSuccess)
        val session = result.getOrThrow()
        assertEquals("empty", session.sessionId)
        assertTrue(session.history().isEmpty())
    }

    @Test
    fun `F-007 A-011 — 복원된 Session 으로 send 가능 (R-014 — 호출 시점 active Provider 사용)`() = runTest {
        val entity = SessionEntity(
            sessionId = "s",
            history = emptyList(),
            savedAt = 0L,
        )
        val store = FakeSessionStore(onLoad = { entity })
        val provider = FakeClaudeLike(
            onSessionComplete = { _, _, _, _ ->
                AiResponse("hello", TokenUsage(0, 0), FinishReason.END_TURN, ProviderId.CLAUDE)
            },
        )
        val client = newClient(provider, store = store)

        val session = client.loadSession("s").getOrThrow()
        val sendResult = session.send(AiRequest("hi"))

        assertTrue(sendResult.isSuccess)
    }

    // -----------------------------------------------------------------
    // 2. E-702 — sessionId 미존재
    // -----------------------------------------------------------------

    @Test
    fun `F-007 E-702 — SessionStore_load 가 null 반환 시 Result_failure(InvalidInput(session not found))`() = runTest {
        val store = FakeSessionStore(onLoad = { null })
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)

        val result = client.loadSession("nope")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("expected InvalidInput", ex is AiException.InvalidInput)
        assertTrue("message must include sessionId", ex?.message?.contains("nope") == true)
        assertTrue(ex?.message?.contains("session not found") == true)
    }

    // -----------------------------------------------------------------
    // 3. E-703 — 손상된 데이터 / schemaVersion 미지원
    // -----------------------------------------------------------------

    @Test
    fun `F-007 E-703 — SessionStore 가 IOError(session data corrupted) throw 시 Result_failure(IOError)`() = runTest {
        val store = FakeSessionStore(onLoad = {
            throw AiException.IOError("session data corrupted")
        })
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)

        val result = client.loadSession("s")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AiException.IOError)
        assertTrue(ex?.message?.contains("corrupted") == true)
    }

    @Test
    fun `F-007 E-703 R-018 — SessionStore 가 IOError(schema unsupported) throw 시 Result_failure(IOError)`() = runTest {
        val store = FakeSessionStore(onLoad = {
            throw AiException.IOError("session schema unsupported: v=2")
        })
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)

        val result = client.loadSession("s")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AiException.IOError)
        assertTrue(ex?.message?.contains("schema unsupported") == true)
        assertTrue(ex?.message?.contains("v=2") == true)
    }

    // -----------------------------------------------------------------
    // 4. E-705 — close 후 호출
    // -----------------------------------------------------------------

    @Test
    fun `F-007 E-705 R-020 케이스 B — close 후 loadSession → Result_failure(Configuration(client closed))`() = runTest {
        val store = FakeSessionStore(onLoad = { fail("should not be called after close"); null })
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)

        client.close()

        val result = client.loadSession("s")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AiException.Configuration)
        assertEquals("client closed", ex?.message)
        // close 후 SessionStore.load 는 호출 안 됨
        assertEquals(0, store.loadCalls.size)
    }

    // -----------------------------------------------------------------
    // 5. E-706 — 취소 시맨틱
    // -----------------------------------------------------------------

    @Test
    fun `F-007 E-706 R-020 케이스 A — load 도중 cancel 시 CancellationException 그대로 전파`() = runTest {
        val loadStarted = CompletableDeferred<Unit>()
        val store = FakeSessionStore(onLoad = {
            loadStarted.complete(Unit)
            kotlinx.coroutines.delay(60_000)
            null
        })
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)

        val job = async { client.loadSession("s") }
        loadStarted.await()
        job.cancel()

        try {
            job.await()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // 정상
        }
    }

    // -----------------------------------------------------------------
    // 6. R-019 다중 인스턴스 정책
    // -----------------------------------------------------------------

    @Test
    fun `F-007 R-019 — 같은 sessionId 로 두 번 loadSession 호출 시 서로 다른 Session 인스턴스 반환`() = runTest {
        val entity = SessionEntity(
            sessionId = "twin",
            history = emptyList(),
            savedAt = 0L,
        )
        val store = FakeSessionStore(onLoad = { entity })
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)

        val s1 = client.loadSession("twin").getOrThrow()
        val s2 = client.loadSession("twin").getOrThrow()

        // 동일 sessionId
        assertEquals("twin", s1.sessionId)
        assertEquals("twin", s2.sessionId)
        // 서로 다른 인스턴스 (R-019 다중 인스턴스 정책)
        assertNotSame(s1, s2)
    }

    // -----------------------------------------------------------------
    // 7. sessionStore 미설정 안전망
    // -----------------------------------------------------------------

    @Test
    fun `F-007 — sessionStore 미설정 시 Result_failure(IOError(session store not configured))`() = runTest {
        val client = AiAgentClient(
            context = context,
            apiKey = "sk-test",
            initialProviderId = ProviderId.CLAUDE,
            modelId = "claude-opus-4-7",
            timeout = 30.seconds,
            providerRegistry = ProviderRegistry(setOf(FakeProvider(ProviderId.CLAUDE))),
            sessionStore = null,
        )

        val result = client.loadSession("s")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AiException.IOError)
        assertTrue(ex?.message?.contains("session store not configured") == true)
    }

    // -----------------------------------------------------------------
    // 헬퍼
    // -----------------------------------------------------------------

    private fun newClient(
        provider: Provider,
        store: SessionStore? = null,
        apiKey: String = "sk-test",
        modelId: String = "claude-opus-4-7",
        timeout: Duration = 30.seconds,
    ): AiAgentClient = AiAgentClient(
        context = context,
        apiKey = apiKey,
        initialProviderId = provider.id,
        modelId = modelId,
        timeout = timeout,
        providerRegistry = ProviderRegistry(setOf(provider)),
        sessionStore = store,
    )

    private class FakeSessionStore(
        private val onSave: suspend (SessionEntity) -> Unit = { },
        private val onLoad: suspend (String) -> SessionEntity? = { null },
        private val onDelete: suspend (String) -> Unit = { },
    ) : SessionStore {
        val saveCalls = mutableListOf<SessionEntity>()
        val loadCalls = mutableListOf<String>()
        val deleteCalls = mutableListOf<String>()

        override suspend fun save(entity: SessionEntity) {
            saveCalls.add(entity)
            onSave(entity)
        }

        override suspend fun load(sessionId: String): SessionEntity? {
            loadCalls.add(sessionId)
            return onLoad(sessionId)
        }

        override suspend fun delete(sessionId: String) {
            deleteCalls.add(sessionId)
            onDelete(sessionId)
        }
    }

    private class FakeProvider(
        override val id: ProviderId,
    ) : Provider {
        override val capabilities: Capabilities = Capabilities(
            supportsImage = true,
            supportsVideo = false,
            supportsStream = false,
            supportsSession = false,
            maxImageSizeBytes = 5L * 1024 * 1024,
            maxImagesPerRequest = 10,
            supportedImageMimeTypes = setOf("image/jpeg", "image/png", "image/webp", "image/gif"),
        )

        override suspend fun complete(request: AiRequest, config: ProviderConfig): AiResponse =
            AiResponse("ok", TokenUsage(0, 0), FinishReason.END_TURN, id)

        override fun stream(request: AiRequest, config: ProviderConfig): Flow<AiStreamEvent> = flowOf()
    }

    private class FakeClaudeLike(
        private val onSessionComplete: suspend (
            history: List<com.androidailab.aisdk.model.Message>,
            request: AiRequest,
            systemPrompt: String?,
            config: ProviderConfig,
        ) -> AiResponse,
    ) : ClaudeProvider() {
        override suspend fun complete(request: AiRequest, config: ProviderConfig): AiResponse =
            onSessionComplete(emptyList(), request, null, config)

        override suspend fun completeForSession(
            history: List<com.androidailab.aisdk.model.Message>,
            request: AiRequest,
            systemPrompt: String?,
            config: ProviderConfig,
        ): AiResponse = onSessionComplete(history, request, systemPrompt, config)
    }
}
