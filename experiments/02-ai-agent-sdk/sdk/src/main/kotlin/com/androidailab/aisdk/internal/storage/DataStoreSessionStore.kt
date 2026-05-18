package com.androidailab.aisdk.internal.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.androidailab.aisdk.model.AiException
import com.androidailab.aisdk.session.SessionEntity
import com.androidailab.aisdk.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException

/**
 * [SessionStore]의 DataStore Preferences 기반 기본 구현 (F-007).
 *
 * 사양 참조:
 * - overview.md "기술 스택": androidx.datastore (Preferences DataStore, D-002)
 * - features.md F-007 정상 흐름 (DataStore Preferences에 `session:{sessionId}` 키로 JSON 저장)
 * - features.md F-007 NFR — 디스크 IO는 `Dispatchers.IO` (호출 스레드 블로킹 금지)
 * - features.md F-007 E-701 (저장 실패) / E-703 (손상/schemaVersion) / E-704 (1MB 초과) / E-706 (취소)
 * - features.md F-007 R-018 (schemaVersion=1만 인정)
 * - features.md F-007 R-019 (단일 프로세스 권장)
 * - data-model.md M-011 (직렬화 정책, DataStore 키 `"session:{sessionId}"`)
 *
 * 정책:
 * - 모든 IO는 [Dispatchers.IO]에서 수행 — `withContext(Dispatchers.IO) { ... }` 패턴.
 * - DataStore의 `edit` / `data.first()`는 자체적으로 취소 cooperative.
 * - 본 구현은 [SessionJson]을 통해 [SessionEntity]를 JSON으로 직렬화/역직렬화한다.
 * - 1MB 초과 시 [AiException.InvalidInput]("session too large to persist") (E-704)로 거부 — 저장 시도 안 함.
 * - schemaVersion != 1 이면 [AiException.IOError]("session schema unsupported: v={loaded}") (E-703).
 *
 * DataStore 파일 정책:
 * - 파일명: `"ai_agent_sdk_sessions"` (고유 — 호출자 앱과 충돌 가능성 낮음).
 * - 단일 프로세스 사용을 가정 (R-019, overview.md NFR).
 *
 * @param context Application context (DataStore는 [Context]가 필요).
 */
