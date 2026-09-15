# 미학습 후보 선별 고정 입력 비교

이 평가는 `1509a19`의 기존 정책과 현재 제품 Kotlin `UnknownWalkingObstaclePolicy.select`를 같은 입력으로 호출한다. 정책을 Python으로 다시 구현하지 않았다. 새 자료 다운로드, 모델 추론 재실행, APK 설치, 실기기·현장 시험은 포함하지 않는다.

**합성 위험 보존 10/10과 미확인 후보 보존 4/4를 유지하면서, 명시적으로 무해한 조각 3개 중 선별 FP는 2개에서 1개로 줄었다. 실제 저장 마스크에서는 기존·새 정책의 선택 결과와 정답 재현율이 같았다.** 실제 사진에는 신뢰할 물리 크기 정답을 공급하지 않았으므로 작은 물체 제외 기능의 실제 보행 효과를 입증한 결과는 아니다.

![고정 입력의 합성 계약, 저장 마스크 재현율, host 선택 호출 시간 비교](/home/ddobagi/Hanium_Dreamup/work/unknown-selection-evaluation-20260913/paired-comparison.png)

## 실제 결과

| 합성 정답 범위 | 기존 정책 | 새 정책 |
| --- | ---: | ---: |
| 위험으로 선언한 10개 중 선별 FN | 0 | 0 |
| 무해로 선언한 3개 중 선별 FP | 2 | 1 |
| 미확인 4개 중 잘못 제거 | 0 | 0 |
| 거리 미확인 음성 후보 금지·전방 마스크 구멍 대조 | 통과 | 통과 |

5,521개 입력 중 선택 결과 또는 사유가 달라진 것은 `verified_compact_nuisance_250cm` 1개다. 안정적으로 반복되고 전체가 보인다고 선언한 2.5m 거리의 1cm 조각이 새 정책에서 제외되었다. 1.8m 조각은 보수적으로 보존되어 FP 1개로 남았다. 합성 표본에서 50% 감소했다는 수치를 실제 보행 오탐 감소율로 확대하지 않는다.

| 실제 정답 범위 | 저장 FastSAM 마스크 전체 | 기존 정책 선별 후 | 새 정책 선별 후 |
| --- | ---: | ---: | ---: |
| COCO primary 밖 mask R50 | 410/694 (59.08%) | 252/694 (36.31%) | 252/694 (36.31%) |
| 그중 작은 객체 mask R50 | 158/323 (48.92%) | 79/323 (24.46%) | 79/323 (24.46%) |
| COCO known+primary 밖 mask R50 | 595/945 (62.96%) | 366/945 (38.73%) | 366/945 (38.73%) |
| COCO 남은 후보 | 5,270 | 2,708 | 2,708 |
| RGB-D instance mask R50 | 48/53 (90.57%) | 45/53 (84.91%) | 45/53 (84.91%) |
| RGB-D 남은 후보 | 233 | 167 | 167 |

이 표의 선별 전후 감소는 전체 사진 정답에 기존 전방 영역 기준을 적용한 결과다. 화면 밖·측면을 포함한 전체 정답 분모를 유지했다. 새 작은 영역 제외로 실제 정답 재현율이 더 낮아지지는 않았지만, 이 입력에서는 metric extent가 없어 해당 제외 조건 자체가 활성화되지 않는다. 전체 이미지 recall 수치를 실제 전방 위험 recall이라고 부르지 않는다. 미매칭 후보는 기존·새 정책 모두 COCO 2,342개, RGB-D 122개이며 불완전한 주석 때문에 이를 FP로 계산하지 않았다.

| Host JVM 선택 호출 시간 | 기존 정책 | 새 정책 |
| --- | ---: | ---: |
| 사례별 중앙값의 중앙값 | 0.170μs | 0.171μs |
| 사례별 중앙값의 P95 | 1.202μs | 1.203μs |
| 사례별 P95의 P95 | 1.273μs | 1.343μs |

전체 5,521개 사례에서 각 정책을 워밍업 5회 후 25회 호출했다. 이 호출 시간은 새 metric extent의 깊이 표본 수집·시간적 검증을 포함하지 않으며 앱 FPS, 배터리 또는 휴대폰 지연을 말해주지 않는다.

## 입력과 정답 범위

