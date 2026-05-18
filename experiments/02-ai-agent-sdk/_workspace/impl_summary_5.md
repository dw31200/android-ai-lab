# 구현 요약 #5 — F-002 (멀티모달 질의 — 이미지)

본 라운드(T12): F-002 본체 — `ImageInput` 모델 보강 + `AiAgentClient.ask` 이미지 검증 흐름 + `Mapper.toAnthropicRequest` 이미지 블록 변환 + 단위 테스트.

---

## 1. 생성/수정 파일 목록

### 1-A. 신규 파일

| 파일 | 책임 |
|------|------|
| `sdk/src/test/kotlin/com/androidailab/aisdk/model/ImageInputTest.kt` | M-003 / R-016 + init 검증 단위 테스트 |
| `sdk/src/test/kotlin/com/androidailab/aisdk/client/AskWithImagesTest.kt` | F-002 ask() 이미지 검증 흐름 (E-201/E-202/E-203/E-204/E-205) |
| `sdk/src/test/kotlin/com/androidailab/aisdk/internal/network/MapperImageTest.kt` | F-002 Mapper 이미지 블록 변환 (Bytes/Url) + E-206/E-207 |

### 1-B. 수정 파일

| 파일 | 변경 내용 |
|------|----------|
| `sdk/src/main/kotlin/com/androidailab/aisdk/model/ImageInput.kt` | `Bytes.init` (data 비어있지 않음, mimeType 비어있지 않음) + `Url.init` (https 스킴 강제 — R-023 형식 검증) |
| `sdk/src/main/kotlin/com/androidailab/aisdk/internal/network/AnthropicMessagesRequest.kt` | `AnthropicMessage.content`를 `String` → `JsonElement`로 변경 (다중 블록 표현). `AnthropicContentRequestBlock` (sealed: Text / Image) + `AnthropicImageSource` 추가 |
| `sdk/src/main/kotlin/com/androidailab/aisdk/internal/network/Mapper.kt` | `toAnthropicRequest`를 suspend로 변경 + `httpClient` 파라미터 추가. 이미지 블록 빌드(`buildContentJson`), 매직 넘버 검증(`verifyMagicNumber`/`detectMimeFromMagic`), URL fetch(`fetchUrlBytes`), base64 인코딩 추가 |
| `sdk/src/main/kotlin/com/androidailab/aisdk/AiAgentClient.kt` | `ask()` 진입 직후 이미지 검증 흐름 추가(`validateImages`) — E-201/E-202/E-203/E-204/E-205 매핑. `IMAGES_TOTAL_MAX_BYTES`(M-001 합계 20MB) 상수 추가 |
| `sdk/src/main/kotlin/com/androidailab/aisdk/provider/claude/ClaudeProvider.kt` | `Mapper.toAnthropicRequest` 호출 시 `httpClient` 인자 추가 (URL fetch 위임) |
| `sdk/src/test/kotlin/com/androidailab/aisdk/internal/network/MapperTest.kt` | 시그니처 변경(suspend, content=JsonElement) 반영 — `runTest` 적용 + 블록 배열 형태 검증 |

---

## 2. 구현한 사양 ID 표

