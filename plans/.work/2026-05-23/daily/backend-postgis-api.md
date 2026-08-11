# Hanium Dreamup / WalkSafe Assist - Backend/PostGIS/API lane note (2026-05-24)

## 최근 진행 근거
- `product/vision.md`, `product/roadmap.md`, `product/backlog.md`, `product/done-criteria.md`, `product/decisions.md` (2026-05-18): 제품 모드는 `feature-growth`; Backend lane은 PostGIS reports no-skip, 신고/중복/상태 변경, GeoJSON/히트맵 thin slice, disposable DB 우선 원칙이 핵심.
- `README.md`, `docs/current_status.md`, `docs/walksafe-v2/backend_api_contract.md`, `docs/walksafe-v2/auto_report_policy.md` (2026-05-22): `/detect/v2`, `/reports/v2` 계약은 구현됨. 단 `/detect/v2` 실제 YOLO26s/COCO adapter는 아직 미연결.
- `daylog/2026-05-22.md`: backend v2 API, `/reports/v2`, route/service split이 추가됐고, 기록상 `backend/tests/test_uploads.py`, `test_detect.py`, `test_detect_v2.py`, `test_reports.py`, `test_reports_v2.py`, `model/test_two_model_runtime.py` 50 tests 통과.
- `daylog/2026-05-23.md`: 2026-05-23에는 backend/frontend/voice/PostGIS 신규 테스트 실행 근거 없음.
- `docs/execution/2026-05-15_backend_postgis_followup.md`: PostGIS healthy, Alembic `202605120001 (head)`, backend tests `22 passed` 과거 근거 있음.
- `docs/execution/2026-05-19_backend_postgis_api.md`: 최신 backend lane 실행에서는 no-DB detect/uploads는 PASS, PostGIS/Alembic/reports no-skip/HTTP smoke/upload/duplicate/cleanup은 환경 제한으로 BLOCKED/PENDING.
- `backend/app/api/reports.py`, `backend/app/services/duplicates.py`: `/reports`, `/reports/v2`, `/reports/duplicate-check` 구현. 중복 기준은 같은 class, 25m, ±10분, GPS 필요.
- `backend/alembic/versions/202605120001_create_reports.py`: 현재 migration은 reports 테이블 1개뿐. v2는 기존 테이블과 JSON metadata를 재사용하며 신규 migration 없음.
- `plans/features`, `plans/verify/ready`: 디렉터리는 있으나 파일 없음. `PM/status`, `PM/reports/*`, product-audit, automation-metrics는 repo 내 `PM` 디렉터리 자체가 없어 확인되지 않음.
- `plans/daily/2026-05-24.md`: 기존 파일 없음.

## 내일 목표 후보
- 1순위: **GeoJSON/Ops thin slice**. product M2의 GIS/운영 목표를 전진시키기 위해 reports 2~3건을 GeoJSON `FeatureCollection`으로 변환하는 local-only slice를 잡는다.
- 2순위: **v2 report query/filter 보강**. 현재 v2 class name은 legacy `ClassName` 필터와 의미가 달라 `/reports` 조회 확장이 필요하다. migration 없이 query/schema/helper부터 작게 시작한다.
- 3순위: **PostGIS reports runtime 재검증**. DB/TCP가 열릴 때만 Alembic, reports no-skip, HTTP smoke, upload/duplicate/cleanup을 PASS로 기록한다.
- 4순위: **관리자 중복 후보 상세 보강안**. 저장 시점 `duplicate_report_ids`뿐 아니라 상세/운영 조회에서 자기 자신 제외 후보를 동적 계산하는 방식을 검토한다.
- 5순위: **v2 API 문서 보정**. 오래된 `docs/api_reference.md`, `docs/inference_contract.md`는 v2를 반영하지 못하므로 canonical 문서와 충돌하지 않게 갱신 후보를 정리한다.
- 6순위: **migration 후보는 설계만**. DB constraint, `updated_at` trigger, status history, auth/rate-limit/storage는 C 작업으로 분리한다.

