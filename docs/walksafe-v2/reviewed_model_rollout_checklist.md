# Reviewed YOLO26s model rollout checklist

- 기준일: 2026-06-02 KST
- 대상 학습: `walksafe_tactile3_reviewed_yolo26s` reviewed tactile 3-class pipeline
- 목적: legacy fallback용 reviewed YOLO26s 학습 결과, backend `/detect/v2` fallback 연결, Android TFLite fallback export/검증을 확인하기 위한 체크리스트

## 2026-06-02 현재 우선순위 보정

- 주 사용자 앱 경로는 Android native ARCore/TFLite APK다.
- 이 문서는 backend/Web/PWA/voice/정책 기준으로 유지하되, Android Device evidence를 대체하지 않는다.
- Android report upload, TTS/haptic, navigation 연결은 bbox/depth 좌표 정합 gate 이후 진행한다.

## 0. 2026-05-23 선택 결과

- Pipeline done: `2026-05-23 19:28:17 KST`
- legacy fallback 선택 후보: Stage1 `best.pt`
- checkpoint:
  - `runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/weights/best.pt`
- 초기 runtime config:
  - `configs/walksafe_two_model_runtime_stage1_mvp_20260523.json`
- health-only env smoke:
  - `bash scripts/check_detect_v2_stage1_candidate_health_20260523.sh`

Stage2 high-res fine-tune은 Stage1보다 best val/test mAP50-95가 낮아 legacy fallback 후보로 쓰지 않는다. 현재 primary 모델 방향은 `unified_walksafe` 13-class다.

## 1. 학습 완료 확인

학습 프로세스를 먼저 확인한다.

```bash
bash scripts/check_walksafe_tactile3_reviewed_training_status_20260522.sh
```

완료 기준:

- `logs/walksafe_tactile3_reviewed_yolo26s_pipeline_20260522.log`에 pipeline 종료 또는 실패 로그가 있다.
- Stage1 run directory가 존재한다.
  - `runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/`
- Stage2 run directory가 존재한다.
  - `runs/detect/walksafe_tactile3_reviewed_yolo26s_img1280_ft80_nomosaic_20260522/`
- 각 run directory에 아래 파일이 있다.
  - `results.csv`
  - `weights/best.pt`
  - `weights/last.pt`
- test validation 결과 directory가 있다.
  - `runs/validation/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522_test/`
  - `runs/validation/walksafe_tactile3_reviewed_yolo26s_img1280_ft80_nomosaic_20260522_test/`

주의:

- 학습 중간에는 `results.csv`가 있어도 최종 모델 선택에 쓰지 않는다.
- Stage2가 완료되어도 test validation이 없으면 앱 적용 후보는 provisional로만 둔다.
- 디스크가 90% 이상이면 새 평가/샘플링 전에 정리 후보를 확인한다.

## 2. CPU-only 성능 리포트

학습 종료 직후에는 먼저 GPU를 쓰지 않는 summary를 실행한다.

```bash
OUT_DIR=runs/reports/walksafe_tactile3_reviewed_yolo26s_20260523 \
  bash scripts/post_reviewed_yolo26s_training_report_20260523.sh
```

리포트에서 확인할 항목:

- Stage1 best epoch와 Stage2 best epoch
- `metrics/mAP50-95(B)`
- `metrics/mAP50(B)`
- precision/recall
- `results.csv` 마지막 epoch와 early-stop 여부
- `weights/best.pt`와 `weights/last.pt` 수정 시각
- test validation directory 존재 여부

## 3. 모델 선택 기준

우선순위는 아래 순서로 둔다.

1. test split 기준 `damaged_tactile_block` 오탐이 허용 가능한가
2. 전체 `mAP50-95(B)`가 Stage1보다 개선됐는가
3. `damaged_tactile_block` precision이 자동 신고에 충분한가
4. recall 저하가 현장 사용성을 해칠 정도인가
5. Stage2 high-res가 실제 inference 비용을 감당할 수 있는가

권장 판단:

- Stage2가 test와 val 모두에서 Stage1보다 명확히 좋으면 Stage2 `best.pt`를 후보로 둔다.
- Stage2가 val에서 낮거나 test가 없으면 Stage1 `best.pt`를 기본 후보로 둔다.
- 수치 차이가 작으면 자동 신고 오탐 리스크가 낮은 쪽을 고른다.

## 4. Threshold tuning 기준

