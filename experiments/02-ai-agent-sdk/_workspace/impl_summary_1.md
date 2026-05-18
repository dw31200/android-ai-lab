# Implementation Summary 1 — T8: F-000 + sdk/ 모듈 골격

작성: android-implementer
작업 범위: SDD Phase 3 Wave 1, T8

---

## 1. 생성한 파일 (절대경로)

### 모듈 빌드 / Manifest
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\build.gradle.kts`
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\AndroidManifest.xml`
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\consumer-rules.pro`
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\proguard-rules.pro`

### main 소스 (구현)
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\AiAgentClient.kt`
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\client\Builder.kt`
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\model\ProviderId.kt`
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\model\AiException.kt`

### main 소스 (후속 라운드 placeholder)
- `...\sdk\src\main\kotlin\com\androidailab\aisdk\provider\.gitkeep`  ← F-005에서 채움
- `...\sdk\src\main\kotlin\com\androidailab\aisdk\session\.gitkeep`   ← F-004에서 채움
- `...\sdk\src\main\kotlin\com\androidailab\aisdk\internal\.gitkeep`  ← F-001/F-007에서 채움
- `...\sdk\src\main\kotlin\com\androidailab\aisdk\di\.gitkeep`        ← F-006에서 채움

### 단위 테스트
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\test\kotlin\com\androidailab\aisdk\client\BuilderTest.kt`
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\test\kotlin\com\androidailab\aisdk\model\AiExceptionTest.kt`

---

## 2. 구현한 사양 ID

| 사양 ID | 종류 | 구현 위치 | 비고 |
|---------|------|-----------|------|
| F-000 | 기능 | `AiAgentClient.kt`, `client/Builder.kt` | 정상 흐름 1~3 모두 구현 |
| A-001 | API | `client/Builder.kt` | apiKey/provider/model/timeout 체이닝 + build() |
| A-002(부분) | API | `AiAgentClient.kt` | 클래스 + companion `builder(context)` 헬퍼만. `ask()` 본체는 F-001에서 |
| E-001 | 예외 | `Builder.build()` | apiKey null/blank → `AiException.Configuration("api key is required")` |
| E-002 | 예외 | `Builder.build()` | 화이트리스트 미포함 → `AiException.Configuration("unknown provider: ...")` |
| E-003 | 예외 | `Builder.build()` | timeout < 1초 → `AiException.Configuration("timeout must be at least 1 second, got ...")` |
| M-005 | 모델 | `model/AiException.kt` | sealed class 7개 variant 모두 정의 (Network/RateLimit/Authentication/Configuration/InvalidInput/ServerError/IOError) |
| M-010 | 모델 | `model/ProviderId.kt` | v0.1 R-009 정책에 따라 CLAUDE 만 |
| ERR-001~ERR-007 | 에러 | `model/AiException.kt` KDoc | 매핑 명세를 KDoc에 명시 |
| D-001 | 결정 | `build.gradle.kts` | OkHttp 4.12.0 의존성 선언 (실제 사용은 F-001) |
| D-002 | 결정 | `build.gradle.kts` | androidx.datastore-preferences 1.1.1 의존성 선언 (F-007) |
| D-003 | 결정 | `AiAgentClient` KDoc / `Builder` KDoc | "메모리에서만 보관, 디스크/로그 노출 금지" 명시 |

---

## 3. 단위 테스트 케이스 목록

### `BuilderTest`
- `F-000 정상 흐름 — 모든 필드 설정 시 client 인스턴스 반환`
- `F-000 정상 흐름 — apiKey 만 설정해도 기본값으로 build 성공`
- `F-000 정상 흐름 — Builder 체이닝은 같은 인스턴스 반환`
- `F-000 정상 흐름 — applicationContext 가 보관됨 (메모리 누수 방지)`
- `F-000 정상 흐름 — companion builder 헬퍼로 동일하게 build 가능`
- `F-000 E-001 — apiKey 미설정 시 Configuration throw`
- `F-000 E-001 — apiKey 빈 문자열이면 Configuration throw`
- `F-000 E-001 — apiKey 공백 문자열이면 Configuration throw`
- `F-000 E-002 — 지원되는 Provider 화이트리스트는 v0_1 시점 CLAUDE 만 포함` (회귀 방지)
- `F-000 E-003 — timeout 0초이면 Configuration throw`
- `F-000 E-003 — timeout 999ms이면 Configuration throw (경계값 직전)`
- `F-000 E-003 — timeout 정확히 1초이면 build 성공 (경계값)`
- `F-000 E-003 — timeout 음수이면 Configuration throw`

