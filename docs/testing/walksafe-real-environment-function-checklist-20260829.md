# WalkSafe 기능별 실환경 체크리스트 — 2026-08-29

이 문서는 사람이 실제 Android 기기·Gateway·Backend·운영 환경에서 기능을 확인할 때 쓰는 실행 색인이다. 표의 열은 요청대로 `기능 | 실행 절차 | 예상 결과`만 둔다.

[2026-08-28 기능 재평가](../planning/walksafe-full-feature-reassessment-20260828.html)와 [2026-08-29 잔여 기능 실행 계획](../planning/walksafe-remaining-feature-execution-plan-20260829.md)을 기준으로 갱신했다. 표의 `구현됨`은 코드·계약이 있다는 뜻일 뿐 실환경 `PASS`가 아니다. 2026-08-30에는 로컬 PostgreSQL·Mailpit 가입 정상경로, Voice 직접·Gateway 추론, SM-G981N 가입·기기점검과 서로 다른 PostgreSQL cluster의 격리 복원을 실행했다. 범위와 수치는 [실행 보고서](walksafe-real-environment-execution-report-20260830.md)를 따르며 운영 SMTP·TMAP·storage·복원, 실기기 음성 청취·TalkBack·현장 보행과 외부 기관은 계속 `NOT_RUN`이다. PASS·정식 SMS 본인확인은 향후 공급자 검토 메모이며 현재 가입 기능이나 실행 대상이 아니다. 실제 결과·후보 hash·기기·수행자·증거는 실행 기록에 별도로 남기며 이 체크리스트에 성공으로 미리 표시하지 않는다. 세부 정지·거리·15분 보행 절차는 [기존 실기기 부록](android_stationary_and_field_test_checklist_20260710.md), 정식 279건은 `docs/deliverables/06-testing/registers/test-cases.json`을 따른다.

## 1. 사용자 Android 앱

