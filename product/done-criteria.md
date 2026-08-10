# Done Criteria

> 상태: `SUPERSEDED_PRODUCT_BOUNDARY_SNAPSHOT`. 아래 Web/PWA 중심 완료기준은 역사 자료다. 현재 완료경계는 `docs/control/walksafe-project-resumption-runbook.md`의 `IMPLEMENTATION_READY`와 EPIC-12 정식 검증 분리를 따른다.

## 완료로 인정하는 기준

- 구현 완료만으로는 `done`이 아니다.
- 실제 실행, 테스트, smoke, 스크린샷, 로그, 리포트 중 하나 이상의 근거가 있어야 한다.
- 실행하지 않은 항목은 `미검증`, `BLOCKED`, `확인 필요`로 남긴다.
- fake, headless, local audio, model metric, Android Device, release 검증은 서로 다른 등급으로 기록한다.
- Web/PWA가 현재 주 경로다. 정적/headless 결과는 모바일 브라우저 Field/Release PASS를 대체하지 않는다. Android Device evidence는 보조 연구 경로로 분리한다.
- PDF 원안 기능은 현재 구현 여부와 분리한다. PDF에 적힌 목표는 근거가 아니라 목표다.
- 모든 완료 판단에는 근거 파일 경로와 기준 APK/hash 또는 dataset/hash를 남긴다.

## 기능 완료 기준 템플릿

| 기능/흐름 | 완료 조건 | 필수 검증 | 증거 파일/로그 | 상태 |
|---|---|---|---|---|
| 보행자 Web/PWA 주 앱 | camera, GPS/heading, real detector, 위험 TTS/진동, 음성 길안내, 신고가 HTTPS 모바일 브라우저에서 동작 | lint/typecheck/build + 실폰 browser E2E + PWA install/offline policy | `apps/web/README.md`, 향후 release/field evidence | 코드 연결, Field/Release 미완료 |
| Android 보조 연구 APK 빌드 | debug APK가 생성되고 설치 경로와 SHA-256이 기록됨 | `./gradlew test`, `./gradlew assembleDebug`, APK hash | `apps/android/README.md`, `docs/status/current_status.md` | 빌드 PASS |
| Android 보조 연구 ARCore depth | ARCore preview, Raw/Full Depth snapshot이 runtime에서 동작하고 unavailable 경로가 안전함 | Android unit/static + 실기기 관찰 | `docs/android/arcore_depth_estimation_architecture.md` | 부분 PASS, Device 정합 미완료 |
| Android 보조 연구 TFLite | JSON config 기반 unified 768 13-class primary의 asset/hash/input/output/class/allowlist/threshold로 local detection 실행 | `check_android_tflite_contract`, Gradle test, 실기기 관찰 | `apps/android/README.md`, `docs/status/current_status.md` | asset/runtime Static·Unit과 연결 실기기 instrumentation load/invoke 1/1 PASS, camera pipeline·FPS·Field 관찰 필요 |
| Android 보조 연구 bbox 좌표 | overlay bbox가 실제 객체 위에 대략 맞고 회전/미러/크롭 오차가 기록됨 | ARCore 실기기에서 중앙/좌우/상하 관찰 | 향후 field note | 미검증 |
| Android 보조 연구 depth/`N보` | depth sample median이 같은 객체 이동과 실측 거리 변화에 일치 | RGB-D/실측 거리, sample count/ratio/median 기록 | 향후 RGB-D/Device report | 미검증 |
| 신고 생성/운영 | `damaged_tactile_block` 신고가 `/reports/v2`에 저장되고 관리자가 검수·필터 후 CSV를 내려받아 외부 채널에 수동 신고 | disposable DB API smoke, 모바일 Web E2E, CSV 검수 절차 | `docs/operations/report_operations.md` | 코드 연결, DB/auth/운영 검증 대기 |
| server detector mode | backend `/detect/v2` yolo/real provider가 품질 승인 모델로 응답하고 Web `server-v2` source가 분리됨 | ASGI/API smoke + model path/hash + browser latency/FPS | `docs/walksafe-v2/backend_model_integration_notes.md` | fake 기본, real release 설정 필요 |
| 모델/static eval | dataset split, metric, threshold, checkpoint/hash가 명시되고 핵심 클래스 성공 기준을 충족 | model eval scripts, 독립 test/field set, CSV/JSON summary | `reports/walksafe_best_eval_20260708/final_evaluation_report.md` | PT 300 epoch 완료. 전체 mAP50 0.554지만 단차/불균일 보도와 corrupt data 때문에 final 아님 |
| STT 음성 명령 | 모바일 Web mic에서 목적지 검색·변경·취소/후보/길안내/재탐색/중지/다음 안내 질의/신고 intent와 UI action이 확인됨 | browser MediaRecorder→local STT E2E/Field | `docs/status/voice_stt_tts_status.md` | Web 순수 callback dispatch와 Android 신고·목적지·후보 번호·다음 안내·중지 action/fail-close PASS, 모바일 Field 미완료 |
| TTS/haptic Web | browser `speechSynthesis`와 진동, `risk > interaction > navigation` 중재와 중복 발화 억제가 모바일 브라우저에서 동작 | Web policy + 실폰 청취/진동/audio focus | 향후 Web field evidence | code connected, Field 미완료. Qwen TTS prototype으로 대체 불가 |
| Navigation/TMAP | TMAP-only backend proxy와 전역 route를 유지하고, 정상 점자블록이 3 frame·700ms/confidence≥0.55/fresh≤1,200ms/GPS≤25m/heading≤35°/camera corridor를 모두 만족할 때만 local short-range steering으로 우선하며 그 외에는 TMAP으로 복귀 | route contract + 단위/기능/통합 test + 실폰 Web 길안내 E2E | `docs/walksafe-v2/navigation_integration_policy.md` | code connected, outdoor/voice/tactile field 미완료. 전체 점자블록 경로망·안전 보장 아님 |
| MLOps/Release | 사용자 동의, storage, 배포, rollback, release evidence가 승인된 범위에서 기록됨 | release gate, 배포 smoke | 향후 release/runbook | blocked_C/확인 필요 |

