# Frontend Two-Model Display Policy Draft

- 작성일: 2026-05-22
- 담당: Worker B, Frontend/앱 표시 정책
- 범위: 문서 초안만 작성한다. 실제 `apps/web` 코드 변경은 하지 않는다.
- 실행 제한: YOLO26s 학습 중이므로 GPU 추론/평가를 실행하지 않았다.
- API 상태: API v2 결과 형식은 아직 확정 전이다. 프론트엔드는 `class_id` 고정 매핑보다 `source_model`/`model_key`/`class_name` 문자열을 기반으로 느슨하게 처리하는 방향을 우선한다.

## 1. 로컬 확인 요약

### 1.1 확인한 frontend 구조

`apps/web`는 현재 Next.js PWA 형태이며, 주요 파일은 다음과 같다.

| 경로 | 확인 내용 |
| --- | --- |
| `apps/web/app/page.tsx` | 보행자 홈 화면, 카메라 프리뷰, 단일 bbox 오버레이, 위험 pill, 음성/진동 알림, 신고 버튼을 포함한다. |
| `apps/web/app/admin/page.tsx` | 신고 조회/관리 화면이다. |
| `apps/web/types/inference.ts` | 현재 v1 `DetectionEvent` 타입과 4개 클래스 매핑을 가진다. |
| `apps/web/lib/detect-api.ts` | `/detect/health`, `/detect` 호출과 서버 응답을 `DetectionEvent[]`로 변환한다. |
| `apps/web/lib/detector.ts` | fake detector로 현재 4개 클래스의 데모 탐지를 생성한다. |
| `apps/web/lib/report-api.ts` | 탐지 결과 신고/중복 확인 연동을 담당한다. |
| `apps/web/lib/voice-api.ts` | 음성 명령/STT API 연동을 담당한다. |
| `apps/web/public/*` | PWA manifest, service worker, icon을 포함한다. |

현재 구현은 한 번에 하나의 대표 탐지(`detection`)를 화면에 보여주는 구조에 가깝다. 두 모델 결과를 그대로 표시하려면 향후 여러 detection을 우선순위로 정렬하고, 채널별 표시/알림을 분리해야 한다.

### 1.2 확인한 문서

| 문서 | 반영한 내용 |
| --- | --- |
| `docs/frontend_api_examples.md` | 현재 `/detect`는 모델 미연결 시 `model_unavailable`을 반환하며, 신고 예시는 v1 `DetectionEvent`를 사용한다. |
| `docs/inference_contract.md` | 현재 계약은 `source: fake/onnx/server`, 정규화 bbox, 4개 v1 클래스 기준이다. |
| `docs/ui_feature_inventory.md` | 보행자 홈은 카메라 중심, 짧은 한국어 문구, aria-live, 음성 쿨다운, 색상 외 구분을 요구한다. |
| `docs/walksafe-v2/coco_inference_policy.md` | COCO 모델은 inference-only 보조 모델이며 allowlist 일반 객체만 사용자 알림 대상으로 삼는다. |
| `docs/walksafe-v2/two_model_runtime_plan.md` | custom tactile 3-class와 COCO pretrained 모델을 `source_model`으로 분리 병합하는 초안이 있다. |

## 2. 표시 대상 분리

두 모델은 의미가 다르므로 같은 위험 목록으로 섞어 보이지 않는다. 프론트 표시도 **tactile 채널**과 **general 채널**로 분리한다.

| 채널 | 모델 후보 | 표시 대상 | 화면 역할 | 알림 역할 |
| --- | --- | --- | --- | --- |
| tactile | `custom_tactile_yolo26s` | `normal_tactile_block` | 점자블록 경로/맥락 표시 | 낮음, 반복 음성은 기본 비권장 |
| tactile | `custom_tactile_yolo26s` | `damaged_tactile_block` | 파손 점자블록 위험 표시 | 높음 |
| tactile | `custom_tactile_yolo26s` | `tactile_damage_area` | 파손 영역 직접 강조 | 최상 |
| general | `coco_yolo26n_pretrained` | `person` | 전방 사람/혼잡 맥락 | 주의 |
| general | `coco_yolo26n_pretrained` | `car` | 차량 존재/접근 맥락 | 주의 |
| general | `coco_yolo26n_pretrained` | `bus` | 대형 차량 맥락 | 주의 |
| general | `coco_yolo26n_pretrained` | `truck` | 대형 차량/시야 차단 맥락 | 주의 |
| general | `coco_yolo26n_pretrained` | `bicycle` | 자전거 접근/공유 경로 맥락 | 중간 |
| general | `coco_yolo26n_pretrained` | `motorcycle` | 빠른 이륜차 맥락 | 주의 |
| general | `coco_yolo26n_pretrained` | `traffic light` | 횡단/교차로 맥락 | 낮음~중간 |
| general | `coco_yolo26n_pretrained` | `bench` | 고정 물체/휴식 시설 맥락 | 낮음 |

