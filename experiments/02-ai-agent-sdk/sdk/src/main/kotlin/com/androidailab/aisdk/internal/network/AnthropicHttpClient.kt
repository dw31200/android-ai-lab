package com.androidailab.aisdk.internal.network

import com.androidailab.aisdk.model.AiException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Anthropic HTTP 호출 wrapper (P-CLAUDE, F-001).
 *
 * 사양 참조:
 * - overview.md "기술 스택": OkHttp (D-001)
 * - features.md F-001 E-106: 코루틴 취소 cooperative — `Call.cancel()` 연동
 * - features.md F-001 E-108: 타임아웃 → [AiException.Network] (cause=SocketTimeoutException)
 * - features.md F-008 정상 흐름 2: `dispatcher.cancelAll()`로 close 시 진행 중 호출 중단
 *
 * 책임:
 * 1. [OkHttpClient]를 timeout과 함께 lazy 생성하고 [com.androidailab.aisdk.AiAgentClient.httpClient]
 *    holder에 등록 (close 시 dispatcher.cancelAll 가능하도록).
 * 2. `Call.enqueue` + [suspendCancellableCoroutine]으로 코루틴 취소를 OkHttp Call cancel에 연동
 *    (작업 원칙 5: "취소 가능성 — OkHttp call cancel을 cooperative 취소에 연결").
 * 3. 외부 예외(IOException 등)는 [ErrorMapper]를 통해 [AiException]으로 변환.
 *
 * thread-safety: 본 클래스는 내부 상태가 없으므로 자유롭게 공유 가능. [OkHttpClient]는 자체 thread-safe.
 *
 * @param httpClient OkHttp 클라이언트 인스턴스. 단위 테스트에서 MockWebServer 기반 클라이언트를 주입할 수 있다.
 */
internal class AnthropicHttpClient(
    val httpClient: OkHttpClient,
) {

    /**
     * HTTP 요청 실행 (suspend, F-001).
     *
     * 동작:
     * - `Call.enqueue` + [suspendCancellableCoroutine]
     * - `cont.invokeOnCancellation { call.cancel() }` — 코루틴 취소 시 OkHttp call cancel (E-106)
     * - 호출 실패(IOException) → [ErrorMapper.fromException]으로 변환 후 resumeWithException
     * - 호출 성공 → resume(response). 호출자가 [Response.body]를 닫을 책임.
     *
     * 본 메서드는 [Response]만 반환한다. status code 검증/본문 파싱은 호출자(Provider)의 책임.
     *
     * @param request OkHttp 요청
     * @return [Response] (호출자가 use 블록으로 닫아야 함)
     * @throws AiException [ErrorMapper.fromException] 매핑 결과
     * @throws kotlinx.coroutines.CancellationException 코루틴 취소 시 (E-106 — 호출자에게 전파)
     */
    suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call: Call = httpClient.newCall(request)

        // E-106: 코루틴 취소 시 OkHttp call cancel — cooperative cancellation
        cont.invokeOnCancellation {
            try {
                call.cancel()
            } catch (_: Throwable) {
                // best-effort. cancel은 throw하지 않는다고 OkHttp 문서 명시되어 있으나 방어적 catch.
            }
        }

        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                // 코루틴이 이미 취소된 경우 resumeWithException 호출하지 않음 — CancellationException이 우선
                if (cont.isCancelled) return
                cont.resumeWithException(ErrorMapper.fromException(e))
            }
        })
    }

    companion object {
        /**
         * [OkHttpClient]를 [timeout]과 함께 빌드한다 (F-001 NFR).
         *
         * 사양 참조:
         * - features.md F-001 NFR "응답 시간: p95 5초 (네트워크 정상 기준)"
         * - api.md A-001: timeout 기본값 30.seconds (S-002)
         *
         * connect/read/write/call 타임아웃 모두 동일 [timeout] 적용.
         * dispatcher는 OkHttp 기본값 사용 (cancelAll로 close 시 진행 중 호출 중단 가능 — F-008).
         */
        fun newOkHttpClient(timeout: Duration): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(timeout.toJavaDuration())
            .readTimeout(timeout.toJavaDuration())
            .writeTimeout(timeout.toJavaDuration())
            .callTimeout(timeout.toJavaDuration())
            .build()
    }
}
