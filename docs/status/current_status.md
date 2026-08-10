# WalkSafe Assist 현재 상태

> 상태: `HISTORICAL_IMPLEMENTATION_SNAPSHOT_20260716`. 이 문서는 당시 Web/PWA 전제의 구현 관찰을 보존하며 현재 제품 정책이 아니다. 현재 상태는 `../control/walksafe-project-continuation-checkpoint.json`, 제품 경계는 `../../configs/walksafe_product_boundary_20260722.json`, 다음 작업은 `../control/audits/walksafe-implementation-remediation-backlog-20260722-r001.json`을 우선한다.

- 기준일: 2026-07-16 KST
- 범위: 현재 로컬 작업트리의 Web/PWA 주 앱, backend/admin, Android 보조 연구 앱, model/data, voice 문서 기준 요약

## 한 줄 요약

WalkSafe Assist의 주 경로는 **Next.js Web/PWA**다. Web과 ARCore 미지원 Android에 TMAP 경로 권한을 유지하는 low 비계량 카메라 보조 경고가 CODE·AUTO로 연결됐지만, 실제 폰 Field 근거는 없다. 2026-07-11 현장 버전은 epoch270 13-class 모델을 `real`, img768, fallback 없음으로 연결했고 production PWA·Cloudflare 임시 HTTPS·TMAP·voice·PostGIS·field/admin 인증·1 Hz 현장 로그를 함께 실행한다. 합성 카메라 원격 브라우저에서 사람 위험 문구 경로와 손상 점자블록 3프레임 자동 신고가 통과했지만, 이는 현재 비계량 보조 경고나 TTC STOP/high·진동·실폰 안전성 근거가 아니다. 실제 외출용 폰 보행 검증, 약한 class 개선, 안정 도메인과 조직 계정/RBAC는 남아 있어 제품 전체 판정은 여전히 PARTIAL이다.

> 운영 주의: 이 문서는 현재 작업트리와 격리 자동검증을 설명한다. 최신 수정은 clean release commit·named account/새 DB 자격 증명으로 재빌드·재기동하고 `/ready`와 release evidence를 다시 통과하기 전까지 실제 서비스에 배포된 것으로 간주하지 않는다.

## 현재 구현 상태

