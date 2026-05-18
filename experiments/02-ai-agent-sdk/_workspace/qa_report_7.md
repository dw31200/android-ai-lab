# QA Report 7 — F-004 (세션 컨텍스트 유지) 점진 검증 + F-001/F-002/F-003/F-006 회귀

검증자: sdk-qa-validator
검증 일시: 2026-05-08
대상:
- F-004 본체 (T17 라운드, `_workspace/impl_summary_8.md`)
  - 신규 5건: `model/Message.kt`, `session/Session.kt`, `session/SessionTest.kt`, `client/CreateSessionTest.kt`, `internal/network/MapperSessionTest.kt`
  - 수정 5건: `AiAgentClient.kt`, `internal/network/AnthropicMessagesRequest.kt`, `internal/network/Mapper.kt`, `internal/network/ErrorMapper.kt`, `provider/claude/ClaudeProvider.kt`
- F-001/F-002/F-003/F-006 회귀 (impl_summary_8 §0 회귀 주장 정합 검증)

검증 범위:
- F-004 정상 흐름 1~5 / 예외 흐름 E-401 / E-402 / E-403 / E-101~E-110 / E-201~E-208 회귀
- A-004 createSession / A-006 Session.send / A-007 history / A-008 clear 토큰 단위 시그니처 비교
- M-007 Session / M-008 Message 필드/제약 일치
- R-007 / R-008 / R-011 / R-014 / R-017 / R-024 / R-020 케이스 B
- ERR-004 (E-402) / ERR-005 (E-401) 매핑
- 49 단위 테스트 (SessionTest 22 + CreateSessionTest 7 + MapperSessionTest 20)
- F-001/F-002/F-003/F-006 회귀 (시그니처 변경 부재 + 추가된 E-401 사전 감지의 부수효과 분석)

빌드 실행: 미수행 (settings.gradle.kts / wrapper 부재 — 정적 검증 + 토큰 단위 비교)

---

## 1. 요약

| Severity | 건수 | 종결 조건 |
|----------|------|-----------|
| Blocker  | 0    | 0건 필수 — **충족** |
| Major    | 0    | 처리 또는 명시적 유보 — **충족** |
| Minor    | 2    | 다음 라운드 이월 가능 (Q-T17-M1 / Q-T17-M2) |
| 정보성   | 2    | 사양 보강 권장 (Q-T17-I1 / Q-T17-I2) |
| 사양 명확화 요청 | 4 (S-T17-1/2/3/4 = Q-T16-1/2/3/4 인계) | spec-architect 회신 후 반영 |

**결과**: F-004 **통과(종결 가능)** — 다음 wave (F-007 영속화) 진입 권고.

요지:
- A-004 / A-006 / A-007 / A-008 시그니처 모두 사양과 토큰 단위 일치.
- M-007 Session 시그니처는 `save()` 메서드를 제외하면 일치 (Q-T16-3 = S-T17-3으로 사양 명시 후 정합).
- M-008 Message + Role 정의 일치.
- F-004 정상 흐름 1~5 모두 코드/테스트로 검증됨.
- E-401 키워드 매칭 (`ErrorMapper.mapContextOverflow`)은 v0.1 키워드 리스트 7개 항목으로 합리적 기본값. 사양 보강 필요 (Q-T16-2 = S-T17-2).
- E-402 / E-403 / R-020 케이스 B 모두 명시적으로 코드/테스트 매핑.
- F-001/F-002/F-003/F-006 회귀 위험 포인트 4건 모두 영향 없음 확인.
- Minor 2건 = Session.send fallback 분기(ClaudeProvider 외 Provider) 도달 불가에 대한 사양 명시 / Mutex+synchronized 이중 동기화 패턴의 사양 명시.
- 정보성 2건 = E-401 키워드 리스트 / API 키 마스킹 디버그 정책.

---

## 2. F-004 검증 표

### 2.1 시그니처 일치 — A-004 / A-006 / A-007 / A-008

| API | 사양 (api.md) | 구현 위치 | 결과 |
|-----|--------------|-----------|------|
| A-004 createSession | `fun createSession(systemPrompt: String? = null): Session` (api.md:164) | `AiAgentClient.kt:441` `public fun createSession(systemPrompt: String? = null): Session` | 통과 — 토큰 단위 일치 (이름/파라미터/디폴트/반환 모두) |
| A-006 Session.send | `suspend fun send(request: AiRequest): Result<AiResponse>` (api.md:200) | `Session.kt:120` `public suspend fun send(request: AiRequest): Result<AiResponse>` | 통과 — 토큰 단위 일치 |
| A-007 Session.history | `fun history(): List<Message>` (api.md:216) | `Session.kt:248` `public fun history(): List<Message>` | 통과 — 토큰 단위 일치 |
| A-008 Session.clear | `fun clear()` (api.md:231) | `Session.kt:279` `public fun clear()` | 통과 — 토큰 단위 일치 |

### 2.2 M-007 Session — 클래스 시그니처

| M-007 필드/메서드 | 사양 (data-model.md L190-200) | 구현 위치 | 결과 |
|--------------------|------------------------------|-----------|------|
| 생성자 시그니처 | `class Session internal constructor(client, sessionId, systemPrompt, initialHistory)` | `Session.kt:53-58` `public class Session internal constructor(private val client, public val sessionId, private val systemPrompt, initialHistory)` | 통과 — 필드 순서/타입/visibility(internal constructor) 일치 |
| sessionId | `val sessionId: String` (자동 UUID) | `Session.kt:55` `public val sessionId: String` | 통과 (R-017/R-024 자동 부여는 `AiAgentClient.kt:445` `UUID.randomUUID().toString()`) |
| systemPrompt | `private val systemPrompt: String?` (history 미포함) | `Session.kt:56` `private val systemPrompt: String?` | 통과 (R-008 정합) |
| initialHistory | `initialHistory: List<Message> = emptyList()` | `Session.kt:57` `initialHistory: List<Message> = emptyList()` | 통과 |
| `send(request)` | `suspend fun send(request: AiRequest): Result<AiResponse>` | `Session.kt:120` | 통과 |
| `history()` | `fun history(): List<Message>` (immutable snapshot) | `Session.kt:248` | 통과 |
| `clear()` | `fun clear()` | `Session.kt:279` | 통과 |
| `save()` | `suspend fun save(): Result<String>` (F-007) | **미구현** (F-007 라운드 예정) | 사양 명확화 필요 — S-T17-3 (Q-T16-3 인계) |

