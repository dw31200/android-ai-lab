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
| F-000 | 클라이언트 초기화 | P0 | 초안 |
| F-001 | 텍스트 단발 질의 | P0 | 초안 |
| F-002 | 멀티모달 질의 (이미지) | P0 | 초안 |
| F-003 | 스트리밍 응답 | P1 | 초안 |
| F-004 | 세션 컨텍스트 유지 | P1 | 초안 |
| F-005 | Provider 선택/교체 | P0 | 초안 |
| F-006 | Hilt 모듈 노출 | P0 | 초안 |

> 우선순위: P0 (필수) / P1 (중요) / P2 (있으면 좋음)

---

## F-000. 클라이언트 초기화

### 설명
SDK 사용 전 `AiAgentClient`를 Builder 패턴으로 초기화한다.

### 사전 조건
- 호출자 앱 컨텍스트 사용 가능
- 사용할 Provider의 API 키 보유

### 정상 흐름
1. 호출자가 `AiAgentClient.Builder(context)` 생성
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

---

## F-001. 텍스트 단발 질의

### 설명
호출자가 텍스트 프롬프트를 전달하면 LLM 응답을 단일 결과로 반환한다.

### 사전 조건
- F-000으로 클라이언트 초기화 완료
- 네트워크 사용 가능

### 정상 흐름
1. 호출자가 `client.ask(AiRequest(prompt = "..."))` 호출
2. SDK가 활성 Provider를 통해 LLM API 요청 전송
3. 응답을 `AiResponse`(M-002)로 변환
4. 결과: `Result.success(AiResponse)` 반환

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

### 비기능 요구사항
- 응답 시간: p95 5초 (네트워크 정상 기준)
- thread-safe: 동시 호출 가능, 최대 큐 8개
- 취소: cooperative cancellation 지원, 취소 시 OkHttp call cancel 연동

---

## F-002. 멀티모달 질의 (이미지)

### 설명
텍스트 프롬프트와 함께 이미지(Uri/Bytes/Url)를 전달하여 멀티모달 질의를 수행한다.

### 사전 조건
- F-000 완료
- 활성 Provider가 이미지 입력 지원 (provider-spec.md 참조)

### 정상 흐름
1. 호출자가 `client.ask(AiRequest(prompt = "...", images = listOf(...)))` 호출
2. SDK가 각 ImageInput을 Provider 형식으로 인코딩 (Uri/Bytes → base64, Url → URL 또는 fetch)
3. Provider API 호출
4. 응답 변환 후 반환

### 예외 흐름
| ID | 조건 | 처리 방식 |
|----|------|-----------|
| E-201 | 이미지 단일 파일 5MB 초과 | `AiException.InvalidInput("image too large")` |
| E-202 | 이미지 총 합계 20MB 초과 | `AiException.InvalidInput("images total too large")` |
| E-203 | Uri 읽기 실패 (권한·존재 X) | `AiException.InvalidInput("uri unreadable")` |
| E-204 | 지원하지 않는 mimeType | `AiException.InvalidInput("unsupported mime type")` |
| E-205 | 활성 Provider가 이미지 미지원 | `AiException.Configuration("provider does not support images")` |
| E-101~E-108 | F-001과 동일 | F-001 참조 |

### 비기능 요구사항
- 이미지 인코딩은 IO Dispatcher에서 수행
- 지원 mimeType: image/jpeg, image/png, image/webp, image/gif
- 응답 시간: p95 10초 (이미지 1장 기준)

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
| E-101~E-108 | F-001과 동일 (스트림 시작 전 단계) | F-001 참조 |

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
4. 호출자가 `session.history()`로 조회 가능
5. `session.clear()`로 초기화

### 예외 흐름
| ID | 조건 | 처리 방식 |
|----|------|-----------|
| E-401 | 누적 토큰이 모델 한계 초과 | `AiException.InvalidInput("context too large")` |
| E-402 | 세션이 client보다 오래 살아남음 (client 닫힘) | `AiException.Configuration` |
| E-101~E-108 | F-001과 동일 | F-001 참조 |

### 비기능 요구사항
- v0.1: 세션은 메모리에만 유지 (D-002 결정 후 영속화)
- thread-safe: 동일 세션의 동시 send는 직렬 처리 (race condition 방지)

---

## F-005. Provider 선택/교체

### 설명
초기화 시 Provider를 선택하거나, 런타임에 다른 Provider로 교체할 수 있다.

### 사전 조건
- F-000 완료
- 교체 대상 Provider의 API 키가 등록됨

### 정상 흐름
1. 초기화 시: Builder의 `.provider(Provider.CLAUDE)`
2. 런타임 교체: `client.useProvider(Provider.CLAUDE)` (진행 중 요청은 영향 없음, 다음 요청부터 적용)

### 예외 흐름
| ID | 조건 | 처리 방식 |
|----|------|-----------|
| E-501 | 등록되지 않은 Provider 선택 | `AiException.Configuration("unknown provider")` |
| E-502 | Provider별 API 키 누락 | `AiException.Configuration("api key missing for provider X")` |

### 비기능 요구사항
- Provider 교체는 즉시 적용
- thread-safe: 교체 중에도 진행 중 요청에 영향 없음

---

## F-006. Hilt 모듈 노출

### 설명
SDK가 Hilt 모듈을 제공하여 호출자가 `@Inject AiAgentClient`로 받을 수 있게 한다.

### 사전 조건
- 호출자 앱이 Hilt 사용 (`@HiltAndroidApp`)

### 정상 흐름
1. 호출자가 SDK 모듈 의존성 추가
2. `@Inject lateinit var client: AiAgentClient` 또는 `@Inject constructor(private val client: AiAgentClient)`
3. SDK가 제공하는 `AiSdkModule`이 자동 적용

### 예외 흐름
| ID | 조건 | 처리 방식 |
|----|------|-----------|
| E-601 | Hilt 미설정 | 컴파일 타임 에러 (사용자 책임) |
| E-602 | API 키 BuildConfig 미설정 | 런타임에 F-000 E-001과 동일 |

### 비기능 요구사항
- Hilt 미사용 호출자도 Builder 패턴(F-000)으로 사용 가능 (Hilt는 옵션)
