# WalkSafe implementation confidence audit - 2026-07-04

작성 기준: 2026-07-04 KST, 2026-07-10 플랫폼 역할 보정.

> Status: SNAPSHOT / RUNTIME SUPERSEDED. 아래 테스트 수치와 unified asset 부재 판정은 2026-07-04 당시 결과다. 현행 img768 asset·실기기 load/invoke instrumentation과 최신 회귀는 `../status/current_status.md`를 우선한다.

현재 주 사용자 앱은 Web/PWA이며 Android native는 ARCore/depth/TFLite 실험·검증 보조 경로다. 아래 Android 판정은 해당 모듈 evidence에만 적용되고 Web/PWA 제품 완료를 뜻하지 않는다.

## 결론

현재 WalkSafe는 **전체 완료가 아니라 PARTIAL**이다. 코드 연결과 정적/unit 검증은 많이 되어 있지만, 제품 기능으로 믿으려면 모델, DB, 실기기, 운영 보안 gate가 아직 남아 있다.

## 지금 믿어도 되는 것

| 영역 | 판정 | 근거 |
|---|---|---|
| Android 권한 분리 | PASS | hazard-only 시작은 runtime `CAMERA` 중심이고, 위치/활동/음성 권한은 기능 진입 시 분리 요청한다. |
| Android 위험 우선순위 기본 | PASS(코드) | STOP/WARNING 우선순위와 damaged tactile block report-only 정책은 unit test로 잠겨 있다. |
| 신호등 색상 판단 보류 | PASS(수정됨) | `traffic light`는 색상 정책이 생기기 전까지 사용자 STOP/WARNING 안내를 만들지 않는다. |
| Android `/reports/v2` client | PASS(코드) | multipart `metadata` + `image` 업로드 경로가 있다. 단 실기기+DB E2E PASS는 아님. |
| backend `/reports/v2` 정책 | PASS(정책 단위) | damaged tactile block, bbox overflow, metadata allowlist, coordinate gate performance exclusion 정책 테스트가 있다. |
| fake/demo와 performance exclusion 분리 | PASS(수정됨) | `performance_excluded=true`만으로 fake/demo로 분류하지 않는다. |
| Web test capture gate | PASS(수정됨) | 기본 off, token/consent/validation/delete에 더해 `NODE_ENV=production` hard block을 추가했다. |
| AIHub189 offline evaluator | PASS(로직) | nested zip, disp16 변환, bbox sampling unit test는 통과한다. |
| AIHub189 원본 검증 | PASS(offline reference) | `Depth_001~005.zip` 전체 2461 frame offline ZED reference run evidence가 있다. |

## 아직 믿으면 안 되는 것

| 영역 | 판정 | 이유 |
|---|---|---|
| 전체 제품 완료 | FAIL | 기능별 PASS가 아니라 PARTIAL 묶음이다. |
| unified Android model 완료 | FAIL | `unified_walksafe` TFLite asset이 없다. 현재 runtime 기대값은 legacy fallback이다. |
| AIHub189 = Android ARCore PASS | FAIL | AIHub189는 offline ZED reference다. `arcore_pass=false`가 맞다. |
| Android ARCore depth/bbox field PASS | BLOCKED | 설치/launch smoke 외에 실제 bbox-depth 정합, N보, TTS/진동 체감 검증은 없다. |
| `/reports/v2` DB runtime 완료 | BLOCKED | backend 정책 테스트는 통과하지만 PostGIS DB path 32개가 skip이다. |
| auth/reporter 신뢰 | FAIL/OUT_OF_SCOPE | `reporter_user_id`, `source=android`, gate status는 서버 인증값이 아니라 client metadata다. |
| 운영 보안 | OUT_OF_SCOPE | auth/RBAC/rate-limit/signed URL/audit/secret은 release lane이다. |
| backend fake 기본값 | PARTIAL | `DETECT_V2_MODE=fake` 기본값은 demo에는 유용하지만 운영 기본값으로 믿으면 안 된다. |
| PWA/Web 제품 표면 | PARTIAL | 주 사용자 앱이지만 service worker가 opt-in이고 offline queue가 제품 흐름에 연결되지 않았으며 브라우저·실폰·Release 검증이 없다. |

## 2026-07-04에 바로 고친 충돌

1. Android `traffic light`가 일반 장애물처럼 STOP/WARNING을 만들 수 있던 경로를 막았다.
2. `performance_excluded=true`와 fake/demo 판별을 분리했다.
3. `TestCapturePanel` API에 production hard block을 추가했다.
4. `walksafe-test-log` 테스트의 날짜 하드코딩을 제거했다.
5. Android depth scaffold check가 production source에 test-only `DepthSmokePipeline`을 요구하던 오래된 기준을 수정했다.

## 다음 작업 순서

1. 제품 표면 정리: Web/PWA manifest/install/offline 흐름을 제품 기준으로 검증하고, Android debug UI, backend debug router와 fake detector를 실험·개발 경로로 분리한다.
2. DB 신뢰도: PostGIS disposable DB를 띄울 수 있을 때 `/reports/v2` no-skip E2E를 실행한다.
3. 모델 신뢰도: final unified TFLite가 나오면 asset drop-in, tensor smoke, `loaded_model_key=unified_walksafe` evidence를 만든다.
4. 실기기 신뢰도: 정지 smoke 다음 단계로 ARCore depth support, bbox-depth 정합, TTS/haptic/TalkBack 수동 검증을 한다.
5. 운영 신뢰도: auth subject 기반 reporter, admin/export/upload 권한, signed URL, audit/rate-limit을 release lane에서 구현한다.

## 이번 검증 결과

| 명령 | 결과 | 해석 |
|---|---|---|
| `./gradlew :app:testDebugUnitTest` | PASS | Android JVM/unit 수준 |
| `.venv/bin/python -m pytest backend/tests/test_report_policy.py backend/tests/test_reports_v2.py backend/tests/test_navigation_routes.py backend/tests/test_detect_v2.py backend/tests/test_android_debug_logs.py` | 41 passed, 32 skipped | 정책/계약 일부 PASS, PostGIS DB path 미검증 |
| `bash scripts/check_frontend_walksafe_test_log_policy_20260701.sh` | PASS | test capture gate 정책 PASS |
| `scripts/check_frontend_admin_report_summary_policy_20260525.sh` | PASS | admin source/fake/export summary 정책 PASS |
| `npm run typecheck` | PASS | Web typecheck PASS |
| `npm run lint` | PASS | Web lint PASS |
| `.venv/bin/python -m pytest tests/test_aihub189_depthprediction_offline.py` | 5 passed | offline evaluator unit PASS |
| `.venv/bin/python scripts/check_android_tflite_contract_20260531.py` | ok=true, `unified_asset_present=false` | static contract만 PASS. unified 완료 아님 |
| `.venv/bin/python scripts/check_android_depth_scaffold_20260531.py` | PASS | Android scaffold 정적 확인 |
