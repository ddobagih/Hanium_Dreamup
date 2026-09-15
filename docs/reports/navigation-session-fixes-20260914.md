# 보행 재시작·테스트 안내 제한·TMAP 음성 연결 수정

## 요청과 실행 계획

1. 같은 휴대폰 재시작 충돌: 새 walk ID와 서버에 남은 기존 lease를 재현 → 동일 actor/device/login family 소유 확인 → 정확한 lease 종료 후 새 시작 → 취소·만료·다른 기기 경쟁 회귀 검증.
2. 테스트 APK 안내 제한: 시작 버튼 이전 목적지 설정부터 안내 중까지 기존 DEBUG bypass를 일관 적용 → 위치 품질·환경·장착·배터리·카메라·추론 실패가 전체 길 안내를 중지하지 않게 분리 → 실제 권한·좌표·로그인·다른 기기 소유권은 유지.
3. TMAP 실제 연결: Android→Gateway→backend→TMAP 보행 경로 확인 → 거리·회전·횡단보도 안내 누락 재현/수정 → 실제 경로 요청 및 고정 경로 시험.
4. 통합 시험·독립 검토 → debug APK 빌드·연결 기기 업데이트 설치. 앱 데이터 보존, 자동 실행/현장 보행 시험 없음.

## 현재 확인

- 서버는 동일 기기라도 서로 다른 walk ID가 겹치면 409를 반환하며, 기존 Android는 모든 충돌을 다른 기기라고 표시했다.
- 서버 lease 유지 시간 90초. 동일 device ID만으로 종료하지 않고 서버 actor/device/login family 검증을 먼저 수행한다.
- 테스트 bypass가 버튼 클릭 상태에 의존하여 목적지 검색·런타임 일부 경로에서 적용되지 않았다.
- 실제 TMAP 보행 경로를 사용한다. 실패 시 가짜 경로로 대체하지 않는다.
- TMAP 좌회전 안내 설명에 횡단보도가 포함되면 거리·회전 대신 고정 주의문만 말하고 가까워져도 재안내하지 않는 결함을 재현했다.

## 검증 경계

- 테스트 안내에 낮은 정확도의 실제 GPS 좌표를 사용할 수 있도록 하되 원본 정확도·측정 시각과 객체/점자블록에 쓰는 품질 판정은 바꾸지 않는다.
- 실제 위치 권한 없음, 현재 좌표 없음, 실제 TMAP 오류, 음성 엔진 실패는 성공으로 위장하지 않는다.
- 암호화 경로 정리·보관 만료는 계속 수행한다. 테스트 중 경로 저장 실패는 메모리 경로 안내와 분리한다.
- 현장 안내 정확도와 스피커 출력의 최종 확인은 사용자가 수행한다.

## 근거

`work/navigation-session-fixes-20260914/` 아래 세션·게이트 감사, 클라이언트 회귀, TMAP 감사/재현 및 통합 결과를 기록한다. 최종 결과는 통합 검증 후 추가한다.


## 최종 구현과 검증 (2026-09-15)

- `GatewayWalkSession.startWithOwnedWalkRecovery`: 최대 4회 요청 내 동일 계정/기기/로그인 family의 잔류 보행만 정리한다. 정상 종료·만료·타 기기 takeover 경쟁을 CAS로 구분하고 매 단계 현재 UI 요청/로그인 상태를 확인한다. 같은 기기의 다른 로그인과 실제 다른 기기를 안내 문구에서도 구분한다.
- `MainActivity`: 테스트 우회가 목적지 검색 이전과 안내 중에도 적용된다. 배터리/발열/저장 공간/추론 지연 때문에 전체 보행을 중지하지 않으며 카메라 release 콜백과 길안내 시작을 분리했다. 모델·TTS 실패는 해당 출력 실패로 남긴다. 실제 epoch/time·로그인·삭제·서버 lease 검증은 유지한다.
- `NavigationTestLocationPolicy`와 `RouteNavigator`: 현재 실제 GPS가 부정확하더라도 안내용 TMAP 경로 투영을 허용한다. 실제 정확도·측정 시각·accepted match는 바꾸지 않는다. 이탈/위치 재확인/도착 확인으로 테스트 발화를 막지 않으며 무효 좌표·미래·10초 초과 오래된 좌표는 안내 근거가 되지 않는다.
- TMAP 횡단보도 문구가 포함된 일반 회전 안내에 거리·방향을 함께 넣고 접근 거리 단계가 바뀌면 다시 말한다. 100m→20m 좌회전 안내·주의 문구 보존을 검증했다.
- 목적지 검색·TMAP 요청은 테스트 APK에서 모바일 데이터 설정에 의해 차단하지 않는다. 인터넷 연결이 실제 없으면 요청 실패를 그대로 표시한다. 다른 전송 기능의 설정은 변경하지 않았다.

