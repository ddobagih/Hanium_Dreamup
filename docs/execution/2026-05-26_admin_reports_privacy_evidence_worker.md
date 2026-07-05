# 2026-05-26 Admin/Reports/Privacy/Evidence worker

## 범위

- 사용자 승인 범위: 실제 전화/SMS, 실제 LLM/Cloud NLU 제외. 운영 DB/외부 기관 제출/배포/삭제 없음.
- 실행 범위: local/mock/static/dry-run, PostGIS test DB가 연결된 local ASGI 테스트.

## 구현

- #12 detect → report → export trace
  - `/reports/v2` 저장 payload에 `trace_id`, `payload_sha256`, `image_sha256`, `data_origin`, `runtime_mode`, `performance_excluded`를 보존/생성.
  - CSV/JSON/GeoJSON export에 bbox, heading, threshold, hashes, fake/demo 제외 사유, status history count를 포함.
  - `scripts/check_detect_report_export_trace_20260524.py`에 `--require-server-source`, artifact JSON/Markdown 출력, dry-run Pydantic/policy 검증 추가.
- #13 export/Admin
  - demo-aware filename/header, JSON manifest, redacted/public export, GeoJSON grid aggregate 추가.
  - Admin에 공개용 GeoJSON, export manifest, 운영자 내부 검토(redacted) 링크 추가.
- #14 summary/cluster
  - `/reports/summary`에 grid/top limit, bounds, status/source breakdown 추가.
  - Admin은 list limit와 무관한 backend summary를 사용하고 cluster 반경 필터 버튼을 제공.
- #15 fake/demo 분리
  - backend/frontend fake 판정에 `data_origin=demo`, `performance_excluded=true` 반영.
  - row/detail badge와 export filename/header로 demo 포함 여부 표시.
- #16 검수 workflow
  - Admin review note/resolution reason 입력, status history 표시, `expected_updated_at` optimistic conflict payload 전송.
- 개인정보/보존
  - decodable 이미지 업로드 EXIF 제거/재인코딩 helper와 fixture 추가.
  - `docs/data_retention_policy.md` 작성.
  - `scripts/check_report_retention_dry_run.py` 추가. 삭제 없이 후보만 출력하고 `--execute-delete`는 차단.

## 검증

- `PYTHONPATH=. .venv/bin/python -m py_compile backend/app/api/reports.py backend/app/uploads.py backend/app/services/report_serialization.py scripts/check_detect_report_export_trace_20260524.py scripts/check_report_retention_dry_run.py` → PASS
- `PYTHONPATH=. .venv/bin/python scripts/check_report_retention_dry_run.py --input-json <fixture> --as-of 2026-05-26T00:00:00Z --output-md /tmp/report_retention_dry_run.md` → PASS, `destructive_action=false`, candidates 2
- `PYTHONPATH=. .venv/bin/python scripts/check_detect_report_export_trace_20260524.py --dry-run-policy --reject-target-check always` → PASS
- `PYTHONPATH=. .venv/bin/python scripts/check_detect_report_export_trace_20260524.py --reject-target-check auto --artifact-json /tmp/detect_report_export_trace.json --artifact-md /tmp/detect_report_export_trace.md` → PASS
- `PYTHONPATH=. .venv/bin/python scripts/check_detect_report_export_trace_20260524.py --detection-payload <server fixture> --reject-target-check auto --require-server-source --artifact-json /tmp/detect_report_export_trace_server.json --artifact-md /tmp/detect_report_export_trace_server.md` → PASS
- `PYTHONPATH=. .venv/bin/python -m pytest backend/tests/test_uploads.py backend/tests/test_reports_v2.py -q -rs` → 28 passed
- `PYTHONPATH=. .venv/bin/python -m pytest backend/tests/test_reports.py -q -rs` → 17 passed
- `PYTHONPATH=. .venv/bin/python -m pytest backend/tests -q -rs` → 87 passed
- `bash scripts/check_frontend_admin_report_summary_policy_20260525.sh` → PASS
- `cd apps/web && npm run typecheck` → PASS
- `cd apps/web && npm run lint` → PASS
- `git diff --check -- <worker files>` → PASS
- `PYTHONPATH=. .venv/bin/python scripts/check_report_retention_dry_run.py --execute-delete` → exit 2, 삭제 차단 PASS

## 남은 리스크

- 실제 외부 기관 제출, 운영 DB 삭제/보존 실행, 배포는 하지 않음.
- Admin UI는 static/headless/typecheck/lint 기준이며 실제 브라우저 클릭 E2E와 운영 지도 SDK는 미검증.
- 실제 Stage1 server trace는 local ASGI/PostGIS와 fixture 기준 PASS이며 운영 모델/운영 DB/외부 전송 PASS가 아니다.
