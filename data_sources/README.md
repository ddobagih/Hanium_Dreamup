# WalkSafe 데이터 소스

이 폴더는 학습 데이터 변환 코드, 소규모 확정 라벨·검수 결정과 provenance 근거를 관리합니다. 원본 이미지와 materialized 대용량 dataset은 저장소에 포함하지 않습니다.

## 현재 상태

[dataset register](../docs/deliverables/08-ai-ml-data/registers/dataset-register.json)는 현재 dataset을 `CANDIDATE_REVALIDATION_REQUIRED`로 기록합니다. 선언된 `data.yaml`과 materialized manifest는 이 저장소에 없고, content hash·split 누수·권리·개인정보·독립 test가 검증되지 않았습니다.

과거 문서의 이미지·bbox 수치는 재계산되지 않은 선언값입니다. 로컬 폴더가 존재하거나 파일을 내려받았다는 사실만으로 현재 학습본 포함·권리 확인·출시 적격을 주장하지 않습니다.

## 폴더 책임

| 경로 | 책임 |
|---|---|
| [`scripts/`](scripts/) | 데이터 탐색·빌드·검수팩·감사·검증 CLI |
| [`manifests/`](manifests/) | 저장소에 보존 가능한 source·변환·검수 근거 |

현재 데이터 관리 정책과 후보 상태는 [data management](../docs/deliverables/08-ai-ml-data/data-management.md), [dataset register](../docs/deliverables/08-ai-ml-data/registers/dataset-register.json), [데이터·AI 가이드](../docs/guides/data-ai-guide.md)를 함께 봅니다.

## 로컬 dataset 검증

현재 저장소에는 train/val 구성의 13-class 후보를 검증하는 current validator와 materialized dataset·`data.yaml`이 없습니다. 존재하지 않는 경로나 도구를 만들어 검증 PASS로 해석하지 않습니다.

`python3 -B model/validate_yolo_dataset.py --data /absolute/path/to/data.yaml`은 train/val/test를 모두 요구하는 과거 구조용 도구입니다. 그 구조와 입력을 실제로 갖춘 경우에만 제한적으로 사용합니다. 현재 후보를 검증하려면 먼저 권리·개인정보·split 근거가 있는 dataset을 준비하고, 후보 구조에 맞는 validator를 구현·검토한 뒤 dataset register에 결과를 기록해야 합니다.

builder·검수 적용·변환 스크립트는 파일을 만들거나 덮어쓸 수 있습니다. [데이터 스크립트 가이드](scripts/README.md)에서 부작용과 입력·출력을 확인하고, 가능하면 `--help`, `--dry-run` 또는 별도 출력 경로를 먼저 사용합니다.

## 반입 규칙

- 원본 AIHub/COCO zip, 사용자 수집물, `datasets/**/images/**`, `datasets/**/labels/**`를 Git에 넣지 않습니다.
- 정확한 source dataset ID·provider·license/이용조건·취득 시점·hash를 기록합니다.
- 자동 제안 라벨은 사람 승인 전 학습 입력으로 사용하지 않습니다.
- 사람·차량 번호·정확 위치·음성 등 개인정보 가능 자료는 최소 수집·분리 저장·보존기한·삭제 절차와 권한 검토가 먼저입니다.
- 기존 materialized dataset을 덮어쓰기보다 새 출력 경로와 새 manifest를 사용합니다.
- train/val/test는 촬영 sequence·장소·시간 누수를 피하도록 분리하고 독립 검증을 남깁니다.

데이터 검증이나 모델 학습이 성공해도 실제 기기·현장·정식 시험·출시 PASS를 뜻하지 않습니다.
