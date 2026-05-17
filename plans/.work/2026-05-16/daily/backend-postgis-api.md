# Hanium Dreamup / WalkSafe Assist - Backend/PostGIS/API lane note (2026-05-17)

## 최근 진행 근거
- `docs/execution/2026-05-14_detect_adapter_implementation.md` (2026-05-14): local Ultralytics YOLO `.pt` 기반 `/detect` adapter 구현. 모델 미설정 시 `model_not_configured`, `.pt` 로드 성공 시 `ready`, detection `source="server"` 계약 정리.
- `docs/execution/2026-05-15_backend_postgis_followup.md` (2026-05-15): PostGIS healthy, Alembic `202605120001 (head)`, 당시 `backend/tests` `22 passed`. 테스트 후 DB row/upload 파일 증가도 기록됨.
- `docs/execution/2026-05-15_runtime_followup_after_reset.md` (2026-05-15): 테스트 row/upload reset 완료. v2 `best.pt` env에서 `/detect/health ready`, known-positive 이미지 `/detect` 결과 `source=server`, detections 4개 확인.
- `docs/execution/2026-05-15_pwa_server_detection_e2e.md`, `docs/execution/2026-05-15_pwa_production_server_e2e.md` (2026-05-15): dev/prod headless E2E에서 `/detect` → `/reports` 저장까지 통과, 저장 report와 `metadata.source`가 모두 `server`로 확인됨.
- `docs/execution/2026-05-16_backend_postgis_api.md` (2026-05-16): `backend/tests/test_reports.py`에 `limit`, `created_to`, JPEG/PNG/WebP 업로드, `new -> reviewed -> resolved`, `empty_image` 테스트 추가. `py_compile`은 PASS, PostGIS 접근 불가로 reports runtime은 `17 skipped`.
- `docs/execution/2026-05-16_model_data_mlops.md` (2026-05-16): backend 권장 env는 `best.pt`, `MODEL_VERSION=walksafe-kr-tactile-v2-full-20260514-best-02a6be87`, threshold `0.35`, IOU `0.7`, image size `640`. `best.onnx`는 존재하지만 현재 backend adapter는 `.pt`만 ready 지원.
- `daylog/2026-05-16.md` (2026-05-16): Backend lane의 최신 미완료는 PostGIS/Docker 접근 가능한 일반 개발 세션에서 `backend/tests/test_reports.py`와 전체 backend tests 재실행 필요로 기록됨.
- `plans/daily/2026-05-17.md`: 현재 파일 없음.

## 내일 목표 후보
- 1순위: PostGIS 접근 가능한 일반 개발 세션에서 5/16에 보강된 reports 테스트와 전체 backend suite를 재검증한다.
- 2순위: 격리된 DB/UPLOAD_DIR 기준으로 실제 HTTP reports smoke를 실행하고 생성 row/upload cleanup까지 확인한다.
- 3순위: `/detect`의 ready/unavailable 회귀를 같은 날 다시 확인하고, backend는 `.pt` artifact만 ready 지원한다는 운영 기준을 고정한다.
- 4순위: stale backend 문서의 “placeholder/adapter 미구현” 표현을 최신 상태 기준으로 갱신할 PR 범위를 정한다.
- 5순위: DB migration backlog 중 작은 1개를 다음 PR 후보로 확정한다. 우선 후보는 `status/source/class_name` check constraint 또는 `updated_at` trigger다.
- 6순위: 외부 공개 전 보안/운영 backlog를 분리한다. 인증/권한, public `/uploads`, CORS, rate limit, request size limit, EXIF 제거, persistent storage가 대상이다.

## 상세 체크리스트 초안
- [ ] PostGIS 재현성 확인 → 검증: `docker compose up -d db`, `python -m alembic -c backend/alembic.ini upgrade head`, `current`가 `202605120001 (head)`인지 기록
- [ ] 전체 backend 테스트 재실행 → 검증: PostGIS 접근 가능한 세션에서 `python -m pytest backend/tests -q -rs` 실행, 현재 `test_detect` 11개와 `test_reports` 17개 기준 결과 기록
- [ ] reports 테스트 부작용 정리 → 검증: 실행 전후 `reports` count와 `backend/uploads/test` 파일 수 기록, 필요 시 `TRUNCATE reports`와 업로드 파일 삭제 후 0건 확인
- [ ] read-only HTTP smoke → 검증: uvicorn 기동 후 `GET /health`, `GET /detect/health`, `GET /reports?limit=1` 응답 기록
- [ ] write HTTP smoke → 검증: `POST /reports`, `GET /reports/{id}`, `GET /reports` 필터, `PATCH /reports/{id}/status` 확인 후 cleanup
- [ ] upload matrix/negative smoke 확인 → 검증: JPEG/PNG/WebP 성공, `unsupported_image_type`, `image_extension_mismatch`, `image_content_mismatch`, `empty_image`, `upload_too_large` status와 `detail.code` 기록
- [ ] duplicate/radius 확인 → 검증: 같은 class/25m/±10분 중복 ID 반환, partial `lat/lng/radius_m` 요청 HTTP 400 확인
- [ ] `/detect` unavailable 회귀 확인 → 검증: 모델 env 미설정 상태에서 `/detect/health unavailable`, `POST /detect` 503 `model_unavailable`
- [ ] `/detect` ready smoke 확인 → 검증: 권장 `MODEL_ARTIFACT_PATH=.../best.pt` env에서 `/detect/health ready`, known-positive `/detect` 200, detection `source=server`
- [ ] ONNX 처리 기준 고정 → 검증: `.onnx`는 현재 export 산출물이지만 backend ready artifact가 아님을 문서/환경 가이드에 명시
- [ ] 문서 충돌 갱신 범위 산정 → 검증: `README.md`, `docs/current_status.md`, `docs/pwa_backend_status.md`, `docs/model_integration_plan.md`, `docs/backend_3day_execution_plan.md` 중 backend 관련 문장 목록 작성
- [ ] DB migration PR 후보 결정 → 검증: check constraint, `updated_at` trigger, status history 중 하나만 선택하고 예상 테스트와 rollback 기준 작성

## 리스크/확인 필요
- 5/16 세션에서는 Docker/PostGIS 접근이 막혀 reports runtime PASS가 확인되지 않았다. FastAPI `TestClient` timeout은 sandbox 제약으로 보인다는 추정이며, 일반 개발 세션에서 재검증이 필요하다.
- `backend/tests/test_reports.py`는 DB row와 업로드 파일을 남길 수 있다. disposable DB 또는 명시적 cleanup 없이는 반복 실행 결과가 누적된다.
- 현재 `/reports`, `/uploads`, status patch는 인증 없이 열려 있다. 외부 공개나 장시간 네트워크 노출 전 제한이 필요하다.
- 업로드 검증은 MIME/확장자/크기/헤더 중심이다. 전체 이미지 decode, EXIF 제거, 악성 파일 검사는 아직 없다.
- `best.onnx`는 export 및 raw tensor smoke 근거가 있지만 backend adapter는 `.pt`만 지원한다.
- v2 모델은 class `0 damaged_tactile_block` 중심 baseline이다. 4개 위험 클래스 전체 성능 근거로 표현하면 안 된다.
- 일부 문서는 여전히 `/detect` placeholder 또는 adapter 미구현 전제를 포함한다. 최신 판단은 5/14 adapter 구현과 5/15 server E2E 근거를 우선한다.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 이번 작업은 backend 문서, daylog, 테스트 파일, adapter 코드 확인으로 범위가 좁고 최종 산출물이 단일 lane note라 병렬화 이득이 작았다.