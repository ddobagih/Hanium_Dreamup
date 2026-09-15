# 길안내 음성 끊김 수정 및 반복 독립 검수

## 결과와 범위

사용자가 보고한 길안내 중간 끊김에 대해 음성 입력/출력과 경로 안내를 나누어 조사했다. 자동 명령 듣기 진입, 지연된 인식 결과, 경로 거리 단계/중복 위치 갱신에서 실제 중단 조건을 코드로 재현하고 수정했다. 독립 검수 → 개선 → 재검수 → 추가 개선 → 최종 독립 확인까지 수행했다.

최종 선택 Android 회귀 **2,447건**, 별도 실제 컴파일된 MainActivity의 제어 환경 시험 **9건** 통과. APK 빌드 완료. 2026-09-15 09:10 S25(SM_S931N)에 데이터 보존 업데이트 설치를 완료했고 설치된 base.apk 해시가 빌드와 일치했다. 실제 사용자 재현 순간의 로그·스피커·마이크·야외 보행은 미검증이다.

## 단계별 계획과 실행

| 단계 | 확인·개선 대상 | 실제 수행 결과 |
| --- | --- | --- |
| 1. 원인 분리 | TTS→자동 입력과 위치→음성 취소를 별도 담당자가 조사 | 실제 Controller 지연 final, Actuator STT 진입 중단, Navigator 거리 단계·중복 위치 중단 재현 |
| 2. 1차 개선 | 발화 보호, 입력 세대 확인, 같은 안내 문장 유지 | MainActivity·Actuator·Controller·Transcriber·RouteNavigator 반영 및 영역별 시험 |
| 3. 2차 독립 검수 | 대기/취소/재개·기기 전환·늦은 콜백·거리 갱신 | 목적별 종료 처리와 버튼 복구 누락, 성공 소비와 실패 취소 혼동 발견 |
| 4. 피드백 반영 | 확인 상태 소유권과 실행 조건 분리 | 동일 확인 토큰만 정리, 새 확인 보존, 성공 소비 시 실패 콜백 금지, 버튼 갱신 |
| 5. 최종 독립 확인 | 수정 지적 해결과 관련 회귀 | 음성 연결 10개 시나리오 그룹·경로 9개 시험 통과, 배정 범위 잔여 지적 없음 |
| 6. 통합·APK | 최종 소스 회귀, APK 코드·자산 확인 | 선택 회귀 2,447건 및 빌드 통과, S25 업데이트 설치·APK 해시 일치 |

Root는 MainActivity와 통합·기록을 담당했다. 음성 처리와 Navigator 구현은 각각 다른 담당자가 맡았다. 독립 검수자는 제품 파일을 수정하지 않았으며 별도 재현을 실행했다.

## 재현한 원인과 변경

1. **명령 듣기를 준비하는 함수가 진행 중인 길안내를 중단했다.** `prepareForSpeechRecognition()`은 기존에 위험 음성만 보호하고 일반 안내를 `stop()`했다. 이제 초기화 중 대기 큐·재생 중 출력·출력 종료 후 500ms가 지나기 전에는 입력 진입을 허용하지 않고 발화와 완료 콜백을 보존한다. 500ms는 앱의 입력 전환 여유 시간이며 Android가 보장하는 값이나 실기기 측정 결과가 아니다.
2. **이미 큐에 들어온 인식 결과가 뒤늦게 명령으로 실행될 수 있었다.** PCM 입력 억제만으로 main thread에 게시된 final은 폐기되지 않았다. 현재 출력 상태, run, 인식 당시 대화 세대를 배달 시 다시 확인하고 세대 변경 시 디코더를 초기화했다.
3. **남은 거리 단계가 바뀌거나 완전히 같은 위치가 반복되면 말하던 문장이 취소됐다.** 동일 안내 지점에서는 실제 발화 종료까지 기존 문장을 유지하고, 이후 최신 거리 단계를 제안한다. 신선한 완전 동일 위치는 이동·도착·이탈 관측으로 다시 누적하지 않는다. 실제 안내 지점 변경, 경로 교체, 역행·내용 변조·만료 위치는 이전 안내 취소를 유지한다.

자동 호출어 대기 서비스가 켜지는 것 자체에는 TTS stop이나 오디오 포커스 요청이 없었다. 따라서 화면의 마이크 표시만으로 실제 중단 원인을 확정하지 않았다. 위험 경고 선점과 외부 오디오 포커스 손실은 여전히 별도 중단 원인이 될 수 있다.

## 앱 입력·출력 연결

- 안내 시작 시 이전 목적지 후보 대화, 일회성 인식, 대기 중인 입력 요청을 정리한다.
- 요청된 음성 입력은 현재 음성이 끝날 때까지 기다린다. 현재 명령·경로·화면·계정·세션·보행 epoch·화면 수명주기를 다시 확인하고, 최대 30초 이후에는 요청을 만료시킨다. 새로운 입력 요청 없이 TTS 완료만으로 일회성 녹음을 시작하지 않는다.
- 대기 중인 입력에는 현재 문장이 끝난 후 순서를 넘기며, 추가 길안내는 잠시 대기한다. 위험 경고의 우선순위는 유지한다.
- 목적별 입력이 시작되지 않으면 같은 RESUME 확인 토큰/TAKEOVER operation만 정리한다. 실행 조건의 generation과 확인 종료 소유권을 구분하고, 정상 시작은 실패 콜백 없이 대기 소유권만 소비한다.
- 초기화 대기 중 명령 응답과 서버 WAV 재생도 출력 상태에 포함한다. 실제 TTS 출력 활동과 입력 전환 여유 시간은 별도 쿼리로 구분한다.
- DEBUG `WalkSafeVoiceOutput`에 시작·완료·중단·선점·포커스 손실·시간 초과·입력 대기 원인을 추가했다. 음성 원문과 좌표는 기록하지 않는다.

