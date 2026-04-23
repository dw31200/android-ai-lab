# 데이터 모델

> 작성 규칙
> - 엔티티 ID: M-숫자 (예: M-001)
> - 모든 필드의 타입과 제약 조건을 명시할 것
> - 엔티티 간 관계를 관계도로 표현할 것

---

## 엔티티 목록

| ID | 엔티티명 | 설명 |
|----|----------|------|
| M-001 | | |
| M-002 | | |

---

## M-001. [엔티티명]

| 필드 | 타입 | 필수 | 설명 | 제약 조건 |
|------|------|------|------|-----------|
| id | String | Y | 고유 식별자 | UUID |
| createdAt | Long | Y | 생성 일시 | timestamp (ms) |
| updatedAt | Long | Y | 수정 일시 | timestamp (ms) |

### Room Entity 정의
```kotlin
@Entity(tableName = "")
data class [엔티티명]Entity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long
)
```

---

## M-002. [엔티티명]

| 필드 | 타입 | 필수 | 설명 | 제약 조건 |
|------|------|------|------|-----------|
| id | String | Y | 고유 식별자 | UUID |

---

## 관계도

```
M-001 [엔티티명] 1 ──< M-002 [엔티티명]
(설명: 하나의 M-001은 여러 M-002를 가진다)
```

---

## 로컬 저장 전략

| 데이터 | 저장 방식 | 이유 |
|--------|-----------|------|
| 사용자 세션 | DataStore | 단순 key-value |
| 목록 데이터 | Room | 쿼리 필요 |
| 설정값 | DataStore | 단순 key-value |
