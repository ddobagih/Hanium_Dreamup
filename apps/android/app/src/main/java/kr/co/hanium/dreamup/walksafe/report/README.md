# Android Report Client

이 패키지는 Android 탐지 결과를 Gateway `/api/reports/v2` 신고 후보와 multipart 요청으로 변환한다.

## 허용 조건

- class는 `damaged_tactile_block`
- model key는 `custom_tactile` 또는 `unified_walksafe`
- fresh metric depth와 Device Gate PASS
- 자동 trigger는 서로 다른 완료 추론 3개, 700ms 안정성, detection confidence 0.70 이상을 모두 충족
- trusted GPS와 report JPEG
- 검증된 첫 실행 actor binding에서 유도되고 활성 Gateway session actor와 일치하는 `reporter_user_id`

`AndroidReportCandidatePolicy`가 allowlist metadata를 만들고 `AndroidReportUploader`가 `metadata`와 `image` part를 전송한다. 자동 신고 성공 후 같은 named actor/class의 실제 GPS 25m 반경에는 10분 cooldown을 적용한다. 성공 cooldown만 app-private preferences에 저장해 Activity/process 재생성 후 복원하고, 만료 정리와 최대 512개 제한을 적용한다. cell 경계와 tracker ID 변경으로 우회하지 않으며 명시적 voice trigger만 cooldown을 우회한다. 이 client gate가 backend 중복 판정이나 병합을 대체하지는 않는다.

`reporter_user_id` metadata는 인증 credential 자체가 아니다. Android가 검증된 actor와 일치하는 Gateway session으로 요청하고 Gateway가 Backend report API로 중계하며, 이 흐름은 공공기관 자동 제출 기능이 아니다.

## 영속 대기열 활성화 경계

신고 대기열은 승인된 capacity profile이 없는 기본 build에서 비활성이다. 활성 build는 아래
7개 값을 Gradle property 또는 같은 이름의 environment variable로 모두 전달해야 한다.

- `WALKSAFE_REPORT_QUEUE_ENABLED=true`
- `WALKSAFE_REPORT_QUEUE_MAX_ENTRIES`
- `WALKSAFE_REPORT_QUEUE_MAX_PAYLOAD_BYTES`
- `WALKSAFE_REPORT_QUEUE_MAX_STORED_ENTRY_BYTES`
- `WALKSAFE_REPORT_QUEUE_MAX_TOTAL_BYTES`
- `WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_ENTRIES`
- `WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_TOTAL_BYTES`

자동신고 한도 뒤에는 최대 저장 entry 한 건 이상을 명시 신고용 reserve로 남겨야 한다. total byte는
평문 image 합이 아니라 queue 내부 암호화 envelope·손상 파일·crash 잔여 regular file의 실제
content byte를 센다. 안전하게 셀 수 없는 symlink·특수 파일·예상 밖 디렉터리가 있으면 신규
신고 저장을 거부하고 임의 삭제하지 않는다. 암호화 envelope 생성부터 최종 용량·차단 표식·중복
ID 확인과 파일 게시까지 같은 storage lock 안에서 수행한다. 동의 철회의 표식·전체 파일 삭제·키
폐기/재생성도 같은 lock을 사용한다. purge 시작 전에 미완료 consent/account 의도 목록을 담은
`lifecycle-pending`을 내구화하고, 다음 privacy hook은 기존 의도까지 fence로 복구한다. 조회·복호화,
receipt/만료 삭제도 같은 OS file lock 안에서 account/consent/pending 표식을 확인한다. 파일 삭제나
키 lifecycle이 실패하면 pending을 남겨 모든 consent의 신규 저장·조회가 재시도 완료 전까지
fail-closed된다. account 삭제는 terminal 우선순위라 이후 consent 변경이 새 키를 만들지 않는다.

capacity profile이 없는 build도 이전 활성 build의 잔존 자료를 없애기 위한 consent/account privacy
purge는 실행한다. 비활성 build에서는 신규 저장·조회·만료정리는 storage를 열지 않는다. consent
purge는 key generation tombstone 뒤 향후 승인된 활성 build로 전환할 수 있도록 빈 fresh key를
만들지만, terminal account 삭제 뒤에는 새 키를 만들지 않는다.

실제 숫자는 지원 기기 저장공간과 암호화 오버헤드 실측 뒤 승인해야 한다. 저장소에는 승인값을
기본값으로 넣지 않았으므로 현재 production profile은 계속 default-off다. MainActivity의
`PERSISTENT_REPORT_QUEUE_ENABLED=false`는 제거된 legacy queue를 purge하기 위한 별도 경계이며
새 queue 활성 flag가 아니다. 활성화 전에는 지원 기기에서 포화·강제종료 복구와 별도 process의
file-lock 경쟁을 포함한 계측 시험을 통과해야 한다. app-private storage를 바꿀 수 있는 비협조
동일-UID 주체에 대한 handle-relative I/O 방어는 현재 보장 범위가 아니다.
