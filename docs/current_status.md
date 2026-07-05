# WalkSafe Assist 현재 상태

- 기준일: 2026-07-02 KST
- 범위: 현재 로컬 작업트리의 Android native 앱, backend/admin, Web/PWA demo, model/data, voice 문서 기준 요약

## 한 줄 요약

WalkSafe Assist의 주 경로는 이제 **Android native ARCore/TFLite APK**다. APK는 빌드되고 정지 실기기 install/launch/crash-free smoke까지 통과했으며, ARCore preview/depth snapshot, TFLite unified-primary detector 계약, debug bbox overlay가 연결되어 있다. 다음 gate는 기능 추가가 아니라 **bbox overlay와 ARCore depth가 같은 객체를 가리키는지 이동/현장 실기기에서 정합 확인**하는 것이다.

## 현재 구현 상태

| 영역 | 현재 상태 | 남은 일 |
|---|---|---|
| Android native | `apps/android` debug APK 빌드 가능. ARCore camera preview, Raw/Full Depth snapshot, TFLite detector, object depth pipeline, developer bbox overlay, source-age/frame-delta stale guard, metadata-only capture log, debug-only frame capture gate, Device Gate 뒤 TTS/haptic 위험 안내, TTC 및 route-bearing motion context 보조 위험 경고, Android `/reports/v2` damage-only multipart upload client가 연결되어 있다. 6/1 latency fix의 legacy fallback은 COCO partial 결과 우선 publish/custom tactile interval 실행을 유지한다. 6/2 기준 runtime config는 `unified_walksafe` primary로 전환했고, unified asset이 없으면 legacy two-model fallback을 사용한다 | 실기기에서 preview/bbox/depth 좌표 정합, `N보` 거리 정확도, report upload DB E2E, 목걸이 착용 field 확인 |
| Android runtime config | `app/src/main/assets/model-config/two_model_runtime.json`이 asset path/input size/class order/COCO allowlist/threshold의 runtime source of truth. 현재 파일은 아직 `walksafe_unified_yolo26n_640_float32.tflite`/CPU 기본값이고, unified asset이 없으면 legacy two-model fallback을 사용한다 | YOLO26n 768 학습/export가 끝난 뒤 768 TFLite asset 경로와 입력 크기로 config를 갱신한다. 최종 asset 생성 전에는 존재하지 않는 768 파일명으로 바꾸지 않는다 |
| Web/PWA | `apps/web`는 fake/server/fake-v2/server-v2 demo, admin/ops 보조, 기존 policy 회귀용으로 유지. Service worker/PWA registration은 `NEXT_PUBLIC_WALKSAFE_PWA_ENABLED=true` opt-in이며 기본값은 off다 | 주 사용자 앱으로 더 밀지 않음. Android gate 이후 필요한 UI만 재사용 |
| Backend v2 | `/detect/v2/health`, `/detect/v2`, `/reports/v2`, `/reports/export`, `/navigation/walking`, `/navigation/destinations/search` 구현. `/detect/v2`는 fake 기본 + `yolo`/`real` lazy provider 구조. `/reports/v2`는 `source=android`와 Android depth/report metadata allowlist를 지원하고, coordinate gate pending/non-pass는 성능 집계 제외로 보강한다 | Android upload code path는 붙어 있으나 bbox/depth Device evidence와 PostGIS no-skip 검증 필요. 운영 DB/배포/외부 제출은 별도 승인 필요 |
| Backend Android debug | `/android/debug/depth-logs`, `/android/debug/depth-logs/recent`, `/android/debug/frame-captures` 구현. 기본 비활성, local/dev metadata-only/developer opt-in 저장 | `ANDROID_DEBUG_LOG_ENABLED=true`로 local/dev에서만 사용. Device PASS나 report evidence로 확대 금지 |
| Admin/Ops | v2 `model_key`/`trigger`/`auto_reported`/`source=android` 필터, CSV/JSON/GeoJSON export, fake/demo 분리 정책, Android source summary/cluster count, reviewed/exclude_fake/redacted damaged tactile internal export preset | auth/RBAC/audit/live DB 검증은 별도 운영 승인 후 보강 |
| Model/static data | 최종 13클래스 PNG 통합본 `datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/data.yaml` 준비 완료. 이미지 196,506장, bbox 607,814개, `e_scooter_obstruction` 19,758 boxes. 모바일 본명 학습 후보는 YOLO26n 입력 768로 최신화했고, 2026-07-01 strict 768 e300 run이 로컬 산출물로 진행 중이다 | 학습 완료/검증 전 최종 metric으로 쓰지 않는다. 로그상 일부 truncated image warning이 있어 데이터 품질 재점검 필요. 학습 후 TFLite INT8/FP16 export와 Android 실기기 FPS/depth 정합 검증 필요 |
| Voice/STT/TTS | local STT/TTS와 PWA voice intent 프로토타입/테스트 존재. Android native에는 `SpeechRecognizer` 기반 "신고해줘" 음성 신고 버튼 경로가 explicit report upload/TTS 정책에 연결되어 있다 | 실폰 mic/TTS 청취/TalkBack과 보행 중 인식률은 후속 Device evidence |
| Navigation | TMAP proxy와 PWA guidance policy 초안 존재 | Android bbox/depth/report gate 이후 후순위로 재사용 검토 |

