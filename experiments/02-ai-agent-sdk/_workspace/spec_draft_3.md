# Spec Draft 3 — Round 3 반영 보고서

작성자: spec-architect
작성 일시: 2026-05-07
대상 검토 보고서: `_workspace/spec_review_2.md`
편집한 사양 파일:
- `spec/overview.md`
- `spec/features.md`
- `spec/api.md`
- `spec/data-model.md`
- `spec/error-handling.md`
- `spec/provider-spec.md`

본 라운드는 라운드 2 검토에서 식별된 Major 4건(R-017~R-020) + Minor 4건(R-021~R-024) **8건 모두 반영**하여 합의 종결을 목표로 한다.

라운드 1 지적(R-001~R-016)은 이미 통과 상태이므로 본 라운드에서 일절 손대지 않았다 (regression 방지).

---

## 1. 본 라운드에서 결정한 정책값 (라운드 3 합리적 기본값)

라운드 2 검토에서 reviewer가 "spec-architect 합리적 기본값으로 결정 가능"으로 판정한 사항을 본 라운드에서 모두 명시적 정책값으로 확정.

| # | 항목 | 라운드 3 결정값 | 근거 / 트레이드오프 |
|---|------|----------------|------------------|
| P-1 | sessionId 부여 정책 | **SDK 자동 UUID 부여만 지원**. `createSession()`/`save()` 모두 호출자 sessionId 인자 없음. `loadSession(sessionId)`은 기존 ID 입력만 받음 | v0.1 단순성 우선. 호출자 명시 부여(`createSession(sessionId = ...)` 등)는 v0.2 검토 (Out of Scope 추가). reviewer 권장 옵션 (a) 채택 |
| P-2 | F-007 정상 흐름 sessionId 표현 | "Session 생성 시 자동 부여된 UUID(M-007.sessionId)"로 통일 | A-010 시그니처(`save(): Result<String>`, 인자 없음)와 정확히 일치. R-017 모순 해소 |
| P-3 | schemaVersion v0.1 정책 | **v0.1은 schemaVersion=1만 인정**. 그 외 즉시 E-703(`AiException.IOError("session schema unsupported: v={loaded}")`)로 거부. 자동 마이그레이션은 v0.1 Out of Scope, v0.2부터 `internal object SessionMigrations`에 `fromN_toNplus1` 함수 등록 패턴으로 추가 예정 (사양에 미리 부기) | v0.1 단순성. v0.1은 schemaVersion=1만 디스크에 쓰므로 E-703은 외부 변조/미래 버전 데이터를 v0.1로 로드 시에만 발생. reviewer 권장 옵션 (a) 채택 |
| P-4 | 다중 Session 인스턴스 동시 접근 정책 | **Last-write-wins**. 같은 sessionId의 다중 Session 인스턴스 각자 send/save 가능, save는 DataStore transactional update로 직렬화되어 마지막 save가 덮어씀. SDK는 충돌 자동 검출 안 함. 호출자가 단일 인스턴스 유지 책임 | 단순하고 예측 가능. Mutex 기반 충돌 검출은 호출자 도메인 정책에 비해 과도. reviewer 권장 옵션 (a) 채택 |
| P-5 | 다중 프로세스 영속화 동시 접근 | **v0.1 Out of Scope**. 호출자 앱은 단일 프로세스에서만 SDK 사용 가정. WorkManager 등 별도 프로세스에서 동일 DataStore 접근은 미지원. v0.2 검토 | DataStore Preferences가 단일 프로세스 권장이므로 사양상 일관됨. overview.md NFR + Out of Scope 모두에 명시 |
| P-6 | close ↔ 진행 중 호출 시맨틱 | **두 케이스 분리 명시**. 케이스 A(close 시점에 in-flight): `CancellationException` throw (Result.failure로 변환 안 함, 표준 코루틴 시맨틱). 케이스 B(close 후 새 호출): suspend는 `Result.failure(Configuration("client closed"))`, Flow는 `AiStreamEvent.Error(Configuration)` emit 후 종료, 동기 함수는 throw | 코루틴 표준 취소 시맨틱을 그대로 따르면서, 새 호출은 명시적 Result.failure로 호출자가 일관 처리 가능. reviewer가 제시한 두 가지 옵션 중 "케이스 분리"를 명확히 정리한 절충안 |
| P-7 | 영속화 시 이미지 처리 정책 | **`ImageInput.Bytes`는 base64 보관(M-011) + 1MB 한계 초과 시 E-704**. SDK는 자동 이미지 제거/압축 없음 (D-004 연장). 호출자가 save 직전 이미지 첨부 메시지를 history에서 제거하거나 텍스트 요약으로 대체 권장. `ImageInput.Url`은 URL 문자열만 보관. 외부 BLOB 저장소 옵션은 v0.2 검토 | D-004 정책과 일관 (이미지 가공은 호출자 책임). 1MB 한계는 DataStore Preferences 특성상 불가피, "거부만 한다" 정책으로 명확 |
| P-8 | `ImageInput.Url` 영속화 SSRF 방어 | **호출자 책임**. SDK는 URL 형식 검증(`https://` 스킴 등)만 수행. 내부망/loopback/메타데이터 엔드포인트 판별/차단은 호출자가 처리. v0.2에서 SDK 차원 URL allowlist 옵션 검토 | SDK가 도메인 allowlist 정책을 일방적으로 정하면 호출자 도메인과 충돌. v0.1은 검증 책임을 호출자에 명확히 위임 |

