# Android metadata-only capture schema - 2026-06-01

최신 detector 전제: 2026-06-02부터 Android runtime은 `unified_walksafe` primary + legacy `custom_tactile`/`coco_general` fallback이다. 아래 schema 필드는 하위 호환 이름을 유지하지만, unified asset이 들어가면 `detectorCompletedModels=[unified_walksafe]`가 정상 경로다.

## 목적

Android 실기기에서 bbox/depth 좌표 정합을 확인할 때, 개인정보 원본을 저장하지 않고 필요한 최소 debug metadata만 남기기 위한 schema다.

## 원칙

- 기본 저장 위치는 메모리 ring buffer다.
- RGB/YUV camera frame, depth raw, confidence image, screenshot, bbox overlay image는 저장하지 않는다.
- 파일 export, 외부 전송, 장기 보존은 별도 사용자 승인 전 금지한다.
- metadata는 Device gate 판단 보조 자료이며, 단독으로 실기기 PASS를 대체하지 않는다.

## 현재 구현 상태

현재 `MetadataCaptureLogEntry`는 다음 필드를 갖는다.

| 필드 | 상태 | 목적 |
|---|---|---|
| `frameTimestampMs` | implemented | 현재 ARCore/depth frame timestamp |
| `detectorFrameTimestampMs` | implemented | detector 결과가 나온 frame timestamp |
| `detectorAgeMs` | implemented | source frame 기준 stale guard 판단 |
| `detectorSourceAgeMs`, `detectorCompletedAgeMs` | implemented | "오래된 frame이 늦게 완료된" 상황과 완료 후 경과 시간을 분리 |
| `detectorFrameDeltaMs` | implemented | 현재 ARCore frame과 detector source frame timestamp 차이 |
| `detectDurationMs` | implemented | detector 전체 소요 시간 |
| `detectorYuvDecodeMs` | implemented | YUV decode 병목 확인 |
| `detectorModelKey`, `detectorModel*Ms` | implemented | unified single-model preprocess/inference/parse 병목 확인 |
| `detectorCoco*Ms`, `detectorCustom*Ms` | implemented | legacy COCO/custom preprocess/inference/parse 병목 분리 |
| `detectorCompletedModels`, `detectorSkippedModels`, `detectorPartial` | implemented | partial COCO 결과와 custom interval skip 확인 |
| `detectionCount` | implemented | 최근 detector 결과 수 |
| `detectionsUsedForDepth` | implemented | depth pipeline에 detection을 넘겼는지 여부 |
| `staleReason` | implemented | age 초과 등 stale 이유 |
| `topDetectionClassName` | implemented | 최고 confidence detection class |
| `topDetectionConfidence` | implemented | 최고 confidence 값 |
| `topDetectionBbox` | implemented | detector normalized bbox |
| `bestDepthClassName` | implemented | best depth output class |
| `bestDepthTrackId` | implemented | best depth output track id |
| `bestDepthSource` | implemented | Raw/Full/pseudo 등 depth source |
| `bestDepthDetectionConfidence`, `bestDepthConfidenceScore` | implemented | detection confidence와 depth 선택 confidence score 분리 |
| `bestDepthMedianM`, `bestDepthP20M`, `bestDepthRiskDistanceM`, `bestDepthIqrM` | implemented | median, class별 위험거리, p20/IQR 비교 |
| `bestDepthValidSampleCount` | implemented | valid depth sample 수 |
| `bestDepthValidSampleRatio` | implemented | valid sample 비율 |
| `bestDepthBbox` | implemented | best depth output bbox |

현재 보존 정책:

- `MetadataCaptureLog.DEFAULT_MAX_ENTRIES = 30`
- `summaryText()` 기본 표시 최근 3개
- session stop 시 `clear()` 호출

## 다음 보강 schema

