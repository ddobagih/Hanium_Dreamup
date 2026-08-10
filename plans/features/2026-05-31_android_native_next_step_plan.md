# Android native ARCore APK 다음 스텝 계획서 - 2026-05-31

> **문서 상태(2026-06-02): 과거 계획 snapshot.** 현재 기준은 `docs/status/current_status.md`, `apps/android/README.md`, `docs/README.md`를 우선한다. APK hash, 모델 방향, `/reports/v2` 허용 대상은 이 문서 작성 후 바뀌었다.


## 기준

- 작성일: 2026-05-31 KST
- 기준 APK: `apps/android/app/build/outputs/apk/debug/app-debug.apk`
- 기준 APK sha256: `55ecfda2205a91d317c879e353119798299ace25bff7f6c7501381761e013b56`
- 기준 문서:
  - `docs/execution/2026-05-31_android_static_dataset_contract_progress.md`
  - `apps/android/README.md`
  - `docs/android/arcore_depth_estimation_architecture.md`
- 제품 방향: Web/PWA 데모가 아니라 Android native ARCore 앱을 주 경로로 한다.

## 현재 상태 요약

- Android debug APK는 빌드 가능하고 실기기 설치 가능한 상태다.
- ARCore camera preview, Raw Depth/Full Depth snapshot, TFLite two-model detector, object depth pipeline이 연결되어 있다.
- 프레임 드랍 문제는 detector background executor + 1초 간격 + in-flight guard로 완화했다.
- `model-config/two_model_runtime.json`은 이제 Android 런타임 source of truth다.
- developer bbox overlay가 추가되어 detection/depth bbox를 화면에서 확인할 수 있다.
- 정적 image-level 평가 산출물은 일부 남아 있으나, reviewed tactile3 원본 image/GT dataset은 로컬에 없어 full static metric rerun은 막혀 있다.

## 핵심 판단

지금은 TTS/진동/신고/길안내 같은 제품 기능을 더 얹기 전에, **Android 실기기에서 탐지 bbox와 ARCore depth가 같은 대상을 가리키는지**를 먼저 확정해야 한다.

특히 현재 pipeline은 `camera image normalized bbox == depth normalized coordinate`라는 identity 가정이 남아 있다. overlay가 실제 객체와 밀리면 이 가정부터 고쳐야 한다.

## 제외/안전 기준

- 사용자 확인 없이 배포, release signing, 외부 공개, 운영 DB 변경, secret 설정, 비용 발생 API 호출은 하지 않는다.
- 실제 신고 자동 업로드, TMAP live 반복 호출, Cloud STT/TTS, 공공기관 제출은 이번 계획 범위 밖이다.
- 실기기에서 직접 봐야 하는 내용은 PASS로 가정하지 않는다.
- 정적 RGB dataset 결과를 ARCore depth/`N보` 정확도 근거로 확대하지 않는다.

## 목표

1. bbox overlay를 기준으로 Android 탐지 좌표와 preview 좌표 정합을 확인한다.
2. depth sampling 결과가 bbox 내부 객체에서 나오는지 확인 가능하게 만든다.
3. 좌표가 어긋나면 ARCore image/display/depth coordinate mapping을 구현한다.
4. 탐지/좌표/depth가 안정화된 뒤에만 TTS/haptic/report upload 기능으로 넘어간다.

## 우선순위 계획

### P0. Overlay 기반 좌표 정합 1차 판정

- [단계] 새 APK 설치 후 사람/점자블록/일반 물체를 화면 중앙, 좌측, 우측, 상단, 하단에 두고 bbox 위치를 관찰한다.
  - 검증: overlay bbox가 실제 객체 위에 대략 맞는지, 회전/미러/크롭/비율 밀림이 있는지 기록한다.
  - 담당: 실기기 관찰은 사용자/기기 필요. 코드 수정은 Codex.

- [단계] overlay가 실제 객체와 맞으면 현 identity mapping을 임시 유지한다.
  - 검증: `person` bbox와 `1보`/distance summary가 같은 객체를 기준으로 움직이는지 확인한다.

- [단계] overlay가 밀리면 ARCore transform 보정 task로 전환한다.
  - 검증: 보정 전/후 같은 장면에서 bbox 위치 오차가 줄어드는지 비교한다.

### P1. ARCore image/display/depth coordinate mapping 보강

