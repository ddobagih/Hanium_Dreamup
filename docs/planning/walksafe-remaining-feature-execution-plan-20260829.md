# WalkSafe 잔여 기능 실행 계획 — 2026-08-29

## 1. 목적과 판정 원칙

이 문서는 `docs/planning/walksafe-full-feature-reassessment-20260828.html` 이후 구현된
기능과 실제 환경에서 아직 실행하지 않은 항목을 분리한다. 8월 28일 재평가와 이전
`DECISION` 표시는 후속 구현을 반영하지 못하므로 현재 코드·migration·자동 회귀 결과를
우선한다.

다음 원칙을 적용한다.

- 기능 구현과 자동 회귀 통과는 실기기·운영·법률·기관 검증 완료를 뜻하지 않는다.
- 기관 제출은 사람이 수행하며 자동 제출·자동 복구·자동 제어를 추가하지 않는다.
- Backend consent receipt가 동의의 권위 근거이고 Gateway local ledger hash는 감사·진단용이다.
- 외부 공급자·승인 수치·운영 자격증명·실행 증거를 임의로 만들지 않는다.
- 독립적인 구현·가벼운 검증은 병렬화할 수 있지만 Gradle 전체 회귀, 전체 pytest,
  PostgreSQL 통합처럼 무거운 전체 검증은 서로 겹치지 않는다.

## 2. 이번에 고정한 제품 결정

1. 가입은 이메일·비밀번호와 이메일로 전송한 6자리 OTP를 사용한다.
2. 사용자가 입력한 생년월일을 서울 날짜 기준으로 계산해 만 14세 미만 가입을 서버에서
   차단한다. 현재 방식은 명의 또는 공적 연령 확인이 아니다.
3. PASS·통신사 SMS 본인확인은 비용·계약이 준비된 뒤 검토할 향후 메모이며 현재 앱은
   호출하지 않는다.
4. 필수·선택 동의는 회원가입 때 받는다. 현재 통합 동의는 `FP-013-1.1.0`이며 로그인 후
   Backend bootstrap과 명시 재동의로 현재 receipt를 만든다.
5. 기기점검은 로그인 뒤 사용자가 전경 화면에서 시작한다. 현재 세션에 결속된
   `FULL_PASS` 또는 `LIMITED_PASS` 전에는 주요 기능을 열지 않는다.
6. 음성 입력은 faster-whisper STT, 음성 안내는 Qwen3-TTS 경로를 사용한다. 실제 모델 실행과
   품질 평가는 별도이며 `길라잡이` wake-word는 승인 자산 전까지 기본 OFF다.
7. 신고 정정은 허용 필드의 append-only content revision이고, 물리삭제는 별도 최소권한
   worker가 수행해 내용 없는 tombstone만 남긴다.
8. raw 검역 원본은 commit 뒤 최대 14일이고, 사람이 승인하고 비식별 검사를 통과한
   sanitized 학습자료는 dataset revision 승인시각부터 최대 3년이다.
9. `CRITICAL` incident는 기록·조회·상태 전이만 수행한다. 상태 변경이 서비스나 기기를
   자동으로 복구·제어·통지하지 않는다.
10. 복원은 외부 서명 tombstone ledger와 trusted head/cutoff가 없거나 불일치하면 ingress를
    열지 않는 fail-closed 계약을 따른다.

## 3. 현재 구현 상태