자동 신고는 사용자에게 즉시 말하지 않는 백그라운드 시설물 신고이므로 오탐을 보수적으로 관리한다.

신고 대상:

- `unified_walksafe:damaged_tactile_block` (primary 13-class 단일 모델)
- `custom_tactile:damaged_tactile_block`

신고 제외:

- `unified_walksafe`의 일반 객체/경로·장애물 class — 위험 경고 입력은 가능하지만 시설물 신고 저장 대상은 아님
- `custom_tactile:tactile_damage_area` — 현재 신고 기준이 아니라 보조 bbox 정보
- `custom_tactile:normal_tactile_block`
- 모든 `coco_general` 일반 객체
- unknown class

초기 정책:

- `damaged_tactile_block` threshold는 `0.50`으로 시작한다. 이는 test split image-level presence sweep에서 F1/recall 균형이 가장 좋았기 때문이다.
- 어드민 검수에서 오탐 부담이 크면 `0.60`, 필요하면 `0.75`로 올린다.
- `tactile_damage_area`는 자동 신고 기준에서 제외하고, 보조 표시/디버그 정보로만 둔다.
- 일반 객체는 신고하지 않고 risk evaluator가 보행 위험으로 판단한 경우에만 TTS/진동 경고로 처리한다.

조정 루프:

1. threshold 후보별 val/test prediction summary를 만든다.
2. 자동 신고 후보 중 상위 false positive를 샘플링한다.
3. 50~100장 단위로 수동 검수한다.
4. 오탐이 많으면 threshold를 올리거나 class별 자동 신고를 제한한다.
5. 미탐이 많으면 데이터 보강 또는 재학습 조건으로 넘긴다.

서비스 기준 평가는 “이미지 안에 손상 점자블록이 하나라도 있는가”를 별도로 확인한다. 이미 저장된 YOLO prediction label이 있으면 아래 스크립트로 bbox 단위가 아닌 image-level presence precision/recall/F1을 계산한다.

```bash
python3 scripts/evaluate_yolo_image_level_presence_20260523.py \
  --gt-label-dir datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/labels/test \
  --pred-label-dir <saved-prediction-label-dir> \
  --image-dir datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/images/test \
  --data-yaml datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/data.yaml \
  --target-class-name damaged_tactile_block \
  --confidence-threshold 0.50 \
  --out-json runs/reports/walksafe_tactile3_reviewed_yolo26s_20260523/image_level_damaged_tactile_block.json
```

이 스크립트는 inference를 새로 돌리지 않고, GT label과 prediction label txt만 비교한다.

## 5. Backend legacy fallback 연결 단계

이 섹션은 reviewed YOLO26s custom tactile + COCO helper를 legacy fallback으로 연결할 때의 기준이다. 2026-06-02 현재 primary 연결은 `DETECT_V2_UNIFIED_MODEL_PATH` 하나로 unified 13-class checkpoint를 지정하는 방식이다.

1. 선택 checkpoint path를 정한다.
   - Stage1 후보: `runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/weights/best.pt`
   - Stage2 후보: `runs/detect/walksafe_tactile3_reviewed_yolo26s_img1280_ft80_nomosaic_20260522/weights/best.pt`
2. 모델 path와 threshold config를 환경변수 또는 설정 파일로 주입한다.
   - 현재 Stage1 후보용 local env는 `docs/execution/2026-05-23_reviewed_yolo26s_final_selection.md`를 따른다.
3. `custom_tactile` adapter가 YOLO 결과를 `model.two_model_runtime.Detection` 입력 형태로 변환하게 한다.
4. COCO helper는 `coco_general` model_key와 allowlist를 유지한다.
5. `/detect/v2` 응답의 `model_key`, `model_class_id`, `source_model`, `threshold_used`를 유지한다.
6. `/reports/v2`는 `unified_walksafe` 또는 legacy `custom_tactile`의 `damaged_tactile_block`만 저장하도록 둔다.

안전장치:

- 실사용 `server-v2`에서 model path가 없거나 load 실패하면 fake-v2로 자동 fallback하지 않는다. 탐지/자동신고를 중단하고 서버 장애를 명확히 알린다.
- fake/fake-v2는 데모/개발 모드 전용이며 실제 안전 판단이나 실제 신고 저장에 쓰지 않는다.
- v1 `/detect`와 `/reports` 계약은 변경하지 않는다.
- v2 class id를 v1 전역 class id로 섞지 않는다.
- cross-model NMS는 적용하지 않는다.

