# Hanium Dreamup / WalkSafe Assist - Backend/PostGIS/API lane note (2026-05-21)

## 최근 진행 근거
- `product/vision.md`, `product/roadmap.md`, `product/backlog.md`, `product/done-criteria.md`, `product/decisions.md` (2026-05-18): 제품 모드는 `feature-growth`; 백엔드 관련 핵심은 PostGIS reports no-skip, 신고/중복/상태 변경, `server(.pt)` 우선, GeoJSON/히트맵 thin slice.
- `README.md`, `docs/current_status.md`, `docs/pwa_backend_status.md` (2026-05-17~18 보정): FastAPI/PostGIS 신고 API, 업로드 검증, 중복 후보, 상태 변경, `/detect` `.pt` adapter는 구현됨. PostGIS runtime 최신 재검증, 실폰 field, 인증/권한/배포는 미완료.
- `docs/api_reference.md`, `docs/report_operations.md`, `docs/backend_environment.md`, `docs/backend_db_reset.md` (2026-05-12): reports/detect 계약, 업로드 오류 코드, duplicate 기준, fake 신고 운영 기준, cleanup 절차가 정리됨.
- `backend/app/main.py`, `backend/app/uploads.py`, `backend/app/detector.py`, `backend/alembic/versions/202605120001_create_reports.py`: `/reports`, `/reports/duplicate-check`, `/uploads`, `/detect`, PostGIS `geometry(Point, 4326)` 및 GIST index 구현 확인.
- `backend/tests/test_reports.py`, `backend/tests/test_detect.py`, `backend/tests/test_uploads.py`: reports/PostGIS, detect adapter, upload serving 테스트 존재. reports 테스트는 DB 미접속 시 skip하며 row/upload 파일을 남길 수 있음.
- `docs/execution/2026-05-15_backend_postgis_followup.md`: PostGIS healthy, Alembic head, backend tests `22 passed` 근거 있음. 이후 `docs/execution/2026-05-15_runtime_followup_after_reset.md`에서 row/upload cleanup 완료.
- `docs/execution/2026-05-19_backend_postgis_api.md`: no-DB detect/uploads 테스트 `13 passed`; 전체 backend `13 passed, 17 skipped`. Docker/PostGIS/Alembic/reports HTTP smoke는 환경 제한으로 BLOCKED.
- `daylog/2026-05-20.md`: 5/20은 모델/Data/MLOps 중심. Backend/PostGIS 새 완료 근거 없음.
- `plans/daily/2026-05-21.md` (2026-05-20 작성): 내일 backend 후보로 reports HTTP smoke, upload matrix, duplicate/radius, GeoJSON/Heatmap Seed, cleanup이 포함됨.
- `plans/features`, `plans/verify/ready`, `plans/verify/reports`는 디렉터리만 있고 파일 없음. `PM/status`, `PM/reports/*`, automation metrics는 repo 내부에서 확인되지 않음.
- 프로젝트 루트 `AGENTS.md`는 없음. 현재 대화에 제공된 AGENTS 지침을 기준으로 적용.

## 내일 목표 후보
- 1순위: **GeoJSON/Heatmap Seed slice** 시작. product M2의 GIS/운영 가치와 연결해 disposable DB row 또는 fixture 2~3건을 GeoJSON `FeatureCollection`으로 변환하는 최소 구현/설계를 잡는다.
- 2순위: **신고 Evidence Trace HTTP smoke**. `POST /reports -> detail/list/filter -> PATCH status -> /uploads`를 report ID 기준으로 추적하고 cleanup 전후 상태를 남긴다.
- 3순위: **Duplicate/Radius Advisory 검증**. 같은 class/25m/±10분 중복 후보가 생성 차단이 아니라 advisory와 운영자 검토 정보로 남는지 확인한다.
- 4순위: **PostGIS/Alembic/reports no-skip 최신 재검증**. Docker/PostGIS가 가능할 때만 PASS로 기록한다.
- 5순위: **upload matrix와 error contract 보정 후보 확인**. JPEG/PNG/WebP 성공, 5개 negative code, `/detect` stale 문서 표현을 점검한다.
- 6순위: **migration 후보는 설계 메모로만 분리**. DB constraint, `updated_at` trigger, status history, auth/rate-limit/storage는 C 작업으로 둔다.

