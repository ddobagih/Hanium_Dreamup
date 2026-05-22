# Backend Two-Model Safety Audit

- 작성일: 2026-05-22 (Asia/Seoul)
- 담당: Worker E — Backend 통합 안전 감사 + GitHub 참고
- 작업 제약: YOLO26s 학습 중이므로 GPU 추론/평가/대량 검증은 실행하지 않았다.
- 변경 제약: 실제 코드 변경, 커밋, 푸시, PR 생성/수정은 하지 않았다.
- 수정 파일: 이 문서(`docs/walksafe-v2/backend_safety_audit.md`)만 생성했다.
- daylog: 사용자 지시가 `daylog 작성 금지`이므로 작성하지 않았다.

## 1. 확인 범위

### 1.1 우선 확인한 backend 파일

- `backend/app/schemas.py`
- `backend/app/models.py`
- `backend/app/detector.py`
- `backend/app/main.py`
- `backend/tests/test_reports.py`
- `backend/tests/test_detect.py`

### 1.2 보조로 확인한 로컬 맥락

- `backend/app/config.py`: `MODEL_CLASS_ORDER` 파싱이 기존 4-class에 고정되어 있어 backend 연결 위험을 판단하는 데 필요했다.
- `model/two_model_runtime.py`, `model/test_two_model_runtime.py`: backend 밖 helper가 pure Python 후처리/merge 단위로 분리되어 있는지 확인했다.
- 기존 문서 `docs/walksafe-v2/two_model_runtime_plan.md`, `docs/walksafe-v2/coco_inference_policy.md`: 새 3-class custom/COCO 역할 분리 맥락 확인용으로만 읽었다.

## 2. 현재 backend 계약 요약

### 2.1 `schemas.py`

현재 schema는 단일 class namespace를 전제로 한다.

- `CLASS_NAMES`는 4개 class만 허용한다.
  - `0: damaged_tactile_block`
  - `1: parked_kickboard_bicycle`
  - `2: construction_obstacle`
  - `3: pothole`
- `ClassId = Literal[0, 1, 2, 3]`, `ClassName = Literal[...]`로 고정되어 있다.
- `ReportMetadata`는 `class_id`, `class_name`, `confidence`, `bbox`, `captured_at`, `source`, `gps`, `heading`만 가진다.
- `ReportMetadata.class_name_matches_id` validator가 `CLASS_NAMES[class_id] == class_name`을 강제한다.
- `DetectHealthResponse.model_class_order`도 `List[ClassName]`이라 기존 4-class 외 이름을 응답할 수 없다.
- `DetectResponse.detections`는 `List[ReportMetadata]`라 v2/two-model metadata를 표현할 공간이 없다.

### 2.2 `models.py`

DB 모델 자체는 class enum 제약이 없다.

- `reports.class_id`는 `Integer`, `reports.class_name`은 `String(64)`이다.
- `payload = Column("metadata", JSONB, nullable=False)`가 있어 원본 metadata JSON 저장은 가능하다.
- 다만 API 입력/응답이 `ReportMetadata`와 `ReportResponse`에 묶여 있어 실제 저장 경로에서는 기존 4-class validation을 통과해야 한다.
- `class_id`가 단일 정수 namespace라 custom 3-class와 COCO class id를 동시에 넣으면 의미 충돌이 난다.

### 2.3 `detector.py`

현재 detector adapter는 단일 YOLO `.pt` 모델 + 기존 backend class order에 맞춰져 있다.

- health와 detect는 `settings.model_artifact_path`, `settings.model_class_order`, threshold/imgsz만 본다.
- `_compatible_class_id_map`은 다음만 허용한다.
  1. model class order가 backend class order와 정확히 일치
  2. 1-class 모델이 backend 첫 class(`damaged_tactile_block`)와 일치
- 그 외에는 `model_class_order_mismatch`로 model unavailable 처리한다.
- `_detection_from_box`는 model class id를 backend class id로 매핑한 뒤 `CLASS_NAMES[backend_class_id]`로 `ReportMetadata`를 만든다.
- `source`는 `server`로만 남고, `source_model`, `model_key`, `semantic_group`, `threshold_used` 같은 two-model 구분 정보가 없다.