권장 UI 구분:

- tactile 채널: `점자/지면` 배지, 발밑 위험 카드, 높은 우선순위 음성/진동.
- general 채널: `주변 객체` 배지, 충돌/주의 맥락 카드, tactile과 다른 색상/아이콘.
- 같은 bbox 위치에 두 모델 결과가 겹쳐도 서로 다른 의미일 수 있으므로 cross-model suppression으로 숨기지 않는다.

## 3. 사용자 알림 우선순위 초안

보행 중 목걸이형 휴대폰 맥락에서는 모든 객체를 읽어 주면 방해가 크다. 한 프레임 또는 짧은 시간창에서는 **최대 1개 핵심 위험 + 필요 시 1개 보조 맥락**만 알리는 방향을 권장한다.

| 우선순위 | 대상 | 알림 채널 | 정책 초안 |
| ---: | --- | --- | --- |
| 1 | `tactile_damage_area` | 즉시 위험 | 발밑 파손 영역으로 보고 최우선 음성/진동. 화면에서도 가장 강하게 강조한다. |
| 2 | `damaged_tactile_block` | 즉시 위험 | 점자블록 파손 위험으로 알린다. `tactile_damage_area`가 함께 있으면 하나의 문구로 합친다. |
| 3 | `car`, `bus`, `truck`, `motorcycle` | 주의 | 충돌/접근 가능성이 있는 큰 객체 맥락으로 알린다. tactile 위험이 있으면 보조 문구로 낮춘다. |
| 4 | `person`, `bicycle` | 주의 | 전방 사람/자전거 존재를 짧게 알린다. 반복 알림은 억제한다. |
| 5 | `traffic light` | 안내 | 횡단/교차로 맥락 정보로 표시한다. 위험 음성보다는 상태 안내에 가깝다. |
| 6 | `normal_tactile_block` | 안내 | 정상 점자블록은 경로/맥락용이다. 위험 알림 우선순위는 낮고, 반복 음성은 피한다. |
| 7 | `bench` | 안내 | 고정 객체 정보로 표시하되 기본 음성 알림은 낮게 둔다. |

충돌 규칙 초안:

- `tactile_damage_area`와 `damaged_tactile_block`이 동시에 있으면 “점자블록 파손” 계열로 묶어 한 번만 알린다.
- `normal_tactile_block`은 파손 클래스와 동시에 있을 때 위험을 낮추는 근거로 쓰지 않는다. “정상도 일부 보임” 정도의 경로 맥락으로만 둔다.
- COCO 큰 객체는 tactile 위험과 다른 채널/배지로 표시한다. tactile 위험을 덮어쓰지 않는다.
- COCO allowlist 밖 클래스는 사용자 알림 대상에서 제외하고, 필요하면 개발 로그/디버그 표시로만 둔다.

## 4. 화면/음성 문구 초안

한국어 문구는 짧고 직접적으로 쓴다. 아래 문구는 초안이며, 실제 사용자 테스트 후 조정한다.

| 대상 | 화면 짧은 문구 | 음성 문구 초안 | 알림 강도 |
| --- | --- | --- | --- |
| `tactile_damage_area` | 파손 영역 주의 | 발밑 파손 영역. 천천히 이동. | 최상 |
| `damaged_tactile_block` | 점자블록 파손 | 점자블록 파손. 발밑 주의. | 높음 |
| `normal_tactile_block` | 점자블록 감지 | 점자블록이 이어집니다. | 낮음, 기본 반복 비권장 |
| `person` | 전방 사람 | 전방에 사람이 있습니다. | 중간 |
| `car` | 차량 주의 | 차량이 있습니다. 주의하세요. | 높음 |
| `bus` | 버스 주의 | 대형 차량 주의. | 높음 |
| `truck` | 트럭 주의 | 대형 차량 주의. | 높음 |
| `bicycle` | 자전거 주의 | 자전거가 있습니다. | 중간 |
| `motorcycle` | 오토바이 주의 | 오토바이 주의. | 높음 |
| `traffic light` | 신호등 감지 | 신호등이 있습니다. | 낮음~중간 |
| `bench` | 벤치 감지 | 벤치가 있습니다. | 낮음, 기본 반복 비권장 |