본 라운드에서 새로 도입한 **미해결 결정(D-XXX)**: **0건** (overview.md "결정 사항" 표 그대로, "미해결 결정: 0건" 명시 유지).

---

## 2. R-017 ~ R-024 항목별 반영 결과

### Major (R-017 ~ R-020)

| R-ID | Severity | 반영 결과 | 변경 파일 / 위치 | 사용한 정책값 |
|------|----------|-----------|----------------|-------------|
| R-017 | Major | F-007 정상 흐름 5단계의 "자동 UUID 또는 save() 인자로 전달된 식별자" 표현 제거. "Session 생성 시 자동 부여된 UUID(M-007.sessionId)"로 통일. A-010 동작 섹션에도 인자 없음 명시. A-010 시그니처(`save(): Result<String>`)는 변경 없음 | features.md F-007 저장 흐름 5단계, api.md A-010 동작 | P-1, P-2 |
| R-018 | Major | M-011 "schemaVersion 정책" 섹션 신규 추가. v0.1은 schemaVersion=1만 인정, 그 외 E-703으로 거부. v0.2 마이그레이션 등록 패턴(SessionMigrations)을 미리 부기. F-007 NFR에도 동일 정책 명시. E-703 설명에 "schemaVersion 미지원" 케이스 추가. error-handling.md ERR-007 발생 조건에도 추가. overview.md Out of Scope에 "자동 마이그레이션 v0.1 미지원" 추가 | data-model.md M-011, features.md F-007 NFR + E-703, error-handling.md, overview.md | P-3 |
| R-019 | Major | F-007에 "다중 인스턴스 정책" 섹션 신규 추가 (last-write-wins, SDK 충돌 검출 없음, 호출자 책임 명시). M-007에도 동일 섹션 추가. overview.md NFR에 "단일 프로세스 사용 가정" 추가. overview.md Out of Scope에 "다중 프로세스 영속화 동시 접근" 추가. A-011 동작에도 다중 인스턴스 정책 부기 | features.md F-007 동시성 모델 직후, data-model.md M-007, overview.md NFR + Out of Scope, api.md A-011 | P-4, P-5 |
| R-020 | Major | F-008에 "close ↔ 진행 중/이후 호출 시맨틱" 섹션 신규 추가 (케이스 A/B 분리, 각 API별 동작 명시). F-008 정상 흐름 2/5단계에서 두 케이스 시맨틱과 일치하도록 문구 수정. A-009 동작에 케이스 표 신규 추가. A-002/A-003/A-006/A-010/A-011/A-012 동작 섹션에 "close 시맨틱: A-009 표 참조" 일괄 부기. createSession(A-004)도 동기 함수 throw 부기 | features.md F-008, api.md A-002~A-012 | P-6 |

