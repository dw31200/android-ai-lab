# Provider 사양

## 목적
여러 LLM 백엔드(Claude, OpenAI, Gemini 등)를 동일한 인터페이스로 호출하기 위한 추상화. 호출자 코드는 Provider에 비종속.

---

## Provider 인터페이스 (P-001)

```kotlin
interface Provider {
    val id: ProviderId
    val capabilities: Capabilities

    suspend fun complete(request: AiRequest, config: ProviderConfig): AiResponse

    fun stream(request: AiRequest, config: ProviderConfig): Flow<AiStreamEvent>
}

data class Capabilities(
    val supportsImage: Boolean,
    val supportsVideo: Boolean,
    val supportsStream: Boolean,
    val supportsSession: Boolean,
    val maxImageSizeBytes: Long,
    val maxImagesPerRequest: Int,
    val supportedImageMimeTypes: Set<String>,
    // v0.2 신규
    val supportsTools: Boolean = false,
    val maxToolsPerRequest: Int = 0,
)

data class ProviderConfig(
    val apiKey: String,
    val modelId: String,
    val timeout: Duration,
)
```

호출자는 Provider 인터페이스를 직접 사용하지 않는다. `AiAgentClient`가 내부적으로 등록된 Provider를 호출한다.

### Capabilities는 Provider 인터페이스 측의 단일 source of truth (R-010)
- `Capabilities`는 Provider 구현체에만 존재
- `ProviderId` enum은 식별자(`displayName`)만 보유, capability 관련 boolean을 갖지 않음 (M-010)
- SDK가 `client.useProvider(id)` 후 capability를 체크해야 할 때는 ProviderRegistry에서 해당 Provider 인스턴스의 `capabilities`를 조회

---

## ProviderRegistry (P-002)

```kotlin
internal class ProviderRegistry(
    providers: Set<Provider>,
) {
    fun get(id: ProviderId): Provider
    fun list(): List<Provider>
    fun capabilities(id: ProviderId): Capabilities = get(id).capabilities
}
```

Hilt를 통해 `Set<@JvmSuppressWildcards Provider>`로 주입.

---

## 지원 Provider 목록

| P-ID | ProviderId enum | 이름 | 상태 | supportsImage | supportsVideo | supportsStream |
|------|-----------------|------|------|---------------|---------------|----------------|
| P-CLAUDE | `ProviderId.CLAUDE` | Anthropic Claude | v0.1 우선 구현 | O | X | O |
| P-OPENAI | (v0.2 추가) | OpenAI GPT | v0.2 (인터페이스만 v0.1, enum은 미포함) | O | X | O |
| P-GEMINI | (v0.3 추가) | Google Gemini | v0.3 | O | O | O |

> v0.1 시점에 `ProviderId` enum에는 `CLAUDE`만 존재 (R-009). OPENAI/GEMINI는 구현 시 enum에 추가된다. 사양상 P-OPENAI/P-GEMINI 식별자(P-XXX)는 미리 부여되지만 enum 값은 구현 라운드에서 추가.

### P-CLAUDE 상세

- **API**: Anthropic Messages API (POST /v1/messages)
- **모델**: claude-opus-4-7, claude-sonnet-4-6, claude-haiku-4-5-20251001
- **이미지**: image/jpeg, image/png, image/webp, image/gif (base64)
- **단일 이미지 한계**: 5MB
- **요청당 이미지**: 최대 20개 (Anthropic 한계는 20, SDK는 10으로 제한 — 보수적)
- **스트리밍**: SSE
- **Capabilities 값**:
  ```kotlin
  Capabilities(
      supportsImage = true,
      supportsVideo = false,
      supportsStream = true,
      supportsSession = true,
      maxImageSizeBytes = 5 * 1024 * 1024,
      maxImagesPerRequest = 10,
      supportedImageMimeTypes = setOf("image/jpeg", "image/png", "image/webp", "image/gif"),
      supportsTools = true,         // v0.2 신규
      maxToolsPerRequest = 32,      // v0.2 신규 (Anthropic 한계는 64지만 SDK는 R-027로 32 제한)
  )
  ```
- **에러 매핑**:
  - 401 → AiException.Authentication
  - 429 → AiException.RateLimit (retry-after 헤더 파싱)
  - 5xx → AiException.ServerError
  - timeout → AiException.Network
  - context_length_exceeded → AiException.InvalidInput("context too large") (E-401)

#### P-CLAUDE tool_use/tool_result 변환 규칙 (v0.2 라운드 7 신규)

**요청 변환 (SDK → Anthropic)**

