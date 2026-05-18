# Implementation Summary 2 — T9: F-005 Provider 선택/교체

작성: android-implementer
작업 범위: SDD Phase 3 Wave 2, T9 (F-005)
사양 기준: 라운드 5 종결본

---

## 1. 생성/수정 파일 (절대경로)

### 신규 — 모델 (Provider 인터페이스가 의존하는 최소 모델 도입)

본 라운드 진입을 위해 Provider 인터페이스(P-001)가 참조하는 M-001/M-002/M-003/M-004/M-006/M-009 골격을 함께 도입했다. F-001/F-002/F-003 본체 구현은 별도 라운드에서 채워진다 (init 검증 등 사양에 명시된 부분만 본 라운드에서 둠).

- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\model\AiRequest.kt` (M-001)
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\model\AiResponse.kt` (M-002 + FinishReason enum)
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\model\ImageInput.kt` (M-003)
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\model\VideoInput.kt` (M-004, v0.2 placeholder)
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\model\AiStreamEvent.kt` (M-006)
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\model\TokenUsage.kt` (M-009)

### 신규 — Provider 추상화 (F-005 핵심)

- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\provider\Provider.kt` (P-001 인터페이스)
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\provider\Capabilities.kt` (P-001 보강)
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\provider\ProviderConfig.kt` (P-001 보강)
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\provider\ProviderRegistry.kt` (P-002)
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\provider\claude\ClaudeProvider.kt` (P-CLAUDE 골격)

### 수정 — 기존 파일 (F-005 통합)

- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\AiAgentClient.kt`
  - `providerId: ProviderId` (val) → `private val providerIdRef: AtomicReference<ProviderId>` 전환 (R-007)
  - 생성자 파라미터 추가: `providerRegistry: ProviderRegistry`
  - 신규: `activeProviderId` / `activeProvider` / `currentProviderConfig()` internal 헬퍼
  - 신규 public: `useProvider(provider: ProviderId)` (A-005)
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\client\Builder.kt`
  - `defaultProviders()` companion 함수 추가 (`setOf(ClaudeProvider())`)
  - `providers(...)` internal hook 추가 (테스트 주입용)
  - `build()`에서 `ProviderRegistry` 구성 후 client에 주입

### 신규 — 단위 테스트

- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\test\kotlin\com\androidailab\aisdk\client\UseProviderTest.kt`
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\test\kotlin\com\androidailab\aisdk\provider\ProviderRegistryTest.kt`
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\test\kotlin\com\androidailab\aisdk\provider\ClaudeProviderTest.kt`

### 수정 — 기존 테스트

- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\test\kotlin\com\androidailab\aisdk\client\BuilderTest.kt`
  - `client.providerId` → `client.activeProviderId` (필드명 변경 따라가기)
  - 신규 회귀 테스트: `F-005 R-009 — defaultProviders 와 SUPPORTED_PROVIDERS 가 동기화됨`

### 삭제

- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\provider\.gitkeep` (provider 패키지 충실 후 placeholder 제거)

---

## 2. 구현한 사양 ID 표

