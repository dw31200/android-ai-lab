# spec_draft_7 — v0.2 Tool use (Function calling) 사양 초안

> 작성자: spec-architect
> 대상 버전: v0.2 (v0.1 SDK F-001~F-008 완료 상태에서 신규 기능 추가)
> 하위 호환: 기존 ask/askStream/createSession/Session.send 시그니처 불변

---

## 1. 신규 기능 ID

| ID | 기능명 | 한 줄 설명 |
|----|--------|-----------|
| F-009 | Tool 등록과 단발 tool 호출 | 호출자가 Tool 정의를 등록하고 `askWithTools(...)`로 1회 tool_use → tool_result 왕복을 수행한다. |
| F-010 | 멀티턴 tool 실행 루프 | SDK가 자동으로 tool_use를 감지해 호출자가 등록한 Executor를 호출하고 tool_result를 다시 모델에 보내는 N회 루프를 수행한다 (최대 N=8). |

---

## 2. 신규 모델 ID

| ID | 엔티티명 | 설명 |
|----|----------|------|
| M-012 | ToolDefinition | 호출자가 등록하는 tool의 이름/설명/입력 스키마 |
| M-013 | ToolCall | 모델이 요청한 tool 호출 (id, name, inputJson) |
| M-014 | ToolResult | tool 실행 결과 (tool_use_id, content, isError) |
| M-015 | ToolSchema | 입력 스키마 정의 (JSON Schema 부분 집합: type/properties/required/description만) |

---

## 3. 신규 API ID

기존 사용 중인 A-001~A-012와 충돌하지 않도록 A-013부터 부여.

| ID | 이름 | 종류 | 관련 F-ID |
|----|------|------|-----------|
| A-013 | AiAgentClient.Builder.registerTool | Builder fun | F-009 |
| A-014 | AiAgentClient.askWithTools | suspend fun | F-009 |
| A-015 | AiAgentClient.executeToolLoop | suspend fun | F-010 |
| A-016 | Session.sendWithTools | suspend fun | F-009 |
| A-017 | Session.executeToolLoop | suspend fun | F-010 |

---

## 4. 신규 예외 ID

기존 E-001~E-801 / ERR-001~ERR-007과 충돌하지 않도록 E-901부터, ERR-901부터 부여.

| ID | 발생 조건 | AiException 매핑 |
|----|-----------|------------------|
| E-901 | 등록된 tool 이름 중복 (registerTool) | Configuration (ERR-901) |
| E-902 | tool 이름이 식별자 규칙 위반 (영문/숫자/_/- 외 문자, 64자 초과) | Configuration (ERR-901) |
| E-903 | ToolSchema에 미지원 JSON Schema 키워드 포함 (oneOf/anyOf/$ref/allOf/not 등) | Configuration (ERR-901) |
| E-904 | ToolSchema 깊이가 R-901 한계(=5) 초과 | Configuration (ERR-901) |
| E-905 | 등록된 tool 개수가 R-902 한계(=32) 초과 | Configuration (ERR-901) |
| E-906 | tool 루프가 R-903 한계(=8회) 초과 | Configuration("tool loop limit exceeded") (ERR-903) |
| E-907 | 모델이 반환한 tool_use 블록 파싱 실패 (JSON 깨짐, name 미등록 tool 지칭 등) | ServerError (ERR-902) |
| E-908 | tool_use.inputJson이 등록된 ToolSchema의 required/type을 만족하지 않음 | ServerError (ERR-902) |
| E-909 | 호출자 Executor가 throw (Throwable) | InvalidInput("tool execution failed: {name}: {detail}") (ERR-904) — CancellationException은 그대로 전파 |
| E-910 | Provider가 tool use 미지원 | Configuration("provider does not support tools") (ERR-901) |

| ID | 에러 코드 | AiException Variant | 설명 |
|----|-----------|--------------------|------|
| ERR-901 | Tool 설정 오류 | Configuration | 등록 단계 검증 실패 (E-901~E-905, E-910) |
| ERR-902 | Tool 응답 파싱 실패 | ServerError | 모델 응답의 tool_use 블록 자체가 잘못됨 (E-907, E-908) |
| ERR-903 | Tool 루프 한계 초과 | Configuration | 멀티턴 무한루프 방지 가드 (E-906) |
| ERR-904 | Tool 실행 실패 | InvalidInput | 호출자 Executor 예외 (E-909) |

