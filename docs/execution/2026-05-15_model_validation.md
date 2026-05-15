# 2026-05-15 Model/Data/MLOps Validation Lane

작업 위치: `/home/ddobagi/Code/hanium-dreamup`
범위: read/validate only + 이 결과 문서 작성. 새 학습, ONNX export, 대용량 삭제/이동은 하지 않았다.

## 1. Git status

명령:

```bash
git status --short --untracked-files=all
```

결과:

```text
 M apps/web/next-env.d.ts
```

판정: 기록 완료. `apps/web/next-env.d.ts`는 기존 수정으로 보이며 이 lane에서 건드리지 않았다.

문서 작성 후 재확인한 status:

```text
?? docs/execution/2026-05-15_backend_validation.md
?? docs/execution/2026-05-15_model_validation.md
?? docs/execution/2026-05-15_pwa_validation.md
?? docs/execution/2026-05-15_voice_validation.md
```

위 재확인 시점에는 `apps/web/next-env.d.ts`가 더 이상 표시되지 않았다. 다른 lane의 동시 작업 산출물로 보이는 문서는 건드리지 않았다.

## 2. Disk / artifact checks

명령:

```bash
df -h /home/ddobagi/Code/hanium-dreamup /home/ddobagi/Downloads
```

결과:

```text
Filesystem      Size  Used Avail Use% Mounted on
/dev/nvme0n1p2  915G  863G  5.9G 100% /
/dev/nvme0n1p2  915G  863G  5.9G 100% /
```

판정: 실패/블로커. repo와 Downloads가 같은 `/` 파티션이며 가용 공간이 `5.9G`, 사용률 `100%`다. 추가 대형 subset, full external validation, export 전 cleanup 승인이 필요하다.

v2 artifact 위치 확인 명령:

```bash
for root in /home/ddobagi/Code/hanium-dreamup /home/ddobagi/Downloads; do
  echo "## $root"
  find "$root" -type f \( -name best.pt -o -name last.pt -o -name results.csv -o -name args.yaml -o -name data.yaml -o -name SHA256SUMS.txt \) -path '*v2*' -printf '%p\t%s bytes\n' 2>/dev/null | sort
done
```

결과 요약:

| path | size |
| --- | ---: |
| `datasets/walksafe_kr_v2/data.yaml` | `189` bytes |
| `runs/detect/walksafe_kr_tactile_v2_full/args.yaml` | `1,735` bytes |
| `runs/detect/walksafe_kr_tactile_v2_full/results.csv` | `8,271` bytes |
| `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt` | `5,447,386` bytes |
| `runs/detect/walksafe_kr_tactile_v2_full/weights/last.pt` | `5,447,386` bytes |
| `runs/validation/walksafe_kr_tactile_v2_freeze_20260514/SHA256SUMS.txt` | `578` bytes |
| `runs/validation/walksafe_kr_tactile_v2_freeze_20260514/args.yaml` | `1,735` bytes |
| `runs/validation/walksafe_kr_tactile_v2_freeze_20260514/data.yaml` | `189` bytes |
| `runs/validation/walksafe_kr_tactile_v2_freeze_20260514/results.csv` | `8,271` bytes |

Downloads 쪽 v2 artifact는 없었다.

hash freeze 확인 명령:

```bash
sha256sum -c runs/validation/walksafe_kr_tactile_v2_freeze_20260514/SHA256SUMS.txt
```

결과:

```text
runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt: OK
runs/detect/walksafe_kr_tactile_v2_full/weights/last.pt: OK
runs/detect/walksafe_kr_tactile_v2_full/results.csv: OK
runs/detect/walksafe_kr_tactile_v2_full/args.yaml: OK
datasets/walksafe_kr_v2/data.yaml: OK
```

판정: v2 freeze artifact는 존재하고 hash 검증 통과.

AI Hub 513 validation zip 확인 명령:

```bash
find /home/ddobagi/Downloads -maxdepth 8 -type f \( -name 'VL1.zip' -o -name 'VL2.zip' -o -name 'VS1.zip' -o -name 'VS2.zip' \) -printf '%p\t%k KiB\t%s bytes\n' 2>/dev/null | sort
```

결과:

| file | size |
| --- | ---: |
| `.../2.Validation/라벨링데이터/VL1.zip` | `49,674,941` bytes |
| `.../2.Validation/라벨링데이터/VL2.zip` | `63,354,839` bytes |
| `.../2.Validation/원천데이터/VS1.zip` | `107,387,942,790` bytes |
| `.../2.Validation/원천데이터/VS2.zip` | `107,388,232,773` bytes |

## 3. Existing metrics from docs/logs only

새 validation/test는 실행하지 않았다. 아래 수치는 기존 docs/logs에서만 요약했다.

