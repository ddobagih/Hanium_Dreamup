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

실패한 전송은 생성 보행 ID나 선택 동의 receipt가 바뀌어도 생성 당시 reporter actor와 현재
로그인 actor가 정확히 같은 경우에만 현재 서버 확인 receipt로 다시 승인한 다음 정지·일시정지·종료
전송 기회에 복구한다. actor binding이 없는 v1 queue envelope는 v2에서 전송하지 않는
fail-closed 자료다. queue에 저장된 생성 시 receipt는 감사 provenance로 유지한다. 자동 신고는 현재
자동신고 선택을 추가로 확인하고, 정확한
성공 receipt 후 cooldown을 내구 저장한 뒤에만 queue 원본을 삭제한다.

## 영속 대기열 활성화 경계

신고 대기열은 승인된 capacity profile이 없는 기본 build에서 비활성이고 release build에서는
설정값과 무관하게 열리지 않는다. debug 수동 capacity 설정은 아래 7개 값을 Gradle property 또는
같은 이름의 environment variable로 모두 전달해야 한다.

- `WALKSAFE_REPORT_QUEUE_ENABLED=true`
- `WALKSAFE_REPORT_QUEUE_MAX_ENTRIES`
- `WALKSAFE_REPORT_QUEUE_MAX_PAYLOAD_BYTES`
- `WALKSAFE_REPORT_QUEUE_MAX_STORED_ENTRY_BYTES`
- `WALKSAFE_REPORT_QUEUE_MAX_TOTAL_BYTES`
- `WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_ENTRIES`
- `WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_TOTAL_BYTES`

로컬 개발에는 `./gradlew :app:assembleDebug -PdevelopmentReportQueue=true`로 한정된
시험 profile을 명시할 수 있다. 같은 property를 `:app:testDebugUnitTest`에도 전달한다.
이 옵션은 debug에만 적용되고 로그인이나 최초 실행 절차 및 선택 동의를 우회하지 않는다.
전체 8건 / 64 MiB, 자동 6건 / 48 MiB, payload 4 MiB, 암호화 저장 entry 12 MiB를 상한으로
사용하며 전송 대상은 `http://127.0.0.1:8081`로 고정한다. 자동 용량 이후 남는 16 MiB에는
최대 명시 신고 entry 1건(12 MiB)과 4 MiB 여유가 있다. payload 대비 3배 저장 한도는 중첩
base64와 암호화 envelope 오버헤드를 위한 개발용 여유이며 지원 기기 실측 승인을 대신하지 않는다.
기존 신고 JPEG 8 MiB 한도와 달리 이 개발 queue는 metadata와 JPEG 합이 4 MiB를 넘으면
저장을 거부한다. 이미지를 임의로 자르거나 승인 상한을 확대하지 않는다. 더 큰 payload를 시험할
경우 위 7개 값을 모두 명시한 기존 profile을 사용하며, 그 profile이 개발 옵션보다 우선한다.
일반 debug build는 계속 기본 비활성이고 release의 queue 차단 조건도 유지한다.

queue 전송 origin은 일반 debug 로그인 URL과 별도로
`WALKSAFE_REPORT_QUEUE_TEST_ORIGIN`에 빌드 시 고정하며, 로그인 session origin이 이 값과 정확히
일치할 때만 status/POST를 만든다. 지정하지 않으면 `adb reverse tcp:8081 tcp:8081` 시험용
`http://127.0.0.1:8081`만 승인한다. 다른 HTTPS origin을 시험하려면 빌드 전에 이 값을 명시해야
하며, 실행 중 URL 입력만으로 queue 전송 대상을 바꿀 수 없다.

자동신고 한도 뒤에는 최대 저장 entry 한 건 이상을 명시 신고용 reserve로 남겨야 한다. total byte는
평문 image 합이 아니라 queue 내부 암호화 envelope·손상 파일·crash 잔여 regular file의 실제
content byte를 센다. 안전하게 셀 수 없는 symlink·특수 파일·예상 밖 디렉터리가 있으면 신규
신고 저장을 거부하고 임의 삭제하지 않는다. 암호화 envelope 생성부터 최종 용량·차단 표식·중복
ID 확인과 파일 게시까지 같은 storage lock 안에서 수행한다. account/전체 신고 권한 철회의
표식·전체 파일 삭제·키 폐기/재생성도 같은 lock을 사용한다. purge 시작 전에 미완료
consent/account 의도 목록을 담은
`lifecycle-pending`을 내구화하고, 다음 privacy hook은 기존 의도까지 fence로 복구한다. 조회·복호화,
receipt/만료 삭제도 같은 OS file lock 안에서 account/consent/pending 표식을 확인한다. 파일 삭제나
키 lifecycle이 실패하면 pending을 남겨 모든 consent의 신규 저장·조회가 재시도 완료 전까지
fail-closed된다. account 삭제는 terminal 우선순위라 이후 consent 변경이 새 키를 만들지 않는다.

자동신고 선택 철회는 같은 lock에서 automatic 전용 pending fence를 먼저 내구화하고, queue에 남은
모든 자동 후보의 생성 receipt를 각각 fence한 뒤 자동 후보만 삭제한다. 중간 실패 시 automatic만
계속 닫히고 명시 신고와 공유 암호화 키는 보존된다. raw 진단수집·학습·통신 선택 변경은 진행 중
전송을 재검증하도록 취소하지만 명시 신고 queue를 삭제하지 않는다.

capacity profile이 없는 build도 이전 활성 build의 잔존 자료를 없애기 위한 consent/account privacy
purge는 실행한다. 비활성 build에서는 신규 저장·조회·만료정리는 storage를 열지 않는다. consent
purge는 key generation tombstone 뒤 향후 승인된 활성 build로 전환할 수 있도록 빈 fresh key를
만들지만, terminal account 삭제 뒤에는 새 키를 만들지 않는다.

production용 실제 숫자는 지원 기기 저장공간과 암호화 오버헤드 실측 뒤 승인해야 한다.
production 승인값은 기본값으로 넣지 않았으므로 현재 production profile은 계속 default-off다. MainActivity의
`PERSISTENT_REPORT_QUEUE_ENABLED=false`는 제거된 legacy queue를 purge하기 위한 별도 경계이며
새 queue 활성 flag가 아니다. 활성화 전에는 지원 기기에서 포화·강제종료 복구와 별도 process의
file-lock 경쟁을 포함한 계측 시험을 통과해야 한다. app-private storage를 바꿀 수 있는 비협조
동일-UID 주체에 대한 handle-relative I/O 방어는 현재 보장 범위가 아니다.
