# Hanium Dreamup / WalkSafe Assist - Backend/PostGIS/API lane note (2026-05-22)

## 최근 진행 근거
- `product/vision.md`, `product/roadmap.md`, `product/backlog.md`, `product/done-criteria.md`, `product/decisions.md` (2026-05-18): 제품 모드는 `feature-growth`; 백엔드 핵심은 PostGIS reports no-skip, 신고/중복/상태 변경, `server(.pt)` source 분리, GeoJSON/히트맵 thin slice.
- `docs/current_status.md` (2026-05-18 보정), `docs/pwa_backend_status.md` (2026-05-17 보정): FastAPI/PostGIS 신고 API, 업로드 검증, 중복 후보, 상태 변경, `/detect` `.pt` adapter는 구현됨. PostGIS runtime 최신 재검증, 실폰 field, 인증/권한/배포는 미완료.
- `backend/app/main.py`, `backend/app/uploads.py`, `backend/app/detector.py`, `backend/alembic/versions/202605120001_create_reports.py`: `/reports`, `/reports/duplicate-check`, `/uploads`, `/detect`, PostGIS `geometry(Point, 4326)` 및 GIST index 구현 확인.
- `backend/tests/test_reports.py`, `backend/tests/test_detect.py`, `backend/tests/test_uploads.py`: reports/PostGIS, detect adapter, upload serving 테스트 존재. reports 테스트는 DB 미접속 시 skip하며 row/upload 파일을 남길 수 있음.
- `docs/execution/2026-05-15_backend_postgis_followup.md`: PostGIS healthy, Alembic head, backend tests `22 passed` 근거 있음. 같은 날 후속 문서/로그에서 row/upload cleanup 완료 근거가 있음.
- `docs/execution/2026-05-19_backend_postgis_api.md`: no-DB detect/uploads 테스트 `13 passed`; 전체 backend `13 passed, 17 skipped`. Docker/PostGIS/Alembic/reports HTTP smoke는 환경 제한으로 BLOCKED.
- `daylog/2026-05-20.md`, `daylog/2026-05-21.md`: 5/20~5/21은 모델/Data/MLOps 중심이며 Backend/PostGIS 새 완료 근거는 없음. PostGIS no-skip/HTTP smoke, upload matrix, duplicate/radius, cleanup은 미검증으로 남음.
- `plans/daily/2026-05-21.md` (작성일 2026-05-20): Backend 후보로 GeoJSON/Heatmap Seed, reports HTTP smoke, upload matrix, duplicate/radius, cleanup 포함.
- `plans/features`, `plans/verify/ready`, `plans/verify/reports`: 디렉터리는 있으나 파일 없음. `PM/status`, `PM/reports/verify`, `PM/reports/product-audit`, automation metrics 경로는 repo 내부에서 확인되지 않음.
- `plans/daily/2026-05-22.md`: 없음 확인. 저장소 내부 `AGENTS.md`도 없음, 현재 대화에 제공된 AGENTS 지침 적용.

## 내일 목표 후보
- 1순위: GeoJSON/Heatmap Seed를 제품 M2 신규 feature slice로 전진. 외부 공개 없이 fixture 또는 disposable DB rows를 GeoJSON `FeatureCollection`으로 변환한다.
- 2순위: 관리자 중복 후보 보강. 기존 `duplicate_report_ids` 필드가 상세/운영 화면에서도 의미 있게 채워지도록, migration 없이 동적 계산하는 backend slice를 검토한다.
- 3순위: 신고 Evidence Trace HTTP smoke. `POST /reports -> detail/list/filter -> PATCH status -> /uploads`를 report ID 기준으로 추적하고 cleanup 전후 상태를 남긴다.
- 4순위: PostGIS/Alembic/reports no-skip 최신 재검증. Docker/PostGIS 접근 가능할 때만 PASS로 기록한다.
- 5순위: upload matrix와 `/detect` error contract 정합성 확인. 오래된 `model_adapter_not_implemented` 문서 표현은 현재 detector reason과 대조한다.
- 6순위: DB migration 후보는 설계 메모만. constraint, `updated_at` trigger, status history, auth/rate-limit/storage는 C 작업으로 둔다.

