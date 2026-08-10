# Backend Environment and CORS

작성 기준일: 2026-07-13

## 목적

FastAPI/PostGIS 백엔드 실행에 필요한 환경 변수와 CORS 설정을 한 곳에 정리한다. 실제 값은 `backend/.env`에 두고, 예시는 `backend/.env.example`을 기준으로 유지한다.

## 기본 실행 순서

```bash
docker compose up -d db
source .venv/bin/activate
python -m pip install -r backend/requirements.txt
cp backend/.env.example backend/.env
python -m alembic -c backend/alembic.ini upgrade head
python -m uvicorn backend.app.main:app --reload --port 8000
```

## 환경 변수

| 이름 | 기본값 | 설명 |
| --- | --- | --- |
| `DATABASE_URL` | `postgresql+psycopg://walksafe:walksafe@localhost:5432/walksafe` | SQLAlchemy DB URL |
| `WALKSAFE_MIGRATION_DATABASE_URL` | 빈 값 | 배포 전용 Alembic URL. 배포 환경에서는 필수이며 `DATABASE_URL`과 사용자명이 달라야 함 |
| `DATABASE_CONNECT_TIMEOUT_SECONDS` | `5` | DB 연결 timeout, 최대 30초 |
| `DATABASE_STATEMENT_TIMEOUT_MS` | `10000` | DB statement timeout, 최대 120000ms |
| `WALKSAFE_TEST_DATABASE_URL` | 빈 값 | 테스트 전용 PostGIS URL. `postgresql+psycopg`, 이름에 `test`, 실제 접속 DB 일치가 필수 |
| `UPLOAD_DIR` | `backend/uploads` | 신고 이미지 저장 폴더. field/staging/production은 service 소유 absolute real directory·권한 `0700` 필수 |
| `MAX_UPLOAD_BYTES` | `8388608` | 업로드 이미지 최대 크기 |
| `ALLOWED_IMAGE_CONTENT_TYPES` | `image/jpeg,image/png,image/webp` | 허용할 이미지 MIME |
| `MODEL_ARTIFACT_PATH` | 빈 값 | 실제 모델 파일 경로 |
| `MODEL_VERSION` | 빈 값 | v1 `/detect` model version 표시 |
| `MODEL_CLASS_ORDER` | `damaged_tactile_block,parked_kickboard_bicycle,construction_obstacle,pothole` | v1 `/detect` class order |
| `MODEL_CONFIDENCE_THRESHOLD` | `0.35` | v1 `/detect` confidence threshold |
| `MODEL_IOU_THRESHOLD` | `0.7` | v1 `/detect` NMS IoU threshold |
| `MODEL_IMAGE_SIZE` | `640` | v1 `/detect` image size |
| `DETECT_V2_MODE` | `fake` | v2 provider mode: `fake`, `yolo`, `real` |
| `DETECT_V2_IMAGE_SIZE` | `768` | unified 배포 입력 크기. 배포 unified checkpoint에서는 768 고정 |
| `DETECT_V2_UNIFIED_MODEL_PATH` | 빈 값 | v2 unified 13-class YOLO checkpoint. 있으면 이 경로를 우선 사용 |
| `DETECT_V2_CUSTOM_TACTILE_MODEL_PATH` | 빈 값 | v2 legacy fallback custom tactile YOLO checkpoint |
| `DETECT_V2_COCO_MODEL_PATH` | 빈 값 | v2 legacy fallback COCO helper model |
| `DETECT_V2_RUNTIME_CONFIG_PATH` | 빈 값 | v2 threshold/runtime config |
| `INFERENCE_PROCESS_ISOLATION_ENABLED` | 환경별 | 배포 환경은 `true` 필수 |
| `INFERENCE_TIMEOUT_SECONDS` | `2.0` | warmed inference timeout, 최대 30초 |
| `INFERENCE_STARTUP_TIMEOUT_SECONDS` | `30.0` | 최초 load/warm-up timeout, 최대 120초 |
| `WALKING_ROUTE_PROVIDER` | `tmap_pedestrian` | 보행 route provider. 제품 계약은 `tmap_pedestrian` 고정 |
| `TMAP_APP_KEY` | 빈 값 | TMAP 보행자 경로안내 appKey. Git에 커밋하지 않는다 |
| `TMAP_PEDESTRIAN_ROUTE_URL` | `https://apis.openapi.sk.com/tmap/routes/pedestrian` | TMAP 보행자 경로안내 endpoint |
| `TMAP_PEDESTRIAN_API_VERSION` | `1` | TMAP 보행자 경로안내 API version query |
| `TMAP_POI_SEARCH_URL` | `https://apis.openapi.sk.com/tmap/pois` | TMAP 목적지 POI 검색 endpoint |
| `TMAP_POI_PROVIDER` | `live` | 목적지 검색 mode. `live` 또는 로컬 fixture용 `mock` |
| `TMAP_TIMEOUT_SECONDS` | `4.0` | TMAP 보행자 경로안내 요청 timeout |
| `TMAP_PEDESTRIAN_SPEED_KMH` | `4.0` | TMAP 보행자 경로 요청 기본 보행 속도(km/h) |
| `MAX_REPORT_METADATA_BYTES` | `65536` | `/reports`, `/reports/v2` metadata JSON 최대 크기 |
| `ANDROID_DEBUG_LOG_ENABLED` | `false` | local/dev Android depth log와 frame capture API 활성화 |
| `ANDROID_DEBUG_LOG_DIR` | `backend/android_debug_logs` | Android debug JSONL/이미지 저장 폴더 |
| `MAX_ANDROID_DEBUG_LOG_BYTES` | `65536` | Android debug payload 또는 frame metadata 최대 크기 |
| `ANDROID_DEBUG_LOG_RETENTION_DAYS` | `7` | debug log 보존일, 최대 30일 |
| `CORS_ORIGINS` | `http://localhost:3000,http://127.0.0.1:3000` | 허용할 프론트엔드 origin 목록 |
| `WALKSAFE_ENVIRONMENT` | `development` | `development`, `test`, `field`, `staging`, `production` |
| `WALKSAFE_SOURCE_COMMIT` | 빈 값 | 배포 환경에서 필수인 정확한 lowercase 40자리 Git SHA |
| `WALKSAFE_FIELD_TEST_SECURITY_ENABLED` | `true` | field/admin 역할 보호 활성화 |
| `WALKSAFE_FIELD_TEST_TOKEN` | 빈 값 | field 역할 service token, 활성 시 최소 24자 |
| `WALKSAFE_ADMIN_TOKEN` | 빈 값 | 관리자 DB 인증을 끈 development/test에서만 쓰는 legacy service token. `WALKSAFE_ADMIN_SECURITY_ENABLED=true`이면 우회 수단으로 인정하지 않음 |
| `WALKSAFE_ADMIN_SECURITY_ENABLED` | `false` | 비밀번호+TOTP, 기기별 세션, 복구, 고위험 재확인을 DB 상태로 관리. field/staging/production은 반드시 `true` |
| `WALKSAFE_ADMIN_ID` | 빈 값 | 한 명의 명명된 최종관리자 식별자. 영문·숫자로 시작하고 `._@-` 포함 최대 64자 |
| `WALKSAFE_ADMIN_TOTP_SECRET` | 빈 값 | 공백·패딩 없는 canonical Base32 TOTP seed. 해독 결과 최소 20바이트이며 Git·DB·관리자 휴대전화 밖의 승인된 비밀 저장소에서 주입 |
| `WALKSAFE_ADMIN_SESSION_TTL_SECONDS` | `43200` | 기기별 관리자 세션 최대 수명. 최대 86400초 |
| `WALKSAFE_ADMIN_STEP_UP_TTL_SECONDS` | `300` | 민감 작업용 최근 TOTP 재확인 유효시간. 최대 900초 |
| `WALKSAFE_ADMIN_RECOVERY_TTL_SECONDS` | `900` | 복구 거래 유효시간. 최대 1800초이며 같은 코드·같은 기기 재개 때 연장하지 않음 |
| `WALKSAFE_ADMIN_AUTH_RATE_LIMIT_ATTEMPTS` | `5` | 로그인·재인증·복구 단계별 한 출처의 최대 실패 시도. 출처를 바꾸는 우회를 막기 위해 관리자 전체에는 이 값의 4배 제한도 적용. 설정 최대 20회 |
| `WALKSAFE_ADMIN_AUTH_RATE_LIMIT_WINDOW_SECONDS` | `300` | 관리자 인증 시도 제한 창. 최대 3600초 |
| `WALKSAFE_GATEWAY_SESSION_SECRET` | 빈 값 | named actor 30초 assertion HMAC secret. 배포 환경 최소 32자·두 token과 달라야 함 |
| `WALKSAFE_PRIVACY_HMAC_SECRET` | 빈 값 | privacy subject 전용 HMAC secret. UTF-8 인코딩 기준 최소 32바이트. FP-046 Alembic도 legacy named-actor report를 generation 1 pseudonymous binding으로 바꾼 뒤 raw actor metadata를 제거할 때 같은 값을 사용하므로 migration/runtime 사이에 바꾸면 안 됨 |
| `WALKSAFE_PRIVACY_HMAC_KEY_VERSION` | `1` | DB singleton key binding과 대조할 양의 key version. 명시적 keyring migration 없이 변경하면 startup/readiness가 fail closed |
| `WALKSAFE_ALLOW_INSECURE_LOCAL_DEV` | `false` | 보안 비활성 local development/test를 명시적으로 허용하는 opt-in |
| `WALKSAFE_MAINTENANCE_LOCK_PATH` | 빈 값 | 보존·backup 작업이 공유하는 service-owned exclusive lock 파일. parent는 기존 current-user 0700 real directory이고 parent 위의 모든 조상은 `/`까지 root-owned·group/other non-writable·service UID 비가용이어야 한다. production 예시는 `/run/walksafe-backend/maintenance.lock`; field launcher는 검증된 `/run/user/$UID`의 `walksafe.lock`을 기본 사용한다 |

