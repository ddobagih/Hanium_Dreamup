# WalkSafe 야간 안정화 결과

작성일: 2026-09-06, Asia/Seoul

## 판정

이번 범위의 코드 수정, 자동 회귀검사, TMAP 실호출, 연결된 휴대폰 설치와 로그인 확인을 수행했다. 실제 보행 안전성, 당사자 사용성, 카메라 인식, 위치 오차 2m 달성을 검증한 것은 아니다.

동시 작업은 최종 담당자를 포함해 7개 이하로 제한하고, 영역별 에이전트 결과를 통합했다. 기존 계정, DB, 앱 데이터를 초기화하지 않았으며 로그인·기기점검·교육을 건너뛰는 APK를 만들지 않았다.

## 1. TalkBack과 앱 음성의 역할

| 구분 | 결정 및 적용 |
| --- | --- |
| 화면 탐색 | 버튼 이름, 입력란, 체크 상태, 화면의 설명은 TalkBack이 읽는다. 앱이 같은 화면 전체를 자동으로 또 읽지 않는다. |
| 회원가입 약관 | 접근 가능한 본문과 선택적인 듣기 버튼을 함께 둔다. 듣기와 동의 체크는 별개다. 듣지 않고 TalkBack으로 읽은 뒤 동의할 수도 있다. |
| 안전 교육 | 사용자가 누른 교육 내용은 앱 TTS가 읽는다. 실제 발화 완료 후 다음 단계를 허용하며, 접근성 알림이나 경과 시간만으로 완료 처리하지 않는다. 중지·재시도를 제공한다. |
| 길안내·객체/위험 안내·명령 응답 | 화면에 시선을 두지 않아도 필요한 정보를 받을 수 있도록 앱 TTS가 담당한다. 오래된 위험 음성을 자동으로 다시 재생하지 않는다. |
| 음성 입력 | 버튼과 호출어 이후 명령 입력의 주 경로를 오프라인 Vosk 한국어 `vosk-model-small-ko-0.22`로 통일했다. |
| 음성 출력 | Android TTS의 실제 준비된 한국어 오프라인 음성을 사용한다. 단말에 설치된 엔진·음성에 따라 소리는 달라질 수 있다. TalkBack 자체 음성은 사용자의 접근성 설정을 따른다. |

