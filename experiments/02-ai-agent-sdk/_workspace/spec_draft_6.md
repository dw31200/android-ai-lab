# Spec Draft 6 — F-002 QA 회신 보강 (S-T12-2 / S-T12-3)

작성자: spec-architect
작성 일시: 2026-05-09
입력: `_workspace/qa_report_4.md` §6 (S-T12-2 / S-T12-3)
범위: F-002 한정. 두 항목 모두 Severity Minor — 다음 라운드 진입 차단 사항 아님. 본 라운드는 사양과 코드 동작 정합 강화 목적.
원칙: **코드 동작은 그대로 유지. 사양만 코드 동작에 맞춰 보강.**

---

## 0. 반영 표

| QA-ID | Severity | 결정 방향 | 반영 위치 | 변경 요지 |
|-------|----------|-----------|-----------|-----------|
| S-T12-2 | Minor | E-202(합계) / E-208(개수) **분리**. 두 케이스가 의미상 다르고 메시지 패턴도 구분 가능(`count=` 토큰 유무)하므로 단일 ID 통합보다 분리가 깔끔. 코드는 두 케이스 모두 InvalidInput으로 throw하므로 ERR 매핑은 동일(ERR-005). | `spec/features.md` F-002 예외 흐름 표 (E-202 행 보강 + E-208 행 신설) | E-202 = 합계(20MB) 초과, E-208 = 개수(Capabilities.maxImagesPerRequest) 초과로 의미 분리. 메시지 패턴 명시 ("images total too large: ..." 합계 / "images total too large: count=..." 개수). |
| S-T12-3 | Minor | F-002에 "v0.1 ImageInput.Uri 정책" 별도 섹션 신설 + E-203 행을 v0.1 정책 명시로 보강 + M-003 Uri Variant 설명 한 줄 추가 + D-004 본문에 한 줄 연장. | `spec/features.md` F-002 정상 흐름 2단계 주석 + 신규 섹션 / `spec/features.md` F-002 예외 흐름 E-203 행 / `spec/data-model.md` M-003 Uri Variant 셀 / `spec/overview.md` D-004 결정 표 사유 셀 | "권한·존재 X" 표현 제거. v0.1은 Uri 도달 시 무조건 거부. v0.2에서 자동 resolve 옵션 검토 예정 명시. |

---

## 1. S-T12-2 — E-202 의미 분리 (변경 전 / 변경 후)

### 1.1 `spec/features.md` F-002 예외 흐름 표

**변경 전 (L120 부근)**:
```
| E-201 | 이미지 단일 파일 5MB 초과 | `AiException.InvalidInput("image too large")` (D-004: SDK는 검증만, 자동 리사이즈 안 함) |
| E-202 | 이미지 총 합계 20MB 초과 | `AiException.InvalidInput("images total too large")` |
| E-203 | Uri 읽기 실패 (권한·존재 X) | `AiException.InvalidInput("uri unreadable")` |
| E-204 | 지원하지 않는 mimeType | `AiException.InvalidInput("unsupported mime type")` |
| E-205 | 활성 Provider가 이미지 미지원 | `AiException.Configuration("provider does not support images")` |
| E-206 | `ImageInput.Url` fetch 실패 ... | ... |
| E-207 | 이미지 디코딩 실패 ... | ... |
| E-101~E-110 | F-001과 동일 | F-001 참조 |
```

**변경 후**:
```
| E-201 | 이미지 단일 파일 5MB 초과 | `AiException.InvalidInput("image too large")` (D-004: SDK는 검증만, 자동 리사이즈 안 함) |
| E-202 | 이미지 총 합계가 M-001의 20MB 한계 초과 | `AiException.InvalidInput("images total too large: ...")` (메시지 패턴: 합계 케이스는 size 정보 표기) |
| E-203 | `ImageInput.Uri` 도달 시 (v0.1 정책 — D-004 연장, 정상 흐름 "v0.1 ImageInput.Uri 정책" 섹션 참조) | `AiException.InvalidInput("uri unreadable")` (호출자가 사전에 ContentResolver로 Bytes 변환 미수행) |
| E-204 | 지원하지 않는 mimeType | `AiException.InvalidInput("unsupported mime type")` |
| E-205 | 활성 Provider가 이미지 미지원 | `AiException.Configuration("provider does not support images")` |
| E-206 | `ImageInput.Url` fetch 실패 ... | ... |
| E-207 | 이미지 디코딩 실패 ... | ... |
| E-208 | 이미지 개수가 활성 Provider의 `Capabilities.maxImagesPerRequest` 초과 (M-001의 ≤10 제약은 통과했으나 Provider 한계가 더 작은 경우. v0.1 P-CLAUDE는 10이라 도달 케이스 없음, 미래 Provider 대비 방어) | `AiException.InvalidInput("images total too large: count=...")` (메시지 패턴: 개수 케이스는 `count=` 토큰 포함, E-202와 텍스트 구별) |
| E-101~E-110 | F-001과 동일 | F-001 참조 |
```

