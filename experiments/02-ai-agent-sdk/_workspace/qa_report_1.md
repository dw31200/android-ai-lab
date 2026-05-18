# QA Report 1 — F-000 (클라이언트 초기화) 점진 검증

검증자: sdk-qa-validator
검증 일시: 2026-05-07
대상: F-000 (라운드 1, T8) — implementer가 `_workspace/impl_summary_1.md`로 검증 요청한 범위 한정
검증 범위: 정상 흐름 / 예외 흐름 (E-001/E-002/E-003) / A-001 시그니처 / M-005 / M-010 / ERR-001~007 KDoc 매핑 / NFR(D-003, explicit-api strict) / 단위 테스트 커버리지
빌드 실행: 미수행 (실험 루트에 settings.gradle.kts/wrapper 부재 — implementer 메모 6번. 정적 검증/관찰 기반)

---

## 1. 요약

| Severity | 건수 | 종결 조건 |
|----------|------|----------|
| Blocker  | 0    | 0건 필수 |
| Major    | 1    | 처리 또는 명시적 유보 |
| Minor    | 2    | 다음 라운드 이월 가능 |
| 사양 명확화 요청 | 2 | spec-architect 회신 후 반영 |

**결과**: F-000 **조건부 통과**. Blocker 0건이므로 후속 진입(F-005/F-008) 자체는 가능하나, Major 1건(Q-001 — A-001 시그니처 형태 불일치)은 호출자 사용 예가 컴파일되지 않는다는 의미라 spec-architect의 사양 명확화 회신 후 반영하는 것이 권장된다(코드 또는 사양 중 하나가 정정되어야 함). Minor 2건은 후속 라운드에서 자연 해소된다.

---

## 2. 항목별 검증 표

### 2.1 F-000 정상 흐름 1~3

| 단계 | 사양 | 구현 위치 | 결과 |
|------|------|-----------|------|
| 1 | `AiAgentClient.Builder(context)` 생성 | `Builder.kt:24-29` (별도 패키지 `client.Builder`), `AiAgentClient.kt:48-49` (`companion.builder(context)`) | **불일치** (Q-001 참조) — 별도 패키지 `client.Builder`로 외부에 노출되어 사양의 `AiAgentClient.Builder(context)` 호출이 직접 컴파일되지 않음 |
| 2 | `.apiKey(...).provider(...).model(...).timeout(...)` 체이닝 | `Builder.kt:38-66` (4개 setter, 모두 `apply` 사용으로 동일 인스턴스 반환) | 통과 |
| 3 | `.build()` 검증 통과 시 `AiAgentClient` 반환 | `Builder.kt:78-107` | 통과 (E-001~E-003 검증 후 internal constructor로 인스턴스 생성) |

### 2.2 예외 흐름 (E-001 / E-002 / E-003)

| ID | 사양 조건 | 구현 위치 | AiException variant | 메시지 | 결과 |
|----|-----------|-----------|----------------------|--------|------|
| E-001 | API 키 미설정 | `Builder.kt:80-83` | `Configuration` | `"api key is required"` | 통과 (null + isBlank 모두 커버) |
| E-002 | 알 수 없는 Provider | `Builder.kt:89-91` | `Configuration` | `"unknown provider: $providerId"` | 통과 (단, v0.1 enum이 CLAUDE 단일이라 실제 throw 경로 도달 불가 — Q-002 Minor 참조) |
| E-003 | timeout < 1초 | `Builder.kt:94-98` | `Configuration` | `"timeout must be at least 1 second, got $timeout"` | 통과 (경계값: `1.seconds`는 통과, `999.ms`/`0.s`/음수는 거부) |

세 케이스 모두 ERR-004(Configuration)에 1:1 매핑되며, 메시지가 의미 있어 호출자가 디버깅 가능.

### 2.3 A-001 시그니처 토큰 단위 비교

api.md L33-39 시그니처 vs `Builder.kt`:

| 토큰 | 사양 | 구현 | 일치 |
|------|------|------|------|
| 클래스 형태 | `class AiAgentClient.Builder(context: Context)` (nested) | `package ...client; public class Builder internal constructor(context: Context)` (별도 패키지, internal constructor) | **불일치** (Q-001) |
| `apiKey(key: String): Builder` | 일치 | `public fun apiKey(key: String): Builder` (Builder.kt:38) | 통과 |
| `provider(provider: ProviderId): Builder` | 일치 | `public fun provider(provider: ProviderId): Builder` (Builder.kt:49) | 통과 |
| `model(modelId: String): Builder` | 일치 | `public fun model(modelId: String): Builder` (Builder.kt:56) | 통과 |
| `timeout(duration: Duration): Builder` | 일치 | `public fun timeout(duration: Duration): Builder` (Builder.kt:64) | 통과 |
| `build(): AiAgentClient` | 일치 | `public fun build(): AiAgentClient` (Builder.kt:78) | 통과 |
| suspend 여부 | 모두 비-suspend (응답 시간 즉시 NFR) | 모두 비-suspend | 통과 |
| nullable | 모든 인자 non-null | 모든 인자 non-null | 통과 |

5개 메서드 시그니처는 토큰 단위로 정확히 일치. **클래스 위치만 불일치**.

### 2.4 M-005 sealed class 골격

| Variant | 사양(data-model.md L141-163) | 구현(`AiException.kt`) | 결과 |
|---------|------------------------------|--------------------------|------|
| Network(cause: Throwable) | ERR-001 | `class Network(cause: Throwable) : AiException(cause = cause)` (L34) | 통과 |
| RateLimit(retryAfter: Duration?) | ERR-002 | `class RateLimit(public val retryAfter: Duration?) : AiException()` (L42) | 통과 (필드 노출됨) |
| Authentication() | ERR-003 | `class Authentication : AiException()` (L49) | 통과 |
| Configuration(message: String) | ERR-004 | `class Configuration(message: String) : AiException(message)` (L57) | 통과 |
| InvalidInput(message: String) | ERR-005 | `class InvalidInput(message: String) : AiException(message)` (L66) | 통과 |
| ServerError(code: Int, message: String?) | ERR-006 | `class ServerError(public val code: Int, message: String? = null) : AiException(message)` (L74-77) | 통과 (code 필드 노출) |
| IOError(message: String, cause: Throwable?) | ERR-007 | `class IOError(message: String, cause: Throwable? = null) : AiException(message, cause)` (L85-88) | 통과 |

7개 variant 모두 정의되었고, `AiExceptionTest.kt` L103-115의 sealed 계층 검증으로 컴파일 단위에서 누락 없음을 확인 가능.

### 2.5 M-010 ProviderId

| 항목 | 사양(data-model.md L263-274, R-009) | 구현(`ProviderId.kt`) | 결과 |
|------|--------------------------------------|------------------------|------|
| enum 값 | v0.1: CLAUDE 단일 | `CLAUDE("Anthropic Claude")` (L20) | 통과 |
| 미구현 Provider 미포함 정책(R-009) | 동작 미정의 위험 회피 | OPENAI/GEMINI 미정의 | 통과 |
| Capabilities 단일 source(R-010) | enum이 식별자만 보유 | `displayName: String` 만 노출 | 통과 |

### 2.6 ERR-001~ERR-007 KDoc 매핑

| ERR | 매핑 KDoc 위치 | 결과 |
|-----|----------------|------|
| ERR-001 | `AiException.kt:13`(클래스), `:29-33`(Network) | 통과 |
| ERR-002 | `:14`, `:36-41`(RateLimit) | 통과 |
| ERR-003 | `:15`, `:44-48`(Authentication) | 통과 |
| ERR-004 | `:16`, `:51-56`(Configuration, E-001~E-003 등 11개 E-XXX 명시) | 통과 |
| ERR-005 | `:17`, `:59-65`(InvalidInput) | 통과 |
| ERR-006 | `:18`, `:68-73`(ServerError) | 통과 |
| ERR-007 | `:19`, `:79-84`(IOError, "라운드 2 신규" 명시) | 통과 |

ERR-001~ERR-007 7개 모두 클래스 KDoc과 각 variant KDoc에 이중 매핑되어 있고, 각 variant가 어떤 E-XXX를 흡수하는지도 명시(예: Configuration KDoc에 `E-001 / E-002 / E-003 / E-109 / E-205 / E-303 / E-402 / E-501 / E-502 / E-602 / E-705`).

### 2.7 NFR

