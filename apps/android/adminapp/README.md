# WalkSafe Android 관리자 앱

관리자 재인증은 신고·요청·사고 상태 변경, 원시 수집 목록·목적 결정·법적 보존의 6개 경로에서 실제 전송 본문의 SHA-256을 `intent_sha256`으로 결속한다. 재인증과 HTTP 전송은 같은 직렬화 함수를 사용하며 상태·버전·내용이 달라지면 기존 승인을 재사용할 수 없다. 원본 열람·제출본 생성·전달 정정은 서버 업무 트랜잭션이 아직 기존 nonce 해시만 검사하므로 이 결속을 적용하지 않는다. 해당 3개는 서버 계약 보완이 남아 있으며, 구 앱의 비결속 승인 호환 경로도 유지된다. 이 구현은 실제 관리자 기기·운영 배포 검증을 대신하지 않는다.

## 2026-09-30 현재 작업 안내

[인수인계 시작 문서](../../../docs/handoff/2026-09-30/README.md)와 [Part B 분담·다음 작업](../../../docs/handoff/2026-09-30/part-b.md)를 먼저 확인한다. 최신 관리자 JVM 시험·APK 기록은 [현재 상태·검증 범위](../../../docs/handoff/2026-09-30/status.md)에 있으며, 실제 기기·복구 훈련·SAF 제공자·사설 배포는 별도 미검증 범위다. 아래 W13·w25 기록의 수치를 현재 전체 시험 결과로 사용하지 않는다.

현재 화면 조립 진입점은 [`AdminBoundaryActivity.java`](src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java)와 [`AdminReportPanel.java`](src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminReportPanel.java)이고, 요청·인증·기기키 처리는 [`security/`](src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/)에서 담당한다. 관리자 앱은 Part B 범위이며 빌드·설치는 [Android 앱별 테스트 가이드](../../../docs/guides/android-apps-testing-guide.md)를 따른다.

사용자 보행 앱과 `applicationId`, 앱 저장소, 세션 namespace를 분리한 내부 관리자 앱이다. 현재 코드는 인증·복구·세션/기기 관리와 신고 목록·상세·처리 상태, 원본 증거 열람, 검토 결정, 제출본 생성·SAF 저장/재연결, 수동 기관 전달 기록, 신고 요청, 외부 사본 삭제 관찰, 감사 조회, 중대 사고 상태 기록, 격리 원본 조회를 포함한다. FP-008의 검토 결정·전달 기록 API는 이 중 한 하위 흐름이며 전체 기능 목록이 아니다.

이메일·문자·기관 API 전송 기능은 없다. 코드에 화면이 있다는 사실은 release 활성화, 관리자 전용 서명·사설 배포 또는 실제 기기 검증을 뜻하지 않는다. 해당 외부 검증은 아래 경계에 따라 별도 판정한다.

## 장치 등록

첫 실행에서 AndroidKeyStore에 내보낼 수 없는 P-256 개인키를 만들고 화면에 공개키 descriptor를 표시한다. descriptor를 신뢰할 수 있는 로컬 운영자에게 전달하고, 운영자는 저장소 루트에서 다음 명령으로 공개키만 등록한다.

```bash
DATABASE_URL="$WALKSAFE_MIGRATION_DATABASE_URL" PYTHONPATH=. \
python scripts/provision_walksafe_admin_device_key.py \
  --admin-id admin-01 \
  --device-id '<descriptor device_id>' \
  --key-version 1 \
  --expected-key-marker '<descriptor key_marker>' \
  --security-level '<descriptor security_level>' \
  --public-key-spki-base64url '<descriptor public_key_spki_base64url>'
```

CLI는 공개키에서 계산한 SHA-256 marker가 descriptor의 `--expected-key-marker`와 정확히 일치하는지 DB write 전에 검증한다. 운영자는 일반 API runtime 계정이 아닌 통제된 로컬 운영/마이그레이션 계정으로 이 명령을 실행한다. 서버 DB는 runtime의 기기키 INSERT를 거부하고 기존 active 키의 revoke UPDATE만 허용한다.

