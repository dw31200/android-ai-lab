---
name: android-sdk-implementer
description: 확정된 SDD 사양을 기반으로 Kotlin Android SDK 모듈을 구현하는 스킬. Provider 추상화, Coroutines + Flow, Hilt 모듈, 멀티모달 입력 처리, 코루틴 취소 처리 패턴을 제공한다. "F-XXX 구현해줘", "SDK 코드 작성", "Provider 구현", "Hilt 모듈" 같은 요청 시 반드시 사용할 것. UI(Compose) 코드 작성에는 사용하지 않음.
---

# android-sdk-implementer

## 언제 이 스킬을 쓰는가

- 합의된 사양(F-XXX, M-XXX, ERR-XXX)을 Kotlin 코드로 변환
- Provider 추상화 계층 구현
- 멀티모달 입력 처리 (Image/Video) 구현
- Coroutine + Flow 기반 비동기 API 구현
- Hilt 모듈 구성

## 구현 원칙

### 1. 사양 ID를 KDoc에 박아라

```kotlin
/**
 * 텍스트 단발 질의 (F-001).
 *
 * @see api.md A-002
 */
suspend fun ask(request: AiRequest): Result<AiResponse>
```

이유: 사양과 코드가 떨어지지 않도록 보장. QA 검증의 근거가 된다.

### 2. Provider는 첫 단계부터 인터페이스

```kotlin
interface Provider {
    val id: ProviderId
    suspend fun complete(request: AiRequest, config: ProviderConfig): AiResponse
    fun stream(request: AiRequest, config: ProviderConfig): Flow<AiStreamEvent>
}

class ClaudeProvider @Inject constructor(
    private val httpClient: HttpClient,
    private val serializer: Serializer,
) : Provider {
    override val id = ProviderId.CLAUDE
    // ...
}
```

이유: 단일 구현이라도 인터페이스를 두면 테스트에서 mock 가능. 멀티 Provider가 사양에 들어 있으니 처음부터 추상화한다.

### 3. 비동기 일관성

| 사용 케이스 | 시그니처 |
|-----------|---------|
| 단발 호출 | `suspend fun` 반환 `Result<T>` |
| 스트리밍 | `Flow<AiStreamEvent>` (콜드 Flow 권장) |
| 절대 금지 | 콜백 기반 API, RxJava |

### 4. 취소 가능성

OkHttp 사용 시 코루틴 취소를 OkHttp call cancel에 연결:

```kotlin
suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
    val call = client.newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(e)
        }
    })
}
```

### 5. 에러 변환

호출자에게는 항상 `AiException` sealed class만 노출:

```kotlin
sealed class AiException(message: String? = null, cause: Throwable? = null)
    : Exception(message, cause) {
    class Network(cause: Throwable) : AiException(cause = cause)
    class RateLimit(val retryAfter: Duration?) : AiException()
    class Authentication : AiException()
    class Configuration(message: String) : AiException(message)
    class InvalidInput(message: String) : AiException(message)
    class ServerError(val code: Int, message: String? = null) : AiException(message)
}
```

내부 예외(IOException, JsonParseException 등)는 모두 위로 변환:

```kotlin
internal suspend fun <T> mapErrors(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e  // 취소는 그대로 전파
} catch (e: IOException) {
    throw AiException.Network(e)
} catch (e: HttpException) {
    when (e.code) {
        401 -> throw AiException.Authentication()
        429 -> throw AiException.RateLimit(e.retryAfter)
        in 500..599 -> throw AiException.ServerError(e.code)
        else -> throw AiException.ServerError(e.code)
    }
}
```

### 6. Hilt 모듈

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AiSdkModule {

    @Provides
    @Singleton
    fun provideHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30.seconds.toJavaDuration())
        .build()

    @Provides
    @IntoSet
    fun provideClaudeProvider(impl: ClaudeProvider): Provider = impl

    @Provides
    @Singleton
    fun provideClient(
        providers: Set<@JvmSuppressWildcards Provider>,
        // ...
    ): AiAgentClient = AiAgentClient(providers, ...)
}
```

호출자는 `@HiltAndroidApp`만 적용하면 자동 주입.

### 7. 멀티모달 입력 직렬화

```kotlin
internal class ImageEncoder(private val context: Context) {
    suspend fun encode(input: ImageInput): EncodedImage = when (input) {
        is ImageInput.Uri -> encodeFromUri(input.uri)
        is ImageInput.Bytes -> encodeFromBytes(input.data, input.mimeType)
        is ImageInput.Url -> EncodedImage.RemoteUrl(input.url)
    }

    private suspend fun encodeFromUri(uri: Uri): EncodedImage =
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri).use { stream ->
                val bytes = stream?.readBytes() ?: throw AiException.InvalidInput("uri 읽기 실패")
                EncodedImage.Base64(Base64.encodeToString(bytes, Base64.NO_WRAP), detectMime(bytes))
            }
        }
    // ...
}
```

이미지 크기 검증은 사양에 명시된 기준(예: 5MB)을 따른다.

## 모듈 구조 (사양 권장)

```
experiments/{exp-id}/sdk/
└── src/
    ├── main/kotlin/com/androidailab/aisdk/
    │   ├── AiAgentClient.kt          ← public 진입점
    │   ├── client/
    │   │   ├── Builder.kt
    │   │   └── Session.kt
    │   ├── model/
    │   │   ├── AiRequest.kt
    │   │   ├── AiResponse.kt
    │   │   ├── ImageInput.kt
    │   │   ├── VideoInput.kt
    │   │   └── AiException.kt
    │   ├── provider/
    │   │   ├── Provider.kt           ← 인터페이스
    │   │   ├── ProviderRegistry.kt
    │   │   └── claude/ClaudeProvider.kt
    │   ├── session/
    │   ├── internal/
    │   │   ├── HttpClient.kt
    │   │   ├── Serializer.kt
    │   │   ├── ImageEncoder.kt
    │   │   └── ErrorMapper.kt
    │   └── di/
    │       └── AiSdkModule.kt
    └── test/kotlin/...
```

## 단위 테스트 (필수)

각 F-XXX마다 최소 다음 케이스를 작성:

```kotlin
class AskTest {
    @Test fun `F-001 정상 흐름 — 텍스트 응답 반환`() = runTest { ... }
    @Test fun `F-001 E-001 — API 키 누락 시 Configuration throw`() = runTest { ... }
    @Test fun `F-001 E-002 — 네트워크 오류 시 Network로 래핑`() = runTest { ... }
    @Test fun `F-001 E-004 — 코루틴 취소 시 진행 중 호출 중단`() = runTest { ... }
}
```

테스트 함수명에 F-XXX, E-XXX를 박아 추적성 보장.

## 자체 체크리스트 (커밋 전)

- [ ] 사양에 없는 동작을 추가하지 않았다
- [ ] 모든 public 함수에 KDoc + 관련 F-XXX/A-XXX 참조
- [ ] 모든 F-XXX에 단위 테스트 작성 (정상 + 모든 E-XXX)
- [ ] 외부 예외가 모두 AiException으로 변환됨
- [ ] suspend/Flow 외 비동기 API 없음
- [ ] CancellationException 별도 처리 (catch 후 그대로 throw)
- [ ] Hilt 모듈로 외부 노출
- [ ] 임의 추상화/플래그 없음

## 사양 누락 발견 시

작업을 멈추고 spec-architect에게 SendMessage:
```
F-002 구현 중 누락 발견. 영상 입력의 최대 크기가 사양에 없음.
data-model.md M-004에 추가 필요. 임의 결정 대신 결정 요청.
```