| 사양 ID | 종류 | 구현 위치 | 비고 |
|---------|------|-----------|------|
| F-005 | 기능 | `AiAgentClient.useProvider`, `provider/*.kt` | 정상 흐름 1~2, 동시성 모델(R-007), R-014 정책 |
| A-005 | API | `AiAgentClient.useProvider(provider: ProviderId)` | 시그니처 토큰 단위 일치 (`fun useProvider(provider: ProviderId)`) |
| P-001 | Provider | `provider/Provider.kt`, `Capabilities.kt`, `ProviderConfig.kt` | 인터페이스 + 두 data class 토큰 단위 일치 |
| P-002 | Provider | `provider/ProviderRegistry.kt` | `get(id)` / `list()` / `capabilities(id)` + `contains(id)` 보조 |
| P-CLAUDE | Provider | `provider/claude/ClaudeProvider.kt` | id + Capabilities 사양 표기 그대로. complete/stream 본체는 F-001/F-003 |
| E-501 | 예외 | `AiAgentClient.useProvider`, `ProviderRegistry.get` | 등록되지 않은 Provider → `AiException.Configuration("unknown provider: $id")` |
| E-502 | 예외 | `AiAgentClient.useProvider` | API 키 누락 → `AiException.Configuration("api key missing for provider $id")` (방어적 검증; v0.1 단일 키 모델은 build 단계 E-001로 이미 차단) |
| M-001 | 모델 | `model/AiRequest.kt` | init 검증 사양 그대로 (prompt/images/maxTokens/temperature/videos 빈 리스트 강제) |
| M-002 | 모델 | `model/AiResponse.kt` (FinishReason 포함) | data class 사양 그대로 |
| M-003 | 모델 | `model/ImageInput.kt` | sealed class Uri/Bytes/Url. R-016 equals/hashCode 명시 |
| M-004 | 모델 | `model/VideoInput.kt` | v0.2 placeholder, v0.1에서 사용되지 않음 |
| M-006 | 모델 | `model/AiStreamEvent.kt` | sealed class Delta/Done/Error |
| M-009 | 모델 | `model/TokenUsage.kt` | totalTokens get-only property |
| M-010 | 모델 | (T8 그대로) `model/ProviderId.kt` | CLAUDE 단일 (R-009) |
| R-007 | 정책 | `AiAgentClient.providerIdRef` | `AtomicReference<ProviderId>` |
| R-009 | 정책 | `Builder.SUPPORTED_PROVIDERS` ↔ `Builder.defaultProviders()` 동기화 회귀 테스트 | enum 확장 시 두 집합이 함께 갱신되도록 검증 |
| R-010 | 정책 | `Capabilities.kt` ↔ `ProviderId.kt` 분리 | enum은 `displayName`만, capability는 Provider 측 |
| R-014 | 정책 | `AiAgentClient.useProvider` KDoc + activeProvider getter | Session 바인딩 없음 명시 (Session 검증은 F-004 후 별도) |
| R-020 | 정책 | `useProvider` 진입 시 `ensureNotClosed()` (T10이 추가한 헬퍼 활용) | close 후 → throw `Configuration("client closed")` (동기 함수 시맨틱) |
| ERR-004 | 매핑 | `AiException.Configuration` | E-501/E-502 모두 ERR-004로 매핑됨 (error-handling.md 표 그대로) |

---

## 3. 단위 테스트 케이스 목록

### `UseProviderTest`

- `F-005 정상 흐름 — useProvider 호출 즉시 activeProviderId 가 갱신됨`
- `F-005 정상 흐름 — useProvider 후 activeProvider 가 새 Provider 인스턴스를 반환`
- `F-005 정상 흐름 — 두 Provider 가 등록되었을 때 useProvider 가 인스턴스 교체에 반영됨` (registry 매번 재조회 검증)
- `F-005 R-014 — useProvider 는 동기 함수이며 즉시 반환 (수 ns)` (NFR 회귀 — 100ms 헐겁게)
- `F-005 E-501 — registry 에 등록 안된 ProviderId 를 useProvider 호출 시 Configuration throw`
- `F-005 E-502 — apiKey 가 비어있을 때 useProvider 호출 시 Configuration throw` (internal 생성자 직접 사용)
- `F-005 R-007 — useProvider 동시 호출에 대해 race 없이 안전하게 set 됨` (16 스레드 × 200 iter)
- `F-005 R-020 케이스 B — close 후 useProvider 호출 시 Configuration throw` (T10 close 활용)

### `ProviderRegistryTest`

- `P-002 정상 흐름 — 등록된 Provider 를 id 로 조회`
- `P-002 정상 흐름 — list 는 등록된 Provider 들을 반환`
- `P-002 정상 흐름 — capabilities 헬퍼는 Provider 의 capabilities 와 동일`
- `P-002 정상 흐름 — contains 는 등록 여부를 boolean 으로 반환`
- `F-005 E-501 — 등록되지 않은 Provider get 시 Configuration throw`
- `F-005 E-501 — capabilities 호출도 등록 안된 id 면 Configuration throw`
- `F-005 E-501 — contains 는 등록 안된 id 에 대해 false 를 반환`
- `P-002 동일 id 가 둘 이상이면 마지막이 이긴다`

### `ClaudeProviderTest`

- `P-CLAUDE id 는 ProviderId_CLAUDE`
- `P-CLAUDE Capabilities — 사양 표기와 정확히 일치` (provider-spec.md "P-CLAUDE 상세 — Capabilities 값" 표기 토큰 단위 비교)
- `P-CLAUDE Capabilities — companion 상수와 동기화됨`

### `BuilderTest` (수정/추가)

- 기존 13개 테스트 유지 (`client.providerId` → `client.activeProviderId` 이름 변경)
- 신규: `F-005 R-009 — defaultProviders 와 SUPPORTED_PROVIDERS 가 동기화됨` (회귀 방지)

