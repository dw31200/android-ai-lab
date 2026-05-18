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
| M-011 | SessionEntity | 영속화 직렬화 엔티티 (DataStore 저장 형식, 라운드 2 신규) |
| M-012 | ToolDefinition | 호출자가 등록하는 tool 정의 (라운드 7 v0.2 신규) |
| M-013 | ToolCall | 모델이 요청한 tool 호출 (라운드 7 v0.2 신규) |
| M-014 | ToolResult | tool 실행 결과 (라운드 7 v0.2 신규) |
| M-015 | ToolSchema | JSON Schema 부분 집합 (라운드 7 v0.2 신규) |

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
| text | String | Y | 응답 텍스트 (빈 문자열 가능, F-001 정상 흐름 4단계 검증 참조) |
| usage | TokenUsage (M-009) | Y | 토큰 사용량 |
| finishReason | FinishReason | Y | 종료 사유 (END_TURN/MAX_TOKENS/STOP_SEQUENCE/OTHER) |
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
| Uri | uri: android.net.Uri | content:// 또는 file:// URI. **v0.1: SDK는 자동 resolve 안 함 — `ask`/`askStream`/`session.send` 진입 시 즉시 E-203(`InvalidInput("uri unreadable")`)으로 거부.** 호출자가 `ContentResolver.openInputStream(uri)`으로 ByteArray를 읽고 `ImageInput.Bytes`로 변환 후 전달해야 한다 (D-004 연장, F-002 "v0.1 ImageInput.Uri 정책" 섹션 참조). v0.2에서 자동 resolve 옵션 검토. |
| Bytes | data: ByteArray, mimeType: String | 메모리 바이트 + mime |
| Url | url: String | https URL (Provider가 fetch) |

### Kotlin 정의
```kotlin
sealed class ImageInput {
    data class Uri(val uri: android.net.Uri) : ImageInput()

    class Bytes(val data: ByteArray, val mimeType: String) : ImageInput() {
        // R-016: data class 기본 equals는 ByteArray의 reference 비교라 부적절.
        // contentEquals + contentHashCode + mimeType 조합으로 명시.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            if (mimeType != other.mimeType) return false
            return data.contentEquals(other.data)
        }
        override fun hashCode(): Int {
            return 31 * data.contentHashCode() + mimeType.hashCode()
        }
        override fun toString(): String =
            "ImageInput.Bytes(mimeType=$mimeType, size=${data.size})"
    }

    data class Url(val url: String) : ImageInput()
}
```

> `Bytes`는 의도적으로 `data class`가 아닌 일반 `class`로 선언 (data class의 자동 생성 equals/hashCode가 ByteArray에 부적절). copy()가 필요한 경우 호출자가 명시적 생성.

### 직렬화 규칙
- `Uri`/`Bytes` → Provider 전송 직전 base64 인코딩 (image/jpeg, png, webp, gif만)
- `Url` → Provider가 URL fetch 지원 시 그대로, 미지원 시 SDK가 fetch 후 Bytes로 변환
- 단일 이미지 5MB 초과 시 E-201 throw (인코딩 시도 안 함)
- 헤더 매직 넘버로 mimeType 실제 검증, 위조 시 E-207

### 영속화 규칙 (M-011 참조)
- `Uri`: URI 문자열만 보관 (앱 재설치 시 깨질 수 있음)
- `Bytes`: base64 + mimeType으로 직렬화. 단, base64 인코딩 후 Session 직렬화 결과가 1MB를 초과하면 E-704로 거부 (F-007 "이미지 영속화 운영 가이드" 참조)
- `Url`: URL 문자열 그대로 보관. 복원 시 SSRF 방어는 SDK가 수행하지 않으며 호출자 책임 (M-011 "ImageInput.Url 영속화 보안 정책" 참조)

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
| IOError | message: String, cause: Throwable? | ERR-007 (라운드 2 신규, F-007 영속화 IO 오류) |

```kotlin
sealed class AiException(message: String? = null, cause: Throwable? = null)
    : Exception(message, cause) {
    class Network(cause: Throwable) : AiException(cause = cause)
    class RateLimit(val retryAfter: Duration?) : AiException()
    class Authentication : AiException()
    class Configuration(message: String) : AiException(message)
    class InvalidInput(message: String) : AiException(message)
    class ServerError(val code: Int, message: String? = null) : AiException(message)
    class IOError(message: String, cause: Throwable? = null) : AiException(message, cause)
}
```

