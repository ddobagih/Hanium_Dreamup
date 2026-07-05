# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps lane note (2026-05-26)

## 최근 진행 근거
- `product/vision.md`, `product/backlog.md`, `product/done-criteria.md`, `product/decisions.md` (2026-05-18~2026-05-24 반영): 한국 보행 환경 validation/test 우선, fake/model/field 근거 분리, 모델 weight/dataset/runs local-only 원칙, `server-v2` 장애 시 fake 자동 fallback 금지.
- `docs/current_status.md` (기준일 2026-05-24): reviewed YOLO26s Stage1 `best.pt`가 MVP/backend integration 후보이며 `/detect/v2` real/yolo lazy provider와 Stage1 threshold config가 준비됨.
- `docs/execution/2026-05-23_reviewed_yolo26s_final_selection.md`: Stage1 best val mAP50-95 `0.73982`, test all mAP50-95 `0.730`; Stage2는 낮아 제외. `damaged_tactile_block` threshold `0.50` image-level F1 `0.922151`, `0.75`는 precision 우선 fallback.
- `docs/execution/2026-05-24_stage1_detect_v2_image_smoke.md`: Stage1 `/detect/v2` ASGI real image smoke PASS. 5장 모두 `damaged_tactile_block` 감지, avg latency `1055.5ms`, cold max `4267.43ms`, warm min `233.06ms`. 실폰/field 근거는 아님.
- `docs/walksafe-v2/reviewed_model_rollout_checklist.md` (2026-05-24): 다음 단계는 Stage1 payload → `/reports/v2` → export trace, threshold FP/FN review, 실폰 latency, blind/external validation.
- `daylog/2026-05-25.md`: 5/25 PM feature batch는 GeoJSON export, navigation timing fixture, motion ROI fixture, voice intent schema 중심. Vision/Model 신규 slice는 선택되지 않았고, PM verify는 sandbox quota 오류로 blocked.
- `/home/ddobagi/PM/status/2026-05-25-features.md`: `WS-VMD-*` model/data slices는 대형 산출물·로컬 데이터 리스크 때문에 5/25 batch에서 보류됨.
- `/home/ddobagi/PM/status/2026-05-25-product-audit.md`: Hanium product 품질 91점. v2 canonical summary, Stage1 YOLO26s/threshold 최신화, scheduler-safe/safe alternative 보강 권고.
- `/home/ddobagi/PM/status/2026-05-25-verify.md`, verify report: Hanium verify `blocked`, push_ready `False`; 원인은 sandbox mount quota.
- 현재 checkout 확인: `plans/daily/2026-05-26.md`는 없음. 원본 checkout은 upstream보다 10커밋 behind이고 dirty 상태. `/` 사용률은 92%, `datasets` 약 250G, `runs` 약 21G.

## 내일 목표 후보
- 1순위: Stage1 `damaged_tactile_block` threshold/FP-FN review pack을 만든다. 제품 목표는 조용한 자동 신고의 오탐 부담을 줄이는 것. 새 full training 없이 기존 prediction label/eval 결과와 제한된 샘플 manifest를 사용한다.
- 2순위: Stage1 real detection payload → report/export trace용 모델 fixture를 고정한다. DB가 막히면 실제 저장 PASS가 아니라 schema/payload fixture와 backend 인계 체크만 남긴다.
- 3순위: external/blind validation manifest preflight를 작성한다. AI Hub 159/외부 데이터 다운로드나 업로드 없이 기존 후보 문서와 manifest 필드만 정한다.
- 4순위: model evidence registry를 갱신한다. fake-v2, health-only, real ASGI smoke, image-level metric, Android/browser latency 미검증을 서로 다른 등급으로 분리한다.
- 5순위: ONNX/export는 preflight만 한다. selected `best.pt`, 디스크 여유, local-only 산출물, PT/ONNX equivalence 조건을 확인하고 실제 export는 별도 승인/환경 gate 뒤로 둔다.

