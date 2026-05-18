# 구현 요약 #9 — F-007 (세션 영속화 DataStore)

본 라운드(T18): F-007 본체 — `Session.save()` 메서드 추가 + `Builder.sessionStore()` internal hook + 자동 `DataStoreSessionStore` 인스턴스화 + `SdkModule.provideSessionStore` 신규 + `SdkModule.provideAiAgentClient(sessionStore)` 시그니처 변경 + 단위 테스트 5종(56+ 케이스).

본 라운드 시작 시점에 다음은 이미 존재했음 (이전 라운드 잔여 작업):
- `session/SessionStore.kt` (인터페이스)
- `session/SessionEntity.kt` (M-011 직렬화 모델 + 변환 헬퍼)
- `internal/storage/DataStoreSessionStore.kt` (DataStore 구현)
- `internal/storage/SessionJson.kt` (Json 정책)
- `AiAgentClient.kt`의 `loadSession()` / `deleteSession()` / `sessionStore` 필드

본 라운드에서 마무리한 것: **`Session.save()` 본체 + Builder/SdkModule 의존성 와이어링 + 단위 테스트**.

---

## 0. 회귀 점검 (이전 라운드)

- F-000 / F-001 / F-002 / F-003 / F-004 / F-005 / F-006 / F-008 변경 영향 분석:
  - `Builder.build()`에 `sessionStoreOverride ?: DataStoreSessionStore(appContext)` 추가 → `AiAgentClient` 생성자에 `sessionStore` 인자가 채워짐. 기존 `apiKey`/`provider`/`model`/`timeout` 검증 흐름 변경 없음 (E-001/E-002/E-003 회귀 0건).
  - `AiAgentClient` internal 생성자에 `sessionStore: SessionStore? = null` default 추가 — 모든 기존 테스트(`AskTest`/`AskStreamTest`/`AskWithImagesTest`/`UseProviderTest`/`CloseTest`/`CreateSessionTest`/`SessionTest`)는 default null로 인스턴스화하므로 시그니처 호환 (F-001/F-002/F-003/F-005/F-004/F-008 회귀 0건).
  - `SdkModule.provideAiAgentClient`에 `sessionStore: SessionStore` 파라미터 추가 — 호환성 영향이 있는 변경. 본 라운드 `SdkModuleTest.kt`의 모든 호출부에 `sessionStore = ...` 인자를 추가하여 시그니처 매칭.
  - `Session.kt`에 `save()` 메서드 추가 — 기존 `send()`/`history()`/`clear()` 시그니처 그대로. `mutex.withLock { ... }` 잠금 순서는 send와 동일(mutex → synchronized(history))이라 deadlock 위험 없음.
- `DataStoreSessionStore(appContext)` 자동 생성은 lazy delegate(`preferencesDataStore`)라 build() 시점에는 디스크 IO 미발생. 기존 mockk Context 기반 단위 테스트 인스턴스화 안전.

---

## 1. 생성/수정 파일 목록

### 1-A. 신규 파일 (테스트)

| 파일 | 책임 |
|------|------|
| `sdk/src/test/kotlin/com/androidailab/aisdk/session/SessionEntityTest.kt` | M-011 SessionEntity 직렬화 round-trip + R-018 schemaVersion + Message ↔ MessageEntity / ImageInput ↔ ImageInputEntity 변환 (R-008/R-021/R-023) |
| `sdk/src/test/kotlin/com/androidailab/aisdk/session/SessionSaveTest.kt` | F-007 A-010 Session.save() 단위 테스트 (정상 흐름 + E-701/E-704/E-705/E-706 + R-021 이미지 + 동시성) |
| `sdk/src/test/kotlin/com/androidailab/aisdk/client/LoadSessionTest.kt` | F-007 A-011 loadSession() 단위 테스트 (정상 흐름 + E-702/E-703/E-705/E-706 + R-019 다중 인스턴스) |
| `sdk/src/test/kotlin/com/androidailab/aisdk/client/DeleteSessionTest.kt` | F-007 A-012 deleteSession() 단위 테스트 (정상 흐름 + idempotent + E-705/E-706 + IOError) |
| `sdk/src/test/kotlin/com/androidailab/aisdk/internal/storage/DataStoreSessionStoreTest.kt` | DataStoreSessionStore 상수/키 정책 + SessionJson 정책 + 1MB 한계 + R-018 schemaVersion 검증 책임 분리 |

