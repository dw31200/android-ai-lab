# 기능 명세

> 작성 규칙
> - 기능 ID: F-숫자 (예: F-001)
> - 예외 ID: E-숫자 (예: E-001)
> - 모든 예외 흐름을 빠짐없이 정의할 것
> - AI CLI 지시 시 기능 ID로 참조 (예: "F-001 구현해줘")

---

## 기능 목록

| ID | 기능명 | 우선순위 | 상태 |
|----|--------|----------|------|
| F-000 | 클라이언트 초기화 | P0 | 라운드 2 |
| F-001 | 텍스트 단발 질의 | P0 | 라운드 2 |
| F-002 | 멀티모달 질의 (이미지) | P0 | 라운드 2 |
| F-003 | 스트리밍 응답 | P1 | 라운드 2 |
| F-004 | 세션 컨텍스트 유지 | P1 | 라운드 2 |
| F-005 | Provider 선택/교체 | P0 | 라운드 2 |
| F-006 | Hilt 모듈 노출 | P0 | 라운드 2 |
| F-007 | 세션 영속화 (DataStore) | P1 | 라운드 2 신규 |
| F-008 | 클라이언트 라이프사이클(close) | P0 | 라운드 2 신규 |

> 우선순위: P0 (필수) / P1 (중요) / P2 (있으면 좋음)

---

## F-000. 클라이언트 초기화

### 설명
SDK 사용 전 `AiAgentClient`를 Builder 패턴으로 초기화한다.

### 사전 조건
- 호출자 앱 컨텍스트 사용 가능
- 사용할 Provider의 API 키 보유

### 정상 흐름
1. 호출자가 `AiAgentClient.builder(context)` 호출하여 Builder 획득 (정적 팩토리, A-001 참조)
2. `.apiKey(...)`, `.provider(...)`, `.model(...)`, `.timeout(...)` 체이닝
3. `.build()` 호출 → 검증 통과 시 `AiAgentClient` 인스턴스 반환

### 예외 흐름
| ID | 조건 | 처리 방식 |
|----|------|-----------|
| E-001 | API 키 미설정 | `build()` 시 `AiException.Configuration` throw |
| E-002 | 알 수 없는 Provider | `AiException.Configuration` throw |
| E-003 | timeout < 1초 | `AiException.Configuration` throw |

### 비기능 요구사항
- 응답 시간: 즉시 (네트워크 호출 없음)
- thread-safe: Builder는 단일 스레드 사용 가정, 결과 client는 thread-safe
- API 키는 메모리에서만 보관 (D-003), Builder 빌드 후에도 디스크/로그에 평문 노출 금지

---

## F-001. 텍스트 단발 질의

### 설명
호출자가 텍스트 프롬프트를 전달하면 LLM 응답을 단일 결과로 반환한다.

### 사전 조건
- F-000으로 클라이언트 초기화 완료
- 네트워크 사용 가능
- client가 close되지 않은 상태(F-008 참조)

### 정상 흐름
1. 호출자가 `client.ask(AiRequest(prompt = "..."))` 호출
2. SDK가 활성 Provider를 통해 LLM API 요청 전송
3. Provider 응답 수신
4. 응답 검증
   - `text`가 빈 문자열이고 `finishReason`이 `END_TURN`/`STOP_SEQUENCE`인 경우 → 빈 응답을 그대로 성공 반환 (호출자가 finishReason으로 판별 가능)
   - `text`가 빈 문자열이고 `finishReason`이 `MAX_TOKENS`/`OTHER`인 경우 → `AiException.ServerError(code=-1, message="empty response")`로 실패 반환
   - 그 외 정상 텍스트는 그대로 통과
5. `AiResponse`(M-002)로 변환
6. 결과: `Result.success(AiResponse)` 반환

### 예외 흐름
| ID | 조건 | 처리 방식 |
|----|------|-----------|
| E-101 | 네트워크 연결 없음 | `Result.failure(AiException.Network)` |
| E-102 | API 인증 실패 (401) | `Result.failure(AiException.Authentication)` |
| E-103 | 레이트 리밋 (429) | `Result.failure(AiException.RateLimit(retryAfter))` |
| E-104 | 서버 오류 (5xx) | `Result.failure(AiException.ServerError)` |
| E-105 | 응답 파싱 실패 | `Result.failure(AiException.ServerError(code=parse))` |
| E-106 | 호출자 코루틴 취소 | 진행 중 요청 중단, `CancellationException` 그대로 전파 |
| E-107 | 빈 prompt | `Result.failure(AiException.InvalidInput)` |
| E-108 | 타임아웃 | `Result.failure(AiException.Network)` (cause=SocketTimeoutException) |
| E-109 | client가 close된 상태에서 호출 | `Result.failure(AiException.Configuration("client closed"))` |
| E-110 | 응답 검증 실패 (정상 흐름 4단계) | `Result.failure(AiException.ServerError(code=-1, message="empty response"))` |