등록 전 로그인·복구 완료·신고 운영 요청은 장치 증명 단계에서 거부된다. marker·버전 불일치를 키 삭제·재생성으로 우회하지 않는다.
- 보안 수준(S18-2): 새 키는 KeyInfo가 `TRUSTED_ENVIRONMENT` 또는 `STRONGBOX`로 보고할 때만 사용하며 그 외(`SOFTWARE`, `UNKNOWN`)는 즉시 삭제한다. descriptor의 `security_level`을 CLI `--security-level`로 전달하고 CLI는 소프트웨어 키를 거부한다. StrongBox는 필수가 아니다.
- 사용자 인증(S18-1): 새 키는 기기 PIN·패턴·비밀번호 또는 강한 생체 인증 후 300초 동안만 서명한다. 화면 잠금이 없는 기기에서는 키를 만들 수 없으며 앱이 그 원인을 알린다(화면 잠금 설정 후 재실행하면 다시 만든다). 인증이 필요하면 앱이 기기 인증 화면을 한 번 띄운다. 비밀번호·인증번호·복구코드를 쓰지 않는 작업(상태 갱신, 세션 폐기, 교체 확인, 운영 조회·기록)만 같은 작업을 한 번 다시 시도한다. 로그인·복구 완료·재인증이 필요한 분실 신고는 먼저 기기키로 전송하지 않는 로컬 서명을 시도해 비밀값을 보내기 전에 기기 인증을 받고, 그래도 인증이 필요해지면 재시도하지 않고 "비밀번호와 새 인증번호를 다시 입력"하도록 안내한다(입력값은 지우며 다시 보내지 않는다). 시간 결속 키(300초)에는 `setInvalidatedByBiometricEnrollment`가 적용되지 않으므로 생체 등록 변경은 키를 무효화하지 않는다. 키를 영구 무효화하는 것은 화면 잠금 제거·초기화이며, 이때 서명은 `KeyPermanentlyInvalidatedException`으로 실패하고 앱은 재시도 없이 잠근다. 이전에 만든 버전 1 키는 교체 전까지 기존 속성을 유지한다.
- 키 무효화·분실 복구(S18-1-F1): 로그인 화면의 "교체용 새 기기키 준비"는 세션 없이도 다음 버전 키를 만들고 descriptor를 보여 준다(기기 ID와 기존 메타데이터는 유지). 운영자가 신뢰 CLI로 `--replace-active --key-version <N> --security-level <수준>`과 그 descriptor를 등록하면 서버는 기존 키를 즉시 폐기하고 새 키를 ACTIVE로 만들며 해당 기기의 세션을 끝내고 감사(`device_key.replace_active`)를 남긴다. 앱에서 "운영자가 등록한 교체 키로 로그인 전환"을 누르면 모든 클라이언트가 그 버전으로만 서명하며, 서버가 그 키의 LOGIN(또는 RECOVERY_COMPLETE) 증명을 받아들인 뒤에만 기기의 기본 키로 전환한다. 등록 전이면 로그인이 거부되고 "기존 기기키로 돌아가기"로 되돌린다. 등록된 키 항목이 기기에서 사라진 경우에도 같은 경로만 제공한다. 실기기 훈련(잠금 해제 설정 제거 → 교체 키 준비 → CLI 등록 → 교체 키 로그인)은 `NOT_RUN`이다.
- 키 교체(S18-3): 세션 화면의 "새 기기키 준비"가 다음 버전 키를 PENDING으로 만들고 descriptor를 보여 준다. 운영자가 같은 CLI로 더 높은 `--key-version`을 등록하면 서버는 PENDING으로 저장하고 기존 ACTIVE 키를 그대로 둔다. "등록한 새 기기키 확인 후 교체"는 기존 키의 기기 증명과 새 키의 서명을 같은 correlation_id로 묶어 `POST /admin/security/device-keys/rotation/confirm`에 보내며, 서버는 새 키 서명을 검증한 트랜잭션에서만 기존 키를 폐기하고 새 키를 활성화한다. 그 전 어떤 단계가 실패해도 기존 키로 계속 서명한다. 응답을 잃으면 세션 기기 목록의 키 버전·marker 접두어로 결과를 맞춘다. 기존 개인키를 잃은 긴급 상황에만 CLI `--replace-active`로 즉시 교체한다. 앱 재설치·데이터 삭제는 교체 절차가 아니다.

## 장치 증명과 운영 API

로그인·복구 완료·신고 운영 요청 전에는 `POST /admin/security/device-proof/challenges`에 exact13 요청을 보낸다. nullable 키도 생략하지 않고 JSON `null`로 포함하며, `X-WalkSafe-Correlation-Id`는 본문의 `correlation_id`와 같아야 한다. 서버의 exact19 응답은 canonical exact18 JSON 문자열인 `signing_payload`를 포함한다. 앱은 그 UTF-8 바이트를 AndroidKeyStore의 P-256 키로 ECDSA-SHA256 서명하고 ASN.1 DER 결과를 padding 없는 Base64url로 보낸다.