관리자 보안 최초 구성은 비밀번호 hash와 일회용 복구코드 hash를 DB에 등록하는 별도 provisioning 절차가 필요하다. TOTP seed와 평문 복구코드는 DB에 저장하지 않는다. 현재 저장소의 내부 helper와 자동검사는 구현 검증용이며, 운영 비밀 저장소·암호화 백업·실제 휴대전화 분실 복구훈련이 끝났다는 증거가 아니다.

보존·삭제처럼 서버 밖에서 실행하는 고위험 도구는 실행 직전에 최근 TOTP 재확인을 마친 세션을 `WALKSAFE_ADMIN_HIGH_RISK_SESSION_TOKEN`과 해당 기기의 `WALKSAFE_ADMIN_HIGH_RISK_DEVICE_ID`로 프로세스에만 주입한다. 세션값을 `.env`, 서비스 파일, 셸 기록, Git에 저장하지 않는다. 값이 없거나 복구 상태·만료·기기 불일치이면 변경 전에 중단한다.

현재 오프라인 삭제의 DB control 잠금은 DB 연결과 transaction이 정상인 동안만 보장된다. 장시간 작업의 연결 상실 fencing, 부분 삭제가 발생해도 남는 독립 진행 journal, 최근 세션의 일회성 운영 인계는 아직 운영 차단 항목이다. 따라서 이 도구들은 내부 구현 검증 범위이며 운영 자동삭제가 준비됐다는 뜻이 아니다.

