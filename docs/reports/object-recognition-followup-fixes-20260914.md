# 객체 인식 추가 검수 8건 개선 계획과 결과

## 범위

직전 독립 검수에서 확인한 8건의 수정·회귀·독립 재검토·debug APK 빌드를 진행한다. 기존 모델 가중치와 768 입력, 사용자 데이터, 안내 시작 우회, 기존 outdoor origin은 보존한다. 설치와 실기기·야외 검증은 별도로 구분한다.

## 단계별 계획

| 단계 | 작업 | 인수 조건 |
| --- | --- | --- |
| 1 경고·거리 확인 | WARNING 완료 후 STOP 승격, 고빈도 Depth의 점프 확인 | 긴급 승격 즉시 후보 전달, 동급 반복 제한·rollback 유지; 50ms 주기에서도 지속 근접을 확인하며 단발 jump·불연속 pose 거부 |
| 2 추적 ID | 같은 클래스 일부 교체 시 명확한 부분 대응 유지 | 4명 중 3명 교대 추적에서 고정 두 명의 ID·안정화·경고 유지; 중첩/교차 모호성·오래된 geometry 제한 유지 |
| 3 미학습 깊이 | 혼합층 비율 조기 반환·분리 성분·표본 부족 후보 선점 수정 | 33→36%·50:50 및 분리 1m/8m에서 유효 근접 관측 보존; 1m24표본 표시와 2m40표본 경고를 실제 각 영역으로 분리 |
| 4 입력·복구 | 새 촬영 프레임을 보조 모델에 공급, 실패한 서비스 재생성 | 기존 primary500+aux400ms 재현에서 primary 기다림이 보조 캡처 나이에 포함되지 않음; 명시 재연결은 해제 확인 뒤 새 epoch 1개만 생성 |
| 5 통합 검증 | 원 실패 입력·관련 회귀·고정5,521입력·독립 재검토·APK | 원 결함 해결과 새 회귀 없음 확인, 검증과 실제 현장 성능 구분 |

## 분담과 경계

- 경고 담당: MetricDistanceContinuityPolicy, WalkSafeFeedbackPolicy 및 해당 tests.
- 추적 담당: ObjectTracker와 해당 tests. 거리 이력의 다른 담당 변경을 보존한다.
- 미학습 담당: depth/unknown, UnknownObjectFeedbackPolicy와 해당 tests. 고정 비교 자료·baseline은 변경하지 않는다.
- 카메라 담당: CameraTestActivity, CameraUnknownObservation, CameraTestFeedbackCoordinator 및 전용 복구/helper/tests.
- Root: Main의 fresh 보조 캡처·드롭 피드백, 통합·Gradle·기록. 검토는 구현자와 다른 인스턴스가 수행한다.

## 변경 전 재현

- WARNING 완료4000ms 뒤 STOP4100ms는6500ms까지 대기.
- Full4→1m,50ms 간격은30출력을거부해1500ms지연;100ms간격은100ms만에확인.
- 4person중3개선택교대,14배치98프레임고정대상까지ID재생성/경고0.
- 연결1m비중32.8%경고→36.2%와50:50미확인/경고0.
- 분리1m180/8m1404표본은각각KNOWN이어도근접경고0.
- 1m24표본선점으로2m40표본경고도0.
- 같은원캡처의primary500+aux400ms는ACCEPTED후3회모두stale_after_decode.
- FastSAM invoke오류후released=true이어도기존service는CLOSED,재연결동작이교체하지않음.

원 감사의 driver·원출력을 `work/object-recognition-followup-fixes-20260914/baseline/`에 보존했다. 숫자는 합성 입력/지연/오류 주입의 재현 결과이며 기기 발생률이나 실측 추론 시간은 아니다.

## 수정과 같은 입력의 결과

아래 시간과 횟수는 실제 제품 클래스에 동일 합성 입력·시계를 주입한 결과다. 휴대폰 모델 추론 시간이나 실제 스피커 발화 시간을 뜻하지 않는다.