## Android APK 기준

```text
APK: apps/android/app/build/outputs/apk/debug/app-debug.apk
SHA-256: 1f5b2020053d755e6f52054453c82bd2eb45d3bd6ade3740a8d9bfa2f35967c9
```

검증 완료 범위:

- `python scripts/check_android_tflite_contract_20260531.py` → PASS, `check_scope=static_contract_only`, `unified_asset_present=false`, `expected_runtime_model=legacy_two_model`, `expected_fallback=true`
- `python scripts/check_android_depth_scaffold_20260531.py` → PASS
- `python scripts/export_android_tflite_models_20260531.py --dry-run` → PASS
- `cd apps/android && ./gradlew test assembleDebug --no-daemon` → BUILD SUCCESSFUL
- `PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -m pytest -p no:cacheprovider backend/tests/test_report_policy.py backend/tests/test_android_debug_logs.py backend/tests/test_reports_v2.py backend/tests/test_navigation_routes.py -q` → 24 passed, 32 skipped(PostGIS 미기동)
- `cd apps/web && npm run typecheck` → PASS
- `cd apps/web && npm run lint` → PASS
- `bash scripts/check_frontend_admin_report_summary_policy_20260525.sh` → PASS
- `bash scripts/check_frontend_walksafe_test_log_policy_20260701.sh` → PASS
- `PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -m pytest -p no:cacheprovider tests/test_aihub189_depthprediction_offline.py -q` → 5 passed, 8 warnings
- `scripts/evaluate_aihub189_depthprediction_offline.py --full-original-zip-run` → AIHub189 `Depth_001~005.zip` original full offline run completed, frames/evaluated 2461, buckets stop 883 / warning 652 / ignore 926, `source_kind=offline_zed_reference`, `arcore_pass=false`, `detector_input_kind=generated_probe_bbox`
- `MetadataCaptureLogTest` → Gradle unit test에 포함
- `adb -s R3CN50F4APH install/start/dumpsys/logcat` → install Success, launch Status ok/COLD/TotalTime 480ms, PID 16571, `MainActivity` RESUMED/visible/reportedDrawn, AndroidRuntime:E empty

실기기 관련 제한:

- 현재 수신된 `logs/android_remote_debug_20260601` 로그 363건은 최신 `1f5b2020...` APK 이전 로그다.
- 이전 로그에서는 `transform_path=identity`/`arcore_mapper_not_connected` 363/363, frame timestamp gap 2~3초대가 보여 depth와 객체가 어긋날 가능성이 컸다.
- 이번 실기기 검증은 정지 install/launch/crash-free smoke만 수행했다. bbox/depth/`N보`/route/TTS/haptic 체감은 아직 PASS로 문서화하지 않았다.

## v2 detection/report 계약

