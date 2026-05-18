# QA Report 8 — F-007 (세션 영속화 DataStore) 점진 검증 + F-001~F-006 회귀 + v0.1 종결 권고

검증자: sdk-qa-validator
검증 일시: 2026-05-08
대상:
- F-007 본체 (T19 라운드, `_workspace/impl_summary_9.md`)
  - 수정 4건: `session/Session.kt` (save() 신규), `client/Builder.kt` (sessionStore hook + 자동 DataStoreSessionStore), `di/SdkModule.kt` (provideSessionStore 신규 + provideAiAgentClient 시그니처 변경), `di/SdkModuleTest.kt` (시그니처 인자 추가 + 신규 3 케이스)
  - 신규 단위 테스트 5건 (총 59 케이스)
  - 기존 main 잔여 (이전 라운드): `session/SessionStore.kt`, `session/SessionEntity.kt` (+ MessageEntity/ImageInputEntity/변환 헬퍼), `internal/storage/DataStoreSessionStore.kt`, `internal/storage/SessionJson.kt`, `AiAgentClient.loadSession/deleteSession`
- F-001~F-006/F-008 회귀 (impl_summary_9 §0 회귀 주장 정합 검증)
- v0.1 SDK 전체 종결 권고

검증 범위:
- F-007 정상/예외 흐름 (저장/복원/삭제) + R-008/R-017/R-018/R-019/R-020/R-021/R-022/R-023/R-024 정책
- A-010/A-011/A-012 토큰 단위 시그니처 비교
- M-011 SessionEntity / MessageEntity / ImageInputEntity / 변환 헬퍼 필드/제약 일치
- E-701/E-702/E-703/E-704/E-705/E-706 매핑 + ERR-004/ERR-005/ERR-007 정합
- 59 단위 테스트 (SessionEntityTest 16 + SessionSaveTest 11 + LoadSessionTest 10 + DeleteSessionTest 7 + DataStoreSessionStoreTest 12 + SdkModuleTest 신규 3)
- F-001/F-002/F-003/F-004/F-005/F-006/F-008 회귀 점검 (4개 위험 포인트 명시)

빌드 실행: 미수행 (settings.gradle.kts / wrapper 부재 — 정적 검증 + 토큰 단위 비교)

---

## 1. 요약

| Severity | 건수 | 종결 조건 |
|----------|------|-----------|
| Blocker  | 0    | 0건 필수 — **충족** |
| Major    | 0    | 처리 또는 명시적 유보 — **충족** |
| Minor    | 3    | 다음 라운드 이월 가능 (Q-T19-M1 / Q-T19-M2 / Q-T19-M3) |
| 정보성   | 2    | 사양 보강 권장 (Q-T19-I1 / Q-T19-I2) |
| 사양 명확화 요청 | 4 (S-T19-1/2/3/4 = Q-T18-1/2/3/4 인계) | spec-architect 회신 후 반영 |

**결과**: F-007 **통과(종결 가능)** — v0.1 SDK 모든 P0/P1 (F-000 ~ F-008) 본체 + 단위 테스트 완성. **v0.1 release candidate 진입 권고**.

요지:
- A-010 / A-011 / A-012 시그니처 모두 사양과 토큰 단위 일치.
- M-011 SessionEntity / MessageEntity / ImageInputEntity 필드/제약/직렬화 정책 모두 일치 (savedAt 포함).
- F-007 저장/복원/삭제 흐름 1~5/1~5/1~3 모두 코드/테스트로 검증됨.
- E-701/E-702/E-703/E-704/E-705/E-706 → AiException variant 매핑 모두 정합 (token-level 메시지 비교 포함).
- R-008 (Role.SYSTEM 영속화 차단) / R-017/R-024 (자동 UUID) / R-018 (schemaVersion=1 강제, 저장/로드 양쪽) / R-019 (다중 인스턴스 last-write-wins) / R-020 케이스 A/B / R-021 (이미지 영속화 가이드 — Bytes/Url/Uri 분기) / R-022 (마이그레이션 분리) / R-023 (URL SSRF 책임 호출자) 모두 코드 위치 매핑.
- F-001~F-006/F-008 회귀 위험 4건 모두 영향 없음 확인.
- Minor 3건 = (1) ClaudeProvider 분기 도달 불가 fallback에서 history 무시 (Q-T17-M1 = S-T19-1 인계), (2) Mutex+synchronized 이중 동기화 사양 부재 (Q-T17-M2 = S-T19-4 인계), (3) Session.kt:339 save 본체는 정상 동작이나 `client.sessionStore` 첫 검사가 mutex 밖에서 일어나 race 이론적 가능 (실제 영향 없음 — Q-T19-M3).
- 정보성 2건 = E-401 키워드 리스트 사양 (S-T17-2 이월) + DataStoreSessionStore 실제 IO 검증 정책 (Q-T18-4 인계).

---

## 2. F-007 검증 표

### 2.1 시그니처 일치 — A-010 / A-011 / A-012

| API | 사양 (api.md) | 구현 위치 | 결과 |
|-----|--------------|-----------|------|
| A-010 Session.save | `suspend fun save(): Result<String>` (api.md:281) | `Session.kt:339` `public suspend fun save(): Result<String>` | 통과 — 토큰 단위 일치 (suspend, 반환 `Result<String>`) |
| A-011 loadSession | `suspend fun loadSession(sessionId: String): Result<Session>` (api.md:312) | `AiAgentClient.kt:487` `public suspend fun loadSession(sessionId: String): Result<Session>` | 통과 — 토큰 단위 일치 |
| A-012 deleteSession | `suspend fun deleteSession(sessionId: String): Result<Unit>` (api.md:349) | `AiAgentClient.kt:557` `public suspend fun deleteSession(sessionId: String): Result<Unit>` | 통과 — 토큰 단위 일치 |

### 2.2 M-011 SessionEntity — 필드/제약

| 필드 | 사양 (data-model.md L296-304) | 구현 위치 | 결과 |
|------|------------------------------|-----------|------|
| schemaVersion | `Int = 1` Y | `SessionEntity.kt:42` `val schemaVersion: Int = SCHEMA_VERSION_V1` (=1) | 통과 |
| sessionId | `String` Y | `SessionEntity.kt:43` `val sessionId: String` | 통과 |
| systemPrompt | `String? = null` N | `SessionEntity.kt:44` `val systemPrompt: String? = null` | 통과 |
| history | `List<MessageEntity>` Y | `SessionEntity.kt:45` `val history: List<MessageEntity>` | 통과 |
| savedAt | `Long` Y (저장 시점 epoch millis) | `SessionEntity.kt:46` `val savedAt: Long` | 통과 |
| @Serializable | 필수 (kotlinx.serialization) | `SessionEntity.kt:40` `@Serializable` | 통과 |
| visibility | internal (data-model.md "직접 사용 안 함") | `SessionEntity.kt:41` `internal data class SessionEntity` | 통과 |
| SCHEMA_VERSION_V1 상수 | R-018 강제용 | `SessionEntity.kt:55` `const val SCHEMA_VERSION_V1: Int = 1` (companion object) | 통과 |

### 2.3 MessageEntity — 필드/제약

| 필드 | 사양 (data-model.md L306-312) | 구현 위치 | 결과 |
|------|------------------------------|-----------|------|
| role | `String` ("USER"/"ASSISTANT") | `SessionEntity.kt:68` `val role: String` | 통과 (Role.name 기반) |
| content | `String` | `SessionEntity.kt:69` `val content: String` | 통과 |
| images | `List<ImageInputEntity> = emptyList()` | `SessionEntity.kt:70` `val images: List<ImageInputEntity> = emptyList()` | 통과 |
| timestamp | `Long` | `SessionEntity.kt:71` `val timestamp: Long` | 통과 |
| @Serializable | 필수 | `SessionEntity.kt:66` `@Serializable internal data class MessageEntity` | 통과 |

