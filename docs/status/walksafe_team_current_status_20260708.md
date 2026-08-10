# WalkSafe team current status - 2026-07-08

> Status: SNAPSHOT / PLATFORM SUPERSEDED. 이 문서는 2026-07-08 당시 Android-primary 가정의 팀 공유본이다. 2026-07-10 사용자 확인으로 Web/PWA가 주 앱이며 Android는 보조 연구 경로로 확정됐다. 최신 판정은 `implementation_audit_20260710.md`와 `current_status.md`를 우선한다.
> 아래 본문의 “현재”와 asset 부재·legacy 기대값은 모두 2026-07-08 당시 표현이며 현재 작업트리 설명이 아니다. 현행 unified img768 적용·실기기 instrumentation 결과로 이 snapshot을 부분 갱신하지 않는다.

작성 기준: 2026-07-08 KST, 현재 로컬 작업트리와 기존 검증 evidence 기준. 이번 작업에서 재실행한 검증은 문서 정합성/검색/diff 확인에 한정한다.

## 한 줄 요약

WalkSafe의 현재 주 경로는 **Android native ARCore/TFLite APK**다. Web/PWA는 demo/admin/support 경로로 유지하고, backend/admin/report는 운영 데이터 경로다. 최종 `unified_walksafe` TFLite, outdoor walking/route PASS, Android ARCore depth 실기기 정합 PASS는 아직 완료가 아니다.

## 현재 제품 방향

| 영역 | 현재 방향 | 팀 공유 시 주의 |
|---|---|---|
| 사용자 앱 | Android native ARCore/TFLite APK가 primary | PWA/headless 결과를 Android Device PASS로 말하지 않는다. |
| Web/PWA | fake/server/fake-v2/server-v2 demo, admin/ops support | 운영 사용자 앱이 아니라 API/admin 검증과 보조 화면이다. |
| Backend/Admin/Report | `/reports/v2`, `/reports/export`, admin 목록/요약/필터/export가 운영 source-of-truth | 공공기관 자동 제출 기능은 없다. 표현은 `운영자 내부 export`로 통일한다. |
| Model/runtime | Android config는 `unified_walksafe` primary + `legacy_two_model` legacy fallback 구조 | final/export-ready unified TFLite asset은 아직 없다. 실제 asset은 legacy custom/COCO 두 개만 확인된다. runtime 전환과 final model 완료를 분리한다. |
| Navigation/voice | backend 목적지 검색/보행 경로 API와 Android route/search/voice report 코드가 연결됨 | Android native voice는 신고 버튼 중심이다. 목적지 설정/후보 선택/길안내 시작 음성 UX, route walking, TTS/진동 체감 PASS는 후속이다. |
| 운영 서비스 | 로그인 필수 방향, report image/위치/신고자/시각 서버 저장, 6개월 보존 정책 | auth/RBAC/rate-limit/signed URL/CSRF/audit/secret/release approval은 출시 lane이다. |

## 구현/연결 확인 범위

