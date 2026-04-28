# AI Agent SDK

## 목적
> 한 문장으로: 이 SDK는 [Android 앱 개발자가] [여러 LLM/AI 에이전트를 통일된 인터페이스로 호출하고 멀티모달 입력으로 응답을 받기] 위한 모듈이다.

## 핵심 가치
- 이 SDK가 해결하는 문제: 각 LLM Provider(Claude, OpenAI, Gemini)마다 SDK·인증·요청 형식이 달라 호출자 코드가 Provider에 종속됨
- 기존 방법과의 차이: Provider를 인터페이스로 추상화하여 런타임 교체 가능, 멀티모달 입력을 sealed class로 통일

## 범위 (Scope)

### In Scope (만든다, v0.1)
- 텍스트 프롬프트 기반 단발성 질의 (F-001)
- 이미지 입력을 포함한 멀티모달 질의 (F-002)
- 스트리밍 응답 수신 (F-003)
- 대화 컨텍스트(세션) 관리 (F-004)
- LLM Provider 추상화 + 런타임 선택 (F-005)
- Hilt 모듈 제공 (F-006)
- Claude(Anthropic) Provider 우선 구현 (P-CLAUDE)

### Out of Scope (안 만든다)
- 영상 입력 (F-007 후보) — 이유: v0.1 범위 축소, Provider 지원 부족
- 오디오 입력 — 이유: Provider별 사양 상이, 별도 라운드
- Tool use(함수 호출) — 이유: 사양 복잡도 높음, v0.2 검토
- 온디바이스 모델 — 이유: 별도 도메인, 본 SDK 범위 외
- OpenAI Provider 구현 (P-OPENAI) — 이유: 추상화만 v0.1에 포함, 실제 구현은 v0.2

## 기술 스택
- Language: Kotlin
- Architecture: Clean Architecture (계층 분리: client/provider/internal)
- 비동기: Coroutines + Flow
- 의존성 주입: Hilt
- HTTP 클라이언트: OkHttp (미해결 결정 D-001)
- 직렬화: kotlinx.serialization
- 최소 SDK: Android 7.0 (API 24)

## 의존성 정책
- 외부 라이브러리는 최소화 (이유: SDK 호출자의 의존성 충돌 방지)
- 허용 라이브러리:
  - androidx.core (Context 접근)
  - kotlinx.coroutines, kotlinx.serialization
  - OkHttp (또는 Ktor — D-001 결정 필요)
  - Hilt (외부 노출용)

## 담당자
| 역할 | 담당 |
|------|------|
| 사양 설계 | spec-architect |
| 사양 검토 | spec-reviewer |
| 구현 | android-implementer |
| QA 검증 | sdk-qa-validator |

## 미해결 결정

| ID | 항목 | 옵션 | 권장 | 결정 |
|----|------|------|------|------|
| D-001 | HTTP 클라이언트 | OkHttp / Ktor | OkHttp (Android 표준, 의존성 작음) | 미정 |
| D-002 | 세션 영속화 | 메모리만 / DataStore / Room | 메모리 (v0.1), DataStore (v0.2) | 미정 |
| D-003 | API 키 저장 | BuildConfig만 / EncryptedSharedPreferences 옵션 추가 | 호출자 책임 (SDK는 받기만) | 미정 |
| D-004 | 이미지 자동 리사이즈 | SDK 책임 / 호출자 책임 | 호출자 책임 (SDK는 크기 검증만) | 미정 |
| D-005 | 재시도 정책 | SDK 기본값 제공 / 호출자 주입 | SDK 기본 + 주입 가능 | 미정 |
