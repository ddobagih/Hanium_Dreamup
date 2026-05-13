# 2026-05-14 디스크 정리 후보 분석

작업 위치: `/home/ddobagi/Code/hanium-dreamup`

## 원칙

- 실제 삭제는 실행하지 않았다.
- 쓰기 범위는 이 문서 하나로 제한했다.
- 다른 에이전트가 병렬로 작업 중이므로, 삭제 전에는 해당 작업자가 현재 쓰는 경로인지 다시 확인해야 한다.
- 측정 중 `apps/web/.next`, `.pytest_cache`, 비venv `__pycache__`, `backend/uploads/test`가 사라졌다. 아래 표에는 최종 관측값과 초기 관측값을 구분해 적었다.

## 현재 용량 요약

프로젝트 루트 기준 최종 관측:

| 경로 | 용량 | 판단 |
| --- | ---: | --- |
| `datasets` | `248G` | 최대 사용처 |
| `datasets/walksafe_kr_v2` | `199G` | v2 기준 데이터셋, 병렬 작업 중 삭제 금지 |
| `datasets/walksafe_kr_v1` | `49G` | 확인 후 삭제 가능 후보 |
| `.venv-voice` | `5.7G` | 재설치 가능하지만 작업 중단 리스크 있음 |
| `.venv` | `5.6G` | 재설치 가능하지만 작업 중단 리스크 있음 |
| `apps/web/node_modules` | `625M` | 재설치 가능 |
| `runs` | `83M` | 대부분 모델 검증 산출물, 선별 필요 |
| `logs` | `15M` | 보존 |
| `outputs` | `1.7M` | 음성 산출물, 선별 필요 |
| `samples` | `1.7M` | 실제 음성 샘플 포함, 보존 |
| `apps/web/.next` | 최종 없음, 초기 `83M` | 재생성 가능 |

Docker:

| 항목 | 전체 | 회수 가능 |
| --- | ---: | ---: |
| Images | `3.445GB` | `1.017GB` |
| Containers | `69.63kB` | `49.15kB` |
| Local Volumes | `386.9MB` | `0B` |
| Build Cache | `0B` | `0B` |

프로젝트 밖이지만 데이터셋 생성 원본으로 확인된 zip:

| 경로 | 용량 | 판단 |
| --- | ---: | --- |
| `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/1.Training/원천데이터/TS8.zip` | `101G` | 확인 후 삭제 가능 |
| `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/1.Training/원천데이터/TS9.zip` | `101G` | 확인 후 삭제 가능 |
| `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation/원천데이터/VS1.zip` | `101G` | 확인 후 삭제 가능 |
| `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation/원천데이터/VS2.zip` | `101G` | 확인 후 삭제 가능 |
| `TL8.zip`, `TL9.zip`, `VL1.zip`, `VL2.zip` | 약 `188M` | 라벨 원본, 작으므로 보존 권장 |

## 1. 즉시 삭제 가능 후보

재생성 가능하거나 테스트 잔여물인 항목이다. 삭제 전 다른 에이전트가 현재 실행 중인 프로세스에서 쓰고 있지 않은지만 확인한다.

| 후보 | 예상 확보 | 리스크 | 비고 |
| --- | ---: | --- | --- |
| `.venv/**/__pycache__` | 약 `256.8M` | 낮음. 다음 실행 때 재컴파일로 약간 느려질 수 있음 | 가상환경 자체는 유지 |
| `.venv-voice/**/__pycache__` | 약 `347.3M` | 낮음. 다음 실행 때 재컴파일로 약간 느려질 수 있음 | 가상환경 자체는 유지 |
| `datasets/*/labels/*.cache` | 약 `11M` | 낮음. YOLO가 다시 생성 | `data.yaml`, label txt는 삭제 금지 |
| `apps/web/.next` | 최종 없음, 초기 `83M` | 낮음. Next dev/build 재실행 필요 | 측정 중 이미 사라짐 |
| `.pytest_cache` | 최종 없음, 초기 `28K` | 낮음 | 측정 중 이미 사라짐 |
| 비venv `__pycache__` | 최종 없음, 초기 약 `268K` | 낮음 | 측정 중 이미 사라짐 |
| `backend/uploads/test/*` | 최종 없음, 초기 약 `36K` | 낮음. 테스트 업로드 산출물 | `.gitkeep`은 보존 |
| `outputs/voice/cache/*` | `4K` | 낮음 | `.gitkeep`은 보존 |

제안 명령, 실행하지 않음:

```bash
find .venv .venv-voice -type d -name '__pycache__' -prune -exec rm -rf {} +
rm -f datasets/*/labels/*.cache
rm -rf apps/web/.next .pytest_cache
find backend scripts voice data_sources -type d -name '__pycache__' -prune -exec rm -rf {} +
find backend/uploads/test -mindepth 1 -maxdepth 1 -type f -delete
find outputs/voice/cache -mindepth 1 ! -name '.gitkeep' -delete
```

## 2. 확인 후 삭제 가능 후보

대부분 용량 회수 효과가 크지만, 재다운로드/재변환/재설치 비용이 있거나 현재 병렬 작업과 충돌할 수 있다.

