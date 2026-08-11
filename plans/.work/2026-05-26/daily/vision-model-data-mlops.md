# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps lane note (2026-05-27)

## 최근 진행 근거
- `product/vision.md`, `product/done-criteria.md`, `product/decisions.md` (2026-05-18 이후): 한국 보행 환경 validation/test 우선, fake/model/field 근거 분리, 모델 weight·dataset·runs local-only 원칙을 유지한다.
- `docs/current_status.md` (기준일 2026-05-25): reviewed YOLO26s 3-class Stage1 `best.pt`가 MVP/backend integration 후보이며, `/detect/v2` real/yolo lazy provider와 Stage1 runtime config가 준비된 상태다.
- `docs/execution/2026-05-23_reviewed_yolo26s_final_selection.md`: Stage1 test all mAP50-95 `0.730`, `damaged_tactile_block` threshold `0.50` image-level F1 `0.922151`; `0.75`는 precision 우선 fallback 후보다.
- `runs/reports/stage1_yolo26s_reviewed_test_presence_20260523_203422/SUMMARY.md`: threshold `0.50/0.60/0.75`별 TP/FP/FN/TN과 precision/recall/F1 근거가 남아 있어 새 inference 없이 review pack 작성 가능하다.
- `docs/execution/2026-05-25_stage1_detect_v2_image_smoke_rerun.md`: 단일 phone smoke 이미지는 `damaged_tactile_block` 미검출이었고, target 기대를 켜지 않은 계약/응답 smoke로만 PASS다. positive/field 성능 근거가 아니다.
- `daylog/2026-05-25.md`: 대용량 `datasets/` 이미지/라벨은 정리되어 skeleton 수준만 남았고, `runs/detect`, `runs/reports`, Stage1 `best.pt`, `yolo26n.pt`, runtime config는 보존됐다.
- `daylog/2026-05-26.md`: PM feature commit `1320a07`은 `/detect/v2` adapter seam, reports v2 metadata filter, PWA Wake Lock, opt-in local TTS fallback을 구현했으나 verify 실패로 push-ready가 아니다.
- `/home/ddobagi/PM/status/2026-05-26-verify.md`, verify report: Hanium verify status `fail`, `push_ready=False`; 원인은 `apps/web/app/_walksafe/hooks/useWakeLock.ts:71` lint 오류이며 PostGIS no-skip 검증도 Docker socket 권한으로 차단됐다.
- `/home/ddobagi/PM/status/product-audit/2026-05-26.json`: product 5개 문서 모두 존재, Hanium heuristic score `100`. 다만 2026-05-26 product-audit markdown/report는 확인되지 않았다.
- `/home/ddobagi/PM/status/2026-05-25-automation-metrics.md`: 최신 automation metrics markdown은 5/25 기준이며 `push_ready: 0`, `pushed: 0`; 5/26 automation metrics markdown은 확인되지 않았다.
- `plans/daily/2026-05-27.md`: 현재 없음.

## 내일 목표 후보
- 1순위: Stage1 `damaged_tactile_block` threshold FP/FN review pack 작성. 자동 신고 오탐 부담을 줄이는 제품 목표와 직접 연결한다.
- 2순위: Stage1 real payload fixture를 고정해 backend/integration의 detect→report→export trace 입력으로 넘긴다.
- 3순위: model evidence registry/model card를 최신화해 metric, ASGI smoke, fake-v2, field 미검증을 분리한다.
- 4순위: external/blind validation manifest preflight를 작성한다. 새 AI Hub 다운로드 없이 schema와 제외 기준만 고정한다.
- 5순위: `/detect/v2` adapter seam verify 지원. PM commit은 lint 실패 상태이므로 모델 lane에서는 backend/model 계약 검증 후보만 분리한다.
- 6순위: ONNX/export는 preflight만 한다. 실제 export·대형 inference·재학습은 실행 후보에서 제외한다.

