# Hanium Dreamup / WalkSafe Assist - Backend/PostGIS/API lane note (2026-05-15)

## 최근 진행 근거
- `docs/execution/2026-05-14_backend.md` / 2026-05-14: PostGIS 컨테이너 healthy, Alembic `202605120001 (head)`, `backend/tests` 통과, `/health`, `/detect/health`, `/reports?limit=5` smoke 확인 기록 있음.
- `docs/execution/2026-05-14_detect_contract_implementation.md` / 2026-05-14: `/detect` 모델 설정 계약 고정. `MODEL_ARTIFACT_PATH`, `MODEL_VERSION`, class order, confidence/IOU/image size 기준 추가.
- `docs/execution/2026-05-14_detect_adapter_implementation.md` / 2026-05-14: Ultralytics YOLO `.pt` 1차 adapter 구현 기록 있음. 변경 범위는 `backend/app/detector.py`, `backend/tests/test_detect.py`, `backend/requirements.txt`.
- `backend/app/detector.py`, `backend/tests/test_detect.py` / 현재 코드 기준: `.pt` artifact가 있으면 lazy-load 후 `/detect/health ready`, `/detect` 성공 응답 `source="server"` 경로가 존재함.
- `docs/backend_3day_execution_plan.md` / 2026-05-15 항목: 신고 API, PostGIS radius query, duplicate check, negative upload smoke, 생성 row/upload cleanup이 내일 목표로 잡혀 있음.
- `docs/report_operations.md` / 2026-05-12: 중복 신고 기준은 같은 `class_name`, 반경 25m, `captured_at` 전후 10분이며 advisory로만 처리.
- `docs/api_reference.md`, `docs/backend_error_contract.md` / 2026-05-12: multipart 신고/탐지 계약과 업로드 오류 코드 기준이 정리되어 있음.
- `plans/daily/2026-05-15.md`는 현재 없음. `daylog/`는 비어 있고 최근 실행 로그는 `docs/execution/`에 있음.

## 내일 목표 후보
- 1순위: 격리된 로컬 DB와 테스트 업로드 폴더에서 신고 API/PostGIS smoke를 실행하고, 생성 row와 업로드 파일 정리 절차를 확정한다.
- 2순위: 실제 `best.pt` 경로를 `MODEL_ARTIFACT_PATH`로 넣은 `/detect` adapter smoke를 백엔드 단독으로 확인한다.
- 3순위: `/detect` 문서 상태를 정리한다. 일부 문서는 아직 placeholder라고 쓰고 있어 2026-05-14 adapter 구현 기록과 충돌한다.
- 4순위: 업로드 운영 정책을 발표/로컬 시연 기준과 외부 공개 기준으로 나눠 결정한다.
- 5순위: DB check constraint, 상태 변경 이력, 인증/권한, S3/persistent storage는 다음 PR 후보로 분리한다.

## 상세 체크리스트 초안
- [ ] smoke 환경 고정 → 검증: disposable PostGIS, 테스트 전용 `UPLOAD_DIR`, Alembic current, 실행 전 row/file 상태 기록
- [ ] read-only smoke 먼저 실행 → 검증: `GET /health`, `GET /detect/health`, `GET /reports?limit=1` 응답 확인
- [ ] 신고 생성/조회/목록/상태 변경 smoke → 검증: `POST /reports`, `GET /reports/{id}`, `GET /reports` 필터, `PATCH /reports/{id}/status` 기대 응답 확인
- [ ] PostGIS radius query 확인 → 검증: 기준 좌표 반경 내 신고가 `GET /reports?lat=&lng=&radius_m=`에 포함되는지 확인
- [ ] duplicate check 확인 → 검증: 같은 class, 25m 이내, ±10분 조건에서 두 번째 신고와 `GET /reports/duplicate-check`가 첫 신고 ID를 반환
- [ ] negative upload/error smoke 최소 3개 확인 → 검증: `unsupported_image_type`, `image_extension_mismatch`, `image_content_mismatch` 또는 `upload_too_large` 코드 확인
- [ ] 생성 row와 업로드 파일 정리 → 검증: 생성 ID 기준 DB 삭제 또는 disposable DB reset, 업로드 파일 제거, 정리 후 목록/파일 확인
- [ ] 실제 `.pt` adapter smoke → 검증: `MODEL_ARTIFACT_PATH` 설정 시 `/detect/health ready`, `POST /detect` 200 또는 빈 `detections: []`, 미설정 시 기존 `model_unavailable` 유지
- [ ] 문서 불일치 정리 범위 확정 → 검증: 업데이트 대상 문서 목록과 실제 변경 필요 여부를 smoke 결과에 함께 기록

## 리스크/확인 필요
- `/detect` 상태 문서가 서로 다름. 최신 로그/코드는 `.pt` adapter 구현 완료지만, 5월 13일 상태 문서와 일부 계획 문서는 placeholder 기준이다.
- `backend/tests/test_reports.py`는 DB row와 `backend/uploads/test` 파일을 만든다. 공유 DB에서 실행하면 안 된다.
- 실제 모델 artifact는 Git 추적 대상이 아니므로 내일 smoke에는 로컬 경로, version 문자열, 가능하면 SHA256 확인이 필요하다.
- 현재 모델은 `damaged_tactile_block` 중심이다. class 1~3 탐지를 서비스 성능으로 주장하면 안 된다.
- `/reports`, `/uploads`, status patch는 인증 없이 열려 있다. 외부 공개 시 네트워크 제한 또는 인증 backlog가 필요하다.
- 업로드 검증은 MIME/확장자/크기/헤더 수준이다. full decode 검증, EXIF 제거, 악성 파일 스캔은 아직 없다.
- DB enum/check constraint가 없어 주요 검증은 Pydantic에 의존한다. 마이그레이션 보강은 smoke 이후 별도 PR로 판단한다.