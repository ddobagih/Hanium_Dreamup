# Backend

Android 사용자 앱과 별도 Android 관리자 앱에 FastAPI 및 PostgreSQL/PostGIS 기반 보행 경로 조회, 신고 저장·검토·내보내기와 관리자 API를 제공한다. 현행 Android 위험 탐지는 앱 내 TFLite가 담당한다.

## 책임 경계

- `LEGACY_REFERENCE_ONLY`인 Web/PWA의 과거 `server-v2` 탐지는 `/detect/v2`를 사용했다. 현행 Android 사용자 앱의 탐지는 기기 내 TFLite에서 수행한다.
- `/detect/v2`의 기본 모드는 `fake`다. 실제 추론은 운영 환경에서 768 unified 모델·runtime config를 명시하며, `/ready`가 실제 warmup 추론과 13-class 순서를 검증한다.
- `/reports/v2`는 손상 점자블록 신고만 저장한다. 공공기관 자동 API 제출은 제공하지 않는다. 관리자는 별도 앱에서 검토 결정을 남기고 외부 기관에 수동 신고한 사실·접수번호·상태만 서버에 기록할 수 있다.
- 현장 gateway의 역할별 service token, actor assertion, rate limit은 임시 운영 경계다. 조직 IdP·중앙 RBAC·signed upload URL을 대신하지 않으므로 외부 공개 API로 간주하지 않는다.
- 관리자 Android 앱은 배포환경에서 `PASSWORD_TOTP` 방식만 사용한다. 정적 `WALKSAFE_ADMIN_TOKEN`은 `WALKSAFE_ADMIN_SECURITY_ENABLED=true`일 때 관리자 API를 우회할 수 없다.

## 관리자 보안 초기 등록과 복구

마이그레이션 후 신뢰할 수 있는 로컬 관리 절차에서 `backend.app.services.admin_security.provision_admin_security`를 한 번 호출해 관리자 비밀번호, TOTP seed, 생성된 고엔트로피 복구코드(각 24자 이상)를 등록한다. 데이터베이스에는 scrypt 인코딩 또는 SHA-256 지문만 남고 평문 비밀번호·TOTP·복구코드·세션 토큰은 저장하지 않는다.

`WALKSAFE_ADMIN_TOTP_SECRET`은 공백·padding 없는 canonical Base32이며 디코딩 결과가 최소 20바이트여야 한다. 로그인·재인증은 ±1 시간구간을 허용하되 이미 사용한 TOTP timecode를 다시 받지 않는다. 로그인, 재인증, 복구 시작, 복구 완료는 PostgreSQL의 출처별·관리자 전체 기록을 함께 기준으로 제한하므로 토큰·출처·프로세스·replica를 바꿔도 시도 횟수가 쉽게 초기화되지 않는다.

기기 분실 복구 시에는 다음 순서를 지킨다.

1. 복구 시작 시 기존 관리자 세션이 모두 폐기되고 상태가 `RECOVERY_IN_PROGRESS`로 바뀐다.
2. 응답을 잃었으면 같은 복구코드와 같은 기기 ID로 다시 시작한다. 기존 만료시각은 유지되고 새 복구 토큰이 발급되며 이전 토큰은 무효가 된다.
3. 별도 비밀관리 경로에서 `WALKSAFE_ADMIN_TOTP_SECRET`을 새 seed로 교체하고 백엔드를 재시작한다.
4. 새 비밀번호와 새 seed의 TOTP로 복구를 완료한다. 복구코드는 이 성공 시점에만 사용 처리된다.

복구 완료 API는 데이터베이스에 기록된 이전 seed 지문과 현재 환경변수의 지문이 다르지 않으면 거부한다. 따라서 환경변수 교체 없이 이전 TOTP를 재사용하는 복구는 불가능하다.

## 관리자 장치 증명과 신고 워크플로

관리자 앱은 AndroidKeyStore에 내보낼 수 없는 P-256 개인키를 만들고 공개 SPKI descriptor만 화면에 표시한다. 신뢰할 수 있는 로컬 운영 절차에서 descriptor의 `device_id`, `key_version`, `public_key_spki_base64url`을 다음 명령에 전달한다. 이 명령은 개인키나 네트워크 등록을 받지 않는다.

```bash
PYTHONPATH=. python scripts/provision_walksafe_admin_device_key.py \
  --admin-id admin-01 \
  --device-id '<registered-device-id>' \
  --key-version 1 \
  --public-key-spki-base64url '<canonical-unpadded-base64url-spki>'
```