| 후보 | 예상 확보 | 리스크 | 확인 조건 |
| --- | ---: | --- | --- |
| `/home/ddobagi/Downloads/.../TS8.zip`, `TS9.zip`, `VS1.zip`, `VS2.zip` | 약 `404G` | 높음. AI Hub 재다운로드 비용 큼 | v2/v3 재생성 계획, 외부 validation 계획, 원본 보관 정책 확인 |
| `datasets/walksafe_kr_v1` | `49G` | 중간. v1 재현과 비교 기준 상실 | v1 모델/문서가 더 필요 없고, 원본 zip 또는 재다운로드 경로가 남아 있는지 확인 |
| `datasets/walksafe_v1` | `72K` | 낮음. 공개 smoke/pretrain 변환본 | 용량 효과 거의 없음 |
| `.venv` 전체 | `5.6G` | 중간. backend/model 작업 즉시 중단, 재설치 필요 | 현재 Python 작업 종료, `requirements-*`로 재현 가능 확인 |
| `.venv-voice` 전체 | `5.7G` | 중간. STT/TTS 작업 즉시 중단, 재설치 필요 | voice 작업 종료, GPU/torch 계열 재설치 시간 감수 |
| `apps/web/node_modules` | `625M` | 낮음-중간. 프론트 작업 중단, `npm install` 필요 | frontend 작업 종료, lockfile 기반 재설치 가능 확인 |
| `outputs/voice/tts/*` | `1.6M` | 낮음. 생성 음성 비교 자료 상실 | TTS 결과를 문서/로그로 충분히 기록했는지 확인 |
| `runs/detect/walksafe_kr_tactile_smoke` | `16M` | 낮음-중간. smoke train 증거 상실 | smoke 결과가 문서화되어 있고 재실행 가능하면 삭제 가능 |
| `runs/detect/walksafe_kr_tactile_v1` | `19M` | 중간. v1 baseline 비교 자료 상실 | v1 비교가 더 필요 없는지 확인 |
| Docker reclaimable images | `1.017GB` | 중간. 다른 프로젝트 이미지까지 영향 가능 | `docker system df -v`로 이미지 소유 확인 후 정리 |

주의: `datasets/walksafe_kr_v2/images`와 `datasets/walksafe_kr_v2/labels`는 `199G`라 회수 효과가 크지만 현재 v2 기준선, test split 검증, 외부 validation, 실패 프레임 분석의 기반이다. 병렬 모델/데이터셋 작업이 완전히 끝나고 `best.pt`, `last.pt`, `results.csv`, `data.yaml`, hash manifest, 원본 파일명/파일키, split count가 모두 보존된 뒤에만 별도 승인으로 다룬다.

제안 명령, 실행하지 않음:

```bash
rm -rf datasets/walksafe_kr_v1
rm -rf .venv .venv-voice
rm -rf apps/web/node_modules
rm -rf outputs/voice/tts/*
rm -rf runs/detect/walksafe_kr_tactile_smoke runs/detect/walksafe_kr_tactile_v1
docker image prune
```

프로젝트 밖 원본 zip 정리 제안, 실행하지 않음:

```bash
rm "/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/1.Training/원천데이터/TS8.zip"
rm "/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/1.Training/원천데이터/TS9.zip"
rm "/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation/원천데이터/VS1.zip"
rm "/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation/원천데이터/VS2.zip"
```

## 3. 삭제 금지

기준선 재현, 평가 근거, 실제 음성 평가에 필요한 항목이다.

| 경로 | 이유 |
| --- | --- |
| `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt` | v2 기준선 best weight |
| `runs/detect/walksafe_kr_tactile_v2_full/weights/last.pt` | v2 기준선 last weight |
| `runs/detect/walksafe_kr_tactile_v2_full/results.csv` | v2 학습 metric 원본 |
| `runs/detect/walksafe_kr_tactile_v2_full/results.png` | v2 metric 시각화 |
| `runs/detect/walksafe_kr_tactile_v2_full/confusion_matrix*.png` | v2 평가 근거 |
| `runs/validation/walksafe_kr_tactile_v2_freeze_20260514/*` | freeze hash와 검증 metadata |
| `datasets/walksafe_kr_v2/data.yaml` | class order와 split 경로 계약 |
| `logs/*` | 학습/음성/검증 실행 로그 |
| `samples/voice/stt/myvoice/*` | 실제 사람 음성 샘플 |
| `outputs/voice/stt_myvoice_results*.csv` | 실제 음성 STT 평가 결과 |
| `LOCAL_DATASETS/*` | 실제 데이터가 아니라 링크 모음. 삭제해도 용량 회수 거의 없음 |

## 우선순위

1. 즉시 가능: venv `__pycache__`와 YOLO label cache를 지우면 약 `615M` 회수 가능하다.
2. 큰 효과: 프로젝트 내부에서는 `datasets/walksafe_kr_v1` 삭제가 `49G`로 가장 현실적인 후보이다.
3. 가장 큰 효과: 프로젝트 밖 Downloads의 AI Hub 원천 zip 4개가 약 `404G`다. 다만 재다운로드 비용이 커서 원본 보관 정책 확인 후 결정해야 한다.
4. 고위험 대안: `datasets/walksafe_kr_v2`는 `199G`지만 현재 기준선 데이터셋이므로 지금은 삭제 금지에 가깝게 보류한다.
