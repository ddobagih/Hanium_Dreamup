# WalkSafe 요구사항 추적표 - 2026-07-01

작성 기준: 2026-07-01 KST, `/home/ddobagi/Code/hanium-dreamup`.

이 문서는 `docs/walksafe_implementation_plan_20260630.html`, `docs/evidence/feature_matrix.md`, `product/*`, `docs/android/*`, `docs/walksafe-v2/*`, `apps/android`, `backend`, `apps/web`, `model`, `scripts`, `configs`를 10개 조사 축으로 나눠 확인한 요구사항 추적표다.

## 판정 원칙

- 주 사용자 앱은 Android native APK다. PWA는 demo/support/API 검증 보조 경로로만 본다.
- 현장 보행/실외 route/체감 검증은 이번 판정에서 제외한다. 정지 실기기 smoke는 install/launch/crash-free까지만 증거로 쓴다. Android camera/depth/overlay/TTS/haptic/GPS/N보/route field 항목은 코드가 있어도 `DEFERRED_DEVICE`로 표기한다.
- PWA, headless, fake, static 결과는 Android Device PASS로 승격하지 않는다.
- `DONE`은 코드와 최소 자동 검증이 있고, 이번 범위에서 더 구현할 코드 gap이 뚜렷하지 않은 항목이다.
- `PARTIAL`은 코드/정책/테스트 일부가 있으나 runtime wiring, 계약, UX, 운영 검증 중 하나가 남은 항목이다.
- `MISSING`은 요구는 있으나 해당 제품 경로 코드가 없는 항목이다.
- `BLOCKED`는 외부 산출물, DB, secret, 실기기, 장시간 학습 등 현재 환경에서 완료 판정할 수 없는 항목이다.

## 한줄 결론

현재 상태는 "운영/모델/현장 검증까지 포함한 전체 완료"가 아니다. 이번 구현 범위에서는 Android upload, off-route gate, TTC/route-bearing 보조 위험 정책, heading/bearing metadata/navigation, admin Android source, TestCapture gate, AIHub189 offline evaluator가 코드/테스트로 보강됐다. 다만 unified TFLite final asset/학습, PostGIS no-skip, 운영 보안/릴리즈, 실외 보행/체감 검증은 제외 범위로 남긴다.

## 조사 축

| Agent | 조사 축 | 핵심 결론 |
|---|---|---|
| A1 | 원천 요구사항 | Android native primary, PWA support, Device PASS 금지 원칙 확정 |
| A2 | Android camera/ARCore/depth/permission | core path와 permission split은 구현됨. field/device 검증은 제외 |
| A3 | Android detector/TFLite/model config | unified path는 있으나 unified asset 없음. legacy fallback만 가능 |
| A4 | Android risk/feedback/accessibility | report-only/severity/TTC/route-bearing motion context 보조 경고/audio focus/TalkBack gate 구현. 실청취/진동 체감은 제외 |
| A5 | Android/backend/PWA navigation/steps | destination search/route UX/off-route 연속 샘플 gate/route bearing metadata와 risk motion context 연결 구현. 실외 route PASS는 제외 |
| A6 | Android report/privacy/debug | Android multipart upload client와 backend v2 source=android 정책 구현. Device+DB E2E는 제외 |
| A7 | Backend API/security/PostGIS | detect/nav/report 정책은 구현. PostGIS tests skip, auth/rate-limit 없음 |
| A8 | Web/PWA isolation | PWA root scope opt-in, TestCapturePanel raw 저장 gate/auth/consent/validation/delete 구현 |
| A9 | Admin/Ops/export/workflow | Android source UI/type/filter/summary/cluster/export 구현. auth/audit/live DB 검증은 제외 |
| A10 | Model/Data/Evidence | dataset/baseline은 있으나 final/export-ready 모델 아님. canonical APK hash는 1f5b2020 기준으로 최신화 |

## 요구사항 추적표