복합 문구 예시:

| 상황 | 화면 문구 | 음성 문구 |
| --- | --- | --- |
| 파손 영역 + 차량 | 파손 영역 · 차량 주의 | 발밑 파손 영역. 차량 주의. |
| 파손 점자블록 + 정상 점자블록 | 점자블록 파손 | 점자블록 파손. 발밑 주의. |
| 정상 점자블록만 반복 감지 | 점자블록 감지 | 기본 무음 또는 낮은 빈도 안내 |
| COCO 객체 다수 | 주변 객체 3개 | 전방 객체가 많습니다. 천천히 이동. |

## 5. bbox overlay 정책 초안

두 모델 결과는 bbox label과 색상으로 구분한다. 단, 색상만으로 의미를 전달하지 말고 label, 아이콘, 배지 텍스트를 함께 둔다.

| 그룹 | label 예시 | 색상/형태 초안 | 표시 정책 |
| --- | --- | --- | --- |
| `tactile_damage_area` | `점자: 파손 영역 64%` | 빨강, 두꺼운 선, 반투명 강조 | 최상단에 표시. 작은 bbox라도 강조한다. |
| `damaged_tactile_block` | `점자: 파손 82%` | 주황/빨강, 실선 | tactile 위험으로 표시한다. |
| `normal_tactile_block` | `점자: 정상 78%` | 초록/청록, 얇은 선 또는 점선 | 경로 맥락용. 위험처럼 보이지 않게 한다. |
| COCO 차량/사람류 | `주변: 차량 77%` | 파랑/보라, 얇은 선 | 충돌/주의 맥락. tactile보다 시각적 강도를 낮춘다. |
| COCO 낮은 우선순위 | `주변: 벤치 70%` | 회색/파랑, 얇은 선 | 필요할 때만 표시한다. |

과도한 박스 표시 제한:

- tactile 위험 bbox는 우선 표시한다.
- COCO bbox는 allowlist 안에서도 상위 confidence/중앙 근접/화면 하단 근접 등 기준으로 제한한다.
- 1차 UI에서는 COCO bbox를 최대 2~3개만 표시하는 방향을 권장한다.
- 낮은 우선순위 COCO 객체가 많으면 개별 bbox 대신 “주변 객체 N개” 배지로 요약한다.
- `tactile_damage_area`가 있으면 해당 bbox를 항상 가장 위 레이어로 둔다.
- 두 모델 bbox가 겹쳐도 `source_model` label을 유지한다. 모델 간 단일 NMS로 한쪽을 제거하지 않는다.

## 6. 접근성/목걸이형 휴대폰 사용 맥락

목걸이형 휴대폰은 화면이 흔들리고 사용자가 화면을 계속 보기 어렵다. 따라서 음성/진동/화면 낭독 정책이 화면 박스 수보다 중요하다.

| 항목 | 정책 초안 |
| --- | --- |
| 음성 길이 | 한 문장은 1~2초 수준으로 짧게 유지한다. |
| 알림 수 | 한 순간에 여러 객체를 모두 읽지 않는다. 핵심 위험 위주로 요약한다. |
| aria-live | tactile 위험은 `assertive`, 일반 객체/안내는 `polite` 또는 상태 텍스트로 둔다. |
| 진동 | tactile 위험과 COCO 주의를 다른 패턴으로 분리한다. 세부 패턴은 향후 구현에서 확정한다. |
| 색상 의존 | 색상 외에 `점자`, `주변`, `파손`, `주의` 같은 텍스트 배지를 함께 둔다. |
| 사용자 방해 | 반복 알림, 긴 문장, 확인 모달은 보행 중 방해가 크므로 피한다. |

쿨다운/중복 억제는 향후 구현 항목이다. 현재 `apps/web/app/page.tsx`에는 같은 위험 유형에 대한 6초 쿨다운 개념이 있으나, 두 모델 체계에서는 최소한 다음 키를 고려해야 한다.

