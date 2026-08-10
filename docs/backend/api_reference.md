# Backend API Reference

작성 기준일: 2026-08-09 KST

## 범위

이 문서는 프론트엔드와 백엔드가 공유해야 하는 FastAPI 계약만 정리한다. v1 legacy 탐지 이벤트 필드의 상세 규칙은 `_archive_candidates/2026-07-08/docs/inference_contract.md`를 참고하되, 현재 v2/unified 기준은 이 문서와 `docs/walksafe-v2/backend_api_contract.md`를 우선한다.

기본 서버 주소:

```text
http://127.0.0.1:8000
```

## 공통 규칙

- 응답은 JSON이다.
- 신고 생성과 서버 추론 요청은 `multipart/form-data`를 사용한다.
- 날짜/시간은 ISO 8601 문자열을 사용한다.
- 위도/경도는 WGS84 좌표를 사용한다.
- 이미지 원본 파일명은 저장 파일명으로 사용하지 않는다. 서버는 UUID 기반 파일명으로 저장한다.
- `WALKSAFE_FIELD_TEST_SECURITY_ENABLED=true`가 기본이며 field token과 관리자 DB 세션 역할을 분리한다. detect/report 생성/navigation/`health`/`ready`는 field, report 조회·검수·summary/export·upload 조회·debug·OpenAPI는 admin 역할이다. 관리자 보안을 끈 development/test에서만 legacy admin token·named actor assertion 계약을 재현할 수 있다.
- 보안 비활성은 `development` 또는 `test`에서 `WALKSAFE_ALLOW_INSECURE_LOCAL_DEV=true`까지 명시한 로컬 개발에만 허용된다. field/staging/production에서 이 우회는 시작 단계에서 거부된다. 이 임시 token/actor 경계는 조직 IAM이나 Release 완료를 뜻하지 않는다.

## 개인정보 동의·계정 삭제

FP-046 개인정보 API는 다음 네 경로만 제공한다. 모든 요청에는 `X-WalkSafe-Actor-Id`와 `X-WalkSafe-Account-Generation`이 필요하다. 보안 활성 환경에서 동의 API는 actor/access/generation에 결속된 표준 `X-WalkSafe-Actor-Assertion`을 사용하고, 세 삭제 API는 method/path/request ID/access-pre digest/tombstone 문맥에 결속된 삭제 전용 assertion을 사용한다.

| API | 추가 필수 헤더 | 성공 응답 |
| --- | --- | --- |
| `POST /privacy/consent-events` | 없음 | 신규 `201`, 동일 요청 재전송 `200`; 동의 영수증 반환 |
| `POST /privacy/account-deletions` | `X-WalkSafe-Deletion-Access-Pre-Digest`; 생성 요청에는 tombstone 헤더를 보내지 않음 | 신규 `202`, 동일 요청 재전송 `200`; 삭제 상태 반환 |
| `GET /privacy/account-deletions/{request_id}/status` | `X-WalkSafe-Deletion-Access-Pre-Digest`, `X-WalkSafe-Deletion-Tombstone-Id` | `200`; 삭제 상태 반환 |
| `POST /privacy/account-deletions/{request_id}/device-evidence` | `X-WalkSafe-Deletion-Access-Pre-Digest`, `X-WalkSafe-Deletion-Tombstone-Id` | `200`; 갱신된 삭제 상태 반환 |

삭제 API의 actor, account generation, request ID, access-pre digest, tombstone ID는 서로 결속되어야 한다. 동의와 삭제 요청은 `request_id` 기반 멱등성을 제공하며 같은 ID로 다른 내용을 보내면 `409`로 거부한다. 공통 오류 상태는 `400`, `401`, `404`, `409`, `413`, `422`, `429`, `503`이고 본문은 `{ "detail": { "code": "...", "message": "..." } }` 형태다. 개인정보 API의 성공·오류 응답에는 모두 `Cache-Control: no-store`와 `Pragma: no-cache`가 적용된다.

## Android 관리자 인증·복구

field/staging/production은 `WALKSAFE_ADMIN_SECURITY_ENABLED=true`가 필수다. 이때 정적 `X-WalkSafe-Admin-Token`은 관리자 권한을 주지 않는다. 모든 관리자 앱 요청은 다음 네 문맥 헤더를 정확히 보내며, 보호 API는 기기별 opaque Bearer 세션도 함께 보낸다.

| 헤더 | 값 |
| --- | --- |
| `X-WalkSafe-App-Kind` | `ADMIN_ANDROID` |
| `X-WalkSafe-Role` | `ADMIN` |
| `X-WalkSafe-Audience` | `walksafe-admin-api` |
| `X-WalkSafe-Device-Id` | 요청 본문 또는 세션에 결속된 기기 ID |
| `Authorization` | 보호 API에서만 `Bearer {opaque-session}` |