서명 대상 요청에는 `X-WalkSafe-Device-Challenge-Id`, `X-WalkSafe-Device-Signature`, `X-WalkSafe-Correlation-Id`를 각각 정확히 한 번 보낸다. 보호된 GET은 결속된 `X-WalkSafe-Read-Purpose`도 사용한다. Challenge API와 별도로 FP-008의 검토 결정·전달 기록 및 후속 정정 흐름은 다음 API를 사용한다. 신고·요청·감사·사고 등 다른 패널의 API는 이 목록에 포함되지 않는다.

- `POST /reports/{report_id}/review-decisions`
- `GET /reports/{report_id}/review-decisions`
- `POST /reports/{report_id}/deliveries`
- `GET /reports/{report_id}/deliveries`
- `POST /reports/{report_id}/delivery-corrections` (S18-40, HIGH 재인증)

결과가 미확정인 검토 결정의 decision_id·신고 ID·의도 해시·revision(사유 원문·사진·인증정보 제외)은 앱 저장소에 남으며, 재실행·재로그인 후 새 결정을 만들기 전에 `GET /reports/{report_id}/review-decisions/{decision_id}`로 먼저 조회한다(S18-36). 기록됐으면 완료로 처리하고, 없다고 확인되면 같은 입력은 같은 decision_id로만 다시 보낸다. 검토 결정과 수동 전달 관찰은 append-only 이력이다. `deliveries` POST를 포함해 어느 API도 외부 기관으로 자료를 보내지 않는다.

잘못 기록한 전달 관찰은 지우거나 고치지 않고 정정 기록을 덧붙인다(S18-40). 앱은 전달 이력을 끝까지 불러온 뒤 현재 유효한(정정되지 않은) 가장 최근 기록 1건만 정정 대상으로 보여 주고, 사유 코드(`WRONG_STATUS`·`WRONG_REPORT`·`DUPLICATE_ENTRY`·`OTHER`)와 메모, 비밀번호·새 인증번호 재인증(`report.delivery.correct`)을 요구한다. 더 이전 기록은 한 단계씩 차례로 정정한다. 이력 항목의 `correction` 객체를 엄격히 검증하고, 상태 전이는 정정되지 않은 기록만 이어서 검사한다. 정정 요청은 자동 재시도하지 않으며 결과가 불확실하면 같은 정정 ID로만 다시 보낸다. 대상·기록 버전·사유·메모가 바뀌면 새 정정 ID를 쓰고, 다시 불러온 이력에 현재 정정 ID가 보이면 기록된 것으로 보고 새 ID로 바꾼다. 서버의 정정 ID 충돌(409 `delivery_correction_idempotency_conflict`)은 다른 충돌과 구분해 안내하고, 재전송에 대한 기존 기록 응답은 같은 관리자·같은 내용이면 이전 세션 기록도 받아들인다. 메모 앞뒤 공백은 서버와 같은 Python `str.strip()` 기준(NBSP·U+3000 포함)으로 정리한다. 정정이 기록되면 전달 이력은 첫 페이지부터 다시 불러와야 한다. 실제로 해결된 뒤 다시 손상된 경우는 정정 대상이 아니며 새 신고로 접수된다(소유자 결정 6: 재개 이벤트는 두지 않고 원 신고는 "해결"로 남는다; 정정은 입력 실수에만 쓴다).

## 단일 관리자 복구 통제

서버 보안상태의 `recovery_custody_state`가 `ATTESTED`로 확인되기 전에는 로그인과 별개로 고위험 작업과 관리자 운영 화면을 잠근다. 관리자가 화면에서 서버 키, 앱 서명 키, 관리자 복구자료를 서로 분리해 각각 암호화 백업했고 같은 저장공간이나 계정에 키를 함께 두지 않았으며, 관리자 복구자료가 실제 휴대전화 밖에 있음을 명시적으로 확인할 때만 `POST /admin/security/recovery-custody/attest`를 호출한다. wire의 `separate_encrypted_backup_confirmed=true`는 이 세 키의 개별 암호화 백업과 저장공간·계정 분리를 모두 직접 확인했다는 뜻이다. 앱은 32-byte 난수로 만든 opaque reference를 전송하며 동일 프로세스·세션에서 결과가 미확정인 같은 자료 유형의 재시도에는 최초 reference를 재사용한다. 성공 확인 또는 로컬 세션 정리 시 폐기한다. 복구자료 원문, reference, 복구 토큰은 앱 저장소에 보존하지 않으며 프로세스 종료 후 reference 복원은 아직 지원하지 않는다. 서버는 reference의 SHA-256만 보존한다.

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

