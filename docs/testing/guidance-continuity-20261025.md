# 2026-10-25 길 안내 센서·음성 연속성 검증

> 이 문서는 02:07 기준 앱·시험 결과 기록이다. 12시 이후 측정값을 실제 휴대폰에 넣어 새 시간 처리 결함을 수정하고 재검증한 결과는 [후속 실기기 검증](measured-input-device-20261025.md)에 기록했다. 아래 기존 APK·21개 시험을 새 APK의 결과로 합산하지 않는다.

## 목표와 현재 검증 경계

후면이 책상을 향한 USB 연결 Galaxy S25에서 순간 센서 오차에 대한 과도한 경고와 기능 제한, 일반 안내에 의한 음성 중단을 개선한다. 측정값의 원본 시각을 바꾸거나 거절된 위치를 새 위치로 게시하지 않는다. 실제 보행·정확한 방향·도착·사람의 청취는 별도 확인 대상이다.

센서·음성 수정과 책상 위 검증을 마쳤다. 관련 자동 회귀시험 4,644개와 기기 컴포넌트·연결 시험 21개가 통과했다. 서버 주소 연결과 기존 로그인 완료 홈 상태가 없어 실제 계정의 경로 시작 시험은 실패했다. 실제 계정의 전체 경로 검증이 남아 있으며 이 실패를 컴포넌트 시험으로 대체해 성공 처리하지 않는다.

최종 Goal 상태는 `blocked`이다. 세 번째 Goal 턴에서도 서버 연결·기존 계정 로그인 선행조건이 해소되지 않았고, 설치된 앱과 작업 파일이 검증한 상태로 유지됨을 확인했다. 전체 길 안내 여정 완료를 뜻하지 않는다.

## 수정한 동작

- 정상 이력이 남은 센서의 짧은 재확인 기간에는 경고 음성을 내지 않고, 실제 기능 제한이 확인될 때 원인과 필요한 행동을 안내한다.
- 3초 미만의 GPS 위치 도약 묶음은 잘못된 측정을 모두 거부하면서 기존 유효 위치를 보존한다. 3초 지속·원래 위치 만료·권한 해제·모의 위치·잘못된 좌표는 제한한다.
- 60도 이상 135도 미만 방향 변화는 서로 다른 측정이 300밀리초 이상 일치한 경우 실제 회전으로 확인한다. 수신 간격이 301~500밀리초인 정상 입력도 확인이 끝난다. 135도 이상 실제 반전의 기존 즉시 대응은 유지한다.
- 일반 상태 안내는 현재 문장이 끝날 때까지 기다린다. 중요한 명령 응답의 완료 권한도 보존한다. 일반 주의 단계가 기존 문장을 끊지 않으며 긴급 경고·정지는 우선한다.
- 긴 문장 대기를 음성 엔진의 준비 실패로 오판하지 않는다. 나뉜 음성의 중간 선점·재시도에서 앞부분을 중복하지 않는다.
- 이전 명령 응답이 자동 취소되거나 긴급 위험이 선점해도 현재 문맥에 속한 최신 대기 상태 안내는 남긴다. 새 사용자 명령·보행 세대·계정·화면·종료에 따른 취소 권한은 유지한다. 상태 유효성을 검사하는 도중 더 최신 안내가 등록되는 경우에도 이전 검사가 최신 안내를 지우지 않는다.
- 정상 이력이 있는 카메라의 승인 경계 주변 각도 변화는 원래 4.5초 재확인 한도 안에서 자동 회복을 기다린다. 현재 부적합 영상은 계속 실패로 취급하며, 기한을 연장하거나 유효 영상으로 승격하지 않는다. 큰 각도 이탈·오래된 영상·처음부터 부적합한 자세는 기존 교정 요구를 유지한다.
- 교차로 근처에서 새 위치가 없어 방향 안내의 문맥이 만료되면, 예약 무효화가 실제 음성 취소에도 전달된다. 무효화된 문장의 늦은 완료를 경로 진행으로 인정하지 않는다.

휴대폰을 책상에 둔 자세는 장애물 인식용 정상 장착으로 취급하지 않았다. 실제 센서 수신은 확인하되, 가려진 후면 카메라·장착 각도·새 위치 기준 부재를 시험에서 정상으로 바꾸지 않았다. 순간 잡음 완화와 지속적인 부적합 상태의 제한을 구분한다.

## 실사용 상황과 확인 방법

