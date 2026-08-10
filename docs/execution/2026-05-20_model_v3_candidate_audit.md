# WalkSafe KR v3 후보/정책 감사 - Agent A

## 범위

- 작업 위치: `/home/ddobagi/Code/hanium-dreamup`
- 감사 대상:
  - `data_sources/manifests/walksafe_kr_v3_candidate_index_2026-05-19.csv`
  - `data_sources/manifests/walksafe_kr_v3_candidate_index_summary_2026-05-19.json`
- 새 학습은 수행하지 않았다.
- 기존 manifest/summary/policy 파일은 수정하지 않았다.

## 실행 명령

```bash
python3 - <<'PY'
import csv,json
from collections import Counter
csv_path='data_sources/manifests/walksafe_kr_v3_candidate_index_2026-05-19.csv'
json_path='data_sources/manifests/walksafe_kr_v3_candidate_index_summary_2026-05-19.json'
with open(csv_path,newline='',encoding='utf-8') as f:
    rows=list(csv.DictReader(f))
with open(json_path,encoding='utf-8') as f:
    summary=json.load(f)
checks=[]
checks.append(('rows_total', len(rows), summary.get('rows_total')))
for name,col,key in [
    ('rows_by_source_manifest','source_manifest','rows_by_source_manifest'),
    ('rows_by_policy_decision','policy_decision','rows_by_policy_decision'),
]:
    actual=dict(Counter(r[col] for r in rows))
    expected=summary.get(key)
    checks.append((name, actual, expected))
for name, actual, expected in checks:
    print(f'{name}: actual={actual} expected={expected} result={"PASS" if actual==expected else "FAIL"}')
if any(a!=e for _,a,e in checks):
    raise SystemExit(1)
print('\nstatus')
for k,v in sorted(Counter(r['status'] for r in rows).items()): print(f'{v}\t{k}')
print('\nprivacy_status')
for k,v in sorted(Counter(r['privacy_status'] for r in rows).items()): print(f'{v}\t{k}')
print('\nsplit_policy')
for k,v in sorted(Counter(r['split_policy'] for r in rows).items()): print(f'{v}\t{k}')
PY
```

```bash
git diff --check
```

## 검산 결과

| 항목 | CSV 계산값 | summary JSON 값 | 결과 |
| --- | ---: | ---: | --- |
| rows_total | 61 | 61 | PASS |
| rows_by_source_manifest 합계 | 61 | 61 | PASS |
| rows_by_policy_decision 합계 | 61 | 61 | PASS |

### policy_decision별 count

| policy_decision | CSV count | summary JSON count | 결과 |
| --- | ---: | ---: | --- |
| `include_as_hard_negative` | 33 | 33 | PASS |
| `include_after_box_review` | 12 | 12 | PASS |
| `hold_until_min_box_policy_review` | 9 | 9 | PASS |
| `include_with_small_object_augmentation` | 3 | 3 | PASS |
| `include_as_hard_negative_and_prioritize_threshold_augmentation_review` | 4 | 4 | PASS |

### source_manifest별 count

| source_manifest | CSV count | summary JSON count | 결과 |
| --- | ---: | ---: | --- |
| `walksafe_kr_v3_curation_manifest_2026-05-18.csv` | 40 | 40 | PASS |
| `walksafe_kr_v3_hard_negative_fp_review_2026-05-18.csv` | 21 | 21 | PASS |

### status / privacy_status / split_policy 요약

| 컬럼 | 값 | count | 비고 |
| --- | --- | ---: | --- |
| status | `candidate_for_v3_review` | 40 | v2 test subset failure triage 후보 |
| status | `candidate_for_v3_hard_negative_review` | 21 | VL1+VS1 hard-negative FP review 후보 |
| privacy_status | `no_face_or_license_plate_visible_in_contact_sheet; keep local-only until source privacy audit` | 40 | source-level privacy audit 전 local-only |
| privacy_status | `no_face_or_license_plate_visible_in_review_sheet; keep local-only until source privacy audit` | 21 | source-level privacy audit 전 local-only |
| split_policy | `do_not_cross_split_with_same_location_or_sequence` | 61 | 동일 장소/연속 프레임 split 혼입 금지 |

> 참고: CSV에는 `privacy` 컬럼이 없고 `privacy_status` 컬럼이 있어 해당 컬럼으로 요약했다.

## 후보 처리 표

`train_ready`는 policy_decision상 포함 가능한 후보를 뜻한다. 단, privacy/location audit이 완료되기 전 실제 학습 투입은 gate에 의해 막혀 있으므로 최종 학습 가능 상태로 보지 않는다.

| 후보 분류 | count | 포함 policy_decision | source_manifest 분포 | 상태 |
| --- | ---: | --- | --- | --- |
| train_ready 정책 후보 | 40 | `include_as_hard_negative` 33, `include_with_small_object_augmentation` 3, `include_as_hard_negative_and_prioritize_threshold_augmentation_review` 4 | curation 19, hard_negative_fp_review 21 | PENDING: privacy/location audit 전 실제 train 투입 금지 |
| needs_manual_relabel | 12 | `include_after_box_review` 12 | curation 12 | PENDING: bbox 위치/크기 수동 보정 필요 |
| hold | 9 | `hold_until_min_box_policy_review` 9 | curation 9 | BLOCKED: min-box 정책 확정 전 학습 포함 금지 |
| audit_required | 61 | 전체 후보 | curation 40, hard_negative_fp_review 21 | BLOCKED: source-level 개인정보/위치정보 audit 필요 |

### 후보 ID 범위/목록 요약

| 후보 분류 | candidate_id |
| --- | --- |
| train_ready 정책 후보 | `v3-failure-001`~`v3-failure-012`, `v3-failure-031`~`v3-failure-032`, `v3-failure-036`~`v3-failure-040`, `v3-vl1vs1-fp-001`~`v3-vl1vs1-fp-021` |
| needs_manual_relabel | `v3-failure-013`~`v3-failure-024` |
| hold | `v3-failure-025`~`v3-failure-030`, `v3-failure-033`~`v3-failure-035` |
| audit_required | 전체 61행 |

## 판정

| 항목 | 판정 | 근거 |
| --- | --- | --- |
| CSV row count와 summary rows_total 일치 | PASS | CSV 61행, JSON `rows_total=61` |
| source_manifest별 count 일치 | PASS | 40 + 21 = 61, JSON과 일치 |
| policy_decision별 count 일치 | PASS | 33 + 12 + 9 + 3 + 4 = 61, JSON과 일치 |
| 새 학습 미수행 | PASS | 감사 중 학습 명령 실행 없음 |
| 실제 v3 학습 투입 가능 여부 | BLOCKED | hold 9행 미해결 및 전체 61행 privacy/location audit 필요 |
| 문서 작업 외 파일 수정 | PASS | 의도한 수정 파일은 본 감사 문서 1개뿐 |
| `git diff --check` | PASS | 출력 없음 |

## 다음 액션

1. `hold_until_min_box_policy_review` 9행에 대한 min-box 포함/제외 기준을 확정한다.
2. `include_after_box_review` 12행의 bbox 위치/크기를 수동 재검수/보정한다.
3. 전체 61행에 대해 source-level 개인정보/위치정보 audit을 완료한다.
4. 동일 장소/연속 프레임 후보가 train/val/test split에 섞이지 않도록 split을 고정한 뒤에만 v3 학습 후보로 넘긴다.