| API | 인증 | 역할 |
| --- | --- | --- |
| `POST /admin/security/device-proof/challenges` | LOGIN·RECOVERY_COMPLETE은 네 문맥 헤더, ACTION은 Bearer도 필요 | 요청 바이트에 결속된 120초 단일사용 challenge 발급 |
| `POST /admin/security/sessions` | 네 문맥 헤더 + 장치 증명 + 비밀번호 + 새 TOTP | 로그인과 기기별 세션 발급 |
| `GET /admin/security/state` | Bearer + 네 문맥 헤더 | 서버 권위 상태 확인 |
| `GET /admin/security/sessions` | Bearer + 네 문맥 헤더 | 최대 100개 기기 세션 조회 |
| `POST /admin/security/sessions/{id}/revoke` | Bearer + 네 문맥 헤더 | 선택 기기 세션 폐기 |
| `POST /admin/security/reauthenticate` | Bearer + 네 문맥 헤더 + 비밀번호 + 새 TOTP | 고위험 작업용 최근 재확인 |
| `POST /admin/security/recovery/start` | 네 문맥 헤더 + 외부 복구코드 | 모든 세션 폐기와 복구 거래 시작·재개 |
| `POST /admin/security/recovery/complete` | 네 문맥 헤더 + 장치 증명 + 복구토큰 + 새 비밀번호 + 교체된 seed의 TOTP | 복구 완료와 새 기기 세션 발급 |

상태는 `NORMAL`, `RECOVERY_REQUIRED`, `RECOVERY_IN_PROGRESS` 세 값뿐이다. 복구 시작 응답을 잃으면 같은 미사용 복구코드와 같은 기기로 다시 시작할 수 있다. 이때 기존 만료시각은 늘어나지 않고 토큰만 교체되며, 이전 토큰은 무효다. 복구코드는 비밀번호와 TOTP seed 교체가 성공한 시점에만 사용 처리된다.

로그인·재인증·복구 실패는 PostgreSQL에서 출처별·관리자 전체로 제한한다. 429 응답의 `Retry-After`는 초 단위다. 오류 본문은 `{ "detail": { "code": "...", "message": "..." } }` 형태이며 앱은 허용된 `code`만 사용자 행동으로 바꾸고 서버 원문은 표시하지 않는다.

`GET /reports/export`, `PATCH /reports/{id}/status`와 오프라인 자료삭제는 상태가 `NORMAL`이고 최근 TOTP 재확인이 남아 있을 때만 시작된다. API는 실제 조회·변경 트랜잭션에서 상태를 다시 검사한다. 오프라인 삭제는 DB 연결이 정상인 동안 같은 관리자 control transaction 잠금을 유지한다. 장시간 삭제 중 연결 상실을 즉시 감지해 이후 항목 삭제를 차단하는 fencing과 부분 실패 durable journal은 아직 운영 차단 항목이다. 민감한 관리자 응답은 `Cache-Control: no-store`로 반환한다. 이 내부 구현은 실제 운영 비밀 provisioning, 관리자 앱 서명·사설 배포, 실기기 분실 복구훈련을 완료했다는 뜻이 아니다.

### 장치 증명 wire 계약

장치 공개키는 신뢰할 수 있는 로컬 절차에서 `scripts/provision_walksafe_admin_device_key.py`로 등록한다. Android 앱의 P-256 개인키는 AndroidKeyStore 밖으로 내보내지 않으며 서버에는 canonical SPKI DER와 그 SHA-256 marker만 저장한다.

Challenge exact13 요청은 다음 13개 키를 모두 물리적으로 포함하고 그 밖의 키를 포함하지 않는다. nullable 값도 생략하지 않고 JSON `null`로 보낸다.

```text
action, admin_id, body_sha256, correlation_id, device_id,
device_key_marker, device_key_version, method, path, purpose,
query_sha256, read_purpose, session_id
```

Challenge 요청 자체도 본문의 `correlation_id`와 같은 `X-WalkSafe-Correlation-Id`를 정확히 한 번 보낸다. 응답 exact19는 `walksafe.admin-device-proof.v2` exact18 필드와 `signing_payload`로만 구성된다. `signing_payload`는 exact18 객체를 UTF-8, key 정렬, 공백 없는 JSON으로 표현한 문자열이며 Base64가 아니다. 앱은 그 문자열의 UTF-8 바이트를 ECDSA P-256/SHA-256으로 서명하고, ASN.1 DER 서명을 padding 없는 Base64url로 인코딩한다. 최종 요청은 challenge와 동일한 body 바이트·query를 재사용하고 다음 헤더를 각각 정확히 한 번 보낸다.

