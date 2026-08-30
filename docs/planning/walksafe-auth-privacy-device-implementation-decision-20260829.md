# WalkSafe 가입·개인정보·기기점검 구현 결정 — 2026-08-29

## 1. 이번 결정

- 신규 회원은 **만 14세 이상만 가입**할 수 있다. 만 14~17세도 보호자 연동 없이 가입할 수 있고, 만 14세 미만 가입·법정대리인 동의 가입은 이번 제품 범위에서 지원하지 않는다.
- 현재 서버는 **사용자가 입력한 생년월일**을 대한민국 표준시 날짜 기준으로 계산해 만 14세 미만을 차단한다. 이는 본인·실명·명의 확인이나 공적 연령 인증이 아니다.
- 가입 인증은 입력한 이메일로 6자리 OTP를 발송하고, OTP 확인 뒤 비밀번호 계정을 생성하는 방식이다. 휴대폰 번호·PASS는 현재 가입 payload와 계정 schema에 없다.
- 서비스 약관과 개인정보 처리 고지·동의는 회원가입 중 받는다. 필수 항목과 선택 항목을 구분하고, 선택 항목은 기본 OFF이며 모두 거부해도 계정을 만들 수 있어야 한다.
- 카메라·마이크·정확한 위치 같은 OS 권한은 가입 동의와 별개로 해당 기능을 처음 쓰기 직전에 설명하고 요청한다.
- 기기점검은 계정 생성과 로그인 뒤 **전경 화면에서 사용자가 시작**한다. 백그라운드에서 자동 시작하지 않는다.
- 기기점검이 `FULL` 또는 명시적인 `LIMITED`가 되기 전에는 보행·길안내·탐지·신고 기능을 열지 않는다.
- 모델 서명·원격 모델 교체는 출시 단계로 미룬다. 현재는 APK/AAB에 포함된 모델과 설정의 SHA-256 검증, 실패 시 안전 중지만 유지한다.
- 실제 기관 제출은 계속 사람이 앱 밖에서 수행한다. 기관 자동 전송이나 자동 복구·자동 제어는 추가하지 않는다.

> 상태 기준: 코드에 구현된 계약과 운영 환경에서 실제로 검증된 결과를 구분한다. 이 문서의 `구현됨`은 저장소 코드·migration·테스트 계약이 존재한다는 뜻이며, 운영 SMTP·실기기·object storage·KMS·backup·기관 연동을 사용한 검증은 별도 표기가 없으면 `E2E NOT_RUN`이다. 출시 적합성은 **NOT_APPROVED**이다.

## 2. 현재 가입·기기점검 흐름

```text
목적·안전 고지
  → 이메일·생년월일 입력
  → [필수] 서비스 이용약관
  → [필수] 개인정보 처리 고지·동의 또는 고지 확인
  → [필수] 위치기반서비스 이용약관(핵심 위치기능을 제공하는 경우)
  → [선택] raw v2 자료 처리 / 자동신고 / 비식별 AI 개선·학습
  → OTP 발급 요청
      └─ 서버가 사용자 입력 생년월일로 만 14세 이상 여부 계산
          ├─ 만 14세 미만/입력 오류 → OTP·enrollment·계정 생성 없이 종료
          └─ 만 14세 이상 → 입력 이메일로 6자리 OTP 발송
  → OTP·비밀번호·가입 동의 제출
  → 계정 생성
  → 로그인
  → 로그인 주체에 FP-013 v1.1 선택 receipt bootstrap·확인
  → 전경 JIT 설명·필요 권한 요청
  → 사용자가 전경 기기점검 시작
      ├─ FULL → 전체 지원 프로필
      ├─ LIMITED → 지원 가능한 기능만 명확히 표시해 허용
      └─ FAIL → 앱 기능 잠금, 원인·재시도 안내
  → 안전교육·연습
  → 기능 사용
```