### 비기능 요구사항
- 응답 시간: p95 5초 (네트워크 정상 기준)
- thread-safe: 동시 호출 가능, 최대 큐 8개
- 취소: cooperative cancellation 지원, 취소 시 OkHttp call cancel 연동
- 자동 재시도 없음(D-005). ERR-001/ERR-002 발생 시 호출자가 재호출

---

## F-002. 멀티모달 질의 (이미지)

### 설명
텍스트 프롬프트와 함께 이미지(Uri/Bytes/Url)를 전달하여 멀티모달 질의를 수행한다.

### 사전 조건
- F-000 완료
- 활성 Provider가 이미지 입력 지원 (provider-spec.md 참조)

### 정상 흐름
1. 호출자가 `client.ask(AiRequest(prompt = "...", images = listOf(...)))` 호출
2. SDK가 각 ImageInput을 검증 (크기/mimeType — Uri는 본 단계에서 즉시 거부, 아래 "v0.1 ImageInput.Uri 정책" 참조)
3. 각 ImageInput을 Provider 형식으로 인코딩 (Bytes → base64, Url → fetch 후 base64. v0.1에서 Uri는 도달 불가)
4. Provider API 호출
5. 응답 변환 후 반환

### v0.1 ImageInput.Uri 정책 (D-004 연장)
- v0.1에서 SDK는 `ImageInput.Uri`를 자동 resolve 하지 않는다. 호출자는 `ContentResolver.openInputStream(uri)`로 ByteArray를 직접 읽고 `ImageInput.Bytes(data, mimeType)`로 변환한 뒤 SDK에 전달해야 한다.
- SDK는 진입 시점(`ask`/`askStream`/`session.send`의 검증 단계)에 `ImageInput.Uri`를 만나면 **권한·존재 여부와 무관하게 즉시** `AiException.InvalidInput("uri unreadable")`로 거부한다 (E-203).
- 사유: ContentResolver 호출은 호출자 컨텍스트·권한·라이프사이클에 의존하므로 SDK가 자동 수행하면 책임 경계가 모호해진다. D-004의 "이미지 가공은 호출자 책임" 정책의 연장.
- v0.2에서 자동 resolve 옵션(예: Builder에 `ContentResolver` 주입) 검토 예정.

### 예외 흐름
| ID | 조건 | 처리 방식 |
|----|------|-----------|
| E-201 | 이미지 단일 파일 5MB 초과 | `AiException.InvalidInput("image too large")` (D-004: SDK는 검증만, 자동 리사이즈 안 함) |
| E-202 | 이미지 총 합계가 M-001의 20MB 한계 초과 | `AiException.InvalidInput("images total too large: ...")` (메시지 패턴: 합계 케이스는 size 정보 표기) |
| E-203 | `ImageInput.Uri` 도달 시 (v0.1 정책 — D-004 연장, 정상 흐름 "v0.1 ImageInput.Uri 정책" 섹션 참조) | `AiException.InvalidInput("uri unreadable")` (호출자가 사전에 ContentResolver로 Bytes 변환 미수행) |
| E-204 | 지원하지 않는 mimeType | `AiException.InvalidInput("unsupported mime type")` |
| E-205 | 활성 Provider가 이미지 미지원 | `AiException.Configuration("provider does not support images")` |
| E-206 | `ImageInput.Url` fetch 실패 (HTTP 4xx/5xx, 타임아웃, DNS 실패, redirect 한계 초과) | HTTP 4xx/잘못된 URL → `AiException.InvalidInput("image url unreachable: {detail}")`, 5xx/timeout/DNS → `AiException.Network(cause)` |
| E-207 | 이미지 디코딩 실패 (손상된 파일, mimeType 위조 — 헤더 매직 넘버 불일치) | `AiException.InvalidInput("image decode failed: {detail}")` |
| E-208 | 이미지 개수가 활성 Provider의 `Capabilities.maxImagesPerRequest` 초과 (M-001의 ≤10 제약은 통과했으나 Provider 한계가 더 작은 경우. v0.1 P-CLAUDE는 10이라 도달 케이스 없음, 미래 Provider 대비 방어) | `AiException.InvalidInput("images total too large: count=...")` (메시지 패턴: 개수 케이스는 `count=` 토큰 포함, E-202와 텍스트 구별) |
| E-101~E-110 | F-001과 동일 | F-001 참조 |

