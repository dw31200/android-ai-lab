# Implementation Summary 3 — T10: F-008 (클라이언트 라이프사이클 close)

작성: android-implementer
작업 범위: SDD Phase 3 Wave 1, T10 (F-008)
선행 라운드: T8 (impl_summary_1.md, F-000 완료)
병렬 라운드: T9 (F-005 useProvider — 같은 `AiAgentClient.kt` 수정 중)

---

## 1. 생성/수정한 파일 (절대경로)

### 수정 (1건)
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\main\kotlin\com\androidailab\aisdk\AiAgentClient.kt`
  - import 추가: `AiException`, `kotlinx.coroutines.*`, `okhttp3.OkHttpClient`, `java.util.concurrent.atomic.AtomicBoolean`
  - 클래스 KDoc에 F-008 / A-009 라이프사이클 섹션 추가
  - 인스턴스 멤버 추가:
    - `private val closed: AtomicBoolean`
    - `internal val scope: CoroutineScope` (`SupervisorJob() + Dispatchers.IO`)
    - `internal var httpClient: OkHttpClient?` (`@Volatile`, F-001/F-006 진입 시 set)
  - 인스턴스 메서드 추가:
    - `public fun close()` — idempotent + thread-safe + best-effort
    - `internal fun ensureNotClosed()` — close 후 호출 검증 헬퍼
    - `internal fun isClosed(): Boolean` — Flow 함수가 사용할 보조 헬퍼
  - **기존 코드는 한 줄도 변경/삭제하지 않음** (T9와 충돌 회피)

### 생성 (1건)
- `C:\Users\82106\android-ai-lab\experiments\02-ai-agent-sdk\sdk\src\test\kotlin\com\androidailab\aisdk\client\CloseTest.kt`
  - F-008 / A-009 / R-020 단위 테스트 13개

---

## 2. 구현한 사양 ID

| 사양 ID | 종류 | 구현 위치 | 비고 |
|---------|------|-----------|------|
| F-008 | 기능 | `AiAgentClient.close/closed/scope/httpClient/ensureNotClosed/isClosed` | 정상 흐름 1~5 (close 정책 + 시맨틱 표) |
| A-009 | API | `AiAgentClient.close()` | 시그니처 `fun close()` 정확 일치, 동작 4단계 + 정책(idempotent/thread-safe) |
| R-020 | 라운드 결정 | `close()` + `ensureNotClosed()` | 케이스 A (진행 중 → CancellationException) + 케이스 B (close 후 → "client closed") |
| E-109 | 예외 | `ensureNotClosed()` | F-001 ask 후속 라운드에서 `Result.failure(Configuration("client closed"))` 변환 진입점 |
| E-110 | (외) | — | F-001 책임 (빈 응답 검증) — 본 라운드 미적용 |
| E-402 | 예외 | `ensureNotClosed()` | F-004 send 후속 라운드 진입점 (헬퍼 재사용) |
| E-705 | 예외 | `ensureNotClosed()` | F-007 save/load/delete 후속 라운드 진입점 |
| E-303 | 예외 | `isClosed()` | F-003 askStream 진입 시 `AiStreamEvent.Error` emit에 사용 (헬퍼만 노출) |
| E-801 | 예외 | `close()` try/catch | close 도중 IO 예외 무시 + best-effort 진행 |
| ERR-004 | 매핑 | `AiException.Configuration("client closed")` | error-handling.md ERR ↔ E-XXX 표 일치 |

> 메시지 문자열 `"client closed"`는 사양과 정확히 일치 (R-020 / api.md A-009 표).

---

## 3. 단위 테스트 케이스 목록

`CloseTest.kt` (총 13개):

**idempotent / 상태 (3)**
- `F-008 close 정책 — 여러 번 호출해도 예외 없이 안전 (idempotent)`
- `F-008 close 정책 — 초기 상태는 isClosed=false`
- `F-008 close 정책 — close 호출 후 isClosed=true`

**R-020 케이스 B — ensureNotClosed (3)**
- `F-008 R-020 케이스 B — close 후 ensureNotClosed 가 Configuration throw` (메시지 "client closed" 정확 일치 검증 포함)
- `F-008 R-020 케이스 B — close 전 ensureNotClosed 는 통과`
- `F-008 E-109 매핑 — close 후 호출은 ERR-004 (Configuration) 로 분류됨`

**OkHttp dispatcher.cancelAll (4)**
- `F-008 정상 흐름 2 — close 시 OkHttp dispatcher cancelAll 호출됨` (mockk verify exactly = 1)
- `F-008 정상 흐름 2 — httpClient 가 null 이어도 close 는 정상 동작` (F-001 진입 전 호환)
- `F-008 E-801 — close 도중 dispatcher cancelAll 가 throw 해도 close 는 진행` (best-effort)
- `F-008 close idempotent — 두 번째 호출에서는 cancelAll 다시 호출 안 함`

**R-020 케이스 A — scope cancellation (3)**
- `F-008 정상 흐름 3 — close 시 scope 가 cancel 되어 더 이상 활성화되지 않음`
- `F-008 R-020 케이스 A — close 시 진행 중 코루틴이 CancellationException 으로 종결` (long-running launch 시뮬레이션)
- `F-008 R-020 케이스 A — close 후 scope launch 결과는 즉시 cancelled`
- `F-008 R-020 케이스 A — close 시 async 도 CancellationException 으로 종결` (Result.failure 미변환 검증)

> 빌드 환경(`settings.gradle.kts` 등)이 아직 없어 본 라운드에서는 컴파일/실행을 수행하지 않음 (T8와 동일 정책). `runTest` / `mockk` / `kotlinx-coroutines-test` 의존성은 T8에서 이미 선언됨 (`sdk/build.gradle.kts`).

---

## 4. 사양 명확화 요청

본 라운드에서 사양에 **명시적으로 없지만** 합리적 결정을 내린 항목:

| 항목 | 사양 위치 | 본 라운드 결정 | 제안 |
|------|-----------|----------------|------|
| 내부 `CoroutineScope`의 dispatcher | api.md A-009 동작 항목 | `Dispatchers.IO` (네트워크/디스크 작업 기본) | overview.md 기술 스택과 일치. 사양에 명시 권장 (F-001/F-007 진입 전에) |
| `OkHttpClient` 핸들 보유 위치 | features.md F-008 정상 흐름 2 | `AiAgentClient.httpClient: OkHttpClient?` (`@Volatile`, internal var, F-001/F-006이 set) | F-001 진입 시 Provider/HttpClient 주입 경로를 결정해 본 holder를 set 하거나 별도 위치로 옮기는 결정 필요 |
| `scope.cancel()` 시 cause 메시지 | api.md A-009 동작 항목 | `"AiAgentClient closed"` | 디버깅용 메시지. 사양은 명시 안 함 — 호출자에 노출되지 않음 |

세 항목 모두 사양 누락이 아니라 **사양이 의도적으로 구현 자유에 맡긴 영역**으로 판단해 spec-architect에게 명확화 요청하지 않음. 다른 결정이 필요하면 알림 부탁.

---

## 5. T9 (F-005 useProvider) 와의 충돌 가능 영역

**같은 파일 수정 — `AiAgentClient.kt`**

본 라운드에서 추가한 부분 (clean하게 분리):

| 위치 | 추가/변경 내용 | 충돌 여부 |
|------|----------------|-----------|
| import 블록 | `AiException`, `kotlinx.coroutines.*`, `OkHttpClient`, `AtomicBoolean` 추가 | T9도 imports 추가 가능 (예: `AtomicReference`, `Provider`). **import 라인 추가만 하므로 git merge 자동 해결 가능성 큼**. 충돌 발생 시 양쪽 import 모두 보존하면 됨 |
| 클래스 KDoc | "라이프사이클(F-008, A-009)" 단락 추가 | T9가 동일 KDoc에 useProvider 단락 추가 가능. 한 KDoc 안에 양쪽 단락이 공존하도록 머지하면 됨 |
| 클래스 본문 | `closed`/`scope`/`httpClient`/`close()`/`ensureNotClosed()`/`isClosed()` 추가. 모두 **"F-008 / A-009 — 라이프사이클 상태"** 와 **"F-008 / A-009 — close"** 두 섹션 주석으로 구분 | T9는 `providerId AtomicReference` + `useProvider()` 추가 예정. **T9의 추가 위치를 본 라운드 추가 블록과 다른 곳(예: 생성자 직후 또는 companion 직전)에 두면 충돌 없음** |
| companion object | **변경 없음** | 안전 |
| 생성자 시그니처 | **변경 없음** (T9가 `providerId: ProviderId` 파라미터를 `AtomicReference`로 변환할 때 본 라운드 코드에는 영향 없음 — 본 라운드는 `providerId`를 사용하지 않음) | 안전 |
| 기존 KDoc/메서드 시그니처 | **변경 없음** | 안전 |

**T9에 양보한 영역**:
- `useProvider()` 본체는 본 라운드에서 작성하지 않음. T9 결과를 신뢰.
- `providerId`를 `val → AtomicReference`로 전환하는 것은 T9 책임. 본 라운드의 `close()` / `ensureNotClosed()` 는 `providerId`에 접근하지 않으므로 영향 없음.

**T9가 본 라운드 헬퍼를 활용해야 할 부분**:
- `useProvider()` 진입 첫 줄에 `ensureNotClosed()` 호출 권장 (api.md A-007: 동기 함수 → throw). T9가 본 라운드를 머지받은 뒤 추가하면 됨.

**머지 충돌 발생 시 가이드**:
1. import 라인은 양쪽 모두 보존 (알파벳순 정렬).
2. 클래스 본문에서 본 라운드의 "F-008 / A-009" 주석 블록 두 개와 T9의 "F-005" 주석 블록을 모두 남기되, 위치는 자유롭게 조정.
3. 클래스 KDoc은 양쪽 라이프사이클/Provider 단락 모두 보존.

---

## 6. 후속 라운드 인계 사항

본 라운드가 노출한 internal 헬퍼/상태를 다른 F 라운드에서 다음과 같이 사용:

### F-001 (텍스트 단발 질의 ask, suspend) — `AiAgentClient.ask()`
```kotlin
public suspend fun ask(request: AiRequest): Result<AiResponse> {
    runCatching { ensureNotClosed() }.onFailure { return Result.failure(it) } // E-109
    return scope.async { /* ... 실제 호출 ... */ }.await() // 케이스 A 자동 처리
        // 또는 withContext(scope.coroutineContext) { ... }
}
```
- `httpClient`를 set 하는 위치 결정 필요 (Builder.build() 또는 F-006 Hilt 모듈).

### F-003 (askStream, Flow) — `AiAgentClient.askStream()`
```kotlin
public fun askStream(request: AiRequest): Flow<AiStreamEvent> = flow {
    if (isClosed()) {
        emit(AiStreamEvent.Error(AiException.Configuration("client closed"))) // 케이스 B
        return@flow
    }
    // ... 실제 스트리밍 ...
}.flowOn(scope.coroutineContext) // 또는 scope에서 collect
```

### F-004 (send, suspend on Session) — `Session.send()`
- `Session`이 `AiAgentClient` 참조 보유. `client.ensureNotClosed()`를 send 진입 첫 줄에 호출 (E-402).
- `Session.history` / `Session.clear` (동기) 도 `client.ensureNotClosed()` 호출 (throw).

### F-005 (useProvider, 동기) — T9 진행 중
- `useProvider()` 진입 첫 줄에 `ensureNotClosed()` 호출 (E-501/E-502 검증 전 또는 후 — 사양 모호. spec 확인 권장).

### F-007 (save/load/delete, suspend) — `AiAgentClient`
- 모든 함수 진입 시 `ensureNotClosed()` (E-705).
- `scope`에서 `Dispatchers.IO`로 launch (이미 scope이 IO 디스패처).
- DataStore 핸들 해제는 본 라운드 `close()`의 placeholder (3단계) 위치에 추가.

### F-006 (Hilt 모듈)
- `httpClient`를 Hilt가 주입한 `OkHttpClient`로 set (Builder.build() 후 또는 `@Provides` 함수에서).
- close 정책: `ApplicationComponent` scope이므로 일반적으로 호출 불필요 (api.md A-009 정책).

---

## 7. sdk-qa-validator 검증 요청

**요청 한 줄**: F-008 / A-009 / R-020 / E-109+E-402+E-705+E-801 정합성 검증 부탁드립니다 — `_workspace/qa_report_3.md` 등으로 회신 부탁.

**검증 포인트**:
1. **A-009 시그니처 일치** — `public fun close()` 토큰 정확 일치 (반환형 Unit 생략).
2. **F-008 정상 흐름 1~5** — 1=close 호출, 2=cancelAll, 3=scope.cancel, 4=DataStore placeholder, 5=ensureNotClosed가 후속 라운드에서 케이스 B 처리.
3. **R-020 케이스 A** — 진행 중 코루틴이 CancellationException으로 종결 (테스트 케이스 3개로 검증).
4. **R-020 케이스 B** — `ensureNotClosed()`가 `AiException.Configuration("client closed")` throw. 메시지 문자열 정확 일치.
5. **idempotent + thread-safe** — `AtomicBoolean.compareAndSet`로 첫 호출만 정리.
6. **E-801** — close 도중 IO 예외 무시 (best-effort) — 테스트로 검증.
7. **ERR-004 매핑** — `AiException.Configuration` 통과.
8. **explicit-api=strict 준수** — `public/internal/private` 모두 명시.

**검증에서 의도적으로 빠진 항목 (후속 라운드 책임)**:
- ask/askStream/send/save/load/delete의 close 통합 (F-001/F-003/F-004/F-007 라운드).
- `useProvider`의 close 통합 (T9/F-005 라운드).
- DataStore 핸들 해제 실제 동작 (F-007 라운드).
- `httpClient` set 시점 (F-001/F-006 라운드).

---

## 8. 자체 체크리스트 (커밋 전)

- [x] 사양에 없는 동작 추가 안 함 (4번 섹션의 3건은 사양이 자유에 맡긴 영역)
- [x] 모든 public/internal 함수에 KDoc + F-008/A-009/R-020 참조
- [x] F-008에 대한 단위 테스트 13개 (정상 + R-020 A/B + E-801 + idempotent)
- [x] 외부 예외 변환 — close 도중 IO 예외는 ERR 미매핑 (E-801 사양 그대로)
- [x] suspend/Flow 외 비동기 API 없음 — `close()`는 동기 함수 (사양 그대로)
- [x] CancellationException 별도 처리 — `scope.cancel()`이 표준 시맨틱으로 케이스 A 자동 처리
- [x] 임의 추상화/플래그 없음
- [x] 메시지 문자열 `"client closed"` 사양 그대로 정확 일치
- [x] T9 충돌 방지 — 기존 코드 한 줄도 변경 안 함 (5번 섹션 가이드 준수)