> 주의: 호출자에게는 위 7개 variant만 노출. 내부 IOException/JsonParseException 등은 모두 위로 변환.

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
    val sessionId: String,                    // 자동 생성 UUID, save/load 키로 사용
    private val systemPrompt: String?,        // history에 포함되지 않음 (R-008)
    initialHistory: List<Message> = emptyList(),  // loadSession 복원 시 사용
) {
    suspend fun send(request: AiRequest): Result<AiResponse>
    fun history(): List<Message>              // immutable snapshot 반환 (R-011)
    fun clear()
    suspend fun save(): Result<String>        // F-007
}
```

### 내부 상태 및 동시성
- 내부 history: `MutableList<Message>` + `Mutex`로 보호 (R-007 참조)
- send 진입 시 Mutex 획득 → history 읽기/쓰기 → 해제. 동시 send는 직렬 처리 (E-403)
- `history()`는 Mutex 안에서 List 복사본을 반환하여 immutable snapshot 보장 (R-011)
- systemPrompt는 history와 별도 보관, history()/save()의 history 필드에 등장하지 않음

### Provider 바인딩 정책 (R-014)
- Session은 특정 Provider에 묶이지 않는다
- send 호출 시점의 client 활성 Provider를 사용
- loadSession으로 복원된 Session도 동일

### 다중 인스턴스 정책 (R-019 라운드 3)
- 같은 sessionId로 `loadSession`을 두 번 이상 호출하면 두 개의 독립 Session 인스턴스가 생성된다 (각자 별도의 history `MutableList<Message>` + Mutex 보유).
- 두 인스턴스가 각각 send/save를 호출해도 SDK는 충돌을 자동 검출하지 않는다.
- save는 DataStore의 transactional update 안에서 직렬화되어 last-write-wins로 수렴한다.
- 호출자는 동일 sessionId에 대해 단일 Session 인스턴스만 유지하도록 보장할 책임이 있다 (예: ViewModel scope에서 sessionId → Session 캐시).
- 다중 프로세스에서 동일 DataStore 파일에 접근하는 시나리오는 v0.1 Out of Scope (overview.md 참조).

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

### Role 사용 정책 (R-008)
- `USER`, `ASSISTANT`만 Session.history()에 등장
- `SYSTEM`은 enum 값으로 정의되어 있지만, Session에서는 systemPrompt 별도 필드로 관리되며 Message로 history에 추가되지 않음
- `Role.SYSTEM`은 Provider 전송용 내부 변환 단계에서만 사용 (Mapper 레벨)

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
// v0.1: 우선 구현된 Provider만 enum에 포함 (R-009)
// 미구현 Provider 식별자를 enum에 미리 두면 호출자가 build()에서 선택 가능해 동작 미정의 위험.
// v0.2/v0.3에서 구현 시 enum 값을 추가한다.
enum class ProviderId(val displayName: String) {
    CLAUDE("Anthropic Claude"),
    // OPENAI는 v0.2 구현 시 추가
    // GEMINI는 v0.3 구현 시 추가
}
```

### 설계 결정 (R-010)
- enum은 Provider 식별자(이름)만 보유
- Capabilities(supportsImage/supportsStream 등)는 **Provider 인터페이스 측의 단일 source of truth** (provider-spec.md 참조)
- 라운드 1의 ProviderId.supportsImage 등 중복 필드는 제거됨
- 호출자가 Capabilities를 조회할 필요가 있다면 `client.capabilities(): Capabilities` 같은 API를 v0.2 검토 (현재 미노출)

---

## M-011. SessionEntity (영속화 직렬화 엔티티) [라운드 2 신규]

DataStore에 저장되는 Session의 직렬화 형식. 호출자는 직접 사용하지 않는 internal 모델이지만, 디스크 호환성을 위해 사양에 명시한다.

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| schemaVersion | Int | Y | 직렬화 스키마 버전 (현재 1). 향후 마이그레이션용 |
| sessionId | String | Y | M-007.sessionId |
| systemPrompt | String? | N | M-007.systemPrompt |
| history | List\<MessageEntity\> | Y | 메시지 이력 |
| savedAt | Long | Y | 저장 시점 epoch millis |

