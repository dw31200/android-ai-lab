package com.androidailab.aisdk.client

import android.content.Context
import com.androidailab.aisdk.AiAgentClient
import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.AiRequest
import com.androidailab.aisdk.model.AiResponse
import com.androidailab.aisdk.model.AiStreamEvent
import com.androidailab.aisdk.model.FinishReason
import com.androidailab.aisdk.model.ProviderId
import com.androidailab.aisdk.model.TokenUsage
import com.androidailab.aisdk.provider.Capabilities
import com.androidailab.aisdk.provider.Provider
import com.androidailab.aisdk.provider.ProviderConfig
import com.androidailab.aisdk.provider.ProviderRegistry
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * F-007 (세션 영속화) AiAgentClient.deleteSession 단위 테스트.
 *
 * 사양 참조:
 * - api.md A-012 (deleteSession 시그니처: `suspend fun deleteSession(sessionId: String): Result<Unit>`)
 * - api.md A-012 ("존재하지 않는 sessionId 삭제 시도 시 성공으로 처리 — idempotent")
 * - features.md F-007 삭제 흐름 1~3
 * - features.md F-007 E-705 / E-706
 * - features.md F-008 R-020 케이스 A/B (close 시맨틱)
 * - error-handling.md ERR-004 (E-705)
 *
 * 검증 사항:
 * 1. 정상 흐름 — SessionStore.delete 호출 → Result.success(Unit)
 * 2. idempotent — 존재하지 않는 sessionId 도 성공
 * 3. E-705 — close 후 deleteSession → Result.failure(Configuration("client closed"))
 * 4. E-706 — delete 도중 cancel → CancellationException 그대로 전파
 * 5. SessionStore IOError → Result.failure(IOError)
 * 6. sessionStore 미설정 → IOError("session store not configured")
 */
class DeleteSessionTest {

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
    fun `F-007 A-012 정상 흐름 — deleteSession 성공 시 Result_success(Unit)`() = runTest {
        val store = FakeSessionStore()
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)

        val result = client.deleteSession("session-x")

        assertTrue(result.isSuccess)
        assertEquals(Unit, result.getOrThrow())
        assertEquals(listOf("session-x"), store.deleteCalls)
    }

    @Test
    fun `F-007 A-012 idempotent — 미존재 sessionId 삭제도 성공으로 처리`() = runTest {
        // SessionStore.delete 자체가 미존재 키도 no-op로 안전 처리하므로 (DataStore Preferences 표준)
        // 본 테스트는 SDK 레이어가 그 결과를 Result.success(Unit)로 그대로 전달함을 검증.
        val store = FakeSessionStore() // onDelete는 no-op
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)

        val result = client.deleteSession("never-existed")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `F-007 A-012 — 여러 번 deleteSession 호출 시 모두 SessionStore_delete 로 전달`() = runTest {
        val store = FakeSessionStore()
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)

        client.deleteSession("a")
        client.deleteSession("b")
        client.deleteSession("c")

        assertEquals(listOf("a", "b", "c"), store.deleteCalls)
    }

    // -----------------------------------------------------------------
    // 2. E-705 — close 후 호출
    // -----------------------------------------------------------------

    @Test
    fun `F-007 E-705 R-020 케이스 B — close 후 deleteSession → Result_failure(Configuration(client closed))`() = runTest {
        val store = FakeSessionStore()
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)

        client.close()

        val result = client.deleteSession("s")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AiException.Configuration)
        assertEquals("client closed", ex?.message)
        // close 후 SessionStore.delete 는 호출 안 됨
        assertEquals(0, store.deleteCalls.size)
    }

    // -----------------------------------------------------------------
    // 3. E-706 — 취소 시맨틱
    // -----------------------------------------------------------------

    @Test
    fun `F-007 E-706 R-020 케이스 A — delete 도중 cancel 시 CancellationException 그대로 전파`() = runTest {
        val deleteStarted = CompletableDeferred<Unit>()
        val store = FakeSessionStore(onDelete = {
            deleteStarted.complete(Unit)
            kotlinx.coroutines.delay(60_000)
        })
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)

        val job = async { client.deleteSession("s") }
        deleteStarted.await()
        job.cancel()

        try {
            job.await()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // 정상 — Result.failure로 감싸지 않음 (R-020 케이스 A)
        }
    }

    // -----------------------------------------------------------------
    // 4. SessionStore IOError → Result.failure(IOError)
    // -----------------------------------------------------------------

    @Test
    fun `F-007 — SessionStore_delete 가 IOError throw 시 Result_failure(IOError) 로 전달`() = runTest {
        val store = FakeSessionStore(onDelete = {
            throw AiException.IOError("delete failed: io error")
        })
        val client = newClient(FakeProvider(ProviderId.CLAUDE), store = store)

        val result = client.deleteSession("s")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AiException.IOError)
        assertTrue(ex?.message?.contains("delete failed") == true)
    }

    // -----------------------------------------------------------------
    // 5. sessionStore 미설정 안전망
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

        val result = client.deleteSession("s")
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
            supportsImage = false,
            supportsVideo = false,
            supportsStream = false,
            supportsSession = false,
            maxImageSizeBytes = 0,
            maxImagesPerRequest = 0,
            supportedImageMimeTypes = emptySet(),
        )

        override suspend fun complete(request: AiRequest, config: ProviderConfig): AiResponse =
            AiResponse("ok", TokenUsage(0, 0), FinishReason.END_TURN, id)

        override fun stream(request: AiRequest, config: ProviderConfig): Flow<AiStreamEvent> = flowOf()
    }
}
