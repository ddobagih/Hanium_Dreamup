# Two-Model Runtime Plan

- 작성일: 2026-05-22
- 작업 상태: YOLO26s custom 학습 진행 중
- 평가 상태: Stage 1/2 평가는 아직 완료 전
- 목적: custom tactile model과 COCO pretrained model을 분리 운용하여 점자블럭/지면 위험과 일반 객체 탐지를 함께 제공한다.

## 1. 확정 모델 구성

### 1.1 Custom tactile model

- 모델: YOLO26s custom
- 현재 학습 대상: 3-class
  - `normal_tactile_block`
  - `damaged_tactile_block`
  - `tactile_damage_area`
- 역할:
  - 발밑/지면 위험 탐지
  - 점자블럭 상태 탐지
  - 파손 점자블럭 및 파손 영역 탐지
- 주의:
  - 현재 custom 모델은 **'깨진 점자블럭만' 탐지하는 모델이 아니다.**
  - 정상 점자블럭(`normal_tactile_block`)도 학습/추론 대상에 포함한다.

### 1.2 COCO model

- 모델: YOLO26n COCO pretrained
- 학습 여부: 학습 없음
- 사용 방식: inference-only
- 역할:
  - 큰 일반 객체 탐지
  - 보행 안전 판단에 필요한 주변 객체 후보 제공
- 사용자 관심 COCO 클래스 예시:
  - `person`
  - `car`
  - `bus`
  - `truck`
  - `bicycle`
  - `motorcycle`
  - `traffic light`
  - `bench`
  - 기타 COCO pretrained 모델이 제공하는 클래스 중 서비스에서 필요하다고 판단한 클래스

## 2. 추론 설계 초안

### 2.1 역할 분리

- custom tactile model:
  - 지면/발밑 중심 위험
  - 점자블럭 존재 여부와 상태
  - 파손 점자블럭 및 파손 영역
- COCO model:
  - 사람, 차량, 오토바이, 자전거, 신호등, 벤치 등 큰 일반 객체
  - custom 모델이 학습하지 않는 주변 객체

### 2.2 병렬 추론

- 서버 GPU 여유가 있을 때 우선 검토한다.
- 같은 프레임을 두 모델에 동시에 입력한다.
- 모델별 후처리와 NMS는 각각 독립적으로 수행한다.
- 최종 단계에서 `source_model`, `class_name`, `bbox`, `confidence`를 유지한 채 결과를 병합한다.

### 2.3 순차 추론

- 모바일/CPU 또는 서버 부하가 큰 경우 우선 검토한다.
- 권장 순서 초안:
  1. custom tactile model 우선 실행
  2. COCO model 실행
  3. 결과 병합
- 이유:
  - 발밑/지면 위험과 점자블럭 상태는 보행 보조에서 우선순위가 높다.
  - 단, 실제 순서는 latency 측정 후 확정해야 한다.

## 3. 결과 병합 JSON 초안

```json
{
  "frame_id": "frame-000001",
  "captured_at": "2026-05-22T12:00:00+09:00",
  "runtime": {
    "mode": "parallel_or_sequential",
    "device": "server_gpu_or_mobile_cpu",
    "stage": "draft"
  },
  "models": [
    {
      "source_model": "custom_tactile_yolo26s",
      "role": "tactile_ground_risk",
      "training_status": "training_in_progress",
      "evaluation_status": "stage_1_2_not_completed"
    },
    {
      "source_model": "coco_yolo26n_pretrained",
      "role": "general_object_detection",
      "training_status": "no_training_inference_only",
      "evaluation_status": "pretrained_model_runtime_validation_required"
    }
  ],
  "detections": [
    {
      "source_model": "custom_tactile_yolo26s",
      "class_name": "normal_tactile_block",
      "bbox_xyxy": [120, 540, 420, 710],
      "confidence": 0.82,
      "threshold_used": 0.25,
      "semantic_group": "tactile_block_state"
    },
    {
      "source_model": "custom_tactile_yolo26s",
      "class_name": "tactile_damage_area",
      "bbox_xyxy": [260, 610, 350, 690],
      "confidence": 0.64,
      "threshold_used": 0.20,
      "semantic_group": "ground_risk"
    },
    {
      "source_model": "coco_yolo26n_pretrained",
      "class_name": "person",
      "bbox_xyxy": [520, 210, 710, 720],
      "confidence": 0.77,
      "threshold_used": 0.30,
      "semantic_group": "general_object"
    }
  ],
  "summary": {
    "has_tactile_block": true,
    "has_tactile_damage": true,
    "has_general_obstacle": true,
    "priority_messages": [
      "점자블럭 파손 영역 후보가 감지됨",
      "전방 사람 객체 후보가 감지됨"
    ]
  }
}
```

