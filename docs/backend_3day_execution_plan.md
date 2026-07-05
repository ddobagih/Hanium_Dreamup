# Backend 3-Day Execution Plan

> **문서 상태(2026-06-02): superseded.** 2026-05-14~16 backend 계획 기록이다. 현재 backend 기준은 `docs/walksafe-v2/backend_api_contract.md`와 `docs/backend_environment.md`다.


작성 기준일: 2026-05-13 KST
실행 기간: 2026-05-14(목) ~ 2026-05-16(토)
담당 범위: FastAPI/PostGIS 백엔드, 신고 API, 업로드 정책, `/detect` 서버 추론 연결 준비

## 기준 자료

- `2026 ICT 한이음 드림업 프로젝트 개요서.pdf`
- `2026년 한이음 드림업 프로젝트 수행계획서.pdf`
- `PROJECT_PLAN.md`
- `docs/current_status.md`
- `docs/pwa_backend_status.md`
- `docs/api_reference.md`
- `docs/inference_contract.md`
- `docs/model_integration_plan.md`
- `docs/model_placeholder_systems.md`
- `docs/backend_environment.md`
- `docs/backend_db_reset.md`
- `docs/backend_error_contract.md`
- `docs/report_operations.md`
- `backend/`

## 3일 목표

프로젝트 계획서의 백엔드 목표는 `FastAPI + PostgreSQL/PostGIS`로 탐지 결과, 위치, 이미지 증거, 신고 상태를 저장하고, 향후 지자체 민원/공간 분석/MLOps 데이터 파이프라인으로 확장 가능한 신고 기반을 만드는 것이다. 이번 3일은 실제 모델 연결 전 단계에서 다음 상태까지 끌어올리는 것을 목표로 한다.

- [ ] 로컬 PostGIS와 Alembic 마이그레이션을 누구나 같은 순서로 재현할 수 있다.
- [ ] 현재 신고 API 계약과 오류 계약을 프론트엔드/모델 담당자에게 고정된 기준으로 공유한다.
- [ ] DB/업로드 폴더에 데이터를 만드는 신고 API 스모크는 격리된 개발 DB에서만 실행하는 절차로 정리한다.
- [ ] 이미지 업로드 정책의 현재 보장 범위와 보안 보강 필요 항목을 분리한다.
- [ ] `/detect` placeholder를 실제 모델 adapter로 교체할 구현 순서, 입력/출력 계약, 테스트 기준을 확정한다.
- [ ] 배포 전 필수 환경 변수, CORS, 업로드 저장소, DB migration, 보안 체크를 한 번에 확인할 수 있다.

## 현재 구현 상태

### 백엔드 런타임

- FastAPI 앱 진입점: `backend/app/main.py`
- 설정: `backend/app/config.py`
- DB 세션: `backend/app/database.py`
- ORM 모델: `backend/app/models.py`
- Pydantic 스키마: `backend/app/schemas.py`
- 업로드 검증/저장: `backend/app/uploads.py`
- 서버 추론 placeholder: `backend/app/detector.py`
- Alembic revision: `backend/alembic/versions/202605120001_create_reports.py`
- 로컬 DB: `docker-compose.yml`의 `postgis/postgis:16-3.5`

### 구현된 엔드포인트

| Method | Path | 현재 상태 | 비고 |
| --- | --- | --- | --- |
| `GET` | `/health` | 구현 | 프로세스 헬스체크 |
| `GET` | `/detect/health` | placeholder | 모델 미연결 상태를 명시적으로 반환 |
| `POST` | `/detect` | placeholder | 이미지 검증 후 `503 model_unavailable` |
| `POST` | `/reports` | 구현 | metadata 검증, 이미지 저장, PostGIS 위치 저장 |
| `GET` | `/reports` | 구현 | 필터, 최신순, 반경 검색 |
| `GET` | `/reports/duplicate-check` | 구현 | 같은 클래스, 시간창, 반경 기준 후보 |
| `GET` | `/reports/{report_id}` | 구현 | 단건 조회 |
| `PATCH` | `/reports/{report_id}/status` | 구현 | `new/reviewed/resolved` 상태 변경 |
| `GET` | `/uploads/{filename}` | 구현 | FastAPI StaticFiles로 로컬 업로드 제공 |

