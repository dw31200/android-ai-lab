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
)

data class ProviderConfig(
    val apiKey: String,
    val modelId: String,
    val timeout: Duration,
)
```

호출자는 Provider 인터페이스를 직접 사용하지 않는다. `AiAgentClient`가 내부적으로 등록된 Provider를 호출한다.

---

## ProviderRegistry (P-002)

```kotlin
internal class ProviderRegistry(
    providers: Set<Provider>,
) {
    fun get(id: ProviderId): Provider
    fun list(): List<Provider>
}
```

Hilt를 통해 `Set<@JvmSuppressWildcards Provider>`로 주입.

---

## 지원 Provider 목록

| ID | 이름 | 상태 | supportsImage | supportsVideo | supportsStream |
|----|------|------|---------------|---------------|----------------|
| P-CLAUDE | Anthropic Claude | v0.1 우선 구현 | O | X | O |
| P-OPENAI | OpenAI GPT | v0.2 (인터페이스만 v0.1) | O | X | O |
| P-GEMINI | Google Gemini | v0.3 | O | O | O |

### P-CLAUDE 상세

- **API**: Anthropic Messages API (POST /v1/messages)
- **모델**: claude-opus-4-7, claude-sonnet-4-6, claude-haiku-4-5-20251001
- **이미지**: image/jpeg, image/png, image/webp, image/gif (base64)
- **단일 이미지 한계**: 5MB
- **요청당 이미지**: 최대 20개 (Anthropic 한계는 20, SDK는 10으로 제한 — 보수적)
- **스트리밍**: SSE
- **에러 매핑**:
  - 401 → AiException.Authentication
  - 429 → AiException.RateLimit (retry-after 헤더 파싱)
  - 5xx → AiException.ServerError
  - timeout → AiException.Network

### P-OPENAI 상세 (인터페이스만)

- **API**: Chat Completions API
- **이미지**: image_url (data: scheme 또는 https URL)
- **스트리밍**: SSE
- 실제 구현은 v0.2

### P-GEMINI 상세 (계획)

- **API**: Generative Language API
- **이미지/영상**: 모두 지원
- v0.3 구현

---

## 신규 Provider 추가 절차

다음 단계를 모두 따라야 한다.

1. **클래스 추가**
   - `provider/{providerName}/{ProviderName}Provider.kt` 파일 생성
   - `Provider` 인터페이스 구현

2. **매퍼 추가**
   - `provider/{providerName}/Mapper.kt`
   - SDK 모델 ↔ Provider 모델 변환 함수
   - 함수명: `AiRequest.toProviderRequest()`, `ProviderResponse.toAiResponse()`

3. **에러 매핑**
   - `provider/{providerName}/ErrorMapper.kt`
   - HTTP status code, response body → AiException 변환

4. **Hilt 등록**
   - `di/AiSdkModule.kt`에 `@Provides @IntoSet` 추가
   - `Set<Provider>`에 자동 합류

5. **ProviderId enum 확장**
   - `ProviderId`에 새 값 추가 + capabilities 명시

6. **단위 테스트**
   - 정상 응답 변환
   - 모든 에러 코드 변환
   - 멀티모달 입력 직렬화 (지원하는 경우)
   - 스트리밍 chunk 파싱 (지원하는 경우)

7. **사양 갱신**
   - 본 문서의 "지원 Provider 목록" 표 갱신
   - 해당 Provider의 상세 섹션 추가

8. **api.md, data-model.md 영향 분석**
   - 새 Provider만의 특화 기능이 있다면 API 추가 검토 (사양에 없는 기능 추가는 spec-architect 협의 필수)

---

## Provider 선택 정책

### 초기화 시
호출자가 Builder에서 명시적으로 선택. 미선택 시 기본값은 `ProviderId.CLAUDE`.

### 런타임 교체
`client.useProvider(id)` 호출. 진행 중 요청은 영향 없음, 다음 요청부터 적용.

### 자동 폴백 (v0.2 검토 사항)
v0.1에서는 자동 폴백 없음. 호출자가 catch 후 `useProvider()` 명시 호출.

---

## 보안 고려

- API 키는 Provider별로 분리하여 보관 (각 ProviderConfig.apiKey)
- Provider 호출 시 키는 HTTPS 헤더로만 전송, 로깅 금지
- 디버그 빌드에서도 API 키는 마스킹 (`sk-***...***xxxx`)

---

## Provider 추상화의 한계 (의도적)

다음은 본 추상화에서 의도적으로 제외:

| 항목 | 이유 |
|------|------|
| Tool use(함수 호출) | Provider별 사양 차이 매우 큼 — v0.2 별도 추상화 |
| 임베딩 생성 | LLM 호출과 다른 도메인 — 본 SDK 범위 외 |
| 이미지 생성 | 별도 SDK 제안 |
| Provider별 고유 파라미터 (예: top_p, top_k) | 추상화하지 않음. 필요 시 ProviderConfig.extra: Map\<String, Any\>로 v0.2 검토 |
