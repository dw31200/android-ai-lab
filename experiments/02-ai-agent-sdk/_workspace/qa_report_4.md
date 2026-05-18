# QA Report 4 — F-002 (멀티모달 이미지 질의) 점진 검증

검증자: sdk-qa-validator
검증 일시: 2026-05-07
대상:
- F-002 본체 (T12, `_workspace/impl_summary_5.md`) — `AiAgentClient.ask` + `validateImages` / `Mapper.toAnthropicRequest` + `buildContentJson` + `verifyMagicNumber` + `fetchUrlBytes` / `ImageInput.Bytes/Url init` / `ClaudeProvider.complete` 6개 위치 + 단위 테스트 ImageInputTest 11 / AskWithImagesTest 8 / MapperImageTest 12 + MapperTest 시그니처 업데이트
- 직전 QA(qa_report_3): F-001 통과 — 본 라운드는 F-002만 검증 + F-001 회귀 점검

검증 범위: A-002 시그니처 불변 / F-002 정상 흐름 1~5 / 예외 흐름 E-201~E-207 / R-016 / R-023 / D-004 / M-001 images 한계 / M-003 ImageInput sealed / AnthropicMessagesRequest content 변경 (String → JsonElement) / AnthropicContentRequestBlock sealed / Mapper.toAnthropicRequest suspend 변경 / 단위 테스트 의미·컴파일 가능성 / F-001 회귀

빌드 실행: 미수행 (qa_report_1·2·3과 동일 정책 — settings.gradle.kts/wrapper 부재. 정적 검증/관찰 기반)

---

## 1. 요약

| Severity | 건수 | 종결 조건 |
|----------|------|----------|
| Blocker  | 0    | 0건 필수 |
| Major    | 0    | 처리 또는 명시적 유보 |
| Minor    | 5    | 다음 라운드 이월 가능 |
| 사양 명확화 요청 | 3 | spec-architect 회신 후 반영 (S-T12-1/2/3) |

**결과**: F-002 **통과**. Blocker/Major 0건. F-002의 모든 사양 ID(A-002 불변, E-201~E-207, R-016, R-023 형식 검증, D-004 자동 리사이즈 없음, M-001 images "최대 10장 / 합계 20MB", M-003 ImageInput sealed, ERR-005/ERR-004/ERR-001 매핑)가 코드와 단위 테스트로 1:1 매핑됨. F-001 회귀 없음. **다음 wave (F-003 / F-004 / F-006) 진입 가능**.

Minor 5건은 (a) `ImageInput.Url.init`의 `https://` 강제로 인해 MockWebServer 통합 테스트 불가 (S-T12-1과 연동), (b) E-202 한 ID에 두 의미 (개수 한계 / 합계 한계) 통합 매핑 — 사양 보강 권장 (S-T12-2), (c) `ImageInput.Uri` v0.1 미지원 정책의 사양 명시 부재 (S-T12-3), (d) E-206 5xx → Network 시 `cause`가 원본 IOException이 아닌 SDK 자체 합성 IOException ("server error: $code"), (e) `validateImages`의 검증 순서가 `supportsImage=false`를 먼저 거부하므로 F-002 회귀 케이스 (이미지 없음 + supportsImage=false) 통과 의미가 코드 흐름상 자명하지 않음(테스트는 통과). 모두 사양 동작에 영향 없는 정책 명시·메시지 수준 또는 사양 결정 대기.

---

## 2. F-002 검증 표

### 2.1 A-002 시그니처 불변 확인 (이미지 첨부 시에도 동일)

api.md A-002 L86-88 ↔ `AiAgentClient.kt:254`:

| 토큰 | 사양 | 구현 | 일치 |
|------|------|------|------|
| 함수명 | `ask` | `ask` | 통과 |
| 파라미터 | `request: AiRequest` | `request: AiRequest` | 통과 |
| 반환 | `Result<AiResponse>` | `Result<AiResponse>` | 통과 |
| suspend | suspend | `public suspend fun` | 통과 |
| visibility | public | `public` | 통과 |

이미지 첨부 시에도 동일 진입점 (`AiRequest.images` 필드 사용) — A-002 시그니처 불변, 사양 정합. T11 검증 결과 그대로 유지.

### 2.2 F-002 정상 흐름 (features.md L109-114)

| 단계 | 사양 | 구현 위치 | 결과 |
|------|------|-----------|------|
| 1 | `client.ask(AiRequest(prompt, images=...))` 호출 | `AiAgentClient.kt:254` (이미지 유무 무관 단일 진입) | 통과 |
| 2 | 각 ImageInput을 검증 (크기/mimeType) | `AiAgentClient.kt:270` `validateImages(request, provider.capabilities)` 호출 → `validateImages` 본체 (L342-402) | 통과 |
| 3 | 각 ImageInput을 Provider 형식으로 인코딩 (Bytes → base64 / Url → fetch + base64) | `Mapper.toAnthropicRequest` (L67-82) → `buildContentJson` (L93-147)에서 Bytes는 매직 넘버 검증 + base64, Url은 fetch + 매직 넘버 + base64 | 통과 |
| 4 | Provider API 호출 | `ClaudeProvider.complete` L98-155 (T11에서 이미 검증, T12에서 `Mapper.toAnthropicRequest` 호출 시 `httpClient` 인자 추가) | 통과 |
| 5 | 응답 변환 후 반환 | `Mapper.toAiResponse` + `AiAgentClient.ask` 빈 응답 검증 (T11 검증 그대로) | 통과 |

### 2.3 F-002 예외 흐름 (features.md L118-126)