| 영역 | 현재 구현/연결 상태 | 근거 | 한계 |
|---|---|---|---|
| Android native | ARCore camera preview, Raw/Full Depth snapshot, TFLite detector, object depth pipeline, debug bbox overlay, stale guard, metadata capture log, debug-only frame capture, 권한 분기, Device Gate 뒤 TTS/haptic/report 후보 생성이 연결되어 있다. | `apps/android/README.md`, `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt`, `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MetadataCaptureLog.kt`, `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/DebugBboxOverlayView.kt` | 정지 install/launch smoke만 Device evidence다. permission request flow, bbox/depth/`N보`/route/TTS/haptic 현장 PASS 아님. main manifest `INTERNET` 권한의 release 정책도 재검증 필요. |
| Android report upload | `AndroidReportUploader`가 `/reports/v2` multipart `metadata` + `image`를 보낸다. `AndroidReportCandidatePolicy`는 `damaged_tactile_block`, fresh metric depth, trusted GPS, 허용 model key, user id gate를 확인한다. | `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportUploader.kt`, `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportCandidatePolicy.kt`, `docs/operations/report_operations.md` | 실기기+DB E2E, PostGIS no-skip, field upload 품질은 미검증이다. |
| Android runtime config | `two_model_runtime.json`은 `primary_model=unified_walksafe`, `fallback_model=legacy_two_model`이다. unified asset이 없으면 legacy fallback을 로드한다. | `apps/android/app/src/main/assets/model-config/two_model_runtime.json`, `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/TwoModelRuntimeConfig.kt`, `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/TfliteAndroidFrameDetector.kt` | config의 unified asset은 `:missing` 상태다. 실제 `models/` asset은 `custom_tactile_yolo26s_float32.tflite`, `coco_yolo26n_float32.tflite`만 확인된다. 768 final TFLite로 바뀐 것이 아니다. |
| Backend API | `/detect/v2/health`, `/detect/v2`, `/reports/v2`, `/reports/export`, `/reports/summary`, `/navigation/walking`, `/navigation/destinations/search`가 구현되어 있다. | `backend/app/api/reports.py`, `backend/app/api/navigation.py`, `docs/walksafe-v2/backend_api_contract.md` | 운영 DB/배포/secret/release approval과 PostGIS 전체 no-skip 검증은 별도다. 기관 제출 기능은 범위 밖이다. |
| Report metadata/admin/export | `source=android`, `model_key`, `trigger`, `auto_reported`, Android depth/report metadata allowlist, CSV/JSON/GeoJSON export, redacted/manifest/grid 옵션이 `/reports/export`에 구현되어 있다. | `docs/operations/report_operations.md`, `docs/backend/api_reference.md`, `backend/app/api/reports.py` | 별도 `/admin/export` route가 아니라 `/reports/export`다. `reporter_user_id`와 gate status는 아직 서버 auth subject가 아니라 client metadata다. |
| Duplicate/cooldown | backend는 같은 class, 반경 `10m`, `captured_at` 전후 `1분` 기준 duplicate 후보를 찾고 저장 payload에 duplicate tag/ID를 남긴다. Android client cooldown은 짧은 반복 억제용이다. | `docs/walksafe-v2/policy_decisions_20260702.md`, `backend/app/services/duplicates.py`, `backend/app/api/reports.py` | backend duplicate는 저장 차단 cooldown이 아니다. 자동 merge/delete도 아니다. 실제 중복 판단은 운영자 검수 영역이다. |
| Navigation backend/search | `/navigation/destinations/search`와 `/navigation/walking`이 앱 전용 schema로 정규화된다. Android UI도 검색 결과 선택 후 경로 요청으로 이어진다. TMAP key는 backend env에만 둔다. mock provider가 있다. | `backend/app/api/navigation.py`, `backend/app/services/tmap_pedestrian.py`, `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/BackendWalkingRouteClient.kt`, `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt`, `docs/walksafe-v2/backend_api_contract.md` | Android `searchDestinations()` 단위 테스트는 별도 확인이 필요하다. 보행 중 route timing/off-route/도착 UX와 TMAP live 재검증은 후속이다. |
| Voice/TTS/haptic 정책 연결 | Android `SpeechRecognizer` 기반 voice report 버튼, explicit report TTS, STOP 상황 진동 정책이 연결되어 있다. | `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt`, `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt`, `docs/status/voice_stt_tts_status.md`, `docs/operations/report_operations.md` | native voice는 현재 신고 버튼 중심이다. 목적지 설정/후보 선택/길안내 시작 voice UX, 실폰 mic 인식률, TalkBack/TTS 청취, 보행 중 진동 체감은 PASS가 아니다. |
| AIHub189 offline evaluator | `Depth_001~005.zip` 원본 전체 2461 frame에 대해 nested zip frame 매칭, disp16 sampling, N보/risk bucket offline evaluator가 실행되었다. | `docs/android/aihub189_depthprediction_offline_validation_20260701.md`, `docs/evidence/aihub189_depthprediction_original_full_20260702.md` | AIHub189는 offline ZED reference다. Android ARCore PASS가 아니며 evidence에는 `arcore_pass=false`가 고정되어 있다. `scale_status=unverified`, confidence 방향 미확정, `detector_input_kind=generated_probe_bbox` 한계도 유지한다. |