### 2.4 `main.py`

API endpoint도 v1 `ReportMetadata` 중심이다.

- `/detect`는 `DetectResponse`를 response model로 사용한다.
- `/reports`는 form의 `metadata`를 `ReportMetadata.model_validate_json`으로 검증한 뒤 `reports` 테이블에 top-level 필드와 `payload`를 저장한다.
- `/reports` 필터와 `/reports/duplicate-check`는 `ClassName` 타입을 사용해 기존 4-class 외 class를 query parameter로 받지 못한다.
- duplicate 후보는 `class_name`, 위치, 시간으로 찾으므로 COCO 일반 객체와 tactile 결과를 같은 duplicate 정책에 넣으면 의미가 섞인다.

### 2.5 `config.py`

`MODEL_CLASS_ORDER`도 기존 4-class와 강하게 결합되어 있다.

- default는 `CLASS_ORDER`이다.
- `_parse_model_class_order`는 env 값이 default 4-class와 다르면 `ValueError`를 던진다.
- 따라서 새 custom 3-class나 COCO class order를 기존 `MODEL_CLASS_ORDER`에 넣어서는 backend가 시작 단계에서 깨질 수 있다.

## 3. DB/ReportMetadata/class validator에서 새 class 체계가 깨질 수 있는 지점

### 3.1 Custom tactile 3-class와 v1 class id/name 불일치

새 custom tactile 계획의 class는 다음 3개로 확인된다.

- `normal_tactile_block`
- `damaged_tactile_block`
- `tactile_damage_area`

v1 backend의 class는 4개이고 `damaged_tactile_block`이 id `0`이다. 반면 새 3-class 일반 YOLO order가 위 순서라면 `damaged_tactile_block`은 id `1`이 된다. 이 상태에서 기존 `ReportMetadata`에 그대로 넣으면 다음 문제가 난다.

- `normal_tactile_block`: `ClassName` Literal에 없어 validation 실패
- `tactile_damage_area`: `ClassName` Literal에 없어 validation 실패
- `damaged_tactile_block` with class id `1`: validator가 id `1`은 `parked_kickboard_bicycle`이어야 한다고 판단해 validation 실패
- `damaged_tactile_block`을 억지로 class id `0`으로 remap하면 새 custom 모델의 원래 id namespace가 사라지고, 정상/파손영역 class는 표현할 수 없다.

### 3.2 COCO class와 v1 schema 불일치

COCO allowlist 후보는 `person`, `car`, `bus`, `truck`, `bicycle`, `motorcycle`, `traffic light`, `bench`로 확인된다. 모두 v1 `ClassName` Literal 밖이다.

- COCO class name은 `/detect` 응답의 `ReportMetadata`에 들어갈 수 없다.
- COCO class id는 COCO 고유 id namespace이므로 v1 `ClassId = Literal[0,1,2,3]`와 충돌한다.
- `traffic light`처럼 공백이 있는 class name도 DB `String`에는 저장 가능하지만, v1 schema/API query에서는 허용되지 않는다.

### 3.3 health response에서도 class_order를 표현하지 못함

`DetectHealthResponse.model_class_order`가 `List[ClassName]`이므로 health 응답조차 새 class order를 담기 어렵다.

- custom 3-class health: `normal_tactile_block`, `tactile_damage_area` 때문에 validation 실패 가능
- COCO health: 모든 allowlist/class order가 validation 실패 가능
- `config.py`의 `MODEL_CLASS_ORDER` parser도 기존 4-class 외 값을 거부한다.

### 3.4 detector adapter의 class order guard가 새 모델을 차단함

현재 `_compatible_class_id_map`은 기존 4-class exact match 또는 기존 첫 class 단일 모델만 허용한다.

- 새 custom 3-class는 기존 backend 4-class와 exact match가 아니므로 `model_class_order_mismatch`가 된다.
- COCO pretrained는 80-class이므로 exact match가 될 수 없다.
- 단일 `MODEL_ARTIFACT_PATH` 구조는 custom tactile과 COCO pretrained를 동시에 표현하지 못한다.

