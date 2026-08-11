# AI task packages

이 디렉터리는 외부 AI 또는 별도 검수자가 처리했던 과거 작업 패키지를 보존한다. 전체가 `LEGACY_REFERENCE`이며 현행 정책·모델 승인·새 작업의 시작점으로 사용하지 않는다.

## 보존된 과거 패키지

| Package | 목적 | 시작 문서 |
| --- | --- | --- |
| `walksafe_v3_relabel_20260521/` | WalkSafe v3 `damaged_tactile_block` 수동 bbox relabel | `walksafe_v3_relabel_20260521/README.md` |
| `walksafe_tactile_damage_area_review_20260522/` | `tactile_damage_area` 오류 후보 120건 C-mode 전체 외부 검수 | `walksafe_tactile_damage_area_review_20260522/GUIDE.md` |

과거 WalkSafe v2 two-model runtime handoff는 현재 작업 목록에서 제외하고 [`legacy/ai_tasks/walksafe_two_model_runtime_20260522/`](../legacy/ai_tasks/walksafe_two_model_runtime_20260522/)에 원본 bytes로 보존한다. 이동 근거와 hash는 [Legacy manifest](../legacy/archive-manifest.json)를 따른다.

위 두 잔여 패키지도 현재 작업 목록이 아니며, 기존 경로 결속을 해소한 뒤 archive할 후보로만 유지한다.

## 운영 원칙

- 원본 dataset, weights, logs, full overlay/crop 원본 묶음은 여기에 넣지 않는다.
- 외부 AI가 실제로 봐야 하는 축소 이미지, contact sheet, 작은 manifest, task prompt만 둔다.
- 각 패키지는 자체 README/GUIDE를 갖고, 어떤 파일을 수정해야 하는지 명확히 적는다.
