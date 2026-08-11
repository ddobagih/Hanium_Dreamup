# Hanium Dreamup / WalkSafe Assist - Backend/PostGIS/API lane note (2026-05-27)

## 최근 진행 근거
- `AGENTS.md`: 프로젝트 루트 파일은 없음. 대화에 제공된 AGENTS 지침을 기준으로 적용. 확인일: 2026-05-26 KST.
- `product/vision.md`, `product/roadmap.md`, `product/backlog.md`, `product/done-criteria.md`, `product/decisions.md`: backend 핵심은 PostGIS 신고 저장/조회/중복/상태 변경, fake 분리, GeoJSON/GIS/Ops, disposable DB 검증이다. 운영 DB, migration, 인증/배포, 지자체 자동 제출은 C 작업으로 분리. 기준 결정: 2026-05-18, 2026-05-24.
- `docs/current_status.md` 기준일 2026-05-25: `/detect/v2`, `/reports/v2`, `/reports/export`, `/reports/summary`, `/navigation/walking` 흐름이 구현되어 있고, 다음 gap은 Stage1 payload→report→export trace, PostGIS no-skip, Admin/Ops export 검증이다.
- `docs/report_operations.md` 2026-05-23, `docs/walksafe-v2/public_agency_submission_policy.md` 2026-05-24: `custom_tactile:damaged_tactile_block`만 신고 저장, `tactile_damage_area`/일반 객체는 저장 거부. 공공기관 자동 제출은 보류하고 reviewed 후보 export/manual submission을 우선한다.
- `daylog/2026-05-25.md`: `/reports`/`/reports/export`에 `demo_filter=all|only_fake|exclude_fake`, GeoJSON export, `redacted`, CSV BOM, distance metadata 보존, detect→report→export trace dry-run/DB gate가 기록됨. 검증 일부는 PASS였으나 field/운영 DB는 제외.
- `daylog/2026-05-26.md`: 원본 checkout은 upstream보다 10커밋 behind이고 dirty/untracked가 많음. PM feature commit `1320a07`은 `/detect/v2` adapter seam, `/reports` v2 metadata filter, PWA Wake Lock, local TTS fallback을 구현했으나 verify 실패.
- `/home/ddobagi/PM/status/2026-05-26-features.md`: Hanium 4개 slice는 isolated worktree에서 commit 완료, push 정책은 verify-passed.
- `/home/ddobagi/PM/status/2026-05-26-verify.md`, `/home/ddobagi/PM/reports/verify/2026-05-26/hanium-dreamup/2026-05-26-hanium-dreamup-feature-batch-report.md`: Hanium verify status `fail`, `push_ready=False`. 원인은 PWA `useWakeLock.ts` lint 실패이며, backend reports PostGIS no-skip은 Docker socket 권한 문제로 `12 passed, 27 skipped`.
- `/home/ddobagi/PM/status/product-audit/2026-05-26.json`: product 문서 5개 모두 존재, heuristic score 100. 2026-05-26 product-audit markdown/report는 확인되지 않음.
- `plans/features/2026-05-26_feature_followup_implementation_list.md`: backend 관련 다음 후보는 #12 Stage1 detect→report→export trace, #13 export/preset, #14 summary/cluster, #15 fake/demo 분리, #16 검수 상태 workflow.
- `plans/daily/2026-05-27.md`: 확인되지 않음. 프로젝트 내부 `plans/verify`도 없음. scheduler 산출물은 `/home/ddobagi/PM/...` 기준으로 확인.

## 내일 목표 후보
1. **기관 제출 후보 export preset / submission-readiness thin slice**: 실제 기관 POST 없이 `reviewed + exclude_fake + custom_tactile + redacted/BOM/GeoJSON` 조합을 backend/API 또는 script/test로 고정한다.
2. **신고 검수 workflow hardening**: 상태 전이 guard, review note, resolution reason, optimistic conflict(`expected_updated_at`), JSONB `status_history`를 migration 없이 fixture/test로 검증한다.
3. **Stage1 server payload → report → export trace 강화**: `source=server`, `model_key`, `source_model`, `threshold_used`, bbox, GPS/heading, `distance_m`, reject 대상, export CSV/JSON/GeoJSON deep field를 report ID 기준으로 대조한다.
4. **PostGIS reports no-skip + upload/duplicate/radius matrix 재검증**: disposable DB가 가능할 때만 Integration PASS로 기록하고, 불가하면 Unit/fixture PARTIAL로 제한한다.
5. **reports summary/cluster 신뢰도 보강**: `/reports/summary`가 list limit과 무관하게 status/source/fake/located/cluster count를 반환하는지 필터 조합 테스트를 추가한다.
6. **05-26 PM backend slice 인수 확인**: `/detect/v2` adapter seam과 `/reports` v2 filter는 backend 관점에서 재검증하되, 전체 feature commit은 PWA lint 실패가 해소되기 전 push-ready로 보지 않는다.

