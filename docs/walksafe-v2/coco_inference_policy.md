# YOLO26n COCO pretrained inference-only 정책

- 기준일: 2026-05-22 KST
- 현재 model_key: `coco_general`
- 현재 source_model 표기: `YOLO26n COCO pretrained`

## 목적

COCO pretrained 모델은 사람, 차량, 자전거, 신호등, 벤치 같은 일반 객체 후보를 제공하는 보조 모델이다. 프로젝트 tactile 데이터로 fine-tune하지 않고 inference-only로 사용한다.

## allowlist

현재 config 기준 allowlist와 threshold는 다음과 같다.

| class | threshold | 처리 |
|---|---:|---|
| `person` | 0.25 | 위험 평가 입력 |
| `car` | 0.25 | 위험 평가 입력 |
| `bus` | 0.25 | 위험 평가 입력 |
| `truck` | 0.25 | 위험 평가 입력 |
| `bicycle` | 0.25 | 위험 평가 입력 |
| `motorcycle` | 0.25 | 위험 평가 입력 |
| `traffic light` | 0.25 | 안내/맥락 후보 |
| `bench` | 0.25 | 고정 객체/맥락 후보 |

allowlist 밖 COCO class는 사용자 알림과 신고 대상에서 제외한다.

## 신고/경고 정책

- COCO/general 객체는 `/reports/v2` 저장 대상이 아니다.
- 객체 존재만으로 TTS 경고하지 않는다.
- 경고는 risk evaluator가 보행 경로 차단, 접근 충돌 가능성 등을 판단했을 때만 낸다.
- 보행자 옆을 지나가거나 멀리 있거나 경로와 무관한 객체는 표시 전용 또는 무시한다.

## 병합 원칙

- `model_key: "coco_general"`을 유지한다.
- custom tactile 결과와 단일 class-id 공간으로 합치지 않는다.
- 모델 간 bbox overlap만으로 suppression하지 않는다.
- confidence만 비교해 tactile damage를 COCO 객체가 덮어쓰지 않는다.

## 아직 필요한 검증

- 실제 YOLO26n COCO inference adapter 연결
- allowlist class smoke test
- 모바일/서버 latency 측정
- risk evaluator 입력으로 쓸 tracking/path/depth 정보 설계
- threshold 조정과 반복 경고 억제 품질 확인
