# 공개 API

> 작성 규칙
> - 모든 public 함수의 시그니처를 토큰 단위로 명시
> - suspend / Flow 여부 명시
> - 관련 F-XXX, M-XXX 참조

---

## API 목록

| ID | 이름 | 종류 | 관련 F-ID |
|----|------|------|-----------|
| A-001 | AiAgentClient.Builder | Builder 클래스 | F-000 |
| A-002 | AiAgentClient.ask | suspend fun | F-001, F-002 |
| A-003 | AiAgentClient.askStream | Flow fun | F-003 |
| A-004 | AiAgentClient.createSession | fun | F-004 |
| A-005 | AiAgentClient.useProvider | fun | F-005 |
| A-006 | Session.send | suspend fun | F-004 |
| A-007 | Session.history | fun | F-004 |
| A-008 | Session.clear | fun | F-004 |

---

## A-001. AiAgentClient.Builder

### 시그니처
```kotlin
class AiAgentClient.Builder(context: Context) {
    fun apiKey(key: String): Builder
    fun provider(provider: ProviderId): Builder
    fun model(modelId: String): Builder
    fun timeout(duration: Duration): Builder
    fun build(): AiAgentClient
}
```

### 동작 (관련 F-000)
- 정상 흐름: F-000 정상 흐름 1~3
- 예외: E-001, E-002, E-003

### 사용 예
```kotlin
val client = AiAgentClient.Builder(context)
    .apiKey(BuildConfig.AI_API_KEY)
    .provider(ProviderId.CLAUDE)
    .model("claude-opus-4-7")
    .timeout(30.seconds)
    .build()
```

---

## A-002. AiAgentClient.ask

### 시그니처
```kotlin
suspend fun ask(request: AiRequest): Result<AiResponse>
```

### 파라미터
| 이름 | 타입 | 필수 | 설명 |
|------|------|------|------|
| request | AiRequest (M-001) | Y | 질의 요청 (텍스트 + 선택적 이미지) |

### 반환
- 성공: `Result.success(AiResponse)` (M-002)
- 실패: `Result.failure(AiException)` (M-005, ERR-001~ERR-006)

### 동작 (관련 F-001, F-002)
- 정상 흐름: F-001/F-002 정상 흐름
- 예외 흐름: E-101~E-108, E-201~E-205

### 사용 예
```kotlin
val result = client.ask(AiRequest(
    prompt = "이 이미지에 무엇이 있나요?",
    images = listOf(ImageInput.Uri(imageUri)),
))
result.onSuccess { response ->
    println(response.text)
}.onFailure { error ->
    when (error) {
        is AiException.Network -> retry()
        is AiException.RateLimit -> waitAndRetry(error.retryAfter)
        else -> showError(error.message)
    }
}
```

---

## A-003. AiAgentClient.askStream

### 시그니처
```kotlin
fun askStream(request: AiRequest): Flow<AiStreamEvent>
```

### 파라미터
| 이름 | 타입 | 필수 | 설명 |
|------|------|------|------|
| request | AiRequest (M-001) | Y | 질의 요청 |

### 반환
- `Flow<AiStreamEvent>` (M-006)
  - `Delta(text)` — 토큰 chunk
  - `Done(response)` — 스트림 종료 + 누적 응답
  - `Error(cause)` — 도중 오류

### 동작 (관련 F-003)
- 정상 흐름: F-003 1~4
- 예외: E-301, E-302, E-303, E-101~E-108

### 사용 예
```kotlin
client.askStream(AiRequest("이야기를 들려줘"))
    .collect { event ->
        when (event) {
            is AiStreamEvent.Delta -> appendText(event.text)
            is AiStreamEvent.Done -> showFinal(event.response)
            is AiStreamEvent.Error -> showError(event.cause)
        }
    }
```

---

## A-004. AiAgentClient.createSession

### 시그니처
```kotlin
fun createSession(systemPrompt: String? = null): Session
```

### 반환
- `Session` 인스턴스 (M-007)

### 동작 (관련 F-004)
- F-004 1단계의 세션 생성

---

## A-005. AiAgentClient.useProvider

### 시그니처
```kotlin
fun useProvider(provider: ProviderId)
```

### 동작 (관련 F-005)
- 활성 Provider 변경. 진행 중 요청 영향 없음, 다음 요청부터 적용.
- 예외: E-501, E-502

---

## A-006. Session.send

### 시그니처
```kotlin
suspend fun send(request: AiRequest): Result<AiResponse>
```

### 동작 (관련 F-004)
- 이전 메시지 + 현재 요청을 합쳐 Provider 호출
- 응답을 history에 추가
- 예외: E-401, E-402, E-101~E-108

---

## A-007. Session.history

### 시그니처
```kotlin
fun history(): List<Message>
```

### 반환
- 현재 세션의 메시지 목록 (M-008)

---

## A-008. Session.clear

### 시그니처
```kotlin
fun clear()
```

### 동작
- 세션 history를 초기화. systemPrompt는 유지.

---

## API 안정성 정책

- v0.x: API 변경 가능 (semver 0.x.y)
- v1.0 이후: public API는 호환성 유지, 변경 시 deprecation 1버전 이상

## API 노출 원칙

- public: 호출자가 직접 사용 (위 표 8개)
- internal: SDK 내부 협업용, 외부 노출 금지 (HttpClient, Serializer, ImageEncoder 등)
- 콜백 기반 API 추가 금지 — 모든 비동기는 suspend 또는 Flow