```kotlin
@Serializable
internal data class SessionEntity(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val systemPrompt: String? = null,
    val history: List<MessageEntity>,
    val savedAt: Long,
)

@Serializable
internal data class MessageEntity(
    val role: String,         // "USER" or "ASSISTANT" (Role enum의 name)
    val content: String,
    val images: List<ImageInputEntity> = emptyList(),
    val timestamp: Long,
)

@Serializable
internal sealed class ImageInputEntity {
    @Serializable
    @SerialName("uri")
    data class Uri(val uri: String) : ImageInputEntity()

    @Serializable
    @SerialName("bytes")
    data class Bytes(val base64: String, val mimeType: String) : ImageInputEntity()

    @Serializable
    @SerialName("url")
    data class Url(val url: String) : ImageInputEntity()
}
```

### 직렬화 정책
- 형식: JSON (kotlinx.serialization)
- 인코딩: UTF-8
- DataStore Preferences 키: `"session:{sessionId}"`
- 보안: API 키는 Session에 포함되지 않으므로 영속화 데이터에 절대 포함되지 않음 (D-003)
- 크기 제한: JSON 직렬화 결과 1MB 초과 시 E-704 (DataStore Preferences는 단일 파일을 한 번에 처리)

### schemaVersion 정책 (R-018 / R-022 라운드 3)
- v0.1은 `schemaVersion = 1`만 인정한다.
- 로드 시 `schemaVersion != 1`이면 자동 마이그레이션을 시도하지 않고 즉시 `E-703`으로 거부 (`AiException.IOError("session schema unsupported: v={loaded}")`).
- 자동 마이그레이션 인터페이스는 v0.1 Out of Scope (overview.md 참조).
- v0.2 이상에서 schemaVersion이 증가할 때는 다음 패턴을 따른다 (사양상 미리 부기, 실제 코드는 v0.2부터):
  ```kotlin
  // (v0.2 시점에 추가될 예시 — v0.1 사양에는 미리 약속만 명시)
  internal object SessionMigrations {
      // from1_to2(json: JsonElement): JsonElement
      // from2_to3(json: JsonElement): JsonElement
      // load 시점에 schemaVersion을 읽고 1단계씩 적용 (v0.2부터)
  }
  ```
- v0.1 시점의 호출자는 schemaVersion이 1 이외의 값으로 디스크에 저장될 일이 없으므로(쓰기 시 항상 1로 직렬화), E-703 발생은 (a) 외부에서 데이터가 수정된 경우, (b) 미래 버전의 SDK가 쓴 데이터를 v0.1 SDK가 로드한 경우 등 비정상 시나리오에 한정된다.

### MessageEntity ↔ Message 변환
- Mapper가 `MessageEntity.role` 문자열을 `Role` enum으로 변환
- `Role.SYSTEM`은 history에 들어가지 않으므로 영속화/복원 시 무시

### ImageInputEntity ↔ ImageInput 변환
- `ImageInput.Uri` → `ImageInputEntity.Uri(uri.toString())`
- `ImageInput.Bytes` → `ImageInputEntity.Bytes(Base64.encode(data), mimeType)`
- `ImageInput.Url` → `ImageInputEntity.Url(url)`
- 복원 시 역변환. 복원된 `ImageInput.Uri`는 호출자 환경에서 read 가능한지 별도 보장 없음 (예: 앱 재설치 후)

### ImageInput.Url 영속화 보안 정책 (R-023 라운드 3)
- 영속화 시 SDK는 URL을 그대로 보관한다 (변환·필터링 없음).
- 복원 시 SDK는 URL의 형식 검증(`https://` 스킴, 길이 등)만 수행한다.
- **SDK는 URL이 외부망/내부망/loopback 중 어디를 가리키는지 판별하지 않는다 (SSRF 방어 미수행)**.
- 복원된 URL을 send에 사용했을 때 발생하는 SSRF 등 보안 책임은 호출자에게 있다 (도메인 allowlist, 내부망 차단 등은 호출자 측 처리).
- v0.2에서 SDK 차원의 URL allowlist 옵션 검토 예정 (provider-spec.md 보안 섹션 참조).