### 비기능 요구사항
- 이미지 인코딩은 IO Dispatcher에서 수행
- 지원 mimeType: image/jpeg, image/png, image/webp, image/gif (헤더 매직 넘버로 실제 검증)
- 응답 시간: p95 10초 (이미지 1장 기준)
- `ImageInput.Url` fetch 시 redirect는 최대 3회까지 허용
- 자동 리사이즈는 수행하지 않음 (D-004) — 호출자가 사전에 5MB 이내로 처리해야 함

---

## F-003. 스트리밍 응답

### 설명
LLM 응답을 토큰 단위로 받아 Flow로 방출한다.

### 사전 조건
- F-000 완료
- 활성 Provider가 스트리밍 지원

### 정상 흐름
1. 호출자가 `client.askStream(AiRequest(...))` 호출
2. SDK가 SSE/chunked 스트림 연결
3. 각 chunk를 `AiStreamEvent.Delta(text)`로 방출
4. 스트림 종료 시 `AiStreamEvent.Done(AiResponse)` 방출

### 예외 흐름
| ID | 조건 | 처리 방식 |
|----|------|-----------|
| E-301 | 스트림 도중 연결 끊김 | `AiStreamEvent.Error(AiException.Network)` 방출 후 종료 |
| E-302 | 스트림 도중 코루틴 취소 | 연결 cancel, `CancellationException` 전파 |
| E-303 | 활성 Provider가 스트리밍 미지원 | 호출 즉시 `AiException.Configuration` throw |
| E-101~E-110 | F-001과 동일 (스트림 시작 전 단계) | F-001 참조 |

### 비기능 요구사항
- 첫 토큰 도착(TTFT): p95 3초
- backpressure: cold Flow, collector가 느리면 자연스럽게 producer가 대기
- 메모리: chunk를 누적하지 않고 방출

---

## F-004. 세션 컨텍스트 유지

### 설명
연속된 질의 간 대화 이력을 유지하여 LLM에 함께 전달한다.

### 사전 조건
- F-000 완료

### 정상 흐름
1. 호출자가 `val session = client.createSession(systemPrompt = "...")` 호출
2. `session.send(request)` 호출 → 이전 메시지 + 현재 요청을 합쳐 Provider 전송
3. 응답을 세션 history에 추가
4. 호출자가 `session.history()`로 조회 가능 (immutable snapshot 반환, R-011)
5. `session.clear()`로 초기화

### systemPrompt 정책 (R-008)
- `systemPrompt`는 Session 생성 시 1회 제공되는 메타데이터로, **history에 포함되지 않는다**.
- `session.history()` 반환 결과에 `Role.SYSTEM` 메시지는 등장하지 않음.
- Provider 전송 시점에는 SDK 내부에서 systemPrompt를 시스템 메시지 위치에 삽입하여 전송 (Provider별 표현 차이는 Mapper가 처리).
- 호출자가 동적으로 system 메시지를 사용해야 한다면 v0.2에서 별도 API 검토.

### 컨텍스트 한계 검증 알고리즘 (E-401)
- SDK는 사전 토큰 카운트 추정을 수행하지 않는다 (Provider별 토크나이저 정확도 차이로 무의미).
- 대신 Provider 응답에서 컨텍스트 초과 에러(예: Anthropic의 `invalid_request_error` with token count)를 감지하면 `AiException.InvalidInput("context too large")`로 변환하여 throw.
- 호출자는 E-401 발생 시 `session.clear()` 또는 history 일부를 제거 후 재호출.