| 번호 | 기능 | 코드 상태 | 아직 필요한 완료 증거 |
|---:|---|---|---|
| 1 | 이메일 OTP 가입·비밀번호 로그인 | `IMPLEMENTED` — Backend account 원장·migration 009, Gateway strict relay, Android 가입·로그인 흐름 | 실제 SMTP 전달, 운영 PostgreSQL·TLS 후보의 가입→로그인 E2E |
| 2 | 사용자입력 DOB·만 14세 미만 차단 | `IMPLEMENTED` — OTP 발급 전과 계정 생성 시 서버 재검증 | 실제 날짜 경계 E2E. PASS/SMS 공급자는 향후 메모이며 현재 미구현 |
| 3 | 통합 동의 v1.1 | `IMPLEMENTED` — signup receipt, `CURRENT_CONSENT`/`SIGNUP_CONSENT`/`RECONSENT_REQUIRED` bootstrap, 이전 Backend receipt CAS, migration 016 | 실제 PostgreSQL upgrade/downgrade, 가입→로그인→bootstrap→재동의 종단, 최종 법률 문안 승인 |
| 4 | 로그인 뒤 전경 기기점검 | `IMPLEMENTED` — 사용자 시작, lifecycle·session 결속, FULL/LIMITED gate와 CameraX 제한 preflight | 지원폰별 권한·열·저장공간·frame·latency·TalkBack 실측과 승인 profile |
| 5 | faster-whisper STT·Qwen3-TTS | `IMPLEMENTED` — Backend voice, Gateway strict relay, Android client·WAV 검증 | 실제 모델 다운로드·추론·청취·마이크·발열·배터리 평가 |
| 6 | `길라잡이` wake-word | `EXTERNAL` — 승인 자산 전까지 controller default-OFF | asset·SHA-256·라이선스와 offline FAR/FRR·발열·배터리 실측 |
| 7 | Gateway raw upload·Backend raw ingest v2 | `IMPLEMENTED` — Backend receipt를 권위 근거로 사용하고 Gateway audit hash와 분리 | 실폰→Gateway→Backend/object store, Wi-Fi·walk lease·응답 유실 운영 E2E |
| 8 | production report queue | `IMPLEMENTED / DEFAULT-OFF` — build profile, 암호화 실제 byte cap, 자동신고 한도·직접신고 reserve, 철회·계정삭제 lifecycle | 지원폰 capacity 값 승인과 활성 build 포화·강제종료·file-lock 시험 |
| 9 | 신고 구조화 정정 | `IMPLEMENTED` — `user_description`·`category_hint` allowlist, append-only revision, CAS·idempotency, 제출 뒤 정정 package | 운영 DB·이미지·재검토·기관 제출 뒤 정정 E2E |
| 10 | 신고 물리삭제 | `IMPLEMENTED` — legal hold, 독립 worker, 본문·이미지·삭제 가능 파생자료 삭제, content-free tombstone | 실제 최소권한 DB 역할·object store·기관 사본·중단 복구 E2E |
| 11 | raw 검역 원본 14일 | `IMPLEMENTED` — 목적별 사람 승인과 무관하게 raw 만료를 연장하지 않는 lifecycle·receipt | 운영 object store inventory와 수동 apply/reconcile, 정확한 14일 경계 실행 |
| 12 | 승인 sanitized 학습자료 3년 | `IMPLEMENTED` — 현재 동의·사람 승인·비식별 gate, immutable dataset revision·lineage·만료 | 실제 승인 데이터·삭제/철회·3년 경계와 실제 학습 실행 |
| 13 | 계정 삭제 | `IMPLEMENTED` — generation 선행 fence, 단계별 재시도, v4 내부 journal의 frozen enrollment UUID | 실제 DB·파일·raw·외부 ledger E2E와 commit 응답 유실 뒤 동일 이메일 신규 가입 경쟁 시험 |
| 14 | record-only `CRITICAL` incident | `IMPLEMENTED` — migration 015, Backend append-only projection/API, 관리자 Android 목록·상세·재인증 상태 전이 | 실제 허용 producer→Backend→관리자 앱 E2E |
| 15 | restore tombstone 재적용 | `IMPLEMENTED / FAIL-CLOSED` — 외부 ledger 검증·trusted head/cutoff·inventory 불일치 시 공개 차단 | 실제 외부 서명 ledger·운영 backup을 이용한 DB+images+raw+파생자료 격리 복원 drill |
| 16 | 개인정보·법률 문안 | `NOT_APPROVED` — 현재 문안과 동의 version은 코드에 결속되지만 출시 승인값이 아님 | 사업자·수탁자·처리방침/삭제 URL·책임자·국외이전 등 placeholder 확정과 권한 있는 검토 |
| 17 | 운영 배포·기관 수동 제출 | `EXTERNAL` — 자동 제출·자동 제어 없음 | object store·KMS·TLS·backup, 서명 APK, 실제 기관 수동 ACK 증거 |
| 18 | 실기기·정식 통합 시험 | `NOT_RUN` | 같은 불변 후보로 TalkBack·15분 보행·279건·5개 gate 실행 |

## 4. 구현된 핵심 계약

### A. 가입·인증·migration

- 공개 가입 입력은 exact JSON과 크기 제한을 적용하고 이메일·OTP·비밀번호를 telemetry에 남기지 않는다.
- OTP digest, 암호화 이메일, 단방향 비밀번호 hash만 서버 원장에 저장한다.
- 가입 receipt에는 당시 문서 version과 필수·선택 응답을 불변으로 남긴다.
- migration 009는 계정·enrollment·가입 동의 자료가 남아 있으면 downgrade를 거부하고 자료를
  보존한다. 비어 있는 경우에만 downgrade가 가능하다.