### 영속화 시 이미지 처리 가이드 (R-021 라운드 3)
- `ImageInput.Bytes`는 base64로 보관되며, 단일 5MB 이미지 한 장만 있어도 영속화 1MB 한계(E-704)를 초과한다.
- 호출자는 영속화 직전에 이미지 첨부 메시지를 history에서 제거하거나, 텍스트 요약으로 대체하거나, `ImageInput.Url`로 외부 호스팅 후 보관하는 방식을 사용해야 한다 (F-007 "이미지 영속화 운영 가이드" 참조).
- SDK는 자동으로 이미지를 제외/압축/외부 업로드 하지 않는다 (D-004 정책의 연장).

---

## M-012. ToolDefinition [v0.2 신규]

호출자가 Builder 시점에 등록하는 tool의 정의.

| 필드 | 타입 | 필수 | 설명 | 제약 조건 |
|------|------|------|------|-----------|
| name | String | Y | tool 식별자 | R-025 정규식 `^[a-zA-Z][a-zA-Z0-9_-]{0,63}$` |
| description | String | Y | 모델이 도구 용도를 이해하기 위한 설명 | 1~1024자 |
| inputSchema | ToolSchema (M-015) | Y | 입력 인자 스키마 | R-026 깊이 5 이하, R-029 부분 집합만 |

### Kotlin 정의
```kotlin
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: ToolSchema,
) {
    init {
        require(name.matches(Regex("^[a-zA-Z][a-zA-Z0-9_-]{0,63}$"))) {
            "invalid tool name: $name"
        }
        require(description.isNotBlank() && description.length <= 1024) {
            "description must be 1..1024 chars"
        }
    }
}
```

### 영속화 정책
- `@Serializable`은 부여하되, **v0.2 본 라운드에서 SessionEntity(M-011)에는 포함되지 않는다** (M-011 schemaVersion = 1 유지)
- 디스크에 ToolDefinition을 보관하지 않음 — Builder에서 매 client 생성 시 새로 등록해야 함

---

## M-013. ToolCall [v0.2 신규]

모델이 반환한 tool_use 블록을 SDK가 파싱한 결과. 호출자 Executor의 입력으로 전달된다.

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| id | String | Y | tool_use_id (Provider가 생성, 예: Anthropic의 `toolu_01...`) |
| name | String | Y | 호출 대상 tool 이름 (등록된 ToolDefinition.name과 매칭) |
| inputJson | JsonElement | Y | 모델이 생성한 input JSON (kotlinx.serialization JsonElement) |

### Kotlin 정의
```kotlin
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val inputJson: JsonElement,
)
```

### 동작
- SDK가 `name`이 등록된 tool에 없으면 E-907로 거부
- SDK가 `inputJson`이 등록된 ToolDefinition.inputSchema의 required/type을 만족하지 않으면 E-908로 거부 (호출자 Executor 호출 전 검증)

---

## M-014. ToolResult [v0.2 신규]

호출자 Executor가 반환하는 tool 실행 결과. SDK가 다음 턴 요청에 tool_result 블록으로 변환한다.

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| toolUseId | String | Y | M-013.id와 동일한 식별자 (Provider가 매칭) |
| content | String | Y | tool 실행 결과 텍스트 (모델이 읽음) |
| isError | Boolean | N | 실행 실패를 모델에게 알릴지 여부 (기본 false) |

### Kotlin 정의
```kotlin
@Serializable
data class ToolResult(
    val toolUseId: String,
    val content: String,
    val isError: Boolean = false,
) {
    companion object {
        fun success(toolUseId: String, content: String): ToolResult =
            ToolResult(toolUseId, content, isError = false)

        fun error(toolUseId: String, message: String): ToolResult =
            ToolResult(toolUseId, message, isError = true)
    }
}
```

### Executor 예외 정책
- Executor 내부에서 throw 시: SDK가 E-909(AiException.InvalidInput)으로 변환하여 루프 중단 (F-009 예외 흐름 참조)
- Executor가 의도적으로 실패를 모델에게 알리고 싶다면 `ToolResult.error(...)`을 반환하면 됨 (이 경우 루프 계속)

---

## M-015. ToolSchema (JSON Schema 부분 집합) [v0.2 신규]

JSON Schema의 **부분 집합**만 지원하는 입력 스키마. 모델이 input을 생성하기 위해 사용하며, SDK는 모델 응답의 input을 이 스키마로 검증한다.

