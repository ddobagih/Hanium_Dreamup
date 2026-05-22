# V2 Safety Alert and Automatic Report Policy

- 작성일: 2026-05-22 KST
- 목적: 시각장애인/저시력 사용자가 화면 버튼을 직접 누르는 흐름보다, 자동 신고, 보행 위험 경고, 길 안내, 음성 명령 중심으로 앱을 사용하는 v2 정책을 정리한다.
- 현재 범위:
  - 타일/점자블록 손상은 자동 신고 대상이다.
  - COCO/general 객체는 신고하지 않지만, 보행 위험이면 경고 대상이다.
  - 사용자가 듣는 음성 안내는 보행 안전 경고와 길 안내 중심으로 제한한다.

## 1. 제품 원칙

- 기본 조작은 화면 터치가 아니라 자동 감지, TTS, 진동, 음성 명령이다.
- 사용자가 화면을 보고 신고 버튼을 누르는 흐름은 보조/디버그 수준으로 둔다.
- 손상 타일/점자블록이 감지되면 가능한 한 자동 신고한다.
- 사용자가 “이거 신고해줘”처럼 명시적으로 요청하면 자동 신고보다 높은 우선순위로 신고를 시도한다.
- 자동 신고는 행정/운영 기록에 가깝다. 사용자가 명시적으로 요청한 경우를 제외하면, 신고 완료 여부를 TTS로 굳이 들려주지 않는다.
- 사용자가 앱을 쓰면서 주로 들어야 하는 것은 신고 상태가 아니라 보행 중 즉시 대응해야 하는 위험 경고와 길 안내다.
- 신고 대상과 경고 대상은 분리한다.
  - 신고: 시설물/타일 손상 기록.
  - 경고: 지금 보행자에게 충돌, 추락, 걸림, 우회 필요 같은 직접 위험을 만드는 상황.

## 2. 자동 신고 대상

현재 저장 대상은 아래 두 class로 제한한다.

| model_key | class_name | 처리 |
|---|---|---|
| `custom_tactile` | `tactile_damage_area` | 자동 신고 대상 |
| `custom_tactile` | `damaged_tactile_block` | 자동 신고 대상 |

아래는 현재 신고 저장 대상이 아니다.

| 대상 | 이유 |
|---|---|
| `normal_tactile_block` | 정상 경로/맥락 정보이며 위험 신고 대상 아님 |
| COCO `person`, `car`, `bus`, `truck`, `bicycle`, `motorcycle`, `traffic light`, `bench` | 보행 안내/주의에는 쓸 수 있으나 시설물 손상 신고로 저장하지 않음 |
| unknown class | 정책 미정이므로 저장하지 않음 |

## 3. 사용자 경고 대상

사용자에게 들려줄 경고는 “탐지되었는가”가 아니라 “보행자가 지금 피하거나 대비해야 하는가”를 기준으로 한다.

### 3.1 경고 대상 예시

| 대상 | 경고 여부 | 조건 |
|---|---:|---|
| 보행 경로 위 장애물 | 예 | 보행자 예상 진행 경로를 막고 있어 회피가 필요할 때 |
| 보행자에게 빠르게 다가오는 객체 | 예 | 충돌 가능성이 높을 때 |
| 보행자 옆으로 지나가는 객체 | 아니오 | 충돌 가능성이 낮고 경로를 막지 않을 때 |
| 땅 꺼짐, 포트홀, 단차 | 예 | 보행자가 밟거나 걸려 넘어질 수 있는 위치일 때 |
| 타일 튀어나옴, 돌출된 보도블록 | 예 | 보행자가 걸릴 수 있는 위치일 때 |
| 타일/점자블록 손상 신고 대상 | 기본적으로 아니오 | 자동 신고만 수행한다. 다만 별도 모델/규칙이 “즉시 보행 위험”으로 판단한 경우에는 경고할 수 있다. |
| 정상 점자블록 | 아니오 | 경로/맥락 정보이며 위험 경고 대상이 아니다. |

### 3.2 일반 객체 처리