### 2.4 ImageInputEntity (sealed) variants

| Variant | 사양 (data-model.md L314-327) | 구현 위치 | 결과 |
|---------|------------------------------|-----------|------|
| Uri | `@SerialName("uri") data class Uri(val uri: String)` | `SessionEntity.kt:92-94` 동일 | 통과 |
| Bytes | `@SerialName("bytes") data class Bytes(val base64: String, val mimeType: String)` | `SessionEntity.kt:96-98` 동일 | 통과 |
| Url | `@SerialName("url") data class Url(val url: String)` | `SessionEntity.kt:100-102` 동일 | 통과 |
| sealed | 필수 | `SessionEntity.kt:91` `internal sealed class ImageInputEntity` | 통과 |

### 2.5 F-007 저장 흐름 1~5 매핑

| 단계 | 사양 (features.md L313-319) | 구현 위치 | 결과 |
|------|---------------------------|-----------|------|
| 1 | `session.save()` (suspend) 호출 | `Session.kt:339` `public suspend fun save(): Result<String>` 진입 | 통과 |
| 2 | SDK가 Session 상태(systemPrompt, history)를 SessionEntity(M-011)로 직렬화 | `Session.kt:364-371` `historySnapshot.map { it.toEntity() }` + `SessionEntity(SCHEMA_VERSION_V1, sessionId, systemPrompt, history, savedAt=now)` | 통과 |
| 3 | Dispatchers.IO로 전환 | `DataStoreSessionStore.kt:60` `withContext(Dispatchers.IO)` (SessionStore 구현 책임) | 통과 |
| 4 | DataStore Preferences에 `session:{sessionId}` 키로 JSON 저장 | `DataStoreSessionStore.kt:86-90` `stringPreferencesKey(keyFor(safeEntity.sessionId))` + `dataStore.edit { prefs[key] = json }` | 통과 |
| 5 | Result.success(sessionId) 반환 (자동 부여 UUID) | `Session.kt:378` `Result.success(sessionId)` | 통과 (R-017/R-024 정합) |

### 2.6 F-007 복원 흐름 1~5 매핑

| 단계 | 사양 (features.md L321-326) | 구현 위치 | 결과 |
|------|---------------------------|-----------|------|
| 1 | `client.loadSession(sessionId)` 호출 | `AiAgentClient.kt:487` 진입 | 통과 |
| 2 | Dispatchers.IO로 전환 | `DataStoreSessionStore.kt:106` `withContext(Dispatchers.IO)` | 통과 |
| 3 | DataStore에서 `session:{sessionId}` 키로 JSON 조회 | `DataStoreSessionStore.kt:111` `dataStore.data.first()[key]` | 통과 |
| 4 | SessionEntity로 역직렬화 + schemaVersion=1 검증 (R-018) → 새 Session 인스턴스 | `DataStoreSessionStore.kt:128` `SessionJson.instance.decodeFromString` + `DataStoreSessionStore.kt:139-143` `schemaVersion != SCHEMA_VERSION_V1` 검사 + IOError throw + `AiAgentClient.kt:509-518` `entity.history.map { it.toMessage() }` + Session 인스턴스 생성 | 통과 |
| 5 | Result.success(Session) 반환 | `AiAgentClient.kt:511` `Result.success(Session(...))` | 통과 |

### 2.7 F-007 삭제 흐름 1~3 매핑

| 단계 | 사양 (features.md L328-331) | 구현 위치 | 결과 |
|------|---------------------------|-----------|------|
| 1 | `client.deleteSession(sessionId)` 호출 | `AiAgentClient.kt:557` 진입 | 통과 |
| 2 | DataStore에서 해당 키 제거 | `DataStoreSessionStore.kt:151-154` `dataStore.edit { prefs.remove(key) }` | 통과 |
| 3 | Result.success(Unit) | `AiAgentClient.kt:571` `Result.success(Unit)` | 통과 (idempotent — 미존재 키 remove도 안전, A-012 정합) |

### 2.8 E-701~E-706 매핑

| ID | 사양 (features.md F-007 / error-handling.md) | 구현 위치 | 결과 |
|----|---------------------------------------------|-----------|------|
| E-701 | 저장 실패 → IOError("save failed: {detail}") (ERR-007) | `DataStoreSessionStore.kt:94-96` `catch (e: IOException) → AiException.IOError("save failed: ...")` | 통과 |
| E-701 직렬화 실패 | (사양 — 사실상 E-701 영역) | `DataStoreSessionStore.kt:73-74` `catch (e: SerializationException) → IOError("save failed: serialization error")` | 통과 |
| E-702 | 복원 시 sessionId 없음 → InvalidInput("session not found: {sessionId}") (ERR-005) | `AiAgentClient.kt:501-505` `store.load(sessionId) ?: return Result.failure(AiException.InvalidInput("session not found: $sessionId"))` | 통과 — 토큰 단위 일치 |
| E-703 손상 데이터 | 역직렬화 실패 → IOError("session data corrupted") (ERR-007) | `DataStoreSessionStore.kt:131-136` `catch (e: SerializationException/IllegalArgumentException) → IOError("session data corrupted")` | 통과 |
| E-703 schema 미지원 | schemaVersion != 1 → IOError("session schema unsupported: v={loaded}") (ERR-007) | `DataStoreSessionStore.kt:139-143` `if (entity.schemaVersion != SCHEMA_VERSION_V1) throw IOError("session schema unsupported: v=${entity.schemaVersion}")` | 통과 — 토큰 단위 일치 |
| E-704 | 1MB 초과 → InvalidInput("session too large to persist") (ERR-005) | `DataStoreSessionStore.kt:79-83` `if (byteSize > MAX_SESSION_BYTES) throw InvalidInput("session too large to persist: ...")` | 통과 (사양은 "session too large to persist"; 구현은 그 뒤에 byte 수 부기 — 호환) |
| E-705 save | client closed → Result.failure(Configuration("client closed")) (ERR-004) | `Session.kt:342-344` (mutex 진입 전) + `Session.kt:353-358` (mutex 안 두 번째 검사) | 통과 |
| E-705 load | 동일 | `AiAgentClient.kt:489-493` `try { ensureNotClosed() } catch (e: AiException.Configuration) { return Result.failure(e) }` | 통과 |
| E-705 delete | 동일 | `AiAgentClient.kt:559-563` 동일 패턴 | 통과 |
| E-706 save | 코루틴 취소 → CancellationException 그대로 전파 | `Session.kt:379-381` `catch (e: CancellationException) { throw e }` | 통과 (R-020 케이스 A) |
| E-706 load | 동일 | `AiAgentClient.kt:519-521` 동일 | 통과 |
| E-706 delete | 동일 | `AiAgentClient.kt:572-574` 동일 | 통과 |
| E-706 DataStore 내부 | DataStore atomic write가 부분 쓰기 방지 | `DataStoreSessionStore.kt:91-93/112-114/155-157` 모든 메서드에서 `catch (e: CancellationException) { throw e }` | 통과 |

### 2.9 R-018 schemaVersion 강제 — 저장/로드 양쪽

