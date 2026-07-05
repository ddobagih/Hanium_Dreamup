# Model Acceptance Criteria Draft

> 작성 목적: YOLO26s 학습 완료 후 `이 모델을 앱에 사용할지`, `threshold 조정만 할지`, `추가 개선/재학습이 필요한지` 판단하기 위한 평가 기준 초안이다.
>
> 주의: 아래 숫자 기준은 **초안/가정**이다. 최종 합격선은 학습 결과 보고서의 실제 val/test 지표, 데이터 규모, 앱 위험도 기준을 확인한 뒤 확정한다.

## 1. 평가 대상 분리

| 모델 | 용도 | 평가 성격 | Acceptance 핵심 |
| --- | --- | --- | --- |
| Custom YOLO26s | 점자블럭 3-class 탐지 | 학습 결과 품질 평가 | mAP, recall, precision, class별 성능, 앱 샘플 리뷰 |
| COCO YOLO26n | 일반 객체 inference-only | 통합/동작 확인 | smoke test, latency, allowlist, custom 결과와 source 분리 |

Custom YOLO26s class:

- `normal_tactile_block`
- `damaged_tactile_block`
- `tactile_damage_area`

## 2. Custom Tactile Model 평가 기준

### Stage 1: 정량 지표 평가

학습 완료 후 val/test 결과에서 아래 항목을 확인한다.

| 항목 | 확인 내용 | 해석 기준 |
| --- | --- | --- |
| mAP50 | IoU 0.50 기준 전체 탐지 성능 | 모델의 기본 탐지 가능성을 보는 1차 지표 |
| mAP50-95 | IoU 0.50~0.95 평균 성능 | bbox 정밀도까지 포함한 stricter 지표 |
| Recall | 실제 객체를 놓치지 않는 정도 | 파손 관련 class는 false negative 위험 때문에 특히 중요 |
| Precision | 탐지 결과가 실제 객체일 가능성 | 과도한 경고/오탐으로 앱 신뢰도를 낮추지 않는지 확인 |
| Class별 성능 | 3개 class 각각의 AP/precision/recall | 전체 평균이 좋아도 특정 class가 약하면 별도 조치 필요 |

`tactile_damage_area`는 상대적으로 작은 bbox일 가능성이 높다. 따라서 mAP50-95가 낮게 나와도 곧바로 실패로 단정하지 말고, 다음을 함께 확인한다.

- mAP50과 recall이 사용 가능한 수준인지
- 실제 앱 화면에서 파손 위치 안내에 충분한지
- label 기준이 일관적인지
- 작은 bbox 특성상 IoU가 조금만 흔들려도 mAP50-95가 크게 낮아지는지

### Stage 2: 앱 관점 샘플 리뷰

정량 지표가 기준에 근접하거나 통과하더라도, 실제 앱 사용 흐름에서 샘플 리뷰를 수행한다.

| Class | 앱에서의 의미 | 중점 확인 |
| --- | --- | --- |
| `normal_tactile_block` | 정상 점자블럭 존재 확인 | 정상 블럭을 안정적으로 감지하는지, 파손 블럭을 정상으로 오분류하지 않는지 |
| `damaged_tactile_block` | 파손된 점자블럭 객체 감지 | 파손 블럭을 놓치지 않는지, 정상 블럭을 과도하게 파손으로 경고하지 않는지 |
| `tactile_damage_area` | 파손 영역 위치 안내 | 파손 영역 bbox가 앱 사용자에게 의미 있는 위치/크기로 표시되는지 |

샘플 리뷰 기준:

- False Positive:
  - 정상 점자블럭을 `damaged_tactile_block`으로 잘못 탐지하는 사례 수
  - 점자블럭이 아닌 바닥 패턴/그림자/타일을 tactile class로 잘못 탐지하는 사례 수
  - `tactile_damage_area`가 실제 파손이 아닌 얼룩, 조명, 그림자에 반복 반응하는지
- False Negative:
  - 실제 파손 점자블럭을 놓치는 사례 수
  - 큰 파손은 잡지만 작은 파손을 반복적으로 놓치는지
  - `damaged_tactile_block`은 잡지만 `tactile_damage_area`를 전혀 표시하지 못하는지
- 분류 혼동:
  - `normal_tactile_block`과 `damaged_tactile_block`이 서로 자주 바뀌는지
  - 파손 영역이 객체 전체 bbox처럼 크게 잡히는지
- 리뷰 방법:
  - val 샘플에서 threshold 후보를 비교한다.
  - test 샘플은 최종 threshold 고정 후 1회 확인한다.
  - 애매한 사례는 label 품질 문제인지 모델 문제인지 따로 표시한다.

## 3. Threshold Tuning 절차

1. Val 결과로 초안 threshold를 정한다.
   - confidence threshold 후보를 비교한다.
   - 필요하면 class별 threshold를 검토한다.
   - 파손 class는 recall 저하가 큰 후보를 우선 배제한다.
