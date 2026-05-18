package com.androidailab.aisdk.di

import javax.inject.Qualifier
import kotlin.annotation.AnnotationRetention.BINARY

/**
 * Hilt qualifier 정의 (F-006).
 *
 * 사양 참조:
 * - features.md F-006 정상 흐름 1~3 (호출자가 BuildConfig 등에서 API 키를 SDK 모듈에 공급)
 * - features.md F-006 사용 예 (R-012) — 호출자가 BuildConfig.AI_API_KEY로 키 주입
 * - error-handling.md E-602 (BuildConfig API 키 미설정 → ERR-004, F-000 E-001과 동일 매핑)
 * - overview.md "기술 스택" Hilt + D-003 (API 키는 메모리에서만 보관)
 *
 * 본 SDK는 호출자 앱이 [ApiKey]로 어노테이트된 `String` 바인딩을 자기 앱 모듈에서
 * 반드시 제공하도록 요구한다. 미제공 시 Hilt 컴파일 시 missing binding 에러로 거부된다
 * (E-601 컴파일 타임 에러 정합).
 *
 * 모델 식별자/타임아웃은 [SdkModule]이 기본값을 제공한다 (S-001 / S-002). 호출자가
 * 다른 값을 사용하고 싶다면 Builder 라우트(F-000)를 사용해 직접 인스턴스화하거나,
 * 자기 앱 모듈에서 `@Provides AiAgentClient`로 SDK의 기본 [SdkModule.provideAiAgentClient]를
 * 대체할 수 있다 (F-006 NFR — Hilt는 옵션, Builder 라우트와 양립).
 *
 * v0.2 검토:
 * - Provider별 분리된 ApiKey (예: `@ApiKey(ProviderId.CLAUDE)`).
 * - 현 v0.1은 단일 Provider 모델이라 단일 [ApiKey]만 노출 (provider-spec.md "v0.1 시점 enum CLAUDE만").
 *
 * @see SdkModule
 */

/**
 * 호출자가 제공해야 하는 활성 Provider API 키 식별 qualifier (D-003).
 *
 * 호출자 앱 측 사용 예 (R-012 보강):
 * ```kotlin
 * @Module
 * @InstallIn(SingletonComponent::class)
 * object AppAiSdkBindings {
 *     @Provides
 *     @ApiKey
 *     fun provideAiApiKey(): String = BuildConfig.AI_API_KEY
 * }
 * ```
 *
 * `@Qualifier`는 동일한 타입(예: String)으로 구분이 안 되는 바인딩을 식별하기 위한 표준
 * (`javax.inject.Qualifier`) 어노테이션이다.
 */
@Qualifier
@Retention(BINARY)
public annotation class ApiKey