| 헤더 | 적용 |
| --- | --- |
| `X-WalkSafe-Device-Challenge-Id` | 모든 장치 증명 요청 |
| `X-WalkSafe-Device-Signature` | 모든 장치 증명 요청 |
| `X-WalkSafe-Correlation-Id` | challenge의 `correlation_id`와 동일 |
| `X-WalkSafe-Read-Purpose` | 보호된 GET만 사용 |

`LOGIN`과 `RECOVERY_COMPLETE`은 `session_id`, `action`, `read_purpose`가 null이다. `ACTION`은 현재 Bearer 세션의 관리자·기기·세션과 정확히 일치해야 한다. Challenge는 성공 검증 트랜잭션에서 한 번만 소비되며 재사용할 수 없다. 전체 exact18/19 필드와 canonical query vector의 정본은 `docs/control/execution/goal-results/WS-GOAL-EPIC-03-FP-008-R001/policy-contract.json`이다.

### 신고 검토·수동 전달 이력

다음 네 API만 FP-008 관리자 운영 화면에 노출된다. 모두 관리자 세션과 장치 증명을 요구하며 민감한 응답은 `no-store`다.

| API | 장치 증명 결속 | 역할 |
| --- | --- | --- |
| `POST /reports/{report_id}/review-decisions` | `action=report.review.decide` | `APPROVED`, `REJECTED`, `DUPLICATE` 결정과 사유·검토표식 기록 |
| `GET /reports/{report_id}/review-decisions` | `read_purpose=report.review_decisions` | revision 순 append-only 결정 이력 조회·조회감사 |
| `POST /reports/{report_id}/deliveries` | `action=report.delivery.create` | 수동 기관 전달의 기관·채널·수신자·상태·접수번호 기록 |
| `GET /reports/{report_id}/deliveries` | `read_purpose=report.delivery_events` | revision 순 append-only 전달 이력 조회·조회감사 |

전달 초기 상태는 `SUBMITTED` 또는 `FAILED`다. 이후 전이는 `FAILED→FAILED|SUBMITTED`, `SUBMITTED→ACKNOWLEDGED|FAILED`, `ACKNOWLEDGED→RESOLVED`만 허용하고 `RESOLVED`는 종료 상태다. `observed_at`은 RFC3339 UTC 문자열이며 서버 기록 시각은 `recorded_at`으로 반환한다.

`expected_revision`과 UUID `idempotency_key`로 경쟁과 재시도를 제어한다. 동일 key와 동일 요청은 기존 결과를 반환하고 다른 요청은 충돌로 거부한다. `ACKNOWLEDGED`와 `RESOLVED`에는 외부 접수번호가 필요하다. `DUPLICATE` 대상은 존재해야 하며 자기 자신일 수 없다. 최신 검토 결정이 위치·사진·개인정보를 모두 확인한 `APPROVED`가 아니면 새 전달 이력을 받을 수 없다. 서버와 앱은 외부 기관 전송을 수행하지 않고 이미 수동으로 수행한 사실만 기록한다.

## 이미지 업로드 정책

| 항목 | 현재 값 |
| --- | --- |
| 허용 MIME | `image/jpeg`, `image/png`, `image/webp` |
| 기본 최대 크기 | `8388608` bytes |
| 저장 위치 | `UPLOAD_DIR` |
| 저장 파일명 | `{report_id}.{jpg|png|webp}` |

검증 순서:

1. multipart `Content-Type`이 허용 목록에 있는지 확인한다.
2. 파일명 확장자가 있으면 MIME과 충돌하지 않는지 확인한다.
3. 최대 크기를 넘지 않는지 확인한다.
4. 파일 바이트의 최소 헤더가 MIME과 맞는지 확인한다.
5. Pillow로 실제 decode한 뒤 metadata를 제거해 안전한 포맷으로 재인코딩한다. decode/sanitize가 실패하거나 의존성이 없으면 원본을 저장하지 않고 거부한다.

오류 코드:

| HTTP | `detail.code` | 의미 |
| ---: | --- | --- |
| 400 | `unsupported_image_type` | 허용되지 않은 MIME |
| 400 | `image_extension_mismatch` | 파일명 확장자와 MIME 불일치 |
| 400 | `empty_image` | 빈 파일 |
| 400 | `image_content_mismatch` | 이미지 헤더와 MIME 불일치 |
| 413 | `upload_too_large` | 최대 업로드 크기 초과 |

## GET /uploads/{filename}

