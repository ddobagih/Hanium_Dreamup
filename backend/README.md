# Backend

Android 사용자 앱과 별도 Android 관리자 앱에 FastAPI 및 PostgreSQL/PostGIS 기반 보행 경로 조회, 신고 저장·검토·내보내기와 관리자 API를 제공한다. 현행 Android 위험 탐지는 앱 내 TFLite가 담당한다.

## 책임 경계

- `LEGACY_REFERENCE_ONLY`인 Web/PWA의 과거 `server-v2` 탐지는 `/detect/v2`를 사용했다. 현행 Android 사용자 앱의 탐지는 기기 내 TFLite에서 수행한다.
- `/detect/v2`의 기본 모드는 `fake`다. 실제 추론은 운영 환경에서 768 unified 모델·runtime config를 명시하며, `/ready`가 실제 warmup 추론과 13-class 순서를 검증한다.
- `/reports/v2`는 손상 점자블록 신고만 저장한다. 공공기관 자동 API 제출은 제공하지 않는다. 관리자는 별도 앱에서 검토 결정을 남기고 외부 기관에 수동 신고한 사실·접수번호·상태만 서버에 기록할 수 있다.
- 현장 gateway의 역할별 service token, actor assertion, rate limit은 임시 운영 경계다. 조직 IdP·중앙 RBAC·signed upload URL을 대신하지 않으므로 외부 공개 API로 간주하지 않는다.
- 관리자 Android 앱은 배포환경에서 `PASSWORD_TOTP` 방식만 사용한다. 정적 `WALKSAFE_ADMIN_TOKEN`은 `WALKSAFE_ADMIN_SECURITY_ENABLED=true`일 때 관리자 API를 우회할 수 없다.

## 관리자 보안 초기 등록과 복구

마이그레이션 후 신뢰할 수 있는 로컬 관리 절차에서 `backend.app.services.admin_security.provision_admin_security`를 한 번 호출해 관리자 비밀번호, TOTP seed, 생성된 고엔트로피 복구코드(각 24자 이상), 별도 issuer key를 함께 등록한다. PostgreSQL provisioning은 일반 runtime 역할이 아닌 DB owner 절차여야 하며 control과 issuer key SHA-256 지문을 같은 transaction에서 결속한다. `admin_security_controls.singleton_scope`의 check·unique 제약은 전체 데이터베이스를 최대 한 행으로 제한하고, provisioning과 보호 작업은 정확히 한 행이 아니면 fail-closed 한다. 이전 migration은 여러 행을 허용했으므로 upgrade 전에 기존 행 수가 0 또는 1인지 확인하고, 여러 행이면 운영자가 먼저 정리해야 한다. 데이터베이스에는 scrypt 인코딩 또는 SHA-256 지문만 남고 평문 비밀번호·TOTP·복구코드·세션 토큰·issuer key는 저장하지 않는다.

`POST /admin/security/recovery-custody/attest`는 휴대전화 밖 복구코드 또는 보안키와 별도 암호화 백업을 확인한 뒤 canonical unpadded Base64url 32바이트 opaque 참조의 SHA-256과 자료 종류·`OFF_PHONE`·별도 백업 확인·서버 시각만 저장한다. 원 복구자료·백업 비밀·opaque 참조 원문은 앱 저장소·DB·Git·감사로그에 넣지 않는다. 이 자기확인 상태는 `UNATTESTED` 또는 `ATTESTED`이며 실제 외부 보관, 새 기기 복구, 분실 복구훈련의 실행 증거를 대신하지 않는다.

`WALKSAFE_ADMIN_TOTP_SECRET`은 공백·padding 없는 canonical Base32이며 디코딩 결과가 최소 20바이트여야 한다. 로그인·재인증은 ±1 시간구간을 허용하되 이미 사용한 TOTP timecode를 다시 받지 않는다. 로그인, 재인증, 복구 시작, 복구 완료는 PostgreSQL의 출처별·관리자 전체 기록을 함께 기준으로 제한하므로 토큰·출처·프로세스·replica를 바꿔도 시도 횟수가 쉽게 초기화되지 않는다.