### 주요 계산 규칙

- 중복 후보 기본값: 같은 `class_name`, 반경 `25m`, `captured_at` 전후 `10분`
- 위치 품질:
  - `missing`: GPS 없음
  - `low`: `accuracy_m` 없음 또는 50m 초과
  - `medium`: 15m 초과 50m 이하
  - `high`: 15m 이하
- 검토 플래그:
  - `fake_source`
  - `low_confidence`: confidence `< 0.7`
  - `missing_location`
  - `low_location_accuracy`
  - `missing_heading`

### 현재 한계

- `/detect`는 실제 추론을 수행하지 않는다.
- 모델 파일 경로가 있어도 `model_adapter_not_implemented` 상태다.
- 신고 이미지는 외부 스토리지 없이 로컬 `UPLOAD_DIR`에 저장된다.
- 인증/권한, rate limit, 감사 로그, 상태 변경 이력 테이블은 없다.
- 이미지 파일은 MIME/확장자/크기/헤더만 확인하며, 전체 이미지 디코딩 검증, EXIF 제거, 악성 파일 스캔은 없다.
- DB 차원의 enum/check constraint는 아직 없고, 주요 검증은 Pydantic 스키마가 담당한다.
- `updated_at`은 SQLAlchemy app 경로의 update에서는 갱신되지만, DB 직접 update에 대한 trigger는 없다.

## API 계약 고정안

### 탐지 클래스

`backend/app/schemas.py`와 `docs/inference_contract.md`의 순서를 유지한다. 모델 담당자는 이 순서가 바뀌면 백엔드/프론트 계약 변경으로 취급해야 한다.

| class_id | class_name |
| ---: | --- |
| 0 | `damaged_tactile_block` |
| 1 | `parked_kickboard_bicycle` |
| 2 | `construction_obstacle` |
| 3 | `pothole` |

### `ReportMetadata`

`POST /reports`의 `metadata` form part와 `/detect` 성공 시 `detections[]` 항목은 같은 구조를 사용한다.

필수/규칙:

- `class_id`: `0..3`
- `class_name`: `class_id`와 반드시 일치
- `confidence`: `0..1`
- `bbox.x`, `bbox.y`: `0..1`
- `bbox.width`, `bbox.height`: `0` 초과 `1` 이하
- `captured_at`: ISO 8601 datetime
- `source`: `fake`, `onnx`, `server`
- `gps`: 없으면 `null`
- `heading`: 없으면 `null`, 있으면 `0 <= heading < 360`

### `POST /reports`

요청:

| part | 내용 |
| --- | --- |
| `metadata` | `ReportMetadata` JSON 문자열 |
| `image` | 실제 JPEG/PNG/WebP 이미지 |

성공 응답:

- HTTP `201`
- `ReportResponse`
- `image_path`는 `/uploads/{uuid}.{jpg|png|webp}`
- `duplicate_report_ids`는 저장 직전에 검색한 후보 ID 목록

### `GET /reports`

쿼리:

- `limit`: 기본 `25`, 범위 `1..100`
- `status`: `new`, `reviewed`, `resolved`
- `class_name`: 4개 클래스 중 하나
- `source`: `fake`, `onnx`, `server`
- `created_from`, `created_to`: 생성일 필터
- `lat`, `lng`, `radius_m`: 반경 검색 시 셋을 함께 전달

주의:

- `lat/lng/radius_m` 중 일부만 전달하면 HTTP `400`
- 반경 검색은 `ST_DWithin(location::geography, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius_m)` 기준

### `/detect` placeholder

현재 정상 상태:

- `GET /detect/health`는 `model_status: "unavailable"` 반환
- `POST /detect`는 이미지 업로드 검증을 통과한 뒤 HTTP `503`과 `detail.code: "model_unavailable"` 반환
- fake detector 결과를 서버 모델 결과처럼 반환하지 않는다.

현재 reason:

| reason | 의미 |
| --- | --- |
| `model_not_configured` | `MODEL_ARTIFACT_PATH`가 비어 있거나 파일 없음 |
| `model_adapter_not_implemented` | 모델 파일은 있으나 adapter 미구현 |

