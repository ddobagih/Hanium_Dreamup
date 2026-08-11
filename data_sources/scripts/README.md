# Data Source Scripts

이 폴더의 6개 CLI는 초기 1·3·4-class 데이터셋과 AI Hub 513 검증 자료를 재현·검수하기 위한 도구다. 현재 13-class 통합 학습본 전체를 이 폴더만으로 다시 만드는 current builder는 아니다. 현재 데이터 기준과 provenance는 상위 `data_sources/README.md`를 먼저 본다.

## 스크립트 분류

| 스크립트 | 분류 | 책임 |
|---|---|---|
| `inspect_aihub513_validation.py` | 검증 보조 | AI Hub 513 VL/VS zip을 추출하지 않고 label·image 대응을 조사 |
| `build_aihub513_validation_subset.py` | 검증 보조 | AI Hub 513의 손상 점자블록 positive와 선택적 negative를 YOLO val/test subset으로 생성 |
| `build_walksafe_v1.py` | 과거 재현 | 다운로드된 공개 소스를 초기 4-class `walksafe_v1` 구조로 병합 |
| `build_walksafe_kr_tactile.py` | 과거 재현 | AI Hub 513 TL8/TL9·TS8/TS9에서 초기 한국 점자블록 dataset 생성 |
| `prepare_tactile_damage_area_review_decisions.py` | 과거 검수 | 3-class damage-area review queue로 수동 결정 CSV template 생성 |
| `apply_tactile_damage_area_review_decisions.py` | 과거 검수 | 완료된 수동 결정을 새 dataset copy와 manifest에 적용 |

`과거 재현`·`과거 검수`는 보존된 provenance를 확인하기 위한 분류다. 산출물을 현재 13-class 학습 입력이나 최신 성능 근거로 자동 승격하지 않는다.

## 안전한 사용 순서

1. 저장소 root에서 `python3 data_sources/scripts/<name>.py --help`로 입력과 출력 경로를 확인한다.
2. 지원하는 builder는 `--dry-run`을 먼저 실행하고, summary는 `/tmp` 또는 ignore된 `runs/` 아래에 쓴다.
3. 원본 zip과 materialize dataset은 Git 밖의 명시적 절대 경로로 지정한다.
4. 생성 후 class order, split, sample 수와 provenance manifest를 검토한 뒤에만 다음 단계 입력으로 사용한다.

`build_walksafe_v1.py`는 dry-run이 없고 기존 target 내용을 정리한다. `apply_tactile_damage_area_review_decisions.py --build --reset`도 target을 다시 만들 수 있으므로 별도 복사본과 명시적 target 없이는 실행하지 않는다. source dataset, 결정 CSV와 생성 결과를 Git의 current 기준으로 오해하지 않는다.

## 가벼운 검증

외부 dataset 없이 문법과 CLI 진입점만 확인한다.

```bash
python3 -m py_compile data_sources/scripts/*.py
for script in data_sources/scripts/*.py; do python3 "$script" --help >/dev/null; done
```

실제 dataset build의 완료 판정은 생성 manifest와 별도의 dataset 검증 결과가 필요하며, 위 명령만으로 데이터 품질이나 학습 적합성을 PASS 처리하지 않는다.
