# 데이터 모델

> 작성 규칙
> - 엔티티 ID: M-숫자 (예: M-001)
> - 모든 필드의 타입과 제약 조건을 명시할 것

---

## 엔티티 목록

| ID | 엔티티명 | 설명 |
|----|----------|------|
| M-001 | AiRequest | 질의 요청 (텍스트 + 선택적 멀티모달 입력) |
| M-002 | AiResponse | 질의 응답 |
| M-003 | ImageInput | 이미지 입력 (sealed class) |
| M-004 | VideoInput | 영상 입력 (sealed class, v0.2) |
| M-005 | AiException | 에러 sealed class |
| M-006 | AiStreamEvent | 스트림 이벤트 sealed class |
| M-007 | Session | 대화 세션 |
| M-008 | Message | 세션 내 메시지 단위 |
| M-009 | TokenUsage | 토큰 사용량 |
| M-010 | ProviderId | Provider 식별자 enum |

---

## M-001. AiRequest

| 필드 | 타입 | 필수 | 설명 | 제약 조건 |
|------|------|------|------|-----------|
| prompt | String | Y | 텍스트 프롬프트 | 1자 이상, 100,000자 이하 |
| images | List\<ImageInput\> | N | 이미지 입력 | 최대 10장, 합계 20MB |
| videos | List\<VideoInput\> | N | 영상 입력 (v0.2) | v0.1에서는 빈 리스트 강제 |
| maxTokens | Int | N | 최대 응답 토큰 | 기본 1024, 1~8192 |
| temperature | Float | N | 샘플링 온도 | 기본 0.7, 0.0~2.0 |

### Kotlin 정의
```kotlin
data class AiRequest(
    val prompt: String,
    val images: List<ImageInput> = emptyList(),
    val videos: List<VideoInput> = emptyList(),
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f,
) {
    init {
        require(prompt.isNotBlank()) { "prompt must not be blank" }
        require(prompt.length <= 100_000) { "prompt too long" }
        require(images.size <= 10) { "too many images" }
        require(maxTokens in 1..8192) { "maxTokens out of range" }
        require(temperature in 0f..2f) { "temperature out of range" }
    }
}
```

---

## M-002. AiResponse

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| text | String | Y | 응답 텍스트 |
| usage | TokenUsage (M-009) | Y | 토큰 사용량 |
| finishReason | FinishReason | Y | 종료 사유 (END_TURN/MAX_TOKENS/STOP_SEQUENCE) |
| providerId | ProviderId (M-010) | Y | 응답을 만든 Provider |

```kotlin
data class AiResponse(
    val text: String,
    val usage: TokenUsage,
    val finishReason: FinishReason,
    val providerId: ProviderId,
)

enum class FinishReason { END_TURN, MAX_TOKENS, STOP_SEQUENCE, OTHER }
```

---

## M-003. ImageInput (sealed class)

| Variant | 필드 | 설명 |
|---------|------|------|
| Uri | uri: android.net.Uri | content:// 또는 file:// URI |
| Bytes | data: ByteArray, mimeType: String | 메모리 바이트 + mime |
| Url | url: String | https URL (Provider가 fetch) |

### Kotlin 정의
```kotlin
sealed class ImageInput {
    data class Uri(val uri: android.net.Uri) : ImageInput()
    data class Bytes(val data: ByteArray, val mimeType: String) : ImageInput() {
        override fun equals(other: Any?): Boolean = ...
        override fun hashCode(): Int = ...
    }
    data class Url(val url: String) : ImageInput()
}
```

### 직렬화 규칙
- `Uri`/`Bytes` → Provider 전송 직전 base64 인코딩 (image/jpeg, png, webp, gif만)
- `Url` → Provider가 URL fetch 지원 시 그대로, 미지원 시 SDK가 fetch 후 Bytes로 변환
- 단일 이미지 5MB 초과 시 E-201 throw (인코딩 시도 안 함)

---

## M-004. VideoInput (sealed class) [v0.2]

v0.1에서는 사용하지 않음. 사양만 미리 정의:

```kotlin
sealed class VideoInput {
    data class Uri(val uri: android.net.Uri) : VideoInput()
    data class Url(val url: String) : VideoInput()
}
```

---

## M-005. AiException (sealed class)

| Variant | 필드 | ERR-XXX 매핑 |
|---------|------|--------------|
| Network | cause: Throwable | ERR-001 |
| RateLimit | retryAfter: Duration? | ERR-002 |
| Authentication | (없음) | ERR-003 |
| Configuration | message: String | ERR-004 |
| InvalidInput | message: String | ERR-005 |
| ServerError | code: Int, message: String? | ERR-006 |

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

> 주의: 호출자에게는 위 6개 variant만 노출. 내부 IOException/JsonParseException 등은 모두 위로 변환.

---

## M-006. AiStreamEvent (sealed class)

```kotlin
sealed class AiStreamEvent {
    data class Delta(val text: String) : AiStreamEvent()
    data class Done(val response: AiResponse) : AiStreamEvent()
    data class Error(val cause: AiException) : AiStreamEvent()
}
```

방출 순서 보장:
- Delta는 0회 이상 방출 가능
- Done 또는 Error는 정확히 1회로 종결
- Done 이후 Delta 방출 금지

---

## M-007. Session

```kotlin
class Session internal constructor(
    private val client: AiAgentClient,
    private val systemPrompt: String?,
) {
    suspend fun send(request: AiRequest): Result<AiResponse>
    fun history(): List<Message>
    fun clear()
}
```

내부 상태: `MutableList<Message>` (thread-safe하게 보호)

---

## M-008. Message

| 필드 | 타입 | 설명 |
|------|------|------|
| role | Role | USER 또는 ASSISTANT |
| content | String | 텍스트 |
| images | List\<ImageInput\> | 첨부 이미지 (USER 메시지에만) |
| timestamp | Long | epoch millis |

```kotlin
data class Message(
    val role: Role,
    val content: String,
    val images: List<ImageInput> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
)

enum class Role { USER, ASSISTANT, SYSTEM }
```

---

## M-009. TokenUsage

```kotlin
data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}
```

---

## M-010. ProviderId (enum)

```kotlin
enum class ProviderId(val displayName: String, val supportsImage: Boolean, val supportsStream: Boolean) {
    CLAUDE("Anthropic Claude", supportsImage = true, supportsStream = true),
    OPENAI("OpenAI GPT", supportsImage = true, supportsStream = true),
    GEMINI("Google Gemini", supportsImage = true, supportsStream = true),
}
```

---

## 관계도

```
AiAgentClient ──1── creates ──N── Session
   │
   │ uses
   ↓
Provider (인터페이스)
   ↑
   │ 구현
ClaudeProviderImpl

AiRequest ──N── ImageInput
AiRequest ──N── VideoInput  (v0.2)
AiResponse ──1── TokenUsage
Session ──N── Message
```

## 직렬화 정책

- Provider 전송용 직렬화는 각 Provider 구현이 책임 (Anthropic API 형식, OpenAI API 형식 등)
- SDK 내부 모델 ↔ Provider 모델 변환은 `provider/{provider}/Mapper.kt`에서 처리
- 호출자 노출 모델은 직렬화 가능해야 함 (kotlinx.serialization @Serializable)