## PostGIS/Alembic 재현성 체크리스트

공유 DB나 운영 DB가 아닌 로컬 개발 DB에서만 실행한다.

### 기본 재현 순서

```bash
docker compose up -d db
source .venv/bin/activate
python -m pip install -r backend/requirements.txt
cp backend/.env.example backend/.env
python -m alembic -c backend/alembic.ini upgrade head
python -m alembic -c backend/alembic.ini current
python -m uvicorn backend.app.main:app --reload --port 8000
```

### DB 상태 확인

- [ ] `docker compose ps`에서 `db`가 healthy인지 확인한다.
- [ ] `python -m alembic -c backend/alembic.ini current`가 `202605120001`을 가리킨다.
- [ ] PostGIS extension이 존재한다.
- [ ] `reports` 테이블이 존재한다.
- [ ] `ix_reports_status`, `ix_reports_class_name`, `ix_reports_created_at`, `ix_reports_location` 인덱스가 존재한다.
- [ ] `reports.location`의 SRID가 4326인지 확인한다.
- [ ] `GET /health`가 `{"status":"ok"}`를 반환한다.

확인 SQL 예:

```sql
SELECT extname, extversion FROM pg_extension WHERE extname = 'postgis';
SELECT indexname FROM pg_indexes WHERE tablename = 'reports' ORDER BY indexname;
SELECT Find_SRID('public', 'reports', 'location');
```

### 마이그레이션 기준

- [ ] 신규 DB는 반드시 Alembic `upgrade head`로 구성한다.
- [ ] `Base.metadata.create_all()` 같은 우회 경로를 추가하지 않는다.
- [ ] 수동 SQL로 스키마를 고친 경우 반드시 Alembic revision으로 되돌려 기록한다.
- [ ] downgrade는 현재 `reports` 테이블만 drop하며 PostGIS extension은 제거하지 않는다는 점을 문서화한다.
- [ ] 다음 schema 변경 시 status/source/class enum 또는 check constraint를 DB 차원에 둘지 결정한다.

## 신고 API 스모크 체크리스트

현재 문서 작성 중에는 DB나 업로드 폴더에 데이터를 만드는 테스트를 실행하지 않았다. 아래 절차는 2026-05-14 이후 백엔드 담당자가 격리된 로컬 개발 DB에서 실행한다.

### 실행 전 조건

- [ ] 공유 DB, 운영 DB, 팀원이 쓰는 DB가 아닌지 확인한다.
- [ ] `UPLOAD_DIR`이 로컬 임시/개발 폴더를 가리키는지 확인한다.
- [ ] 테스트에 사용할 실제 JPEG/PNG/WebP 파일을 준비한다. 파일 확장자, MIME, 실제 바이트 헤더가 일치해야 한다.
- [ ] smoke 실행 후 생성된 DB row와 업로드 파일을 삭제할 방법을 정한다.

### Read-only smoke

```bash
curl http://127.0.0.1:8000/health
curl http://127.0.0.1:8000/detect/health
curl "http://127.0.0.1:8000/reports?limit=1"
```

확인:

- [ ] `/health`는 `200`
- [ ] `/detect/health`는 모델 연결 전 `unavailable`
- [ ] `/reports?limit=1`은 DB 연결 실패 없이 `200`과 배열 응답

### Create/read/update smoke

```bash
metadata='{
  "class_id": 0,
  "class_name": "damaged_tactile_block",
  "confidence": 0.91,
  "bbox": {"x": 0.2, "y": 0.35, "width": 0.4, "height": 0.22},
  "captured_at": "2026-05-14T09:00:00Z",
  "source": "fake",
  "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
  "heading": 181
}'

curl -X POST http://127.0.0.1:8000/reports \
  -F "metadata=$metadata" \
  -F "image=@/absolute/path/to/sample.jpg;type=image/jpeg"
```

확인:

- [ ] HTTP `201`
- [ ] `status`가 `new`
- [ ] `image_path`가 `/uploads/{uuid}.jpg`
- [ ] `location_quality`가 `high`
- [ ] `review_flags`에 `fake_source`가 포함된다.
- [ ] `duplicate_report_ids`가 배열이다.

