# WalkSafe 모델 개발

이 폴더는 WalkSafe 모델 후보의 registry, Android·Backend 개발 runtime 결속, 변환·검증 helper를 관리합니다. 모델의 승인·평가 정본은 이 README가 아니라 [AIML model register](../docs/deliverables/08-ai-ml-data/registers/model-register.json)와 [local registry](registry/walksafe-model-registry.json)입니다.

## 현재 runtime

| 역할 | 경로 | 상태 |
|---|---|---|
| Android primary | [`walksafe_unified_yolo26n_768_float32.tflite`](../apps/android/app/src/main/assets/models/walksafe_unified_yolo26n_768_float32.tflite) | unified 13-class, 768 입력, 개발 후보 활성 |
| primary source 후보 | [`walksafe_13cls_yolo26n_img768_best_epoch270.pt`](artifacts/candidates/walksafe_13cls_yolo26n_img768_20260708/walksafe_13cls_yolo26n_img768_best_epoch270.pt) | `CANDIDATE`, 배포 승인 안 됨 |
| legacy tactile fallback | [`custom_tactile_yolo26s_float32.tflite`](../apps/android/app/src/main/assets/models/custom_tactile_yolo26s_float32.tflite) | fallback component, source provenance 미완료 |
| legacy COCO fallback | [`coco_yolo26n_float32.tflite`](../apps/android/app/src/main/assets/models/coco_yolo26n_float32.tflite) | fallback component, source provenance 미완료 |

Android runtime source of truth는 [`two_model_runtime.json`](../apps/android/app/src/main/assets/model-config/two_model_runtime.json)입니다. `primary_model=unified_walksafe`이며 primary asset 누락·hash/tensor/load 실패 또는 제한 횟수의 반복 invoke 실패가 발생하고 config에 완전한 legacy pair가 있을 때 legacy fallback으로 전환합니다.

현재 model register 기준으로 다음 항목은 `NOT_RUN` 또는 미검증입니다.

- 독립 test dataset 평가
- dataset content hash와 sequence-safe split
- TFLite 변환 동등성의 정식 평가
- Android 실제 기기 성능·안전 시험
- 데이터 권리·개인정보 정식 검토
- 모델 승인과 release gate

따라서 개발 runtime에서 모델을 읽거나 instrumentation smoke가 통과해도 정식 모델 승인이나 출시 적격을 뜻하지 않습니다.

## 13-class 순서

class ID는 아래 순서를 사용합니다. 다른 모델의 class ID와 전역 번호처럼 섞지 않습니다.

```text
0 person
1 bicycle
2 car
3 motorcycle
4 bus
5 truck
6 traffic light
7 normal_tactile_block
8 damaged_tactile_block
9 crosswalk
10 curb_step
11 uneven_sidewalk
12 e_scooter_obstruction
```

정확한 class order, threshold, tensor shape와 artifact hash는 runtime config와 model register를 기준으로 확인합니다.

## 폴더 책임

| 경로 | 역할 |
|---|---|
| [`registry/`](registry/) | 로컬 모델 후보·artifact·metric·blocker 결속 |
| [`deployments/`](deployments/) | Android·Backend 개발 runtime의 active candidate 기록 |
| [`artifacts/candidates/`](artifacts/candidates/) | 검증 가능한 기존 모델 후보 예외 |
| [`two_model_runtime.py`](two_model_runtime.py) | 이미 생성된 detection payload의 CPU-only 필터·fallback helper |
| [`train_yolo.py`](train_yolo.py) | 경로를 명시해 실행하는 학습 wrapper |
| [`requirements.txt`](requirements.txt) | 모델 도구 의존성 입력. 설치 전 중앙 개발환경 가이드 확인 |

## 검증

runtime helper의 저장소 내부 단위검사:

```bash
python3 -m pytest model/test_two_model_runtime.py -q
```

로컬 registry hash와 참조 검사:

```bash
python3 scripts/manage_local_model_registry.py verify
```

현재 `verify`는 registry가 요구하는 materialized dataset manifest와 학습 `results.csv`가 저장소에 없어 의도적으로 두 누락을 보고하고 종료 코드 1을 반환한다. 설치 오류와 구분하려면 저장소에 있는 runtime artifact·config의 정적 결속만 확인하는 다음 명령을 사용한다.

```bash
python3 scripts/manage_local_model_registry.py verify-runtime
```

두 누락의 의미와 후속 검증 경계는 [데이터·AI 가이드](../docs/guides/data-ai-guide.md#현재-provenance-상태)를 따른다.

학습·export·dataset builder를 실행하기 전에는 [데이터·AI 가이드](../docs/guides/data-ai-guide.md)와 [데이터 스크립트 가이드](../data_sources/scripts/README.md)를 읽고 `--help`, `--dry-run` 또는 `--print-only`를 먼저 사용합니다. 대용량 dataset이나 운영 runtime을 이 명령만으로 변경하지 않습니다.

## Git 저장 예외와 금지

현재 추적 중인 primary source PT 1개와 Android TFLite 3개는 config·register·hash에 결속된 기존 예외입니다. 이 예외는 새 weight를 임의로 추가하는 정책이 아닙니다.

다음은 기본적으로 로컬 또는 승인된 artifact 저장소에 둡니다.

- 원본·materialized dataset과 review image pack
- `runs/`, 임시 export, checkpoint 묶음
- 새 `.pt`, `.onnx`, `.engine`, `.tflite`
- 사용자 영상·음성·정확 위치와 권리 검토 전 원본

새 모델 artifact가 꼭 필요하면 source·license·dataset·도구 버전·hash·평가·rollback을 먼저 기록하고 리뷰를 받아야 합니다.