저장된 신고 이미지를 반환한다. 경로 구분자를 포함한 filename은 거부하며 응답에는 `Cache-Control: no-store`가 붙는다. 보안 활성 profile에서는 admin token·named actor assertion이 필요하다. signed URL은 아니므로 gateway 밖 공개 URL로 노출하지 않는다.

## GET /health

백엔드 프로세스 상태 확인.

응답:

```json
{
  "status": "ok"
}
```

## GET /ready

dependency-aware readiness다. 단순 liveness인 `/health`와 달리 다음 항목을 실제 확인한다.

- DB 연결과 Alembic expected head 일치
- 개인정보 HMAC 비밀의 32 UTF-8 byte 최소 길이와 key version, DB runtime role 결속
- upload root가 symlink 아닌 실제 디렉터리인지와 임시 파일·디렉터리 `fsync` 가능 여부
- real/yolo detector artifact/runtime binding과 blank-frame model load·inference warm-up
- 보행 provider가 TMAP-only이고 POI mode가 `live`이며 서버 key가 존재하는지
- 신고 이미지 암호화 keyring·active key와 파일/DB 암호화 경계
- 신고 저장소의 DB·파일 inventory 일치와 원본 평문·고아 파일 부재

모두 통과하면 `200 status=ready`, 하나라도 실패하면 `503 status=not_ready`와 항목별 sanitized reason을 반환한다. inference worker가 이미 warm 상태로 다른 요청을 처리 중이면 readiness가 그 작업 뒤에서 무기한 대기하지 않는다.

## GET /detect/health

v1 서버 추론 모델 상태 확인. `MODEL_ARTIFACT_PATH`에 호환되는 `.pt`가 설정되어 있으면 `ready`, 없거나 로드할 수 없으면 `unavailable`을 반환한다.

응답 예:

```json
{
  "model_status": "unavailable",
  "model_version": null,
  "reason": "model_not_configured"
}
```

## POST /detect

v1 서버 추론 API. `MODEL_ARTIFACT_PATH`가 준비된 개발 환경에서는 YOLO `.pt` adapter 결과를 v1 `DetectionEvent`로 반환한다. 모델이 설정되지 않았거나 로드할 수 없으면 이미지를 검증한 뒤 `503 model_unavailable`을 반환한다.

요청:

| part | 내용 |
| --- | --- |
| `context` | `DetectContext` JSON 문자열 |
| `image` | 카메라 프레임 이미지 |

`context` 예:

```json
{
  "captured_at": "2026-05-12T12:00:00Z",
  "gps": {
    "latitude": 37.5665,
    "longitude": 126.978,
    "accuracy_m": 9.5
  },
  "heading": 180
}
```

모델 미설정 오류 응답 예:

```json
{
  "detail": {
    "code": "model_unavailable",
    "reason": "model_not_configured"
  }
}
```


## GET /detect/v2/health

v2 탐지 provider 설정 상태를 확인한다. `DETECT_V2_MODE` 기본값은 `fake`이며, `yolo` 또는 `real`이면 unified model path를 우선 확인하고 없으면 legacy custom tactile/COCO model path pair를 확인한다. 이 health API는 Ultralytics/Pillow/model weight를 실제로 로드하지 않는다.

응답 예:

```json
{
  "schema_version": "detect.v2",
  "mode": "fake",
  "status": "ready",
  "reason": null,
  "runtime_primary_model": "unified_walksafe",
  "runtime_fallback_model": "legacy_two_model",
  "configured_runtime": null,
  "custom_tactile_model_path": null,
  "coco_model_path": null,
  "unified_model_path": null,
  "runtime_config_path": null
}
```

`yolo`/`real` mode에서 model path가 없으면 `status: "unavailable"`, `reason: "detect_v2_model_not_configured:..."`가 될 수 있다.

## POST /detect/v2

v2 서버 탐지 API. 기본은 fake provider이며, `DETECT_V2_MODE=yolo` 또는 `real`에서 model path가 설정되어 있으면 `LazyYoloDetectV2Runtime`/`YoloDetectV2Provider`를 사용한다. `DETECT_V2_UNIFIED_MODEL_PATH`가 있으면 `unified_walksafe` 단일 모델을 우선 사용하고, 없으면 legacy custom tactile + COCO pair로 fallback한다. 실제 Ultralytics/Pillow/model load는 첫 실제 `/detect/v2` 요청 시점까지 지연된다.

요청:

| part | 내용 |
| --- | --- |
| `context` | `DetectContext` JSON 문자열 |
| `image` | 카메라 프레임 이미지 |

응답 예:

```json
{
  "schema_version": "detect.v2",
  "detections": [
    {
      "schema_version": "detect.v2",
      "model_key": "unified_walksafe",
      "source_model": "walksafe_unified_yolo26n",
      "model_class_id": 8,
      "class_name": "damaged_tactile_block",
      "category": "tactile_damage",
      "confidence": 0.91,
      "bbox": {"x": 0.2, "y": 0.35, "width": 0.4, "height": 0.22},
      "threshold_used": 0.6,
      "captured_at": "2026-05-12T12:00:00Z",
      "gps": null,
      "heading": null
    }
  ]
}
```

주요 필드:

| 필드 | 의미 |
| --- | --- |
| `model_key` | primary `unified_walksafe`; fallback/legacy에서는 `custom_tactile` 또는 `coco_general` |
| `model_class_id` | 해당 `model_key` 안에서만 의미 있는 class id. `unified_walksafe`는 13-class order 기준 |
| `class_name` | v2 class name 문자열 |
| `category` | runtime config가 정한 `tactile_damage`, `vehicle`, `path_guidance` 등 용도 분류 |
| `threshold_used` | runtime filter에 사용된 threshold |
| `distance_m` | 선택 거리값. `0 <= distance_m <= 50`일 때만 유효 |
| `distance_source` | `sensor_depth`, `manual_fixture`, `model_estimate`, `unknown` |
| `distance_confidence` | 거리값 신뢰도 `0..1`. 프론트는 source/confidence가 없으면 보폭 문구를 제외 |

`yolo`/`real` mode에서 provider를 만들 수 없으면 `503 detect_v2_unavailable`을 반환한다. inference는 별도 worker에서 실행되고 queue/startup/request timeout을 넘기면 worker를 종료·재생성한다. client 응답에는 내부 경로나 원 예외 문자열 대신 제한된 오류 code/type만 반환한다.

## GET /navigation/destinations/search/health

목적지 검색 provider 상태를 확인한다. 실제 TMAP 호출 없이 key 설정 또는 mock mode만 확인한다.

```json
{
  "provider": "tmap_poi",
  "mode": "mock",
  "status": "ready",
  "reason": null,
  "poi_search_url": null
}
```

## GET /navigation/destinations/search

장소 이름을 목적지 후보로 정규화한다. `TMAP_POI_PROVIDER=mock`에서는 로컬 fixture/alias만 사용해 외부 호출을 하지 않는다. provider 응답 안에 동일한 비어 있지 않은 후보 ID가 둘 이상이면 client가 잘못된 장소를 선택하지 않도록 전체 응답을 거부한다.

Query:

| query | 의미 |
| --- | --- |
| `query` | 장소명. “서울역으로 안내해줘” 같은 조사/명령 suffix는 서버에서 보수적으로 정규화 |
| `limit` | 1~10, 기본 5 |
| `origin_lat`, `origin_lng` | 선택. mock 후보 거리 정렬 또는 live 검색 중심점에 사용 |

응답 후보:

```json
{
  "schema_version": "walksafe.destination_search.v1",
  "provider": "tmap_poi",
  "query": "서울역",
  "results": [
    {
      "id": "mock-seoul-station",
      "name": "서울역",
      "point": {"latitude": 37.5546788, "longitude": 126.9706069, "name": "서울역"},
      "address": "서울 중구 봉래동2가",
      "road_address": "서울 중구 한강대로 405",
      "category": "교통",
      "result_type": "alias",
      "distance_m": 1250
    }
  ]
}
```

Android client는 response `query`가 whitespace-normalized 요청 query와 같고 결과가 요청 `limit` 이내인지, ID 고유성, `result_type`(`poi`, `address`, `alias`)과 모든 좌표/필드를 확인한다. 후보 하나라도 malformed면 일부만 사용하지 않고 응답 전체를 거부한다.

## GET /navigation/walking/health

현재 TMAP 보행 route 설정 상태를 확인한다. 실제 TMAP API를 호출하지 않고 key 설정 여부만 확인한다. 제품 provider는 `tmap_pedestrian` 고정이다.

응답 예:

```json
{
  "provider": "tmap_pedestrian",
  "status": "unavailable",
  "reason": "tmap_app_key_missing",
  "walking_directions_url": "https://apis.openapi.sk.com/tmap/routes/pedestrian"
}
```

## POST /navigation/walking

TMAP 보행 경로를 WalkSafe 앱 전용 schema로 정규화한다. TMAP key는 백엔드 env에만 두고, 프론트는 이 endpoint만 호출한다. 현재 제품·운영 계약은 `tmap_pedestrian` 고정이며 다른 provider로 전환하지 않는다.

요청:

```json
{
  "origin": {"latitude": 37.39472714688412, "longitude": 127.11015314141542},
  "destination": {"latitude": 37.401937080111644, "longitude": 127.10824367964793, "name": "테스트 목적지"},
  "waypoints": [],
  "priority": "STAIR_AVOID",
  "radius_m": 5000
}
```