### `AiExceptionTest`
- `Network 는 cause 를 보관한다` (ERR-001)
- `RateLimit 는 retryAfter 를 보관한다 (값 있음)` (ERR-002)
- `RateLimit 의 retryAfter 는 null 가능`
- `Authentication 는 메시지 없이 생성 가능` (ERR-003)
- `Configuration 은 message 를 보관한다` (ERR-004)
- `InvalidInput 은 message 를 보관한다` (ERR-005)
- `ServerError 는 code 와 message 를 보관한다` (ERR-006)
- `ServerError 의 message 는 null 가능`
- `IOError 는 message 와 cause 를 보관한다` (ERR-007)
- `IOError 의 cause 는 null 가능`
- `AiException 의 모든 variant 는 Exception 하위 타입`

### 빌드/실행 명령어
프로젝트 루트(`experiments/02-ai-agent-sdk/`)에 settings.gradle.kts/루트 build.gradle.kts/gradle wrapper가 아직 없으므로 본 라운드에서는 **컴파일/테스트 실행을 하지 않았다** (작업 지시 정책: "빌드 환경 미보장. 컴파일 가능한 형태로 작성하는 것이 1차 목표").

빌드 환경이 갖춰진 후 다음 명령으로 실행 가능:
```
./gradlew :sdk:test
./gradlew :sdk:assembleDebug
```

---

## 4. 사양 명확화 요청

본 라운드에서 사양에 없는 두 가지 작은 항목에 대해 합리적 기본값을 사용했다. 향후 spec-architect 검토 필요.

| 항목 | 사양 위치 | 본 라운드 결정 | 제안 |
|------|-----------|----------------|------|
| `Builder.model()` 미설정 시 기본 모델 ID | api.md A-001 | `"claude-opus-4-7"` (provider-spec.md P-CLAUDE에 등장하는 모델 중 첫 번째) | api.md에 기본값 명시하거나 미설정 시 build()에서 거부할지 결정. 현재는 합리적 기본값으로 통과시킴. |
| `Builder.timeout()` 미설정 시 기본 타임아웃 | api.md A-001 사용 예에는 30초 | `30.seconds` (사용 예와 동일) | 사양에 기본값 명시 권장. |

이 두 결정은 **사양 모호함 발견 시 합리적 기본값으로 구현 + 본 섹션에 기록** 정책을 따랐다. 사양에 명시되면 즉시 반영한다.

---

## 5. 다음 단계 (F-005, F-008 의존성 정리)

본 라운드에서 후속 작업이 사용할 수 있도록 다음을 준비했다.

### F-005 (Provider 선택/교체) 진입 가능 — `provider/` 패키지 빈 상태
- **재사용 가능**: `model.ProviderId` (현 CLAUDE 단일 값), `model.AiException.Configuration` (E-501/E-502 매핑)
- **F-005가 추가할 것**:
  - `provider/Provider.kt` 인터페이스 (P-001)
  - `provider/Capabilities.kt`, `provider/ProviderConfig.kt` (data-model 보강)
  - `provider/ProviderRegistry.kt` (P-002)
  - `provider/claude/ClaudeProvider.kt` (P-CLAUDE)
  - `Builder.SUPPORTED_PROVIDERS` 화이트리스트는 enum 확장 시점에 동기 갱신 필요 (회귀 테스트 `F-000 E-002` 참조)
- **F-005가 사용할 의존성**: `AiAgentClient.providerId/apiKey/modelId/timeout` 모두 internal로 노출 중 — `useProvider()` 추가 시 `providerId`를 `AtomicReference`로 전환 필요(현재는 val).

### F-008 (close) 진입 가능 — `AiAgentClient.kt` 골격에 추가만 하면 됨
- **준비 상태**: `AiAgentClient` 클래스만 존재. `close()` 메서드는 미정의 → F-008에서 추가.
- **F-008이 추가할 것**:
  - `close()` 메서드 (R-020 케이스 A/B 시맨틱)
  - 내부 `CoroutineScope` (`SupervisorJob` 기반) — F-001에서 먼저 도입될 수도 있음
  - 닫힘 상태 플래그 (`AtomicBoolean`)
  - close 후 호출 시 `AiException.Configuration("client closed")` 반환/throw
- **재사용 가능**: `AiException.Configuration` (E-109/E-402/E-705 매핑)

### F-001 (텍스트 단발 질의) — F-005 선행 필요
- F-005의 Provider 인터페이스가 먼저 있어야 `ask(request)`가 실제 호출을 위임할 수 있다.
- 본 라운드에서 OkHttp/Coroutines/Serialization 의존성은 이미 build.gradle.kts에 선언되어 있다.

### F-006 (Hilt 모듈) — F-001/F-005 선행 필요
- 본 라운드에서 Hilt 의존성(2.51.1)은 build.gradle.kts에 선언만 하고 plugin도 등록함. KSP 컴파일러 등록도 완료.
- F-006에서 `di/AiSdkModule.kt`만 추가하면 됨.

