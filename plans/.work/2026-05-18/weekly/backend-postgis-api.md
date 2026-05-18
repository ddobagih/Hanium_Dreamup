# Hanium Dreamup / WalkSafe Assist - Backend/PostGIS/API weekly lane note (2026-W21)

## 이번 주 목표 후보

- PostGIS 접근 가능한 일반 개발 세션에서 Alembic, reports API, duplicate/radius, upload, cleanup을 skip 없이 재검증한다.
- `reports/detect` API는 새 기능보다 회귀 방어를 우선한다: `/detect` env 미설정 `model_unavailable`, `.pt` ready, `source=server` 저장 경로를 분리 확인한다.
- 테스트 데이터 정책을 고정한다: disposable DB 사용 또는 실행 후 row/upload cleanup 전후 count 기록.
- upload error contract를 실제 HTTP 기준으로 확인한다: JPEG/PNG/WebP 성공과 5개 negative code.
- DB migration 후보는 바로 크게 묶지 않고, runtime 안정 확인 뒤 작은 후보로 선별한다: `status/source/class_name` constraint, `updated_at` trigger, status history.
- 외부 공개 전 backlog를 명확히 둔다: `/reports`, `/uploads`, status patch 인증/권한, rate limit, 외부 스토리지, 이미지 EXIF/decoder 검증.
- 확인 시점에 `plans/weekly/2026-W21.md`는 없었고, 최근 근거는 `daylog/2026-05-18.md`, `plans/daily/2026-05-19.md`, `docs/current_status.md`, `docs/pwa_backend_status.md`, `docs/execution/2026-05-15_backend_postgis_followup.md`를 우선했다.

## 날짜별/단계별 체크리스트

- [2026-05-18 월] 현재 상태 기준선 정리  
  검증: 최근 daylog/docs 기준으로 backend 구현 완료/미완료 분리, sandbox 제약으로 PostGIS runtime 미완료임을 명시.

- [2026-05-19 화] PostGIS/Alembic runtime gate 확보  
  검증: `docker compose up -d db`, db healthy, `python -m alembic -c backend/alembic.ini upgrade head`, `current == 202605120001 (head)`.

- [2026-05-20 수] reports 테스트와 schema 확인  
  검증: PostGIS extension, `reports` table/index, `Find_SRID(...)=4326`, `python -m pytest backend/tests/test_reports.py -q -rs` skip 없이 PASS.

- [2026-05-21 목] 실제 HTTP smoke와 upload matrix  
  검증: uvicorn 기준 `/health`, `/detect/health`, `/reports?limit=1`, `POST /reports`, 상세 조회, list filter, status patch, JPEG/PNG/WebP 성공.

- [2026-05-22 금] duplicate/radius와 `/detect` 회귀  
  검증: 같은 class/25m/±10분 duplicate ID 반환, partial radius query HTTP 400, env 미설정 `/detect` 503, `best.pt` ready, known-positive `source=server`.

- [2026-05-23 토] cleanup과 migration 후보 정리  
  검증: 생성 row/upload 파일 삭제 후 count/file 상태 기록. migration 후보는 작은 PR 단위와 보류 항목으로 분리.

- [2026-05-24 일] 문서/보고 기준 정리  
  검증: `docs/execution/2026-05-24_backend_postgis_api.md` 또는 주간 통합 문서에 PASS/FAIL/BLOCKED/PENDING 분리. 실행하지 않은 검증은 PASS로 쓰지 않음.

## 검증 계획

- `python -m pytest backend/tests/test_detect.py -q -rs`
- `python -m pytest backend/tests/test_uploads.py -q -rs`
- `python -m pytest backend/tests/test_reports.py -q -rs`
- `python -m pytest backend/tests -q -rs`
- uvicorn 실제 HTTP smoke: `/health`, `/detect/health`, `/reports`, `/reports/duplicate-check`, `/uploads/{filename}`
- upload negative: `unsupported_image_type`, `image_extension_mismatch`, `empty_image`, `image_content_mismatch`, `upload_too_large`
- cleanup: 실행 전후 `reports` count, `backend/uploads` 파일 수, 테스트 업로드 삭제 여부 기록
- 문서 검증: API 계약과 실제 `backend/app/schemas.py`, `backend/app/uploads.py`, `backend/app/main.py` 응답이 어긋나지 않는지 확인

## 리스크/확인 필요

- 현재 Codex sandbox에서는 Docker socket, PostGIS, local TCP가 막혀 runtime 검증이 다시 실패할 수 있다.
- `backend/tests/test_reports.py`는 실제 DB row와 upload 파일을 남길 수 있으므로 cleanup 허용 여부가 필요하다.
- `/reports`, `/uploads`, status patch는 인증 없이 열려 있어 외부 공개 전 제한 정책이 필요하다.
- upload 검증은 MIME/확장자/크기/header 중심이다. EXIF 제거, full decode 검증, 악성 파일 스캔은 아직 별도 backlog다.
- v2 모델은 class `0 damaged_tactile_block` baseline이다. 4-class 성능 근거로 쓰면 안 된다.
- `source=server` headless smoke는 API/fixture 근거이며 실폰 field 성능 근거와 분리해야 한다.

## 병렬 에이전트 활용 메모

- 이번 weekly lane note 작성에는 하위/병렬 에이전트를 사용하지 않았다.
- 검토 범위가 backend lane 문서, daylog, 코드/테스트 계약 확인으로 충분히 좁아 단일 검토로 통합했다.
- 실제 주간 실행에서는 Backend Runtime과 Integration/Field를 분리하면 좋다. Backend는 DB/API/cleanup, Integration은 PWA `source=server` 저장과 `/admin` 확인을 맡기면 파일 충돌 위험이 낮다.