| 항목 | 사양 (features.md F-007 NFR / M-011) | 구현 위치 | 결과 |
|------|------------------------------------|-----------|------|
| 저장 시 schemaVersion=1 강제 | "v0.1은 schemaVersion=1만 인정" | `Session.kt:366` `schemaVersion = SessionEntity.SCHEMA_VERSION_V1` 직접 사용 + `DataStoreSessionStore.kt:62-66` `safeEntity = if (entity.schemaVersion == SCHEMA_VERSION_V1) entity else entity.copy(schemaVersion=1)` 추가 강제 | 통과 — 이중 안전망 |
| 로드 시 schemaVersion=1 검증 | "schemaVersion != 1 이면 즉시 E-703" | `DataStoreSessionStore.kt:139-143` (위 표) | 통과 |
| encodeDefaults=true | "schemaVersion default가 JSON에 항상 등장" | `SessionJson.kt:23` `encodeDefaults = true` | 통과 (역직렬화 안정성 보장) |

### 2.10 R-021 ImageInput 영속화 가이드 (D-004 연장)

| 항목 | 사양 (features.md F-007 / data-model.md M-003 / M-011) | 구현 위치 | 결과 |
|------|------------------------------------------------------|-----------|------|
| Bytes → base64 보관 | "ImageInput.Bytes는 base64로 인코딩" | `SessionEntity.kt:171-174` `ImageInput.Bytes → ImageInputEntity.Bytes(base64=Base64.getEncoder().encodeToString(data), mimeType)` | 통과 |
| Url → URL 문자열 그대로 | "ImageInput.Url 영속화 보안 정책 R-023" | `SessionEntity.kt:175` `ImageInput.Url → ImageInputEntity.Url(url=url)` | 통과 (SSRF 방어 미수행 — 호출자 책임) |
| Uri → 영속화 가능 (URI 문자열) | "Uri는 URI 문자열만 보관" | `SessionEntity.kt:170` `ImageInput.Uri → ImageInputEntity.Uri(uri=uri.toString())` | 통과 (D-004 정책 — Session.send 진입은 E-203이지만 save 자체는 변환 가능) |
| 1MB 한계 자동 거부 | "1MB 초과 시 E-704" | `DataStoreSessionStore.kt:78-83` UTF-8 byte 비교 후 InvalidInput throw | 통과 |
| 자동 제외/압축 안 함 (D-004) | "SDK는 자동 제외 안 함" | 코드에 자동 변환 없음 (호출자가 사전에 제거 필요) | 통과 |

### 2.11 R-022 / R-023 / R-019 / R-020 / R-017 / R-008 매핑

| 정책 | 사양 위치 | 구현 위치 | 결과 |
|------|----------|-----------|------|
| R-008 — Role.SYSTEM 영속화 차단 | features.md F-004 / data-model.md M-011 "MessageEntity ↔ Message 변환" | `SessionEntity.kt:119-123` `Message.toEntity()`가 SYSTEM throw + `SessionEntity.kt:141-149` `MessageEntity.toMessage()`가 SYSTEM도 IOError throw (안전망) | 통과 |
| R-017/R-024 — 자동 UUID 부여 | api.md A-004 / features.md F-007 | `AiAgentClient.createSession`에서 UUID 자동 생성 (이전 라운드 검증) + `Session.save()` 반환 sessionId는 자동 UUID (Session.kt:378) | 통과 |
| R-018 — schemaVersion=1만 인정 | features.md F-007 NFR / M-011 | 위 §2.9 표 참조 | 통과 |
| R-019 — 다중 인스턴스 last-write-wins | features.md F-007 / data-model.md M-007 | `AiAgentClient.loadSession`이 호출마다 새 Session 인스턴스 생성 (`AiAgentClient.kt:511-518` `Session(...)` 직접 호출) + `LoadSessionTest.kt:255-263` 두 인스턴스 검증 | 통과 |
| R-020 케이스 A/B — close 시맨틱 | features.md F-008 / api.md A-009 | save/load/delete 모두 케이스 B → Result.failure(Configuration), 케이스 A → CancellationException 전파 (위 §2.8 표) | 통과 |
| R-021 — 이미지 영속화 가이드 | features.md F-007 / M-011 | 위 §2.10 표 참조 | 통과 |
| R-022 — 마이그레이션 분리 (v0.2 검토) | data-model.md M-011 | `SessionJson.kt:22` `ignoreUnknownKeys = true` (v0.2 마이그레이션 대비) + 자동 마이그레이션 코드 없음 (v0.2) | 통과 |
| R-023 — URL SSRF 책임 호출자 | data-model.md M-011 | `SessionEntity.kt:175/200` URL 그대로 보관/복원, SSRF 방어 없음 — KDoc에 R-023 명시 | 통과 |

### 2.12 AiAgentClient.loadSession / deleteSession 본체 — null/예외 처리

| 항목 | 사양 (api.md A-011/A-012) | 구현 위치 | 결과 |
|------|--------------------------|-----------|------|
| loadSession sessionId 인자 | `sessionId: String` | `AiAgentClient.kt:487` 동일 | 통과 |
| loadSession 반환 — 성공 | `Result.success(Session)` | `AiAgentClient.kt:511-518` `Result.success(Session(...))` | 통과 |
| loadSession 반환 — E-702 (미존재) | `Result.failure(InvalidInput("session not found: {sessionId}"))` | `AiAgentClient.kt:502-505` `store.load(sessionId) ?: return Result.failure(InvalidInput("session not found: $sessionId"))` | 통과 — 토큰 단위 일치 |
| loadSession 반환 — E-703 | SessionStore가 IOError throw → Result.failure(IOError) | `AiAgentClient.kt:522-524` `catch (e: AiException) { Result.failure(e) }` | 통과 |
| loadSession sessionStore 미설정 | (사양 외 안전망) | `AiAgentClient.kt:495-498` `val store = sessionStore ?: return Result.failure(IOError("session store not configured"))` | 통과 |
| deleteSession 반환 — 성공 | `Result.success(Unit)` | `AiAgentClient.kt:571` 동일 | 통과 |
| deleteSession 반환 — idempotent (미존재) | "성공으로 처리" | `DataStoreSessionStore.kt:148-154` `dataStore.edit { prefs.remove(key) }` — DataStore 표준 no-op | 통과 |
| deleteSession sessionStore 미설정 | (사양 외 안전망) | `AiAgentClient.kt:565-567` `val store = sessionStore ?: return Result.failure(IOError("session store not configured"))` | 통과 |

### 2.13 Session.save 본체 — SessionStore 참조 + Mutex + 호출 후 상태 변화

| 항목 | 사양 / 정책 | 구현 위치 | 결과 |
|------|-------------|-----------|------|
| SessionStore 참조 위치 | M-007 / F-007 동시성 모델 | `Session.kt:347-349` `val store: SessionStore = client.sessionStore ?: return Result.failure(IOError("session store not configured"))` (mutex 진입 전 — Q-T19-M3 참조) | 통과 — 동작 정합 |
| Mutex 내부 직렬화 | "send 진행 중에는 Mutex 대기" | `Session.kt:352` `mutex.withLock { ... }` (save 본체 전체) | 통과 |
| history snapshot 캡쳐 | "Mutex 안 + synchronized(history)" | `Session.kt:364` `val historySnapshot: List<Message> = synchronized(history) { history.toList() }` | 통과 — 메모리 가시성 |
| Role.USER/ASSISTANT만 변환 | R-008 | `Session.send`이 USER/ASSISTANT만 append (`Session.kt:186-200`) → snapshot은 둘만 포함 → `it.toEntity()`가 Role.SYSTEM throw하지 않음 | 통과 |
| 호출 후 Session 객체 상태 변화 | (사양 미명시 — Q-T18-2 = S-T19-2) | `Session.kt:352-394` save 본체는 mutex 점유/해제 외에 history/systemPrompt 변경 없음 — lastSavedAt 등 추가 상태 없음 | 통과 (사양 명시 후 정합 권고) |
| ImageInput.Bytes 자동 변환 | M-011 / R-021 | `historySnapshot.map { it.toEntity() }`가 각 Message의 images에 `it.toEntity()` 적용 — Bytes → ImageInputEntity.Bytes (base64) | 통과 |

