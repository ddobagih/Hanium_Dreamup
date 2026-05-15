# Hanium Dreamup / WalkSafe Assist - Backend/PostGIS/API lane note (2026-05-16)

## 최근 진행 근거
- 사용자 제공 AGENTS 지침 / 2026-05-15 KST: 저장소 내부 `AGENTS.md`는 확인되지 않아, 사용자 메시지의 지침을 적용.
- `docs/backend_3day_execution_plan.md` / 2026-05-13: 2026-05-16 목표는 `/detect` 실제 연결 리허설, 배포/보안 점검, 다음 PR 후보 분리.
- `docs/execution/2026-05-14_backend.md` / 2026-05-14: PostGIS healthy, Alembic `202605120001 (head)`, `backend/tests` 통과, `/health`, `/detect/health`, `/reports?limit=5` smoke 기록 있음.
- `docs/execution/2026-05-14_detect_contract_implementation.md` / 2026-05-14: `MODEL_ARTIFACT_PATH`, `MODEL_VERSION`, class order, confidence/IOU/image size 설정 계약 고정.
- `docs/execution/2026-05-14_detect_adapter_implementation.md` / 2026-05-14: Ultralytics YOLO `.pt` adapter 1차 구현, `backend/tests/test_detect.py` 통과 기록 있음.
- `docs/execution/2026-05-14_next_step_parallel.md` / 2026-05-14: v2 `best.pt`로 FastAPI `TestClient` `/detect/health ready`, `/detect` HTTP 200, detection 4개, 첫 detection `source: server` 기록 있음.
- `plans/daily/2026-05-15.md`, `plans/.work/2026-05-15/catchup/backend-postgis-api.md` / 2026-05-15: 남은 항목은 실서버 `/detect` smoke, 격리 smoke 전후 상태 기록, cleanup 확인, PWA server detector 실제 저장 확인, placeholder 문서 충돌 정리.
- `daylog/2026-05-15.md` / 2026-05-15: PWA server detector wiring은 확인됐지만, 브라우저/실폰 `/detect` 호출과 `source: "server"` 신고 저장은 미확인으로 기록.
- `backend/app/detector.py`, `backend/tests/test_detect.py` / 2026-05-15 탐색 기준: `.pt` lazy-load, class mapping, normalized bbox, `source="server"` 응답 경로와 unavailable 회귀 테스트 존재.
- `backend/tests/test_reports.py` / 2026-05-15 탐색 기준: 신고 생성/조회/필터/상태 변경/radius/duplicate/업로드 오류 테스트가 있으나 DB row와 `backend/uploads/test` 파일을 만들 수 있음.
- `plans/daily/2026-05-16.md` / 2026-05-15 탐색 기준: 파일 없음.

## 내일 목표 후보
- 1순위: 2026-05-15 backend smoke 완료 근거 공백을 닫는다. 격리 DB, 테스트 `UPLOAD_DIR`, 생성 row/file cleanup까지 기록한다.
- 2순위: 실제 `uvicorn` 서버 기준으로 v2 `best.pt` `/detect` adapter smoke를 확인한다.
- 3순위: PWA server mode와 연동해 `/detect` 결과가 `source: "server"` 신고로 저장되는지 프론트 lane과 맞춰 확인한다.
- 4순위: placeholder 기준 문서와 adapter 구현 이후 최신 상태가 충돌하는 문서 목록을 정리한다.
- 5순위: 외부 공개 전 보안/운영 backlog를 PR 후보로 분리한다. 인증, `/uploads` 공개, CORS, persistent storage, rate limit, EXIF/재인코딩, DB constraint가 대상이다.
- 6순위: DB 마이그레이션 보강은 smoke 이후 별도 PR로 판단한다.

