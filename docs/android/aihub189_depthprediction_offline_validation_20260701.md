# AIHub 189 인도보행 DepthPrediction 오프라인 검증 문서 - 2026-07-01

## 목적

AIHub 189 `인도보행 영상/뎁스프리딕션` 데이터를 Android ARCore 실기기 검증 전에 사용할 수 있는 오프라인 depth 검증 데이터로 정리한다.

이 문서는 모델 학습용 데이터셋 문서가 아니다. 목표는 RGB 프레임, disparity, confidence를 이용해 객체 거리 추정, `N보` 변환, 위험 경고 정책을 실기기 없이 먼저 검증하는 것이다.

## 결론

`Depth_001~005.zip`은 검증 데이터 후보로 사용할 수 있다.

다만 이 데이터는 줄자로 잰 실측 GT가 아니라 ZED 스테레오 기반 `disparity/depth prediction` 계열 reference 데이터로 봐야 한다. 따라서 “ARCore depth 최종 성능 PASS” 근거가 아니라 “depth pipeline 오프라인 사전 검증” 근거로만 사용한다.

## 로컬 데이터 위치

```text
/home/ddobagi/Downloads/한이음 드림업 데이터셋/인도보행 영상/뎁스프리딕션/Depth_001~005.zip
```

확인한 상태:

| 항목 | 값 |
|---|---|
| 로컬 파일 | `Depth_001~005.zip` |
| 용량 | 18.98 GiB |
| 외부 zip 내부 | `Depth_001.zip` ~ `Depth_005.zip` |
| 현재 13-class YOLO 학습 사용 여부 | 미사용 |
| 권장 용도 | depth sampler, 거리 추정, step/risk policy 오프라인 검증 |

AIHub 189 전체 file tree에는 `Depth_006~010.zip`, `Depth_011~015.zip`, `Depth_016~020.zip`, `Depth_021~024.zip`도 있으나, 현재 로컬 확인 범위는 `Depth_001~005.zip`이다.

## 샘플 구조

`Depth_001.zip` 샘플에서 확인한 파일 패턴:

```text
Depth_001.conf
ZED1_KSC_001032_confidence.png
ZED1_KSC_001032_confidence_save.png
ZED1_KSC_001032_disp.png
ZED1_KSC_001032_disp16.png
ZED1_KSC_001032_L.png
ZED1_KSC_001032_left.png
ZED1_KSC_001032_R.png
ZED1_KSC_001032_right.png
ZED1_KSC_001033_confidence.png
ZED1_KSC_001033_confidence_save.png
ZED1_KSC_001033_disp.png
ZED1_KSC_001033_disp16.png
ZED1_KSC_001033_L.png
ZED1_KSC_001033_left.png
ZED1_KSC_001033_R.png
ZED1_KSC_001033_right.png
```

샘플 이미지 속성:

| 파일 | 속성 |
|---|---|
| `*_left.png` | 1920x1080 RGB |
| `*_right.png` | 1920x1080 RGB |
| `*_disp16.png` | 1920x1080 16-bit grayscale |
| `*_disp.png` | 시각화용 disparity 이미지로 보임 |
| `*_confidence.png` | 1920x1080 8-bit grayscale |
| `Depth_001.conf` | ZED camera intrinsic/extrinsic 정보 |

`Depth_001.conf`에서 확인한 주요 값:

| 항목 | 값 |
|---|---|
| `LEFT_CAM_FHD.fx` | `1394.83` |
| `LEFT_CAM_FHD.fy` | `1394.83` |
| `LEFT_CAM_FHD.cx` | `932.377` |
| `LEFT_CAM_FHD.cy` | `559.96` |
| `STEREO.BaseLine` | `120.009` mm |

## 데이터 성격

이 데이터는 다음 세트를 같은 frame id로 묶어 사용할 수 있다.

| 데이터 | 역할 |
|---|---|
| left image | detector 입력 이미지 |
| right image | 스테레오 원본 확인용 |
| disp16 | 거리 변환용 disparity reference |
| confidence | depth/disparity sampling 품질 필터 |
| conf | 카메라 파라미터와 baseline |

이름이 `뎁스프리딕션`이고 내부가 `disp16/confidence` 중심이므로, 앱의 최종 카메라 depth와 동일한 센서 데이터로 취급하면 안 된다. 문서와 코드에서는 `ground truth`보다 `reference disparity`, `reference depth`, `offline depth reference` 같은 명칭을 쓴다.

## Depth 변환 원칙

스테레오 disparity에서 거리로 변환하는 기본식은 다음이다.

