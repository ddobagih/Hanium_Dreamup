# Hanium Dreamup / WalkSafe Assist - Backend/PostGIS/API lane note (2026-05-25)

## 최근 진행 근거
- `product/roadmap.md`, `product/backlog.md`, `product/done-criteria.md`, `product/decisions.md` (2026-05-18): 운영 모드는 `feature-growth`; Backend 핵심은 PostGIS reports no-skip, HTTP smoke, duplicate/radius, upload 검증이며 migration/운영 DB/외부 연동은 C 작업.
- `plans/daily/2026-05-24.md` (작성 2026-05-23): Backend 목표는 GeoJSON/Ops thin slice, `/reports/v2` HTTP trace, upload/duplicate/cleanup. 단 문서의 “`/detect/v2` real adapter 미완료” 판단은 2026-05-23 후속 작업 이후 stale.
- `docs/current_status.md`, `docs/walksafe-v2/backend_api_contract.md`, `docs/report_operations.md` (2026-05-23): `/detect/v2`, `/reports/v2`, `/reports/export`, `/navigation/walking` 구현. v2 신고는 `custom_tactile:damaged_tactile_block`만 허용하고 GPS 필수.
- `daylog/2026-05-22.md`: `/detect/v2`, `/reports/v2`, v2 자동/음성 신고, route/service split 구현 후 backend/model 관련 50 tests PASS 기록.
- `daylog/2026-05-23.md`: lazy YOLO provider, `/reports/export`, v2 metadata filters 추가 후 110 tests PASS. Stage1 `best.pt`를 MVP/backend 후보로 선택했고 real `/detect/v2` ASGI smoke가 adapter 수정 후 2 detections로 PASS.
- `daylog/2026-05-24.md`: TMAP `/navigation/walking` provider 전환 확인, navigation test 7 passed, web lint/typecheck PASS. 통합 재시도는 21 passed, 14 skipped, 3 errors로 detect v2 임시 디렉터리 setup 문제가 남음.
- `backend/app/api/reports.py`, `backend/app/services/report_policy.py`, `backend/app/services/duplicates.py`: `/reports`, `/reports/v2`, list/export/duplicate/detail/status 구현. 중복 기준은 같은 class, 25m, ±10분.
- `backend/alembic/versions/202605120001_create_reports.py`: 현재 migration은 PostGIS extension + `reports` 테이블 1개. v2는 신규 migration 없이 JSONB metadata 재사용.
- `plans/features`, `plans/verify/ready`, `plans/verify/reports`: 디렉터리는 있으나 파일 없음. `PM` 디렉터리도 없어 feature/verify/product-audit/automation-metrics 산출물 확인 불가.
- `plans/daily/2026-05-25.md`: 없음.

## 내일 목표 후보
- 1순위: **신고 GeoJSON/Ops export thin slice**. 기존 `/reports/export`의 CSV/JSON 흐름 위에 `format=geojson` 또는 내부 serializer를 붙여 product M2 GIS/Ops 목표를 전진.
- 2순위: **real `/detect/v2` → `/reports/v2` → export/status evidence trace**. Stage1 후보 env와 GPS fixture로 server source 신고가 저장·조회·필터·export되는지 report ID 기준으로 기록.
- 3순위: **PostGIS reports no-skip + HTTP smoke 재검증**. disposable DB가 가능할 때만 Alembic, reports tests, upload/duplicate/radius, cleanup을 PASS 처리.
- 4순위: **5/24 backend subset 3 errors 원인 분리**. detect v2 임시 디렉터리 setup 문제를 재현/격리해 통합 검증 신뢰도 회복.
- 5순위: **upload/duplicate matrix 고정**. JPEG/PNG/WebP 성공, 오류 5종, partial radius 400, duplicate advisory를 한 묶음으로 문서화.
- 6순위: **migration/security backlog 정리만**. constraint, status history, auth/rate limit, storage/EXIF 보강은 설계 또는 disposable DB dry-run 후보로만 둠.