## 상세 체크리스트 초안
- [ ] Stage1 threshold review pack 초안 작성 → 검증: 기존 `runs/reports/stage1_yolo26s_reviewed_test_presence_20260523_203422/`와 `docs/execution/2026-05-23_reviewed_yolo26s_final_selection.md`의 `0.50/0.60/0.75` precision/recall/F1을 재대조하고, 새 inference 없이 표로 정리.
- [ ] FP/FN review queue 50~100장 후보 manifest 설계 → 검증: `image_path`, `gt_has_damage`, `pred_conf`, `threshold_bucket`, `review_reason`, `privacy_status`, `split_key` 필드 포함 여부 확인.
- [ ] `tactile_damage_area` 정책 재확인 → 검증: `/reports/v2` 저장 대상에서 제외, 보조 display/debug 및 데이터 개선 큐로만 남기는지 rollout checklist와 current status 기준으로 대조.
- [ ] Stage1 real payload fixture 고정 → 검증: 5/24 smoke의 `model_key=custom_tactile`, `class_name=damaged_tactile_block`, `threshold_used=0.5`, bbox `{x,y,width,height}`, GPS/heading 포함 payload 1~2건을 trace 입력 후보로 지정.
- [ ] detect → report → export trace 인계 조건 정리 → 검증: DB 가능 시 `/reports/v2` 저장과 CSV/JSON/GeoJSON export, DB 불가 시 schema fixture만 PARTIAL로 표기.
- [ ] external/blind validation manifest preflight 작성 → 검증: AI Hub 159/VL1+VS1 후보를 새 다운로드 없이 `source_type`, `source_file`, `label_status`, `privacy_status`, `location_status`, `exclude_reason` 필드로 정리.
- [ ] COCO helper 정책 smoke 기준 보강 → 검증: allowlist 8개 class, `model_key=coco_general`, 신고 저장 금지, risk evaluator 입력 전용, latency 필드 기록 기준 확인.
- [ ] model evidence registry 갱신 → 검증: fake-v2 demo, health-only readiness, real ASGI image smoke, image-level metric, browser/Android latency 미검증을 별도 등급으로 기록.
- [ ] ONNX/export preflight만 수행 → 검증: actual export 실행 없이 selected checkpoint, 예상 산출물 경로, 디스크 gate, local-only/commit 금지 기준을 확인.
- [ ] 대형 작업 금지 확인 → 검증: 새 full training, 대형 다운로드, 외부 업로드, `.pt`/`.onnx`/dataset image/label staging 없음.

## 리스크/확인 필요
- PM verify가 2026-05-25 quota 오류로 blocked라 5/25 feature batch를 검증 완료로 보지 않는다. safe alternative: marker/실행 보고 기준으로 “검증 대기”만 반영.
- 원본 checkout이 dirty이고 upstream보다 10커밋 behind다. safe alternative: 내일 모델 계획은 실행 전 worktree/commit 기준을 먼저 확인하고, `git add .`나 대형 산출물 staging을 금지한다.
- 디스크 사용률 92%, `datasets` 250G라 full sampling/train/export는 위험하다. safe alternative: CPU-only parser, 기존 saved prediction label, manifest-only review를 우선한다.
- Stage1 image smoke는 ASGI/저장 이미지 5장 plumbing 근거다. 실폰 카메라, 목걸이 착용, browser latency, 현장 accuracy 근거로 쓰지 않는다.
- `damaged_tactile_block` threshold `0.50`은 admin-reviewed 자동 신고 초기값이다. 오탐 비용이 커지면 `0.60` 또는 `0.75` 상향 후보를 검토한다.
- class `1..3` 한국 GT와 4-class 서비스 성능은 아직 미확정이다. safe alternative: blind validation manifest와 라벨링 기준만 준비한다.
- 모델 weight, `runs/`, dataset image/label, AI Hub 원본, `.onnx`는 계속 local-only다. 외부 업로드/공개/배포는 C 작업으로 둔다.

## 병렬 에이전트 활용 메모
- 하위/병렬 에이전트는 사용하지 않았다. 이번 작업은 최종 lane note 작성용 read-only 근거 수집이었고, 같은 최종 문서를 여러 에이전트가 나눠 쓰면 충돌 가능성이 더 컸다.
- 대신 product/PM/daylog/model 문서를 병렬 shell read로 나눠 확인했고, 결론은 이 note에서 단일 통합했다.