## 중대 사고 기록

관리자 앱은 Backend가 실제 허용된 내부 producer로부터 받은 `CRITICAL` 사고만
`GET /admin/incidents`와 `GET /admin/incidents/{incident_id}`로 조회한다. 앱에는 사고를
임의 생성하거나 장애를 흉내 내는 버튼이 없다. 목록과 상세는 상태와 문자 표기를 함께
사용하며, append-only 상태 이력에는 민감자료 원문 대신 관찰 요약과 근거 SHA-256만
표시한다.

상태 기록은 `OPEN -> ACKNOWLEDGED -> RESOLVED`와 동일 사고의
`RESOLVED -> REOPENED -> ACKNOWLEDGED -> RESOLVED`만 허용한다. 매 변경은
`admin.incident.status.update` exact-path 기기 증명과 비밀번호·TOTP 일회성 재인증을
거쳐 `PATCH /admin/incidents/{incident_id}/status`에 기록한다. version 충돌은 자동으로
재제출하지 않고 최신 상세를 다시 조회한다. `RESOLVED`는 운영자가 확인한 기록 상태일
뿐이며 앱은 자동 복구, 자동 제어, 서비스 재시작 또는 외부 통지를 수행하지 않는다.

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

### Android 실기기 회귀 시험

`./gradlew :adminapp:connectedDebugAndroidTest`는 연결된 기기에 별도 관리자 앱과 시험 APK를 설치한다.
시험은 합성 계정·기기 내부 모의 HTTP 서버·시험용 서명키로 6개 본문 결속 경로와
신고/사고 지연 통신 취소를 검사한다. 운영 서버의 관리자 계정이나 신고는 사용하지 않는다.
Activity 시험은 비밀번호 입력란을 채운 뒤 배경 전환·복귀 시 삭제와 `FLAG_SECURE`를 확인한다.
전달 기록 정정의 비밀번호·추가 인증 입력도 공통 비밀값 삭제에 포함한다.

기기키 시험은 별도 버전의 시험 키만 생성·삭제한다. 드래그 잠금에서는 키 생성 거절을 확인하며,
실제 하드웨어 서명 시험은 보안 잠금이 없어 제외된다. 서명 성공 경로를 검사하려면 사용자가
PIN·패턴·비밀번호를 설정하고 최근 인증한 기기가 필요하다. 모의 서버 전송 통과는 운영 서버
기기 등록·MFA 검증, 실제 관리자 업무 전체나 아래 표의 종단 검증 완료를 의미하지 않는다.

`AdminSecurityRaceDeviceTest`는 실제 Activity의 중지·복귀, 작업 실행기, 보안 컨트롤러를
사용하고 인증 API 응답만 지연시키는 시험이다. 로그인·복구 응답이 늦게 도착해도 권한이
다시 열리지 않는지, 현재 세션 로그아웃이 서버 응답 전에 로컬 세션과 화면 접근을
제거하는지 검사한다. 서버 폐기 요청에 필요한 토큰과 HTTP 결속은 요청 종료까지 필요하며,
서버 응답 실패를 서버 로그아웃 성공으로 표시하지 않는다. 실제 MFA·운영 DB 검증은 별도다.

## 기능별 상태 구분
아래 표는 기능마다 소스 구현, 서버 연결, debug 사용, release 허용, 실기기 검증을 따로 적는다. 한 열이 `예`라고 다른 열까지 완료된 것은 아니다. release 열은 `build.gradle.kts`의 `ADMIN_SECURITY_WORKFLOWS_ENABLED`·`ADMIN_OPERATIONAL_WORKFLOWS_ENABLED` 값이고, 실기기 열은 이 저장소에 실행 증거가 있는지만 뜻한다.

