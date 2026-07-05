# Android ARCore 좌표 매핑 P1 설계 계획

## 2026-06-02 현재 상태

- 이 문서는 P1 설계/검증 기준으로 유지한다.
- Android 앱에는 ARCore `IMAGE_PIXELS -> VIEW` overlay mapper와 `IMAGE_PIXELS -> TEXTURE_NORMALIZED` depth mapper 우선 경로가 추가됐다.
- 단, 실기기 bbox/depth/`N보` 정합은 아직 PASS가 아니므로 아래의 identity 제거/검증 항목은 fallback 감시와 Device gate 기준으로 남긴다.

## 배경

현재 Android 앱에는 detector의 normalized bbox를 preview view 좌표와 depth image 좌표에 그대로 대응시키는 identity 가정이 남아 있다. 이 가정은 다음 조건이 모두 맞을 때만 안전하다.

- detector 입력 이미지, ARCore camera image, preview display, depth image가 같은 종횡비와 crop 기준을 공유한다.
- display rotation, camera sensor orientation, mirror 여부가 이미 같은 기준으로 보정되어 있다.
- depth image 해상도와 camera image 해상도 차이가 단순 비율 스케일만으로 해결된다.

실제 ARCore 경로에서는 preview crop, device rotation, camera texture transform, depth image 해상도 차이 때문에 bbox overlay와 depth sampling 위치가 서로 다르게 어긋날 수 있다. P1의 목표는 이 identity 가정을 명시적으로 제거할 수 있도록 overlay mapper와 depth mapper를 분리해 설계하는 것이다.

## 목표

1. detector normalized bbox를 화면 표시용 view 좌표로 변환하는 overlay mapper를 둔다.
2. detector normalized bbox 또는 representative point를 depth image pixel 좌표로 변환하는 depth mapper를 별도로 둔다.
3. 두 mapper가 같은 입력 bbox를 받더라도 서로 다른 ARCore transform 경로와 출력 좌표계를 가질 수 있음을 코드 구조에 반영한다.
4. 실기기 없이 검증 가능한 순수 좌표/계약 테스트와 실기기 필수 검증을 분리한다.
5. 이미지/깊이 파일 저장 없이 metadata-only log로 좌표 정합성 판단에 필요한 필드만 남길 수 있게 한다.

## 비목표

- detector 모델, Kotlin 추론 경로, ARCore 세션 생명주기 자체를 이 문서에서 변경하지 않는다.
- 이미지 파일, camera frame, depth image, confidence image를 저장하거나 export하지 않는다.
- 별도 승인 전에는 로컬/외부 저장소로 frame dump, screenshot dump, depth raw dump를 만들지 않는다.

## 좌표계 분리

### overlay mapper

`overlay mapper`는 detector normalized bbox를 `DebugBboxOverlayView`가 그릴 수 있는 view pixel 좌표로 바꾼다.

입력 후보:

- detector normalized bbox: `[0.0, 1.0]` 범위의 left/top/right/bottom
- detector input size와 letterbox/crop metadata
- preview view width/height
- display rotation
- ARCore camera/display transform metadata

출력 후보:

- view pixel bbox
- view pixel center point
- clipping 여부
- transform path 이름과 version

책임:

- preview에 보이는 실제 객체 위치와 bbox가 맞는지 검증 가능한 좌표를 만든다.
- depth image 해상도나 depth validity는 판단하지 않는다.

### depth mapper

`depth mapper`는 detector normalized bbox를 ARCore depth image pixel 좌표로 바꾼다.

입력 후보:

- detector normalized bbox 또는 bbox 내부 sample point
- detector input size와 letterbox/crop metadata
- camera image 기준 좌표
- depth image width/height
- ARCore depth image 좌표 변환 metadata

출력 후보:

- depth image pixel bbox 또는 sample polygon
- depth image pixel center point
- depth image bounds clipping 여부
- transform path 이름과 version

책임:

- depth sampler가 올바른 depth pixel을 읽도록 좌표를 제공한다.
- 화면 overlay 위치가 맞는지 직접 판단하지 않는다.

## ARCore transformCoordinates2d 확인 결과

2026-06-01 KST 기준 현재 Android 의존성은 `com.google.ar:core:1.54.0`이다.

로컬 Gradle cache의 ARCore 1.54.0 AAR에서 `javap`로 확인한 API:

```text
Frame.transformCoordinates2d(Coordinates2d, FloatBuffer, Coordinates2d, FloatBuffer)
Frame.transformCoordinates2d(Coordinates2d, float[], Coordinates2d, float[])
```

