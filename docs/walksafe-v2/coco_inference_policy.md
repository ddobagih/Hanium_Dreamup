# YOLO26n COCO Pretrained Inference-only 운영 정책

## 목적

YOLO26n COCO pretrained 모델은 **큰 일반 객체를 인식하는 보조 모델**로 사용한다. 사용자 주변의 사람, 차량, 교통 시설물, 벤치처럼 COCO에 포함된 일반 객체를 빠르게 감지해 안내 신호로 제공하는 것이 목적이다.

이 모델은 프로젝트의 custom tactile 모델과 역할을 분리한다.

- **COCO YOLO26n**: 사람, 차량, 자전거, 신호등, 벤치 등 일반 객체 감지
- **custom tactile 모델**: 프로젝트 데이터로 정의한 촉각/점자/특수 표식 등 도메인 객체 감지

두 모델은 학습 목적, 클래스 의미, 출력 해석이 다르므로 하나의 모델처럼 취급하지 않는다.

## 학습 정책

YOLO26n COCO pretrained 모델은 **학습 없이 inference-only로 사용**한다.

- COCO pretrained weight를 그대로 사용한다.
- 프로젝트 데이터로 fine-tune하지 않는다.
- custom tactile 데이터셋을 COCO 모델 학습에 섞지 않는다.
- 성능 튜닝은 우선 threshold, allowlist, 알림 정책 조정으로 제한한다.

## COCO allowlist 초안

아래 클래스만 1차 allowlist로 사용한다. threshold와 알림 우선순위는 초안이며, 실제 smoke test 이후 조정이 필요하다.

| class | 위험/안내 의미 | 초안 threshold | 사용자 알림 우선순위 |
|---|---|---:|---|
| person | 보행자/동행자/전방 사람 존재 안내 | 0.45 | 높음 |
| car | 차량 접근 또는 주변 차도/주차 차량 안내 | 0.45 | 높음 |
| bus | 대형 차량 접근 및 정류장 주변 안내 | 0.50 | 높음 |
| truck | 대형 차량 접근, 시야 차단 가능성 안내 | 0.50 | 높음 |
| bicycle | 자전거 접근 또는 통행 경로 공유 가능성 안내 | 0.40 | 중간 |
| motorcycle | 이륜차 접근, 빠른 이동체 가능성 안내 | 0.45 | 높음 |
| traffic light | 횡단/교차로 주변 신호 시설 존재 안내 | 0.35 | 중간 |
| bench | 휴식 시설 또는 보행 공간 내 고정 물체 안내 | 0.40 | 낮음 |

운영 시에는 allowlist 외 COCO 클래스는 기본적으로 사용자 알림 대상에서 제외한다. 필요하면 별도 정책 문서에서 클래스 추가 사유와 알림 우선순위를 정한 뒤 확장한다.

## custom tactile 결과와 병합 시 주의사항

COCO 모델과 custom tactile 모델의 class 의미는 서로 다르다. 따라서 두 모델의 결과를 병합할 때는 **모델 간 NMS를 수행하지 않는다**.

권장 방식:

- 각 detection에 `source_model`을 명시한다.
  - 예: `source_model: "coco_yolo26n"`
  - 예: `source_model: "custom_tactile"`
- 같은 화면 위치에 bbox가 겹쳐도 서로 다른 의미의 객체일 수 있으므로 cross-model suppression을 하지 않는다.
- 후처리, 알림 문구, 우선순위 계산은 `source_model`과 `class`를 함께 보고 결정한다.
- 모델별 confidence threshold를 분리한다.

피해야 할 방식:

- COCO class와 custom tactile class를 하나의 class id 공간에 임의로 합치기
- 두 모델 출력을 합친 뒤 단일 NMS로 bbox 제거하기
- confidence만 비교해 한 모델의 결과가 다른 모델의 결과를 덮어쓰기

## 모바일/서버 운영 옵션

운영 방식은 아직 확정하지 않는다.

- **서버 GPU 추론**: 서버에서 추론하고 모바일/클라이언트는 결과만 받는 방식. 지연 시간, 네트워크 상태, GPU 점유율 확인이 필요하다.
- **로컬 CPU/ONNX 추론 가능성**: 모바일 또는 엣지 장치에서 ONNX 등으로 실행할 가능성은 있다. 다만 현재는 추정이며, 실제 성능과 배포 방식은 아직 확인하지 않았다.

현재 정책에서 확정된 것은 **YOLO26n COCO pretrained를 학습 없이 inference-only 후보로 둔다**는 점뿐이다. 서버/모바일 배포 방식은 별도 검증 후 결정한다.

## 테스트 방법 초안

현재 YOLO26s 학습이 GPU에서 진행 중이므로, 이 문서 작성 시점에는 실제 GPU 추론/평가를 실행하지 않는다.

학습이 끝난 뒤 또는 별도 GPU 사용 가능 시간에 다음 smoke test만 최소로 수행한다.

1. allowlist 객체가 포함된 이미지 1~2장을 준비한다.
2. YOLO26n COCO pretrained로 inference-only 실행한다.
3. allowlist class만 필터링되는지 확인한다.
4. 각 결과에 `source_model: "coco_yolo26n"`이 붙는지 확인한다.
5. custom tactile 결과와 합칠 때 모델 간 NMS가 실행되지 않는지 확인한다.
6. threshold 초안이 너무 높거나 낮은지 로그로 확인하고 조정 후보를 기록한다.

smoke test는 기능 연결 확인 목적이며, 정량 평가나 대량 추론은 별도 계획을 세운 뒤 진행한다.