## 상세 체크리스트 초안
- [ ] backend runtime gate 기록 → 검증: `git status`, `DATABASE_URL`, `UPLOAD_DIR`, `MODEL_ARTIFACT_PATH`, `MODEL_VERSION`, 포트 `5432/8000`, 실행 전 reports count와 upload 파일 수 기록
- [ ] GeoJSON serializer/API thin slice 설계 → 검증: WGS84 point, `id/status/class_name/source/location_quality/review_flags/image_path/captured_at` 포함 기준 문서화
- [ ] GeoJSON fixture 구현 또는 disposable DB smoke → 검증: synthetic/report row 2~3건이 GeoJSON `FeatureCollection`으로 변환되고 feature 수와 좌표 순서 `[lng, lat]` 확인
- [ ] 관리자 중복 후보 상세 응답 보강안 확인 → 검증: 같은 class/25m/±10분 기준에서 자기 자신 제외 duplicate id가 `GET /reports/{id}` 또는 전용 helper로 재현됨
- [ ] disposable DB/cleanup 기준 확정 → 검증: 운영/공유 DB가 아닌지 확인하고 `TRUNCATE reports` 또는 테스트 DB 재생성, upload cleanup 절차 기록
- [ ] PostGIS/Alembic 재검증 → 검증: db healthy, `alembic upgrade head`, `current == 202605120001 (head)`, PostGIS extension, SRID 4326 확인
- [ ] reports no-skip 테스트 실행 → 검증: `python -m pytest backend/tests/test_reports.py -q -rs`가 skip/fail 없이 PASS하거나 BLOCKED 사유 기록
- [ ] 신고 Evidence Trace HTTP smoke → 검증: `/health`, `POST /reports`, `GET /reports/{id}`, list filter, `PATCH /reports/{id}/status`, `/uploads/{filename}` 응답과 report ID 기록
- [ ] upload matrix 확인 → 검증: JPEG/PNG/WebP 성공과 `unsupported_image_type`, `image_extension_mismatch`, `empty_image`, `image_content_mismatch`, `upload_too_large` status/detail.code 기록
- [ ] duplicate/radius advisory 확인 → 검증: `/reports/duplicate-check`, radius list filter, partial radius query HTTP 400 확인
- [ ] `/detect` 회귀 확인 → 검증: 모델 미설정 `503 model_unavailable`, `.pt` 설정 시 `/detect/health ready`, known-positive `/detect` timeout 없는 `200`과 `source=server`
- [ ] cleanup 수행 → 검증: 생성 report row와 upload 파일 삭제 후 count/file 상태가 실행 전 기준으로 복귀
- [ ] migration 후보 메모 → 검증: DB constraint, `updated_at` trigger, status history, auth/rate-limit/storage를 C 작업으로 분리하고 rollback 필요성 명시

## 리스크/확인 필요
- Docker/PostGIS/local TCP가 막히면 reports 테스트는 다시 skip될 수 있다. safe alternative는 no-DB detect/uploads 테스트, schema checklist, GeoJSON fixture serializer까지만 수행하고 PostGIS PASS로 쓰지 않는 것이다.
- `backend/tests/test_reports.py`는 persistent DB row와 upload 파일을 만들 수 있다. disposable DB 또는 cleanup 기준 없이 실행하면 안 된다.
- GeoJSON/히트맵은 위치정보를 다룬다. safe alternative는 synthetic fixture 또는 disposable DB sample이며 외부 공개/업로드는 금지한다.
- 관리자 중복 후보를 DB에 저장하려면 migration/데이터 영향이 커질 수 있다. safe alternative는 기존 PostGIS query로 상세 응답에서 동적 계산하는 방식이다.
- `/detect/health ready`는 실제 inference 성공 근거가 아니다. `/detect`가 timeout 없이 detection 응답을 반환해야 PASS다.
- `docs/backend_error_contract.md`와 일부 오래된 문서에는 현재 detector reason과 다른 placeholder 표현이 남아 있다.
- `/reports`, `/uploads`, status patch는 인증/권한 없이 열려 있다. 실제 외부 배포, 운영 DB, secret, 외부 storage, 지자체 API 연동은 자동 계획에 넣지 않는다.
- v2 모델은 class `0 damaged_tactile_block` baseline이다. backend `source=server` smoke를 4-class 서비스 성능으로 표현하면 안 된다.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 범위가 backend 단일 lane의 문서/코드 읽기 기반 계획 수립으로 충분히 좁아 하위 에이전트 분리 없이 직접 통합했다.
- 병렬 shell 조회로 product/docs/daylog/plans/backend code/tests 근거를 확인했고, 결론은 GeoJSON Seed와 관리자 중복 후보 보강을 신규 feature slice로 우선하되 PostGIS runtime 검증은 환경 gate가 열릴 때만 PASS 처리한다는 것이다.