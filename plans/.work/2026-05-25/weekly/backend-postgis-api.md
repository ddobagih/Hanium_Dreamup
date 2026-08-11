# Hanium Dreamup / WalkSafe Assist - Backend/PostGIS/API weekly lane note (2026-W22)

## 이번 주 목표 후보

- 기준선 고정: `plans/weekly/2026-W22.md`는 아직 없고, 이번 주 backend 기준은 `product/*.md`, `docs/current_status.md`, `docs/report_operations.md`, `docs/walksafe-v2/backend_api_contract.md`, `daylog/2026-05-25.md`, PM 5/25 feature/verify/product-audit 산출물로 둔다.
- 5/25 PM feature commit `82ec532`의 GeoJSON export/admin URL slice는 `verify blocked`, `push_ready=False`이므로 완료로 쓰지 않는다. 원본 checkout, clean worktree, PM feature worktree를 먼저 분리해 검증한다.
- 핵심 feature slice는 “검토 가능한 신고 export”로 잡는다. CSV/JSON/GeoJSON export가 `model_key`, `trigger`, `auto_reported`, `location_quality`, `review_flags`, `image_path`, 좌표 `[lng, lat]`, 좌표 없음 `geometry: null`을 안정적으로 제공하게 한다.
- `detect/v2 -> reports/v2 -> reports/{id} -> status patch -> reports/export CSV/JSON/GeoJSON -> cleanup`을 report ID 기준 evidence trace로 묶는다.
- PostGIS disposable DB가 가능하면 reports no-skip, Alembic head, upload validation, duplicate/radius를 최신 코드 기준으로 재검증한다. DB가 막히면 serializer/ASGI fixture까지만 PARTIAL로 기록한다.
- migration/security/auth/rate-limit/status history/운영 DB/public agency 자동 제출은 설계·backlog만 정리하고 실제 migration/운영 작업은 하지 않는다.

## 날짜별/단계별 체크리스트

- 2026-05-25 월: source-of-truth gate
  - [ ] 원본 checkout, clean worktree, PM feature worktree 상태 분리 -> 검증: `git status`, ahead/behind, PM `82ec532` marker와 verify blocked 상태 표기
  - [ ] backend 변경 충돌 후보 확인 -> 검증: `backend/app/api/reports.py`, `report_serialization.py`, `test_reports_v2.py`, PM `test_reports_export.py` 차이 목록화
  - [ ] 금지 범위 확인 -> 검증: 운영 DB, secret, migration 적용, 외부 제출, destructive cleanup 없음

- 2026-05-26 화: GeoJSON/export contract 인수 검증
  - [ ] CSV/JSON/GeoJSON 필드 기준 확정 -> 검증: v2 metadata 필터와 운영 검수 필드가 모두 포함되는지 fixture 확인
  - [ ] GeoJSON shape 고정 -> 검증: `FeatureCollection`, `Point [lng, lat]`, `geometry: null`, properties에서 원본 latitude/longitude 중복 제거 여부 확인
  - [ ] Admin export URL과 backend query 보존 확인 -> 검증: `status`, `class_name`, `model_key`, `trigger`, `auto_reported`, 날짜/반경 필터 유지

- 2026-05-27 수: detect/report/export trace
  - [ ] Stage1 `damaged_tactile_block` payload 1건을 `/reports/v2` 저장 입력으로 고정 -> 검증: `custom_tactile`, `threshold_used=0.5`, GPS/heading 포함
  - [ ] 저장 허용/거부 matrix 확인 -> 검증: `damaged_tactile_block` 저장, `tactile_damage_area`, `normal_tactile_block`, `coco_general`은 422 또는 저장 제외
  - [ ] report ID trace 작성 -> 검증: detail 조회, status `new -> reviewed -> resolved`, CSV/JSON/GeoJSON export, cleanup이 같은 ID로 연결됨

- 2026-05-28 목: PostGIS/runtime 재검증
  - [ ] Alembic/PostGIS gate -> 검증: disposable DB, `alembic current`, `upgrade head`, PostGIS extension/SRID 4326
  - [ ] reports no-skip -> 검증: `python -m pytest backend/tests/test_reports.py backend/tests/test_reports_v2.py backend/tests/test_reports_export.py -q -rs`
  - [ ] upload/duplicate/radius matrix -> 검증: JPEG/PNG/WebP 성공, 5개 upload error, 같은 class 25m/10분 duplicate, partial radius 400