- `GET /detect/v2/health`
  - v2 provider mode, model path, runtime config readiness를 확인한다.
  - `fake` mode는 모델 path 없이 ready다.
  - `yolo`/`real` mode는 path/config readiness를 보여주며, 실제 모델 로딩은 첫 `/detect/v2` 요청 시점까지 지연된다.
- `POST /detect/v2`
  - backend 기본값은 fake contract 응답이다.
  - `DETECT_V2_MODE=yolo` 또는 `real`에서 `DETECT_V2_UNIFIED_MODEL_PATH`가 있으면 unified 단일 모델을 우선 사용하고, 없으면 custom tactile+COCO legacy pair가 모두 있을 때 fallback provider를 사용한다.
  - Android 주 경로에서는 local TFLite detector가 primary runtime이며, backend `/detect/v2`는 운영/API 호환 경로다.
- `POST /reports/v2`
  - `unified_walksafe` 또는 legacy `custom_tactile`의 `damaged_tactile_block`만 허용한다.
  - `tactile_damage_area`, `normal_tactile_block`, COCO/general 객체와 unified의 일반 객체는 거부한다.
  - Android native 자동 업로드 코드는 Device Gate 뒤에 연결되어 있고 `source=android`로 저장된다. client multipart 전송 경로와 metadata/image part는 JVM 테스트로 확인했다. Device evidence와 PostGIS no-skip 검증 전까지 field/DB E2E 완료 주장 금지.
- `GET /reports/export`
  - 관리자/운영자가 현재 필터 조건의 신고 목록을 CSV 기본, JSON/GeoJSON 옵션으로 내보낸다.
  - 기관 자동 전송이나 제출 기능이 아니라 운영자 내부 검수/다운로드용이다.

## 사용자 안내/신고 정책

- 손상 점자블록(`damaged_tactile_block`): 자동/음성 요청 신고 대상이다. 자동 신고만 있을 때 사용자 TTS는 기본으로 내보내지 않는다.
- 손상 영역(`tactile_damage_area`): 보조 bbox 정보이며 신고 기준으로 쓰지 않는다.
- 일반 객체: 신고 대상이 아니다. 장애물, 접근 객체, 경로 차단 등 보행 위험 상황일 때만 음성 경고 대상이다.
- 공공기관 제출: 제품 범위에 넣지 않는다. 내부 검수와 `/reports/export` 다운로드만 제공한다.
- 서버 장애/fake fallback: 실사용 `server-v2`에서는 fake-v2로 자동 전환하지 않는다. fake-v2는 데모 전용이다.
- STT/음성: Android native `SpeechRecognizer` 음성 신고 버튼은 `create_report` 정책 의미로 연결되어 있다. 실폰 mic/TTS 청취와 보행 중 인식률은 아직 PASS가 아니다.

## 다음 실행 gap

1. unified TFLite asset 투입 확인
   - 학습/export가 끝나면 우선 `walksafe_unified_aihub183_png_yolo26n_768_*` 계열 TFLite asset을 생성한다.
   - asset 생성 후 `two_model_runtime.json`의 unified asset/input size를 768 기준으로 바꾸고 `detector_completed_models=[unified_walksafe]`가 찍히는지 확인한다.
2. Android overlay 좌표 정합 확인
   - 사람/점자블록/일반 물체를 화면 중앙·좌우·상하에 두고 bbox 위치가 실제 객체와 대략 맞는지 확인한다.
   - 검증 전까지 headless/PWA 결과를 Android Device PASS로 쓰지 않는다.
3. ARCore depth sampling 정합 확인
   - best depth bbox와 sample count/median이 같은 객체 이동에 따라 일관되게 변하는지 확인한다.
   - `1보` 문구는 실측 거리/RGB-D 근거 전까지 정확도 PASS로 쓰지 않는다.
4. Coordinate mapper 보강/검증 여부 결정
   - ARCore image/display/depth transform mapper 우선 경로는 연결되어 있다. 실기기에서 overlay/depth가 밀리면 transform 보정과 fallback 조건을 추가 검증한다.
