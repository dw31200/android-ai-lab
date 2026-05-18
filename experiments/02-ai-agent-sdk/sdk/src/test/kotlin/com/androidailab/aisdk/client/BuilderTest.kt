package com.androidailab.aisdk.client

import android.content.Context
import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.model.ProviderId
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * F-000 (클라이언트 초기화) Builder 검증 테스트.
 *
 * 사양 참조:
 * - features.md F-000 정상 흐름 1~3, E-001~E-003
 * - api.md A-001 (Builder), A-002 (build)
 * - error-handling.md ERR-004 매핑
 *
 * 테스트 함수명에 F-XXX/E-XXX를 박아 추적성 보장 (스킬 SKILL.md "단위 테스트").
 */
class BuilderTest {

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
    // 정상 케이스
    // -----------------------------------------------------------------

    @Test
    fun `F-000 정상 흐름 — 모든 필드 설정 시 client 인스턴스 반환`() {
        val client = Builder(context)
            .apiKey("sk-test-12345")
            .provider(ProviderId.CLAUDE)
            .model("claude-opus-4-7")
            .timeout(30.seconds)
            .build()

        assertNotNull(client)
        assertEquals("sk-test-12345", client.apiKey)
        assertEquals(ProviderId.CLAUDE, client.activeProviderId)
        assertEquals("claude-opus-4-7", client.modelId)
        assertEquals(30.seconds, client.timeout)
    }

    @Test
    fun `F-000 정상 흐름 — apiKey 만 설정해도 기본값으로 build 성공`() {
        // provider/model/timeout 미설정 시 합리적 기본값으로 통과
        val client = Builder(context)
            .apiKey("sk-test-default")
            .build()

        assertEquals(ProviderId.CLAUDE, client.activeProviderId)
        assertEquals(Builder.DEFAULT_MODEL_ID, client.modelId)
        assertEquals(Builder.DEFAULT_TIMEOUT, client.timeout)
    }

    @Test
    fun `F-000 정상 흐름 — Builder 체이닝은 같은 인스턴스 반환`() {
        val builder = Builder(context)
        val chained = builder
            .apiKey("sk-test")
            .provider(ProviderId.CLAUDE)
            .model("claude-opus-4-7")
            .timeout(30.seconds)

        assertSame(builder, chained)
    }

    @Test
    fun `F-000 정상 흐름 — applicationContext 가 보관됨 (메모리 누수 방지)`() {
        val client = Builder(context)
            .apiKey("sk-test")
            .build()

        // Builder 가 context.applicationContext 를 내부에 저장했는지 확인
        assertSame(appContext, client.context)
    }

    @Test
    fun `F-000 정상 흐름 — companion builder 헬퍼로 동일하게 build 가능`() {
        val client = com.androidailab.aisdk.AiAgentClient
            .builder(context)
            .apiKey("sk-test")
            .build()

        assertNotNull(client)
        assertEquals("sk-test", client.apiKey)
    }

    // -----------------------------------------------------------------
    // E-001: apiKey 누락
    // -----------------------------------------------------------------

    @Test
    fun `F-000 E-001 — apiKey 미설정 시 Configuration throw`() {
        try {
            Builder(context).build()
            fail("expected AiException.Configuration but no exception was thrown")
        } catch (e: AiException.Configuration) {
            assertTrue(
                "message should mention 'api key', actual=${e.message}",
                e.message?.contains("api key", ignoreCase = true) == true,
            )
        }
    }

    @Test
    fun `F-000 E-001 — apiKey 빈 문자열이면 Configuration throw`() {
        try {
            Builder(context).apiKey("").build()
            fail("expected AiException.Configuration but no exception was thrown")
        } catch (e: AiException.Configuration) {
            assertTrue(e.message?.contains("api key", ignoreCase = true) == true)
        }
    }

    @Test
    fun `F-000 E-001 — apiKey 공백 문자열이면 Configuration throw`() {
        try {
            Builder(context).apiKey("   ").build()
            fail("expected AiException.Configuration but no exception was thrown")
        } catch (e: AiException.Configuration) {
            assertTrue(e.message?.contains("api key", ignoreCase = true) == true)
        }
    }

    // -----------------------------------------------------------------
    // E-002: 알 수 없는 Provider
    // -----------------------------------------------------------------
    //
    // 주의: v0.1 ProviderId enum 값에는 CLAUDE 만 존재 (R-009).
    // 따라서 enum에 정의되지 않은 값을 만들 방법이 없으므로, 본 케이스는
    // (a) 화이트리스트가 enum 확장 시 자동으로 따라가지 않는다는 회귀 방지 의미,
    // (b) 화이트리스트 검사 자체가 build() 경로에서 동작하는지 확인 의미를 갖는다.
    // 향후 enum에 OPENAI/GEMINI가 추가되고 SDK가 아직 구현 못 했을 때 본 검증이 발효된다.

    @Test
    fun `F-000 E-002 — 지원되는 Provider 화이트리스트는 v0_1 시점 CLAUDE 만 포함`() {
        // Builder.SUPPORTED_PROVIDERS 를 직접 검증하여 enum 확장 시 회귀 방지
        assertEquals(setOf(ProviderId.CLAUDE), Builder.SUPPORTED_PROVIDERS)
    }

    @Test
    fun `F-005 R-009 — defaultProviders 와 SUPPORTED_PROVIDERS 가 동기화됨`() {
        // R-009: enum에 추가되는 모든 값은 즉시 동작 가능해야 함.
        // SUPPORTED_PROVIDERS 와 defaultProviders 가 항상 같은 식별자 집합을 가져야 한다.
        val whitelistIds = Builder.SUPPORTED_PROVIDERS
        val registeredIds = Builder.defaultProviders().map { it.id }.toSet()
        assertEquals(
            "defaultProviders ids must match SUPPORTED_PROVIDERS",
            whitelistIds,
            registeredIds,
        )
    }

    // -----------------------------------------------------------------
    // E-003: timeout < 1초
    // -----------------------------------------------------------------

    @Test
    fun `F-000 E-003 — timeout 0초이면 Configuration throw`() {
        try {
            Builder(context)
                .apiKey("sk-test")
                .timeout(0.seconds)
                .build()
            fail("expected AiException.Configuration but no exception was thrown")
        } catch (e: AiException.Configuration) {
            assertTrue(
                "message should mention 'timeout', actual=${e.message}",
                e.message?.contains("timeout", ignoreCase = true) == true,
            )
        }
    }

    @Test
    fun `F-000 E-003 — timeout 999ms이면 Configuration throw (경계값 직전)`() {
        try {
            Builder(context)
                .apiKey("sk-test")
                .timeout(999.milliseconds)
                .build()
            fail("expected AiException.Configuration but no exception was thrown")
        } catch (e: AiException.Configuration) {
            assertTrue(e.message?.contains("timeout", ignoreCase = true) == true)
        }
    }

    @Test
    fun `F-000 E-003 — timeout 정확히 1초이면 build 성공 (경계값)`() {
        val client = Builder(context)
            .apiKey("sk-test")
            .timeout(1.seconds)
            .build()

        assertEquals(1.seconds, client.timeout)
    }

    @Test
    fun `F-000 E-003 — timeout 음수이면 Configuration throw`() {
        try {
            Builder(context)
                .apiKey("sk-test")
                .timeout((-5).seconds)
                .build()
            fail("expected AiException.Configuration but no exception was thrown")
        } catch (e: AiException.Configuration) {
            assertTrue(e.message?.contains("timeout", ignoreCase = true) == true)
        }
    }
}
