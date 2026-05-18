# 공개 API

> 작성 규칙
> - 모든 public 함수의 시그니처를 토큰 단위로 명시
> - suspend / Flow 여부 명시
> - 관련 F-XXX, M-XXX 참조

---

## API 목록

| ID | 이름 | 종류 | 관련 F-ID |
|----|------|------|-----------|
| A-001 | AiAgentClient.builder | Builder 정적 팩토리 + Builder 클래스 | F-000 |
| A-002 | AiAgentClient.ask | suspend fun | F-001, F-002 |
| A-003 | AiAgentClient.askStream | Flow fun | F-003 |
| A-004 | AiAgentClient.createSession | fun | F-004 |
| A-005 | AiAgentClient.useProvider | fun | F-005 |
| A-006 | Session.send | suspend fun | F-004 |
| A-007 | Session.history | fun | F-004 |
| A-008 | Session.clear | fun | F-004 |
| A-009 | AiAgentClient.close | fun | F-008 |
| A-010 | Session.save | suspend fun | F-007 |
| A-011 | AiAgentClient.loadSession | suspend fun | F-007 |
| A-012 | AiAgentClient.deleteSession | suspend fun | F-007 |
| A-013 | Builder.registerTool | Builder fun (v0.2) | F-009 |
| A-014 | AiAgentClient.askWithTools | suspend fun (v0.2) | F-009 |
| A-015 | AiAgentClient.executeToolLoop | suspend fun (v0.2) | F-010 |
| A-016 | Session.sendWithTools | suspend fun (v0.2) | F-009 |
| A-017 | Session.executeToolLoop | suspend fun (v0.2) | F-010 |

---

## A-001. AiAgentClient.builder

### 시그니처
```kotlin
class AiAgentClient {
    public companion object {
        public fun builder(context: Context): Builder
    }
}

public class Builder internal constructor(context: Context) {
    public fun apiKey(key: String): Builder
    public fun provider(provider: ProviderId): Builder
    public fun model(modelId: String): Builder       // 미설정 시 기본값: "claude-opus-4-7" (S-001)
    public fun timeout(duration: Duration): Builder  // 미설정 시 기본값: 30.seconds (S-002)
    public fun build(): AiAgentClient
}
```

### 호출 형태 (S-Q-001 라운드 5 결정)
- Builder 인스턴스는 **`AiAgentClient.builder(context)` 정적 팩토리(companion object 함수)**로 획득한다.
- `Builder` 클래스 자체는 별도 패키지(`com.androidailab.aisdk.client.Builder`)에 위치하며 생성자가 `internal`이라 호출자가 `Builder(context)`로 직접 생성할 수 없다.
- 즉, 호출자가 사용 가능한 진입점은 `AiAgentClient.builder(context)` 한 가지로 통일한다 (`AiAgentClient.Builder(context)` 직접 호출 형태는 v0.1 API에 존재하지 않음).
- 사유: 정적 팩토리 패턴이 더 Kotlin idiomatic하며, 향후 Builder 구현체 교체 시 호출자에 영향을 주지 않는다.

### 기본값 정책
| 메서드 | 미설정 시 기본값 | 근거 |
|--------|------------------|------|
| `apiKey(...)` | (없음) | 미설정 시 build에서 E-001로 거부 |
| `provider(...)` | `ProviderId.CLAUDE` | v0.1 단일 Provider (M-010, R-009) |
| `model(...)` | `"claude-opus-4-7"` | provider-spec.md P-CLAUDE의 첫 번째 모델 (S-001 라운드 5 결정) |
| `timeout(...)` | `30.seconds` | F-001 NFR p95 5초 + 안전 마진 (S-002 라운드 5 결정) |

### 동작 (관련 F-000)
- 정상 흐름: F-000 정상 흐름 1~3
- 예외: E-001, E-002, E-003