등록된 ToolDefinition을 Anthropic Messages API의 `tools` 배열로 직렬화:
```json
{
  "model": "claude-opus-4-7",
  "max_tokens": 1024,
  "tools": [
    {
      "name": "get_weather",
      "description": "Get current weather for a location",
      "input_schema": {
        "type": "object",
        "properties": {
          "location": { "type": "string", "description": "city name" }
        },
        "required": ["location"]
      }
    }
  ],
  "messages": [...]
}
```

- `ToolDefinition.name` → `tools[].name`
- `ToolDefinition.description` → `tools[].description`
- `ToolDefinition.inputSchema` (ToolSchema M-015) → `tools[].input_schema` (JSON Schema 부분 집합)
  - `ToolSchema.ObjectType` → `{"type": "object", "properties": {...}, "required": [...]}`
  - `ToolSchema.StringType` → `{"type": "string", "description": "...", "enum": [...]}`
  - `ToolSchema.ArrayType` → `{"type": "array", "items": {...}}`
  - 기타 scalar 타입은 `{"type": "integer/number/boolean", "description": "..."}`로 직렬화

`tool_choice`는 v0.2 본 라운드에서 `auto`(기본)만 사용. SDK는 `tool_choice`를 명시적으로 송신하지 않음 (Anthropic 기본값 = `auto`).

**응답 변환 (Anthropic → SDK)**

Anthropic 응답이 `stop_reason: "tool_use"`이고 `content` 배열에 `tool_use` 블록이 포함되어 있을 때:
```json
{
  "stop_reason": "tool_use",
  "content": [
    { "type": "text", "text": "I'll check the weather." },
    {
      "type": "tool_use",
      "id": "toolu_01ABC...",
      "name": "get_weather",
      "input": { "location": "Seoul" }
    }
  ]
}
```

- 각 `tool_use` 블록 → `ToolCall(id, name, inputJson)` (M-013)
- `tool_use.name`이 등록된 ToolDefinition에 없으면 E-907
- `tool_use.input`이 등록된 inputSchema의 required/type을 만족하지 않으면 E-908
- 동일 응답 내 여러 `tool_use` 블록이 있을 수 있음 → F-010 병렬 Executor 실행

**다음 턴 요청 변환 (ToolResult → Anthropic)**

호출자 Executor가 반환한 `ToolResult`(M-014)를 다음 턴의 user 메시지로 직렬화:
```json
{
  "role": "user",
  "content": [
    {
      "type": "tool_result",
      "tool_use_id": "toolu_01ABC...",
      "content": "Sunny, 22°C",
      "is_error": false
    }
  ]
}
```

- `ToolResult.toolUseId` → `tool_result.tool_use_id`
- `ToolResult.content` → `tool_result.content`
- `ToolResult.isError` → `tool_result.is_error` (false면 생략 가능)

**루프 종결 조건**

- `stop_reason == "tool_use"`: F-010 루프 계속, 다음 턴 요청 송신
- `stop_reason == "end_turn"` / `"stop_sequence"` / `"max_tokens"`: 루프 종료, 최종 `AiResponse` 반환
- 루프 횟수가 R-028(=8)에 도달하면 E-906으로 종료

**`Capabilities.maxToolsPerRequest` 검증**

Builder.build() 시점에 등록된 tool 개수가 P-CLAUDE의 `maxToolsPerRequest`(=32)를 초과하면 E-905로 거부.

### P-OPENAI 상세 (인터페이스만, v0.2 구현)

- **API**: Chat Completions API
- **이미지**: image_url (data: scheme 또는 https URL)
- **스트리밍**: SSE
- 실제 구현은 v0.2

### P-GEMINI 상세 (계획, v0.3)

- **API**: Generative Language API
- **이미지/영상**: 모두 지원
- v0.3 구현

---

## 신규 Provider 추가 절차 (R-015 보강)

다음 단계를 모두 따라야 한다.

1. **P-XXX ID 부여**
   - 본 문서의 "지원 Provider 목록" 표에 새 행 추가
   - P-XXX ID는 Provider 이름 약어 사용 (예: P-CLAUDE, P-MISTRAL). 숫자 충돌 방지 위해 식별자 형태로 부여
   - P-XXX는 사양 문서에서 Provider를 참조할 때 사용하는 ID이며, 코드의 `ProviderId` enum 값과 1:1 대응

2. **클래스 추가**
   - `provider/{providerName}/{ProviderName}Provider.kt` 파일 생성
   - `Provider` 인터페이스 구현
   - `capabilities` 프로퍼티는 Provider 구현 측에서 생성 (M-010 enum에 추가하지 않음, R-010)