## 상세 체크리스트 초안
- [ ] backend runtime gate 기록 → 검증: `DATABASE_URL`, `UPLOAD_DIR`, 포트 `5432/8000`, DB 접근 가능 여부, 실행 전 reports count/upload 파일 수 기록
- [ ] GeoJSON serializer/API thin slice 범위 확정 → 검증: 외부 공개/운영 DB 없이 synthetic fixture 또는 disposable DB row만 사용한다고 명시
- [ ] GeoJSON 변환 구현 후보 작성 → 검증: 2~3건이 `FeatureCollection`으로 변환되고 좌표 순서 `[lng, lat]`, `id/status/class_name/source/location_quality/review_flags` 포함 확인
- [ ] v2 report query/filter 설계 → 검증: legacy class와 v2 `tactile_damage_area`, `damaged_tactile_block` 조회 기준이 충돌 없이 문서화됨
- [ ] PostGIS/Alembic 가능 시 재검증 → 검증: DB healthy, `alembic current == 202605120001 (head)`, PostGIS extension/SRID 4326 확인
- [ ] reports no-skip 테스트 가능 시 실행 → 검증: `python -m pytest backend/tests/test_reports.py backend/tests/test_reports_v2.py -q -rs`가 skip 없이 PASS하거나 BLOCKED 사유 기록
- [ ] reports HTTP smoke 가능 시 실행 → 검증: `/health`, `POST /reports/v2`, `GET /reports/{id}`, list/filter, `PATCH /status`, `/uploads/{filename}`를 report ID 기준으로 기록
- [ ] upload/duplicate matrix 확인 → 검증: JPEG/PNG/WebP 성공, 5개 upload error code, duplicate/radius advisory, partial radius HTTP 400 기록
- [ ] cleanup 수행 → 검증: 생성 report row와 upload 파일이 실행 전 기준으로 복귀했는지 기록
- [ ] migration 후보 메모 → 검증: constraint/trigger/status history/auth/storage를 C 작업으로 분리하고 disposable DB dry-run만 safe alternative로 남김

## 리스크/확인 필요
- Docker/PostGIS/local TCP가 막히면 reports runtime은 다시 skip/BLOCKED가 된다. safe alternative는 no-DB tests, schema checklist, GeoJSON fixture serializer까지만 수행.
- `backend/tests/test_reports*`는 DB row와 `backend/uploads/test` 파일을 남길 수 있다. disposable DB 또는 cleanup 기준이 선행되어야 한다.
- `/detect/v2`는 fake contract다. 실제 YOLO26s/COCO adapter 연결이나 성능 판단은 모델 lane 의존이며, fake 결과를 안전/성능 근거로 쓰면 안 된다.
- v2는 기존 `reports` 테이블을 재사용해 `class_id` 의미가 v1/v2에서 다르다. migration 없이 metadata와 query layer에서 먼저 분리하는 것이 안전하다.
- `/reports`, `/uploads`, status patch는 인증/권한/rate limit 없이 열려 있다. 외부 배포, 운영 DB, secret, S3, 지자체 API 연동은 자동 실행 계획에 넣지 않는다.
- `docs/api_reference.md`, `docs/inference_contract.md`는 2026-05-12 기준이라 v2 source of truth로 쓰면 안 된다.

## 병렬 에이전트 활용 메모
- 사용함.
- 하위 에이전트 1: product/docs/scheduler/PM 산출물 확인. 결론은 product M2 GIS/Ops slice가 신규 feature 후보이고, `plans/features`, `plans/verify/ready`, `PM/*` 산출물은 확인되지 않았다는 것.
- 하위 에이전트 2: backend code/tests/migration 상태 확인. 결론은 `/reports/v2`는 기존 reports 테이블 재사용, `/detect/v2`는 fake contract, 최신 신규 backend 테스트는 2026-05-23에 없다는 것.
- 통합 결론: 내일은 catch-up 반복보다 GeoJSON/Ops와 v2 report query/filter를 우선하고, PostGIS runtime은 환경 gate가 열릴 때만 PASS 처리한다.