| 기능 | 실행 절차 | 예상 결과 |
|---|---|---|
| 설치·앱 역할 분리 | 서명된 사용자 앱을 clean install·업데이트하고 관리자 앱과 번갈아 실행한다. 잘못된 signer와 downgrade도 시도한다. | 사용자 앱의 package·서명·저장소·화면이 관리자 앱과 분리되고 잘못된 업데이트는 거부된다. debug/unsigned 설치는 출시 PASS가 아니다. |
| [이메일 OTP 가입](../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/account/EmailAccountEnrollment.kt) `[실기기 Mailpit 이메일 OTP 가입→자동 로그인 정상경로 PASS·외부 SMTP/오류변형 NOT_RUN]` | 실제 시험용 이메일에 OTP를 요청하고 6자리 정상·오류·만료 코드, 재전송 제한, 10자 이상 비밀번호, 필수 동의 누락과 exact retry를 시험한다. | 정상 이메일 OTP·비밀번호·필수 동의 조합만 계정을 만들고 이후 이메일+비밀번호로 로그인한다. OTP·비밀번호·생년월일 원문은 가입 중단 복원자료나 로그에 남지 않는다. |
| 사용자입력 생년월일·만 14세 미만 차단 `[구현됨·실환경 NOT_RUN]` | 서울 날짜 경계에서 사용자가 생년월일을 입력해 만 13세, 정확히 만 14세, 14세 이상으로 이메일 OTP 가입을 시도한다. 네트워크 요청을 관찰해 PASS·통신사 SMS 본인확인이 호출되지 않는지도 확인한다. | 입력 생년월일상 만 14세 미만은 OTP 발급 전에 서버에서도 차단된다. 현재 흐름은 명의·연령을 공적으로 확인했다고 주장하지 않으며 유료 PASS/SMS 공급자는 향후 메모로만 유지된다. |
| 가입 동의 v1.1 | 필수 문서 일부 미동의, 필수 전체 동의, 선택 전체 거부, 선택별 허용 조합으로 가입한다. `FP-013-1.1.0`의 목적·항목·raw 14일·sanitized 학습자료 3년·거부 효과를 열어 보고 TalkBack 낭독도 확인한다. | 필수 미동의만 진행을 막고 선택 false는 가입을 막지 않는다. 가입 receipt에는 당시 문서 version과 선택이 원자 귀속되며 bootstrap 전에는 raw·자동신고·학습 기능 허용 receipt로 대신 쓰이지 않는다. |
| 로그인 후 통합 동의 v1.1 bootstrap·재동의 `[신규 가입 로컬 PASS·재동의 전체 변형/운영 E2E NOT_RUN]` | 신규 가입, 현재 v1.1 event 보유 계정, 과거 v1 event 계정으로 각각 로그인한다. `CURRENT_CONSENT`, `SIGNUP_CONSENT`, `RECONSENT_REQUIRED`를 확인하고 이전 Backend receipt로 저장한 뒤 stale receipt, exact retry, 다른 계정·generation·installation을 보낸다. | bootstrap은 현재 선택 또는 재동의 필요 상태만 제시하고 자체로 기능을 열지 않는다. 명시 저장 후 현재 Backend v1.1 receipt가 생겨야 선택 기능이 열리며 exact replay만 멱등이고 stale CAS·교차계정 요청은 로컬 동의나 기능 gate를 바꾸지 않는다. |
| 가입 중단·복원 | 이메일 OTP 요청, 동의, 계정 활성화, 로그인 뒤 각각 앱을 강제종료·재실행한다. | 유효기간 안의 불투명 enrollment handle·문서 버전·선택값만 암호화 복원된다. 이메일·생년월일·비밀번호·OTP·pending request는 복원되지 않고 stale/위변조 상태는 거부된다. |
| 이메일+비밀번호 로그인·로그아웃 | 가입한 이메일+비밀번호와 잘못된 비밀번호로 로그인하고 로그아웃·재로그인·반복 실패를 시험한다. | 서버가 확인한 현재 account generation의 actor만 활성화되고 인증 제한을 우회하지 못한다. 로그아웃은 세션만 끝내며 계정 삭제만 전체 enrollment를 초기화한다. |
| 장기 로그인·다기기 세션 `[현재 release 기본 OFF]` | 장기 로그인이 열린 통제 후보에서 재시작·refresh·기기 목록·다른 기기 폐기를 실행하고 기본 release도 확인한다. | 활성 후보는 동일 actor의 허용 세션만 복원하고 refresh 재사용 family를 폐기한다. 현재 release는 장기 로그인을 열지 않는다. |
| [로그인 뒤 전경 기기점검](../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/PostLoginDeviceCheckPolicy.kt) `[기종 독립 구현·최신 APK 로그인 뒤 실제 종단 NOT_RUN]` | 로그인 직후 화면에서 점검을 시작해 로컬 한국어 TTS 합성, 사용자 시작 Vosk 호출어, fresh GPS fix, 진동 체감, CameraX 프레임+실제 detector, Depth를 확인한다. 점검 중 Home 이동·회전·전화·권한 철회·timeout도 재현한다. | 제조사·모델명은 판정에 쓰지 않는다. 점검 전에는 보행·길안내·탐지·신고가 잠기며 전경의 현재 session·attempt 결과만 반영한다. background/stale/timeout은 모든 점검 자원을 닫고 통과시키지 않는다. |
| 기기점검 FULL PASS | Depth 지원 기기에서 공통 실제 기능시험과 약 10초 metric Depth preflight를 통과한다. 같은 절차를 서로 다른 제조사·Android 버전에서도 반복한다. | 특정 휴대폰 목록과 무관하게 공통 기능과 실제 metric Depth가 모두 성공할 때만 `FULL`이 되고 안전교육 뒤 전체 기능이 열린다. 앱·OS·권한·기능 결과 변경 시 다시 점검한다. |
| 기기점검 LIMITED PASS | Depth가 명시적으로 미지원인 기기에서 로컬 TTS·Vosk·GPS·진동·CameraX+detector 등 공통 실제 기능시험을 통과한다. | 제한 사유가 화면·TalkBack으로 안내되고 `LIMITED` 기능만 열린다. 미터·걸음 거리·STOP 같은 금지 출력은 나오지 않는다. |
| 기기점검 실패·재시도 | 필수 권한 거부, 실제 TTS 합성 실패, Vosk 호출어 timeout, GPS fix 실패, 진동 미확인, CameraX/detector 실패, 저장공간 low, 배터리 low, critical thermal을 각각 만든다. | `FAIL` 원인과 해결 방법이 항목별로 보이고 보행·길안내·탐지·신고는 계속 잠긴다. 상태 회복 뒤 사용자가 전경에서 다시 시작해야 한다. |
| Android 권한 JIT | 카메라·정확한 위치·마이크·신체활동을 각각 최초 거부, 다시 묻지 않음, 사용 중 철회, 설정 재허용으로 시험한다. | 가입 동의와 OS 권한이 분리된다. 권한별 사전 설명 뒤 요청하며 필수 권한 상실 시 현재 기능이 안전 중지되고 사용자 확인 없이 자동 재개하지 않는다. |
| 사용환경·장착 gate `[승인 profile 필요]` | 승인/비승인 기기, 가슴·목 장착, 흔들림·가림·어두움·GPS 불량을 재현한다. | 승인 profile과 신뢰 가능한 측정이 없으면 시작이 차단된다. 현재 production profile이 `null`인 후보는 실제 보행 BLOCKED가 정상이다. |
| 배터리·저장공간·발열 gate | 시작 전과 보행 중에 low battery, low storage, critical thermal을 각각 만든다. | 시작 전에는 보행을 열지 않고 보행 중에는 안전 출력·전송을 중지한다. 상태 회복과 재확인 전 자동 재개하지 않는다. |
| 단일 활성 보행 | 기기 A 보행 중 같은 계정의 기기 B에서 시작·거절·명시 takeover를 수행하고 lease 만료를 재현한다. | 계정당 live walk는 하나다. 명시 확인 없는 takeover가 없고 갱신 실패·만료 시 안전 출력이 중지된다. |
| 보행 생명주기 | 시작, background 전환, foreground 복귀, 일시정지, 재개 확인, 종료, 강제종료 후 재실행을 수행한다. | background에서 ARCore 보행을 지속하지 않고 출력을 취소한다. 복귀·재시작은 stale 판단을 재사용하지 않고 재검사·확인을 요구한다. |
| ARCore Depth 거리 | 0.5m·1m·2m 고정 물체, 화면 회전, Raw Depth 부족과 Full Depth fallback을 시험한다. | bbox·depth 좌표가 맞고 fresh metric 신뢰가 있을 때만 미터·걸음 표현이 나온다. stale·저신뢰 depth에는 계측 표현이 없다. |
| ARCore 미지원 CameraX 보조 | 실제 Depth 미지원 기기에서 fresh camera·IMU·TMAP·GPS 조건의 일치/불일치를 시험한다. | 승인 제한 모드에서는 좌/중/우 low advisory만 가능하다. 미터·걸음·STOP·local steering·자동신고는 나오지 않는다. |
| 모델 로드·13종 탐지 `[승인 camera profile 필요]` | 통제 fixture로 13종을 제시하고 모델 asset hash·tensor·class 순서 오류와 delegate 실패를 만든다. | 승인된 APK 내 모델·설정만 실행된다. 무결성·profile·모델이 불명확하면 탐지 출력을 열지 않고 안전 중지한다. |
| 추적·TTC·stale·위험 등급 | 동일 물체 3프레임/700ms 전후, 접근·정지·이탈, 카메라 가림과 오래된 frame을 재현한다. | fresh metric track만 거리·TTC·위험 후보가 되고 stale 결과는 폐기된다. 손상 점자블록은 위험 길안내가 아니라 신고 후보로만 처리된다. |
| TTS·진동 우선순위 | 길안내 발화 중 위험을 만들고 TTS 초기화 지연·실패·한국어 미지원·음소거를 시험한다. | 위험 안내가 우선하고 중복 발화가 억제된다. STOP만 강제 위험진동을 사용하며 TTS 신뢰 상실 시 안전 중지한다. |
| TalkBack 전체 흐름 | TalkBack touch exploration으로 가입→동의→권한→기기점검→보행→경로→신고→오류→계정삭제를 화면을 보지 않고 수행한다. | 앱 TTS 중복, 초점 손실, label 없는 조작 없이 상태·오류·복귀가 전달된다. 완료할 수 없는 단계는 원인과 다음 조작을 읽어 준다. |
| GPS·걸음·보폭 | 실외 정지·직선 왕복에서 정확도 좋음/나쁨/jump, step counter와 accelerometer fallback을 시험한다. | trusted GPS만 경로 위치·방향에 쓰고 걸음은 보조 진행량으로만 쓴다. GPS 불신 시 방향 안내를 중지하고 dead reckoning으로 대체하지 않는다. |
| 목적지 검색·선택 | 텍스트·버튼형 음성으로 검색, 결과 없음, 상위 3개, 더보기, 범위 밖 번호, 취소, 선택을 실행한다. | 이름·주소·거리가 순서대로 제시되고 명시 선택 전 기존 경로가 바뀌지 않는다. 실패·취소는 기존 안전 상태를 유지한다. |
| TMAP 경로 안내 `[운영 provider NOT_RUN]` | 실제 provider로 목적지를 선택해 경로 시작·안내지점 진행·timeout·rate limit을 시험한다. | Backend가 확인한 TMAP 경로만 전역 경로가 된다. 실패 시 방향 안내만 멈추고 로컬 안전기능 성공으로 가장하지 않는다. |
| 경로 이탈·재탐색·도착 | 이탈 의심/확정, 위치 재확인, 새 경로 성공·2회 실패, 도착 후보 확인/거절을 수행한다. | 사용자 선택 전 자동 재탐색·도착 확정이 없다. 새 경로 확인 전 기존 방향을 신뢰하지 않고 반복 실패는 안전 중지한다. |
| 점자블록 local 보조 | active 경로에서 정상 점자블록을 same-frame depth·pose·GPS·corridor 일치/불일치로 관찰하고 손상 블록도 제시한다. | 모든 결속이 fresh일 때만 local 보조가 나오며 하나라도 불일치하면 TMAP 안내로 fallback한다. 손상 블록은 신고 후보만 된다. |
| 버튼형 음성명령 | 보행 중 버튼으로 목적지·후보선택·다음 안내·재탐색·위치 재확인·신고·보행 종료 확인/취소를 정확·저신뢰 문구로 실행한다. | 정확 문구·신뢰도·현재 epoch가 맞을 때만 동작하고 종료는 2단계 확인이다. stale·저신뢰·오인식은 상태를 바꾸지 않는다. |
| [faster-whisper STT·Qwen3-TTS](../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewaySpeechClient.kt) `[로컬 모델·Gateway relay PASS·실기기 마이크/스피커 NOT_RUN]` | 고정한 모델 revision과 로컬 음성 서버를 설정한 후보에서 실제 마이크 입력을 STT로 보내고 안내 문구를 TTS WAV로 받아 재생한다. timeout·낮은 음향 신뢰도·잘못된 model revision·과대/손상 WAV도 시험한다. | faster-whisper 결과는 intent와 앱 안전정책을 통과할 때만 동작 후보가 되고 Qwen3-TTS는 검증된 WAV 안내만 반환한다. 실패가 자동 동작이나 성공으로 바뀌지 않으며 안전 안내는 단말 TTS·진동 경계를 유지한다. 실기기 입력·재생과 사람의 청취 평가는 별도다. |
| `길라잡이` 한 문장 호출 `[구현됨·실제 발화 NOT_RUN]` | 로그인·기기점검·안전교육을 마치고 전경 ACTIVE 보행에서 마이크·알림 권한을 허용한다. 앱을 offline으로 둔 채 `길라잡이, 서울역으로 안내해줘`와 지원하는 신고·다음 안내·재탐색 명령을 말한다. | 지속 알림이 보이고 0.60 이상 final 결과에서 호출어 뒤 명령만 기존 안전 parser로 전달된다. 목적지 후보를 임의 선택하지 않고 안내가 끝난 뒤 다시 호출어 대기로 돌아간다. 네트워크가 필요한 실제 검색만 명시적으로 실패할 수 있다. |
| `길라잡이` 분리 호출·timeout | 전경 ACTIVE 보행에서 `길라잡이`만 말한 뒤 6초 안에 명령을 말하고, 다시 호출한 뒤 6초 동안 아무 말도 하지 않는다. | 첫 시도는 다음 final 명령 하나만 처리한다. 두 번째는 아무 동작 없이 command timeout으로 닫히고 다시 호출어 대기로 돌아간다. |
| 호출어 오탐·미탐·저신뢰 | 조용한 실내·도로 소음·바람·복수 화자에서 호출어, `길라자비`·`길라잡이가` 같은 유사어, 일반 대화와 신뢰도 낮은 명령을 반복한다. | 정확한 `길라잡이`/`길라 잡이`와 유한한 confidence 0.60 이상만 command window를 연다. 유사어·신뢰도 누락/저신뢰·지원하지 않는 명령은 보행·경로·신고 상태를 바꾸지 않는다. |
| 호출어 마이크·생명주기 | 호출어 대기 중 버튼형 단말 STT, Gateway 녹음, TTS/TalkBack, 위험 안내를 각각 실행하고 Home 이동·일시정지·종료·마이크 철회·앱 강제종료를 수행한다. | 한 번에 한 마이크 소유자만 동작하고 앱/TalkBack 발화가 호출어로 재인식되지 않는다. ACTIVE 이탈·권한 철회에서 service와 마이크가 멈추며 foreground 복귀만으로 자동 재개하지 않는다. 음성 원본·인식 원문 파일이나 서버 전송이 생기지 않는다. |
| 호출어 장시간 자원 시험 | 실제 장착 상태에서 camera·GPS·탐지·TTS와 호출어를 동시에 15분 이상 실행하며 cold start·연속 30회 명령·화면 회전·전화 수신을 재현하고 1분 간격으로 RSS·배터리·thermal을 기록한다. | native crash·ANR·마이크 고착이 없고 기기 안전 gate가 critical thermal·battery/storage 상태를 차단한다. 측정 수치는 기기·OS·APK hash와 함께 실행 보고서에 남기며 기준 확정 전에는 출시 PASS로 표시하지 않는다. |
| 손상 점자블록 직접·자동 신고 | 정상/손상 블록, GPS 없음, actor 불일치, 동의 없음, 반복 cooldown 조건에서 직접·자동 신고를 실행한다. | 조건을 모두 만족한 손상 후보만 생성된다. 자동신고는 보행 중 임시 저장만 하고 정지 재확인/종료 뒤 전송하며 직접신고만 허용된 예외를 따른다. |
| 영속 신고 queue `[승인 capacity profile 필요]` | 기본/활성 후보에서 offline 저장, 강제종료 복원, 포화, 자동 reserve, 직접신고, ACTIVE/정지 drain, receipt 불일치, 철회·계정삭제 purge를 시험한다. | 기본 후보는 신규 queue를 만들지 않는다. 활성 후보는 암호화 실제 byte cap과 직접신고 reserve를 지키고 ACTIVE 중 POST하지 않으며 exact receipt 뒤만 삭제한다. |
| [내 신고 내용 조회·구조화 정정](../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/UserReportController.kt) `[구현됨·실환경 NOT_RUN]` | 본인 신고 목록·상세·현재 content revision을 조회하고 `user_description`·`category_hint`를 각각 수정/비움/생략한다. 같은 idempotency key exact retry, stale revision, 다른 actor/generation도 시험한다. | 현재 본인 generation만 최소 내용을 보고 허용 필드 patch만 새 불변 content revision으로 추가한다. stale·소유권 불일치는 숨김/거부되고 기존 제출본 변경이나 기관 자동 재전송은 없다. |
| [신고 물리삭제 요청·상태](../../backend/app/services/report_deletion.py) `[구현됨·실환경 NOT_RUN]` | 본인 신고 삭제를 요청하고 상태를 `PENDING`, `LEGAL_HOLD`, `REJECTED`, `DELETED` 경로별로 재조회한다. worker 중단·재시작, 이미지/파생자료 실패, 외부 기관 사본 존재도 시험한다. | hold가 없을 때만 본문·이미지·삭제 가능한 파생자료를 물리삭제하고 내용 없는 최소 tombstone·영수증만 남긴다. 앱에는 삭제 상태와 외부 사본 수가 구분되어 보이며 외부 사본을 자동 삭제·전송하지 않는다. |
| 네트워크·용량 장애 격리 | Wi‑Fi/이동통신 설정, offline, Gateway/Backend 장애, stale capacity, 큰 응답을 보행 전·중에 재현한다. | 서버 기능만 명시적으로 지연·차단되고 로컬 안전 판단을 거짓 성공으로 바꾸지 않는다. 이동통신 OFF면 허용된 자료는 Wi‑Fi까지 대기한다. |
| [저주파 raw metadata 수집·업로드](../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/rawcollection/RawCollectionRuntimeCoordinator.kt) `[구현됨·실기기/운영 E2E NOT_RUN]` | raw 선택 동의 on/off에서 보행 ACTIVE 중 DETECTION/PERFORMANCE 집계자료를 만들고, 일시정지·재점검 완료·Wi-Fi·유효 walk lease 조건에서 Android→Gateway→Backend manifest/chunk/commit을 실행한다. 철회·계정삭제·보행종료·응답 유실도 시험한다. | 영상·음성·위치·경로 없이 저주파 집계 metadata만 ACTIVE 중 수집되고 ACTIVE/ENDED 중에는 업로드하지 않는다. Backend v2 exact `QUARANTINED` 영수증 뒤만 단말 자료를 지우며 철회·삭제·부분 종료 자료는 폐기한다. 실제 단말·Gateway·Backend E2E는 현재 `NOT_RUN`이다. |
| 계정 삭제 E2E | 2단계 확인, 준비 중 종료·재시작, offline 재시도, device evidence, 완료 후 재실행·재가입을 수행한다. | 확인 직후 수집·신고·queue·세션이 fence된다. 서버와 단말 삭제가 확인된 뒤만 새 등록으로 전환하고 실패 중에는 잠금을 유지한다. |
| 회전·큰 글자·스위치 접근 | 주요 화면에서 200% 글자, 회전, TalkBack·스위치 접근, 확인창 취소·복귀를 시험한다. | 선택·필터·진행상태는 복원되지만 비밀과 stale callback은 복원되지 않는다. 핵심 조작이 잘리거나 가려지지 않는다. |
| 15분 실제 보행 | 안전요원과 통제 경로에서 15분간 탐지·depth·GPS·걸음·경로·음성·신고를 함께 실행한다. | crash·무단 background 수집·미처리 안전중지가 없고 승인 profile 범위를 지킨다. 승인 profile이 없으면 PASS 대신 BLOCKED다. |