### Minor (R-021 ~ R-024)

| R-ID | Severity | 반영 결과 | 변경 파일 / 위치 | 사용한 정책값 |
|------|----------|-----------|----------------|-------------|
| R-021 | Minor | F-007 데이터 크기 정책 직후에 "이미지 영속화 운영 가이드" 섹션 신규 추가 (단일 5MB 이미지가 base64 후 6.7MB로 1MB 한계 초과 사실 명시 + 호출자 권장 처리 3가지). M-003 영속화 규칙도 보강. M-011에도 "영속화 시 이미지 처리 가이드" 섹션 추가 | features.md F-007, data-model.md M-003 + M-011 | P-7 |
| R-022 | Minor | M-011 "schemaVersion 정책" 섹션에 마이그레이션 등록 패턴(`internal object SessionMigrations`, `fromN_toNplus1` 함수) 미리 부기. v0.2부터 실제 추가 명시. R-018과 단일 섹션으로 통합 | data-model.md M-011 | P-3 |
| R-023 | Minor | provider-spec.md 보안 섹션에 "ImageInput.Url SSRF 방어 정책" 신규 추가 (SDK는 URL 형식 검증만, 내부망 차단 등은 호출자 책임). M-011에 "ImageInput.Url 영속화 보안 정책" 섹션 신규. M-003 영속화 규칙도 보강. F-007 NFR에도 동일 정책 명시 | provider-spec.md 보안 섹션, data-model.md M-011 + M-003, features.md F-007 NFR | P-8 |
| R-024 | Minor | overview.md Out of Scope에 "호출자 지정 sessionId 부여" 명시. A-004 createSession에 "sessionId 부여 정책" 섹션 신규 (v0.1은 자동 UUID만, 호출자 명시는 v0.2). A-010 동작에도 동일 정책 명시. R-017과 정합 | overview.md, api.md A-004 + A-010 | P-1 |

---

## 3. 신규/수정/삭제된 ID 목록

### 신규 ID
**없음** (라운드 3는 정책 명세 보강 라운드. 신규 F/E/M/A/ERR/D 0건).

### 수정된 ID (의미/문구 보강 — ID 자체는 유지)

| ID | 변경 내용 |
|----|----------|
| F-007 | 정상 흐름 5단계 sessionId 출처 통일 (R-017), 다중 인스턴스 정책 추가 (R-019), 이미지 영속화 가이드 추가 (R-021), schemaVersion 정책 NFR 추가 (R-018), URL SSRF 호출자 책임 NFR 추가 (R-023), E-703 설명에 schemaVersion 미지원 케이스 추가 |
| F-008 | 정상 흐름 2/5단계를 케이스 A/B 시맨틱과 일치하도록 수정, "close ↔ 진행 중/이후 호출 시맨틱" 섹션 신규 (R-020) |
| A-002 | 동작 섹션에 close 시맨틱 부기 |
| A-003 | 동작 섹션에 close 시맨틱(Flow 케이스 A/B) 부기 |
| A-004 | "sessionId 부여 정책" 섹션 신규 (R-024), close 시맨틱 부기 |
| A-006 | 동작 섹션에 close 시맨틱 부기 (E-402 매핑 명시) |
| A-009 | "close 시맨틱" 케이스 표 신규 (R-020), 정책 섹션 정리 |
| A-010 | sessionId 자동 UUID 명시 강화 (R-017, R-024), 다중 인스턴스 last-write-wins 부기, close 시맨틱 부기 |
| A-011 | schemaVersion 정책 부기 (R-018), 다중 인스턴스 정책 부기 (R-019), close 시맨틱 부기 |
| A-012 | close 시맨틱 부기 |
| M-003 | 영속화 규칙에 1MB 거부(E-704)/SSRF 호출자 책임 부기 |
| M-007 | "다중 인스턴스 정책" 섹션 신규 (R-019) |
| M-011 | "schemaVersion 정책" 섹션 신규 (R-018, R-022 통합), "ImageInput.Url 영속화 보안 정책" 섹션 신규 (R-023), "영속화 시 이미지 처리 가이드" 섹션 신규 (R-021). 기존 "직렬화 정책"의 마이그레이션 한 줄은 신규 섹션으로 대체 |
| E-703 | 설명에 "schemaVersion 미지원" 케이스 추가 (R-018) |
| ERR-007 | 발생 조건에 "schemaVersion 미지원" 추가 (R-018) |

