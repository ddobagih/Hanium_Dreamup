# 방향 센서 복구 및 콜백 스레드 개선

## 적용

- 기준 커밋: `2f50e2968e2561627b77d4bad8ba2c27f071dca0`, `current`.
- 사용자 제공 `/home/ddobagi/Downloads/route_compass_fix.zip`의 패치를 적용했다. 제품 Tracker와 센서 생명주기 정적 시험은 제공 modified 파일과 바이트 일치한다.
- 회전 센서 품질 거부 근거와 최신 회전 이벤트가 모두 500ms보다 오래되면, 현재 중력·자력계 관측을 독립적으로 평가한다. 최근 불량 관측, 신선도·자기장·자세 기준은 계속 적용한다.
- 네 방향 센서 콜백을 동일한 전용 HandlerThread에서 수신한다. 종료·등록 실패 때 리스너 해제와 quitSafely를 수행하며 이전 등록의 콜백은 세대·리스너 경계로 거부한다.
- 방향 거부 이유, 센서 종류, 진북 방위, 관측 나이를 opt-in RouteCompass 로그에 최대 초당 한 번 남긴다. 좌표는 기록하지 않는다.
- 후면 카메라 수평 방향 기준과 상대각 계산을 유지한다. 북쪽 경로를 남쪽으로 바라보면 6시이며, 카메라가 바닥을 향해 수평 방향을 정할 수 없는 자세는 계속 거부한다.

## 검증

- 동일 제공 회귀 시험: 원본 28 통과/5 실패 → 현재 제품 소스 33 통과/0 실패. 원본 실패 중 하나는 새 진단 로그 검사다.
- 관련 Android 단위·통합 시험 167 suites, 1,601 tests, 실패·오류 0. 기존 12개 레거시 정적 검사 제외를 그대로 사용했으며 전체 저장소 무제외 통과를 의미하지 않는다. 실제 ChestMountedHeading 단위 시험도 포함됐다.
- assembleDebug, git diff --check, 제공 수정본과의 byte 비교 통과.
- 독립 검수에서 잔여 P1/P2 지적 없음. 현재 Tracker를 대상으로 콜백이 monitor에서 대기 중인 stop→start 20회, 센서 등록 성공/실패 16조합의 시작·정리 검사를 모두 통과했다. 근거는 `work/compass-patch-20260915/review/tests.log`이며 실제 JVM 스레드와 Android 경계 대역을 사용했다.
- 제공 33개 시험은 Android 센서·시계·지자기·PDR·기하 경계를 대역으로 사용한다. 이 시험만으로 실제 센서 정확도나 PDR 동작을 입증하지 않는다.
- Handler 전달 스레드와 quitSafely 동작은 [SensorManager 공식 문서](https://developer.android.com/reference/android/hardware/SensorManager) 및 [HandlerThread 공식 문서](https://developer.android.com/reference/android/os/HandlerThread)와 대조했다.
- 원시 실행 근거: `work/compass-patch-20260915/regression-results.json`, `integration/command.json`, `integration/tests.json`, `integration/gradle.log`. 제공 자료와 실행기는 같은 work 디렉터리에 보존했다.

## APK 및 현장 확인

- APK: `work/apk-download-20260915/public/WalkSafe-compass-thread-20260916.apk`.
- SHA-256: `cf48bd914424d679da1d4332c99ff0964c4e288159d51f3a771cfff81300571d`.
- 원본 패키지 `kr.co.hanium.dreamup.walksafe`, 기존 APK와 동일 서명 확인.
- 공개 다운로드 응답 HTTP 200, 크기 453,805,096 bytes, ZIP 서명 확인: [수정 APK](https://advisors-skirt-editing-assessing.trycloudflare.com/WalkSafe-compass-thread-20260916.apk). 임시 터널이 종료되면 링크는 사용할 수 없다.
- 현재 ADB 연결 기기 없음. 설치와 실기기 방향·음성 안내 검증은 미수행이다.
- 현장 확인: 카메라 후면을 진행 방향으로 세운 상태에서 안내 시작 전후 제자리 회전, 북쪽 경로/남쪽 후면 방향의 6시 안내, 앱 재진입 후 방향 갱신을 확인한다. 진단이 필요하면 아래 로그로 방향 방위와 관측 나이, 거부 이유를 함께 확인한다.

```bash
adb shell setprop log.tag.RouteCompass DEBUG
adb logcat -v threadtime 'RouteCompass:D *:S'
```