관리자 세션·재확인·복구 credential 발급에는 TOTP seed와 별개의 256-bit issuer key를 사용한다. 환경에는 값이 아니라 `WALKSAFE_ADMIN_CREDENTIAL_ISSUER_KEY_FILE`의 정규화된 절대경로만 두며, production 기본 경로는 `/etc/walksafe/admin-credential-issuer.key`다. 파일 내용은 정확히 32바이트를 나타내는 canonical unpadded Base64url 43자와 선택적 LF 한 개뿐이다. 배포 파일은 `root:walksafe-backend`, 단일 hard link, `0440`으로 두고 모든 상위 디렉터리를 root 소유·group/other 쓰기 불가로 유지한다. 이 키를 TOTP·복구자료·gateway session·privacy HMAC·보고서 이미지 키·서명키와 재사용하거나 일반 backend 환경파일에 넣지 않는다. 파일 부재·형식·권한·소유권·경로 identity가 맞지 않으면 발급 기능은 fail-closed하며 비밀값과 파일 경로를 로그나 오류에 넣지 않는다.

운영 배포에서는 API와 migration 권한을 합치지 않는다. API는 `walksafe-backend`와 `/etc/walksafe/backend-runtime.env`만 사용하고, Alembic은 별도 `walksafe-maintenance`와 `/etc/walksafe/backend-migration.env`만 사용한다. 두 환경파일은 `root:root` 소유, 단일 hard link, `0600`으로 두며 systemd만 읽어 각 프로세스 환경에 전달한다. runtime 파일에는 `WALKSAFE_MIGRATION_DATABASE_URL`을, migration 파일에는 API DB URL·TOTP·issuer·gateway·provider·image-key credential을 넣지 않는다. 각 unit은 상대 환경파일과 legacy `/etc/walksafe/backend.env`를 mount namespace에서 가린다.

기존 control 한 행이 있는 상태에서 이 migration을 적용하면 issuer 지문은 의도적으로 `NULL`이고 readiness와 모든 credential mutation은 중단된다. 운영 ingress를 닫은 maintenance window에서 runtime DB URL이 아닌 owner용 `WALKSAFE_MIGRATION_DATABASE_URL`로 한 번 결속한다. 전용 `walksafe-issuer-bind` 계정은 API와 동시에 실행되지 않으며, systemd credential로 복제된 issuer key와 migration 환경만 받는다. 원본 issuer 파일, runtime·migration 환경파일은 프로세스 namespace에서 모두 가린다. 같은 키 재실행은 멱등이고 다른 키 교체 및 runtime-role 실행은 거부된다. 명령 출력에는 키·파일 경로·DB URL이 포함되지 않는다.

`backend-migration.env`는 셸 문법 파일이 아니므로 `source`하거나 값을 명령줄로 복사하지 않는다. [`walksafe-backend.conf`](../deploy/sysusers.d/walksafe-backend.conf)를 설치하고 `systemd-sysusers`를 실행해 `walksafe-maintenance`와 `walksafe-issuer-bind`를 먼저 만든다. API용 `walksafe-backend`는 이 파일이 만들지 않으므로 별도로 존재해야 한다. systemd가 환경파일과 credential을 직접 전달하는 고정 oneshot을 아래 전환 절차의 migration 성공 뒤에만 수동 실행한다.

이 unit의 `Conflicts=`는 보조 안전장치일 뿐 ingress 차단과 API의 명시적 정지를 대신하지 않는다. unit을 enable하지 않고 전환할 때 한 번만 수동 실행한다. 성공 후 API를 명시적으로 다시 시작하며, 필요하면 journal에서 비밀값·경로·DB URL이 아닌 `BOUND` 상태만 확인한다. 결속 뒤에도 API startup과 credential 발급에 issuer 원본이 필요하므로 `/etc/walksafe/admin-credential-issuer.key`를 삭제하지 않는다.

`/ready`와 backend startup은 매번 파일을 다시 검증하고 private DB 지문과 대조한다. 정상 상태에서는 `admin_totp_binding=matched`만 허용한다. 단, `RECOVERY_IN_PROGRESS`이고 DB의 단일 active transaction·pending token·expiry·이전 TOTP 지문·issuer가 모두 정확히 결속된 경우에만 새 seed를 `admin_totp_binding=recovery_candidate`로 한 번 결속하고 startup을 허용한다. 후보가 한 번 결속된 뒤에는 이전 seed와 다른 후보 모두 startup에서 거부한다. 이 예외는 복구 완료용이며 로그인·세션·고위험 작업 등 일반 관리자 작업은 새 seed로 계속 fail-closed한다. 정상 요청 service는 인스턴스 안에서 한 번만 읽으며, 파일 부재·권한 오류·미결속·불일치 중 하나라도 있으면 credential 발급 및 복구 mutation을 수행하지 않는다.

기기 분실 복구 시에는 다음 순서를 지킨다.

