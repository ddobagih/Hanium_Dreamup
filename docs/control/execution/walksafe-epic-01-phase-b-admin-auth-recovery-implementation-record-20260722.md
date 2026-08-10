# EPIC-01 Phase B 관리자 인증·복구 내부 구현 기록

- 문서 ID: `WS-EPIC-01-PHASE-B-ADMIN-AUTH-RECOVERY-IMPLEMENTATION-20260722-001`
- 버전: `0.1.0`
- 상태: `INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS`
- EPIC: `EPIC-01 IN_PROGRESS`

## 이번에 내부 구현한 것

- **PASSWORD_TOTP_AND_REPLAY_REJECTION** — 개별 관리자 비밀번호의 scrypt 저장과 TOTP 추가 본인확인, 성공한 같은 시각구간 또는 과거 TOTP 재사용 거부를 서버 권한으로 구현했다.
- **SERVER_AUTHORITATIVE_DEVICE_BOUND_SESSIONS** — 불투명 세션 원문은 응답 시점에만 만들고 서버에는 SHA-256 지문만 저장하며, 관리자 앱 종류·역할·대상 API·기기 ID를 함께 검사하고 세션 조회·개별 폐기를 제공한다.
- **FAIL_CLOSED_RECOVERY_STATE** — 복구 시작 시 기존 세션을 폐기하고 고위험 작업을 동결한다. 복구코드는 성공적으로 완료될 때만 사용 처리하며, 응답 유실 때 같은 미사용 코드·같은 기기에서 만료를 늘리지 않고 복구 증명값을 회전해 재개한다.
- **HIGH_RISK_OPERATION_GUARD** — 복구 중이거나 최근 비밀번호·TOTP 재확인이 없으면 고위험 작업을 실패 닫힘으로 차단한다. API는 실제 변경 transaction에서 다시 검사한다. 오프라인 삭제는 DB 연결이 정상 유지되는 동안 관리자 control transaction 잠금을 유지하고 성공 시 관리자·세션 결속 감사기록을 함께 commit한다.
- **ADMIN_ANDROID_SECURITY_ONLY_BOUNDARY** — 관리자 Android 앱은 보안 제어 화면만 열고 업무 기능은 잠근다. 세션·복구 증명값은 프로세스 메모리에만 두며 release 빌드는 HTTPS 관리자 API 주소가 없으면 생성하지 않는다.

## 내부 검증 경계

- `PASS_INTERNAL` Android 관리자 앱 단위·조립·정적검사: `cd apps/android && ./gradlew :adminapp:testDebugUnitTest :adminapp:assembleDebug :adminapp:lintDebug --offline --no-daemon`
- `PASS_INTERNAL` 격리 PostgreSQL을 포함한 백엔드 전체 회귀: `WALKSAFE_TEST_DATABASE_URL=<isolated-db> python -m pytest backend/tests -q`
- `PASS_INTERNAL` 격리 PostgreSQL 관리자 전체 흐름: `WALKSAFE_TEST_DATABASE_URL=<isolated-db> python -m pytest backend/tests/test_admin_security.py -q`
- `PASS_INTERNAL` 고위험 자료삭제 잠금·감사 결속과 Android 제품경계: `python -m pytest tests/test_walksafe_admin_high_risk_data_delete_gate.py tests/test_walksafe_backup_prune.py tests/test_walksafe_android_product_boundary.py -q`
- `PASS_INTERNAL_UNSIGNED_APK_ONLY` 관리자 release API origin 양·음성 gate: `WALKSAFE_ADMIN_API_ORIGIN=https://<approved-origin> ./gradlew :adminapp:assembleRelease --offline --no-daemon; unset origin validation must fail`

위 결과는 내부 코드·구성요소 회귀다. 정식 시험, 실제 휴대전화 분실 복구훈련, 실기기 서명·배포 또는 출시 증거가 아니다.

## 아직 남은 것

- `ADMIN-PRODUCTION-PROVISIONING` — OPEN: 실제 운영 관리자 계정·TOTP 비밀·고엔트로피 복구코드를 비밀관리 절차로 만들고 배포환경에 주입하지 않았다.
- `ADMIN-OFF-PHONE-ENCRYPTED-CUSTODY` — OPEN: 관리자 휴대전화와 분리된 암호화 복구자료·서버키·서명키 백업을 실제로 만들고 복원 확인하지 않았다.
- `GATE-SINGLE-ADMIN-RECOVERY-DRILL` — NOT_RUN: 실제 휴대전화 분실을 가정한 외부 복구수단·세션폐기·고위험 동결·복구훈련을 실행하지 않았다.
- `ADMIN-SIGNING-DISTRIBUTION-DEVICE` — DEFERRED: 관리자 앱 실제 서명·비공개 배포·등록 실기기 검증은 후속 EPIC과 정식 시험에 남아 있다.
- `ADMIN-OFFLINE-DELETE-FENCING` — OPEN: 장시간 오프라인 삭제 중 DB 연결·transaction 잠금이 끊기면 이후 삭제를 즉시 중단시키는 operation lease·fencing과 항목별 재검증이 아직 없다.
- `ADMIN-OFFLINE-DELETE-DURABLE-JOURNAL` — OPEN: 현장 로그·백업 삭제가 일부 진행된 뒤 실패해도 독립 STARTED·항목별 진행·FAILED 기록이 남는 durable journal을 아직 강제하지 않는다.
- `ADMIN-RETENTION-CREDENTIAL-HANDOFF` — OPEN: 정기 보존삭제에 최근 관리자 재확인 세션을 파일에 저장하지 않고 일회성으로 인계하는 절차와 최소권한 DB role 계약이 아직 없다.
- `ADMIN-DB-ROLE-SEPARATION` — OPEN: migration owner와 runtime·retention DB role을 분리하고 DDL 권한을 회수하지 않아 DB owner가 append-only 감사 trigger를 변경할 수 있는 잔여 위험이 있다.
- `EPIC-01-REMAINING-PHASES` — OPEN: 실행 중 미터 거리 preflight, Legacy Web 전체 기술 폐쇄, Android API gateway 추출과 나머지 목적 표면 정합화가 남아 있다.

정식 시험 **279/279는 NOT_RUN**, 5개 gate는 **NOT_RUN·미면제**, 출시는 **NOT_ELIGIBLE**이다.

## 다음 한 가지 작업

EPIC-01에서 실행 중 실제 미터 거리 frame과 승인된 지정 기기 프로필을 함께 검사하는 runtime metric preflight를 구현한다.

내용 지문: `de806b1f9ecd5d7ce5b86f781a6caf70a61b1a142682ff94fbac302d98929e87`