### 1.2 사유

- **E-202와 E-208을 분리한 이유**: 두 케이스는 발생 조건(합계 byte vs 이미지 개수)과 검출 단계(누적 후 비교 vs 진입 시 size 비교)가 다르다. 단일 ID에 OR 묶음으로 통합하면 후속 라운드에서 케이스별 단위 테스트·로그·메시지 가이드를 부착할 때 식별자가 모호해진다. ID 컨벤션상 E-숫자 단일이 깔끔하므로 `E-202'` 같은 변형보다 신규 ID `E-208`이 일관적.
- **E-208 신규 ID 부여 정당성**: 본 사양 grep에서 E-208이 어디에서도 사용되지 않음을 확인했고, F-002 시리즈(E-201~E-207) 다음 번호로 연속.
- **메시지 패턴 명시**: 코드 동작(`AiAgentClient.kt:355`의 `count=` vs L395의 size 표기)을 사양 셀에 적시하여, 향후 messages 변경 시 사양과 동기화 책임을 명확히. 호출자는 메시지 패턴으로 두 케이스를 구별 가능 (단, 메시지 텍스트는 안정 API가 아님 — variant + 메시지 prefix 정도만 의존).
- **ERR 매핑은 동일(ERR-005)**: 두 ID 모두 `AiException.InvalidInput`이라 `error-handling.md`의 ERR-005 매핑 표에 변경 없음. error-handling.md 갱신 불필요.
- **A-002/A-006 시그니처 영향 없음**: 두 케이스 모두 진입 시 검증 실패로 `Result.failure(...)` 반환, 시그니처 불변.

### 1.3 영향받는 다른 사양 ID

| ID | 영향 | 처리 |
|----|------|------|
| ERR-005 | 매핑 표 변경 없음 (E-202·E-208 모두 InvalidInput → ERR-005) | error-handling.md 변경 없음 |
| F-004 E-201~E-207 (F-002 동일) | 본 보강에 따라 F-004 예외 흐름 "F-002와 동일" 표기에 E-208도 자동 포함됨 | features.md L199 `E-201~E-207` 토큰 그대로 둘지 검토 — **본 라운드는 그대로 유지**(E-208까지 명시적으로 갱신하면 F-004 검증 다시 필요. Severity Minor 범위 외) |
| M-001 `images.size <= 10` (data-model.md L49) | E-208 발생 조건은 M-001 init을 통과한 후 Provider 한계가 더 작은 경우로 한정 — 본 보강에서 명시적으로 적시 | data-model.md 변경 불필요 |
| Capabilities.maxImagesPerRequest (provider-spec.md) | E-208이 본 필드를 직접 참조 | provider-spec.md 변경 불필요 (P-CLAUDE는 10 그대로, 도달 불가 명시는 features.md E-208 셀에 이미 포함) |

> 메모: F-004의 "E-201~E-207 동일" 표기는 코드 재사용(`session.send`도 `validateImages` 재사용 예정 — qa_report_4 §7) 시 E-208도 자동 적용된다. F-004 라운드 진입 시 spec-architect가 그 시점에 동기화하면 됨. 본 라운드에서 무리하게 끌어오지 않음.

---

## 2. S-T12-3 — ImageInput.Uri v0.1 정책 명시 (변경 전 / 변경 후)

### 2.1 `spec/features.md` F-002 정상 흐름 + 신규 섹션

**변경 전 (L109-114)**:
```
### 정상 흐름
1. 호출자가 `client.ask(AiRequest(prompt = "...", images = listOf(...)))` 호출
2. SDK가 각 ImageInput을 검증 (크기/mimeType)
3. 각 ImageInput을 Provider 형식으로 인코딩 (Uri/Bytes → base64, Url → URL 또는 fetch)
4. Provider API 호출
5. 응답 변환 후 반환

### 예외 흐름
```