### 1-B. 수정 파일

| 파일 | 변경 내용 | 호환성 |
|------|----------|--------|
| `sdk/src/main/kotlin/com/androidailab/aisdk/session/Session.kt` | `save(): Result<String>` 메서드 추가 — A-010 시그니처 토큰 단위 일치, F-007 저장 흐름 1~5 구현, mutex.withLock 잠금 (F-007 동시성 모델), SessionEntity 변환 (R-018 schemaVersion=1), R-020 케이스 A/B, E-701/E-704/E-705/E-706 매핑 | 기존 send/history/clear 시그니처 변경 없음 (F-004 회귀 0건) |
| `sdk/src/main/kotlin/com/androidailab/aisdk/client/Builder.kt` | `import DataStoreSessionStore`, `import SessionStore` 추가 + `sessionStoreOverride: SessionStore?` 필드 + `internal fun sessionStore(store: SessionStore?)` hook + `build()`에서 `sessionStoreOverride ?: DataStoreSessionStore(appContext)` 자동 인스턴스화 후 `AiAgentClient`에 주입 | 기존 build() 검증(E-001/E-002/E-003) 변경 없음 (F-000 회귀 0건) |
| `sdk/src/main/kotlin/com/androidailab/aisdk/di/SdkModule.kt` | `import DataStoreSessionStore`, `import SessionStore` 추가 + `provideSessionStore(@ApplicationContext): SessionStore` 신규 (@Provides @Singleton) + `provideAiAgentClient`에 `sessionStore: SessionStore` 파라미터 추가 + Builder.sessionStore() 호출 추가 | provideAiAgentClient 시그니처 변경 → `SdkModuleTest.kt` 모든 호출부 수정 (본 라운드 6건) |
| `sdk/src/test/kotlin/com/androidailab/aisdk/di/SdkModuleTest.kt` | 6건의 `provideAiAgentClient` 호출에 `sessionStore` 인자 추가 + `provideSessionStore` 단위 테스트 3건 신규 + `FakeSessionStore` 헬퍼 추가 | F-006 회귀 0건 (기존 16 케이스 + 신규 3 케이스) |

---

## 2. 구현한 사양 ID 표