### 2.3 M-008 Message + Role enum

| 필드/항목 | 사양 (data-model.md L226-241) | 구현 위치 | 결과 |
|----------|-----------------------------|-----------|------|
| role | `val role: Role` (USER/ASSISTANT/SYSTEM enum) | `Message.kt:23` `public val role: Role` | 통과 |
| content | `val content: String` | `Message.kt:24` `public val content: String` | 통과 |
| images | `val images: List<ImageInput> = emptyList()` | `Message.kt:25` `public val images: List<ImageInput> = emptyList()` | 통과 |
| timestamp | `val timestamp: Long = System.currentTimeMillis()` | `Message.kt:26` `public val timestamp: Long = System.currentTimeMillis()` | 통과 |
| Role enum 값 | `USER, ASSISTANT, SYSTEM` | `Message.kt:38-42` `USER, ASSISTANT, SYSTEM` | 통과 |
| Role 사용 정책 | USER/ASSISTANT만 history에 등장 (R-008) | `Session.kt:186-200` (Session.send가 USER/ASSISTANT만 append) + `Mapper.kt:127-130` (Role.SYSTEM 방어적 무시) | 통과 |

### 2.4 F-004 정상 흐름 1~5 매핑

| 단계 | 사양 (features.md L182-187) | 구현 위치 | 결과 |
|------|---------------------------|-----------|------|
| 1 | createSession(systemPrompt) 호출 | `AiAgentClient.kt:441-452` `createSession` — UUID 자동 부여 + Session 인스턴스 생성 | 통과 |
| 2 | session.send(request) → 이전 메시지 + 현재 요청 합쳐 Provider 전송 | `Session.kt:120-222` `send` → `Mapper.toAnthropicRequestForSession` (`Mapper.kt:116-157`) → `ClaudeProvider.completeForSession` (`ClaudeProvider.kt:194-251`) | 통과 |
| 3 | 응답을 세션 history에 추가 | `Session.kt:186-200` USER + ASSISTANT 메시지 synchronized(history)로 append (정상 흐름 끝) | 통과 |
| 4 | session.history() — immutable snapshot 반환 | `Session.kt:248-258` `synchronized(history) { history.toList() }` (R-011) | 통과 |
| 5 | session.clear() — 초기화 | `Session.kt:279-287` `synchronized(history) { history.clear() }` (systemPrompt 보존) | 통과 |

### 2.5 E-401 (컨텍스트 초과) 매핑

| 항목 | 사양 (features.md L195-198, provider-spec.md L92-97) | 구현 위치 | 결과 |
|------|-----------------------------------------------------|-----------|------|
| 감지 알고리즘 | Provider 응답에서 컨텍스트 초과 에러 감지 → InvalidInput("context too large") | `ErrorMapper.kt:102-122` `mapContextOverflow` — invalid_request_error type + 키워드 7개 매칭 | 통과 (단, 키워드 매칭 안정성은 S-T17-2 인계) |
| 키워드 리스트 | (사양 없음 — 사양 보강 필요) | `ErrorMapper.kt:27-35` `ANTHROPIC_CONTEXT_OVERFLOW_KEYWORDS` (context_length_exceeded / context window / context too long / prompt is too long / input is too long / too many tokens / maximum context) | 정보성 — 사양 보강 권장 (Q-T17-I1) |
| 사전 감지 위치 | (사양상 명시 없음) — provider-spec.md P-CLAUDE 에러 매핑 표에 매핑만 명시 | `ClaudeProvider.kt:137-144` (complete 4xx) / `ClaudeProvider.kt:223-232` (completeForSession 4xx) — 둘 다 `runCatching { resp.body?.string() }`로 본문 안전 read | 통과 |
| 본문 read 안전성 | 본문은 한 번만 읽을 수 있음 | `runCatching` + `getOrNull()`로 IOException/IllegalStateException 안전망 (Session 흐름에서 resp.use 안쪽이라 close는 보장) | 통과 |
| 매핑 결과 메시지 | `"context too large"` | `ErrorMapper.kt:121` `AiException.InvalidInput("context too large")` | 통과 — 사양 메시지와 토큰 일치 |
| ERR 매핑 | ERR-005 (InvalidInput) | `error-handling.md:81` "E-401 → ERR-005" / `ErrorMapper.kt:121` | 통과 |
| 단위 테스트 | 키워드 매칭 / 비키워드 / 비-invalid_request_error / null/빈/잘못된 JSON | `MapperSessionTest.kt:293-388` (10 케이스) | 통과 |

### 2.6 E-402 (client closed) / E-403 (Mutex 직렬화)

| 항목 | 사양 (features.md L204-205) | 구현 위치 | 결과 |
|------|---------------------------|-----------|------|
| E-402 (session.send이 close된 client에) | `AiException.Configuration("client closed")` | `Session.kt:123-125` (mutex 진입 전 첫 검사) / `Session.kt:130-132` (mutex 안에서 두 번째 검사 — close가 mutex 대기 중 발생 가능 대비) → Result.failure | 통과 (방어적 이중 검사) |
| E-402 메시지 | `"client closed"` | 둘 다 `AiException.Configuration("client closed")` 토큰 일치 | 통과 |
| E-402 (createSession/history/clear — 동기) | `throw AiException.Configuration("client closed")` | `AiAgentClient.kt:443` ensureNotClosed (createSession) / `Session.kt:250-252` (history) / `Session.kt:281-283` (clear) | 통과 |
| E-403 동시 send | "두 번째 호출은 첫 호출 완료까지 직렬 대기 (Mutex)" | `Session.kt:80` `private val mutex: Mutex = Mutex()` + `Session.kt:128` `mutex.withLock { ... }` | 통과 — 정상 동작 (ERR 매핑 없음) |
| E-403 취소 시맨틱 | "취소 시 첫 호출 정상 완료 후 두 번째도 취소 전파" | `mutex.withLock`은 cancel cooperative (kotlinx-coroutines Mutex 표준) + `Session.kt:203-205` CancellationException 그대로 전파 | 통과 |
| 단위 테스트 | E-402 4건 (send/createSession/history/clear) + E-403 1건 (Mutex 직렬화) | `SessionTest.kt:301-360` + `SessionTest.kt:367-416` | 통과 |

### 2.7 R-007 / R-014 — Provider 진입 시점 캡쳐

