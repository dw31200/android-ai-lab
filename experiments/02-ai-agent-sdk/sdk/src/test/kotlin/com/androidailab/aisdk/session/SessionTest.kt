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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * F-004 (세션 컨텍스트 유지) Session 단위 테스트.
 *
 * 사양 참조:
 * - features.md F-004 정상 흐름 1~5 / 예외 흐름 (E-401/E-402/E-403)
 * - api.md A-004 (createSession) / A-006 (send) / A-007 (history) / A-008 (clear)
 * - data-model.md M-007 (Session) / M-008 (Message, Role)
 * - features.md F-005 R-014 (Session은 Provider에 묶이지 않음)
 * - features.md F-008 R-020 케이스 A/B (close 시맨틱)
 * - error-handling.md ERR-004 / ERR-005
 *
 * 본 테스트가 검증하는 것:
 * 1. createSession 정상 흐름 — UUID sessionId + 빈 history
 * 2. send 정상 흐름 — USER 메시지 + ASSISTANT 응답이 history에 누적
 * 3. systemPrompt — Session 보관됨, history에는 등장하지 않음 (R-008)
 * 4. history() — immutable snapshot (R-011)
 * 5. clear() — history만 초기화, systemPrompt 보존
 * 6. R-020 케이스 B — close 후 send/createSession/history/clear → 정의된 시맨틱
 * 7. E-402 — close 후 send → Result.failure(Configuration("client closed"))
 * 8. E-403 — 동시 send는 Mutex로 직렬화 (정상 동작)
 * 9. R-014 — Session은 send 진입 시 client.activeProvider 캡쳐
 * 10. E-106 — send 도중 취소 시 CancellationException 그대로 전파
 * 11. AiException pass-through — Provider가 throw하면 Result.failure로 그대로 전달
 * 12. F-001 R-005 / E-110 — 빈 응답 검증 (END_TURN OK / MAX_TOKENS 실패)
 * 13. send 실패 시 history는 변경되지 않음 (atomic update)
 */
class SessionTest {

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
    // createSession 정상 흐름
    // -----------------------------------------------------------------

    @Test
    fun `F-004 A-004 — createSession 은 UUID sessionId 와 빈 history 를 가진 Session 반환`() {
        val client = newClient(FakeProvider(ProviderId.CLAUDE))

        val session = client.createSession()

        assertNotNull(session)
        // UUID 형식 검증 (java.util.UUID로 파싱 성공)
        UUID.fromString(session.sessionId)
        assertTrue(session.history().isEmpty())
    }

    @Test
    fun `F-004 A-004 — createSession 두 번 호출 시 서로 다른 sessionId`() {
        val client = newClient(FakeProvider(ProviderId.CLAUDE))

        val s1 = client.createSession()
        val s2 = client.createSession()

        assertFalse("sessionId must differ", s1.sessionId == s2.sessionId)
    }

    @Test
    fun `F-004 A-004 — createSession(systemPrompt) 는 systemPrompt 를 Session 에 보관`() = runTest {
        var capturedSystem: String? = "<not-captured>"
        val provider = FakeClaudeLike(
            id = ProviderId.CLAUDE,
            onSessionComplete = { history, request, systemPrompt, _ ->
                capturedSystem = systemPrompt
                AiResponse("ok", TokenUsage(0, 0), FinishReason.END_TURN, ProviderId.CLAUDE)
            },
        )
        val client = newClient(provider)

        val session = client.createSession(systemPrompt = "You are helpful.")
        val result = session.send(AiRequest("hi"))

        assertTrue(result.isSuccess)
        assertEquals("You are helpful.", capturedSystem)
    }

    // -----------------------------------------------------------------
    // send 정상 흐름 (F-004 정상 흐름 2~3)
    // -----------------------------------------------------------------

    @Test
    fun `F-004 정상 흐름 — send 성공 시 USER + ASSISTANT 메시지가 history 에 누적`() = runTest {
        val provider = FakeClaudeLike(
            ProviderId.CLAUDE,
            onSessionComplete = { _, _, _, _ ->
                AiResponse("hello!", TokenUsage(1, 2), FinishReason.END_TURN, ProviderId.CLAUDE)
            },
        )
        val client = newClient(provider)
        val session = client.createSession()

        val result = session.send(AiRequest("hi"))

        assertTrue(result.isSuccess)
        val history = session.history()
        assertEquals(2, history.size)
        assertEquals(Role.USER, history[0].role)
        assertEquals("hi", history[0].content)
        assertEquals(Role.ASSISTANT, history[1].role)
        assertEquals("hello!", history[1].content)
    }

