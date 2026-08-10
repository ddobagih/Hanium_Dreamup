# Local deployment state

`local-deployment.json`은 backend와 Android 개발 runtime에 적용한 모델을 기록하는 local control-plane 문서다. 현재 두 target의 active model은 `walksafe-13cls-yolo26n-img768-epoch270-20260708`이다.

- backend: canonical PT SHA-256 `a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669`, `real`/`img768`
- Android: unified float32 TFLite SHA-256 `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19`, 입력 `[1,768,768,3]`, 출력 `[1,300,6]`, 13 classes

이 레코드는 개발 runtime 연결 근거이며 실제 프로세스가 새 코드를 serving 중이라는 근거나 Release 승인 근거가 아니다. 두 target 모두 `development_candidate_active`이고 `release_eligible=false`다. Android SM-G981N instrumentation model load/invoke 1/1은 통과했지만 대화형 camera pipeline·fallback 미발생·FPS와 실외 Device `FIELD`는 미검증이다.