```text
depth_m = fx_px * baseline_m / disparity_px
```

현재 샘플 기준:

```text
baseline_m = 120.009 / 1000 = 0.120009
fx_px = 1394.83
```

주의할 점:

- `disp16.png`의 raw value가 실제 disparity pixel인지, fixed-point scale이 적용된 값인지 확정해야 한다.
- 샘플 검산상 `disp16 / 16.0`을 disparity pixel로 보는 경우 보행 장면에 더 그럴듯한 거리값이 나온다.
- 하지만 이 scale은 데이터셋 공식 문서, ZED export 설정, 또는 수동 거리 샘플로 한 번 더 확정해야 한다.
- `confidence.png`의 값 방향도 확정해야 한다. 값이 높을수록 신뢰도가 높은지, 낮을수록 좋은지 문서나 샘플 검산 없이 하드코딩하지 않는다.

후보 변환:

```text
disp_px = disp16_raw / 16.0
depth_m = 1394.83 * 0.120009 / disp_px
```

metric 정확도 주장을 하려면 `disp16` scale과 confidence 의미를 먼저 확정해야 한다.

## 사용 가능한 검증

이 데이터로 검증할 수 있는 항목:

| 검증 항목 | 방법 |
|---|---|
| depth file pairing | `*_left.png`, `*_disp16.png`, `*_confidence.png` frame id 매칭 |
| bbox-depth sampling | detector bbox 내부에서 valid disparity sample 수집 |
| robust distance | 0, invalid, outlier 제거 후 median/p20/p80 산출 |
| confidence filtering | confidence 조건 미달 sample 제거 또는 low-quality 판정 |
| step conversion | `ceil(distance_m / step_length_m)`으로 `N보` 산출 |
| warning bucket | 거리/접근/valid ratio 기준으로 경고 단계 산출 |
| message policy | 너무 먼 객체, 낮은 confidence, cooldown 조건에서 발화 억제 |
| regression fixture | 동일 frame에서 거리/보 수/경고 단계가 재현되는지 테스트 |

## 검증할 수 없는 항목

이 데이터만으로 검증하면 안 되는 항목:

| 항목 | 이유 |
|---|---|
| ARCore Raw Depth 정확도 | ZED 스테레오 reference와 Android 폰 depth sensor는 다르다 |
| 실시간 카메라-depth 좌표 정합 | Android preview crop, rotation, ARCore transform 문제는 실기기에서만 확정 가능하다 |
| 사용자가 걸을 때의 흔들림 | 정적 frame 기반 검증으로는 motion blur와 착용 각도를 대체하지 못한다 |
| GPS/네비 통합 | 경로 안내, heading, bearing, 보행 위치 추적은 포함되지 않는다 |
| TTS/진동 실제 타이밍 | 오프라인 policy 결과만 검증 가능하다 |
| 최종 안전성 PASS | 실제 보행 상황과 실기기 반복 검증이 필요하다 |

## 권장 오프라인 검증 파이프라인

```text
Depth_001~005.zip
  -> 필요한 inner zip만 파일시스템 추출 없이 선택적으로 읽기
  -> frame manifest 생성
  -> left image에 detector 실행
  -> detector bbox를 left/disp16 좌표에 매핑
  -> bbox 내부 disparity/confidence sampling
  -> distance_m, valid_ratio, confidence_summary 계산
  -> step_count, risk_bucket, message_policy 결과 계산
  -> CSV/JSON report 생성
  -> unit/integration test fixture로 고정
```

권장 산출물:

| 산출물 | 설명 | 커밋 여부 |
|---|---|---|
| `artifacts/aihub189_depthprediction/frame_manifest.csv` | frame id와 left/disp16/confidence 경로 | 선택 |
| `artifacts/aihub189_depthprediction/depth_eval.csv` | detection별 거리/보 수/위험 단계 | 선택 |
| `artifacts/aihub189_depthprediction/summary.json` | valid ratio, 거리 bucket, 실패 사유 요약 | 선택 |
| 작은 fixture 일부 | unit test용 최소 이미지 또는 synthetic depth | 가능 |
| 원본 이미지/depth 전체 | 19GB 이상 대용량 원본 | 커밋 금지 |

## 구현 시 필요한 모듈

Android 실기기 구현과 같은 정책을 공유하려면 순수 로직을 분리한다.