- [단계] ARCore `Frame.transformCoordinates2d(...)` 기반으로 camera image normalized 좌표를 display/view 좌표로 변환하는 mapper를 추가한다.
  - 검증: 좌/우/상/하 bbox fixture에서 view 좌표가 화면 범위 안에 들어오고 회전별 round-trip 테스트가 통과한다.

- [단계] depth sampling용 mapper와 overlay mapper를 분리한다.
  - 검증: overlay는 view 좌표, sampler는 depth image 좌표를 사용한다는 테스트/문서가 남는다.

- [단계] 기존 `identityMapper()` 사용 위치에 TODO가 아니라 명시적 `DepthCoordinateMapper`를 연결한다.
  - 검증: `ObjectDepthRuntimePipelineTest`가 mapper 주입 케이스를 포함한다.

### P2. Metadata-only capture log 모드

- [단계] 이미지 저장 없이 frame timestamp, detector timestamp, class, confidence, bbox, source, depth median, sample count, detection age를 logcat 또는 내부 ring buffer에 남긴다.
  - 검증: 개인정보 이미지 파일이 생성되지 않고, 10~30개 최근 frame 요약만 확인 가능하다.

- [단계] session stop 시 capture buffer를 clear한다.
  - 검증: 앱 재시작/세션 중지 후 stale log가 남지 않는다.

- [단계] 필요 시 debug-only export 버튼은 별도 승인 후 추가한다.
  - 검증: 기본 동작에서 파일 저장/외부 전송이 없다.

### P3. Android TFLite threshold/config 정리

- [단계] Android threshold와 backend `/detect/v2` threshold 차이를 의도적으로 분리할지 맞출지 결정 문서화한다.
  - 검증: `two_model_runtime.json`, backend runtime config, 문서가 같은 결정을 설명한다.

- [단계] Android threshold sweep 후보를 정적 산출물 기준으로 정리한다.
  - 검증: `reports/runs/evaluations/stage1_yolo26s_reviewed_test_presence_error_candidates_20260531/`와 `reports/runs/evaluations/predictions_manifest_presence_20260531/`를 근거로 threshold별 tradeoff 표를 만든다.

- [단계] full static rerun은 reviewed tactile3 dataset이 복원된 뒤 실행한다.
  - 검증: dataset 부재 시 BLOCKED로 기록하고 PASS로 쓰지 않는다.

### P4. ARCore RGB-D 검증 데이터셋 설계

- [단계] RGB-D capture schema를 문서화한다.
  - 검증: RGB frame, Raw Depth, Raw confidence, Full Depth, timestamp, rotation, intrinsics, bbox, distance GT 필드가 정의된다.

- [단계] 앱 내 capture 구현은 metadata-first로 시작하고, 이미지/깊이 파일 저장은 개인정보·용량 기준을 정한 뒤 진행한다.
  - 검증: 기본 APK는 사용자 이미지/깊이 파일을 저장하지 않는다.

- [단계] `1보` 검증 metric을 정의한다.
  - 검증: 거리 구간별 MAE/RMSE, step bucket error, Raw/Full fallback rate가 문서에 포함된다.

### P5. 제품 기능 연결 전 gate

아래 gate가 만족되기 전에는 TTS/haptic/report upload를 제품 기능으로 붙이지 않는다.

- [단계] bbox overlay가 실제 객체와 대략 정렬된다.
  - 검증: 실기기 관찰 기록 또는 capture log 근거.

- [단계] depth sample count/median이 객체 이동에 따라 일관되게 변한다.
  - 검증: 같은 객체를 가까이/멀리 이동했을 때 distance가 단조롭게 변한다.

- [단계] detector age가 지나치게 오래된 결과를 쓰지 않는다.
  - 검증: detection age limit 정책 또는 UI/log 확인.

- [단계] `damaged_tactile_block`은 report-only 정책을 유지한다.
  - 검증: 사용자 경고 TTS를 바로 내지 않고 신고 후보로만 처리한다.

### P6. Gate 통과 후 제품 기능 순서

1. TTS/haptic 최소 연결
   - [단계] `UserFacingDepth.message`를 Android `TextToSpeech`와 haptic으로 연결하되 rate limit을 유지한다.
   - 검증: JVM/unit 가능한 policy test + 실기기에서 중복 발화 없음 확인.