배포 DB 계정은 다음처럼 분리한다. Alembic은 public app schema를 소유하고 `CREATEROLE`을 가진 별도 non-superuser `walksafe_migrator`로만 실행한다. FP-046이 만든 `walksafe_receipt_purge_owner`의 owner 이전·downgrade를 위해 migration이 이 계정에 `SET ROLE` 가능한 membership을 부여하며, 같은 계정을 이후 migration에도 유지한다. 기존 동명 role이 있으면 모두 안전한 `NOLOGIN/NOSUPERUSER/NOCREATEDB/NOCREATEROLE/NOREPLICATION/NOBYPASSRLS` 상태여야 하고, 그렇지 않으면 non-superuser migration은 fail closed한다. API 로그인 계정(예: `walksafe_backend_app`)은 `NOSUPERUSER NOCREATEDB NOCREATEROLE`이며 NOLOGIN privilege group `walksafe_backend_runtime`만 상속한다. API 계정은 receipt table owner나 `walksafe_receipt_purge_owner` 구성원이 될 수 없고, receipt `UPDATE/DELETE/TRUNCATE` 및 purge 함수 실행 권한도 없어야 한다. 시작 및 `/ready` preflight가 이 경계를 확인하고 위반 시 fail closed한다.

관리자 보안이 켜진 API replica는 `/ready`에서 런타임 TOTP seed의 지문과 DB control 지문을 비교한다. 복구 뒤 이전 seed를 가진 replica는 준비 완료가 되지 않으며 관리자 요청도 거부한다. systemd 백엔드 서비스는 credential이 core dump에 남지 않도록 `LimitCORE=0`을 사용한다.