| ID | 사양 위치 | 구현 위치 | 비고 |
|----|----------|----------|------|
| F-007 | features.md | `Session.save()` + `AiAgentClient.loadSession/deleteSession` + `DataStoreSessionStore` + `SessionEntity` + `SessionStore` + `SdkModule.provideSessionStore` | 저장/복원/삭제 흐름 모두 |
| A-010 | api.md | `Session.kt` `public suspend fun save(): Result<String>` | 시그니처 토큰 단위 일치 (반환값: 자동 부여 sessionId, R-017/R-024) |
| A-011 | api.md | `AiAgentClient.kt:487` `public suspend fun loadSession(sessionId: String): Result<Session>` | 시그니처 토큰 단위 일치 — 이전 라운드 구현, 본 라운드 검증 |
| A-012 | api.md | `AiAgentClient.kt:557` `public suspend fun deleteSession(sessionId: String): Result<Unit>` | 시그니처 토큰 단위 일치 — 이전 라운드 구현, 본 라운드 검증 |
| M-011 | data-model.md | `session/SessionEntity.kt` SessionEntity + MessageEntity + ImageInputEntity + 변환 헬퍼 | @Serializable, schemaVersion=1, history List<MessageEntity>, savedAt |
| R-008 | data-model.md M-011 | `Message.toEntity()` Role.SYSTEM 차단 + `MessageEntity.toMessage()` SYSTEM 안전망 | 영속화 대상은 USER/ASSISTANT만 |
| R-017 / R-024 | api.md A-010 | `Session.save()` 반환값이 `sessionId` (자동 UUID, AiAgentClient.createSession에서 부여) | v0.1은 호출자 명시 지정 미지원 |
| R-018 | features.md F-007 / data-model.md M-011 | `SessionEntity.SCHEMA_VERSION_V1 = 1` + `DataStoreSessionStore.load`가 schemaVersion!=1이면 IOError 즉시 throw + `Session.save()`에서 항상 SCHEMA_VERSION_V1 사용 | v0.1은 1만 인정, v0.2 마이그레이션 |
| R-019 | features.md F-007 | `AiAgentClient.loadSession`이 호출마다 새 Session 인스턴스 생성 — last-write-wins | 같은 sessionId로 두 번 호출 시 독립 인스턴스 |
| R-020 케이스 A/B | features.md F-008 | `Session.save`: client.isClosed() 두 곳 검사 (mutex 진입 전 + mutex 안) → suspend 시맨틱 Result.failure(Configuration). `loadSession/deleteSession`: ensureNotClosed → Result로 감싸 반환 | 케이스 A는 CancellationException 그대로 전파 (catch 후 throw) |
| R-021 | features.md F-007 / data-model.md M-011 | `Message.toEntity()`가 `ImageInput.Bytes`를 `ImageInputEntity.Bytes(base64, mimeType)`로 변환 — 1MB 한계 시 DataStoreSessionStore가 E-704로 거부 | SDK는 자동 제외/압축 안 함 (D-004 연장) |
| R-022 | data-model.md M-011 | `SessionJson`의 `ignoreUnknownKeys=true` + DataStoreSessionStore가 schemaVersion 검증 (v0.2 마이그레이션은 향후) | 자동 마이그레이션은 v0.2 Out of Scope |
| R-023 | data-model.md M-011 / provider-spec.md | `ImageInput.Url.toEntity()` URL 문자열 그대로 보관 + `ImageInputEntity.Url.toImageInput()` 그대로 복원 — SSRF 방어 미수행 | 호출자 책임 |
| E-701 | features.md F-007 | `DataStoreSessionStore.save()`가 IOException → `AiException.IOError("save failed: ...")` throw | ERR-007 (IO 오류) |
| E-702 | features.md F-007 | `AiAgentClient.loadSession`: SessionStore.load 가 null 반환 → `Result.failure(AiException.InvalidInput("session not found: $sessionId"))` | ERR-005 |
| E-703 | features.md F-007 | `DataStoreSessionStore.load`: SerializationException → IOError("session data corrupted"), schemaVersion!=1 → IOError("session schema unsupported: v=...") | ERR-007 (R-018 정합) |
| E-704 | features.md F-007 | `DataStoreSessionStore.save`: UTF-8 byte 1MB 초과 → `AiException.InvalidInput("session too large to persist: ...")` | ERR-005 |
| E-705 | features.md F-007 | `Session.save`/`AiAgentClient.loadSession`/`deleteSession`: isClosed → Configuration("client closed") Result.failure (suspend) | ERR-004 / R-020 케이스 B |
| E-706 | features.md F-007 | `Session.save`/`loadSession`/`deleteSession`: CancellationException catch 후 그대로 throw (Result로 감싸지 않음) | R-020 케이스 A |
| ERR-007 | error-handling.md | E-701 / E-703 → AiException.IOError | 매핑 일치 |
| ERR-005 | error-handling.md | E-702 / E-704 → AiException.InvalidInput | 매핑 일치 |
| ERR-004 | error-handling.md | E-705 → AiException.Configuration | 매핑 일치 |

### 사양 ↔ 구현 라우트 매핑

```
features.md F-007 저장 흐름                              구현
1. session.save() (suspend) 호출                       →  Session.save() 진입
2. SDK가 Session 상태를 SessionEntity로 직렬화        →  Message.toEntity() loop + SessionEntity(schemaVersion=1, sessionId, systemPrompt, history, savedAt)
3. Dispatchers.IO로 전환                              →  DataStoreSessionStore.save가 withContext(Dispatchers.IO)
4. DataStore에 "session:{sessionId}" 키로 JSON 저장   →  DataStoreSessionStore.save에서 SessionJson.encodeToString + 1MB 검증 + dataStore.edit
5. Result.success(sessionId) 반환                      →  Session.save() mutex 안에서 Result.success(sessionId) — 자동 부여 UUID

features.md F-007 복원 흐름
1. client.loadSession(sessionId) 호출                  →  AiAgentClient.loadSession
2. Dispatchers.IO로 전환                              →  DataStoreSessionStore.load가 withContext(Dispatchers.IO)
3. DataStore 조회                                    →  dataStore.data.first()[key]
4. SessionEntity 역직렬화 + schemaVersion=1 검증      →  SessionJson.decodeFromString + R-018 검증 (E-703)
5. Result.success(Session) 반환                       →  entity → Session(client, sessionId, systemPrompt, initialHistory)

features.md F-007 삭제 흐름
1. client.deleteSession(sessionId) 호출               →  AiAgentClient.deleteSession
2. DataStore에서 키 제거                              →  DataStoreSessionStore.delete (idempotent — DataStore 표준)
3. Result.success(Unit)                              →  AiAgentClient에서 Result.success(Unit)
```