| ReqID | 영역 | 요구사항 | 원천 | 구현 증거 | 검증/증거 | 상태 | 남은 구현/차단 |
|---|---|---|---|---|---|---|---|
| WS-REQ-001 | Scope | Android native를 주 사용자 앱으로 두고 PWA는 demo/support로 격리한다. | `README.md`, `product/vision.md`, `docs/evidence/feature_matrix.md`, 6/30 HTML | Android app is primary; PWA service worker registration is disabled by default and opt-in via `NEXT_PUBLIC_WALKSAFE_PWA_ENABLED` | `check_frontend_pwa_policy_20260526.sh`, web typecheck/lint | DONE | PWA remains demo/support only |
| WS-REQ-002 | Evidence | PWA/headless/fake 결과를 Android Device PASS로 쓰지 않는다. | `docs/evidence/feature_matrix.md`, `product/done-criteria.md` | evidence 문서에 등급 원칙 있음 | 정적 조사 | DONE | 이후 문서에서 계속 준수 필요 |
| WS-REQ-003 | Android Camera | ARCore camera preview와 같은 frame 기반 detector 입력을 제공한다. | 6/30 HTML Android camera/ARCore | `apps/android/.../MainActivity.kt`, `CameraBackgroundRenderer.kt`, `ArCoreFrameProvider.kt` | Android static/depth scaffold PASS | DEFERRED_DEVICE | 목걸이 착용 각도, 회전/crop, preview 안정성 실기기 필요 |
| WS-REQ-004 | Android ARCore/Depth | ARCore availability/install/session 및 `DepthMode.AUTOMATIC`을 처리한다. | 6/30 HTML, Android docs | `MainActivity.kt`, `ArCoreFrameProvider.kt`, manifest ARCore optional | static scaffold PASS | DEFERRED_DEVICE | 지원/미지원 기기 UX와 실제 depth availability 실기기 필요 |
| WS-REQ-005 | Android Depth | Raw Depth+confidence 우선, Full Depth fallback을 제공한다. | `docs/android/arcore_depth_estimation_architecture.md`, 6/30 HTML | `DepthFrameSnapshot.kt`, `DepthEstimator.kt`, `ObjectDepthRuntimePipeline.kt` | depth/unit tests 존재 | DEFERRED_DEVICE | 0.5m/1m/2m depth 추세와 same-object 검증 필요 |
| WS-REQ-006 | Android Overlay | bbox overlay가 실제 객체 위치와 맞아야 한다. | `product/backlog.md` P0, device checklist | `DebugBboxOverlayView.kt`, `CoordinateMapper.kt`, `MainActivity.kt` | `CoordinateMapperTest`, device checklist | DEFERRED_DEVICE | 중앙/좌/우/상/하/회전 실기기 PASS 없음 |
| WS-REQ-007 | Android Stale | stale detection은 depth/alert/report 후보에서 제외하고 reason을 남긴다. | 6/30 HTML P0, `feature_matrix.md` | `DetectionSnapshot.staleReason`, `MetadataCaptureLog`, `DeviceGateState` | `MetadataCaptureLogTest`, `DeviceGateTest`, Gradle tests PASS | DONE | 실기기 stale UI 체감 확인은 제외 1 |
| WS-REQ-008 | Permission | hazard-only는 camera 중심으로 시작하고 location/activity는 필요한 시점에 분리 요청한다. | 6/30 HTML, 이전 goal gap | `AndroidManifest.xml`, `MainActivity.missingRuntimePermissions()` | 정적 조사 | DONE | -
| WS-REQ-009 | Device Gate | startup, actuator, alert, report gate를 코드로 분리한다. | `docs/evidence/feature_matrix.md`, 6/30 HTML | `DeviceGate.kt`, `WalkSafeFeedbackPolicy.kt`, `AndroidReportCandidatePolicy.kt` | `DeviceGateTest` | DONE | 실제 gate PASS 뒤 TTS/진동/report는 실기기 필요 |
| WS-REQ-010 | Debug Metadata | Android debug evidence는 metadata-only를 기본으로 한다. | `docs/android/android_metadata_capture_schema_20260601.md` | `MetadataCaptureLog.kt`, `MetadataLogJsonEncoder.kt`, release Noop uploader, model fallback metadata fields | `MetadataLogJsonEncoderTest`, backend debug disabled tests, Gradle tests PASS | DONE | raw frame capture는 별도 debug-only opt-in |
| WS-REQ-011 | Debug Frame | raw image frame capture는 debug/local-dev gate 뒤에만 있어야 한다. | 6/30 HTML privacy/debug | debug build `HttpFrameCaptureUploader`, release Noop, `BuildConfig.DEBUG` button hide, backend flag default off | `MainActivityAccessibilityStaticTest`, Gradle tests PASS | DONE | 운영 auth/rate-limit 완성은 제외 3 |
| WS-REQ-012 | Detector Runtime | Android detector는 unified primary path와 legacy fallback을 제공한다. | `README.md`, model docs, 6/30 HTML | `TfliteAndroidFrameDetector.createWithStatus`, `two_model_runtime.json`, fallback reason/effective model metadata | `check_android_tflite_contract` ok=true with `unified_asset_present=false`/`expected_fallback=true`, Gradle tests PASS | DONE | unified asset 투입 완료 판정은 제외 2 |
| WS-REQ-013 | Unified Asset | `unified_walksafe` TFLite asset이 APK asset으로 존재해야 한다. | `docs/evidence/feature_matrix.md` addendum, model plan | config references `models/walksafe_unified_yolo26n_640_float32.tflite` | asset listing, contract warning | BLOCKED | 실제 asset 없음. legacy custom/COCO 2개만 존재. 최종 unified asset 투입은 제외 2 |
| WS-REQ-014 | Input Size | 640/768 runtime contract를 확정하고 config와 asset을 일치시킨다. | 6/27 training plan, 6/30 HTML | Android config는 640, 6/27 plan은 768 | 정적 조사 | BLOCKED | 최종 640/768 결정 및 TFLite export/config 반영은 제외 2 |
| WS-REQ-015 | Model Trace | `source_model`, `threshold_used`는 실제 config/model metadata에서 trace 가능해야 한다. | report/source metadata decision, 6/30 HTML | backend configs and Android `two_model_runtime.json` include `source_model`; Android report metadata derives `threshold_used`, `source_model`, `model_key`, fallback/loaded model fields from runtime candidate/config | `check_android_tflite_contract_20260531.py`, `AndroidReportCandidatePolicyTest`, backend reports tests | DONE | unified asset 없음과 Android/backend threshold drift는 `threshold_used` source별 trace로 남김 |
| WS-REQ-016 | Tensor Contract | TFLite input/output tensor signature를 검사한다. | 6/30 HTML detector/runtime | runtime assumes `[1,300,6]`; static parser/contract checks exist | parser/unit tests, static check says no inference | BLOCKED | final unified TFLite asset 없음. Interpreter tensor smoke는 제외 2 이후 가능 |
| WS-REQ-017 | Latency/FPS | Android detector latency/FPS/thermal evidence를 제공한다. | `product/done-criteria.md`, 6/30 HTML | timing fields exist in Android detector | metadata timing fields | BLOCKED | unified asset 및 실기기 FPS/thermal 없음 |
| WS-REQ-018 | Risk Policy | 손상 점자블록은 report-only, 보행 위험 객체는 경로/접근 조건에서만 alert한다. | README v2 policy, `feature_matrix.md` #9-#11 | `MessagePolicy.kt`, `DepthEstimator.kt`, `AndroidRiskSelectionPolicy.kt`, `MotionContext.kt`, `ObjectDepthRuntimePipeline.kt` | `MessagePolicyTest`, `AndroidRiskSelectionPolicyTest`, `MotionContextTest`, `ObjectDepthRuntimePipelineTest`, Gradle tests PASS | DONE | metric distance/trend/TTC와 route-bearing alignment quality가 alert confidence path에 연결됨. IMU 센서 fusion과 field validation은 제외 1/후속 |
| WS-REQ-019 | Severity | INFO confidence가 STOP/WARNING을 누르지 않도록 severity 우선순위를 보장한다. | 6/30 gap prompt, safety policy | `AndroidRiskSelectionPolicy`가 STOP/WARNING 우선순위 뒤 confidence tie-break를 적용하고, damaged tactile block은 별도 report-only 후보로 유지 | `AndroidRiskSelectionPolicyTest`, Gradle tests PASS | DONE | 정지 smoke 범위라 실제 다중 객체 카메라 장면 검증은 제외 |
| WS-REQ-020 | TTS/Haptic | Android TTS와 vibration을 Device Gate 이후 위험 안내에 연결한다. | 6/30 HTML Android feedback | `AndroidFeedbackActuator.kt`, `WalkSafeFeedbackPolicy`, `MainActivity.emitFeedbackAction`, onPause release | `WalkSafeFeedbackPolicyTest`, `MainActivityAccessibilityStaticTest`, Gradle tests PASS | DEFERRED_DEVICE | 코드 연결은 완료. 실청취/진동 체감 PASS는 제외 1 |
| WS-REQ-021 | TalkBack/Audio | TalkBack, audio focus, 중복 발화 억제와 nav/risk 우선순위를 처리한다. | `product/done-criteria.md`, 6/30 HTML | `AndroidFeedbackActuator`가 utterance 완료/오류/중지/onPause 시 audio focus를 release하고, screen reader 활성 시 앱 TTS 중복 발화를 억제하되 위험 진동은 유지 | `MainActivityAccessibilityStaticTest`, Gradle tests PASS | DONE | 실청취/진동 체감 PASS는 제외 |
| WS-REQ-022 | Android Accessibility | contentDescription, focus order, large text, TalkBack flow를 제공한다. | 6/30 HTML accessibility | status label contentDescription/live region, focusable controls, non-control detail/overlay accessibility 제외, screen reader active gate | `MainActivityAccessibilityStaticTest`, Gradle tests PASS | DONE | TalkBack 수동 체감 PASS는 제외 |
| WS-REQ-023 | Android Location | FusedLocation과 trusted GPS policy를 report/navigation에 쓴다. | navigation policy, 6/30 HTML | `LocationPolicy.kt`, `MainActivity.startLocationUpdatesIfAllowed`, heading capture/report metadata | `LocationPolicyTest`, `AndroidReportCandidatePolicyTest`, Gradle tests PASS | DEFERRED_DEVICE | 코드 연결은 완료. 실폰 GPS/report E2E는 제외 1/4 |
| WS-REQ-024 | Android Destination | Android 목적지 검색/후보 선택/더보기/재검색/취소를 제공한다. | `feature_matrix.md` #1/#2, 6/30 HTML | `MainActivity.performDestinationSearch`, `DestinationSearchResult`, search reset/cancel UI update, more result cap | Gradle tests PASS, backend/PWA tests PASS | DONE | Android에는 TMAP key를 넣지 않고 backend search proxy만 사용 |
| WS-REQ-025 | Android Route UX | Android 경로 시작/취소/중지와 in-flight 상태를 제공한다. | `feature_matrix.md` #3, 6/30 HTML | `MainActivity.onRouteButtonClicked`, `routeRequestInFlight`, cancel clears navigator/currentDestination and returns to hazard-only | Android navigation JVM tests PASS, Gradle tests PASS | DONE | 실외 route PASS는 제외 |
| WS-REQ-026 | Android Off-route | off-route/reroute/arrival gate를 제공한다. | `feature_matrix.md` #4/#5 | `RouteNavigator.kt`, `MainActivity.updateRouteGuidance` | `RouteNavigatorTest`, Gradle tests PASS | DONE | 연속 샘플/accuracy overlap gate 구현. 실외 field 검증은 제외 1 |
| WS-REQ-027 | Android Steps | step tracker와 step length calibration을 실제 N보 안내에 연결한다. | `feature_matrix.md` #6/#7 | `AndroidStepTracker.kt`, `StepLengthEstimator.kt` | `StepLengthEstimatorTest`, `MessagePolicyTest` | DONE | -
| WS-REQ-028 | Heading/Bearing | heading 또는 route bearing을 보조 신호로 연결한다. | 6/30 HTML navigation/risk | location.bearing/이동 기반 heading 수집 후 report metadata 전달, backend route guide `bearing_deg` 생성, Android route guide `bearing_deg`/turn/point/facility metadata 파싱, route bearing alignment를 `MotionContext`로 depth confidence에 전달 | `AndroidReportCandidatePolicyTest`, `BackendWalkingRouteClientTest`, `MotionContextTest`, `ObjectDepthRuntimePipelineTest`, `backend/tests/test_navigation_routes.py`, Gradle/pytest PASS | DONE | 실외 route PASS는 제외 |
| WS-REQ-029 | Backend Navigation | TMAP/Kakao walking route proxy와 POI search를 제공한다. | `docs/walksafe-v2/navigation_integration_policy.md` | `backend/app/api/navigation.py`, `tmap_pedestrian.py`, `kakao_mobility.py` | `backend/tests/test_navigation_routes.py` PASS | DONE | live key/quota/실경로 smoke는 제외 |
| WS-REQ-030 | Android Report Candidate | Android에서 damaged tactile block + trusted GPS + allowed model만 report 후보로 만든다. | auto report policy, metadata decision | `AndroidRiskSelectionPolicy.selectReportOutput`, `AndroidReportCandidatePolicy.kt`, `MainActivity.processReportCandidate` | `AndroidRiskSelectionPolicyTest`, `AndroidReportCandidatePolicyTest`, Gradle tests PASS | DONE | 실기기/DB E2E와 offline retry는 제외/후속 |
| WS-REQ-031 | Android Report Upload | Android가 `/reports/v2` multipart upload를 실제 수행한다. | 6/30 HTML Android report | `MainActivity.prepareReportCandidate/processReportCandidate`, `AndroidReportUploader.upload` | `AndroidReportUploaderTest`, `MainActivityReportUploadStaticTest`, Gradle tests PASS | DONE | client 전송 경로 구현 완료. 실기기 camera/report DB E2E와 PostGIS no-skip은 제외 1/4 |
| WS-REQ-032 | Report Timestamp | Android `captured_at`은 backend datetime 계약과 맞아야 한다. | backend schema, report policy | `DetectionSnapshot.capturedAtMs` wall-clock 기반 `AndroidReportCandidatePolicy.timestampMsToIsoUtc()`로 ISO-8601 UTC 변환 | `AndroidReportCandidatePolicyTest`, Gradle tests PASS | DONE | - |
| WS-REQ-033 | Report Metadata | Android report metadata allowlist를 APK/model/hash/depth quality/gate status까지 확정한다. | `android_report_source_metadata_decision_20260601.md`, 6/30 HTML | Android metadata includes source/model/hash/depth quality/gate status/heading/fallback fields; backend allowlist preserves safe fields and excludes raw debug payload | `AndroidReportCandidatePolicyTest`, `test_reports_v2.py`, `test_android_debug_logs.py` | DONE | raw image/depth/GPS debug payload는 allowlist에 넣지 않음 |
| WS-REQ-034 | Backend Reports v2 | `/reports/v2`는 unified/custom tactile damaged block + GPS만 저장한다. | backend API contract, feature matrix | `ReportV2Metadata`, `report_policy.py`, `/reports/v2` endpoint | `test_report_policy.py` PASS, `test_reports_v2.py` PostGIS skip | BLOCKED | 정책/스키마 테스트는 PASS. DB integration no-skip은 제외 4 |
| WS-REQ-035 | BBox Validation | bbox는 `x+width <= 1`, `y+height <= 1`까지 검증한다. | 6/30 gap, backend contract | `BBox` validates individual fields plus x/y overflow | `test_reports_v2.py` x/y overflow rejection tests | DONE | - |
| WS-REQ-036 | PostGIS | report/radius/duplicate/export DB path를 no-skip으로 검증한다. | `feature_matrix.md`, report ops | PostGIS model/migration exists | backend report tests skipped where DB unavailable | BLOCKED | disposable PostGIS 또는 local DB 기동 후 skipped=0 검증은 제외 4 |
| WS-REQ-037 | Backend Security | admin/report/export/upload/debug endpoint에 auth/RBAC/rate limit/audit을 둔다. | 6/30 HTML security/admin | dev/test gates and test-log bearer token only | 정적 조사 | OUT_OF_SCOPE | auth/RBAC/CSRF/rate-limit/audit 운영 완성은 제외 3 |
| WS-REQ-038 | Upload Privacy | image upload는 type/size/EXIF strip, 조회 접근 제어를 갖춘다. | privacy checklist, report ops | `uploads.py` strips EXIF when decodable; test/debug capture is env/auth/consent gated | `test_uploads.py`, `check_frontend_walksafe_test_log_policy_20260701.sh` | OUT_OF_SCOPE | signed URL/조회 접근 제어 운영 완성은 제외 3 |
| WS-REQ-039 | Admin List/Detail | Admin report list/detail/filter/status를 제공한다. | 6/30 HTML Admin/Ops | `apps/web/app/admin/page.tsx`, backend list/status endpoints, Android source/unified model filter option | web typecheck/lint, admin policy script PASS | DONE | auth/RBAC/계정 기반 운영 UX는 제외 3. pagination polish는 후속 |
| WS-REQ-040 | Admin Export | CSV/JSON/GeoJSON, redaction, manifest, no-store export를 제공한다. | `feature_matrix.md` #13, report ops | backend export, frontend export links, internal reviewed/exclude_fake/redacted/damaged_tactile_block preset | frontend admin policy PASS, backend DB tests skipped | BLOCKED | 접근권한은 제외 3, live DB no-skip은 제외 4. 외부 기관 제출 기능 아님 |
| WS-REQ-041 | Admin Summary/Map | summary/source/status/location/cluster와 지도/히트맵을 제공한다. | `feature_matrix.md` #14 | backend summary, grid aggregate, admin summary panel, Android source counts | frontend summary policy PASS, backend DB tests skipped | BLOCKED | 실제 map SDK/heatmap UI는 후속, live DB no-skip은 제외 4 |
| WS-REQ-042 | Android Source | backend/admin/web 전체가 `source=android`를 지원한다. | source metadata decision, 6/30 HTML | `apps/web/types/inference.ts`, `apps/web/app/admin/page.tsx`, summary/cluster source counts, fixed reviewed/redacted damaged tactile export URL | backend policy tests, `check_frontend_admin_report_summary_policy_20260525.sh` | DONE | 운영 auth/승인은 제외 |
| WS-REQ-043 | Review Workflow | `new -> reviewed -> resolved`, note/reason/history/conflict guard를 제공한다. | `feature_matrix.md` #16 | backend status endpoint, admin note/history UI | DB tests skipped in current env | BLOCKED | actor audit/auth는 제외 3, live DB no-skip은 제외 4 |
| WS-REQ-044 | Demo Separation | fake/demo data를 운영/성능 집계와 분리한다. | `feature_matrix.md` #15 | backend/frontend fake detection, demo badges/filter, coordinate gate performance exclusion | `test_reports_v2.py`, frontend admin policy PASS | DONE | live DB 성능 집계 no-skip은 제외 4 |
| WS-REQ-045 | PWA Camera/Risk/Nav | PWA camera/risk/nav/settings/offline을 demo/support로 유지한다. | PWA docs, 6/30 HTML | wide PWA implementation remains demo/support; Android native is primary | PWA policy scripts PASS, web typecheck/lint PASS | DONE | Android 구현 완료 근거로 쓰지 않음. PWA field evidence는 별도 |
| WS-REQ-046 | PWA Root Scope | PWA manifest/SW가 production root를 오염시키지 않는다. | PWA support requirement | service worker registration is env opt-in via `NEXT_PUBLIC_WALKSAFE_PWA_ENABLED`; default `.env.example` keeps it disabled for production root | `check_frontend_pwa_policy_20260526.sh`, `npm run typecheck`, `npm run lint` | DONE | PWA install/offline 자체는 demo/support opt-in 경로 |
| WS-REQ-047 | TestCapturePanel | raw image/GPS test capture는 dev/auth/consent gate 뒤에 둔다. | 6/30 HTML privacy/test capture | `TestCapturePanel` and `/api/walksafe-test-log` default disabled; server env gate, bearer token, explicit consent, metadata/JPEG size/type validation, retention/delete path | `check_frontend_walksafe_test_log_policy_20260701.sh`, `npm run typecheck`, `npm run lint` | DONE | raw debug capture remains dev/test only |
| WS-REQ-048 | PWA Offline | PWA install/offline shell과 opt-in offline queue를 제공한다. | `feature_matrix.md` PWA/offline | `usePwaStatus`, `sw.js`, `offline-report-queue.ts` | `check_frontend_pwa_policy_20260526.sh` PASS | DONE | offline sync product polish와 field evidence는 후속 |
| WS-REQ-049 | Settings | emergency contacts/settings는 local-only, max 3, masking, no-send를 보장한다. | `feature_matrix.md` #17/#18 | PWA settings localStorage, `useWalkSafeSettings` schema v3 migration/contact cap/masking/no-send copy | `settings-privacy.test.ts` compiled/executed, web typecheck/lint PASS | DONE | Android native secure settings UX는 current hazard/report/navigation scope 밖 후속 |
| WS-REQ-050 | Voice/STT | voice command는 report/repeat/location/destination/nav intent를 지원하고 Android에서는 명시적 "신고해줘" 신고 경로를 제공한다. | voice docs, feature matrix #19/#20, report ops | Android `SpeechRecognizer` button routes recognized report phrase to explicit `/reports/v2` upload; web/voice server paths remain prototype/support lane | `MainActivityReportUploadStaticTest`, Gradle tests PASS, voice/local policy evidence | DONE | 실폰 mic/TTS 청취, TalkBack 충돌, 보행 중 인식률은 제외 1 Device evidence. Cloud/paid STT and external URL approval are 제외 3 |
| WS-REQ-051 | Model Dataset | unified 13-class dataset/class order를 유지한다. | `model/README.md`, dataset manifests | latest train/val dataset and class order exist | dataset validator PASS | BLOCKED | independent test/holdout/final model validation은 제외 2 |
| WS-REQ-052 | Model Training | final/export-ready model metric과 hash를 evidence로 남긴다. | model done criteria, 6/27 plan | 100 epoch baseline exists; e300 run in progress | A10 static evidence | BLOCKED | e300 미완료, 640/768 drift, independent test metric 없음 |
| WS-REQ-053 | Unified Export | final model을 TFLite로 export해 Android asset/config에 반영한다. | export scripts, Android runtime config | export scripts exist | dry-run/pass claims only | BLOCKED | unified TFLite asset 없음, TensorFlow/export env 미준비 |
| WS-REQ-054 | Evidence Consistency | APK hash, PASS/BLOCKED 상태, 구현 범위를 최신 코드와 일치시킨다. | `docs/evidence/final_report_index.md`, Android evidence | canonical docs updated to APK hash `1f5b2020053d755e6f52054453c82bd2eb45d3bd6ade3740a8d9bfa2f35967c9` and stationary device smoke scope | `sha256sum`, Gradle build, adb install/launch smoke | DONE | 과거 execution snapshot hash는 이력으로 남음. field/device 보행 PASS는 제외 |
| WS-REQ-055 | Release/Ops | HTTPS/domain/storage/external submission/retraining automation은 승인 후 별도 lane으로 둔다. | 6/30 HTML release/MLOps | 문서 수준 | 정적 조사 | BLOCKED | secrets, 계정, 운영 승인 필요 |

