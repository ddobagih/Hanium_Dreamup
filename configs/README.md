# Backend 모델 runtime 설정

이 폴더는 Python/backend detection helper가 읽는 모델 클래스·threshold 계약을 보관합니다. 모델 weight나 비밀값은 넣지 않습니다.

| 파일 | 상태와 용도 |
| --- | --- |
| `walksafe_unified_epoch270_field_20260711.json` | 현재 backend field/development runtime의 canonical 설정. epoch270 PT SHA-256, img768, 13-class unified primary, fallback `null`을 고정 |
| `walksafe_two_model_runtime_stage1_mvp_20260523.json` | 과거 Stage1/호환 설정. unified와 legacy threshold 비교용이며 현재 field profile이 아님 |
| `walksafe_two_model_runtime_20260522.yaml` | `model/two_model_runtime.py` helper와 unit test가 사용하는 legacy/base 설정 |

Android source of truth는 별도 파일인 `apps/android/app/src/main/assets/model-config/two_model_runtime.json`이며 unified 768 TFLite를 expected primary로 사용한다. asset SHA-256은 `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19`, 입력은 `[1,768,768,3]`, 출력은 `[1,300,6]`, class 수는 13이다. legacy pair는 unified load·hash·tensor 실패 fallback으로만 남긴다. backend와 Android의 threshold는 목적과 검증 이력이 다르므로 자동으로 같다고 가정하지 않는다. 정적 계약과 SM-G981N instrumentation 실제 load/invoke 1/1은 통과했지만 대화형 camera pipeline·fallback 미발생·FPS, 야외 보행, Release 승인을 증명하지 않는다.
