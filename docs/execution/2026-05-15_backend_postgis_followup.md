# 2026-05-15 Backend/PostGIS Follow-up

범위: backend validation + PostGIS 재검증. 백엔드 소스는 수정하지 않았고, 모델 학습/export 및 무거운 모델 실행은 하지 않았다.

## Git status 기록

시작 시 실행:

```bash
git status --short --untracked-files=all
```

결과:

```text
 M daylog/2026-05-15.md
?? docs/execution/2026-05-15_backend_validation.md
?? docs/execution/2026-05-15_disk_cleanup_candidates.md
?? docs/execution/2026-05-15_installed_program_usage_candidates.md
?? docs/execution/2026-05-15_low_risk_cleanup_result.md
?? docs/execution/2026-05-15_model_validation.md
?? docs/execution/2026-05-15_obs_removal_attempt.md
?? docs/execution/2026-05-15_parallel_validation_summary.md
?? docs/execution/2026-05-15_pwa_validation.md
?? docs/execution/2026-05-15_voice_validation.md
?? docs/execution/2026-05-15_wine_obs_snap_cleanup_attempt.md
?? docs/execution/2026-05-15_wine_obs_snap_cleanup_result.md
```

테스트 후 중간 재확인 시 `docs/execution/2026-05-15_pwa_server_mode_followup.md`가 추가로 보였으나, 이번 lane에서 만든 파일은 아니다.

## PostGIS 기동

```bash
docker compose up -d db
```

결과: PASS. `walksafe-postgis` 컨테이너가 시작됨.

```bash
docker compose ps db
```

결과:

```text
NAME               IMAGE                    COMMAND                  SERVICE   CREATED        STATUS                        PORTS
walksafe-postgis   postgis/postgis:16-3.5   "docker-entrypoint.s…"   db        35 hours ago   Up About a minute (healthy)   0.0.0.0:5432->5432/tcp, [::]:5432->5432/tcp
```

참고: 라벨 출력용 shell wrapper에서 `printf` 옵션 경고가 한 번 있었지만, `docker compose up -d db` 자체와 health wait는 성공했다.

## Alembic

```bash
.venv/bin/python --version
.venv/bin/python -m alembic -c backend/alembic.ini upgrade head
.venv/bin/python -m alembic -c backend/alembic.ini current
```

결과: PASS.

```text
Python 3.14.4
INFO  [alembic.runtime.migration] Context impl PostgresqlImpl.
INFO  [alembic.runtime.migration] Will assume transactional DDL.
INFO  [alembic.runtime.migration] Context impl PostgresqlImpl.
INFO  [alembic.runtime.migration] Will assume transactional DDL.
202605120001 (head)
```

## Pytest 전후 DB/uploads 상태

Pytest 전:

```text
database_url=postgresql+psycopg://walksafe:walksafe@localhost:5432/walksafe
reports_count_before_pytest=6
backend/uploads: exists=True file_count=1 size_bytes=1
backend/uploads/test: exists=True file_count=0 size_bytes=0
```

```bash
.venv/bin/python -m pytest backend/tests
```

결과: PASS.

```text
collected 22 items
backend/tests/test_detect.py ...........                                 [ 50%]
backend/tests/test_reports.py ...........                                [100%]
22 passed in 0.40s
```

Pytest 후:

```text
reports_count_after_pytest=12
backend/uploads: exists=True file_count=7 size_bytes=121
backend/uploads/test: exists=True file_count=6 size_bytes=120
```

정리 동작 메모:

- `backend/tests/test_reports.py`는 테스트 DB를 truncate/rollback하지 않고 같은 PostGIS DB에 report row를 남긴다.
- 이번 실행에서 `reports` row가 `6 -> 12`로 6개 증가했다.
- 테스트 업로드 파일 6개가 `backend/uploads/test/*.jpg`에 생성되었다.
- `backend/uploads/test/`는 `.gitignore`에 의해 ignored 상태다.
- 사용자 지시에 따라 persistent DB row와 테스트 업로드 파일을 삭제하지 않았다.

## Health smoke

실행 방식: FastAPI `TestClient`; uvicorn 서버는 띄우지 않았다.

```bash
.venv/bin/python - <<'PY'
from fastapi.testclient import TestClient
from backend.app.main import app
client = TestClient(app)
for path in ('/health', '/detect/health'):
    response = client.get(path)
    print(f'{path} status={response.status_code}')
    print(response.json())
PY
```

결과: PASS.

```text
/health status=200
{'status': 'ok'}
/detect/health status=200
{'model_status': 'unavailable', 'model_version': None, 'reason': 'model_not_configured', 'model_artifact_path': None, 'model_class_order': ['damaged_tactile_block', 'parked_kickboard_bicycle', 'construction_obstacle', 'pothole'], 'model_confidence_threshold': 0.35, 'model_iou_threshold': 0.7, 'model_image_size': 640}
```

## 판정

- PostGIS: PASS, `walksafe-postgis` healthy.
- Alembic: PASS, `202605120001 (head)`.
- Backend tests: PASS, `22 passed in 0.40s`.
- Health smoke: PASS, `/health` 200 and `/detect/health` 200.
- Blocker: 없음.
- 미실행: heavy model training/export, uvicorn server smoke, `/detect` ready inference smoke.