후속 확인:

```bash
curl http://127.0.0.1:8000/reports/{report_id}
curl "http://127.0.0.1:8000/reports?lat=37.5665&lng=126.978&radius_m=100"
curl -X PATCH http://127.0.0.1:8000/reports/{report_id}/status \
  -H "Content-Type: application/json" \
  -d '{"status":"reviewed"}'
```

- [ ] 단건 조회가 같은 `id`를 반환한다.
- [ ] 반경 검색 결과에 생성한 신고가 포함된다.
- [ ] 상태 변경 후 `status`가 `reviewed`로 바뀐다.

### Duplicate smoke

- [ ] 같은 `class_name`, 25m 이내, `captured_at` 전후 10분 이내의 두 번째 신고를 생성한다.
- [ ] 두 번째 `POST /reports` 응답의 `duplicate_report_ids`에 첫 번째 신고 ID가 포함된다.
- [ ] `GET /reports/duplicate-check`도 두 신고 ID를 반환한다.

### Negative smoke

- [ ] `metadata.class_id`와 `class_name` 불일치 시 HTTP `422`
- [ ] 미지원 MIME(`text/plain`) 시 HTTP `400`, `detail.code: "unsupported_image_type"`
- [ ] 확장자/MIME 불일치 시 HTTP `400`, `detail.code: "image_extension_mismatch"`
- [ ] 이미지 헤더 불일치 시 HTTP `400`, `detail.code: "image_content_mismatch"`
- [ ] 크기 초과 시 HTTP `413`, `detail.code: "upload_too_large"`
- [ ] `lat/lng/radius_m` 일부만 전달 시 HTTP `400`

## 업로드 정책

### 현재 정책

| 항목 | 값 |
| --- | --- |
| 허용 MIME | `image/jpeg`, `image/png`, `image/webp` |
| 기본 최대 크기 | `8388608` bytes |
| 설정 변수 | `ALLOWED_IMAGE_CONTENT_TYPES`, `MAX_UPLOAD_BYTES` |
| 저장 위치 | `UPLOAD_DIR` |
| 저장 파일명 | `{report_id}.{jpg|png|webp}` |
| 원본 파일명 사용 | 저장에는 사용하지 않음 |

검증 순서:

1. multipart content type이 허용 목록에 있는지 확인
2. 파일명 확장자가 있으면 MIME과 맞는지 확인
3. `MAX_UPLOAD_BYTES + 1`까지만 읽고 크기 초과를 차단
4. JPEG/PNG/WebP 최소 헤더 확인
5. 신고 생성 시 UUID 기반 파일명으로 저장

### 3일 내 결정할 업로드 운영 기준

- [ ] 발표/로컬 시연은 로컬 `backend/uploads`를 유지한다.
- [ ] 외부 배포 시 `UPLOAD_DIR`을 컨테이너 내부 임시 경로로 두지 않고 persistent volume 또는 S3로 분리한다.
- [ ] 업로드 이미지의 공개 URL 정책을 정한다. 현재 `/uploads`는 인증 없이 정적 제공된다.
- [ ] 이미지 EXIF에 위치/기기 정보가 남을 수 있음을 보안 리스크로 기록한다.
- [ ] 운영 전 EXIF 제거 또는 이미지 재인코딩을 도입할지 결정한다.
- [ ] 악성 이미지 정밀 검사를 이번 범위에서 제외할 경우, 제한 사항으로 명시한다.
- [ ] `ALLOWED_IMAGE_CONTENT_TYPES`에 HEIC/GIF 등을 추가하지 않는다. 코드의 suffix/extension/signature 지원 없이는 거부된다.

## `/detect` 실제 모델 adapter 계획

### 유지할 계약

- 요청은 계속 `multipart/form-data`
- part 이름은 `context`, `image`
- `context`는 `DetectContext` JSON 문자열
- `image`는 신고 업로드와 같은 검증 정책 적용
- 성공 응답은 `DetectResponse`
- 각 detection은 `ReportMetadata` 검증을 통과해야 한다.
- 서버 추론 결과의 `source`는 `server`
- `bbox`는 원본 입력 이미지 기준 정규화 좌표
- `confidence`는 `0..1`
- 모델 class order는 `CLASS_NAMES`와 일치