## TMAP 보행 길안내 설정

`/navigation/walking`은 서버가 TMAP 보행자 경로안내 API만 호출하는 proxy endpoint다. TMAP appKey는 백엔드 `.env`에만 둔다.

```env
WALKING_ROUTE_PROVIDER=tmap_pedestrian
TMAP_APP_KEY=티맵_APP_KEY
```

실제 provider 연결 smoke:

```bash
python3 scripts/check_tmap_pedestrian_route_smoke_20260524.py
```

`WALKING_ROUTE_PROVIDER`는 `tmap_pedestrian`만 허용한다. 다른 값은 시작 설정 또는 요청 처리에서 거부하며 자동·수동 provider 전환은 없다.

프론트 테스트용 목적지 좌표는 `apps/web/.env.local` 같은 프론트 env에 둔다. 이 값은 브라우저 번들에 포함되므로 secret을 넣지 않는다.

```env
NEXT_PUBLIC_WALKSAFE_DESTINATION_LAT=
NEXT_PUBLIC_WALKSAFE_DESTINATION_LNG=
NEXT_PUBLIC_WALKSAFE_DESTINATION_NAME=
```

## v2 unified-primary 로컬 예시

검증할 unified 13-class checkpoint가 있으면 backend는 path 하나만 지정하면 된다. 저장소에 모델 파일이 존재하는 것만으로는 자동 연결되지 않는다.

```env
DETECT_V2_MODE=yolo
DETECT_V2_UNIFIED_MODEL_PATH=/absolute/path/to/walksafe-unified-best.pt
DETECT_V2_RUNTIME_CONFIG_PATH=/home/ddobagi/Code/hanium-dreamup/configs/walksafe_two_model_runtime_stage1_mvp_20260523.json
```

`GET /detect/v2/health`의 `ready`는 경로/config 확인 결과다. 실제 weight load와 inference 성공은 이미지 smoke로 별도 검증한다. 운영 환경에서 `DETECT_V2_MODE=fake`를 실사용 준비 상태로 해석하지 않는다.

실제 serving 준비 여부는 `GET /ready`를 사용한다. 이 endpoint는 DB/Alembic head, upload root write+file/directory fsync, real detector의 blank-frame warm-up과 TMAP-only/live/key를 함께 확인하며 하나라도 실패하면 503이다.

Unified path가 없을 때만 아래 legacy two-model fallback 예시를 사용한다.

