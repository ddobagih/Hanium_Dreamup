# temp 기반 로컬 기능 통합 — 2026-09-30

## 기준과 작업 위치

- 기준: 팀 `origin/temp`, `2e5744725a8c033a1817f3cc8a8ec679bc15ac9d`.
- 작업 폴더: `/Users/luminggi/Desktop/WalkMate/integration-temp-local-20260930`.
- 작업 브랜치: `integration/temp-local-features-20260930`.
- 가져온 기능: `merge-review-team-20260914`의 `66b3e9e` 및 해당 폴더의 미커밋 기능 변경.
- 원본 작업 폴더는 보존했다. 이번 변경은 커밋·푸시하지 않았다.

## 기능별 이식

| 기능 | 통합 내용 | 팀 변경과의 조정 |
|---|---|---|
| 접근성 UI | 전체 화면 도움말·시작 확인·종료 확인, 큰 세로 버튼, 음성 중심 안내, 재점검/취소, 음성 신고 보조 화면 | 목적지 선택 음성에 주소·거리 근거를 보존. 도움말에 이전 후보 등 팀 명령을 반영. 후보 안내 완료 후 선택 권한을 여는 팀 처리를 유지 |
| 안내 화면 | 음성 명령·일시정지·안내 종료·터치 오작동 방지, 검은 보호 화면과 3초 길게 누르기 | 시험용 안전 점검 우회 빌드의 표시는 보존. 일반 안내에서 우회는 꺼짐 |
| 보행 상태 | 목적지 선택/준비부터 보행 영역 소유, 메인 호출과 분리, 설정에서 안내로 복귀, 종료 정리 후 메인 복귀, 뒤로 가기 종료 확인 | 팀의 준비 상태 확인 토큰, 경로 결정 토큰, 계정/세션 세대 검사, 런타임 종료 정리를 보존 |
| Whisper | 서버 STT 연결, 발화 종료 감지, turbo 로컬 설정·모델 캐시, 선택형 hotword/initial_prompt, 비교 시험 스크립트·기존 결과 | 로컬 모델은 `dropbox-dash/faster-whisper-large-v3-turbo`, CPU/int8/한국어. hotword와 initial_prompt 비활성. 모델 고정 revision과 파일 검증 유지 |
| MobileSAM | 별도 `mobileSam` 빌드, 사진+YOLO 후보/수동 박스 분할, ONNX 모델, 기하/기기 시험 코드 | 실시간 보행 판단에는 연결하지 않음. 별도 시험 앱이라는 기존 범위를 유지 |
| 장소 검색 | 서울역 등 명시한 역 이름을 가까운 무관한 시설보다 우선 검색 | 팀의 공유 TMAP 실행기·응답 처리·제한 정책에 맞춰 테스트를 이식 |

Whisper 로컬 설정 `.env.voice-local`, `weights/voice`와 Android 모델 자산은 새 폴더에 복사했다. 비밀 설정과 대형 모델은 기존 Git 제외 정책을 따른다. 실제 실행 서버·DB·휴대전화는 이번 작업에서 전환하지 않았다. 배포용 기본 env 예제의 medium 값을 운영 승인 없이 일괄 변경하지 않았으며, 이 로컬 통합 환경은 별도 turbo 설정을 사용한다.

## 검증 결과

| 검증 | 결과 |
|---|---|
| 일반 Android APK | 생성 성공, 서버 STT 활성, 안전 점검 우회 비활성 |
| MobileSAM APK | 생성 성공 |
| Android 전체 JVM | 5,816건: 통과 5,735 / 실패 62 / 건너뜀 19 |
| 같은 컴퓨터의 수정 전 temp | 5,760건: 실패 63 / 건너뜀 17 |
| 원본 대비 추가 실패 | 0건. 기존 환경 안내 정적 검사 1건은 통과로 전환 |
| 새로 가져온 Android 시험 클래스 | 14개 클래스, 50건, 실패 0 |
| MobileSAM 기하 | 2/2 통과 |
| MobileSAM 기기시험 코드 | 컴파일 성공. 기기 실행은 미실시 |
| 장소 검색 회귀 | 364/364 통과 |
| 음성·모델 검증 | 143건 통과. Linux abstract socket을 사용하는 프로세스 잠금 검사 3건은 macOS에서 실패하며 원본 temp도 같은 3건 실패 |
| turbo 모델 캐시 | revision·manifest·모델 파일 해시 검증 통과 |
| 변경 파일 공백/충돌 검사 | 통과 |

전체 시험 PASS를 의미하지 않는다. Android의 남은 62건은 같은 환경의 수정 전 temp에서도 실패했다. 팀이 기록한 타 환경의 기존 실패 21건과 이 컴퓨터의 63건을 같은 수치로 취급하지 않는다. 신규 실패가 없다는 비교는 이 컴퓨터에서 직접 실행한 두 결과의 시험 이름을 대조한 것이다.