### Adapter 구현 순서

1. 모델 담당자에게 산출물 경로, 포맷, class order, 입력 크기, 전처리, confidence threshold 후보를 받는다.
2. `MODEL_ARTIFACT_PATH`를 절대 경로로 지정하는 운영 방식을 확정한다.
3. 백엔드 의존성을 결정한다.
   - ONNX면 `onnxruntime` 계열 검토
   - PyTorch/Ultralytics 직접 로딩이면 서버 메모리와 cold start 비용을 별도 기록
4. `backend/app/detector.py`에 model load 경계를 만든다.
5. 이미지 bytes를 안전하게 decode하고 RGB/resize/normalize 전처리를 수행한다.
6. 모델 출력 박스를 원본 이미지 기준 normalized bbox로 변환한다.
7. NMS와 confidence threshold를 적용한다.
8. class id를 `CLASS_NAMES`로 매핑한다.
9. `captured_at`은 `context.captured_at`이 있으면 사용하고, 없으면 서버 UTC now를 사용한다.
10. `gps`, `heading`은 `context` 값을 그대로 전달한다.
11. `ReportMetadata`로 각 detection을 검증한다.
12. `DetectResponse(model_status="ready", model_version=..., detections=[...])`를 반환한다.
13. `/detect/health`가 로드 성공 시 `model_status: "ready"`와 `model_version`을 반환하게 한다.
14. 모델 로드 실패, 추론 실패, 빈 결과의 응답 정책을 프론트엔드와 공유한다.

### Adapter 테스트 기준

- [ ] 모델 파일이 없으면 `/detect/health`는 `model_not_configured`
- [ ] 모델 파일이 있지만 로드 실패하면 명시적 reason을 반환
- [ ] 모델 로드 성공 시 `/detect/health`는 `ready`
- [ ] `/detect` 성공 응답의 모든 detection이 `ReportMetadata` 검증을 통과
- [ ] `source`는 항상 `server`
- [ ] bbox는 `0..1` 범위이고 width/height는 0 초과
- [ ] class id/name mismatch가 발생하지 않음
- [ ] 빈 탐지 결과는 `detections: []`로 반환할지, 최소 threshold를 낮출지 결정
- [ ] 현재 v2 모델이 class 0 중심이라는 사실을 보고/시연 자료에 명확히 표시

## 2026-05-14(목) 체크리스트

목표: 현재 백엔드 상태를 재현하고 API 계약을 freeze한다.

- [ ] `backend/.env.example`과 `docs/backend_environment.md`가 실제 `Settings`와 일치하는지 확인한다.
- [ ] PostGIS Docker image, DB 이름, 사용자, 포트, volume 이름을 문서와 대조한다.
- [ ] Alembic `upgrade head` 재현 절차를 로컬 개발 DB 기준으로 확인한다.
- [ ] `reports` 테이블 컬럼과 `Report` ORM 컬럼이 어긋나지 않는지 확인한다.
- [ ] `docs/api_reference.md`와 `backend/app/schemas.py`의 필드/enum이 일치하는지 확인한다.
- [ ] 프론트 담당자에게 `ReportMetadata`, `ReportResponse`, upload error code, `/detect` placeholder 응답을 공유한다.
- [ ] 모델 담당자에게 class order와 `/detect` 성공 응답 계약을 공유한다.
- [ ] STT 담당자와 백엔드 포트 충돌 여부를 확인한다. 현재 백엔드는 `8000`, voice prototype은 문서상 `9001`이다.
- [ ] 5월 14일 종료 전에 변경 필요한 API 계약이 있으면 문서 먼저 갱신하고 코드 변경은 별도 합의한다.

산출물:

- [ ] 재현 명령 목록
- [ ] API 계약 변경 여부 판단
- [ ] 프론트/모델/STT 담당자에게 공유한 백엔드 계약 요약

## 2026-05-15(금) 체크리스트

목표: 신고 API와 PostGIS 동작을 격리 환경에서 smoke하고 운영 기준을 정한다.

