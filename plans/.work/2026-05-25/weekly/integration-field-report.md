# Hanium Dreamup / WalkSafe Assist - Integration/Field Test/Report weekly lane note (2026-W22)

## 이번 주 목표 후보

- W22 기준선 고정: `plans/weekly/2026-W22.md`는 확인되지 않았고 `plans/weekly/2026-W21.md`만 있다. 이번 note는 W22 주간 통합표를 직접 수정하지 않는 lane 입력이다.
- Source-of-truth 분리: 원본 checkout은 `behind 10`/dirty 상태로 기록되어 있고, clean worktree `0ee4be0`과 PM feature worktree commit `82ec532`이 따로 존재한다. 이번 주 integration 근거는 어떤 worktree/commit 기준인지 먼저 고정한다.
- 5/25 PM feature batch 검증 복구: GeoJSON export/admin URL, offline navigation cue fixture, motion ROI fixture/helper, voice intent schema endpoint는 “검증 대기” 상태다. verify는 sandbox mount quota 오류로 `blocked`, `push_ready=false`이므로 완료/배포 근거로 쓰지 않는다.
- 핵심 integration spine 확정: Stage1 `server-v2` 후보 기준 `detect/v2 -> reports/v2 -> admin/status -> CSV/JSON/GeoJSON export -> cleanup`을 같은 report ID로 연결한다.
- 실폰 없는 데모/보고 runbook 작성: mock route guide, Stage1 damage payload, admin export, public agency manual export 흐름을 하나의 시연 기준으로 묶되 Device E2E/field 근거와 분리한다.
- Android 목걸이 controlled smoke 준비/실행: 장비가 있으면 ADB reverse, 카메라 각도, GPS/heading, TTS/진동, `fake-v2`/`server-v2` source 분리를 기록한다. 장비가 없으면 BLOCKED와 fixture 대안을 남긴다.
- 공공기관 제출 기준 정리: 직접 자동 제출은 제외하고, `reviewed` 후보를 CSV/JSON/GeoJSON으로 수동 제출 준비하는 kit와 검수 기준을 만든다.
- Evidence registry 갱신: fake/mock/fixture/headless/model metric/Device E2E/field를 분리하고, Stage1 image smoke와 TMAP timing smoke를 실제 보행 안전 성능으로 과대해석하지 않는다.

## 날짜별/단계별 체크리스트

- 2026-05-25 월: W22 baseline 수집
  - `product/*`, `docs/current_status.md`, `docs/walksafe-v2/*`, 최근 `daylog`, PM 5/25 feature/verify/product-audit를 근거로 통합 상태를 정리한다.
  - 검증: W22 weekly 부재, PM 5/25 verify blocked, product audit 91/100, 5/24 automation metrics `push_ready=0` 기록.

- 2026-05-26 화: source/worktree/runtime gate
  - 원본 checkout, clean worktree, PM feature worktree 기준을 표로 분리한다.
  - PM feature batch marker와 commit `82ec532` 기준 검증 재시도 조건을 정한다.
  - 검증: `git status`, ahead/behind, PM marker, backend/web/voice/PostGIS/Android/TMAP env PASS/BLOCKED 표.

- 2026-05-27 수: report ID evidence trace
  - Stage1 detection payload 1건을 `/reports/v2` 저장, status patch, CSV/JSON/GeoJSON export, cleanup까지 연결한다.
  - DB/HTTP가 막히면 ASGI/direct handler 또는 serializer fixture로 PARTIAL만 남긴다.
  - 검증: `damaged_tactile_block` 저장, `tactile_damage_area`/normal/general 저장 제외, export 필드와 report ID 대조.

- 2026-05-28 목: 데모/보고 runbook thin slice
  - mock navigation cue, offline timing fixture, public agency export kit, voice `create_report` fixture를 하나의 데모 시나리오로 묶는다.
  - 검증: route cue `10초 뒤/곧/지금`, 위험 TTS 우선순위, manual export 흐름, 외부 민원 자동 POST 없음.

- 2026-05-29 금: Device/field gate
  - Android 기기 접근 가능 시 목걸이 착용 controlled smoke를 실행한다.
  - 가능하면 TalkBack/PWA install/offline/실폰 mic도 같은 표에 기록하되, 실행하지 못한 항목은 BLOCKED로 둔다.
  - 검증: 기기명, Chrome, ADB reverse/LAN, 권한, 카메라 각도, GPS accuracy, heading, TTS/진동, source 분리.