| NFR | 근거 | 결과 |
|-----|------|------|
| API 키 메모리 보관, 디스크/로그 노출 금지(D-003) | `AiAgentClient.kt:36`(internal val), `Builder.kt:30`(private var), 어떤 `toString()`/`KDoc`도 키 노출 없음 | 통과 |
| 응답 즉시 (네트워크 호출 없음) | `Builder.build()`는 검증 + 인스턴스 생성만, IO/suspend 없음 | 통과 |
| Builder 단일 스레드 가정 / client thread-safe | KDoc(`Builder.kt:19`, `AiAgentClient.kt:19-22`) 명시. 본 라운드 client는 read-only 필드만 보유하여 자연스럽게 thread-safe | 통과 |
| explicit-api=strict | `build.gradle.kts:48` `freeCompilerArgs = ... + "-Xexplicit-api=strict"`, 모든 public 선언에 `public` 명시(`AiAgentClient.kt:33,41,49`, `Builder.kt:24,38,49,56,64,78,109`, `AiException.kt:23,34,42,49,57,66,74,85`, `ProviderId.kt:19`) | 통과 |
| context는 applicationContext만 보관 | `Builder.kt:28` `context.applicationContext ?: context` | 통과 (Q-003 Minor 참조 — null fallback 정책) |
| 콜백 금지, suspend/Flow만 비동기 | 본 라운드는 동기 Builder만 — 적용 대상 없음 | N/A |

### 2.8 단위 테스트 검증

| 항목 | 결과 |
|------|------|
| `BuilderTest`가 E-001 커버 | 통과 (3개 케이스: 미설정/빈문자열/공백) |
| `BuilderTest`가 E-002 커버 | 부분 통과 (`F-000 E-002 — ... 화이트리스트는 v0_1 시점 CLAUDE 만 포함` — 회귀 검증으로만, throw 경로는 enum 단일값이라 실재 호출 불가. 의도가 코멘트(L146-152)에 명시되어 의도적 제약임) |
| `BuilderTest`가 E-003 커버 | 통과 (4개 케이스: 0초/999ms/1초 경계/음수) |
| `BuilderTest` 정상 흐름 커버 | 통과 (5개 케이스: 전체필드/기본값/체이닝 동일성/applicationContext 보관/companion 헬퍼) |
| `AiExceptionTest`가 7개 variant 모두 커버 | 통과 (Network/RateLimit×2/Authentication/Configuration/InvalidInput/ServerError×2/IOError×2 + sealed 계층 일괄 11 케이스) |
| 테스트 코드 컴파일 가능성(관찰) | 통과 (mockk/junit4 의존성은 `build.gradle.kts:78` 선언됨, kotlin.time.Duration import 정상, AiException variant import 정상) |

---

## 3. 발견된 이슈

### Q-001 [Major] A-001 사용 예의 `AiAgentClient.Builder(context)`가 컴파일되지 않음

- **위치**:
  - 사양: `spec/api.md:33` (`class AiAgentClient.Builder(context: Context)`), `spec/api.md:48` 사용 예 (`val client = AiAgentClient.Builder(context).apiKey(...)`)
  - 구현: `sdk/src/main/kotlin/com/androidailab/aisdk/AiAgentClient.kt:33-51` (Builder가 nested class가 아님), `sdk/src/main/kotlin/com/androidailab/aisdk/client/Builder.kt:24` (별도 패키지 `client`의 최상위 클래스, `internal constructor`)
- **사양 의도**: A-001 시그니처와 사용 예 모두 `AiAgentClient.Builder(context)` 형태(중첩 클래스 또는 동일 클래스의 nested)로 호출 가능해야 함.
- **현 구현 동작**:
  - `Builder` 클래스가 `com.androidailab.aisdk.client.Builder`이고 생성자가 `internal`이라 외부 호출자는 `Builder(context)`로 직접 생성 불가.
  - `AiAgentClient.Builder` 식 자체가 컴파일되지 않음(중첩이 아님).
  - 우회 경로: `AiAgentClient.builder(context)`(소문자, companion 헬퍼)만 호출 가능.
- **재현**: api.md L48의 사용 예를 호출자 모듈에 그대로 작성:
  ```kotlin
  val client = AiAgentClient.Builder(context)   // ✗ 컴파일 에러: Unresolved reference 'Builder'
      .apiKey(...)
  ```
  vs 현재 구현으로 컴파일 가능한 형태:
  ```kotlin
  val client = AiAgentClient.builder(context)   // ✓ companion 헬퍼
      .apiKey(...)
  ```
- **해결 방향(둘 중 택1, spec-architect/implementer 협의)**:
  - **(A) 사양 정정**: api.md A-001 시그니처를 `AiAgentClient.builder(context: Context): Builder` 정적 팩토리 형태로 변경하고 사용 예도 동기화. 현 구현 그대로 통과.
  - **(B) 구현 정정**: `Builder`를 `AiAgentClient`의 nested public class로 옮기거나(`class AiAgentClient { class Builder(context) ... }`), 별도 패키지 유지 시 typealias `AiAgentClient.Builder = client.Builder` 추가하고 생성자를 public으로. 사양 그대로 통과.
