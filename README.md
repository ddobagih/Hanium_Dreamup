# Hanium Dreamup - AI 보행 지원 시스템

시각장애인 보행 지원을 위한 PWA 기반 프로젝트입니다. 전체 구현 계획은 `PROJECT_PLAN.md`를 기준으로 관리합니다.

## 현재 진행 단계

1차 구현은 모델 v1부터 시작합니다.

- 탐지 대상: 파손/단절 점자블록, 방치 킥보드/자전거, 공사 구조물/적치물, 포트홀
- 목표: 객체 인식 정확도 90% 이상, 경보 지연 1초 이내
- 우선 작업: 데이터셋 구성, 라벨링, YOLO baseline 학습, 브라우저 추론 검토
- 기준 환경: 한국 보행 환경. 해외 공개 데이터는 smoke test 또는 pretrain 후보로만 사용합니다.

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

## PWA/백엔드 병렬 개발 빠른 시작

모델 개발과 별개로 `apps/web`의 Next.js PWA는 fake detector로 동작합니다. 탐지 이벤트 형식은 `docs/inference_contract.md`를 기준으로 맞춥니다.
모델 미구현 때문에 임시로 넣은 대체 시스템은 `docs/model_placeholder_systems.md`에서 따로 관리합니다.
외부 프론트엔드 작업자는 `docs/frontend_handoff_without_model.md`를 먼저 보면 됩니다.

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