| 자료 | 고정 입력 | 가능한 정답 평가 | 없는 정답 |
| --- | ---: | --- | --- |
| 독립 합성 시나리오 | 18개 | 선언한 선별 계약의 FN·FP·미확인 후보 보존 | 실제 보행 위험, 모델 인식률 |
| 기존 COCO 80장 FastSAM 768 출력 | 5,270개 마스크 | 945개 비crowd 객체의 마스크 R50, 그중 현행 primary 밖 694개 | 전체 장애물 주석, 물리 크기·깊이·보행 위험 |
| 기존 UOIS RGB-D 4장 FastSAM 768 출력 | 233개 마스크 | 53개 가시 인스턴스의 마스크 R50 | semantic class, 독립 실측 거리, 보행 위험 |

현행 21클래스의 클래스·allowlist를 현재 설정 파일에서 다시 읽었다. COCO의 클래스 이름과 직접 대응하는 것은 `person, bicycle, car, motorcycle, bus, truck, traffic light` 7개다. `passenger_car`만 COCO `car`로 명시적으로 대응했다. 결과는 known 251개, primary 밖 694개, crowd 13개이며 과거 13클래스 기준 수와 같지만 과거 분할을 그대로 가정하지 않았다. 추가된 점자블록·공사시설·전봇대 등의 클래스는 COCO 이름과 유추 대응시키지 않았다.

원본 자료는 `/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth/` 아래에 있다. `fixtures/coco_annotations.json`의 원래 주석을 모두 보존했고 `candidates/outputs/fastsam_768/predictions.jsonl`, `candidates/outputs/rgbd_fastsam_768/predictions.jsonl`의 저장 마스크를 사용했다. 각 사진 SHA-256이 예측 캐시와 일치하는지 확인했다. 원본 자료는 수정하지 않았다.

RGB-D 인스턴스 수는 OCID 두 장 10·20개, OSD 두 장 7·16개다. OSD의 ID 1도 객체다. 별도 GraspNet 한 장은 인스턴스 정답이 없어 재현율 집계에서 제외했다. UOIS의 예제별 내참수와 실제 단위 변환의 독립 확인이 부족하므로 실측 물체 크기를 계산하거나 정답으로 만들지 않았다.

## 비교 방법

- 18개 합성 정답은 결과를 보고 바꾸지 않는다. 가는 기둥, 낮은 걸림 막대, 멀리서 접근하는 작은 투영, 즉시 가까운 작은 객체, 작은 부분 마스크, 중첩 상자 안의 별개 물체를 포함한다.
- `keep_collision_relevant`는 선별 단계의 보존, `suppress_verified_nuisance`는 시나리오가 완전하고 해가 없다고 명시한 작은 조각의 제거, `retain_uncertain`은 제거 근거가 부족한 후보의 보존을 뜻한다. 실제 제품 입력에서 물체 전체 경계를 안다는 뜻은 아니다.
- 1.8m의 무해한 작은 조각도 독립 nuisance 정답에 남긴다. 정책이 가까운 작은 객체를 보수적으로 보존하면 해당 표본의 FP로 그대로 기록한다. 거리 문턱에 맞추어 정답을 바꾸지 않는다.
- 실제 사진의 모든 후보는 `UNKNOWN` 깊이와 metric extent 없음으로 재생한다. 반복 관측 준비 상태와 회전 0은 고정한 재생 가정이다. 정적 사진에서 실제 추적 이력·카메라 자세·실측 크기를 얻었다고 주장하지 않는다.
- 마스크 IoU 0.5 이상, confidence순 일대일 매칭으로 모든 비crowd 정답을 평가한다. 화면 전방 띠 밖의 정답도 전체 사진 분모에 유지하므로 선별 전후 감소에는 의도된 전방 영역 제외가 포함된다. 이 감소를 보행 위험 FN으로 해석하지 않는다.
- 미매칭 마스크 개수는 기록하되 FP로 부르지 않는다. COCO와 UOIS의 배경에는 모든 물체·표면이 완전하게 주석되어 있지 않다. crowd는 객체 정답 분모에서 제외한다.
- 중첩·겹침 시나리오는 각 후보의 개별 보존을 확인한다. 단일 후보 `select` 호출로 화면 중복 제거 또는 음성 병합 성공을 주장하지 않는다.

## 실행과 재현

준비된 입력은 `/home/ddobagi/Hanium_Dreamup/work/unknown-selection-evaluation-20260913/paired-input.json`이며 5,521개 사례다. SHA-256은 `5aeedc2f3eda9222cdee492ac4f9f7abf99424f9b389721144749ce9d10722c3`이다. 합성 manifest, 현재 클래스 설정, 주석, 마스크 캐시, 사진, RGB-D GT의 입력 해시를 함께 보관한다.