| 영역 | 현재 상태 | 남은 일 |
|---|---|---|
| Web/PWA | 현장 profile은 production PWA, 기본 `server-v2`, epoch270 real API, camera/GPS/heading, 4초 보조 ROI, TTC 경고, 음성 TMAP 길안내, damage-only 자동 신고를 조립한다. 현재 기본·release 길안내는 TMAP-only다. 일반 점자블록 탐지는 실제 경로를 확정하지 않으며, 감독형 tactile 연구 gate도 명시 flag와 현재 route에 결합된 fresh camera→route projection evidence를 함께 요구하지만 현재 supplier가 없어 비활성이다. 기존 risk evaluator가 alertable하지 않은 후보는 후면 camera·detector·활성 TMAP·foreground/visibility/freshness와 서로 다른 연속 3프레임·700ms gate를 통과할 때만 좌·중앙(정면)·우 low 비계량 보조 경고가 될 수 있다. IMU 안정도는 있으면 추가 gate로 쓰며 전달 전 재검사에 실패하면 폐기한다. 기존 stable bbox/ROI 위험 경고와 명시 동의 damaged-report 경로는 유지한다. 탐지 항목 하나라도 schema·13-class 매핑·threshold 계약을 위반하면 frame 전체를 실패 처리하며 fake/demo로 자동 대체하지 않는다. 한 번의 빈 detection frame은 직전 후보를 유지하고 2회 연속 비었을 때만 위험을 해제한다. 모든 BFF upstream 호출에는 공통 timeout이 있고 Cloudflare 원격 합성 카메라 E2E와 metadata 적재가 통과했다 | 비계량 경고를 포함해 실제 외출용 폰의 camera/mic/TTS/진동/길안내/오탐·미탐·보행 체감을 검증해야 한다. offline은 hydrated authentication/static shell 수준이고 안전 API는 network-only다 |
| Backend v2 | epoch270 SHA-256 고정, img768, unified 단일 model, legacy/fake fallback 없음으로 현장 runtime을 기동한다. `/detect/v2`, damage-only `/reports/v2`, export와 TMAP API가 same-origin proxy 뒤에서 동작한다. 전용 test DB·임시 upload root 전체 회귀와 운영 신고 수 불변, 운영 migration head/기존 행 제약 위반 0건을 확인했다 | 과거 비격리 회귀로 생긴 고신뢰 오염 후보는 read-only audit만 했고 삭제하지 않았다. 운영 자격 증명 회전·새 코드 재배포·backup/restore 뒤 승인 정리가 필요하다 |
| Backend Android debug | `/android/debug/depth-logs`, `/android/debug/depth-logs/recent`, `/android/debug/frame-captures` 구현. 기본 비활성, local/dev metadata-only/developer opt-in 저장 | `ANDROID_DEBUG_LOG_ENABLED=true`로 local/dev에서만 사용. Device PASS나 report evidence로 확대 금지 |
| Admin/Ops | field/admin named account의 HttpOnly HMAC session을 분리하고 로그인 실패 5회/5분 뒤 15분 차단한다. Next BFF는 actor ID와 role을 30초 유효 HMAC assertion으로 backend에 결합하며, backend는 assertion 없는 caller actor header를 거부한다. field는 탐지·길안내·음성·신고만, admin은 목록·상태·upload·export·exact source 기반 0.001° cell 집계 heatmap을 사용한다 | env 기반 named account는 조직 IdP·credential lifecycle·RBAC가 아니다. 기관 제출은 관리자가 agency CSV/manifest를 수동 제출하며 운영 backup/180일 삭제 job은 아직 enable하지 않았다 |
| Android native 보조 연구 | ARCore camera/depth, TFLite detector, bbox/depth overlay, stale guard, TTS/haptic, route UI와 damage-only report client가 연결돼 있다. `AndroidVoiceCommand`는 recognizer 최상위 가설만 사용하고 제공된 confidence가 0.55 미만이면 닫는다. 명시 신고·목적지 설정/변경·후보 번호 선택·취소·다음 안내·길안내 중지를 action에 연결하고, 후보 상위 3개의 번호·이름·주소·거리를 bounded 음성으로 읽는다. 검색 실패·선택 전에는 기존 경로를 보존하며 `risk > interaction > navigation`과 위험 전 STT 취소를 적용한다. production 요청은 named actor/account로 HTTPS Next gateway session을 만든 뒤 cookie로만 전송한다. ARCore·Depth 지원 단말은 기존 `ARCORE_METRIC` 연구 경로를 유지한다. ARCore 미지원 단말은 설치 가능한 CameraX+TFLite 제한 모드이며 camera permission·detector·fresh IMU·활성 TMAP gate가 맞을 때만 `CAMERA_IMU_NON_METRIC` low 방향 보조 경고를 낸다. 서로 다른 연속 3프레임·700ms와 전달 시점 tier·lifecycle·freshness를 재검사하고 실패하면 `TMAP_ONLY`다. 같은 심각도 경고는 전달 중 항목을 포함한 최대 3개 bounded FIFO로 순차 처리하며, 실제 TTS 완료 또는 TalkBack 전달 확인 뒤에만 cooldown을 시작한다. 전달 실패·gate/TTL/후보 소실은 예약을 폐기한다. P-09에 따라 이 low 경고는 진동하지 않는다. 제한 모드는 거리·보폭·STOP/high·local steering·경로 변경·report 후보를 만들지 않으며 기존 ARCore metric 위험·tactile 연구와 damage-only report client는 유지한다 | JVM 회귀·lint·debug/AndroidTest APK assemble/SHA와 연결 실기기 instrumentation 2/2는 기존 범위에서 PASS다. ARCore 미지원 실기기의 CameraX/IMU/TTS/TalkBack·오탐·미탐·지연은 `FIELD UNVERIFIED/OPEN`이다. ARCore metric 대화형 launch 및 camera→preview/bbox/depth·`N보`·mic/TTS/haptic·gateway 전체 흐름, FPS/지연과 현장 로그도 Device Field 검증이 필요하다 |
| Android runtime config | `app/src/main/assets/model-config/two_model_runtime.json`이 Android asset/input/class/threshold source of truth다. epoch270 PT에서 export한 unified float32 TFLite를 primary로 적용했고 asset SHA-256과 768 입력·13-class·`[1,768,768,3]`→`[1,300,6]` tensor 계약을 고정했다. legacy pair는 unified load·hash·tensor 실패 시의 통제된 fallback으로만 남기며 최근 계측은 `loaded_model=unified_walksafe`, `fallback_used=false`였다 | 연결 실기기 instrumentation에서 unified와 legacy asset 계약·load/invoke 2/2는 통과했지만 배포 적격이나 전체 Device PASS가 아니다. 대화형 camera pipeline의 fallback 미발생·FPS와 실외 동작을 확인해야 한다 |
| Model/static data | 13클래스 통합본은 이미지 196,506장, bbox 607,814개다. YOLO26n img768 epoch270 best를 2026-07-11 관찰용 field candidate로 적용했다. mAP50 0.554, mAP50-95 0.417이며 전동킥보드 원천의 공식 식별은 AIHub 572다 | field 적용은 출시 승인이 아니다. AIHub 전동킥보드 동일 촬영 sequence의 train/val 누수와 materialized image/label hash 부재가 확인돼 registry blocker로 추가했다. sequence-safe 재분할·per-file hash 재생성·재학습·독립 test가 필요하다 |
| Voice/STT/TTS | Web local STT는 순수 executor를 통해 목적지·후보·길안내·신고 callback을 실제 dispatch하며 spy 회귀로 검증한다. Android `SpeechRecognizer`도 신고·목적지 설정/변경·후보 번호 선택·취소·다음 안내·중지를 action에 연결한다. 두 경로 모두 실행 명령 부정형과 유효하지 않은 선택을 no-op 처리한다. browser TTS가 경고·안내를 담당한다 | 실제 외출용 폰 mic, 주변 소음, TTS 청취, TalkBack은 아직 PASS가 아니다 |
| Navigation | 목적지까지 전역 경로는 TMAP live POI와 `STAIR_AVOID` 보행 경로가 담당한다. 이전 progress를 anchor로 교차·왕복 구간 점프를 막고, 거리 미상 단일 목적지 후보는 자동 선택하지 않는다. confirmed off-route에서는 reroute 진행·cooldown·최대 횟수 상태 모두 기존 guide·stale 도착을 억제하고 rate-limit된 대기 안내만 반환한다. Web/Android 현재 기본·release 전역 경로는 모두 TMAP-only다. Web의 supervised tactile 연구 gate는 명시 flag와 현재 TMAP route ID에 결합된 fresh `walksafe.camera_route_projection.v1` evidence가 동시에 있어야만 후보가 되지만, 현재 주 조립 경로에는 evidence supplier가 없다. Android ARCore metric supplier는 route-bound 연구 경로로 연결돼 있다. 일반 정상 점자블록 detection·bbox·heading·camera ROI만으로 실제 경로나 조향을 확정하지 않는다. Web `CAMERA_NON_METRIC_ADVISORY`와 ARCore 미지원 Android `CAMERA_IMU_NON_METRIC`은 TMAP 경로를 바꾸거나 안전 경로·조향을 만들지 않는 low 보완 안내뿐이며 gate 실패 시 `TMAP_ONLY`다 | projection supplier와 감독형 Field 검증 전에는 tactile local steering을 완료로 주장하지 않는다. Web 실제 폰, ARCore 미지원 Android 실기기, Android metric calibration/EIS·GPS drift·tactile local steering과 실외 목적지 보행 품질이 모두 Field 미검증이다 |