| 번호 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 1 경고 승격 | WARNING 완료 4,000ms 뒤 STOP 4,100ms가 6,500ms까지 차단 | STOP 4,100ms에 즉시 후보 전달. WARNING/STOP 승격만 낮은 완료 단계의 제한을 우회하며 동급 반복과 전달 실패 rollback 유지 |
| 2 연속 Depth | Full 4→1m, 50ms 간격에서 확인 기준 시각이 계속 갱신되어 1,500ms 지연 | 최초 확인 후보 시각을 보존해 100ms에 확인. 거부 표본 30→2, 단발 이상값과 자세 불연속 거부 유지 |
| 3 부분 추적 | 4명 중 3명 선택을 교대하면 계속 보이는 사람도 ID 초기화·경고 0 | 높은 IoU와 겹침 경쟁 없음이 확인된 부분 대응 ID 보존. 98프레임에서 고정 ID 1·2, 확인 횟수 14, 안정 경고 후보 0→168, 피드백 선택 0→1. 고정 대조군 252후보/1회 유지 |
| 4 혼합 비율 | 1m 지지층이 32.8→36.2%로 커지거나 50:50이면 전체 모호성 때문에 근접 경고 누락 | 전체 거리 미확정 유지, 별도로 충분한 1m 지지 영역은 보존·경고 |
| 5 분리 성분 | 8m 1,404표본과 분리된 1m 180표본이 모두 확인돼도 가까운 성분 경고 누락 | 실제 1m 연결 성분의 지지 픽셀·범위로 근접 후보 생성 |
| 6 적격 영역 | 1m 24표본이 먼저 선택되어 2m 40표본 경고도 차단 | 1m는 표시 유지, Raw 30표본을 충족하는 2m는 별도 실제 영역으로 경고. Full 50표본 기준 유지 |
| 7 촬영 신선도 | 주 모델 500ms를 기다린 같은 사진에 보조 400ms를 더해 800ms 한도 초과 | 주 모델 완료 사진 재사용 제거. 새 카메라 사진을 보조 입력으로 획득해 같은 가상 지연에서 결과 전달. 새 사진 자체가 801ms를 넘으면 여전히 폐기 |
| 8 오류 복구 | FastSAM invoke 실패 후 종료된 서비스를 재사용해 계속 CLOSED | 사용자가 재연결하면 기존 native 해제 확인 뒤 새 epoch로 한 번 생성. 최대 native 1개, 새 프레임 ACCEPTED. 해제 불명확·pause·destroy·늦은 callback 차단 |

### 표시와 경고의 구분

- 가장 가까운 관측 영역과 경고 가능한 영역은 서로 다를 수 있다. 1m의 표본이 부족하고 2m의 표본이 충분하면 1m 관측 표시를 보존하면서 2m 실제 bbox에 경고를 붙인다.
- 객체 전체의 거리·ID·속도가 모호한 것을 확정값으로 바꾸지 않는다. 현재 근접 영역의 존재만 독립 근거로 전달한다.
- Raw 정보가 충분하거나 모호성·충돌 근거를 가진 경우 Full이 덮어쓰지 않는다. Raw 지지가 없는 경우에만 Full의 독립 지지 영역도 fallback에 사용한다.
- Camera UNKNOWN 오류 정리는 해당 소스의 미전달 후보와 예약만 취소한다. PRIMARY 경고와 실제 완료 이력은 보존한다.

## 통합 검증

- 1차 Gradle: 66 suites, **728 tests, 실패·오류·skip 0**, `:app:assembleDebug` 성공. Main/Camera 전체 Java·Kotlin 컴파일 포함.
- 마지막 표시 순서 3파일 수정 후 영향받는 Camera **31 tests 통과**, APK 재빌드 성공. 최종 각 suite 결과를 합치면 **66 suites / 서로 다른 730 tests 통과**다. 730개를 한 번의 실행으로 돌렸다는 의미는 아니다. 수정하지 않은 범위를 반복 실행하지 않았고, 최종 소스 대조에서 추가 변동은 없었다.
- 기존 고정 입력 **5,521개**, 입력 SHA256 `5aeedc2f3eda9222cdee492ac4f9f7abf99424f9b389721144749ce9d10722c3`. 직전 9월 14일 개선 결과와 표시·경고 후보·사유 결정 차이 **0개**.
- 고정 합성 위험 10/10, 미확인 4/4 유지. 무해 3장면 중 오인식 후보 1개는 남아 있다. COCO 미학습 매칭 252/694, RGBD 45/53 유지.
- 고정 입력은 캐시된 모델 마스크의 후처리 재실행이다. 모델을 다시 실행한 결과나 실제 보행 위험 재현율이 아니다. 독립 깊이·추적·복구 문제는 별도의 원 실패 입력 및 새 회귀로 확인했다.
- `paired-score.json`의 baseline은 HEAD `1509a19`이다. 이번 변경과 직전 완료본 차이는 별도 `paired-decision-comparison.json`으로 확인했다.

