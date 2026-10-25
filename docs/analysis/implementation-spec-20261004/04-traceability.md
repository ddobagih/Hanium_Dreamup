# 기능 이벤트 상태 정책 구현 검증 대응

[전체 목차](README.md) · [기능 목록](02-features.md) · [정책](03-policies.md)

## 현재 구현의 연결표

[기능 ↔ 이벤트 ↔ 상태 ↔ 정책 ↔ 구현 ↔ 테스트 CSV](evidence/feature-event-state-policy-test.csv)는 상세 명세의 이벤트별 행으로 구성된다. 코드 링크의 줄 번호는 고정 작업트리 기준이며, 테스트는 이번 실행 결과가 아닌 TEST_EXPECTATION이다.

## 기존 개선 항목과 연결

[기존 942개 항목 상태와 A B 배정](evidence/legacy-item-mapping.csv), [항목별 기능 대응 CSV](evidence/improvement-feature-map.csv), [상세 JSON](evidence/improvement-feature-map.json)을 함께 사용한다. 원장 상태는 그대로 보존했다.

| 대응 수준 | 항목 수 |
|---|---:|
| SHARED_FILE_OR_TEST_CANDIDATE | 782 |
| EXPLICIT_ITEM_ID | 34 |
| CATEGORY_ONLY | 126 |

- EXPLICIT_ITEM_ID: 분야 작성자가 근거를 읽고 기존 source_id를 직접 연결했다. 원문 전체 인수 조건의 완료 재판정은 아니다.
- SHARED_FILE_OR_TEST_CANDIDATE: 원장 근거와 기능 명세에서 같은 구현/시험 파일명이 발견되었다. MainActivity·공통 config 같은 넓은 파일은 후보 추출에서 제외했다. 파일명 일치만으로 요구사항 동일성을 확정하지 않는다.
- CATEGORY_ONLY: 기존 분야에 속하는 기능을 탐색 후보로 연결했다. 개별 항목 구현에 대한 직접 추적은 미확인이다.
- UNMAPPED: 이 분석의 기능 명세와 연결 근거를 찾지 못했다. 누락 또는 범위 차이를 추가 검토해야 한다.

각 행의 explicit_feature_ids, implementation_candidate_feature_ids, category_candidate_feature_ids를 구분해야 한다. 후보 대응을 직접 증거처럼 사용하지 않는다. A189나 B188이라는 배정 수를 기능 수 또는 해결된 결함 수로 환산하지 않는다.

## 진입점과 읽은 범위

[선언 진입점 인벤토리](evidence/entrypoint-inventory.json)는 HTTP, CLI, Android component/callback, UI listener, systemd의 선언 후보를 수집한다. 동적 호출/정책 분기/실제 환경 활성 여부는 분야별 본문과 독립 검토가 필요하다. 파일명이 기능 근거에 있다는 사실만으로 그 파일의 모든 분기가 문서화되었다고 선언하지 않는다.