## 우선순위별 미완료 묶음

### P0 - 구현 없이는 전체 완료 주장 불가

1. unified TFLite artifact/contract: `WS-REQ-013`, `WS-REQ-014`, `WS-REQ-016`, `WS-REQ-052`, `WS-REQ-053`은 제외 2로 `BLOCKED`.
2. PostGIS report DB path no-skip: `WS-REQ-034`, `WS-REQ-036`, `WS-REQ-040`, `WS-REQ-041`, `WS-REQ-043`의 live DB 검증은 제외 4로 `BLOCKED`.
3. 운영 보안/릴리즈: `WS-REQ-037`, `WS-REQ-038`, `WS-REQ-055`는 제외 3으로 `OUT_OF_SCOPE`/`BLOCKED`.

### P1 - 기능 완성도와 UX를 막는 항목

1. Android native secure settings and STT/mic polish: `WS-REQ-049`와 `WS-REQ-050`의 실폰 mic/TTS 청취, TalkBack 충돌, 보행 중 인식률은 후속 Device evidence다.

### P2/Release - 이번 구현과 분리 가능하지만 완료 주장 시 명시 필요

1. auth/RBAC/rate-limit/audit/signed URL/actor audit: 제외 3.
2. live map SDK/heatmap/bulk workflow/pagination polish: 후속 UI/운영 lane.
3. HTTPS/domain/storage/external submission/retraining automation: 제외 3.