### 3.5 DB 저장은 가능해 보여도 API 의미가 깨짐

`reports` 테이블은 `class_id Integer`, `class_name String`, `payload JSONB`라 물리 저장은 유연하다. 하지만 현재 저장 경로는 v1 schema를 먼저 통과한다.

- v2 detection을 `/reports`로 직접 보내면 schema validation에서 막힌다.
- validation을 완화해 통과시키더라도 `class_id` 단일 namespace가 ambiguous하다.
  - 예: `class_id=0`이 v1 damaged인지, custom normal인지, COCO person인지 알 수 없다.
- `ReportResponse` top-level `class_id/class_name/source`는 단일 detection/report 중심이라 two-model multi-detection payload와 맞지 않는다.
- duplicate query와 review flag가 v1 report semantics에 묶여 있어 COCO 객체까지 같은 정책으로 처리하면 잘못된 중복/검토 플래그가 생길 수 있다.

## 4. 기존 tests가 기대하는 class_order와 새 3-class/COCO가 충돌하는 지점

### 4.1 `backend/tests/test_detect.py`

`test_detect.py`는 `CLASS_ORDER`를 기존 4-class 계약으로 사용한다.

- `DEFAULT_MODEL_CONTRACT["model_class_order"] == list(CLASS_ORDER)`를 기대한다.
- fixture가 매 테스트마다 `settings.model_class_order = CLASS_ORDER`로 되돌린다.
- fake YOLO names도 기존 `CLASS_ORDER`와 exact match하게 구성한다.
- health ready test는 기존 4-class order가 그대로 응답되는 것을 기대한다.
- class order mismatch test는 single class `pothole`이 기존 첫 class가 아니므로 mismatch가 나는 것을 기대한다.
- detect success test는 class id `0`이 `damaged_tactile_block`으로 반환된다고 기대한다.
- single-class test도 class id `0`을 `damaged_tactile_block`으로 매핑하는 기존 특례를 기대한다.

따라서 기존 v1 class order를 새 3-class로 직접 교체하면 위 테스트들이 대량 실패할 가능성이 높다.

### 4.2 `backend/tests/test_reports.py`

`test_reports.py`는 v1 report 저장/조회/중복 정책을 기존 4-class로 검증한다.

- `sample_metadata()` 기본값은 `class_id=0`, `class_name=damaged_tactile_block`이다.
- filter test는 `construction_obstacle`을 사용한다.
- duplicate test는 `pothole`을 사용한다.
- mismatched class test는 기존 validator가 `class_id=0` + `class_name=pothole`을 422로 거부하는 것을 기대한다.
- `/reports` query의 `class_name`도 기존 `ClassName` 타입에 의존한다.

즉 v1 `ReportMetadata`를 새 3-class로 바꾸면 기존 report API의 backward compatibility가 깨진다.

### 4.3 two-model helper와 backend v1 응답 구조 충돌

`model/two_model_runtime.py`의 `Detection`은 `model_key`, `class_name`, `confidence`, `bbox`, `source_model`, `category`를 갖는다. 이는 v1 `ReportMetadata`보다 정보가 많고 class id를 필수로 두지 않는다.

- helper는 custom 3-class와 COCO allowlist를 분리한다.
- cross-model NMS를 하지 않는 정책을 갖는다.
- backend v1 `DetectResponse`는 `List[ReportMetadata]`라 helper 결과를 손실 없이 담을 수 없다.

## 5. 안전한 구현 순서

### 5.1 1단계 — v2 schema 추가

기존 `ReportMetadata`, `DetectResponse`, `DetectHealthResponse`를 바꾸지 말고 v2 schema를 별도로 추가한다.

권장 v2 schema 방향:

- `schema_version`: 예 `2`
- `model_key`: `custom_tactile` / `coco_general`
- `source_model`: 예 `YOLO26s custom`, `YOLO26n COCO pretrained`
- `class_name`: `str` 또는 v2 전용 Literal
- `class_id`: 모델별 raw id로 optional 저장. 전역 id처럼 해석하지 않는다.
- `bbox`: 기존 normalized `BBox` 재사용 가능
- `confidence`: `0..1`
- `category` 또는 `semantic_group`
- `threshold_used`
- `raw`: 모델별 원본 metadata를 담는 optional dict

핵심은 v2 detection에는 v1 `CLASS_NAMES[class_id] == class_name` validator를 적용하지 않는 것이다.

### 5.2 2단계 — 기존 `/detect`와 `ReportMetadata` 유지

기존 endpoint/contract는 유지한다.

- `/detect`: 현재 v1 behavior 유지
- `/detect/health`: 현재 no-model/ready/unavailable 응답 유지
- `/reports`: 기존 `ReportMetadata` 기반 저장 유지
- 기존 `backend/tests/test_detect.py`, `backend/tests/test_reports.py`는 수정 없이 통과해야 한다.

새 기능은 `/detect/v2`, `/detect/v2/health` 또는 backend 내부 helper 테스트로 시작하는 편이 안전하다.

### 5.3 3단계 — `two_model_runtime` helper를 backend adapter 바깥에서 먼저 테스트

이미 `model/two_model_runtime.py`는 Ultralytics/PIL/GPU runtime을 import하지 않는 pure Python helper로 보인다. 이 방향을 유지한다.

우선 backend adapter에 붙이지 말고 다음을 fake detection으로 검증한다.

- custom 3-class threshold
- COCO allowlist threshold
- `model_key` 혼입 방지
- cross-model NMS 미실행
- JSON serialization
- invalid bbox/confidence/config rejection

이 단계에서는 실제 YOLO weight load, GPU 추론, 평가를 하지 않는다.

### 5.4 4단계 — backend adapter는 v2 schema가 안정된 뒤 연결

adapter 연결 시에도 v1 adapter를 덮어쓰지 않는다.

- v1 `YoloPtAdapter`는 그대로 두고, v2용 adapter/helper를 분리한다.
- custom tactile model과 COCO model은 별도 artifact/config/status로 다룬다.
- class order 검증은 모델별 namespace 기준으로 한다.
- COCO는 full class order를 backend v1 `ClassName`에 넣지 않는다.
- health는 per-model status와 reason을 반환한다.

### 5.5 5단계 — DB 저장은 v2 raw JSON/metadata 우선 또는 별도 테이블/필드 검토

즉시 기존 `reports.class_id/class_name`에 two-model 결과를 밀어 넣지 않는다.

안전한 선택지:

1. 초기: v2 결과를 API 응답으로만 제공하고 DB 저장은 보류
2. 최소 저장: 기존 `payload` 또는 별도 JSONB 필드에 v2 raw JSON을 저장하되, top-level v1 `class_id/class_name`에 의미를 섞지 않음
3. 정식 저장: 별도 테이블 또는 별도 v2 필드 검토
   - 예: `detection_results_v2(report_id/frame_id, schema_version, model_key, source_model, class_name, raw_class_id, bbox, confidence, threshold_used, raw_payload)`

별도 테이블/필드 없이 기존 `reports` row 하나에 여러 모델 detection을 top-level로 압축하면 class namespace, duplicate, review flag, filtering 의미가 모두 깨질 수 있다.

## 6. 최소 테스트 계획

### 6.1 Schema validation

- v1 `ReportMetadata`는 기존 4-class 정상 입력을 계속 허용한다.
- v1 `ReportMetadata`는 기존 mismatched `class_id/class_name`을 계속 422로 거부한다.
- v2 detection schema는 custom 3-class를 허용한다.
- v2 detection schema는 COCO allowlist class를 허용한다.
- v2 detection schema는 confidence 범위, bbox 범위, 빈 `model_key/source_model/class_name`을 거부한다.
- v2 health schema는 기존 `ClassName` Literal 없이 model별 class/allowlist를 문자열로 serialize할 수 있어야 한다.

### 6.2 Fake two-model detections