### B. 통합 동의 v1.1

- 현재 policy는 `FP-013-1.1.0`이고 raw·자동신고·학습 항목은 각 `1.1.0`, 모바일 전송 설정은
  `FP-013-MOBILE-1.0.0`이다.
- 로그인 뒤 Gateway current 조회를 먼저 사용한다. 현재 local receipt가 없거나 알려진 v1이면
  인증된 Backend bootstrap을 사용한다.
- 현재 Backend v1.1 event가 있으면 `CURRENT_CONSENT`, privacy event가 없고 가입 문서가 정확히
  현재면 `SIGNUP_CONSENT`, stale·불명확 상태면 `RECONSENT_REQUIRED`로 모든 선택 기능을 막는다.
- bootstrap 결과 자체는 기능 허용 receipt가 아니다. Android가 명시 저장하고 Backend가 발급한
  현재 v1.1 receipt를 받은 뒤에만 raw·신고·학습 gate를 연다.
- PUT은 `expected_previous_backend_receipt_sha256`로 최신 subject receipt를 CAS한다. exact request
  replay는 CAS보다 먼저 판정하고, stale CAS·다른 actor/account generation/installation은 로컬
  ledger를 바꾸지 않는다.
- migration 016은 v1 evidence를 역사 자료로 보존하면서 신규 event는 v1.1만 허용한다. v1.1
  evidence가 있으면 downgrade를 거부한다.

### C. 기기·음성

- 로그인한 actor·session·attempt generation과 현재 foreground lifecycle에 결속된 결과만 인정한다.
- Depth 지원 기기는 FULL, 명시적 미지원이면서 CameraX 제한 preflight를 통과한 기기는 LIMITED다.
  background·timeout·stale·필수 기능 실패는 기능을 잠근다.
- faster-whisper 결과는 intent와 앱 안전정책을 통과한 경우에만 동작 후보가 된다. Qwen3-TTS는
  bounded WAV 검증 뒤 재생하며 안전 중요 안내는 단말 TTS·진동 fallback을 유지한다.

### D. raw·신고·학습·삭제

- raw upload는 현재 account generation, Backend 동의 receipt, active walk lease, 전송 조건을
  모두 확인하고 Backend v2 `QUARANTINED` exact receipt 뒤에만 단말 자료를 삭제한다.
- 신고 정정은 원본 사진·위치·시각·모델 결과와 기존 기관 package를 변경하지 않는다.
- 신고 물리삭제와 계정 삭제는 요청 접수와 실제 effect를 분리하고 복구 가능한 journal과 독립
  receipt를 사용한다. 계정 삭제 v4 journal은 삭제 시작 시점의 enrollment UUID만 고정해 이후 같은
  이메일로 만든 신규 enrollment를 삭제 대상으로 오인하지 않는다.
- raw 사람 승인과 학습 편입은 raw 14일 보존을 연장하지 않는다. 학습에는 승인된 sanitized
  artifact만 들어가며 정확 위치·원본 음성·영상은 허용하지 않는다.

### E. 중대사고·복원

- CRITICAL reason allowlist와 stable idempotency key만 기록하고
  `OPEN → ACKNOWLEDGED → RESOLVED`, `REOPENED → ACKNOWLEDGED` 전이를 append-only로 남긴다.
- ACK·해결·재개는 관리자 재인증·device proof·사유·관찰 근거·CAS를 요구한다.
- 복원 tombstone 검증은 복원 DB 밖 원장과 trusted head/cutoff가 필수다. 실패 시 ingress는
  닫힌 채로 남고 실제 운영 복원 완료로 표시하지 않는다.

## 5. 남은 외부 실행