## 2026-07-11 원거리 현장 빌드 근거

- runtime config: `configs/walksafe_unified_epoch270_field_20260711.json`
- model source: `walksafe_unified_yolo26n_epoch270_sha256_a38857e999e1`
- model SHA-256: `a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669`
- runtime: `real`, `unified_walksafe`, img768, `runtime_fallback_model=null`
- TMAP live: 서울역 POI 3개, 서울 도심→서울역 `STAIR_AVOID` 1,961m/1,504초, 16 steps/14 guides route 응답 확인
- 원격 person 시나리오: 실제 모델 bbox, 3프레임 이후 “멈추세요” UI와 JSONL을 확인한 안전정책 수정 전 historical artifact. 현행은 trusted depth 없이 bbox-scale TTC만으로 high/STOP을 만들지 않으므로 현재 STOP 회귀 근거가 아니다.
- 원격 damaged 시나리오: 신뢰도 약 0.91, 3개 연속 프레임, GPS 5m, 무음 자동 신고, PostGIS 저장 PASS
- production 수동 판정: field session과 명시 동의 뒤 `맞음 저장`이 JPEG/JSON/JSONL로 현재 run에 저장되는 것 PASS
- damaged 합성 신고는 관리자 이력에 `synthetic_integration_test`로 검수·종결해 실제 field evidence와 구분했다.
- production UI에서 개발 overlay가 없고 390x844 모바일 viewport의 헤더·bbox·경고·상태 영역을 확인했다.
- 실행/체크리스트: `docs/testing/web_remote_field_test_20260711.md`