- 일반 객체는 자동 신고하지 않는다.
- 일반 객체는 보행 위험일 때만 경고한다.
- `person`, `car`, `bus`, `truck`, `bicycle`, `motorcycle`, `kickboard` 같은 객체는 존재 자체로 경고하지 않는다.
- 경고 조건은 다음 중 하나 이상이다.
  - 보행자의 예상 경로를 막고 있다.
  - 보행자 쪽으로 접근 중이며 충돌 가능성이 있다.
  - 빠르게 가까워지고 있어 사용자가 멈추거나 회피해야 한다.
- 보행자 옆을 지나가거나, 멀리 있거나, 진행 경로와 무관한 객체는 display-only 또는 무시한다.

### 3.3 예측 시스템 요구

다가오는 객체 경고는 단일 프레임 탐지만으로 확정하지 않는다. 처음부터 특정 화면 하단 중앙 영역 하나로 제한하지 않고, 위험 판단 모듈을 분리해서 확장 가능하게 설계한다.

위험 판단 모듈은 최소한 다음 입력을 고려한다.

- 객체 class, confidence, bbox
- 프레임 간 bbox 중심 이동과 bbox 크기 변화
- 객체 track의 지속 시간과 안정성
- 카메라 화면에서의 위치와 사용자의 예상 진행 경로
- 객체와 예상 진행 경로의 교차 가능성
- 충돌까지 남은 시간 또는 근접 위험 점수

초기 구현은 카메라 영상에서 얻을 수 있는 화면 좌표와 시간 변화량을 사용하되, 설계를 다음 단계 입력으로 확장 가능하게 둔다.

- heading/IMU
- depth 또는 monocular depth 추정
- optical flow
- 보행 가능 영역/바닥 segmentation
- 사용자 이동 속도 또는 보행 방향 추정

즉, 화면 하단 중앙을 고정 규칙으로 박지 않는다. 필요하면 초기 heuristic의 일부로 화면 내 위험 가중치를 둘 수 있지만, 최종 판단은 `risk_score`, `risk_type`, `time_to_collision`, `path_intersection` 같은 명시적 결과를 내는 예측/위험 평가 계층에서 결정한다.

### 3.4 위험 판단 출력

위험 판단은 탐지 결과를 그대로 TTS로 바꾸지 않고, 별도 결과로 변환한다.

| 필드 | 의미 |
|---|---|
| `alertable` | 사용자에게 경고할지 여부 |
| `risk_type` | `blocking_path`, `approaching_collision`, `ground_hazard`, `report_only_damage`, `display_only` 등 |
| `risk_level` | `none`, `low`, `medium`, `high`, `critical` |
| `reason` | 경고 또는 미경고 이유 |
| `recommended_message` | 사용자에게 말할 짧은 문장 |

자동 신고 대상인 타일/점자블록 손상은 기본적으로 `report_only_damage`로 처리한다. 단, 별도 위험 판단이 보행 중 걸림/추락 위험을 판단하면 `ground_hazard`로 경고할 수 있다.

## 4. 음성/TTS 정책

- TTS는 사용자 행동이 필요한 정보 위주로 제한한다.
  - 예: “전방 장애물”, “오른쪽으로 피하세요”, “빠르게 접근 중”, “발밑 주의”, 길 안내.
- 자동 신고 성공/중복/저장 ID 같은 운영 상태는 기본적으로 TTS로 말하지 않는다.
- 자동 신고 실패도 사용자가 즉시 조치할 수 없는 경우에는 반복 안내하지 않는다.
- 사용자가 “신고해줘”처럼 명시적으로 요청한 경우에는 요청 처리 완료 여부를 짧게 TTS로 안내한다.
- UI 상태에는 신고 상태를 표시할 수 있지만, 시각장애인 사용자의 주 음성 채널을 신고 상태로 점유하지 않는다.

## 5. 자동 신고 상태 체계

Frontend v2 자동 신고 상태는 다음으로 정리한다.

| 상태 | 의미 | 사용자 안내 |
|---|---|---|
| `idle` | 자동 신고 대기 | 자동 신고 대기 |
| `waiting_location` | 손상 후보는 있으나 GPS 없음 | 위치 필요 |
| `not_reportable` | 현재 v2 detections 중 신고 대상 없음 | 대상 없음 |
| `cooldown` | 같은 class/근사 위치가 최근 신고됨 | 최근 신고됨 |
| `sending` | `/reports/v2` 전송 중 | UI 상태 표시. 자동 신고는 TTS 생략 |
| `sent` | 신고 저장 완료 | UI 상태 표시. 자동 신고는 TTS 생략 |
| `failed` | 신고 실패 | UI 상태 표시. 자동 신고는 TTS 반복 생략 |