internal class DataStoreSessionStore(
    private val context: Context,
) : SessionStore {

    /**
     * DataStore 핸들 (DataStore Preferences의 표준 패턴).
     *
     * 본 SDK는 [DATASTORE_FILE_NAME] 고정 파일을 사용 — 호출자가 별도 파일을 지정하려면
     * SessionStore 인터페이스를 직접 구현해 SdkModule 바인딩을 교체해야 한다.
     *
     * `preferencesDataStore`는 process-singleton — 같은 파일 이름에 두 번째 호출하면 IllegalStateException.
     * 본 클래스는 SDK가 `@Singleton`으로 단일 인스턴스 보장하므로 안전 (F-007 R-019).
     */
    private val dataStore: DataStore<Preferences>
        get() = context.applicationContext.sessionsDataStore

    override suspend fun save(entity: SessionEntity): Unit = withContext(Dispatchers.IO) {
        // R-018: 저장 시 schemaVersion은 항상 1로 강제 (entity가 다른 값을 가져도 v0.1 SDK는 1로 보장).
        val safeEntity = if (entity.schemaVersion == SessionEntity.SCHEMA_VERSION_V1) {
            entity
        } else {
            entity.copy(schemaVersion = SessionEntity.SCHEMA_VERSION_V1)
        }

        // 1. JSON 직렬화 (E-701 직렬화 실패 대비 — Role.SYSTEM 등은 toEntity가 이미 차단)
        val json: String = try {
            SessionJson.instance.encodeToString(SessionEntity.serializer(), safeEntity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            throw AiException.IOError("save failed: serialization error", cause = e)
        }

        // 2. E-704: 1MB 초과 검증 (UTF-8 byte 길이 기준 — JSON ASCII가 다수지만 한글/이모지 대비)
        val byteSize = json.toByteArray(Charsets.UTF_8).size
        if (byteSize > MAX_SESSION_BYTES) {
            throw AiException.InvalidInput(
                "session too large to persist: $byteSize > $MAX_SESSION_BYTES bytes",
            )
        }

        // 3. DataStore에 저장 (transactional update — atomic write)
        val key = stringPreferencesKey(keyFor(safeEntity.sessionId))
        try {
            dataStore.edit { prefs ->
                prefs[key] = json
            }
        } catch (e: CancellationException) {
            // E-706: 취소는 그대로 전파 (부분 쓰기는 DataStore atomic write가 방지)
            throw e
        } catch (e: IOException) {
            // E-701: 디스크 가득/IO 오류
            throw AiException.IOError("save failed: ${e.message ?: "io error"}", cause = e)
        } catch (e: AiException) {
            // 안전망: AiException은 그대로 전파
            throw e
        } catch (e: Throwable) {
            // 그 외 DataStore 내부 오류 → IOError로 변환
            throw AiException.IOError("save failed: ${e.message ?: "unknown"}", cause = e)
        }
    }

    override suspend fun load(sessionId: String): SessionEntity? = withContext(Dispatchers.IO) {
        val key = stringPreferencesKey(keyFor(sessionId))

        // 1. DataStore에서 JSON 조회
        val json: String? = try {
            dataStore.data.first()[key]
        } catch (e: CancellationException) {
            // E-706: 취소 그대로 전파
            throw e
        } catch (e: IOException) {
            throw AiException.IOError("load failed: ${e.message ?: "io error"}", cause = e)
        } catch (e: AiException) {
            throw e
        } catch (e: Throwable) {
            throw AiException.IOError("load failed: ${e.message ?: "unknown"}", cause = e)
        }

        // 2. 미존재 → null 반환 (호출자가 E-702로 변환)
        if (json == null) return@withContext null

        // 3. JSON 역직렬화 (E-703 손상 데이터)
        val entity: SessionEntity = try {
            SessionJson.instance.decodeFromString(SessionEntity.serializer(), json)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            throw AiException.IOError("session data corrupted", cause = e)
        } catch (e: IllegalArgumentException) {
            // kotlinx-serialization은 일부 구조 오류를 IllegalArgumentException으로 던지기도 함
            throw AiException.IOError("session data corrupted", cause = e)
        }

        // 4. R-018: schemaVersion 검증 — 1이 아니면 즉시 E-703
        if (entity.schemaVersion != SessionEntity.SCHEMA_VERSION_V1) {
            throw AiException.IOError(
                "session schema unsupported: v=${entity.schemaVersion}",
            )
        }

        entity
    }

    override suspend fun delete(sessionId: String): Unit = withContext(Dispatchers.IO) {
        val key = stringPreferencesKey(keyFor(sessionId))
        try {
            dataStore.edit { prefs ->
                // idempotent — 없는 키 remove도 안전 (DataStore가 no-op로 처리)
                prefs.remove(key)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw AiException.IOError("delete failed: ${e.message ?: "io error"}", cause = e)
        } catch (e: AiException) {
            throw e
        } catch (e: Throwable) {
            throw AiException.IOError("delete failed: ${e.message ?: "unknown"}", cause = e)
        }
    }

    internal companion object {
        /**
         * DataStore Preferences 파일명 (앱 내부 저장소 기준).
         *
         * 호출자 앱과의 키 충돌을 줄이기 위해 SDK 고유 접두사 사용.
         */
        const val DATASTORE_FILE_NAME: String = "ai_agent_sdk_sessions"

        /**
         * DataStore Preferences 키 접두사 (M-011 "DataStore Preferences 키: `session:{sessionId}`").
         */
        const val KEY_PREFIX: String = "session:"

        /**
         * 단일 Session 직렬화 결과 1MB 한계 (E-704, F-007 데이터 크기 정책).
         *
         * UTF-8 byte 길이로 비교 (JSON은 ASCII 다수이나 한글/이모지 시 영향 — 안전한 byte 기준).
         */
        const val MAX_SESSION_BYTES: Int = 1024 * 1024

        /**
         * sessionId → DataStore key 변환 헬퍼.
         *
         * M-011 직렬화 정책: "DataStore Preferences 키: `session:{sessionId}`".
         */
        internal fun keyFor(sessionId: String): String = "$KEY_PREFIX$sessionId"
    }
}

/**
 * DataStore Preferences 인스턴스 — `preferencesDataStore` delegate.
 *
 * 같은 파일 이름으로 두 번 등록하면 IllegalStateException이므로 top-level extension property
 * (process-singleton) 패턴을 사용한다.
 */
private val Context.sessionsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = DataStoreSessionStore.DATASTORE_FILE_NAME,
)