## Android 보조 연구 APK 현재 빌드와 과거 Device smoke

```text
APK: apps/android/app/build/outputs/apk/debug/app-debug.apk
SHA-256: 9ab74fd181c587f1ab46df08cc3839c7bb9641c253c80a35a083dee2b2e4c9e5
bytes: 94,936,131
AndroidTest SHA-256: deaaa81863a4c981431ada91ec2f5ee5756bd2fdb493ccfdced9cc5e210d0824
AndroidTest bytes: 369,615
```

위 hash는 2026-07-13 검증 debug/AndroidTest assemble 결과다. 이후 source를 수정했다면 최종 빌드 hash로 다시 갱신해야 한다. 연결 `SM-G981N` instrumentation에서 unified와 legacy asset 계약·load/invoke 2/2를 통과했지만, 대화형 launch 기록은 이전 APK의 historical smoke이므로 현재 변경본 전체 Device 근거로 사용하지 않는다.

기존 debug APK는 source commit을 주입하지 않아 DEX provenance가 `unverified`이고, 실행 중 backend도 아직 `WALKSAFE_SOURCE_COMMIT`을 노출하지 않는다. 따라서 이 APK는 보안 활성 `/reports/v2`의 성공 근거가 아니다. 공식 Android field 검증은 clean commit을 주입한 고정 debug field APK를 실제 기기에 설치하고 그 APK SHA를 field summary에 결속한다. 서명 release APK는 같은 source commit의 별도 SHA로 검증하며, debug field 결과를 release APK 자체의 실기기 실행 근거로 주장하지 않는다.

검증 완료 범위:

- `python scripts/check_android_tflite_contract_20260531.py` → PASS, `check_scope=static_contract_only`, `unified_asset_present=true`, `expected_runtime_model=unified_walksafe`, `expected_fallback=false`
- `python scripts/check_android_depth_scaffold_20260531.py` → PASS
- `python scripts/export_android_tflite_models_20260531.py --dry-run` → PASS
- `cd apps/android && ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --no-daemon` → BUILD SUCCESSFUL, JVM 214/214, failures/errors/skips 0
- `cd apps/android && ./gradlew connectedDebugAndroidTest --no-daemon` on `SM-G981N` Android 13 → unified와 legacy asset 계약·load/invoke instrumentation 2/2 PASS
- 2026-07-02 scoped backend 기록: `PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -m pytest -p no:cacheprovider backend/tests/test_report_policy.py backend/tests/test_android_debug_logs.py backend/tests/test_reports_v2.py backend/tests/test_navigation_routes.py -q` → 24 passed, 32 skipped(PostGIS 미기동)
- `cd apps/web && npm run typecheck` → PASS
- `cd apps/web && npm run lint` → PASS
- `bash scripts/check_frontend_admin_report_summary_policy_20260525.sh` → PASS
- `bash scripts/check_frontend_walksafe_test_log_policy_20260701.sh` → PASS
- `PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -m pytest -p no:cacheprovider tests/test_aihub189_depthprediction_offline.py -q` → 5 passed, 8 warnings
- `scripts/evaluate_aihub189_depthprediction_offline.py --full-original-zip-run` → AIHub189 `Depth_001~005.zip` original full offline run completed, frames/evaluated 2461, buckets stop 883 / warning 652 / ignore 926, `source_kind=offline_zed_reference`, `arcore_pass=false`, `detector_input_kind=generated_probe_bbox`
- `MetadataCaptureLogTest` → Gradle unit test에 포함
- `adb -s <연결 기기> install/start/dumpsys/logcat` → install Success, launch Status ok/COLD/TotalTime 480ms, `MainActivity` RESUMED/visible/reportedDrawn, AndroidRuntime:E empty

2026-07-10 재감사 결과(historical snapshot):

아래 고정 건수는 당시 작업트리 기록이며 최신 회귀 건수로 인용하지 않는다.

- 당시 non-isolated PostGIS 결과만으로 한 clean PASS 주장은 사용하지 않는다. 현재는 fail-closed로 강제된 별도 test DB와 임시 upload root에서 전체 PostGIS 회귀가 **skipped 0 PASS**이고 운영 신고·기존 test upload 수가 변하지 않음을 다시 확인했다. 고정 test count는 작업트리에 따라 달라지므로 historical daylog 기록으로만 남긴다.
- voice unit은 PASS했고 실제 cached sample `신고해.m4a`는 `transcript=신고해`, `intent=create_report`로 분류됐다.
- Web lint/typecheck와 PWA/risk/report/navigation/settings/admin 정책 스크립트 → PASS.
- Android `./gradlew testDebugUnitTest assembleDebug --no-daemon`은 당시 PASS했지만 현재 변경본 APK 근거가 아니다.
- current 13-class dataset validator → train 167,759, val 28,747, boxes 607,814, PASS.
- 분류 validator는 재감사 시작 시 삭제된 dataset 경로 때문에 FAIL했다. current filesystem 기준 153,919 repo rows/86 Downloads rows로 재생성한 뒤 전체 6개 검사가 PASS했다.

2026-07-13 최신 자동검증 경계:

- Backend·root Python의 과거 non-isolated PostGIS 기록 자체는 clean regression 근거로 쓰지 않는다. 최종 Unit Python 238개, 격리 Functional Python/PostGIS 241개·0 skip, Integration Python 109개·0 skip과 backend full 316개가 PASS했고 canonical PT warm-up과 Alembic head `202607130002`도 통과했다.
- Web: test·typecheck·lint·전체 frontend policy check와 실제 Next 16.2.6 production build(Turbopack 경고 0), NFT 58 traces/1,307 unique files 범위 검사 PASS.
- Voice: 129개 회귀에서 부정문·모호/정정 목적지 `safe_noop`, acoustic gate, 입력 길이, event-loop 외 추론, bounded TTS queue/cancellation PASS.
- Android: JVM 214/214, lint, depth scaffold, debug/AndroidTest APK assemble PASS. 연결 `SM-G981N` instrumentation에서 unified와 legacy asset 계약·load/invoke 2/2도 PASS했다. native 음성은 최상위 가설·confidence gate, 신고·목적지 설정/변경·상위 3개 후보 열거·번호 선택·취소·다음 안내·길안내 중지와 fail-closed 분기를 JVM에서 검증했다. route/POI response는 요청 endpoint 100m 정합, 양수 summary, geometry-summary 비율, guide 순서와 query/limit/ID/type 전체 계약을 확인하며 item 하나라도 잘못되면 전체 응답을 거부한다. confirmed off-route에서는 “다음 경로” 질의도 기존 도착/회전 안내를 합성하지 않는다. 현재 변경본의 대화형 mic/TTS·launch와 ARCore camera/depth 전체 Device Field는 미검증이다.