## 상세 체크리스트 초안
- [ ] worktree/source gate 기록 → 검증: 원본 `git status`, behind `0 10`, PM worktree commit `1320a07`, dirty 파일 범위를 표로 기록
- [ ] 05-26 backend slice 재검증 → 검증: `backend/tests/test_detect_v2.py`, `backend/tests/test_reports_v2.py`, `model/test_two_model_runtime.py` 실행 또는 deps/DB BLOCKED 사유 기록
- [ ] submission-ready export preset 설계 → 검증: `status=reviewed`, `demo_filter=exclude_fake`, `model_key=custom_tactile`, `format=csv/json/geojson`, `redacted=true`, `bom=true` query fixture 확인
- [ ] export deep fields 고정 → 검증: `model_key`, `source_model`, `trigger`, `auto_reported`, `distance_m`, `location_quality`, `review_flags`, `image_path` redaction, `Cache-Control: no-store`, attachment filename 테스트
- [ ] status workflow 테스트 보강 → 검증: `new→reviewed→resolved`, invalid transition 422, `resolved→new` 차단, conflict 409, note/reason/history 보존 확인
- [ ] Stage1 trace script 확장 → 검증: `--require-server-source`, reject 대상 422, CSV/JSON/GeoJSON field 대조, report id/export URL/cleanup 결과를 JSON/Markdown artifact로 기록
- [ ] non-reportable v2 저장 차단 회귀 → 검증: `normal_tactile_block`, `tactile_damage_area`, `coco_general`, unknown class가 `/reports/v2`에서 저장되지 않음
- [ ] PostGIS no-skip 가능 시 실행 → 검증: disposable DB에서 reports 관련 pytest가 skip 없이 PASS, Alembic head, 생성 row/upload cleanup 전후 count 기록
- [ ] upload validation matrix 확인 → 검증: JPEG/PNG/WebP 성공, unsupported MIME, extension mismatch, empty image, content mismatch, too large 오류 코드 확인
- [ ] duplicate/radius matrix 확인 → 검증: 같은 class 25m/10분 후보 포함, class mismatch/out-of-window/out-of-radius 제외, partial radius query 400
- [ ] `/reports/summary` 필터 조합 검증 → 검증: `demo_filter`, `status`, `source`, `model_key`, `trigger`, `auto_reported`, radius/date 필터에서 count/cluster가 일관됨
- [ ] migration backlog 분리 → 검증: DB constraint, 별도 status history table, auth/rate-limit, external storage, submissions table은 승인 필요 C 작업으로 문서화만 수행

## 리스크/확인 필요
- PM feature commit `1320a07`은 verify 실패 상태다. safe alternative: backend slice만 독립 재검증하고, 전체 push-ready 판단은 PWA lint 수정 후로 둔다.
- 원본 checkout은 dirty + behind 10이다. safe alternative: PM isolated worktree 또는 clean worktree 기준으로 검증하고 `git add .`, reset, 강제 push는 금지한다.
- Docker/PostGIS 권한이 없으면 reports runtime PASS가 아니다. safe alternative: serializer/Pydantic/ASGI fixture와 dry-run policy만 PARTIAL로 기록한다.
- 실제 YOLO weight/GPU/대형 dataset이 필요하면 자동 실행하지 않는다. safe alternative: 5/24 Stage1 payload fixture 또는 mocked adapter seam으로 계약만 검증한다.
- 기관 자동 제출, 운영 DB export, secret, 외부 storage, 지자체 API POST는 실행 대상이 아니다. safe alternative: reviewed-only manual export preset과 preview artifact.
- status history는 현재 JSONB payload 기반이 안전 대안이다. 별도 테이블/migration은 C-001로 두고 rollback/disposable DB 계획 전까지 적용하지 않는다.
- 업로드 검증은 MIME/header 중심이다. EXIF 제거, 악성 파일 검사, auth/rate-limit는 운영 보안 완료로 쓰지 않는다.
- 추정: 2026-05-27 backend 신규 slice 중 제품 목표를 가장 밀 수 있는 항목은 “기관 제출 후보 export preset + 검수 workflow hardening”이다. 실제 connector는 기관/개인정보/권한 확인 전까지 보류한다.

## 병렬 에이전트 활용 메모
- 하위/병렬 에이전트는 사용하지 않았다.
- 이유: 최종 산출물이 단일 lane note이고, 문서/코드/PM 산출물 읽기 중심이라 별도 에이전트보다 단일 통합 판단이 충돌 위험이 낮았다.
- 독립 파일 조회와 상태 확인은 병렬 shell 조회로 처리했고, 결론은 이 note에 통합했다.