    @Test
    fun `F-004 정상 흐름 — 연속 send 시 history 가 순차 누적`() = runTest {
        val counter = AtomicInteger(0)
        val provider = FakeClaudeLike(
            ProviderId.CLAUDE,
            onSessionComplete = { _, _, _, _ ->
                AiResponse(
                    "answer-${counter.incrementAndGet()}",
                    TokenUsage(0, 0),
                    FinishReason.END_TURN,
                    ProviderId.CLAUDE,
                )
            },
        )
        val client = newClient(provider)
        val session = client.createSession()

        session.send(AiRequest("q1")).getOrThrow()
        session.send(AiRequest("q2")).getOrThrow()
        session.send(AiRequest("q3")).getOrThrow()

        val history = session.history()
        assertEquals(6, history.size)
        assertEquals("q1", history[0].content)
        assertEquals("answer-1", history[1].content)
        assertEquals("q2", history[2].content)
        assertEquals("answer-2", history[3].content)
        assertEquals("q3", history[4].content)
        assertEquals("answer-3", history[5].content)
    }

    @Test
    fun `F-004 정상 흐름 — 두 번째 send 호출 시 첫 send 의 history 가 Provider 에 전달됨`() = runTest {
        val capturedHistories = mutableListOf<List<Message>>()
        val counter = AtomicInteger(0)
        val provider = FakeClaudeLike(
            ProviderId.CLAUDE,
            onSessionComplete = { history, _, _, _ ->
                capturedHistories.add(history)
                AiResponse(
                    "r${counter.incrementAndGet()}",
                    TokenUsage(0, 0),
                    FinishReason.END_TURN,
                    ProviderId.CLAUDE,
                )
            },
        )
        val client = newClient(provider)
        val session = client.createSession()

        session.send(AiRequest("first"))
        session.send(AiRequest("second"))

        assertEquals(2, capturedHistories.size)
        // 첫 send는 history가 비어있음
        assertEquals(0, capturedHistories[0].size)
        // 두 번째 send는 USER(first) + ASSISTANT(r1)가 history로 전달됨
        assertEquals(2, capturedHistories[1].size)
        assertEquals(Role.USER, capturedHistories[1][0].role)
        assertEquals("first", capturedHistories[1][0].content)
        assertEquals(Role.ASSISTANT, capturedHistories[1][1].role)
        assertEquals("r1", capturedHistories[1][1].content)
    }

    // -----------------------------------------------------------------
    // R-008 systemPrompt 정책 — history에 포함되지 않음
    // -----------------------------------------------------------------

    @Test
    fun `F-004 R-008 — systemPrompt 는 history 에 등장하지 않음`() = runTest {
        val provider = FakeClaudeLike(
            ProviderId.CLAUDE,
            onSessionComplete = { _, _, _, _ ->
                AiResponse("ok", TokenUsage(0, 0), FinishReason.END_TURN, ProviderId.CLAUDE)
            },
        )
        val client = newClient(provider)
        val session = client.createSession(systemPrompt = "be brief")

        session.send(AiRequest("hi"))

        val history = session.history()
        assertEquals(2, history.size)
        // R-008: SYSTEM 메시지는 history에 등장하지 않음
        assertFalse(history.any { it.role == Role.SYSTEM })
        assertEquals(Role.USER, history[0].role)
        assertEquals(Role.ASSISTANT, history[1].role)
    }

    // -----------------------------------------------------------------
    // R-011 immutable snapshot
    // -----------------------------------------------------------------

    @Test
    fun `F-004 A-007 R-011 — history() 반환은 immutable snapshot (이후 send 가 발생해도 변경 없음)`() = runTest {
        val provider = FakeClaudeLike(
            ProviderId.CLAUDE,
            onSessionComplete = { _, _, _, _ ->
                AiResponse("a", TokenUsage(0, 0), FinishReason.END_TURN, ProviderId.CLAUDE)
            },
        )
        val client = newClient(provider)
        val session = client.createSession()
        session.send(AiRequest("q1"))

        val snapshot = session.history()
        val sizeBefore = snapshot.size

        // 추가 send 발생
        session.send(AiRequest("q2"))

        // snapshot은 그대로 (R-011: snapshot 보장)
        assertEquals(sizeBefore, snapshot.size)
        assertEquals(2, snapshot.size)
        // 그러나 새로 호출하면 누적된 history
        assertEquals(4, session.history().size)
    }