## 상세 체크리스트 초안
- [ ] Stage1 threshold review pack 작성 → 검증: `runs/reports/.../presence_threshold_sweep_summary.csv`와 `SUMMARY.md`에서 `0.50/0.60/0.75` TP/FP/FN/TN, precision/recall/F1 재대조
- [ ] FP/FN review queue manifest 초안 작성 → 검증: `image_path`, `gt_has_damage`, `pred_conf`, `threshold_bucket`, `review_reason`, `privacy_status`, `split_key`, `safe_action` 필드 포함
- [ ] Stage1 payload fixture 1~2건 선정 → 검증: `model_key=custom_tactile`, `class_name=damaged_tactile_block`, `threshold_used=0.50`, bbox, optional gps/heading 포함 여부 확인
- [ ] `tactile_damage_area` 정책 재확인 → 검증: 자동/음성 신고 제외, 보조 bbox/debug와 데이터 개선 큐로만 남는지 `backend_api_contract.md`와 rollout checklist 대조
- [ ] model evidence registry 초안 작성 → 검증: Stage1 metric, ASGI image smoke, single negative smoke, fake-v2, Android/browser latency 미검증을 별도 등급으로 표기
- [ ] external/blind validation manifest preflight → 검증: 새 다운로드 없이 `source_type`, `source_file`, `label_status`, `privacy_status`, `location_status`, `exclude_reason` 필드 정의
- [ ] `/detect/v2` adapter seam backend/model 계약 확인 → 검증: 가능 시 `backend/tests/test_detect_v2.py`, `model/test_two_model_runtime.py`; 불가 시 dependency BLOCKED와 실행 명령 기록
- [ ] ONNX/export preflight → 검증: selected checkpoint, 예상 `.onnx` 경로, 디스크 gate, local-only/commit 금지, PT/ONNX equivalence 조건만 기록
- [ ] 대형 작업 금지 gate → 검증: 새 full training, 대형 다운로드, 외부 업로드, dataset image/label, `.pt/.onnx` staging 없음 확인

## 리스크/확인 필요
- PM 2026-05-26 feature commit `1320a07`은 verify 실패 상태다. safe alternative: adapter seam은 검증 대기 근거로만 반영하고 push-ready로 쓰지 않는다.
- 원본 checkout은 upstream보다 10커밋 behind이고 dirty/untracked가 많다. safe alternative: 실행 전 worktree/commit 기준을 먼저 기록하고 대형 산출물 staging을 금지한다.
- `datasets/` 실제 이미지는 정리되어 skeleton 수준이다. safe alternative: 남아 있는 `runs/reports`, `runs/detect`, manifest, 단일 smoke 이미지, fixture 기반으로만 진행한다.
- PostGIS/Docker 권한 차단으로 detect→report→export 실제 저장 trace는 환경 의존이다. safe alternative: payload/schema fixture와 ASGI/direct handler trace를 PARTIAL로 기록한다.
- Stage1 metric과 ASGI smoke는 실폰 field accuracy, 목걸이 착용 latency, PDF의 경보 지연 1초 달성 근거가 아니다.
- class `1..3` 한국 GT와 4-class 서비스 성능은 아직 미확정이다. safe alternative: 수집/라벨/비식별 manifest schema만 준비한다.
- 모델 weight, `runs/`, `.onnx`, AI Hub 원본, dataset image/label은 local-only다. 외부 업로드, 배포, 공개, cloud storage는 C 작업으로 둔다.

## 병렬 에이전트 활용 메모
- 하위/병렬 에이전트는 사용하지 않았다. 최종 산출물이 단일 lane note이고 파일 수정이 없어서 충돌 가능성을 줄이는 편이 낫다고 판단했다.
- 대신 product, PM status, daylog, model/runtime 문서는 병렬 shell 조회로 나눠 확인했고, 결론은 이 note에서 단일 통합했다.