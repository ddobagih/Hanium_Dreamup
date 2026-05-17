# Hanium Dreamup / WalkSafe Assist - Backend/PostGIS/API lane note (2026-05-18)

## 최근 진행 근거
- `README.md`, `docs/current_status.md`, `docs/pwa_backend_status.md` (2026-05-17 보정): `/detect` `.pt` adapter와 PWA server detector mode smoke는 완료. PostGIS runtime 재검증은 미완료로 남음.
- `docs/model_integration_plan.md` (2026-05-17 보정): backend ready artifact는 `best.pt`; `best.onnx`는 backend ready 기준이 아님.
- `docs/execution/2026-05-15_backend_postgis_followup.md` (2026-05-15): PostGIS healthy, Alembic `202605120001 (head)`, 당시 `backend/tests` `22 passed`.
- `docs/execution/2026-05-15_runtime_followup_after_reset.md` (2026-05-15): 테스트 row/upload reset 완료, `best.pt` 기준 `/detect/health ready`, known-positive `/detect` `source=server` 확인.
- `docs/execution/2026-05-15_pwa_server_detection_e2e.md` (2026-05-15): PWA server mode에서 `/detect` → `/reports` 저장, report와 metadata `source=server` 확인 후 cleanup 완료.
- `docs/execution/2026-05-16_backend_postgis_api.md` (2026-05-16): `test_reports.py`에 reports/upload/status/empty image 테스트 보강. PostGIS 접근 불가로 runtime은 `17 skipped`.
- `plans/.work/2026-05-17/execute-r1/backend-postgis-api.md`, `daylog/2026-05-17.md` (2026-05-17): AnyIO/TestClient hang 회피, async endpoint 조정, `/uploads/{filename}` 직접 응답, `ASGITestClient` 전환. `test_detect.py` 11 passed, `test_uploads.py` 2 passed, 전체 `backend/tests`는 `13 passed, 17 skipped`.
- `plans/daily/2026-05-18.md`는 없음. `plans/.work/2026-05-18/daily`, `weekly`도 비어 있음.
- 저장소 내부 `AGENTS.md`는 없음. 사용자 제공 AGENTS 지침 적용.

## 내일 목표 후보
- 1순위: PostGIS 접근 가능한 일반 개발 세션에서 Alembic과 전체 backend tests를 재검증한다.
- 2순위: 격리된 DB/UPLOAD_DIR로 reports HTTP smoke, upload matrix/negative, duplicate/radius, cleanup을 완료한다.
- 3순위: 5/17 async/ASGI 변경 이후 `/detect` unavailable/ready와 `/uploads/{filename}` 회귀를 확인한다.
- 4순위: stale 문서의 `/detect placeholder`, `adapter 미구현`, 오류 reason 목록을 최신 상태로 정리할 범위를 확정한다.
- 5순위: DB migration 후보는 runtime 안정 확인 후 하나만 고른다. 우선 후보는 `status/source/class_name` check constraint 또는 `updated_at` trigger.
- 6순위: 인증/권한, public `/uploads`, CORS, rate limit, EXIF 제거, 외부 스토리지는 구현보다 backlog 분리와 위험 표기를 우선한다.

## 상세 체크리스트 초안
- [ ] PostGIS/Alembic 재현 → 검증: `docker compose up -d db`, DB healthy, `alembic upgrade head`, `alembic current == 202605120001 (head)` 기록
- [ ] 테스트 데이터 격리 → 검증: 실행 전 `reports` count, `backend/uploads/test` 파일 수, 사용할 `UPLOAD_DIR` 기록
- [ ] reports 테스트 재실행 → 검증: `python -m pytest backend/tests/test_reports.py -q -rs`에서 skip/fail 없이 결과 기록
- [ ] 전체 backend suite 재실행 → 검증: `python -m pytest backend/tests -q -rs` 결과와 skip/fail/timeout 여부 기록
- [ ] read-only HTTP smoke → 검증: uvicorn 기준 `GET /health`, `/detect/health`, `/reports?limit=1` 응답 기록
- [ ] write HTTP smoke → 검증: `POST /reports`, `GET /reports/{id}`, list filter, `PATCH new -> reviewed -> resolved` 확인
- [ ] upload matrix/negative 확인 → 검증: JPEG/PNG/WebP 성공, 5개 negative `detail.code`와 HTTP status 기록
- [ ] duplicate/radius 확인 → 검증: 같은 class/25m/±10분 duplicate 반환, partial radius query HTTP 400 확인
- [ ] `/detect` 회귀 확인 → 검증: env 미설정 `503 model_unavailable`, `best.pt` env `ready`, known-positive `source=server`
- [ ] `/uploads/{filename}` 회귀 확인 → 검증: 정상 이미지 content-type, path traversal 404 확인
- [ ] 테스트 데이터 cleanup → 검증: 생성 report row와 upload 파일 삭제 후 count/file 상태 기록
- [ ] 문서 갱신 범위 산정 → 검증: `api_reference`, `inference_contract`, `frontend_api_examples`, `backend_error_contract`, `pre_model_backend_todo`의 stale 문장 목록 작성

## 리스크/확인 필요
- 이번 lane note 작성 중 새 pytest/docker/curl 검증은 실행하지 않았다. 위 PASS/SKIP은 기존 문서 근거다.
- 현재 작업트리에 5/17 backend 변경과 미추적 테스트 파일이 남아 있다. merge 전 diff 확인이 필요하다.
- `test_reports.py`는 실제 DB row와 upload 파일을 남길 수 있어 cleanup 없이는 결과가 누적된다.
- 일부 문서는 아직 `/detect` placeholder 또는 `model_adapter_not_implemented` 전제를 포함한다.
- backend ready artifact는 현재 `.pt`만이다. ONNX를 backend ready로 쓰려면 별도 adapter/검증이 필요하다.
- v2 모델은 class `0 damaged_tactile_block` 중심 baseline이다. 4-class 서비스 성능으로 표현하면 안 된다.
- `/reports`, `/uploads`, status patch는 인증 없이 열려 있다. 외부 노출 전 권한, CORS, rate limit, storage 정책 확인이 필요하다.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 이번 작업은 문서와 backend 코드/테스트 근거 확인 중심이고 최종 산출물이 단일 lane note라 하위 에이전트로 나눌 만큼 독립 작업이 크지 않았다.