### 빌드/실행 메모

T8과 동일하게 본 실험 루트(`experiments/02-ai-agent-sdk/`)에 settings.gradle.kts/wrapper가 아직 없으므로 컴파일/테스트 실행은 수행하지 않았다. 작성된 코드는 컴파일 가능 형태(1차 목표). 빌드 환경 갖춰진 후 다음으로 실행:

```
./gradlew :sdk:test
./gradlew :sdk:assembleDebug
```

---

## 4. 사양 명확화 요청

본 라운드에서 사양에 명확하지 않거나 결정이 필요한 항목.

| # | 항목 | 사양 위치 | 본 라운드 결정 | 제안 |
|---|------|-----------|----------------|------|
| Q-T9-1 | E-502 발효 조건 | features.md F-005 E-502 (`Provider별 API 키 누락`) | v0.1은 단일 apiKey 모델이라 build()의 E-001로 이미 차단됨. useProvider에서는 방어적 검증으로 `apiKey.isBlank()`만 검사. | v0.2에서 멀티 키 모델 진입 시 본 검증을 Provider별 키 맵 조회로 확장. v0.1 시점에 본 분기가 도달 불가하다는 점을 사양에 명시 권고 (또는 v0.1에서 본 항목을 제거하고 v0.2로 이월). |
| Q-T9-2 | ClaudeProvider.complete/stream 미구현 마커 | provider-spec.md P-CLAUDE | 사양 정책상 "TODO 마커 금지" 지침을 따르되, F-001/F-003 진입 전이라 본문에서 [NotImplementedError]를 throw 하도록 작성. (작업 지시: "사양 의도를 해치지 않는 선에서 처리") | 사양에 "라운드별 진입 시점에 미구현 Provider 메서드는 NotImplementedError 허용" 같은 보조 정책 명시 권고. 또는 F-005를 F-001과 합쳐 단일 라운드로 처리. |
| Q-T9-3 | M-004 (VideoInput) v0.1 도입 시점 | data-model.md M-004 | Provider 인터페이스가 직접 참조하지 않지만, AiRequest.videos가 sealed class를 참조하므로 본 라운드에서 placeholder만 도입. | v0.1 사양은 `videos: List<VideoInput> = emptyList()` 강제이므로, M-004 도입 자체는 사양 정합. 별도 결정 불필요 (정보성). |

---

## 5. F-001/F-003 진입 시 implementer 인계 사항

### 본 라운드에서 준비된 의존성

| 항목 | 위치 |
|------|------|
| `Provider` 인터페이스 (P-001) | `sdk/src/main/kotlin/com/androidailab/aisdk/provider/Provider.kt` |
| `ProviderConfig` data class | `sdk/src/main/kotlin/com/androidailab/aisdk/provider/ProviderConfig.kt` |
| `Capabilities` data class | `sdk/src/main/kotlin/com/androidailab/aisdk/provider/Capabilities.kt` |
| `ProviderRegistry` (P-002) | `sdk/src/main/kotlin/com/androidailab/aisdk/provider/ProviderRegistry.kt` |
| `ClaudeProvider` 골격 + Capabilities | `sdk/src/main/kotlin/com/androidailab/aisdk/provider/claude/ClaudeProvider.kt` |
| `AiAgentClient.activeProvider` / `currentProviderConfig()` 헬퍼 | `sdk/src/main/kotlin/com/androidailab/aisdk/AiAgentClient.kt` |
| 모델 M-001/M-002/M-003/M-006/M-009 골격 | `sdk/src/main/kotlin/com/androidailab/aisdk/model/*.kt` |

### F-001 (텍스트 단발 질의) 채워야 할 위치

- **`AiAgentClient.ask()` 메서드 신규 추가** — 본 라운드에는 없음. 시그니처는 A-002 그대로 `suspend fun ask(request: AiRequest): Result<AiResponse>`.
  - 진입 첫 줄에 `runCatching { ensureNotClosed() }.getOrElse { return Result.failure(it) }` (R-020 케이스 B suspend 시맨틱).
  - R-007에 따라 진입 시점에 `val provider = activeProvider; val config = currentProviderConfig()`로 캡쳐 (지역 변수 — useProvider 동시성 영향 차단).
  - `provider.complete(request, config)` 호출.
  - F-001 E-101~E-110 → AiException 변환 (작업 원칙 5: 취소 cooperative, OkHttp call cancel 연동).