## 2. 관리자 Android 앱

| 기능 | 실행 절차 | 예상 결과 |
|---|---|---|
| 설치·역할 분리 | 서명된 관리자 앱을 사용자 앱과 함께 설치·업데이트하고 데이터·세션을 확인한다. | 별도 package·서명·저장소·배포 경계를 유지하고 사용자 자격으로 관리자 API를 쓸 수 없다. |
| 기기키 생성·운영 등록 `[실기기 AndroidKeyStore 공개키 등록 PASS·재설치/미등록 변형 NOT_RUN]` | 새 기기에서 P-256 descriptor를 만들고 통제된 절차로 공개키를 등록한다. 재설치·미등록 키도 시험한다. | 개인키는 AndroidKeyStore 밖으로 나오지 않고 네트워크 자체 등록은 없다. 등록된 현재 키만 proof를 통과한다. |
| 비밀번호·TOTP·기기증명 로그인 `[실기기 device proof+password/TOTP 정상 로그인 PASS·오류/replay 변형 NOT_RUN]` | 정상·오류 비밀번호/TOTP, TOTP replay, challenge replay·만료, 반복 실패를 실행한다. | 세 요소가 모두 맞아야 세션이 생기고 replay·rate-limit 우회가 차단된다. 비밀은 회전·로그에 남지 않는다. |
| 보안상태·세션 관리 `[실기기 state/session 조회 PASS·폐기/장애 변형 NOT_RUN]` | 상태·세션 목록, 현재 로그아웃, 다른 세션 폐기, offline·malformed 응답을 시험한다. | 현재 control/custody/device/session만 보이고 폐기 세션은 재사용되지 않는다. 오류 때 운영 UI가 잠긴다. |
| off-phone custody `[실기기 UNATTESTED 확인·ATTESTED/실복구 NOT_RUN]` | 실제 휴대전화 밖 복구자료를 준비한 경우에만 확인을 기록하고 미확인 상태도 시험한다. | 원 복구자료는 앱·서버로 전송되지 않고 opaque 참조만 남는다. 미확인 상태에서 고위험 작업은 잠긴다. |
| 분실 관리자 기기 폐기 | 기기 A에서 기기 B를 분실 신고하고 B의 기존 세션·키를 재사용한다. 현재 기기 self-target도 시도한다. | 대상 기기의 키와 세션이 함께 폐기되고 재사용할 수 없다. self-target은 거부된다. |
| 단일 관리자 복구 | 외부 복구코드로 start, 응답 유실 exact retry, 새 TOTP·비밀번호 complete, 이전 credential 재사용을 실행한다. | 복구 중 일반 운영은 잠기고 대상키 proof만 허용된다. 완료 후 이전 seed·코드·세션은 무효다. |
| 신고 운영 workflow `[복원 APK operations=false·custody UNATTESTED로 review/ZIP/기관 상태 NOT_RUN]` | 기본 release와 명시적으로 열린 통제 후보를 각각 실행한다. | 현재 release는 신고 검토·전달 mutation을 열지 않는다. 열린 후보도 NORMAL·custody ATTESTED·device session·재인증 없이는 변경하지 않는다. |
| 신고 목록·필터·상세 | ID·상태·유형·기간, 다음 페이지, 결과 없음, 오류 재시도, 상세와 회전 복원을 실행한다. | strict 최소 projection만 보이고 회전은 선택 ID만 보존한다. 세션·비밀은 다시 확인한다. |
| 신고 상태 변경 | 허용 다음 상태를 선택하고 비밀번호·TOTP로 재인증한다. stale version과 다른 action nonce도 보낸다. | 정확한 action/path/nonce·version의 허용 전이만 한 번 적용되고 자동 기관 재제출은 없다. |
| 검토 결정·이력 | 위치·사진·개인정보 검토 후 APPROVED/REJECTED/DUPLICATE를 기록하고 누락·중복대상을 시험한다. | 유효 결정만 append-only revision으로 남고 공개 사유가 필요한 결정은 사유 없이는 거부된다. 기존 이력을 덮지 않는다. |
| 기관 수동 제출용 ZIP | 승인된 revision에서 재인증 후 문서 선택기로 ZIP 저장·취소·실패를 시험한다. | 고정 revision의 package만 저장되고 저장 성공 전 전달 가능으로 기록하지 않는다. ZIP 생성은 기관 전송이 아니다. |
| 기관 수동 제출·ACK | 사람이 앱 밖 공식 채널로 ZIP을 제출한 뒤 SUBMITTED, 실제 접수번호 뒤 ACKNOWLEDGED, 처리 확인 뒤 RESOLVED를 기록한다. | 앱은 이메일·문자·기관 API를 호출하지 않는다. 실제 외부 행위 뒤에만 시각·기관·채널·접수번호·상태를 append-only로 기록한다. |
| 전달 replay·stale 보호 | 같은 idempotency key exact replay, 다른 payload 재사용, stale revision, 승인 전 제출, ACK 번호 누락을 실행한다. | exact replay만 같은 결과이고 충돌·stale·사전조건 누락은 거부된다. 과거 이력은 유지된다. |
| 사용자 정정·삭제 요청 운영 | 요청 상세를 보고 ACKNOWLEDGED→RESOLVED/REJECTED를 공개 답변·내부 메모와 함께 재인증해 변경한다. | 상태 CAS와 action 결속만 append-only 처리된다. 이 작업 자체가 원본 정정·물리삭제·기관 재전송을 수행하지 않는다. |
| [중대사고 record-only 화면](../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminIncidentPanel.java) `[Backend·관리자 UI 구현됨·실제 producer E2E NOT_RUN]` | 실제 허용 producer가 만든 `CRITICAL` 기록만 목록·상세로 조회하고 `OPEN`→`ACKNOWLEDGED`→`RESOLVED` 및 `REOPENED` 허용 전이를 사유·관찰·evidence SHA-256·재인증과 함께 기록한다. stale status version과 예상 밖 severity/필드도 시험한다. | 허용 전이만 append-only 사건으로 기록되고 충돌 시 최신 상태를 다시 표시한다. `RESOLVED`는 운영자가 해결 사실을 기록한 상태일 뿐 앱·서버가 자동 복구·자동 제어·자동 통지하지 않는다. 실제 producer→Backend→관리자 앱 종단 검증은 현재 `NOT_RUN`이다. |
| 관리자 감사 목록 | 사건 유형·관리자 ID로 조회하고 페이지 추가·오류 재시도를 실행한다. | proof·read purpose가 맞는 최소 감사 projection만 보이고 민감 원문·mutation 기능은 없다. |
| strict 응답·장애 격리 | 예상 밖 JSON, 잘못된 content type, 큰 body, timeout, Backend 장애 중 조회·변경을 시도한다. | 응답을 추측해 적용하지 않고 운영 UI를 실패·잠금 상태로 둔다. 관리자 장애가 사용자 로컬 안전기능을 중단시키지 않는다. |
| TalkBack·회전·비밀 제거 | TalkBack·큰 글자·회전으로 로그인→목록→상세→재인증→저장 흐름을 수행한다. | heading·label·상태가 읽히고 필터·선택만 복원된다. password·TOTP·recovery code는 비워진다. |
| 사설 배포·분실 drill | 별도 signer·사설 채널의 설치/업데이트/이전판 차단과 시험 기기 분실 후 권한·세션·키 폐기를 수행한다. | 사용자 앱과 다른 signer/채널을 유지하고 폐기 기기는 재설치만으로 권한을 되찾지 못한다. |