### 예외 흐름
| ID | 조건 | 처리 방식 |
|----|------|-----------|
| E-401 | 누적 토큰이 모델 한계 초과 (Provider 응답으로 감지) | `AiException.InvalidInput("context too large")` |
| E-402 | 세션이 client보다 오래 살아남음 (client.close() 호출 후 session.send) | `AiException.Configuration("client closed")` |
| E-403 | 동일 Session에 대해 send가 동시 호출됨 | 두 번째 호출은 첫 호출 완료까지 직렬 대기 (Mutex). 취소 시 첫 호출 정상 완료 후 두 번째도 취소 전파 |
| E-101~E-110, E-201~E-207 | F-001/F-002와 동일 | 해당 F 참조 |

### 비기능 요구사항
- v0.1: 세션은 메모리에 유지, 호출자가 명시적으로 `session.save()` 호출 시 DataStore에 영속화 (F-007)
- thread-safe: 동일 세션의 동시 send는 Mutex로 직렬 처리 (race condition 방지)
- `history()` 반환은 호출 시점의 immutable snapshot (이후 send와 race 없음)

---

## F-005. Provider 선택/교체

### 설명
초기화 시 Provider를 선택하거나, 런타임에 다른 Provider로 교체할 수 있다.

### 사전 조건
- F-000 완료
- 교체 대상 Provider의 API 키가 등록됨

### 정상 흐름
1. 초기화 시: Builder의 `.provider(ProviderId.CLAUDE)`
2. 런타임 교체: `client.useProvider(ProviderId.CLAUDE)` (진행 중 요청은 영향 없음, 다음 요청부터 적용)

### 동시성 모델 (R-007)
- 활성 Provider 식별자는 `AtomicReference<ProviderId>`로 보관
- `useProvider()` 호출 시 atomic set만 수행 (수 ns)
- 진행 중 요청은 호출 시점에 이미 Provider 인스턴스를 자기 스택에 캡쳐했으므로 변경 영향 없음
- 새 요청은 ask/askStream 진입 시 atomic get으로 현재 Provider 조회
- Session도 동일: `session.send()` 진입 시 atomic get하여 그 시점의 Provider 사용 (Session 생성 시 고정되지 않음 — R-014)

### Session과 Provider의 관계 (R-014)
- Session은 특정 Provider에 묶이지 않는다.
- `session.send()` 호출 시점의 client 활성 Provider를 사용한다.
- 호출자가 `client.useProvider()`로 교체한 후 같은 Session에서 send하면 새 Provider로 전송.
- 단, history가 이전 Provider의 응답으로 채워진 경우 새 Provider가 그 history를 처리할 수 있는지는 호출자 책임 (E-401 가능성).

### 예외 흐름
| ID | 조건 | 처리 방식 |
|----|------|-----------|
| E-501 | 등록되지 않은 Provider 선택 | `AiException.Configuration("unknown provider")` |
| E-502 | Provider별 API 키 누락 | `AiException.Configuration("api key missing for provider X")` |

### 비기능 요구사항
- Provider 교체는 즉시 적용 (atomic set, 수 ns)
- thread-safe: AtomicReference 기반, 교체 중에도 진행 중 요청에 영향 없음

---

## F-006. Hilt 모듈 노출

### 설명
SDK가 Hilt 모듈을 제공하여 호출자가 `@Inject AiAgentClient`로 받을 수 있게 한다.

### 사전 조건
- 호출자 앱이 Hilt 사용 (`@HiltAndroidApp`)

### 정상 흐름
1. 호출자가 SDK 모듈 의존성 추가
2. SDK가 제공하는 `AiSdkModule`이 자동 적용
3. 호출자가 `@Inject lateinit var client: AiAgentClient` 또는 생성자 주입

### 예외 흐름
| ID | 조건 | 처리 방식 |
|----|------|-----------|
| E-601 | Hilt 미설정 | 컴파일 타임 에러 (사용자 책임) |
| E-602 | API 키 BuildConfig 미설정 | 런타임에 F-000 E-001과 동일 |

### 사용 예 (R-012)
```kotlin
// 1. Application 클래스
@HiltAndroidApp
class MyApplication : Application()

// 2. SDK가 제공하는 AiSdkModule이 자동으로 AiAgentClient를 @Provides로 노출
//    (호출자는 별도 Module 작성 불필요)

// 3. ViewModel 생성자 주입
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val client: AiAgentClient,
) : ViewModel() {
    fun ask(prompt: String) = viewModelScope.launch {
        val result = client.ask(AiRequest(prompt))
        // ...
    }
}

// 4. Activity / Composable에서 hiltViewModel() 사용
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) { /* ... */ }
```