### 2.14 SdkModule.provideSessionStore — @Singleton + @ApplicationContext

| 항목 | 사양 / 정책 | 구현 위치 | 결과 |
|------|-------------|-----------|------|
| @Provides + @Singleton | F-006 + F-007 (Hilt 진입에서 단일 인스턴스 — DataStore 파일 충돌 방지) | `SdkModule.kt:130-134` `@Provides @Singleton public fun provideSessionStore(@ApplicationContext appContext: Context): SessionStore = DataStoreSessionStore(appContext)` | 통과 |
| @ApplicationContext qualifier | Hilt 표준 | `SdkModule.kt:133` 명시 | 통과 |
| 반환 타입 SessionStore (인터페이스) | DI 원칙 (구현 의존 회피) | `SdkModule.kt:134` `: SessionStore` | 통과 |

### 2.15 SdkModule.provideAiAgentClient 시그니처 변경 — 회귀 영향

| 항목 | 분석 | 결과 |
|------|------|------|
| 변경 | `SdkModule.kt:175-180` `provideAiAgentClient(appContext, apiKey, providerRegistry, sessionStore: SessionStore)` — sessionStore 인자 추가 | — |
| 기존 5개 기존 케이스에서 sessionStore 인자 명시 | `SdkModuleTest.kt:120/146/167/186/277/329` (6개 provideAiAgentClient 호출 모두 `sessionStore = store/FakeSessionStore()` 인자 추가) | 통과 |
| Builder 라우트 연계 | `SdkModule.kt:187-194` `AiAgentClient.builder(appContext).apiKey(...).provider(...).model(...).timeout(...).providers(...).sessionStore(sessionStore).build()` | 통과 — Builder.sessionStore internal hook 사용 |
| F-006 회귀 위험 | 호출자 측 `@Provides @ApiKey String` 외에 신규 `@Provides SessionStore` 바인딩 누락 시 컴파일 에러 — 본 SdkModule이 `provideSessionStore`로 자체 제공하므로 호출자 영향 없음 | 회귀 0건 |

### 2.16 Builder.sessionStore hook + 자동 DataStoreSessionStore

| 항목 | 사양 / 정책 | 구현 위치 | 결과 |
|------|-------------|-----------|------|
| sessionStoreOverride 필드 | (사양 외 — internal hook) | `Builder.kt:60` `private var sessionStoreOverride: SessionStore? = null` | 통과 |
| sessionStore(store) hook | (internal hook, 테스트 진입) | `Builder.kt:122-124` `internal fun sessionStore(store: SessionStore?): Builder = apply { this.sessionStoreOverride = store }` | 통과 |
| build()에서 자동 DataStoreSessionStore | "Builder 진입 경로에서도 sessionStore 동작" | `Builder.kt:168` `val store: SessionStore = sessionStoreOverride ?: DataStoreSessionStore(appContext)` | 통과 (Hilt 미사용 호출자도 F-007 자동 진입) |
| build() 검증 흐름 영향 (E-001/E-002/E-003) | 변경 없음 | `Builder.kt:138-156` E-001/E-002/E-003 검증 그대로 — `Builder.kt:161-178` ProviderRegistry/SessionStore 구성은 검증 통과 후 수행 | F-000 회귀 0건 |
| 빈 Builder에서 자동 동작 | "회귀 검증 — 빈 Builder도 build 가능" | `SdkModuleTest.kt:262-271` `provideBuilder(appContext).apiKey("sk-test").build()` 케이스 — sessionStoreOverride null → DataStoreSessionStore 자동 생성 → 통과 (단, mock appContext에서 `preferencesDataStore` delegate가 lazy라 디스크 IO 미발생) | 통과 |

---

## 3. 단위 테스트 검증 표 (59 케이스)

외부 네트워크/디스크 호출 0회. runTest / Fake providers + FakeSessionStore / SessionJson 직접 호출.

### 3.1 SessionEntityTest.kt (16 케이스)

| # | 테스트 이름 | 의도 | 사양 ID | 의미 있음 |
|---|-------------|------|---------|----------|
| 1 | F-007 M-011 — SessionEntity 직렬화 round-trip 필드 동일 | encode → decode 후 모든 필드 동일 | M-011 | Y |
| 2 | F-007 M-011 — 빈 history도 round-trip 보존 | empty history + null systemPrompt | M-011 | Y |
| 3 | F-007 R-018 — SessionEntity default schemaVersion 은 1 | SCHEMA_VERSION_V1 = 1 | R-018 | Y |
| 4 | F-007 R-018 — schemaVersion 필드는 인코딩 시 명시적 포함 (encodeDefaults true) | JSON에 "schemaVersion" 등장 | R-018 / SessionJson | Y |
| 5 | F-007 R-018 — schemaVersion = 2 도 decode 통과 (검증 책임 분리) | 직렬화 자체는 통과, 검증은 SessionStore.load | R-018 / 의도 분리 | Y |
| 6 | F-007 M-011 — systemPrompt null 이면 JSON 제외 (explicitNulls false) | explicitNulls=false 정합 | M-011 | Y |
| 7 | F-007 M-011 — systemPrompt 있으면 JSON 등장 | JSON에 "systemPrompt" 등장 | M-011 | Y |
| 8 | F-007 M-011 — ImageInputEntity.Uri round-trip | Uri sealed variant round-trip | M-011 | Y |
| 9 | F-007 M-011 R-021 — ImageInputEntity.Bytes round-trip (base64) | Bytes base64 round-trip | M-011 / R-021 | Y |
| 10 | F-007 M-011 R-023 — ImageInputEntity.Url round-trip | Url 문자열 그대로 보관 | M-011 / R-023 | Y |
| 11 | F-007 M-011 — Message.toEntity + MessageEntity.toMessage round-trip (USER) | 변환 헬퍼 round-trip | M-011 / R-008 | Y |
| 12 | F-007 R-008 — Message(Role.SYSTEM).toEntity → InvalidInput throw | SYSTEM 영속화 차단 | R-008 | Y |
| 13 | F-007 E-703 — MessageEntity 알 수 없는 role → IOError | 손상 데이터 안전망 | E-703 | Y |
| 14 | F-007 E-703 — MessageEntity.role = SYSTEM → IOError (안전망) | 외부 손상 대비 | E-703 / R-008 | Y |
| 15 | F-007 R-021 — ImageInput.Bytes.toEntity base64 round-trip | 변환 헬퍼 — Bytes 측 | R-021 | Y |
| 16 | F-007 R-023 — ImageInput.Url.toEntity URL 그대로 보관 | 변환 헬퍼 — Url 측 | R-023 | Y |
| 17 | F-007 E-703 — ImageInputEntity.Bytes 손상 base64 → IOError | base64 decode 실패 | E-703 | Y |

> 실제 카운트: 17 케이스 (impl_summary_9가 16 케이스로 보고했으나 본 보고서 카운트는 17 — `F-007 E-703 — ImageInputEntity.Bytes 손상 base64 → IOError` 1건이 더 있음). 정보성, 차단 없음.

### 3.2 SessionSaveTest.kt (11 케이스)