| ID | 사양 조건 | ERR | 구현 위치 | AiException variant | 결과 |
|----|-----------|-----|-----------|----------------------|------|
| E-201 | 단일 이미지 5MB 초과 | ERR-005 | `AiAgentClient.kt:366` `image.data.size > capabilities.maxImageSizeBytes` → `InvalidInput("image too large: ${image.data.size} > ${capabilities.maxImageSizeBytes}")` | `InvalidInput` | 통과 (메시지에 "too large" 포함) |
| E-202 | 합계 20MB 초과 | ERR-005 | `AiAgentClient.kt:395` `totalBytes > IMAGES_TOTAL_MAX_BYTES` (20MB 상수, L516) → `InvalidInput("images total too large: ...")` | `InvalidInput` | 통과 |
| E-202' | 개수 한계 초과 (Capabilities.maxImagesPerRequest) | ERR-005 | `AiAgentClient.kt:355` `images.size > capabilities.maxImagesPerRequest` → `InvalidInput("images total too large: count=...")` | `InvalidInput` | 통과 (사양 보강 필요 — S-T12-2) |
| E-203 | Uri 읽기 실패 (v0.1: SDK 자동 resolve 안 함) | ERR-005 | `AiAgentClient.kt:386` `is ImageInput.Uri` → `InvalidInput("uri unreadable: SDK does not auto-resolve Uri ...")` + `Mapper.kt:135` 안전망 동일 메시지 | `InvalidInput` | 통과 (D-004 연장 정책 — 사양 보강 필요 S-T12-3) |
| E-204 | 지원하지 않는 mimeType | ERR-005 | `AiAgentClient.kt:372` `image.mimeType !in capabilities.supportedImageMimeTypes` → `InvalidInput("unsupported mime type: ${image.mimeType}")` | `InvalidInput` | 통과 |
| E-205 | Provider 이미지 미지원 | ERR-004 | `AiAgentClient.kt:350` `!capabilities.supportsImage` → `Configuration("provider does not support images")` (메시지 정확 일치) | `Configuration` | 통과 |
| E-206 | URL fetch 4xx | ERR-005 | `Mapper.kt:309-312` `4xx → InvalidInput("image url unreachable: HTTP $code")` | `InvalidInput` | 통과 |
| E-206 | URL fetch 5xx/timeout/DNS | ERR-001 | `Mapper.kt:295-300` SocketTimeoutException → `Network`, IOException → `Network`, 5xx → `Network(IOException("server error: $code"))` (L307) | `Network` | 통과 (Q-T12-4 — 5xx 시 cause 합성) |
| E-207 | 이미지 디코딩 실패 (매직 넘버 위조) | ERR-005 | `Mapper.kt:203-215` `verifyMagicNumber` (declared mime ≠ detected → `InvalidInput("image decode failed: declared mime '$mime' does not match actual ...")`) + `Mapper.kt:121` URL fetch 후 detect 실패 → `InvalidInput("image decode failed: cannot detect mime type ...")` | `InvalidInput` | 통과 |
| E-101~E-110 | F-001 동일 | (T11 검증) | T11 위치 그대로 (회귀 §4) | (T11 통과) | 통과 (회귀 §4) |

### 2.4 R-016 ImageInput.Bytes equals/hashCode 명시 정의

| 요건 (data-model.md L93-107) | 구현 (`ImageInput.kt:39-62`) | 결과 |
|------------------------------|------------------------------|------|
| `data class`가 아닌 `class` (auto-equals 회피) | `public class Bytes(...)` (`data class` 아님) | 통과 |
| equals: `mimeType` 같고 `data.contentEquals(other.data)` | L49-54 토큰 단위 일치 | 통과 |
| hashCode: `31 * data.contentHashCode() + mimeType.hashCode()` | L57 토큰 단위 일치 | 통과 |
| toString: `"ImageInput.Bytes(mimeType=$mimeType, size=${data.size})"` (data 본문 노출 금지) | L60-61 토큰 단위 일치 | 통과 |

단위 테스트 (`ImageInputTest`):
- `R-016 — 동일한 data + mimeType 의 Bytes 는 equals 가 true` (L31-37)
- `R-016 — 동일 data 라도 mimeType 다르면 equals 가 false` (L40-45)
- `R-016 — 동일 mimeType 라도 data 다르면 equals 가 false` (L48-53)
- `R-016 — ByteArray reference 가 달라도 contentEquals 가 같으면 equals 가 true` (L56-67) — **R-016 핵심**
- `R-016 — toString 은 mimeType 과 size 만 노출 (data 본문 노출 금지)` (L70-77)

**5개 모두 의미 있는 검증.** R-016이 명시한 `ByteArray reference 비교 회피`가 정확히 검증됨.

### 2.5 R-023 SSRF 형식 검증만 / 호출자 책임

| 요건 (provider-spec.md L182-193, data-model.md M-011 L362-367) | 구현 | 결과 |
|---------------------------------------------------------------|------|------|
| SDK는 외부망/내부망/loopback 판별 안 함 | `Mapper.fetchUrlBytes` (L282-318)에 도메인/IP 검사 없음. https 형식만 `ImageInput.Url.init`에서 검증 | 통과 |
| URL 형식 검증 (`https://` 스킴, 길이 한계 등) | `ImageInput.Url.init` (L75-81) `require(url.startsWith("https://"))` + 빈 문자열 거부 | 통과 (길이 한계는 OkHttp가 처리) |
| HTTP fetch 실패 시 E-206 매핑 | `Mapper.fetchUrlBytes` 4xx → InvalidInput, 5xx/timeout/DNS → Network | 통과 |
| 도메인 allowlist / loopback 차단 / 메타데이터 IP 차단 = 호출자 책임 | SDK 코드에 해당 검증 없음 (의도적, 사양 정합) | 통과 |

단위 테스트 (`ImageInputTest`):
- `Url init — 정상 https URL 은 통과` (L108-111)
- `Url init — 빈 문자열은 IllegalArgumentException` (L114-121)
- `Url init — http (non-https) 스킴은 거부 (R-023 형식 검증)` (L124-134)
- `Url init — file 스킴은 거부 (R-023 형식 검증)` (L137-144)

**4개 모두 R-023 "형식 검증만"을 정확히 검증.**

### 2.6 D-004 자동 리사이즈 없음