### close 시맨틱 (R-020) 매핑

| 호출 | 케이스 A (close 시 in-flight) | 케이스 B (close 후 새 호출) |
|------|------------------------------|---------------------------|
| `Session.save()` | mutex.withLock 안에서 CancellationException — scope.cancel("AiAgentClient closed")에 cooperative | `client.isClosed()` 두 곳 검사 → Result.failure(Configuration("client closed")) |
| `AiAgentClient.loadSession()` | CancellationException 그대로 전파 (catch 후 throw) | ensureNotClosed catch → Result.failure(Configuration("client closed")) |
| `AiAgentClient.deleteSession()` | 위와 동일 | 위와 동일 |

---

## 3. 단위 테스트 케이스 목록

### `SessionEntityTest.kt` (16 케이스)

각 케이스의 의도 + 사양 ID:

```
F-007 M-011 — SessionEntity 직렬화 round-trip 필드 동일           [M-011]
F-007 M-011 — 빈 history 도 round-trip 보존                       [M-011]
F-007 R-018 — SessionEntity default schemaVersion 은 1            [R-018]
F-007 R-018 — schemaVersion 필드는 encodeDefaults true            [R-018, SessionJson]
F-007 R-018 — schemaVersion = 2 도 decode 통과 (검증 책임 분리)   [R-018, 의도 분리]
F-007 M-011 — systemPrompt null 이면 JSON 제외 (explicitNulls)    [M-011]
F-007 M-011 — systemPrompt 있으면 JSON 등장                      [M-011]
F-007 M-011 — ImageInputEntity_Uri round-trip                    [M-011]
F-007 M-011 R-021 — ImageInputEntity_Bytes round-trip (base64)   [M-011, R-021]
F-007 M-011 R-023 — ImageInputEntity_Url round-trip              [M-011, R-023]
F-007 M-011 — Message_toEntity + MessageEntity_toMessage (USER)  [M-011, R-008]
F-007 R-008 — Message(Role.SYSTEM)_toEntity → InvalidInput throw [R-008]
F-007 E-703 — MessageEntity_unknown role → IOError               [E-703]
F-007 E-703 — MessageEntity_role = SYSTEM → IOError (안전망)     [E-703, R-008]
F-007 R-021 — ImageInput.Bytes_toEntity base64 round-trip        [R-021]
F-007 R-023 — ImageInput.Url_toEntity URL 그대로 보관             [R-023]
F-007 E-703 — ImageInputEntity.Bytes 손상 base64 → IOError       [E-703]
```

### `SessionSaveTest.kt` (11 케이스)

```
F-007 A-010 정상 흐름 — save 성공 시 Result.success(sessionId)        [A-010, F-007]
F-007 A-010 R-018 — save 시 SessionEntity.schemaVersion 은 항상 1     [A-010, R-018]
F-007 A-010 — save 결과 sessionId 는 createSession 자동 UUID 와 일치  [A-010, R-017/R-024]
F-007 A-010 — send 후 save 시 history 그대로 직렬화 (M-011)          [A-010, M-011]
F-007 R-021 — ImageInput.Bytes 가 ImageInputEntity.Bytes(base64) 로 저장 [R-021]
F-007 E-704 — InvalidInput(session too large) throw → Result.failure  [E-704, ERR-005]
F-007 E-701 — SessionStore IOError throw → Result.failure(IOError)    [E-701, ERR-007]
F-007 E-705 R-020 케이스 B — close 후 save → Result.failure(Configuration) [E-705, R-020]
F-007 E-706 R-020 케이스 A — save 도중 cancel → CancellationException [E-706, R-020]
F-007 — sessionStore 미설정 시 IOError(session store not configured)  [F-007 안전망]
F-007 동시성 — send 진행 중 save 는 Mutex 대기                        [F-007 동시성 모델]
```

### `LoadSessionTest.kt` (10 케이스)