### 비기능 요구사항
- Hilt 미사용 호출자도 Builder 패턴(F-000)으로 사용 가능 (Hilt는 옵션)

---

## F-007. 세션 영속화 (DataStore)

### 설명
호출자가 `Session`을 디스크(Preferences DataStore)에 저장하고 이후 복원할 수 있다. process death / 재시작 시 대화 이력 보존을 위해 제공한다 (D-002 결정으로 v0.1에 포함).

### 사전 조건
- F-000 완료
- 영속화 대상 Session이 client에서 생성됨
- 디스크 쓰기 권한 (앱 내부 저장소, 별도 권한 불필요)

### 정상 흐름

**저장 흐름**:
1. 호출자가 `session.save()` (suspend) 호출
2. SDK가 Session 상태(systemPrompt, history)를 `SessionEntity`(M-011)로 직렬화
3. `Dispatchers.IO`로 전환
4. DataStore의 Preferences에 `session:{sessionId}` 키로 JSON 저장
5. 결과: `Result.success(sessionId)` 반환. 반환되는 sessionId는 **Session 생성 시 자동 부여된 UUID**(M-007.sessionId)이며, 호출자가 save 시점에 다른 식별자를 지정할 수 없음 (R-017/R-024 라운드 3 결정 — v0.1은 자동 UUID만 지원, 호출자 명시 부여는 v0.2)

**복원 흐름**:
1. 호출자가 `client.loadSession(sessionId)` (suspend) 호출
2. SDK가 `Dispatchers.IO`로 전환
3. DataStore에서 `session:{sessionId}` 키로 JSON 조회
4. `SessionEntity`로 역직렬화 → 새 `Session` 인스턴스 구성
5. 결과: `Result.success(Session)` 반환

**삭제 흐름**:
1. 호출자가 `client.deleteSession(sessionId)` (suspend) 호출
2. DataStore에서 해당 키 제거
3. 결과: `Result.success(Unit)`

### 트리거 정책 및 합리적 기본값 (사양 결정)
- **명시적 save 모델 채택** (자동 저장 안 함). 사유:
  - 자동 저장은 매 send마다 disk IO 발생 → 성능 비용
  - 호출자가 어느 시점에 영속화할지 도메인 정책에 따라 결정해야 함 (예: 백그라운드 진입, 화면 종료)
  - 명시적 모델은 단순하고 테스트 가능
- 자동 저장 옵션은 v0.2 검토 사항으로 명시 (out-of-scope)

### 동시성 모델
- 동일 sessionId에 대해 save/load/delete가 동시 호출되면 DataStore의 transactional update로 직렬화됨
- 동일 Session 인스턴스에서 send 진행 중에 save 호출 시: save는 send 완료까지 Mutex 대기 (history 무결성 보장)

### 다중 인스턴스 정책 (R-019 라운드 3)
- 같은 `sessionId`를 가진 **여러 Session 인스턴스가 동시에 존재**할 수 있는 경우(예: 호출자가 `loadSession(id)`을 두 번 호출하여 인스턴스를 두 개 만든 경우):
  - 두 인스턴스의 `send`는 각자 별도 Mutex로 직렬화되며, 서로 history를 공유하지 않는다 (각 인스턴스가 자기 메모리 상의 history를 가짐).
  - 두 인스턴스가 각각 `save()`를 호출하면 DataStore의 transactional update 안에서 직렬화되어 **마지막 save가 이전 save를 덮어쓴다 (last-write-wins)**.
  - SDK는 이 충돌을 자동 검출하지 않는다 (충돌 검출/병합은 호출자 도메인 정책).
  - 호출자는 동일 sessionId에 대해 단일 Session 인스턴스만 사용하도록 보장할 책임이 있다.
- 다중 프로세스(같은 앱의 별도 프로세스, WorkManager 등)에서 같은 DataStore 파일을 동시에 사용하는 시나리오는 v0.1에서 **지원하지 않음 (Out of Scope, overview.md 참조)**. DataStore Preferences가 단일 프로세스 권장이기 때문.

### 데이터 크기 정책
- 단일 Session의 직렬화 결과가 1MB 초과 시 `AiException.InvalidInput("session too large to persist")`로 실패 (DataStore Preferences는 파일 전체를 한 번에 읽고 쓰므로 크기 큰 history는 부적합)
- 1MB 초과 history 보관이 필요한 경우 호출자가 history를 직접 잘라 save 또는 v0.2의 외부 저장소 옵션 대기

