# Android 관리자 앱 코드 지도

상태: `CURRENT_PRODUCT`

진입점은 [`AdminBoundaryActivity.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java), 제품 경계와 실행 제한은 [관리자 앱 README](../../../apps/android/adminapp/README.md)입니다.

## 책임

| 영역 | 주요 코드 | 책임 |
|---|---|---|
| 화면 조립 | [`AdminBoundaryActivity.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java) | 로그인·복구·custody 확인·분실 기기 폐기·신고 검토·수동 전달 기록 UI |
| 장치 증명 | [`AdminDeviceKeyStore.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminDeviceKeyStore.java), [`AdminDeviceProof.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminDeviceProof.java) | AndroidKeyStore P-256 기기키와 요청 서명 |
| 인증·복구 | [`AdminSecurityController.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java), [`AdminSecurityHttpClient.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClient.java), [`AdminRecoveryCustodyState.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminRecoveryCustodyState.java) | 비밀번호·TOTP·복구·세션·재인증, off-phone custody 상태와 원격 분실 기기 폐기 |
| 운영 요청 | [`AdminOperationsApi.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminOperationsApi.java), [`AdminOperationsHttpClient.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminOperationsHttpClient.java) | 신고 검토 결정과 기관 전달 관찰 이력 |
| 고위험 gate | [`AdminHighRiskActionGate.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminHighRiskActionGate.java) | action·method·path·nonce 결속 재인증 경계 |
| strict 계약 | [`AdminCanonicalEncoding.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminCanonicalEncoding.java), [`AdminStrictJson.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminStrictJson.java) | canonical 요청·응답 형식과 예상 밖 필드 거부 |

## 중요한 한계

- 기관 이메일·문자·외부 API를 자동 호출하지 않습니다. 앱 밖에서 수행한 전달 사실만 기록합니다.
- 신고 UUID 직접 입력 중심의 내부 도구 수준이며 목록·상세·상태 변경 전체 UI가 완성된 운영 제품이라고 보지 않습니다.
- custody `ATTESTED`는 휴대전화 밖 복구수단과 별도 암호화 백업을 관리자가 확인했다는 내부 상태일 뿐 실제 복구자료 보관, 새 기기 복구, 분실 복구훈련을 증명하지 않습니다. 앱은 별도의 임의 32바이트 opaque 참조를 요청마다 만들며 원 복구자료나 참조 원문을 저장하지 않습니다.
- 장치 증명이 켜진 환경에서는 복구 시작 전에 대상 기기에 기존 active 신뢰 키가 정확히 하나 있어야 합니다. 서버는 시작 시 그 키만 보존하고 다른 active 기기키와 기존 session을 폐기하며, 보존한 키의 request-bound proof로 복구를 완료합니다. 복구 중 로컬 키 프로비저닝도 active recovery transaction의 같은 기기만 허용하고 앱은 네트워크로 키를 자체 등록하지 않습니다.
- 분실 신고는 현재 호출 기기가 아닌 대상 기기를 선택해 그 기기의 공개키와 세션을 서버에서 폐기합니다. 저장소 내부 UI·회귀만으로 실제 분실 기기나 운영 DB에서 폐기가 실행됐다고 보지 않습니다.
- 고위험 UI gate의 범위는 출시 승인·권한 변경·데이터 삭제 세 action뿐입니다. `NORMAL`과 custody `ATTESTED`, 최근 재인증 및 일치하는 action·method·path·nonce 결속이 모두 있어야 허용하며 UI 판정만으로 서버·offline 권한을 부여하지 않습니다.
- release build의 운영 workflow는 잠겨 있고 전용 서명·사설 배포·실기기 복구훈련과 5개 release gate는 canonical `NOT_RUN`(미면제), 출시는 `NOT_ELIGIBLE`입니다.
- 저장소 내부 JVM 테스트와 debug build는 실제 관리자 기기·운영 DB·외부 기관·독립 모델·법률·보안·접근성 검토를 대신하지 않습니다.

관리자 API를 바꾸면 Android client, Backend [`admin_security.py`](../../../backend/app/api/admin_security.py)·[`reports.py`](../../../backend/app/api/reports.py), 관련 service와 OpenAPI·장치 증명·재인증 테스트를 같은 변경에서 확인합니다. 실행법은 [테스트 가이드](../testing-guide.md)를 따릅니다.