Android 보조 연구 실기기 관련 제한:

- 현재 수신된 `logs/android_remote_debug_20260601` 로그 363건은 `8a84a03e...` APK보다도 이전 기록이며 현재 `9ab74fd1...` APK 근거가 아니다.
- 이전 로그에서는 `transform_path=identity`/`arcore_mapper_not_connected` 363/363, frame timestamp gap 2~3초대가 보여 depth와 객체가 어긋날 가능성이 컸다.
- 당시 변경 전 APK를 연결 폰에 `-r` 설치해 빌드본·설치본 SHA-256 일치, 카메라·위치·신체 활동·마이크 권한, process 기동과 crash 부재를 확인했다. 현재 변경본은 instrumentation harness 설치·TFLite invoke만 확인했으며 대화형 launch·권한·화면 근거는 아니다.
- USB reverse를 통해 폰에서 backend health, fake `/detect/v2`, PostGIS `/reports/v2`, 상태 변경과 필터 CSV export를 통과했다. 합성 좌표·fake source를 사용했으므로 모델 성능 근거는 아니다.
- 폰이 PIN 잠금 상태여서 field session 버튼, camera preview, ARCore Depth, bbox/`N보`, TTS/haptic/음성 체감은 아직 BLOCKED다.

## v2 detection/report 계약

- `GET /detect/v2/health`
  - admin 전용 runtime health이며 model/runtime config는 basename만 반환한다.
  - v2 provider mode, model path, runtime config readiness를 확인한다.
  - `fake` mode는 모델 path 없이 ready다.
  - `yolo`/`real` mode는 path/config 정적 상태를 보여준다. 실제 serving readiness는 아래 `/ready`의 warm-up을 통과해야 한다.
- `GET /ready`
  - field 접근 readiness다. DB 연결·Alembic head, upload root의 실제 fsync 가능한 쓰기, detector artifact/config binding과 실제 blank-frame model load·inference·class order warm-up, TMAP 보행 provider 고정·서버 키 존재를 함께 확인한다.
  - 하나라도 실패하면 HTTP 503과 `not_ready`를 반환하며 liveness `/health`와 구분한다.
- `GET /detect/health`
  - field에서 사용하는 legacy readiness이며 status/version/reason과 sanitized class order·confidence/IoU/image size를 반환하되 model artifact는 basename만 노출한다.
- `POST /detect/v2`
  - 일반 개발 기본값은 fake contract일 수 있으나 2026-07-11 field profile은 `real`로 고정한다.
  - `DETECT_V2_MODE=yolo` 또는 `real`에서 `DETECT_V2_UNIFIED_MODEL_PATH`가 있으면 unified 단일 모델을 우선 사용하고, 없으면 custom tactile+COCO legacy pair가 모두 있을 때 fallback provider를 사용한다.
  - field runner는 epoch270 SHA-256, runtime config, img768, fallback null을 확인한 뒤에만 준비 완료로 판정한다.
- `POST /reports/v2`
  - `unified_walksafe` 또는 legacy `custom_tactile`의 `damaged_tactile_block`만 허용한다.
  - `tactile_damage_area`, `normal_tactile_block`, COCO/general 객체와 unified의 일반 객체는 거부한다.
  - Web v2 자동 신고 hook은 신뢰도 0.70, GPS 15m, 서로 다른 동일 후보 camera frame 3개와 최소 700ms를 통과한 뒤 이미지·위치·metadata를 전송한다. 합성 브라우저→PostGIS E2E는 통과했지만 실제 보행 현장 완료 근거는 아니다.