### 이미지 영속화 운영 가이드 (R-021 라운드 3)
- `ImageInput.Bytes`는 영속화 시 base64로 인코딩되며, 단일 이미지 5MB는 base64 ≈ 6.7MB로 단일 이미지가 포함된 history는 사실상 1MB 한계를 초과한다 (E-704 발생 가능성 매우 높음).
- 따라서 **이미지가 포함된 history를 영속화하려는 호출자는 다음 중 하나를 선택해야 한다**:
  1. (권장) save 직전에 history에서 이미지를 제거(또는 텍스트 요약으로 대체)한 별도의 Session으로 save
  2. 이미지 첨부 메시지를 저장하지 않고 텍스트 메시지만 영속화 (호출자가 history를 사전에 필터링)
  3. v0.2의 외부 저장소(BLOB) 옵션을 기다림
- SDK는 자동으로 이미지를 제외하거나 압축하지 않는다 (D-004 정책의 연장 — 이미지 가공은 호출자 책임). 1MB 한계 초과 시 E-704로 거부할 뿐이다.
- `ImageInput.Url`은 URL 문자열만 보관하므로 영속화 크기 영향이 작다. 단, 복원 시 URL fetch 시 보안 정책은 R-023 항목 참조.

### 예외 흐름
| ID | 조건 | 처리 방식 |
|----|------|-----------|
| E-701 | 저장 실패 (디스크 가득, IO 오류) | `Result.failure(AiException.IOError("save failed: {detail}"))` |
| E-702 | 복원 시 sessionId 없음 | `Result.failure(AiException.InvalidInput("session not found: {sessionId}"))` |
| E-703 | 복원 시 손상된 데이터 (역직렬화 실패) **또는 schemaVersion 미지원** (R-018) | `Result.failure(AiException.IOError("session data corrupted"))` (역직렬화 실패) / `Result.failure(AiException.IOError("session schema unsupported: v={loaded}"))` (schemaVersion ≠ 1). SDK는 손상/미지원 키를 자동 삭제하지 않음 (호출자가 deleteSession으로 명시적 삭제) |
| E-704 | 직렬화 결과 1MB 초과 | `Result.failure(AiException.InvalidInput("session too large to persist"))` |
| E-705 | client가 close된 상태에서 save/load/delete 호출 | `Result.failure(AiException.Configuration("client closed"))` |
| E-706 | 코루틴 취소 (저장/복원 도중) | `CancellationException` 전파, 부분 쓰기는 DataStore의 atomic write로 방지 |

### 비기능 요구사항
- 디스크 IO는 반드시 `Dispatchers.IO`에서 수행, 호출 스레드 블로킹 금지
- 단일 save 응답 시간: p95 500ms (history 100 메시지 기준)
- 단일 load 응답 시간: p95 300ms
- 직렬화 형식: JSON (kotlinx.serialization), 향후 스키마 변경 시 `schemaVersion` 필드로 마이그레이션
- schemaVersion v0.1 정책 (R-018 라운드 3): v0.1은 `schemaVersion = 1`만 인정. 로드 시 `schemaVersion != 1`이면 자동 마이그레이션을 시도하지 않고 즉시 `E-703`으로 거부 (`AiException.IOError("session schema unsupported: v={loaded}")`). v0.2부터 `internal object SessionMigrations`에 `from1_to2(json) → json` 같은 함수를 등록하는 패턴으로 마이그레이션 추가 (data-model.md M-011 참조)
- 보안: API 키는 Session에 포함되지 않음 (D-003) → 영속화 데이터에 절대 포함 금지
- 이미지 데이터: `ImageInput.Bytes`의 `data: ByteArray`는 영속화하지 않고 base64로 인코딩 (M-011 참조). `ImageInput.Uri`는 URI 문자열만 보관 (앱 재설치 시 복원 후 read 실패는 호출자 처리 책임)
- `ImageInput.Url` 영속화/복원 보안 (R-023 라운드 3): SDK는 영속화 시 URL을 그대로 보관하고 복원 시 URL을 그대로 반환한다. URL이 외부망/내부망/loopback을 가리키는지 판별하지 않는다(SSRF 방어 미수행). 복원된 URL을 다시 send에 사용하기 전에 도메인 allowlist 적용 등 SSRF 방어는 **호출자 책임**. SDK는 URL 문자열의 형식 검증(`https://` 스킴 등)만 수행한다. URL allowlist 옵션은 v0.2 검토 (provider-spec.md 보안 섹션 참조)

