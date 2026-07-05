# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps lane note (2026-05-25)

## 최근 진행 근거
- `product/vision.md`, `product/roadmap.md`, `product/done-criteria.md` (2026-05-18): 모델 성능은 한국 보행 환경 validation/test와 실제 실행 근거로만 제품 성능으로 쓴다. fake/headless/model metric/field 근거는 분리해야 한다.
- 루트 `AGENTS.md`는 없음. 사용자 제공 AGENTS 지침을 기준으로 확인했다.
- `plans/features`, `plans/verify/ready`는 비어 있고 `PM/` 디렉터리가 없어 feature scheduler, verify scheduler, product audit, automation metrics 산출물은 확인되지 않았다. 기존 `plans/daily/2026-05-25.md`도 없다.
- `daylog/2026-05-23.md`, `docs/execution/2026-05-23_reviewed_yolo26s_final_selection.md`: reviewed YOLO26s pipeline 완료 후 Stage1 `best.pt`가 MVP/backend integration 후보로 선정됐다. Stage1 test all mAP50-95 `0.730`, Stage2 `0.720`.
- `docs/execution/2026-05-23_reviewed_yolo26s_final_selection.md`: `damaged_tactile_block` image-level presence는 threshold `0.50` 기준 precision `0.945326`, recall `0.900084`, F1 `0.922151`.
- `daylog/2026-05-23.md`: `/detect/v2` real YOLO adapter의 Ultralytics `Boxes` 변환 문제를 수정했고, Stage1 후보 env에서 ASGI smoke가 HTTP 200, detection 2건으로 통과했다.
- `datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/REVIEW_APPLY.md` (2026-05-22): 120건 review decision 적용 dataset이 built 상태다. `validation_status_counts.ok=120`, blocked row 없음, materialized rows `23,475`.
- `docs/current_status.md` (2026-05-23): `/detect/v2`는 fake 기본 + `yolo`/`real` lazy provider 구조이며, Stage1 후보와 `configs/walksafe_two_model_runtime_stage1_mvp_20260523.json`이 초기 운영 threshold config다.
- `daylog/2026-05-24.md`: 5/24 작업은 주로 TMAP/navigation 전환이었다. Android 실폰, TalkBack, 목걸이 착용, 실제 길안내 TTS 품질은 아직 미검증이다.

## 내일 목표 후보
- 1순위: Stage1 YOLO26s 후보의 `server-v2` 실제 inference evidence를 넓힌다. 단일 성공 smoke에서 3~5개 통제 이미지, COCO allowlist, bbox 변환, threshold_used, 실패 케이스까지 확장한다.
- 2순위: `damaged_tactile_block` threshold 운영값 결정을 위한 FP/FN review slice를 만든다. `0.50`, `0.60`, `0.75` 후보를 비교하고 50~100장 수동 검수 큐를 우선한다.
- 3순위: `server-v2` latency 측정 계획을 고정한다. cold load, warm request, CPU/GPU, 실폰 PWA 호출을 분리한다.
- 4순위: model-data evidence registry를 갱신한다. fake-v2, health-only, real ASGI smoke, model metric, field 미검증을 서로 다른 등급으로 기록한다.
- 5순위: selected Stage1 후보의 ONNX/export preflight를 준비한다. 실제 export는 디스크/환경 gate와 충돌이 없을 때만 실행하고, browser latency는 별도 기기 없으면 미검증으로 둔다.
- 6순위: 외부/blind validation 계획을 보강한다. 현재 v3 holdout은 이미 오류분석에 사용된 참고 평가셋이므로 최종 blind test로 쓰지 않는다.
- 7순위: class `1..3` 한국 GT manifest thin slice를 갱신한다. 대형 다운로드나 새 학습 없이 source/privacy/split/label_status 필드부터 확정한다.

