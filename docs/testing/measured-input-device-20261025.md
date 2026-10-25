# 2026-10-25 휴대폰 측정값 주입 검증

## 결과와 증거 범위

USB 연결 Galaxy S25(SM-S931N)에 시험 측정값을 넣어 **4개 사례가 모두 통과**했다. 최종 실행은 104.202초였다. 같은 수정 소스의 자동 회귀시험 **518개 클래스·4,653개 시험도 전부 통과**했으며 실패·오류·건너뜀·소스 변경은 0이다.

측정값은 시험 전용 입력원이 만든 값이고, 위치·방향 제어와 경로 판단·Main Handler·SystemClock·한국어 음성 엔진은 실제 휴대폰에서 실행했다. 실제 센서 자체의 측정값을 바꾸거나 물리 이동을 재현한 시험은 아니다. 실제 MainActivity 시작·기존 계정 UI·서버 경로 수신·사람 청취·야외 보행은 이 성공 범위에 포함하지 않는다.

## 실기기에서 발견하고 수정한 문제

1. **정상 GPS를 장애로 오판:** GPS 수신 시각과 Main 처리 시각 사이에 watchdog 평가가 먼저 실행되면, 수신 시각으로 정책을 다시 평가하면서 정상적인 큐 대기를 시계 역행으로 처리했다. 기존 실패에서 원 GPS 1723882ms는 정상 APPLIED·품질 PASS였지만 1723883ms 평가 뒤 처리되어 위치 문제 음성과 방향 제한이 발생했다.
2. **짧은 GPS 도약에 기존 위치 소거:** 품질 gate는 현재 처리 시각으로 재확인 기간을 시작했는데, 직후 과거 수신 시각으로 조회했다. 음수 경과 시간으로 PASS가 해제되어 정상 위치·원시각이 지워졌다.

PositioningCoordinator는 원 측정·수신 시각을 estimator에 그대로 전달하고 현재 처리 시각으로 confidence와 snapshot을 평가한다. PositioningSessionController는 gate 기록 뒤 새 현재 시각으로 조회한다. 미래·음수·만료·중복·순서·소유권 검사는 유지하며, 기존 3초 GPS 도약 확인·9초 방향 재확인·10초 위치 만료 기준은 바꾸지 않았다.

수정 전 결정적 회귀시험 7개 중 두 오류가 실제 실패했다. 수정 후에는 처리 도중 시각이 더 진행하는 조건과 수신 시각이 처리 시각보다 앞선 잘못된 입력까지 포함한 9개 시험이 통과했다.

## 실제 휴대폰 사례

| 사례 | 입력과 확인 | 최종 관측 |
| --- | --- | --- |
| 짧은 큰 GPS 도약 | 정상 이력 뒤 1.25초 동안 북쪽 1000m 도약 | 실제 두 입력이 HARD_REJECTED/CORRUPT_JUMP. 기존 trusted/raw 위치의 원시각 2946541ms 유지, gate PASS, 회복 GPS 수용, 원 음성 DONE·ACK 1회 |
| 짧은 GPS 오차 보정 | 1.25초 동안 북쪽 200m 오차 | 실제 두 입력이 DOWNWEIGHTED. weight 약 0.0494/0.0572, 필터 오차 약 0.396/1.434m. 최종 정상 오차 약 0.000849m가 합산 불확실도 4.018m 이내, 원 음성 DONE·ACK 1회 |
| 방향 정확도 저하 후 회복 | 90도 방향, 정확도 5→35→5도, 약 650ms 저하 | 불안정 13프레임 동안 원 onset/deadline 2929140/2938140ms 고정. 약 670ms 뒤 정상 관측, 원 음성 START→DONE·ACK 1회 |
| 경로 밖에서 접근·합류 | 경로 북쪽 30m, 1.2m/s로 접근 | 최근접 경로 지점의 상대 시계 방향·직선거리 안내, 더 새 GPS에 따른 실제 합류와 일반 경로 안내. 30m·12m 접근 안내와 정상 550m 안내가 각각 native DONE·ACK |

전체 GPS 210건에서 원 측정 시각≤수신 시각≤처리 시각을 확인했다. native 음성은 REQUESTED·STARTED·DONE 각각 6건, 문제 상태 음성 요청·취소·STOP·엔진 오류는 0건이었다. 이 수치는 이번 최종 실행의 관측이다.

접근 사례의 시험은 위치 변화로 기존 수치의 근거가 사라진 중간 접근 문장의 제한된 취소를 별도 관찰한다. 같은 경로/버전·더 새 GPS·거리 감소·pending token 없음이 필요하며, 추가 유효성 predicate 호출은 하지 않는다. 취소 당시 구체적인 거리·방향 근거 소실을 직접 증명하는 한계가 있다. **이번 최종 실행은 그 취소 허용 예외를 사용하지 않고 모두 완료했다.**

## 실행 구성