```text
source_model 또는 model_key + class_name + 위치 버킷 + 시간창
```

예상 구현 방향:

- 같은 위치/같은 클래스의 반복 알림은 일정 시간 억제한다.
- `tactile_damage_area`처럼 최상위 위험은 쿨다운 중에도 신뢰도 급상승 또는 화면 중앙 진입 시 재알림을 허용할 수 있다.
- `normal_tactile_block`, `bench`, `traffic light`는 기본적으로 낮은 빈도의 안내로 둔다.
- 신고/확인 UI는 보행을 멈추게 하는 modal보다 상태 문구와 큰 버튼을 우선한다.

## 7. API v2 확정 전 frontend 처리 방향

현재 `docs/inference_contract.md`와 `apps/web/types/inference.ts`는 v1 4-class `DetectionEvent` 기준이다. 두 모델 결과를 위한 API v2는 아직 확정 전이므로, 프론트는 다음 원칙으로 느슨하게 처리하는 것이 안전하다.

| 필드 | 처리 방향 |
| --- | --- |
| `source_model` | 있으면 최우선 사용한다. 예: `custom_tactile_yolo26s`, `coco_yolo26n_pretrained`. |
| `model_key` | 있으면 채널 구분에 사용한다. 예: `tactile`, `general`, `coco`. |
| `source` | v1 호환 필드로 유지한다. `server`만으로는 모델 종류를 알 수 없으므로 단독 판단하지 않는다. |
| `class_name` | 표시/알림의 핵심 키로 사용한다. `class_id`보다 우선한다. |
| `class_id` | 모델별 class id 공간이 다를 수 있으므로 전역 의미로 고정하지 않는다. |
| `bbox` / `bbox_xyxy` | v2에서 형식이 확정될 때까지 adapter 계층에서 정규화한다. UI 컴포넌트는 정규화 bbox를 받는 방향이 단순하다. |
| `semantic_group` | 있으면 우선순위 계산 보조값으로 쓴다. 없으면 `model_key`와 `class_name`으로 추정한다. |
| unknown field | 무시하되 원본 detection은 신고/디버그 용도로 보존하는 방향을 검토한다. |

권장 v2 수용 방식 초안:

```json
{
  "detections": [
    {
      "source_model": "custom_tactile_yolo26s",
      "model_key": "tactile",
      "class_name": "tactile_damage_area",
      "confidence": 0.64,
      "bbox": { "x": 0.32, "y": 0.62, "width": 0.18, "height": 0.12 }
    },
    {
      "source_model": "coco_yolo26n_pretrained",
      "model_key": "general",
      "class_name": "car",
      "confidence": 0.77,
      "bbox": { "x": 0.58, "y": 0.22, "width": 0.30, "height": 0.50 }
    }
  ]
}
```

프론트에서 피해야 할 것:

- `class_id: 0`을 전역으로 `damaged_tactile_block`이라고 단정하기.
- COCO 객체를 tactile 위험과 같은 배지/음성 채널로 섞기.
- API v2 필드가 확정되기 전에 TypeScript union을 과하게 고정해 백엔드 변경을 어렵게 만들기.
- allowlist 밖 COCO 클래스를 사용자에게 곧바로 음성 경고하기.

## 8. 향후 구현 전 확인 필요 항목

| 항목 | 확인 필요 이유 |
| --- | --- |
| API v2 최종 schema | `source_model`, `model_key`, bbox 형식, class id 범위를 확정해야 한다. |
| tactile class별 threshold | YOLO26s custom 학습/평가 완료 후 표시 기준을 정해야 한다. |
| COCO allowlist threshold | inference-only smoke test 이후 오탐/과다 알림을 확인해야 한다. |
| 다중 detection UI 구조 | 현재 단일 대표 detection 중심 UI에서 여러 bbox/카드로 확장할 범위를 정해야 한다. |
| 음성/진동 쿨다운 | 접근성 테스트 후 source/class/location 기반 억제 규칙을 정해야 한다. |
| 신고 metadata | v2 detection을 신고할 때 `source_model`/`model_key`를 보존할지 백엔드와 맞춰야 한다. |

이 문서는 표시 정책 초안이며, 실제 frontend 코드 변경은 별도 작업에서 API v2 계약 확정 후 진행한다.