- **권장**: (A) 사양 정정. companion 헬퍼는 KDoc과 BuilderTest에 이미 둘 다 노출되어 있어 호환성 부담이 적고, A-001 사용 예의 `AiAgentClient.Builder(...)`를 `AiAgentClient.builder(...)`로 1자 변경만 필요.
- **Severity 사유**: Blocker가 아닌 이유는 (1) 의미적 동작은 동일(체이닝 → build로 같은 결과)이며, (2) BuilderTest에서 두 호출 형태(직접 `Builder(context)` 및 `AiAgentClient.builder(context)`)가 모두 검증되어 동작 일치는 확인됨. 다만 사양 사용 예가 호출자 코드로 그대로 옮겨가면 컴파일 실패하므로 호출자 영향이 있어 Major.

### Q-002 [Minor] E-002 throw 경로의 실재 도달 불가 (의도된 제약)

- **위치**: `sdk/src/main/kotlin/com/androidailab/aisdk/client/Builder.kt:89-91`, `sdk/src/test/kotlin/com/androidailab/aisdk/client/BuilderTest.kt:153-157`
- **현상**: v0.1에서 `ProviderId` enum이 `CLAUDE` 하나뿐이라 `providerId !in SUPPORTED_PROVIDERS` 분기는 컴파일된 enum 값으로는 도달 불가. 단위 테스트는 throw가 아니라 화이트리스트 집합(`Builder.SUPPORTED_PROVIDERS == setOf(CLAUDE)`)만 회귀 검증.
- **사양 의도**: features.md F-000 E-002와 R-009 정책 — "v0.1은 CLAUDE만, 후속 enum 확장 시 SDK가 아직 구현 못한 값에 대비". 따라서 본 라운드에서 throw 경로 자체에 도달할 수 없는 것은 사양과 정합.
- **권장**: 본 라운드 통과로 처리. F-005에서 `useProvider()`가 추가되거나 enum이 확장되는 시점에 즉시 throw 케이스 단위 테스트를 추가하도록 후속 라운드에 인계(`impl_summary_1.md` 5번 섹션이 이미 인계 명시).
- **Severity 사유**: 사양에 어긋나지 않으며, 회귀 방지 테스트(SUPPORTED_PROVIDERS 집합 검증)가 enum 확장 시 자동으로 깨지도록 설계되어 있음.

### Q-003 [Minor] `Builder.kt:28` `applicationContext` null fallback 미정의

- **위치**: `sdk/src/main/kotlin/com/androidailab/aisdk/client/Builder.kt:28`
- **현상**: `private val appContext: Context = context.applicationContext ?: context` — applicationContext가 null이면 원본 context를 보관(메모리 누수 위험 잔존).
- **사양 의도**: F-000 NFR L52-54 "API 키 메모리 보관 + 디스크/로그 노출 금지"만 명시, applicationContext null 처리는 미명시.
- **현실적 평가**:
  - 정상적인 Android 환경에서 `context.applicationContext`는 non-null. null 가능성은 mockup/test 환경에서만 실재.
  - 단위 테스트(`BuilderTest.kt:36-37`)도 `mockk` 환경에서 `applicationContext`를 명시 stub함.
- **권장**: 본 라운드 통과. F-006(Hilt) 진입 시 `@ApplicationContext`로 주입받게 되면 자연 해소되며, 별도 보강 불필요.

---

## 4. 사양 명확화 필요 항목 (spec-architect 회신 요청)

implementer가 `_workspace/impl_summary_1.md` 4번 섹션에서 "사양 모호함 발견 → 합리적 기본값으로 통과 + 본 보고서에 기록" 정책으로 진행한 두 항목을 **검증 측면에서도 사양 결손**으로 동의한다. 회신 권고:

| # | 항목 | 현 임의 결정 | 영향 | 권장 사양 정정 |
|---|------|--------------|------|----------------|
| S-001 | `Builder.model()` 미설정 시 기본값 | `"claude-opus-4-7"` (`Builder.kt:111`) | F-000 정상 흐름 "apiKey만 설정해도 build 성공" 케이스가 사양에 없는 기본 모델로 통과. 호출자가 의도치 않게 미래에 deprecate되는 모델을 받을 수 있음 | api.md A-001에 다음 중 택1 명시: (a) 기본값 명시 (예: `model(modelId: String = ProviderId.CLAUDE.defaultModel)` 형태), (b) `model()` 미설정을 `build()` 시점에 E-XXX로 거부, (c) 사양에 "Provider별 default model은 provider-spec.md 참조" 추가 |
| S-002 | `Builder.timeout()` 미설정 시 기본값 | `30.seconds` (`Builder.kt:114`) | api.md A-001 사용 예에 `30.seconds` 등장하지만 사양 정의 표에는 없음. F-000 NFR/feature 정의에도 default timeout 미언급 | api.md A-001 또는 features.md F-000 NFR에 "timeout 미설정 시 기본값 30초" 명시 |