1. 장치 증명이 켜져 있으면 복구 시작 전에 복구 대상 `device_id`에 정확히 하나의 기존 active 신뢰 기기키가 있어야 한다. 시작 시 그 키 하나만 보존하고 다른 active 기기키와 모든 기존 세션을 폐기하며, custody를 `UNATTESTED`로 초기화하고 상태를 `RECOVERY_IN_PROGRESS`로 바꾼다. 장치 증명이 꺼진 환경에서는 active 기기키를 모두 폐기한다.
2. 응답을 잃었으면 같은 복구코드와 같은 기기 ID로 다시 시작한다. 기존 만료시각은 유지되고 새 복구 토큰이 발급되며 이전 토큰은 무효가 된다.
3. 별도 비밀관리 경로에서 `WALKSAFE_ADMIN_TOTP_SECRET`을 새 seed로 교체하고 백엔드를 재시작한다. startup은 정확한 active recovery 결속을 확인한 뒤 그 seed를 recovery candidate로 고정하며, 다른 seed로 다시 바꾸면 거부한다. issuer key는 TOTP 복구와 별도 수명주기로 관리하며 복구 seed 대신 재사용하지 않는다.
4. 장치 증명이 켜져 있으면 시작 때 보존된 복구 대상 기기키로 request-bound proof를 만든다. 복구 중 로컬 프로비저닝은 active 복구 transaction의 같은 `device_id`만 허용하고 다른 기기키 등록은 거부한다.
5. 복구 대상 기기 proof, 새 비밀번호와 새 seed의 TOTP로 복구를 완료한다. 복구코드는 이 성공 시점에만 사용 처리된다.

복구 완료 API는 데이터베이스에 기록된 이전 seed 지문과 현재 환경변수의 지문이 다르지 않으면 거부한다. 따라서 환경변수 교체 없이 이전 TOTP를 재사용하는 복구는 불가능하다.

## 운영 권한 분리·계약 호환 전환

새 Android 관리자 앱은 구 backend의 기존 응답을 제한적으로 수용하지만, 구 APK는 새 backend의 확장 응답과 호환되지 않는다. 따라서 단일 관리자 소유의 active 관리자 기기를 먼저 전수 식별하고, **모든 기기의 APK를 새 후보로 갱신해 구 backend 연결까지 확인한 뒤에만** backend 전환을 시작한다. 갱신하지 못한 기기는 전환 전에 해당 session과 device key를 폐기한다. 새 migration 위의 구 backend와 구 APK↔신 backend 혼합 운영은 지원하지 않는다.

운영 전환은 같은 release candidate와 승인된 maintenance window에서 다음 순서로 수행한다.