## 4. Threshold 정책 초안

현재 threshold는 확정값이 아니다. class별 threshold는 YOLO26s custom 학습 완료 후 Stage 1/2 평가 결과를 보고 확정한다.

아래 값은 **권장 초안이며 추정**이다.

| 모델 | 클래스/그룹 | threshold 초안 | 상태 |
| --- | --- | ---: | --- |
| YOLO26s custom | `normal_tactile_block` | 0.25 | 추정, 평가 후 확정 |
| YOLO26s custom | `damaged_tactile_block` | 0.25 | 추정, 평가 후 확정 |
| YOLO26s custom | `tactile_damage_area` | 0.20 | 추정, 평가 후 확정 |
| YOLO26n COCO pretrained | `person`, `car`, `bus`, `truck` | 0.30 | 추정, 런타임 검증 후 조정 |
| YOLO26n COCO pretrained | `bicycle`, `motorcycle`, `traffic light`, `bench` | 0.25 | 추정, 런타임 검증 후 조정 |

운영 원칙 초안:

- custom tactile class는 Stage 1/2 평가의 precision/recall, 오탐/미탐 사례를 보고 class별로 조정한다.
- `tactile_damage_area`는 작은 영역일 가능성이 있어 초기에는 낮은 threshold를 검토할 수 있으나, 오탐이 많으면 상향한다.
- COCO class는 서비스 관심 클래스만 필터링하고, 전체 COCO class를 그대로 사용자 경고에 연결하지 않는다.
- 최종 threshold는 실제 영상, 모바일/서버 런타임, 사용자 경고 품질을 함께 보고 확정한다.

## 5. 모바일/서버 연동 주의사항

- GPU/CPU 비용:
  - 두 모델을 매 프레임 실행하면 비용과 latency가 증가한다.
  - 서버 GPU 병렬 추론, 서버 순차 추론, 모바일 CPU/NPU 순차 추론을 분리해 측정해야 한다.
- 추론 순서:
  - 현재 초안은 custom tactile model 우선이다.
  - 실제 순서는 프레임 처리 시간, 배터리, 네트워크 왕복 시간, 경고 우선순위를 측정한 뒤 확정한다.
- bbox/NMS:
  - 모델 간 bbox가 겹쳐도 의미가 다를 수 있다.
  - custom의 `tactile_damage_area`와 COCO의 일반 객체 bbox는 같은 class 체계가 아니므로 단순 class merge를 하지 않는다.
  - 모델별 NMS를 먼저 적용하고, 병합 단계에서는 `source_model`과 `semantic_group`을 유지한다.
- 결과 병합:
  - 단순히 class name만 합치는 방식은 피한다.
  - downstream 판단 로직은 `source_model`, `semantic_group`, `confidence`, `bbox`, 프레임 위치를 함께 사용해야 한다.

## 6. 아직 확정 전인 항목

- YOLO26s custom Stage 1/2 평가 결과
- class별 최종 threshold
- 병렬/순차 중 기본 런타임 모드
- 모바일 단독 추론 여부와 서버 연동 방식
- 모델 간 결과 충돌 시 우선순위 규칙