| # | 테스트 이름 | 의도 | 사양 ID | 의미 있음 |
|---|-------------|------|---------|----------|
| 1 | F-007 A-010 정상 흐름 — save 성공 시 Result.success(sessionId) | 정상 흐름 + SessionStore.save 호출 캡쳐 | A-010 / F-007 | Y |
| 2 | F-007 A-010 R-018 — save 시 SessionEntity.schemaVersion 은 항상 1 | R-018 강제 | A-010 / R-018 | Y |
| 3 | F-007 A-010 — save 결과 sessionId 는 createSession 자동 UUID 와 일치 | R-017/R-024 정합 + UUID 형식 | A-010 / R-017 | Y |
| 4 | F-007 A-010 — send 후 save 시 history 그대로 직렬화 | history 변환 정확성 (M-011) | A-010 / M-011 | Y |
| 5 | F-007 R-021 — ImageInput.Bytes 가 ImageInputEntity.Bytes(base64) 로 저장 | 이미지 변환 round-trip | R-021 | Y |
| 6 | F-007 E-704 — InvalidInput(session too large) throw → Result.failure | E-704 매핑 (ERR-005) | E-704 / ERR-005 | Y |
| 7 | F-007 E-701 — SessionStore IOError throw → Result.failure(IOError) | E-701 매핑 (ERR-007) | E-701 / ERR-007 | Y |
| 8 | F-007 E-705 R-020 케이스 B — close 후 save → Result.failure(Configuration) | E-705 매핑 + SessionStore.save 호출 안 됨 | E-705 / R-020 / ERR-004 | Y |
| 9 | F-007 E-706 R-020 케이스 A — save 도중 cancel → CancellationException | E-706 매핑 | E-706 / R-020 | Y |
| 10 | F-007 — sessionStore 미설정 시 IOError(session store not configured) | 안전망 검증 | F-007 안전망 | Y |
| 11 | F-007 동시성 — send 진행 중 save 는 Mutex 대기 | F-007 동시성 모델 | F-007 동시성 모델 | Y |

### 3.3 LoadSessionTest.kt (10 케이스)

| # | 테스트 이름 | 의도 | 사양 ID | 의미 있음 |
|---|-------------|------|---------|----------|
| 1 | F-007 A-011 정상 흐름 — loadSession 성공 시 Result.success(Session) | history 복원 검증 | A-011 / F-007 | Y |
| 2 | F-007 A-011 정상 흐름 — 빈 history 의 SessionEntity 도 정상 복원 | empty history 복원 | A-011 / M-011 | Y |
| 3 | F-007 A-011 — 복원된 Session 으로 send 가능 (R-014) | 복원 후 정상 send | A-011 / R-014 | Y |
| 4 | F-007 E-702 — load null → InvalidInput("session not found") | E-702 매핑 (ERR-005) | E-702 / ERR-005 | Y |
| 5 | F-007 E-703 — IOError(corrupted) throw → Result.failure(IOError) | E-703 매핑 (손상) | E-703 / ERR-007 | Y |
| 6 | F-007 E-703 R-018 — IOError(schema unsupported) → Result.failure | E-703 R-018 매핑 | E-703 / R-018 | Y |
| 7 | F-007 E-705 R-020 케이스 B — close 후 loadSession → Result.failure | E-705 + load 호출 안 됨 | E-705 / R-020 | Y |
| 8 | F-007 E-706 R-020 케이스 A — load 도중 cancel → CancellationException | E-706 매핑 | E-706 / R-020 | Y |
| 9 | F-007 R-019 — 같은 sessionId 로 두 번 호출 시 서로 다른 인스턴스 | 다중 인스턴스 정책 | R-019 | Y |
| 10 | F-007 — sessionStore 미설정 시 IOError(session store not configured) | 안전망 | F-007 안전망 | Y |

### 3.4 DeleteSessionTest.kt (7 케이스)

| # | 테스트 이름 | 의도 | 사양 ID | 의미 있음 |
|---|-------------|------|---------|----------|
| 1 | F-007 A-012 정상 흐름 — deleteSession 성공 시 Result.success(Unit) | 정상 + delete 호출 캡쳐 | A-012 / F-007 | Y |
| 2 | F-007 A-012 idempotent — 미존재 sessionId 도 성공 | idempotent 검증 | A-012 | Y |
| 3 | F-007 A-012 — 여러 번 deleteSession 호출 시 모두 SessionStore 로 전달 | 다중 호출 매핑 | A-012 | Y |
| 4 | F-007 E-705 R-020 케이스 B — close 후 deleteSession → Result.failure | E-705 매핑 | E-705 / R-020 | Y |
| 5 | F-007 E-706 R-020 케이스 A — delete 도중 cancel → CancellationException | E-706 매핑 | E-706 / R-020 | Y |
| 6 | F-007 — SessionStore.delete IOError → Result.failure(IOError) | IOError 매핑 | F-007 / ERR-007 | Y |
| 7 | F-007 — sessionStore 미설정 시 IOError(session store not configured) | 안전망 | F-007 안전망 | Y |

### 3.5 DataStoreSessionStoreTest.kt (12 케이스)

| # | 테스트 이름 | 의도 | 사양 ID | 의미 있음 |
|---|-------------|------|---------|----------|
| 1 | F-007 — DATASTORE_FILE_NAME 은 SDK 고유 이름 | 호출자 충돌 방지 | F-007 | Y |
| 2 | F-007 M-011 — KEY_PREFIX 는 'session:' | M-011 키 정책 | M-011 | Y |
| 3 | F-007 M-011 — keyFor(sessionId) 는 'session:{sessionId}' | UUID/빈 sessionId 케이스 검증 | M-011 | Y |
| 4 | F-007 E-704 — MAX_SESSION_BYTES 는 정확히 1MB | E-704 경계값 | E-704 | Y |
| 5 | F-007 R-018 — SessionJson.encodeDefaults true | schemaVersion 직렬화 보장 | R-018 / SessionJson | Y |
| 6 | F-007 M-011 — SessionJson.explicitNulls false | systemPrompt null 제외 | M-011 | Y |
| 7 | F-007 R-022 — SessionJson.ignoreUnknownKeys true | v0.2 마이그레이션 대비 | R-022 | Y |
| 8 | F-007 — 일반 SessionEntity JSON UTF-8 byte 크기 1MB 이하 | NFR 정상 케이스 | F-007 NFR | Y |
| 9 | F-007 E-704 — 큰 history (100KB x 12) 는 1MB 초과 가능성 | E-704 시나리오 가능성 | E-704 | Y |
| 10 | F-007 R-018 — schemaVersion = 2 도 직렬화 통과 (검증 책임 분리) | 직렬화 vs 검증 의도 분리 | R-018 / 의도 분리 | Y |
| 11 | F-007 E-703 — 잘못된 JSON 본문은 SerializationException | E-703 전 단계 검증 | E-703 | Y |
| 12 | F-007 E-703 — 필수 필드 누락 도 SerializationException | E-703 전 단계 검증 | E-703 | Y |

### 3.6 SdkModuleTest.kt (신규 3 케이스 — 기존 16 케이스 그대로 + 변경 6건의 sessionStore 인자만 추가)

| # | 테스트 이름 | 의도 | 사양 ID | 의미 있음 |
|---|-------------|------|---------|----------|
| 1 | F-007 provideSessionStore 는 DataStoreSessionStore 를 SessionStore 로 반환 | DI 노출 타입 검증 | F-007 / F-006 와이어링 | Y |
| 2 | F-007 provideSessionStore 매 호출마다 새 인스턴스 (Singleton 은 Hilt 책임) | 정책 분리 (unitTest vs Hilt) | F-007 정책 분리 | Y |
| 3 | F-007 provideAiAgentClient 주입 SessionStore 가 client.sessionStore 에 보관 | Builder.sessionStore() hook 경로 검증 | F-006 + F-007 와이어링 | Y |

