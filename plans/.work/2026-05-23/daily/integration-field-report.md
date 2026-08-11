# Hanium Dreamup / WalkSafe Assist - Integration/Field Test/Report lane note (2026-05-24)

## 최근 진행 근거
- 2026-05-18 `product/vision.md`, `product/roadmap.md`, `product/backlog.md`, `product/done-criteria.md`, `product/decisions.md`: 제품 방향은 착용형 스마트폰 PWA, TTS/진동 우선, 신고 evidence 축적이다. 운영 모드는 `feature-growth`.
- 2026-05-22 `README.md`, `docs/current_status.md`, `docs/walksafe-v2/*.md`: `fake-v2`/`server-v2`, `/detect/v2`, `/reports/v2`, 자동 tactile damage reporting 기준이 현재 canonical. 단 `/detect/v2`는 아직 실제 YOLO26s/COCO adapter가 아니라 fake contract.
- 2026-05-22 `daylog/2026-05-22.md`: v2 fake contract, `/reports/v2`, 자동 신고 상태 머신, voice-triggered report, admin v2 metadata 표시, PWA hooks/risk evaluator 분리가 기록됨.
- 2026-05-22 `docs/execution/2026-05-22_yolo26s_stage1_stage2_review.md`: Stage1 `best.pt`가 현재 MVP 후보. Stage2는 개선 근거가 약함. `tactile_damage_area`는 FP/FN과 threshold calibration 필요.
- 2026-05-23 `daylog/2026-05-23.md`: PWA/Backend/Voice/Android/PostGIS 신규 검증 없음. reviewed YOLO26s 학습은 진행 중으로 기록되어 최종 성능 근거 아님.
- 2026-05-19 `docs/execution/2026-05-19_integration_field_report.md`: PWA 정적/build와 backend no-DB 일부 PASS. PostGIS, loopback HTTP, Android/ADB, voice browser/phone, `/admin` 운영 흐름은 BLOCKED/PENDING.
- `plans/features`, `plans/verify/ready`, `plans/verify/reports`: 디렉터리는 있으나 파일 없음. `PM/status`, `PM/reports/*`, product audit, automation metrics는 `PM` 디렉터리 자체 없음.
- `plans/daily/2026-05-24.md`: 없음. 저장소 루트 `AGENTS.md`도 없음으로 확인되어 사용자 제공 지침을 적용.

## 내일 목표 후보
- 1순위: `server-v2` 실제 모델 연결을 위한 integration evidence trace를 만든다. 목표는 adapter가 준비됐을 때 `detect -> reports/v2 -> admin`을 report ID 기준으로 한 번에 검증하는 thin slice다.
- 2순위: `/reports/v2` 포함 PostGIS no-skip/API smoke를 disposable DB에서 확인한다. 막히면 HTTP trace template과 fixture metadata로 대체한다.
- 3순위: `/admin` 운영 흐름을 v2 metadata 기준으로 확인한다. `source`, `model_key`, `source_model`, `trigger`, `auto_reported`, 상태 변경을 같은 표에 묶는다.
- 4순위: Android 목걸이 착용 smoke를 `fake-v2`와 `server-v2`로 분리 계획한다. 가능하면 카메라/GPS/heading/TTS/진동/자동신고 상태를 실기기로 기록한다.
- 5순위: 데모/보고 kit를 갱신한다. fake, server, model metric, voice direct-call, HTTP, field 근거를 분리하고 Stage1 후보/미완료 adapter/미완료 field를 과대해석하지 않는다.

## 상세 체크리스트 초안
- [ ] 공통 runtime gate 작성 → 검증: DB `5432`, backend `8000`, web `3000`, voice `9001`, Android/ADB, 주요 env를 PASS/BLOCKED로 기록
- [ ] `server-v2` evidence trace template 작성 → 검증: `/detect/v2`, `/reports/v2`, `/reports/{id}`, `/admin`, status patch, upload URL, cleanup 항목이 report ID 기준으로 연결됨
- [ ] 실제 adapter 가능 시 `server-v2` smoke 실행 → 검증: `model_key`, `source_model`, `threshold_used`, bbox, `source=server`, `/reports/v2` 저장, `/admin` 조회 기록
- [ ] adapter 미완료 시 safe alternative 수행 → 검증: fake contract fixture로 schema/report/admin trace만 확인하고 모델 성능 PASS로 쓰지 않음
- [ ] PostGIS 가능 시 v2 포함 backend no-skip 테스트 실행 → 검증: `test_reports.py`, `test_reports_v2.py` skip 없이 PASS 또는 차단 사유 기록
- [ ] `/admin` v2 운영 흐름 확인 → 검증: 목록/상세/이미지/source/v2 metadata/review flags/status `new -> reviewed -> resolved`
- [ ] Android 목걸이 smoke 가능 시 실행 → 검증: 기기명, Chrome, 접속 방식, 권한, 카메라 각도, GPS/heading, TTS/진동, `fake-v2`/`server-v2` 분리
- [ ] voice `신고해` v2 흐름 확인 → 검증: `create_report`가 `server-v2`에서 `/reports/v2 trigger=voice`로 연결되는지 기록
- [ ] 데모/보고 evidence registry 갱신 → 검증: 날짜, 근거 파일, 검증 등급, 재사용 가능 범위, 한계가 표로 정리됨

## 리스크/확인 필요
- `/detect/v2`는 현재 fake contract다. 실제 YOLO26s/COCO adapter 전까지 `server-v2` 결과를 모델 성능 근거로 쓰면 안 된다.
- Docker/PostGIS, loopback HTTP, Android/ADB가 막힐 수 있다. safe alternative는 fixture, dry-run trace, no-DB schema 확인이다.
- reviewed YOLO26s 학습은 2026-05-23 기준 진행 중 기록이다. 완료/최종 성능으로 표현하지 않는다.
- `tactile_damage_area`는 오탐/미탐 리스크가 커서 per-class threshold와 검수 큐가 필요하다.
- 브랜치가 upstream보다 8커밋 뒤처지고 미추적 산출물이 많다. 새 PR/commit 계획 전 `git status`와 범위 통제가 필요하다.
- 운영 DB, secret, AWS/S3, Kakao Map 실제 key, 지자체 API, 외부 배포, 공개 데이터셋은 자동 실행 계획에서 제외한다.

## 병렬 에이전트 활용 메모
- 병렬 에이전트 2개를 사용했다.
- Agent 1: product/scheduler/PM 산출물과 `plans/daily/2026-05-24.md` 존재 여부 확인. 결론은 feature/verify/PM 산출물 없음, 5/24 계획 파일 없음.
- Agent 2: 최근 daylog/docs/current/v2/integration 근거 확인. 결론은 v2 계약 구현은 전진했지만 실제 adapter, PostGIS, 실폰 field, voice E2E는 미완료.
- 통합 결론: 2026-05-24는 기존 blocked 항목 반복보다 `server-v2 실제 모델 연결을 받을 수 있는 report/admin evidence trace`를 우선 신규 slice로 잡는다.