| 실사용 상황 | 기대 동작 | 검증 대상 |
| --- | --- | --- |
| 걷는 동안 휴대폰이 잠깐 대각선으로 흔들림 | 200밀리초 안의 여러 센서 값만으로 기존 문장을 취소하지 않음 | RouteHeadingTrendRegressionTest, RouteDirectionSessionFunctionalGraceTest |
| 실제로 90도 회전함 | 300밀리초 이상 이어진 관측으로 새 방향 확인 | RouteHeadingTrendRegressionTest |
| 기기 부하로 방향 수신이 느려짐 | 301·400·500밀리초 수신 간격에서도 회전 보류가 끝남 | RouteHeadingTrendRegressionTest |
| 사용자가 뒤로 돌아섬 | 큰 반전에는 기존 방향 안내를 유지하지 않음 | RouteHeadingTrendRegressionTest |
| 방향 오차가 경계값을 오감 | 일시 경계 변화로 문장 중단·경고 반복 없음 | RouteDirectionSessionFunctionalGraceTest |
| 방향 센서가 1초 누락됐다 회복 | 정상 이력 유예 중 무음 재확인 | RouteDirectionSessionFunctionalGraceTest |
| 방향을 계속 확인할 수 없음 | 유예 만료 뒤 구체적인 원인·행동 안내 | RouteDirectionGraceNoticeRegressionTest |
| 높은 건물 사이에서 GPS 좌표가 짧게 여러 번 도약 | 도약 좌표는 거절, 이전 위치와 시각은 보존 | PositioningTransientOutlierBurstTest |
| 큰 GPS 도약이 여러 초 계속됨 | 최초 장애 시작 시각을 갱신하지 않고 기능 제한 | PositioningTransientOutlierBurstTest |
| 위치 콜백 자체가 지연됨 | 오래된 입력을 짧은 잡음처럼 취급하지 않음 | PositioningTransientOutlierBurstTest |
| 위치가 오래됐거나 모의 위치·잘못된 좌표가 들어옴 | 정상 위치로 승격하거나 도착을 확정하지 않음 | PositioningTransientOutlierBurstTest, ContinuousGpsSessionTest |
| GPS·방향이 동시에 흔들렸다 서로 다른 순서로 회복 | 현재 문장을 보존하고 각각의 기한·회복을 유지 | CombinedSensorGraceFollowupTest, GuidanceSensorReplayTest |
| 카메라·GPS가 순간 실패했다 회복 | 유예 시작만으로 상태 경고를 내지 않음 | RuntimeEnvironmentWarningContinuityTest |
| 카메라 각도가 승인 경계를 잠깐 넘었다 돌아옴 | 현재 영상은 실패로 유지하며, 정상 이력이 유효한 짧은 재확인 동안 수동 교정 상태를 남기지 않음 | PhoneMountingBoundaryRecheckTest, CameraAngleContinuityDeviceTest |
| 큰 장착 각도 이탈·지속 경계 초과·처음부터 부적합한 각도 | 즉시 또는 원래 재확인 한도 뒤 교정 요구, 재확인 기한 재시작 없음 | PhoneMountingBoundaryRecheckTest, CameraAngleContinuityDeviceTest |
| 긴 길 안내 중 준비 완료 알림 도착 | 길 안내의 실제 완료 후 일반 상태 전달 | SpeechStatusContinuityRegressionTest |
| 중요한 명령 응답 중 상태가 여러 번 바뀜 | 명령 완료 권한 보존, 최신 유효 상태만 전달 | SpeechStatusContinuityRegressionTest |
| 실제 휴대폰 음성 완료 뒤 상태 안내가 대기함 | 상태 API 한 번 요청만으로 실제 Handler가 자동 재개하고 실제 음성 엔진이 완료 | SpeechOutputNativeContinuityDeviceTest |
| 실제 음성 명령을 긴급 정지가 선점하고 상태 안내가 대기함 | 명령 취소 1회, 긴급 음성 완료 뒤 대기 안내 자동 재생·완료 1회 | SpeechOutputNativeContinuityDeviceTest |
| 실제 음성 명령 중 대기 상태가 새 상태로 바뀜 | 기존 상태는 음성 엔진에 보내지 않고 최신 상태만 자동 완료 | SpeechOutputNativeContinuityDeviceTest |
| 앞 문장이 자동 취소되거나 긴급 위험에 선점됨 | 같은 현재 문맥의 대기 상태는 보존, 이전 문장 완료는 차단 | SpeechStatusContinuityRegressionTest |
| 상태 검사 도중 더 최신 상태 안내가 등록됨 | 이전 검사의 정리가 최신 등록을 지우지 않음 | SpeechStatusContinuityRegressionTest |
| 긴 상태 음성의 두 번째 부분 직전 다른 안내가 시작 | 첫 부분을 중복하지 않고 다음 부분에서 재개 | SpeechStatusContinuityRegressionTest |
| 일반 주의 물체가 길 안내 중 감지됨 | 기존 문장을 끊지 않고 최신 프레임으로 재평가 | CautionSpeechContinuityRegressionTest |
| 긴급 경고·정지 위험이 길 안내 중 감지됨 | 현재 문장을 선점하고 현재 경로만 재시도 | RouteSpeechDeviceTest |
| 경로 변경 후 이전 음성의 완료가 늦게 도착 | 새 경로 안내를 완료로 처리하지 않음 | RouteSpeechDeviceTest |
| 교차로 근처에서 새 위치가 들어오지 않음 | 무효화된 방향 안내를 실제 음성 중지에도 전달하고, 새 위치 전에는 회전·도착을 판단하지 않음 | RouteGuidanceContextExpiryRegressionTest |
| 책상 위에서 실제 센서 중지·재시작 | 이전 센서 세대 거부, 새 실제 수신 확인 | DeskSensorObservationDeviceTest |
| 시험이 소유한 센서 수신만 실제로 끊김 | 감시가 재등록하고 새 원본 센서 시각으로 복구 | DeskSensorObservationDeviceTest |