- 2026-05-29 금: 운영 검수/export kit
  - [ ] public agency manual export kit 초안 -> 검증: reviewed 후보, 중복 후보, 오탐 검토, 위치 품질, review flags 포함. 자동 POST 없음
  - [ ] cleanup 기준 문서화 -> 검증: 실행 전후 row count, upload 파일 count, disposable reset 또는 명시 삭제 기록
  - [ ] migration/security backlog -> 검증: status history, DB constraint, `updated_at` trigger, auth/rate-limit, EXIF/storage 정책을 C 작업으로 분리

- 2026-05-30 토: 회귀/문서 정리
  - [ ] backend API 문서와 product 용어 정합성 확인 -> 검증: `server-v2`, `/detect/v2`, `/reports/v2`, `custom_tactile`, `damaged_tactile_block` 기준으로 legacy 표현 목록화
  - [ ] evidence 문서 갱신 -> 검증: fake/mock/headless/PostGIS/field 근거를 분리해 `docs/execution` 후보에 정리

- 2026-05-31 일: 주간 마감
  - [ ] PASS/PARTIAL/BLOCKED 요약 -> 검증: 실행한 명령만 PASS, 미실행은 환경/의존성 사유 기록
  - [ ] 다음 주 이관 -> 검증: DB 필요, 장비 필요, 제품 결정 필요, C 작업으로 분류

## 검증 계획

- Static: `git diff --check`, backend 관련 `py_compile`, 변경 파일 범위 확인.
- Unit/fixture: GeoJSON serializer fixture, `backend/tests/test_reports_v2.py`, PM feature의 `backend/tests/test_reports_export.py` 후보 인수 여부 확인.
- Integration: disposable DB에서 reports no-skip pytest, Alembic head, PostGIS radius query, `/reports/v2` 저장/조회/status/export.
- Trace smoke: `scripts/check_detect_report_export_trace_20260524.py`를 최신 worktree 기준으로 재실행하거나 같은 항목을 ASGI client로 재현.
- Upload matrix: 허용 MIME 3종과 `unsupported_image_type`, `image_extension_mismatch`, `empty_image`, `image_content_mismatch`, `upload_too_large`.
- Cleanup: 생성 report row와 upload 파일을 실행 전 상태로 되돌린 근거 기록.
- 미실행 원칙: PostGIS/TCP/pytest/geoalchemy2가 없으면 runtime PASS 금지, fixture PARTIAL로만 기록.

## 리스크/확인 필요

- PM 5/25 verify는 sandbox mount quota 오류로 막혔다. `82ec532`은 검증 대기이며 push-ready가 아니다.
- 원본 checkout은 upstream보다 10커밋 behind이고 dirty 상태다. `git add .`, reset, 강제 push, 대형 산출물 staging 금지.
- PM feature의 GeoJSON serializer는 운영 검수 필드가 일부 좋지만, 현재 v2 metadata 필터/CSV/JSON 계약과 충돌 가능성이 있어 그대로 채택하면 안 된다.
- Docker/PostGIS/local TCP가 막히면 reports runtime, duplicate/radius, cleanup 검증은 BLOCKED다. 대안은 serializer fixture와 ASGI direct handler다.
- Stage1 image smoke는 저장 이미지/ASGI 근거다. 실폰 field accuracy나 PDF 지연 1초 달성 근거로 쓰지 않는다.
- `/reports`, `/uploads`, status patch는 인증/rate-limit 없이 열려 있다. 외부 공개 전 보안/운영 정책이 필요하다.
- 운영 DB, 실제 migration 적용, 지자체 API POST, S3/secret, destructive cleanup은 사용자 승인 전 C 작업으로 유지한다.

## 병렬 에이전트 활용 메모

- 이번 lane note 작성에는 별도 하위 에이전트를 사용하지 않았고, 문서/PM/backend 파일 읽기만 병렬 조회로 처리했다.
- 주간 실행에서는 병렬화 가능하다: Export Contract 담당, PostGIS Runtime 담당, Detect-Report Trace 담당, Ops/Backlog 문서 담당으로 나누되 같은 backend 파일을 동시에 수정하지 않는다.
- 최종 PASS/BLOCKED 판정과 daylog 통합은 단일 담당자가 합쳐야 한다.