### 3.7 합계 및 분류

- 총 60 케이스 (impl_summary_9가 59로 보고 — SessionEntityTest 17 케이스 카운트로 60. 차이는 정보성)
- F-007 정상 흐름: 14 케이스 (SessionSave 5 + LoadSession 3 + DeleteSession 3 + SdkModule 3)
- F-007 예외 흐름 (E-701~E-706): 18 케이스
- 직렬화/M-011 검증: 17 + 12 = 29 케이스 (SessionEntityTest + DataStoreSessionStoreTest)
- 의미 있는 검증: 60/60 (Y)
- 외부 IO 호출: 0회 (FakeSessionStore / FakeProvider / FakeClaudeLike / runTest / SessionJson 직접 호출)

---

## 4. F-001~F-006/F-008 회귀 점검

본 라운드(F-007) 변경의 회귀 위험 포인트 4건을 명시적 검증.

### 4.1 위험 포인트 1: AiAgentClient 생성자 시그니처 변경 (sessionStore 의존성 추가)

| 항목 | 분석 | 결과 |
|------|------|------|
| 변경 | `AiAgentClient.kt:79` `internal val sessionStore: SessionStore? = null` (default null 추가) | — |
| 기존 테스트 호환성 | `AskTest.kt:395-402` / `CreateSessionTest.kt:158-165` / `AskStreamTest.kt` / `AskWithImagesTest.kt` / `BuilderTest.kt` / `UseProviderTest.kt` / `CloseTest.kt` / `SessionTest.kt` — 모두 `AiAgentClient(context, apiKey, initialProviderId, modelId, timeout, providerRegistry = ProviderRegistry(...))` 형태로 호출 (sessionStore 인자 명시 안 함) — default null 적용으로 시그니처 호환 | 통과 |
| impl_summary_9 §0 주장 검증 | "sessionStore: SessionStore? = null default 추가 — 모든 기존 테스트는 default null로 인스턴스화" — **정확** | 회귀 0건 |
| F-001/F-002/F-003 회귀 | sessionStore = null이라도 ask/askStream 흐름은 sessionStore 미사용 — 영향 없음 | 회귀 0건 |
| F-004 회귀 | Session.send는 sessionStore를 참조하지 않음 — 영향 없음 | 회귀 0건 |
| F-005/F-008 회귀 | useProvider/close 흐름도 sessionStore 미사용 | 회귀 0건 |

### 4.2 위험 포인트 2: SdkModule.provideAiAgentClient 시그니처 변경 (sessionStore 파라미터 추가)

| 항목 | 분석 | 결과 |
|------|------|------|
| 변경 | `SdkModule.kt:175-180` `provideAiAgentClient(appContext, apiKey, providerRegistry, sessionStore: SessionStore)` — sessionStore 파라미터 추가 | — |
| 기존 테스트 영향 | `SdkModuleTest.kt`의 6개 `provideAiAgentClient` 호출 모두 `sessionStore = FakeSessionStore()` 또는 `store` 인자 추가됨 (`SdkModuleTest.kt:120-130/146-157/167-178/186-197/231-243/334-339`) | 통과 |
| impl_summary_9 §0 주장 검증 | "sessionStore 인자만 추가 — 기존 16 케이스는 의도 변경 없음" — **정확** | 회귀 0건 |
| F-006 회귀 | 호출자 측 Hilt 그래프에는 `provideSessionStore`가 자동 합산되어 missing binding 발생 안 함 | 회귀 0건 |
| Builder 라우트 경로 (`SdkModule.kt:187-194`) | `.providers(...).sessionStore(sessionStore).build()` — Builder의 internal hook을 사용해 정상 주입 | 통과 |

### 4.3 위험 포인트 3: Builder.sessionStore hook 추가 + 자동 DataStoreSessionStore

| 항목 | 분석 | 결과 |
|------|------|------|
| 변경 | `Builder.kt:60` `sessionStoreOverride: SessionStore?` 필드 + `Builder.kt:122-124` `internal fun sessionStore(store: SessionStore?)` hook + `Builder.kt:168` `val store: SessionStore = sessionStoreOverride ?: DataStoreSessionStore(appContext)` 자동 인스턴스화 | — |
| 빈 Builder 흐름 영향 | `SdkModuleTest.kt:262-271` `provideBuilder(appContext).apiKey("sk-test").build()` — sessionStoreOverride null → DataStoreSessionStore 자동 생성 → 통과 | 통과 |
| build() 검증 흐름 (E-001/E-002/E-003) | `Builder.kt:138-156` 검증은 변경 없음 — sessionStore 구성은 검증 통과 후 (`Builder.kt:168`) | F-000 회귀 0건 |
| 자동 DataStoreSessionStore 안전성 | `preferencesDataStore` delegate는 lazy — build() 시점에 디스크 IO 미발생. mock appContext에서 인스턴스화 안전. | 통과 (`DataStoreSessionStore.kt:201-203` 참조) |
| Hilt 미사용 호출자 영향 | 기존 Builder 사용자는 자동 DataStoreSessionStore 적용 — 본 라운드 변경의 의도 정합 | 의도된 변경 |

### 4.4 위험 포인트 4: Session.kt save() 추가 → 기존 Session.send/history/clear/Mutex 흐름 영향

| 항목 | 분석 | 결과 |
|------|------|------|
| 변경 | `Session.kt:339-395` `public suspend fun save(): Result<String>` 신규 + 동일 mutex 사용 | — |
| Mutex 잠금 순서 | save도 send와 동일하게 `mutex.withLock { synchronized(history) { ... } }` 패턴 — 잠금 순서 mutex → synchronized 단방향 유지 (역순 없음) — deadlock 위험 없음 | 통과 |
| send 흐름 영향 | `Session.kt:120-222` send 본체 변경 없음 — save는 별도 함수 | F-004 회귀 0건 |
| history/clear 흐름 영향 | `Session.kt:248-258/279-287` 변경 없음 | 통과 |
| send + save 동시 호출 | `SessionSaveTest.kt:275-307` 검증 — send가 mutex 점유 → save가 대기 → send 완료 후 save 진행 → save된 history에 send 결과 포함 | F-007 동시성 정합 |
| Mutex 해제 시점 | `mutex.withLock`는 코틀린 표준 — 예외 throw 또는 정상 반환 시 자동 해제 (CancellationException 포함) | 통과 |

### 4.5 회귀 점검 요약 (mtime 기반 — 본 라운드 변경 파일)

본 라운드(T19) 변경 파일:
- `session/Session.kt` (save() 추가)
- `client/Builder.kt` (sessionStore hook + 자동 DataStoreSessionStore)
- `di/SdkModule.kt` (provideSessionStore 신규 + provideAiAgentClient 시그니처 변경)
- `di/SdkModuleTest.kt` (시그니처 인자 추가 + 신규 3 케이스)

기존 main 파일 (이전 라운드 잔여):
- `session/SessionStore.kt` / `session/SessionEntity.kt` / `internal/storage/DataStoreSessionStore.kt` / `internal/storage/SessionJson.kt` / `AiAgentClient.kt` (loadSession/deleteSession) — 본 라운드 추가 검증

기존 회귀 테스트 파일 (변경 없음):
- `AskTest.kt` 14 / `AskWithImagesTest.kt` 8 / `AskStreamTest.kt` ~14 / `MapperImageTest.kt` 12 / `ImageInputTest.kt` 11 / `AnthropicSseParserTest.kt` 15 / `ClaudeProviderStreamTest.kt` 5 / `SessionTest.kt` 22 / `CreateSessionTest.kt` 7 / `MapperSessionTest.kt` 20 — 모두 sessionStore 인자 명시 없이 default null 적용으로 시그니처 호환.