| 검증 | 결과 | 근거 |
| --- | --- | --- |
| Android 최종 선택 회귀·APK 빌드 | 2,282건, 실패/오류/skip 0; BUILD SUCCESSFUL | `integration-final/summary.json`, `command.json`, `gradle.log` |
| 세션 복구 독립 검증 | 37/37; 실제 현재 TS ledger와 Kotlin client를 연결한 6개 포함 | `session-audit/recovery-review.md` |
| RouteNavigator 독립 검증 | 73/73; 위 통합 시험에도 포함 | `tmap-live/route-review.md` |
| Backend 경로/POI 격리 시험 | 124/124 | `tmap-live/loopback-allowed/backend-result.json` |
| 실제 제공자 호출 | HTTP 200, TMAP 보행 경로 466m, 좌표 24점, 안내점 5개, 0.302초 | `tmap-live/live-route-result.json` |
| 외부 Gateway 접근 | 실제 field-walk API가 정상적인 미인증 401 응답 반환 | `public-gateway-reachability.json` |
| APK 내용 | 새 복구/안내 정책 코드가 DEX에 존재; 모델 6개 SHA가 v6 APK와 동일 | `apk.json` |

### 넓은 시험에서 확인한 기존 불일치와 한계

처음 넓힌 시험은 2,295개 중 16개가 실패했다. 이 중 이번 호출 이름/분기 변경에 따른 기존 정적 테스트 3개의 추출 기준을 수정했다. 새 권한 모달 시험 1개는 Android JVM stub의 `isFinishing not mocked` 오류로 실행할 수 없어 제거했고, 통과로 계산하지 않았다. Main 실제 인스턴스 회귀 4개는 통합 통과했다.

나머지 기존 정적 불일치 12개는 최종 선택 회귀에서 명시적으로 제외했다. 11개는 수정 전/후 Main 소스에 같은 컴파일 테스트를 실행하여 같은 assert 실패를 재현했다. ARCore bytecode 검사 1개는 관련 3개 메서드 소스가 수정 전/후 byte-identical하고 현재 실패가 재현됨을 확인했으며 수정 전 전체 APK 재컴파일은 하지 않았다. 목록은 `preexisting-static-exclusions.json`, 분류/근거는 `baseline-audit/baseline-classification.json`에 있다. 전체 저장소 시험이 모두 통과했다고 주장하지 않는다.

실제 TMAP 확인은 현재 실행 중인 backend 설정과 현재 adapter를 사용한 공개장소 간 1회 경로 요청이다. 사용자 실제 위치·계정·DB를 사용하지 않았다. 기기의 로그인된 앱부터 Gateway를 거치는 현장 길안내·스피커 출력·권한 모달은 사용자가 확인할 범위로 남아 있다.

### APK와 설치

- APK: `/home/ddobagi/Hanium_Dreamup/apps/android/app/build/outputs/apk/debug/app-debug.apk`
- SHA-256: `4306bf4c0d8ae63775fd81065e886d75605f34396cbcf842cd4e6a3d5d92d353`
- 기존 Cloudflare endpoint 및 로그인 후 기기점검 설정 유지, DEBUG 안내 제한 해제 활성화.
- 첫 설치 시도 직전 USB가 끊겼으나 사용자 재연결 후 Galaxy S25 (`SM_S931N`)에 `adb install -r` 성공. 패키지 `lastUpdateTime=2026-09-15 00:23:08`, 휴대폰에 설치된 base.apk SHA-256이 빌드 APK와 정확히 일치함을 확인했다. `install.json`에 기록했으며 앱 자동 실행·데이터 초기화는 하지 않았다.