## 실행 결과

- 초기 관련 JVM 회귀시험: 11개 클래스, 163개 통과, 실패·오류·건너뜀 0. 이후 추가 수정이 있어 최종 결과와 구분한다.
- 기존 설치본의 실제 한국어 음성 완료·취소·재시도 컴포넌트 시험: 2개 통과.
- 기존 계정으로 실제 경로 시작: 로그인 완료 홈을 확보하지 못해 1개 실패. 앱에 설정된 기존 서버 도메인도 현재 연결되지 않음.
- 수정 후보 APK와 시험 APK 빌드·데이터 보존 설치·설치본 해시 대조 통과.
- 후보 실기기 음성 시험: 6개 통과, 107.201초. 실제 한국어 음성 엔진의 시작·완료·긴급 선점·재시도·늦은 완료를 확인했다. 경로·위험 입력과 상태 재시도 시점은 시험에서 제어했다.
- 후보 실기기 센서 시험: 1개 통과, 63.412초. 실제 센서 중지·재시작·시험 소유 리스너 수신 공백·재등록·세대 격리·정상 정리를 확인했다. 새 실제 위치 기준이 없어 방위 정확도는 판단하지 않았다.
- 확장 회귀시험 1차: 514개 클래스, 4,602개 중 19개 실패. 이전 즉시 경고·두 번만의 회전 확정·상태 음성 선점 계약과의 충돌, 기존 JVM 호출 호환, 문맥 만료 취소 전달을 조사·수정했다. 실패 결과는 보존하며 최종 통과로 계산하지 않는다.
- 카메라 경계 수정 후 확장시험 2차: 517개 클래스, 4,641개 통과. 실패·오류·건너뜀 0, 실행 중 소스 변경 없음. 별도 검토에서 찾은 음성 자동 취소 경합의 후속 변형과 재진입에 의한 최신 안내 유실을 추가 수정하고 아래 최종 결과로 재검증했다.
- 카메라 각도 기기 시험: 4개 통과, 5.585초. 실제 Android 시계·Handler·제품 게이트·장착 세션을 사용하며 각도·영상·준비 상태 입력은 제어했다. 실제 카메라 영상이나 실제 47도 자세를 측정한 결과로 해석하지 않는다.

### 최종 제품 소스와 설치본에서의 결과