- `GET /reports/export`
  - 관리자/운영자가 현재 필터 조건의 신고 목록을 최대 10,000행까지 CSV 기본, JSON/GeoJSON 옵션으로 내보낸다. 초과 시 자르지 않고 413으로 필터 축소를 요구한다.
  - `agency`는 named human admin이 reviewed·damaged·high≤15m·non-fake로 검수한 정확 좌표 최소 필드만 허용하며 `image_path`와 image binary는 포함하지 않는다.
  - 관리자는 agency 파일을 기관별 외부 채널에 별도로 수동 신고한다. 기관 API 자동 전송은 없고 접수 receipt 기록 도구만 있으며 실제 접수 근거는 없다.

## 사용자 안내/신고 정책

- 손상 점자블록(`damaged_tactile_block`): 자동/음성 요청 신고 대상이다. 자동 신고만 있을 때 사용자 TTS는 기본으로 내보내지 않는다.
- 손상 영역(`tactile_damage_area`): 보조 bbox 정보이며 신고 기준으로 쓰지 않는다.
- 일반 객체: 신고 대상이 아니다. 장애물, 접근 객체, 경로 차단 등 보행 위험 상황일 때만 음성 경고 대상이다.
- 공공기관 제출: API 자동 연계는 제품 범위에 넣지 않는다. 관리자가 내부 검수와 `/reports/export` CSV 다운로드 후 기관 외부 채널에 별도로 수동 신고한다.
- 서버 장애/fake fallback: 실사용 `server-v2`에서는 fake-v2로 자동 전환하지 않는다. fake-v2는 데모 전용이다.
- STT/음성: Web은 순수 executor와 callback spy로 목적지 설정·후보 선택·취소·다음 안내·시작·중지 dispatch를 검증했다. Android 보조 앱도 명시 신고·목적지 설정/변경·후보 번호 선택·취소·다음 안내·길안내 중지를 연결하고 부정문·검색 중·범위 밖 번호를 no-op 처리한다. 두 경로 모두 실폰 mic/TTS 청취와 보행 중 인식률은 아직 PASS가 아니다.

## 다음 실행 gap

1. 모델 품질 gate 복구
   - corrupt image/label 2,331장을 복구 또는 제외하고 `curb_step`, `uneven_sidewalk`, `crosswalk`, `normal_tactile_block` 라벨 기준을 재검수한다.
   - 출처와 분리된 독립 test/field set에서 목표 지표를 다시 정의하고 측정한다.
2. Web 모바일 현장 검증
   - camera/GPS/heading/mic/TTS/진동, 목적지 검색·길안내·재탐색·신고 흐름을 실폰 브라우저에서 확인한다.
   - field JSONL과 맞음/틀림 수동 판정을 함께 분석해 class별 recall·오탐·지연 출시 하한을 정한다.
   - Android overlay/depth/TFLite는 보조 연구 evidence로 별도 기록한다.
3. 현장 profile의 release 승격
   - Cloudflare quick tunnel을 안정 domain/TLS, monitoring, backup/rollback이 있는 환경으로 교체한다.
   - env 기반 named field/admin account를 조직 IdP, credential lifecycle과 역할 기반 인증으로 교체한다.
4. 운영 DB·보존 E2E
   - 전용 `walksafe_test` DB의 no-skip 회귀를 정기적으로 유지하고 운영 환경의 auth/RBAC, audit, 보존·복구를 검증한다.
5. 운영 보안과 남은 원안 gap
   - 현재 alpha-beta GPS/step/route projection을 기준 궤적과 비교하고 정밀 측위 개선 여부를 판단한다.
   - local model registry·승격·rollback·격리 도구를 자동 재학습/무중단 배포로 과장하지 않고 후속 MLOps lane으로 확장한다.

