# Backend API Routers

이 폴더는 HTTP transport 계층을 담당한다. 요청 parsing, dependency 연결, status code와 오류 payload 변환까지만 처리하고 탐지·중복·보안·provider 규칙은 `../services/`에 둔다. `backend/app/main.py`가 모든 router를 조립하고 OpenAPI 계약을 설치한다.

| 모듈 | endpoint 영역 |
|---|---|
| `health.py` | `/health`, `/ready`와 DB·모델·외부 의존성, 관리자 TOTP·private issuer-key DB 결속 readiness |
| `detect.py` | v1/v2 이미지 탐지 |
| `navigation.py` | 목적지 검색과 보행 경로 |
| `privacy.py` | 동의 event와 계정 삭제 v2 backend lifecycle |
| `reports.py` | 신고 생성·조회·요약·export, 검토 결정과 수동 전달 이력 |
| `uploads.py` | 목적 제한 grant를 사용한 암호화 신고 원본 조회 |
| `android_debug.py` | local/dev Android depth log와 frame capture |
| `admin_security.py` | 관리자 로그인, session, TOTP 재확인·복구, off-phone custody 확인, 분실 기기 폐기와 device-proof challenge |

## 요청 경계

API router 앞의 `FieldTestSecurityMiddleware`가 field와 admin 역할을 분리한다. actor가 필요한 작업은 Gateway가 서명한 짧은 수명의 actor assertion도 검증한다. 알 수 없는 신규 route는 보안 기능이 켜진 환경에서 admin 전용으로 fail-closed 된다. 개인정보 경로에는 body limit, no-store와 privacy 오류 변환 middleware도 적용된다.

custody 확인과 분실 기기 신고는 bearer·관리자 앱 종류·역할·audience·현재 기기 ID에 더해 요청 원문에 결속된 `ACTION` device proof를 요구한다. custody request schema는 opaque 참조를 Base64url 43자로 제한하고 service가 decode·re-encode해 canonical 32바이트인지 추가 검증한다. 요청은 `OFF_PHONE`과 별도 암호화 백업 확인만 받고, 분실 신고는 현재 호출 기기가 아닌 대상 기기 ID와 빈 JSON 본문만 받는다. 상태 응답은 정확히 `security_state`, 문자열 `state_version`, `observed_at`, `recovery_custody_state`, nullable timezone-aware `recovery_custody_attested_at` 다섯 필드이며 custody 상태와 확인 시각이 일치해야 한다. Pydantic route source와 생성된 [`contracts/walksafe.openapi.json`](../../../contracts/walksafe.openapi.json)이 공개 계약이며 JSON은 직접 고치지 않는다. 이 내부 구현은 실제 off-phone 보관이나 분실 복구훈련을 완료했다는 증거가 아니다.

field/staging/production의 actor rate limit은 PostgreSQL transaction advisory lock과 짧은 수명의 event table을 사용해 worker·replica 사이에서 원자적으로 공유한다. DB accounting이 실패하면 해당 요청은 `503`으로 fail-closed 된다. `memory` 저장소는 development/test에서만 허용된다. 다중 replica 배포에서는 PostgreSQL뿐 아니라 `UPLOAD_DIR`도 모든 replica가 같은 내구성 저장소를 보도록 구성해야 한다. Android debug router는 `ANDROID_DEBUG_LOG_ENABLED=false`가 기본이다.

## 변경 시 확인

- route를 추가하거나 request/response schema를 바꾸면 `backend/app/openapi_contract.py`, 관련 Pydantic schema와 Gateway `openapi.json`의 공개 경계를 함께 확인한다.
- router 안에 provider 호출 형식 변환 이상의 정책을 새로 넣지 말고 재사용 규칙은 `../services/`에 둔다.
- field/admin 권한, actor assertion, no-store 또는 요청 크기 제한을 우회하는 별도 router를 만들지 않는다.
- readiness 응답은 실제 확인하지 않은 외부 서비스·기기·배포 상태를 PASS로 만들지 않는다.
- 관리자 보안이 켜져 있으면 readiness probe마다 issuer-key 파일을 다시 검증하고 private DB 지문과 비교한다. 파일·경로·키·DB URL은 응답이나 예외에 넣지 않으며 startup도 같은 불일치를 serving 전에 거부한다.

실제 기기·운영 DB·복구훈련·독립 검토·정식 시험과 release gate는 증거가 없으면 모두 `NOT_RUN`(미면제), 출시는 `NOT_ELIGIBLE`로 유지한다.

저장소 root에서 전용 test PostGIS를 지정해 관련 route와 계약 테스트를 실행한다.

```bash
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. WALKSAFE_TEST_DATABASE_URL="$WALKSAFE_TEST_DATABASE_URL" \
  python3 -m pytest -p no:cacheprovider \
  backend/tests/test_openapi_contract.py \
  backend/tests/test_field_test_security.py \
  backend/tests/test_navigation_routes.py \
  backend/tests/test_reports_v2.py -q
```

관리자·개인정보 route를 수정하면 `test_admin_security.py`, `test_admin_device_proof.py`, `test_admin_report_workflow.py`, `test_privacy_lifecycle.py`도 대상에 포함한다. 테스트 DB를 지정하지 않거나 연결할 수 없는 상태는 PASS가 아니다.
