# android-ai-lab

AI CLI를 활용한 Android 개발 실험 저장소.

## 프로젝트 구조

- `experiments/` : 각 실험 앱 모듈 (숫자-주제명 형식)
- `docs/prompts/` : 검증된 프롬프트 패턴 모음
- `docs/retrospectives/` : 주차별 스터디 회고

## 코딩 컨벤션

- 언어: Kotlin
- UI: Jetpack Compose
- 아키텍처: MVVM + Clean Architecture
- 비동기: Coroutines + Flow
- 의존성 주입: Hilt

## 브랜치 전략

- `main` : 안정 브랜치
- `feature/#이슈번호-설명` : 기능 개발
- `fix/#이슈번호-설명` : 버그 수정
- `study/#이슈번호-설명` : 학습/실습

## 커밋 컨벤션

```
feat: 새 기능
fix: 버그 수정
refactor: 리팩토링
study: 학습/실습
docs: 문서
test: 테스트
```

## 실험 네이밍

`experiments/숫자-주제명/` 형식으로 관리
예: `experiments/01-harness-basics/`

## 주의사항

- API 키는 절대 커밋하지 않음 (local.properties 사용)
- 각 실험은 독립적인 Android 모듈로 구성
