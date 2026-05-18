# Hanium Dreamup / WalkSafe Assist - Backend/PostGIS/API execution (2026-05-19)

## 범위

- 담당 lane: Backend/PostGIS/API
- 대상: `backend/app`, `backend/tests`, reports/detect API, 업로드 검증, 중복 신고, DB 마이그레이션 후보
- 근거: `plans/.work/2026-05-19/execute-r1/backend-postgis-api.md`
- 주의: 이 문서는 r1 실행 note에 기록된 결과만 정식 실행 문서로 승격한 것이다. r2에서 런타임 재시도나 새 코드 변경은 하지 않았다.

## 요약

| 항목 | 상태 | 근거 |
| --- | --- | --- |
| backend 코드 변경 | PASS | r1에서 새 코드 변경 없음. `backend/**`, `backend/tests/**`, `backend/alembic/**` 미커밋 변경 없음 |
| no-DB detect/uploads 테스트 | PASS | `pytest backend/tests/test_detect.py backend/tests/test_uploads.py -q -rs`: `13 passed` |
| 전체 backend suite | PARTIAL | `pytest backend/tests -q -rs`: `13 passed, 17 skipped`; skip은 PostGIS test DB 미접속 |
| `/detect/health` ASGI ready smoke | PASS | `.pt` ASGI smoke에서 `/detect/health` `200 ready` |
| 실제 `/detect` inference | BLOCKED | 30초 timeout, `exit_code=124`; PASS 근거로 사용하지 않음 |
| PostGIS/Alembic | BLOCKED | Docker socket 권한과 DB 접속 제한으로 `alembic current`, `alembic upgrade head` 실패 |
| reports no-skip 테스트 | BLOCKED | `pytest backend/tests/test_reports.py -q -rs`: `17 skipped` |
| HTTP smoke | BLOCKED | uvicorn은 `127.0.0.1:18000`에서 기동됐지만 curl socket open이 `Operation not permitted`로 실패 |
| upload/duplicate/cleanup runtime | PENDING | DB/loopback 접근 불가로 생성 row/upload가 없어 runtime 확인 및 cleanup 대상 없음 |
| DB migration 후보 | PENDING | 이전 audit에서 C 위험/대형 작업으로 분류되어 r1/r2 자동 수행 대상 아님 |

## PASS

- `pytest backend/tests/test_detect.py backend/tests/test_uploads.py -q -rs`: `13 passed`.
- `pytest backend/tests -q -rs`: no-DB 테스트 13개는 통과했고, PostGIS 의존 tests 17개는 skip.
- `.pt` ASGI smoke 재시도에서 `/detect/health`는 `200 ready`.
- `torch`/`ultralytics` import 확인: PASS.
- `py_compile backend/app/*.py backend/tests/*.py backend/alembic/...`: PASS.
- `backend/uploads`에는 `.gitkeep`만 있고, 이번 실행에서 생성 upload 없음.
- `git diff --check`: PASS.

## BLOCKED / FAIL

- `docker compose ps`, `docker compose up -d db`: Docker socket permission denied.
- `ss -ltnp`: `Cannot open netlink socket: Operation not permitted`.
- backend env 확인 시 `DATABASE_URL`, `UPLOAD_DIR`, `MODEL_ARTIFACT_PATH`, `MODEL_VERSION`, `NEXT_PUBLIC_*` 출력 없음.
- `alembic current`, `alembic upgrade head`: `psycopg.OperationalError: connection is bad`.
- `pytest backend/tests/test_reports.py -q -rs`: `17 skipped`, PostGIS test DB 미접속.
- uvicorn HTTP smoke: `/health`, `/detect/health`, `/reports?limit=1` curl 모두 `failed to open socket: Operation not permitted`.
- 실제 `/detect` inference 호출: 30초 timeout으로 종료되어 PASS 처리하지 않음.

## PENDING

- PostGIS/Alembic head 확인.
- reports no-skip PASS.
- reports HTTP write/read/filter/status smoke.
- upload success matrix와 negative matrix runtime 확인.
- duplicate/radius runtime 확인.
- row/upload cleanup.
- disposable DB 또는 테스트 row/upload 삭제 정책 확정.

## 다음 실행 조건

- Docker/PostGIS와 local loopback socket 접근이 가능한 일반 개발 세션에서 재실행해야 한다.
- reports 테스트는 disposable DB 또는 테스트 전용 `UPLOAD_DIR` 기준을 먼저 확정한 뒤 실행한다.
- `/detect` 실제 inference는 `/detect/health ready`와 별개로 timeout 없는 성공 응답을 확인하기 전까지 완료로 쓰지 않는다.

## 병렬 에이전트 활용 메모

- r1 backend lane note에는 하위/병렬 에이전트 사용 없음으로 기록되어 있다.
- r2 문서 승격 작업도 단일 파일 작성 범위라 하위/병렬 에이전트를 사용하지 않았다.
