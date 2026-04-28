# 에러 처리 명세

> 작성 규칙
> - 에러 코드: ERR-숫자 (예: ERR-001)
> - 모든 에러는 호출자에게 보여줄 메시지를 정의할 것
> - 에러 발생 시 호출자가 취할 복구 방법을 명시할 것

---

## 에러 유형 분류

| 유형 | 설명 | 호출자 권장 처리 |
|------|------|----------------|
| 네트워크 | 연결 실패, 타임아웃 | 재시도 (백오프) |
| 레이트 리밋 | 429 응답 | retryAfter 후 재시도 |
| 인증 | 401 응답, API 키 누락 | API 키 재설정 |
| 설정 | Builder 잘못된 인자, Provider 미지원 기능 | 코드 수정 |
| 입력 | prompt 빈 값, 이미지 크기 초과 | 입력 검증 후 재호출 |
| 서버 | 5xx, 응답 파싱 실패 | 보고/재시도 |

---

## 에러 코드 정의

| ID | 유형 | 발생 조건 | 호출자에게 보일 메시지 (영문) | 호출자 권장 처리 |
|----|------|-----------|----------------------------|----------------|
| ERR-001 | 네트워크 | 인터넷 연결 없음, 타임아웃, DNS 실패 | "Network error. Please check your connection." | 재시도 |
| ERR-002 | 레이트 리밋 | API 429 응답 | "Rate limit exceeded. Retry after {duration}." | retryAfter 후 재시도 |
| ERR-003 | 인증 | API 401 응답, 잘못된 API 키 | "Authentication failed. Check your API key." | API 키 재설정 |
| ERR-004 | 설정 | Builder 잘못, 미지원 기능 | "Configuration error: {detail}" | 코드 수정 |
| ERR-005 | 입력 | prompt 빈 값, 이미지 5MB 초과, 지원하지 않는 mime | "Invalid input: {detail}" | 입력 검증 |
| ERR-006 | 서버 | API 5xx, 파싱 실패 | "Server error ({code}). Please try again later." | 보고/재시도 |

> SDK는 사용자에게 직접 메시지를 보여주지 않는다. 호출자(앱)가 필요에 따라 위 메시지를 표시하거나 자체 메시지로 변환한다.

---

## ERR ↔ AiException 매핑

| ERR ID | AiException Variant | 추가 정보 |
|--------|--------------------|---------|
| ERR-001 | AiException.Network | cause: Throwable |
| ERR-002 | AiException.RateLimit | retryAfter: Duration? |
| ERR-003 | AiException.Authentication | - |
| ERR-004 | AiException.Configuration | message: String |
| ERR-005 | AiException.InvalidInput | message: String |
| ERR-006 | AiException.ServerError | code: Int, message: String? |

---

## E-XXX (기능별 예외) ↔ ERR-XXX 매핑

| E-XXX | 발생 위치 | ERR-XXX |
|-------|----------|---------|
| E-001 (API 키 누락) | F-000 build() | ERR-004 |
| E-002 (알 수 없는 Provider) | F-000 build() | ERR-004 |
| E-003 (timeout 잘못) | F-000 build() | ERR-004 |
| E-101 (네트워크 없음) | F-001 ask() | ERR-001 |
| E-102 (401) | F-001 ask() | ERR-003 |
| E-103 (429) | F-001 ask() | ERR-002 |
| E-104 (5xx) | F-001 ask() | ERR-006 |
| E-105 (파싱 실패) | F-001 ask() | ERR-006 |
| E-106 (취소) | F-001 ask() | (CancellationException 그대로 전파, ERR 매핑 없음) |
| E-107 (빈 prompt) | F-001 ask() | ERR-005 |
| E-108 (타임아웃) | F-001 ask() | ERR-001 |
| E-201~E-205 (이미지 관련) | F-002 ask() | ERR-005 (E-205만 ERR-004) |
| E-301 (스트림 끊김) | F-003 askStream() | ERR-001 |
| E-302 (스트림 취소) | F-003 askStream() | (CancellationException) |
| E-303 (스트리밍 미지원) | F-003 askStream() | ERR-004 |
| E-401 (컨텍스트 초과) | F-004 send() | ERR-005 |
| E-402 (client 닫힘) | F-004 send() | ERR-004 |
| E-501 (등록 안된 Provider) | F-005 useProvider() | ERR-004 |
| E-502 (Provider 키 누락) | F-005 useProvider() | ERR-004 |

> 매핑 누락 = QA 검증 실패 조건

---

## 에러 처리 호출 예 (호출자 측)

```kotlin
val result = client.ask(request)
result.fold(
    onSuccess = { response -> showResponse(response) },
    onFailure = { error ->
        when (error) {
            is AiException.Network -> retryWithBackoff()
            is AiException.RateLimit -> {
                val wait = error.retryAfter ?: 5.seconds
                delay(wait); retry()
            }
            is AiException.Authentication -> promptForApiKey()
            is AiException.Configuration -> reportToDeveloper(error.message)
            is AiException.InvalidInput -> showValidation(error.message)
            is AiException.ServerError -> reportAndRetryLater(error.code)
        }
    }
)
```

## 로깅 정책

| 빌드 | 로깅 내용 |
|------|----------|
| Debug | prompt, 응답 텍스트, 에러 상세 (단, API 키는 마스킹) |
| Release | 메타데이터만 (요청 ID, Provider, 응답 길이, 에러 ERR-XXX) — prompt/응답 본문 로깅 금지 |

API 키는 어떤 빌드에서도 평문 로깅 금지.