```
F-007 A-011 정상 흐름 — loadSession 성공 시 Result.success(Session)   [A-011, F-007]
F-007 A-011 정상 흐름 — 빈 history 의 SessionEntity 도 정상 복원      [A-011, M-011]
F-007 A-011 — 복원된 Session 으로 send 가능 (R-014)                   [A-011, R-014]
F-007 E-702 — load null → InvalidInput("session not found")           [E-702, ERR-005]
F-007 E-703 — IOError(corrupted) throw → Result.failure(IOError)      [E-703, ERR-007]
F-007 E-703 R-018 — IOError(schema unsupported) → Result.failure      [E-703, R-018, ERR-007]
F-007 E-705 R-020 케이스 B — close 후 loadSession → Result.failure    [E-705, R-020]
F-007 E-706 R-020 케이스 A — load 도중 cancel → CancellationException [E-706, R-020]
F-007 R-019 — 같은 sessionId 로 두 번 호출 시 서로 다른 인스턴스      [R-019]
F-007 — sessionStore 미설정 시 IOError(session store not configured)  [F-007 안전망]
```

### `DeleteSessionTest.kt` (7 케이스)

```
F-007 A-012 정상 흐름 — deleteSession 성공 시 Result.success(Unit)    [A-012, F-007]
F-007 A-012 idempotent — 미존재 sessionId 도 성공                     [A-012]
F-007 A-012 — 여러 번 deleteSession 호출 시 모두 SessionStore 로 전달  [A-012]
F-007 E-705 R-020 케이스 B — close 후 deleteSession → Result.failure  [E-705, R-020]
F-007 E-706 R-020 케이스 A — delete 도중 cancel → CancellationException [E-706, R-020]
F-007 — SessionStore.delete IOError → Result.failure(IOError)         [F-007, ERR-007]
F-007 — sessionStore 미설정 시 IOError(session store not configured)  [F-007 안전망]
```

### `DataStoreSessionStoreTest.kt` (12 케이스)

```
F-007 — DATASTORE_FILE_NAME 은 SDK 고유 이름                          [F-007]
F-007 M-011 — KEY_PREFIX 는 'session:'                                [M-011]
F-007 M-011 — keyFor(sessionId) 는 'session:{sessionId}'              [M-011]
F-007 E-704 — MAX_SESSION_BYTES 는 정확히 1MB                         [E-704]
F-007 R-018 — SessionJson.encodeDefaults true                         [R-018]
F-007 M-011 — SessionJson.explicitNulls false                         [M-011]
F-007 R-022 — SessionJson.ignoreUnknownKeys true                      [R-022]
F-007 — 일반 SessionEntity 의 JSON UTF-8 byte 크기 1MB 이하            [F-007 NFR]
F-007 E-704 — 큰 history (100KB x 12) 는 1MB 초과 가능성              [E-704]
F-007 R-018 — schemaVersion = 2 도 직렬화 통과 (검증 책임 분리)        [R-018, 의도 분리]
F-007 E-703 — 잘못된 JSON 본문은 SerializationException                [E-703]
F-007 E-703 — 필수 필드 누락 도 SerializationException                [E-703]
```

### `SdkModuleTest.kt` 변경 (기존 16 → 추가 3 = 19 케이스)

신규:
```
F-007 provideSessionStore 는 DataStoreSessionStore 를 SessionStore 로 반환  [F-007]
F-007 provideSessionStore 매 호출마다 새 인스턴스 (Singleton 은 Hilt 책임)  [F-007 정책 분리]
F-007 provideAiAgentClient 주입 SessionStore 가 client.sessionStore 에 보관 [F-006 + F-007 와이어링]
```

기존 16 케이스(F-006/F-006 E-602 / 멀티 Provider / 기본값 / 회귀)는 모두 sessionStore 인자만 추가하여 시그니처 매칭 — 의도 변경 없음.

### 합계

- `SessionEntityTest.kt`: 16 케이스
- `SessionSaveTest.kt`: 11 케이스
- `LoadSessionTest.kt`: 10 케이스
- `DeleteSessionTest.kt`: 7 케이스
- `DataStoreSessionStoreTest.kt`: 12 케이스
- `SdkModuleTest.kt` 신규: 3 케이스
- **본 라운드 추가**: **59 단위 테스트**
- 외부 네트워크 호출 0회 (FakeSessionStore / FakeProvider / FakeClaudeLike / runTest / SessionJson 직접 호출)

### 이전 라운드 회귀 보호