## 정책 확정 사항

- 자동 신고 대상은 `damaged_tactile_block` 하나다.
- 손상 점자블록은 사용자에게 기본 TTS/진동 안내를 하지 않는다.
- 사용자가 명시적으로 신고를 요청한 경우에만 신고 완료/실패/중복 TTS를 낸다.
- GPS가 없으면 자동/음성 신고 모두 저장하지 않는다. 명시 신고 요청 시에는 위치 정보가 필요하다고 안내한다.
- duplicate 기준은 같은 `class_name`, 반경 `10m`, `captured_at` 전후 `1분`이다. 중복 후보여도 저장하고 duplicate tag를 붙인다.
- `fake fallback`은 demo 전용이다. 실사용/운영 경로에서 fake로 자동 전환하지 않는다.
- 기관 제출 기능은 없다. 운영자 대시보드 검수와 `운영자 내부 export`만 있다.
- report image, 위치, 신고자 식별자, 신고 시각은 서버에 저장한다.
- report row와 image는 6개월, 즉 180일 보존 정책이다. 현재 retention 자동화는 dry-run 후보 출력까지만 있고 실제 삭제 job/scheduler는 미완료다.
- 최종 운영 서비스 방향은 로그인 필수다. 현재 Android는 local user id gate이고, 운영 auth/RBAC는 별도 출시 lane이다.

## 기존 검증 근거

아래 표는 기존 문서/로그에 기록된 evidence를 요약한 것이다. 이번 문서 정리 작업에서 Gradle, pytest, npm, Android device smoke, AIHub189 evaluator를 재실행했다는 뜻이 아니다.

| Evidence | 기존 기록 | 이 evidence가 증명하는 것 | 증명하지 않는 것 |
|---|---|---|---|
| Android TFLite contract | `python scripts/check_android_tflite_contract_20260531.py` PASS, `check_scope=static_contract_only`, `ok=true`, `unified_asset_present=false`, `expected_runtime_model=legacy_two_model`, `expected_fallback=true` | Android runtime contract와 fallback 기대값 | final unified TFLite 완료 |
| Android depth scaffold | `python scripts/check_android_depth_scaffold_20260531.py` PASS | 코드 scaffold와 필수 연결 정적 확인 | ARCore depth field 정확도 |
| Android build/unit | `cd apps/android && ./gradlew test assembleDebug --no-daemon` BUILD SUCCESSFUL | JVM/unit test와 debug APK build | outdoor walking PASS |
| 정지 실기기 smoke | `SM-G981N` / Android 13 / `R3CN50F4APH`: install Success, `am start -W` Status ok, TotalTime 480ms, `MainActivity` visible/reportedDrawn, AndroidRuntime:E empty | 설치/실행/초기 crash-free smoke | bbox/depth/route/TTS/haptic 실사용 PASS |
| Backend pytest | report/navigation/debug 중심 pytest 기록: 24 passed/32 skipped 또는 감사 문서 기준 41 passed/32 skipped | 정책/계약/unit 일부 PASS | PostGIS no-skip E2E와 운영 DB 완료 |
| Web typecheck/lint | `cd apps/web && npm run typecheck`, `npm run lint` PASS | Web/PWA/admin 타입/린트 | Android native Device evidence |
| Admin/report policy scripts | `check_frontend_admin_report_summary_policy_20260525.sh`, `check_frontend_walksafe_test_log_policy_20260701.sh` PASS | admin summary/export/fake 분리와 test capture gate 정책 | 운영 auth/release security |
| AIHub189 offline validation | original full offline run frames/evaluated 2461, stop 883 / warning 652 / ignore 926, `source_kind=offline_zed_reference`, `detector_input_kind=generated_probe_bbox`, `scale_status=unverified`, `confidence_policy=disabled_unknown_direction`, `arcore_pass=false` | offline depth pipeline/probe replay | Android ARCore 성능, detector 성능, metric accuracy final |