| scope | source | images / instances | precision | recall | mAP50 | mAP50-95 | 판정/제한 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| v2 validation final | `docs/execution/2026-05-14_model.md`, `logs/walksafe_kr_tactile_v2_full.log` | `4,695 / 8,085` log 기준 | `0.73656` doc 기준 | `0.58380` | `0.66394` | `0.49194` | 기준선 동결 가능, recall 낮음 |
| v2 test split | `docs/execution/2026-05-14_model.md`, `docs/model_v2_status.md` | `2,347 / 3,979` | `0.728` | `0.581` | `0.657` | `0.481` | validation 대비 mAP50-95 delta `-0.01094` |
| AI Hub 513 VL2+VS2 tactile external subset | `docs/execution/2026-05-14_external_validation_subset.md`, `runs/validation/aihub513_vl2_vs2_tactile_subset_val.log` | `2,082 / 5,325` | `0.751` | `0.605` | `0.680` | `0.501` | class `0 damaged_tactile_block` 전용 subset. 4-class 전체 성능으로 해석 금지 |
| AI Hub 513 VL1+VS1 dry-run | `docs/execution/2026-05-14_external_validation_subset.md` | `1,038 tactile images / 0 boxes` | n/a | n/a | n/a | n/a | hard-negative check 용도만 가능 |

추가 확인 명령:

```bash
perl -pe 's/\e\[[0-9;]*[A-Za-z]//g' logs/walksafe_kr_tactile_v2_full.log | rg '^\s+(all|damaged_tactile_block)\s+' | tail -4
perl -pe 's/\e\[[0-9;]*[A-Za-z]//g' runs/validation/aihub513_vl2_vs2_tactile_subset_val.log | rg '^\s+(all|damaged_tactile_block)\s+' | tail -4
```

로그 확인 결과 v2 validation은 rounded `all 4695 8085 0.739 0.583 0.664 0.492`, external subset은 `all 2082 5325 0.751 0.605 0.68 0.501`로 문서 요약과 일치한다.

## 4. Git large artifact tracking check

명령:

```bash
git ls-files '*.pt' '*.pth' '*.onnx' '*.engine' '*.tflite' 'runs/**' 'datasets/walksafe_kr_v2/**' 'datasets/**/images/**' 'datasets/**/labels/**'
```

결과:

```text
datasets/walksafe_kr_v1/images/test/.gitkeep
datasets/walksafe_kr_v1/images/train/.gitkeep
datasets/walksafe_kr_v1/images/val/.gitkeep
datasets/walksafe_kr_v1/labels/test/.gitkeep
datasets/walksafe_kr_v1/labels/train/.gitkeep
datasets/walksafe_kr_v1/labels/val/.gitkeep
datasets/walksafe_v1/images/test/.gitkeep
datasets/walksafe_v1/images/train/.gitkeep
datasets/walksafe_v1/images/val/.gitkeep
datasets/walksafe_v1/labels/test/.gitkeep
datasets/walksafe_v1/labels/train/.gitkeep
datasets/walksafe_v1/labels/val/.gitkeep
```

명령:

```bash
git ls-files -z | xargs -0 du -b 2>/dev/null | sort -nr | head -20
```

결과 상위 tracked 파일은 PDF 2개(`2,682,799`, `1,347,372` bytes)와 소스/문서 파일이었다. `.pt`, `.onnx`, `runs/**`, `datasets/walksafe_kr_v2/**` 추적은 확인되지 않았다.

판정: 통과. Git이 대형 모델 artifact를 추적 중이라는 근거 없음.

## 5. ONNX / failure-sampling status

명령:

```bash
find /home/ddobagi/Code/hanium-dreamup/runs /home/ddobagi/Code/hanium-dreamup/models /home/ddobagi/Code/hanium-dreamup/model -type f -name '*.onnx' -printf '%p\t%s bytes\n' 2>/dev/null | sort
find /home/ddobagi/Code/hanium-dreamup/runs/failure_sampling -maxdepth 3 -type f -printf '%p\t%s bytes\n' 2>/dev/null | sort || true
```

결과: ONNX 파일 없음. `runs/failure_sampling` 산출 파일 없음.

판정:

- ONNX export / PT-vs-ONNX equivalence / latency: pending. 실행됨으로 표시하지 않는다.
- failure sampling: pending. 실행됨으로 표시하지 않는다.
- AI Hub 513 VL2+VS2 tactile external subset validation은 기존 docs/logs 기준 실행됨.

## Blockers

1. `/` 파티션 사용률 `100%`, 가용 `5.9G`라 추가 대형 검증/산출 작업 위험.
2. 기존 worktree에 `apps/web/next-env.d.ts` 수정이 있어 다른 lane 소유 변경으로 취급해야 한다.
3. external subset metric은 class 0 전용이다. 4-class 서비스 성능이나 full official validation으로 과대표기하면 안 된다.

## Next steps / parent follow-up

1. cleanup 후보와 삭제 승인 범위를 부모가 결정해야 한다. 이 lane에서는 삭제하지 않았다.
2. VL2+VS2 tactile subset 결과를 2026-05-15 external validation 근거로 채택할지, full official validation을 별도 목표로 유지할지 결정이 필요하다.
3. 디스크 확보 후 ONNX export/equivalence/latency를 계획된 2026-05-16 lane에서 실행한다.
4. failure sampling은 입력 source(AI Hub 159 일부 또는 직접 촬영/보행 영상)와 산출 경로를 확정한 뒤 실행한다.