### 사용 예
```kotlin
val client = AiAgentClient.builder(context)
    .apiKey(BuildConfig.AI_API_KEY)
    .provider(ProviderId.CLAUDE)
    .model("claude-opus-4-7")
    .timeout(30.seconds)
    .build()

// model/timeout/provider 미설정 시 기본값 사용 가능
val clientMinimal = AiAgentClient.builder(context)
    .apiKey(BuildConfig.AI_API_KEY)
    .build()  // provider=CLAUDE, model="claude-opus-4-7", timeout=30.seconds
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
- 실패: `Result.failure(AiException)` (M-005, ERR-001~ERR-007)

### 동작 (관련 F-001, F-002)
- 정상 흐름: F-001/F-002 정상 흐름
- 예외 흐름: E-101~E-110, E-201~E-207
- close 시맨틱: A-009 표 참조 (진행 중이던 호출은 CancellationException, close 후 새 호출은 Result.failure(Configuration("client closed")))

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
- 예외: E-301, E-302, E-303, E-101~E-110
- close 시맨틱: A-009 표 참조 (진행 중 Flow는 CancellationException으로 종료, close 후 새 collect는 즉시 `AiStreamEvent.Error(AiException.Configuration("client closed"))` emit 후 종료)

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
- 새 세션은 SDK가 자동 생성한 sessionId(UUID)를 가진다. 영속화 시 이 sessionId가 키로 사용됨.

### sessionId 부여 정책 (R-017/R-024 라운드 3)
- v0.1은 SDK 자동 UUID 부여만 지원. 호출자가 sessionId를 명시 지정하는 시그니처(`createSession(systemPrompt, sessionId)` 등)는 **v0.1 Out of Scope**, v0.2 검토 사항 (overview.md 참조).
- 같은 호출자 도메인 식별자로 Session을 재사용하려면 `loadSession(savedId)`을 사용하면 된다. 새 Session에 임의 sessionId를 주입하는 인터페이스는 v0.1에 없다.

### 동작 (관련 F-004)
- F-004 1단계의 세션 생성
- close 시맨틱: client가 close된 후 호출 시 `AiException.Configuration("client closed")` throw (동기 함수)

---

## A-005. AiAgentClient.useProvider

### 시그니처
```kotlin
fun useProvider(provider: ProviderId)
```

### 동작 (관련 F-005)
- 활성 Provider 변경. AtomicReference set 수행. 진행 중 요청 영향 없음, 다음 요청부터 적용.
- 동일 client에서 생성된 모든 Session도 다음 send부터 새 Provider 사용 (Session은 Provider에 묶이지 않음, R-014 참조)
- close 시맨틱: A-009 표 참조 (close 후 호출 시 AiException.Configuration("client closed") throw — 동기 함수)
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
- 동일 Session에 대해 동시 호출 시 Mutex로 직렬 처리 (E-403)
- close 시맨틱: A-009 표 참조 (케이스 A → CancellationException, 케이스 B → Result.failure(Configuration("client closed")), E-402 매핑)
- 예외: E-401, E-402, E-403, E-101~E-110, E-201~E-207

---

## A-007. Session.history

### 시그니처
```kotlin
fun history(): List<Message>
```

### 반환
- 호출 시점의 history immutable snapshot (M-008)
- 반환된 List는 호출 후 send가 발생해도 변경되지 않음 (R-011: snapshot 보장)
- `Role.SYSTEM` 메시지는 포함되지 않음 (systemPrompt는 별도 보관 — R-008)
- close 시맨틱: A-009 표 참조 (close 후 호출 시 AiException.Configuration("client closed") throw — 동기 함수)

---

## A-008. Session.clear

### 시그니처
```kotlin
fun clear()
```

### 동작
- 세션 history를 초기화. systemPrompt는 유지.
- thread-safe
- close 시맨틱: A-009 표 참조 (close 후 호출 시 AiException.Configuration("client closed") throw — 동기 함수)

---

## A-009. AiAgentClient.close

### 시그니처
```kotlin
fun close()
```

### 동작 (관련 F-008)
- 진행 중인 모든 요청을 취소 (OkHttp dispatcher cancelAll)
- 내부 CoroutineScope cancel
- DataStore 핸들 해제(있다면)

### close 시맨틱 (R-020 라운드 3, F-008 "close ↔ 진행 중/이후 호출 시맨틱" 참조)

| 케이스 | 호출자에게 보이는 결과 |
|--------|--------------------|
| A. close 시점에 이미 진행 중이던 ask/askStream/send/save/loadSession/deleteSession | 진행 중 코루틴이 `CancellationException` throw (Result.failure로 감싸지 않음, 표준 코루틴 취소 시맨틱) |
| B. close 완료 후 새로 호출된 ask/send/save/loadSession/deleteSession (suspend) | 즉시 `Result.failure(AiException.Configuration("client closed"))` 반환 |
| B. close 완료 후 새로 호출된 askStream (Flow) | 첫 collect 시 `AiStreamEvent.Error(AiException.Configuration("client closed"))` emit 후 Flow 종료 |
| B. close 완료 후 호출된 createSession/useProvider/Session.history/Session.clear (동기) | `AiException.Configuration("client closed")` throw |

### 정책
- idempotent: 여러 번 호출 가능
- thread-safe
- Hilt 주입된 ApplicationComponent scope client는 일반적으로 close 불필요

### 사용 예
```kotlin
override fun onTerminate() {
    super.onTerminate()
    client.close()
}
```

---

## A-010. Session.save

### 시그니처
```kotlin
suspend fun save(): Result<String>
```

### 동작 (관련 F-007)
- Session의 systemPrompt + history를 직렬화하여 DataStore에 저장
- `Dispatchers.IO`에서 수행 (NFR)
- 반환된 `String`은 **Session 생성 시 자동 부여된 UUID**(M-007.sessionId). save() 인자로 다른 식별자를 전달하는 인터페이스는 v0.1에서 제공하지 않음 (R-017/R-024 라운드 3 — 호출자 명시 부여는 v0.2 검토)
- 동일 sessionId로 여러 번 호출 시 덮어쓰기 (last-write-wins, F-007 다중 인스턴스 정책 참조)
- close 시맨틱: A-009 표 참조 (케이스 A는 CancellationException, 케이스 B는 Result.failure(Configuration("client closed")))

### 반환
- 성공: `Result.success(sessionId: String)`
- 실패: `Result.failure(AiException)` — E-701, E-704, E-705, E-706

### 사용 예
```kotlin
viewModelScope.launch {
    val saveResult = session.save()
    saveResult.onSuccess { id ->
        // 호출자가 sessionId를 보관 (예: SharedPreferences)
        savedStateHandle["lastSessionId"] = id
    }
}
```

---

## A-011. AiAgentClient.loadSession

### 시그니처
```kotlin
suspend fun loadSession(sessionId: String): Result<Session>
```

### 동작 (관련 F-007)
- DataStore에서 sessionId로 조회 → `SessionEntity`(M-011) 역직렬화 → 새 `Session` 인스턴스 반환
- `Dispatchers.IO`에서 수행
- 복원된 Session은 호출 시점의 client 활성 Provider를 사용 (Session은 Provider에 묶이지 않음)
- schemaVersion 정책 (R-018 라운드 3): 역직렬화된 `SessionEntity.schemaVersion`이 1이 아니면 즉시 `Result.failure(AiException.IOError("session schema unsupported: v={loaded}"))` 반환 (E-703). 자동 마이그레이션은 v0.2부터
- 다중 인스턴스 정책 (R-019 라운드 3): 같은 sessionId로 loadSession을 두 번 호출하면 두 개의 독립 Session 인스턴스가 생성된다. 두 인스턴스의 save는 last-write-wins. F-007 "다중 인스턴스 정책" 참조
- close 시맨틱: A-009 표 참조

### 반환
- 성공: `Result.success(Session)`
- 실패: `Result.failure(AiException)` — E-702, E-703, E-705, E-706

### 사용 예
```kotlin
val sessionId = savedStateHandle.get<String>("lastSessionId") ?: return
val result = client.loadSession(sessionId)
result.onSuccess { session ->
    // 복원된 세션 사용
    session.send(AiRequest("계속 이야기해 줘"))
}.onFailure { error ->
    when (error) {
        is AiException.InvalidInput -> startNewSession()
        is AiException.IOError -> reportToDeveloper(error.message)
        else -> showError(error.message)
    }
}
```

---

## A-012. AiAgentClient.deleteSession

### 시그니처
```kotlin
suspend fun deleteSession(sessionId: String): Result<Unit>
```

### 동작 (관련 F-007)
- DataStore에서 해당 sessionId 키 제거
- 존재하지 않는 sessionId 삭제 시도 시 성공으로 처리 (idempotent)
- close 시맨틱: A-009 표 참조 (케이스 A → CancellationException, 케이스 B → Result.failure(Configuration("client closed")))
- 예외: E-705, E-706

---

---

## A-013. Builder.registerTool [v0.2 신규]

### 시그니처
```kotlin
public class Builder {
    // 기존 메서드 ...
    public fun registerTool(
        definition: ToolDefinition,
        executor: ToolExecutor,
    ): Builder
}