3. **매퍼 추가**
   - `provider/{providerName}/Mapper.kt`
   - SDK 모델 ↔ Provider 모델 변환 함수
   - 함수명: `AiRequest.toProviderRequest()`, `ProviderResponse.toAiResponse()`

4. **에러 매핑**
   - `provider/{providerName}/ErrorMapper.kt`
   - HTTP status code, response body → AiException 변환
   - 컨텍스트 초과 등 Provider 고유 에러도 매핑

5. **Hilt 등록**
   - `di/AiSdkModule.kt`에 `@Provides @IntoSet` 추가
   - `Set<Provider>`에 자동 합류

6. **ProviderId enum 확장**
   - `ProviderId`에 새 값 추가 (`displayName`만, capability 필드 없음)

7. **단위 테스트**
   - 정상 응답 변환
   - 모든 에러 코드 변환
   - 멀티모달 입력 직렬화 (지원하는 경우)
   - 스트리밍 chunk 파싱 (지원하는 경우)

8. **사양 갱신**
   - 본 문서의 "지원 Provider 목록" 표 갱신 (P-XXX, ProviderId enum, Capabilities 값 명시)
   - 해당 Provider의 상세 섹션 추가
   - data-model.md M-010 ProviderId enum 정의 갱신

9. **api.md, data-model.md 영향 분석**
   - 새 Provider만의 특화 기능이 있다면 API 추가 검토 (사양에 없는 기능 추가는 spec-architect 협의 필수)

---

## Provider 선택 정책

### 초기화 시
호출자가 Builder에서 명시적으로 선택. 미선택 시 기본값은 `ProviderId.CLAUDE`.

### 런타임 교체
`client.useProvider(id)` 호출. AtomicReference set 기반 (F-005 동시성 모델 참조). 진행 중 요청은 영향 없음, 다음 요청부터 적용.

### 자동 폴백 (v0.2 검토 사항)
v0.1에서는 자동 폴백 없음. 호출자가 catch 후 `useProvider()` 명시 호출.

---

## 보안 고려

- API 키는 Provider별로 분리하여 보관 (각 ProviderConfig.apiKey)
- API 키는 SDK 메모리에서만 유지, 디스크 영속화 금지 (D-003)
- Provider 호출 시 키는 HTTPS 헤더로만 전송, 로깅 금지
- 디버그 빌드에서도 API 키는 마스킹 (`sk-***...***xxxx`)
- 영속화된 SessionEntity(M-011)에는 API 키가 포함되지 않음

### ImageInput.Url SSRF 방어 정책 (R-023 라운드 3)

- v0.1 SDK는 `ImageInput.Url`의 URL이 외부망/내부망/loopback/메타데이터 엔드포인트(예: 169.254.169.254) 중 어디를 가리키는지 **판별하지 않는다**.
- SDK가 수행하는 검증은 다음에 한정된다:
  - URL 형식 검증 (`https://` 스킴, 길이 한계 등)
  - HTTP fetch 실패 시 E-206으로 변환 (4xx/5xx/timeout/DNS/redirect 한계)
- 다음은 모두 **호출자 책임**이다:
  - URL이 외부 도메인인지 확인 (도메인 allowlist 등)
  - 내부망/loopback/메타데이터 IP 차단
  - 영속화 후 복원된 URL을 send에 사용해도 안전한지 재검증
- 영속화된 `ImageInputEntity.Url`은 어떤 변환도 거치지 않은 원본 URL이며, 복원 시점에 호출자가 다시 검증해야 한다 (M-011 "ImageInput.Url 영속화 보안 정책" 참조).
- v0.2에서 SDK 차원의 URL allowlist 옵션 (예: `AiAgentClient.builder(context).urlAllowlist(...)`) 검토 예정.

---

## Provider 추상화의 한계 (의도적)

다음은 본 추상화에서 의도적으로 제외:

| 항목 | 이유 |
|------|------|
| Tool use(함수 호출) | **v0.2 라운드 7에서 추가** (F-009/F-010). P-CLAUDE의 tool_use/tool_result 패턴 우선 구현. OpenAI function calling은 P-OPENAI 구현 시 별도 라운드 |
| 임베딩 생성 | LLM 호출과 다른 도메인 — 본 SDK 범위 외 |
| 이미지 생성 | 별도 SDK 제안 |
| Provider별 고유 파라미터 (예: top_p, top_k) | 추상화하지 않음. 필요 시 ProviderConfig.extra: Map\<String, Any\>로 v0.2 검토 |
| 자동 재시도 | D-005에 따라 v0.1 미포함. v0.2에 RetryPolicy 주입 인터페이스 추가 예정 |
