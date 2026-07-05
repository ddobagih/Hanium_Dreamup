# Hanium Dreamup / WalkSafe Assist - Backend/PostGIS/API lane note (2026-05-26)

## 최근 진행 근거
- `AGENTS.md`: 프로젝트 루트에는 없음. 사용자 제공 AGENTS 지침을 기준으로 적용. 확인일: 2026-05-25 KST.
- `product/vision.md`, `product/roadmap.md`, `product/backlog.md`, `product/done-criteria.md`, `product/decisions.md`: 신고 데이터는 이미지, 위치 품질, 검토 플래그, 중복 후보, 상태 이력/운영 검토까지 이어져야 하며, 공공기관 직접 제출은 보류하고 export/manual review 우선. 결정 기준: 2026-05-18, 2026-05-24.
- `docs/current_status.md` 기준일 2026-05-24: `/detect/v2`, `/reports/v2`, `/reports/export` CSV/JSON/GeoJSON, `/navigation/walking` 구현. 남은 backend gap은 Stage1 실제 detection payload → report → export trace, Admin export URL 회귀, PostGIS runtime 재검증.
- `docs/report_operations.md` 기준일 2026-05-23: `damaged_tactile_block`만 신고 저장, `tactile_damage_area`/COCO 일반 객체는 저장 거부, 중복 기준은 같은 class + 25m + 전후 10분, export는 기관 자동 전송이 아니라 검토/제출 준비용.
- `docs/walksafe-v2/backend_api_contract.md` 기준일 2026-05-23: v2 `model_key`, `trigger`, `auto_reported` 필터와 `/reports/export?format=geojson` 계약이 backend source of truth.
- `daylog/2026-05-24.md`: `/reports/export` GeoJSON 추가, detect → report → export trace script 추가, `backend/tests/test_reports_v2.py backend/tests/test_navigation_routes.py backend/tests/test_detect_v2.py -q` 34 passed 기록. 단, 일부 Stage1 image smoke 기록은 원본/clean worktree 간 내용 차이가 있어 재확인 필요.
- `daylog/2026-05-25.md`: clean worktree `0ee4be0`은 원격 최신과 일치, PM feature commit `82ec532`는 GeoJSON export/admin URL 등 신규 slice를 만들었지만 verify blocked/push_ready false.
- `/home/ddobagi/PM/status/2026-05-25-features.md`: Hanium 완료 slice 중 backend 관련은 GeoJSON export endpoint/admin URL. 운영 DB/export, live API, 실기기 센서/마이크 대신 local endpoint, mock, fixture를 선택.
- `/home/ddobagi/PM/status/2026-05-25-verify.md`: Hanium verify status `blocked`, attempts 3, push_ready `False`. 원인은 sandbox mount quota 오류.
- `/home/ddobagi/PM/status/2026-05-25-product-audit.md`: Hanium product 품질 91점. 보강 포인트는 v2 canonical summary와 legacy detector 용어 drift.
- `plans/daily/2026-05-25.md`: backend 후보는 GeoJSON/Ops export, Stage1 detect→reports trace, PostGIS no-skip, upload/duplicate/radius matrix, detect v2 temp-dir error 격리.
- `plans/daily/2026-05-26.md`: 확인 가능한 파일 없음.

## 내일 목표 후보
1. PM 5/25 GeoJSON export slice를 원본 checkout 기준으로 검증/통합 가능 상태까지 정리한다.
2. `server-v2` 또는 저장된 Stage1 detection payload 기준 `detect/v2 → reports/v2 → reports/{id} → status → export CSV/JSON/GeoJSON → cleanup` trace를 만든다.
3. PostGIS disposable DB에서 reports no-skip, duplicate/radius, upload validation matrix를 재검증한다.
4. GeoJSON export properties를 운영 검수에 맞게 보강한다: `location_quality`, `review_flags`, `model_key`, `trigger`, `auto_reported` 포함 여부를 확정한다.
5. DB migration/status history/auth/rate-limit/submissions 계층은 C 작업으로 설계만 남기고 실제 migration은 실행하지 않는다.