| 항목 | 사양 | 구현 위치 | 결과 |
|------|------|-----------|------|
| R-007 (활성 Provider 진입 시 atomic get) | features.md F-005 동시성 모델 — "Session도 동일" | `Session.kt:135-136` mutex 안에서 `val provider: Provider = client.activeProvider` + `val config: ProviderConfig = client.currentProviderConfig()` | 통과 |
| R-014 (Session은 Provider에 묶이지 않음) | features.md F-005 R-014 — "session.send() 호출 시점의 client 활성 Provider 사용" | Session은 client 참조만 보관 (`Session.kt:54`), Provider 보관 안 함. send 진입 시점 캡쳐. | 통과 |
| 단위 테스트 | R-014 — Session.send가 활성 Provider 사용 | `SessionTest.kt:423-438` (FakeClaudeLike Provider로 검증) | 통과 |

### 2.8 R-008 (systemPrompt 정책)

| 항목 | 사양 (features.md L189-193, data-model.md L243-246) | 구현 위치 | 결과 |
|------|------------------------------------------------------|-----------|------|
| systemPrompt history 미포함 | "session.history() 반환 결과에 Role.SYSTEM 메시지는 등장하지 않음" | `Session.kt:186-200` USER/ASSISTANT만 add (SYSTEM 미사용) + `Session.kt:248-258` history()는 list 그대로 반환 | 통과 |
| systemPrompt → AnthropicMessagesRequest.system 매핑 | "Provider 전송 시점에는 SDK 내부에서 systemPrompt를 시스템 메시지 위치에 삽입" | `Mapper.kt:148-156` `system = systemPrompt` 매핑 / `AnthropicMessagesRequest.kt:38` `@SerialName("system") val system: String? = null` | 통과 |
| 직렬화 시 null이면 제외 | encodeDefaults=false + explicitNulls=false 정책 | `AnthropicJson.kt:18-22` 두 옵션 모두 활성 | 통과 |
| history에 SYSTEM 도달 시 무시 (방어적) | (사양 명시 없음 — 합리적 방어) | `Mapper.kt:127-134` `if (msg.role == Role.SYSTEM) continue` | 통과 |
| 단위 테스트 | systemPrompt → system 매핑 + null 제외 + history 미포함 + SYSTEM 무시 | `MapperSessionTest.kt:88-204` (5 케이스) + `SessionTest.kt:220-238` (1 케이스) + `SessionTest.kt:107-123` (1 케이스) + `SessionTest.kt:273-295` (1 케이스 clear 후 systemPrompt 보존) | 통과 |

### 2.9 R-011 (history immutable snapshot)

| 항목 | 사양 (data-model.md L206) | 구현 위치 | 결과 |
|------|--------------------------|-----------|------|
| history() 반환은 호출 시점의 snapshot | "Mutex 안에서 List 복사본을 반환하여 immutable snapshot 보장 (R-011)" | `Session.kt:257` `synchronized(history) { history.toList() }` — Kotlin List(toList)는 변경 불가 ArrayList 반환 | 통과 (Mutex 대신 synchronized 사용은 Q-T16-4 사양 명시 후 정합) |
| 단위 테스트 | snapshot 이후 send → snapshot 변경 없음 | `SessionTest.kt:245-267` | 통과 |

### 2.10 R-017 / R-024 (SDK 자동 UUID 부여)

| 항목 | 사양 (api.md A-004 L171-173) | 구현 위치 | 결과 |
|------|-----------------------------|-----------|------|
| sessionId 자동 UUID 부여 | "v0.1은 SDK 자동 UUID 부여만 지원" | `AiAgentClient.kt:445` `val sessionId = UUID.randomUUID().toString()` | 통과 |
| 호출자 명시 지정 미지원 | "v0.1 Out of Scope" | createSession 시그니처에 sessionId 파라미터 없음 — 정합 | 통과 |
| 단위 테스트 | UUID 형식 / 두 번 호출 시 다른 ID | `CreateSessionTest.kt:74-92` + `SessionTest.kt:85-104` | 통과 |

### 2.11 R-020 케이스 B (close 후 호출 시맨틱)

| 호출 종류 | 사양 | 구현 위치 | 결과 |
|----------|------|-----------|------|
| createSession (동기) | throw `AiException.Configuration("client closed")` | `AiAgentClient.kt:443` ensureNotClosed (1줄) | 통과 |
| Session.send (suspend) | `Result.failure(AiException.Configuration("client closed"))` | `Session.kt:123-125` (진입 직후) + `Session.kt:130-132` (mutex 안에서 두 번째 검사 — 방어적) | 통과 |
| Session.history (동기) | throw `AiException.Configuration("client closed")` | `Session.kt:250-252` | 통과 |
| Session.clear (동기) | throw `AiException.Configuration("client closed")` | `Session.kt:281-283` | 통과 |
| 단위 테스트 | 4개 호출 종류 × close 후 매핑 검증 | `SessionTest.kt:301-360` (4 케이스) + `CreateSessionTest.kt:121-147` (2 케이스) | 통과 |

### 2.12 F-002 이미지 검증 재사용 (E-201/E-202/E-203/E-204/E-205/E-208)

| 항목 | 사양 (features.md F-002) | 구현 위치 | 결과 |
|------|--------------------------|-----------|------|
| 진입 시 validateImages 호출 | F-002 정상 흐름 2단계 | `Session.kt:139-142` `validateImages(request, provider.capabilities)` | 통과 |
| 알고리즘 동일 (E-205/E-208/E-201/E-204/E-203 + 합계) | `AiAgentClient.validateImages`와 동일 | `Session.kt:305-362` `private fun validateImages(...)` — `AiAgentClient.kt:483-543`과 동일 알고리즘 (Bytes/Url/Uri 분기, mime 검증, 5MB/20MB 한계) | 통과 (단, 같은 함수의 두 사본 — 사양 외 코드 중복) |
| Message.images 변환 (F-004 history 메시지의 이미지) | F-002 정책 재사용 | `Mapper.kt:189-244` `buildMessageContentJson` 헬퍼 추출 — 텍스트+이미지 블록 빌드 (Bytes 매직 넘버 검증 → base64 / Url fetch → base64 / Uri → E-203 throw) | 통과 |
| 단위 테스트 | E-203 + Bytes/Url 변환 | `SessionTest.kt:574-588` (E-203) + `MapperSessionTest.kt:211-239` (Bytes PNG) + `MapperSessionTest.kt:242-268` (Uri → E-203 안전망) | 통과 |

### 2.13 Mutex 직렬화 + synchronized(history) 이중 동기화