## 3. Android Gateway

| 기능 | 실행 절차 | 예상 결과 |
|---|---|---|
| 운영 시작·상태파일 보호 | 운영 계정으로 시작, 두 번째 process, key/lock/state 누락·권한 오류·symlink·손상과 재시작을 시험한다. | 정확한 단일 process와 보호된 저장소에서만 시작한다. 신뢰할 수 없으면 요청을 받지 않고 빈 정상상태로 초기화하지 않는다. |
| 단기 field session | 정상·오류 credential과 general/account-deletion-recovery scope로 로그인·조회·로그아웃한다. | 정확한 scope의 Secure/HttpOnly/no-store session만 생기고 recovery scope는 일반 보행·신고에 쓸 수 없다. |
| [이메일 OTP 가입·비밀번호 로그인 relay](../../apps/android-gateway/src/account-relay.ts) `[로컬 PostgreSQL+Mailpit PASS·운영 SMTP NOT_RUN]` | `/api/account-enrollments/email-otp`, `/api/accounts`, 이메일+비밀번호 field session을 정상·과대·여분 필드·오류 OTP·stale handle·응답 유실 조합으로 호출한다. | Gateway는 bounded exact JSON만 Backend로 중계하고 이메일·OTP·비밀번호·enrollment handle을 telemetry에 남기지 않는다. 로컬 정상경로 성공을 외부 받은편지함·운영 SMTP 성공으로 대신 쓰지 않는다. |
| 장기 기기 로그인·refresh `[기능 활성 환경]` | device login, access 만료, refresh, 응답 유실 retry, 소비 refresh 재사용과 다른 family를 시험한다. | actor/device/family 결속 회전과 exact retry만 허용한다. 재사용 family만 폐기되고 raw refresh는 URL·로그·평문 저장소에 남지 않는다. |
| 기기 목록·원격 폐기 | 자기 기기 목록, 다른 기기 폐기, 다른 actor device ID와 잘못된 query를 보낸다. | 본인 active 기기만 보이고 폐기는 멱등이다. 다른 actor와 허용되지 않은 query는 노출 없이 거부된다. |
| field walk ledger | 같은 계정 두 세션에서 조회·acquire·renew·takeover·end와 stale operation/request ID를 실행한다. | 계정당 live walk 하나와 서버시각 lease만 권위가 있다. exact retry는 멱등이고 stale/mismatch는 기존 권한을 바꾸지 않는다. |
| 목적지 검색 proxy | 인증 유무, 정상·빈·긴 query, provider timeout·오류, 큰 응답을 실행한다. | 인증된 허용 query만 Backend로 전달되고 strict/bounded 결과만 반환한다. provider 비밀을 노출하지 않는다. |
| 경로 proxy | 정상·경계 밖 좌표, 큰·느린 body, upstream timeout을 실행한다. | bounded 인증 요청만 전달되고 strict 경로만 반환한다. 실패를 기존 경로 성공으로 바꾸지 않는다. |
| 신고 v2 upload·receipt | 수동/자동 purpose와 metadata, transport header all-or-none, 동일 ID exact retry, 과대·동시 upload를 시험한다. | server-derived actor와 purpose가 맞는 bounded 요청만 전달한다. exact payload만 같은 receipt이고 충돌·과대는 Backend fetch 전에 거부된다. |
| 신고 transport 상태 | 본인·다른 actor·없는 ID로 조회하고 Backend 예상 밖 필드를 보낸다. | 본인 persisted receipt와 coarse 상태만 보이고 다른 actor와 없음은 같은 응답이다. strict projection 밖 응답은 거부된다. |
| [내 신고 내용·정정·삭제 상태 proxy](../../apps/android-gateway/src/routes.ts) `[구현됨·실환경 NOT_RUN]` | 목록·상세·현재 content, 구조화 correction, 삭제 request/status를 generation·cursor·expected revision·idempotency·여분 필드 조합으로 보낸다. | 현재 actor generation의 strict 최소 projection과 exact replay만 허용한다. 정정은 허용 필드의 새 revision이고 삭제 상태는 Backend 결과를 그대로 제한 투영하며 기관 자동 재전송은 없다. |
| 통합 동의 v1.1 bootstrap·CAS `[신규 가입 로컬 PASS·전체 변형/운영 E2E NOT_RUN]` | 현재 local receipt 조회, 알려진 v1 local state, local 404를 각각 만들고 Backend bootstrap을 호출한다. `CURRENT_CONSENT`·`SIGNUP_CONSENT`·`RECONSENT_REQUIRED`, 이전 Backend receipt CAS, exact retry, stale receipt와 다른 actor·generation·installation을 시험한다. | Gateway는 인증된 동일 account generation만 Backend에 중계한다. bootstrap을 허용 receipt로 승격하지 않고 Backend v1.1 exact receipt 뒤에만 local ledger를 갱신하며 stale CAS·교차계정·Backend 409에서는 기존 ledger를 바꾸지 않는다. |
| 계정 삭제 durable fence | 첫 요청, 응답 유실 retry, 잘못된 capability, status, device evidence CAS를 실행한다. | Backend 전송 전 generation이 영구 fence되고 capability로 멱등 복구한다. 오류 뒤에도 fence/outbox를 유지한다. |
| capacity 전달 | fresh NORMAL/DEGRADED/BLOCKED와 stale/없는 상태에서 신고·raw 입장을 시도한다. | fresh 단조 version만 전달되고 stale/unknown은 허용으로 해석되지 않는다. 로컬 안전기능과 서버 저장 차단을 구분한다. |
| 요청 제한·보안 응답 | method/content type/body/time/rate limit, malformed path/query, cache·CORS, 비정상 upstream을 시험한다. | 계약별 오류와 no-store 경계가 일관되고 secret·actor assertion·내부 경로가 노출되지 않는다. |
| 재시작·key rotation·outbox | 동의·session·삭제 outbox 상태에서 kill/restart, 허용 key rotation, 구 key 제거·손상을 시험한다. | 허용 keyring만 기존 상태를 복호화하고 새 write는 active key를 쓴다. 미완료 작업을 복구하며 손상을 정상으로 가장하지 않는다. |
| [raw upload relay](../../apps/android-gateway/src/raw-collection-relay.ts) `[구현됨·운영 E2E NOT_RUN]` | v7 general session, 현재 account generation, raw 동의, active walk lease, Wi-Fi 조건에서 manifest HEAD/POST, chunk HEAD/PUT, status GET, commit POST를 실행한다. 누락 조건·과대 body·redirect·동시 write·receipt 불일치도 시험한다. | 네 조건과 strict bounds가 모두 맞을 때만 Backend ingest로 전달되고 전역 단일 write를 지킨다. Backend v2 `QUARANTINED` exact receipt만 반환하며 Gateway local ledger hash를 Backend consent receipt로 대신 쓰지 않는다. 실제 운영 종단 결과는 `NOT_RUN`이다. |

