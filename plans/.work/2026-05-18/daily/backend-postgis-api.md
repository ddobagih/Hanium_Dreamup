# Hanium Dreamup / WalkSafe Assist - Backend/PostGIS/API lane note (2026-05-19)

## 최근 진행 근거
- `README.md` (2026-05-18 보정): `.pt` server detector mode와 backend 실행/검증 명령이 최신 기준으로 정리됨.
- `docs/current_status.md` (2026-05-18 최신 보정): 백엔드는 FastAPI/PostGIS 신고 API, 업로드 검증, 중복 후보, `/detect` `.pt` adapter까지 구현됐고 PostGIS runtime 재검증은 미완료로 남음.
- `docs/pwa_backend_status.md` (2026-05-17 최신 보정): `NEXT_PUBLIC_DETECTOR_MODE=server`와 backend `/detect` 기반 `source: "server"` headless smoke 근거가 있으나 실폰 field 근거는 없음.
- `docs/model_integration_plan.md` (2026-05-17~18 반영): backend ready artifact는 `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt`; ONNX는 backend ready 기준이 아님.
- `daylog/2026-05-18.md`: backend `test_detect` 11 passed, `test_uploads` 2 passed, 전체 backend `13 passed, 17 skipped`; PostGIS/Alembic/reports HTTP smoke는 Docker/DB/TCP 제한으로 완료 처리하지 않음.
- `plans/.work/2026-05-18/execute-r1/backend-postgis-api.md`: `.pt` ASGI `/detect/health` ready, `/detect` 200, `detect_count=2`, first `source=server`; reports runtime은 PostGIS 미접속으로 skip.
- `docs/execution/2026-05-15_backend_postgis_followup.md`: 마지막 완전 PostGIS 기준 검증은 `docker compose` healthy, Alembic `202605120001 (head)`, backend tests `22 passed`.
- `docs/execution/2026-05-15_runtime_followup_after_reset.md`: test row/upload reset 후 `reports` count 0 확인, `.pt` ready smoke와 known-positive `/detect` 확인.
- `docs/execution/2026-05-15_pwa_server_detection_e2e.md`: headless browser E2E에서 `source=server`, `metadata_source=server` report 저장과 cleanup 확인.
- `plans/daily/2026-05-19.md`: 없음. 현재 lane note가 merge 에이전트의 2026-05-19 계획 입력이 되어야 함.
- 저장소 내부 `AGENTS.md`: 없음. 사용자 제공 AGENTS 지침 기준 적용.

## 내일 목표 후보
- PostGIS/Alembic/reports runtime을 일반 개발 세션에서 재검증한다.
- 테스트 데이터 격리와 cleanup 기준을 먼저 고정한 뒤 reports 테스트를 skip 없이 통과시킨다.
- uvicorn 기준 실제 HTTP smoke로 `/health`, `/detect/health`, `/reports`, `/reports/duplicate-check`, `/uploads`를 확인한다.
- 업로드 성공 matrix와 negative error contract를 실제 API 기준으로 재확인한다.
- duplicate/radius query와 `new -> reviewed -> resolved` 상태 변경을 DB row 기준으로 검증한다.
- `.pt` `/detect` ready와 env 미설정 `model_unavailable` 양쪽 회귀를 확인하되, v2는 class `0` baseline으로만 기록한다.
- 생성된 report row와 upload 파일 cleanup 전후 count를 남긴다.
- schema migration 후보는 즉시 구현보다 runtime 안정 확인 후 작은 PR 후보로 결정한다.

## 상세 체크리스트 초안
- [ ] 작업트리/runtime gate 확인 → 검증: `git status --short --branch --untracked-files=all`, `DATABASE_URL`, `UPLOAD_DIR`, `MODEL_ARTIFACT_PATH`, 포트 `5432/8000` 상태 기록
- [ ] PostGIS/Alembic 재검증 → 검증: `docker compose up -d db`, db healthy, `alembic upgrade head`, `alembic current == 202605120001 (head)`
- [ ] DB 스키마 확인 → 검증: PostGIS extension, `reports` 테이블, `ix_reports_*`, `Find_SRID('public','reports','location') == 4326`
- [ ] 테스트 데이터 격리 → 검증: 실행 전 reports count, upload 파일 수, 테스트 전용 `UPLOAD_DIR` 또는 cleanup 허용 여부 기록
- [ ] reports 테스트 재실행 → 검증: `python -m pytest backend/tests/test_reports.py -q -rs` skip/fail 없이 PASS
- [ ] 전체 backend suite 재실행 → 검증: `python -m pytest backend/tests -q -rs` no skip/fail/timeout
- [ ] read/write HTTP smoke → 검증: `/health`, `/detect/health`, `/reports?limit=1`, `POST /reports`, 상세 조회, 필터, status patch 응답 기록
- [ ] upload matrix와 negative 확인 → 검증: JPEG/PNG/WebP 성공, `unsupported_image_type`, `image_extension_mismatch`, `empty_image`, `image_content_mismatch`, `upload_too_large` status/code 기록
- [ ] duplicate/radius 확인 → 검증: 같은 class/25m/±10분 duplicate ID 반환, partial radius query HTTP 400
- [ ] `/detect` 회귀 확인 → 검증: env 미설정 `503 model_unavailable`, `best.pt` env `ready`, known-positive `/detect` `source=server`
- [ ] `/uploads/{filename}` 확인 → 검증: 정상 이미지 content-type, path traversal 404
- [ ] cleanup 및 문서화 → 검증: 생성 row/upload 파일 삭제 후 count/file 상태를 `docs/execution/2026-05-19_backend_postgis_api.md` 또는 merge 문서에 기록

## 리스크/확인 필요
- `test_reports.py`는 실제 DB row와 upload 파일을 남길 수 있으므로 disposable DB 또는 cleanup 허용 여부가 필요하다.
- 현재 `git status` 기준 `daylog/2026-05-18.md` 수정이 남아 있어 merge 전 확인이 필요하다.
- 같은 sandbox 제약이면 Docker socket, PostGIS, local TCP smoke가 다시 막힐 수 있다.
- `/reports`, `/uploads`, status patch는 인증/권한 없이 열려 있어 외부 공개 전 제한 정책이 필요하다.
- 업로드 검증은 MIME/확장자/크기/header 중심이며 EXIF 제거, 전체 이미지 decode 검증, 악성 파일 스캔은 아직 없다.
- DB 차원의 status/source/class constraint, `updated_at` trigger, status history는 backlog이며 바로 migration으로 묶으면 범위가 커질 수 있다.
- v2 모델은 `damaged_tactile_block` 중심 baseline이다. 4-class 서비스 성능이나 실제 보행 안전 근거로 표현하면 안 된다.

## 병렬 에이전트 활용 메모
- 이번 lane note 작성에는 하위/병렬 에이전트를 사용하지 않았다.
- 범위가 backend 문서, daylog, 코드 상태 대조로 충분히 좁아 단일 검토로 통합했다.
- 기존 `2026-05-18 r1` backend 실행 note에는 읽기 전용 explorer 1개 사용 기록이 있으며, 통합 결론은 “코드 변경보다 PostGIS/runtime 검증이 우선”이다.