1. 새 APK·backend·migration·설정의 후보 식별자와 DB backup·복원 절차를 확인한다. 새 APK가 설치된 모든 active 관리자 기기에서 구 backend 기본 흐름이 동작하는지 확인한다.
2. API용 `walksafe-backend` 계정이 별도로 존재하는지 확인한다. [`walksafe-backend.conf`](../deploy/sysusers.d/walksafe-backend.conf)를 `/etc/sysusers.d/`에 설치한 뒤 `systemd-sysusers`로 로그인 불가 계정 `walksafe-maintenance`와 `walksafe-issuer-bind`를 만든다. 세 계정은 서로 다른 UID·primary GID를 사용하고 다른 두 역할의 그룹에 가입시키지 않는다.
3. `/etc/walksafe/backend-runtime.env`와 `/etc/walksafe/backend-migration.env`를 각각 regular file, `root:root`, `0600`, hard-link count 1로 설치한다. 둘 다 symlink이면 안 된다. runtime 파일에는 migration URL을 넣지 않고 migration 파일에는 API DB·TOTP·issuer·gateway·provider·image-key credential을 넣지 않는다.
4. `/etc/walksafe/admin-credential-issuer.key`를 regular file, `root:walksafe-backend`, 정확히 `0440`, hard-link count 1로 설치한다. 파일과 모든 상위 경로는 symlink가 아니어야 하고, 상위 디렉터리는 root 소유이며 group/other 쓰기를 허용하지 않는다. `0640`을 포함한 다른 mode는 배포 loader가 거부한다.
5. 설치한 systemd unit의 실제 `FragmentPath`가 root 소유이고 group/other 쓰기 불가인지 확인하고 `systemctl daemon-reload`를 실행한다. Backend는 migration만 자동 요구하며 issuer bind를 자동 실행하지 않는다는 점을 확인한다.
6. 관리자 ingress에서 신규 요청을 차단하고 진행 중 요청을 배출한 다음 `walksafe-backend.service`를 명시적으로 정지한다. `walksafe-admin-issuer-bind.service`의 `Conflicts=`에 정지를 맡기지 않는다.
7. 과거 `/etc/walksafe/backend.env`를 API가 읽었던 배치라면 거기에 있던 migration credential을 재사용하지 않는다. API를 정지한 상태에서 기존 credential·세션을 폐기 또는 회전하고, 새 maintenance 전용 credential은 `backend-migration.env`에만 넣는다.
8. `walksafe-backend-migrate.service`를 수동 실행해 성공과 예상 Alembic head `202608250002`를 확인한다. migration 완료부터 issuer bind 성공 전까지 새 backend의 startup/readiness가 fail-closed하는 것이 정상이며 API를 시작하지 않는다. migration이 실패해도 API와 ingress를 정지한 채 원인을 해결하며 구 backend를 새 schema 위에서 시작하지 않는다.
9. `walksafe-admin-issuer-bind.service`를 `walksafe-issuer-bind` 비권한 계정으로 수동 실행해 `BOUND`를 확인한다. 이 oneshot은 enable하지 않는다. migration 성공만으로 bind가 실행됐다고 가정하지 않는다.
10. 결속에 사용한 migration credential을 다시 회전하거나 더 이상 필요 없으면 폐기하고 DB session을 무효화한다. 새 값은 계속 migration 환경에만 둔다. 기존 `/etc/walksafe/backend.env`의 정확한 대상·소유권·파일 종류를 확인한 뒤 삭제하며, 읽을 수 있는 백업이나 다른 서비스 환경으로 옮기지 않는다. 보존할 전환 증거에는 비밀값 대신 receipt와 지문만 남긴다.
11. 아래 점검에서 파일·계정 분리가 모두 확인된 뒤에만 `walksafe-backend.service`를 시작한다. issuer 원본은 API가 계속 사용하므로 유지한다.
12. startup 성공, `/ready` HTTP 200, `admin_credential_issuer_binding=matched`를 확인한다. 정상 전환이면 `admin_totp_binding=matched`여야 한다. 진행 중 복구 재시작이면 `recovery_candidate`는 복구 완료 요청에만 사용하고, 완료 후 다시 시작한 backend에서 `matched`로 바뀌었는지와 같은 후보의 state·session·복구·고위험 작업 smoke를 확인한다. 확인 후에만 관리자 ingress와 신규 session 발급을 재개한다.

수동 실행 순서는 다음과 같다. `walksafe-backend.service`의 시작은 migration을 요구하지만 bind를 대신하지 않으므로 `migrate → bind → backend`를 생략하거나 병렬 실행하지 않는다.

```bash
sudo systemctl stop walksafe-backend.service
sudo systemctl start walksafe-backend-migrate.service
sudo systemctl --no-pager --full status walksafe-backend-migrate.service
sudo systemctl start walksafe-admin-issuer-bind.service
sudo systemctl --no-pager --full status walksafe-admin-issuer-bind.service
sudo systemctl start walksafe-backend.service
```

민감 파일과 계정 분리는 비밀값을 출력하지 않는 다음 점검으로 확인한다. `stat` 결과는 두 env가 `root:root 600 1 regular file`, issuer가 `root:walksafe-backend 440 1 regular file`이어야 한다. `namei -l`에서는 issuer 경로의 모든 구성요소가 symlink가 아니고 모든 상위 디렉터리가 root 소유·group/other 쓰기 불가여야 한다. 각 `id` 결과는 세 서비스 계정의 UID·primary GID와 상호 그룹 membership이 분리돼 있음을 보여야 한다.

```bash
sudo test ! -L /etc/walksafe/backend-runtime.env
sudo test ! -L /etc/walksafe/backend-migration.env
sudo test ! -L /etc/walksafe/admin-credential-issuer.key
sudo stat -Lc '%U:%G %a %h %F %n' \
  /etc/walksafe/backend-runtime.env \
  /etc/walksafe/backend-migration.env \
  /etc/walksafe/admin-credential-issuer.key
sudo namei -l /etc/walksafe/admin-credential-issuer.key
id walksafe-backend
id walksafe-maintenance
id walksafe-issuer-bind
systemctl show -p FragmentPath \
  walksafe-backend.service \
  walksafe-backend-migrate.service \
  walksafe-admin-issuer-bind.service
```