- 2026-05-30 토: evidence registry와 리허설
  - fake/server/model/voice/navigation/export evidence를 한 표로 정리하고 데모 리허설 순서를 확정한다.
  - 검증: 각 근거의 재사용 가능 범위와 한계가 명시됨. Stage1 smoke를 field accuracy로 쓰지 않음.

- 2026-05-31 일: 주간 마감/다음 주 이관
  - PASS/PARTIAL/BLOCKED, safe alternative, C 작업을 분리해 다음 주 입력으로 넘긴다.
  - 검증: execution/daylog 작성 대상은 실제 수정·검증이 있었던 경우로 한정하고, integration lane 요약은 단일 통합자가 정리.

## 검증 계획

- Static/fixture:
  - `node apps/web/scripts/check-navigation-guidance-fixture.mjs`
  - `node apps/web/scripts/check-motion-roi-fixture.mjs`
  - `bash scripts/check_frontend_navigation_guidance_policy_20260524.sh`
  - `bash scripts/check_frontend_risk_evaluator_policy_20260523.sh`

- Backend/API:
  - `python -m pytest backend/tests/test_reports_v2.py backend/tests/test_detect_v2.py backend/tests/test_navigation_routes.py -q`
  - 가능 시 `backend/tests/test_reports.py backend/tests/test_reports_export.py -q -rs`
  - `scripts/check_detect_report_export_trace_20260524.py`로 report ID trace 재확인.

- Model integration:
  - Stage1 `/detect/v2` image smoke는 저장 이미지 기반 ASGI 근거로만 사용한다.
  - threshold `0.50/0.60/0.75` 검토는 기존 saved prediction/eval 기반으로 정리하고 새 full training은 하지 않는다.

- Device E2E:
  - `docs/neck_worn_phone_test_checklist.md` 기준으로만 PASS 처리한다.
  - headless, fixture, ASGI, local sample 결과를 Android 목걸이 field PASS로 대체하지 않는다.

- Report/evidence:
  - 모든 결과에 `Static`, `Integration`, `Headless E2E`, `Device E2E`, `Model Eval`, `GIS/Ops` 등급을 붙인다.
  - 실행하지 않은 검증은 `BLOCKED` 또는 `PENDING`으로 남긴다.

## 리스크/확인 필요

- PM 5/25 verify는 quota 오류로 blocked다. `82ec532`은 검증 대기 기능 묶음이지 push-ready 산출물이 아니다.
- 원본 checkout dirty/behind와 clean worktree/PM worktree가 섞이면 근거가 오염된다. 주간 실행 전 기준 worktree를 확정해야 한다.
- PostGIS/Docker/local TCP가 없으면 reports runtime, duplicate/radius, cleanup PASS를 쓸 수 없다. 대안은 serializer fixture와 ASGI direct trace다.
- Android/ADB/TalkBack/실폰 mic가 없으면 Device E2E는 BLOCKED다. 대안은 정적 접근성 점검, mock route, voice fixture다.
- TMAP live smoke는 이미 성공 근거가 있으나 appKey와 원본 응답 장기 저장은 약관/secret 이슈가 있다. mock/offline timing fixture를 기본 대안으로 둔다.
- 공공기관 자동 제출, 운영 DB, secret, AWS/S3, Cloud STT/TTS, 외부 배포, 공개 데이터셋, destructive cleanup은 C 작업으로 보류한다.
- Stage1 YOLO26s `best.pt`는 MVP/backend integration 후보지만 실폰 latency, field accuracy, PDF의 경보 지연 1초 달성 근거가 아니다.

## 병렬 에이전트 활용 메모

- 이 lane note 작성에는 별도 하위 에이전트를 쓰지 않았고, 문서/daylog/PM 산출물은 병렬 조회 후 단일 판단으로 통합했다.
- 실제 주간 실행은 병렬화 권장:
  - Runtime Gate 담당: worktree/env/포트/장비/DB 상태 분리.
  - Backend Export 담당: report trace, GeoJSON export, public agency export kit.
  - PWA/Field 담당: navigation/motion fixture, 접근성 정적 점검, Android 목걸이 smoke.
  - Voice 담당: intent schema, `create_report` fixture, TTS/fallback 근거.
  - Model Evidence 담당: Stage1 payload, threshold review, evidence registry 입력.
- 최종 PASS/BLOCKED 판정과 daylog/주간 통합은 Integration 담당자가 한 번에 병합한다.