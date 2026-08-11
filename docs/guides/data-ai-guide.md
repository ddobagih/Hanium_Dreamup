# 데이터·AI 가이드

이 문서는 데이터·모델 작업의 안전한 진입점을 설명한다. class, threshold, hash, 승인 상태의 정본을 복제하지 않으며 값이 충돌하면 연결된 runtime config, registry와 정식 산출물을 따른다.

## 현재 runtime 경계

Android 개발 runtime의 primary는 **unified 13-class, img768, float32 TFLite**다. 정확한 asset, class 순서, threshold, tensor와 hash 계약은 [Android runtime config](../../apps/android/app/src/main/assets/model-config/two_model_runtime.json)가 정본이다. Backend 개발 runtime은 [Backend runtime 설정](../../configs/walksafe_unified_epoch270_field_20260711.json)을 별도로 사용한다.

현재 Git이 추적하는 모델 binary는 `.gitignore`에 파일별로 명시한 **PT 1개와 TFLite 3개 추적 예외**뿐이다. 이 예외는 저장소 반입 허용 범위이지 모델의 평가·출시 승인이 아니다.

| 역할 | 추적 파일 | 현재 경계 |
|---|---|---|
| Backend 개발 후보 | [epoch270 PT](../../model/artifacts/candidates/walksafe_13cls_yolo26n_img768_20260708/walksafe_13cls_yolo26n_img768_best_epoch270.pt) | unified 13-class 후보. 출시 승인 모델이 아님 |
| Android primary | [unified img768 TFLite](../../apps/android/app/src/main/assets/models/walksafe_unified_yolo26n_768_float32.tflite) | 현행 개발 runtime primary |
| Android fallback | [custom tactile TFLite](../../apps/android/app/src/main/assets/models/custom_tactile_yolo26s_float32.tflite) | primary 손상·불일치 때 쓰는 legacy fallback component |
| Android fallback | [COCO TFLite](../../apps/android/app/src/main/assets/models/coco_yolo26n_float32.tflite) | primary 손상·불일치 때 쓰는 legacy fallback component |

이 네 파일의 추적은 루트 [.gitignore](../../.gitignore)에 좁은 예외로 명시돼 있다. 추가 binary가 필요하다는 이유만으로 `git add -f`를 사용하거나 예외 범위를 넓히지 않는다. 후보와 배치 연결 상태는 [모델 registry](../../model/registry/walksafe-model-registry.json)와 [local deployment](../../model/deployments/local-deployment.json)에서 확인한다.

## 정본과 역할

| 대상 | 정본·안내 |
|---|---|
| Android 모델 선택·tensor·class·threshold | [Android runtime config](../../apps/android/app/src/main/assets/model-config/two_model_runtime.json) |
| Backend 모델 선택·class·threshold | [Backend runtime 설정](../../configs/walksafe_unified_epoch270_field_20260711.json) |
| 후보 artifact·학습·평가 metadata | [모델 registry](../../model/registry/walksafe-model-registry.json) |
| 개발 배치 대상과 generation | [local deployment](../../model/deployments/local-deployment.json) |
| 모델 코드·검증 진입점 | [모델 README](../../model/README.md), [artifact README](../../model/artifacts/README.md) |
| 데이터 변환·후보 provenance | [데이터 소스 README](../../data_sources/README.md), [data-source register](../deliverables/08-ai-ml-data/registers/data-source-register.json) |
| 데이터셋 재현성·gate 상태 | [dataset register](../deliverables/08-ai-ml-data/registers/dataset-register.json) |
| 정식 평가·운영 계획과 결과 경계 | [모델 평가 산출물](../deliverables/08-ai-ml-data/model-evaluation.md), [모델 운영 산출물](../deliverables/08-ai-ml-data/model-operations.md) |

README의 설명은 작업 안내다. runtime 값은 config, 파일 지문과 후보 상태는 registry, 정식 승인·시험 상태는 `docs/deliverables/08-ai-ml-data/`가 판단한다.

## 현재 provenance 상태

후보 PT와 Android primary TFLite의 source/export hash는 registry와 runtime config에 결속돼 있다. 그러나 registry가 참조하는 materialized dataset manifest와 학습 `results.csv`는 현재 저장소에 없으며, registry 검사는 두 경로를 누락으로 보고한다. [dataset register](../deliverables/08-ai-ml-data/registers/dataset-register.json)에 등록된 후보의 현재 상태는 `CANDIDATE_REVALIDATION_REQUIRED`, `manifest_exists=false`다.

따라서 선언된 데이터 수량·metric을 현재 저장소에서 재현한 값으로 취급하지 않는다. source 권리·privacy 검토, immutable manifest, 독립 test split도 닫히지 않았다. 누락 자료를 빈 파일이나 새 hash로 대신 만들지 말고, 권한 있는 외부 원자료가 확보됐을 때 provenance와 함께 다시 검증한다.

