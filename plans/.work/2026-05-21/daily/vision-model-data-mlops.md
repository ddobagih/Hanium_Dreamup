# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps lane note (2026-05-22)

## 최근 진행 근거
- `product/vision.md`, `product/decisions.md` (2026-05-18): 모델 성능은 한국 보행 환경 validation/test와 field 근거만 제품 성능으로 사용. v2는 class `0` baseline이며 4-class 성능으로 보고 금지.
- `plans/features`, `plans/verify/ready`, `plans/verify/reports` (확인일 2026-05-21): 디렉터리는 있으나 파일 없음. `PM` 디렉터리도 없어 product audit, verify, automation metrics 산출물 반영 불가.
- `plans/daily/2026-05-22.md`: 없음.
- `daylog/2026-05-21.md`: 2026-05-21 실행 문서 0개, 신규 모델 실행 근거 없음. 최신 실행 근거는 2026-05-20 산출물.
- `docs/execution/2026-05-20_model_v3_*`: v3 review queue 196행, privacy pass 196행/126 unique, train staging unique 35장, `datasets/walksafe_kr_v3` validator PASS, 1 epoch smoke training exit `0`.
- `docs/execution/2026-05-20_model_v3_holdout_candidate.md`: holdout candidate 2,282 image/label pairs. v2 외부검증/오류분석에 이미 사용되어 최종 blind test로는 약함.
- `docs/execution/2026-05-20_model_v3_holdout_eval.md`: v2 baseline mAP50-95 `0.499`, v3 smoke `0.494`; smoke 비교는 공식 개선/퇴보 근거가 아님. JPEG restore warning 157회 기록.
- `data_sources/manifests/walksafe_kr_v4_class123_data_plan_2026-05-20.md`: class `1..3` 한국 GT 원본 미확보. 직접 촬영 또는 AI Hub 159 소형 subset manifest가 다음 확장 축.
- `docs/execution/2026-05-20_model_v3_manual_review_pack.md`: manual review 70행 decision template 대기. false positive extra box 47행, missed defect 20행, small/far 3행.
- `docs/execution/2026-05-20_model_near_duplicate_split_audit.md`: dHash near-duplicate 후보 120행, filename group 미커버 106행.

## 내일 목표 후보
- 1순위: v4 class `1..3` 데이터 intake manifest thin slice. 4개 위험 객체 제품 목표를 앞으로 미는 신규 feature slice로, 실제 다운로드 없이 직접 촬영/AI Hub 159 후보 schema와 검증 규칙을 먼저 고정.
- 2순위: 독립 blind holdout 확보 계획 구체화. 기존 holdout candidate가 약하므로 v3/v4 평가용 새 source, split key, privacy gate를 분리.
- 3순위: v3 manual review 70행 처리 기준 정리. decision template 미입력 행 수와 `accept_existing_gt_positive`, `needs_bbox_relabel`, `hard_negative_empty_label`, `exclude_unclear_or_policy` 기준을 고정.
- 4순위: near-duplicate/split gate 보강. dHash 후보 120행과 filename group을 대조해 same-group same-split 또는 eval 제외 후보로 표시.
- 5순위: JPEG restore warning 영향 점검. symlink 원본 수정 가능성 확인 방법과 copy-on-write eval 정책을 문서화.
- 6순위: v3 evidence 분리. validator PASS, smoke training PASS, holdout-candidate 참고 평가를 공식 성능과 분리.
- 7순위: ONNX/browser latency는 기기/브라우저가 있을 때만 실행. 없으면 측정 harness/조건만 남기고 `PENDING/BLOCKED`.

## 상세 체크리스트 초안
- [ ] 시작 gate 확인 → 검증: `df -h`, v2/v3 weight hash, `git ls-files '*.pt' '*.onnx' 'runs/**' 'datasets/**/images/**' 'datasets/**/labels/**'` 기록.
- [ ] scheduler/PM 산출물 재확인 → 검증: `plans/features`, `plans/verify/*`, `PM/status`, `PM/reports` 존재 여부 기록.
- [ ] v4 class `1..3` intake manifest 초안 작성 → 검증: `class_id`, `source_type`, `source_file`, `image_path`, `label_status`, `privacy_status`, `location_status`, `split_key`, `exclude_reason` 필드 정의.
- [ ] AI Hub 159 소형 subset 후보 재확인 → 검증: `School`, `Building_area`, `Bridge` zip 보유 여부와 예상 용량만 기록. 실제 다운로드는 보류.
- [ ] 직접 촬영 후보 shot list 작성 → 검증: 킥보드/자전거, 공사 장애물, 포트홀별 positive/negative 목표와 장소 단위 split key 기준 기록.
- [ ] manual review 70행 decision 상태 집계 → 검증: decision template 빈 값 수, bucket별 미해결 수, label 반영 금지 항목 기록.
- [ ] v3 label/readiness 최신 기준 정리 → 검증: hard-negative 55행, bbox 12행 사용자 수용, small/far 50행 제외, min-box 9행 제외 상태가 충돌 없이 설명됨.
- [ ] near-duplicate/split 후속 표 작성 → 검증: dHash 후보 120행, filename group 미커버 106행을 split 위험으로 표시.
- [ ] holdout 오염 방지 점검 → 검증: train staging과 holdout exact path overlap 0 재확인, 기존 holdout candidate를 최종 blind test로 쓰지 않음.
- [ ] JPEG restore 영향 확인 계획 작성 → 검증: hash/mtime 확인 또는 copy-on-write eval 대안 문서화.
- [ ] v3 validator 재실행 가능 시 수행 → 검증: `data_sources/scripts/validate_yolo_dataset.py`로 v3와 holdout candidate PASS/FAIL 기록.
- [ ] 실행 문서 작성 → 검증: 새 full training, full sampling, 4-class metric, 공식 v3 성능을 실행하지 않았다면 PASS로 쓰지 않음.

## 리스크/확인 필요
- `/` 사용률 99%, 가용 16~17G 수준이라 full sampling, VL1+VS1 전체 inference, 대형 다운로드, full training은 자동 실행 금지. 안전 대안은 manifest, bounded CSV, validator.
- class `1..3` 한국 GT가 없어 4-class metric과 정확도 90% 달성 보고 금지. 안전 대안은 intake manifest와 라벨 기준 수립.
- v3 smoke weight는 35장 unique staging + 1 epoch 결과다. 공식 성능 근거로 사용 금지.
- holdout candidate 2,282장은 최종 blind test로 약하다. 새 blind 후보 확보 전에는 참고 평가로만 분리.
- JPEG restore warning 157회는 symlink 원본 수정 가능성이 있다. 확인 전 추정으로만 기록.
- ONNX browser latency는 실제 브라우저/기기 필요. CPU ONNX latency로 대체 금지.
- 2026-05-20 문서에는 단계별 중간 문구가 섞여 있으므로 최신 기준은 validator, materialization, smoke, holdout eval 문서로 맞춘다.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 이유: 이번 작업은 lane note 작성을 위한 근거 수집이며, 필요한 범위가 product 문서, daylog, execution 문서, manifest count 확인으로 충분히 분리되어 병렬 shell 조회만으로 처리 가능했다.
- 통합 결론: 2026-05-22는 새 학습 반복보다 v4 class `1..3` intake manifest, 독립 holdout 계획, v3 manual/split/integrity gate를 우선한다.