| 기능 | 소스 구현 | 서버 연결 (backend 경로) | debug 사용 | release 허용 | 실기기 검증 |
|---|---|---|---|---|---|
| 로그인·재인증·로그아웃·세션/기기 목록·분실 신고 | 예 | `/admin/security/*` | 예 | 코드상 켜짐 (보안 flag `true`, HTTPS origin 필수) | `NOT_RUN` |
| 복구 시작·완료, 복구자료 외부 보관 확인 | 예 | `/admin/security/recovery/*`, `/admin/security/recovery-custody/attest` | 예 | 코드상 켜짐 | `NOT_RUN` (복구 훈련 포함) |
| 신고 목록·상세·처리 상태 | 예 | `/admin/reports`, `/admin/reports/{id}`, `/admin/reports/{id}/status` | `WALKSAFE_ADMIN_OPERATIONAL_DEBUG=true`일 때만 | 아니오 (운영 flag `false` 고정) | `NOT_RUN` |
| 원본 증거 열람·검토 결정 | 예 | `/reports/{id}/original-access-grants`, `/reports/{id}/review-decisions` | 위와 같음 | 아니오 | `NOT_RUN` |
| 제출본 생성·SAF 저장/재연결 | 예 | `/admin/reports/{id}/delivery-packages` | 위와 같음 | 아니오 | `NOT_RUN` (SAF 제공자별) |
| 수동 기관 전달 기록 | 예 | `/reports/{id}/deliveries` (전송 없음, 기록만) | 위와 같음 | 아니오 | `NOT_RUN` |
| 신고 요청·외부 사본 삭제 관찰 | 예 | `/admin/report-requests`, `/admin/report-deletions/*` | 위와 같음 | 아니오 | `NOT_RUN` |
| 감사 조회 | 예 | `/admin/reports/audits` | 위와 같음 | 아니오 | `NOT_RUN` |
| 중대 사고 상태 기록 | 예 | `/admin/incidents` | 위와 같음 | 아니오 | `NOT_RUN` |
| 격리 원본 조회 | 예 | `/admin/raw-collections/*` | 위와 같음 | 아니오 | `NOT_RUN` |

서명된 비디버그 내부 배포 빌드, 운영 서버 주소·기기 등록·배포 회수 검증이 끝나기 전에는 release의 운영 flag를 켜지 않는다. 표의 `NOT_RUN`은 외부 증거가 생길 때만 바꾼다.
## 과거 W13·w25 기록과 검증 버전 경계