**변경 후**:
```
### 정상 흐름
1. 호출자가 `client.ask(AiRequest(prompt = "...", images = listOf(...)))` 호출
2. SDK가 각 ImageInput을 검증 (크기/mimeType — Uri는 본 단계에서 즉시 거부, 아래 "v0.1 ImageInput.Uri 정책" 참조)
3. 각 ImageInput을 Provider 형식으로 인코딩 (Bytes → base64, Url → fetch 후 base64. v0.1에서 Uri는 도달 불가)
4. Provider API 호출
5. 응답 변환 후 반환

### v0.1 ImageInput.Uri 정책 (D-004 연장)
- v0.1에서 SDK는 `ImageInput.Uri`를 자동 resolve 하지 않는다. 호출자는 `ContentResolver.openInputStream(uri)`로 ByteArray를 직접 읽고 `ImageInput.Bytes(data, mimeType)`로 변환한 뒤 SDK에 전달해야 한다.
- SDK는 진입 시점(`ask`/`askStream`/`session.send`의 검증 단계)에 `ImageInput.Uri`를 만나면 **권한·존재 여부와 무관하게 즉시** `AiException.InvalidInput("uri unreadable")`로 거부한다 (E-203).
- 사유: ContentResolver 호출은 호출자 컨텍스트·권한·라이프사이클에 의존하므로 SDK가 자동 수행하면 책임 경계가 모호해진다. D-004의 "이미지 가공은 호출자 책임" 정책의 연장.
- v0.2에서 자동 resolve 옵션(예: Builder에 `ContentResolver` 주입) 검토 예정.

### 예외 흐름
```

### 2.2 `spec/features.md` F-002 예외 흐름 E-203 행 (위 §1.1에 함께 반영됨)

**변경 전**: `| E-203 | Uri 읽기 실패 (권한·존재 X) | AiException.InvalidInput("uri unreadable") |`

**변경 후**: `| E-203 | ImageInput.Uri 도달 시 (v0.1 정책 — D-004 연장, 정상 흐름 "v0.1 ImageInput.Uri 정책" 섹션 참조) | AiException.InvalidInput("uri unreadable") (호출자가 사전에 ContentResolver로 Bytes 변환 미수행) |`

### 2.3 `spec/data-model.md` M-003 Uri Variant 설명

**변경 전**:
```
| Uri | uri: android.net.Uri | content:// 또는 file:// URI |
```

**변경 후**:
```
| Uri | uri: android.net.Uri | content:// 또는 file:// URI. **v0.1: SDK는 자동 resolve 안 함 — `ask`/`askStream`/`session.send` 진입 시 즉시 E-203(`InvalidInput("uri unreadable")`)으로 거부.** 호출자가 `ContentResolver.openInputStream(uri)`으로 ByteArray를 읽고 `ImageInput.Bytes`로 변환 후 전달해야 한다 (D-004 연장, F-002 "v0.1 ImageInput.Uri 정책" 섹션 참조). v0.2에서 자동 resolve 옵션 검토. |
```

### 2.4 `spec/overview.md` D-004 결정 표 사유 셀

**변경 전**:
```
| D-004 | 이미지 자동 리사이즈 | **호출자 책임** | SDK는 단일 5MB / 합계 20MB 검증만 수행. 자동 리사이즈는 품질 손실·정책 결정 도메인이 호출자에 있으므로 SDK는 관여하지 않음 |
```

**변경 후**:
```
| D-004 | 이미지 자동 리사이즈 | **호출자 책임** | SDK는 단일 5MB / 합계 20MB 검증만 수행. 자동 리사이즈는 품질 손실·정책 결정 도메인이 호출자에 있으므로 SDK는 관여하지 않음. 본 정책의 연장으로, **`ImageInput.Uri` 자동 resolve(ContentResolver 호출)도 SDK가 수행하지 않는다** — 진입 시 즉시 E-203 거부 (F-002 "v0.1 ImageInput.Uri 정책" 섹션 참조) |
```

### 2.5 사유

- **"권한·존재 X" 표현 제거**: QA 지적대로 호출자에게 "Uri가 정상이면 통과"하는 인상을 주는 모호한 문구. v0.1 코드는 모든 Uri 도달을 무조건 거부하므로 사양도 그렇게 명시.
- **F-002 정상 흐름에 별도 섹션을 둔 이유**: 표 셀 한 줄로는 "왜 자동 resolve를 안 하는가"의 도메인 사유가 전달되지 않는다. F-007 "이미지 영속화 운영 가이드"(L349-356) 패턴을 따라 정상 흐름 직후 정책 섹션을 둔다.
- **D-004 본문 연장**: D-004는 원래 "리사이즈"만 다뤘으나, 본 라운드 정책이 D-004의 **연장(extension)**임을 명시하여 단일 결정으로 묶음. 새 D-XXX를 만들지 않은 이유는 본 결정이 이미 합의된 D-004의 "이미지 가공은 호출자 책임"의 직접 연장이며, 별도 결정 갈래를 늘리면 합의 비용 증가.
- **M-003 Uri Variant 설명 보강**: 호출자가 data-model.md만 보고 ImageInput을 선택할 가능성이 있으므로 v0.1 거부 정책을 Variant 셀에 짧게 부기. 상세는 F-002로 링크.
- **v0.2 검토 항목 명시**: 본 정책이 v0.1 한정임을 분명히 하여 호출자가 향후 마이그레이션 가능성을 예측할 수 있게.

### 2.6 영향받는 다른 사양 ID