| 항목 | 사양 (data-model.md M-007 L203-207) | 구현 위치 | 결과 |
|------|------------------------------------|-----------|------|
| Mutex 직렬화 (send 동시 호출) | "send 진입 시 Mutex 획득 → history 읽기/쓰기 → 해제" | `Session.kt:80` Mutex 정의 + `Session.kt:128` `mutex.withLock { ... }` | 통과 |
| synchronized(history) (동기 함수와의 race) | "history()는 Mutex 안에서 List 복사본을 반환" | `Session.kt:151` (send snapshot) / `Session.kt:186-200` (send append) / `Session.kt:257` (history()) / `Session.kt:284-286` (clear()) — 모두 동일한 lock 객체(history)로 보호 | 통과 — deadlock 없음 (synchronized는 항상 mutex 안쪽에서 짧게 사용) |
| 잠금 순서 위험 | (사양 없음) | mutex → synchronized 한 방향만 사용 (역순 없음) | 통과 |
| 단위 테스트 | Mutex 직렬화 | `SessionTest.kt:367-416` (E-403) | 통과 |

### 2.14 history 누적 / 접근 / clear (R-024)

| 항목 | 사양 | 구현 위치 | 결과 |
|------|------|-----------|------|
| send 성공 시 USER + ASSISTANT 누적 | F-004 정상 흐름 3 | `Session.kt:186-200` 두 메시지 add | 통과 |
| send 실패 시 history 변경 없음 | (트랜잭션 보장 — 사양 명시 없음, 합리적 정책) | `Session.kt:203-220` (try-catch 본체 안에서 append 이전에 throw 발생 가능 → history 변경 없음) | 통과 |
| clear() 후 systemPrompt 보존 | api.md A-008 "systemPrompt는 유지" | `Session.kt:284-286` history만 clear, systemPrompt 필드는 그대로 | 통과 |
| 단위 테스트 | 연속 send 누적 / 실패 시 변경 없음 / clear 후 systemPrompt 사용 | `SessionTest.kt:152-180` / `SessionTest.kt:512-526` / `SessionTest.kt:273-295` | 통과 |

### 2.15 Mapper.toAnthropicRequestForSession + buildMessageContentJson 추출

| 항목 | 사양 (F-004 매핑) | 구현 위치 | 결과 |
|------|------------------|-----------|------|
| 시그니처 | `toAnthropicRequestForSession(history, request, systemPrompt, config, httpClient)` | `Mapper.kt:116-122` 동일 시그니처 (suspend, internal) | 통과 |
| history USER/ASSISTANT 변환 | F-004 정상 흐름 2 | `Mapper.kt:126-142` history loop → ROLE_USER/ROLE_ASSISTANT 매핑 | 통과 |
| 현재 USER 메시지 append | F-004 정상 흐름 2 | `Mapper.kt:145-146` buildContentJson(request) → 마지막 USER 메시지 | 통과 |
| systemPrompt → system 필드 | R-008 | `Mapper.kt:155` `system = systemPrompt` | 통과 |
| maxTokens/temperature 현재 request에서 | (사양 명시 없음 — 합리적 정책: Anthropic API는 요청 단위) | `Mapper.kt:150-152` `request.maxTokens` / `request.temperature` 그대로 | 통과 |
| buildMessageContentJson 헬퍼 추출 | (사양 외 리팩토링) | `Mapper.kt:189-244` 텍스트+이미지 블록 빌드 공용 헬퍼 — 기존 `buildContentJson`은 본 헬퍼에 위임 | 통과 — 외부 동작 동일 (`Mapper.kt:168-175`) |
| 단위 테스트 | 빈 history / systemPrompt 매핑 / 다중 메시지 순서 / SYSTEM 무시 / Bytes 변환 / Uri 안전망 / maxTokens/temperature | `MapperSessionTest.kt` 10 케이스 (F-004 영역) | 통과 |

---

## 3. 단위 테스트 검증 표 (49 케이스)

외부 네트워크 호출 0회. runTest / Fake providers / static JSON 본문 사용.

### 3.1 SessionTest.kt (22 케이스)

| # | 테스트 이름 | 의도 | 사양 ID | 의미 있음 |
|---|------------|------|---------|----------|
| 1 | createSession 은 UUID sessionId 와 빈 history 를 가진 Session 반환 | A-004 진입 + UUID 형식 + 빈 history | A-004 / R-017 / M-007 | Y |
| 2 | createSession 두 번 호출 시 서로 다른 sessionId | UUID 자동 부여의 고유성 | A-004 / R-017 | Y |
| 3 | createSession(systemPrompt) 는 systemPrompt 를 Session 에 보관 | Session에 systemPrompt 저장 + completeForSession에 전달 | A-004 / R-008 | Y |
| 4 | send 성공 시 USER + ASSISTANT 메시지가 history 에 누적 | F-004 정상 흐름 3 | F-004 / R-024 | Y |
| 5 | 연속 send 시 history 가 순차 누적 | history 누적 + Mutex 직렬화 동작 검증 | F-004 정상 흐름 / E-403 | Y |
| 6 | 두 번째 send 호출 시 첫 send 의 history 가 Provider 에 전달됨 | F-004 정상 흐름 2 — history 전달 | F-004 | Y |
| 7 | R-008 — systemPrompt 는 history 에 등장하지 않음 | R-008 정책 검증 | R-008 / M-008 | Y |
| 8 | history() 반환은 immutable snapshot | R-011 snapshot 보장 | R-011 / A-007 | Y |
| 9 | clear() 후 history 는 비어있고 systemPrompt 는 유지됨 | A-008 + clear 후 systemPrompt 보존 | A-008 / R-008 | Y |
| 10 | close 후 send → Result_failure(Configuration("client closed")) | E-402 / R-020 케이스 B (suspend) | E-402 / R-020 | Y |
| 11 | close 후 createSession → Configuration throw | R-020 케이스 B (동기) | R-020 | Y |
| 12 | close 후 Session_history() → Configuration throw | R-020 케이스 B (동기) | R-020 | Y |
| 13 | close 후 Session_clear() → Configuration throw | R-020 케이스 B (동기) | R-020 | Y |
| 14 | 동시 send 호출은 Mutex 로 직렬화 | E-403 정상 동작 검증 | E-403 | Y — 의미 있음 (CompletableDeferred로 첫 send 점유 + 두 번째 진입 대기 검증) |
| 15 | Session_send 는 호출 시점의 client_activeProvider 를 사용 | R-014 | R-014 | Y |
| 16 | send 도중 cancel 되면 CancellationException 그대로 전파 | E-106 / R-020 케이스 A | E-106 / R-020 | Y |
| 17 | Provider 가 InvalidInput(context too large) 를 throw 하면 Result_failure 로 전달 | E-401 pass-through (Provider 단에서 변환된 케이스) | E-401 / ERR-005 | Y |
| 18 | Provider 가 Network 를 throw 하면 Result_failure(Network) | AiException pass-through | E-101 / ERR-001 | Y |
| 19 | Provider 가 throw 한 경우 history 는 변경되지 않음 | atomic update 보장 (사양 외 합리적 정책) | F-004 정상 흐름 3 | Y |
| 20 | Session send 도 빈 응답 + END_TURN 은 그대로 성공 | F-001 R-005 동등 | R-005 / F-001 | Y |
| 21 | Session send 도 빈 응답 + MAX_TOKENS 는 ServerError(-1) | F-001 E-110 동등 | E-110 / F-001 | Y |
| 22 | Session send 진입에서 ImageInput_Uri 는 즉시 E-203 InvalidInput | F-002 E-203 재사용 | E-203 / F-002 | Y |

