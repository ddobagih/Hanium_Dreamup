# Evidence Feature Matrix

- 기준 문서: `plans/features/2026-05-26_feature_followup_implementation_list.md`
- 기준 항목 수: 240개 (`#1`~`#20` 211개, 공통 29개)
- 목적: 구현 완료를 주장하기 전에 필요한 증거 등급과 산출물을 기능 단위로 고정한다.
- 주의: `부분 PASS`는 local/mock/static/headless/integration 등 명시된 등급만 통과했다는 뜻이다. Device/Release/운영/외부 연동 완료로 확대하지 않는다.


## 2026-07-10 플랫폼 역할 보정

- 주 사용자 앱 경로는 Web/PWA다. Web/PWA의 브라우저·실폰·Release evidence를 제품 완료 기준으로 관리한다.
- Android native는 ARCore/depth/TFLite 실험·검증 보조 경로다. 아래 Android addendum은 해당 모듈의 Device evidence에만 적용된다.

## 2026-07-16 비계량 카메라 보조 경고 capability

| Tier | 적용 범위 | 허용 기능 | 실패 시 |
|---|---|---|---|
| `ARCORE_METRIC` | ARCore·Depth 지원 Android | 기존 metric 위험 판단과 route-bound tactile 연구 경로 | metric/tactile gate의 기존 TMAP fail-close |
| `CAMERA_NON_METRIC_ADVISORY` | Web/PWA | 후면 camera·detector·활성 TMAP·foreground/visibility/freshness gate의 low 좌·중앙(정면)·우 보조 경고. IMU 안정도는 제공될 때 추가 gate | `TMAP_ONLY` |
| `CAMERA_IMU_NON_METRIC` | ARCore 미지원 Android 제한 모드 | CameraX·detector·fresh IMU·활성 TMAP gate의 low 좌·가운데·우 보조 경고 | `TMAP_ONLY` |
| `TMAP_ONLY` | camera/detector/freshness 또는 해당 플랫폼 필수 gate 불충족 | TMAP 전역 경로·안내만 유지 | 비계량 local action 없음 |

- 비계량 보조 경고는 같은 후보의 서로 다른 연속 3프레임과 최소 700ms를 모두 요구하며, 전달 직전에 tier·lifecycle·freshness·platform gate를 다시 확인한다. queued action이 만료되거나 gate가 깨지면 폐기한다.
- `normal_tactile_block`은 low 안내가 가능하다. `damaged_tactile_block`과 `tactile_damage_area`는 제외하며 기존 별도 report-only 경계를 유지한다.
- 이 경로는 거리·보폭·STOP/high·local steering·안전 경로·경로 변경·자동 신고의 근거가 아니다. Android 제한 모드는 reports=false다. Web의 기존 stable bbox/ROI 위험 경고와 명시 동의 server-v2 damaged-report 파이프라인은 유지한다.
- 근거 등급은 CODE·AUTO다. Web 실제 폰과 ARCore 미지원 Android 실기기 Field는 모두 `UNVERIFIED/OPEN`이며 Release 완료로 주장하지 않는다.

## 2026-05-31 Android native addendum

- 이 APK는 Android 실험·검증 모듈의 기준 artifact다.
- 기준 APK: `apps/android/app/build/outputs/apk/debug/app-debug.apk`
- APK SHA-256: `1f5b2020053d755e6f52054453c82bd2eb45d3bd6ade3740a8d9bfa2f35967c9`
- Static/Unit/Build PASS: Android JSON runtime config, TFLite contract check, depth scaffold check, Gradle test/build.
- Device 미검증: bbox overlay 정합, ARCore depth sample이 같은 객체를 가리키는지, 실측 거리 기반 `N보` 정확도.
- Web/PWA/headless/voice 결과는 Android Device evidence를 대체하지 않고, Android Device evidence도 Web/PWA 브라우저·Release evidence를 대체하지 않는다.

## 2026-06-01 Android gate evidence addendum