| ID | 사양 위치 | 구현 위치 | 비고 |
|----|----------|----------|------|
| F-002 | features.md | `AiAgentClient.ask` + `validateImages` + `Mapper.toAnthropicRequest` + `Mapper.buildContentJson` | 정상 흐름 1~5단계 모두 구현 |
| A-002 | api.md | `AiAgentClient.ask` (시그니처 불변) | 이미지 첨부 시 동일한 `ask` 진입 |
| M-001 | data-model.md | `AiRequest` (불변) + `AiAgentClient.IMAGES_TOTAL_MAX_BYTES` 상수 | "최대 10장" init + "합계 20MB" 검증 흐름 |
| M-003 | data-model.md | `ImageInput.kt` (Bytes init/Url init 보강) | R-016 equals/hashCode 유지 + 신규 검증 |
| R-016 | data-model.md M-003 | `ImageInput.Bytes.equals/hashCode` (`contentEquals` + `contentHashCode` + `mimeType`) | T9 라운드 도입, 본 라운드 검증 단위 테스트 추가 |
| R-021 | features.md F-007 / data-model.md M-011 | (이미지 영속화 가이드는 호출자 책임 — 본 라운드 코드 변경 없음) | F-007에서 적용 |
| R-023 | data-model.md M-011 / provider-spec.md "ImageInput.Url SSRF 방어 정책" | `ImageInput.Url.init` 의 https 스킴 강제 + `Mapper.fetchUrlBytes` 형식 검증만 수행 (SSRF 검사 없음) | SDK 형식 검증, 도메인 allowlist는 호출자 책임 |
| R-003 | (사양 명시 — provider-spec.md 보안 섹션 / E-206) | `Mapper.fetchUrlBytes` HTTP fetch 처리 | URL fetch 실패 시 E-206 매핑 (4xx → InvalidInput / 5xx·timeout·DNS → Network) |
| R-004 | (이미지 디코딩 책임 — E-207) | `Mapper.verifyMagicNumber` + `Mapper.detectMimeFromMagic` | JPEG/PNG/WebP/GIF 매직 넘버 검증 |
| E-201 | features.md F-002 | `AiAgentClient.validateImages` (단일 5MB → InvalidInput "image too large") | ERR-005 |
| E-202 | features.md F-002 | `AiAgentClient.validateImages` (합계 20MB / 개수 한계 → InvalidInput "images total too large") | ERR-005 |
| E-203 | features.md F-002 | `AiAgentClient.validateImages` (`ImageInput.Uri` 도달 → InvalidInput "uri unreadable") + `Mapper.buildContentJson` 안전망 | ERR-005, D-004 — SDK는 Uri 자동 resolve 안 함 |
| E-204 | features.md F-002 | `AiAgentClient.validateImages` (mimeType 화이트리스트) | ERR-005 |
| E-205 | features.md F-002 | `AiAgentClient.validateImages` (Capabilities.supportsImage=false → Configuration "provider does not support images") | ERR-004 |
| E-206 | features.md F-002 | `Mapper.fetchUrlBytes` (4xx → InvalidInput / 5xx·timeout·DNS → Network) | ERR-005/ERR-001 |
| E-207 | features.md F-002 | `Mapper.verifyMagicNumber` + `detectMimeFromMagic` (mismatched mime / unknown magic → InvalidInput "image decode failed") | ERR-005 |
| ERR-005 | error-handling.md | `AiException.InvalidInput` (E-201/E-202/E-203/E-204/E-206 URL invalid/E-207 매핑) | KDoc에 명시됨 |
| ERR-004 | error-handling.md | `AiException.Configuration` (E-205 매핑) | KDoc에 명시됨 |
| ERR-001 | error-handling.md | `AiException.Network` (E-206 5xx/timeout/DNS) | KDoc에 명시됨 |
| P-CLAUDE | provider-spec.md | `ClaudeProvider.MAX_IMAGE_SIZE_BYTES`/`MAX_IMAGES_PER_REQUEST`/`SUPPORTED_IMAGE_MIME_TYPES` (T9 라운드 도입, 본 라운드 활용) | Capabilities로 검증 |
| D-004 | overview.md | `AiAgentClient.validateImages` (자동 리사이즈 없음 — 호출자 책임) + `ImageInput.Uri` 자동 resolve 거부 | E-203 매핑 |

---

## 3. 단위 테스트 케이스 목록

### `ImageInputTest.kt` (10 케이스)