- [ ] DB/업로드 데이터를 생성하는 smoke는 로컬 개발 DB에서만 실행한다.
- [ ] read-only smoke(`/health`, `/detect/health`, `/reports?limit=1`)를 먼저 확인한다.
- [ ] `POST /reports` create smoke를 실행한다.
- [ ] `GET /reports/{id}` 단건 조회를 확인한다.
- [ ] `GET /reports` 필터를 확인한다.
- [ ] `PATCH /reports/{id}/status` 상태 변경을 확인한다.
- [ ] radius query가 PostGIS `ST_DWithin`으로 동작하는지 확인한다.
- [ ] duplicate check의 시간창/반경 기준을 확인한다.
- [ ] negative upload/error smoke를 최소 3개 이상 확인한다.
- [ ] smoke 후 생성 row와 업로드 파일을 정리한다.
- [ ] `backend/tests/test_reports.py`는 DB와 업로드 파일을 생성하므로 disposable DB에서만 실행하도록 팀에 알린다.
- [ ] 필요한 경우에만 `python -m pytest --collect-only backend/tests`로 테스트 목록만 확인한다.

산출물:

- [ ] smoke 결과 요약
- [ ] 발견된 계약 불일치 목록
- [ ] 업로드 운영 정책 결정 사항
- [ ] DB 정리 여부 확인

## 2026-05-16(토) 체크리스트

목표: 실제 모델 adapter와 배포/보안 준비 항목을 코드 작업 전 수준까지 확정한다.

- [ ] 모델 담당자에게 받을 artifact 정보를 정리한다.
  - 파일 포맷
  - class order
  - 입력 이미지 크기
  - 전처리 방식
  - confidence threshold
  - NMS 방식
  - model version 문자열
- [ ] `/detect` adapter 구현 PR의 범위를 `backend/app/detector.py`, 필요한 경우 `backend/requirements.txt`, 테스트 파일로 제한하는 계획을 세운다.
- [ ] adapter 테스트 fixture 정책을 정한다. 실제 대형 모델 파일은 Git에 올리지 않는다.
- [ ] 모델 없는 환경에서도 기존 placeholder 테스트가 통과해야 한다는 기준을 세운다.
- [ ] 배포 환경 변수 체크리스트를 확정한다.
- [ ] CORS origin을 실제 프론트 배포 주소로 제한하는 기준을 정한다.
- [ ] 업로드 저장소를 로컬 volume으로 둘지 S3로 넘길지 발표/운영 단계를 나눠 결정한다.
- [ ] public `/uploads` 노출 정책을 검토한다.
- [ ] 인증/권한이 없는 상태로 외부 공개하지 않는다는 보안 기준을 공유한다.
- [ ] 5월 16일 종료 시점에 다음 PR 후보를 작은 단위로 쪼갠다.

산출물:

- [ ] 모델 adapter 구현 설계 메모
- [ ] 배포 전 환경 변수 체크리스트
- [ ] 보안 보강 backlog
- [ ] 다음 주 백엔드 PR 후보 목록

## 테스트 기준

### 지금 바로 안전한 확인

앱 코드를 변경하지 않고 문서/계약만 확인할 때:

```bash
python -m pytest --collect-only backend/tests
```

주의:

- collect-only도 테스트 모듈 import 과정에서 앱 설정을 읽을 수 있다.
- 실제 테스트 실행은 DB와 업로드 폴더에 데이터를 만든다.

### DB/업로드 데이터를 만드는 테스트

`backend/tests/test_reports.py`는 다음을 수행한다.

- Alembic `upgrade head`
- `POST /reports`
- 업로드 파일 저장
- PostGIS 반경 검색
- duplicate check
- 상태 변경

따라서 실행 조건:

- [ ] disposable local PostGIS
- [ ] 테스트 전용 `UPLOAD_DIR`
- [ ] 실행 후 row와 파일 정리 가능
- [ ] 공유 DB 금지

`backend/tests/test_detect.py`는 DB 생성은 하지 않지만 FastAPI 앱과 업로드 검증을 사용한다. 모델 없는 상태에서 `/detect`가 `503 model_unavailable`을 반환하는 계약을 보호한다.

