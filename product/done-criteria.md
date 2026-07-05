# Done Criteria

## 완료로 인정하는 기준

- 구현 완료만으로는 `done`이 아니다.
- 실제 실행, 테스트, smoke, 스크린샷, 로그, 리포트 중 하나 이상의 근거가 있어야 한다.
- 실행하지 않은 항목은 `미검증`, `BLOCKED`, `확인 필요`로 남긴다.
- fake, headless, local audio, model metric, Android Device, release 검증은 서로 다른 등급으로 기록한다.
- Android native가 현재 주 경로다. PWA/headless 결과는 Android Device PASS를 대체하지 않는다.
- PDF 원안 기능은 현재 구현 여부와 분리한다. PDF에 적힌 목표는 근거가 아니라 목표다.
- 모든 완료 판단에는 근거 파일 경로와 기준 APK/hash 또는 dataset/hash를 남긴다.

## 기능 완료 기준 템플릿

| 기능/흐름 | 완료 조건 | 필수 검증 | 증거 파일/로그 | 상태 |
|---|---|---|---|---|
| Android native APK 빌드 | debug APK가 생성되고 설치 경로와 SHA-256이 기록됨 | `./gradlew test`, `./gradlew assembleDebug`, APK hash | `apps/android/README.md`, `docs/current_status.md` | 빌드 PASS |
| Android ARCore preview/depth | ARCore preview, Raw/Full Depth snapshot이 runtime에서 동작하고 unavailable 경로가 안전함 | Android unit/static + 실기기 관찰 | `docs/android/arcore_depth_estimation_architecture.md` | 부분 PASS, Device 정합 미완료 |
| Android TFLite detector | JSON config 기반 unified-primary/legacy-fallback asset/input/class/allowlist/threshold로 local detection 실행 | `check_android_tflite_contract`, Gradle test, 실기기 관찰 | `apps/android/README.md`, `docs/current_status.md` | Static/Unit PASS, Device 관찰 필요 |
| Android bbox overlay 좌표 정합 | overlay bbox가 실제 객체 위에 대략 맞고 회전/미러/크롭 오차가 기록됨 | ARCore 실기기에서 중앙/좌우/상하 관찰 | 향후 field note | 미검증 |
| Android depth/`N보` 안내 | depth sample median이 같은 객체 이동과 실측 거리 변화에 일치 | RGB-D/실측 거리, sample count/ratio/median 기록 | 향후 RGB-D/Device report | 미검증 |
| 보행자 PWA demo | camera fallback, bbox overlay, detector mode, GPS/heading, TTS/진동이 demo source에 맞게 표시됨 | `npm run lint`, `typecheck`, `build`; 실폰은 별도 | 과거 `docs/execution/*` | 보조 경로 |
| 신고 생성/운영 | `damaged_tactile_block`만 `/reports/v2` 저장 후보가 되고 `/admin`/export에서 검수 가능 | disposable DB API smoke, export CSV/JSON/GeoJSON | `docs/report_operations.md` | backend 부분 PASS, Android upload 보류 |
| server detector mode | backend `/detect/v2` yolo/real lazy provider가 설정된 모델에서 응답하고 source가 분리됨 | ASGI/API smoke + model path/hash | `docs/walksafe-v2/backend_model_integration_notes.md` | backend 경로, Android primary 아님 |
| 모델/static eval | dataset split, metric, threshold, checkpoint/hash가 명시됨 | model eval scripts, CSV/JSON summary | `docs/model_unified_13class_aihub_sources_20260602.md`, `docs/evidence/android_static_threshold_evidence_20260601.md` | unified 학습 전 source plan, legacy saved prediction 부분 가능, full rerun blocked |
| STT 음성 명령 | 브라우저/실폰 또는 Android mic에서 transcript, intent, confidence, UI action이 확인됨 | mic E2E/Device | `docs/voice_stt_tts_status.md` | local prototype PASS, Device 미완료 |
| TTS/haptic Android | gate 통과 후 rate limit과 중복 발화 억제가 동작 | Android unit + 실기기 청취/진동 | 향후 Android execution | gate 전 보류 |
| Navigation/TMAP | backend proxy, mock route, Android/PWA 안내가 위험 TTS를 덮지 않음 | route contract + 실폰 길안내 E2E | `docs/walksafe-v2/navigation_integration_policy.md` | 후순위 |
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

## 검증 등급

| 등급 | 의미 | 예시 |
|---|---|---|
| Static | 코드/문서/설정 정적 확인 | `git diff --check`, grep, py_compile, manifest row count |
| Unit | 단위 테스트 | JVM/Python/TS unit test, schema validation |
| Integration | 서버/DB/API 통합 | API smoke, PostGIS reports no-skip, voice HTTP contract |
| Headless E2E | 브라우저/fixture 기반 사용자 흐름 | PWA server-mode fixture, ASGI `.pt` smoke |
| Android Device | 실기기/GUI/센서 사용자 흐름 | ARCore camera/depth, bbox overlay, TTS/진동, mic, TalkBack |
| Model Eval | 모델/데이터 검증 | test split mAP/F1, threshold sweep, checkpoint/hash |
| GIS/Ops | 공간 데이터 운영 검증 | PostGIS radius/cluster, GeoJSON export, status 처리 |
| Release | 배포/제출/운영 | domain, auth, storage, rollback, monitoring, release artifact |

## evidence badge 규칙

- badge는 `[등급 상태]` 형식으로 쓰고, `PASS`에는 실행 명령/환경/날짜/증거 경로를 함께 남긴다.
- Android Device PASS에는 APK hash, 기기/OS, 관찰 절차, 화면/로그 근거를 남긴다.
- fake/mock/local fixture는 해당 Static/Unit/Headless 근거로만 쓰며, 실제 보행 안전·Android Device·Model·Release PASS로 확대하지 않는다.
- Headless/ASGI/browser fixture PASS는 Android 실폰 카메라·ARCore depth·TTS/진동·mic·TalkBack PASS가 아니다.
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
- STT local sample 성공을 브라우저/실폰/Android mic E2E 완료로 표현
- AWS/S3, 외부 지도 API 고도화, 지자체 API, Cloud STT/TTS, 공공 데이터셋 공개, Blue-Green 배포를 구현/검증 없이 실제 운영 완료로 표현

## 기능별 증거 우선순위

| 기능 영역 | 1순위 증거 | 2순위 증거 | 완료 판정 주의 |
|---|---|---|---|
| Android native | Android 실기기 기록, APK hash, log/screenshot | Gradle test/build | build PASS는 field PASS가 아님 |
| Depth/`N보` | RGB-D/실측 거리 기록 | unit fixture | static RGB는 depth 근거 아님 |
| Backend/PostGIS | no-skip pytest, HTTP smoke, disposable DB 기록 | ASGI no-DB smoke | DB skip이 있으면 runtime 완료 아님 |
| Detector | Android TFLite + Device 관찰, model metric | backend ASGI `.pt` smoke | fake와 server/android 성능 분리 |
| Model | 한국 test/validation metric, threshold sweep, failure review | saved predictions analysis | class/threshold/dataset 범위 제한 |
| Voice | Android/browser/phone mic + UI action | local audio script | local sample은 E2E가 아님 |
| GIS/Ops | GeoJSON/export/status workflow | DB row 확인 | 실제 지자체 연계와 구분 |
| Release | 승인된 env/secret/storage/domain + rollback 또는 명시적 demo/mock release evidence | local runbook | 실제 외부 연동은 별도 승인 전 실행 금지 |