```
R-016 — 동일한 data + mimeType 의 Bytes 는 equals 가 true
R-016 — 동일 data 라도 mimeType 다르면 equals 가 false
R-016 — 동일 mimeType 라도 data 다르면 equals 가 false
R-016 — ByteArray reference 가 달라도 contentEquals 가 같으면 equals 가 true
R-016 — toString 은 mimeType 과 size 만 노출 (data 본문 노출 금지)
Bytes init — data 가 빈 배열이면 IllegalArgumentException
Bytes init — mimeType 이 빈 문자열이면 IllegalArgumentException
Url init — 정상 https URL 은 통과
Url init — 빈 문자열은 IllegalArgumentException
Url init — http (non-https) 스킴은 거부 (R-023 형식 검증)
Url init — file 스킴은 거부 (R-023 형식 검증)
```

### `AskWithImagesTest.kt` (8 케이스)

```
F-002 정상 흐름 — 이미지 1장 첨부 시 검증 통과 후 Provider 호출 (Result_success)
F-002 E-201 — 단일 이미지가 maxImageSizeBytes 초과 시 InvalidInput
F-002 E-202 — images 합계가 20MB 초과 시 InvalidInput
F-002 E-202 — Capabilities maxImagesPerRequest 초과 시 InvalidInput (Provider 한계 강제)
F-002 E-204 — 지원하지 않는 mimeType 시 InvalidInput
F-002 E-205 — Provider 가 supportsImage=false 면 Configuration("provider does not support images")
F-002 E-203 — ImageInput_Uri 도달 시 InvalidInput("uri unreadable")
F-002 회귀 — images 빈 리스트 시 검증은 즉시 통과 (F-001 텍스트 흐름 영향 없음)
```

각 케이스에서 검증 실패 시 `Provider.complete`가 호출되지 않음을 `completeCallCount`로 추가 검증(회귀 방지).

### `MapperImageTest.kt` (12 케이스)

```
F-001 회귀 — 텍스트만 있을 때 content 는 단일 text 블록 배열
F-002 — Bytes(PNG) 첨부 시 content 는 text + image 블록 (base64 round-trip)
F-002 — JPEG 매직 넘버 통과
F-002 — WebP 매직 넘버 통과
F-002 — GIF 매직 넘버 통과
F-002 E-207 — declared mime 와 실제 매직 넘버 불일치 시 InvalidInput
F-002 E-207 — 알 수 없는 매직 넘버 (random bytes) 면 InvalidInput
F-002 E-206 — fetchUrlBytes HTTP 404 → InvalidInput("image url unreachable")
F-002 E-206 — fetchUrlBytes HTTP 503 → AiException_Network
F-002 E-206 — fetchUrlBytes httpClient 가 null 이면 Configuration
F-002 — fetchUrlBytes 정상 응답은 응답 바이트 그대로 반환 (이후 매직 넘버 검증)
F-002 E-203 — ImageInput_Uri 도달 시 InvalidInput("uri unreadable") (Mapper 안전망)
```

URL fetch 테스트는 `MockWebServer` 사용. `ImageInput.Url`의 init이 https 스킴을 강제하므로(MockWebServer는 http) URL fetch 검증은 `Mapper.fetchUrlBytes`(internal) 직접 호출로 수행 (`ImageInput.Url` 인스턴스화는 형식 검증 단위 테스트에서 별도 수행).

### 합계
- 신규 단위 테스트: **30 케이스** (Image 10 + Ask images 8 + Mapper image 12)
- 기존 `MapperTest`도 시그니처 변경에 맞춰 2개 케이스를 업데이트(suspend + content=JsonElement 검증)

---

## 4. 사양 명확화 요청

### S-T12-1 [Minor] `ImageInput.Url`의 init 검증과 MockWebServer 테스트 호환성