## 이번 문서 검증

이번 작업의 검증 범위는 문서 존재, 핵심 키워드, drift 수정 검색, diff whitespace 확인이다. 실제 Android/Backend/Web/Model 검증 재실행은 다음 개발 gate에서 별도 수행해야 한다.

- `test -s docs/status/walksafe_team_current_status_20260708.md`
- `rg -n "기존 검증 근거|이번 문서 검증|Android native|AIHub189|arcore_pass=false|unified|legacy fallback|운영자 내부 export|fake fallback|GPS|10m|1분" docs/status/walksafe_team_current_status_20260708.md`
- `rg -n "walksafe_team_current_status_20260708" docs/README.md docs/inventory/document_inventory_20260707.md docs/inventory/document_consolidation_plan_20260707.md`
- `rg -n "source=android|destination|navigation/destinations/search|final/export-ready|APK|sha256|tactile_damage_area" docs apps/android backend model`
- `git diff --check -- docs/README.md docs/status/walksafe_team_current_status_20260708.md docs/inventory/document_inventory_20260707.md docs/inventory/document_consolidation_plan_20260707.md docs/walksafe-v2 docs/evidence docs/android daylog/2026-07-08.md`

## 아직 완료라고 말하면 안 되는 것

- Outdoor walking/route PASS.
- `N보` 실측 거리 정확도 PASS.
- TTS/진동/TalkBack 체감 PASS.
- final/export-ready `unified_walksafe` TFLite 완료.
- Android ARCore depth 실기기 정합 PASS.
- Android bbox overlay와 ARCore depth가 같은 객체를 가리킨다는 field PASS.
- PostGIS no-skip `/reports/v2` DB E2E.
- retention 180일 자동 삭제 job/scheduler 완료.
- 실제 운영 auth/RBAC/rate-limit/signed URL/CSRF/audit/secret 관리/release approval.
- AIHub189 offline validation을 Android ARCore PASS로 해석하는 주장.
- fake/PWA/headless 결과를 Android Device PASS로 해석하는 주장.
- `check_android_tflite_contract ok=true`를 final unified 완료로 해석하는 주장.
- 공공기관 자동 제출 또는 기관 제출 기능 구현 완료 주장.
- `tactile_damage_area` 외부 검수/AI suggestion/manual bbox queue를 reviewed dataset 적용 완료로 해석하는 주장. 현재 repo 근거는 `pending_decisions`, `built_dataset=false`, 40건 manual bbox queue다.

## 팀원이 다음에 보면 되는 문서

| 문서 | 읽는 이유 |
|---|---|
| `docs/README.md` | 전체 문서 지도와 canonical/superseded 구분 |
| `docs/status/current_status.md` | 2026-07-02 기준 상세 현재 상태와 검증 명령 |
| `apps/android/README.md` | Android APK, runtime config, overlay/debug/report 상태 |
| `docs/operations/report_operations.md` | 신고/중복/export/운영 정책 |
| `docs/walksafe-v2/policy_decisions_20260702.md` | 사용자 확정 정책 원문 |
| `docs/walksafe-v2/implementation_confidence_audit_20260704.md` | 믿어도 되는 범위와 믿으면 안 되는 범위 |
| `docs/inventory/document_inventory_20260707.md` | 문서 분류 인벤토리와 Downloads 후보 판단 |
| `docs/inventory/classification_audit_summary_20260708.md` | machine-readable repo/Downloads manifest 요약과 검증 결과 |
| `docs/inventory/document_consolidation_plan_20260707.md` | 문서 정리 선택지와 사용자 결정 질문 |
| `docs/evidence/final_report_index.md` | 발표/보고용 evidence 색인과 완료 주장 제한 |
| `docs/evidence/aihub189_depthprediction_original_full_20260702.md` | AIHub189 original full offline validation 결과 |