현재 Android는 OTP 요청 전에 필수 동의 3개를 로컬에서 먼저 확인하고, OTP 발급 뒤에도 선택값을 포함한 가입 동의를 계정 생성 시 다시 제출한다. 가입 receipt는 Backend에 불변 증거로 남고, 로그인 뒤 authenticated bootstrap과 명시 저장을 거쳐 현재 FP-013 v1.1 Backend receipt를 만든다. 이 코드 경로의 자동 회귀는 통과했으며 운영 SMTP·PostgreSQL 종단은 `E2E NOT_RUN`이다.

가입 전 임시 상태에는 불투명 `enrollment_handle`, 단계·만료시각·정책 버전·선택값을 암호화해 저장한다. Backend enrollment DB에는 원문 생년월일 열이 없지만 이메일과 생년월일에 결합된 request HMAC은 저장한다. 이메일은 암호화본과 조회 HMAC으로, OTP는 HMAC으로 저장하며 비밀번호 원문은 저장하지 않는다. `enrollment_handle`은 OTP 검증과 계정 생성에만 쓰고 로그인 session을 요구하지 않아 가입 전 credential의 순환 의존을 만들지 않는다.

## 3. 향후 유료 휴대폰·PASS 본인확인 공급자 메모 — 현재 미구현

이 절은 향후 대안을 위한 조사 메모이며 현재 가입 흐름의 설명이 아니다. 현재 앱·Gateway·Backend에는 휴대폰 번호, PASS callback, CI/DI, 통신사 명의대조 또는 본인확인 공급자 연동이 없다. 공급자·계약 주체·비용·수신 항목·보유기간이 모두 미확정이므로 도입 상태는 **NOT_APPROVED**이다. [KISA 본인확인기관 현황](https://identity.kisa.or.kr/web/main/contents/M010-03), [KISA 휴대폰 본인확인 방식](https://identity.kisa.or.kr/web/main/contents/M030-02)

| 향후 조사 후보 | 공식 공개 자료 | 현재 판정 |
|---|---|---|
| NICE아이디 | [휴대폰 본인확인 상품](https://www.niceid.co.kr/prod_mobile.nc), [도입 절차](https://www.niceid.co.kr/serv_qstn.nc) | `[NOT_APPROVED: 계약·견적·수신 항목·처리 국가]`; 현재 미연동 |
| KCB OKName | [공식 서비스 안내](https://www.ok-name.co.kr/) | `[NOT_APPROVED: 계약·견적·수신 항목·처리 국가]`; 현재 미연동 |
| 다날 | [본인확인 서비스](https://www.danalpay.com/service_introduction/personal_check), [계약 절차](https://www.danalpay.com/service_application/application_process) | `[NOT_APPROVED: 계약·견적·수신 항목·처리 국가]`; 현재 미연동 |
| KG이니시스 | [통합인증 연동 안내](https://manual.inicis.com/sa/auth.html) | `[NOT_APPROVED: PASS와 법정 본인확인 기능 범위·계약]`; 현재 미연동 |

도입을 다시 검토할 때는 `[NOT_APPROVED: 계약 주체]`, 최소 수신 항목, CI/DI 필요성, callback 서명·replay 방지, sandbox/운영 전환, 위탁·제3자 제공·국외이전, 보유·파기 시점을 먼저 확정한다. 그 전에는 공급자 선택, 휴대폰 본인확인 또는 PASS 연동 완료라고 표현하지 않는다.

## 4. 만 14세 정책과 개인정보 원칙

개인정보 보호법 제22조의2는 만 14세 미만 아동의 동의 기반 처리에 법정대리인 동의와 확인을 요구한다. 이번 제품은 그 절차를 지원하지 않으므로 서버 계산 결과가 만 14세 미만이면 OTP·enrollment·계정을 만들지 않는다. 계산 입력은 사용자가 직접 입력한 생년월일이며 신원이나 실제 생년월일을 증명하지 않는다. [개인정보 보호법 제22조의2](https://www.law.go.kr/법령/개인정보보호법/제22조의2), [개인정보보호위원회 아동·청소년 안내서](https://www.pipc.go.kr/np/cop/bbs/selectBoardArticle.do?bbsId=BS217&mCode=G010030000&nttId=10896)

단순한 `만 14세 이상입니다` 체크 대신 서버가 날짜를 계산하지만, 자기입력 생년월일만으로 충분한 연령확인인지에 대한 법률 검토는 `[NOT_APPROVED: 연령확인 방식 적정성]`이다. 개인정보보호위원회도 실제 연령확인 절차가 없는 사례에 개선을 권고했다. [개인정보보호위원회 사례](https://www.pipc.go.kr/np/cop/bbs/selectBoardArticle.do?bbsId=BS074&mCode=C020010000&nttId=8776)

가입 동의와 OS 권한은 분리한다.

- 가입 화면: 서비스 약관, 개인정보 처리, 위치기반서비스 약관을 필수 3개로, raw v2 자료 처리·자동신고·비식별 학습 재사용을 선택 3개로 구분한다. 선택값은 기본 OFF이다.
- 현재 코드는 위치기반서비스 이용약관을 필수로 검사한다. 법적 필수 여부와 위치기반서비스사업 해당성은 `[NOT_APPROVED: 위치정보 법률 검토]`이다.
- 기능 실행 직전: 카메라·마이크·정확한 위치를 왜 지금 쓰는지 짧게 다시 고지하고 Android 권한을 요청한다.
- 선택 동의를 모두 거부해도 계정과 기기 내 기본 안전기능은 사용할 수 있어야 한다. 선택 데이터가 필요한 기능만 정확히 제한한다.
- 가입 receipt에는 선택 3개만 저장한다. 로그인 후 FP-013 v1.1에서는 `MOBILE_NETWORK_TRANSFER`가 네 번째 독립 boolean으로 통합 동의 receipt에 남고 Wi-Fi/셀룰러 전송 설정에 매핑된다. 개인정보 목적 동의와 완전히 분리된 설정으로 옮기는 결정은 `[NOT_APPROVED: mobile 항목 최종 schema/UI]`이다.
- active raw v2 경로는 현재 `DETECTION`·`PERFORMANCE` metadata만 허용하며 영상·음성·이미지·정확한 위치·이동 경로를 업로드하지 않는다. 서버 v2 검역은 commit부터 최대 14일이다.
- 학습 재사용은 별도 선택 동의, 사람 승인, 비식별 검사 통과와 정제 manifest를 모두 갖춘 정제 이미지·라벨·metadata만 dataset revision에 편입하고 승인일부터 최대 3년 보유한다. 정확한 위치·원본 음성·식별 가능한 제3자 얼굴은 제외한다. [개인정보보호위원회 이동형 영상기기 안내서](https://pipc.go.kr/np/cop/bbs/selectBoardArticle.do?bbsId=BS217&mCode=G010030020&nttId=10679)

동의 v1.1은 Android·Gateway·Backend의 version·authenticated bootstrap·이전 Backend receipt CAS·migration 016과 raw·report·training의 exact policy/item-version 소비 gate까지 **구현됨**이다. Android 전체 1,280건, Gateway 전체 144건, Backend 동의 표적 264건 자동 회귀가 통과했다. PostgreSQL 실환경 통합 63건은 DB URL이 없어 `NOT_RUN`으로 남는다.

처리방침은 `[NOT_APPROVED: 실제 사업자명·수탁자·수신기관·보유기간·국외이전·개인정보보호 책임자 연락처]`와 `[NOT_APPROVED: 공개 개인정보처리방침 URL]`, `[NOT_APPROVED: 앱 밖 계정 삭제 URL]`을 확정해야 한다. [개인정보 보호법 제30조](https://www.law.go.kr/법령/개인정보보호법/제30조), [개인정보보호위원회 2026 처리방침 작성지침](https://www.pipc.go.kr/np/cop/bbs/selectBoardArticle.do?bbsId=BS217&mCode=D010030000&nttId=12018), [Google Play 사용자 데이터 정책](https://support.google.com/googleplay/android-developer/answer/10144311?hl=ko)

## 5. 전경 기기점검 판정

지원 스마트폰 목록을 가입 전에 고정하는 대신 실제 설치 기기에서 점검한다. 다만 검증되지 않은 기기를 무조건 통과시키는 방식은 아니다.

| 결과 | 조건 | 기능 개방 |
|---|---|---|
| `FULL` | 필수 권한·카메라·GPS·TTS·진동·저장공간·배터리·열 상태가 정상이고 ARCore metric depth도 신뢰 가능 | 승인된 전체 기능 |
| `LIMITED` | 필수 안전기능은 정상이지만 metric depth 등 선택 하드웨어가 명시적으로 미지원이며 CameraX 보조 같은 승인된 제한 모드가 가능 | 제한 사유를 화면·TalkBack으로 알리고 해당 기능만 비활성 |
| `FAIL` | 필수 권한 거부, 필수 하드웨어 미지원, 저장공간/배터리/열 상태 차단, 무결성 오류 | 모든 앱 기능 잠금, 원인과 해결 방법 제공 |
| `NOT_RUN`·`REQUESTING_PERMISSIONS`·`RUNNING` | 시작 전 또는 점검 진행 중 | 통과로 간주하지 않고 기능 잠금 유지 |

현재 상세 점검 snapshot은 process-local이며 actor·로그인 session generation·시도 generation에 결속한다. 별도의 암호화 상세결과 저장이나 앱/OS/profile 버전 binding은 구현돼 있지 않다. background 전환, session 변경, depth unknown/timeout은 `FAIL`이고 stale binding 결과는 통과로 승격하지 않는다. 계측 카메라 검사는 로그인 뒤 사용자가 `권한과 기기 기능 점검 시작`을 누른 전경에서만 수행한다.

**코드 상태: 구현됨.** `PostLoginDeviceCheckPolicy`, `FirstRunOnboardingPolicy`와 `MainActivity`가 사용자 시작, JIT 권한, `FULL`/`LIMITED` 통과 증거, `FAIL`/`NOT_RUN` 잠금과 현재 session feature gate를 연결한다. **운영 상태: E2E NOT_RUN.** 승인 실기기 표본에서 카메라·ARCore depth·한국어 오프라인 TTS·background/stale·제한 모드까지 통과시킨 release-candidate 증적은 `[NOT_APPROVED: 실기기 E2E receipt]`이다. 점검 결과 영속화나 버전 binding 추가 여부는 `[NOT_APPROVED: 기기점검 persistence 정책]`이다.

## 6. STT·TTS 구현과 `길라잡이` 호출어 분리

- **STT 구현됨:** 사용자 시작 전경 버튼 경로의 Gateway STT와 Android 온디바이스 `SpeechRecognizer` one-shot 경로가 있다. 상시 청취나 호출어 탐지가 아니다.
- **TTS 구현됨:** Gateway TTS client 경로와 `AndroidFeedbackActuator`의 Android `TextToSpeech` 오프라인 한국어 fallback·안전/상호작용/길안내 우선순위 queue가 있다.
- **호출어 미구현/default-off:** `WakeWordController`는 승인 profile이 있을 때만 시작 가능한 gate이지만 production profile은 `null`이다. 저장소 assets에 승인된 `길라잡이` `.ppn`/`.pv` 또는 자체 KWS 모델이 없고, controller는 현재 마이크나 모델을 직접 열지 않는다.
- 실기기 STT/TTS 운영 E2E와 호출어 공급자·asset 승인은 각각 `[NOT_APPROVED: 음성 실기기 E2E receipt]`, `[NOT_APPROVED: 호출어 모델·라이선스·asset SHA-256]`이다.

다음 표는 호출어를 나중에 도입할 경우의 공식 자료 조사이며 현재 구현을 뜻하지 않는다.

| 후보 | 장점 | 차단점 | 판정 |
|---|---|---|---|
| Picovoice Porcupine | 한국어 custom phrase와 Android 온디바이스 실행을 공식 안내한다. [Android 안내](https://picovoice.ai/docs/quick-start/porcupine-android/), [한국어 FAQ](https://picovoice.ai/docs/faq/porcupine/) | AccessKey, 재배포·상용 조건, 안전 중대 용도 제한을 서면으로 해소해야 한다. [이용약관](https://picovoice.ai/docs/terms-of-use/) | `[NOT_APPROVED: 계약·asset·기기 검증]` |
| 자체 LiteRT KWS | 모델·라이선스·오프라인 동작을 직접 통제할 수 있다. [TensorFlow audio tutorial](https://www.tensorflow.org/tutorials/audio/simple_audio) | 한국어 양성·유사어·소음 데이터, 학습, threshold, FAR/FRR 검증 부담이 크다. | Porcupine 계약 불가 시 대안 |
| Edge Impulse | custom 학습 도구가 있다. [Android KWS](https://docs.edgeimpulse.com/tutorials/topics/android/keyword-spotting) | 외부 배포 조건과 NDK/JNI 통합 부담이 있다. | 우선순위 낮음 |
| openWakeWord | 오픈소스 | 공식 문서상 현재 영어 중심이며 Android 통합이 없다. | 제외 |

Porcupine을 포함한 어떤 후보도 `길라잡이` Android asset, 버전·SHA-256·재배포 권한, 안전보조 앱 사용에 대한 서면 허용, offline cold-start와 기기별 오탐·미탐·발열·배터리 결과가 확보되기 전에는 production profile에 넣지 않는다.

## 7. 기능 구현 1~7 재검토 결과

| 결정 | 저장소 구현 상태 | 운영 환경 E2E |
|---|---|---|
| 1. Gateway raw upload | Android·Gateway·Backend 경계 구현됨; Backend ingest 기본 OFF | `NOT_RUN` |
| 2. 신고 정정 | Android UI/client·Gateway relay·Backend API/service/schema 구현됨 | `NOT_RUN` |
| 3. 신고 물리 삭제 | Backend API/service·최소권한 수동 worker·schema 구현됨 | `NOT_RUN` |
| 4. raw v2 14일 검역 | Backend lifecycle·수동 retention worker·v2 receipt 구현됨 | `NOT_RUN` |
| 5. 승인 학습자료 3년 | 사람 승인·비식별·dataset lifecycle 코드 구현됨 | production 모델 lineage·만료/철회 적용 `NOT_RUN` |
| 6. 관리자 중대 장애 | trusted in-process recorder·record-only workflow·API/UI 구현됨 | 실제 runtime producer 배선 `NOT_RUN` |
| 7. backup 복원 tombstone 재적용 | 서명 bundle 검증·계획·generic adapter·publish gate 구현됨 | concrete adapter·ledger publisher·복원 drill `NOT_RUN` |

위 `구현됨`은 해당 저장소 경계의 코드와 테스트 계약이 있다는 뜻이다. 동일 release candidate와 운영 자격증명·외부 인프라를 사용한 전체 경로 성공을 뜻하지 않으며, 1~7 모두 운영 E2E 증적은 `[NOT_APPROVED: 기능별 운영 E2E receipt]`이다.

### 1) Gateway raw upload

**코드 상태: 구현됨·Backend ingest 기본 OFF / 운영 E2E: NOT_RUN.**

- Backend의 현재 동의 event와 receipt를 유일한 허용 근거로 사용한다.
- Gateway local hash는 `gateway_audit_record_sha256`처럼 이름부터 감사 전용으로 분리하고 업로드 허용 판단에 쓰지 않는다.
- 단기·장기 로그인 방식과 무관하게 같은 `actor + account_generation`의 동의를 조회한다.
- Gateway는 인증 actor, active walk, Backend 동의 상태를 body read 전에 확인한다. Backend 장애·철회·세대 불일치는 body를 읽지 않고 거부한다.
- Backend는 Gateway를 신뢰해 생략하지 않고 같은 receipt를 다시 검증한다.

### 2) 신고 정정

**코드 상태: Android UI/client·Gateway·Backend 구현됨 / 운영 E2E: NOT_RUN.**

- 원본 사진, 캡처 위치·시각, 모델 결과, 제출 package는 불변이다.
- 사용자가 작성한 설명과 허용된 분류 보조값만 allowlist patch로 수정한다.
- `expected_revision`과 idempotency key를 요구하고 새 content revision을 만든다.
- 새 revision은 기존 검토를 무효화하고 재검토한다.
- 이미 기관에 제출한 package는 바꾸지 않고 새 정정 package를 만들어 이전 제출과 연결한다.

### 3) 신고 물리 삭제

**코드 상태: Backend·수동 worker 구현됨 / 운영 E2E: NOT_RUN.** 실제 object storage·KMS·backup·기관 사본 삭제 경로는 검증되지 않았다.

- 삭제 요청 접수와 실제 삭제를 분리한다. legal hold가 없을 때만 최소권한 worker가 본문·이미지·삭제 가능한 파생물을 삭제한다.
- tombstone에는 원문·위치·사진을 넣지 않고 불투명 report/subject key, 삭제시각, 범위, receipt digest, hold 판정만 남긴다.
- 관리자 앱은 worker를 직접 호출하지 않는다.
- 이미 기관에 전달된 사본은 앱이 삭제했다고 주장하지 않고 기관 요청·회신 상태를 별도로 기록한다.
- 백업에서 부활하지 않도록 7번 복원 절차와 결속한다.

### 4) raw v2 14일 검역

**코드 상태: 구현됨 / 운영 E2E: NOT_RUN.** active Android raw v2는 `DETECTION`·`PERFORMANCE` metadata만 만들며, 사용자의 명시적 재확인으로 `PAUSED`가 된 뒤 조건을 재검사해 업로드한다. `END`에서는 raw upload를 시작하지 않고 partial을 폐기한다.

- 14일은 서버가 전체 object를 검증·commit한 시각부터 센다.
- 검역 대상 raw v2 자료와 서비스 신고 증거를 구분한다. 신고 처리에 필요한 자료를 raw 검역 만료와 함께 잘못 지우지 않는다.
- collection은 v2 `QUARANTINED` 상태로 두고, `REPORT`·`TRAINING` scope별 사람 결정을 append-only `APPROVED`/`REJECTED` event로 분리한다.
- 14일 안에 어느 목적으로도 승인되지 않은 v2 자료는 삭제 영수증과 함께 삭제한다.
- hold는 법적 근거, 승인자, 사유, 만료시각이 모두 있을 때만 허용한다.

### 5) 승인 학습자료 3년

**코드 상태: dataset lifecycle 구현됨 / production 모델 lineage·운영 E2E: NOT_RUN.** 실제 운영자가 승인한 production dataset, 학습 run lineage, 만료·철회 적용 증적은 `[NOT_APPROVED: production model lineage receipt]`이다.

- 별도 AI 선택 동의, 항목별 사람 승인, 비식별 검사를 모두 통과한 자료만 dataset revision에 편입한다.
- 정확한 위치, 원본 음성, 제3자 얼굴이 포함된 원본은 기본 제외한다.
- 3년은 dataset revision 승인시각부터 센다.
- 철회·만료 시 향후 dataset과 후속 모델 학습에서 제외하고 삭제 가능한 source·feature·cache를 제거한다.
- 이미 학습된 모델에서 즉시 완전 제거된다고 약속하지 않는다. 영향 모델 lineage 기록과 다음 승인 모델 제외는 운영 실행·검증 전까지 완료로 표시하지 않는다.

### 6) 관리자 중대 장애

**코드 상태: trusted in-process recorder·record-only workflow·API/UI 구현됨 / 실제 runtime producer 배선·운영 E2E: NOT_RUN.** 이 상태는 자동 복구·자동 제어 구현을 의미하지 않는다.

- 허용된 내부 producer 또는 권한 있는 운영자만 stable incident ID로 생성한다.
- `CRITICAL`은 사용자 안전기능의 광범위 중단, 데이터 무결성·보안 사고, 광범위한 잘못된 안전출력으로 한정한다.
- `OPEN → ACKNOWLEDGED → RESOLVED`, 동일 사고 재발 시 `REOPENED`만 허용한다.
- ACK·해결·재개에는 관리자 재인증, 사유, 관찰 근거를 요구하고 전이를 append-only로 남긴다.
- 이 기능은 장애 기록·표시만 하며 자동 복구나 자동 제어를 수행하지 않는다.

### 7) 백업 복원 tombstone 재적용

**코드 상태: 서명 검증·재적용 계획·generic adapter interface·read-only checker·publish gate 구현됨 / concrete adapter·ledger publisher·운영 복원 drill: NOT_RUN.**

- 삭제 원장은 복원 대상 DB와 분리한 append-only 서명 저장소에 둔다.
- 복원은 격리 환경에서 수동으로 수행하고 trusted head/cutoff를 확인한다.
- report DB row와 결속된 report upload artifact에 tombstone을 재적용하고 완전한 restore inventory를 대조한 뒤에만 ingress를 연다. 현재 계약 범위를 raw·학습 object 전체로 확대해 표현하지 않는다.
- 원장·서명키·trusted head 중 하나라도 없거나 불일치하면 서비스를 열지 않는다.
- 운영 object store·KMS·backup과 독립 trust anchor를 사용한 실제 복원 PASS는 `[NOT_APPROVED: 운영 복원 receipt]`로 남겨 둔다.

## 8. 현재 구현 상태

현재 저장소에서 확인되는 코드 경계와 남은 운영 검증은 다음과 같다.

| 영역 | 현재 판정 | 근거·남은 경계 |
|---|---|---|
| 생년월일 만 14세 차단 | **구현됨** | Backend가 자기입력 생년월일을 서울 날짜로 계산하고 만 14세 미만에는 OTP·enrollment를 만들지 않음; 본인·명의·공적 연령 인증 아님 |
| 이메일 OTP·계정·로그인 | **구현됨** | Android→Gateway→Backend 6자리 OTP, 암호화 이메일/HMAC, scrypt password digest, account/session 흐름 존재; 운영 SMTP E2E는 `NOT_RUN` |
| 가입 전 enrollment credential | **구현됨** | opaque handle은 OTP 검증·계정 생성에만 사용하고 로그인 session을 요구하지 않음 |
| 필수/선택 가입·통합 동의 v1.1 | **구현됨** | 가입 receipt, authenticated bootstrap, 이전 receipt CAS, 철회, exact-version 소비 gate와 migration 016 존재; 운영 PostgreSQL E2E는 `NOT_RUN` |
| 로그인 후 사용자 시작 전경 기기점검 | **구현됨** | JIT 권한, 계측, state machine evidence와 feature gate 연결; 운영 실기기 E2E는 `NOT_RUN` |
| 기능 결정 1~7 | **구현 경계별 상태는 7절** | 1~4 구현, 5 lifecycle 구현·model lineage `NOT_RUN`, 6 recorder/API/UI 구현·producer `NOT_RUN`, 7 검증/publish gate 구현·drill `NOT_RUN` |
| 버튼형 STT·TTS | **구현됨** | Gateway speech와 on-device `SpeechRecognizer`/`TextToSpeech` 경로 존재; 운영 실기기 E2E는 `NOT_RUN` |
| `길라잡이` wake word | **default-off / asset 없음** | production profile `null`; 승인 model·SDK·라이선스 없음 |
| 휴대폰·PASS 본인확인 | **현재 미구현** | 향후 유료 공급자 조사만 존재; 계약·callback·CI/DI schema 없음 |

주요 코드 기준은 `backend/app/services/accounts.py`, `backend/app/models.py`, `apps/android-gateway/src/account-relay.ts`, Android의 `GatewayAccountClient.kt`, `EmailAccountEnrollment.kt`, `FirstRunOnboardingPolicy.kt`, `PostLoginDeviceCheckPolicy.kt`, `MainActivity.kt`이다. 기능 결정 1~7의 구현 경계는 각 raw/report/training/incident/restore service와 migration, 대응 테스트에 있다. 이 문서 갱신 자체는 해당 테스트를 재실행했다는 증적이 아니다.

## 9. 출시 전 남은 검증과 미확정값

| 검증 영역 | 현재 상태 | 출시 전 필요한 결과 |
|---|---|---|
| 가입·통합 동의 v1.1 | `AUTOMATED PASS / PostgreSQL E2E NOT_RUN` | 자동 회귀는 통과. 실제 PostgreSQL에서 가입→로그인→bootstrap→재동의, exact replay·stale CAS·철회·선택 전체 false 검증 필요 |
| 운영 이메일 가입 | `E2E NOT_RUN` | `[NOT_APPROVED: SMTP 서비스/수탁자·host·sender·처리 국가·로그 보유기간]` 확정 후 실제 수신·만료·재전송·실패·계정 로그인 검증 |
| 만 14세 정책 | `E2E NOT_RUN` | 경계일·시간대·재시도·replay 시험과 `[NOT_APPROVED: 자기입력 생년월일 방식의 법적 적정성]` |
| 전경 기기점검 | `E2E NOT_RUN` | 승인 실기기 matrix에서 FULL/LIMITED/FAIL/background/stale·권한 변경 검증 |
| raw·신고·학습 | `E2E NOT_RUN` | 운영 Gateway/Backend/object storage/KMS에서 v2 14일 검역, PAUSED 전송, END raw 미전송, 신고 정정·삭제, 사람 승인 학습자료 3년 lifecycle과 `[NOT_APPROVED: production model lineage receipt]` 검증 |
| 중대 장애·backup 복원 | `E2E NOT_RUN` | 실제 incident producer 배선과 운영 관리자 credential 검증; concrete restore adapter·ledger publisher·별도 trust anchor·backup을 사용한 tombstone 재적용 drill |
| STT·TTS·wake word | `E2E NOT_RUN` | 실기기 한국어 offline STT/TTS 검증; wake word는 `[NOT_APPROVED: 공급자·라이선스·asset·오탐/미탐 기준]` 전까지 OFF |
| 법무·Play 공개 | `NOT_APPROVED` | `[NOT_APPROVED: 운영 사업자·수탁자·TMAP 역할·수신기관·보유기간·국외이전·책임자]`, `[NOT_APPROVED: 개인정보처리방침/약관/외부 계정 삭제 URL]`, 실제 traffic과 Data safety 일치 |

출시 검토는 [개인정보보호위원회 개인정보 처리방침 작성지침](https://www.pipc.go.kr/np/cop/bbs/selectBoardArticle.do?bbsId=BS217&mCode=D010030000&nttId=12018), [개인정보 보호법 제30조](https://www.law.go.kr/법령/개인정보보호법/제30조), [Google Play 사용자 데이터 정책](https://support.google.com/googleplay/android-developer/answer/10144311?hl=ko)과 [Google Play Data safety 안내](https://support.google.com/googleplay/android-developer/answer/10787469?hl=ko)를 실제 처리와 대조해 수행한다. 위 표의 `NOT_RUN` 또는 `[NOT_APPROVED: …]` 중 하나라도 남으면 출시 상태는 `NOT_APPROVED`이다.