- 신규 gate 계획: `plans/features/2026-06-01_android_native_gate_execution_plan.md`
- 실기기 관찰 양식: `docs/android/android_device_overlay_depth_checklist_20260601.md`
- metadata-only schema: `docs/android/android_metadata_capture_schema_20260601.md`
- server debug log runbook: `docs/android/android_server_debug_log_runbook_20260601.md`
- RGB-D dataset schema: `docs/android/arcore_rgbd_dataset_schema_20260601.md`
- static threshold 근거: `docs/evidence/android_static_threshold_evidence_20260601.md`
- report upload 전 source/metadata 결정 초안: `docs/walksafe-v2/android_report_source_metadata_decision_20260601.md`
- ARCore API 확인: 현재 Android 의존성은 `com.google.ar:core:1.54.0`이고 `Coordinates2d`에는 `IMAGE_PIXELS`, `TEXTURE_NORMALIZED`, `VIEW`가 있다. depth 전용 enum은 없어 depth mapper는 `IMAGE_PIXELS -> TEXTURE_NORMALIZED -> depth pixel` 경로를 후보로 둔다.
- Device 미검증 상태는 유지한다. 위 문서들은 gate와 evidence 양식을 고정한 것이며, overlay/depth/`N보` 실기기 PASS를 의미하지 않는다.
- Server debug log upload 성공도 Device PASS가 아니다. local/dev metadata-only 원인 분석 근거로만 사용한다.


## 2026-06-02 unified-primary addendum

- Android/backend/model 문서의 현재 detector 기준은 `unified_walksafe` 13-class primary + legacy custom tactile/COCO fallback이다.
- `bench`는 unified class order에서 제외했고, legacy COCO fallback allowlist에서만 호환용으로 남을 수 있다.
- `/reports/v2` 저장 허용 대상은 `unified_walksafe` 또는 legacy `custom_tactile`의 `damaged_tactile_block`뿐이다.
- unified Android TFLite asset이 아직 없으면 APK는 legacy fallback으로 내려갈 수 있으므로, unified 성능/latency PASS로 쓰지 않는다.

## 2026-07-01 documentation/code consistency addendum

- Android `/reports/v2` upload code path and `source=android` backend/admin/export support are now present.
- TTS/haptic/report upload remains gated by startup/fresh metric depth/stale checks, and route-bearing alignment is passed into Android depth `MotionContext` for conservative risk confidence. Code-connected does not equal Android Device PASS.
- Current APK hash for documentation source-of-truth is `1f5b2020053d755e6f52054453c82bd2eb45d3bd6ade3740a8d9bfa2f35967c9`.
- Mobile-first unified training target is YOLO26n `imgsz=768`; Android config remains on the 640 unified asset path until an actual 768 TFLite artifact exists.

## 상태/등급 원칙

| 상태 | 의미 | 필수 기록 |
|---|---|---|
| `PASS` | 해당 등급의 검증을 실제 실행했고 기대 결과와 실제 결과가 일치 | 실행 명령, 날짜, 환경, 로그/스크린샷/리포트 경로 |
| `BLOCKED` | 실행하려 했지만 환경/권한/secret/기기/비용/장시간 작업 등으로 막힘 | blocker 원인, 재개 조건, 과대 주장 금지 문구 |
| `미검증` | 아직 실행하지 않음 | 예정 검증 등급, 필요한 fixture/env, 담당 범위 |

| 등급 | 완료로 인정되는 근거 | 과대 주장 금지 |
|---|---|---|
| `Static` | 문서/설정/코드 경로/스키마 정적 확인, grep, link check | 런타임 동작 PASS로 쓰지 않음 |
| `Unit` | 단위 테스트, parser/schema/policy fixture | 브라우저/서버 통합 PASS로 쓰지 않음 |
| `Integration` | API/DB/서버 contract smoke, disposable DB | 실기기/GUI/운영 DB PASS로 쓰지 않음 |
| `Headless` | 브라우저/ASGI/fixture 기반 E2E | Android 착용형/센서 field PASS로 쓰지 않음 |
| `Device` | 실폰 GUI, 카메라, GPS/heading, mic, TTS/진동, TalkBack 기록 | headless나 local sample로 대체하지 않음 |
| `Model` | dataset/split/metric/hash/latency/failure review | fake/demo 또는 단일 class 결과를 전체 모델 성능으로 쓰지 않음 |
| `GIS/Ops` | PostGIS/GeoJSON/cluster/export/status workflow 운영 검증 | 외부 기관 자동 API 제출/운영 배포로 쓰지 않음. 관리자 CSV 수동 신고는 별도 운영 검증 |
| `Release` | 승인된 env/secret/domain/storage/rollback/release artifact | 승인 없는 배포/외부 전송/비용 발생 금지 |