## 배포 체크리스트

- [ ] `DATABASE_URL`은 기본 개발 계정이 아닌 배포 전용 계정으로 설정한다.
- [ ] `CORS_ORIGINS`는 실제 프론트엔드 origin만 허용한다.
- [ ] `UPLOAD_DIR`은 컨테이너 재시작 시 사라지지 않는 위치로 둔다.
- [ ] `MAX_UPLOAD_BYTES`는 운영 비용과 모바일 네트워크를 고려해 유지 또는 축소한다.
- [ ] `ALLOWED_IMAGE_CONTENT_TYPES`는 현재 코드가 지원하는 3개 MIME만 둔다.
- [ ] `MODEL_ARTIFACT_PATH`는 배포 환경의 실제 읽기 가능한 경로로 둔다.
- [ ] release 절차에 `python -m alembic -c backend/alembic.ini upgrade head`를 포함한다.
- [ ] `/health`와 `/detect/health`를 배포 후 확인한다.
- [ ] reverse proxy 또는 플랫폼에서 HTTPS를 강제한다.
- [ ] DB backup/restore 절차를 운영 문서에 추가한다.
- [ ] 업로드 파일 backup 또는 S3 이관 계획을 정한다.

## 보안 체크리스트

- [ ] 운영/외부 시연 DB에는 기본 비밀번호 `walksafe/walksafe`를 사용하지 않는다.
- [ ] 외부 공개 전 인증/권한 없이 `/reports`, `/uploads`, status patch가 열려 있는 문제를 해결하거나 네트워크를 제한한다.
- [ ] `/uploads` 공개 이미지에 민감 정보가 포함될 수 있음을 명시한다.
- [ ] EXIF 제거 또는 서버 재인코딩 도입 여부를 결정한다.
- [ ] rate limit 또는 reverse proxy request size limit을 둔다.
- [ ] 업로드 파일 확장자/MIME/header 검증 외에 이미지 decoder 검증을 추가할지 결정한다.
- [ ] CORS wildcard를 사용하지 않는다.
- [ ] 로그에 원본 이미지 bytes, 민감 위치, DB password가 남지 않게 한다.
- [ ] 운영 DB에 직접 접속 가능한 계정을 최소화한다.
- [ ] 상태 변경 API에는 관리자 권한이 필요하다는 backlog를 남긴다.

## 다른 담당자와 맞출 인터페이스

### 프론트엔드

- [ ] `POST /reports`는 계속 multipart `metadata`, `image`
- [ ] `GET /reports/duplicate-check` 결과는 advisory로만 표시
- [ ] `duplicate_report_ids`가 있어도 서버는 신고 생성을 막지 않음
- [ ] `model_unavailable`이면 fake 또는 클라이언트 detector 모드를 유지
- [ ] upload error는 `detail.code` 우선 처리

### 모델

- [ ] class order는 백엔드 `CLASS_NAMES`와 동일
- [ ] `/detect` 성공 시 `source: "server"`
- [ ] bbox는 원본 프레임 기준 normalized
- [ ] 모델 산출물은 Git에 올리지 않고 `MODEL_ARTIFACT_PATH`로 주입
- [ ] 현재 점자블록 v2가 class 0 중심이라는 제한을 성능 자료에 명시

### STT/TTS

- [ ] 백엔드 신고 API와 voice server 포트/경로를 분리
- [ ] 음성 명령이 신고 API를 호출할 경우 같은 `ReportMetadata` 계약을 사용
- [ ] 위험 안내 TTS는 `/detect`의 ready/unavailable 상태에 따라 문구를 분기

## 다음 주 PR 후보

- [ ] `/detect` model adapter 최소 구현
- [ ] adapter 없는 환경과 있는 환경을 분리한 detect 테스트
- [ ] upload 이미지 full decode 검증 또는 EXIF 제거
- [ ] status/source/class DB check constraint 추가 Alembic revision
- [ ] 신고 상태 변경 이력 테이블
- [ ] 관리자 API 인증/권한
- [ ] S3 또는 persistent object storage adapter
- [ ] GeoJSON export 또는 heatmap API 초안