롤백할 때도 migration credential을 runtime env, API 프로세스 또는 복원한 `/etc/walksafe/backend.env`에 노출하지 않는다. DB 호환성·downgrade가 별도로 입증된 경우에만 maintenance 계정과 migration env로 DB를 되돌리고, API는 runtime role과 runtime env만 사용한다. 호환성이 입증되지 않았거나 분리 권한으로 복귀할 수 없으면 ingress와 API를 정지한 fail-closed 상태를 유지한다. 구 APK를 새 backend에 다시 연결하는 app-only rollback도 금지한다. 이 절은 전환 절차이며 실제 배포·복구훈련을 수행했다는 증거가 아니다.

## 관리자 장치 증명과 신고 워크플로

관리자 앱은 AndroidKeyStore에 내보낼 수 없는 P-256 개인키를 만들고 공개 SPKI descriptor만 화면에 표시한다. 신뢰할 수 있는 로컬 운영 절차에서 descriptor의 `device_id`, `key_version`, `public_key_spki_base64url`을 다음 명령에 전달한다. 이 명령은 개인키나 네트워크 등록을 받지 않는다.

```bash
python scripts/provision_walksafe_admin_device_key.py \
  --admin-id admin-01 \
  --device-id '<registered-device-id>' \
  --key-version 1 \
  --expected-key-marker '<descriptor-key-marker>' \
  --public-key-spki-base64url '<canonical-unpadded-base64url-spki>'
```

명령은 SPKI에서 계산한 SHA-256 marker가 descriptor marker와 일치하는지 DB 연결 전에 검증한다. 일반 API runtime 계정이 아닌 통제된 로컬 운영/마이그레이션 계정으로 실행한다. DB ACL은 `walksafe_backend_runtime`의 `admin_device_keys` INSERT를 거부하고 UPDATE trigger는 기존 active 키의 revoke 전이만 허용하므로, 네트워크 runtime이 신뢰 키를 직접 생성·교체·재활성화할 수 없다.

로그인, 복구 완료, custody 확인, 분실 기기 신고, 신고 검토·전달 이력 API는 `POST /admin/security/device-proof/challenges`에서 120초 단일사용 challenge를 받은 뒤 같은 요청 바이트에 대한 ECDSA-SHA256 서명을 함께 보낸다. 보호 요청은 `X-WalkSafe-Device-Challenge-Id`, `X-WalkSafe-Device-Signature`, `X-WalkSafe-Correlation-Id`를 사용하며, 이력 GET은 정확한 `X-WalkSafe-Read-Purpose`도 요구한다. 서명은 HTTP method, path, canonical query, 원문 body SHA-256, 관리자·기기·세션과 목적에 결속된다.

`POST /admin/security/devices/{device_id}/report-lost`는 호출 중인 현재 기기를 대상으로 받지 않으며, 다른 대상 기기의 active 공개키와 모든 미폐기 세션을 같은 DB transaction에서 폐기하고 감사한다. 이후 그 키는 challenge 발급·검증과 로그인을 통과할 수 없다. 출시 승인·권한 변경·데이터 삭제의 정책 gate는 관리자 control이 `NORMAL`이고 custody가 `ATTESTED`이며 기기 결속 세션과 단일사용 재확인이 유효할 때만 통과한다. 이 세 경로 문자열은 offline 정책 결속자이며 공개 HTTP endpoint가 아니다. offline helper는 재확인 nonce와 감사를 caller mutation 전에 commit하므로 caller가 실패해도 nonce는 사용 처리된다.

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

현행 API schema는 실제 route·Pydantic source에서 생성한 [`contracts/walksafe.openapi.json`](../contracts/walksafe.openapi.json), 로컬 환경 절차는 [개발 환경 가이드](../docs/guides/development-environment-guide.md#python과-backend)를 기준으로 본다. [`backend/.env.example`](.env.example)은 development/test 편의용이며 production 설정으로 복사하지 않는다. 운영 환경은 `deploy/config/walksafe-backend.env.example`과 `walksafe-backend-migration.env.example`, 실제 `config.py`가 정본이고 deployment에서는 repository `backend/.env`를 읽지 않는다. [`docs/backend/api_reference.md`](../docs/backend/api_reference.md)와 [`docs/backend/backend_environment.md`](../docs/backend/backend_environment.md)는 FP-046 완료 근거에 bytes가 결속된 역사 설명이므로 오래된 인증·저장·실행 문구를 현행 계약으로 사용하지 않는다.

저장소 내부 구현·자동 테스트는 실제 off-phone custody, 관리자 기기 분실·새 기기 복구훈련, 운영 provisioning·서명·사설 배포, 독립 검토 또는 정식 시험을 실행한 증거가 아니다. 해당 항목과 release gate는 모두 `NOT_RUN`(미면제), 출시는 `NOT_ELIGIBLE`로 유지한다.

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