5. Metadata-only capture log 확인
   - 이미지/깊이 파일 저장 없이 frame timestamp, detection source age/frame delta, bbox, confidence, depth median/p20/risk distance/sample count, preview/depth size, transform path/fallback reason만 남기는 debug ring buffer를 연결했다. 실기기에서 표시 품질은 확인 필요하다.
6. Android/backend threshold 결정
   - Android TFLite JSON threshold와 backend Stage1 config threshold 차이를 의도적 분리로 둘지 맞출지 결정한다.
7. Reviewed static dataset 복구 후 full metric rerun
   - 현재 로컬에는 reviewed tactile3 원본 image/GT dataset이 없어 full static metric rerun은 blocked다.

## 모델/데이터 상태

- custom tactile 후보: YOLO26s 3-class
  - `normal_tactile_block`
  - `damaged_tactile_block`
  - `tactile_damage_area`
- unified 단일 모델 후보: YOLO26n 13-class, 모바일 본명 입력 크기 768
  - `person`, `bicycle`, `car`, `motorcycle`, `bus`, `truck`, `traffic light`
  - `normal_tactile_block`, `damaged_tactile_block`, `crosswalk`, `curb_step`, `uneven_sidewalk`, `e_scooter_obstruction`
  - `bench`는 unified에서 제외한다. legacy COCO fallback의 `bench`는 호환용으로 유지할 수 있다.
- AIHub source plan: `docs/model_unified_13class_aihub_sources_20260602.md`
  - 1순위 신규 후보는 AIHub 186(베리어프리존)과 AIHub 189(인도보행 영상)이다.
  - AIHub 513은 현재 builder가 직접 지원하는 local/보조 source로 유지한다.
  - `e_scooter_obstruction`은 AIHub 189/614 후보에서 PM을 obstruction으로 재라벨하거나 직접 촬영/수동 라벨링이 필요하다.
- 2026-05-23 KST 기준 reviewed YOLO26s 학습 파이프라인은 완료됐고, MVP/backend integration 후보는 Stage1 `best.pt`였다.
- 기존 문서상 reviewed dataset build/materialized rows 기록은 남아 있으나, 2026-05-31 현재 로컬 작업트리에는 full static rerun에 필요한 reviewed tactile3 원본 image/GT dataset이 없다.
- 현재 즉시 사용 가능한 근거:
  - saved Stage1 prediction labels
  - saved image-level presence summary
  - backend PT model artifacts
  - Android TFLite assets
- 모델 weight, run 산출물, materialized dataset images/labels, `.tflite` asset은 GitHub에 올리지 않는다.

## 문서 기준

- 전체 문서 지도: `docs/README.md`
- Android 설치/상태: `apps/android/README.md`
- Android depth/TFLite 구조: `docs/android/arcore_depth_estimation_architecture.md`
- Android 과거 gate 계획 snapshot: `plans/features/2026-06-01_android_native_gate_execution_plan.md` (최신 기준은 이 문서와 Android README 우선)
- Android 실기기 체크리스트: `docs/android/android_device_overlay_depth_checklist_20260601.md`
- Android metadata schema: `docs/android/android_metadata_capture_schema_20260601.md`
- Android server debug log runbook: `docs/android/android_server_debug_log_runbook_20260601.md`
- Android RGB-D dataset schema: `docs/android/arcore_rgbd_dataset_schema_20260601.md`
- Android static threshold evidence: `docs/evidence/android_static_threshold_evidence_20260601.md`
- Android report source/metadata draft: `docs/walksafe-v2/android_report_source_metadata_decision_20260601.md`
- Android 실행 기록: `docs/execution/2026-05-31_android_static_dataset_contract_progress.md`
- v2 source of truth: `docs/walksafe-v2/README.md`
- v2 API 계약: `docs/walksafe-v2/backend_api_contract.md`
- 신고 운영: `docs/report_operations.md`
- `docs/execution/`은 과거 실행 기록 성격이 강하며, 최신 구현 기준은 위 문서들을 우선한다.