## 이번 조사에서 확인된 검증 결과

| 검증 | 결과 | 해석 |
|---|---|---|
| `python3 scripts/check_android_tflite_contract_20260531.py` | ok=true, `check_scope=static_contract_only`, `unified_asset_present=false`, `expected_runtime_model=legacy_two_model`, `expected_fallback=true` | static contract는 통과지만 unified 완료 아님 |
| `python3 scripts/check_android_depth_scaffold_20260531.py` | PASS | Android depth scaffold 정적 확인 |
| `cd apps/android && ./gradlew test assembleDebug --no-daemon` | BUILD SUCCESSFUL, APK sha256 `1f5b2020053d755e6f52054453c82bd2eb45d3bd6ade3740a8d9bfa2f35967c9` | JVM/unit/build 수준 PASS, 보행 Device PASS 아님 |
| backend navigation/reports/debug subset | 24 passed, 32 skipped | navigation/debug/report policy PASS. PostGIS 미기동으로 reports DB path skip |
| frontend admin/test-log policy scripts | PASS | Android source/admin export/test capture gate policy PASS |
| `cd apps/web && npm run typecheck && npm run lint` | PASS | web type/lint PASS |
| settings privacy policy test | PASS | emergency contacts local-only, max 3, masking, no-send policy PASS |
| AIHub189 offline evaluator tests | 5 passed | offline ZED reference evaluator unit PASS |
| AIHub189 original offline full run | frames/evaluated 2461, stop=883 warning=652 ignore=926 | `Depth_001~005.zip` original full run PASS as offline ZED reference. `arcore_pass=false`, detector 성능 검증 아님 |
| dataset validator | PASS reported by A10 | train/val dataset class order 확인, final model PASS 아님 |
| `adb -s R3CN50F4APH install/start/dumpsys/logcat` | install Success, launch Status ok/COLD/TotalTime 480ms, PID 16571, `MainActivity` RESUMED/visible/reportedDrawn, AndroidRuntime:E empty | 정지 install/launch/crash-free smoke만 PASS |

## 구현 골 작성 시 사용할 차단 문구

다음 항목을 완료 조건으로 박지 않으면 "다 구현"으로 수렴하지 않는다.

- Android `/reports/v2` upload는 필수 구현이다. 후보 문자열만으로 완료 금지.
- unified TFLite asset이 없으면 unified runtime 완료 금지.
- PostGIS tests가 skip이면 report DB runtime 완료 금지.
- PWA/headless 결과로 Android PASS 금지.
- 실기기 없는 항목은 `DEFERRED_DEVICE`로만 표기하고 PASS 금지.
- `source=android`는 backend뿐 아니라 frontend/admin 타입/summary/filter까지 반영해야 한다.
- TestCapturePanel/raw image/GPS 저장은 production/default surface에 노출 금지.