typealias ToolExecutor = suspend (ToolCall) -> ToolResult
```

### 동작 (관련 F-009)
- ToolDefinition(M-012)을 Builder의 내부 등록 테이블에 추가
- `build()` 호출 시 다음을 검증:
  - 이름 규칙 (R-025) → 실패 시 E-902
  - 동일 이름 중복 → E-901
  - 스키마 미지원 키워드 (R-029) → E-903
  - 스키마 깊이 5 초과 (R-026) → E-904
  - 등록 개수 32 초과 (R-027) → E-905
- 검증 통과 시 client 내부에 immutable Map<String, Pair<ToolDefinition, ToolExecutor>>로 보관
- 런타임 추가/제거 메서드는 v0.2에 없음 (R-030)

### 사용 예
```kotlin
val client = AiAgentClient.builder(context)
    .apiKey(BuildConfig.AI_API_KEY)
    .registerTool(
        definition = ToolDefinition(
            name = "get_weather",
            description = "Get current weather for a location",
            inputSchema = ToolSchema.objectSchema(
                properties = mapOf(
                    "location" to ToolSchema.string(description = "city name"),
                ),
                required = listOf("location"),
            ),
        ),
        executor = { call ->
            val location = call.inputJson.jsonObject["location"]?.jsonPrimitive?.content
                ?: return@registerTool ToolResult.error(call.id, "location missing")
            val weather = fetchWeather(location)
            ToolResult.success(call.id, weather)
        },
    )
    .build()
