# Legacy 자료

이 디렉터리는 현재 WalkSafe 제품·산출물 판단에 사용하지 않는 자료를 원래 bytes 그대로 보존한다. 현재 구현, 정책, 테스트 또는 팀 분담의 출발점으로 사용하지 않는다.

## 보존된 자료

| 범위 | 원래 위치 | 상태 |
| --- | --- | --- |
| [2026-05-22 two-model AI handoff](ai_tasks/walksafe_two_model_runtime_20260522/README.md) | `ai_tasks/walksafe_two_model_runtime_20260522/` | `LEGACY_REFERENCE / HISTORICAL_ONLY` |

정확한 원본·목적지·Git blob·SHA-256·크기는 [이동 manifest](archive-manifest.json)에 있다. payload 두 파일의 본문과 내부 과거 경로 표기는 이동 과정에서 수정하지 않았다.

## 사용 규칙

- 현재 제품 정보는 루트 [프로젝트 안내](../README.md)와 [중앙 가이드](../docs/guides/README.md)를 사용한다.
- Legacy payload를 현재 코드·CI·산출물의 입력으로 연결하지 않는다.
- 이동 전 상태는 `archive/current-pre-modernization-20260811` 브랜치와 `preservation/current-pre-modernization-20260811` 태그에서 복구할 수 있다.
- 새 이동은 checkpoint·canonical·Goal·static-protected·runtime 참조 감사를 통과한 뒤 manifest와 회귀 테스트를 함께 갱신한다.
- 원격 `main`은 과거 상태 보존용으로 그대로 두며 현재 구현의 참고 자료로 사용하지 않는다.