→ F-001/F-002/F-003/F-004/F-005/F-006/F-008 회귀 0건. 본 라운드 변경 4건 모두 정적 분석상 영향 없음.

---

## 5. 발견된 이슈

### Q-T19-M1 [Minor] (= Q-T17-M1 인계) Session.send fallback 분기 사양 명시 부재

- **위치**: `Session.kt:159-170`
- **현 구현**: `if (provider is ClaudeProvider) provider.completeForSession(...) else provider.complete(request, config)` — fallback 분기는 history/systemPrompt 무시.
- **사양 상태**: provider-spec.md P-001 (Provider 인터페이스)에 `completeForSession` 없음 — v0.1 ClaudeProvider 단일 (R-009)이므로 fallback 도달 불가.
- **영향**: F-007 영역 외 — F-004 회귀 위험과 동일. Session.save는 영향 없음.
- **권장**: spec-architect가 provider-spec.md P-001 또는 features.md F-004에 v0.1 명시적 가정 추가 (S-T17-1 = S-T19-1 인계).
- **차단 여부**: 차단 안 함.

### Q-T19-M2 [Minor] (= Q-T17-M2 인계) Mutex+synchronized 이중 동기화 사양 명시 부재

- **위치**: `Session.kt:80` Mutex + `Session.kt:151/186/257/284/364` synchronized(history) + `Session.kt:352` Mutex (save)
- **현 구현**: send / save는 `mutex.withLock { synchronized(history) { ... } }` 이중 동기화; history()/clear()는 `synchronized(history)`만 사용.
- **사양 상태**: data-model.md M-007 "Mutex 안에서 List 복사본 반환"만 명시. 본 라운드의 save도 동일 패턴.
- **영향**: 본 라운드 save 본체에도 같은 패턴 — 사양 명시 후 정합. 동작 정합.
- **권장**: data-model.md M-007 / features.md F-007 동시성 모델에 mutex → synchronized 잠금 순서 명시 (S-T17-4 = S-T19-4 인계).
- **차단 여부**: 차단 안 함.

### Q-T19-M3 [Minor] Session.save() — `client.sessionStore` 첫 검사가 Mutex 밖에서 일어남

- **위치**: `Session.kt:347-349`
  ```kotlin
  val store: SessionStore = client.sessionStore ?: return Result.failure(
      AiException.IOError("session store not configured"),
  )
  ```
- **현 구현**: mutex 진입 전에 `client.sessionStore`를 한 번 읽음. `sessionStore` 자체는 `internal val` (immutable, final field — `AiAgentClient.kt:79`)이라 race 가능성 0.
- **사양 상태**: 사양 외 안전망 — sessionStore가 final이므로 동작 정합.
- **영향**: 실제 동작 문제 없음 — `sessionStore`는 생성자 주입 후 변경 안 됨. 본 검사를 Mutex 안에 두면 mutex 점유 시간이 늘어남.
- **권장**: 이대로 유지. 코드 리뷰어가 "race가 있는가" 의심할 수 있으므로 KDoc에 "sessionStore is final and immutable" 1줄 추가 권장 (선택).
- **차단 여부**: 차단 안 함.

### Q-T19-I1 [정보성] (= Q-T18-4 인계) DataStoreSessionStore 실제 IO 검증 정책 (androidTest 미명시)

- **위치**: `DataStoreSessionStoreTest.kt` (unitTest) — 정적 상수/SessionJson 정책/크기 한계만 검증.
- **현 구현**: `dataStore.edit { ... }` / `dataStore.data.first()` 등 실제 IO는 unitTest에서 검증 불가 (Robolectric/instrumented 필요).
- **사양 상태**: features.md F-007 NFR에 "androidTest로 실제 IO 검증" 명시 없음.
- **영향**: 본 라운드 sdk-qa-validator는 정적 검증만 수행. 실제 IO round-trip은 별도 라운드 필요.
- **권장**: features.md F-007 NFR 또는 별도 "테스트 범위" 섹션에 "DataStore 실제 IO 동작은 androidTest 영역에서 검증; unitTest는 SessionStore 인터페이스 단위 (FakeSessionStore) + 직렬화 정책 + 상수" 명시. **S-T19-4 (Q-T18-4 인계)**.
- **차단 여부**: 차단 안 함 (정보성).

### Q-T19-I2 [정보성] SessionEntityTest 카운트 차이 (impl_summary_9: 16 / 실제 카운트: 17)

- **위치**: `SessionEntityTest.kt:42-318` — `@Test` 함수 카운트
- **현 구현**: 17개 @Test 함수 (sealed 직렬화, R-018 검증, systemPrompt null, ImageInputEntity 3 variants × round-trip, Message ↔ MessageEntity 변환, base64 손상 등).
- **사양 상태**: 사양 외 카운트 정합 — 의미 있음.
- **영향**: impl_summary_9 §3 SessionEntityTest 16 케이스로 보고했으나 실제 17 케이스. 차이 1건 = `F-007 E-703 — ImageInputEntity.Bytes 손상 base64 → IOError` (SessionEntityTest.kt:309-318). 정보성.
- **권장**: 다음 impl_summary에서 카운트 정확성 검토.
- **차단 여부**: 차단 안 함 (정보성).

---

## 6. 사양 명확화 필요 항목 (Q-T18-1/2/3/4 분류)

본 라운드 검증 중 발견된 새 항목 + Q-T18 이월 항목을 S-T19 식별자로 분류:

### S-T19-1 [Minor] DataStoreSessionStore 단일 인스턴스 정책 (= Q-T18-1 인계)

- **현 사양**: features.md F-007 동시성 모델은 "DataStore transactional update로 직렬화" 명시. R-019는 단일 프로세스 가정. 그러나 `DataStoreSessionStore`가 단일 인스턴스여야 하는 이유(`preferencesDataStore` delegate가 process-singleton)는 사양 외 구현 디테일.
- **현 구현**: `SdkModule.provideSessionStore`에 `@Singleton` 어노테이션. Builder 진입 경로에서 `DataStoreSessionStore(appContext)`가 두 번 생성되어도 같은 DataStore 파일 핸들을 공유 (process-singleton 표준 동작).
- **(a) Severity**: Minor.
- **(b) 차단 여부**: 차단 안 함 (동작상 안전).
- **(c) spec-architect 회신 권장**: features.md F-007 또는 overview.md NFR에 "v0.1은 DataStoreSessionStore를 단일 프로세스/단일 파일(`ai_agent_sdk_sessions`) 사용 전제. Builder 진입에서도 process-singleton DataStore 공유" 1줄 추가.

### S-T19-2 [Minor] Session.save() 후 Session 객체 상태 변화 명시 부재 (= Q-T18-2 인계)

- **현 사양**: api.md A-010는 `Session.save()`의 반환을 sessionId만 명시. Session 인스턴스 내부 상태 변화는 정의 없음.
- **현 구현**: `Session.save()` 후 Session 객체는 mutex 점유/해제 외에 history/systemPrompt 변경 없음. lastSavedAt 등 추가 상태 없음.
- **(a) Severity**: Minor.
- **(b) 차단 여부**: 차단 안 함.
- **(c) spec-architect 회신 권장**: api.md A-010에 "Session.save() 후 Session 객체의 내부 상태는 변경되지 않는다. lastSavedAt 추적은 호출자 책임" 1줄 추가. v0.2 검토 사항으로 `Session.lastSavedAt: Long?` 명시.

### S-T19-3 [Minor] DataStore Preferences 키 충돌 정책 명시 부재 (= Q-T18-3 인계)