음성 요청 신고(`trigger: voice`)는 사용자가 명시적으로 요청한 흐름이므로 처리 완료/실패를 짧게 TTS로 안내한다.

## 6. 자동 신고 중복 억제

- 자동 신고는 같은 class + 근사 위치 기준 cooldown을 적용한다.
- 현재 frontend 기본값:
  - cooldown: 10분
  - 위치 key: 위도/경도 소수점 4자리
- cooldown은 성공한 신고에만 기록한다.
- 신고 실패는 cooldown으로 막지 않는다.

## 7. 위치 정책

- 자동 신고(`trigger: auto`)는 GPS가 없으면 보내지 않는다.
- 음성 명령 신고(`trigger: voice`)는 사용자 명시 요청이므로 GPS가 없어도 `/reports/v2` 저장을 시도할 수 있다.
- 위치가 없는 신고는 운영자 검토에서 `missing_location` 플래그로 드러난다.

## 8. 음성 명령 정책

- `create_report` intent는 v2 모드에서 현재 신고 가능한 tactile damage detection을 `/reports/v2`로 저장한다.
- 음성 명령 신고는 cooldown을 우회한다.
- `fake-v2`는 데모 모드이므로 실제 저장하지 않는다.
- `server-v2`에서만 실제 저장한다.
- 신고 대상이 없으면 “현재 신고할 타일 손상이 없습니다”로 안내한다.
- 음성 명령의 핵심 용도는 신고 요청, 상태 반복, 현재 위치 확인, 목적지 설정, 길 안내 시작/중지처럼 사용자가 화면 없이 앱을 조작하는 것이다.

## 9. Backend 저장 정책

- API: `POST /reports/v2`
- form-data:
  - `metadata`: `detect.v2` detection + `trigger` + `auto_reported`
  - `image`: 신고 프레임 이미지
- DB:
  - 기존 `reports` 테이블 재사용
  - `class_id = model_class_id`
  - `class_name = v2 class_name`
  - `source = fake` if `source_model` starts with `fake`, otherwise `server`
  - 원본 v2 metadata는 `metadata` JSONB payload에 보존
- 신규 migration은 추가하지 않았다.

## 10. Admin 표시 정책

- `/admin`은 v2 class label을 표시할 수 있어야 한다.
- v2 metadata가 있으면 상세에서 다음을 보여준다.
  - `schema_version`
  - `model_key`
  - `source_model`
  - `trigger`
  - `auto_reported`
- 기존 v1 신고 목록/상태 변경 흐름은 유지한다.

## 11. 아직 남은 결정

- 자동 신고 cooldown 기본값을 실제 현장 테스트 후 조정할지 여부
- 음성 “신고 취소해줘”를 실제 삭제, 상태 변경, 취소 요청 플래그 중 무엇으로 구현할지
- v2 class filter를 backend `/reports` query에서 공식 지원할지 여부
- 실제 YOLO26s/COCO adapter 연결 후 source_model 명칭과 threshold 정책 확정
- 일반 객체 경고 구현은 화면 하단 중앙 고정 규칙으로 제한하지 않는다.
  - 1차 입력은 화면 좌표와 프레임 간 변화량을 사용한다.
  - 위험 판단 모듈은 heading/IMU/depth/segmentation을 나중에 연결할 수 있게 분리한다.
  - 접근 여부는 bbox 중심 이동, bbox 크기 증가, track 안정성, 예상 경로 교차 가능성을 함께 본다.
- “타일 튀어나옴”, “땅 꺼짐”을 현재 tactile damage class와 별도 class로 분리할지 여부
- 자동 신고 UI 상태는 유지하되 TTS를 어디까지 묵음 처리할지

## 12. 이번 구현 검증

- Backend v2/report 관련 tests 통과
- Frontend lint/typecheck 통과
- GPU 학습/평가/추론, DB migration 생성, 삭제, commit/push는 수행하지 않았다.