- 기존 W13 기준은 관리자 JVM 단위시험 302개/43 suites 통과이며 close 경쟁 재현의 red/green 로그는 `W13-SECURITY-NOTES.md`에 남아 있다. APK 결과는 부모 통합 담당의 기존 근거를 사용한다.
- S20-54 원본 열람 권한 소비와 실제 수신 구분: 원본 이미지는 서버가 권한(grant)을 소비하고 감사 `ACCESS_GRANTED`/`approved_grant_consumed`를 commit한 뒤 HTTP로 반환한다. 이 감사의 의미는 **서버 반환 준비**(서버 승인·반환 준비 시점)이며 관리자 기기의 실제 수신 완료가 아니다(서버에는 수신 확인 기록이 없다). 앱은 일회용 토큰을 권한에서 꺼낸 뒤의 실패(응답 유실·시간 초과·길이/해시/형식 불일치, 검증 뒤 화면 표시용 디코딩 실패)를 `OriginalEvidenceNotReceivedException`으로 구분해 "이 기기에서 원본을 받아 표시하지 못했습니다. 기존 원본 열람 권한은 더는 쓸 수 없습니다" → "비밀번호와 새 6자리 인증번호로 … 새 열람 권한을 받으세요"를 안내한다. 서버에 닿기 전 취소·연결 거부처럼 서버가 권한을 소비하지 않았을 수 있는 경우도 있으므로 상태 문구는 소비 여부를 단정하지 않는다(토큰은 앱에서 이미 0으로 지워져 어느 경우든 다시 쓸 수 없다). 열람 버튼의 TalkBack 이름은 보이는 이름 "정확한 위치·원본 사진 일회 열람"으로 시작한다. 기존 토큰을 재사용·재활성화하는 경로는 없고, 다시 열람은 매번 새 재인증·새 목적 제한 grant다. 실기기 응답 유실 재현은 `NOT_RUN`.
- w25 관리자 앱 정비(Gradle은 Root 단일 실행, 실기기 `NOT_RUN`): (S18-19) 운영·격리원본 클라이언트도 보안 클라이언트와 같은 요청당 전체 20초(연결 8초+읽기 12초) 단조시계 deadline과 예약 연결 끊기를 쓰며, 시간 초과·취소는 "서버 처리 여부 불명"으로 실패하고 쓰기를 자동 재전송하지 않는다. 로그인·복구 시간 초과는 인증 실패가 아니라 "처리했는지 알 수 없음" 문구로 보인다. (S18-18) 화면 종료(close)는 로컬 잠금·비밀값 참조 해제를 먼저 하고 그 뒤 세 클라이언트의 진행 중 요청을 기다리지 않고 취소한다(서버 revoke는 보내지 않음). 로그아웃 실패는 "이 기기에서는 로그아웃했습니다/서버 세션 폐기는 확인하지 못했습니다"로 나눠 보인다. 보고·사고(OkHttp) 클라이언트 취소 연결은 남은 일이다. (S18-20) 로그인·복구 시작·복구 완료 비밀값은 입력란→char[]→요청 bytes까지 String 없이 전달하고 사용 뒤·실패·큐 폐기 때 0으로 덮는다. 플랫폼 입력란이나 HTTP 스택의 다른 사본까지 지운다는 보장은 아니다. (S18-23/S18-21) 기기키 준비 실패는 비밀값 없는 오류 코드(`METADATA_INVALID`, `VERSION_UNSUPPORTED`, `MARKER_MISMATCH`, `METADATA_WRITE_FAILED`, `KEYSTORE_UNAVAILABLE`, `HARDWARE_KEY_UNSUPPORTED`, `REGISTERED_KEY_MISSING`, `LOCK_SCREEN_REQUIRED`, `UNKNOWN`)와 다음 행동 1개로 보이며, 정상일 수 있는 키를 지우라고 안내하지 않는다. (S18-16) 중대 사고 상태·원시자료 결정 결과 콜백도 화면 활성·작업 세대·세션 세대가 같을 때만 반영한다.
- w25 후속(검토 adm-F1~F7, Gradle은 Root, 실기기 `NOT_RUN`): 원본 이미지 한 번 열람 GET만 전체 제한 시간을 20초 + 예상 크기 ÷ 64 KiB/s(상한 150초, R14)로 두고 JSON·쓰기는 20초를 유지한다. 화면 종료 취소는 해당 클라이언트에서 되돌릴 수 없어(sticky) challenge 뒤 보호 쓰기·로그인 POST가 close 이후 연결을 열지 않는다. 쓰기 본문은 고정 길이 스트리밍으로 보내 플랫폼이 재시도용 사본을 두지 않게 한다(플랫폼 재전송 억제는 실기기 확인 대상). AndroidKeyStore의 원시 예외(KeyStoreException·UnrecoverableKeyException·ProviderException·CertificateException·NoSuchProviderException)는 `KEYSTORE_UNAVAILABLE`로 보인다. 등록 키가 없다는 응답은 잠시 뒤 한 번 더 확인하고, 안내의 첫 행동은 기기·앱 재시작이며 그 뒤에도 같을 때만 교체 키를 준비한다. 로그아웃 실패 문구는 이 기기의 세션 값이 실제로 지워졌을 때만 "지웠습니다"라고 말한다. 이력 조회 결과도 화면이 시작된 상태·중지 전 세대일 때만 반영한다.
- 그 이후 S18-21/22/23 대응으로 키 준비와 원본 이미지 디코딩을 worker로 옮기고 초기화 실패 단계를 구분했다. 키 준비 중에는 관리자 입력을 열지 않으며 종료된 Activity의 결과는 채택하지 않는다. 이미지 적용 직전에 신고·콘텐츠·세션·기기·만료·화면 세대를 다시 검사한다.
- 위 후속 작업 당시에는 새 정적 회귀시험 6개를 미실행으로 기록했다. 이후 통합한 현재 관리자 JVM 전체 시험과 APK의 결과는 [현재 상태 문서](../../../docs/handoff/2026-09-30/status.md)를 따른다. 과거 302 통과 기록과 당시 APK를 최신 소스의 시험·설치 근거로 사용하지 않는다.
- 로컬 잠금은 서버 로그아웃이나 키의 사용자 인증 요구 설정을 대신하지 않는다. SAF 복귀 예외, 확정된 전달/상태 변경의 조회 복구, 즉시 로컬 차단과 비차단 snapshot을 유지한다.
- AndroidKeyStore 실기기 지연, 큰 이미지 디코딩 중 홈/회전/만료, 기기 인증 취소·복귀, SAF 제공자별 취소·반환 및 실제 폰 데이터 보존 시험은 미수행이다. 기존 폰 DB에 접근하거나 수정하지 않았다.
- S18-1~48의 개별 상태와 잔여 조치는 `/home/ddobagi/Hanium_chat_review_2026-09-17/progress-slices/S18.jsonl`에 기록한다. 미해결 서버 계약·키 정책·외부 custody·기관 접수·PostgreSQL·배포 검증을 로컬 잠금 수정으로 완료 처리하지 않는다.