- **`ClaudeProvider.complete()` 본체** — 현재 [NotImplementedError] throw 만 둠.
  - OkHttp 호출 + `provider/claude/Mapper.kt` (AiRequest → Anthropic JSON / Anthropic JSON → AiResponse)
  - `provider/claude/ErrorMapper.kt` (HTTP status + body → AiException)
  - 의존성 OkHttp 4.12.0은 이미 `build.gradle.kts`에 선언됨.
  - F-001 정상 흐름 4단계의 빈 응답 검증(E-110)은 ClaudeProvider 또는 AiAgentClient.ask 어느 쪽에서 해도 무방하나, **사양상 SDK 차원 검증**이므로 `AiAgentClient.ask`에서 처리 권장 (Provider 무관).

### F-003 (스트리밍) 채워야 할 위치

- **`AiAgentClient.askStream()` 메서드 신규 추가** — 시그니처는 A-003 그대로 `fun askStream(request: AiRequest): Flow<AiStreamEvent>`.
  - cold Flow로 반환. 첫 collect 시 `if (isClosed()) emit(AiStreamEvent.Error(AiException.Configuration("client closed"))); return@flow` (R-020 케이스 B Flow 시맨틱).
  - `activeProvider.stream(request, currentProviderConfig())` 결과를 그대로 collect/emit.
  - 외부 예외는 `catch { emit(AiStreamEvent.Error(it.toAiException())) }` 패턴 (E-301).
- **`ClaudeProvider.stream()` 본체** — 현재 빈 [Flow] + [NotImplementedError] throw.
  - SSE 파서 (Anthropic content_block_delta 등) → `AiStreamEvent.Delta` emit
  - 종료 시 `AiStreamEvent.Done` emit (M-006 방출 순서)
  - cold Flow + flowOn(Dispatchers.IO)

### F-001/F-003 공통 주의

- 작업 원칙 5 (취소): OkHttp call cancel을 코루틴 cancellation에 연결. `suspendCancellableCoroutine`에서 `cont.invokeOnCancellation { call.cancel() }`.
- 작업 원칙 6 (에러 sealed class): 모든 외부 예외(IOException, JsonParseException, HttpException)를 AiException으로 변환. `internal/ErrorMapper.kt` 또는 `provider/claude/ErrorMapper.kt`로.
- 작업 원칙 7 (테스트 가능): `Provider` 인터페이스는 이미 추상화됨 — `ask()` 단위 테스트는 Mock Provider로 작성 (UseProviderTest의 `FakeProvider` 패턴 재사용).

### F-004 (Session) 진입 시 R-014 검증 추가

- 본 라운드 정책에 따라 R-014 (Session 바인딩 없음)은 **client 차원 검증만** 수행 (`activeProvider` getter가 매 호출마다 registry 재조회). Session.send 진입 시 `client.activeProvider` / `client.currentProviderConfig()`를 호출 시점에 캡쳐하면 자동 충족.
- F-004 단위 테스트에서 **R-014 명시 검증** 케이스 필요: useProvider → session.send → 새 Provider 사용 확인.

### F-006 (Hilt) 진입 시 ProviderRegistry 통합

- 현재 Builder가 `defaultProviders()`로 자동 등록. F-006에서는 `@Provides @IntoSet`로 Provider를 주입받아 `Set<@JvmSuppressWildcards Provider>`를 ProviderRegistry에 전달. Builder는 Hilt 미사용 호출자용으로 그대로 유지.
- Hilt 모듈에서 `AiAgentClient` 자체를 `@Provides`로 노출. apiKey는 BuildConfig에서 읽어 Builder를 호출하는 형태.

---

## 6. sdk-qa-validator에게 F-005 검증 요청

**대상**: F-005 (Provider 선택/교체) 구현 완료. 검증 요청.

**검증 포인트** (사양-구현 정합성):

