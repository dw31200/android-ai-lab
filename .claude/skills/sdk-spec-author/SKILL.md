---
name: sdk-spec-author
description: SDK 모듈의 SDD 사양 문서를 작성하는 스킬. overview/features/api/data-model/error-handling/provider-spec 6종 문서를 본 프로젝트의 ID 컨벤션(F-/E-/M-/ERR-)으로 작성한다. 새 SDK 사양 작성, 사양 초안 작성, 사양 추가/수정/보완, "사양 만들어줘", "spec 작성", "F-XXX 추가" 같은 요청 시 반드시 사용할 것. SDK가 아닌 일반 앱 사양은 features/screens/data-model/error-handling 4종으로 축소 적용.
---

# sdk-spec-author

## 언제 이 스킬을 쓰는가

- 신규 SDK 모듈의 사양 초안 작성
- 기존 사양에 새 F-XXX 추가
- 사양 검토 결과 반영하여 개선
- 멀티모달, Provider 추상화, 비동기 흐름이 포함된 SDK 사양 작성

## 작성 원칙

### 1. ID는 변경하지 않는다

한 번 부여한 ID(F-001, M-001 등)는 절대 바꾸지 않는다. 폐기는 가능(상태: deprecated)하지만 재사용은 금지. 후속 단계가 모두 ID로 참조하기 때문이다.

### 2. 정상 흐름 1줄, 예외 흐름 N줄

좋은 사양은 정상 흐름이 짧고 예외 흐름이 길다. 정상 흐름을 길게 쓰는 충동을 참고, 그 에너지를 예외 흐름에 쓴다.

### 3. NFR은 측정 가능한 기준만

| 나쁜 예 | 좋은 예 |
|---------|---------|
| 빠르게 응답 | p95 응답시간 3초 이내 (캐시 미스 기준) |
| 안전하게 저장 | API 키는 BuildConfig 또는 EncryptedSharedPreferences에만 저장 |
| 동시성 처리 | AiAgentClient는 thread-safe; 동시 요청 최대 8개 큐잉 |

### 4. 한국어 + 영어 식별자

설명은 한국어, 코드 식별자(클래스/함수/필드명)는 영어. 본 프로젝트 컨벤션이다.

### 5. 사양은 토론용 문서

사양 작성 시 결정이 갈리는 지점은 "## 미해결 결정" 섹션에 옵션 A/B/C로 나열한다. 검토자가 토론을 시작할 지점이 된다.

## 6종 문서 작성 가이드

### overview.md — 무엇/왜/범위

```markdown
# {SDK 이름}

## 목적
> 한 문장으로: 이 SDK는 [누가] [무엇을] 하기 위한 모듈이다.

## 핵심 가치
- 해결하는 문제:
- 기존 방법과의 차이:

## 범위 (Scope)
### In Scope
- 기능 A (F-001)
- 기능 B (F-002)

### Out of Scope
- 기능 C — 이유:
- 기능 D — 이유:

## 기술 스택
(프로젝트 표준 + SDK 특화 추가)

## 의존성 정책
- 외부 라이브러리는 최소화 (이유: SDK 사용처의 충돌 방지)
- 허용 라이브러리 목록:

## 미해결 결정
| 항목 | 옵션 | 권장 | 결정 |
|------|------|------|------|
| HTTP 클라이언트 | OkHttp / Ktor | OkHttp (안드로이드 표준) | 미정 |
```

### features.md — F-XXX 기능 명세

기존 템플릿을 그대로 사용하되, SDK는 "사용자 액션" 대신 "호출자(client) 액션"으로 표현.

```markdown
## F-001. 텍스트 단발 질의

### 설명
호출자가 텍스트 프롬프트를 전달하면 LLM 응답을 단일 결과로 반환한다.

### 사전 조건
- AiAgentClient가 초기화되어 있음 (API 키 포함)
- 네트워크 사용 가능

### 정상 흐름
1. 호출자가 client.ask(AiRequest(prompt = "...")) 호출
2. SDK가 Provider를 통해 LLM API 요청
3. 응답을 AiResponse로 변환하여 반환
4. 결과: Result.success(AiResponse)

### 예외 흐름
| ID | 조건 | 처리 방식 |
|----|------|-----------|
| E-001 | API 키 누락 | AiException.Configuration 즉시 throw |
| E-002 | 네트워크 오류 | AiException.Network로 래핑, 재시도 정책 적용 |
| E-003 | 응답 파싱 실패 | AiException.ServerError(code=parse) |
| E-004 | 호출자 코루틴 취소 | 진행 중 요청 중단, CancellationException 전파 |

### 비기능 요구사항
- 응답 시간: p95 5초 (네트워크 정상 기준)
- 동시성: thread-safe, 동시 호출 가능
- 취소: cooperative cancellation 지원
```

### api.md — 공개 API 표면 (SDK 특화, screens.md 대체)