## 검증 근거

근거 루트: `work/voice-handoff-20260915/`.

- `audit-round1/report.md`, `delivery-audit-round1/review.md`: 수정 전 독립 재현. 첫 안내 62m→60m 변화와 동일 fix 취소를 확인했다. 거리 경계 왕복 때 매번 끊긴다는 초기 가설은 실제 실행에서 기각했다.
- `audio-fix/README.md`: 실제 Controller 7·Transcriber 5·Actuator 5개 시나리오 그룹, 관련 기존 JUnit 24건 통과. Android/Vosk/PCM 경계는 가짜 의존성으로 제어했다.
- `route-fix/result.md`: Navigator 기존 88 + 신규 10 = 98건 통과. 기존 시험 중 새 관측인데 같은 timestamp를 재사용하던 fixture를 실제 새 시각으로 보정하고, 변경된 문장 유지 계약의 기대값을 갱신했다.
- `audit-round2/report.md`: 추가로 발견한 목적별 종료·버튼·소유권 문제와 보완 계획.
- `audit-final/report.md`: 실제 Controller와 Transcriber를 함께 연결한 독립 5그룹, Actuator 원문 메서드와 실제 큐/콜백 등록기 독립 5그룹 통과. Main 최종 수정도 독립 소스 검토 완료.
- `delivery-audit-round2/review.md`: 독립 경로 회귀 9건 통과. 유효 중복 관측 20회에도 matcher/위치 시각/도착·이탈 횟수 불변, 잘못된 위치 취소, 실패 후 2초 대기와 늦은 콜백 무효화 확인.
- `main-integration/README.md`: 실제 컴파일된 MainActivity에 제어 가능한 Android clock/Handler/TextView 경계만 제공한 JUnit 9건 통과. 첫 일반 JVM 실행의 Android `not mocked` 실패 8건은 `integration-1`에 보존했다. 그 시험을 별도 실행 위치로 옮겼으며 일반 프로젝트 전체 시험에 기본값 mocking을 적용하지 않았다.
- `integration-final/summary.json`: **242 suites / 2,447 tests / 실패 0 / 오류 0 / skip 0**, `:app:assembleDebug` 통과. 기존 작업에서 확인한 정적 불일치 12건은 이전의 동일 제외 목록을 유지했다. 목록: `work/navigation-session-fixes-20260914/preexisting-static-exclusions.json`. 전체 저장소 시험 통과 주장은 하지 않는다.
- 검사 시작/종료 소스 일치, 최종 독립 검토의 소스 SHA와 현재 소스 일치, `git diff --check` 통과.

별도 시나리오·기존 JUnit·통합 회귀에는 겹치는 범위가 있어 숫자를 합쳐 독립 시험 총수로 주장하지 않는다. PC 코드·상태 시험은 실제 스피커 출력이나 사람 음성 인식 정확도를 입증하지 않는다.

## APK와 남은 확인

- APK: `apps/android/app/build/outputs/apk/debug/app-debug.apk`
- 패키지: `kr.co.hanium.dreamup.walksafe`
- SHA-256: `1a08346886999a33215740140daf2feb4f9eddc14de34c49c1e34a773e8048eb`
- 크기: 453,805,096 bytes. 새 동작 메서드·진단 태그의 DEX 포함 확인: `apk.json`.
- 모델 자산 6개, 기존 외부 Gateway 주소, DEBUG 안내 제한 해제 및 로그인 후 기기점검 설정 유지. 서버·계정·DB·권한 변경 및 push 없음.
- 설치 확인: S25(SM_S931N), lastUpdateTime=2026-09-15 09:10:45, `adb install -r` Success 및 base.apk SHA-256 일치(`install.json`). 데이터 보존, 앱 자동 실행 없음.
- 야외 연결 확인: APK의 Cloudflare Gateway에서 `/api/field-walk`에 미인증 요청 시 예상된 `401 gateway_unauthorized` 응답. 외부 연결 가능 확인이며 인증된 경로 요청·실제 이동통신망 시험은 수행하지 않았다. 개발 PC 서버와 터널을 계속 실행해야 한다.
- 모바일 데이터 정책: 설정의 `모바일 데이터 사용: 허용 · Wi-Fi와 이동통신` 상태에서 서버 기능을 이동통신망으로 사용할 수 있다. 실제 휴대폰 선택값은 암호화 저장되어 이번 읽기 점검으로 확인하지 않았고 설정을 변경하지 않았다. 현재 DEBUG APK의 목적지 검색·보행 경로 요청은 연결된 이동통신망에서도 제한 우회가 적용되지만 로그인·서버 음성 등에는 모바일 데이터 허용 설정이 필요하다. 근거: `network-check.json`.
- 사용자는 안내 시작 후 첫 문장 완결, 발화 중 입력 요청의 대기, 발화 후 명령 듣기, 안내 중단/목적지 변경 후 늦은 입력 부재를 실제 휴대폰에서 확인한다.

## SDK 근거

Android의 [`TextToSpeech.stop()`](https://developer.android.com/reference/android/speech/tts/TextToSpeech#stop())은 현재 발화와 남은 큐를 중단한다. 발화 요청 접수와 실제 종료를 구분하기 위해 [`UtteranceProgressListener`](https://developer.android.com/reference/kotlin/android/speech/tts/UtteranceProgressListener)의 완료/중단 콜백을 기준으로 소유권을 관리한다. Context7 조회가 해당 API 대신 다른 media API를 반환하여 Android 공식 문서로 확인했다.