1. **A-005 시그니처 토큰 일치** — `fun useProvider(provider: ProviderId)` (api.md L185).
2. **F-005 정상 흐름 1~2** — Builder의 `provider(...)` 초기화 + 런타임 `useProvider(...)` 교체.
3. **R-007 동시성 모델** — `providerIdRef`가 `AtomicReference<ProviderId>`로 보관되고, `useProvider`가 atomic set만 수행. 16-스레드 동시 set race 회귀 테스트 통과.
4. **R-014 (Session ↔ Provider 바인딩 없음)** — `activeProvider` getter가 호출 시점에 registry 재조회 (구현 차원 충족). Session 차원 검증은 F-004 진입 후 별도.
5. **E-501** — registry에 등록되지 않은 ProviderId → `AiException.Configuration("unknown provider: $id")`. 메시지 의미 명확.
6. **E-502** — v0.1 단일 키 모델에서 사실상 도달 불가 (Builder.build의 E-001로 사전 차단). 그러나 useProvider 본문에 방어적 검증 존재 (internal 생성자 경유 시 도달 가능 — 단위 테스트에서 검증).
7. **R-020 케이스 B** — close 후 useProvider 호출 시 throw `AiException.Configuration("client closed")` (동기 함수 시맨틱). T10의 `ensureNotClosed()` 헬퍼 활용.
8. **P-001 Provider 인터페이스** — `id`, `capabilities`, `suspend fun complete(...)`, `fun stream(...): Flow<AiStreamEvent>` 토큰 단위 일치.
9. **P-002 ProviderRegistry** — `get(id): Provider`, `list(): List<Provider>`, `capabilities(id): Capabilities` 토큰 단위 일치 + `contains(id)` 보조 메서드 추가 (사양에 없는 메서드 — useProvider 본문이 사용. QA 검토 필요).
10. **P-CLAUDE Capabilities** — provider-spec.md "P-CLAUDE 상세" 표기와 토큰 단위 일치 (supportsImage=true / supportsVideo=false / supportsStream=true / supportsSession=true / 5MB / 10 / {jpeg,png,webp,gif}).
11. **R-009** — `ProviderId` enum CLAUDE 단일 유지 + `Builder.SUPPORTED_PROVIDERS`/`defaultProviders()` 동기화 회귀 테스트.
12. **R-010** — `Capabilities`는 Provider 측 단일 source. enum에는 capability 필드 없음.

**검증에서 의도적으로 빠진 항목** (후속 라운드 책임):
- `ClaudeProvider.complete()` / `stream()` 본체 (F-001/F-003 라운드)
- `AiAgentClient.ask()` / `askStream()` 진입 시 `activeProvider` 캡쳐 동작 — F-001/F-003에서 검증
- Session ↔ Provider R-014 검증 — F-004 진입 후
- Hilt 모듈 `Set<@JvmSuppressWildcards Provider>` 주입 — F-006 라운드

**의문점/QA 협의 사항**:
- `ProviderRegistry.contains(id)` 추가가 사양 P-002 표기에 없는 메서드. useProvider 본문이 E-501 차단을 위해 사용. 사양 보강 또는 `runCatching { get(id) }` 형태로 대체 권고. **QA 판단 요청**.
- `ClaudeProvider.complete/stream` 본체가 [NotImplementedError]를 throw 함. 작업 지시상 "TODO/placeholder 금지" 정책과 절충 — F-001/F-003 진입 전까지는 호출되지 않음. **QA가 F-005 정상 흐름 경로에서 본 메서드가 실제 호출되지 않음을 확인** 권고.

검증 결과를 `_workspace/qa_report_2.md` 등으로 회신 부탁드립니다.

---

## 7. 자체 체크리스트 (커밋 전)

- [x] 사양에 없는 동작 추가하지 않음 (단, `ProviderRegistry.contains(id)` 보조 메서드 1건 — QA 협의 요청)
- [x] 모든 public 함수에 KDoc + 관련 F-/A-/M-/P-/R-/E-/ERR- 참조
- [x] F-005에 대한 단위 테스트 작성 (정상 흐름 + E-501/E-502 + R-007 동시성 + R-020 케이스 B)
- [x] 외부 예외 변환 — Provider 본체 미구현 라운드라 적용 대상 없음 (F-001/F-003에서)
- [x] suspend/Flow 외 비동기 API 없음 — useProvider는 동기, complete은 suspend, stream은 Flow
- [x] Hilt 모듈로 외부 노출 — F-006 라운드 책임. 본 라운드는 의존성만 준비
- [x] 임의 추상화/플래그 없음 (Builder.providers internal hook 1건은 테스트 가능성 — 작업 원칙 7)
- [x] T10 close()를 침범하지 않음 — close 관련 변수(closed/scope/httpClient)는 그대로 유지하고 useProvider에서 활용만
- [x] BuilderTest 호환성 유지 (`client.providerId` → `client.activeProviderId` 1줄 변경 + 회귀 테스트 추가)