| ID | 영향 | 처리 |
|----|------|------|
| F-002 정상 흐름 2단계 | 주석 추가 | 본 보강에서 함께 반영 |
| F-002 정상 흐름 3단계 | "Uri/Bytes → base64" 표현이 v0.1 동작과 모순 — "Bytes → base64, Url → fetch 후 base64. v0.1에서 Uri는 도달 불가"로 정정 | 본 보강에서 함께 반영 |
| E-203 행 (위 §1.1과 동시 반영) | 조건 셀과 처리 방식 셀 모두 보강 | 본 보강에서 함께 반영 |
| F-004 예외 흐름 "F-001/F-002와 동일" 표기 | E-203의 의미가 변경되었으나 ID는 그대로 — F-004 표기는 변경 불필요 | 변경 없음 |
| F-007 영속화 (M-011 ImageInput.Uri 보관) | "URI 문자열만 보관 (앱 재설치 시 깨질 수 있음)" 문구는 v0.1에 ImageInput.Uri가 history에 들어갈 경로가 거부되므로 도달 불가 — 단, M-011 ImageInputEntity.Uri 정의 자체는 v0.2 대비 보관 가치 있음 | data-model.md M-011 변경 없음 (도달 불가지만 직렬화 형식은 미래 대비) |
| D-004 (overview.md) | 본문 연장 — 본 보강에서 반영 | 함께 반영 |

---

## 3. 사양 ID 컨벤션 점검

- 신규 ID: **E-208** (F-002 예외) — 기존 spec 디렉토리 grep 결과 미사용 확인 후 부여.
- 기존 ID 의미 변경: **E-202** (조건 셀 보강), **E-203** (조건 셀·처리 셀 보강) — ID는 유지, 의미 명확화.
- D-XXX 신규 0건 (D-004 연장으로 처리).
- F-/M-/ERR-/A-/R-/P-/S- 신규 0건.

---

## 4. 본 라운드에서 건드리지 않은 부분 (의도적)

- F-002 외 모든 F-: 변경 없음.
- error-handling.md ERR-005 매핑 표: E-208도 ERR-005로 매핑되지만 ERR 표 자체는 ID-단위가 아닌 variant-단위이므로 변경 불필요.
- api.md A-002/A-003/A-006 시그니처: 변경 없음 (Result.failure 반환은 기존 시그니처 그대로).
- provider-spec.md P-CLAUDE Capabilities: 변경 없음 (maxImagesPerRequest=10 유지).
- F-004 E-201~E-207 동일 표기: E-208 자동 포함 의미는 본 draft §1.3 메모로만 기록, F-004 본문 갱신은 F-004 라운드로 이연.
- Q-T12-1 (MockWebServer https 강제 통합 테스트 불가) / Q-T12-4 (E-206 5xx cause 합성) / Q-T12-5 (validateImages 회귀 흐름) / S-T11-1 (F-001 E-107 위치) — 본 라운드 범위 외, 사양 변경 불필요로 유보.

---

## 5. 편집한 spec 파일 목록

| 파일 | 변경 라인 (대략) | 변경 요지 |
|------|------------------|-----------|
| `spec/features.md` | F-002 예외 흐름 표(L119-126 부근, E-202·E-203 행 보강 + E-208 행 신설), F-002 정상 흐름 2~3단계 주석, 신규 "v0.1 ImageInput.Uri 정책" 섹션 (예외 흐름 직전) | S-T12-2 + S-T12-3 통합 반영 |
| `spec/data-model.md` | M-003 ImageInput sealed 표 Uri Variant 셀 (L82 부근) | S-T12-3 부수 반영 |
| `spec/overview.md` | 결정 사항 표 D-004 사유 셀 (L72 부근) | S-T12-3 부수 반영 (D-004 연장 명시) |

---

## 6. 다음 단계 권고 (오케스트레이터에게)

1. 본 보강은 코드 변경 0건 — 다음 wave (F-003 / F-004 / F-006) 진입에 영향 없음.
2. spec-reviewer 호출 여부는 오케스트레이터 결정. 신규 ID 1건(E-208) + 기존 ID 의미 명확화 2건 + D-004 연장이라 라운드 1~5 검토 결과를 뒤집지 않음 — fast-track 가능.
3. F-004 진입 시 spec-architect는 E-208을 F-004 예외 흐름의 "F-002와 동일" 토큰에 자동 포함시킬지 명시 갱신할지 결정해야 함 (현재는 자동 포함 의미로 유지).
4. android-implementer는 본 라운드 코드 변경 없음. 단, 다음 라운드에서 단위 테스트 메시지 매칭(예: `AskWithImagesTest` 4번 케이스)이 사양 E-208을 직접 참조하도록 KDoc/주석에 ID를 부기하면 추적성 향상 (의무 아님, 권장).
