# Feature Backlog

> 상태: `SUPERSEDED_PRODUCT_BOUNDARY_SNAPSHOT`. 아래 PWA 백로그는 새 작업에 사용하지 않는다. 현재 정본은 `docs/control/audits/walksafe-implementation-remediation-backlog-20260722-r001.json`이며 첫 작업은 `EPIC-01`이다.

## 작성 규칙

- 기능은 사용자 가치가 보이는 단위로 적는다.
- 바로 구현할 때는 0.5~1일 안에 끝낼 수 있는 slice로 쪼갠다.
- 기능마다 완료 기준과 검증 방법을 반드시 둔다.
- 근거 없는 기능은 `아이디어/확인 필요`로 둔다.
- PDF 원안 기능은 출처가 있어도 현재 구현 범위를 넘으면 P1/P2/C 또는 확인 필요로 둔다.
- 2026-07-10 이후 P0는 Web/PWA의 real detector, 모바일 field, release와 관리자 CSV 수동 신고 운영 검증이다.

## P0 - Web/PWA release gate

| ID | 기능 | 사용자 가치 | 첫 slice | 완료 기준 | 검증 | 상태 | 근거 |
|---|---|---|---|---|---|---|---|
| P0-001 | Web real detector 연결 | fake가 아닌 모델로 위험을 판단 | 품질 승인 checkpoint를 backend `yolo`/`real` provider에 연결하고 Web을 `server-v2`로 실행 | model path/hash/threshold/source와 fail-closed 상태가 기록됨 | Integration/Headless | planned | `docs/walksafe-v2/backend_model_integration_notes.md` |
| P0-002 | PWA/HTTPS release | 사용자가 주 앱을 설치하고 권한을 안전하게 사용할 수 있음 | PWA enable, domain/HTTPS, production env 고정 | install·update·camera/GPS/mic 권한 성공/실패와 rollback 근거 | Release | blocked_env/release | `apps/web/README.md` |
| P0-003 | Web mobile field E2E | 실제 보행 상황에서 음성·길안내·경고 사용 가능 | 실폰에서 sampled 탐지→browser TTS/진동, 목적지 변경·취소·다음 안내 질의→TMAP route→재탐색, 신고 확인 | 위험>상호작용>길안내, frozen/stale fail-safe, tactile 근거리 보조를 포함한 환경/기기/브라우저/로그 PASS/FAIL 기록 | Web Mobile Field | blocked_env/device | `docs/status/current_status.md` |
| P0-004 | report→관리자 CSV E2E | 신고를 검수해 기관에 수동 제출할 수 있음 | disposable PostGIS에서 신고·중복·상태·필터 CSV 실행 | no-skip DB, CSV 표본, 외부 수동 신고 runbook | Integration/GIS | code_connected_db_pending | `docs/operations/report_operations.md` |
| P0-005 | 관리자 auth/RBAC/audit | 무권한 신고 조회·다운로드 방지 | admin/API 인증과 역할별 검수/export 권한 | 권한 거부·감사 로그 테스트 | Security/Integration | todo | `docs/status/implementation_audit_20260710.md` |
| P0-006 | 13-class 모델 품질 복구 | 약한 보도 위험 클래스 미탐을 줄임 | corrupt data와 라벨 기준 정리, source별/독립 평가 | 성공 기준을 고정하고 재평가 리포트 생성 | Model | in_progress | `reports/walksafe_best_eval_20260708/final_evaluation_report.md` |
| P0-007 | 플랫폼·기관 문서 정합 | 제출 약속과 제품 운영 범위를 일치시킴 | Web/PWA primary, Android 보조, 관리자 CSV 수동 신고 반영 | current·제품·제출·분류 문서 검증 | Static | done_20260710 | `docs/status/implementation_audit_20260710.md` |

## P1 - gate 이후 바로 필요한 것