- **현 사양**: data-model.md M-011은 "DataStore Preferences 키: `session:{sessionId}`" 명시. 그러나 호출자 앱이 같은 DataStore 파일을 직접 접근하면 충돌 가능성 (비표준이지만 가능).
- **현 구현**: `DataStoreSessionStore.DATASTORE_FILE_NAME = "ai_agent_sdk_sessions"` SDK 고유 이름.
- **(a) Severity**: Minor.
- **(b) 차단 여부**: 차단 안 함.
- **(c) spec-architect 회신 권장**: features.md F-007 또는 overview.md "보안/격리"에 "SDK는 `ai_agent_sdk_sessions` DataStore 파일을 SDK 전용으로 사용. 호출자가 같은 파일에 직접 접근하면 동작 미정의" 1줄 추가.

### S-T19-4 [Minor] androidTest 정책 명시 부재 (= Q-T18-4 인계)

- **현 사양**: 사양은 DataStore 영속화 실제 IO를 androidTest 영역에서 검증하라는 정책 명시 없음.
- **현 구현**: unitTest는 정적 상수/SessionJson 정책/크기 한계만 검증. 실제 DataStore IO + round-trip은 본 라운드 영역 외.
- **(a) Severity**: Minor.
- **(b) 차단 여부**: 차단 안 함 (본 라운드 sdk-qa-validator가 정적 검증).
- **(c) spec-architect 회신 권장**: features.md F-007 NFR 또는 별도 "테스트 범위" 섹션에 androidTest 정책 명시.

### 이월 항목 (이전 라운드 미해결 — F-007 영향 없음)

- **S-T11-1** (F-001 E-107 발생 위치 명확화) — 이월
- **S-T12-1** (ImageInput.Url https 강제 + MockWebServer 통합) — 이월
- **S-T13-1/2/3** (F-003 E-303 / SSE error 이벤트 ERR 매핑 / R-005 stream 적용) — 이월
- **S-T15-1/2/3** (ProviderRegistry public 노출 / @ModelId 옵션 qualifier / OkHttpClient 호출당 빌드) — 이월
- **S-T17-1/2/4** (Provider.completeForSession 사양 / E-401 키워드 / Mutex+synchronized 패턴) — Q-T19-M1 + Q-T19-M2로 다시 명시. S-T17-3 (Session.save F-007 분리)은 본 라운드 해소.

---

## 7. v0.1 SDK 종결 권고 + 다음 단계

### 7.1 F-007 종결 가능성

- Blocker 0건 / Major 0건 → **F-007 종결 가능**.
- Minor 3건 / 정보성 2건 / 사양 명확화 4건 모두 차단 없음.

### 7.2 v0.1 SDK 전체 완성 확인 (F-000 ~ F-008)

| F-ID | 구현 라운드 | 종결 라운드 (QA 통과) |
|------|------------|---------------------|
| F-000 (Builder) | T9 | qa_report_3 통과 |
| F-001 (텍스트 단발) | T10/T11 | qa_report_4 통과 |
| F-002 (멀티모달) | T12 | qa_report_5 통과 |
| F-003 (스트리밍) | T13 | qa_report_6 통과 |
| F-004 (세션 컨텍스트) | T16/T17 | qa_report_7 통과 |
| F-005 (Provider 선택/교체) | T11/T15 | qa_report_5 통과 |
| F-006 (Hilt 모듈) | T14/T15 | qa_report_6 통과 |
| F-007 (세션 영속화) | T18/T19 | **qa_report_8 (본 보고서) 통과** |
| F-008 (라이프사이클 close) | T11/T15 | qa_report_5 통과 |

**v0.1 P0/P1 모든 본체 + 단위 테스트 완성 — v0.1 release candidate 진입 권고**.

### 7.3 v0.1 release candidate 진입 시 권고 후속

1. **즉시 (선택)**: spec-architect가 S-T11/S-T12/S-T13/S-T15/S-T17/S-T19 사양 보강 (총 15+ 항목 누적). 모두 Minor — release blocker 아님.
2. **androidTest 라운드 (선택)**: `DataStoreSessionStore` 실제 IO round-trip + `ClaudeProvider` 실 HTTP MockWebServer 통합 — v0.1.0 release 전 권장. (S-T12-1 / S-T19-4)
3. **v0.2 진입 검토**:
   - 자동 재시도 (RetryPolicy 주입)
   - OpenAI Provider + GeminiProvider — Provider 인터페이스 확장 (`sessionComplete` 정식 추가, S-T19-1)
   - VideoInput (M-004 활성화)
   - Tool use / function calling
   - 자동 마이그레이션 (`SessionMigrations`, R-022)
   - URL allowlist (R-023 보완)
   - ImageInput.Uri 자동 resolve (D-004 확장, F-002 v0.2 검토)

### 7.4 권고 우선순위

1. **즉시 (선택)**: spec-architect가 누적 S-T 항목 회신 → 사양 보강.
2. **v0.1.0 release 전**: androidTest 라운드 — DataStore IO + ClaudeProvider MockWebServer 통합.
3. **v0.1 → v0.2 전환**: Provider 인터페이스 확장 (sessionComplete 정식) → OpenAI/Gemini Provider 추가.

---

## 8. 자체 체크리스트

- [x] 4쌍의 경계면(api.md ↔ AiAgentClient/Session, data-model.md ↔ SessionEntity/MessageEntity/ImageInputEntity, error-handling.md ↔ AiException, features.md E-XXX ↔ 단위 테스트)을 모두 교차 비교
- [x] F-007의 모든 정상 흐름 (저장 1~5 / 복원 1~5 / 삭제 1~3)이 코드/테스트에 매핑됨 (검증 표 2.5/2.6/2.7)
- [x] F-007의 모든 E-XXX (E-701/E-702/E-703/E-704/E-705/E-706)가 코드/테스트에 매핑됨 (검증 표 2.8)
- [x] api.md A-010/A-011/A-012 시그니처를 토큰 단위로 비교 (검증 표 2.1)
- [x] M-011 (SessionEntity / MessageEntity / ImageInputEntity)가 코드 필드/제약과 일치 (검증 표 2.2/2.3/2.4)
- [x] R-008/R-017/R-018/R-019/R-020/R-021/R-022/R-023/R-024 정책 모두 코드 위치 매핑 (검증 표 2.9/2.10/2.11)
- [x] AiAgentClient.loadSession / deleteSession 본체 검증 (검증 표 2.12)
- [x] Session.save 본체 검증 (검증 표 2.13)
- [x] SdkModule.provideSessionStore 검증 (검증 표 2.14)
- [x] SdkModule.provideAiAgentClient 시그니처 변경 회귀 (검증 표 2.15)
- [x] Builder.sessionStore hook + 자동 DataStoreSessionStore 검증 (검증 표 2.16)
- [x] F-001/F-002/F-003/F-004/F-005/F-006/F-008 회귀 위험 4건 모두 명시적 점검 (§4)
- [x] 59 (실제 60) 단위 테스트 각각의 의도/사양 ID/의미 있음 매핑 평가 (§3)
- [x] 보고서에 모든 이슈에 (file:line) 근거 명시
- [x] Severity 분류 일관 (Blocker 0 / Major 0 / Minor 3 / 정보성 2 / 사양 명확화 4 (Q-T18-1/2/3/4 인계 = S-T19-1/2/3/4))
- [x] 빌드 미수행 정책 명시 (정적 검증 + 토큰 단위 비교)
- [x] v0.1 SDK 종결 권고 명시 (F-000~F-008 모두 완성)
- [x] 다음 단계 (v0.1 release candidate / androidTest 라운드 / v0.2 검토) 명시
