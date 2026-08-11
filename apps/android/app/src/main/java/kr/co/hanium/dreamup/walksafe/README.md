# WalkSafe Android Application

이 패키지는 Android native 사용자 앱의 조립 지점이다. `MainActivity`가 ARCore frame, TFLite 탐지, depth 추정, 사용자 피드백, 길안내, 신고를 연결하고 하위 패키지가 각 정책과 외부 연동을 맡는다.

## 실행 흐름

```text
ARCore camera/depth frame
  -> inference/TFLite detector
  -> depth/object tracking and metric distance
  -> device/readiness gate
  -> feedback/TTS and haptic
  -> report/damage-only upload

trusted GPS + step sensor
  -> navigation/backend route
  -> route progress and guidance
```

## 주요 파일

| 경로 | 책임 |
|---|---|
| `MainActivity.kt` | ARCore render loop, 비동기 탐지, UI, 권한, 길안내와 신고 조립 |
| `CameraBackgroundRenderer.kt` | ARCore external camera texture 렌더링 |
| `DebugBboxOverlayView.kt` | 개발용 bbox/depth overlay |
| `MetadataCaptureLog.kt` | 이미지 없이 최근 frame metadata를 보관하는 ring buffer |
| `depth/` | 좌표 매핑, depth sampling, tracking, 거리와 메시지 정책 |
| `inference/` | runtime JSON과 TFLite 전처리/추론/파싱 |
| `device/` | 센서 결과를 사용자 동작으로 올리기 전 readiness gate |
| `feedback/` | TTS, TalkBack, 진동과 반복 억제 |
| `navigation/` | GPS 신뢰도, 보폭, backend 경로와 경로 진행 상태 |
| `report/` | 손상 점자블록 신고 후보와 multipart upload |
| `debuglog/` | debug/release source set이 교체하는 진단 업로더 계약 |

## 변경 시 주의

- ARCore `Frame`과 `Image` lifecycle은 render loop가 소유한다. 다른 스레드에는 복사한 값만 넘긴다.
- detector bbox, preview view, depth image는 좌표계가 다르다. mapper를 우회해 normalized 좌표를 직접 섞지 않는다.
- pseudo-depth 추세는 metric 거리가 아니므로 `N보` 안내나 신고 근거로 쓰지 않는다.
- TTS, 진동, 신고 후보는 `DeviceGateState`를 통과한 결과만 사용한다.
- `reporter_user_id`는 현재 로컬 입력값이며 서버 인증 주체가 아니다.
- JVM 테스트와 APK 빌드는 실기기 ARCore/보행 검증을 대체하지 않는다.

현행 빌드와 검증 한계는 [Android 사용자 앱 코드 지도](../../../../../../../../../../../docs/guides/code/android-user.md)를 먼저 본다. `apps/android/README.md`는 hash로 결속된 과거 snapshot이므로 현행 절차로 사용하지 않는다.