| 묶음 | 사전 입력 | 실행 증거 | 완료 판정 |
|---|---|---|---|
| 이메일 | 운영 SMTP 계정·발신 도메인·비밀 보관·rate limit | 실제 메일 수신, 오류·만료·재전송·응답 유실 원자료 | 이메일 OTP 가입·로그인 E2E PASS |
| 향후 본인확인 | PASS/정식 SMS 공급자·계약 주체·sandbox/production 정보 | 공급자 callback·취소·중복·timeout 원자료 | 현재 출시 범위 밖 메모이며 선정 전에는 호출하지 않음 |
| 개인정보·법무 | 실제 사업자·수탁자·URL·책임자와 권한 있는 검토자 | 서명된 review/approval ID와 최종 문안 | `NOT_APPROVED` 제거 가능 판정 |
| 음성·모델 | 고정 model revision, 실행 장비, `길라잡이` 승인 자산이 있다면 그 hash·권리 | 실제 STT/TTS 추론·청취와 wake-word 오탐/미탐·발열·배터리 원자료 | 모델별 승인 범위만 runtime 허용 |
| 기기 | 지원폰 후보, OS·권한·장착·배터리·열·저장공간 상태 | FULL/LIMITED/FAIL, queue 포화·강제종료·frame/latency·TalkBack·진동 원자료 | 승인 profile 범위만 기능 개방 |
| 운영·복원 | PostgreSQL, object store, KMS, TLS, backup, 외부 tombstone ledger | 실제 write/read/delete, key rotation, 격리 restore, inventory 대조·경보 | 모든 저장소 tombstone 재적용 뒤만 ingress 공개 |
| 기관 | 수동 제출 담당, 공식 기관 채널·수신 담당 | package hash·제출시각·실제 ACK·처리 증빙 | 앱 저장 성공과 기관 접수 모두 확인 |
| 출시 | 불변 candidate, 두 앱 signer, 배포 channel, 정식 QA 역할 | APK·server·model hash, 279건, 5개 gate | 독립 GO/NO-GO이며 `NOT_RUN`을 PASS로 바꾸지 않음 |

## 6. 다음 실행 단계와 검증

1. 운영 입력 전 자동 회귀 고정
   - Backend consent/account/privacy 회귀, Gateway 전체, 사용자 Android 전체를 같은 코드에서 확인한다.
   - 현재 확인 기준은 migration head `202608290016`, Backend 동의 slice 264건 통과,
     Gateway 144/144 통과, 사용자 Android 전체 unit test 통과다.
2. 개발 환경 통합
   - 로컬 SMTP 시험 서버와 PostgreSQL에서 이메일 OTP 가입→로그인→동의 bootstrap→v1.1 저장을
     실행한다.
   - 검증: v1 재동의, stale receipt CAS, 교차계정·교차 generation·응답 유실을 포함한다.
3. 실제 기기
   - 로그인 뒤 전경 기기점검, CameraX LIMITED, STT/TTS, queue·raw·신고·삭제를 실제 지원폰에서
     순서대로 실행한다.
   - 검증: lifecycle 전환, 권한 철회, 발열·저장공간·네트워크 장애와 TalkBack을 포함한다.
4. 운영 저장소·복원
   - 운영과 동일한 PostgreSQL/object store/KMS/TLS 후보에서 raw 14일, 학습 3년, 신고·계정 삭제,
     외부 tombstone 복원을 실행한다.
   - 검증: DB·images·raw·파생자료 inventory가 일치하기 전 ingress가 열리지 않아야 한다.
5. 법률·기관·출시
   - 최종 문안과 URL을 승인하고, 사람이 기관에 제출해 실제 ACK를 기록한 뒤 같은 불변 후보로
     정식 279건과 5개 gate를 수행한다.

가벼운 독립 테스트는 병렬 실행할 수 있다. CPU·메모리·디스크 사용량을 보면서 작업 수를 조정하되
임의의 고정 lane 수나 worker 수를 강제하지 않는다. 다만 전체 Gradle, 전체 pytest,
PostgreSQL 통합처럼 무거운 전체 suite는 서로 동시에 실행하지 않는다.

## 7. 이번 범위에서 하지 않는 일

- PASS/SMS 공급자를 현재 이메일 가입 흐름에 임의로 연결하지 않는다.
- 승인값을 임의 숫자로 정해 report queue나 미승인 기기 profile을 production 활성화하지 않는다.
- `길라잡이` 자산·라이선스 없이 상시 마이크 wake-word를 열지 않는다.
- 자유서술 정정문을 parser로 해석하거나 관리자 요청만으로 즉시 물리삭제하지 않는다.
- 외부 tombstone ledger 없이 복원 완료를 주장하지 않는다.
- 기관 자동 제출, 운영 자동 복구·자동 제어·자동 통지를 추가하지 않는다.
- 자동 회귀를 실기기·실모델·운영·법률·기관·정식 279건 완료로 주장하지 않는다.
