# Hanium Dreamup / WalkSafe Assist - Backend/PostGIS/API lane note (2026-05-20)

## 최근 진행 근거
- `AGENTS.md`: 프로젝트 루트 내부 파일은 없음. 사용자 제공 AGENTS 지침 적용.
- `plans/daily/2026-05-20.md`: 없음 확인.
- `docs/current_status.md` 2026-05-18 최신 보정: FastAPI/PostGIS 신고 API, 업로드 검증, 중복 후보, 상태 변경, `/detect` `.pt` adapter는 구현됨. 남은 일은 PostGIS runtime 재검증, 외부 스토리지, 배포 환경, 인증/권한.
- `docs/pwa_backend_status.md` 2026-05-17 최신 보정: server detector mode와 `source: "server"` 신고 저장 smoke 근거가 있으나, PostGIS runtime 재검증과 실폰 field 근거는 아직 없음.
- `docs/api_reference.md`, `docs/inference_contract.md`, `docs/report_operations.md` 2026-05-12: reports/detect 계약, 업로드 오류 코드, duplicate 기준, fake 신고 운영 기준이 정리됨.
- `docs/execution/2026-05-15_backend_postgis_followup.md`: Docker PostGIS healthy, Alembic `202605120001 (head)`, `python -m pytest backend/tests` `22 passed` 근거 있음. 단, 테스트가 row/upload를 생성함.
- `docs/execution/2026-05-15_runtime_followup_after_reset.md`: `reports` row `12 -> 0`, `backend/uploads/test` 정리 완료. 실제 `best.pt` `/detect` known-positive `source=server` 확인.
- `docs/execution/2026-05-15_pwa_server_detection_e2e.md`: PWA server-mode headless E2E PASS, `metadata.source=server`, cleanup 완료.
- `docs/execution/2026-05-19_backend_postgis_api.md`: 최신 재시도에서는 no-DB detect/uploads 테스트 `13 passed`, 전체 backend `13 passed, 17 skipped`. Docker/PostGIS/Alembic/reports HTTP smoke는 환경 제한으로 BLOCKED.
- `product/backlog.md`, `product/decisions.md` 2026-05-18: P0는 PostGIS reports no-skip + HTTP smoke. PostGIS smoke는 disposable DB 우선으로 결정됨. schema migration/auth/storage는 C 작업으로 보류.

## 내일 목표 후보
- 1순위: disposable DB 기준 PostGIS/Alembic/reports runtime을 최신 코드로 재검증한다.
- 2순위: reports HTTP smoke를 실제 서버로 확인하고 row/upload cleanup 전후 상태를 남긴다.
- 3순위: 업로드 성공/실패 matrix와 duplicate/radius 검색을 HTTP 기준으로 재확인한다.
- 4순위: `/detect` unavailable/ready 양쪽 회귀를 분리 확인한다. ready는 timeout 없는 known-positive 성공 응답만 PASS로 둔다.
- 5순위: DB migration 후보는 구현하지 말고 constraint, `updated_at` trigger, status history의 필요성과 영향만 작은 설계 메모로 정리한다.

## 상세 체크리스트 초안
- [ ] backend runtime gate 기록 → 검증: `git status`, `DATABASE_URL`, `UPLOAD_DIR`, `MODEL_ARTIFACT_PATH`, `MODEL_VERSION`, 포트 `5432/8000`, 실행 전 reports count와 upload 파일 수 기록.
- [ ] disposable DB 준비 → 검증: 운영/공유 DB가 아닌지 확인하고, 테스트 전 cleanup/rollback 절차를 문서화.
- [ ] PostGIS/Alembic 재검증 → 검증: `docker compose up -d db`, db healthy, `python -m alembic -c backend/alembic.ini upgrade head`, `current == 202605120001 (head)`.
- [ ] DB schema 확인 → 검증: PostGIS extension, `reports` 테이블/index, `Find_SRID('public','reports','location') == 4326`.
- [ ] reports 테스트 no-skip 실행 → 검증: `python -m pytest backend/tests/test_reports.py -q -rs`가 skip/fail 없이 PASS.
- [ ] 전체 backend suite 실행 → 검증: `python -m pytest backend/tests -q -rs` 결과에서 skip/fail/timeout 여부 기록.
- [ ] HTTP read/write smoke → 검증: `/health`, `/detect/health`, `/reports?limit=1`, `POST /reports`, 단건 조회, 필터, `PATCH /status` 응답 기록.
- [ ] upload matrix 확인 → 검증: JPEG/PNG/WebP 성공, `unsupported_image_type`, `image_extension_mismatch`, `empty_image`, `image_content_mismatch`, `upload_too_large`의 status/detail.code 기록.
- [ ] duplicate/radius 확인 → 검증: 같은 class/25m/±10분 신고에서 duplicate ID 반환, `lat/lng/radius_m` 일부 누락 시 HTTP 400 확인.
- [ ] `/detect` 회귀 확인 → 검증: 모델 미설정 `503 model_unavailable`, `best.pt` 설정 시 `/detect/health ready`, known-positive `/detect` `200`과 `source=server`.
- [ ] cleanup 수행 → 검증: 생성 report row와 upload 파일 삭제 후 count/file 상태가 실행 전 기준으로 돌아왔는지 기록.
- [ ] migration 후보 정리 → 검증: 코드 변경 없이 `status/source/class_name` constraint, `updated_at` trigger, status history, auth/rate-limit/storage를 C 작업 후보로 분리.

## 리스크/확인 필요
- Docker/PostGIS/local TCP가 막힌 환경이면 5월 19일과 동일하게 reports 테스트가 skip될 가능성이 높음.
- `backend/tests/test_reports.py`는 persistent DB row와 upload 파일을 만들 수 있으므로 disposable DB 또는 cleanup 전제 필요.
- `/detect/health ready`는 실제 inference 성공 근거가 아님. `/detect`가 timeout 없이 detection 응답을 반환해야 PASS.
- `/reports`, `/uploads`, status patch는 인증/권한 없이 열려 있어 외부 공개 전 CORS, rate limit, storage, auth 정책이 필요.
- v2 모델은 class `0` baseline이므로 backend `source=server` smoke를 4-class 서비스 성능으로 표현하면 안 됨.
- 현재 작업트리에는 `daylog/2026-05-19.md` 수정 상태가 있어 merge 시 기존 daylog 변경과 충돌하지 않게 확인 필요.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 이번 lane note는 문서/코드 읽기 기반 계획 수립이고 범위가 backend 단일 lane으로 좁아, 하위 에이전트 없이 직접 근거를 통합했다.