### 후속 모델 추가 필요
- **F-001 진입 시**: `model/AiRequest.kt`(M-001), `model/AiResponse.kt`(M-002), `model/TokenUsage.kt`(M-009), `model/FinishReason` enum
- **F-002 진입 시**: `model/ImageInput.kt`(M-003)
- **F-003 진입 시**: `model/AiStreamEvent.kt`(M-006)
- **F-004 진입 시**: `model/Message.kt`(M-008), `session/Session.kt`(M-007)
- **F-007 진입 시**: `internal/SessionEntity.kt`(M-011), `internal/SessionStore.kt`

---

## 6. 루트 빌드 설정 메모

**중요**: 본 실험 루트(`experiments/02-ai-agent-sdk/`)에는 `settings.gradle.kts`, 루트 `build.gradle.kts`, `gradle/wrapper/` 등 Gradle 멀티프로젝트 구성이 **아직 없다**.

작업 정책 ("android-ai-lab 루트 프로젝트 빌드 설정을 임의로 변경하지 마세요")에 따라 본 라운드에서는 sdk 모듈 자체 파일만 생성했다. 다음 중 한 시점에 오케스트레이터 또는 사용자가 다음을 추가해야 sdk 모듈을 빌드할 수 있다:

1. `experiments/02-ai-agent-sdk/settings.gradle.kts` — `include(":sdk")`
2. `experiments/02-ai-agent-sdk/build.gradle.kts` — 루트 plugins 선언 (AGP, Kotlin, Hilt, KSP, kotlinx-serialization)
3. `experiments/02-ai-agent-sdk/gradle.properties`, `gradle/wrapper/...`

이 세팅은 다른 실험에 영향을 주지 않는 "실험 로컬" 설정이며, 본 실험에 한정된다.

---

## 7. sdk-qa-validator에게 검증 요청

**대상**: F-000 (클라이언트 초기화) 구현 완료. 검증 요청.

**검증 포인트** (사양-구현 정합성):
1. **F-000 정상 흐름 1~3** — `AiAgentClient.builder(context).apiKey(...).provider(...).model(...).timeout(...).build()` 체인이 사양 그대로 동작하는가?
2. **A-001 시그니처 토큰 일치** — `Builder(context: Context)`, `apiKey(key: String): Builder`, `provider(provider: ProviderId): Builder`, `model(modelId: String): Builder`, `timeout(duration: Duration): Builder`, `build(): AiAgentClient` 가 모두 일치하는가?
3. **E-001/E-002/E-003** — 예외 흐름이 모두 `AiException.Configuration`으로 변환되는가? 메시지가 의미 있는가?
4. **M-005 sealed class 골격** — 7개 variant가 모두 정의되어 있고 각 variant의 추가 필드(`retryAfter`, `code`, `cause`)가 사양과 일치하는가?
5. **M-010 ProviderId** — v0.1 시점 R-009 정책 (CLAUDE 단일)을 따르는가?
6. **ERR-XXX 매핑** — `AiException` 각 variant의 KDoc에 ERR-001~ERR-007 매핑이 명시되어 있는가? (현재는 클래스 KDoc + 각 variant KDoc에 모두 명시)
7. **NFR**:
   - thread-safe Builder는 단일 스레드 사용 가정 (사양 그대로) ✓
   - API 키 메모리 보관 (D-003) — `AiAgentClient.apiKey`는 internal val로만 노출, 어떤 toString도 키를 출력하지 않음 ✓
   - 응답 즉시 (네트워크 호출 없음) ✓
8. **explicit-api=strict** — 모든 public 선언이 명시적으로 `public` 마크 ✓

**검증에서 의도적으로 빠진 항목** (후속 라운드 책임):
- ask/askStream/createSession/useProvider/loadSession/deleteSession/close (F-001/F-003/F-004/F-005/F-007/F-008)
- Provider 인터페이스 / ProviderRegistry (F-005)
- Hilt 모듈 (F-006)
- 모델 클래스 M-001/M-002/M-003/M-006/M-007/M-008/M-009/M-011 (해당 F 라운드)

검증 결과를 `_workspace/qa_report_1.md` 등으로 회신 부탁드립니다.

---

## 8. 자체 체크리스트 (커밋 전)

- [x] 사양에 없는 동작을 추가하지 않음 (단, 4번 섹션에 사양 모호함 2건 기록)
- [x] 모든 public 함수에 KDoc + 관련 F-/A-/M-/ERR- 참조
- [x] F-000에 대한 단위 테스트 작성 (정상 + E-001/E-002/E-003 모두)
- [x] 외부 예외 변환 — 본 라운드는 외부 예외 발생 없음, sealed class 정의만
- [x] suspend/Flow 외 비동기 API 없음 — 본 라운드는 동기 Builder만
- [x] Hilt 모듈로 외부 노출 — 본 라운드는 의존성 선언까지, 모듈은 F-006
- [x] 임의 추상화/플래그 없음