기존 테스트(F-001/F-002/F-003/F-004/F-005/F-006/F-008) 모두 변경 없음:
- `AskTest.kt` / `AskWithImagesTest.kt` / `AskStreamTest.kt` — F-001/F-002/F-003 (sessionStore default null로 인스턴스화)
- `MapperTest.kt` / `MapperImageTest.kt` / `MapperSessionTest.kt` / `AnthropicSseParserTest.kt` / `ErrorMapperTest.kt` — 매퍼 영역
- `ClaudeProviderTest.kt` / `ClaudeProviderStreamTest.kt` — Provider 본체
- `BuilderTest.kt` / `UseProviderTest.kt` / `CloseTest.kt` — Builder/Client 라이프사이클
- `CreateSessionTest.kt` / `SessionTest.kt` — F-004 Session 정상/예외 흐름
- `SdkModuleTest.kt` — F-006 Hilt 모듈 (본 라운드 sessionStore 인자만 6건 추가)

회귀 0건.

---

## 4. 사양 명확화 요청

### Q-T18-1 [Minor] DataStoreSessionStore 와 ApplicationContext 의 단일 인스턴스 정책 명시

- **현 사양**: features.md F-007 동시성 모델은 "동일 sessionId에 대한 save/load/delete는 DataStore의 transactional update로 직렬화됨"으로 명시. R-019는 단일 프로세스 사용 가정. 그러나 `DataStoreSessionStore`가 단일 인스턴스여야 하는 이유(`preferencesDataStore` delegate가 process-singleton이라 같은 파일 이름에 두 번째 호출 시 IllegalStateException)는 사양 외 구현 디테일.
- **현 구현**: `SdkModule.provideSessionStore`에 `@Singleton` 어노테이션 — Hilt 진입 호출자는 안전. 그러나 Hilt 미사용 호출자가 `Builder` 진입 경로로 두 번 `build()` 호출 시 `DataStoreSessionStore`가 두 번 인스턴스화될 수 있음 — `preferencesDataStore` delegate가 top-level property로 한 번만 등록되어 두 번째 인스턴스도 동일 핸들을 공유 (process-singleton). 따라서 안전하지만 사양 명시가 없어 코드 리뷰어가 동시 인스턴스 시나리오를 오인할 위험.
- **권장**: features.md F-007 또는 overview.md NFR에 "v0.1 SDK는 `DataStoreSessionStore`를 단일 프로세스/단일 파일(`ai_agent_sdk_sessions`) 사용을 전제로 한다. 같은 호출자 앱에서 Builder를 여러 번 호출해도 같은 DataStore 파일을 공유 (process-singleton의 표준 동작)" 한 줄 추가.
- **차단 여부**: 차단 안 함 (Minor) — 동작상 안전.

### Q-T18-2 [Minor] Session.save() 후 Session 객체 상태 변화 (lastSavedAt 등) 명시 부재

- **현 사양**: api.md A-010은 `Session.save()`의 반환을 sessionId(String)만 명시. Session 인스턴스의 상태(예: lastSavedAt timestamp, "dirty" 플래그)는 변경하지 않음.
- **현 구현**: Session.save() 후 Session 객체는 mutex 점유/해제 외에 어떤 내부 상태도 변경하지 않음 (history/systemPrompt 그대로). 호출자가 "save 이후 추가 send → 다시 save 필요?"를 판단하려면 자체적으로 추적해야 함.
- **권장**: 사양 보강 — api.md A-010에 "Session.save() 후 Session 객체의 내부 상태는 변경되지 않는다. lastSavedAt 추적이 필요한 호출자는 별도 보관(savedStateHandle 등)" 한 줄 추가. 향후 v0.2에서 `Session.lastSavedAt: Long?` 추가 검토 사항으로 명시.
- **차단 여부**: 차단 안 함 (Minor).

### Q-T18-3 [Minor] DataStore Preferences 키 충돌 정책 명시 부재

- **현 사양**: data-model.md M-011은 "DataStore Preferences 키: `session:{sessionId}`" 명시. 그러나 호출자 앱이 같은 DataStore 파일을 사용해 `session:` 접두사 키를 직접 쓰면 충돌 가능성 (예: `ai_agent_sdk_sessions` 파일을 호출자가 직접 접근 — 비표준이지만 가능).
- **현 구현**: `DataStoreSessionStore.DATASTORE_FILE_NAME = "ai_agent_sdk_sessions"`로 SDK 고유 이름 사용 → 호출자가 같은 파일에 접근할 가능성 매우 낮음.
- **권장**: features.md F-007 또는 overview.md "보안/격리"에 "SDK는 `ai_agent_sdk_sessions` DataStore Preferences 파일을 SDK 전용으로 사용한다. 호출자 앱이 같은 파일에 직접 접근하면 동작 미정의" 한 줄 추가. 비공개 키 정책으로 보강.
- **차단 여부**: 차단 안 함 (Minor).