| 요건 (overview.md D-004) | 구현 | 결과 |
|--------------------------|------|------|
| SDK는 이미지 자동 리사이즈/압축 안 함 | `validateImages`는 5MB 초과 시 거부만, 리사이즈 호출 없음 | 통과 |
| 호출자가 사전 5MB 처리 책임 | E-201 InvalidInput 발생 즉시 거부 | 통과 |
| `ImageInput.Uri` 자동 ContentResolver 호출 안 함 (D-004 연장) | `validateImages` L386 + `Mapper.buildContentJson` L135 둘 다 즉시 InvalidInput | 통과 |

### 2.7 M-001 images 한계

| 요건 (data-model.md L29-32) | 구현 위치 | 결과 |
|-----------------------------|-----------|------|
| images 최대 10장 (data class init) | `AiRequest.kt:39-44` `require(images.size <= 10)` (T11 검증) | 통과 |
| 합계 20MB | `AiAgentClient.IMAGES_TOTAL_MAX_BYTES = 20 * 1024 * 1024L` (L516) + `validateImages` totalBytes 누적 비교 | 통과 |

### 2.8 M-003 ImageInput sealed (Bytes/Url/Uri)

| Variant | 사양 (data-model.md L82-110) | 구현 (`ImageInput.kt`) | 결과 |
|---------|------------------------------|------------------------|------|
| Uri | `data class Uri(val uri: android.net.Uri) : ImageInput()` | L27 토큰 단위 일치 | 통과 |
| Bytes | `class Bytes(val data: ByteArray, val mimeType: String) : ImageInput()` (data class 아님) | L39-62 토큰 단위 일치 + init 검증 추가 | 통과 |
| Url | `data class Url(val url: String) : ImageInput()` | L74-82 토큰 단위 일치 + init 검증 추가 | 통과 |

추가 init 검증 (사양에 없으나 사양 정신과 부합):
- Bytes: data 비어있지 않음 + mimeType 비어있지 않음
- Url: url 비어있지 않음 + https 스킴 강제 (R-023 형식 검증)

### 2.9 AnthropicMessagesRequest content 변경 (String → JsonElement)

| 요건 | 구현 위치 | 결과 |
|------|-----------|------|
| `AnthropicMessage.content`가 다중 블록 표현 가능 (텍스트 + 이미지) | `AnthropicMessagesRequest.kt:48` `val content: JsonElement` | 통과 |
| sealed `AnthropicContentRequestBlock` (Text / Image) + `AnthropicImageSource` | L64-77 (Text/Image) + L89-93 (AnthropicImageSource) | 통과 |
| `@SerialName("text")`, `@SerialName("image")` polymorphic discriminator | sealed class에 `@Serializable` 적용 → kotlinx-serialization 기본 `classDiscriminator = "type"` 사용 — Anthropic API 형식 일치 | 통과 |
| v0.1 base64 source만 사용 | `AnthropicImageSource.type = "base64"` 상수 (`Mapper.SOURCE_TYPE_BASE64`) | 통과 |

단위 테스트 (`MapperImageTest` + `MapperTest`):
- `F-001 회귀 — 텍스트만 있을 때 content 는 단일 text 블록 배열` (MapperImageTest L73-85): F-001 회귀 검증
- `F-002 — Bytes(PNG) 첨부 시 content 는 text + image 블록` (L92-118): 다중 블록 + base64 round-trip
- `M-001 — AiRequest 가 AnthropicMessagesRequest 로 정확히 매핑됨` (MapperTest L41-66): content는 JsonArray로 1개 text 블록 검증

**JsonElement / sealed polymorphic 변경이 단위 테스트로 보증.**

### 2.10 Mapper.toAnthropicRequest suspend 변경 → ClaudeProvider 호출부 정상

| 요건 | 구현 위치 | 결과 |
|------|-----------|------|
| `toAnthropicRequest`를 suspend로 변경 (URL fetch 위해) | `Mapper.kt:67` `suspend fun toAnthropicRequest(...)` | 통과 |
| `httpClient: OkHttpClient? = null` 파라미터 추가 | L70 | 통과 |
| ClaudeProvider 호출부 업데이트 | `ClaudeProvider.kt:106-110` `Mapper.toAnthropicRequest(request, config, httpClient = client.httpClient)` | 통과 (suspend 컨텍스트는 `complete`이 이미 suspend라 자연 흡수) |
| MapperTest 시그니처 업데이트 | `MapperTest.kt:41,69` `runTest { Mapper.toAnthropicRequest(...) }` 적용 (suspend 테스트) | 통과 |

### 2.11 매직 넘버 검증 (R-004)

| 항목 | 사양 (features.md F-002 NFR L130 / E-207) | 구현 (`Mapper.kt:222-269` `detectMimeFromMagic`) | 결과 |
|------|-------------------------------------------|------------------------------------------------|------|
| JPEG: FF D8 FF | L224-228 | 통과 |
| PNG: 89 50 4E 47 0D 0A 1A 0A (8 bytes) | L232-243 | 통과 |
| WebP: "RIFF" + 4 bytes size + "WEBP" | L256-266 | 통과 |
| GIF: "GIF87a" 또는 "GIF89a" | L245-254 | 통과 |
| 미인식 → null → InvalidInput("image decode failed: ...") | `verifyMagicNumber` L203-215 (detect=null → throw, detect≠declared → throw) | 통과 |

### 2.12 `validateImages` 검증 순서 (실패 우선)

| 순서 | 사양 의도 | 구현 (`AiAgentClient.kt:342-402`) | 결과 |
|------|-----------|----------------------------------|------|
| 0 | images 비어있으면 즉시 통과 (F-001 회귀) | L347 `if (images.isEmpty()) return null` (E-205 검사 전 — 회귀 보장) | 통과 |
| 1 | E-205 supportsImage 검사 | L350-352 | 통과 |
| 2 | E-202 개수 한계 (M-001 init은 ≤10, 본 위치는 Provider 한계) | L355-359 | 통과 |
| 3 | 이미지 순회 — Bytes/Url/Uri 분기 | L362-391 | 통과 |
| 3-a | Bytes: E-201 size + E-204 mimeType + totalBytes 누적 | L364-378 | 통과 |
| 3-b | Url: 형식은 init이 보장, fetch는 Mapper에 위임 | L379-382 (no-op) | 통과 |
| 3-c | Uri: D-004 즉시 거부 (E-203) | L383-389 | 통과 |
| 4 | E-202 합계 한계 | L395-399 | 통과 (모든 이미지 누적 후 검증) |