---

## 5. 신규 규칙 ID

기존 R-001~R-024와 충돌하지 않도록 R-025부터 부여.

| ID | 규칙 |
|----|------|
| R-025 | Tool 이름은 `^[a-zA-Z][a-zA-Z0-9_-]{0,63}$` 정규식을 만족해야 한다 (Anthropic 권장 규칙과 일치). |
| R-026 (=R-901 별칭) | ToolSchema의 최대 깊이는 5 (root object를 깊이 1로 카운트). 초과 시 E-904. |
| R-027 (=R-902 별칭) | 단일 client에 등록 가능한 tool 최대 개수는 32. 초과 시 E-905. |
| R-028 (=R-903 별칭) | 단일 `askWithTools`/`executeToolLoop` 호출의 tool 루프 최대 횟수는 8. 초과 시 E-906. |
| R-029 | ToolSchema 지원 키워드: `type`(string/number/integer/boolean/object/array/null), `properties`, `required`, `description`, `items`(array의 element schema), `enum`(scalar only). 그 외는 미지원 (E-903). |
| R-030 | tool 등록은 Builder 단계에서만 가능. 런타임에 추가/제거 불가 (스레드 안전성·예측 가능성). v0.3 검토. |
| R-031 | `Session.sendWithTools`는 Session.send와 같은 Mutex로 직렬화된다 (동일 Session 동시 호출 시 큐잉, E-403 시맨틱 재사용). |

> R-901/R-902/R-903은 spec 문서 내부에서는 R-026/R-027/R-028로 통일 부여한다. 본 초안에서 ERR 매핑 표가 "R-901~R-903"으로 적힌 흔적이 보이면 R-026~R-028로 읽어야 한다.

---

## 6. Provider 매핑 영향

### P-CLAUDE (Anthropic Messages API)

- 요청 body에 `tools: [{ name, description, input_schema }]` 추가
- 응답 content가 `tool_use` 블록을 포함 → SDK가 M-013(ToolCall)으로 변환
- 다음 턴 요청 시 `messages`에 `{ role: "user", content: [{ type: "tool_result", tool_use_id, content, is_error }] }` 형식으로 ToolResult 삽입
- `stop_reason == "tool_use"`인 경우만 루프 계속, 그 외(`end_turn`/`max_tokens` 등)는 루프 종료
- Capabilities에 `supportsTools: Boolean = true` 추가 (P-CLAUDE는 true)

### Capabilities 확장

```kotlin
data class Capabilities(
    // 기존 필드 ...
    val supportsTools: Boolean,       // v0.2 신규
    val maxToolsPerRequest: Int,      // v0.2 신규 (Anthropic = 64이지만 SDK는 R-027 = 32로 제한)
)
```

---

## 7. 회귀 영향 분석 (하위 호환)

| 항목 | 영향 | 대응 |
|------|------|------|
| `AiAgentClient` 인터페이스 | 메서드 **추가만** (askWithTools, executeToolLoop) | 기존 ask/askStream/createSession 시그니처 불변 |
| `Session` 인터페이스 | sendWithTools/executeToolLoop **추가만** | 기존 send/history/clear/save 시그니처 불변 |
| `AiAgentClient.Builder` | `registerTool(...)` 빌더 메서드 추가 | 기존 apiKey/provider/model/timeout 체이닝 불변 |
| `Capabilities` 데이터 클래스 | 신규 필드 2개 추가 (supportsTools, maxToolsPerRequest) | data class 필드 추가 → ABI 변경. v0.1 호출자가 직접 생성한 적 없으므로 영향 미미하지만, 사양상 신규 NFR로 명시 |
| `M-011 SessionEntity.schemaVersion` | **변경 없음 (= 1 유지)** | tool 관련 history는 v0.2 별도 ToolSession으로 분리하거나 v0.2 schema 추가 시 별도 라운드에서 논의. v0.2 본 라운드는 영속화 미지원 (Out of Scope, F-009/F-010은 in-memory 한정) |
| 기존 ERR-001~ERR-007 매핑 | 변경 없음 | ERR-901~ERR-904는 신규 매핑만 추가 |