## 240개 항목 커버리지 매트릭스

| Ref | 기능/영역 | 항목 수 | 최소 evidence 등급 | PASS에 필요한 대표 산출물 | 현재 상태 |
|---|---|---:|---|---|---|
| #1 | 목적지 이름 → 좌표 검색/geocoding | 12 (P0 5/P1 5/P2 2) | Static, Unit, Integration, Headless | API 계약 대조, TMAP mock/local provider pytest, health ASGI, search debounce/cancel fixture | Unit/Headless 부분 PASS: search health/mock provider/origin 거리/API 문서/debounce/cancel/거리 표시. live provider 반복 호출은 dry-run |
| #2 | TMAP 검색 후보 선택 | 8 (P0 3/P1 4/P2 1) | Unit, Headless, Static | 후보 선택 상태 동기화 test, 범위 초과 음성 거절 fixture, 후보 list ARIA/static check | Unit/Headless 부분 PASS: 후보 선택/범위 초과 차단/더보기/다시 검색/취소/접근성 static. 실폰 음성 E2E는 미검증 |
| #3 | 목적지 설정 후 길안내 시작 정책 | 9 (P0 4/P1 4/P2 1) | Unit, Headless, Integration | `requested_start` intent test, 목적지 선택만으로 route fetch 0회, start prerequisite matrix | Unit/Headless 부분 PASS: `requested_start`, stop UI/intent, start prerequisite/gate, route request 제한 |
| #4 | 경로 이탈 감지 | 9 (P0 3/P1 4/P2 2) | Unit, Headless, Device | off-route threshold fixture, GPS accuracy/route projection edge test, 실폰 GPS field는 별도 | Unit/Headless 부분 PASS: accuracy/projection/도착/진행률/GPS jump guard. 실폰 GPS field는 미검증 |
| #5 | 재탐색 상태/음성 안내 | 9 (P0 4/P1 3/P2 1/C 1) | Unit, Headless, Integration, Device | reroute intent/action 분리 test, route request gate script, 위험 TTS 우선순위 fixture | Unit/Headless 부분 PASS: 자동 재탐색 코드 경로, GPS 튐 제한, in-flight/cooldown/max-count gate. live request는 dry-run |
| #6 | 보폭 자동 측정 | 8 (P0 3/P1 3/P2 2) | Unit, Headless, Device | localStorage 예외 mock, confidence 상태 test, DeviceMotion permission fixture, 실기기 보행 로그 | 부분 PASS: confidence/outlier/TTL fixture, DeviceMotion permission UI/log schema/dry-run. 실기기 보행 로그는 미검증 |
| #7 | 실제 거리/깊이 기반 “약 N보 앞” 안내 | 11 (P0 4/P1 4/P2 2/C 1) | Static, Unit, Integration, Model, Device | `distance_m` schema/parser test, source/confidence fixture, 거리 없음 시 보폭 문구 금지, depth sensor field는 C-gate | Unit/Headless 부분 PASS: schema/parser/runtime/frontend source/confidence, browser depth capability gate. Android ARCore depth snapshot/overlay는 구현됐지만 bbox-depth 정합과 실측 `N보`는 미검증 |
| #8 | 위험 객체 방향 안내 | 8 (P0 2/P1 3/P2 3) | Unit, Headless, Device | 방향 경계 fixture, invalid bbox parser test, overlay/TTS 일관성 headless, camera orientation device 기록 | Unit/Headless 부분 PASS: 방향 경계/overflow bbox/하단·발밑 표현. Android overlay orientation/coordinate Device 정합은 미검증 |
| #9 | 위험 행동 안내 | 8 (P0 3/P1 4/P2 1) | Static, Unit, Headless | risk type/action matrix, `path_obstacle` phrase fixture, damage report-only 회귀 test | Unit/Headless 부분 PASS: risk/action fixture, high-risk action phrase, damage report-only, priority helper |
| #10 | IMU/ROI 기반 충돌 가능 영역 필터 | 10 (P0 2/P1 5/P2 2/C 1) | Unit, Headless, Device | ROI debug gate test, heading/speed unknown fixture, motion ROI runner, 실기기 calibration은 C-gate | Unit/Headless 부분 PASS: debug gate, heading/speed unknown safe fallback, motion shift, lower-half/center-band, non-blocking class 제외, motion ROI runner, sensor log schema. Android 실기기 bbox/depth calibration은 미검증 |
| #11 | bbox 변화 기반 접근 중 객체 판단 | 9 (P0 3/P1 4/P2 2) | Unit, Headless, Device | 객체 tracking key fixture, 모든 detection history test, stale reset test, camera jitter device 보강 | Unit/Headless 부분 PASS: tracking key, all-detection history, stale reset, TTC boundary, jitter/constant/decreasing negative. Android device jitter/track field는 미검증 |
| #12 | Stage1 detector payload → report → export trace | 15 (P0 6/P1 7/P2 2) | Integration, Headless, Model, GIS/Ops | `source=server` trace, detect→report→CSV/JSON/GeoJSON artifact, export deep field 대조, payload hash | 부분 PASS: dry-run policy/Pydantic 검증, report payload/image hash, trace artifact 옵션, CSV/JSON/GeoJSON deep field. 실제 Stage1 image→server trace는 DB/모델 환경 필요 |
| #13 | Admin GeoJSON export UI | 12 (P0 3/P1 7/P2 2) | Unit, Integration, Headless, GIS/Ops | export 링크 DOM test, JSON filename/header test, no-store header, GeoJSON properties snapshot | 부분 PASS: export link/policy, demo-aware filename/header, no-store, redacted/public export, manifest, GeoJSON props/grid aggregate. 실지도 UI는 미검증 |
| #14 | Admin 신고 지도/히트맵/클러스터 | 10 (P0 2/P1 6/P2 2) | Integration, Headless, GIS/Ops | backend cluster/summary endpoint test, list limit 문구/static check, grid/cluster DOM fixture | 부분 PASS: backend summary full-query, cluster bounds/status/source breakdown, Admin summary 전환/cluster radius filter. 외부 SDK 지도는 미구현 |
| #15 | fake/demo 신고 데이터 운영 분리 | 10 (P0 3/P1 5/P2 2) | Static, Unit, Integration, GIS/Ops | backend/frontend fake 판정 동일 fixture, demo badge DOM test, demo/source filter 조합 test | 부분 PASS: backend/frontend fake 판정 확장, `data_origin`/`runtime_mode`, `performance_excluded`, row/detail badge, export filename/header. 모델 성능 리포트 자동 제외는 미검증 |
| #16 | 신고 검수 상태 workflow | 15 (P0 4/P1 8/P2 3) | Unit, Integration, GIS/Ops | audit trail/history test, review note 저장, transition matrix, optimistic conflict test | 부분 PASS: transition guard/history/note/reason/conflict guard, Admin note/history 표시. bulk/assignee/submission batch는 미구현 |
| #17 | 긴급 연락처 설정 | 8 (P0 4/P1 3/C 1) | Static, Unit, Headless, Device | localStorage 저장 test, phone validation, clear/masking UI, guardian field 미전송 회귀 | 부분 PASS: 복수 연락처 local-only schema, validation/masking/no-send/tel-sms grep. 실제 전화·SMS는 제외 |
| #18 | 보호자/초기 설정 최소 UI | 17 (P0 7/P1 8/P2 2) | Static, Unit, Headless, Device | settings migration fixture, setup checklist DOM, viewport/skip link/static, live region policy, TalkBack 수동 기록 | Unit/Static 부분 PASS: schema v3 migration/checklist/DeviceMotion/PWA settings UI, viewport/skip/live/static/48px/reduced motion 일부. TalkBack device는 미검증 |
| #19 | TTS HTTP cache header 자동 확인 | 12 (P0 4/P1 7/P2 1) | Unit, Integration, Headless, Device | batch TTS smoke, `X-Voice-Cached=true` unit, media type/bytes check, fallback과 cache PASS 분리 | Unit/Integration-local 부분 PASS: batch script, cache header unit, media/bytes check, fallback=false, cache key inputs, max length, CORS/OPTIONS contract. 휴대폰 청취는 미검증 |
| #20 | unknown/저신뢰 명령용 NLU fallback | 11 (P0 4/P1 5/P2 1/C 1) | Unit, Integration, Headless | disabled fallback no-op, `should_execute=false`, 부정 명령/목적지-신고 충돌 fixture, LLM 연결은 C-gate | Unit/Integration-local 부분 PASS: unknown/low-confidence/safe no-op/intent schema/privacy telemetry. 실제 LLM/Cloud NLU는 사용자 지시로 제외 |
| 공통 | PWA/offline | 10 (P0 4/P1 5/P2 1) | Static, Headless, Device, Release | install/offline state mock, offline preflight, no-store audit, manifest/SW audit, PWA install/offline browser·실폰 field | Unit/Static 부분 PASS: install/update state hook, SW version/update banner, no-store/offline shell, 192/512 PNG icons, opt-in offline report queue TTL/용량/수동 재시도 fixture. PWA install/offline field는 미검증이며 Android native APK field와 별도 |
| 공통 | 개인정보/데이터 보존 | 10 (P0 4/P1 5/P2 1) | Static, Unit, Integration, GIS/Ops | raw payload allowlist test, metadata size limit, PII 미전송, no-store, retention dry-run, EXIF 제거 fixture | 부분 PASS: allowlist/size/no-store/redacted/local contact no-send, retention policy/dry-run, decodable image EXIF 재인코딩 fixture |
| 공통 | 접근성/evidence | 9 (P0 3/P1 4/P2 2) | Static, Headless, Device, Release | 이 matrix, PASS/BLOCKED/미검증 template, privacy checklist, 최종 보고 evidence index | Static PASS: matrix/template/checklist + local accessibility static. Device/Release는 미검증 |