    // -----------------------------------------------------------------
    // A-008 clear — history만 초기화, systemPrompt 보존
    // -----------------------------------------------------------------

    @Test
    fun `F-004 A-008 — clear() 후 history 는 비어있고 systemPrompt 는 유지됨`() = runTest {
        var capturedSystem: String? = null
        val provider = FakeClaudeLike(
            ProviderId.CLAUDE,
            onSessionComplete = { _, _, systemPrompt, _ ->
                capturedSystem = systemPrompt
                AiResponse("ok", TokenUsage(0, 0), FinishReason.END_TURN, ProviderId.CLAUDE)
            },
        )
        val client = newClient(provider)
        val session = client.createSession(systemPrompt = "stay polite")

        session.send(AiRequest("hi"))
        assertEquals(2, session.history().size)

        session.clear()
        assertTrue(session.history().isEmpty())

        // 추가 send 시 systemPrompt가 여전히 전달됨
        session.send(AiRequest("again"))
        assertEquals("stay polite", capturedSystem)
    }

    // -----------------------------------------------------------------
    // R-020 케이스 B — close 후 호출
    // -----------------------------------------------------------------

    @Test
    fun `F-004 E-402 R-020 케이스 B — close 후 send → Result_failure(Configuration(client closed))`() = runTest {
        val client = newClient(FakeClaudeLike(ProviderId.CLAUDE))
        val session = client.createSession()
        client.close()

        val result = session.send(AiRequest("hi"))

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("expected Configuration, got ${ex?.javaClass?.simpleName}", ex is AiException.Configuration)
        assertTrue(
            "message should be 'client closed', actual=${ex?.message}",
            ex?.message?.contains("client closed") == true,
        )
    }

    @Test
    fun `F-004 R-020 케이스 B — close 후 createSession → Configuration(client closed) throw`() {
        val client = newClient(FakeClaudeLike(ProviderId.CLAUDE))
        client.close()

        try {
            client.createSession()
            fail("expected Configuration throw")
        } catch (e: AiException.Configuration) {
            assertTrue(
                "message should be 'client closed', actual=${e.message}",
                e.message?.contains("client closed") == true,
            )
        }
    }

    @Test
    fun `F-004 R-020 케이스 B — close 후 Session_history() → Configuration throw`() {
        val client = newClient(FakeClaudeLike(ProviderId.CLAUDE))
        val session = client.createSession()
        client.close()

        try {
            session.history()
            fail("expected Configuration throw")
        } catch (e: AiException.Configuration) {
            assertTrue(e.message?.contains("client closed") == true)
        }
    }

    @Test
    fun `F-004 R-020 케이스 B — close 후 Session_clear() → Configuration throw`() {
        val client = newClient(FakeClaudeLike(ProviderId.CLAUDE))
        val session = client.createSession()
        client.close()

        try {
            session.clear()
            fail("expected Configuration throw")
        } catch (e: AiException.Configuration) {
            assertTrue(e.message?.contains("client closed") == true)
        }
    }

    // -----------------------------------------------------------------
    // E-403 동시 send Mutex 직렬화
    // -----------------------------------------------------------------

    @Test
    fun `F-004 E-403 — 동시 send 호출은 Mutex 로 직렬화 (정상 동작 — 두 번째는 첫 번째 완료 후 실행)`() = runTest {
        // 각 send가 진입한 순서를 기록한다.
        val order = mutableListOf<Int>()
        val firstGate = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val counter = AtomicInteger(0)

        val provider = FakeClaudeLike(
            ProviderId.CLAUDE,
            onSessionComplete = { _, request, _, _ ->
                val callIndex = counter.incrementAndGet()
                synchronized(order) { order.add(callIndex) }
                if (callIndex == 1) {
                    firstStarted.complete(Unit)
                    firstGate.await() // 첫 send를 의도적으로 대기시킴
                }
                AiResponse(
                    "r${request.prompt}",
                    TokenUsage(0, 0),
                    FinishReason.END_TURN,
                    ProviderId.CLAUDE,
                )
            },
        )
        val client = newClient(provider)
        val session = client.createSession()

        val firstAsync = async { session.send(AiRequest("q1")) }
        firstStarted.await() // 첫 send가 mutex를 잡았음을 확인

        // 두 번째 send는 mutex에 대기되어야 함 — counter가 첫 send가 풀려나기 전에 증가하지 않아야 함.
        val secondAsync = async { session.send(AiRequest("q2")) }
        // 짧게 대기하여 두 번째 send가 진입하지 않았음을 확인
        delay(50)
        synchronized(order) {
            assertEquals("E-403: second send must not have entered yet", 1, order.size)
        }

        // 첫 send 풀어주기
        firstGate.complete(Unit)
        val r1 = firstAsync.await()
        val r2 = secondAsync.await()

        assertTrue(r1.isSuccess)
        assertTrue(r2.isSuccess)
        // 직렬화 보장: order는 [1, 2]
        assertEquals(listOf(1, 2), synchronized(order) { order.toList() })
        // history는 누적: 4개
        assertEquals(4, session.history().size)
    }

