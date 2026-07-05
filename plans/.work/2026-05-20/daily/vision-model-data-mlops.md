# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps lane note (2026-05-21)

## 최근 진행 근거
- `product/vision.md`, `product/decisions.md` (2026-05-18): 모델 성능은 한국 보행 환경 validation/test와 실폰/착용형 근거만 제품 성능으로 사용. v2는 class `0: damaged_tactile_block` baseline이며 4-class 성능/정확도 90% 달성으로 쓰지 않음.
- `product/backlog.md`, `product/done-criteria.md` (확인일 2026-05-20): `P0-006 v3 model-data 작은 curation slice`, `P1-005 class 1..3 한국 GT 확보 계획`이 모델 lane의 제품 진전 후보.
- `plans/features`, `plans/verify/ready`, `plans/verify/reports` (확인일 2026-05-20): 디렉터리는 있으나 파일 없음. `PM/status`, `PM/reports`, product audit, automation metrics는 `PM` 디렉터리 자체가 없어 반영할 산출물 없음.
- `plans/daily/2026-05-21.md` (작성일 2026-05-20): 5/21 통합 계획은 `feature-growth` 기준이며 model lane은 v3/v4 gate 명확화와 class `1..3` manifest slice를 우선 후보로 둠.
- `daylog/2026-05-20.md`: v3 review queue 196행, privacy pass 196행/126 unique images, v3 train staging unique 35장, holdout candidate 2,282쌍, validator PASS, v3 1 epoch smoke training 완료를 기록. 단 smoke metric은 공식 성능 아님.
- `docs/execution/2026-05-20_model_v3_smoke_training.md`: `datasets/walksafe_kr_v3` train 28장/val 7장으로 1 epoch smoke training exit `0`. mAP50-95 `0.45985`는 pipeline smoke용.
- `docs/execution/2026-05-20_model_v3_holdout_eval.md`: holdout 후보에서 v2 baseline mAP50-95 `0.499`, v3 smoke `0.494`; v3 smoke 개선/퇴보 결론 금지.
- `docs/execution/2026-05-20_model_v3_holdout_candidate.md`: holdout candidate 2,282 image/label pairs, positive 2,082장/5,326 boxes, negative 200장. v2 외부검증/오류분석에 이미 사용되어 최종 blind test로는 약함.
- `data_sources/manifests/walksafe_kr_v4_class123_data_plan_2026-05-20.md`: class `1..3` 한국 GT 원본은 repo와 `/home/ddobagi/Downloads` 기준 미확보. 직접 촬영 또는 AI Hub 159 소형 subset이 다음 확장 축.
- `docs/execution/2026-05-20_model_near_duplicate_split_audit.md`: dHash near-duplicate 후보 120행, filename group 미커버 106행. split 확정 전 수동 확인 필요.
- `docs/execution/2026-05-20_model_v3_holdout_eval.md`, `daylog/2026-05-20.md`: holdout eval 중 `corrupt JPEG restored and saved` warning 157회. symlink target 원본 수정 가능성 확인 필요.

## 내일 목표 후보
- 1순위: v4 class `1..3` 데이터 확보 manifest thin slice 시작. 제품 목표인 4개 위험 객체로 전진하는 신규 feature slice이며, 직접 촬영/AI Hub 159 소형 subset 후보를 `source_type`, `privacy_status`, `location_status`, `split_key`, `exclude_reason` 기준으로 먼저 관리.
- 2순위: v3 manual review 70행 decision template 입력/반영 계획 고정. 자동 후보를 바로 학습 데이터로 넣지 않고 `include/hold/exclude/relabel` 근거를 명확히 분리.
- 3순위: near-duplicate/split gate 정리. dHash 후보 120행과 filename group을 대조해 train/val/holdout 누수 위험을 줄이고, 기존 holdout candidate는 최종 blind test가 아님을 계속 표시.
- 4순위: v3 artifact integrity 점검. holdout eval의 JPEG restore warning 157회가 원본 symlink target을 수정했는지 hash/mtime 기반으로 확인하고, 필요하면 향후 eval은 copy-on-write 또는 read-only source 정책으로 계획.
- 5순위: v3 smoke 결과를 evidence registry에 정리. `validator PASS`, `1 epoch smoke PASS`, `holdout-candidate 참고 평가`를 공식 성능과 분리해 보고 문구를 고정.
- 6순위: browser/ONNX Runtime Web latency와 v3 ONNX export는 기기/브라우저 환경이 있을 때만 측정. 없으면 `PENDING/BLOCKED`로 남김.
- 7순위: full 2,347장 sampling, VL1+VS1 전체 inference, full training, 대형 다운로드는 디스크/개인정보/split gate 전 자동 실행하지 않음.

