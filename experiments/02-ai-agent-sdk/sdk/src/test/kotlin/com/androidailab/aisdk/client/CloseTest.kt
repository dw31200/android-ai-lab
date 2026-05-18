package com.androidailab.aisdk.client

import android.content.Context
import com.androidailab.aisdk.AiAgentClient
import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.ProviderId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * F-008 (클라이언트 라이프사이클 close) 검증 테스트.
 *
 * 사양 참조:
 * - features.md F-008 정상 흐름 1~5 + close 정책
 * - api.md A-009 (close 시그니처 + 시맨틱 표 + 정책)
 * - features.md R-020 케이스 A/B (라운드 3 결정)
 * - error-handling.md E-109 / E-402 / E-705 / E-801 매핑
 *
 * 테스트 함수명에 F-008 / A-009 / R-020을 박아 추적성 보장.
 */
class CloseTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        val appContext = mockk<Context>(relaxed = true)
        context = mockk(relaxed = true) {
            every { applicationContext } returns appContext
        }
    }

    private fun newClient(): AiAgentClient =
        Builder(context)
            .apiKey("sk-test-close")
            .provider(ProviderId.CLAUDE)
            .timeout(30.seconds)
            .build()

    // -----------------------------------------------------------------
    // close idempotent (A-009 정책)
    // -----------------------------------------------------------------

    @Test
    fun `F-008 close 정책 — 여러 번 호출해도 예외 없이 안전 (idempotent)`() {
        val client = newClient()
        client.close()
        client.close()
        client.close()
        // 예외 없이 통과해야 함
        assertTrue("close after close 는 no-op", client.isClosed())
    }

    @Test
    fun `F-008 close 정책 — 초기 상태는 isClosed=false`() {
        val client = newClient()
        assertFalse("초기 상태에서 isClosed 는 false", client.isClosed())
    }

    @Test
    fun `F-008 close 정책 — close 호출 후 isClosed=true`() {
        val client = newClient()
        client.close()
        assertTrue("close 후 isClosed 는 true", client.isClosed())
    }

    // -----------------------------------------------------------------
    // R-020 케이스 B — ensureNotClosed throw 검증
    // -----------------------------------------------------------------

    @Test
    fun `F-008 R-020 케이스 B — close 후 ensureNotClosed 가 Configuration throw`() {
        val client = newClient()
        client.close()

        try {
            client.ensureNotClosed()
            fail("close 후 ensureNotClosed 는 AiException.Configuration 을 throw 해야 함")
        } catch (e: AiException.Configuration) {
            // R-020 — 메시지는 사양 그대로 "client closed" 와 정확히 일치
            assertEquals("client closed", e.message)
        }
    }

    @Test
    fun `F-008 R-020 케이스 B — close 전 ensureNotClosed 는 통과`() {
        val client = newClient()
        // close 호출 안 함 → throw 하면 안 됨
        client.ensureNotClosed()
    }

    @Test
    fun `F-008 E-109 매핑 — close 후 호출은 ERR-004 (Configuration) 로 분류됨`() {
        // E-109/E-402/E-705 모두 ERR-004 → AiException.Configuration 으로 매핑
        // (error-handling.md ERR ↔ E-XXX 매핑 표 참조)
        val client = newClient()
        client.close()
        try {
            client.ensureNotClosed()
            fail("expected AiException.Configuration")
        } catch (e: AiException.Configuration) {
            // ERR-004로 매핑됨 — Configuration variant 인지 확인
            assertTrue("AiException.Configuration 이어야 함", e is AiException.Configuration)
        }
    }

    // -----------------------------------------------------------------
    // OkHttp dispatcher.cancelAll 호출 검증
    // -----------------------------------------------------------------

    @Test
    fun `F-008 정상 흐름 2 — close 시 OkHttp dispatcher cancelAll 호출됨`() {
        val client = newClient()

        val mockDispatcher = mockk<Dispatcher>(relaxed = true)
        val mockOkHttp = mockk<OkHttpClient> {
            every { dispatcher } returns mockDispatcher
        }
        // F-001/F-006 진입 시점에 set 되는 핸들을 시뮬레이션
        client.httpClient = mockOkHttp

        client.close()

        verify(exactly = 1) { mockDispatcher.cancelAll() }
    }

    @Test
    fun `F-008 정상 흐름 2 — httpClient 가 null 이어도 close 는 정상 동작`() {
        // F-001 진입 전 (T8/T10 시점) 에는 httpClient 가 null 상태
        // 이때도 close 가 안전해야 함 (idempotent + best-effort 정책)
        val client = newClient()
        assertNotNull(client) // 가드: 정상 build
        // httpClient 미설정 → null
        client.close()
        assertTrue(client.isClosed())
    }

    @Test
    fun `F-008 E-801 — close 도중 dispatcher cancelAll 가 throw 해도 close 는 진행`() {
        // E-801: close 도중 IO 예외는 무시 (best-effort)
        val client = newClient()
        val mockDispatcher = mockk<Dispatcher> {
            every { cancelAll() } throws RuntimeException("simulated IO failure")
        }
        val mockOkHttp = mockk<OkHttpClient> {
            every { dispatcher } returns mockDispatcher
        }
        client.httpClient = mockOkHttp

        // close 자체는 throw 하면 안 됨 (E-801 best-effort)
        client.close()

        assertTrue("E-801 — IO 예외에도 closed 플래그는 true", client.isClosed())
    }

    @Test
    fun `F-008 close idempotent — 두 번째 호출에서는 cancelAll 다시 호출 안 함`() {
        val client = newClient()
        val mockDispatcher = mockk<Dispatcher>(relaxed = true)
        val mockOkHttp = mockk<OkHttpClient> {
            every { dispatcher } returns mockDispatcher
        }
        client.httpClient = mockOkHttp

        client.close()
        client.close()
        client.close()

        // idempotent — cancelAll 은 첫 호출에서만 1회 실행
        verify(exactly = 1) { mockDispatcher.cancelAll() }
    }

    // -----------------------------------------------------------------
    // Scope cancellation — R-020 케이스 A
    // -----------------------------------------------------------------

    @Test
    fun `F-008 정상 흐름 3 — close 시 scope 가 cancel 되어 더 이상 활성화되지 않음`() {
        val client = newClient()
        assertTrue("close 전 scope 는 active", client.scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)

        client.close()

        assertFalse(
            "close 후 scope 의 Job 은 active 가 아님",
            client.scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive,
        )
    }

    @Test
    fun `F-008 R-020 케이스 A — close 시 진행 중 코루틴이 CancellationException 으로 종결`() = runTest {
        val client = newClient()

        val started = CompletableDeferred<Unit>()
        var caughtCancellation = false

        // SDK 내부 scope 에서 가짜 long-running 작업 launch — F-001/F-007이 사용할 패턴 시뮬레이션
        val job = client.scope.launch {
            try {
                started.complete(Unit)
                delay(60_000) // 60초 대기 — close 가 cancel 시켜야 종결
            } catch (e: CancellationException) {
                caughtCancellation = true
                throw e
            }
        }

        started.await()

        client.close()
        job.join()

        assertTrue(
            "케이스 A — 진행 중 코루틴은 CancellationException 으로 종결되어야 함",
            caughtCancellation,
        )
        assertTrue("Job 은 cancelled", job.isCancelled)
    }

    @Test
    fun `F-008 R-020 케이스 A — close 후 scope launch 결과는 즉시 cancelled`() = runTest {
        val client = newClient()
        client.close()

        // close 후 scope 에 새로 launch 하면 즉시 cancelled 상태
        val job = client.scope.launch {
            delay(1000)
        }
        advanceUntilIdle()

        // SupervisorJob 이 cancel 된 상태이므로 자식 Job 은 시작 즉시 cancelled
        assertTrue(
            "close 된 scope 에서 launch 한 Job 은 cancelled 상태여야 함",
            job.isCancelled,
        )
    }

    @Test
    fun `F-008 R-020 케이스 A — close 시 async 도 CancellationException 으로 종결`() = runTest {
        val client = newClient()

        val started = CompletableDeferred<Unit>()
        val deferred = client.scope.async {
            started.complete(Unit)
            delay(60_000)
            "should-not-reach"
        }

        started.await()
        client.close()

        try {
            deferred.await()
            fail("케이스 A — close 후 async.await 는 CancellationException 을 throw 해야 함")
        } catch (e: CancellationException) {
            // 표준 코루틴 시맨틱 — Result.failure 로 변환되지 않음
        }
    }
}
