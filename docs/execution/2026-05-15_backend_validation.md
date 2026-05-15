# 2026-05-15 Backend/PostGIS/API Validation

범위: read/validate 중심. 백엔드 코드는 수정하지 않았다. 모델 학습/export는 실행하지 않았고, uvicorn 서버도 띄우지 않았다.

## Git status 기록

시작 시 실행:

```bash
git status --short --untracked-files=all
```

결과: 출력 없음.

중간 재확인 시:

```text
 M apps/web/next-env.d.ts
```

해당 파일은 이번 lane에서 수정하지 않았다.

문서 작성 후 최종 재확인 시:

```text
?? docs/execution/2026-05-15_backend_validation.md
?? docs/execution/2026-05-15_pwa_validation.md
```

이 중 이번 lane에서 작성한 파일은 `docs/execution/2026-05-15_backend_validation.md`뿐이다.

## 환경/명령 확인

확인한 실행 기준:

- README 백엔드 설치/실행: `python -m pip install -r backend/requirements.txt`, `cp backend/.env.example backend/.env`, `python -m alembic -c backend/alembic.ini upgrade head`, `python -m uvicorn backend.app.main:app --reload --port 8000`
- README 테스트: `python -m pytest backend/tests`
- `backend/requirements.txt`: FastAPI, Uvicorn, SQLAlchemy, psycopg, GeoAlchemy2, Alembic, pytest, Ultralytics, Pillow 포함
- `backend/.env`는 없음. 기본 DB URL은 `postgresql+psycopg://walksafe:***@localhost:5432/walksafe`로 확인했다.
- `MODEL_ARTIFACT_PATH=None`, `model_artifact_exists=False`, `MODEL_VERSION=None`.

## Pytest 결과

실행:

```bash
source .venv/bin/activate
python -V
python -m pytest backend/tests
```

결과:

```text
using .venv
Python 3.14.4
collected 22 items
backend/tests/test_detect.py ...........                                 [ 50%]
backend/tests/test_reports.py sssssssssss                                [100%]
11 passed, 11 skipped in 0.95s
```

PostGIS skip 사유 확인:

```bash
source .venv/bin/activate
python -m pytest backend/tests/test_reports.py -rs -q
```

결과: `11 skipped in 0.27s`. 모든 skip은 PostGIS DB 미기동 때문이다.

```text
PostGIS test database is not reachable: connection to server at "127.0.0.1", port 5432 failed: Connection refused
```

## API/TestClient smoke

실행 방식: FastAPI `TestClient`. 임시 uvicorn 프로세스는 사용하지 않았으므로 남은 서버 프로세스는 없다.

실행:

```bash
source .venv/bin/activate
python - <<'PY'
import base64
import json
from fastapi.testclient import TestClient
from backend.app.main import app

client = TestClient(app)
png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=')
context = {
    'captured_at': '2026-05-15T00:00:00.000Z',
    'gps': {'latitude': 37.5665, 'longitude': 126.978, 'accuracy_m': 9.5},
    'heading': 180.0,
}
print(client.get('/health').status_code, client.get('/health').json())
print(client.get('/detect/health').status_code, client.get('/detect/health').json())
print(client.post('/detect', data={'context': json.dumps(context)}, files={'image': ('frame.png', png, 'image/png')}).status_code)
PY
```

실제 출력 요약:

```text
GET /health -> 200 {"status": "ok"}
GET /detect/health -> 200 {"model_status": "unavailable", "reason": "model_not_configured", ...}
POST /detect -> 503 {"detail": {"code": "model_unavailable", "reason": "model_not_configured", ...}}
```

`/detect` ready behavior는 확인하지 않았다. 이유: `MODEL_ARTIFACT_PATH`가 설정되어 있지 않고, 바로 사용할 model artifact/env가 없었다.

## 판정

- Detect API 기본 계약: 통과. 모델 미설정 상태에서 health는 unavailable, detect는 503/model_unavailable을 반환한다.
- 전체 pytest: 부분 통과. detect tests 11개는 통과, reports/PostGIS tests 11개는 DB 미기동으로 skip.
- PostGIS lane blocker: 로컬 `127.0.0.1:5432` PostGIS가 실행 중이지 않다.
- Cleanup: 임시 서버 없음. 모델 학습/export 없음. 백엔드 코드 변경 없음.

## Parent follow-up

1. PostGIS까지 실제 통과 확인하려면 `docker compose up -d db` 후 `python -m alembic -c backend/alembic.ini upgrade head`와 `python -m pytest backend/tests`를 다시 실행해야 한다.
2. `/detect` ready smoke는 `MODEL_ARTIFACT_PATH`와 `MODEL_VERSION`이 준비된 뒤에만 가볍게 재검증한다.