## 독립 검토

- 위험: 현재 거리·피드백 소스 재컴파일 후 원 하네스 및 49개 회귀 통과. WARNING→STOP와 선택적 취소 API 추가 지적 없음.
- 추적: 변경된 거리 정책을 함께 컴파일해 원 교대/고정 하네스와 신규 부분 대응 5개 통과. 추가 지적 없음.
- 미학습: 원 7시나리오, 별도 Raw/Full 경계 9개, 신규 회귀 7개 독립 실행 통과. Raw 29/30·Full 49/50 및 Raw 우선 경계 확인.
- Main: 새 입력의 품질 admission 누락을 독립 검토에서 발견해 같은 사진의 밝기·가림·움직임·장착 관측으로 기존 정책을 호출하도록 수정. 이미지 해제·session/geometry 경계·discard 연결 검토, 실제 서비스 신규 신선도 3개 직접 통과.
- Camera: 독립 검토에서 추가 bbox가 높은 위험도 표시를 밀어내는 문제를 발견해 전체 후보를 위험도→TTC→거리로 정렬한 뒤 6개 상한을 적용했다. 같은 입력에서 STOP 0→6개 표시로 독립 재확인. 1m 표시와 별도 2m 경고의 실제 거리·bbox도 유지했다. 복구·큐 시험은 리뷰어가 소스 및 담당자의 직접 실행 결과를 대조했다.

## 진행 상태와 한계

승인한 8건 구현·회귀·독립 재검토·통합·APK 빌드를 완료했다. 검토 중 추가로 발견한 입력 품질 검사와 표시 우선순위 문제도 수정하고 재확인했다. 실제 휴대폰 설치·한국어 발화·현장 인식률·FPS·발열은 이번 범위에서 실행하지 않았다. 모델 가중치와 768 입력을 유지하며 실측 처리 속도가 개선됐다고 판단하지 않는다.

동급 위험 후보 4개에 표시·전달 예산 3개를 적용하는 기존 반복 정책은 이번 8건에 포함하지 않았으며 변경하지 않았다. 네 번째 후보의 장시간 배제 가능성은 별도의 우선순위 정책 검토 대상으로 남는다.

## APK

- 경로: `/home/ddobagi/Hanium_Dreamup/apps/android/app/build/outputs/apk/debug/app-debug.apk`
- SHA256: `ab68ede0a244641fec2f009951a56e4ad1f79afea32f7ac88f74ca6c6461dd49`
- 크기: 454,183,607 bytes, 빌드 시각: `2026-09-14T01:32:19.225132+09:00`.
- 기존 outdoor gateway: `https://yield-bacterial-corners-necklace.trycloudflare.com`. 생성된 BuildConfig에서 유지 확인. 이번 작업에서 서버 연결 상태를 시험하지 않았다.
- 이번 설치·push 없음. 다음 실행 단계는 새 APK를 설치한 뒤 실제 카메라·음성을 확인하는 것이다.

## 근거

- `work/object-recognition-followup-fixes-20260914/baseline/`: 변경 전 원 재현.
- 같은 폴더의 `implementation-*`, `review-*`: 구현 및 다른 인스턴스의 직접 실행 명령·원출력·검토 소스.
- `gradle-command.json`, `gradle-integration.log`, `integration-tests.json`, `integration-test-xml/`: 1차 통합 실행.
- `gradle-camera-final-command.json`, `gradle-camera-final.log`, `camera-final-tests.json`, `latest-tests.json`: 최종 영향 범위 재검증.
- `verified-source-sha256.json`, `apk.json`: 검증 소스와 빌드 산출물 식별.
- `paired-replay.json`, `paired-score.json`, `paired-decision-comparison.json`: 동일 고정 자료 비교.