목적지 음성의 주소 누락은 제품 코드를 보완했다. 전체 화면 도움말로 이동한 명령 예시, 종료 확인 단계, 보행 영역 복귀 등에 대한 정적 검사는 새 구조를 검사하도록 수정했다. 상태/음성/후보 선택 안전 정책을 제거해 검사를 통과시키지 않았다.

로컬 상세 결과: `work/integration-verification/results.json` 및 같은 폴더의 로그. 원본 비교용 소스는 `work/temp-baseline`에 보관했다. 원본 temp의 macOS 빌드 도구 검증값만 기존 로컬 검증값으로 보충했으며 제품/시험 소스는 원본 그대로다.

## 빌드 재현

프로젝트의 `apps/android`에서 JDK 21과 Android SDK를 지정한다.

```sh
export JAVA_HOME=/Users/luminggi/Library/Java/JavaVirtualMachines/ms-21.0.9/Contents/Home
export ANDROID_HOME=/Users/luminggi/Library/Android/sdk
./gradlew :app:assembleDebug :app:assembleMobileSam -Pwalksafe.serverSttEnabled=true --offline --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g -Pkotlin.daemon.jvmargs=-Xmx3g
./gradlew :app:testDebugUnitTest -Pwalksafe.serverSttEnabled=true --offline --max-workers=2
./gradlew :app:testMobileSamUnitTest --tests '*MobileSamGeometryTest' :app:compileMobileSamAndroidTestKotlin -PmobileSamDeviceTests=true -Pwalksafe.serverSttEnabled=true --offline --max-workers=2
```

일반 단위시험과 MobileSAM 단위시험은 서로 다른 testBuildType이므로 별도로 실행한다. SDK·Gradle 의존성·모델 자산이 이미 있는 이 컴퓨터를 기준으로 한 명령이다.

## 다음 실기기 확인

도움말/설정 왕복, 목적지 선택→점검→시작 확인→안내, 보행 중 호출, 뒤로 가기 종료/복귀, 잠금·백그라운드·안전정지, 터치 보호 해제, 실제 Whisper 발화, MobileSAM 사진 분할을 확인한다. 음성 인식 정확도·카메라 성능 개선은 이번 통합 검증 결과만으로 주장하지 않는다.


## 2026-10-06 통합 상태 및 추가 검증

위의 검증 표와 다음 실기기 항목은 9월 30일 통합 당시 기록이다. 이후 계정 전환·동의 철회 복구, 신고 좌표 근거, 이메일 장기 로그인·복원, 사전 연습 음성 완료, 위치 확인 대기와 후속 장소 검색의 명령 추적을 보완했다. 장기 로그인은 교육 완료 후 다음 비밀번호 로그인부터 적용한다.

- Android 담당 모듈 회귀: 1,375개 중 1,363개 통과, 선택 실행 12개 제외, 실패 0개. 별도 세션 저장 형식 보완 검사는 36개 중 34개 통과·2개 제외이며 앞의 검사와 중복된다.
- Gateway Linux 회귀: 620개 중 618개 통과·2개 실패 후, 명세와 음성 전달 완료 검사 불일치를 수정했다. 해당 파일의 89개 재검사를 통과했다. 재검사를 더해 별도 전체 검사 수로 계산하지 않는다.
- Backend 계정·신고·관리자·TMAP: 전용 PostgreSQL 시험 DB에서 1,383개 중 1,382개 통과. 남은 관리자 설정 검사는 필수 배포 설정을 명시하고 비관리자 실행 환경에서 재검증하여 통과했다.
- Android debug 및 기기 시험 APK 빌드 성공. SM-A716S에 설치 후 실제 역 검색·준비 음성·재점검·취소, 서버 녹음 취소·재진입 2개 검사 통과.
- 사전 연습 긴 음성의 실제 완료와 중단, 도움말, 동의 부여·철회, 로그인 복원, 터치 보호 미리보기, 별도 MobileSAM 사진 분할도 앞선 실기기 검사에서 확인했다. MobileSAM의 기준 마스크 일치는 변환 호환성 근거이며 현장 인식 정확도가 아니다.

팀원 B 188개는 아직 전체 완료가 아니다. 25개에 현재 확인 근거를 연결했으며 나머지는 항목별 대조가 남아 있다. 명령별 발화 완료 추적, 실제 야외 보행·위험 안내, 실제 신고 전송, 장시간 로그인, 운영 환경 검증은 별도로 남긴다. 이 문서는 출시 승인이나 기존 원장의 DONE 판정이 아니다.

공통 `MainActivity.kt`에는 UI·보행·음성·계정 연결 변경이 함께 포함되므로 A 담당자의 변경과 통합할 때 해당 연결을 대조한다. 로컬 설정·토큰·시험 계정·기기 원본 로그·APK·모델 가중치는 게시하지 않는다. MobileSAM 모델 재생성은 `apps/android/app/src/mobileSam/README.md`, 음성 서버 설정은 `deploy/config/walksafe-voice.env.example`을 참조한다. 로컬 `.env.voice-local` 및 모델 캐시는 별도로 준비해야 한다.
