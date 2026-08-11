# Android 관리자 앱 코드 지도

상태: `CURRENT_PRODUCT`

진입점은 [`AdminBoundaryActivity.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java), 제품 경계와 실행 제한은 [관리자 앱 README](../../../apps/android/adminapp/README.md)입니다.

## 책임

| 영역 | 주요 코드 | 책임 |
|---|---|---|
| 화면 조립 | [`AdminBoundaryActivity.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java) | 로그인·복구·신고 검토·수동 전달 기록 UI |
| 장치 증명 | [`AdminDeviceKeyStore.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminDeviceKeyStore.java), [`AdminDeviceProof.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminDeviceProof.java) | AndroidKeyStore P-256 기기키와 요청 서명 |
| 인증·복구 | [`AdminSecurityController.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java), [`AdminSecurityHttpClient.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClient.java) | 비밀번호·TOTP·복구·세션·재인증 상태 |
| 운영 요청 | [`AdminOperationsApi.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminOperationsApi.java), [`AdminOperationsHttpClient.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminOperationsHttpClient.java) | 신고 검토 결정과 기관 전달 관찰 이력 |
| 고위험 gate | [`AdminHighRiskActionGate.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminHighRiskActionGate.java) | action·method·path·nonce 결속 재인증 경계 |
| strict 계약 | [`AdminCanonicalEncoding.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminCanonicalEncoding.java), [`AdminStrictJson.java`](../../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminStrictJson.java) | canonical 요청·응답 형식과 예상 밖 필드 거부 |

## 중요한 한계

- 기관 이메일·문자·외부 API를 자동 호출하지 않습니다. 앱 밖에서 수행한 전달 사실만 기록합니다.
- 신고 UUID 직접 입력 중심의 내부 도구 수준이며 목록·상세·상태 변경 전체 UI가 완성된 운영 제품이라고 보지 않습니다.
- release build의 운영 workflow는 잠겨 있고 전용 서명·사설 배포·실기기 복구훈련은 `NOT_RUN`입니다.
- 저장소 내부 JVM 테스트와 debug build는 실제 관리자 기기·운영 DB·외부 기관·독립 보안 검토를 대신하지 않습니다.

관리자 API를 바꾸면 Android client, Backend [`admin_security.py`](../../../backend/app/api/admin_security.py)·[`reports.py`](../../../backend/app/api/reports.py), 관련 service와 OpenAPI·장치 증명·재인증 테스트를 같은 변경에서 확인합니다. 실행법은 [테스트 가이드](../testing-guide.md)를 따릅니다.