## 4. Backend

| 기능 | 실행 절차 | 예상 결과 |
|---|---|---|
| startup·readiness | 후보 migration/config/key로 시작하고 DB/PostGIS, model warmup, image key, issuer/TOTP, raw 경로를 하나씩 깨뜨린다. | process 생존과 실제 readiness가 구분되고 필수 결속 하나라도 틀리면 해당 mutation을 fail-closed한다. |
| 역할 분리·migration | migration→issuer bind→runtime 순서를 실행하고 runtime 계정의 migration/bind, 병렬 시작, 구 env 재사용을 시도한다. 현행 head `202608300005`을 확인하고 v1.1 동의 evidence가 있는 016 downgrade와 계정 자료가 있는 migration 009 downgrade도 시도한다. | 서로 다른 계정·키 경계와 migration head `202608300005` 전제만 허용하고 API 계정은 owner 권한을 얻지 않는다. v1.1 동의 또는 계정·enrollment·가입 receipt 자료가 남은 downgrade는 거부되어 기존 자료가 보존된다. |
| 서버 capacity | 정상·저하·차단·측정 중단·TTL 만료·재시작 version을 만들고 상태·readiness를 조회한다. | fresh 단조 version만 반환하고 stale/없음은 503이다. 용량 차단은 저장 입장만 막는다. |
| TMAP 목적지·경로 `[운영 provider NOT_RUN]` | 실제 계정으로 정상·없음·경계 좌표·timeout·rate limit과 provider health를 시험한다. | secret은 Backend에만 있고 허용 host·TLS만 사용한다. 정상만 strict 결과로 변환하고 장애는 명시 실패다. |
| 탐지 책임 경계 | 사용자 E2E 중 Backend detect 호출 유무와 legacy/debug endpoint를 격리 환경에서 확인한다. | 사용자 위험탐지는 단말 TFLite에서 끝난다. legacy/fake endpoint 성공은 출시 탐지 PASS가 아니다. |
| 손상 점자블록 신고 | 정상/다른 class, model key, GPS/depth, actor/generation, purpose, JPEG 크기·metadata 오류를 보낸다. | 허용된 손상 신고만 저장하고 실패 시 DB·파일·receipt 일부를 남기지 않는다. |
| 신고 멱등·status | 동일 report ID exact retry, 같은 ID 다른 payload, 응답 유실 뒤 조회, 다른 actor 조회를 실행한다. | exact payload만 같은 persisted receipt를 반환하고 충돌을 거부한다. owner generation 외에는 숨긴다. |
| 이미지 정제·암호화·원본 접근 | EXIF 포함 이미지, key rotate/decrypt-only/compromised, 직접 uploads 접근, 목적 grant 재사용을 시험한다. | 정제한 AES-256-GCM envelope만 저장한다. 관리자 proof·재인증·목적 grant·감사 뒤 한 번만 복호화한다. |
| 사용자 신고·요청 원장 | 목록·상세·정정/삭제 intent를 owner/generation/cursor/idempotency 조합으로 시험한다. | owner에게 최소 상태만 보이고 intent는 append-only 접수만 한다. 원본·이미지는 이 단계에서 바뀌지 않는다. |
| 관리자 신고 projection·CAS | proof/read purpose로 목록·상세 후 허용 상태 전이를 재인증하고 stale version을 보낸다. | strict projection과 허용 다음 상태만 보이고 정확한 action/path/nonce·version의 변경만 한 번 적용된다. |
| 검토 결정 | APPROVED/REJECTED/DUPLICATE, 검토 3항목, 공개 사유·중복 ID·exact retry를 실행한다. | 유효 결정만 append-only revision으로 남고 과거 결정을 덮지 않는다. 승인 전 package를 열지 않는다. |
| 기관 package·전달 이력 | 승인 revision에서 package를 만들고 사람이 제출한 뒤 SUBMITTED→ACKNOWLEDGED→RESOLVED/FAILED를 기록한다. | package 생성은 외부 제출이 아니다. 실제 관찰 사실과 접수번호만 append-only로 기록하고 외부 API를 자동 호출하지 않는다. |
| 사용자 요청 관리자 처리 | 요청 status를 공개 답변·내부 메모와 함께 CAS 변경하고 stale·wrong nonce를 실행한다. | 허용 전이만 기록되고 RESOLVED 표시는 별도 정정·물리삭제 effect를 만들지 않는다. |
| 관리자 인증·세션·복구 | provisioning 후 password/TOTP/device proof, replay, custody, lost revoke, recovery start/retry/complete를 수행한다. | 세 요소와 현재 control 상태를 모두 요구한다. 복구 중 일반 mutation은 잠기고 완료 뒤 구 credential·세션은 무효다. |
| [이메일 계정 enrollment](../../backend/app/api/accounts.py) `[로컬 SMTP/PostgreSQL 정상경로 PASS·운영/오류변형 NOT_RUN]` | 사용자 입력 생년월일과 이메일로 OTP를 요청하고 6자리 OTP·비밀번호·필수/선택 동의로 계정을 만든다. 만 14세 경계, 오류·만료·재전송·replay·동일 이메일 중복·SMTP 응답 유실을 시험하고 계정 자료가 있는 migration 009 downgrade도 시도한다. | 입력 생년월일상 만 14세 이상이며 OTP와 필수 동의가 맞을 때만 actor와 가입 receipt가 생긴다. 선택 false는 허용되고 이메일은 암호화, OTP는 검증용 digest, 비밀번호는 단방향 hash로 저장된다. 자료가 남은 009 downgrade는 거부되고 PASS/SMS 명의·연령확인을 대신했다고 주장하지 않는다. |
| 통합 동의 v1.1 bootstrap·event `[신규 가입 PostgreSQL PASS·전체 변형/운영 E2E NOT_RUN]` | privacy event가 없는 현재 signup receipt, 현재 v1.1 event, 과거 v1 event·withdrawal을 각각 준비해 bootstrap한다. 명시 재동의를 이전 receipt CAS, exact retry, stale receipt, 다른 actor·generation·installation과 DB 오류로 실행한다. | zero-event 현재 signup만 `SIGNUP_CONSENT`, 현재 event는 `CURRENT_CONSENT`, stale·불명확 상태는 `RECONSENT_REQUIRED`다. exact replay는 CAS보다 먼저 같은 receipt를 반환하고 stale CAS·교차계정·오류는 새 event나 허용 확장을 만들지 않는다. |
| 계정 삭제 worker v4 | 요청·status·device evidence, worker 재시작, DB·이미지·raw·journal 단계별 실패와 retry를 실행한다. DB commit 응답 유실 뒤 같은 이메일로 새 enrollment를 만든 다음 v4 journal을 reconcile한다. | generation이 먼저 fence되고 삭제 시작 시 고정한 enrollment UUID만 단계별로 멱등 삭제한다. 이후 생성된 같은 이메일 enrollment는 보존되고, 외부 manifest에는 UUID나 이메일 HMAC을 노출하지 않으며 요구 범위와 receipt가 충족된 뒤만 완료한다. |
| [raw ingest v2](../../backend/app/api/raw_collections.py) `[구현됨·운영 E2E NOT_RUN]` | ingest 비활성/활성 후보에서 manifest HEAD/POST, chunk HEAD/PUT, status, commit을 순서·중복·hash·size·retry·암호화 조합으로 실행한다. consent·walk·generation·tombstone 불일치도 보낸다. | 비활성 설정은 수신하지 않고 활성 후보도 현재 Backend consent·walk·manifest 결속과 exact commit digest가 맞아야 암호화 저장한다. 신규 commit은 v2 `QUARANTINED` 영수증을 반환하며 실제 운영 DB/object store E2E는 `NOT_RUN`이다. |
| [raw 14일 `QUARANTINED`](../../backend/app/services/raw_collection_lifecycle.py) `[구현됨·운영 lifecycle NOT_RUN]` | commit 직후와 정확한 14일 경계에서 REPORT/TRAINING 사람 승인·거부, current consent 철회, account deletion, legal hold, idempotency/CAS와 worker 재시작을 시험한다. | 두 목적 결정은 독립 append-only 기록이고 승인은 원본 검역기간을 연장하지 않는다. 유효한 legal hold가 없으면 승인 여부와 무관하게 14일 원본을 삭제하고 내용 없는 삭제 영수증을 남긴다. |
| [승인 학습자료 3년](../../backend/app/services/training_dataset_lifecycle.py) `[구현됨·실데이터/학습 NOT_RUN]` | 최신 training 동의, 사람의 TRAINING 승인, 비식별 pass, 허용 kind의 sanitized artifact를 순서대로 준비해 immutable dataset revision을 만들고 철회·계정삭제·manifest 변조·3년 경계를 시험한다. | 세 gate와 현재 동의를 모두 통과한 sanitized artifact만 dataset에 편입되고 정확 위치·음성·영상 등 금지 원본은 제외된다. revision은 승인 시각부터 3년이며 철회·삭제·만료 뒤 후속 학습 gate를 통과하지 못한다. 실제 데이터와 모델 학습은 `NOT_RUN`이다. |
| [신고 구조화 정정 effect](../../backend/app/services/report_content_corrections.py) `[구현됨·운영 E2E NOT_RUN]` | `user_description`·`category_hint` 허용 patch와 금지/여분 필드, no-change, stale revision, idempotency 충돌, 제출 전·후 정정을 실행한다. | 원본 사진·위치·시각·모델 결과는 불변이고 허용 내용만 새 content revision으로 남아 재검토가 필요하다. 제출 후 기존 package bytes를 바꾸지 않고 재승인한 v2 정정 package가 이전 package를 참조한다. |
| [신고 물리삭제](../../scripts/delete_reports.py) `[구현됨·운영 worker NOT_RUN]` | 삭제 요청을 접수·처리한 뒤 legal hold 유/무, preview/apply, CAS 변화, worker 실패·재시작, DB·이미지·파생물·기관사본을 시험한다. | hold 없을 때 전용 worker가 본문·이미지·삭제 가능한 파생자료를 물리삭제하고 원문·위치 없는 최소 tombstone과 영수증만 남긴다. 기관사본 수와 상태는 별도로 남기며 기관 사본 삭제로 가장하지 않는다. |
| 중대사고 record-only `[Backend·관리자 client 구현됨·실제 producer E2E NOT_RUN]` | 실제 허용 producer와 비허용 source에서 stable ID duplicate, 5개 `CRITICAL` reason, ACKNOWLEDGED·RESOLVED·REOPENED 상태 CAS와 관리자 재인증을 시험한다. | 허용 producer와 CRITICAL 기준만 append-only 상태 사건을 만들고 `RESOLVED`는 관찰된 해결 사실의 기록으로만 해석한다. 자동 복구·자동 제어·외부 자동 통지는 수행하지 않는다. |
| [raw 보존·삭제 수동 도구](../../scripts/manage_raw_collection_retention.py) `[구현됨·운영 apply NOT_RUN]` | legacy v1 180일과 v2 14일 경계에서 preview, 승인된 수동 apply, reconcile, account deletion 우선, legal hold, 파일/DB 불일치·symlink를 시험한다. | preview는 무변경이고 apply는 정확 후보만 최소권한으로 삭제해 영수증을 남긴다. v2 목적 승인은 14일을 연장하지 않고 자동 timer가 임의 실행되지 않는다. |
| backup·격리 restore tombstone `[격리·별도 cluster PASS·운영 복원 NOT_RUN]` | DB+images+raw+파생자료를 빈 격리 환경에 복원해 외부 서명 tombstone ledger가 없음·손상·stale head·정상인 경우를 각각 시험하고 inventory를 대조한다. | 외부 ledger와 trusted head/cutoff를 검증해 tombstone을 모든 저장소에 먼저 재적용한 뒤 대조가 일치할 때만 ingress를 연다. ledger가 없거나 검증·재적용이 실패하면 서비스 공개를 차단하며 격리 개발 훈련을 운영 복원 성공으로 대신 쓰지 않는다. |
| 요청 보안·실패 원자성 | body/time/rate limit, media/path/query 오류, CORS/no-store, DB/file/audit 단계별 실패를 실행한다. | 계약 밖 요청은 일관된 오류이고 비밀·stack을 노출하지 않는다. 일부만 성공한 상태를 정상으로 반환하지 않는다. |
| debug·legacy 격리 | production ingress에서 debug, legacy reports/detect, 직접 Backend 접근을 시도한다. | 현 제품 Gateway 경로 외 debug/legacy는 공개되지 않고 debug 성공을 release PASS로 쓰지 않는다. |
| 사용자 안전과 운영 장애 격리 | 관리자·DB·provider·기관 기록 장애 중 사용자 로컬 탐지와 서버 기능을 함께 관찰한다. | 운영 장애가 사용자 기기 내 안전출력을 멈추지 않는다. 서버 실패는 사용자에게 실패로 보이고 성공으로 조작되지 않는다. |

