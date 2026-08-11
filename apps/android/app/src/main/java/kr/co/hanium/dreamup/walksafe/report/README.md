# Android Report Client

이 패키지는 Android 탐지 결과를 Gateway `/api/reports/v2` 신고 후보와 multipart 요청으로 변환한다.

## 허용 조건

- class는 `damaged_tactile_block`
- model key는 `custom_tactile` 또는 `unified_walksafe`
- fresh metric depth와 Device Gate PASS
- 자동 trigger는 서로 다른 완료 추론 3개, 700ms 안정성, detection confidence 0.70 이상을 모두 충족
- trusted GPS와 report JPEG
- 비어 있지 않은 local `reporter_user_id`

`AndroidReportCandidatePolicy`가 allowlist metadata를 만들고 `AndroidReportUploader`가 `metadata`와 `image` part를 전송한다. 자동 신고 성공 후 같은 named actor/class의 실제 GPS 25m 반경에는 10분 cooldown을 적용한다. 성공 cooldown만 app-private preferences에 저장해 Activity/process 재생성 후 복원하고, 만료 정리와 최대 512개 제한을 적용한다. cell 경계와 tracker ID 변경으로 우회하지 않으며 명시적 voice trigger만 cooldown을 우회한다. 이 client gate가 backend 중복 판정이나 병합을 대체하지는 않는다.

현재 `reporter_user_id`는 인증 토큰이 아니다. 요청은 인증된 Gateway가 Backend report API로 중계하며 공공기관 자동 제출 기능이 아니다.
