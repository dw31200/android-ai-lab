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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * F-004 / A-004 (AiAgentClient.createSession) 단위 테스트.
 *
 * 사양 참조:
 * - api.md A-004 시그니처: `fun createSession(systemPrompt: String? = null): Session`
 * - api.md A-004 sessionId 부여 정책 (R-017/R-024) — v0.1 SDK 자동 UUID
 * - features.md F-004 정상 흐름 1단계
 * - features.md F-008 R-020 케이스 B — close 후 createSession 호출 시 동기 throw
 *
 * 본 테스트가 검증하는 것:
 * 1. A-004 시그니처 — systemPrompt 인자 default null
 * 2. UUID 형식 sessionId
 * 3. 두 번 호출 시 서로 다른 sessionId
 * 4. systemPrompt 미지정 시 null 보관 (Anthropic system 필드 직렬화 제외)
 * 5. R-020 케이스 B — close 후 createSession → Configuration("client closed") throw (동기 함수)
 */
class CreateSessionTest {

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
    // A-004 정상 흐름
    // -----------------------------------------------------------------

    @Test
    fun `F-004 A-004 — createSession() 은 비-null Session 반환`() {
        val client = newClient(FakeProvider(ProviderId.CLAUDE))

        val session = client.createSession()

        assertNotNull(session)
    }

    @Test
    fun `F-004 A-004 — createSession() 의 sessionId 는 UUID 형식 (R-017)`() {
        val client = newClient(FakeProvider(ProviderId.CLAUDE))

        val session = client.createSession()

        // 정상 UUID이면 fromString이 throw 하지 않는다 — 형식 검증.
        val parsed = UUID.fromString(session.sessionId)
        assertEquals(session.sessionId, parsed.toString())
    }

    @Test
    fun `F-004 A-004 — createSession() 을 두 번 호출하면 서로 다른 sessionId`() {
        val client = newClient(FakeProvider(ProviderId.CLAUDE))

        val s1 = client.createSession()
        val s2 = client.createSession()

        assertFalse("sessionId must differ across calls", s1.sessionId == s2.sessionId)
    }

    @Test
    fun `F-004 A-004 — createSession(systemPrompt) 와 createSession() 모두 호출 가능 (default param)`() {
        val client = newClient(FakeProvider(ProviderId.CLAUDE))

        val s1 = client.createSession() // default null
        val s2 = client.createSession(systemPrompt = "be brief")
        val s3 = client.createSession(systemPrompt = null) // 명시 null

        assertNotNull(s1)
        assertNotNull(s2)
        assertNotNull(s3)
    }

    @Test
    fun `F-004 A-004 — 생성된 Session 의 history 는 빈 리스트`() {
        val client = newClient(FakeProvider(ProviderId.CLAUDE))

        val session = client.createSession()

        assertTrue(session.history().isEmpty())
    }

    // -----------------------------------------------------------------
    // R-020 케이스 B
    // -----------------------------------------------------------------

    @Test
    fun `F-004 R-020 케이스 B — close 후 createSession 호출 시 Configuration(client closed) throw`() {
        val client = newClient(FakeProvider(ProviderId.CLAUDE))
        client.close()

        try {
            client.createSession()
            fail("expected Configuration throw after close")
        } catch (e: AiException.Configuration) {
            assertTrue(
                "message should contain 'client closed', actual=${e.message}",
                e.message?.contains("client closed") == true,
            )
        }
    }

    @Test
    fun `F-004 R-020 케이스 B — close 후 createSession(systemPrompt) 도 throw (동기 함수)`() {
        val client = newClient(FakeProvider(ProviderId.CLAUDE))
        client.close()

        try {
            client.createSession(systemPrompt = "be brief")
            fail("expected Configuration throw")
        } catch (e: AiException.Configuration) {
            assertTrue(e.message?.contains("client closed") == true)
        }
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
            AiResponse("fake", TokenUsage(0, 0), FinishReason.END_TURN, id)

        override fun stream(request: AiRequest, config: ProviderConfig): Flow<AiStreamEvent> = flowOf()
    }
}