    // -----------------------------------------------------------------
    // R-014 — Session은 Provider에 묶이지 않음 (send 진입 시점 캡쳐)
    // -----------------------------------------------------------------

    @Test
    fun `F-004 R-014 — Session_send 는 호출 시점의 client_activeProvider 를 사용`() = runTest {
        val providerA = FakeClaudeLike(
            ProviderId.CLAUDE,
            onSessionComplete = { _, _, _, _ ->
                AiResponse("from-A", TokenUsage(0, 0), FinishReason.END_TURN, ProviderId.CLAUDE)
            },
        )
        val client = newClient(providerA)
        val session = client.createSession()

        // Session은 생성 시 Provider를 캡쳐하지 않으므로, send 호출 시점의 active Provider 사용
        val result = session.send(AiRequest("hi"))

        assertTrue(result.isSuccess)
        assertEquals("from-A", result.getOrNull()?.text)
    }

    // -----------------------------------------------------------------
    // E-106 — 코루틴 취소
    // -----------------------------------------------------------------

    @Test
    fun `F-004 E-106 — send 도중 cancel 되면 CancellationException 그대로 전파`() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val provider = FakeClaudeLike(
            ProviderId.CLAUDE,
            onSessionComplete = { _, _, _, _ ->
                started.complete(Unit)
                gate.await()
                throw IllegalStateException("must not reach here")
            },
        )
        val client = newClient(provider)
        val session = client.createSession()

        val deferred = async { session.send(AiRequest("hi")) }
        started.await()
        deferred.cancel(CancellationException("test cancel"))