```markdown
# 공개 API

> 작성 규칙
> - 모든 public 함수의 시그니처를 토큰 단위로 명시
> - suspend / Flow 여부 명시
> - 관련 F-XXX, M-XXX 참조

## API 목록

| ID | 이름 | 종류 | 관련 F-ID |
|----|------|------|-----------|
| A-001 | AiAgentClient.Builder | Builder | F-000 |
| A-002 | AiAgentClient.ask | suspend fun | F-001 |
| A-003 | AiAgentClient.askStream | Flow | F-003 |

---

## A-002. AiAgentClient.ask

### 시그니처
```kotlin
suspend fun ask(request: AiRequest): Result<AiResponse>
```

### 파라미터
| 이름 | 타입 | 필수 | 설명 |
|------|------|------|------|
| request | AiRequest (M-001) | Y | 질의 요청 |

### 반환
- 성공: `Result.success(AiResponse)` (M-002)
- 실패: `Result.failure(AiException)` (ERR-001~ERR-006)

### 동작 (관련 F-001)
- 정상 흐름: F-001 정상 흐름 1~4
- 예외 흐름: E-001 ~ E-004

### 사용 예
(짧은 사용 예 코드 1개)
```

### data-model.md — 요청/응답/멀티모달 모델

기존 템플릿에 SDK 특화 추가:

```markdown
## M-001. AiRequest

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| prompt | String | Y | 텍스트 프롬프트 |
| images | List<ImageInput> | N | 이미지 입력 (멀티모달) |
| videos | List<VideoInput> | N | 영상 입력 (멀티모달) |
| maxTokens | Int | N | 기본값 1024 |
| temperature | Float | N | 기본값 0.7, 범위 0.0~2.0 |

### Kotlin 정의
(data class 정의)

---

## M-003. ImageInput (sealed class)

| Variant | 설명 |
|---------|------|
| Uri | content:// 또는 file:// URI |
| Bytes | ByteArray + mimeType |
| Url | https URL |

### 직렬화 규칙
- 모든 variant는 Provider 전송 직전 base64로 변환
- Url variant는 Provider가 URL fetch를 지원하는 경우 그대로 전달
```

### error-handling.md — ERR-XXX 에러 코드

기존 템플릿 그대로 사용 + AiException sealed class 매핑 표 추가:

```markdown
## ERR ↔ AiException 매핑

| ERR ID | AiException Variant | 호출자 권장 처리 |
|--------|--------------------|--------------|
| ERR-001 | AiException.Network | 재시도 |
| ERR-002 | AiException.RateLimit | retryAfter 후 재시도 |
| ERR-003 | AiException.Authentication | API 키 재설정 |
| ERR-004 | AiException.Configuration | 설정 수정 |
| ERR-005 | AiException.InvalidInput | 입력 검증 |
| ERR-006 | AiException.ServerError | 보고/재시도 |
```

### provider-spec.md — Provider 추상화 (SDK 특화)

```markdown
# Provider 사양

## 목적
여러 LLM 백엔드(Claude, OpenAI, Gemini 등)를 동일한 인터페이스로 호출하기 위한 추상화.

## Provider 인터페이스 (P-001)

### 시그니처
```kotlin
interface Provider {
    val id: String
    suspend fun complete(request: AiRequest, config: ProviderConfig): AiResponse
    fun stream(request: AiRequest, config: ProviderConfig): Flow<AiStreamEvent>
}
```

## 지원 Provider 목록

| ID | 이름 | 상태 | 멀티모달 지원 |
|----|------|------|--------------|
| P-CLAUDE | Anthropic Claude | 우선 구현 | 이미지 O, 영상 X |
| P-OPENAI | OpenAI GPT | v0.2 | 이미지 O, 영상 X |
| P-GEMINI | Google Gemini | v0.3 | 이미지 O, 영상 O |

## 신규 Provider 추가 절차
1. provider/ 패키지에 ProviderImpl 클래스 추가
2. ProviderRegistry에 등록 (Hilt @IntoSet)
3. ProviderId enum 확장
4. 단위 테스트 추가 (mock 응답 기반)
5. provider-spec.md의 지원 목록 갱신
```

## 작성 순서 (권장)

1. **overview.md** 먼저 작성 — 범위 결정이 모든 후속 작업의 전제
2. **provider-spec.md** — SDK 특화로 가장 구조적 결정 필요
3. **data-model.md** — 데이터 형태가 정해지면 API 시그니처가 따라옴
4. **api.md** — data-model 위에서 자연스럽게 도출
5. **features.md** — api.md를 사용 시나리오로 풀어쓴 것
6. **error-handling.md** — features의 예외 흐름을 모아 ERR-XXX 정리

## ID 충돌 방지

새 spec 작성 전:
```bash
grep -rh "^| F-" experiments/{exp-id}/spec/ | grep -oE "F-[0-9]+" | sort -u
```
같은 명령으로 사용 중인 ID 목록을 확인하고, 가장 큰 번호 +1부터 부여한다.

## 미해결 결정 처리

사양에 결정이 갈리는 항목은 무조건 "## 미해결 결정" 표에 적는다. 검토자(spec-reviewer)가 이 표를 보고 토론을 시작한다. 임의로 결정해서 묻어두지 않는다.

## 산출물 검증 (자체 체크리스트)

- [ ] 모든 F-XXX에 정상 + 예외 흐름이 채워져 있음
- [ ] 모든 NFR이 측정 가능한 기준
- [ ] api.md의 시그니처가 data-model의 클래스를 정확히 참조
- [ ] error-handling의 모든 ERR-XXX가 features의 E-XXX와 매핑됨
- [ ] provider-spec의 인터페이스가 api.md와 모순 없음
- [ ] In Scope / Out of Scope가 둘 다 채워져 있음