| ID | 기능 | 사용자 가치 | 첫 slice | 완료 기준 | 검증 | 상태 | 근거 |
|---|---|---|---|---|---|---|---|
| P1-001 | Web offline 정책 연결 | 저신호에서도 앱 상태와 미전송 신고를 안전하게 다룸 | shell cache와 offline queue 정책을 사용자 흐름에 연결 | 탐지·길안내 fail-closed, 중복 없는 재전송 | Browser E2E | helper_only | `apps/web/README.md` |
| P1-002 | 3~5초 미래 ROI | 이동 방향 위험을 더 일찍 경고 | browser GPS/heading/speed 기반 예측 ROI 기준 정의 | fixture와 field에서 horizon/경로/경고 결과 기록 | Unit/Field | todo | `docs/status/implementation_audit_20260710.md` |
| P1-003 | IMU+GPS 정밀 측위 | 신고 위치 품질 향상 | 브라우저 센서 가용성 조사와 Kalman/dead-reckoning prototype | 기준 경로 대비 위치 오차 리포트 | Research/Field | todo | `docs/status/implementation_audit_20260710.md` |
| P1-004 | GIS heatmap UI | 관리자 보수 우선순위 파악 | 기존 grid/cluster/GeoJSON을 지도에 표시 | 필터와 지도 집계가 동일 | GIS/Browser | backend_only | `docs/status/current_status.md` |

## P2 - 나중에 / 아이디어

| ID | 기능 | 사용자 가치 | 보류 이유 | 확인 필요 |
|---|---|---|---|---|
| P2-001 | Android ARCore/depth 보조 연구 | browser 외 depth/온디바이스 가능성 확인 | 주 Web release를 막지 않음 | overlay/depth/TFLite/FPS evidence 분리 |
| P2-002 | Android unified TFLite export | 네이티브 실험에서 통합 모델 평가 | model quality gate 전 투입 금지 | tensor contract, APK, Device FPS |
| P2-003 | Android 음성/길안내 실기기 검증 | 네이티브 대안 연구 | 후보 번호 선택까지 action 연결 완료, 주 Web release를 막지 않음 | 실폰 mic/TTS·보행 중 인식률, source 분리 |
| P2-005 | 지자체 민원 API 연동 | 현재 제품 가치에 포함하지 않음 | 사용할 수 있는 기관 API가 없어 관리자 CSV 수동 신고로 확정 | 제품 범위 밖 |
| P2-006 | MLOps/data upload skeleton | 현장 데이터로 모델 개선 | 이미지·센서 로그 동의, storage, 비용, 비식별 정책 필요 | opt-in 범위, 저장/삭제 정책 |
| P2-007 | 실서비스 도메인/HTTPS release | 최종 산출물 제출 | 도메인, 배포 계정, secret, storage, 운영 정책 필요 | 사용자 승인 전 실행 금지 |

## 오늘/다음 실행 후보

| 후보 | 유형 | 예상 변경 범위 | 검증 방법 | 막힘 |
|---|---|---|---|---|
| 약한 class label/data 정리 | 모델/데이터 | `curb_step`, `uneven_sidewalk`, corrupt image | source별/독립 eval과 failure review | 대량 데이터 검수 |
| Web real detector 연결 | Web/backend/model | quality-approved checkpoint와 production config | ASGI, browser source/latency | model quality gate |
| Web mobile field note | 검증 | 실폰 브라우저 관찰 기록 | camera/GPS/mic/TTS/route/report | HTTPS/Device 필요 |
| PostGIS report no-skip | 통합 검증 | backend report/duplicate/review/export | 전체 backend report tests skip 0 | disposable DB 필요 |
| Admin CSV 수동 신고 runbook | 운영/제출 | 검수·필터·CSV·외부 접수 절차 | sample CSV와 단계별 확인 | 운영 채널 확인 |

## 백로그 상태 값

- `todo`: 아직 시작 전
- `planned`: 일/주간 계획에 들어감
- `in_progress`: 구현 중
- `code_connected_device_pending`: 코드 연결 완료, 실기기 검증 대기
- `code_connected_db_pending`: 코드 연결 완료, DB 검증 대기
- `code_connected_db_device_pending`: 코드 연결 완료, DB와 실기기 검증 대기
- `decision_needed`: 사용자 또는 제출 범위 결정 필요
- `blocked_by_gate`: 선행 gate 통과 전 보류
- `blocked_B`: 사용자 확인 필요
- `blocked_C`: 위험/대형 작업이라 승인 필요
- `blocked_env`: 환경 문제로 중단
- `done`: 완료 기준과 검증 근거 있음
- `dropped`: 명시적으로 제외