2. Backend `/reports/v2` upload 연결
   - [단계] `custom_tactile:damaged_tactile_block`만 report 후보로 만든다.
   - 검증: normal tactile, tactile damage area, COCO 객체는 저장 요청을 만들지 않는다.

3. GPS/heading metadata 연결
   - [단계] report upload에는 GPS 필수, heading은 가능할 때만 포함한다.
   - 검증: GPS 없을 때 저장하지 않고 사용자/운영 문구가 정확하다.

4. Navigation/TMAP은 후순위
   - [단계] Android에서 backend navigation proxy를 재사용한다.
   - 검증: API key를 Android client에 넣지 않는다.

## 팀/작업 lane

| lane | 담당 범위 | 첫 작업 | 검증 |
|---|---|---|---|
| Android Coordinate lane | overlay/view/depth 좌표계 | ARCore transform mapper 설계/구현 | Gradle test, 실기기 overlay 관찰 |
| Android Runtime lane | detection age, capture log, session lifecycle | metadata-only capture log | stale detection 없음, 파일 저장 없음 |
| Model/Data lane | threshold/config/evidence | Android/backend threshold decision note | 기존 reports 기반 tradeoff 표 |
| Product Safety lane | TTS/report 전 gate | 기능 연결 전 checklist | PASS/PARTIAL/BLOCKED 분리 |
| Ops lane | backend/admin 유지 | `/reports/v2` 계약 재확인 | report policy tests |

## 바로 실행 가능한 Codex 작업

1. ARCore coordinate mapper 설계 문서와 테스트 skeleton 작성
   - [단계] `docs/android/arcore_coordinate_mapping_plan.md` 작성
   - 검증: identity 가정, overlay mapper, depth mapper가 분리돼 설명됨

2. metadata-only capture log 구현
   - [단계] 이미지 저장 없이 최근 N개 detection/depth summary를 보관
   - 검증: unit/static check + assembleDebug

3. threshold decision note 작성
   - [단계] Android/backend threshold 차이를 표로 정리
   - 검증: `check_android_tflite_contract` warning 의미가 문서에 반영됨

4. detector age guard 추가
   - [단계] 너무 오래된 `latestDetections`는 depth pipeline에 넘기지 않음
   - 검증: stale detection test 또는 static check

## 사용자/실기기 없이는 완료할 수 없는 작업

- 새 APK 설치 후 overlay가 실제 객체 위에 맞는지 확인
- 실제 거리 0.5m, 1m, 2m 등에서 `N보` 안내가 맞는지 측정
- 목걸이 착용 각도/흔들림/조명 변화 field 확인
- TalkBack/TTS/haptic 청취 품질 확인

이 항목들은 계획에는 포함하지만, 실제 실행 전까지 PASS로 표기하지 않는다.

## 2026-05-31 Codex 진행 상황

- 완료: `/home/ddobagi/Code`의 push용 worktree 2개(`hanium-dreamup-push-20260525`, `hanium-dreamup-pushprep`) 제거.
- 완료: P1 준비 문서 `docs/android/arcore_coordinate_mapping_plan.md` 작성. 실제 mapper 구현은 ARCore API 확인과 실기기 관찰 후 진행.
- 완료: P2 일부 구현. `MetadataCaptureLog`로 이미지/깊이 파일 저장 없는 최근 frame metadata ring buffer를 추가하고 session stop 시 clear한다.
- 완료: P3 decision note `docs/android/android_backend_threshold_decision_20260531.md` 작성. 현재 결정은 Android/backend threshold 분리 유지다.
- 완료: P5 일부 구현. `MAX_DETECTION_AGE_MS=2500`을 넘은 detector 결과는 depth pipeline 입력에서 제외한다.
- 미완료: P0 overlay 좌표 정합, ARCore depth 실측, `N보` 정확도는 Device/RGB-D evidence 전까지 PASS가 아니다.

## 성공 기준

- Android JSON config가 계속 runtime source of truth로 유지된다.
- overlay 또는 capture log로 detection bbox, depth sample, distance가 같은 객체를 가리키는지 설명 가능하다.
- 좌표 정합 전에는 TTS/report 제품 기능을 임의로 연결하지 않는다.
- 정적 RGB dataset 결과와 ARCore depth field 결과를 문서에서 분리한다.
- daylog에는 실제 변경 파일과 실제 실행 검증만 기록한다.