## 6. Frontend/accessibility 검증

확인 모드:

- `NEXT_PUBLIC_DETECTOR_MODE=fake-v2`
- `NEXT_PUBLIC_DETECTOR_MODE=server-v2`

필수 확인:

- tactile damage 자동 신고는 사용자에게 완료 TTS를 말하지 않는다.
- 음성 명령 신고는 완료/실패를 짧게 TTS로 말한다.
- 일반 객체는 신고되지 않는다.
- 일반 객체는 risk evaluator가 보행 위험으로 판단한 경우에만 경고한다.
- 타일 손상 자체는 보행 위험 TTS로 반복 안내하지 않는다.
- 보행 위험 TTS는 너무 잦게 반복되지 않는다.

## 7. E2E smoke 순서

1. Backend unit tests

```bash
PATH="$PWD/.venv/bin:$PATH" PYTHONPATH=. python3 -m pytest \
  backend/tests/test_detect_v2.py \
  backend/tests/test_reports_v2.py \
  backend/tests/test_detect.py \
  backend/tests/test_reports.py \
  model/test_two_model_runtime.py -q
```

2. Backend v2 contract smoke

```bash
.venv/bin/python scripts/check_detect_v2_contract_smoke_20260523.py
```

이 smoke는 ASGI app을 직접 호출하며 서버를 띄우지 않는다. 기본 fake v2 계약, 손상 점자블록 report 저장, general object report 거부를 확인한다.

3. Frontend risk policy smoke

```bash
bash scripts/check_frontend_risk_evaluator_policy_20260523.sh
```

이 smoke는 TypeScript risk evaluator를 임시 디렉터리에 compile한 뒤 Node로 실행한다. 손상 점자블록 report-only, 손상 영역 보조 표시, normal tactile no-risk, general object display-only, bbox history approaching/blocking alert 정책을 확인한다.

4. Frontend checks

```bash
cd apps/web
npm run lint
npm run typecheck
```

5. 서버 모드 수동 smoke

- 카메라 frame 업로드가 `/detect/v2`로 들어가는지 확인한다.
- damage detection이 있을 때 `/reports/v2` 자동 신고가 한 번만 발생하는지 확인한다.
- GPS가 없을 때 자동/음성 신고 모두 저장하지 않고 위치 확인 안내로 대기하는지 확인한다.

6. Stage1 image smoke

- 저장된 damage-positive 후보 이미지와 known-negative 이미지 3~5장을 `server-v2` `/detect/v2`에 통과시킨다.
- 각 이미지에 대해 `model_key`, `class_name`, `confidence`, `threshold_used`, bbox, 응답 지연시간을 기록한다.
- damage-positive 후보는 `--expect-target-detected`를 켜고 실행한다. selected image 중 `target_class=damaged_tactile_block` 검출이 하나도 없으면 smoke는 `status=failed`, exit 1이어야 한다.
- known-negative 단일 이미지 smoke는 `--expect-target-detected` 없이 실행하며, `damaged_tactile_block` 검출이 없어도 PASS 가능해야 한다.
- 실제 unified detection payload에 `model_key=unified_walksafe`, `class_name=damaged_tactile_block`, `threshold_used`, bbox, confidence가 남는지 확인한다. legacy Stage1 fallback smoke에서는 `model_key=custom_tactile`도 허용한다.
- `damaged_tactile_block` 후보는 자동 신고 후보로 이어질 수 있는지 확인하고, `tactile_damage_area`/일반 객체는 report 저장 대상이 아닌지 확인한다.
- 실폰 카메라 입력이 아니므로 field 성능, 착용 각도, TalkBack/TTS 지연 근거로 쓰지 않는다.
- 정리된 로컬 데이터셋이 없으면 `scripts/check_detect_v2_stage1_image_smoke_20260524.py --image <image>` 또는 `--image-dir <images/test>`로 재실행한다. 입력 이미지가 없으면 smoke 결과는 `blocked`와 다음 실행 명령을 기록해야 한다.