| 확인 범위 | 결과 | 근거 파일 |
| --- | --- | --- |
| 관련 자동 회귀시험 | 517개 클래스, 4,644개 통과. 실패·오류·건너뜀 0, 시험 중 소스 변경 없음 | `jvm-final-expanded/summary.json`, `final-expanded-tests.log` |
| 실제 한국어 음성 엔진 | 6개 통과, 107.718초. 일반 알림·주의 비선점, 긴급 위험 선점, 현재 경로 재시도, 이전 경로의 실제 늦은 완료 확인 | `device-final-native_tts.txt` |
| 실제 센서 수신·중지/재시작·수신 공백 복구 | 1개 통과, 64.061초. 실제 새 센서 시각과 세대·정리 확인 | `device-final-native_sensor.txt` |
| 카메라 각도 게이트·장착 세션 | 제어 입력 4개 통과, 6.355초. 실제 Android 시계·Handler 사용 | `device-final-camera_gate.txt` |
| Main 화면·안내 소유자·Android Handler 연결 | 제어 입력 7개 통과, 20.937초. 일반 문장 완료 → 구체적인 경고 완료 → 실제 Handler의 경로 안내 예약·완료, ACTION/INFO 완료 횟수·늦은 경고 콜백 격리 확인 | `device-wrapper-r2.txt`, `device-wrapper-r2-result.json` |
| 실제 음성 엔진·출력 Controller·자동 재시도 연결 | 3개 통과, 34.345초. 실제 NAV 완료 → 상태 자동 재개, 실제 STOP 선점 후 대기 상태 자동 재개, 최신 상태만 자동 완료. 제어 콜백·가상 시계·수동 재요청 없음 | `device-native-controller.txt`, `device-native-controller-result.json` |
| 기존 실제 계정으로 경로 시작 | 1개 실패, 26.574초. 로그인 완료 홈 부재, 설정된 기존 서버 도메인 DNS 조회 실패. 자세 예외 적용 전 실패 | `device-final-real-route-attempt.json`, `device-final-real-route-attempt.txt` |

위 결과의 앱 SHA-256은 `dbd982b2020238e47a5290ab13dce6c36207c023c8f00441cf3fd833d316a82c`이다. `walksafe-guidance-continuity-debug.apk`를 데이터 삭제 없이 설치했고 설치 파일 해시도 대조했다. 실제 음성·센서·카메라 시험 APK SHA-256은 `bcb408c3884d2966953d7c6d4e8b867bb55fd7cb8c96b11cbf2e56a9bdebebdd`이다. Main 연결 재시험은 같은 앱을 유지하고 시험 APK만 `f0232a99a072bd0c6af03156b737cf43707124360f0b03b5439dac8c4320a31d`로 교체했다. 상세 경로·크기·대조 결과는 `installed-final.json`, `installed-wrapper-r2.json`에 있다. 재시험 중 제품 소스와 해당 시험 소스 변경은 없었다.

후속 자동 재개 연결 시험도 같은 사용자 앱을 유지하고 시험 APK만 `dfc87fbc6b7445afc091ad796597643f1807e6fec370da8bae0e8681b68da316`으로 설치했다. 상세 근거는 `installed-native-controller.json`, 합산 결과는 `verification-with-native-controller.json`이다. 실제 한국어 엔진·SystemClock·MainLooper Handler·기존 SpeechOutputController를 연결했다. 준비 문맥·위험·화면 포트는 제어 입력이며 Main 로그인·HTTP를 사용하지 않는다. 실제 이벤트 로그에서 NAV DONE → 상태 요청, 명령 DONE → 최신 상태 요청, 긴급 STOP DONE → 대기 상태 요청 순서를 확인했다. 각 대기 상태 API는 한 번만 호출했고 수동 재제출·콜백 주입·시계 가속이 없었다. 선점된 이전 발화의 원시 엔진 onStop은 기존 actuator가 노출하지 않아 별도 확인한 것으로 주장하지 않는다. 이 시험의 설치 직전·실행 후 설정 파일 해시는 모두 동일했다. 제품 소스 변경 없이 시험 APK만 빌드했으므로 자동 회귀시험은 반복하지 않았다. 독립 읽기 전용 검토에서 소스와 실제 실행 결과를 대조했다.

Main 연결 첫 실행은 7개 중 2개가 실패했다. 기존 시험이 첫 제출을 경로 안내로 가정했으나 새 구체적인 지속 장애 경고가 먼저 제출된 결과였다. 경고의 START/DONE → 실제 250밀리초 Handler 재시도 → 경로 안내 예약·START/DONE·완료 승인까지 시험을 보완했다. 원 GPS 측정 시각, 9초 방향 유예, 기존 유예 기한 뒤 750밀리초 관측 상한과 위치 유효기간을 그대로 유지했다. 경고가 경로를 미리 예약하거나 완료 처리하지 않는 검사와 늦은 경고 DONE/ERROR 격리 검사를 추가했다. 첫 실패 로그는 `device-final-main_wrapper.txt`에 보존했다. 독립 읽기 전용 검토에서도 제품 실패를 숨기는 완화나 추가 확정 결함은 발견하지 못했다.