## v2 legacy Stage1 fallback 로컬 예시

2026-05-23 KST 기준 reviewed YOLO26s MVP/backend integration 후보는 Stage1 `best.pt`다.

```env
DETECT_V2_MODE=yolo
DETECT_V2_CUSTOM_TACTILE_MODEL_PATH=/absolute/path/to/custom-tactile-best.pt
DETECT_V2_COCO_MODEL_PATH=/home/ddobagi/Code/hanium-dreamup/model/artifacts/pretrained/yolo26n.pt
DETECT_V2_RUNTIME_CONFIG_PATH=/home/ddobagi/Code/hanium-dreamup/configs/walksafe_two_model_runtime_stage1_mvp_20260523.json
```

경로/config readiness만 확인하려면 아래 health-only smoke를 사용한다.

```bash
bash scripts/check_detect_v2_stage1_candidate_health_20260523.sh
```

이 smoke는 YOLO weight를 로드하거나 inference를 실행하지 않는다.

## CORS 설정

`CORS_ORIGINS`는 쉼표로 구분한다.

```env
CORS_ORIGINS=http://localhost:3000,http://127.0.0.1:3000
```

외부 프론트엔드가 다른 포트에서 실행되면 해당 origin을 추가한다.

```env
CORS_ORIGINS=http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173
```

운영 또는 발표 환경에서는 `*` 대신 실제 프론트엔드 주소만 넣는다.

## 업로드 설정

현재 서버가 실제로 처리하는 MIME은 다음 3개다.

```text
image/jpeg
image/png
image/webp
```

`ALLOWED_IMAGE_CONTENT_TYPES`에 다른 MIME을 추가해도 코드에서 지원하지 않으면 업로드가 거부된다. HEIC, GIF 같은 형식을 받으려면 `backend/app/uploads.py`의 확장자, 저장 suffix, 파일 헤더 검증을 함께 추가해야 한다.

서버는 원본 파일명을 저장 파일명으로 사용하지 않는다. 원본 파일명은 확장자와 MIME의 명백한 불일치를 확인하는 데만 사용한다.

## 정적 이미지 경로

업로드된 이미지는 FastAPI에서 `/uploads`로 제공된다.

예:

```text
/uploads/0e6d9c2a-0c35-4c08-8f4b-2c0e2c9ef111.jpg
```

로컬 저장 위치를 바꾸려면 `UPLOAD_DIR`을 수정한다.

보안 활성 profile의 `/uploads/{filename}`은 admin token과 named actor assertion으로 보호된다. signed URL은 아니므로 gateway 밖 공개 URL로 노출하지 않는다. 보안을 끄는 경우는 `development`/`test`와 `WALKSAFE_ALLOW_INSECURE_LOCAL_DEV=true`를 함께 명시한 로컬 개발뿐이다.

## Android debug API

`ANDROID_DEBUG_LOG_ENABLED=false`가 기본이다. local/dev evidence 수집 때만 명시적으로 켠다. field/staging/production에서는 `true` 설정을 시작 단계에서 거부하고, 보안 활성 local profile에서도 endpoint는 admin 역할만 접근한다. debug directory는 `0700`, JSONL/image는 `0600`으로 만들며 symlink와 경로 이탈을 거부한다. depth log는 metadata-only지만 frame capture는 실제 이미지를 저장할 수 있다.

## 테스트 DB preflight

Functional/Integration은 운영 `DATABASE_URL`을 재사용하지 않는다.

```bash
WALKSAFE_TEST_DATABASE_URL='postgresql+psycopg://.../walksafe_test' \
  python3 scripts/check_walksafe_test_database_20260713.py
```

검사는 URL driver, test 이름, 보호된 운영 DB명과의 불일치, 5초 이내 연결과 실제 `current_database()` 일치를 확인한다. 이후 migration을 적용하고 advisory lock을 획득한 상태에서 no-skip 회귀를 실행한다.