```bash
# known-negative 또는 실폰 단일 이미지: target 검출이 없어도 계약/응답 smoke로 PASS 가능
.venv/bin/python scripts/check_detect_v2_stage1_image_smoke_20260524.py \
  --image <known_negative_image> \
  --output docs/execution/2026-05-25_stage1_detect_v2_image_smoke_rerun.md

# reviewed-label positive 이미지가 준비된 경우: target 미검출이면 FAIL/exit 1
.venv/bin/python scripts/check_detect_v2_stage1_image_smoke_20260524.py \
  --image-dir datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/images/test \
  --target-positive only \
  --expect-target-detected \
  --output docs/execution/2026-05-25_stage1_detect_v2_image_smoke_rerun.md
```

7. 실제 Stage1 detection payload → report → export trace

- 실제 Stage1 `/detect/v2` 응답의 `damaged_tactile_block` detection payload 1건을 그대로 근거로 삼아 `/reports/v2` 자동 신고로 저장한다.
- 같은 신고가 `/reports/export` CSV/JSON/GeoJSON에서 `model_key`, `trigger`, `auto_reported`, 위치, 원본 v2 metadata 확인에 필요한 필드와 함께 조회되는지 확인한다.
- trace에서 detection payload id/시간/GPS 또는 테스트 좌표와 export row를 대조해 같은 건임을 확인한다.
- trace 목적은 기관 자동 제출이 아니라 내부 검수와 제출 준비 흐름 검증이다.

8. Admin export URL regression

- Admin export 버튼/링크가 현재 필터 조건을 보존한 `/reports/export` URL을 생성하는지 확인한다.
- 기존 CSV 기본 export URL이 GeoJSON 옵션 추가 후에도 깨지지 않는지 확인한다.
- `format=json`/`format=geojson` 선택 시에도 `model_key`, `trigger`, `auto_reported` 등 기존 query가 유지되는지 확인한다.

9. Offline timing fixture

- 실제 TMAP 호출 없이 고정 route guide point fixture로 추정 속도, 보폭 입력에 따른 “10초 뒤/곧/지금” 안내 경계값을 재현한다.
- fixture 결과는 길안내 timing 로직 회귀 방지용이며, 운영 품질이나 실폰 보정 완료 근거로 쓰지 않는다.

10. Admin GeoJSON export UI smoke

- `/reports/export?format=geojson` 신고 Point Feature를 운영자가 UI에서 선택/다운로드할 수 있는지 확인한다.
- `/reports/export` 신고 위치와 길안내 route context를 운영자가 비교할 최소 필드를 정한다.
- TMAP 원본 경로 응답 장기 저장은 약관 확인 전까지 금지한다.

11. 온라인 TMAP smoke와 실폰 보정

- 길안내 타이밍 smoke 도구(`scripts/check_navigation_guidance_timing_20260524.py --timeout-seconds 12`)로 실제 TMAP route guide point, 추정 속도, 보폭 입력에 따른 안내 시점을 확인한다.
- 이후 실폰에서 보폭 기본값, GPS 속도 튐, TalkBack/TTS 지연, 위험 경고와 길안내 TTS 중재 지연을 보정한다.
- 실폰 보정 전에는 길안내 timing을 운영 품질로 확정하지 않는다.

## 8. Rollback

문제가 있으면 순서대로 되돌린다.

1. 실사용 `server-v2`는 탐지/자동신고를 중단하고 장애 상태를 안내한다.
2. 데모/개발 세션만 명시적으로 `fake-v2` 또는 기존 `fake` 모드로 전환한다.
3. backend 실제 model path를 unset한다.
4. `damaged_tactile_block` threshold를 `0.60` 또는 `0.75`로 상향한다.
5. 자동 신고를 임시 중지한다.
6. 선택 checkpoint를 Stage2에서 Stage1로 되돌린다.

## 9. 다음 데이터 개선 루프

재학습이 필요한 조건:

- `damaged_tactile_block` false positive가 현장 자동 신고에 부담될 정도로 많다.
- 보도블록 파손과 점자블록 손상이 혼동된다.
- 작은 손상, 야간/역광, 흔들림, 가림 상황에서 false negative가 많다.
- Stage2 high-res가 성능 이득 없이 속도/메모리 부담만 키운다.

반복 절차:

1. false positive/false negative 후보를 100장 단위로 추출한다.
2. contact sheet와 crop sheet를 만든다.
3. 외부 AI/사람 검수로 bbox/class decision을 확정한다.
4. reviewed dataset을 재빌드한다.
5. 짧은 smoke train 또는 threshold-only 재평가 후 full train 여부를 결정한다.