실제 기기 시험도 입력·콜백의 경계가 다르다. 음성 시험은 경로·위험 입력과 재시도 시점을 제어하고 실제 음성 엔진의 시작·완료를 관찰한다. 센서 시험은 실제 Android 센서를 관찰하지만 새 실제 위치 기준이 없어 절대 방위 정확도를 판단하지 않는다. 카메라 시험은 각도·영상 품질 입력을 제어한다. Main 연결 시험은 준비된 계정·경로·준비 상태와 제어 음성 콜백을 쓰며 실제 로그인·HTTP·음향 검증이 아니다.

최종 앱 설치 직전부터 첫 기기 컴포넌트 실행까지 확인한 설정 파일 7개 해시가 모두 동일했다. 이후 실제 계정 경로 시작을 위해 Main을 기동한 시각에 일반 설정 `walksafe.xml`과 Android ID 파일의 해시가 바뀌었고, 나머지 5개 파일은 동일했다. 일반 설정에 기동 진단 시각을 기록하는 기존 코드도 확인했으나, 작업 시작 시점의 원문이 없어 파일 안의 모든 항목을 비교한 것은 아니다. 최초 설치 시각은 2026-10-03 23:26:25로 유지됐다. 최초 설정 해시 수집 시도는 명령 오류로 무효였으므로, 전체 작업 동안 모든 설정이 동일했다는 근거로 사용하지 않는다. 비교 범위와 오류 기록은 `preference-preservation-final.json`, `prefs-hashes-*.txt`, `prefs-hash-attempt-note.json`에 남겼다.

### 남은 실제 사용 검증

- 유효한 서버 연결과 기존 사용자 로그인이 복구된 상태에서 목적지 선택 → 경로 시작 → 음성 안내 유지 → 실제 도착 → 종료 후 홈을 확인해야 한다.
- 후면이 책상을 향한 상태에서 실제 보행 방향·후면 영상의 장애물 인식·야외 위치 정확도·사람이 들은 음성의 명료함은 확인하지 않았다.
- 서버·DB·저장된 계정/교육 자료를 시험용으로 초기화하거나, 로그인·장착·센서 신뢰 조건을 우회해 전체 경로를 성공 처리하지 않았다.

### 실제 서버 복구 선행조건 확인

2026-10-25 02:01 재확인에서 사용자 앱·시험 APK 설치 해시가 기록과 일치하고, 47개 작업 파일의 해시도 동일했다. 기존 서버 도메인은 여전히 DNS 조회 실패였으며 Backend·Gateway·Voice 서비스는 실행되지 않고 있었다. 근거는 `third-turn-blocker-check.json`이다.

기존 PostGIS 컨테이너만 잠시 정상 기동하여 기존 실행기의 `db-current`와 `db-heads`를 조회했다. 실제 DB revision은 `202609300009`, 현재 서버가 요구하는 head는 `202610110001`이었다. 두 조회는 성공했고 스키마 업그레이드·초기화·Backend/Gateway/Voice 기동은 하지 않았다. 확인 후 컨테이너를 정상 종료해 중지 상태로 되돌렸다. 근거는 `database-metadata-check.json`, `database-metadata-restored-stopped.json`이다.

현재 공식 Gateway 기동 경로는 계정 삭제 outbox worker를 즉시 시작하며, Backend 기동은 기존 신고·RAW 저장소 복구와 일부 상태 기록을 수행한다. 이를 끄고 일반 로그인·경로 API만 제공하는 공식 서비스 진입점은 확인하지 못했다. 따라서 단순한 읽기 전용 연결 확인처럼 전체 서버를 시작하지 않았다. 또 저장된 로그인은 서버 origin에 결속되어 있어 USB loopback 등 다른 주소로 자동 이전할 수 없다. 실제 계정의 검증을 이어가려면 정상 서버 연결과 휴대폰에서의 기존 계정 로그인이 필요하다.

## 원본과 근거

이번 작업의 사본·실행 로그·시험 보고서는 `/home/ddobagi/.local/state/walksafe-development/guidance-continuity-20261025`에 보관한다. 원래 사용자 APK와 시험 APK도 해당 폴더에 보존했다. 계정·교육·동의·권한 데이터를 지우지 않는다.

음성 처리 확인에는 [Android 음성 대기열 공식 문서](https://developer.android.com/reference/android/speech/tts/TextToSpeech#QUEUE_FLUSH)와 [음성 중단 콜백 문서](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener#onStop(java.lang.String,%20boolean))를 참고했다. 실제 기기의 시작·완료 콜백은 사람의 청취나 야외 안전 확인과 구분한다.