## PDF 정량 목표 처리

| 목표 | 완료로 쓰기 위한 조건 | 현재 상태 |
|---|---|---|
| 객체 인식 정확도 90% 이상 | 어떤 metric인지 정의하고, 한국 test/field 기준으로 재현 가능한 리포트 필요 | 미달/미확인. Stage1 image-level 수치와 Android TFLite field 성능은 구분 |
| 경보 지연 1초 이내 | camera frame→TFLite→depth→TTS/haptic 시작까지 기기 지정 측정 필요 | Android frame drop은 완화됐지만 end-to-end latency PASS 아님 |
| `N보` 거리 정확도 | ARCore RGB-D와 실측 거리 기준 MAE/RMSE 또는 bucket error 필요 | 미검증 |
| 월 1회 이상 재학습 | 데이터 수집 동의, pipeline, 모델 버전, rollback, 성능 비교 필요 | C/B 보류 |
| 초기 대비 인식 성능 15% 이상 향상 | baseline metric과 비교 metric 정의 필요 | 미완료 |
| 단위 테스트 커버리지 60% 이상 | coverage 도구와 대상 범위 정의 후 CI 기록 필요 | 미확인 |
| 최종 보고서/소스코드/배포 URL | release evidence checklist와 제출 기준 충족 | M3 보류 |
| PWA 성과목표 | HTTPS/domain에 Web/PWA가 배포되고 install·camera·GPS·mic·detector·신고 smoke 근거가 있음 | 플랫폼 확정, Release 미완료 |

## 검증 등급

