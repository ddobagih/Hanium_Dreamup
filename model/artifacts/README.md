# Model artifacts

모델 weight/export 파일은 루트가 아니라 이 폴더 아래에 둔다.

| 폴더 | 내용 |
| --- | --- |
| `pretrained/` | YOLO pretrained/base helper 모델과 export 후보 |
| `exports/` | 모델 export 산출물 디렉터리. 현재 `yolo26n_saved_model/`은 80-class COCO pretrained export다. |
| `candidates/` | 평가 리포트에서 선별한 13-class 모델 후보 weight |

학습 결과 전체는 기존대로 `runs/`에 둔다. Android asset으로 투입된 파일은 `apps/android/app/src/main/assets/` 기준 문서를 따른다.

2026-07-08 평가 후보는 `candidates/walksafe_13cls_yolo26n_img768_20260708/`에 있다. 현재 backend 개발 runtime의 canonical PT는 `walksafe_13cls_yolo26n_img768_best_epoch270.pt`이며 SHA-256은 `a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669`다. 이 PT에서 export한 Android용 13-class TFLite는 `apps/android/app/src/main/assets/models/walksafe_unified_yolo26n_768_float32.tflite`에 있고 SHA-256은 `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19`다.

TFLite 계약은 float32 입력 `[1,768,768,3]`, 출력 `[1,300,6]`, 13개 class다. backend와 Android 개발 runtime에 후보를 연결한 사실은 `deployment_eligible=true` 또는 실기기·실외 검증 완료를 뜻하지 않는다.