## 상세 체크리스트 초안
- [ ] runtime/worktree gate 기록 → 검증: `git status --short --branch --untracked-files=all`, upstream behind, `DATABASE_URL`, `UPLOAD_DIR`, 포트 `5432/8000`, 실행 전 reports count와 upload 파일 수 기록
- [ ] GeoJSON export thin slice 설계/구현 후보 확정 → 검증: synthetic fixture 2~3건이 `FeatureCollection`으로 변환되고 좌표 `[lng, lat]`, `id/status/class_name/source/model_key/trigger/auto_reported/location_quality/review_flags` 포함
- [ ] GeoJSON safe alternative 준비 → 검증: DB가 막히면 pure serializer/unit fixture까지만 PASS, PostGIS/GIS runtime은 BLOCKED로 기록
- [ ] Stage1 real `/detect/v2` ASGI smoke 재확인 → 검증: `/detect/v2/health` ready, controlled image에서 `damaged_tactile_block`, `threshold_used=0.50`, non-fake `source_model` 확인. field 성능으로 쓰지 않음
- [ ] `/reports/v2` evidence trace 실행 가능 시 진행 → 검증: GPS 포함 `damaged_tactile_block` POST, `source=server`, `GET /reports/{id}`, `GET /reports?model_key=custom_tactile`, `trigger/auto_reported` filter, `PATCH status`, export 확인
- [ ] reports no-skip 테스트 가능 시 실행 → 검증: `python -m pytest backend/tests/test_reports.py backend/tests/test_reports_v2.py -q -rs`가 skip 없이 PASS하거나 DB 차단 사유 기록
- [ ] upload/duplicate/radius matrix 확인 → 검증: JPEG/PNG/WebP 성공, `unsupported_image_type`, `image_extension_mismatch`, `empty_image`, `image_content_mismatch`, `upload_too_large`, duplicate 25m/10분, partial radius HTTP 400
- [ ] cleanup 수행 → 검증: 생성 report row와 upload 파일을 삭제하거나 disposable DB reset으로 실행 전 count/file 상태 복귀
- [ ] 5/24 detect v2 temp-dir errors 격리 → 검증: 관련 backend subset을 별도 `TMPDIR`/cache 설정으로 재실행하고, 코드 실패인지 환경 setup 실패인지 분리
- [ ] migration 후보 메모 → 검증: 신규 migration 적용 없음. check constraint, status history, `updated_at` trigger, auth/rate-limit/storage/EXIF 제거 후보와 rollback 조건만 정리

## 리스크/확인 필요
- Docker/PostGIS/local TCP가 막히면 reports runtime은 완료 처리 불가. safe alternative는 no-DB tests, schema checklist, synthetic GeoJSON fixture.
- real `/detect/v2`는 local weight와 `yolo26n.pt`가 필요하고 cold load/업로드 크기 이슈가 있다. 대형 이미지나 `MAX_UPLOAD_BYTES` 변경은 smoke 범위로만 제한.
- `/reports/v2`는 GPS 필수다. GPS 없는 자동/음성 신고 실패는 현재 정책상 예상 422로 봐야 한다.
- `backend/uploads`에 기존 로컬 파일이 많다. 내일 smoke는 실행 전후 파일 수와 cleanup을 반드시 남긴다.
- 업로드 검증은 header 수준이다. full image decode, EXIF 제거, 악성 파일 검사는 미구현이므로 운영 보안 완료로 쓰지 않는다.
- `/reports`, `/uploads`, status patch는 인증/권한/rate-limit 없이 열려 있다. 외부 배포, 운영 DB, secret, S3, 지자체 API 직접 연동은 계획 실행 대상에서 제외.
- product 문서 일부는 Kakao 후순위 표현이 남아 있고, 최신 v2 문서는 TMAP proxy를 source of truth로 본다. Backend 계획은 최신 `docs/walksafe-v2/*` 기준으로 보정 필요.
- PM/product-audit/automation-metrics 산출물이 없으므로 자동화 성과는 daylog/execution/docs 근거로만 판단.

## 병렬 에이전트 활용 메모
- 사용함.
- 하위 에이전트 1: product/scheduler/PM 산출물 확인. 결론은 `feature-growth`, PostGIS no-skip, GIS/Ops 목표가 유효하고 `plans/features`, `plans/verify`, `PM/*` 산출물은 없다는 것.
- 하위 에이전트 2: backend code/tests/docs/daylog 확인. 결론은 `/detect/v2` real provider와 `/reports/export`는 2026-05-23에 전진했고, 2026-05-25에는 PostGIS runtime, upload/duplicate, cleanup, temp-dir errors가 남은 핵심 검증이라는 것.
- 통합 결론: 내일은 기존 blocked 반복만 하지 말고 GeoJSON/Ops export와 real detect-to-report evidence trace를 신규 slice로 우선하되, PASS 판정은 disposable DB/ASGI smoke 등 검증 등급을 분리해 기록한다.