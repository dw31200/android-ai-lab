# 에러 처리 명세

> 작성 규칙
> - 에러 코드: ERR-숫자 (예: ERR-001)
> - 모든 에러는 호출자에게 보여줄 메시지를 정의할 것
> - 에러 발생 시 호출자가 취할 복구 방법을 명시할 것

---

## 에러 유형 분류

| 유형 | 설명 | 호출자 권장 처리 |
|------|------|----------------|
| 네트워크 | 연결 실패, 타임아웃 | 재호출 (D-005: SDK는 자동 재시도 안 함, 호출자 책임) |
| 레이트 리밋 | 429 응답 | retryAfter 후 재호출 |
| 인증 | 401 응답, API 키 누락 | API 키 재설정 |
| 설정 | Builder 잘못된 인자, Provider 미지원 기능, client closed | 코드 수정 |
| 입력 | prompt 빈 값, 이미지 크기 초과, 컨텍스트 초과 | 입력 검증 후 재호출 |
| 서버 | 5xx, 응답 파싱 실패, 빈 응답 | 보고/재호출 |
| IO | 디스크 영속화 실패, 손상된 데이터 | 보고/사용자 안내 |

---

## 에러 코드 정의

> 호출자에게 보일 메시지는 **권장 예시**이며, 호출자가 자체 i18n/UI 정책에 맞게 변환해도 무방하다 (R-013). SDK는 사용자에게 직접 메시지를 보여주지 않는다.

| ID | 유형 | 발생 조건 | 호출자에게 보일 메시지 (영문 권장 예시) | 호출자 권장 처리 |
|----|------|-----------|----------------------------|----------------|
| ERR-001 | 네트워크 | 인터넷 연결 없음, 타임아웃, DNS 실패 | "Network error. Please check your connection." | 재호출 |
| ERR-002 | 레이트 리밋 | API 429 응답 | "Rate limit exceeded. Retry after {duration}." | retryAfter 후 재호출 |
| ERR-003 | 인증 | API 401 응답, 잘못된 API 키 | "Authentication failed. Check your API key." | API 키 재설정 |
| ERR-004 | 설정 | Builder 잘못, 미지원 기능, client closed | "Configuration error: {detail}" | 코드 수정 |
| ERR-005 | 입력 | prompt 빈 값, 이미지 5MB 초과, 지원하지 않는 mime, 컨텍스트 초과, session not found, session too large | "Invalid input: {detail}" | 입력 검증 후 재호출 |
| ERR-006 | 서버 | API 5xx, 파싱 실패, 빈 응답 | "Server error ({code}). Please try again later." | 보고/재호출 |
| ERR-007 | IO (라운드 2 신규) | 영속화 저장 실패 (디스크 가득, IO 오류), 손상된 데이터, schemaVersion 미지원 (R-018 라운드 3) | "Storage error: {detail}" | 사용자 안내 또는 deleteSession 후 새로 시작 |

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
| ERR-007 | AiException.IOError | message: String, cause: Throwable? |

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
| E-109 (client closed) | F-001 ask() | ERR-004 |
| E-110 (빈 응답 검증 실패) | F-001 ask() | ERR-006 |
| E-201 (이미지 5MB 초과) | F-002 ask() | ERR-005 |
| E-202 (이미지 합계 20MB 초과) | F-002 ask() | ERR-005 |
| E-203 (Uri 읽기 실패) | F-002 ask() | ERR-005 |
| E-204 (지원하지 않는 mime) | F-002 ask() | ERR-005 |
| E-205 (Provider 이미지 미지원) | F-002 ask() | ERR-004 |
| E-206 (URL fetch 실패) | F-002 ask() | URL invalid → ERR-005 / 네트워크 → ERR-001 |
| E-207 (이미지 디코딩 실패) | F-002 ask() | ERR-005 |
| E-301 (스트림 끊김) | F-003 askStream() | ERR-001 |
| E-302 (스트림 취소) | F-003 askStream() | (CancellationException) |
| E-303 (스트리밍 미지원) | F-003 askStream() | ERR-004 |
| E-401 (컨텍스트 초과) | F-004 send() | ERR-005 |
| E-402 (client closed) | F-004 send() | ERR-004 |
| E-403 (동시 send) | F-004 send() | (Mutex로 직렬화, ERR 매핑 없음 — 정상 동작) |
| E-501 (등록 안된 Provider) | F-005 useProvider() | ERR-004 |
| E-502 (Provider 키 누락) | F-005 useProvider() | ERR-004 |
| E-601 (Hilt 미설정) | F-006 | (컴파일 에러, ERR 매핑 없음) |
| E-602 (BuildConfig API 키 미설정) | F-006 | ERR-004 (E-001과 동일) |
| E-701 (저장 IO 실패) | F-007 save() | ERR-007 |
| E-702 (sessionId 없음) | F-007 loadSession() | ERR-005 |
| E-703 (손상된 데이터 또는 schemaVersion 미지원) | F-007 loadSession() | ERR-007 |
| E-704 (직렬화 1MB 초과) | F-007 save() | ERR-005 |
| E-705 (client closed) | F-007 save/load/delete | ERR-004 |
| E-706 (영속화 도중 취소) | F-007 save/load/delete | (CancellationException) |
| E-801 (close 도중 IO) | F-008 close() | (무시, 로그만, ERR 매핑 없음) |

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
            is AiException.IOError -> reportStorageIssue(error.message)
            else -> { /* CancellationException 등 */ }
        }
    }
)
```

## 영속화 에러 처리 예 (F-007)

```kotlin
val loadResult = client.loadSession(sessionId)
loadResult.fold(
    onSuccess = { session -> attachSession(session) },
    onFailure = { error ->
        when (error) {
            is AiException.InvalidInput -> {
                // E-702: sessionId 없음 → 새 세션 시작
                startNewSession()
            }
            is AiException.IOError -> {
                // E-703: 손상된 데이터 → 사용자에게 안내 후 deleteSession
                showCorruptedSessionDialog {
                    viewModelScope.launch { client.deleteSession(sessionId) }
                }
            }
            is AiException.Configuration -> reportToDeveloper(error.message)
            else -> showError(error)
        }
    }
)
```

## 로깅 정책

| 빌드 | 로깅 내용 |
|------|----------|
| Debug | prompt, 응답 텍스트, 에러 상세 (단, API 키는 마스킹) |
| Release | 메타데이터만 (요청 ID, Provider, 응답 길이, 에러 ERR-XXX) — prompt/응답 본문 로깅 금지 |

API 키는 어떤 빌드에서도 평문 로깅 금지. 영속화된 Session 데이터에도 절대 포함되지 않음 (D-003 NFR).