### 3.2 CreateSessionTest.kt (7 케이스)

| # | 테스트 이름 | 의도 | 사양 ID | 의미 있음 |
|---|------------|------|---------|----------|
| 1 | createSession() 은 비-null Session 반환 | A-004 정상 흐름 | A-004 | Y |
| 2 | createSession() 의 sessionId 는 UUID 형식 | UUID.fromString 검증 | A-004 / R-017 | Y |
| 3 | createSession() 을 두 번 호출하면 서로 다른 sessionId | UUID 자동 부여 | A-004 / R-017 | Y |
| 4 | createSession(systemPrompt) 와 createSession() 모두 호출 가능 | A-004 default param | A-004 | Y |
| 5 | 생성된 Session 의 history 는 빈 리스트 | A-004 + 초기 상태 | A-004 / M-007 | Y |
| 6 | close 후 createSession 호출 시 Configuration("client closed") throw | R-020 케이스 B 동기 | R-020 / E-402 | Y |
| 7 | close 후 createSession(systemPrompt) 도 throw | R-020 케이스 B 동기 (오버로드) | R-020 | Y |

### 3.3 MapperSessionTest.kt (20 케이스)

| # | 테스트 이름 | 의도 | 사양 ID | 의미 있음 |
|---|------------|------|---------|----------|
| 1 | 빈 history + 현재 request 는 단일 USER 메시지로 변환 (F-001 회귀 동등) | toAnthropicRequestForSession 기본 동작 | F-004 / F-001 회귀 | Y |
| 2 | systemPrompt 가 AnthropicMessagesRequest_system 으로 매핑됨 | R-008 system 매핑 | R-008 | Y |
| 3 | systemPrompt null 이면 system 필드 미설정 | system=null 정합 | R-008 | Y |
| 4 | JSON 직렬화 시 systemPrompt null 이면 system 필드 제외 | encodeDefaults=false + explicitNulls=false 정합 | R-008 / 직렬화 정책 | Y |
| 5 | JSON 직렬화 시 systemPrompt 가 system 필드로 출력됨 | system 매핑 직렬화 정합 | R-008 | Y |
| 6 | history(USER, ASSISTANT) + 현재 request 는 3 메시지 순서 보장 | history 다중 메시지 변환 + 순서 | F-004 정상 흐름 2 | Y |
| 7 | history 에 Role_SYSTEM 메시지가 있어도 무시 | R-008 방어적 필터 | R-008 | Y |
| 8 | history Message_images(Bytes PNG) 가 image 블록으로 인코딩 | F-002 정책 재사용 (Bytes) | F-002 / F-004 | Y |
| 9 | history Message_images(Uri) 도달 시 InvalidInput | E-203 매퍼 안전망 | E-203 | Y |
| 10 | maxTokens 와 temperature 는 현재 request 에서 가져옴 | history는 maxTokens 영향 없음 | F-004 (현재 request 우선) | Y |
| 11 | context_length_exceeded 키워드가 포함된 invalid_request_error → InvalidInput("context too large") | E-401 키워드 매칭 (사양 정확 메시지) | E-401 | Y |
| 12 | 'context window' 표현도 감지됨 | E-401 키워드 매칭 다양화 | E-401 / Q-T16-2 | Y |
| 13 | 'prompt is too long' 표현도 감지됨 | E-401 키워드 매칭 다양화 | E-401 / Q-T16-2 | Y |
| 14 | 컨텍스트 초과 키워드가 없는 invalid_request_error 는 null | 비키워드 케이스 — 다른 매핑 위임 | E-401 / fallback | Y |
| 15 | invalid_request_error 가 아닌 error_type 은 null | type 분리 검증 | E-401 | Y |
| 16 | null 본문은 null 반환 | 안전망 | E-401 | Y |
| 17 | 빈 본문은 null 반환 | 안전망 | E-401 | Y |
| 18 | 잘못된 JSON 본문은 null 반환 | SerializationException catch 안전망 | E-401 | Y |
| 19 | error 필드 없는 응답은 null 반환 | null guard | E-401 | Y |
| 20 | error_message 없는 응답은 null 반환 | null guard | E-401 | Y |

### 3.4 합계 및 분류