- **현 사양**: provider-spec.md "URL 형식 검증 (`https://` 스킴)" / data-model.md M-011 "URL 문자열의 형식 검증"
- **현 구현**: `ImageInput.Url.init`이 `https://` 스킴을 강제 → 인스턴스화 자체에서 `IllegalArgumentException`
- **영향**: MockWebServer는 기본적으로 http 응답이라 `ImageInput.Url(server.url("/").toString())` 인스턴스화가 실패. 본 라운드는 `Mapper.fetchUrlBytes`(internal)을 직접 테스트하는 우회로 해결. 통합 테스트(end-to-end ImageInput.Url → Mapper)는 `MockWebServer.useHttps()` 또는 fakeHttp 도입이 필요.
- **권장**: 사양 변경 없음. 본 라운드 통과 처리. 추후 통합 테스트 인프라 도입 시 `MockWebServer.useHttps`로 본 패턴 보강.

### S-T12-2 [정보성] E-202의 의미 분리 (개수 한계 vs 합계 한계)

- **현 사양**: features.md F-002 E-202 "이미지 총 합계 20MB 초과" → InvalidInput("images total too large")
- **추가 케이스**: Provider별 `Capabilities.maxImagesPerRequest`가 M-001 init의 size <= 10보다 작은 경우, 합계 20MB 미만이라도 거부할 필요. 본 라운드 구현은 두 케이스(개수 / 바이트 합계) 모두 E-202 매핑 + `InvalidInput`로 통일.
- **권장**: 사양 보강 — E-202 "이미지 총 합계 20MB 초과 또는 Provider 한계(maxImagesPerRequest) 초과"로 명시. 본 라운드 코드 정합.

### S-T12-3 [Minor] `ImageInput.Uri`의 v0.1 처리 정책

- **현 사양**: features.md F-002 E-203 "Uri 읽기 실패 (권한·존재 X)" → `InvalidInput("uri unreadable")`
- **현 구현**: SDK는 `ImageInput.Uri`를 ContentResolver로 자동 해석하지 않고, ask 진입 시점에 즉시 `InvalidInput("uri unreadable")`로 거부 (D-004의 연장 — 호출자 책임).
- **이유**: ContentResolver 호출은 Android Context 의존성이 강하고 호출자 권한 정책(URI permission grant)에 종속되어, SDK가 자동 처리하면 호출자가 권한 부여를 잊었을 때 동작이 달라진다. 호출자가 ContentResolver로 ByteArray로 변환 후 `ImageInput.Bytes`로 전달하는 패턴 권장.
- **권장**: 사양 보강 — F-002 정상 흐름 3 또는 별도 섹션에 "v0.1: SDK는 `ImageInput.Uri`를 자동 resolve 하지 않음. 호출자가 ContentResolver로 Bytes 변환 후 전달"로 명시. v0.2에서 자동 resolve 옵션 검토.

(Q-T11-1 (E-107 발생 위치 명확화)는 직전 라운드 미해결이지만 본 라운드 영향 없음 — 그대로 이월.)

---

## 5. F-003 진입 가이드 (스트리밍 응답)

본 라운드(F-002)에서 추가된 변경 중 F-003에 영향:

1. **`Mapper.toAnthropicRequest` suspend 변경**: stream() 본체에서도 동일하게 호출하므로 Flow 내부에서 `flow { val req = Mapper.toAnthropicRequest(...); ... }` 형태로 감싼다 (suspend 함수는 Flow 빌더 내부에서 호출 가능).
2. **이미지 블록 변환은 stream에도 동일 적용**: `AnthropicMessagesRequest.messages[0].content`가 `JsonElement` 블록 배열이므로 stream에서도 그대로 사용 가능. SSE 응답은 텍스트 chunk만 받으므로 stream 본체는 텍스트 디코딩에만 집중.
3. **SSE 파서 위치**: `internal/network/AnthropicSseParser.kt` 신규 (impl_summary_4 §5 가이드 그대로).
4. **이미지 검증 흐름은 askStream 진입 직후에도 동일하게**: `AiAgentClient.askStream`에 `validateImages` 호출을 추가해야 함 (E-201/E-202/E-204/E-205). 본 라운드에서는 askStream 본체가 미구현이므로 그 라운드에서 추가.
5. **`ClaudeProvider.stream` 본체는 본 라운드 NotImplementedError 그대로 유지**. F-003에서 SSE 파서로 교체.