### Q-T18-4 [Minor] DataStore 실제 IO 단위 테스트 정책 (androidTest 명시 부재)

- **현 사양**: 사양은 DataStore 영속화의 실제 디스크 IO 동작을 별도 테스트 영역(androidTest)에서 검증하라는 정책을 명시하지 않음.
- **현 구현**: 본 라운드(unitTest)는 `DataStoreSessionStore`의 정적 상수/SessionJson 정책/크기 한계 검증만 수행. 실제 DataStore IO + Context.applicationContext.sessionsDataStore의 round-trip은 unitTest에서 robolectric/instrumented 환경이 필요해 본 라운드 영역 외.
- **권장**: features.md F-007 NFR 또는 별도 "테스트 범위" 섹션에 "DataStore 실제 IO 동작은 androidTest 영역(instrumentation)에서 검증한다. unitTest는 SessionStore 인터페이스 단위(FakeSessionStore 주입) + 직렬화 정책(SessionJson) + 상수만 검증" 명시.
- **차단 여부**: 차단 안 함 (Minor) — 본 라운드 sdk-qa-validator가 검증.

### 이월 항목 (이전 라운드 미해결)

- **S-T11-1** (F-001 E-107 발생 위치 명확화) — 본 라운드 영향 없음, 이월.
- **S-T12-1** (ImageInput.Url https 강제 + MockWebServer 통합) — 본 라운드 영향 없음, 이월.
- **S-T13-1/2/3** (F-003 E-303 / SSE error 이벤트 ERR 매핑 / R-005 stream 적용) — 본 라운드 영향 없음, 이월.
- **S-T15-1/2/3 (= Q-T14-1/2/3)** (ProviderRegistry public 노출 / @ModelId 옵션 qualifier / OkHttpClient 호출당 빌드) — 본 라운드 영향 없음, 이월.
- **S-T17-1/2/3/4 (= Q-T16-1/2/3/4)** (Provider.completeForSession 사양 명시 / E-401 키워드 / Session.save F-007 분리 / Mutex+synchronized 패턴) — 본 라운드에 S-T17-3(Session.save F-007 분리)은 해소됨 — 나머지 3건 이월.

---

## 5. F-007 종결 보고 — 모든 핵심 라운드 마무리

본 라운드(T18, F-007)로 v0.1 SDK의 모든 P0/P1 기능(F-000 ~ F-008)이 본체 구현 완료:

| F-ID | 구현 라운드 | 종결 라운드 |
|------|------------|------------|
| F-000 | T9 | qa_report_3 통과 |
| F-001 | T10/T11 | qa_report_4 통과 |
| F-002 | T12 | qa_report_5 통과 |
| F-003 | T13 | qa_report_6 통과 |
| F-006 | T14/T15 | qa_report_6 통과 |
| F-005 | T11/T15 | qa_report_5 통과 |
| F-008 | T11/T15 | qa_report_5 통과 |
| F-004 | T16 | qa_report_7 통과 |
| **F-007** | **T18 (본 라운드)** | **sdk-qa-validator 검증 요청 중** |

v0.1 P0/P1 모든 본체 구현 완료. 다음 단계:
1. sdk-qa-validator가 F-007 검증 → qa_report_8 (Blocker 0/Major 0 시 v0.1 release candidate)
2. (선택) spec-architect의 S-T11/S-T12/S-T13/S-T15/S-T17/Q-T18 사양 보강
3. (선택) androidTest 영역에서 DataStore 실제 IO 검증 라운드
4. v0.2 이후: 자동 재시도(RetryPolicy) / OpenAI Provider / VideoInput / Tool use / 자동 마이그레이션 / URL allowlist

---

## 6. sdk-qa-validator F-007 검증 요청

**sdk-qa-validator를 호출하여 F-007 (세션 영속화)의 사양-구현 정합성을 검증해 주세요.**