        try {
            deferred.await()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // 정상 — Result로 감싸지 않음
        }
    }

    // -----------------------------------------------------------------
    // AiException pass-through
    // -----------------------------------------------------------------

    @Test
    fun `F-004 E-401 — Provider 가 InvalidInput(context too large) 를 throw 하면 Result_failure 로 전달`() = runTest {
        val provider = FakeClaudeLike(
            ProviderId.CLAUDE,
            onSessionComplete = { _, _, _, _ -> throw AiException.InvalidInput("context too large") },
        )
        val client = newClient(provider)
        val session = client.createSession()

        val result = session.send(AiRequest("hi"))

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("expected InvalidInput, got ${ex?.javaClass?.simpleName}", ex is AiException.InvalidInput)
        assertTrue(
            "message should be 'context too large', actual=${ex?.message}",
            ex?.message == "context too large",
        )
    }

    @Test
    fun `F-004 — Provider 가 Network 를 throw 하면 Result_failure(Network) 로 전달`() = runTest {
        val cause = java.io.IOException("offline")
        val provider = FakeClaudeLike(
            ProviderId.CLAUDE,
            onSessionComplete = { _, _, _, _ -> throw AiException.Network(cause) },
        )
        val client = newClient(provider)
        val session = client.createSession()

        val result = session.send(AiRequest("hi"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AiException.Network)
    }

    @Test
    fun `F-004 — Provider 가 throw 한 경우 history 는 변경되지 않음`() = runTest {
        val provider = FakeClaudeLike(
            ProviderId.CLAUDE,
            onSessionComplete = { _, _, _, _ -> throw AiException.Network(java.io.IOException("offline")) },
        )
        val client = newClient(provider)
        val session = client.createSession()
        assertTrue(session.history().isEmpty())

        val result = session.send(AiRequest("hi"))
        assertTrue(result.isFailure)

        // E-101 등 실패 시 history는 변경되지 않음 (atomic update 보장)
        assertTrue(session.history().isEmpty())
    }

    // -----------------------------------------------------------------
    // F-001 R-005 / E-110 빈 응답 검증 (Session에서도 동일)
    // -----------------------------------------------------------------

    @Test
    fun `F-004 R-005 — Session send 도 빈 응답 + END_TURN 은 그대로 성공`() = runTest {
        val provider = FakeClaudeLike(
            ProviderId.CLAUDE,
            onSessionComplete = { _, _, _, _ ->
                AiResponse("", TokenUsage(0, 0), FinishReason.END_TURN, ProviderId.CLAUDE)
            },
        )
        val client = newClient(provider)
        val session = client.createSession()

        val result = session.send(AiRequest("hi"))

        assertTrue(result.isSuccess)
        assertEquals("", result.getOrNull()?.text)
    }

    @Test
    fun `F-004 E-110 — Session send 도 빈 응답 + MAX_TOKENS 는 ServerError(-1)`() = runTest {
        val provider = FakeClaudeLike(
            ProviderId.CLAUDE,
            onSessionComplete = { _, _, _, _ ->
                AiResponse("", TokenUsage(0, 0), FinishReason.MAX_TOKENS, ProviderId.CLAUDE)
            },
        )
        val client = newClient(provider)
        val session = client.createSession()

        val result = session.send(AiRequest("hi"))

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as AiException.ServerError
        assertEquals(-1, ex.code)
        // E-110 실패 시 history는 변경되지 않음
        assertTrue(session.history().isEmpty())
    }

    // -----------------------------------------------------------------
    // F-002 검증 재사용 (Session도 동일)
    // -----------------------------------------------------------------

    @Test
    fun `F-004 + F-002 — Session send 진입에서 ImageInput_Uri 는 즉시 E-203 InvalidInput`() = runTest {
        val provider = FakeClaudeLike(ProviderId.CLAUDE)
        val client = newClient(provider)
        val session = client.createSession()

        val uri = mockk<android.net.Uri>(relaxed = true)
        val result = session.send(
            AiRequest(prompt = "hi", images = listOf(ImageInput.Uri(uri))),
        )

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AiException.InvalidInput)
        assertTrue(ex?.message?.contains("uri unreadable") == true)
    }

    // -----------------------------------------------------------------
    // 헬퍼
    // -----------------------------------------------------------------

    private fun newClient(
        provider: Provider,
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
    )

    /**
     * 일반 Provider (history/systemPrompt 전달 안 됨).
     *
     * F-004 진입 시 Session.send가 ClaudeProvider 이외의 Provider를 만나면 fallback으로 일반 complete를
     * 호출한다 (history/systemPrompt 무시). 본 Fake는 그 경로 검증용.
     */
    private class FakeProvider(
        override val id: ProviderId,
        private val completeBehavior: suspend (AiRequest, ProviderConfig) -> AiResponse = { _, _ ->
            AiResponse("fake", TokenUsage(0, 0), FinishReason.END_TURN, id)
        },
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
            completeBehavior(request, config)

        override fun stream(request: AiRequest, config: ProviderConfig): Flow<AiStreamEvent> = flowOf()
    }

    /**
     * ClaudeProvider 서브타입 — Session.send가 `completeForSession`을 호출할 때 history/systemPrompt를
     * 캡쳐할 수 있도록 한다. F-004 Session 경로 검증에 사용.
     *
     * 부모 ClaudeProvider의 [Capabilities] (supportsImage=true 등) 와 [id] (ProviderId.CLAUDE) 를 그대로 사용.
     */
    private class FakeClaudeLike(
        @Suppress("UNUSED_PARAMETER") providerIdHint: ProviderId = ProviderId.CLAUDE,
        private val onSessionComplete: suspend (
            history: List<Message>,
            request: AiRequest,
            systemPrompt: String?,
            config: ProviderConfig,
        ) -> AiResponse = { _, _, _, _ ->
            AiResponse("ok", TokenUsage(0, 0), FinishReason.END_TURN, ProviderId.CLAUDE)
        },
    ) : ClaudeProvider() {

        override suspend fun complete(request: AiRequest, config: ProviderConfig): AiResponse {
            // 일반 complete는 Session.send에서 도달 안 함 (Session.send는 completeForSession을 사용).
            // 안전망: 동일 람다로 위임.
            return onSessionComplete(emptyList(), request, null, config)
        }

        override suspend fun completeForSession(
            history: List<Message>,
            request: AiRequest,
            systemPrompt: String?,
            config: ProviderConfig,
        ): AiResponse = onSessionComplete(history, request, systemPrompt, config)
    }
}