진입 시 체크리스트:
- [ ] `AiAgentClient.askStream` Flow 빌더 내부 첫 단계에 `if (isClosed()) emit(AiStreamEvent.Error(Configuration("client closed"))); return@flow` 추가 (R-020 케이스 B)
- [ ] `validateImages` 호출 (E-201/E-202/E-204/E-205) — 실패 시 `emit(Error(...))` 후 종료
- [ ] `ClaudeProvider.stream`에서 `Mapper.toAnthropicRequest(httpClient = client.httpClient)` 사용
- [ ] SSE 파서가 `Delta` 0+ → `Done | Error` 1 (M-006) 방출 순서 보장
- [ ] `currentCoroutineContext().isActive` 또는 `awaitClose { call.cancel() }`로 E-302 cooperative

---

## 6. F-004 진입 가이드 (세션 컨텍스트 유지)

본 라운드 변경의 F-004 영향:

1. **`Session.send`도 이미지 검증 + 매핑 사용**: F-004 send()는 history + 현재 request를 합쳐 Provider 전송하므로, 본 라운드 추가된 `validateImages` + `Mapper.toAnthropicRequest`(suspend) 패턴을 동일하게 적용.
2. **`Message.images`(M-008) → AnthropicMessage 변환**: 현재 `Mapper.toAnthropicRequest`는 단일 user 메시지만 만든다. F-004에서 history 다중 메시지 변환 함수가 필요 (`Mapper.toAnthropicRequestForSession(history, current, config, httpClient)` 등 별도 함수 또는 기존 함수 확장).
3. **`Message.images`도 ImageInput.Bytes/Url/Uri 처리 동일**: 매직 넘버 검증, base64 인코딩, URL fetch 모두 재사용.
4. **systemPrompt(R-008)는 Anthropic API의 top-level `system` 필드로 매핑**: `AnthropicMessagesRequest`에 `system: String? = null` 추가 + Mapper에서 `request.systemPrompt`(만약 Session이 보유) 또는 인자로 전달.
5. **Mutex로 동시 send 직렬화 (E-403)**: Session 내부에 `Mutex`를 두고 `mutex.withLock { ... }`. session.send도 R-007에 따라 진입 시점에 `client.activeProvider`를 atomic get (R-014).

진입 시 체크리스트:
- [ ] `Session` 클래스 (M-007 시그니처) 신규
- [ ] `AiAgentClient.createSession(systemPrompt)` 본체 (sessionId UUID 생성)
- [ ] `Session.send` 진입 시 `validateImages` 재사용 (Mutex 안에서)
- [ ] history 변환 — `Mapper`에 `toAnthropicRequestForSession` 또는 변환 함수 추가
- [ ] E-401 (컨텍스트 초과) — Anthropic 에러 응답에서 감지 → InvalidInput

---

## 7. sdk-qa-validator F-002 검증 요청

**sdk-qa-validator를 호출하여 F-002 (멀티모달 이미지 질의)의 사양-구현 정합성을 검증해 주세요. 본체는 `AiAgentClient.ask` + `validateImages` / `Mapper.toAnthropicRequest` + `buildContentJson` + `verifyMagicNumber` + `fetchUrlBytes` / `ImageInput.Bytes/Url init` / `ClaudeProvider.complete` 6개 위치이며, 단위 테스트는 `ImageInputTest.kt` (11) / `AskWithImagesTest.kt` (8) / `MapperImageTest.kt` (12) + `MapperTest.kt` 시그니처 업데이트입니다. F-002 사양 ID는 features.md F-002, api.md A-002 (시그니처 불변), data-model.md M-001/M-003, error-handling.md ERR-005/ERR-004/ERR-001, E-201~E-207, R-016, R-023 입니다.**
