# Android Inference

이 패키지는 ARCore camera `Image`를 TFLite 입력으로 바꾸고 YOLO end-to-end 출력을 공통 `DetectionCandidate`로 변환한다.

## Runtime 계약

- `TwoModelRuntimeConfig`가 asset 경로, input size, class order, allowlist, threshold와 delegate의 source of truth다.
- `unified_walksafe` 768 asset의 SHA-256과 input/output tensor 계약이 맞으면 단일 모델을 사용한다.
- unified asset이 없거나 무결성·tensor 검증에 실패하고 fallback이 허용되면 custom tactile + COCO 두 모델을 함께 로드한다.
- legacy 모드에서는 COCO partial 결과를 먼저 publish하고 custom 결과가 끝나면 합친 결과를 publish한다.
- delegate 실행이 실패할 때 config가 허용한 경우에만 CPU interpreter를 다시 만든다.

현재 config의 primary는 13-class 768 float32 unified asset이다. 원본 PT와 TFLite 해시, export 버전, `[1,768,768,3] -> [1,300,6]` 계약을 config와 JVM test에 고정한다.