- fake custom detections와 fake COCO detections를 helper에 넣어 threshold filtering을 검증한다.
- COCO allowlist 밖 class는 제외되는지 검증한다.
- bbox가 겹쳐도 custom/COCO detection이 모두 남는지 검증한다.
- custom 입력에 `model_key=coco_general`이 섞이면 실패하는지 검증한다.
- 결과 dict가 JSON serialize 가능한지 검증한다.
- 이 테스트는 Ultralytics/PIL/실제 model load 없이 실행해야 한다.

### 6.3 Backward compatibility

- 기존 `/detect/health` no model 응답이 유지되는지 확인한다.
- 기존 `/detect` no model 503 응답이 유지되는지 확인한다.
- 기존 fake YOLO 기반 `/detect` success test가 유지되는지 확인한다.
- 기존 `/reports` 생성/조회/필터/중복/validator test가 유지되는지 확인한다.
- `MODEL_CLASS_ORDER` default가 기존 4-class로 유지되는지 확인한다.

### 6.4 No model configured health

- v1 `/detect/health`: model artifact 미설정이면 `model_status=unavailable`, `reason=model_not_configured` 유지
- v2 `/detect/v2/health`: custom/coco가 모두 미설정이면 HTTP 200에서 per-model unavailable reason을 반환
- v2 `/detect/v2`: 모델 미설정 시 503 또는 명시적 unavailable 응답을 반환하되 실제 adapter/model load가 호출되지 않음을 monkeypatch로 확인
- 일부 모델만 설정된 경우 partial availability 정책을 별도 테스트로 고정

## 7. GitHub/PR 참고 결과

읽기 전용으로만 확인했다.

### 7.1 로컬 git 상태

- repo root: `/home/ddobagi/Code/hanium-dreamup`
- 현재 branch: `codex/walksafe-v3-relabel-task-package`
- HEAD: `cf4a67e Add missing WalkSafe v3 crack label`
- remote:
  - `origin https://github.com/ddobagih/Hanium_Dreamup.git (fetch)`
  - `origin https://github.com/ddobagih/Hanium_Dreamup.git (push)`
- 작업 전 `git status --short`에는 다른 워커의 것으로 보이는 수정/미추적 파일이 다수 있었다. 본 작업에서는 되돌리거나 정리하지 않았다.

### 7.2 `gh pr status`

`gh pr status` 실행 성공.

- current branch PR: `#2 [codex] Add WalkSafe v3 relabel task package`
- created by you: `#2 [codex] Add WalkSafe v3 relabel task package`
- review requested from you: 없음

### 7.3 `gh pr view 2 --json ...`

`gh pr view 2` 실행 성공.

- PR: `#2`
- title: `[codex] Add WalkSafe v3 relabel task package`
- state: `OPEN`
- draft: `true`
- base: `main`
- head: `codex/walksafe-v3-relabel-task-package`
- author: `ddobagih`
- url: `https://github.com/ddobagih/Hanium_Dreamup/pull/2`
- createdAt: `2026-05-21T02:30:16Z`
- updatedAt: `2026-05-21T03:22:50Z`
- mergeStateStatus: `UNKNOWN`
- reviewDecision: 빈 값

## 8. 결론 및 backend 연결 전 차단 조건

backend에 바로 two-model 결과를 연결하는 것은 위험하다. 가장 큰 이유는 v1 `ReportMetadata`가 기존 4-class id/name validator에 고정되어 있고, detector/config/tests가 이 계약을 강하게 기대하기 때문이다.

연결 전 최소 차단 조건:

1. v2 schema를 v1과 분리해 추가한다.
2. 기존 `/detect`, `/detect/health`, `/reports`, `ReportMetadata`는 유지한다.
3. `two_model_runtime` helper를 fake detection으로 충분히 검증한다.
4. DB 저장은 v2 raw JSON 우선 또는 별도 테이블/필드를 결정한 뒤 진행한다.
5. no-model health와 backward compatibility test를 먼저 고정한다.
6. YOLO26s 학습 중에는 실제 GPU 추론/평가를 실행하지 않는다.
