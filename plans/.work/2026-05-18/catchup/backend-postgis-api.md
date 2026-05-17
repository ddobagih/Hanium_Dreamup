# Hanium Dreamup / WalkSafe Assist - Backend/PostGIS/API catch-up lane note (2026-05-18)

## 확인한 근거
- `plans/daily/2026-05-17.md`: Backend/PostGIS/API 체크리스트 확인.
- `daylog/2026-05-17.md`: backend 변경, 검증, 미완료 항목 확인.
- `plans/.work/2026-05-17/execute-r1/backend-postgis-api.md`: 5/17 backend 실행 상세 확인.
- `plans/catchup/2026-05-17-execution-r1.md`: r1 통합 결과 확인.
- `docs/execution/2026-05-17_integration_field_report.md`: Docker/PostGIS, loopback HTTP, `/admin` 미완료 근거 확인.
- `docs/execution/2026-05-17_model_data_mlops.md`: backend ready artifact가 `.pt` 기준임을 확인.
- `docs/execution/2026-05-16_backend_postgis_api.md`, `docs/execution/2026-05-15_backend_postgis_followup.md`, `docs/execution/2026-05-15_runtime_followup_after_reset.md`: 이전 PostGIS/Alembic/backend baseline 확인.
- `daylog/2026-05-18.md`, `docs/execution/2026-05-18*`: 현재 확인되지 않음.
- 읽기 전용 점검이므로 파일/daylog는 작성하지 않음.

## 완료로 판단한 항목
- Python 3.14/AnyIO 환경의 `TestClient`, sync endpoint, `StaticFiles`, threadpool hang 회피 수정 완료.
- `/health`, `/detect/health`, reports read/update endpoint, DB dependency async 조정 완료.
- `/uploads/{filename}` 직접 응답 라우트 전환 및 회귀 테스트 추가 완료.
- backend 테스트 클라이언트가 `httpx.ASGITransport` 기반 `ASGITestClient`로 전환됨.
- `/detect` ASGI 회귀 확인 완료: env 미설정 health `unavailable`, `best.pt` ready, known-positive `/detect` 200, detection `source=server`.
- upload negative ASGI smoke 5종 확인 완료: `unsupported_image_type`, `image_extension_mismatch`, `image_content_mismatch`, `empty_image`, `upload_too_large`.
- 검증 일부 완료: `test_detect.py` 11 passed, `test_uploads.py` 2 passed, 전체 `backend/tests`는 실행됐으나 `13 passed, 17 skipped`.

## 미완료 작업 후보
- [ ] PostGIS/Alembic 5/17 재검증 → 이유/근거: Docker socket 권한 없음으로 `docker compose ps db` 실패, Alembic runtime 미확인.
- [ ] reports 테스트 runtime PASS → 이유/근거: PostGIS test DB 미접속으로 `test_reports.py`가 skip됨.
- [ ] 전체 backend suite no-skip PASS → 이유/근거: 전체 결과가 `13 passed, 17 skipped`라 완료로 볼 수 없음.
- [ ] uvicorn 기준 HTTP smoke → 이유/근거: sandbox local TCP 제한으로 curl/실서버 smoke 실패, ASGI smoke로만 대체됨.
- [ ] reports write smoke → 이유/근거: `POST /reports`, `GET /reports/{id}`, list filter, `PATCH new -> reviewed -> resolved` 실 DB 확인 없음.
- [ ] upload success matrix → 이유/근거: JPEG/PNG/WebP 테스트 코드는 있으나 실제 DB row/upload 파일 생성 근거 없음.
- [ ] duplicate/radius runtime 확인 → 이유/근거: PostGIS 필요 항목이며 5/17에는 미확인.
- [ ] row/upload cleanup → 이유/근거: 신규 reports runtime 실행이 막혀 cleanup 전후 count/file 근거 없음.
- [ ] `docs/execution/2026-05-17_backend_postgis_api.md` 작성 → 이유/근거: 계획상 산출물이지만 실제 확인된 것은 `plans/.work/.../execute-r1/backend-postgis-api.md`.
- [ ] DB migration 후보 결정/구현 → 이유/근거: constraint, `updated_at` trigger, status history는 backlog로 남음.

## 오늘 catch-up 후보 스케줄
- [ ] PostGIS/Alembic 환경 재현 → 검증: `docker compose up -d db`, DB healthy, `alembic upgrade head`, `alembic current == 202605120001 (head)`.
- [ ] 테스트 데이터 격리 → 검증: 실행 전 `reports` count, `UPLOAD_DIR`, upload 파일 수 기록.
- [ ] reports 테스트 재실행 → 검증: `python -m pytest backend/tests/test_reports.py -q -rs` skip/fail 없이 PASS.
- [ ] 전체 backend suite 재실행 → 검증: `python -m pytest backend/tests -q -rs` no skip/fail/timeout.
- [ ] read-only HTTP smoke → 검증: uvicorn 기준 `GET /health`, `/detect/health`, `/reports?limit=1`.
- [ ] write HTTP smoke → 검증: `POST /reports`, `GET /reports/{id}`, 필터, `PATCH new -> reviewed -> resolved`.
- [ ] upload 검증 → 검증: JPEG/PNG/WebP 성공 row/file 생성, 5개 negative `detail.code`와 status 기록.
- [ ] duplicate/radius 검증 → 검증: 같은 class/25m/±10분 duplicate ID 반환, partial radius query HTTP 400.
- [ ] `/detect` live 회귀 → 검증: POST env 미설정 503 `model_unavailable`, `best.pt` ready, known-positive `source=server`.
- [ ] cleanup 및 실행 문서화 → 검증: 생성 report row/upload 파일 삭제 후 count/file 상태와 미실행 사유 기록.

## 확인 필요
- 5/18 새벽 daylog/docs 실행 근거가 없어 5/17 이후 추가 완료는 확인되지 않음.
- 현재 작업트리에 backend 변경과 미추적 테스트 파일이 남아 있어 merge 전 diff 확인 필요.
- `test_reports.py`는 실제 DB row/upload 파일을 남길 수 있으므로 runtime 실행 시 cleanup 기준이 필요함.
- backend ready artifact는 현재 `.pt`; ONNX는 backend ready 기준 아님.
- v2 모델은 class `0 damaged_tactile_block` baseline이며 4-class 서비스 성능 근거로 쓰면 안 됨.
- `/reports`, `/uploads`, status patch는 인증 없이 열려 있어 외부 공개 전 권한/CORS/rate limit/storage 정책 확인 필요.

## 병렬 에이전트 활용 메모
- 이번 점검에서는 하위/병렬 에이전트를 사용하지 않음.
- 범위가 backend lane 문서와 실행 note 대조 중심이라 단일 검토로 통합함.