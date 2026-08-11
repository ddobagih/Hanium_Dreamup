# Feature Backlog

## 작성 규칙

- 기능은 사용자 가치가 보이는 단위로 적는다.
- 바로 구현할 때는 0.5~1일 안에 끝낼 수 있는 slice로 쪼갠다.
- 기능마다 완료 기준과 검증 방법을 반드시 둔다.
- 근거 없는 기능은 `아이디어/확인 필요`로 둔다.
- PDF 원안 기능은 출처가 있어도 현재 구현 범위를 넘으면 P1/P2/C 또는 확인 필요로 둔다.
- 2026-05-31 이후 P0는 Android native ARCore APK의 bbox/depth/TFLite 정합 검증이다.

## P0 - Android native core gate

| ID | 기능 | 사용자 가치 | 첫 slice | 완료 기준 | 검증 | 상태 | 근거 |
|---|---|---|---|---|---|---|---|
| P0-001 | Android overlay 좌표 정합 | 박스가 실제 객체 위에 맞아야 거리/경고가 의미 있음 | 최신 APK 설치 후 중앙/좌/우/상/하 물체 bbox 관찰 | offset/rotation/crop 문제가 PASS/FAIL/BLOCKED로 기록됨 | Device | blocked_env/device | `docs/android/android_device_overlay_depth_checklist_20260601.md` |
| P0-002 | ARCore depth sample 정합 | `1보`/거리 안내가 같은 객체 기준인지 확인 | 사람/물체를 가까이/멀리 움직이며 sample count/median 관찰 | distance가 단조롭게 변하고 bbox 내부 sample로 설명 가능 | Device | blocked_env/device | `docs/android/arcore_depth_estimation_architecture.md` |
| P0-003 | Coordinate mapper 보강 | overlay가 밀리면 잘못된 객체 거리 안내를 막음 | ARCore `transformCoordinates2d` 기반 mapper 설계/테스트 | view mapper와 depth mapper가 분리되고 회전/crop fixture 통과 | Unit/Device | planned | `apps/android/README.md` |
| P0-004 | detector age/stale guard | 오래된 detection으로 위험 안내하지 않게 함 | source/completed age와 frame delta 기준 stale guard 반영 | stale result가 depth 후보에서 제외되고 debug log에 이유가 남음 | Unit/Static, Device 확인 필요 | done_static_device_pending | `docs/current_status.md` |
| P0-005 | Android threshold/config decision | Android TFLite와 backend threshold 혼동 방지 | Android JSON vs backend config table 작성 | 차이가 의도인지 문서화되고 check warning 의미가 설명됨 | Static | done | `docs/android/android_backend_threshold_decision_20260531.md` |

## P1 - gate 이후 바로 필요한 것

| ID | 기능 | 사용자 가치 | 첫 slice | 완료 기준 | 검증 | 상태 | 근거 |
|---|---|---|---|---|---|---|---|
| P1-001 | metadata-only capture log | 이미지 저장 없이 bbox/depth 반복 검토 | 최근 N개 frameTs/detectorTs/class/bbox/depth median ring buffer | 기본 동작에서 이미지/깊이 파일 저장 없음, session stop 시 clear | Unit/Static | done_static_device_pending | `docs/android/android_metadata_capture_schema_20260601.md` |
| P1-002 | reviewed static dataset 복구 후 full metric rerun | detector threshold를 정적 이미지 기준으로 재평가 | dataset 존재 확인 후 script rerun | dataset/hash/split/metric이 기록됨 | Model | blocked_env/data | `docs/execution/2026-05-31_android_static_dataset_contract_progress.md` |
| P1-003 | Android TTS/haptic 최소 연결 | 화면을 보지 않아도 위험을 인지 | gate 통과 후 `UserFacingDepth.message`를 Android TTS/haptic에 연결 | rate limit과 report-only damage 정책 유지 | Unit/Device | blocked_by_gate | `docs/current_status.md` |
| P1-004 | Android `/reports/v2` upload | 손상 점자블록을 운영 검수로 연결 | `damaged_tactile_block`만 GPS 포함 upload 후보 생성 | normal/tactile area/COCO는 요청 생성 안 함 | Unit/Integration | blocked_by_gate | `docs/report_operations.md` |
| P1-005 | Backend/Admin export 회귀 | Android 신고가 운영 화면에서 관리 가능해야 함 | disposable DB에서 report→admin→CSV/JSON/GeoJSON trace | fake/demo/source metadata가 구분됨 | Integration/GIS | planned | `docs/report_operations.md` |

## P2 - 나중에 / 아이디어

| ID | 기능 | 사용자 가치 | 보류 이유 | 확인 필요 |
|---|---|---|---|---|
| P2-001 | STT/voice Android 연결 | hands-free 신고/반복/음성 on/off | Android bbox/depth gate 전 P0가 아님 | local voice prototype 재사용 방식, mic permission, privacy |
| P2-002 | TMAP/navigation Android 연결 | 길안내와 위험 안내 결합 | 목적지 검색/좌표 변환/실폰 보정이 남음 | backend proxy 재사용, Android client에 key 미포함 |
| P2-003 | PWA 설치/offline/TalkBack 수동 점검 | Web demo 접근성 보조 | 주 앱이 Android native로 이동 | Android/PWA 각각 evidence 분리 |
| P2-004 | Kakao Mobility/Map fallback | provider 다양화 | 현재 권한/제휴/비용 확인 필요 | 사용자 승인, key/약관 |
| P2-005 | 지자체 민원 시스템 실제 API 연동 | 신고 데이터가 공공 처리 시스템으로 이어짐 | 외부 기관/API/개인정보/운영 책임 필요 | demo/mock/export 우선, 실제 연동은 C 작업 |
| P2-006 | MLOps/data upload skeleton | 현장 데이터로 모델 개선 | 이미지·센서 로그 동의, storage, 비용, 비식별 정책 필요 | opt-in 범위, 저장/삭제 정책 |
| P2-007 | 실서비스 도메인/HTTPS release | 최종 산출물 제출 | 도메인, 배포 계정, secret, storage, 운영 정책 필요 | 사용자 승인 전 실행 금지 |

## 오늘/다음 실행 후보

| 후보 | 유형 | 예상 변경 범위 | 검증 방법 | 막힘 |
|---|---|---|---|---|
| Android overlay field note | 검증 | 실기기 관찰 기록 문서 | 중앙/좌/우/상/하 bbox 정합 기록 | Device 필요 |
| ARCore coordinate mapper plan/test | 구현/검증 | Android depth/inference mapper 또는 문서 | Gradle test + overlay field 재확인 | Device 최종 확인 필요 |
| detector age guard | 구현 | Android runtime | stale detection fixture/static/Gradle test | 없음 |
| metadata-only capture log | 구현 | Android debug runtime | 파일 저장 없음, ring buffer clear test | export는 별도 승인 |
| unified-primary doc cleanup | 문서/검증 | README/docs/product | canonical/legacy/superseded 분리와 삭제 후보 표시 | 실제 삭제는 승인 필요 |

## 백로그 상태 값

- `todo`: 아직 시작 전
- `planned`: 일/주간 계획에 들어감
- `in_progress`: 구현 중
- `blocked_by_gate`: 선행 gate 통과 전 보류
- `blocked_B`: 사용자 확인 필요
- `blocked_C`: 위험/대형 작업이라 승인 필요
- `blocked_env`: 환경 문제로 중단
- `done`: 완료 기준과 검증 근거 있음
- `dropped`: 명시적으로 제외
