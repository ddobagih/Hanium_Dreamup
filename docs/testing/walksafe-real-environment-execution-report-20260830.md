# WalkSafe 실환경 기능 실행 보고서 — 2026-08-30

> 상태: **로컬 실행 완료 / 출시 승인 아님**  
> 기준 시각: 2026-08-30 23:35 KST  
> 기준 Git commit: `2c2fb2609ab80c26c1c055d6953ba93f5e4f5a79` + 검증 중인 미커밋 기능 변경  
> 판정 원칙: 코드·로컬 통합·실기기·외부 운영 결과를 서로 대신하지 않는다. 비밀번호, OTP, 세션, 음성 서비스 token과 암호화 키는 이 문서에 기록하지 않는다.

> 2026-08-30 후속 판정: 정상 데모는 가능하지만, [실운영 전 필수 데이터베이스 보강 백로그](../planning/walksafe-production-database-hardening-backlog-20260830.md)를 완료하기 전에는 운영 배포하지 않는다.

## 판정

- `PASS`: 적힌 환경과 범위에서 실제 실행 결과가 기대 결과와 일치함
- `LIMITED`: 일부 실제 경로만 확인했으며 수동 또는 외부 경로가 남음
- `NOT_RUN`: 실행에 필요한 입력·환경·승인이 없어 실행하지 않음
- `NOT_APPROVED`: 문안이나 운영 결정을 권한 있는 사람이 아직 확정하지 않음

## 요약

| 단계 | 현재 판정 | 확인한 범위 | 남은 범위 |
| --- | --- | --- | --- |
| 1. 개발 통합시험 | `PASS` | head `202608300005`의 PostGIS+Mailpit에서 가입→OTP→계정 생성→로그인→동의→음성→로그아웃 종단 실행 | 운영 후보 DB·SMTP·TLS 환경 재실행 |
| 2. 실제 이메일 연결 | `LIMITED` | 검증된 STARTTLS와 Mailpit 실제 수신 | 운영 SMTP 업체·발신 주소·수신 주소를 정한 외부 수신 |
| 3. 실제 스마트폰 | `LIMITED` | SM-G981N에서 사용자 가입·OTP·자동 로그인·기기점검 이력, 최신 기종 독립 기기점검 APK 설치·cold launch, TFLite·AndroidKeyStore 계측시험 6건과 관리자 AndroidKeyStore 키 등록·device proof·비밀번호/TOTP 로그인·상태/세션 조회 | 최신 APK 로그인 뒤 실제 진동 확인·`길라잡이` 발화·오프라인 한국어 TTS·GPS fix·CameraX/탐지·Depth 종단점검, TalkBack·실외 보행·지속 발열, 다른 제조사·Android 버전의 동일 기능시험 |
| 4. 실제 음성·모델 | `PASS`(로컬 서비스·Gateway·Android JVM) / `LIMITED`(실기기) | faster-whisper·Qwen3-TTS 실제 추론, Gateway relay, Vosk 동일 모델 호출어/명령 구현·APK/model 설치 | 로그인·ACTIVE 보행의 실제 호출어 마이크·스피커·오탐/미탐·장시간 자원 평가 |
| 5. 운영 저장소·복원 | `LIMITED` | 암호화 backup·서명 manifest·격리 restore·삭제/raw DB 시험, 서로 다른 PostgreSQL cluster tombstone 재적용 | 운영 후보·실데이터·사람 승인 절차의 복원 훈련 |
| 6. 개인정보·출시 문안 | `NOT_APPROVED` | 공적 근거 재확인과 초안 개선 | 사업자·수탁자·보유기간·책임자·공개 URL 확정 및 김민호 승인 |
| 7. 최종 E2E·출시시험 | `LIMITED` | 사용자·관리자 앱 빌드·설치, 로컬 계약·회귀, 관리자 `operations=false` APK 복원 | custody `ATTESTED` 이후 검토·ZIP·기관 상태, 사람이 기관에 제출하고 실제 접수 확인, 배포 서명·HTTPS 운영 환경 |