- GPS는 PositioningSessionController의 실제 소유권·세대·binding·원시각 검사를 통과한다. 방향은 시험 전용 Tracker에서 RouteCompassSession의 실제 수신·coalescing·유예 경로로 전달한다.
- NavigationSessionComposition의 실제 위치 보정·방향 판단·RouteNavigator·RouteGuidanceController와 SpeechOutputController/AndroidFeedbackActuator를 연결한다. Navigator 직접 update·수동 ACK·가짜 native 완료·음성 중 시계 정지는 없다.
- 별도 생산원이 실제 시간에 따라 GPS 약 2Hz·방향 약 20Hz를 계속 공급한다. 기존 물리 GPS·회전 센서·보행 수집원을 등록하지 않아 시험값과 섞지 않는다.
- 시험 actor·계정 문맥·준비 상태·경로·SDK 해독 뒤 GPS와 후면축 투영 뒤 방향은 메모리 내 제어 입력이다. 영속 프로필 저장을 금지하고 기존 계정·교육 기록을 조작하지 않는다.
- 실제 연결 단말과 native TTS를 사용하는 명시적 실행 인자 `allowMeasuredInputGuidanceTest=true`를 전달했다. 4개 모두 실행됐으며 건너뛴 시험은 없다.

## 설치본과 보존

| 항목 | SHA-256 |
| --- | --- |
| 사용자 앱 | `bd3b514021a64496641ce1abc1a914e34267ede0504a310a9c1d585c18b5dcea` |
| 시험 APK | `7fa61db6258a11eb8d3fd1bc97edd676e7f8d44a010132d492b29e823795b0a7` |
| PositioningCoordinator | `d796d9a73988b3302357847296db5882c1bdffd8382cc2a0486d02598f1ab0c2` |
| PositioningSessionController | `f400c53d17a3d97c5c46ade0fb243b2981a33856bcc3bcc6b859a696b255a84d` |
| 기기 시험 | `830893fb60fc2de227f226120ae743fff557680d12641dc0c0e1f02e0523434c` |
| 기기 fixture | `055ddf15c84ec85b33fb43c0b0d4d2329c8e44994e8cdf9a9f11a21ab5ae4c0a` |

기존 사용자 앱 `dbd982b2…`에서 새 `bd3b5140…`으로 데이터 삭제 없이 갱신했다. 설치된 사용자·시험 APK 해시를 다시 대조했다. 초기와 최종의 기존 설정 XML 7개 해시가 모두 같았다. 제품 소스 650개 중 위 두 파일만 초기 대비 변경됐으며, 승인한 최종 소스와 시험 6파일은 실행 전후 같았다. 서버·DB·로그인·권한·교육·센서 모의 위치 설정은 변경하지 않았다. 커밋·푸시는 수행하지 않았다.

## 실패 이력과 최종 판정

- `attempt-r1`: 새 fixture의 동명 인터페이스 충돌로 컴파일 실패. 설치·기기 실행 0회.
- `attempt-r2`: 기존 앱의 3개 중 2개 통과. 200m를 HARD_REJECTED로 기대했으나 실제는 DOWNWEIGHTED여서 실패했다. 검사 기준을 낮추지 않고 1000m hard 거부 사례와 200m soft 보정 사례로 구분했다. 실패 직후 정리되어 당시 200m의 native 완료는 증명하지 못했다.
- `attempt-r3`: 기존 앱의 3개 중 방향 1개만 통과. 위 두 실제 시간 처리 결함과 거절 snapshot 관측 누락을 발견했다. 기존 typed Diagnostics.confidenceLine에서 실제 snapshot을 관측하도록 시험을 보완했다.
- `clock-jvm-baseline-results`: 수정 전 7개 중 2개 실패를 재현했다.
- `clock-fixed-jvm-r1-results`: 4,653개 중 기존 Composition 시험의 관측 기대값 1개 실패. 수신 시각이라고 이름 붙인 값이 실제로 현재 평가 시각이어서 이름·기대값을 정정하고, 별도로 원 측정 1800/수신 1950/평가 2000·ns·owner 검사를 추가했다.
- `clock-fixed-jvm-r2-summary.json`: 시험 파일 명시 cast 컴파일 실패·동결 해시 변경이 있어 실제 시험 실행 0회다. Gradle이 남긴 이전 XML은 새 실행 근거에서 제외했다.
- 최종 `clock-fixed-jvm-summary.json`은 실행 후 새로 생성된 XML만 집계한 4,653/4,653 통과다. 최종 `result.json`은 수정 APK의 4/4 통과다. 독립 검토자가 원 XML·기기 로그·소스·설치 해시와 판정 범위를 대조했다.

## 남은 확인

실제 SensorEvent에서 방향을 계산하는 변환·물리 GPS 오차·실보행 PDR·야외 안전성·실제 사용자 청취는 별도 검증이 필요하다. 최근접 지점의 시계 방향과 직선거리는 보행 가능한 연결 경로 생성의 증거가 아니다. 실제 Main UI의 계정 로그인→서버 경로 수신→도착 전체 흐름과 카메라 테스트 버튼의 멈춤도 이번 시험으로 검증하지 않았다. 책상에 놓인 카메라 자세를 정상 장착으로 승인하지 않았다. 기존 전체 Goal의 완료 판정을 바꾸지 않는다.

상세 원본: `/home/ddobagi/.local/state/walksafe-development/measured-input-device-20261025/`. 주요 파일은 `final-verification.json`, `result.json`, `device-inputs.txt`, `installed.json`, `target-device.json`, `clock-fixed-jvm-summary.json`과 원 XML, 빌드·실패 이력이다.
