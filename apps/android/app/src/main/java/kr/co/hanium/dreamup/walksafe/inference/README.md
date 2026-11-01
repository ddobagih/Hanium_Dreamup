# Android Inference

이 패키지는 ARCore camera `Image`를 TFLite 입력으로 바꾸고 YOLO end-to-end 출력을 공통 `DetectionCandidate`로 변환한다.

## Runtime 계약

- `TwoModelRuntimeConfig`가 asset 경로, input size, class order, allowlist, threshold와 delegate의 source of truth다.
- `unified_walksafe` 768 asset의 SHA-256과 input/output tensor 계약이 맞으면 단일 모델을 사용한다.
- unified asset이 없거나 무결성·tensor 검증에 실패하고 fallback이 허용되면 custom tactile + COCO 두 모델을 함께 로드한다.
- legacy 모드에서는 COCO partial 결과를 먼저 publish하고 custom 결과가 끝나면 합친 결과를 publish한다.
- delegate 실행이 실패할 때 config가 허용한 경우에만 CPU interpreter를 다시 만든다.

현재 config의 primary는 13-class 768 float32 unified asset이다. 원본 PT와 TFLite 해시, export 버전, `[1,768,768,3] -> [1,300,6]` 계약을 config와 JVM test에 고정한다.

## 기기점검의 샘플 기반 실행 선택

`composite/`는 내장 테스트 이미지4장으로 기본 객체 인식과 FastSAM의 CPU/GPU·처리 간격 조합을 측정합니다. 사용자 카메라나 ARCore 세션을 만들지 않습니다. native 준비가 반환하지 않는 경우를 앱 본체에서 분리하기 위해 별도 `:fixture_runtime` Service를 사용하며, 새 후보는 이전 worker의 실제 종료 확인 후 시작합니다.

`JointFixtureRuntimeEvidence`는 샘플 RGB 제출부터 전처리·추론·후처리까지의 관측이며 카메라·거리·UI 실측 증거가 아닙니다. 선택 기록은 schema2의 `SAMPLE_IMAGES_TWO_MODEL`로 저장합니다. `AndroidJointRuntimeLoader`는 선택된 실제 실행값과 예제 추론을 확인하고, native 정리 실패가 예외 또는 결과 reason으로 반환되면 초기 CPU 준비도 함께 차단합니다. 실제 카메라·보행의 source/geometry/fresh depth gate는 각 runtime 소유자에 남습니다.
