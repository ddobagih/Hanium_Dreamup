# Backend Tests

백엔드 테스트는 실행 조건이 다른 두 범주로 나뉜다.

## 순수 계약 테스트

탐지 adapter, 업로드 검증, TMAP navigation 정규화, 신고 정책, Android debug API와 관리자 보안 request/service 계약을 fake 객체 또는 임시 디렉터리로 검증한다. `test_admin_security.py`, `test_admin_device_proof.py`, `test_openapi_contract.py`는 singleton·custody reset, 대상/현재 분실 기기 폐기, 고위험 gate, device-proof와 생성 OpenAPI 경계를 확인하지만 실제 기기·운영 DB·복구훈련 증거는 만들지 않는다.

## PostGIS 통합 테스트

`test_reports.py`, `test_reports_v2.py`와 관리자 보안 Postgres 경로는 `WALKSAFE_TEST_DATABASE_URL`로 지정한 전용 PostGIS에 연결하고 Alembic migration을 적용한다. `test_actor_rate_limit_store.py`는 기존 rate-limit group과 `privacy`를 같은 PostgreSQL CHECK 안에서 허용하고, privacy actor의 12회 요청 뒤 13회째만 제한하는지 확인한다. 관리자 통합 경로는 recovery에서 대상 기기의 기존 active 신뢰 키 하나만 보존하고 다른 session/key와 custody를 초기화하는지, 보존 키의 proof 없이 완료할 수 없는지, 대상 device key/session 폐기와 audit 저장, 재확인 동결을 실제 transaction으로 확인한다. custody DB 회귀는 runtime의 직접 INSERT·UPDATE와 같은 transaction에 위조 audit을 함께 넣은 UPDATE도 거부하고, 제한된 SECURITY DEFINER 함수가 만든 exact marker와 service audit이 함께 있을 때만 허용되는지 확인한다. URL은 `postgresql+psycopg`를 사용하고 데이터베이스 이름에 `test`가 포함되어야 한다. 현행 계층 runner는 DB preflight를 먼저 실행하므로 DB가 없거나 접속할 수 없으면 skip해 PASS로 만들지 않고 실패한다.

관리자 Postgres 회귀는 여러 control 행이 가능했던 predecessor에서 0·1행 upgrade 성공과 2행 fail-fast rollback을 확인하고, 기존 한 행의 issuer 지문이 미결속으로 시작하는지, owner 최초 결속·같은 키 멱등·다른 키 및 runtime 역할 거부가 지켜지는지 검사한다. 실제 TOTP+틀린 issuer와 틀린 TOTP+실제 issuer를 각각 넣은 직접 session mint가 모두 `42501`로 거부되고 row를 남기지 않는지도 확인한다. runtime DB role의 unaudited custody 직접 update와 trigger 비활성화 시도도 거부한다. `test_admin_runtime_acl_hardening.py`는 후속 head에서 device-proof challenge의 runtime UPDATE가 `consumed_at`으로만 제한되고, nonce·binding 변경과 unconsume이 trigger/ACL 양쪽에서 거부되는지 확인한다. startup recovery candidate는 exact issuer·public/private 현재 지문·단일 active transaction·pending token/expiry에만 결속되며 일반 관리자 작업에는 사용되지 않는다. 이 경로는 `WALKSAFE_TEST_DATABASE_URL`이 있을 때만 실행된다. Alembic offline `--sql`, custody 시각·참조와 audit의 완전한 상관관계는 아직 검증하지 않으므로 PASS 범위에 포함하지 않는다.

```bash
docker compose up -d db
export PYTHON_BIN="$PWD/.venv-tests/bin/python"
test -x "$PYTHON_BIN"
DATABASE_URL="$WALKSAFE_TEST_DATABASE_URL" "$PYTHON_BIN" -m alembic -c backend/alembic.ini upgrade head
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. WALKSAFE_TEST_DATABASE_URL="$WALKSAFE_TEST_DATABASE_URL" \
  "$PYTHON_BIN" -m pytest -p no:cacheprovider \
  backend/tests/test_admin_security.py \
  backend/tests/test_admin_device_proof.py \
  backend/tests/test_openapi_contract.py -q
```

테스트 업로드는 세션마다 생성되는 임시 디렉터리를 사용한다. 실제 운영 upload 디렉터리와 혼용하지 않는다.

이 내부 결과는 실제 off-phone 복구자료 custody, 관리자 휴대전화 분실·새 기기 복구훈련, 운영 provisioning·서명·사설 배포, 외부 검토 또는 279개 정식 시험을 실행한 결과가 아니다. 해당 항목과 5개 release gate는 모두 `NOT_RUN`(미면제), 출시는 `NOT_ELIGIBLE`로 유지한다.
