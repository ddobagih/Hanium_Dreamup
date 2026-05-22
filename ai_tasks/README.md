# AI task packages

이 디렉터리는 외부 AI 또는 별도 검수자가 독립적으로 처리할 수 있는 작은 작업 패키지를 모아둔다.

## 패키지 목록

| Package | 목적 | 시작 문서 |
| --- | --- | --- |
| `walksafe_v3_relabel_20260521/` | WalkSafe v3 `damaged_tactile_block` 수동 bbox relabel | `walksafe_v3_relabel_20260521/README.md` |
| `walksafe_tactile_damage_area_review_20260522/` | `tactile_damage_area` 오류 후보 120건 C-mode 전체 외부 검수 | `walksafe_tactile_damage_area_review_20260522/GUIDE.md` |

## 운영 원칙

- 원본 dataset, weights, logs, full overlay/crop 원본 묶음은 여기에 넣지 않는다.
- 외부 AI가 실제로 봐야 하는 축소 이미지, contact sheet, 작은 manifest, task prompt만 둔다.
- 각 패키지는 자체 README/GUIDE를 갖고, 어떤 파일을 수정해야 하는지 명확히 적는다.