---

## 8. 사양 결정 사항 (사용자 확인 필요)

다음 결정은 spec-reviewer / 사용자 확인이 필요할 수 있다.

1. **Tool 등록 시점**: Builder 시점에만 등록 (R-030). 런타임 추가/제거 미지원 → 사용자가 동의?
2. **루프 한계 8회**: Anthropic 일반적 권장은 4~10. 8을 채택 (R-028) → 사용자 의견?
3. **Executor 예외 정책**: 호출자 Executor가 throw 시 SDK가 InvalidInput으로 변환 후 호출자에게 Result.failure로 돌려준다 (즉, 루프 중단). 대안은 "에러 결과를 tool_result(is_error=true)로 다음 턴에 전달하여 모델이 복구하도록 위임"인데, v0.2는 단순성 우선으로 **루프 중단**을 채택. → 사용자 의견?
4. **영속화 미지원**: tool 관련 메시지는 SessionEntity 직렬화 대상이 아님 (v0.2 본 라운드 Out of Scope). save된 Session을 load하면 history만 복원되며 tool_use/tool_result 블록은 일반 ASSISTANT/USER 텍스트로 직렬화되지 않음 — 호출자는 tool 호출 도중 save하지 말 것을 권장.

---

## 9. 다음 라운드(T20-spec-review)에서 spec-reviewer가 확인할 핵심 포인트

1. **F-010 멀티턴 루프의 종결 조건**: `stop_reason != "tool_use"` 외에 호출자 측에서 명시적으로 루프 중단(취소)할 수 있는 경로(코루틴 cancel)가 정확히 어떻게 동작하는지 시맨틱 명세 확인.
2. **E-908 (스키마 검증 실패) 위치**: SDK가 검증할지 모델 책임으로 넘길지. 본 초안은 "SDK가 사전 검증해 ERR-902로 throw"로 결정 → reviewer 동의 필요.
3. **F-009와 Session의 상호작용**: `Session.sendWithTools` 도중 tool_use → tool_result 메시지가 history에 어떻게 쌓이는지 (M-008.Message에 tool 메타 필드 추가 vs 별도 internal 트랙 유지). 본 초안은 **별도 internal 트랙 유지(history()에는 USER/ASSISTANT 텍스트만 노출)**로 가정.
4. **Capabilities 필드 추가의 ABI 영향**: v0.1 호출자 코드가 Capabilities를 직접 생성하지 않으므로 영향 미미하지만, 외부에서 mock Provider를 만든 테스트 코드는 영향. 사양상 "v0.2 mandatory field"로 명시 필요.
5. **Anthropic tool_choice 옵션 (auto/any/tool/none)**: v0.2 본 라운드에서는 `auto`(기본)만 노출하고 `tool`/`any`/`none`은 v0.3 검토로 분리. → 누락이 아닌 의도적 축소임을 명확히.

---

## 10. 산출물 매핑 (2단계 갱신 대상)

| 파일 | 추가 내용 |
|------|----------|
| spec/overview.md | "v0.2: Tool use" 섹션 추가, Out of Scope에서 "Tool use" 줄 삭제(이동) |
| spec/features.md | F-009/F-010 신규 섹션, F-001 표에 "v0.2 신규" 줄 추가 |
| spec/api.md | A-013~A-017 시그니처/예시, Tool/ToolDefinition/ToolResult 인터페이스 |
| spec/data-model.md | M-012~M-015 데이터 클래스, @Serializable 미적용 사유 명시 (v0.2 본 라운드 영속화 미지원) |
| spec/error-handling.md | ERR-901~ERR-904 표, E-901~E-910 매핑 추가 |
| spec/provider-spec.md | P-CLAUDE tool_use/tool_result 변환 규칙, Capabilities 필드 2개 추가 |
