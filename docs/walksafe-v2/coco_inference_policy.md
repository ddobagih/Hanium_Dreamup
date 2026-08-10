# YOLO26n COCO pretrained inference-only 정책

- 기준일: 2026-06-02 KST
- 현재 model_key: `coco_general` (legacy fallback 전용)
- 현재 source_model 표기: `YOLO26n COCO pretrained`

## 2026-06-02 현재 우선순위 보정

- 주 사용자 앱 경로는 Web/PWA다.
- 기본 detector 경로는 `unified_walksafe` 13-class 단일 모델이다.
- 이 문서의 `coco_general` 정책은 unified asset이 없을 때 쓰는 legacy fallback 또는 과거 smoke 해석용이다.
- Android native는 ARCore/depth/TFLite 실험·검증 보조 경로다. Web/PWA 브라우저·Release evidence와 Android Device evidence는 서로 대체하지 않는다.
- Android report upload, TTS/haptic, navigation 연결은 bbox/depth 좌표 정합 gate 이후 진행한다.

## 목적

COCO pretrained 모델은 legacy fallback에서 사람, 차량, 자전거, 신호등, 벤치 같은 일반 객체 후보를 제공하는 보조 모델이다. 프로젝트 tactile 데이터로 fine-tune하지 않고 inference-only로 사용한다. 새 학습 산출물은 COCO allowlist 중 `bench`를 제외한 일반 객체를 `unified_walksafe` 안에 포함한다.

## allowlist

legacy backend 기본 config 기준 allowlist와 threshold는 다음과 같다.

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

## legacy 병합 원칙

- `model_key: "coco_general"`을 유지한다.
- custom tactile 결과와 단일 class-id 공간으로 합치지 않는다.
- 모델 간 bbox overlap만으로 suppression하지 않는다.
- confidence만 비교해 tactile damage를 COCO 객체가 덮어쓰지 않는다.

## 아직 필요한 검증

- Android TFLite COCO helper와 backend YOLO26n COCO inference adapter 결과 분리 검증
- allowlist class smoke test
- Android TFLite latency와 backend server latency를 분리 측정
- risk evaluator 입력으로 쓸 tracking/path/depth 정보 설계
- threshold 조정과 반복 경고 억제 품질 확인