## 반입 금지와 추적 예외

다음 자료는 신규로 Git에 반입하지 않는다.

- 원본·가공 이미지, 영상, 음성, 정확 위치, 사용자 신고 원본 등 개인정보·민감 가능 자료
- AIHub 원본 zip, materialize된 대형 데이터셋, review 이미지팩
- `runs/`, 임시 weight, export 디렉터리, calibration 자료와 대형 중간 산출물
- 출처·권리·privacy·동의 상태를 증명하지 못한 데이터 또는 fallback 모델
- API key, token, 계정, 개인 식별정보가 포함된 manifest·로그·notebook

대형·민감 자료는 승인된 Git 외부 저장 경계에 두고 저장 위치 자체에 접근권한·보존·삭제 정책을 적용한다. 저장소에는 필요한 최소 metadata, content hash, 권리·privacy 상태와 외부 보관 식별자만 남긴다. 실제 원본 경로나 비밀값을 문서에 적지 않는다.

새 모델 binary를 추적해야 한다면 기존 네 파일의 선례를 자동 승인으로 해석하지 않는다. 목적, 크기, 대체 저장 수단, 라이선스·재배포 권리, 개인정보 영향, source와 변환 provenance, content hash, 소비 runtime, rollback과 제거 조건을 검토한 뒤 **파일 하나 단위의 명시적 예외**만 추가한다.

## provenance 최소 항목

데이터나 모델을 후보로 등록할 때 다음을 연결한다.

1. source ID, 제공자, 공식 위치, 취득 시점과 취득 방법
2. license·학습·재배포 권리, privacy·동의·철회 적용성의 검토 상태
3. 원본 content hash·크기와 Git 외부 보관 식별자
4. 변환 코드 commit, 실행 명령·환경, 입력·출력 manifest와 실패·제외 수량
5. dataset class, immutable split, capture-group 누수 검사와 독립 test 분리
6. 학습 코드·환경·seed·config, dataset hash, 결과 원자료와 선택 기준
7. export 도구 버전, source model hash, 출력 hash, class·tensor·전처리 계약
8. registry 상태, runtime generation, 소비 앱·서버 commit과 rollback 후보

자동 제안 라벨과 사람 승인 라벨을 구분한다. 선언된 수량이나 과거 metric은 현재 저장소에서 재계산되지 않았다면 검증 완료로 기록하지 않는다.

## 작업 흐름

```text
source 후보 등록
  -> 권리·privacy·동의 gate
  -> immutable manifest와 품질·split·누수 검사
  -> 고정 config로 학습
  -> 후보 artifact·결과를 registry에 등록
  -> TFLite export와 source↔export binding
  -> host smoke·계약 회귀
  -> 독립 평가·동등성·실기기 시험
  -> 승인된 generation만 release 심사
```

선행 gate가 닫히지 않으면 다음 단계의 실행이나 승격을 완료로 표시하지 않는다. registry의 `development_candidate_active`는 개발 runtime 연결 상태일 뿐 승인·배포 적격 상태가 아니다.

## 변경 체크리스트

모델, class, threshold 또는 asset을 바꿀 때는 최소한 다음을 함께 확인한다.

- Android와 Backend 중 어느 runtime이 바뀌는지 분리한다.
- source PT, export TFLite, runtime config, class map과 hash가 같은 generation에 결속되는지 확인한다.
- Android asset loader의 hash·입출력 tensor 검사와 fallback 동작을 회귀한다.
- Backend readiness·warmup과 OpenAPI consumer 영향이 있는지 확인한다.
- registry와 deployment metadata를 실제 실행 결과에 맞춰 갱신한다.
- dataset·split·전처리 변경이면 기존 metric을 새 후보의 결과로 재사용하지 않는다.
- 새 binary나 민감 자료가 staging되지 않았는지 확인한다.

추적 binary 목록은 다음처럼 확인할 수 있다.

```bash
git ls-files '*.pt' '*.tflite'
git ls-files -z '*.pt' '*.tflite' | xargs -0 sha256sum
python scripts/manage_local_model_registry.py verify
```

현재 registry 검사는 위에서 설명한 dataset manifest와 학습 결과 누락을 보고하는 것이 정상이며, 이를 PASS로 바꿀 목적으로 증거를 조작하지 않는다. 이후 검사 PASS가 되더라도 해당 hash·구조 범위만 증명한다. 현재 후보의 독립 test 평가, PT↔TFLite 정식 동등성, 실제 기기 성능·발열·야외 안전성, 배포·운영 검증은 `NOT_RUN`이며 출시는 `NOT_ELIGIBLE`이다. 이 경계는 [모델 평가 산출물](../deliverables/08-ai-ml-data/model-evaluation.md)과 [모델 운영 산출물](../deliverables/08-ai-ml-data/model-operations.md)에서 확인한다.