---

## F-008. 클라이언트 라이프사이클 (close)

### 설명
호출자가 명시적으로 `AiAgentClient`를 종료할 수 있다. close 후 모든 API 호출은 실패한다 (R-002).

### 사전 조건
- F-000 완료

### 정상 흐름
1. 호출자가 `client.close()` 호출
2. SDK가 진행 중 요청을 모두 취소 (OkHttp dispatcher.cancelAll()). 진행 중 코루틴은 케이스 A 시맨틱(아래 "close ↔ 진행 중/이후 호출 시맨틱")에 따라 `CancellationException`으로 종결됨
3. 내부 코루틴 스코프 cancel
4. DataStore 핸들 해제(있다면)
5. 이후 ask/askStream/createSession/save/load/delete 새 호출은 케이스 B 시맨틱에 따라 처리 (suspend/Flow는 `Result.failure(AiException.Configuration("client closed"))` 반환, 동기 함수는 throw)

### close 정책
- close는 idempotent (여러 번 호출 가능, 두 번째부터 no-op)
- close는 thread-safe
- Hilt 주입된 client는 `ApplicationComponent` scope이므로 일반적으로 close 불필요. 호출자가 명시적으로 close 호출한 경우만 발효

### close ↔ 진행 중/이후 호출 시맨틱 (R-020 라운드 3)

`close()`와 `ask`/`askStream`/`session.send`/`session.save`/`loadSession`/`deleteSession`의 상호작용을 두 케이스로 명확히 구분한다. 두 케이스는 다른 시맨틱을 가지며, 호출자는 코드에서 둘 다 처리해야 한다.

**케이스 A — close 호출 시점에 이미 진행 중인 호출 (in-flight)**
- 진행 중인 코루틴은 SDK가 내부적으로 cancel 신호를 보내며, 코루틴이 cooperative cancellation 지점에서 `CancellationException`을 throw 한다.
- `Result.failure(...)`로 변환되지 않는다. 표준 코루틴 취소 시맨틱(structured concurrency)을 그대로 따른다.
- 호출자는 `try { result = client.ask(...) } catch (e: CancellationException) { ... }` 또는 상위 코루틴 스코프의 취소로 처리한다. 일반 `runCatching`은 `CancellationException`을 다시 throw하는 표준 동작이며 SDK는 이를 변경하지 않는다.

**케이스 B — close 완료 후 새로 호출된 API**
- close가 끝난 뒤 새로 들어오는 `ask`/`askStream`/`createSession`/`session.send`/`session.save`/`loadSession`/`deleteSession`은 **suspend/Flow 함수의 경우 즉시 `Result.failure(AiException.Configuration("client closed"))`를 반환**한다 (E-109/E-402/E-705/E-303 참조).
- 동기 함수의 경우 (`createSession`, `useProvider`, `Session.history`, `Session.clear`) → `AiException.Configuration("client closed")`를 throw 한다.
- `askStream`은 collect 시작 즉시 종결 이벤트로 `AiStreamEvent.Error(AiException.Configuration("client closed"))`를 한 번 emit 후 Flow 종료한다 (혹은 즉시 throw 후 Flow가 onError로 전파).

**구분 기준**: 호출자가 코드에서 `client.close()`를 호출한 시점을 기준으로, 그 호출이 시작된 코루틴이 cancel 영향권에 들어가면 케이스 A, 그렇지 않은 새 호출은 케이스 B다. 두 케이스의 차이는 `Result.failure`(B) vs `CancellationException` throw(A)이다.

이 정책은 `A-002 ask` / `A-003 askStream` / `A-006 send` / `A-009 close` / `A-010 save` / `A-011 loadSession` / `A-012 deleteSession`의 동작 섹션에서 동일하게 적용된다.

### 예외 흐름
| ID | 조건 | 처리 방식 |
|----|------|-----------|
| E-801 | close 도중 IO 예외 | 무시하고 close 진행 (best-effort), 로그만 남김 |

### 비기능 요구사항
- close 응답 시간: p95 100ms (취소 신호 전파 후 즉시 반환)
- close 후 어떤 API도 호출자 코루틴을 멈추지 않음 (즉시 실패 반환)