`priority`는 `RECOMMEND`, `MAIN_STREET`, `DISTANCE`, `STAIR_AVOID`를 지원한다. TMAP 기본 요청은 시각장애인 보행 안전을 우선해 `STAIR_AVOID`를 사용한다. 응답의 `distance_m`/`duration_s`는 앱 내부 안내 계산 입력이며, 사용자 TTS는 거리(m) 단독 표현보다 시간/보폭 기반 표현을 우선한다. 예: “10초 뒤 좌회전 준비”, “약 15보 앞”, “지금 좌회전하세요”. 보폭 기본값은 개인화 전 임시값이고, GPS/센서 기반 속도 추정에는 오차가 있으므로 보폭 안내는 근사 표현으로 다룬다.

응답:

```json
{
  "schema_version": "walksafe.walking_route.v1",
  "provider": "tmap_pedestrian",
  "provider_route_id": null,
  "priority": "STAIR_AVOID",
  "summary": {"distance_m": 1281, "duration_s": 1220},
  "polyline": [{"latitude": 37.39472714688412, "longitude": 127.11015314141542}],
  "steps": [
    {
      "index": 0,
      "distance_m": 20,
      "duration_s": 18,
      "instruction": "테스트길, 20m",
      "road_name": "테스트길",
      "turn_type": null,
      "facility_type": 0,
      "points": [{"latitude": 37.39472714688412, "longitude": 127.11015314141542}]
    }
  ],
  "guide_points": [
    {
      "index": 0,
      "point": {"latitude": 37.398, "longitude": 127.109},
      "instruction": "좌회전",
      "turn_type": 13,
      "point_type": "GP",
      "distance_from_start_m": 20,
      "remaining_distance_m": 1261,
      "bearing_deg": 12.4
    }
  ],
  "provider_result_code": 0,
  "provider_result_message": "OK"
}
```

`guide_points`는 TMAP Point feature의 안내 지점이다. `bearing_deg`는 route polyline의 다음 segment 방향에서 계산한 0 이상 360 미만의 보조 heading이다. 프론트는 `distance_from_start_m`/`remaining_distance_m`와 현재 진행 거리, 보행 속도/보폭 추정값을 비교해 “10초 뒤 좌회전 준비”, “약 15보 앞”, “지금 좌회전하세요” 같은 안내를 만들 수 있다.

Android client는 위 schema와 provider/result/`STAIR_AVOID` 외에도 distance·duration이 양수인지, 요청 origin/destination과 polyline 양끝이 각각 100m 이내인지, polyline geometry와 summary distance의 비율이 허용 범위인지, guide index·route 투영·선언 거리가 진행 순서대로 단조인지 확인한다. 한 항목이라도 실패하면 부분 geometry/guide를 사용하지 않고 응답 전체를 거부한다.

오류:

| HTTP | `detail.code` | 의미 |
| ---: | --- | --- |
| 503 | `tmap_app_key_missing` | 백엔드에 TMAP appKey 없음 |
| 502 | `tmap_invalid_api_key` | TMAP appKey가 잘못됐거나 보행자 경로안내 상품 권한 없음 |
| 504 | `tmap_timeout` | TMAP provider timeout |
| 502 | `tmap_provider_error` | TMAP provider HTTP 오류 |
| 422 | `route_unavailable` | provider가 경로 없음/실패 결과 반환 |
| 422 | `route_priority_unsupported` | TMAP에서 지원하지 않는 요청 priority |

## POST /reports

탐지 이벤트와 이미지를 신고로 저장한다.

요청:

| part | 내용 |
| --- | --- |
| `metadata` | `DetectionEvent` JSON 문자열 |
| `image` | 신고 이미지 |

`metadata` 예:

```json
{
  "class_id": 0,
  "class_name": "damaged_tactile_block",
  "confidence": 0.91,
  "bbox": {
    "x": 0.2,
    "y": 0.35,
    "width": 0.4,
    "height": 0.22
  },
  "captured_at": "2026-05-12T12:00:00Z",
  "source": "fake",
  "gps": {
    "latitude": 37.5665,
    "longitude": 126.978,
    "accuracy_m": 9.5
  },
  "heading": 181
}
```

응답은 `ReportResponse`이며 주요 필드는 다음과 같다.

| 필드 | 의미 |
| --- | --- |
| `id` | 신고 UUID |
| `status` | `new`, `reviewed`, `resolved` |
| `image_path` | 정적 이미지 경로 |
| `location_quality` | `missing`, `low`, `medium`, `high` |
| `review_flags` | 검토 필요 사유 목록 |
| `duplicate_report_ids` | 저장 직전 발견한 중복 후보 ID |