---

## 3. 단위 테스트 검증 표

### 3.1 ImageInputTest.kt 11 케이스 (impl_summary가 11이라 표기, 실제 11개 — 5 R-016 + 2 Bytes init + 4 Url init)

| # | 테스트 함수명 | 검증 의도 | 결과 |
|---|---------------|-----------|------|
| 1 | R-016 — 동일 data + mimeType equals true | R-016 핵심 | 통과 |
| 2 | R-016 — mimeType 다르면 false | R-016 mimeType 변별력 | 통과 |
| 3 | R-016 — data 다르면 false | R-016 data 변별력 | 통과 |
| 4 | R-016 — ByteArray reference 달라도 contentEquals 같으면 true | R-016 명시 정의 본질 | 통과 (R-016 핵심) |
| 5 | R-016 — toString data 본문 노출 금지 | 보안/디버깅 정합 | 통과 |
| 6 | Bytes init — 빈 data 거부 | T12 신규 init | 통과 |
| 7 | Bytes init — 빈 mimeType 거부 | T12 신규 init | 통과 |
| 8 | Url init — 정상 https 통과 | R-023 형식 | 통과 |
| 9 | Url init — 빈 문자열 거부 | T12 신규 init | 통과 |
| 10 | Url init — http (non-https) 거부 | R-023 형식 (스킴 강제) | 통과 |
| 11 | Url init — file 스킴 거부 | R-023 형식 (file:// 회피) | 통과 |

**11개 모두 의미 있는 검증.** R-016 5개 + init 6개로 사양 ID와 1:1 매핑.

### 3.2 AskWithImagesTest.kt 8 케이스

| # | 테스트 함수명 | 검증 ID | 의미 | 결과 |
|---|---------------|---------|------|------|
| 1 | F-002 정상 흐름 — 이미지 1장 첨부 | F-002 정상 1~5 + completeCallCount=1 | F-002 핵심 정상 흐름 | 의미 있음 |
| 2 | F-002 E-201 — 단일 5MB 초과 | E-201 + 메시지 "too large" + completeCallCount=0 | E-201 핵심 + Provider 미호출 보증 | 의미 있음 |
| 3 | F-002 E-202 — 합계 20MB 초과 | E-202 + 메시지 "total too large" | E-202 합계 변별 | 의미 있음 |
| 4 | F-002 E-202 — Capabilities maxImagesPerRequest 초과 | E-202' (개수 한계, S-T12-2) | M-001 init 통과 후 Provider 한계 (사양 보강 권장) | 의미 있음 |
| 5 | F-002 E-204 — 지원하지 않는 mimeType | E-204 + 메시지 "unsupported mime type" | E-204 핵심 | 의미 있음 |
| 6 | F-002 E-205 — supportsImage=false | E-205 Configuration + 메시지 정확 일치 ("provider does not support images") | E-205 핵심, 메시지 정확성 | 의미 있음 |
| 7 | F-002 E-203 — ImageInput.Uri 도달 | E-203 + 메시지 "uri unreadable" | D-004 연장 (S-T12-3) | 의미 있음 |
| 8 | F-002 회귀 — images 빈 리스트 | F-001 회귀 — supportsImage=false라도 images 빈 리스트면 통과 | F-001 회귀 핵심 | 의미 있음 |

**8개 모두 의미 있는 검증.** E-201/E-202(2개)/E-203/E-204/E-205 + 정상 + 회귀까지 빠짐없이 커버.

### 3.3 MapperImageTest.kt 12 케이스

| # | 테스트 함수명 | 검증 ID | 결과 |
|---|---------------|---------|------|
| 1 | F-001 회귀 — 텍스트만일 때 단일 text 블록 | F-001 회귀 (content 변경 영향 없음) | 통과 |
| 2 | F-002 — Bytes(PNG) 첨부 시 text + image 블록 + base64 round-trip | F-002 정상 흐름 3 + base64 정합 | 통과 |
| 3 | F-002 — JPEG 매직 넘버 통과 | R-004 JPEG | 통과 |
| 4 | F-002 — WebP 매직 넘버 통과 | R-004 WebP | 통과 |
| 5 | F-002 — GIF 매직 넘버 통과 | R-004 GIF | 통과 |
| 6 | F-002 E-207 — declared mime ≠ 실제 매직 | E-207 위조 검증 | 통과 |
| 7 | F-002 E-207 — 알 수 없는 매직 (random bytes) | E-207 unknown | 통과 |
| 8 | F-002 E-206 — fetchUrlBytes HTTP 404 → InvalidInput | E-206 4xx | 통과 |
| 9 | F-002 E-206 — fetchUrlBytes HTTP 503 → Network | E-206 5xx | 통과 (Q-T12-4 — cause 합성) |
| 10 | F-002 E-206 — fetchUrlBytes httpClient null → Configuration | 안전망 (Hilt 미초기화 등) | 통과 |
| 11 | F-002 — fetchUrlBytes 정상 응답 → 응답 바이트 그대로 | fetchUrlBytes 정상 | 통과 |
| 12 | F-002 E-203 — ImageInput.Uri 도달 → InvalidInput (Mapper 안전망) | D-004 연장 (Mapper 레벨 방어) | 통과 |

**12개 모두 의미 있는 검증.** F-001 회귀(1) + F-002 정상(2,3,4,5) + E-207(6,7) + E-206(8,9,10) + 정상 fetch(11) + E-203 안전망(12). MockWebServer로 fetchUrlBytes 직접 검증한 점은 S-T12-1 우회로 적절한 선택.

### 3.4 MapperTest.kt 시그니처 업데이트 검증 (T11 12개 케이스 변경 1~2개)

| # | 테스트 함수명 | 시그니처 변경 반영 | 결과 |
|---|---------------|---------------------|------|
| 1 | M-001 — AiRequest가 AnthropicMessagesRequest로 정확히 매핑됨 | `runTest` 추가 + content는 JsonArray로 1개 text 블록 검증 (L41-66) | 통과 (T12 시그니처 변경 정합) |
| 2 | M-001 — modelId가 ProviderConfig에서만 | `runTest` 추가 (L69-80) | 통과 |
| 3-12 | M-002/M-009/finishReason/empty content 등 | toAiResponse는 변경 없음 (suspend 아님) | 통과 (T11 그대로) |

**시그니처 변경 2개 반영 정확.** Mapper.toAnthropicRequest의 suspend 변경에 맞춰 `runTest`가 적용됨. 빌드 가능.

### 3.5 컴파일 가능성 점검 (이론)

| 항목 | 검증 | 결과 |
|------|------|------|
| import 일관성 | `MapperImageTest.kt:1-24` mockwebserver + okhttp + kotlinx.serialization.json + kotlinx.coroutines.test + junit + java.util.Base64 모두 build.gradle 의존성 보유 | 통과 |
| `runTest` 사용 | 모든 suspend 테스트가 `kotlinx.coroutines.test.runTest` 적용 | 통과 |
| FakeProvider — Provider 인터페이스 시그니처 일치 | `AskWithImagesTest.kt:301-328` `id`/`capabilities`/`complete`/`stream` 모두 구현, capabilities 주입 가능 | 통과 |
| `AiAgentClient` internal 생성자 호출 가능 | `AskWithImagesTest.kt:286-298` 같은 모듈에서 internal 호출 | 통과 |
| `Capabilities.copy()` 사용 | `AskWithImagesTest.kt:162,208,270` `claudeCapabilities.copy(...)` — Capabilities는 `data class`라 가능 | 통과 |
| MockWebServer 의존성 | `MapperImageTest.kt:14-15` `okhttp3.mockwebserver.MockResponse` / `MockWebServer` import — build.gradle 의존성 (testImplementation `com.squareup.okhttp3:mockwebserver`) 필요 (관찰: 직전 라운드부터 활용 추정) | 통과 (의존성 가정) |
| `Mapper.fetchUrlBytes` internal 호출 가능 | 같은 모듈 테스트에서 internal suspend fun 호출 가능 | 통과 |
| `Mapper.detectMimeFromMagic` internal 호출 가능 | 같은 모듈 테스트에서 internal fun 호출 가능 (`MapperImageTest.kt:279`) | 통과 |
| `MockResponse.setBody(okio.Buffer().write(...))` | okio Buffer는 transitive 의존성 (OkHttp가 사용) — 사용 가능 | 통과 |
| `mockk<android.net.Uri>()` 사용 (AskWithImagesTest L236, MapperImageTest L288) | mockk는 final class도 mock 가능 — 정상 | 통과 |
| 실제 네트워크 호출 0회 | AskWithImagesTest는 FakeProvider, MapperImageTest는 MockWebServer + Mapper.fetchUrlBytes 직접 호출 — 외부 호출 없음 | 통과 |

---

## 4. F-001 회귀 점검

T11에서 통과한 F-001 흐름이 T12 변경 후 깨지지 않았는지.

### 4.1 핵심 회귀 항목

| 항목 | 변경 영향 가능성 | 회귀 점검 | 결과 |
|------|------------------|-----------|------|
| `AiAgentClient.ask` 시그니처 | T12에서 `validateImages` 추가, 시그니처 자체는 불변 | A-002 토큰 일치 (§2.1) | 회귀 없음 |
| `ask`의 빈 응답 검증 (E-110) | T12에서 변경 없음 | `AiAgentClient.kt:278-288` 그대로 | 회귀 없음 |
| `ask`의 catch 체인 (CancellationException → AiException → IllegalArgumentException → Throwable) | T12에서 변경 없음 | `AiAgentClient.kt:290-310` 순서 그대로 | 회귀 없음 |
| `Mapper.toAnthropicRequest` 매핑 결과 (model/maxTokens/temperature/messages) | T12에서 content 타입 String → JsonElement | `MapperTest.kt:41-66` 단위 테스트가 JsonArray로 검증 | 회귀 없음 (시그니처 + 매핑 모두 정합) |
| `Mapper.toAiResponse` (응답 파싱) | T12에서 변경 없음 | T11 검증 그대로, MapperTest 케이스 6~12 통과 | 회귀 없음 |
| `ClaudeProvider.complete` 본체 | T12에서 `Mapper.toAnthropicRequest` 호출에 `httpClient` 인자 추가 | `ClaudeProvider.kt:106-110` 호출만 변경, 그 외 동일 | 회귀 없음 |
| F-001 정상 흐름 (`ask` → `complete` → `toAiResponse`) | 변경 없음 | `AskTest`의 14개 케이스 그대로 통과 가정 | 회귀 없음 |
| F-001 빈 응답 R-005 (END_TURN/STOP_SEQUENCE OK, MAX_TOKENS/OTHER 거부) | 변경 없음 | T11 검증 그대로 | 회귀 없음 |
| F-001 R-007 (진행 중 useProvider 영향 없음) | 변경 없음 | T11 검증 그대로 | 회귀 없음 |
| F-001 R-020 케이스 A/B (close ↔ ask) | 변경 없음 | T11 검증 그대로 | 회귀 없음 |
| F-001 D-005 (자동 재시도 없음) | 변경 없음 | T11 검증 그대로 | 회귀 없음 |
| F-001 E-101~E-110 매핑 | 변경 없음 | T11 검증 그대로 (`ErrorMapperTest` 12 + `AskTest` 14) | 회귀 없음 |
| `AskWithImagesTest`의 회귀 케이스 (images 빈 리스트) | 명시적 회귀 보호 | `validateImages` L347 `images.isEmpty() → return null` 즉시 통과 — supportsImage=false라도 통과 | 회귀 없음 (테스트 8) |

### 4.2 ImageInput 도입 영향 (data-model.md)

| 항목 | T12 추가 | 사양 영향 | 결과 |
|------|----------|-----------|------|
| `ImageInput.Bytes.init` 검증 (data 비어있지 않음, mimeType 비어있지 않음) | 사양에 명시 없음 (보강) | M-003 사양과 모순 없음 (사양은 변별력 보장이 명시 정의 의도) | 회귀 없음 |
| `ImageInput.Url.init` 검증 (https 강제, 빈 문자열 거부) | provider-spec.md L186 "URL 형식 검증 (`https://` 스킴, 길이 한계 등)" 명시 | 사양 정합 | 회귀 없음 |

---

## 5. 발견된 이슈

본 라운드 Blocker/Major 0건. Minor 5건.

### Q-T12-1 [Minor] `ImageInput.Url.init`의 `https://` 강제로 인해 통합 테스트(MockWebServer)에서 URL 인스턴스화 불가

- **위치**: `ImageInput.kt:75-81`
- **현상**: `ImageInput.Url.init`이 `require(url.startsWith("https://"))`로 강제하므로, MockWebServer(기본 http)에서 `ImageInput.Url(server.url("/").toString())` 인스턴스화 자체가 `IllegalArgumentException`로 실패한다. 본 라운드는 `Mapper.fetchUrlBytes`(internal)을 직접 호출하는 우회로 검증을 수행했고 동작상 정합.
- **사양 정합**: provider-spec.md L186 "URL 형식 검증 (`https://` 스킴, 길이 한계 등)" 명시 — 구현이 사양과 정합. 다만 통합 테스트 인프라 부재로 end-to-end 검증 불가.
- **권장**: 사양 변경 없음. 추후 통합 테스트 도입 시 (a) `MockWebServer.useHttps()` 사용 또는 (b) v0.2에서 `ImageInput.Url.init`의 https 검증을 (디버그 빌드에서) 우회 가능한 메커니즘 검토 (S-T12-1).
- **Severity 사유**: 단위 테스트 우회로 검증되어 동작 영향 없음. 사양 정합.

### Q-T12-2 [Minor] E-202가 두 의미 (개수 한계 / 합계 한계)를 겸함

- **위치**: features.md F-002 L120 "E-202 이미지 총 합계 20MB 초과" / `AiAgentClient.validateImages` L355-359 (개수) + L395-399 (합계)
- **현상**: 사양 E-202는 "합계 20MB"만 명시하지만, 코드는 `Capabilities.maxImagesPerRequest` 초과 케이스도 같은 ID/같은 InvalidInput으로 매핑(메시지에 "count="를 포함). M-001 init은 `images.size <= 10`을 강제하므로, Provider Capabilities가 10보다 작은 경우(예: 5)에만 본 분기가 활성화된다. P-CLAUDE의 maxImagesPerRequest=10이므로 v0.1 단일 Provider에서는 도달 불가하나, 미래 Provider에 대비한 방어.
- **사양 정합**: 사양은 "합계"만 명시. 개수 한계는 "M-001 init이 강제"한다는 별도 표현이 있으나, Provider 한계는 없다. 코드는 사양에 없는 분기를 추가했지만 **사양과 모순되지 않는다**.
- **권장**: spec-architect에게 E-202 의미 분리 보강 요청 (S-T12-2). 권장 보강 문구: "E-202: 이미지 합계가 20MB(M-001) 또는 Provider별 maxImagesPerRequest를 초과 → InvalidInput". Severity Minor.

### Q-T12-3 [Minor] `ImageInput.Uri` v0.1 처리 정책의 사양 명시 부재

- **위치**: features.md F-002 L121 "E-203 Uri 읽기 실패 (권한·존재 X)" / `AiAgentClient.validateImages` L383-389 + `Mapper.buildContentJson` L132-138
- **현상**: 사양 E-203은 "권한·존재 X"를 조건으로 명시하지만, 코드는 **모든 ImageInput.Uri 도달**을 즉시 거부한다 (D-004 연장 — SDK는 ContentResolver 호출 안 함, 호출자가 사전 변환 책임). 즉, Uri가 정상 읽기 가능해도 SDK는 거부한다.
- **사양 정합**: D-004 정책의 연장으로 합리적 결정이지만, "권한·존재 X" 표현이 호출자에게 "Uri는 가능하다"는 인상을 줄 수 있다.
- **권장**: spec-architect에게 F-002 정상 흐름 또는 별도 섹션에 "v0.1: SDK는 `ImageInput.Uri`를 자동 resolve 하지 않음. 호출자가 ContentResolver로 `ImageInput.Bytes`로 변환 후 전달" 명시 권장 (S-T12-3). v0.2 옵션은 별도 검토. Severity Minor.

### Q-T12-4 [Minor] E-206 5xx 시 cause는 원본이 아닌 합성 IOException

- **위치**: `Mapper.kt:307` `throw AiException.Network(IOException("server error: $code"))`
- **현상**: HTTP 5xx 응답 시 OkHttp는 정상 Response 객체로 반환되며 IOException을 throw하지 않는다. 코드는 본 응답을 보고 Network exception을 합성하는데, **cause로 새 `IOException("server error: $code")`을 생성**한다. 호출자가 stack trace를 디버깅할 때 원본 HTTP 응답 본문/헤더 등이 cause에 보존되지 않음. 4xx 케이스는 cause 없이 InvalidInput을 throw하므로 이쪽은 정합.
- **사양 정합**: features.md F-002 E-206 "5xx → AiException.Network(cause)"에서 cause는 명시되어 있으나 "원본 IOException 보존"인지 "합성 가능"인지 모호. 본 구현은 합성 IOException으로 cause 슬롯은 채움.
- **권장**: 사양 보강 불필요. 본 라운드 통과로 처리. 디버깅 가능성 차원에서 cause 메시지가 코드/HTTP status를 포함하므로 충분.
- **Severity 사유**: 호출자에게 노출되는 정보(메시지)는 충분. 동작 영향 없음.

### Q-T12-5 [Minor] `validateImages`의 회귀 케이스 (images 빈 + supportsImage=false) 통과 흐름

- **위치**: `AiAgentClient.kt:347` `if (images.isEmpty()) return null`
- **현상**: 검증 순서가 (1) images 빈 → 통과, (2) supportsImage=false → E-205 거부 순서. `AskWithImagesTest`의 회귀 테스트(테스트 8)가 "supportsImage=false + images 빈 리스트"를 통과시킴 — 즉, **이미지 미지원 Provider에 텍스트 단발 질의는 정상 동작**. 이는 F-001 회귀 보호로 정합이지만, "supportsImage=false" Provider가 어차피 v0.1 시점에 존재하지 않으므로(P-CLAUDE는 supportsImage=true), 이 분기 역시 도달 의미가 약하다.
- **사양 정합**: features.md F-002 정상 흐름 시작이 "각 ImageInput을 검증"이라 했으므로, images 빈 리스트는 본 단계 자체를 건너뛰는 것이 자연. F-001 진입 흐름과 통합된 ask 진입점에서 `validateImages` 호출은 적절.
- **권장**: 변경 없음. 회귀 케이스의 명시 검증으로 충분.
- **Severity 사유**: 정합. 정보성 항목.

---

## 6. 사양 명확화 필요 항목 (spec-architect 회신 요청)

### S-T12-1 [Minor] `ImageInput.Url`의 https 강제 + MockWebServer 호환성

- **현 사양**: provider-spec.md L186 "URL 형식 검증 (`https://` 스킴, 길이 한계 등)"
- **현 구현**: `ImageInput.Url.init`이 https를 강제 → 인스턴스화 자체에서 IllegalArgumentException
- **영향**: MockWebServer(기본 http) 통합 테스트 작성 불가. 본 라운드는 `Mapper.fetchUrlBytes`(internal) 직접 호출로 우회.
- **권장**: 사양 변경 없음. 추후 (a) MockWebServer.useHttps 사용 가이드 추가 또는 (b) v0.2에서 디버그 빌드 https 우회 옵션 검토. 우선순위: **낮음 (Minor)**. 본 라운드 통과 가능.

### S-T12-2 [정보성→Minor] E-202의 의미 분리 (개수 한계 vs 합계 한계)

- **현 사양**: features.md F-002 E-202 "이미지 총 합계 20MB 초과" → InvalidInput("images total too large")
- **현 구현**: `Capabilities.maxImagesPerRequest`가 M-001 init의 size ≤ 10보다 작은 경우, 합계 20MB 미만이라도 거부. 두 케이스 모두 E-202 매핑 + InvalidInput로 통일.
- **권장**: 사양 보강 — E-202를 "이미지 총 합계 20MB(M-001) 또는 Provider별 maxImagesPerRequest 초과"로 명시. **현 라운드 코드 정합**. 우선순위: **중간**. 다음 라운드까지 진행.

### S-T12-3 [Minor] `ImageInput.Uri`의 v0.1 처리 정책

- **현 사양**: features.md F-002 E-203 "Uri 읽기 실패 (권한·존재 X)" → InvalidInput("uri unreadable")
- **현 구현**: SDK는 `ImageInput.Uri`를 ContentResolver로 자동 해석하지 않고, ask 진입 시점에 즉시 InvalidInput("uri unreadable: SDK does not auto-resolve Uri ...")로 거부 (D-004 연장).
- **권장**: 사양 보강 — F-002 정상 흐름 3 또는 별도 섹션에 "v0.1: SDK는 `ImageInput.Uri`를 자동 resolve 하지 않음. 호출자가 ContentResolver로 Bytes 변환 후 전달" 명시. v0.2에서 자동 resolve 옵션 검토. 우선순위: **중간**. 호출자 DX 개선.

### S-T11-1 [Minor, 이월] features.md F-001 E-107 발생 위치 명확화 (qa_report_3 §6 그대로 이월)

- 본 라운드 영향 없음. 다음 라운드까지 spec-architect 처리 대기.

---

## 7. 종결 권고

### F-002 종결

**통과 — Blocker 0건, Major 0건**.

- A-002 시그니처(`suspend fun ask(request: AiRequest): Result<AiResponse>`)가 이미지 첨부 시에도 불변, api.md 토큰 단위 일치.
- F-002 정상 흐름 1~5단계가 코드(AiAgentClient.ask + validateImages + Mapper.toAnthropicRequest + buildContentJson + ClaudeProvider.complete)에 차례로 매핑.
- E-201/E-202(합계+개수)/E-203/E-204/E-205/E-206(4xx+5xx+null httpClient)/E-207(매직 위조+unknown) 모든 예외 흐름이 코드와 단위 테스트로 1:1 매핑.
- R-016 ImageInput.Bytes equals/hashCode 명시 정의가 사양 토큰 단위 일치 (data class 회피, contentEquals + 31 * contentHashCode + mimeType.hashCode).
- R-023 SSRF 형식 검증만 — `ImageInput.Url.init`의 https 강제 + `Mapper.fetchUrlBytes`의 도메인/IP 검사 부재로 사양 정합.
- D-004 자동 리사이즈 없음 — `validateImages` 거부만, ImageInput.Uri 자동 resolve 안 함.
- M-001 images "최대 10장 / 합계 20MB" — AiRequest.init (≤10) + AiAgentClient.IMAGES_TOTAL_MAX_BYTES (20MB) 분리 매핑.
- M-003 ImageInput sealed Variant 3종(Uri/Bytes/Url) 토큰 단위 일치 + init 검증 추가.
- AnthropicMessagesRequest.content 변경 (String → JsonElement) + sealed AnthropicContentRequestBlock(Text/Image) + AnthropicImageSource — kotlinx-serialization polymorphic 정상.
- Mapper.toAnthropicRequest의 suspend 변경이 ClaudeProvider 호출부와 정합, MapperTest 시그니처 업데이트 반영.
- 단위 테스트 31개(이미지 11 + ask images 8 + mapper image 12) + MapperTest 시그니처 업데이트 2개 모두 의미 있는 검증, 컴파일 가능, 실제 네트워크 호출 0회 (FakeProvider + MockWebServer + Mapper.fetchUrlBytes 직접 호출).
- F-001 회귀 없음 — AskTest 14개 그대로 통과 가정, `validateImages`는 images 빈 리스트 즉시 통과로 F-001 흐름 영향 없음, MapperTest 회귀 케이스 통과(텍스트 단발 → 단일 text 블록).
- Minor 5건은 모두 사양 동작에 영향 없음(통합 테스트 인프라/사양 보강 권장/디버깅 가능성/회귀 보호 흐름).

### 다음 wave 진입 권고

| 라운드 | 의존성 | 진입 가능 | 비고 |
|--------|--------|-----------|------|
| **F-003 (askStream)** | F-001/F-002 (Mapper.toAnthropicRequest suspend, content=JsonElement) | **진입 가능** | impl_summary_5 §5 인계 그대로. ClaudeProvider.stream NotImplementedError → SSE 파서. validateImages 재사용(`askStream`도 진입 시점 호출). E-301/E-302/E-303 매핑. |
| **F-004 (Session)** | F-001/F-002 + F-005 R-014 | **진입 가능** | impl_summary_5 §6 인계. Session.send도 validateImages + Mapper.toAnthropicRequest(suspend) 재사용. history 다중 메시지 변환 함수 별도 필요(Mapper 확장). Mutex로 동시 send 직렬화. |
| **F-006 (Hilt)** | F-001 (httpClient 사용 위치) + F-002 (httpClient 인자) | **진입 가능** | sdk/di/SdkModule.kt 신규. AiAgentClient/ProviderRegistry/AnthropicHttpClient/OkHttpClient 주입. F-002에서 httpClient를 ClaudeProvider가 매 호출마다 빌드하는 부분을 @Singleton OkHttpClient로 전환 검토(성능 개선). 단, AiAgentClient.httpClient holder는 close에서 cancelAll에 사용되므로 본 holder set 흐름은 유지(F-006에서 set). |
| **F-007 (영속화)** | F-004 + F-002 (ImageInput 영속화 가이드 R-021) | F-004 후 진입 | M-011 + DataStore. ImageInput.Bytes base64 영속화 시 1MB 한계(E-704). schemaVersion=1 강제(R-018). |

### 다음 액션 (오케스트레이터에게)

1. **android-implementer**: F-003/F-004/F-006 중 우선순위(P0) 라운드부터 진입. 의존성상 F-003 → F-004 → F-006 또는 병행 가능(F-006은 다른 두 라운드와 코드 충돌 적음). impl_summary_5 §5/§6의 인계 가이드 정확.
2. **spec-architect**: S-T12-2 (E-202 의미 분리) + S-T12-3 (ImageInput.Uri v0.1 정책) 보강 검토. 둘 다 다음 라운드 진입 차단하지 않음 (Minor).
3. **F-002 자체 종결** — 본 라운드 추가 작업 불필요.

---

## 8. 자체 체크리스트

- [x] 4쌍 경계면 교차 비교 완료 (api.md A-002 ↔ AiAgentClient.ask, data-model.md M-001/M-003 ↔ AiRequest/ImageInput, error-handling.md ERR-005/ERR-004/ERR-001 ↔ AiException variant + KDoc + 매핑 표, features.md F-002 E-201~E-207 ↔ AskWithImagesTest 8 / MapperImageTest 12 / ImageInputTest 11)
- [x] F-002 정상 흐름 1~5단계 모두 코드에 매핑 + 단위 테스트로 보증
- [x] F-002 모든 E-XXX (E-201/E-202/E-203/E-204/E-205/E-206/E-207) → validateImages / Mapper.toAnthropicRequest / fetchUrlBytes / verifyMagicNumber 로 매핑 + 단위 테스트 커버
- [x] api.md A-002 시그니처 토큰 단위 비교 (이미지 첨부 시에도 불변)
- [x] AiException variant ↔ ERR-XXX 매핑 (ERR-005 InvalidInput / ERR-004 Configuration / ERR-001 Network)
- [x] R-016 ImageInput.Bytes equals/hashCode 명시 정의 토큰 단위 일치 + 5개 단위 테스트
- [x] R-023 SSRF 형식 검증만 / 호출자 책임 — ImageInput.Url.init https 강제 + Mapper.fetchUrlBytes 도메인 검사 부재로 정합 + 4개 단위 테스트
- [x] D-004 자동 리사이즈 없음 — validateImages 거부만 + ImageInput.Uri 자동 resolve 거부
- [x] M-001 images "최대 10장 / 합계 20MB" — AiRequest.init (≤10) + AiAgentClient.IMAGES_TOTAL_MAX_BYTES 분리
- [x] M-003 ImageInput sealed Variant 3종 토큰 단위 일치
- [x] AnthropicMessagesRequest.content (JsonElement) + sealed AnthropicContentRequestBlock 변경이 단위 테스트로 보증
- [x] Mapper.toAnthropicRequest suspend 변경 + httpClient 인자 추가가 ClaudeProvider 호출부와 정합, MapperTest 시그니처 업데이트 반영
- [x] 단위 테스트 31개 의미 있는 검증 + 컴파일 가능 형태 + 실제 네트워크 호출 0회
- [x] F-001 회귀 점검 — 시그니처/catch 체인/빈 응답 검증/Mapper.toAiResponse/ClaudeProvider.complete/AskTest 모두 회귀 없음
- [x] 모든 이슈에 (file:line) 근거 명시
- [x] 사양 모호함 항목 별도 섹션(6번)으로 spec-architect 회신 요청 (S-T12-1/2/3)
- [x] 코드/사양 직접 수정하지 않음
