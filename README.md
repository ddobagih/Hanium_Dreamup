# Hanium Dreamup - WalkSafe Assist

시각장애인/저시력 보행자를 위한 PWA 기반 보행 보조 프로젝트입니다. 사용자는 화면 버튼보다 **자동 감지, 음성 안내, 진동, 음성 명령**을 중심으로 앱을 사용한다는 전제로 설계합니다.

## 현재 기준 상태

- 기준일: 2026-05-22 KST
- 앱: `apps/web` Next.js PWA
- 백엔드: `backend` FastAPI + PostGIS 신고 API
- 기본 탐지 흐름:
  - v1 legacy: `/detect`, `/reports`
  - v2 two-model contract: `/detect/v2`, `/reports/v2`
- v2 앱 모드:
  - `NEXT_PUBLIC_DETECTOR_MODE=fake-v2`: 브라우저 fake two-model 데모
  - `NEXT_PUBLIC_DETECTOR_MODE=server-v2`: backend `/detect/v2` 호출 + `/reports/v2` 저장
- 현재 `/detect/v2`는 실제 YOLO 추론이 아니라 fake contract 구현이다. 실제 YOLO26s/COCO adapter 연결은 후속 작업이다.

자세한 최신 상태는 `docs/current_status.md`, 문서 위치는 `docs/README.md`를 먼저 봅니다.

## v2 제품 정책 요약

- 타일/점자블록 손상은 자동 신고 대상이다.
- 자동 신고 완료/중복/실패는 기본적으로 사용자에게 TTS로 말하지 않는다.
- 사용자가 음성으로 “신고해줘”라고 명시 요청한 경우에는 완료/실패를 짧게 TTS로 안내한다.
- 일반 객체는 신고하지 않는다.
- 사람/차량/자전거 같은 일반 객체는 존재 자체가 아니라 **보행 경로 차단** 또는 **충돌 가능 접근**일 때만 경고한다.
- 타일 손상은 기본적으로 경고가 아니라 자동 신고 대상이며, 즉시 보행 위험으로 별도 판단된 경우에만 경고할 수 있다.

관련 문서: `docs/walksafe-v2/README.md`

## 빠른 실행

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

### Frontend

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
- 데이터셋 이미지/라벨, AI Hub 원본 zip, `runs/`, `.pt`, `.onnx`, 음성 샘플/출력/로그는 로컬 산출물로 유지합니다.
- v2 런타임 설정은 `configs/walksafe_two_model_runtime_20260522.yaml`, helper는 `model/two_model_runtime.py`를 봅니다.
- tactile damage area 외부 검수 패키지는 `ai_tasks/walksafe_tactile_damage_area_review_20260522/`에 모았습니다.
