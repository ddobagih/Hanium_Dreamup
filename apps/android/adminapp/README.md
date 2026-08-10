# WalkSafe Android 관리자 앱

사용자 보행 앱과 `applicationId`, 앱 저장소, 세션 namespace를 분리한 내부 관리자 앱이다. FP-008 범위는 신고 검토 결정과 이미 수동으로 수행한 기관 전달의 상태·접수번호를 기록하고 조회하는 기능까지다. 이메일·문자·기관 API 전송 기능은 없다. 관리자 전용 서명과 사설 배포는 아직 `NOT_RUN`이다.

## 장치 등록

첫 실행에서 AndroidKeyStore에 내보낼 수 없는 P-256 개인키를 만들고 화면에 공개키 descriptor를 표시한다. descriptor를 신뢰할 수 있는 로컬 운영자에게 전달하고, 운영자는 저장소 루트에서 다음 명령으로 공개키만 등록한다.

```bash
PYTHONPATH=. python scripts/provision_walksafe_admin_device_key.py \
  --admin-id admin-01 \
  --device-id '<descriptor device_id>' \
  --key-version 1 \
  --public-key-spki-base64url '<descriptor public_key_spki_base64url>'
```

등록 전 로그인·복구 완료·신고 운영 요청은 장치 증명 단계에서 거부된다. 키 marker나 버전이 달라졌다면 더 높은 버전으로 명시적으로 회전한다.

## 장치 증명과 운영 API

로그인·복구 완료·신고 운영 요청 전에는 `POST /admin/security/device-proof/challenges`에 exact13 요청을 보낸다. nullable 키도 생략하지 않고 JSON `null`로 포함하며, `X-WalkSafe-Correlation-Id`는 본문의 `correlation_id`와 같아야 한다. 서버의 exact19 응답은 canonical exact18 JSON 문자열인 `signing_payload`를 포함한다. 앱은 그 UTF-8 바이트를 AndroidKeyStore의 P-256 키로 ECDSA-SHA256 서명하고 ASN.1 DER 결과를 padding 없는 Base64url로 보낸다.

서명 대상 요청에는 `X-WalkSafe-Device-Challenge-Id`, `X-WalkSafe-Device-Signature`, `X-WalkSafe-Correlation-Id`를 각각 정확히 한 번 보낸다. 보호된 GET은 결속된 `X-WalkSafe-Read-Purpose`도 사용한다. Challenge API와 별도로 FP-008 화면이 사용하는 운영 API는 정확히 다음 네 개다.

- `POST /reports/{report_id}/review-decisions`
- `GET /reports/{report_id}/review-decisions`
- `POST /reports/{report_id}/deliveries`
- `GET /reports/{report_id}/deliveries`

검토 결정과 수동 전달 관찰은 append-only 이력이다. `deliveries` POST를 포함해 어느 API도 외부 기관으로 자료를 보내지 않는다.

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