로그인, 복구 완료, 신고 검토·전달 이력 API는 `POST /admin/security/device-proof/challenges`에서 120초 단일사용 challenge를 받은 뒤 같은 요청 바이트에 대한 ECDSA-SHA256 서명을 함께 보낸다. 보호 요청은 `X-WalkSafe-Device-Challenge-Id`, `X-WalkSafe-Device-Signature`, `X-WalkSafe-Correlation-Id`를 사용하며, 이력 GET은 정확한 `X-WalkSafe-Read-Purpose`도 요구한다. 서명은 HTTP method, path, canonical query, 원문 body SHA-256, 관리자·기기·세션과 목적에 결속된다.

`POST/GET /reports/{report_id}/review-decisions`는 승인·거절·중복 결정을 append-only 이력으로 남긴다. `POST/GET /reports/{report_id}/deliveries`는 최신 결정이 모든 검토 항목을 통과한 `APPROVED`일 때만 수동 전달 관찰을 append-only로 기록한다. 이 API와 Android 앱은 이메일·문자·기관 API를 호출하지 않는다.

## 신고 이미지 원본 암호화

신규 신고 이미지는 검증·메타데이터 제거 후 AES-256-GCM `.wse` envelope로만 저장한다. 논리 `image_path`는 기존 `/uploads/<report-id>.<ext>` 계약을 유지하지만 이 경로는 DB 기반 관리자 세션, 고위험 재인증으로 발급한 목적 제한 1회용 grant, 성공 감사 기록 없이는 원본을 반환하지 않는다. 기존 평문 파일은 runtime에서 자동 변환하거나 fallback으로 읽지 않는다.

키는 DB·`UPLOAD_DIR`·환경변수 밖의 secret file 또는 KMS-agent에 둔다. secret file의 JSON 형식은 다음과 같으며 `material`은 정확히 32바이트의 canonical unpadded Base64url이다.

```json
{"generation":1,"keys":[{"id":"report-image-key-2026-01","material":"<32-byte-base64url>","state":"active"}],"previous_manifest_sha256":null,"schema":"walksafe.report-image-keyring.v1"}
```

회전 시 이전 active는 `decrypt-only` 또는 `compromised`로만 전이한다. `compromised` 항목은 `material`을 제거하고 기존 `material_sha256`만 유지하며 API 복호화가 금지된다. 배포환경의 DB at-rest·transport·서로 다른 키 경계 설정은 시작 전제인 운영자 선언이며 실제 cloud DB/KMS 검증 결과를 대신하지 않는다.

## 구조

| 경로 | 역할 |
|---|---|
| `app/` | FastAPI 조립, 설정, schema, DB model과 공통 업로드 처리 |
| `app/api/` | HTTP endpoint와 요청/오류 변환 |
| `app/services/` | 탐지, 신고 정책, 중복 판정, TMAP 목적지·보행 경로 연동 |
| `tests/` | 순수 계약 테스트와 PostGIS 통합 테스트 |
| `alembic/` | PostGIS schema migration |

현행 API schema는 실제 route·Pydantic source에서 생성한 [`contracts/walksafe.openapi.json`](../contracts/walksafe.openapi.json), 로컬 환경 절차는 [개발 환경 가이드](../docs/guides/development-environment-guide.md#python과-backend), 환경값은 [`backend/.env.example`](.env.example)과 실제 `config.py`를 기준으로 본다. [`docs/backend/api_reference.md`](../docs/backend/api_reference.md)와 [`docs/backend/backend_environment.md`](../docs/backend/backend_environment.md)는 FP-046 완료 근거에 bytes가 결속된 역사 설명이므로 오래된 인증·저장·실행 문구를 현행 계약으로 사용하지 않는다.

## 실행

Backend lock과 로컬 설정을 준비하는 정확한 순서는 [개발 환경 가이드](../docs/guides/development-environment-guide.md#python과-backend)를 따른다. Compose는 `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`가 없는 상태에서 실행되지 않으므로 `backend/.env`를 먼저 만들고 예시의 `CHANGE_ME`를 실제 로컬 값으로 바꾼다.

```bash
source .venv-backend/bin/activate
docker compose --env-file backend/.env up -d db
python -m alembic -c backend/alembic.ini upgrade head
PYTHONPATH=. python -m uvicorn backend.app.main:app --reload --port 8000
```

## 검증

Backend 검증도 개별 `backend/tests`를 한 번에 직접 수집하지 않고 [현재 테스트 가이드](../docs/guides/testing-guide.md)와 `scripts/run_walksafe_test_layers_current.sh`의 Unit·Functional·Integration 분류를 사용한다. 테스트 Python은 `.venv-tests/bin/python`, DB 검사가 필요한 계층은 이름에 `test`가 포함되고 운영 DB와 다른 전용 PostGIS를 사용한다. 지정한 DB에 연결할 수 없으면 테스트는 skip하지 않고 실패한다.