## 5. 출시·실환경 종단

| 기능 | 실행 절차 | 예상 결과 |
|---|---|---|
| 사용자·관리자 서명/설치/업데이트 | 같은 후보의 두 release APK를 지정 채널에서 clean install·업데이트하고 package·role·data 분리와 잘못된 signer·downgrade를 시험한다. | 두 앱의 package/signing/distribution 경계를 유지하고 올바른 업데이트만 허용한다. debug/unsigned는 출시 PASS가 아니다. |
| Gateway·Backend 후보 배포 `[운영 배포 NOT_RUN]` | 같은 후보의 Gateway·Backend·migration·설정으로 maintenance 전환 후 외부 단말에서 TLS readiness와 허용 경로를 확인한다. | migration·key·provider·readiness가 모두 맞을 때만 실제 TLS ingress가 열린다. localhost·USB reverse 결과는 출시 PASS가 아니며 운영 배포 전에는 `NOT_RUN`이다. |
| 사용자→관리자→기관→사용자 E2E `[NOT_RUN]` | 손상 블록 신고, Backend receipt, 관리자 검토·ZIP 저장, 사람이 기관 공식 채널로 제출, 실제 ACK 기록, 사용자 상태 재조회를 수행한다. | 같은 actor/report/content/package revision이 유지되고 실제 ACK 뒤만 상태가 바뀐다. 앱·Gateway·Backend는 기관 API·이메일·문자 전송을 자동 실행하지 않는다. |
| 자동제어·자동외부전송 부재 | 신고 검토·ZIP 생성·중대사고 ACK/RESOLVED·raw 승인 중 네트워크 호출과 process/service 상태를 관찰한다. | ZIP은 사람이 저장·제출할 자료일 뿐 자동 전송되지 않고 중대사고 상태 변경도 restart·복구·기기 제어를 실행하지 않는다. 예상 밖 외부 호출이나 제어가 한 건이라도 있으면 FAIL이다. |
| TalkBack 실기기 E2E | 두 앱에서 TalkBack만으로 가입·기기점검·보행·신고·검토·오류·복구를 수행한다. | 화면을 보지 않고 필수 흐름을 완료하며 TTS 중복·초점 손실·label 없는 조작·접근 불가 결함이 없다. |
| 통제 현장 보행 | 승인 기기·장착·환경·안전요원으로 정지시험 후 15분 보행, 이탈·점자블록·TTS/진동·망 장애·중지를 수행한다. | 승인 수치와 안전중지 조건을 만족하고 위해·미처리 near miss가 없다. profile·안전계획이 없으면 BLOCKED다. |
| 다기기·관리자 분실 복구 | 사용자 두 기기 보행 인계와 관리자 예비 기기·외부 복구자료로 분실 폐기·복구를 실제 운영 DB에서 수행한다. | 사용자 live walk는 하나이고 분실 관리자 키·세션은 폐기된다. 구 기기는 권한을 되찾지 못한다. |
| queue·capacity·raw 출시 gate `[실기기/운영 E2E NOT_RUN]` | 승인 기기 profile로 queue 포화·강제종료·lock 경쟁, server capacity, Android→Gateway→Backend raw v2, 14일 검역·독립 목적검토·비활성 경계를 확인한다. | 실제 byte cap·reserve·stale fail-closed와 v2 `QUARANTINED` exact receipt가 확인된다. 구현 테스트만으로 gate를 PASS로 만들지 않으며 승인 E2E 전에는 출시 후보에서 raw를 열지 않는다. |
| 개인정보·위치 검토 | 최종 문안, 처리방침 URL, 위치 약관, 수탁·제3자·국외이전, Data safety를 실제 traffic·SDK와 대조해 김민호와 권한 있는 검토자가 확인한다. | 모든 placeholder가 실제 값으로 채워지고 문안·코드·배포가 일치할 때만 승인한다. 미확정 하나라도 있으면 NOT_APPROVED다. |
| 정식 279건·5개 gate | 이름 붙인 동일 후보와 승인 환경에서 279건과 5개 미면제 gate를 각각 실행한다. 재실행은 새 run으로 남긴다. | NOT_RUN/BLOCKED/FAIL을 성공으로 합치지 않는다. 모든 요구 결과 전에는 출시 GO가 아니다. |
| 업데이트·서비스 복구 | 두 Android 앱, Gateway, Backend, DB migration, APK 내장 모델·config를 승인 순서로 업데이트하고 의도한 장애 뒤 정상 후보를 복구한다. | 세대가 섞인 상태를 운영하지 않고 삭제 fence·key 호환성을 보존한다. 실제 복구 확인 전 ingress를 다시 열지 않는다. |