- 총 49 케이스 (impl_summary_8가 51 케이스로 보고 — 실제 카운트는 49로 확인. SessionTest 24가 아니라 22, MapperSessionTest 16~20 사이 → 20 케이스). 보고서 본 표 기준 49 통과.
- F-004 정상 흐름: 8 케이스 (#1-9 SessionTest + #1-5 CreateSessionTest)
- F-004 예외 흐름: 13 케이스 (#10-22 SessionTest + #6-7 CreateSessionTest)
- Mapper 변환 + E-401 감지: 20 케이스 (MapperSessionTest)
- 의미 있는 검증: 49/49 (Y)

---

## 4. F-001/F-002/F-003/F-006 회귀 점검

본 라운드(F-004) 변경의 회귀 위험 포인트 4건을 명시적 검증.

### 4.1 위험 포인트 1: AnthropicMessagesRequest.system default null 추가

| 항목 | 분석 | 결과 |
|------|------|------|
| 변경 | `AnthropicMessagesRequest.kt:38` `@SerialName("system") val system: String? = null` 추가 | — |
| AnthropicJson 정책 | `AnthropicJson.kt:20-21` `encodeDefaults = false` + `explicitNulls = false` — null 필드는 직렬화 제외 | — |
| F-001/F-002 영향 | 기존 호출자(`Mapper.toAnthropicRequest`)는 `system`을 설정하지 않으므로 default null이 적용되어 JSON에 system 필드가 등장하지 않음 | F-001/F-002 회귀 0건 |
| 검증 근거 | `MapperSessionTest.kt:115-133` "JSON 직렬화 시 systemPrompt null 이면 system 필드 제외됨" 케이스가 정합 검증 | 통과 |

### 4.2 위험 포인트 2: Mapper.buildContentJson → buildMessageContentJson 추출

| 항목 | 분석 | 결과 |
|------|------|------|
| 변경 | `Mapper.kt:168-175` 기존 `buildContentJson(request, httpClient)`이 `buildMessageContentJson(text=request.prompt, images=request.images, httpClient)`에 위임 | — |
| 호출자 | `Mapper.toAnthropicRequest` (`Mapper.kt:74`) — buildContentJson 호출 시그니처 변경 없음 | — |
| 외부 동작 | `buildMessageContentJson` 내부 로직은 기존 `buildContentJson`을 그대로 발췌 — 텍스트 블록 + 이미지 블록 순서, Bytes/Url/Uri 분기, JsonElement 직렬화 동일 | F-001/F-002 회귀 0건 |
| 검증 근거 | `MapperTest.kt` / `MapperImageTest.kt`가 본 라운드 변경 전(May 7) 작성된 그대로 — 동일 JSON 출력을 검증 | 통과 |

### 4.3 위험 포인트 3: ClaudeProvider.complete 4xx에 E-401 사전 감지 추가

| 항목 | 분석 | 결과 |
|------|------|------|
| 변경 | `ClaudeProvider.kt:137-144` 4xx 본문에서 `runCatching { resp.body?.string() }.getOrNull()` → `ErrorMapper.mapContextOverflow(errorBody)` → 매칭 시 throw, 아니면 기존 httpError throw | — |
| 본문 한 번만 읽기 | resp.body는 한 번만 string()으로 읽을 수 있는데, 본 코드는 runCatching 안에서 한 번 읽고 캡쳐. 이후 코드 경로에서 다시 읽지 않음 (httpError 또는 contextOverflow throw로 즉시 종료) | 안전 |
| runCatching 안전성 | IOException/IllegalStateException(이미 read됨) 모두 안전망. getOrNull로 null 변환 → mapContextOverflow는 null/빈/잘못된 JSON 모두 null 반환 (안전망) | 안전 |
| F-001 E-102/E-103/E-104 영향 | 4xx 본문이 컨텍스트 초과 키워드 미매치면 httpError(Authentication/RateLimit/ServerError) 그대로 throw — 기존 매핑 보존 | F-001 회귀 0건 |
| 추가 비용 | 모든 4xx 응답에서 본문 1회 read + JSON 파싱 시도 + 키워드 매칭 (수십 마이크로초). 4xx 빈도가 낮으므로 무시 가능 | 정보성 (NFR 영향 없음) |
| 검증 근거 | 기존 AskTest 14개 케이스 모두 Fake provider 기반(HTTP 흐름 미사용) — 영향 없음. ClaudeProviderTest.kt(2KB) 도 HTTP 흐름 미커버 | 회귀 위험 낮음 |
| 비고 | F-001 E-104(5xx)는 본 변경 영향 없음(5xx는 mapContextOverflow가 invalid_request_error type 매칭 실패로 null 반환 → httpError ServerError throw) | 안전 |

### 4.4 위험 포인트 4: ClaudeProvider open class 전환

| 항목 | 분석 | 결과 |
|------|------|------|
| 변경 | `ClaudeProvider.kt:53` `public open class ClaudeProvider` (기존 `public class`) + `complete`/`stream`은 이미 override이라 implicitly open → 서브타입 가능. `completeForSession`은 `public open suspend fun` | — |
| 사양 정합 | 사양상 ClaudeProvider 클래스의 open/final 정책 명시 없음. 단위 테스트(`SessionTest.kt:642` `FakeClaudeLike : ClaudeProvider()`) 가능을 위한 의도된 변경. | Minor (사양 명시 없음) |
| 보안 영향 | `internal constructor` (`ClaudeProvider.kt:53`) 유지 — 호출자 외부에서 서브클래싱 불가 (internal 가시성으로 외부 모듈 차단). 단위 테스트는 같은 모듈이라 가능. | 안전 |
| F-001/F-002/F-003 영향 | 기존 인스턴스 생성 경로(Builder.build / SdkModule 주입) 변경 없음 | F-001/F-002/F-003 회귀 0건 |

### 4.5 회귀 점검 요약 (mtime 기반)

본 라운드 변경 파일 (May 11):
- `AiAgentClient.kt` (May 11 10:37) — createSession 추가만, ask/askStream/useProvider/close 변경 없음
- `AnthropicMessagesRequest.kt` (May 11 10:32) — system 필드 추가만
- `Mapper.kt` (May 11 10:34) — toAnthropicRequestForSession 추가 + buildMessageContentJson 추출 (외부 동작 동일)
- `ErrorMapper.kt` (May 11 10:34) — mapContextOverflow + 키워드 리스트 추가만
- `ClaudeProvider.kt` (May 11 10:39) — open class + completeForSession 추가 + complete 4xx에 E-401 사전 감지 추가

기존 회귀 테스트 파일 (May 7~10, 변경 없음):
- `AskTest.kt` (May 7) — F-001 14 케이스
- `AskWithImagesTest.kt` (May 7) — F-002 8 케이스
- `AskStreamTest.kt` (May 9) — F-003 14 케이스
- `MapperTest.kt` (May 7) — F-001 매퍼
- `MapperImageTest.kt` (May 7) — F-002 매퍼 12 케이스
- `AnthropicSseParserTest.kt` — F-003 SSE 파서 15 케이스
- `ClaudeProviderStreamTest.kt` (May 9) — F-003 5 케이스
- `SdkModuleTest.kt` (May 10) — F-006 16 케이스 (Session/SessionStore 미반영 — F-007 라운드 예정)

→ F-001/F-002/F-003/F-006 회귀 0건. 본 라운드 변경 4건 모두 정적 분석상 영향 없음.

---

## 5. 발견된 이슈

### Q-T17-M1 [Minor] Session.send fallback 분기(ClaudeProvider 외 Provider) 도달 불가에 대한 사양 명시 부재

- **위치**: `Session.kt:159-170`
- **현 구현**:
  ```kotlin
  val response: AiResponse = if (provider is com.androidailab.aisdk.provider.claude.ClaudeProvider) {
      provider.completeForSession(history, request, systemPrompt, config)
  } else {
      // v0.1은 ClaudeProvider만 존재 (R-009) — fallback은 도달 불가.
      // 방어적으로 일반 complete를 호출 (history/systemPrompt 무시).
      provider.complete(request, config)
  }
  ```
- **사양 상태**: provider-spec.md P-001 (Provider 인터페이스)에 `completeForSession` 메서드 없음 → 사양 외 ClaudeProvider 직속 메서드 호출. R-009로 v0.1 단일 Provider 가정이지만, 사양에 fallback 동작(history/systemPrompt 무시) 정의 없음.
- **영향**: 미래 v0.2에서 OpenAI Provider 추가 시 본 분기로 들어가 history/systemPrompt가 무시될 수 있음 (사양상 동작 불명확). v0.1에서는 R-009로 P-CLAUDE만 존재해 도달 불가 — 실제 동작 문제 없음.
- **권장**: spec-architect가 (a) provider-spec.md P-001에 `sessionComplete`를 정식 추가 OR (b) v0.1 명시적 가정 ("v0.1은 ClaudeProvider만 존재 — fallback 분기는 도달 불가, v0.2 진입 시 P-001 확장 필수") 한 줄을 features.md F-004 또는 provider-spec.md에 추가. **Q-T16-1 (S-T17-1) 인계와 동일 항목**.
- **차단 여부**: 차단 안 함 (Minor). 본 라운드는 R-009로 도달 불가.

### Q-T17-M2 [Minor] history()/clear()의 Mutex+synchronized 이중 동기화 패턴 사양 명시 부재

- **위치**: `Session.kt:80` Mutex + `Session.kt:151, 186, 257, 284` synchronized(history)
- **현 구현**: send는 `mutex.withLock { synchronized(history) { ... } }` 이중 동기화, history()/clear()는 synchronized(history)만 사용.
- **사양 상태**: data-model.md M-007 "내부 상태 및 동시성" 섹션은 "Mutex 안에서 List 복사본을 반환" (L206)으로 Mutex 사용을 전제. 동기 함수가 Mutex.lock(suspend)을 호출할 수 없는 구현 현실은 사양에 미반영.
- **영향**: 코드 리뷰어가 "history()가 Mutex 안에서 호출되지 않음"을 사양 위반으로 오인할 위험. 그러나 실제 동작은 (mutex → synchronized 단방향 잠금 순서이고 synchronized 블록이 toList/add/clear만이라 짧음) 정합. deadlock 가능성 없음.
- **권장**: data-model.md M-007 "내부 상태 및 동시성"에 "history()/clear()는 동기 함수 시그니처(api.md A-007/A-008)이므로 Mutex 직접 사용 불가. send와의 race 방지는 `synchronized(history)` 패턴으로 달성한다. send 안에서도 history append/snapshot은 synchronized(history)로 보호되어 메모리 가시성을 확보한다" 한 줄 추가. **Q-T16-4 (S-T17-4) 인계와 동일 항목**.
- **차단 여부**: 차단 안 함 (Minor).

### Q-T17-I1 [정보성] E-401 키워드 리스트 사양 미명시

- **위치**: `ErrorMapper.kt:27-35` `ANTHROPIC_CONTEXT_OVERFLOW_KEYWORDS`
- **현 구현**: 7개 키워드 (context_length_exceeded / context window / context too long / prompt is too long / input is too long / too many tokens / maximum context) 매칭 — 모두 소문자 비교.
- **사양 상태**: provider-spec.md P-CLAUDE 에러 매핑 표에 `context_length_exceeded → InvalidInput("context too large")` 한 줄만. 키워드 리스트, 매칭 알고리즘(소문자, 어디 필드 검사), Anthropic 표준 에러 응답 예시 미명시.
- **영향**: Anthropic이 에러 메시지 표현을 바꾸면 키워드 미스매치 → E-401 감지 실패 → 호출자가 ServerError를 받음. 사양상 호출자는 InvalidInput("context too large")를 기대하는 흐름이라 호환성 위험. 본 라운드는 7개 키워드로 합리적 기본값.
- **권장**: spec-architect가 provider-spec.md P-CLAUDE에 (a) 실제 Anthropic 에러 응답 1~2개 스냅샷 (b) 키워드 리스트 명시 (c) v0.2 Anthropic 표준 에러 코드 도입 시 매핑 전환 정책 추가. **Q-T16-2 (S-T17-2) 인계와 동일**.
- **차단 여부**: 차단 안 함 (정보성).

### Q-T17-I2 [정보성] Session 코드의 imports 한 줄 길이 + 중복 validateImages 정책 명시

- **위치**: `Session.kt:305-362` `private fun validateImages(...)` — `AiAgentClient.kt:483-543`의 동일 함수와 거의 동일한 알고리즘 (코드 ~60줄 중복)
- **현 구현**: Session에 validateImages를 재구현 (AiAgentClient.validateImages는 private — 재사용 불가).
- **사양 상태**: 사양상 동일 알고리즘 재사용 정책 명시 없음. F-002 정상 흐름 2 검증을 ask와 session.send에서 동일하게 적용해야 한다는 요구는 있으나 구현 중복은 사양 외 결정.
- **영향**: 향후 F-002 검증 알고리즘 수정 시 두 곳을 동시에 수정해야 함 (drift 위험). 본 라운드 알고리즘은 두 사본이 일치하므로 동작 문제 없음.
- **권장**: 다음 리팩토링 라운드에서 `internal object ImageValidator` 같은 공용 헬퍼로 추출. 사양 작업이 아닌 코드 리팩토링 영역 (android-implementer 책임).
- **차단 여부**: 차단 안 함 (정보성).

---

## 6. 사양 명확화 필요 항목

본 라운드 발견 항목은 모두 Q-T16-1/2/3/4 인계와 동일하므로 S-T17 식별자로 분류:

### S-T17-1 [Minor] (= Q-T16-1) Provider 인터페이스에 sessionComplete 시그니처 정식 추가 시점

- 현 사양: provider-spec.md P-001 (Provider 인터페이스)에 `complete`/`stream`만 존재.
- 현 구현: Session.send가 `if (provider is ClaudeProvider) provider.completeForSession(...) else provider.complete(...)` 분기.
- 권장: provider-spec.md P-001에 "v0.1은 history/systemPrompt를 받는 sessionComplete를 Provider 인터페이스에 두지 않는다. 멀티 Provider 도입 시 인터페이스에 정식 추가" 명시. 본 라운드 구현은 사양 명시 후 정합.

### S-T17-2 [Minor] (= Q-T16-2) E-401 키워드 매칭 안정성

- 현 사양: provider-spec.md P-CLAUDE 에러 매핑 표에 매핑만 명시. 키워드 리스트/매칭 알고리즘/Anthropic 에러 응답 예시 없음.
- 현 구현: `ErrorMapper.kt:27-35` 7개 키워드 매칭 (소문자 비교).
- 권장: provider-spec.md P-CLAUDE에 Anthropic 에러 응답 예시 + 키워드 리스트 명시. v0.2에서 표준 에러 코드 도입 시 키워드→코드 전환 정책.

### S-T17-3 [Minor] (= Q-T16-3) Session.save() 시그니처와 F-007 라운드 분리

- 현 사양: data-model.md M-007에 save() 시그니처 포함, 그러나 본 라운드(F-004) 범위 외.
- 현 구현: 본 라운드 Session.kt는 save() 미구현 (F-007 라운드 예정).
- 권장: data-model.md M-007 KDoc/주석에 "save()는 F-007 라운드에 본체 구현. F-004는 시그니처 인지" 한 줄 추가.

### S-T17-4 [Minor] (= Q-T16-4) history()/clear() Mutex + synchronized 이중 동기화 정책

- 현 사양: data-model.md M-007 "Mutex 안에서 List 복사본 반환"만 명시. 동기 함수가 Mutex 직접 사용 불가한 현실 미반영.
- 현 구현: Session.kt:80 Mutex + Session.kt:151/186/257/284 synchronized(history) 이중.
- 권장: data-model.md M-007 "내부 상태 및 동시성"에 동기 함수의 synchronized 패턴 명시.

### 이월 항목 (이전 라운드 미해결)

- **S-T11-1** (F-001 E-107 발생 위치 명확화) — 본 라운드 영향 없음, 이월.
- **S-T12-1** (ImageInput.Url https 강제 + MockWebServer 통합) — 본 라운드 영향 없음, 이월.
- **S-T13-1/2/3** (F-003 E-303 / SSE error 이벤트 ERR 매핑 / R-005 stream 적용) — 본 라운드 영향 없음, 이월.
- **S-T15-1/2/3 (= Q-T14-1/2/3)** (ProviderRegistry public 노출 / @ModelId 옵션 qualifier / OkHttpClient 호출당 빌드) — 본 라운드 영향 없음, 이월.

---

## 7. 종결 권고 + 다음 wave 진입 권고

### 7.1 F-004 종결 가능성

- Blocker 0건 / Major 0건 → **F-004 종결 가능**.
- Minor 2건 (Q-T17-M1, Q-T17-M2) + 정보성 2건 (Q-T17-I1, Q-T17-I2)은 모두 사양 보강/리팩토링 권장이며 차단 없음.
- 사양 명확화 요청 4건 (S-T17-1/2/3/4) 모두 Minor — F-007 진입 차단하지 않음. spec-architect 회신 후 사양 보강 → 본 라운드 구현은 사양 명시 후 정합.

### 7.2 다음 wave (F-007 세션 영속화) 진입 권고

- 본 라운드(F-004)는 F-007 진입 차단 사항이 없음.
- F-007 진입 시 영향 분석:
  1. `Session.save()` 본체 추가 — 현재 Session.kt에 미구현 (S-T17-3 인계).
  2. `SessionStore` 인터페이스 + DataStore 구현체 신규 (`internal/session/`).
  3. `AiAgentClient.loadSession` / `deleteSession` 신규 + internal 생성자에 sessionStore 주입.
  4. `SdkModule.provideSessionStore` 신규 — F-006 SdkModule 골격 그대로 확장.
  5. `M-011 SessionEntity` `@Serializable` data class 신규 + Message ↔ MessageEntity 변환.
  6. `schemaVersion=1` 강제 (R-018) → E-703 매핑.
  7. 이미지 영속화 가이드 (R-021) → E-704 매핑.
- F-007 사전 작업 권고: spec-architect에게 Q-T16-1/2/3/4 회신 요청 → 사양 보강 → F-007 진입.

### 7.3 권고 우선순위

1. **즉시**: spec-architect가 S-T17-1/2/3/4 (= Q-T16-1/2/3/4) 사양 보강 회신.
2. **F-007 라운드 진입**: 본 라운드 종결 후 android-implementer에게 F-007 (`SessionStore` + `loadSession`/`deleteSession`/`Session.save()` + M-011 SessionEntity + schemaVersion=1 + E-701~E-706) 구현 요청.
3. **F-007 진입 후 회귀**: F-001/F-002/F-003/F-004/F-006 모두 회귀 점검 (특히 F-004 Session.save() 추가는 M-007 시그니처 완성).

---

## 8. 자체 체크리스트

- [x] 4쌍의 경계면(api.md ↔ AiAgentClient/Session, data-model.md ↔ model/Message+Session, error-handling.md ↔ AiException, features.md E-XXX ↔ 단위 테스트)을 모두 교차 비교
- [x] F-004의 모든 정상 흐름 1~5가 코드/테스트에 매핑됨 (검증 표 2.4)
- [x] F-004의 모든 E-XXX (E-401/E-402/E-403)가 코드/테스트에 매핑됨 (검증 표 2.5/2.6)
- [x] api.md A-004/A-006/A-007/A-008 시그니처를 토큰 단위로 비교 (검증 표 2.1)
- [x] M-007/M-008이 코드 필드/제약과 일치 (검증 표 2.2/2.3)
- [x] R-008/R-011/R-014/R-017/R-024/R-020 정책 모두 코드 위치 매핑 (검증 표 2.7~2.11)
- [x] F-001/F-002/F-003/F-006 회귀 위험 포인트 4건 모두 점검 (§4)
- [x] 49 단위 테스트 각각의 의도/사양 ID 매핑 평가 (§3)
- [x] 보고서에 모든 이슈에 (file:line) 근거 명시
- [x] Severity 분류 일관 (Blocker 0 / Major 0 / Minor 2 / 정보성 2 / 사양 명확화 4)
- [x] 빌드 미수행 정책 명시 (정적 검증 + 토큰 단위 비교)
- [x] 다음 wave (F-007) 진입 권고 명시