| 필드 | 상태 | 목적 | 기본 저장 |
|---|---|---|---|
| `previewWidth`, `previewHeight` | implemented | overlay view scaling 판단 | memory only |
| `cameraImageWidth`, `cameraImageHeight` | implemented | camera image coordinate 판단 | memory only |
| `depthWidth`, `depthHeight` | implemented | depth image coordinate 판단 | memory only |
| `displayRotation` | implemented | rotation issue 판단 | memory only |
| `detectorInputWidth`, `detectorInputHeight` | planned | model input/letterbox 판단 | memory only |
| `overlayBboxView` | planned | view pixel bbox 확인 | memory only |
| `depthBboxPixels` | planned | depth image pixel bbox 확인 | memory only |
| `overlayTransformPath` | implemented | `arcore_image_to_view` 등 overlay 좌표 변환 구분 | memory only |
| `depthTransformPath` | implemented | `arcore_image_to_texture_normalized`, `identity` 등 depth 좌표 변환 구분 | memory only |
| `transformPath` | implemented | 하위 호환용 depth transform alias | memory only |
| `fallbackReason` | implemented | fallback 사용 이유 설명 | memory only |
| `arcoreFrameTimestampNs` | planned | ARCore frame/depth timestamp 비교 | memory only |
| `rawDepthAvailable` | planned | Raw depth path availability | memory only |
| `fullDepthAvailable` | planned | Full depth fallback availability | memory only |
| `rawConfidenceSummary` | planned | Raw confidence 기반 sample 품질 확인 | memory only |
| `suppressedReason` | planned | stale/empty/low confidence 제외 이유 | memory only |
| `rgbFrameRef` | forbidden by default | RGB 원본 파일 참조 | no |
| `rawDepthRef` | forbidden by default | depth raw 파일 참조 | no |
| `confidenceImageRef` | forbidden by default | confidence image 파일 참조 | no |
| `screenshotRef` | forbidden by default | bbox overlay screenshot 참조 | no |

## summary text 권장 형식

```text
capture: frameTs=... detFrameTs=... age=... frameDelta=... used|suppressed stale=...
  sourceAge=... detect=... models=unified_walksafe
  top=person 88% bbox=(x,y,w,h)
  camera=640x480 preview=1080x2340 depthSize=160x90 rotation=0
  overlayTransform=arcore_image_to_view depthTransform=arcore_image_to_texture_normalized fallback=none
  depth=person RAW risk=1.10m median=1.20m score=80% samples=84 ratio=72% depthSize=160x120
```

## server debug log 연계

2026-06-01 기준 debug build에는 local/dev backend로 metadata-only log를 upload하는 기능이 있다.

- Android 버튼: `서버 로그 켜기`
- backend endpoint: `POST /android/debug/depth-logs`
- backend 기본값: `ANDROID_DEBUG_LOG_ENABLED=false`
- runbook: `docs/android/android_server_debug_log_runbook_20260601.md`

이 upload는 `/reports/v2` 신고와 분리된다. 이미지, raw depth, confidence image, screenshot, GPS, audio, secret은 payload에 포함하지 않는다.

## 작업 계획

- [단계] `transformPath`와 `fallbackReason`을 metadata에 추가한다.
  -> 검증: 완료. identity 경로가 debug detail에서 `transform=identity fallback=arcore_mapper_not_connected`로 보인다.

- [단계] preview/camera/depth size를 metadata에 추가한다.
  -> 검증: 완료. preview, camera image, depth size와 display rotation을 기록한다.

- [단계] detector timing을 metadata에 추가한다.
  -> 검증: 완료. YUV decode, COCO/custom preprocess/inference/parse, partial/skipped model 정보를 기록한다.

- [단계] depth 선택 판별용 frame delta/risk/score 필드를 추가한다.
  -> 검증: 완료. `detectorFrameDeltaMs`, `bestDepthRiskDistanceM`, `bestDepthP20M`, `bestDepthConfidenceScore`, `bestDepthTrackId`를 기록한다.

- [단계] overlay view bbox와 depth pixel bbox를 분리해 기록한다.
  -> 검증: overlay 좌표와 sampler 좌표가 같은 필드에 섞이지 않는다.

- [단계] ring buffer cap/clear 정책을 유지한다.
  -> 검증: `MetadataCaptureLogTest`가 cap, clear, stale summary를 계속 확인한다.

- [단계] 파일 export 버튼은 별도 승인 전 만들지 않는다.
  -> 검증: APK 기본 동작에서 frame/depth/screenshot 파일이 생성되지 않는다.

## PASS로 쓰면 안 되는 항목

- metadata log가 존재한다는 사실만으로 좌표 정합 PASS라고 쓰는 것.
- `bbox_norm`만 보고 view/depth 좌표가 맞는다고 쓰는 것.
- depth median 값이 있다는 이유만으로 같은 객체를 읽었다고 쓰는 것.
- 이미지/depth 파일 저장을 사용자 승인 없이 debug 편의 기능으로 추가하는 것.