| 등급 | 의미 | 예시 |
|---|---|---|
| Static | 코드/문서/설정 정적 확인 | `git diff --check`, grep, py_compile, manifest row count |
| Unit | 단위 테스트 | JVM/Python/TS unit test, schema validation |
| Integration | 서버/DB/API 통합 | API smoke, PostGIS reports no-skip, voice HTTP contract |
| Headless E2E | 브라우저/fixture 기반 사용자 흐름 | PWA server-mode fixture, ASGI `.pt` smoke |
| Web Mobile Field | 실폰 브라우저 사용자 흐름 | camera/GPS/mic/TTS/진동/길안내/신고 |
| Android Device | 실기기/GUI/센서 사용자 흐름 | ARCore camera/depth, bbox overlay, TTS/진동, mic, TalkBack |
| Model Eval | 모델/데이터 검증 | test split mAP/F1, threshold sweep, checkpoint/hash |
| GIS/Ops | 공간 데이터 운영 검증 | PostGIS radius/cluster, GeoJSON export, status 처리 |
| Release | 배포/제출/운영 | domain, auth, storage, rollback, monitoring, release artifact |

## evidence badge 규칙

- badge는 `[등급 상태]` 형식으로 쓰고, `PASS`에는 실행 명령/환경/날짜/증거 경로를 함께 남긴다.
- Android Device PASS에는 APK hash, 기기/OS, 관찰 절차, 화면/로그 근거를 남긴다.
- fake/mock/local fixture는 해당 Static/Unit/Headless 근거로만 쓰며, 실제 보행 안전·Android Device·Model·Release PASS로 확대하지 않는다.
- Headless/ASGI/browser fixture PASS는 모바일 Web 카메라·GPS·TTS/진동·mic·TalkBack Field PASS가 아니다.
- Model PASS는 dataset split, metric 정의, class 범위, checkpoint/hash를 함께 제한해 표기하고 fake/demo는 제외한다.

## 완료 금지 조건

다음은 완료로 쓰지 않는다.

- fake/mock 결과를 실제 운영 검증처럼 표현
- headless smoke를 Android 실기기/GUI 검증처럼 표현
- 기존 PASS를 최신 변경 이후 PASS처럼 재사용
- 사용자가 정해야 할 값/계정/경로/배포 여부를 임의 결정
- 운영 DB/secret/Play Console/destructive QA를 승인 없이 실행
- static RGB 결과를 ARCore depth/`N보` 정확도 근거로 사용
- overlay가 실제 객체와 맞는지 보지 않고 TTS/haptic/report upload 완료로 표현
- Android threshold와 backend threshold가 같다고 근거 없이 표현
- STT local sample 성공을 모바일 Web mic/TTS E2E 완료로 표현
- AWS/S3, 외부 지도 API 고도화, 지자체 API, Cloud STT/TTS, 공공 데이터셋 공개, Blue-Green 배포를 구현/검증 없이 실제 운영 완료로 표현

## 기능별 증거 우선순위

| 기능 영역 | 1순위 증거 | 2순위 증거 | 완료 판정 주의 |
|---|---|---|---|
| Web/PWA | 모바일 브라우저 기록, release URL/config, screenshot/log | lint/typecheck/headless | static/headless PASS는 field/release PASS가 아님 |
| Android 보조 연구 | Android 실기기 기록, APK hash, log/screenshot | Gradle test/build | Web 제품 PASS와 분리하고 build PASS는 field PASS가 아님 |
| Depth/`N보` | RGB-D/실측 거리 기록 | unit fixture | static RGB는 depth 근거 아님 |
| Backend/PostGIS | no-skip pytest, HTTP smoke, disposable DB 기록 | ASGI no-DB smoke | DB skip이 있으면 runtime 완료 아님 |
| Detector | Android TFLite + Device 관찰, model metric | backend ASGI `.pt` smoke | fake와 server/android 성능 분리 |
| Model | 한국 test/validation metric, threshold sweep, failure review | saved predictions analysis | class/threshold/dataset 범위 제한 |
| Voice | Web mobile browser mic + UI action | local audio script | local sample은 E2E가 아님 |
| GIS/Ops | GeoJSON/export/status workflow | DB row 확인 | 실제 지자체 연계와 구분 |
| Release | 승인된 env/secret/storage/domain + rollback 또는 명시적 demo/mock release evidence | local runbook | 실제 외부 연동은 별도 승인 전 실행 금지 |
