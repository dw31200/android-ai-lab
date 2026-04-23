# 화면 명세

> 작성 규칙
> - 화면 ID: S-숫자 (예: S-001)
> - 각 화면의 UiState를 반드시 정의할 것
> - 화면 전환은 모두 명시할 것
> - AI CLI 지시 시 화면 ID로 참조 (예: "S-001 구현해줘")

---

## 화면 목록

| ID | 화면명 | 진입 경로 | 관련 기능 |
|----|--------|-----------|-----------|
| S-001 | | | |
| S-002 | | | |

---

## S-001. [화면명]

### 진입 경로
- 경로 1
- 경로 2

### UI 구성요소
| 컴포넌트 | 타입 | 설명 | 비고 |
|----------|------|------|------|
| | TextField | | |
| | Button | | |
| | Text | | |

### UiState 정의
```kotlin
data class [화면명]UiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
```

### UiEvent 정의 (사용자 액션)
```kotlin
sealed class [화면명]Event {
    // data class OnButtonClick(...) : [화면명]Event()
}
```

### 화면 상태별 UI
| 상태 | UI 변화 |
|------|---------|
| 로딩 중 | 버튼 비활성화, 로딩 인디케이터 표시 |
| 에러 | 에러 메시지 표시 |
| 성공 | |

### 화면 전환
| 트리거 | 이동 대상 | 비고 |
|--------|-----------|------|
| | S-002 | |

---

## S-002. [화면명]

### 진입 경로

### UI 구성요소
| 컴포넌트 | 타입 | 설명 | 비고 |
|----------|------|------|------|

### UiState 정의
```kotlin
data class [화면명]UiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
```

### 화면 전환
| 트리거 | 이동 대상 | 비고 |
|--------|-----------|------|