## 상세 체크리스트 초안
- [ ] backend runtime gate 기록 → 검증: `git status`, `DATABASE_URL`, `UPLOAD_DIR`, `MODEL_ARTIFACT_PATH`, `MODEL_VERSION`, 포트 `5432/8000`, 실행 전 reports count와 upload 파일 수 기록
- [ ] disposable DB/cleanup 기준 확정 → 검증: 운영/공유 DB가 아닌지 확인하고 `TRUNCATE reports` 또는 테스트 DB 재생성, upload cleanup 절차 문서화
- [ ] PostGIS/Alembic 재검증 → 검증: db healthy, `python -m alembic -c backend/alembic.ini upgrade head`, `current == 202605120001 (head)`, SRID 4326 확인
- [ ] reports no-skip 테스트 실행 → 검증: `python -m pytest backend/tests/test_reports.py -q -rs`가 skip/fail 없이 PASS하거나 BLOCKED 사유 기록
- [ ] 전체 backend suite 실행 → 검증: `python -m pytest backend/tests -q -rs` 결과의 pass/skip/fail/timeout 분리 기록
- [ ] 신고 Evidence Trace HTTP smoke → 검증: `/health`, `/reports?limit=1`, `POST /reports`, `GET /reports/{id}`, list filter, `PATCH /reports/{id}/status`, `/uploads/{filename}` 응답과 report ID 기록
- [ ] upload matrix 확인 → 검증: JPEG/PNG/WebP 성공과 `unsupported_image_type`, `image_extension_mismatch`, `empty_image`, `image_content_mismatch`, `upload_too_large` status/detail.code 기록
- [ ] duplicate/radius advisory 확인 → 검증: 같은 class/25m/±10분에서 `duplicate_report_ids` 반환, `/reports/duplicate-check`, radius list filter, partial radius query HTTP 400 확인
- [ ] GeoJSON/Heatmap Seed slice 작성 → 검증: disposable DB row 또는 fixture 2~3건을 GeoJSON `FeatureCollection`으로 변환하고 feature 수, WGS84 좌표, `source`, `location_quality` 포함 여부 확인
- [ ] `/detect` 회귀 확인 → 검증: 모델 미설정 `503 model_unavailable`, `.pt` 설정 시 `/detect/health ready`, known-positive `/detect` timeout 없는 `200`과 `source=server`
- [ ] 문서 stale 표현 후보 정리 → 검증: `docs/api_reference.md`, `docs/backend_error_contract.md`의 placeholder/reason 표현과 `backend/app/detector.py` reason 값 대조
- [ ] cleanup 수행 → 검증: 생성 report row와 upload 파일 삭제 후 count/file 상태가 실행 전 기준으로 돌아왔는지 기록
- [ ] migration 후보 설계 메모 → 검증: DB constraint, `updated_at` trigger, status history, auth/rate-limit/storage를 C 작업으로 분리하고 rollback 필요성 명시

## 리스크/확인 필요
- Docker/PostGIS/local TCP가 막히면 reports 테스트는 다시 skip될 수 있다. safe alternative는 no-DB detect/uploads 테스트, migration 파일/schema checklist, fixture serializer까지만 수행하고 PostGIS PASS로 쓰지 않는 것이다.
- `backend/tests/test_reports.py`는 persistent DB row와 upload 파일을 만들 수 있다. disposable DB 또는 cleanup 기준 없이 실행하면 안 된다.
- `/detect/health ready`는 실제 inference 성공 근거가 아니다. `/detect`가 timeout 없이 detection 응답을 반환해야 PASS다.
- `docs/backend_error_contract.md`에는 오래된 `model_adapter_not_implemented` reason이 남아 있어 현재 detector reason 목록과 불일치 가능성이 있다.
- `POST /reports`는 파일 저장 후 DB commit 순서라 DB commit 실패 시 orphan upload 가능성이 있다. 내일은 구현 변경보다 관찰/cleanup/설계 메모로 다루는 것이 안전하다.
- DB constraint, trigger, status history는 migration 영향이 있어 자동 실행하지 않는다. safe alternative는 테스트 DB 전용 검증 계획과 rollback 메모 작성이다.
- GeoJSON/히트맵은 위치정보를 다룬다. safe alternative는 synthetic fixture 또는 disposable DB sample이며 외부 공개/업로드는 금지한다.
- `/reports`, `/uploads`, status patch는 인증/권한 없이 열려 있다. 실제 외부 배포, 운영 DB, secret, 외부 storage, 지자체 API 연동은 자동 계획에 넣지 않는다.
- `source=fake`는 UI/API/운영 흐름 근거일 뿐 정확도, 지연시간, 실제 보행 안전 근거가 아니다.
- v2 모델은 class `0 damaged_tactile_block` baseline이다. backend `source=server` smoke를 4-class 서비스 성능으로 표현하면 안 된다.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 백엔드 범위가 `backend/app`, `backend/tests`, 관련 문서와 기존 5/21 계획으로 충분히 좁혀져 있어 하위 에이전트 분리 없이 직접 통합했다.
- 대신 병렬 shell 조회로 product/docs/daylog/plans/backend code/tests 근거를 나눠 확인했고, 결론은 GeoJSON Seed를 신규 feature slice로 우선하되 PostGIS runtime 검증은 환경 gate가 열릴 때만 PASS로 처리한다는 것이다.