## 상세 체크리스트 초안
- [ ] 시작 gate 확인 → 검증: `git status --short --branch --untracked-files=all`, `df -h`, `.pt/.onnx/runs/datasets images/labels` 추적 여부 기록.
- [ ] scheduler/PM 산출물 재확인 → 검증: `plans/features`, `plans/verify/ready`, `PM/status`, `PM/reports` 부재 또는 신규 파일 존재 여부 기록.
- [ ] Stage1 후보 health-only smoke 재확인 → 검증: `.venv` PATH 전제로 `bash scripts/check_detect_v2_stage1_candidate_health_20260523.sh`, mode/status/path/config 기록.
- [ ] real `/detect/v2` 통제 이미지 smoke 확장 → 검증: Stage1 env로 3~5장 ASGI 요청, HTTP status, latency, `model_key`, `source_model`, `threshold_used`, bbox `{x,y,width,height}` 기록.
- [ ] COCO helper smoke 기준 확인 → 검증: `yolo26n.pt` allowlist class만 반환, COCO/general은 `/reports/v2` 저장 대상이 아님을 기록.
- [ ] `damaged_tactile_block` FP/FN 샘플링 큐 작성 → 검증: threshold `0.50/0.60/0.75`별 TP/FP/FN/TN 요약과 50~100장 review manifest 생성 계획.
- [ ] `tactile_damage_area` 정책 유지 확인 → 검증: 자동/음성 신고 대상에서 제외, 보조 display/debug로만 유지, 재학습 후보는 별도 review queue로 분리.
- [ ] latency 측정 설계 작성 → 검증: cold load/warm request, CPU/GPU, backend ASGI/PWA server-v2, 실폰 field를 분리한 측정 표준 기록.
- [ ] ONNX/export preflight → 검증: selected `best.pt` 고정, 디스크 여유, export 산출물 local-only, PT/ONNX metric 또는 tensor smoke 조건 정리.
- [ ] external/blind validation manifest 초안 → 검증: `source_type`, `privacy_status`, `location_status`, `split_key`, `label_status`, `exclude_reason` 필드 포함.
- [ ] model card/evidence registry 갱신 후보 정리 → 검증: Stage1 metric, image-level presence, real ASGI smoke, field/Android/browser latency 미검증을 분리해 기록.
- [ ] 실행 문서 인계 → 검증: 실제 실행한 검증만 PASS로 쓰고, 미실행 항목은 BLOCKED/PENDING 및 safe alternative와 함께 남김.

## 리스크/확인 필요
- `plans/daily/2026-05-24.md`는 2026-05-23 오전 상태를 일부 반영해 “학습 진행 중/real adapter 미완료” 표현이 stale이다. 최신 근거는 2026-05-23 daylog와 final selection 문서다.
- Stage1 metric은 test split과 saved prediction 기준이다. 실폰 field 정확도, camera frame latency, browser/ONNX latency 근거는 아직 없다.
- threshold `0.50`은 초기값이다. 자동 신고 오탐 비용이 커지면 `0.60` 또는 `0.75` 상향 후보를 검토하되 recall 하락을 같이 기록해야 한다.
- `tactile_damage_area`는 작은 bbox/라벨 경계 문제로 FP/FN 위험이 높다. 신고 기준으로 되돌리지 말고 보조 정보와 데이터 개선 루프로 유지한다.
- 기본 upload limit `8MB`에서 413 사례가 있었다. 실서비스 이미지 크기 정책과 `MAX_UPLOAD_BYTES`는 확인 필요다.
- selected Stage1 후보에는 아직 source-controlled ONNX/export 근거가 없다. 기존 ONNX 동등성은 v2 baseline용이다.
- `runs/`, `logs/`, `.pt`, `.onnx`, dataset images/labels는 local-only다. PR/보고에는 요약 문서만 근거로 남긴다.
- 대형 다운로드, 새 full training, 운영 배포, secret/API key, 외부 업로드, 공공기관 자동 제출은 자동 계획에서 직접 실행하지 않는다. safe alternative는 CPU-only parser, saved prediction evaluator, fixture, dry-run config, local adapter다.

## 병렬 에이전트 활용 메모
- 사용함.
- Product/상위 문서 조사 에이전트: product 목표, done 기준, fake/server/model 근거 분리, 신규 feature slice 후보를 확인했다.
- Scheduler/최근 로그 조사 에이전트: `plans/daily/2026-05-25.md` 부재, `plans/features`/`plans/verify`/`PM` 산출물 부재, 2026-05-23 모델 진행 완료와 2026-05-24 plan stale 지점을 확인했다.
- Model/Data 조사 에이전트: Stage1 후보 수치, reviewed dataset 상태, health-only smoke 전제, ONNX/browser latency gap, safe alternative를 확인했다.
- 통합 결론: 2026-05-25는 catch-up 반복보다 Stage1 후보의 실제 `server-v2` inference evidence, threshold review, latency 설계, blind validation/manifest, ONNX preflight를 우선한다.