### 지원 키워드 (R-029)
- `type`: `string` / `number` / `integer` / `boolean` / `object` / `array` / `null`
- `properties`: object 타입의 필드 정의
- `required`: object 타입의 필수 필드 이름 리스트
- `description`: 필드 설명 (모델 힌트)
- `items`: array 타입의 element schema
- `enum`: scalar 값(string/number/integer/boolean)의 허용 집합

### 미지원 키워드 (E-903 거부)
- `oneOf`, `anyOf`, `allOf`, `not`
- `$ref`, `$defs`
- `pattern`, `format`
- `minimum`, `maximum`, `minLength`, `maxLength`
- `additionalProperties`, `patternProperties`

### Kotlin 정의
```kotlin
@Serializable
sealed class ToolSchema {
    @Serializable
    @SerialName("string")
    data class StringType(val description: String? = null, val enum: List<String>? = null) : ToolSchema()

    @Serializable
    @SerialName("integer")
    data class IntegerType(val description: String? = null, val enum: List<Int>? = null) : ToolSchema()

    @Serializable
    @SerialName("number")
    data class NumberType(val description: String? = null) : ToolSchema()

    @Serializable
    @SerialName("boolean")
    data class BooleanType(val description: String? = null) : ToolSchema()

    @Serializable
    @SerialName("object")
    data class ObjectType(
        val properties: Map<String, ToolSchema>,
        val required: List<String> = emptyList(),
        val description: String? = null,
    ) : ToolSchema()

    @Serializable
    @SerialName("array")
    data class ArrayType(
        val items: ToolSchema,
        val description: String? = null,
    ) : ToolSchema()

    companion object {
        fun string(description: String? = null, enum: List<String>? = null) =
            StringType(description, enum)
        fun integer(description: String? = null) = IntegerType(description)
        fun objectSchema(
            properties: Map<String, ToolSchema>,
            required: List<String> = emptyList(),
            description: String? = null,
        ) = ObjectType(properties, required, description)
    }
}
```

### 깊이 검증 (R-026)
- root ObjectType을 깊이 1로 카운트
- ObjectType.properties / ArrayType.items를 따라 들어갈 때마다 깊이 +1
- 최대 깊이 5. 6 이상이면 Builder.build()에서 E-904로 즉시 거부
- 검증 시점: Builder.build() (런타임 ask 호출 시점에 다시 검증하지 않음)

### Provider 직렬화
- P-CLAUDE: Anthropic Messages API의 `input_schema` 필드 형식으로 직렬화 (provider-spec.md "P-CLAUDE tool 변환 규칙" 참조)
- Anthropic이 요구하는 JSON Schema 형식 일부와 정확히 매핑되도록 SerialName을 `type` 값에 맞춤 (`string`/`object`/`array` 등)

### 영속화 정책
- v0.2 본 라운드에서는 영속화 대상 아님 — M-011 SessionEntity에 포함되지 않음
- ToolDefinition은 매 Builder 시점에 호출자가 다시 등록해야 함

---

## 관계도

```
AiAgentClient ──1── creates ──N── Session
   │                                │
   │ uses                           │ save/load via DataStore
   ↓                                ↓
Provider (인터페이스)            SessionEntity (M-011)
   ↑
   │ 구현
ClaudeProviderImpl

AiRequest ──N── ImageInput
AiRequest ──N── VideoInput  (v0.2)
AiResponse ──1── TokenUsage
Session ──N── Message
SessionEntity ──N── MessageEntity ──N── ImageInputEntity

AiAgentClient ──N── ToolDefinition (v0.2, Builder 시점 등록)
ToolDefinition ──1── ToolSchema
[Provider tool_use 응답] ──N── ToolCall ──(Executor)──> ToolResult
```

## 직렬화 정책

- Provider 전송용 직렬화는 각 Provider 구현이 책임 (Anthropic API 형식, OpenAI API 형식 등)
- SDK 내부 모델 ↔ Provider 모델 변환은 `provider/{provider}/Mapper.kt`에서 처리
- 호출자 노출 모델은 직렬화 가능해야 함 (kotlinx.serialization @Serializable)
- 영속화용 Internal 모델(M-011)은 SDK 외부 노출 금지