## evidence 기록 위치 규칙

- 실행 로그/리포트: `docs/execution/YYYY-MM-DD_<area>_<scope>.md`
- 최종 제출용 index 후보: `docs/evidence/final_report_index.md`
- 기능별 PASS를 주장할 때는 위 matrix의 `Ref`와 원문 항목을 함께 적는다.
- 같은 결과를 재사용할 때는 “재사용 근거”로만 표시하고, 최신 변경 후 재실행이 아니면 최신 PASS로 쓰지 않는다.

## 2026-05-26 safe-slice evidence

- 실행 기록: `docs/execution/2026-05-26_feature_followup_safe_slice.md`
- Admin/Reports/Privacy 워커 추가 기록: `docs/execution/2026-05-26_admin_reports_privacy_evidence_worker.md`
- 최종 통합 검증 기록: `docs/execution/2026-05-26_feature_followup_final_verification.md`
- 최종 보고용 evidence index: `docs/evidence/final_report_index.md`
- 대표 PASS:
  - `backend/tests` → 87 passed
  - `tests model` → 85 passed
  - frontend policy scripts, settings/privacy/PWA/accessibility static check, `npm run typecheck`, `npm run lint`, `npm run build` → PASS
  - local voice contract smoke, detect→report→export trace, retention dry-run → PASS
- 제한:
  - 위 PASS는 local/mock/static/fixture 기준이다.
  - Web/PWA 브라우저·실폰·TalkBack·Release, live TMAP provider 호출, 운영 DB/배포/기관 자동 API 전송, 실제 전화/SMS, 실제 LLM/Cloud NLU, Android depth sensor session과 장시간 학습은 완료로 주장하지 않는다.

## badge 예시

| 올바른 표기 | 의미 |
|---|---|
| `[Unit PASS] #7 distance_m parser fixture, 2026-05-26, log: ...` | 단위 수준 PASS만 인정 |
| `[Headless PASS / Device 미검증] #12 source=server ASGI trace` | headless는 통과했지만 실폰 field는 아님 |
| `[Model PASS: class0 baseline only]` | metric 범위를 badge에 제한 |
| `[Release BLOCKED: secret/domain 승인 필요]` | 승인 전 배포 완료 주장 금지 |
| `[미검증]` | 실행 전 기본값 |
