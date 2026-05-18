package com.androidailab.aisdk.session

import android.content.Context
import com.androidailab.aisdk.AiAgentClient
import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.AiRequest
import com.androidailab.aisdk.model.AiResponse
import com.androidailab.aisdk.model.AiStreamEvent
import com.androidailab.aisdk.model.FinishReason
import com.androidailab.aisdk.model.ImageInput
import com.androidailab.aisdk.model.Message
import com.androidailab.aisdk.model.ProviderId
import com.androidailab.aisdk.model.Role
import com.androidailab.aisdk.model.TokenUsage
import com.androidailab.aisdk.provider.Capabilities
import com.androidailab.aisdk.provider.Provider
import com.androidailab.aisdk.provider.ProviderConfig
import com.androidailab.aisdk.provider.ProviderRegistry
import com.androidailab.aisdk.provider.claude.ClaudeProvider
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * F-007 (세션 영속화) Session.save 단위 테스트.
 *
 * 사양 참조:
 * - api.md A-010 (Session.save 시그니처)
 * - features.md F-007 저장 흐름 / 동시성 모델 / 다중 인스턴스 정책 (R-019) / 이미지 영속화 (R-021)
 * - features.md F-007 예외 흐름 — E-701 / E-704 / E-705 / E-706
 * - data-model.md M-011 (SessionEntity 변환)
 * - features.md F-008 R-020 케이스 A/B (close 시맨틱)
 * - error-handling.md ERR-004 (E-705) / ERR-005 (E-704) / ERR-007 (E-701)
 *
 * 검증 사항:
 * 1. save 성공 → Result.success(sessionId) 반환, SessionStore.save가 SessionEntity로 호출됨
 * 2. SessionEntity 필드: sessionId/systemPrompt/history/schemaVersion=1
 * 3. history Message → MessageEntity 변환 (R-008)
 * 4. R-021 — 이미지가 포함된 history 의 Bytes/Url/Uri 변환
 * 5. E-704 — SessionStore가 1MB 초과 InvalidInput throw 시 Result.failure
 * 6. E-701 — SessionStore가 IOError throw 시 Result.failure
 * 7. E-705 — close 후 save → Result.failure(Configuration("client closed"))
 * 8. E-706 — save 도중 cancel → CancellationException 그대로 전파
 * 9. send 진행 중 save 호출 시 Mutex 대기 (F-007 동시성 모델)
 * 10. sessionStore 미설정 시 IOError("session store not configured")
 */
class SessionSaveTest {

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
    // 1. 정상 흐름 — A-010 / F-007 저장 흐름
    // -----------------------------------------------------------------

    @Test
    fun `F-007 A-010 정상 흐름 — save 성공 시 Result_success(sessionId) 반환`() = runTest {
        val store = FakeSessionStore()
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)
        val session = client.createSession(systemPrompt = "polite")

        val result = session.save()