```bash
/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth/fixtures/.venv/bin/python \
  scripts/evaluate_unknown_selection_20260913.py prepare \
  --output work/unknown-selection-evaluation-20260913/paired-input.json
```

`UnknownSelectionFixedDataEvaluationTest` 실행 JVM에는 다음 system property를 전달한다. Gradle 실행 JVM의 `-D`만으로 test worker에 전달된다고 가정하지 않는다.

```text
walksafe.unknownSelection.input=/home/ddobagi/Hanium_Dreamup/work/unknown-selection-evaluation-20260913/paired-input.json
walksafe.unknownSelection.output=/home/ddobagi/Hanium_Dreamup/work/unknown-selection-evaluation-20260913/paired-replay.json
walksafe.unknownSelection.repetitions=25
```

기준선은 `BaselineUnknownWalkingObstaclePolicySnapshot.kt`다. `1509a19` 원문에서 공유 결과 클래스 선언을 제거하고 객체 이름만 바꿨으며 정책 본문이 그 변환과 정확히 같은지 검사했다. 한 JVM에서 같은 mask와 depth 객체를 두 정책에 전달하며 사례마다 실행 순서를 바꾼다. 각 사례의 워밍업 5회를 제외하고 25회 호출한다.

```bash
/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth/fixtures/.venv/bin/python \
  scripts/evaluate_unknown_selection_20260913.py score \
  --input work/unknown-selection-evaluation-20260913/paired-input.json \
  --replay work/unknown-selection-evaluation-20260913/paired-replay.json \
  --output work/unknown-selection-evaluation-20260913/paired-score.json

/home/ddobagi/Hanium_Dreamup/.venv-backend/bin/python \
  scripts/evaluate_unknown_selection_20260913.py plot \
  --score work/unknown-selection-evaluation-20260913/paired-score.json \
  --output work/unknown-selection-evaluation-20260913/paired-comparison.png
```

지연은 host JVM의 `select`와 reflection 호출만 포함한다. 마스크 decode, 모델, 깊이 산출, 카메라, 추적, 화면, 음성, 휴대폰 실행 시간은 제외한다. 사례별 호출 중앙값의 중앙값과 P95, 사례별 P95의 P95를 구분하여 기록한다.

## 검증 상태

입력 준비와 사진 해시 검증을 완료했다. Python 구문, 시나리오 ID 중복, 기준선 정책 본문 보존을 검사했다. 집계 코드는 별도 작은 마스크로 일대일 매칭, 중복 예측, crowd 분모 제외, 빈 선택, known 클래스 분할, FN·FP·미확인 후보 분리를 확인했다. Root가 실행한 실제 Kotlin 재생 5,521행을 읽어 입력 SHA-256과 사례 ID 전체 일치를 확인한 뒤 집계했다. 위험·미확인 후보 보존 및 명시한 warning/control 계약을 JUnit에서 검증했고, 무해 표본의 FP는 남은 그대로 보고했다. 생성된 도표를 열어 숫자와 잘림 여부를 확인했다.

- 실행 로그: `/home/ddobagi/Hanium_Dreamup/work/unknown-selection-20260913/gradle.log`
- Kotlin 재생: `/home/ddobagi/Hanium_Dreamup/work/unknown-selection-evaluation-20260913/paired-replay.json`
- 집계 결과: `/home/ddobagi/Hanium_Dreamup/work/unknown-selection-evaluation-20260913/paired-score.json`
- JUnit 결과: `/home/ddobagi/Hanium_Dreamup/apps/android/app/build/test-results/testDebugUnitTest/TEST-kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownSelectionFixedDataEvaluationTest.xml`

## 판정 한계

이 시험은 새 정책이 명시한 사례를 어떻게 선별하는지와 저장 모델 마스크를 얼마나 보존하는지 보여준다. 실제 물체의 누락 없는 주석, 물리 크기, 발에 걸리는 높이, 사용자 경로, 충돌 시각 정답이 없으므로 실세계 위험 재현율이나 전체 경고 정확도를 계산할 수 없다. `unknown`은 현재 primary 클래스 밖이라는 뜻이며 FastSAM이 학습하지 않았던 객체라는 뜻이 아니다.
