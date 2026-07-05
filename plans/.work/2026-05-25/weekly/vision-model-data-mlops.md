# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps weekly lane note (2026-W22)

## 이번 주 목표 후보

- Stage1 YOLO26s 후보를 이번 주 모델 기준선으로 고정한다. 근거: `Stage1 best.pt`, test all mAP50-95 `0.730`, `damaged_tactile_block` threshold `0.50` image-level F1 `0.922151`.
- threshold `0.50/0.60/0.75` FP/FN review pack을 만든다. 자동 신고 오탐 부담이 크면 threshold 상향 후보를 남기되 recall 하락을 같이 기록한다.
- Stage1 `/detect/v2` payload를 `/reports/v2`와 CSV/JSON/GeoJSON export trace에 연결한다. DB가 막히면 PASS가 아니라 schema fixture/handler trace로만 기록한다.
- external/blind validation manifest를 준비한다. 새 대형 다운로드 없이 `source_type`, `label_status`, `privacy_status`, `location_status`, `exclude_reason`부터 고정한다.
- model evidence registry/model card를 최신화한다. fake-v2, health-only, real ASGI smoke, saved metric, Android/browser latency 미검증을 분리한다.
- ONNX/export는 preflight 중심으로 둔다. 실제 export는 디스크·환경 gate 통과 시에만 실행하고, 기존 v2 ONNX equivalence를 YOLO26s 근거로 재사용하지 않는다.
- PM 5/25 feature batch는 모델 slice가 제외됐고 verify가 sandbox quota로 blocked라, 이번 주 모델 계획은 “검증 대기 신규 기능”과 별도로 운영한다.

## 날짜별/단계별 체크리스트

- 월 2026-05-25: 주간 기준선 정리 -> 검증: `plans/weekly/2026-W22.md` 부재, `plans/weekly/2026-W21.md`, 최근 daylog, `/home/ddobagi/PM/status/*` 반영 여부 기록.
- 월 2026-05-25: runtime/data guard 확인 -> 검증: `git status`, `df -h`, `.pt/.onnx/runs/datasets` Git 추적 금지 여부를 표로 기록.
- 화 2026-05-26: Stage1 threshold review pack 작성 -> 검증: saved prediction/eval 결과로 `0.50/0.60/0.75` TP/FP/FN/TN, precision/recall/F1 재대조.
- 화 2026-05-26: FP/FN review queue manifest 설계 -> 검증: `image_path`, `gt_has_damage`, `pred_conf`, `threshold_bucket`, `review_reason`, `privacy_status`, `split_key` 필드 포함.
- 수 2026-05-27: Stage1 real payload fixture 고정 -> 검증: `model_key=custom_tactile`, `class_name=damaged_tactile_block`, `threshold_used=0.5`, bbox, GPS, heading payload 1~2건 선정.
- 수 2026-05-27: detect → report → export trace 인계 -> 검증: DB 가능 시 `/reports/v2` 저장과 export 대조, DB 불가 시 fixture trace를 PARTIAL로 표기.
- 목 2026-05-28: external/blind validation preflight -> 검증: AI Hub 159/VL 후보를 새 다운로드 없이 manifest schema와 제외 기준으로만 정리.
- 목 2026-05-28: class `1..3` 한국 GT 계획 보강 -> 검증: 킥보드/자전거, 공사 장애물, 포트홀별 source/privacy/split/label 상태 정의.
- 금 2026-05-29: latency 측정 표준 작성 -> 검증: cold load, warm request, ASGI, PWA `server-v2`, Android field, CPU/GPU를 분리한 표 작성.
- 금 2026-05-29: ONNX/export preflight -> 검증: selected checkpoint, 예상 산출물 경로, 디스크 여유, local-only, PT/ONNX smoke 조건 기록.
- 토 2026-05-30: model evidence registry/model card 갱신 -> 검증: Stage1 metric, ASGI smoke 5장, field/browser latency 미검증, `tactile_damage_area` 보조 정책 분리.
- 일 2026-05-31: 다음 주 인계 정리 -> 검증: “학습 가능”, “데이터 필요”, “환경 필요”, “제품 판단 필요”, “C 작업”으로 분류.

## 검증 계획

- Static: `git status`, `df -h`, `git ls-files`로 대형 산출물 staging 금지와 디스크 gate 확인.
- Model Eval: 기존 saved prediction label과 report만 사용해 threshold sweep을 재대조한다. 새 inference를 실행하지 않은 경우 반드시 그렇게 쓴다.
- Headless E2E: Stage1 `/detect/v2` ASGI image smoke는 plumbing/latency 근거로만 사용한다. 실폰 field 성능으로 쓰지 않는다.
- Integration: `damaged_tactile_block`만 `/reports/v2` 저장 대상인지 확인하고, `tactile_damage_area`, `normal_tactile_block`, COCO/general은 저장 제외로 검증한다.
- GIS/Ops: export trace는 CSV/JSON/GeoJSON shape, `model_key`, `trigger`, `auto_reported`, 위치 품질, v2 metadata 보존을 확인한다.
- Device E2E: Android 목걸이, TalkBack, browser latency는 이번 lane 단독 완료 기준이 아니다. 실행되면 integration lane 근거와 연결한다.

## 리스크/확인 필요

- `plans/weekly/2026-W22.md`는 현재 확인되지 않았다. 이 note가 W22 Vision lane 초안이다.
- 프로젝트 내부 `PM/`은 없지만 `/home/ddobagi/PM/status`에는 5/25 feature/verify/product-audit 산출물이 있다. 경로 혼선을 계획에 남긴다.
- 5/25 verify는 quota 오류로 `blocked`, `push_ready=false`다. PM feature batch 결과를 검증 완료로 쓰지 않는다.
- Stage1 image smoke는 ASGI 저장 이미지 5장 근거다. Android 카메라, 목걸이 착용, browser latency, field accuracy 근거가 아니다.
- `tactile_damage_area`는 작은 bbox/라벨 경계 병목이 있어 자동/음성 신고 대상에서 제외하고 보조 표시·데이터 개선 큐로 유지한다.
- 디스크 사용률과 로컬 데이터 규모 때문에 full training, full sampling, 대형 다운로드, 외부 업로드는 C 작업으로 둔다.
- 모델 weight, `.onnx`, `runs/`, dataset image/label, AI Hub 원본은 local-only다.
- product audit 보강점은 v2 canonical summary와 scheduler-safe/safe alternative 컬럼이다. 모델 계획에도 `requires_env`, `requires_device`, `safe_alternative`를 붙인다.

## 병렬 에이전트 활용 메모

- 이번 lane note 작성에는 별도 하위 에이전트는 쓰지 않았다. 최종 산출물이 단일 markdown이라 충돌을 피했다.
- 대신 product/PM/daylog/model 문서는 병렬 shell 조회로 나눠 확인했고, 결론을 단일 note로 통합했다.
- 실제 주간 실행에서는 `Model Threshold/Evidence`, `Backend/Integration Trace`, `External Validation Manifest`, `Product Evidence Registry`를 병렬화할 수 있다. 단, daylog와 최종 evidence registry는 한 에이전트가 통합한다.