```

---

## A-014. AiAgentClient.askWithTools [v0.2 신규]

### 시그니처
```kotlin
suspend fun askWithTools(request: AiRequest): Result<AiResponse>
```

### 파라미터
| 이름 | 타입 | 필수 | 설명 |
|------|------|------|------|
| request | AiRequest (M-001) | Y | 질의 요청. 이미지/maxTokens 등 기존 필드와 호환 |

### 반환
- 성공: `Result.success(AiResponse)` (M-002) — 최종 ASSISTANT 응답
- 실패: `Result.failure(AiException)` — E-907~E-910 + 기존 E-101~E-110

### 동작 (관련 F-009)
- 등록된 tool이 0개면 즉시 `Result.failure(AiException.Configuration("no tools registered"))` (E-910과 다름, 별도 검증)
- 정상 흐름: F-009 1~7단계
- close 시맨틱: A-009 표 참조

### 사용 예
```kotlin
val result = client.askWithTools(AiRequest("What is the weather in Seoul?"))
result.onSuccess { response ->
    // tool 결과를 반영한 최종 텍스트
    println(response.text)
}.onFailure { error ->
    when (error) {
        is AiException.Configuration -> reportToDeveloper(error.message)
        is AiException.InvalidInput -> showToolExecutionError(error.message)
        is AiException.ServerError -> reportAndRetry(error.code)
        else -> showError(error)
    }
}
```

---

## A-015. AiAgentClient.executeToolLoop [v0.2 신규]

### 시그니처
```kotlin
suspend fun executeToolLoop(request: AiRequest): Result<AiResponse>
```

### 동작 (관련 F-010)
- F-010 정상 흐름 1~4단계 수행
- 루프 최대 8회 (R-028), 초과 시 E-906
- 병렬 Executor 실행 (동일 턴 내 여러 tool_use 블록)
- close 시맨틱: A-009 표 참조

### 반환
- 성공: `Result.success(AiResponse)` — 루프 종료 시 최종 ASSISTANT 응답
- 실패: `Result.failure(AiException)` — E-906~E-910 + 기존 E-101~E-110

### 사용 예
```kotlin
val result = client.executeToolLoop(AiRequest(
    "Find a restaurant in Seoul and book a table for 2 at 7pm.",
))
// 모델이 search_restaurant → get_availability → book_table 3개 tool을 순차 호출하는 시나리오에서
// SDK가 루프를 자동 처리한 뒤 최종 ASSISTANT 응답을 반환
```

---

## A-016. Session.sendWithTools [v0.2 신규]

### 시그니처
```kotlin
suspend fun sendWithTools(request: AiRequest): Result<AiResponse>
```

### 동작 (관련 F-009)
- F-009의 단발 흐름을 세션 history와 결합
- Session.send와 같은 Mutex로 직렬화 (R-031, E-403 시맨틱 재사용)
- tool_use/tool_result 블록은 history()에 노출되지 않음 (USER/ASSISTANT 텍스트만)
- 최종 ASSISTANT 응답만 history에 추가
- close 시맨틱: A-009 표 참조

### 반환
- 성공: `Result.success(AiResponse)`
- 실패: `Result.failure(AiException)` — F-009와 동일

---

## A-017. Session.executeToolLoop [v0.2 신규]

### 시그니처
```kotlin
suspend fun executeToolLoop(request: AiRequest): Result<AiResponse>
```

### 동작 (관련 F-010)
- F-010 멀티턴 루프를 세션 history와 결합
- Session.send와 같은 Mutex로 직렬화 (R-031)
- 루프 도중 `session.save()` 동시 호출 시 루프 완료까지 대기
- 영속화 시 tool 메시지는 직렬화 대상 아님 (M-011 schemaVersion = 1 유지)
- close 시맨틱: A-009 표 참조

### 반환
- 성공: `Result.success(AiResponse)` — 루프 종료 시 최종 ASSISTANT 응답
- 실패: `Result.failure(AiException)` — F-010과 동일

---

## API 안정성 정책

- v0.x: API 변경 가능 (semver 0.x.y)
- v1.0 이후: public API는 호환성 유지, 변경 시 deprecation 1버전 이상

## API 노출 원칙

- public: 호출자가 직접 사용 (위 표 12개)
- internal: SDK 내부 협업용, 외부 노출 금지 (HttpClient, Serializer, ImageEncoder, SessionStore 등)
- 콜백 기반 API 추가 금지 — 모든 비동기는 suspend 또는 Flow