## 1. 개발 통합시험

- 실제 PostgreSQL/PostGIS와 Mailpit SMTP를 사용했다.
- Gateway를 거쳐 이메일 OTP 요청, Mailpit 수신, OTP 검증, 계정 생성, 비밀번호 로그인, 통합 동의와 로그아웃을 실행했다.
- 실기기 사용자 앱 UI에서도 필수 3개 동의만 선택해 OTP 요청→Mailpit 수신→6자리 입력→계정 생성→자동 로그인→기기점검 화면 이동을 실행했다. 선택 3개는 기본 OFF로 유지했다.
- OTP·비밀번호·token은 출력하거나 문서에 저장하지 않았다.
- 새 전용 DB와 기존 휴대폰 개발·시험 DB 모두 단일 migration head `202608300005`를 확인했다.
- Backend 전체 회귀는 fresh DB에서 `1440 passed, 2 skipped`였다. 두 skip은 서로 다른 실제 PostgreSQL cluster가 필요한 복원 통합시험이며 별도 실행에서 `2 passed`였다.
- 관리자 원본 증거 v2와 최종 ACL 경계는 fresh migration·downgrade/re-upgrade·실제 PostgreSQL 회귀를 통과했다. runtime 직접 DML은 차단되고 정상 grant·1회 access·승인 경로만 허용됐다.
- Backend 인증 readiness의 DB·계정·암호화·저장소 검사는 모두 준비 상태다. 전체 readiness가 `not_ready`인 유일한 원인은 실제 TMAP 공급자 대신 mock을 사용한 점이다.

## 2. 실제 이메일 연결

- 로컬 CA로 인증서를 검증하는 STARTTLS와 Mailpit 수신은 `PASS`이다. 평문 SMTP나 인증서 검증 우회는 사용하지 않았다.
- 외부 SMTP는 업체, 계약 계정, 발신 도메인/주소와 시험 수신 주소가 정해지지 않아 `NOT_RUN`이다.
- 현재 결과를 Gmail 등 외부 받은편지함 수신 성공으로 해석하지 않는다.

## 3. 실제 스마트폰

시험 기기는 Samsung `SM-G981N`, Android 13/API 33이다.