## POST /reports/v2

v2 탐지 metadata와 이미지를 신고로 저장한다. 저장 대상은 `unified_walksafe` 또는 legacy `custom_tactile`의 손상 점자블록(`damaged_tactile_block`)이다. 손상 점자블록은 자동 신고만 수행할 때 사용자 TTS 기본값이 없다. 손상 영역 bbox(`tactile_damage_area`)와 일반 객체(`coco_general`, unified 일반 객체 등)는 시설물 신고 대상이 아니라 보조 정보/사용자 위험 경고 입력이므로 저장을 거부한다. 사용자 위험 경고는 장애물, 보행자에게 접근하는 객체, 경로 차단 중심이며, `낙상 위험`은 현재 MVP에서 별도 경고 카테고리로 쓰지 않는다.

요청 metadata는 `/detect/v2` detection 필드에 다음 필드를 추가한다.

| 필드 | 의미 |
| --- | --- |
| `trigger` | `auto` 또는 `voice` |
| `auto_reported` | 자동 신고 여부 boolean |
| `source` | optional `android`. Android native upload source를 backend/admin/export에서 분리 |
| `reporter_user_id` | optional client metadata. 현재 서버 인증 subject가 아님 |
| `coordinate_gate_status` | optional string. Android bbox/depth gate 상태 |

현재 허용 저장 대상:

| model_key | class_name |
| --- | --- |
| `unified_walksafe` | `damaged_tactile_block` |
| `custom_tactile` | `damaged_tactile_block` |

표에 없는 model/class 조합은 `422`로 거부된다. v2 손상 점자블록 신고는 GPS가 있어야 저장된다. 명시 allowlist 밖의 추가 metadata 필드는 오류 없이 버리며 저장하지 않는다. `source=android`, `reporter_user_id`, gate 값은 현재 client가 보낸 값이지 인증된 서버 판정이 아니다.

저장 시 서버는 payload/image SHA-256과 `data_origin`, `performance_excluded`를 보강한다. fake/demo source이거나 `coordinate_gate_status`가 없거나 `pass`가 아니면 성능 근거에서 제외하지만, coordinate gate만으로 데이터를 fake/demo로 바꾸지는 않는다.

## GET /reports

신고 목록을 최신순으로 조회한다.

쿼리:

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `limit` | 아니오 | `1..100`, 기본 `25` |
| `status` | 아니오 | `new`, `reviewed`, `resolved` |
| `class_name` | 아니오 | v1 4개 탐지 클래스 또는 v2 class name 문자열 |
| `source` | 아니오 | `fake`, `onnx`, `server`, `android` |
| `demo_filter` | 아니오 | `all` 기본, `only_fake`, `exclude_fake` |
| `model_key` | 아니오 | v2 metadata `model_key` |
| `trigger` | 아니오 | v2 metadata `trigger` (`auto`, `voice`) |
| `auto_reported` | 아니오 | v2 metadata `auto_reported` boolean |
| `created_from` | 아니오 | 생성 시각 시작 |
| `created_to` | 아니오 | 생성 시각 끝 |
| `lat` | 반경 검색 시 필요 | 위도 |
| `lng` | 반경 검색 시 필요 | 경도 |
| `radius_m` | 반경 검색 시 필요 | 검색 반경 미터 |

`lat`, `lng`, `radius_m`는 셋을 함께 보내야 한다.


## GET /reports/summary

`GET /reports`와 같은 필터 조건으로 전체 신고 수, 상태/source count, 위치 있음/없음, grid cluster 요약을 반환한다. Admin 목록 limit와 별개로 현재 필터 전체를 요약하되 최대 10,000행까지만 허용한다. 10,000행을 넘으면 잘라서 왜곡하지 않고 `413 report_summary_too_large`와 `max_rows=10000`을 반환한다.

주요 query는 `GET /reports`와 동일하며 `source=android`, `model_key`, `trigger`, `auto_reported`, `demo_filter`를 함께 사용할 수 있다. `grid_size_degrees`와 `top_limit`으로 표시용 grid 크기와 반환 cluster 수를 조절한다.


## GET /reports/export

신고 목록을 최대 10,000행까지 내보낸다. 초과하면 `413`으로 필터 축소를 요구한다. 기본은 CSV이고 `format=json`, `format=geojson`을 지원한다. 필터는 `GET /reports`와 같은 v2 metadata 필터(`model_key`, `trigger`, `auto_reported`) 및 위치/시간 필터를 재사용한다. GeoJSON은 위치가 있는 신고를 Point Feature로 내보내며, 위치가 없는 신고는 `geometry: null`로 둔다. 실제 기관 확인용 `agency` profile은 검수된 손상 위치의 정확 좌표를 유지하지만, append-only export audit에는 원 query의 정확 `lat`/`lng` 대신 반경 사용 여부 같은 비식별 filter 요약만 남긴다.