## 모델/데이터 상태

- unified 후보: YOLO26n 13-class, img768, 300 epoch 완료. 현재 후보는 epoch 270 best PT다.
- class order: `person`, `bicycle`, `car`, `motorcycle`, `bus`, `truck`, `traffic light`, `normal_tactile_block`, `damaged_tactile_block`, `crosswalk`, `curb_step`, `uneven_sidewalk`, `e_scooter_obstruction`.
- dataset: `datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/data.yaml`.
- 전동킥보드 source의 공식 식별은 AIHub 572이며, dataset/run 경로의 `aihub183`은 레거시 내부 별칭이다.
- 최신 평가·출처·그래프: `docs/model-data/latest_model_report_20260710/WalkSafe_최신_모델_종합보고서_20260710.md`.
- 모델 평가와 2026-07-11 backend field runtime은 모두 img768이다. 그래도 validation GPU 지표를 모바일 네트워크 포함 E2E 지연으로 간주하지 않는다.
- 후보 weight: `model/artifacts/candidates/walksafe_13cls_yolo26n_img768_20260708/walksafe_13cls_yolo26n_img768_best_epoch270.pt`, SHA-256 `a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669`.
- Android APK source에는 unified float32 13-class asset이 runtime primary로 적용됐다. SHA-256은 `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19`, 입력/출력은 `[1,768,768,3]`/`[1,300,6]`이며 최근 계측은 `fallback_used=false`였다. legacy pair는 unified load·hash·tensor 실패 fallback으로만 남으며, 이 사실은 Device Field나 배포 승인을 뜻하지 않는다.
- 모델 weight, run 산출물, materialized dataset, `.tflite` asset은 로컬 전용이다.

## 문서 기준

- 전체 문서 지도: `docs/README.md`
- 최신 원안/코드 구현 감사: `docs/status/implementation_audit_20260710.md`
- Web/PWA 실행/상태: `apps/web/README.md`
- Android 보조 연구 설치/상태: `apps/android/README.md`
- Android depth/TFLite 구조: `docs/android/arcore_depth_estimation_architecture.md`
- Android 과거 gate 계획 snapshot: `plans/features/2026-06-01_android_native_gate_execution_plan.md` (최신 기준은 이 문서와 Android README 우선)
- Android 실기기 체크리스트: `docs/android/android_device_overlay_depth_checklist_20260601.md`
- Android 정지·원거리 로그 체크리스트: `docs/testing/android_stationary_and_field_test_checklist_20260710.md`
- 2026-07-11 외출용 폰 통합 체크리스트: `docs/testing/phone_field_test_master_checklist_20260710.md`
- Web/PWA 원거리 현장 실행서: `docs/testing/web_remote_field_test_20260711.md`
- 제작설계 상세 DOCX 8종: `docs/submission/design_documents/README.md`
- Downloads 삭제 준비 판정: `docs/inventory/downloads_cleanup_readiness_20260710.md`
- Android metadata schema: `docs/android/android_metadata_capture_schema_20260601.md`
- Android server debug log runbook: `docs/android/android_server_debug_log_runbook_20260601.md`
- Android RGB-D dataset schema: `docs/android/arcore_rgbd_dataset_schema_20260601.md`
- Android static threshold evidence: `docs/evidence/android_static_threshold_evidence_20260601.md`
- Android report source/metadata draft: `docs/walksafe-v2/android_report_source_metadata_decision_20260601.md`
- Android 실행 기록: `docs/execution/2026-05-31_android_static_dataset_contract_progress.md`
- v2 source of truth: `docs/walksafe-v2/README.md`
- v2 API 계약: `docs/walksafe-v2/backend_api_contract.md`
- 신고 운영: `docs/operations/report_operations.md`
- `docs/execution/`은 과거 실행 기록 성격이 강하며, 최신 구현 기준은 위 문서들을 우선한다.
