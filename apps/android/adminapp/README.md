# WalkSafe Android 관리자 앱

사용자 보행 앱과 `applicationId`, 앱 저장소, 세션 namespace를 분리한 내부 관리자 앱이다. FP-008 범위는 신고 검토 결정과 이미 수동으로 수행한 기관 전달의 상태·접수번호를 기록하고 조회하는 기능까지다. 이메일·문자·기관 API 전송 기능은 없다. 관리자 전용 서명과 사설 배포는 아직 `NOT_RUN`이다.

## 장치 등록

첫 실행에서 AndroidKeyStore에 내보낼 수 없는 P-256 개인키를 만들고 화면에 공개키 descriptor를 표시한다. descriptor를 신뢰할 수 있는 로컬 운영자에게 전달하고, 운영자는 저장소 루트에서 다음 명령으로 공개키만 등록한다.

```bash
DATABASE_URL="$WALKSAFE_MIGRATION_DATABASE_URL" PYTHONPATH=. \
python scripts/provision_walksafe_admin_device_key.py \
  --admin-id admin-01 \
  --device-id '<descriptor device_id>' \
  --key-version 1 \
  --expected-key-marker '<descriptor key_marker>' \
  --public-key-spki-base64url '<descriptor public_key_spki_base64url>'
```

CLI는 공개키에서 계산한 SHA-256 marker가 descriptor의 `--expected-key-marker`와 정확히 일치하는지 DB write 전에 검증한다. 운영자는 일반 API runtime 계정이 아닌 통제된 로컬 운영/마이그레이션 계정으로 이 명령을 실행한다. 서버 DB는 runtime의 기기키 INSERT를 거부하고 기존 active 키의 revoke UPDATE만 허용한다.

등록 전 로그인·복구 완료·신고 운영 요청은 장치 증명 단계에서 거부된다. 키 marker나 버전이 달라졌다면 더 높은 버전으로 명시적으로 회전한다.

## 장치 증명과 운영 API

로그인·복구 완료·신고 운영 요청 전에는 `POST /admin/security/device-proof/challenges`에 exact13 요청을 보낸다. nullable 키도 생략하지 않고 JSON `null`로 포함하며, `X-WalkSafe-Correlation-Id`는 본문의 `correlation_id`와 같아야 한다. 서버의 exact19 응답은 canonical exact18 JSON 문자열인 `signing_payload`를 포함한다. 앱은 그 UTF-8 바이트를 AndroidKeyStore의 P-256 키로 ECDSA-SHA256 서명하고 ASN.1 DER 결과를 padding 없는 Base64url로 보낸다.

서명 대상 요청에는 `X-WalkSafe-Device-Challenge-Id`, `X-WalkSafe-Device-Signature`, `X-WalkSafe-Correlation-Id`를 각각 정확히 한 번 보낸다. 보호된 GET은 결속된 `X-WalkSafe-Read-Purpose`도 사용한다. Challenge API와 별도로 FP-008 화면이 사용하는 운영 API는 정확히 다음 네 개다.

- `POST /reports/{report_id}/review-decisions`
- `GET /reports/{report_id}/review-decisions`
- `POST /reports/{report_id}/deliveries`
- `GET /reports/{report_id}/deliveries`

검토 결정과 수동 전달 관찰은 append-only 이력이다. `deliveries` POST를 포함해 어느 API도 외부 기관으로 자료를 보내지 않는다.

## 단일 관리자 복구 통제

서버 보안상태의 `recovery_custody_state`가 `ATTESTED`로 확인되기 전에는 로그인과 별개로 고위험 작업과 관리자 운영 화면을 잠근다. 관리자가 화면에서 서버 키, 앱 서명 키, 관리자 복구자료를 서로 분리해 각각 암호화 백업했고 같은 저장공간이나 계정에 키를 함께 두지 않았으며, 관리자 복구자료가 실제 휴대전화 밖에 있음을 명시적으로 확인할 때만 `POST /admin/security/recovery-custody/attest`를 호출한다. wire의 `separate_encrypted_backup_confirmed=true`는 이 세 키의 개별 암호화 백업과 저장공간·계정 분리를 모두 직접 확인했다는 뜻이다. 앱은 요청마다 32-byte 난수로 만든 일회성 opaque reference만 전송하며 복구자료 원문, reference, 복구 토큰을 앱 저장소에 보존하지 않는다. 서버는 reference의 SHA-256만 보존한다.

이 화면의 확인 기록과 host/offline 테스트는 실제 운영 custody 보관, 백업 매체 확인 또는 분실 복구 훈련 증거가 아니다. 해당 실제 운영 검증과 훈련은 `NOT_RUN`이며, 외부 증거 없이 완료됐다고 주장하지 않는다.

인증된 현재 기기는 서버의 active 장치 키 inventory에 있는 다른 기기에만 `POST /admin/security/devices/{device_id}/report-lost`를 호출할 수 있다. 따라서 active 세션 없이 장치 키만 남은 원격 기기도 목록에 표시하고 신고할 수 있다. 성공하면 해당 원격 기기의 모든 세션과 장치 키가 폐기되며, 현재 기기를 자기 자신이 분실 신고하는 경로는 클라이언트에서도 거부한다. 기존 복구 시작은 계속 모든 기존 세션을 폐기하고, 시작 응답을 잃은 경우 같은 복구코드와 기기로 재시도하는 계약을 유지한다.

## 동일 후보 lockstep 전환

구 Android 앱↔신 backend와 새 migration 위의 구 backend는 지원하지 않는다. 신 Android 앱은 전환 중 구 backend의 state exact3와 sessions exact1을 읽을 수 있지만 custody를 `UNATTESTED`로 간주해 고위험 작업을 잠그므로, maintenance window에서 APK를 먼저 갱신한 뒤 같은 release candidate의 migration·backend로 전환한다.

1. 관리자 ingress와 신규 관리자 세션 발급을 차단한다.
2. DB backup과 migration·설정·APK preflight를 확인한다.
3. 구 backend를 유지한 채 모든 active 관리자 기기의 APK를 같은 후보로 교체한다. 갱신하지 않은 기기의 세션과 키는 폐기한다.
4. migration과 backend를 적용하고 같은 후보 조합에서 state exact5, sessions exact2, 복구와 고위험 작업 smoke를 확인한다.
5. 확인 후에만 관리자 ingress와 신규 세션을 재개한다.

DB 호환성이 별도로 입증되지 않은 app-only rollback은 금지한다. 이 절은 전환 절차이며 실제 배포·복구훈련을 수행했다는 증거가 아니다.

## 내부 검증 빌드

신고 검토·전달 화면은 기본과 release에서 항상 잠겨 있다. 로컬 debug 검증에서만 `WALKSAFE_ADMIN_OPERATIONAL_DEBUG=true`를 명시해 연다.

```bash
cd apps/android
WALKSAFE_ADMIN_OPERATIONAL_DEBUG=true \
  ./gradlew --offline :adminapp:testDebugUnitTest :adminapp:assembleDebug :adminapp:lintDebug \
  --no-daemon --max-workers=1
```

debug API origin 기본값은 `http://127.0.0.1:8000`이다. 다른 내부 주소는 `WALKSAFE_ADMIN_API_ORIGIN`으로 지정한다. release는 정확한 루트 HTTPS origin이 없으면 빌드를 거부하고, 운영 워크플로 flag는 입력값과 관계없이 `false`로 고정된다.

이 host/offline 검증은 서명된 사설 배포, 실제 기기 실행, 기관 전달, 운영 인증·DB 검증 또는 출시 승인을 뜻하지 않는다.
