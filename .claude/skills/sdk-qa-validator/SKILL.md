---
name: sdk-qa-validator
description: SDK 사양과 구현 코드를 경계면 단위로 교차 비교하여 누락·불일치를 찾는 검증 스킬. F-XXX의 정상/예외 흐름이 코드에서 동작하는지, api.md 시그니처가 실제 코드와 토큰 단위로 일치하는지, ERR-XXX가 모두 throw 경로에 매핑되는지 검증한다. "QA 검증", "정합성 검사", "사양 일치 확인", "F-XXX 검증해줘" 요청 시 반드시 사용할 것. 단순 lint 검사가 아니라 사양-구현 일치 검증.
---

# sdk-qa-validator

## 언제 이 스킬을 쓰는가

- android-implementer가 F-XXX 구현 완료를 알린 직후 (점진적 QA)
- 전체 구현이 끝난 후 통합 검증
- 사양 변경 시 영향 코드의 재검증

## 검증 원칙

### 1. 존재가 아닌 동작 일치

"`ask` 함수가 있다"는 통과가 아니다. 다음을 확인해야 통과:
- 정상 입력에 대해 정상 흐름 1~N단계가 코드에서 차례로 실행됨
- 모든 E-XXX 조건에서 정확한 AiException variant가 throw됨
- 반환 타입이 사양과 토큰 단위로 일치 (`Result<AiResponse>` vs `AiResponse?` 등 구분)

### 2. 경계면 교차 비교 (Boundary Cross-check)

다음 4쌍의 경계면을 교차 비교:

| 쌍 | 비교 대상 | 확인 |
|----|----------|------|
| api.md ↔ AiAgentClient.kt | 함수 시그니처 | 토큰 단위 일치 |
| data-model.md ↔ model/*.kt | 필드 타입/필수 | 모든 필드 일치 |
| error-handling.md ↔ AiException.kt | sealed class variant | ERR-XXX 1:1 매핑 |
| features.md E-XXX ↔ 단위 테스트 | 예외 흐름 | 모든 E-XXX 테스트 케이스 존재 |

### 3. 점진적 QA

전체 구현 완료 후 1회가 아니라, F-XXX 단위로 즉시 검증:
- F-001 구현 완료 알림 → F-001 검증 → 결과 회신
- F-002 구현 시작 가능

이유: 전체 끝나고 모아서 검증하면 결함이 누적되어 수정 비용이 폭증.

## 검증 절차

### Step 1: 시그니처 매핑

```bash
# api.md에서 모든 함수 시그니처 추출
grep -E "^suspend fun|^fun|^val" experiments/{exp-id}/spec/api.md

# 코드에서 public 함수 추출 후 비교
```

각 함수에 대해:
- 이름 일치
- 파라미터 타입과 순서 일치
- 반환 타입 일치
- suspend/Flow 일치
- visibility (public/internal) 일치

### Step 2: data-model 일치

각 M-XXX에 대해:
```kotlin
// data-model.md M-001
data class AiRequest(
    val prompt: String,
    val images: List<ImageInput>,  // 필수=N (기본값 emptyList())
    val maxTokens: Int,             // 필수=N (기본값 1024)
    ...
)
```
↔ 실제 코드의 `AiRequest.kt` 토큰 단위 비교.

차이가 있으면 보고서에 (file:line)과 함께 기록.

### Step 3: 예외 흐름 매핑

각 F-XXX의 예외 흐름 표:
| ID | 조건 | 처리 |
| E-001 | API 키 누락 | Configuration throw |

검증:
- 코드에 해당 throw 경로가 존재하는가
- 단위 테스트가 해당 경로를 커버하는가
- AiException variant가 정확한가

### Step 4: ERR ↔ AiException 매핑

```bash
# error-handling.md의 매핑 표 추출
# AiException.kt의 sealed class variant 추출
# 1:1 매핑 검증
```

누락된 variant나 사양에 없는 variant를 보고서에 기록.

### Step 5: NFR 검증

- 취소 동작: 단위 테스트로 `cancel()` 호출 시 진행 중 요청 중단 확인
- thread-safety: 동시 호출 테스트
- 타임아웃: 사양의 timeout 값과 OkHttp/Ktor 설정 일치
- 큰 입력 거부: 사양 한계(예: 5MB) 초과 시 InvalidInput throw 확인

## 검증 보고서 형식

```markdown
# QA Report — {exp-id} F-XXX (또는 전체)

검증자: sdk-qa-validator
검증 일시: {YYYY-MM-DD}
대상: F-001, F-002 (점진 검증) / 전체 (통합 검증)
검증 범위: 구조 / 시그니처 / 데이터 모델 / 예외 / NFR

## 요약
- Blocker: {n}건
- Major: {n}건
- Minor: {n}건

## 항목별 검증

| ID | 차원 | 결과 | 근거 | 비고 |
|----|------|------|------|------|
| Q-001 | 시그니처 (A-002) | 통과 | AiAgentClient.kt:42 | - |
| Q-002 | 예외 (E-002) | 누락 | network 오류 시 IOException 그대로 전파 | AiException.Network로 래핑 필요 |
...

## 발견 이슈

### Q-002 [Blocker] E-002 미구현
- **위치**: AiAgentClient.kt:67
- **사양**: F-001 E-002 — 네트워크 오류 시 AiException.Network로 래핑
- **현 구현**: IOException이 그대로 전파됨
- **재현**: 단위 테스트 NetworkErrorTest.kt 추가 필요
- **권장 수정**: ErrorMapper.mapErrors 적용

## 권장 후속 작업
1. android-implementer에게 Q-002 수정 요청 (Blocker)
...
```

## Severity 정의

| Severity | 정의 | 종결 |
|----------|------|------|
| **Blocker** | 사양과 명백히 다른 동작, 또는 데이터 손상 가능 | 0건 필수 |
| **Major** | 사양 일부 불일치, 단위 테스트 누락 | 모두 처리 또는 명시적 유보 |
| **Minor** | 문서/스타일 | 다음 라운드로 이월 가능 |

## 단위 테스트 작성

검증 근거로 단위 테스트를 직접 작성하여 제출 가능:

```kotlin
class F001E002NetworkErrorTest {
    @Test fun `네트워크 오류 시 AiException Network 발생`() = runTest {
        val provider = mockProvider { throw IOException("offline") }
        val client = AiAgentClient(providers = setOf(provider))

        val result = client.ask(AiRequest("hi"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AiException.Network)
    }
}
```

테스트 코드는 검증 산출물이며, 본 코드 수정과는 다르다 (코드 수정은 implementer 책임).

## 사양 자체의 결함 발견 시

검증 중 사양 모호함이나 모순 발견 시:
- 즉시 spec-architect에게 SendMessage
- "사양과 구현 모두 잘못됨" 케이스로 분류
- 보고서에 별도 섹션 "## 사양 결함 의심"으로 기록

## 자체 체크리스트

- [ ] 4쌍의 경계면을 모두 교차 비교했는가
- [ ] 모든 F-XXX의 모든 E-XXX가 단위 테스트로 커버되는가
- [ ] api.md의 시그니처를 토큰 단위로 비교했는가
- [ ] AiException variant가 ERR-XXX와 1:1 매핑되는가
- [ ] 취소·thread-safety·타임아웃 NFR을 단위 테스트로 검증했는가
- [ ] 보고서에 모든 이슈에 (file:line) 근거가 있는가