## 상세 체크리스트 초안
- [ ] 원본 checkout과 PM feature worktree 차이 확인 → 검증: `git status`, `git rev-list --left-right --count`, `git show --stat 82ec532`, 위험 파일 staging 없음 확인
- [ ] GeoJSON export contract 고정 → 검증: `backend/tests/test_reports_export.py`, `backend/tests/test_reports_v2.py`에서 FeatureCollection, `[lng, lat]`, `geometry: null`, 필터/limit 보존 확인
- [ ] GeoJSON properties 보강 여부 결정 → 검증: `location_quality`, `review_flags`, `model_key`, `trigger`, `auto_reported`가 export에 포함되는지 fixture로 확인
- [ ] detect-report-export trace 재실행 → 검증: `scripts/check_detect_report_export_trace_20260524.py`로 report ID 기준 저장/상세/status/export 확인
- [ ] non-reportable v2 대상 차단 확인 → 검증: `tactile_damage_area`, `normal_tactile_block`, `coco_general`이 `/reports/v2`에서 422 또는 저장 제외
- [ ] PostGIS no-skip 재검증 → 검증: disposable DB에서 `python -m pytest backend/tests/test_reports.py backend/tests/test_reports_v2.py backend/tests/test_reports_export.py -q -rs`
- [ ] upload validation matrix 확인 → 검증: JPEG/PNG/WebP 성공, unsupported MIME, extension mismatch, empty image, content mismatch, upload too large
- [ ] duplicate/radius matrix 확인 → 검증: 같은 class 25m/10분 후보 포함, class mismatch/out-of-window/out-of-radius 제외, partial radius query 400
- [ ] cleanup 절차 기록 → 검증: 생성 report row와 upload 파일 삭제 또는 disposable DB reset 전후 상태 기록
- [ ] migration backlog만 문서화 → 검증: status history, DB check constraint, `updated_at` DB trigger, auth/rate-limit, `submissions/manual_export`는 승인 필요 C 작업으로 분리

## 리스크/확인 필요
- PM 5/25 feature commit은 verify blocked 상태다. safe alternative: feature worktree에서 marker 후보 명령을 재실행하고, 통과한 파일만 원본 checkout에 선별 반영한다.
- 원본 checkout은 upstream보다 10커밋 뒤처지고 dirty 상태다. safe alternative: clean worktree 또는 feature worktree 기준으로 검증하고 `git add .` 없이 파일 단위로만 다룬다.
- PostGIS/Docker/TCP가 막히면 runtime PASS로 쓰면 안 된다. safe alternative: serializer/unit fixture와 ASGI no-DB 확인까지만 PARTIAL로 기록한다.
- PM feature 환경에서는 `pytest`, `geoalchemy2`, `node_modules` 부재가 기록됐다. safe alternative: 프로젝트 `.venv`/정상 deps 환경에서 재검증하거나 BLOCKED 사유를 명확히 남긴다.
- Stage1 image smoke 기록이 문서마다 다르게 보인다. safe alternative: 동일 worktree와 동일 이미지 세트로 3~5장 재실행하고 field accuracy로 과대해석하지 않는다.
- 운영 DB, 실제 migration 적용, secret, 외부 업로드, TMAP/Kakao live 호출, 공공기관 자동 제출은 자동 계획에서 직접 실행 금지. safe alternative: mock, fixture, dry-run, local adapter, export kit.
- 추정: 내일 backend lane의 가장 제품 지향적인 신규 slice는 “검토 가능한 GeoJSON/export kit 완성”이다. 실제 공공 제출 connector는 기관/권한/개인정보 결정 전까지 보류한다.

## 병렬 에이전트 활용 메모
- 이번 lane note 작성에는 별도 하위 에이전트를 사용하지 않았다.
- 이유: 최종 산출물이 단일 lane note이고, 조사 범위가 문서/상태/코드 읽기 중심이라 병렬 하위 에이전트보다 단일 통합 판단이 충돌 위험이 낮다.
- 독립 파일 읽기와 상태 확인은 병렬 shell 조회로 처리했다.