## 상세 체크리스트 초안
- [ ] smoke 환경 격리 → 검증: disposable PostGIS 또는 로컬 개발 DB 여부, 테스트 전용 `UPLOAD_DIR`, 실행 전 row/file 상태 기록
- [ ] PostGIS/Alembic 재현 확인 → 검증: `docker compose up -d db`, Alembic `upgrade head/current`, PostGIS extension/table/index/SRID 확인
- [ ] read-only API smoke → 검증: `GET /health`, `GET /detect/health`, `GET /reports?limit=1` 응답 기록
- [ ] 신고 API 실서버 smoke → 검증: `POST /reports`, `GET /reports/{id}`, `GET /reports` 필터, `PATCH /reports/{id}/status` 기대 응답 확인
- [ ] PostGIS radius/duplicate 확인 → 검증: 반경 내 조회 포함, partial radius HTTP 400, 같은 class/25m/±10분 duplicate ID 반환 확인
- [ ] 업로드 negative smoke → 검증: `unsupported_image_type`, `image_extension_mismatch`, `image_content_mismatch`, `empty_image`, `upload_too_large` 중 최소 4개 HTTP status와 `detail.code` 기록
- [ ] 생성 데이터 정리 → 검증: 생성 ID 기준 DB 삭제 또는 disposable DB reset, 업로드 파일 제거, 정리 후 목록/파일 상태 확인
- [ ] `/detect` 실서버 adapter smoke → 검증: `MODEL_ARTIFACT_PATH`, `MODEL_VERSION` 설정 후 `/detect/health ready`, 샘플 `POST /detect` HTTP 200, `source: "server"` 또는 정상 빈 `detections: []` 기록
- [ ] `/detect` unavailable 회귀 확인 → 검증: 모델 경로 미설정 또는 부재 시 `/detect/health unavailable`, `POST /detect` 503 `model_unavailable` 유지
- [ ] 모델 handoff 값 대조 → 검증: `CLASS_ORDER`, `MODEL_CONFIDENCE_THRESHOLD`, `MODEL_IOU_THRESHOLD`, `MODEL_IMAGE_SIZE`, `best.pt` hash/version이 모델 문서와 일치하는지 기록
- [ ] PWA server report 저장 확인 지원 → 검증: 프론트 lane과 함께 `/detect` 호출, bbox 표시, `/reports` 저장 payload의 `source: "server"` 확인
- [ ] 문서 충돌 목록 작성 → 검증: `placeholder`, `model_adapter_not_implemented`, `fake 중심` 서술이 남은 문서를 최신 adapter 상태 기준으로 분류
- [ ] 배포/보안 backlog 분리 → 검증: 인증/권한, public `/uploads`, CORS origin, persistent volume/S3, request size limit, EXIF 제거, rate limit 항목을 다음 PR 후보로 정리
- [ ] DB migration 후보 결정 → 검증: status/source/class check constraint, `updated_at` DB trigger, 상태 변경 이력 테이블을 당장 범위와 후속 범위로 분리

## 리스크/확인 필요
- `docs/execution/2026-05-15*.md`가 없어 2026-05-15 실제 완료 근거가 제한적이다.
- 일부 5월 12~13일 문서는 `/detect` placeholder 기준이다. 최신 판단은 2026-05-14 adapter 구현 로그와 현재 `backend/app/detector.py`를 우선해야 한다.
- PWA server detector wiring은 미추적 파일/프론트 변경으로 존재하지만, 실제 브라우저/실폰에서 `source: "server"` 신고 저장은 아직 완료로 쓰면 안 된다.
- v2 모델은 실질적으로 `damaged_tactile_block` 중심 근거다. 4개 위험 클래스 전체 성능으로 주장하면 안 된다.
- `backend/tests/test_reports.py`와 신고 smoke는 DB row와 업로드 파일을 만든다. 공유 DB에서 실행 금지.
- `/reports`, `/uploads`, status patch는 인증 없이 열려 있다. 외부 공개 또는 장시간 네트워크 노출 전 제한이 필요하다.
- 업로드 검증은 MIME/확장자/크기/헤더 수준이다. 전체 decode 검증, EXIF 제거, 악성 파일 스캔은 아직 없다.
- `/detect` 내부 이미지 decode 실패는 현재 503 `model_unavailable`로 묶일 수 있어 프론트 오류 표시가 혼동될 수 있다.
- DB enum/check constraint가 없어 핵심 검증은 Pydantic에 의존한다.

## 병렬 에이전트 활용 메모
- 하위/병렬 에이전트는 사용하지 않음.
- 이번 작업은 backend lane의 문서와 코드 대조 범위가 명확했고, 최종 계획 파일을 수정하지 않는 lane note 작성이라 단일 에이전트가 확인했다.