## 남은 작업/결정

### 이번 작업에서 바로 보정한 문서 drift

- `PROJECT_PLAN.md`와 초기 PWA 문서는 Android primary 기준에서 `_archive_candidates/2026-07-08/`로 이동했다.
- `docs/evidence/templates.md`, `docs/evidence/privacy_checklist.md`의 `source=android` 누락/초안 표현을 backend/admin/export 현재 기준에 맞게 보정했다.
- `docs/walksafe-v2/navigation_integration_policy.md`, `docs/walksafe-v2/voice_command_strategy.md`의 destination search 미연결 표현을 "backend `/navigation/destinations/search` 구현 및 Android UI 연결, Android/voice 실사용 UX 검증 후속"으로 보정했다.
- `docs/walksafe-v2/two_model_runtime_plan.md`에서 runtime/config 연결과 final/export-ready unified TFLite 완료를 분리했다.
- `docs/inventory/walksafe_documentation_audit_20260701.md`에서 APK hash current/history를 분리했다.
- 기관 제출 표현은 `운영자 내부 export`로 통일한다. 이 기준은 `docs/README.md`와 `docs/evidence/final_report_index.md`에도 반영했다.
- 운영 fake fallback은 demo 전용, 실사용 fail-closed 원칙으로 문서화한다.

### 사용자 결정이 필요한 항목

- Downloads의 AIHub training brief를 superseded로 두고 repo 기준 최신 팀 공유 요약을 새로 만들지.
- `tactile_damage_area`를 1차 unified 범위 밖의 manual bbox 검수 후보로 계속 둘지, 별도 14-class 비교안으로 되살릴지.
- `ai_tasks`는 manifest/summary 중심 evidence로 관리하고 이미지/contact sheet/raw 산출물은 필요 시 로컬 evidence로만 둘지.

### 모델 학습 완료 후 준비

- strict 768 unified training/export 완료 후 TFLite asset을 생성한다.
- `two_model_runtime.json`을 실제 768 asset 경로/input size로 갱신한다.
- Android에서 `loaded_model_key=unified_walksafe`, tensor smoke, FPS, bbox/depth 정합 evidence를 만든다.
- outdoor walking route, TTS/haptic, GPS/step/N보 현장 smoke를 별도 기록한다.

### Downloads 후보 처리

- `/home/ddobagi/Downloads`에서 바로 CANONICAL로 승격할 문서는 없다.
- `/home/ddobagi/Downloads/walksafe_overlay_fourth_review_151.md`는 참고 자료로만 둔다. 집계는 정상 119 / 수정 21 / 제외 9 / 애매함 2지만, dataset 반영 완료 근거로 쓰지 않는다.
- Downloads의 AIHub training brief는 repo의 `data_sources/manifests/walksafe_aihub_source_usage_20260628.md`보다 오래된 팀 공유 요약이므로 `SUPERSEDED`로 둔다.
- `navigation_4_blind` 원본은 UX 참고 `EXTERNAL`로만 둔다. 코드/모델/assets/env 방식을 직접 반입하지 않는다.

## 문서 정리 상태

- 이번 통합본은 팀원이 한 파일로 현재 상태를 파악하기 위한 문서다.
- `docs/inventory/document_inventory_20260707.md`는 문서 분류표이고, 이 통합본의 상세 상태 요약을 대체하지 않는다.
- `docs/inventory/document_consolidation_plan_20260707.md`는 이후 문서 정리 선택지와 결정 질문이다.
- 실제 문서 삭제는 하지 않았다. 사용자 승인에 따라 legacy/superseded 후보는 `_archive_candidates/2026-07-08/`로 staging 이동했다.
- 원본 AIHub zip, inner zip, 이미지/depth 전체, model weight, exported TFLite/ONNX, secret, `.env`, DB dump는 커밋/저장 대상으로 보지 않는다.