화면 읽기와 명시적 앱 발화를 나누는 것은 Android 접근성 지침을 바탕으로 한 이 프로젝트의 설계 판단이다. 시각장애인 당사자 실험으로 최적성을 입증했다는 뜻은 아니다. [Android 접근성 원칙](https://developer.android.com/guide/topics/ui/accessibility/views/principles-views), [TalkBack 사용 안내](https://support.google.com/accessibility/android/answer/6007066), [TTS 실제 진행 콜백](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener).

Vosk 모델은 기기 내 처리를 위한 선택이며, 실제 이 앱의 인식률이 다른 모델보다 높다고 검증한 것은 아니다. [Vosk 공식 모델 목록](https://alphacephei.com/vosk/models).

상세 명령·실패·재시도 정책은 `docs/planning/accessibility-voice-design-20260906.md`의 후속 구현 반영 절에 정리했다. 대표 명령은 `서울역으로 안내해줘`, `더 듣기`, `목적지 취소`, `안내 시작`, `다시 말해줘`, `도움말`, `다음 안내 알려줘`, `경로 다시 찾아줘`, `위치 다시 확인`, `도착했어`, `아직 도착 아니야`, `길안내 종료`, `신고해`, `보행 일시정지`, `보행 재개`, `보행 종료` 및 종료 확인·취소다. 단독 번호는 현재 유효한 목적지 후보를 고르는 상황에서만 받는다. 임의의 모든 자연어 표현을 이해한다는 보장은 하지 않는다.

## 2. 주요 수정

| 영역 | 수정한 문제 및 경계 |
| --- | --- |
| 음성 입력 | 모델 준비 지연과 실제 마이크 준비 상태를 구분하고, 요청 세대·리스로 이전 콜백과 마이크 중복 점유를 차단했다. 일회성 오류가 이후 음성 입력을 계속 잠그지 않도록 정리했다. |
| 음성 출력 | 실제 완료·오류·중단을 구분했다. 개별 발화 실패를 곧바로 영구 엔진 장애로 확대하지 않고 제한된 복구를 제공한다. |
| 약관 음성 | 기기점검 전체가 BLOCKED라는 이유로 가입 전 약관까지 읽지 못하던 실제 오류를 수정했다. 약관 전용 `speakConsentClause()`만 실제 TTS READY를 기준으로 실행하며, 오디오 포커스와 완료·실패 처리는 유지한다. 보행·위험·교육 게이트는 완화하지 않았다. |
| 약관·교육 UI | 듣기 중지, 다시 듣기, 재시도 상태를 실제 버튼에 표시한다. 청취 결과를 동의나 교육 완료로 임의 변환하지 않는다. |
| 계정·설정 UI | 회원가입을 열 때 남아 있던 과거 로그인 실패 표시, 누락된 계정 삭제 복구 컨트롤의 부모 연결, 보이지 않던 설정 개인정보 상태 표시를 수정했다. |
| 접근성 표시 | 입력란·미선택 컨트롤 테두리 대비를 보강했다. 기존의 큰 버튼과 미니멀한 화면 방향을 유지했다. |
| 신고·수집 | 선택 동의와 실제 실행 가능 상태를 구분해 아직 활성화되지 않은 상태를 드러낸다. 개발용 신고 큐는 용량·항목·출처 제한을 유지한 명시적 빌드 옵션으로만 활성화한다. 자동 동의나 실제 외부 신고 전송은 하지 않았다. |
| 관리자 앱 | 목록 CONTENT 상태에서만 페이지 이동을 허용하고 상세 화면이나 요청 진행 중 중복 페이지 요청을 막았다. |
| 위치 분석 앱 | RINEX GPS 시각의 UTC 변환과 윤초를 보정했다. 실제 관측이 없는 자료, 중복·역전된 시각, 과장된 시간 범위, 잘못된 PPK 날짜·시각과 미지원 시간계를 검증한다. |
| 개발 서버 | 기존 TLS 검증을 끄지 않고 공개 CA와 개발 SMTP CA를 함께 신뢰하는 전용 번들로 구성했다. 실제 Gateway·Backend 준비 상태와 인증 흐름을 확인했다. |
| DB 검사 | 실제 휴대폰용 DB와 분리한 임시 PostGIS에서 DB 필수 회귀검사를 실행했다. 실제 DB 초기화나 계정 삭제는 하지 않았다. |

핵심 파일은 `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt`, `feedback/AndroidFeedbackActuator.kt`, `voice/VoskSpeechRecognizer.kt`, `voice/VoiceMicrophoneLease.kt`, `navigation/AndroidVoiceCommand.kt`, `navigation/BackendWalkingRouteClient.kt`, `backend/app/services/tmap_pedestrian.py`와 각 모듈의 대응 테스트다. `feedback/`, `voice/`, `navigation/` 표기는 같은 WalkSafe Kotlin 소스 루트 기준이다.

## 3. TMAP과 경로 안전성

제공받은 키를 저장소 밖 개발용 비밀 설정에 적용했다. 보고서나 소스에 키 원문을 기록하지 않았다.

- 실제 POI 검색과 보행 경로 요청이 성공했다. 서울시청에서 서울역으로 향하는 고정 시험 좌표에서 POI 3건, 경로 2,092m·1,630초·90개 점을 확인했다. 현재 사용자 위치를 측정한 결과가 아니다.
- POI 거리 단위, 숫자 문자열로 오는 시설·회전 코드, 잘못된 좌표·거리, 인증·설정·제한 오류 구분을 보완했다.
- 계단 회피 옵션 30을 요청하고, 반환 결과에 계단 코드가 남아 있으면 해당 경로를 차단한다. 계단 경로를 조용히 다시 허용하는 대체 처리는 넣지 않았다.
- 검증된 보도 연결망 없이 경로 좌표를 임의로 옮기는 기능은 구현하지 않았다. 차도 진입, 단절, 횡단 가능 여부를 확인할 수 없기 때문이다.
- 계단 코드가 없다는 사실은 시각장애인에게 안전하다는 보장이 아니다. 공사, 임시 장애물, 보도 유무, 횡단보도 상태 등의 현장 검증은 별도다.

TMAP의 보행 경로 파라미터와 응답 형식에 근거한 제한적 보완이다. [TMAP 보행자 경로 안내](https://tmap-skopenapi.readme.io/reference/보행자-경로안내), [TMAP 장소 통합 검색](https://tmap-skopenapi.readme.io/reference/장소통합검색).

## 4. 검증 결과

| 검사 | 결과 |
| --- | --- |
| 사용자 Android 앱 | 1,823 통과, 1 건너뜀, 실패·오류 0 |
| 관리자 Android 앱 | 261 통과, 실패·오류 0 |
| 위치 분석 Android 앱 | 55 통과, 실패·오류 0 |
| Android Lint | 3개 모듈 통과 |
| Backend 비DB 검사 | 1,515 통과, 조건부 165 건너뜀 |
| Backend DB 필수 검사 | 격리 PostGIS에서 109 통과, 건너뜀 0 |
| Android Gateway | 161 통과, 타입 검사·빌드 통과 |
| 실제 TMAP | POI·보행 경로 성공, 실제 런타임 인증된 `/ready` HTTP 200 |
| 실제 계정 API | 새 합성 계정의 OTP 202, 가입 201, 로그인 200, 세션 200 확인 |
| 실제 휴대폰 로그인 | 새 합성 계정 로그인 후 정상 첫 안전 안내 화면 진입 확인 |
| 약관 TTS, TalkBack OFF | 재생 중에서 다시 듣기로 전환, 필수 동의 체크 자동 선택 없음 |
| 약관 TTS, TalkBack ON | 접근성 서비스가 바인딩되고 터치 탐색이 켜진 상태에서 재생 중·완료 화면 확인. 원래 접근성 설정 복원 |

통과 수 합계는 3,924개다. 건너뛴 조건부 검사를 통과로 계산하지 않았다. 단위·정적 계약·회귀검사가 포함된 수치로, 3,924개의 실제 보행 시험을 의미하지 않는다.

최종 Android 명령:

```bash
./gradlew :app:testDebugUnitTest :adminapp:testDebugUnitTest \
  :positionevalapp:testDebugUnitTest \
  :app:lintDebug :adminapp:lintDebug :positionevalapp:lintDebug \
  :app:assembleDebug --console=plain \
  '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' \
  -Pkotlin.daemon.jvmargs=-Xmx6g --max-workers=2 \
  -PdevelopmentReportQueue=true
```

기본 512MiB Gradle 프로세스가 마지막 패키징에서 메모리 부족으로 실패하여 해당 실행에만 메모리를 늘렸고 재실행은 성공했다. 프로젝트의 JVM 설정 파일을 임의 변경하지 않았다.

주요 검사 로그:

- `/tmp/walksafe-overnight-android-final-consent-retry.log`
- `/tmp/walksafe-overnight-backend-nondb-lQZUP1.log`
- `/tmp/walksafe-db-regression-_lo0nhh9/db-109.log`
- `/tmp/walksafe-overnight-gateway-VL1cMc.log`

### TalkBack 검사 도구 간섭

초기 `uiautomator dump` 반복 시험에서 약관 재생이 재시도로 끝났다. 이 도구는 기본 UiAutomation 연결 시 다른 접근성 서비스를 억제할 수 있고, 실제 시험에서도 TalkBack TTS 클라이언트의 반복 재연결이 관찰됐다. 이 초기 결과를 순수한 제품 결함으로 단정하지 않았다. [AOSP DumpCommand](https://android.googlesource.com/platform/frameworks/uiautomator/+/17fac436d78f6ac642386a245fb4fdb7243a91a4/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java), [AOSP UiAutomation 기본 연결·억제 플래그](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/app/UiAutomation.java).

최종 시험에서는 TalkBack을 켜기 전에만 버튼 좌표를 얻고, 켜진 동안 `uiautomator`를 한 번도 호출하지 않았다. 서비스 준비 후 명시적으로 듣기를 실행하고 `screencap`으로 재생 중과 완료를 확인했다. 전후 모두 TalkBack 바인딩과 터치 탐색이 유지됐고 동의 체크는 비선택이었다. 이 결과를 최종 판정에 사용했다. 실제 음질, 소리 겹침, 이해 가능성은 사람이 평가하지 않았으므로 별도 검증이 필요하다.

## 5. 설치 및 현재 상태

- 연결된 Samsung SM-S931N에 사용자 앱 수정본과 위치 분석 앱을 기존 데이터 유지 방식으로 설치했다.
- 관리자 APK는 빌드했으며, 이번에 휴대폰에 새로 설치하지 않았다.
- 사용자 앱은 새 합성 테스트 계정으로 로그인한 뒤 첫 안전 안내 화면에 두었다. 실제 호출어 시험·교육·장착 확인 등을 완료한 것처럼 기록하지 않았다.
- 새 계정의 선택 수집·자동 신고 동의는 모두 꺼져 있다. 기존 테스트 계정의 비밀번호를 바꾸거나 계정을 삭제하지 않았다.
- 현재 개발 PC의 Gateway·Backend와 USB `adb reverse tcp:8081 tcp:8081`을 사용한다. PC 재시작이나 USB 분리 이후의 자동 연결을 보장하는 서비스 설치는 하지 않았다.
- APK 위치는 `apps/android/app/build/outputs/apk/debug/app-debug.apk`, `apps/android/adminapp/build/outputs/apk/debug/adminapp-debug.apk`, `apps/android/positionevalapp/build/outputs/apk/debug/positionevalapp-debug.apk`다.

## 6. 보류 및 미구현

| 항목 | 이유·다음 검증 |
| --- | --- |
| 실제 시각장애인 사용성 | 당사자와 화면 탐색, 초점 유지, 약관·교육 이해, 음성 충돌을 확인해야 한다. |
| 실제 반복 음성 명령 | 사람의 발화·주변 소음·호출어·마이크 준비 신호를 포함한 반복 시험은 이번 무인 시험으로 대체하지 않았다. |
| 카메라·객체 거리·Depth 프레임 | 휴대폰 후면이 바닥을 향한 조건이므로 실제 대상 인식·거리·경고 품질을 시험하지 않았다. 서버 탐지기는 개발 fake 모드이며 실제 모델 성능 증거가 아니다. |
| 실제 안전 교육 완주 | 실제 폰의 약관 발화는 확인했지만 초기 절차를 우회해 교육 전체를 강제로 완료하지 않았다. |
| 자동 신고의 현장 전체 흐름 | 카메라 취득부터 실제 전송·관리자 확인까지의 실제 현장 시험은 하지 않았다. 큐·동의·서버 처리를 회귀검사한 범위와 구분한다. |
| NGII 실다운로드·PPK 정답 품질 | 실제 키·관측 로그·기준국 자료를 묶은 전체 처리는 별도다. PPK 출력이 생겼다는 이유만으로 독립 정답으로 인정할 수 없다. |
| 위치 오차 2m 및 실보행 | 정지한 실내 휴대폰과 합성 경로로 정확도를 입증할 수 없다. 독립 기준 위치와 동시간대 실측 자료가 필요하다. |
| 임의 안전 우회로 생성 | 연결 가능한 보도망과 검증된 위험 정보가 없어 미구현. 계단 회피·차단까지만 적용했다. |
| 클라우드 STT 자동 전송 | 기본 입력 모델을 몰래 클라우드 서비스로 대체하지 않았다. |

기존 Goal에는 별도의 현장 정확도 목표가 남아 있어 완료로 바꾸지 않았다. 새 Goal 생성도 기존 미완료 Goal 때문에 거절되어, 이번 범위와 결과는 `docs/planning/overnight-runtime-accessibility-20260906.md` 및 본 보고서로 추적했다. 이번 자동검사 통과를 프로젝트 전체 또는 기존 현장 목표 달성으로 표시하지 않는다.