| 모듈 | 책임 |
|---|---|
| `DepthPredictionArchiveReader` | nested zip에서 frame별 left/disp16/confidence를 읽는다 |
| `ZedDisparityConverter` | `conf`의 fx/baseline과 scale로 depth meter를 만든다 |
| `DepthReferenceFrame` | left image size, disparity size, confidence size, frame id를 담는다 |
| `OfflineDepthSampler` | bbox 내부 disparity/confidence sample을 수집한다 |
| `RobustDepthStats` | Android와 동일한 invalid/outlier/median 정책을 적용한다 |
| `StepDistancePolicy` | meter를 사용자 보폭 기반 `N보`로 바꾼다 |
| `RiskDistancePolicy` | 거리/valid ratio/객체 class로 경고 등급을 만든다 |
| `OfflineDepthEvalReport` | CSV/JSON summary를 생성한다 |

가능하면 Android의 `DepthSampler`, `RobustDepthStats`, `MessagePolicy`와 같은 규칙을 테스트에서 공유한다. 앱 코드에 Android 의존성이 강하면 순수 Kotlin/JVM 모듈 또는 Python reference script로 먼저 검증하고, Android 쪽에는 동일 test vector를 넣는다.

## 테스트 기준

최소 smoke 기준:

- [x] outer zip에서 inner zip 목록을 읽을 수 있다.
- [x] inner zip 하나에서 `left/disp16/confidence/conf`를 frame id로 매칭할 수 있다.
- [x] `disp16` raw value `0` 또는 invalid sample을 제거한다.
- [x] bbox 내부 valid sample 비율을 계산한다.
- [x] 같은 bbox에 대해 distance median이 재현된다.
- [x] confidence 의미가 확정되기 전에는 confidence threshold PASS를 주장하지 않는다.

오프라인 기능 테스트 기준:

- [x] detector output bbox를 넣으면 `distance_m`, `step_count`, `risk_bucket`이 나온다.
- [x] 너무 먼 객체나 valid ratio 부족 객체는 경고하지 않는다.
- [x] 가까운 전방 객체는 warning bucket으로 들어간다.
- [x] 같은 frame fixture에서 결과가 deterministic하다.
- [x] summary report에 실패 사유가 남는다.

2026-07-02 evidence:

- `docs/evidence/aihub189_depthprediction_original_full_20260702.md`
- 실제 `Depth_001~005.zip` original full offline run: frames/evaluated 2461.
- `detector_input_kind=generated_probe_bbox`이므로 detector 성능 검증으로 쓰지 않는다.
- `source_kind=offline_zed_reference`, `reference_not_ground_truth=true`, `arcore_pass=false`를 유지한다.

최종 Android와 연결할 때의 기준:

- [ ] 같은 sampling 정책으로 AIHub reference depth와 Android ARCore depth log를 별도 report로 분리한다.
- [ ] AIHub 결과를 ARCore 성능 PASS로 표기하지 않는다.
- [ ] 실기기에서는 `camera preview -> detector bbox -> depth bbox` 좌표 정합을 별도로 확인한다.

## 기존 문서와의 관계

- `docs/android/arcore_rgbd_dataset_schema_20260601.md`는 Android ARCore RGB-D 검증 schema다.
- 이 문서는 AIHub 189 ZED depthprediction 데이터를 그 schema의 오프라인 reference source로 쓰는 방법을 정의한다.
- `data_sources/manifests/walksafe_aihub_source_usage_20260628.md`에는 이 zip이 현재 YOLO 객체탐지 학습 입력이 아니라 깊이추정/후처리 실험 후보라고 기록되어 있다.

## Goal prompt에 넣을 문장

```text
AIHub 189 인도보행 영상의 뎁스프리딕션 데이터
(`/home/ddobagi/Downloads/한이음 드림업 데이터셋/인도보행 영상/뎁스프리딕션/Depth_001~005.zip`)를
오프라인 depth 검증 데이터로 지원하라.

구현 범위:
- nested zip에서 frame별 left image, disp16, confidence, conf를 읽는 manifest builder를 만든다.
- ZED conf의 fx/baseline과 확정된 disp16 scale로 reference distance meter를 계산한다.
- detector bbox 내부 depth sampling, valid ratio, robust median/p20/p80을 산출한다.
- distance를 사용자 보폭 기반 N보와 risk bucket으로 변환한다.
- 결과를 CSV/JSON report로 저장하고, 작은 fixture 기반 unit/integration test를 추가한다.

제약:
- 이 데이터는 ARCore 실기기 최종 PASS 근거가 아니라 오프라인 reference 검증 근거로만 사용한다.
- 원본 19GB zip과 추출 이미지/depth 전체는 git에 커밋하지 않는다.
- disp16 scale과 confidence 값 방향을 확정하기 전에는 metric accuracy PASS를 주장하지 않는다.
```