## 상세 체크리스트 초안
- [ ] 시작 gate 확인 → 검증: `df -h /home/ddobagi/Code/hanium-dreamup /home/ddobagi/Downloads`, v2/v3 weight hash, `git ls-files '*.pt' '*.onnx' 'runs/**' 'datasets/**/images/**' 'datasets/**/labels/**'`로 대형 산출물 추적 여부 기록.
- [ ] scheduler/PM 산출물 재확인 → 검증: `plans/features`, `plans/verify/ready`, `plans/verify/reports`, `PM/status`, `PM/reports` 존재 여부를 PASS/없음으로 기록.
- [ ] v4 class `1..3` manifest fixture 작성 → 검증: class별 positive/negative 목표, 직접 촬영 후보 필드, AI Hub 159 후보 필드, 개인정보/위치/split 필드가 누락 없이 정의됨.
- [ ] AI Hub 159 소형 subset 보유 여부 재확인 → 검증: `School`, `Building_area`, `Bridge` zip 존재 여부와 예상 용량만 기록. 실제 다운로드는 디스크 gate와 승인 전 보류.
- [ ] manual review 70행 decision 반영 준비 → 검증: false positive extra box 47행, missed defect 20행, small/far 3행을 입력 템플릿 기준으로 집계하고 미입력 행 수 기록.
- [ ] v3 label/readiness manifest 갱신 기준 정리 → 검증: hard-negative 55행, bbox 12행 사용자 수용, small/far 50행 제외, min-box 9행 제외/hold 상태가 충돌 없이 설명됨.
- [ ] near-duplicate/split audit 후속 표 작성 → 검증: dHash group, filename group, source image basename 중복을 대조하고 split 확정 전 학습/평가 혼입 금지 표시.
- [ ] holdout candidate 오염 방지 점검 → 검증: train staging과 exact path overlap 0 재확인, v2 분석에 사용된 holdout 후보를 최종 blind test로 쓰지 않음.
- [ ] JPEG restore warning 영향 확인 계획 작성 → 검증: eval 전후 hash/mtime 확인 방법 또는 copy 기반 eval 대안을 문서화. 실제 원본 수정이 확인되기 전 추정으로 표시.
- [ ] v3 validator 재실행 가능 시 수행 → 검증: `python3 data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_kr_v3`, `datasets/walksafe_kr_v3_holdout_candidate` PASS/FAIL 기록.
- [ ] ONNX/browser latency gate 판단 → 검증: 브라우저/기기 확보 시 p50/p95를 기록하고, 미확보 시 backend CPU ONNX latency로 대체하지 않음.
- [ ] 실행 문서 입력 정리 → 검증: 새 학습, 4-class metric, 공식 v3 성능, field 성능을 실행하지 않았다면 PASS로 쓰지 않음.

## 리스크/확인 필요
- `/` 사용률 99%, 가용 16~17G 수준이라 full sampling, VL1+VS1 전체 inference, 대형 다운로드, full training은 C 작업에 가깝다. 안전한 대안은 bounded CSV/manifest 작업과 validator 재실행.
- class `1..3` 한국 GT가 없으므로 4-class metric과 정확도 90% 달성 보고는 금지. 안전한 대안은 직접 촬영/AI Hub 소형 subset manifest와 라벨 기준 수립.
- v3 smoke weight는 35장 unique staging + 1 epoch 결과라 공식 성능 근거가 아니다. 안전한 대안은 pipeline smoke PASS로만 표기.
- holdout candidate 2,282장은 v2 외부검증/오류분석에 이미 쓰여 최종 blind test로 약하다. 안전한 대안은 v3 학습/threshold tuning과 분리하고 새 blind 후보 확보 계획을 둠.
- dHash near-duplicate 후보 120행은 자동 후보일 뿐 final split이 아니다. 안전한 대안은 인간 검수 전 same-group same-split 보수 정책.
- holdout eval의 JPEG restore warning 157회는 원본 symlink target 수정 가능성이 있다. 확인 전에는 추정으로만 쓰고, 다음 eval은 원본 보호 방식을 계획.
- PM product audit, verify scheduler, automation metrics 산출물이 현재 없다. 내일 새 파일이 생기면 merge 단계에서 반영 필요.
- v3 ONNX export 완료 근거와 browser/ONNX Runtime Web latency 근거는 없다. backend CPU ONNX 결과를 브라우저 성능으로 대체하지 않음.

## 병렬 에이전트 활용 메모
- 사용함.
- Agent 1: product/scheduler/PM 산출물 존재 여부와 제품 우선순위 확인. 결론: `plans/features`, `plans/verify`, `PM` 산출물이 없어 product 문서와 5/20 모델 산출물 기준으로 계획해야 함.
- Agent 2: 최근 daylog/execution 기반 완료·미완료·리스크 확인. 결론: v3 privacy/materialization/validator/smoke는 진행됐지만 공식 성능, 4-class metric, full training은 미완료.
- Agent 3: model/data/datasets/scripts 상태 확인. 결론: `datasets/walksafe_kr_v3`, privacy sanitized staging pool, holdout candidate, v2 ONNX 상태와 실행 가능한 validator 명령을 확인.
- 통합 결론: 내일은 새 학습 반복보다 v4 class `1..3` 데이터 manifest, v3 manual/split/holdout gate, artifact integrity를 우선한다.