**Q-001(A-001 시그니처 형태 — nested vs companion factory)**도 사양 정정 vs 구현 정정 중 어느 쪽인지 spec-architect 결정이 필요.

---

## 5. 종결 권고

### F-000 자체

**조건부 통과** — Blocker 0건. F-000 정상 흐름 1~3 모두 코드에서 차례로 동작 가능하고, E-001~E-003이 모두 `AiException.Configuration`로 정확히 변환되며, M-005 7개 variant + M-010 CLAUDE 단일 + ERR-001~007 KDoc 매핑 + explicit-api=strict + D-003 NFR 모두 충족.

남은 Major 1건(Q-001)은 사양-구현 양쪽 중 한 쪽 정정이 필요하며, 의미상 동작은 동일하므로 **F-005/F-008 진입을 막지는 않는다**. 단, F-006(Hilt) 또는 외부 호출자 사용 예가 등장하기 전에 정정되어야 한다(호출자 사용 예가 컴파일되지 않으면 SDK의 1차 가치 손상).

### 후속 라운드 진입 권고

| 라운드 | 의존성 | 진입 가능 여부 | 비고 |
|--------|--------|----------------|------|
| **F-005 (Provider 선택/교체)** | F-000의 `ProviderId`, `AiException.Configuration`(E-501/E-502 매핑) | **진입 가능** | impl_summary 5번 섹션 인계 정확. enum 확장 시 `Builder.SUPPORTED_PROVIDERS` 동기 갱신 + Q-002 단위 테스트 보강을 함께 진행 |
| **F-008 (close)** | F-000의 `AiAgentClient` 골격, `AiException.Configuration` | **진입 가능** | impl_summary 5번 섹션 인계 정확. R-020 케이스 A/B 시맨틱 구현 시 단위 테스트로 케이스 A=CancellationException, 케이스 B=Result.failure(Configuration) 모두 커버 필요 |
| **F-001 (텍스트 단발)** | F-005 선행 (Provider 인터페이스 필요) | F-005 후 진입 | OkHttp(D-001) 의존성은 이미 선언됨 |
| **F-006 (Hilt)** | F-001/F-005 선행 + Q-001 정정 권장 | 보류 | Hilt가 외부에 노출하는 시그니처가 Q-001과 직결되므로 정정 후 진입 권장 |

### 다음 액션 (오케스트레이터에게)

1. **spec-architect**: S-001/S-002 사양 명확화 + Q-001 정정 방향(사양 vs 구현) 결정 → api.md 패치
2. **android-implementer**: spec-architect 결정에 따라 (Q-001-A 시) `Builder.kt`/`AiAgentClient.kt` 그대로 유지 + KDoc 정합 / (Q-001-B 시) `Builder`를 nested로 이동 또는 typealias 추가
3. **F-005/F-008 진입**: Q-001 결정과 병렬로 진행 가능

---

## 6. 자체 체크리스트

- [x] 4쌍의 경계면 교차 비교 완료 (api.md ↔ Builder/AiAgentClient, data-model.md M-005/M-010 ↔ AiException/ProviderId, error-handling.md ERR ↔ AiException variant, features.md F-000 E-XXX ↔ BuilderTest)
- [x] 모든 F-000 E-XXX(E-001/E-002/E-003)가 단위 테스트로 커버됨 (E-002는 의도된 제약 — Q-002 참조)
- [x] api.md 시그니처를 토큰 단위로 비교 (5개 메서드 모두 일치, 클래스 위치만 Q-001)
- [x] AiException 7개 variant ↔ ERR-001~ERR-007 1:1 매핑 (KDoc 이중 명시 확인)
- [x] explicit-api=strict / D-003 NFR 검증
- [x] 모든 이슈에 (file:line) 근거 명시
- [x] 사양 모호함 발견 항목 별도 섹션(4번)으로 spec-architect 회신 요청
- [x] 코드/사양 직접 수정하지 않음