### 삭제된 ID
**없음**.

---

## 4. 미해결 결정 (D-XXX) 0건 확인

- overview.md "결정 사항 (라운드 2 종결)" 표는 D-001~D-005 5건 모두 종결 상태 그대로 유지.
- "미해결 결정: 0건." 문구도 그대로 유지.
- 본 라운드에서 새로 도입한 D-XXX 0건.
- "## 미해결 결정" 섹션 부재 (grep 확인 완료).

```
$ grep -r "^## 미해결" experiments/02-ai-agent-sdk/spec/
(no matches)
$ grep -rE "D-00[6-9]" experiments/02-ai-agent-sdk/spec/
(no matches)
$ grep "미해결 결정" experiments/02-ai-agent-sdk/spec/overview.md
미해결 결정: 0건.
```

---

## 5. 합의 종결 조건 자체 평가

| 조건 | 충족 여부 | 비고 |
|------|-----------|------|
| Critical 0건 | ✅ 충족 | 라운드 2도 0건, 본 라운드 신규 0건 |
| Major 0건 | ✅ 충족 | 라운드 2의 R-017~R-020 4건 모두 반영 |
| Minor 0건 | ✅ 충족 | 라운드 2의 R-021~R-024 4건 모두 반영 |
| 미해결 결정(D-XXX) 0건 | ✅ 충족 | 신규 도입 0건 |
| 모든 라운드 1 지적 반영 유지 | ✅ 충족 | R-001~R-016 통과분 손대지 않음 |
| 모든 라운드 2 지적 반영 | ✅ 충족 | R-017~R-024 8건 모두 |
| ID 충돌 0건 | ✅ 충족 | 신규 ID 0건이므로 충돌 가능성 없음 |
| 모순 0건 | ✅ 충족 (자체 검토) | A-010 시그니처 ↔ F-007 정상 흐름 통일 (R-017), F-008 ↔ A-009/A-010/A-011/A-012 시맨틱 통일 (R-020) |

→ **종결 조건 충족**. spec-reviewer 라운드 3 검토에서 동일 평가가 나오면 Phase 3(구현) 진입 가능.

---

## 6. 다음 단계

### 6.1 spec-reviewer 라운드 3 검토 요청
- 입력: 본 보고서 `_workspace/spec_draft_3.md` + 갱신된 6종 spec 파일
- 검토 포인트:
  - R-017~R-024 8건 반영 완전성
  - 새로운 모순/누락 도입 여부 (특히 close 시맨틱 표가 모든 API에 일관 적용됐는지)
  - 라운드 1/2 통과분 regression 없음 확인

### 6.2 라운드 3 통과 시 종결
- Critical 0 + Major 0 + Minor 0 + 미해결 결정 0 충족 → Phase 3(android-implementer) 진입.

### 6.3 잔여 사용자 결정 필요 여부
- **없음**. R-017~R-024 모두 spec-architect 합리적 기본값으로 결정 완료.
- 단, R-019 결정의 "다중 프로세스 Out of Scope"가 호출자 도메인(WorkManager 활용 등)에 영향이 있을 수 있어, Phase 3 진입 전 사용자 확인이 안전하다는 reviewer의 라운드 2 의견은 본 사양에는 Out of Scope로 명시 처리. 사용자가 v0.1에 다중 프로세스 지원이 필수라고 판단하면 별도 라운드 필요.
