# Hanium Dreamup - WalkSafe Assist

시각장애인/저시력 보행자를 위한 보행 보조 프로젝트입니다. 현재 주 사용자 앱은 **Android native ARCore/TFLite APK**이고, Web/PWA는 데모·운영 보조·과거 호환 경로로 유지합니다.

## 현재 기준 상태

- 기준일: 2026-07-01 KST
- 주 앱: `apps/android` Android native ARCore 앱
- 보조 앱: `apps/web` Next.js PWA/demo/admin web
- 백엔드: `backend` FastAPI + PostGIS 신고/운영 API
- Android 현재 APK:
  - 경로: `apps/android/app/build/outputs/apk/debug/app-debug.apk`
  - SHA-256: `1f5b2020053d755e6f52054453c82bd2eb45d3bd6ade3740a8d9bfa2f35967c9`
- Android 구현 상태:
  - ARCore camera preview, Raw Depth/Full Depth snapshot 연결
  - 로컬 TFLite unified-primary detector 계약 연결(현재 unified asset 미존재 시 legacy two-model fallback)
  - `model-config/two_model_runtime.json`을 Android runtime source of truth로 사용
  - developer bbox overlay로 detection/depth bbox, class/confidence/source/distance/sample 정보를 화면 표시
  - Device Gate 뒤에서 TTS/haptic 위험 안내와 `damaged_tactile_block` Android `/reports/v2` upload 후보를 생성한다. 실기기 bbox/depth 정합과 PostGIS no-skip 검증 전까지 제품 완료로 보지 않는다.
- 모델/데이터 최신 결정:
  - unified 단일 모델 후보는 13-class로 고정했다.
  - 순서: `person`, `bicycle`, `car`, `motorcycle`, `bus`, `truck`, `traffic light`, `normal_tactile_block`, `damaged_tactile_block`, `crosswalk`, `curb_step`, `uneven_sidewalk`, `e_scooter_obstruction`
  - `bench`는 unified에서 제외한다. legacy COCO fallback의 `bench`는 호환용으로 남길 수 있다.
  - AIHub source plan은 `docs/model_unified_13class_aihub_sources_20260602.md`를 본다.
- 현재 핵심 gate:
  - bbox overlay가 실제 객체 위에 맞는지 실기기에서 확인
  - ARCore depth sampling이 bbox 내부 같은 객체를 가리키는지 확인
  - TTS/haptic/report upload 코드는 Device Gate 뒤에 연결되어 있으나, 좌표/depth 정합 전에는 실사용 완료로 주장하지 않음
- debug 보조:
  - debug APK는 local/dev backend로 metadata-only depth log를 보낼 수 있다.
  - 기본 비활성이고 이미지/depth raw/GPS는 전송하지 않는다.

자세한 최신 상태는 `docs/current_status.md`, 문서 위치는 `docs/README.md`, Android 설치/검증은 `apps/android/README.md`를 먼저 봅니다.
2026-07-01 문서 정합성 감사는 `docs/walksafe_documentation_audit_20260701.md`, 구식/중복 문서 분류는 `docs/archive_candidates_20260701.md`를 봅니다.
실기기 overlay/depth 관찰은 `docs/android/android_device_overlay_depth_checklist_20260601.md`를 기준으로 기록합니다.
서버 debug log는 `docs/android/android_server_debug_log_runbook_20260601.md`를 기준으로 사용합니다.

## v2 제품 정책 요약

- 타일/점자블록 손상은 신고 대상이다.
- 자동 신고 완료/중복/실패는 기본적으로 사용자에게 TTS로 말하지 않는다.
- 사용자가 음성으로 “신고해줘”라고 명시 요청한 경우에는 완료/실패를 짧게 TTS로 안내한다.
- 일반 객체는 신고하지 않는다.
- 사람/차량/자전거 같은 일반 객체는 존재 자체가 아니라 **보행 경로 차단** 또는 **충돌 가능 접근**일 때만 경고한다.
- 타일 손상은 기본적으로 경고가 아니라 신고 대상이며, 즉시 보행 위험으로 별도 판단된 경우에만 경고할 수 있다.

관련 문서: `docs/walksafe-v2/README.md`

## 빠른 실행

### Android APK 빌드/설치

```bash
cd apps/android
./gradlew test --no-daemon
./gradlew assembleDebug --no-daemon
cd ../..
adb install -r /home/ddobagi/Code/hanium-dreamup/apps/android/app/build/outputs/apk/debug/app-debug.apk
```

ARCore Depth 검증에는 ARCore 지원 Android 기기, Google Play Services for AR, 카메라 권한이 필요합니다.

### Backend

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r backend/requirements.txt
cp backend/.env.example backend/.env
docker compose up -d db
python -m alembic -c backend/alembic.ini upgrade head
PYTHONPATH=. python -m uvicorn backend.app.main:app --reload --port 8000
```

### Frontend/PWA demo

```bash
cd apps/web
npm install
NEXT_PUBLIC_DETECTOR_MODE=fake-v2 npm run dev
```

서버 v2 흐름을 확인할 때는 backend 실행 후 다음처럼 실행합니다.

```bash
cd apps/web
NEXT_PUBLIC_DETECTOR_MODE=server-v2 npm run dev
```

## 검증 명령

```bash
python scripts/check_android_tflite_contract_20260531.py
python scripts/check_android_depth_scaffold_20260531.py
python scripts/export_android_tflite_models_20260531.py --dry-run
cd apps/android && ./gradlew test --no-daemon
cd apps/android && ./gradlew assembleDebug --no-daemon
```

Web/backend 회귀가 필요할 때:

```bash
PATH="$PWD/.venv/bin:$PATH" PYTHONPATH=. python3 -m pytest \
  backend/tests/test_uploads.py \
  backend/tests/test_detect.py \
  backend/tests/test_detect_v2.py \
  backend/tests/test_reports.py \
  backend/tests/test_reports_v2.py \
  model/test_two_model_runtime.py -q

cd apps/web
npm run lint
npm run typecheck
npm run build
```

## 모델/데이터 원칙

- GitHub에는 문서, 설정, 경량 소스만 올립니다.
- 데이터셋 이미지/라벨, AI Hub 원본 zip, `runs/`, `.pt`, `.onnx`, `.tflite`, 음성 샘플/출력/로그는 로컬 산출물로 유지합니다.
- Android TFLite runtime 설정은 `apps/android/app/src/main/assets/model-config/two_model_runtime.json`을 기준으로 봅니다. 현재 `primary_model=unified_walksafe`, `fallback_model=legacy_two_model`이라 unified TFLite asset을 해당 경로에 넣으면 단일 모델 경로가 우선 로드됩니다.
- 모바일 본명 학습 후보는 YOLO26n 입력 768입니다. 단, 2026-07-01 현재 Android runtime config는 unified TFLite export 전까지 640 asset path를 유지하며, 실제 unified asset은 아직 APK에 없습니다.
- backend v2 runtime 설정은 `configs/walksafe_two_model_runtime_stage1_mvp_20260523.json`을 기준으로 보며, `DETECT_V2_UNIFIED_MODEL_PATH`가 있으면 unified 단일 모델을 우선 사용합니다. Android threshold와 같다고 가정하지 않습니다.
- tactile damage area 외부 검수 패키지는 `ai_tasks/walksafe_tactile_damage_area_review_20260522/`에 모았습니다.