- 사용자·관리자 debug APK 모두 `adb install -r` 성공
- 두 앱 모두 cold start 성공, 프로세스 유지, 즉시 FATAL/ANR 없음
- 활성 queue 프로필로 TFLite·AndroidKeyStore 실기기 계측시험 `6 passed`
- 최신 기종 독립 기기점검 사용자 debug APK는 223,172,638 bytes이고 SHA-256은 `18876f3eca3539f982325887f9c65ea5933f87030312326dd2708d2ec96eef6e`다. `adb install -r`로 설치했고, 기기 `base.apk`의 SHA-256도 같은 값임을 확인했다.
- APK에서 arm64-v8a/armeabi-v7a `libvosk.so`와 한국어 모델 manifest·핵심 graph/model asset을 확인했다. cold launch 뒤 앱 전용 `no_backup/voice-models`에 hash 검증된 252MB 모델이 준비됐다.
- 관리자 앱은 `operations=false` 기본 잠금 APK로 복원했다. SHA-256: `0c930319a5e7356122e99d0c95cc9b221894eec986006dc931092a56bb81dfc7`
- 기기에서 다시 회수한 두 `base.apk`의 SHA-256이 각각 빌드 APK와 일치
- 카메라, GPS, 가속도계, 자이로스코프 하드웨어 존재
- 정확·대략 위치, 카메라, 활동 인식, 마이크 권한 허용 상태 확인
- 실제 앱 화면에서 필수 가입 동의 3개를 선택하고 OTP를 입력해 계정 생성·자동 로그인을 완료했다.
- 관리자 앱은 AndroidKeyStore P-256 공개키 등록, device proof, 비밀번호와 미사용 TOTP 로그인, 보안 상태·세션 조회를 실제 기기에서 완료했다.
- 관리자 custody는 `UNATTESTED`이고 승인 기록은 0건이어서 운영 화면이 잠겼다. `ATTESTED`를 가장하지 않았고 검토·ZIP·기관 상태 변경은 실행하지 않았다.
- 로그인 뒤 사용자 확인 대화상자를 거쳐 권한·기기점검을 실행했다. 카메라·정확한 위치·마이크·신체 활동 권한은 허용 상태였다.
- 위치 서비스 활성, 배터리 100%, 관찰 온도 약 32°C
- 이전 후보의 기기점검은 오프라인 한국어 TTS voice metadata 단계에서 `FAIL`로 안전하게 잠겼다. 최신 후보는 특정 제조사·모델 목록 대신 실제 로컬 한국어 음성 파일 합성, Vosk 호출어 인식, fresh GPS fix, 사용자 진동 확인, CameraX 프레임+production detector 실행, 실제 Depth 프레임을 차례로 요구하도록 교체했다.
- 최신 후보의 Android JVM 단위시험은 `1,374 passed`, lint·APK 조립은 PASS이고, SM-G981N의 TFLite·AndroidKeyStore 계측시험은 `6 passed`다. 최신 APK는 로그인 화면까지 무충돌 실행했지만 로그인 뒤 사람 입력이 필요한 전체 기기점검은 아직 `NOT_RUN`이다.
- TalkBack은 꺼져 있어 음성 중복·포커스 이동 수동시험은 `NOT_RUN`
- 실제 실외 보행·ARCore depth·발열 지속시험은 `NOT_RUN`
- 최신 cold launch 직후 앱 baseline은 대략 `TOTAL PSS 150MB`, `TOTAL RSS 269MB`, thermal status 0, 배터리 100%, 약 26.4°C였다. Vosk 모델이 아직 load되지 않은 로그인 화면 관찰값이므로 ACTIVE 호출어 부하는 나타내지 않는다.

## 4. 실제 음성·모델

- Voice `/health`와 `/ready`: STT·TTS worker 모두 ready
- Gateway 세션을 통한 TTS: `Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice`, fallback 없이 WAV 생성, 약 1.88초
- 같은 WAV를 Gateway STT에 입력: `Systran/faster-whisper-medium`, 약 0.59초
- STT 응답은 비어 있지 않았고 기대 핵심어, 40자리 model revision과 `execution_allowed=true`를 확인했다.
- 음성 단위 회귀: `165 passed`
- Gateway relay 실제 응답은 STT·TTS 모두 `200`이었고 세션 종료 뒤 credential을 보존하지 않았다.
- 실기기 마이크 입력·스피커 청취 품질 평가는 `NOT_RUN`이다.
- Android에는 Apache-2.0 공식 `vosk-model-small-ko-0.22`와 archive/file SHA-256을 결속해 `길라잡이` 호출어와 명령을 같은 온디바이스 모델로 처리하도록 구현했다. 로그인 뒤 기기점검도 실제 final transcript가 호출어를 인식해야 통과하며, 사용자 시작 전에는 마이크를 열지 않고 TalkBack 안내 길이만큼 기다린 뒤 청취한다. JVM 전체 회귀·APK 빌드·asset/native library 확인·실기기 private 모델 준비는 PASS다.
- 시험 기기는 최신 설치 뒤 로그아웃 화면이며 Android 13 알림 권한도 미허용이다. 따라서 실제 `길라잡이` 발화→명령→TTS→재대기, 오탐·미탐, TalkBack 충돌과 장시간 발열/배터리는 `NOT_RUN`으로 유지한다.

## 5. 운영 저장소·복원

