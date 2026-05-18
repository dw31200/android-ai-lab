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
- 세션 영속화 (DataStore 기반, 명시적 save/load) (F-007)
- 클라이언트 라이프사이클(close) (F-008)
- Claude(Anthropic) Provider 우선 구현 (P-CLAUDE)

### Out of Scope (안 만든다)
- 영상 입력 — 이유: v0.1 범위 축소, Provider 지원 부족 (v0.2 검토)
- 오디오 입력 — 이유: Provider별 사양 상이, 별도 라운드
- Tool use(함수 호출) — 이유: 사양 복잡도 높음, v0.2 검토
- 온디바이스 모델 — 이유: 별도 도메인, 본 SDK 범위 외
- OpenAI Provider 구현 (P-OPENAI) — 이유: 추상화만 v0.1에 포함, 실제 구현은 v0.2
- 자동 재시도 정책 — 이유: D-005 결정에 따라 v0.1은 호출자 재호출 책임, v0.2에서 RetryPolicy 주입 도입
- 이미지 자동 리사이즈 — 이유: D-004 결정. SDK는 5MB 검증만, 리사이즈는 호출자 책임 (품질 손실 위험·정책 결정이 호출자 도메인)
- API 키 디스크 영속화 — 이유: D-003 결정. SDK는 메모리에서만 보관. 디스크 보관은 호출자가 EncryptedSharedPreferences 등으로 별도 처리
- 다중 프로세스에서의 영속화 동시 접근 (R-019 라운드 3) — 이유: DataStore Preferences는 단일 프로세스 사용 권장. 본 SDK는 같은 앱 단일 프로세스 사용을 가정하며, WorkManager 등 별도 프로세스에서의 동시 save/load 동기화는 v0.2에서 별도 추상화 검토
- 호출자 지정 sessionId 부여 (R-024 라운드 3) — 이유: v0.1은 `createSession()`이 SDK 내부에서 UUID를 자동 부여하는 단일 정책만 지원. 호출자가 sessionId를 명시 제어하는 인터페이스(`createSession(sessionId: String? = null)` 등)는 v0.2 검토 (단순성·테스트 용이성 우선)
- schemaVersion 마이그레이션 자동 변환 (R-018/R-022 라운드 3) — 이유: v0.1은 `schemaVersion = 1`만 존재하므로 마이그레이션이 적용될 케이스 자체가 없음. v0.2부터 `internal object SessionMigrations`에 `fromN_toNplus1(json: JsonElement): JsonElement` 함수를 등록하는 패턴으로 추가. v0.1에서 schemaVersion이 1이 아니면 ERR-007(IOError, E-703)로 즉시 거부

## 기술 스택
- Language: Kotlin
- Architecture: Clean Architecture (계층 분리: client/provider/internal)
- 비동기: Coroutines + Flow
- 의존성 주입: Hilt
- HTTP 클라이언트: OkHttp (D-001 결정 채택)
- 직렬화: kotlinx.serialization
- 영속화: androidx.datastore (Preferences DataStore, 세션 저장 — D-002 결정 채택)
- 최소 SDK: Android 7.0 (API 24)

## 의존성 정책
- 외부 라이브러리는 최소화 (이유: SDK 호출자의 의존성 충돌 방지)
- 허용 라이브러리:
  - androidx.core (Context 접근)
  - androidx.datastore-preferences (세션 영속화 — F-007)
  - kotlinx.coroutines, kotlinx.serialization
  - OkHttp (HTTP 클라이언트 — D-001)
  - Hilt (외부 노출용)

## 담당자
| 역할 | 담당 |
|------|------|
| 사양 설계 | spec-architect |
| 사양 검토 | spec-reviewer |
| 구현 | android-implementer |
| QA 검증 | sdk-qa-validator |

## 결정 사항 (라운드 2 종결)

라운드 1에서 미해결이었던 D-001~D-005를 본 라운드에서 모두 확정.

| ID | 항목 | 결정 | 사유 / 영향 |
|----|------|------|------------|
| D-001 | HTTP 클라이언트 | **OkHttp** | reviewer 권장 채택. Android 표준, kotlinx.serialization과 통합 부담 작음, 코루틴 취소를 `call.cancel()`로 cooperative 변환 패턴 검증됨 |
| D-002 | 세션 영속화 | **DataStore 기반 영속화 v0.1 포함** | 사용자 결정으로 reviewer 권장(메모리만)을 뒤집고 스코프 확장. v0.1부터 명시적 save/load API 제공 (F-007 신규) |
| D-003 | API 키 저장 | **호출자 책임** | SDK는 Builder.apiKey()로 받아 메모리에 보관, 디스크 영속화 금지를 NFR로 명시. EncryptedSharedPreferences 등은 호출자 선택 |
| D-004 | 이미지 자동 리사이즈 | **호출자 책임** | SDK는 단일 5MB / 합계 20MB 검증만 수행. 자동 리사이즈는 품질 손실·정책 결정 도메인이 호출자에 있으므로 SDK는 관여하지 않음. 본 정책의 연장으로, **`ImageInput.Uri` 자동 resolve(ContentResolver 호출)도 SDK가 수행하지 않는다** — 진입 시 즉시 E-203 거부 (F-002 "v0.1 ImageInput.Uri 정책" 섹션 참조) |
| D-005 | 재시도 정책 | **v0.1 자동 재시도 없음, 호출자 재호출** | v0.1은 SDK가 ERR-001/ERR-002를 그대로 throw, 호출자가 catch 후 재호출. v0.2에서 RetryPolicy 주입 인터페이스 추가 예정 |

미해결 결정: 0건.

## NFR (전역)

- API 키는 호출자에서 받은 후 SDK 메모리에서만 유지 (디스크/로그 영속화 금지)
- 동시성: `AiAgentClient`는 thread-safe, 동시 요청 최대 8개 큐잉
- 디스크 IO(F-007 영속화)는 `Dispatchers.IO`에서만 수행, 호출 스레드 블로킹 금지
- 영속화 프로세스 모델 (R-019 라운드 3): v0.1은 호출자 앱이 **단일 프로세스에서만 SDK를 사용**한다고 가정. DataStore Preferences는 단일 프로세스 권장이며, 다중 프로세스에서 같은 DataStore 파일을 동시에 사용하는 동작은 정의되지 않음 (Out of Scope)
- 모든 public 비동기 API는 suspend 또는 Flow (콜백 금지)
- 코루틴 cooperative cancellation 지원
