# Hanium Dreamup - AI 보행 지원 시스템

시각장애인 보행 지원을 위한 PWA 기반 프로젝트입니다. 전체 구현 계획은 `PROJECT_PLAN.md`를 기준으로 관리합니다.

## 현재 진행 단계

현재 진행 상태는 아래 문서로 나누어 관리합니다.

- `docs/current_status.md`: 전체 현재 상태 요약
- `docs/model_training_status.md`: YOLO 데이터셋과 v1/v2 학습 결과
- `docs/voice_stt_tts_status.md`: 로컬 STT/TTS 프로토타입과 실제 음성 테스트 결과
- `docs/pwa_backend_status.md`: PWA, 백엔드, fake detector, 모델 미연결 상태

핵심 상태:

- 탐지 대상: 파손/단절 점자블록, 방치 킥보드/자전거, 공사 구조물/적치물, 포트홀
- 목표: 객체 인식 정확도 90% 이상, 경보 지연 1초 이내
- 완료: AI Hub 513 `TL8/TL9/TS8/TS9` 기반 점자블록 v1/v2 로컬 학습
- 진행: test split/외부 검증, ONNX 또는 서버 추론 연결 검토, PWA 음성 명령 연동
- 기준 환경: 한국 보행 환경. 해외 공개 데이터는 smoke test 또는 pretrain 후보로만 사용합니다.

데이터셋 이미지/라벨, AI Hub zip, `runs/`, `.pt`, 음성 샘플/출력/로그는 GitHub에 올리지 않고 로컬에서만 처리합니다.

## 모델 작업 빠른 시작

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements-model.txt
python model/validate_yolo_dataset.py
python model/train_yolo.py --dry-run
```

기본 학습 대상 데이터는 `datasets/walksafe_kr_v1/` 아래에 YOLO 형식으로 배치합니다. 이미지 원본은 개인정보와 위치 정보가 포함될 수 있으므로 기본적으로 Git 추적에서 제외합니다.

해외 공개 baseline 데이터셋을 다시 생성하거나 확인할 때는 명시적으로 `walksafe_v1`을 지정합니다.

```bash
python data_sources/scripts/build_walksafe_v1.py
python model/validate_yolo_dataset.py --data datasets/walksafe_v1/data.yaml
python model/train_yolo.py --data datasets/walksafe_v1/data.yaml --epochs 1 --batch 4 --name walksafe_public_smoke
```

AI Hub 513 점자블록 데이터로 만든 로컬 데이터셋과 학습 결과 요약은 `docs/model_training_status.md`를 봅니다. 실제 산출물은 로컬 `datasets/`, `runs/`, `logs/` 아래에만 보관합니다.

## PWA/백엔드 병렬 개발 빠른 시작

모델 개발과 별개로 `apps/web`의 Next.js PWA는 fake detector로 동작합니다. 탐지 이벤트 형식은 `docs/inference_contract.md`를 기준으로 맞춥니다.
모델 미구현 때문에 임시로 넣은 대체 시스템은 `docs/model_placeholder_systems.md`에서 따로 관리합니다.
외부 프론트엔드 작업자는 `docs/frontend_handoff_without_model.md`를 먼저 보면 됩니다.
Figma AI로 만든 UI를 구현 기준으로 정리할 때는 `docs/figma_ui_handoff.md`를 사용합니다.
Figma Make 화면을 시각장애인 실제 사용 기준으로 검수할 때는 `docs/figma_make_accessibility_review.md`를 사용합니다.
백엔드 API와 실행 환경은 `docs/api_reference.md`, `docs/backend_environment.md`, `docs/backend_db_reset.md`를 기준으로 확인합니다.
실제 모델 연결 단계는 `docs/model_integration_plan.md`에 따로 정리합니다.
모델 학습이 끝난 뒤 넘겨받을 항목은 `docs/model_training_handoff.md`에 정리합니다.
외부 프론트엔드 연동 시에는 `docs/frontend_api_examples.md`, `docs/backend_error_contract.md`, `docs/report_operations.md`, `docs/pre_model_backend_todo.md`를 함께 확인합니다.

```bash
docker compose up -d db
source .venv/bin/activate
python -m pip install -r backend/requirements.txt
cp backend/.env.example backend/.env
python -m alembic -c backend/alembic.ini upgrade head
python -m uvicorn backend.app.main:app --reload --port 8000
```

다른 터미널에서 PWA를 실행합니다.

```bash
cd apps/web
npm install
npm run dev
```

신고 관리 화면은 `http://localhost:3000/admin`에서 확인합니다. 여기서 신고 목록 조회, 상태/유형/소스/날짜/반경 필터, 신고 상태 변경을 할 수 있습니다.

검증 명령:

```bash
npx @google/design.md@0.1.1 lint DESIGN.md
cd apps/web && npm run lint && npm run typecheck && npm run build && npm audit
cd ../.. && python -m pytest backend/tests
```

## 로컬 음성 프로토타입

음성 서버는 YOLO용 `.venv`와 분리된 `.venv-voice`에서 실행합니다.

```bash
python3 -m venv .venv-voice
source .venv-voice/bin/activate
python -m pip install -r requirements-voice.txt
PYTHONPATH=. python -m uvicorn voice.server:app --host 127.0.0.1 --port 9001
```

상세 실행 방법과 테스트 결과는 `docs/local_voice_server_plan.md`, 현재 판단 요약은 `docs/voice_stt_tts_status.md`를 봅니다.
