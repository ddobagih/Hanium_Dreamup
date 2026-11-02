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

`composite/`는 내장 이미지4장으로 기본 객체 인식과 FastSAM의 실행 조합을 측정합니다. 사용자 카메라나 ARCore 촬영 프레임을 요구하지 않습니다. 운영 점검은 `JointRuntimeSampleHost`의 운영 proxy와 비공개 `:vision_runtime`을 사용하며, `:fixture_runtime`은 명시적 debug 레거시 진단에만 남습니다.

GPU 선행 잠정 측정 뒤 CPU1 참조를 확보하고, 최종 실제 pair에 이미지4장×역할2개를 엄격히 비교합니다. 같은 native 인스턴스의 공동 측정까지 통과해야 선택을 검증하며, 다음 후보는 이전 consumer·실제 모델 회수 확인 후에 시작합니다. 샘플 결과는 실제 카메라·거리·화면 성능의 보장이 아닙니다.

저장·현재 pair 증명·회수 정산·재점검 권한과 원인 추적 로그는 [Android 코드 지도](../../../../../../../../../../../../docs/guides/code/android-user.md)의 점검 절에서 확인합니다. 실제 카메라·보행의 source/geometry/fresh depth gate는 각 runtime 소유자에 남습니다.
