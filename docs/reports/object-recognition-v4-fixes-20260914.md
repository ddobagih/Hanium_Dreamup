# 객체 인식 독립 검수 v4 개선

원본 Android 앱의 독립 검수 4건을 개선한다. 모델과 768 입력, 기존 WIP, 사용자 데이터는 유지한다. 실기기 테스트는 사용자가 진행한다.

## 단계와 인수 기준

| 단계 | 담당 | 구현 | 검증 |
|---|---|---|---|
| 1 | 좌표 담당 | 점자블록 접지점·하단·좌우는 upright, 물리 투영은 센서 좌표 | 4회전 동일 물리 장면의 방향·위치 일치, 좌우/클립/불명 회전 대조 |
| 2 | Root | 신선한 Full 결과와 유효한 기존 Raw 경고를 함께 보관 | 원래 frame/capture/deadline 유지, Main/Camera STOP 시작, 만료·소멸·문맥 변경 |
| 3 | 추적 담당 | 새 검출과 이전 추적 위치를 같은 시각에서 대응 | 두 이동객체/400ms ID 유지, 단일/100ms·교대·가림·교차·예산 대조 |
| 4 | 안내 담당 | 같은 capture의 과거 관측과 현재 연속 추적을 이용한 비동기 중복 억제 | 같은 물체 100ms 지연, 별도 객체·위험 상승·지연 시작/완료·문맥 무효화 |
| 5 | Root + 독립 검수자 | 연결 통합·회귀·APK 빌드 | 각 원실패 개선, 독립 지적 해소, 관련 테스트와 빌드 통과 |

1/3/4는 소유 파일별 병렬 구현하며 2와 앱 연결은 Root가 담당한다. 제품이 바뀐 뒤 구현자와 다른 검수자가 원 실패와 경계 조건을 확인한다. 동시 성공 추적 3개 상한은 이번 4개 결함 수정과 구분하여 유지한다.

## 진행 상태

- 4건 구현·독립 재검수·최종 통합 검증 완료. 초기 검수 및 고정 재현은 `work/object-recognition-v4-fixes-20260914/audit/`에 보존했다.
- 실제 신경망 정확도·폰 ARCore·음성·야외 성능을 이번 합성 시험으로 증명하지 않는다.

## 구현과 원실패 대조

| 항목 | 수정 전 | 수정 후 확인 |
|---|---|---|
| 점자블록 세로 안내 | 같은 전방 물체 q1 오른쪽/q3 왼쪽 | q0~3 모두 전방, 같은 접점 Earth (0,2,-1.28)m |
| 혼합 Raw/Full | 새 Full WARNING이 아직 유효한 Raw STOP 삭제 | Main/Camera STOP 시작 허용, 기존 잔여500ms·새 경고와 다른 deadline 유지 |
| 복수 이동 객체 | 객체별 ID6개, 현재94개 중 안정 출력0 | 객체별 ID1개, 현재94개 유지·안정 출력62 |
| 비동기 같은 물체 | 3초 PRIMARY→UNKNOWN→PRIMARY | PRIMARY→PRIMARY, 별도 객체는 모두 유지 |

- 좌표: 접지점은 upright에서 선택하고 센서로 역변환한다. 화면상 좌우와 물리 깊이 투영 좌표를 구분한다. 잘린 하단과 회전 불명은 점자 방향 안내만 거절하고 유효한 Depth는 유지한다.
- 보존: 원본 batch/sample을 여러 개 유지하여 각 capture·deadline을 보존한다. 현재 같은 ID의 신선한 근거는 교체하며, 지지 ID에서 빠지거나 만료된 영역은 새로운 출력 시작을 막는다. 이미 수락된 완료와 명시 문맥 취소는 구분한다.
- 추적: source 시각의 실제 관측을 사용하며, 정확한 시각이 없으면 source 이전 최근 관측으로만 비교한다. 독립 검수에서 발견한 current-only fallback의 ID 상호 승계도 제거했다. 실제 교차·상충에서는 새 ID로 다시 누적한다.
- 비동기 안내: FastSAM capture와 같은 시점의 주 객체 관측부터 현재까지 유일한 영역·거리·방향 연속성을 확인한다. 원본 frame/deadline은 바꾸지 않는다. 기존 추적의 시간/이력 한계를 재사용하며, 근거가 없으면 두 경고를 보존한다.

위 수치는 호스트 합성 입력의 후보·예약 결과이다. 실제 음성 횟수, 실제 영상 인식률, ARCore 정확도 또는 현장 안전성 수치가 아니다.

## 독립 검수에서 보완한 경계

- source 이력이 없는 1090ms 검출에서 source/current가 상충할 때 이전 두 ID가 교환되어 승계되는 회귀를 발견했다. source 이전 근거로 비교하도록 보완하고 직접 pipeline 입력까지 재검증했다.
- 이미 claim된 UNKNOWN STOP에 PRIMARY가 같은 STOP으로 뒤따를 때 대표 이름 교체 때문에 기존 출력이 취소되는 경계를 발견했다. 동급 진행 출력은 유지하고 더 높은 위험은 선점하도록 보완했다. claim 확인·선택·취소·평가를 같은 정책 monitor에서 수행하고 실제 출력 호출은 lock 밖으로 두어 콜백 경쟁도 차단했다.