- 비어 있지 않은 DB·업로드를 OpenPGP로 암호화하고 서명 manifest를 검증한 뒤 격리 DB에 복원했다.
- DB 1건, 이미지 1건, 암호화 업로드 1건의 source/target inventory가 일치했고 missing/orphan/hash/size 불일치는 0건이었다.
- 신고 물리삭제와 raw 보존 경계는 실제 PostgreSQL 표적시험을 통과했다.
- source/target root identity 고정, 특수파일 nonblocking 거부, artifact hash·DB metadata 결속, target 신규 접속 차단, 동일 PostgreSQL cluster 거부, 사람이 승인한 immutable action의 1회 실행을 구현했다.
- post-commit 검증은 append-only `PASSED`/`FAILED`로 남고, 알려진 `FAILED`는 재시작 뒤에도 통과로 바뀌지 않는다. 알 수 없는 중단은 다시 검증해 `PASSED`가 확인돼야 공개할 수 있다.
- 서로 다른 실제 PostgreSQL cluster를 사용한 복원 재적용 통합시험 `2 passed`와 관련 표적 회귀를 통과했다.
- 이는 격리 개발 훈련 결과다. 운영 후보·실데이터·운영자 승인·백업 보관 정책을 사용한 복원은 `NOT_RUN`이다.

## 6. 개인정보·출시 문안

- 현재 문안은 `NOT_APPROVED / 출시 사용 금지` 초안이다.
- 현재 구현은 입력 생년월일로 만 14세 이상 여부만 계산하며 PASS·휴대폰 명의·실명·공적 연령 인증을 주장하지 않는다.
- 다음 값이 필요하다: 운영 사업자, 개인정보 책임자/연락처, SMTP·지도·저장소·AI 수탁/제공 관계, 처리 국가, 보유·파기 기간, 기관 수신자, 개인정보처리방침·약관·계정삭제 공개 URL.
- 위 값을 실제 처리와 맞춘 뒤 김민호 및 권한 있는 책임자의 승인이 필요하다.

## 7. 최종 E2E·출시시험

- 최신 Android 기본 프로필 사용자 앱 단위시험 `1,374 passed`, 관리자 앱 단위시험 `183 passed`, 두 앱 lint와 관리자 debug build를 통과했다.
- 실기기 queue 활성 프로필은 별도로 build·설치했고 계측시험 `6 passed`이다.
- Gateway 전체 회귀 `144 passed`, backup·환경 식별 회귀 `100 passed`이다.
- Backend 전체 회귀는 `1440 passed, 2 skipped`이고, 별도 cluster가 필요한 두 복원 통합시험은 따로 `2 passed`였다.
- 활성 문서 전체 검사는 오래된 control 문서 3개의 상태 불일치만 남았다. 이번에 수정한 문서의 로컬 link·script 참조 검사는 `0 errors`였다.
- 사용자 신고→관리자 검토→패키지 생성·전달 사실 기록은 실제 PostgreSQL 통합 회귀에서 확인했다. 관리자 실기기에서는 공개키 등록→device proof→비밀번호/TOTP 로그인→상태·세션 조회까지 성공했다. custody `UNATTESTED`와 `operations=false` 기본 잠금 때문에 검토·수동 SAF ZIP 저장·기관 상태 변경은 `NOT_RUN`이다.
- 사람이 기관 공식 채널로 제출하고 실제 접수번호를 확인하는 단계는 기관·담당자 입력이 없어 `NOT_RUN`이다. 앱은 기관 자동 제출이나 자동 제어를 하지 않는다.
- 운영 HTTPS origin, release signing, 배포 채널이 없어 release 배포시험은 `NOT_RUN`이다.

## 외부 입력 대기

1. 운영 SMTP 업체·발신주소·시험 수신주소
2. 개인정보·위치정보 문안의 실제 사업자·수탁자·보유기간·책임자·공개 URL과 승인
3. 실제 기관 담당자·제출 채널·접수 확인
4. 최신 APK 로그인 뒤 실제 진동 확인·`길라잡이` 발화·오프라인 한국어 TTS·GPS fix·CameraX/탐지·Depth 기기점검과 ACTIVE 보행, TalkBack·카메라 지속 frame·실외 보행 관찰
5. 제조사·모델 whitelist 없이 서로 다른 제조사·Android 버전·Depth 지원 조합에서 같은 기능시험을 반복한 결과
6. 실제 TMAP app key와 운영 provider 시험
7. 운영 HTTPS origin, Android release signing과 배포 채널