쿼리:

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `format` | 아니오 | `csv` 기본, `json`, `geojson` 지원 |
| `status` | 아니오 | `new`, `reviewed`, `resolved` |
| `class_name` | 아니오 | v1/v2 class name 문자열 |
| `source` | 아니오 | `fake`, `onnx`, `server`, `android` |
| `demo_filter` | 아니오 | `all` 기본, `only_fake`, `exclude_fake` |
| `model_key` | 아니오 | v2 metadata `model_key` |
| `trigger` | 아니오 | v2 metadata `trigger` |
| `auto_reported` | 아니오 | v2 metadata `auto_reported` boolean |
| `created_from`, `created_to` | 아니오 | 생성 시각 범위 |
| `lat`, `lng`, `radius_m` | 반경 검색 시 필요 | 셋을 함께 보내야 함 |
| `redacted` | 아니오 | `true`면 좌표 rounding, `image_path` 제거 |
| `bom` | 아니오 | CSV UTF-8 BOM |
| `manifest` | 아니오 | JSON export manifest wrapper |
| `aggregate` | 아니오 | `format=geojson`에서 `grid` 지원 |

Export 응답은 `Cache-Control: no-store`, `X-WalkSafe-Demo-Filter`, demo-aware `Content-Disposition` filename을 포함한다.

CSV 컬럼:

```text
id,status,class_name,confidence,source,latitude,longitude,accuracy_m,heading,bbox_x,bbox_y,bbox_width,bbox_height,captured_at,created_at,model_key,source_model,threshold_used,trigger,auto_reported,reporter_user_id,distance_m,trace_id,payload_sha256,image_sha256,data_origin,runtime_mode,performance_excluded,performance_exclusion_reason,location_quality,review_flags,duplicate_report_ids,status_history_count,review_note,resolution_reason,image_path
```

## GET /reports/duplicate-check

중복 신고 후보를 조회한다.

쿼리:

| 이름 | 기본값 | 설명 |
| --- | ---: | --- |
| `class_name` | 필수 | 탐지 클래스 |
| `captured_at` | 필수 | 탐지 시각 |
| `lat` | 필수 | 위도 |
| `lng` | 필수 | 경도 |
| `radius_m` | `10` | 같은 위치로 볼 반경 |
| `minutes` | `1` | `captured_at` 전후 시간 범위 |

응답:

```json
{
  "duplicate_report_ids": ["..."],
  "duplicate_count": 1
}
```

## GET /reports/{report_id}

신고 상세 조회.

오류:

| HTTP | 의미 |
| ---: | --- |
| 404 | 신고 없음 |

## PATCH /reports/{report_id}/status

신고 상태 변경.

요청:

```json
{
  "status": "reviewed"
}
```

허용 상태:

```text
new, reviewed, resolved
```

`note`, `resolution_reason`, `expected_updated_at`을 선택적으로 보낼 수 있다. 상태 변경 이력은 report metadata의 최근 20건까지 저장하며 `expected_updated_at` 충돌은 `409`를 반환한다.

- `new` -> `new`, `reviewed`, `resolved`
- `reviewed` -> `new`, `reviewed`, `resolved`
- `resolved` -> `reviewed`, `resolved`

## Android debug endpoints

아래 endpoint는 `ANDROID_DEBUG_LOG_ENABLED=true`일 때만 local/dev evidence 수집용으로 사용한다.

| Method | Path | 역할 |
|---|---|---|
| `POST` | `/android/debug/depth-logs` | 검증된 depth/detection metadata를 JSONL로 추가 |
| `GET` | `/android/debug/depth-logs/recent` | 최근 depth log 조회 |
| `POST` | `/android/debug/frame-captures` | image와 JSON metadata를 debug 폴더에 저장 |
| `GET` | `/android/debug/frame-captures/recent` | 최근 frame capture record 조회 |

depth log schema는 raw RGB/depth media 참조 같은 미허용 필드를 거부한다. JSONL·image 파일은 `0600`, 저장 디렉터리는 `0700`으로 만들고 symlink/경로 이탈을 거부한다. frame capture는 실제 이미지를 저장하므로 운영 telemetry나 신고 evidence로 자동 승격하지 않는다. field/staging/production에서는 `ANDROID_DEBUG_LOG_ENABLED=true` 자체를 시작 단계에서 거부한다.