## 1차 통합

- 관련 Gradle 91 suites/990 tests, 실패·오류·skip 0. debug APK 빌드 성공.
- 이 실행 후 독립 검수의 추가 경계 보완이 있으므로 최종 검증·APK 판정은 별도 기록한다.
- 근거: `work/object-recognition-v4-fixes-20260914/first-integration/`.


## 최종 검증 결과

- 관련 Gradle **91 suites / 998 tests**, 실패·오류·skip **0/0/0**. `:app:assembleDebug` 성공. 원본 앱의 관련 영역 시험이며 전체 저장소·실기기 시험을 의미하지 않는다.
- 좌표 독립 검수: JUnit67개 + 독립경계79조건 통과. 회전·좌우·클립·비균일깊이·일반 클래스 영향을 확인했다.
- 혼합 보존: Root33개 회귀와 최종 실제 Main/Camera/actuator/producer 독립36개 통과.
- 추적 독립 검수: 최종159개 통과. source 시각이 없는 추가 상충 회귀와 실제 pipeline 연결까지 확인했다.
- 중복 안내: 자체94개 및 최종 독립6개 통과. 동급 진행 STOP 유지와 다른 스레드의 출력 콜백을 확인했다.
- 위 부분 시험 수는 서로 겹치므로 합산하지 않는다. 최종 독립 검토 범위에서 미해결 지적 없음.
- 기존 고정 마스크 **5,521개**, 입력 SHA/ID 일치, 직전 v3 대비 `show/warningCandidate/reason` 변경 **0개**. 모델 추론·카메라·Depth를 재실행한 정확도 시험이 아니라 캐시된 마스크의 선별 회귀다.
- `git diff --check` 통과. 기존 WIP를 보존했으며 커밋·push·폰 설치는 하지 않았다.

### APK

- 경로: `/home/ddobagi/Hanium_Dreamup/apps/android/app/build/outputs/apk/debug/app-debug.apk`
- SHA256: `50367860ae724687c69a8c794fdd972d1d44e149ba594ad29f31174b145d0f19`
- 크기: 454183607 bytes
- 신규 upright 접점, 복수 경고 보존, source 시각 관측, 완료 이력 연결·claim 조회·WarningRegionContext 코드의 DEX 포함을 확인했다.
- 기존 **21클래스/768 모델** `assets/models/walkmate_yolo11n_21cls_768_float32.tflite` 유지. 모델 SHA256 `d10aa174a2ceb8ae116a09b7b570e27ff71c296fbd98f240c11e69dbdb434b57`.
- 기존 outdoor origin `https://yield-bacterial-corners-necklace.trycloudflare.com`을 빌드에 유지했다. 서버 접속 상태를 이번 작업에서 확인한 것은 아니다.

## 근거 파일

- [최종 Gradle 명령·결과](/home/ddobagi/Hanium_Dreamup/work/object-recognition-v4-fixes-20260914/gradle-command.json), [시험 집계](/home/ddobagi/Hanium_Dreamup/work/object-recognition-v4-fixes-20260914/gradle-tests.json)
- [좌표 검수](/home/ddobagi/Hanium_Dreamup/work/object-recognition-v4-fixes-20260914/tactile-review/review.md)
- [혼합 보존 최종 검수](/home/ddobagi/Hanium_Dreamup/work/object-recognition-v4-fixes-20260914/retention-review/final-review2/REVIEW.md)
- [추적 최종 검사](/home/ddobagi/Hanium_Dreamup/work/object-recognition-v4-fixes-20260914/tracking-review/fixed/junit.log)
- [중복 안내 최종 검수](/home/ddobagi/Hanium_Dreamup/work/object-recognition-v4-fixes-20260914/dedup-review/recheck/review.md)
- [APK 확인](/home/ddobagi/Hanium_Dreamup/work/object-recognition-v4-fixes-20260914/apk.json), [고정 마스크 비교](/home/ddobagi/Hanium_Dreamup/work/object-recognition-v4-fixes-20260914/fixed-input-comparison.json)

## 남은 범위

- 실제 휴대폰의 인식·ARCore·음성·진동 및 야외 오경고/누락은 사용자가 시험해야 한다. 이번 호스트 시험을 현장 성능으로 주장하지 않는다.
- 성공한 주 객체 동시 추적 3개 상한은 유지했다. 모든 검출 객체를 동시에 현재 위치/Depth로 추적하는 별도 개선은 이번 4건에 포함하지 않았다.
- 비동기 중복 억제는 실제 source 시점의 대응과 현재까지 연속 근거가 있는 경우에 적용한다. 근거 부족·변화·가림·다른 객체가 모호하면 각 경고를 보존한다.
- 다음 실기기 확인: 전방 점자블록 좌우 안내, 여러 객체 동시 이동, 가까운 미학습 물체와 다른 물체의 동시 등장, 같은 물체의 학습/FastSAM 반복 안내를 확인한다.
