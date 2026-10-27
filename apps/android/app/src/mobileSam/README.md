# WalkMate MobileSAM 시험 v1.1

기존 앱 옆에 별도로 설치하는 사진 기반 비교 버전이다.

- 빌드 형식: `mobileSam`
- 패키지: `kr.co.hanium.dreamup.walksafe.mobilesam`
- 버전: `0.1.0-mobilesam-v1.1`
- 실행 화면: `MobileSamTestActivity`
- 모델: 기존 앱의 21클래스 YOLO TFLite + MobileSAM vit_t encoder/box decoder ONNX
- 런타임: 기존 YOLO CPU 2스레드 + ONNX Runtime Android 1.30.0 CPU 2스레드

## 사용

1. 앱을 열고 사진을 불러오거나 촬영한다. 최초 예제는 공개 사진이다.
2. **YOLO → MobileSAM**을 누른다. 기본값은 점자블록 4클래스만 분할한다.
3. **점자블록만 분할**을 해제하면 전체 탐지 중 신뢰도가 높은 최대 3개를 분할한다.
4. **마스크 표시**를 끄고 켜면서 동일한 사진의 YOLO 상자와 분할 결과를 비교한다.
5. YOLO가 놓친 대상은 **영역 직접 지정** 후 드래그해서 분할만 검사한다. 이 결과는 `수동 영역`으로 표시하며 객체 이름을 추정하지 않는다.

v1.1에서는 **예제 사진** 버튼에서 점자블록·횡단보도 / 어두운 점자블록 / 차량 진출입로를 선택할 수 있다. 화면에는 분할 대상으로 선택한 최대 3개 YOLO 상자만 표시하고, 전체 탐지 개수는 아래 결과에 표시한다. MobileSAM 모델을 처음 불러왔을 때만 시간에 모델 로드가 포함됐다고 표시한다.

MobileSAM은 YOLO가 놓친 점자블록을 이 자동 흐름에서 새로 찾아내지 않는다. 예제에서 점자블록 탐지가 0개일 수 있으며, 이는 정상적인 빈 결과로 표시한다. 초록 상자는 YOLO 탐지이며 색칠한 픽셀만 MobileSAM 출력이다. 최대 3개를 고른 뒤 사진당 인코더를 한 번 실행하고 각 상자를 디코딩한다.

이 버전에는 실시간 영상 추적, 자동 인도/차도 의미 분류, 깊이·단차 측정, 보행 가능 판정, 음성 길 안내 연결이 없다. 화면 시간은 현재 기기의 사진 처리 시간이며 첫 실행의 MobileSAM 시간에는 모델 로드도 포함된다. UI의 YOLO 시간은 모델 로드 이후부터 측정한다. 사진은 로컬에서 처리한다.

## 빌드와 검사

`apps/android`에서 실행한다. Android SDK와 Java 21이 필요하다.

```sh
./gradlew :app:assembleMobileSam
./gradlew :app:testMobileSamUnitTest --tests '*MobileSamGeometryTest' -PmobileSamDeviceTests=true
./gradlew :app:connectedMobileSamAndroidTest -PmobileSamDeviceTests=true -Pandroid.testInstrumentationRunnerArguments.class=kr.co.hanium.dreamup.walksafe.mobilesam.MobileSamDeviceTest
```

고정 보행 장면의 반복 검사는 `MobileSamReviewTest`를 지정한다. `android.testInstrumentationRunnerArguments.reviewRound=1` 또는 `2`를 전달하면 앱 내부 `files/mobilesam-review/round-1` 또는 `round-2`에 수치와 실제 마스크를 저장한다. 대상 상자는 `src/androidTestMobileSam/assets/mobilesam_review_cases.json`에 사전 고정돼 있으며 정답 마스크가 아니다.

최초 전체 컴파일에서 메모리가 부족하면 위 명령에 `-Pkotlin.daemon.jvmargs=-Xmx3g -Dorg.gradle.jvmargs=-Xmx2g --max-workers=2`를 붙인다.

APK: `app/build/outputs/apk/mobileSam/app-mobileSam.apk`.

`mobileSamDeviceTests=true`는 검사 대상을 이 빌드로 전환한다. 기본 debug 검사 설정은 그대로 둔다. ONNX Runtime 및 ONNX 모델은 `mobileSam` 빌드에만 포함된다. 공통 MainActivity가 참조하는 디버그 구현은 기존 `src/debug/java`를 재사용한다.

기기 검사는 실제 모델 로드, 데스크톱 기준 마스크와의 IoU ≥ 0.98, YOLO → MobileSAM 자동 연결, 터치로 지정한 수동 영역, 화면 오버레이를 검사한다. 기준 마스크는 분할 정답이 아니라 변환·좌표 정합 확인용이다. 에뮬레이터 수치는 휴대폰 지연 시간이나 현장 정확도가 아니다.

## 모델 파일 재생성

대용량 ONNX와 검사 기준 `.bin` 파일은 저장소의 기존 바이너리 제외 규칙을 따른다. 로컬 APK에는 모델이 들어 있다. 다른 컴퓨터에서 재현하려면 저장소 루트에서 다음과 같이 내보낸다.

```sh
python scripts/export_mobilesam_test.py \
  --checkpoint /path/to/mobile_sam.pt \
  --output apps/android/app/src/mobileSam/assets/models/mobilesam \
  --images apps/android/app/src/mobileSam/assets/mobilesam-sample/japan_crosswalk.jpg /path/to/driveway.jpg \
  --reference-output apps/android/app/src/androidTestMobileSam/assets
```

사용한 Python 패키지: Python 3.13, torch 2.10.0, ultralytics 8.4.163, onnx 1.23.0, onnxruntime 1.30.0, opencv-python 5.0.0.93, numpy 2.5.3. 체크포인트 SHA-256 및 ONNX 파일 해시는 `assets/models/mobilesam/mobile_sam_manifest.json`에 기록되어 있다. 앱은 설치된 asset을 복사하면서 해당 SHA-256을 검증한다.

전처리는 비교 실험에 사용한 Ultralytics MobileSAM과 맞춘다. 긴 변 1024, RGB, 우측/하단 114 패딩, mean/std 정규화, CHW 입력을 사용한다. 원본 좌표로 상자를 비례 변환하고, 마스크 logit의 패딩을 제거한 다음 원본 크기로 보간하고 마지막에 0으로 이진화한다. 앱의 기존 RGB 리사이즈 코드를 재사용한다.

출처: [MobileSAM](https://github.com/ChaoningZhang/MobileSAM), [Ultralytics SAM 구현](https://github.com/ultralytics/ultralytics/tree/main/ultralytics/models/sam), [ONNX Runtime Java](https://onnxruntime.ai/docs/get-started/with-java.html). 예제 사진의 작가·라이선스·수정 내용은 `assets/mobilesam-sample/ATTRIBUTION.txt`와 앱 내 출처 안내에 있다.