2. Val 샘플에서 false positive/false negative를 리뷰한다.
   - 오탐이 많으면 confidence threshold 상향을 검토한다.
   - 미탐이 많으면 confidence threshold 하향 또는 데이터/label 개선을 검토한다.
   - NMS/IoU 설정 변경이 필요한지 확인하되, 변경 사유를 기록한다.
3. Threshold 후보를 하나로 고정한다.
   - 최종 후보의 confidence, IoU/NMS, class별 threshold 여부를 기록한다.
4. Test set에서 최종 확인한다.
   - test 결과는 최종 검증용으로만 사용한다.
   - test 결과를 보고 반복적으로 threshold를 재조정하지 않는다.
5. Test에서 실패하면 원인을 분리한다.
   - threshold 문제인지
   - label 품질 문제인지
   - 데이터 다양성 부족인지
   - 모델 capacity/학습 설정 문제인지

## 4. Custom YOLO26s 판정표 초안

아래 숫자는 **초안/가정**이며, 최종 기준은 학습 결과 보고서 이후 확정한다.

| 판정 | 정량 기준 초안 | 정성 기준 | 후속 조치 |
| --- | --- | --- | --- |
| 합격 | 전체 mAP50 ≥ 0.70, 전체 mAP50-95 ≥ 0.40, precision ≥ 0.70, recall ≥ 0.70 | 앱 샘플에서 치명적 미탐이 드물고, 파손/정상 혼동이 사용성을 크게 해치지 않음 | threshold 고정 후 앱 통합 후보로 사용 |
| 보류 | 전체 mAP50 0.55~0.70 또는 precision/recall 중 하나가 0.55~0.70 | 특정 class 약점이 있거나 오탐/미탐 패턴이 threshold로 일부 개선 가능 | threshold 재검토, label 샘플 리뷰, 소규모 데이터 보강 검토 |
| 재학습 | 전체 mAP50 < 0.55 또는 주요 class recall < 0.50 | 파손 점자블럭을 반복적으로 놓치거나 정상/파손 혼동이 큼 | 데이터/label/학습 설정 개선 후 재학습 |

Class별 보조 기준 초안:

| Class | 합격 후보 기준 초안 | 주의 사항 |
| --- | --- | --- |
| `normal_tactile_block` | AP50 ≥ 0.75, recall ≥ 0.70 | 정상 블럭을 안정적으로 잡되, 파손 블럭을 정상으로 덮어쓰면 안 됨 |
| `damaged_tactile_block` | AP50 ≥ 0.65, recall ≥ 0.70 | 안전/안내 관점에서 false negative를 특히 중요하게 본다 |
| `tactile_damage_area` | AP50 ≥ 0.45, recall ≥ 0.55 | 작은 bbox 특성상 mAP50-95는 보조 지표로 해석하고 샘플 위치 품질을 함께 본다 |

## 5. COCO YOLO26n Acceptance 기준

COCO YOLO26n은 학습 없이 사용하는 inference-only 모델이므로 custom tactile 모델과 같은 mAP 기준으로 평가하지 않는다. Acceptance는 통합 동작과 앱 안전장치 중심으로 본다.

| 항목 | 합격 기준 | 보류 기준 |
| --- | --- | --- |
| Smoke test | 샘플 이미지/프레임에서 inference가 에러 없이 완료됨 | 로딩 실패, 빈 응답 형식 오류, runtime exception 발생 |
| Allowlist | 설정된 allowlist class만 반환하고, allowlist 밖 class는 앱 결과에서 제외됨 | allowlist 밖 class가 사용자 결과에 노출됨 |
| Latency | 목표 실행 환경에서 사전에 합의한 latency budget 이내 | p95 latency가 budget을 반복 초과하거나 앱 UX를 저해함 |
| 큰 객체 오탐 리뷰 | 사람/차량/기둥 등 큰 객체가 tactile 결과처럼 해석되지 않음 | 큰 객체 오탐이 custom 결과와 섞여 사용자 판단을 방해함 |
| Source 분리 | 결과에 custom/COCO source가 구분되어 후처리와 UI에서 분리 가능 | COCO 결과와 custom tactile 결과가 같은 class 체계처럼 섞임 |

COCO 기준 메모:

- allowlist class 목록은 별도 설정값을 따른다. 이 문서에서는 임의로 정하지 않는다.
- latency 숫자는 배포/시연 장치가 확정된 뒤 정한다.
- COCO 결과는 보조 정보로 취급하고, tactile damage 판정은 custom YOLO26s 결과와 분리한다.
- custom class와 COCO class가 동일한 후처리 경로에서 섞이면 false positive 원인 분석이 어려워지므로 `source=custom` / `source=coco` 같은 구분이 필요하다.

## 6. 최종 결과 보고서에 포함할 항목

- 학습 run ID 또는 weights 경로
- dataset split 기준과 이미지 수
- val/test mAP50, mAP50-95, precision, recall
- class별 AP/precision/recall
- 최종 confidence threshold, IoU/NMS 설정
- false positive/false negative 대표 샘플
- 합격/보류/재학습 최종 판정과 근거
- COCO smoke/latency/allowlist 확인 결과