확인된 `Coordinates2d` enum:

```text
TEXTURE_TEXELS
TEXTURE_NORMALIZED
IMAGE_PIXELS
IMAGE_NORMALIZED
OPENGL_NORMALIZED_DEVICE_COORDINATES
VIEW
VIEW_NORMALIZED
```

현재 codebase에서도 preview background는 이미 ARCore transform을 사용한다.

```text
apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/CameraBackgroundRenderer.kt
OPENGL_NORMALIZED_DEVICE_COORDINATES -> TEXTURE_NORMALIZED
```

`VIEW`/`VIEW_NORMALIZED` 변환은 최근 `Session.setDisplayGeometry(...)` 값에 의존한다. 현재 `MainActivity`에는 display geometry update 호출이 있으므로 mapper 구현 시 frame update, display rotation, view size 갱신 순서를 같이 확인해야 한다.

## overlay/depth 변환 후보 경로

후보 경로:

1. detector normalized bbox
   -> camera image pixel 좌표
   -> `Frame.transformCoordinates2d(IMAGE_PIXELS, ..., VIEW, ...)`
   -> view pixel 좌표
   -> overlay mapper 출력

2. detector normalized bbox
   -> camera image pixel 좌표
   -> `Frame.transformCoordinates2d(IMAGE_PIXELS, ..., TEXTURE_NORMALIZED, ...)`
   -> `textureNormalized * depthImage.width/height`
   -> depth image pixel 좌표
   -> depth mapper 출력

depth 관련 주의:

- `Coordinates2d`에는 `DEPTH_*` target enum이 없다.
- depth mapper는 `TEXTURE_NORMALIZED`를 depth image normalized 좌표로 쓰는 ARCore depth guide 계열 경로를 따른다.
- 변환 결과가 음수이거나 `[0, 1]` 밖이면 cropped/no-depth 영역으로 처리해야 한다.
- Raw depth confidence image는 depth image와 같은 크기다.
- 모든 ARCore frame마다 새 raw depth가 있는 것은 아니므로 timestamp와 availability를 metadata에 남긴다.

남은 확인 항목:

- detector 결과가 async라서 현재 `Frame`과 timestamp가 다를 때 좌표 오차가 얼마나 생기는지 실기기에서 확인한다.
- `IMAGE_PIXELS -> VIEW`와 `IMAGE_PIXELS -> TEXTURE_NORMALIZED` 변환을 bbox 네 corner에 적용한 뒤 corner ordering/clipping을 어떻게 정리할지 테스트한다.
- mapper 적용 후 overlay draw와 depth sampling latency가 frame drop을 재발시키지 않는지 확인한다.

## legacy/identity fallback 감시 위치

| 영역 | 파일/위치 | fallback/감시 동작 | P1 조치 |
|---|---|---|---|
| overlay | `DebugBboxOverlayView.kt` `onDraw()` | ARCore view mapper 출력이 없으면 단순 scaling path가 fallback으로 남을 수 있음 | metadata의 transform path로 fallback 여부 노출 |
| overlay | `DebugBboxOverlayView.kt` `RectNorm.toScreenRect()` | `x * screenWidth`, `y * screenHeight` | fallback/legacy path로만 유지하고 current PASS 근거로 쓰지 않음 |
| depth runtime | `ObjectDepthRuntimePipeline.kt` `process()` | ARCore mapper가 없으면 depth 입력 제외 또는 identity fallback으로 표시 | frame-aware mapper 우선 경로 유지, fallback은 Device gate에서 실패/주의로 판정 |
| depth runtime | `ObjectDepthRuntimePipeline.kt` `identityMapper()` | model/image/depth size 동일, scale 1, pad 0 가정 | `transform_path=identity`로 명명하고 fallback 처리 |
| coordinate mapper | `CoordinateMapper.kt` | normalized image/depth point를 그대로 반환 | ARCore transform adapter 추가 |
| sampler | `DepthSampler.kt` | normalized polygon을 depth pixel bounds로 변환 | depth mapper 출력 polygon만 입력 |

## metadata-only log 연계 필드

좌표 정합성 디버깅은 기본적으로 metadata-only log만 허용한다. 이미지/깊이 파일 저장, frame export, 외부 업로드는 별도 승인 전 금지한다.

로그 후보 필드:

- timestamp 또는 frame index
- device model, Android API level, ARCore availability/status 요약
- preview view width/height
- camera image width/height
- depth image width/height
- display rotation
- detector input width/height
- detector bbox normalized 좌표
- overlay mapper 출력 bbox/center와 clipping 여부
- depth mapper 출력 bbox/center와 clipping 여부
- transform path 이름, mapper version
- depth sample count, valid sample count, confidence summary
- median depth, p20 depth, IQR, metric source
- mapper fallback 여부와 fallback reason

금지 항목:

- RGB/YUV camera image 저장
- depth raw image 저장
- confidence image 저장
- screenshot 저장
- bbox가 그려진 preview 이미지 저장
- 사용자 위치, 얼굴, 주변 환경을 재식별할 수 있는 원본 데이터 export

## 실기기 없이 가능한 Unit/static 테스트

- identity mapper 테스트: 기존 가정 경로가 명시적으로 `identity` 이름을 갖고, fallback으로만 사용되는지 확인한다.
- bbox normalization 테스트: `[0, 1]` 범위 입력이 view/depth bounds 안으로 clipping되는지 확인한다.
- rotation table 테스트: 0/90/180/270도 입력에 대해 mapper 계약이 일관된 corner ordering을 유지하는지 확인한다.
- aspect ratio 테스트: preview와 detector input 종횡비가 다를 때 overlay mapper와 depth mapper가 같은 스케일 함수를 공유하지 않도록 확인한다.
- metadata schema 테스트: 이미지 byte array 없이 mapper input/output metadata만 직렬화 가능한지 확인한다.
- static check: Android Kotlin 코드 변경 없이 문서/계약에서 `overlay mapper`, `depth mapper`, `transformCoordinates2d` 경로가 분리되어 있는지 확인한다.

## 실기기 필수 검증

- preview overlay 정합성: 화면에 보이는 물체 경계와 bbox가 rotation별로 맞는지 확인한다.
- depth sample 정합성: 가까운 단일 물체의 bbox 내부 depth가 배경 depth가 아닌 물체 depth를 읽는지 확인한다.
- rotation 전환: portrait/landscape 및 기기 회전 후 overlay mapper와 depth mapper가 동시에 깨지지 않는지 확인한다.
- depth availability: depth unavailable/low confidence 상황에서 fallback과 사용자 UX가 정상인지 확인한다.
- 성능: mapper 적용 후 frame 처리 지연이 사용자 경고 타이밍을 망치지 않는지 확인한다.
- Device PASS: 실기기에서 overlay 위치, depth sample 위치, rotation, unavailable 경로를 모두 확인했을 때만 사용할 수 있다.

## 단계별 진행안

1. 현재 identity 경로를 명명한다.
   - 검증: log/테스트에서 transform path가 `identity`로 구분된다.
2. overlay mapper와 depth mapper interface를 분리한다.
   - 검증: view 좌표 출력 타입과 depth image 좌표 출력 타입이 섞이지 않는다.
3. `Frame.transformCoordinates2d(...)` 기반 후보 adapter를 조사 후 구현한다.
   - 검증: 확인 필요 API 이름을 실제 ARCore 의존 버전 기준으로 확정한다.
4. metadata-only log를 연결한다.
   - 검증: 이미지/깊이 byte 저장 없이 mapper input/output과 depth summary만 남는다.
5. 실기기 검증 체크리스트를 수행한다.
   - 검증: Device PASS 조건을 모두 만족하거나 실패 조건을 명시한다.

## 성공 기준

- detector normalized bbox가 view 좌표와 depth image 좌표로 각각 독립 변환된다.
- overlay mapper와 depth mapper의 입력/출력 타입, 책임, fallback reason이 구분된다.
- `transformCoordinates2d` 사용 여부와 source/target 좌표계가 공식 API 확인 후 문서/코드에 반영된다.
- 이미지/깊이 파일 저장 없이 metadata-only log로 좌표 정합성 판단에 필요한 최소 필드를 남길 수 있다.
- 실기기에서 rotation별 overlay 정합성과 depth sample 정합성을 확인했다.

## PASS로 쓰면 안 되는 항목

- 기존 identity 가정만 유지한 상태에서의 JVM 테스트 통과
- overlay만 맞고 depth mapper 정합성을 확인하지 않은 상태
- depth sample 값이 나온다는 이유만으로 위치 정합성을 확인하지 않은 상태
- 실기기 없이 emulator/static 테스트만 통과한 상태
- 이미지/깊이 파일을 무단 저장하거나 export해서 얻은 검증 결과
- ARCore enum/API 이름을 확인 필요로 남긴 채 완료로 표시하는 것