        assertTrue(result.isSuccess)
        assertEquals(session.sessionId, result.getOrThrow())
        assertEquals(1, store.saveCalls.size)
        val saved = store.saveCalls[0]
        assertEquals(session.sessionId, saved.sessionId)
        assertEquals("polite", saved.systemPrompt)
        assertEquals(1, saved.schemaVersion)
        assertEquals(0, saved.history.size)
    }

    @Test
    fun `F-007 A-010 R-018 — save 시 SessionEntity_schemaVersion 은 항상 1`() = runTest {
        val store = FakeSessionStore()
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)
        val session = client.createSession()

        session.save().getOrThrow()

        assertEquals(SessionEntity.SCHEMA_VERSION_V1, store.saveCalls[0].schemaVersion)
    }

    @Test
    fun `F-007 A-010 — save 결과의 sessionId 는 createSession 자동 UUID 와 일치`() = runTest {
        val store = FakeSessionStore()
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)
        val session = client.createSession()

        val savedId = session.save().getOrThrow()
        assertEquals(session.sessionId, savedId)
        // UUID 형식 검증
        java.util.UUID.fromString(savedId)
    }

    @Test
    fun `F-007 A-010 — send 가 history 에 누적한 후 save 시 history 그대로 직렬화 (M-011)`() = runTest {
        val store = FakeSessionStore()
        val provider = FakeClaudeLike(
            onSessionComplete = { _, _, _, _ ->
                AiResponse("answer", TokenUsage(0, 0), FinishReason.END_TURN, ProviderId.CLAUDE)
            },
        )
        val client = newClient(provider, store = store)
        val session = client.createSession(systemPrompt = "p")

        session.send(AiRequest("q1")).getOrThrow()

        val result = session.save()
        assertTrue(result.isSuccess)

        val saved = store.saveCalls[0]
        assertEquals(2, saved.history.size)
        assertEquals("USER", saved.history[0].role)
        assertEquals("q1", saved.history[0].content)
        assertEquals("ASSISTANT", saved.history[1].role)
        assertEquals("answer", saved.history[1].content)
        assertEquals("p", saved.systemPrompt)
    }

    @Test
    fun `F-007 R-021 — history 의 ImageInput_Bytes 가 ImageInputEntity_Bytes(base64) 로 저장`() = runTest {
        val store = FakeSessionStore()
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)
        val session = client.createSession()

        // history에 직접 Message 주입 — initialHistory로 시뮬레이션
        val raw = byteArrayOf(0x10, 0x20, 0x30)
        val historyMsg = Message(
            role = Role.USER,
            content = "see image",
            images = listOf(ImageInput.Bytes(raw, "image/png")),
            timestamp = 1000L,
        )
        val rebuilt = Session(client, "test-id", systemPrompt = null, initialHistory = listOf(historyMsg))
        // initialHistory가 보존되는지 검증을 위해 newSession은 builder 우회
        val result = rebuilt.save()

        assertTrue(result.isSuccess)
        val saved = store.saveCalls[0]
        assertEquals(1, saved.history.size)
        val img = saved.history[0].images[0] as ImageInputEntity.Bytes
        assertEquals("image/png", img.mimeType)
        // base64 round-trip 검증
        val decoded = java.util.Base64.getDecoder().decode(img.base64)
        assertTrue(raw.contentEquals(decoded))
    }

    // -----------------------------------------------------------------
    // 2. 예외 흐름
    // -----------------------------------------------------------------

    @Test
    fun `F-007 E-704 — SessionStore 가 InvalidInput(session too large) throw 시 Result_failure`() = runTest {
        val store = FakeSessionStore(onSave = {
            throw AiException.InvalidInput("session too large to persist: 2000000 > 1048576 bytes")
        })
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)
        val session = client.createSession()

        val result = session.save()

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("expected InvalidInput, got ${ex?.let { it::class.simpleName }}", ex is AiException.InvalidInput)
        assertTrue(ex?.message?.contains("session too large") == true)
    }

    @Test
    fun `F-007 E-701 — SessionStore 가 IOError throw 시 Result_failure(IOError)`() = runTest {
        val store = FakeSessionStore(onSave = {
            throw AiException.IOError("save failed: disk full")
        })
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)
        val session = client.createSession()

        val result = session.save()

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AiException.IOError)
        assertTrue(ex?.message?.contains("save failed") == true)
    }

    @Test
    fun `F-007 E-705 R-020 케이스 B — close 후 save → Result_failure(Configuration(client closed))`() = runTest {
        val store = FakeSessionStore()
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)
        val session = client.createSession()

        client.close()

        val result = session.save()
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AiException.Configuration)
        assertEquals("client closed", ex?.message)
        // close 후 SessionStore.save는 호출되지 않아야 함
        assertEquals(0, store.saveCalls.size)
    }

    @Test
    fun `F-007 E-706 R-020 케이스 A — save 도중 cancel 시 CancellationException 그대로 전파`() = runTest {
        val saveStarted = CompletableDeferred<Unit>()
        val store = FakeSessionStore(onSave = {
            saveStarted.complete(Unit)
            // 영원히 대기 — 외부에서 cancel
            kotlinx.coroutines.delay(60_000)
        })
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)
        val session = client.createSession()

        val job = async { session.save() }
        saveStarted.await()
        job.cancel()

        try {
            job.await()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // 정상 — CancellationException 그대로 전파
        }
    }

    @Test
    fun `F-007 — sessionStore 미설정 시 Result_failure(IOError(session store not configured))`() = runTest {
        // Builder가 자동으로 SessionStore를 만들지만, 본 테스트는 sessionStoreOverride=null 시나리오 검증.
        // 직접 AiAgentClient를 sessionStore = null로 만들어 검증한다.
        val client = AiAgentClient(
            context = context,
            apiKey = "sk-test",
            initialProviderId = ProviderId.CLAUDE,
            modelId = "claude-opus-4-7",
            timeout = 30.seconds,
            providerRegistry = ProviderRegistry(setOf(FakeProvider(ProviderId.CLAUDE))),
            sessionStore = null,
        )
        val session = client.createSession()

        val result = session.save()

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AiException.IOError)
        assertTrue(ex?.message?.contains("session store not configured") == true)
    }

    // -----------------------------------------------------------------
    // 3. 동시성 — F-007 동시성 모델 (send 진행 중 save 대기)
    // -----------------------------------------------------------------

    @Test
    fun `F-007 동시성 — send 진행 중 save 는 Mutex 대기 (history 무결성)`() = runTest {
        val sendStarted = CompletableDeferred<Unit>()
        val sendBlock = CompletableDeferred<Unit>()
        val provider = FakeClaudeLike(
            onSessionComplete = { _, _, _, _ ->
                sendStarted.complete(Unit)
                sendBlock.await()
                AiResponse("done", TokenUsage(0, 0), FinishReason.END_TURN, ProviderId.CLAUDE)
            },
        )
        val store = FakeSessionStore()
        val client = newClient(provider, store = store)
        val session = client.createSession()

        // 1. send 시작 (Mutex 점유 + sendStarted 신호)
        val sendJob = async { session.send(AiRequest("hi")) }
        sendStarted.await()

        // 2. save 호출 — Mutex 대기 (send 완료까지)
        val saveJob = async { session.save() }
        // saveJob은 아직 완료 안 됨
        assertTrue(saveJob.isActive)
        assertEquals(0, store.saveCalls.size)

        // 3. send 완료 → save 진행
        sendBlock.complete(Unit)
        sendJob.await()
        val saveResult = saveJob.await()

        assertTrue(saveResult.isSuccess)
        // send가 history에 누적된 후 save가 진행되어야 함
        assertEquals(2, store.saveCalls[0].history.size)
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

    /**
     * Fake SessionStore — save/load/delete 호출 캡쳐 + onSave/onLoad/onDelete hook으로
     * 예외 시나리오를 시뮬레이션한다.
     */
    private class FakeSessionStore(
        private val onSave: suspend (SessionEntity) -> Unit = { /* no-op */ },
        private val onLoad: suspend (String) -> SessionEntity? = { null },
        private val onDelete: suspend (String) -> Unit = { /* no-op */ },
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

    /**
     * 일반 Provider (history/systemPrompt 전달 안 됨). F-007 save 테스트에서 send 미사용 시나리오용.
     */
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

    /**
     * ClaudeProvider 서브타입 — Session.send가 completeForSession을 호출할 때 동작 hook.
     */
    private class FakeClaudeLike(
        private val onSessionComplete: suspend (
            history: List<Message>,
            request: AiRequest,
            systemPrompt: String?,
            config: ProviderConfig,
        ) -> AiResponse,
    ) : ClaudeProvider() {
        override suspend fun complete(request: AiRequest, config: ProviderConfig): AiResponse =
            onSessionComplete(emptyList(), request, null, config)

        override suspend fun completeForSession(
            history: List<Message>,
            request: AiRequest,
            systemPrompt: String?,
            config: ProviderConfig,
        ): AiResponse = onSessionComplete(history, request, systemPrompt, config)
    }
}