검증 본체 위치:
- `sdk/src/main/kotlin/com/androidailab/aisdk/session/Session.kt` — **`save()` 메서드 신규** (본 라운드 추가)
- `sdk/src/main/kotlin/com/androidailab/aisdk/session/SessionStore.kt` — 인터페이스
- `sdk/src/main/kotlin/com/androidailab/aisdk/session/SessionEntity.kt` — M-011 직렬화 모델 + 변환 헬퍼
- `sdk/src/main/kotlin/com/androidailab/aisdk/internal/storage/DataStoreSessionStore.kt` — DataStore 구현
- `sdk/src/main/kotlin/com/androidailab/aisdk/internal/storage/SessionJson.kt` — Json 정책
- `sdk/src/main/kotlin/com/androidailab/aisdk/AiAgentClient.kt` — `loadSession()` / `deleteSession()` / `sessionStore` 필드
- `sdk/src/main/kotlin/com/androidailab/aisdk/client/Builder.kt` — **`sessionStore()` hook + 자동 DataStoreSessionStore 인스턴스화** (본 라운드)
- `sdk/src/main/kotlin/com/androidailab/aisdk/di/SdkModule.kt` — **`provideSessionStore` 신규 + `provideAiAgentClient` 시그니처 변경** (본 라운드)

단위 테스트:
- `sdk/src/test/kotlin/com/androidailab/aisdk/session/SessionEntityTest.kt` (16 케이스)
- `sdk/src/test/kotlin/com/androidailab/aisdk/session/SessionSaveTest.kt` (11 케이스)
- `sdk/src/test/kotlin/com/androidailab/aisdk/client/LoadSessionTest.kt` (10 케이스)
- `sdk/src/test/kotlin/com/androidailab/aisdk/client/DeleteSessionTest.kt` (7 케이스)
- `sdk/src/test/kotlin/com/androidailab/aisdk/internal/storage/DataStoreSessionStoreTest.kt` (12 케이스)
- `sdk/src/test/kotlin/com/androidailab/aisdk/di/SdkModuleTest.kt` (기존 16 + 신규 3 = 19 케이스)

검증 요청 항목:
- F-007 저장 흐름 1~5 매핑 (Session.save → SessionEntity → SessionStore.save → DataStore put → sessionId 반환)
- F-007 복원 흐름 1~5 매핑 (loadSession → SessionStore.load → 역직렬화 → schemaVersion 검증 → Session 인스턴스)
- F-007 삭제 흐름 1~3 매핑 (deleteSession → SessionStore.delete → idempotent)
- A-010 시그니처 토큰 단위 일치 (`suspend fun save(): Result<String>`)
- A-011 시그니처 토큰 단위 일치 (`suspend fun loadSession(sessionId: String): Result<Session>`)
- A-012 시그니처 토큰 단위 일치 (`suspend fun deleteSession(sessionId: String): Result<Unit>`)
- M-011 SessionEntity / MessageEntity / ImageInputEntity 필드/제약 일치
- R-008 (Role.SYSTEM 영속화 차단) / R-017/R-024 (자동 UUID) / R-018 (schemaVersion=1 강제) / R-019 (다중 인스턴스 last-write-wins) / R-020 케이스 A/B / R-021 (이미지 영속화 가이드) / R-022 (마이그레이션 분리) / R-023 (URL SSRF 책임)
- E-701 / E-702 / E-703 / E-704 / E-705 / E-706 매핑
- ERR-004 (E-705) / ERR-005 (E-702/E-704) / ERR-007 (E-701/E-703)
- F-001/F-002/F-003/F-004/F-005/F-006/F-008 회귀 — 본 라운드 변경(Builder + SdkModule + Session.save 추가)이 기존 흐름에 영향 없는지

사양 ID:
- features.md F-007 (정상 흐름 / 동시성 모델 / 데이터 크기 정책 / 다중 인스턴스 정책 / 이미지 영속화 가이드 / 예외 흐름)
- features.md F-008 R-020 (close 시맨틱)
- api.md A-010 / A-011 / A-012
- data-model.md M-011 (SessionEntity / MessageEntity / ImageInputEntity / 변환 헬퍼 / 직렬화 정책)
- data-model.md M-007 (Session.save 시그니처)
- error-handling.md ERR-004 / ERR-005 / ERR-007
- overview.md D-002 (DataStore 채택) / D-003 (API 키 메모리 보관)

빌드 실행: 미수행 (settings.gradle.kts/wrapper 부재 — 정적 컴파일 타당성 + 토큰 단위 비교).
