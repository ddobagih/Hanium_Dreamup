# 2026-05-14 Parallel Follow-up Summary

작업 범위:

- 실폰 검증 제외
- 로컬 AI Hub zip 재탐색
- 데이터셋/디스크 정리 후보 분석
- AI Hub 513 external validation readiness 점검
- 실폰 없이 가능한 앱/음성/백엔드 다음 작업 정리

## 결론

로컬에서 `VL1.zip`, `VL2.zip`, `VS1.zip`, `VS2.zip`, `TL8.zip`, `TL9.zip`, `TS8.zip`, `TS9.zip` 8개를 모두 찾았다.

원본 zip은 프로젝트 내부가 아니라 아래 경로에 있다.

```text
/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터
```

프로젝트에서 보기 쉽도록 `LOCAL_DATASETS/raw_downloads/`에 symlink를 만들었다. 실제 zip은 복사하지 않았다.

```text
LOCAL_DATASETS/raw_downloads/TL8.zip
LOCAL_DATASETS/raw_downloads/TL9.zip
LOCAL_DATASETS/raw_downloads/TS8.zip
LOCAL_DATASETS/raw_downloads/TS9.zip
LOCAL_DATASETS/raw_downloads/VL1.zip
LOCAL_DATASETS/raw_downloads/VL2.zip
LOCAL_DATASETS/raw_downloads/VS1.zip
LOCAL_DATASETS/raw_downloads/VS2.zip
```

`LOCAL_DATASETS/`는 `.git/info/exclude`에 등록된 로컬 전용 경로이며 GitHub에 올라가지 않는다.

## 발견한 Raw Zip

| 파일 | 역할 | 원본 경로 | 크기 |
| --- | --- | --- | ---: |
| `TL8.zip` | training label | `~/Downloads/119.../1.Training/라벨링데이터/TL8.zip` | `37.8 MiB` |
| `TL9.zip` | training label | `~/Downloads/119.../1.Training/라벨링데이터/TL9.zip` | `40.8 MiB` |
| `TS8.zip` | training source image | `~/Downloads/119.../1.Training/원천데이터/TS8.zip` | `100.1 GiB` |
| `TS9.zip` | training source image | `~/Downloads/119.../1.Training/원천데이터/TS9.zip` | `100.1 GiB` |
| `VL1.zip` | validation label | `~/Downloads/119.../2.Validation/라벨링데이터/VL1.zip` | `47.4 MiB` |
| `VL2.zip` | validation label | `~/Downloads/119.../2.Validation/라벨링데이터/VL2.zip` | `60.5 MiB` |
| `VS1.zip` | validation source image | `~/Downloads/119.../2.Validation/원천데이터/VS1.zip` | `100.1 GiB` |
| `VS2.zip` | validation source image | `~/Downloads/119.../2.Validation/원천데이터/VS2.zip` | `100.1 GiB` |

전체 AI Hub 513 raw zip 폴더는 약 `401G`다.

## 현재 디스크 상태

확인 시점:

```text
/dev/nvme0n1p2  915G  845G  25G  98%
```

프로젝트 주요 용량:

| 경로 | 용량 | 판단 |
| --- | ---: | --- |
| `datasets` | `248G` | 가장 큰 프로젝트 내부 사용처 |
| `datasets/walksafe_kr_v2` | `199G` | v2 기준선 데이터셋, 지금 삭제 금지 |
| `datasets/walksafe_kr_v1` | `49G` | 확인 후 삭제 가능 후보 |
| `.venv` | `5.6G` | 재설치 가능하지만 작업 중단 리스크 |
| `.venv-voice` | `5.7G` | 재설치 가능하지만 STT/TTS 재검증 비용 있음 |
| `apps/web/node_modules` | `625M` | 재설치 가능 |
| `runs` | `83M` | 모델 검증 근거라 선별 필요 |

## 실행한 안전 정리

아래 재생성 가능한 캐시/테스트 산출물만 정리했다.

```text
.pytest_cache
apps/web/.next
backend/uploads/test
비venv __pycache__
```

가상환경 내부 `__pycache__`는 정리하지 않았다. 용량 대비 작업 안정성 이득이 작고, 현재 `.venv`와 `.venv-voice`를 계속 사용 중이기 때문이다.

## 삭제 보류 판단

대용량이지만 지금 바로 삭제하지 않은 항목:

| 항목 | 용량 | 보류 이유 |
| --- | ---: | --- |
| `VS1.zip`, `VS2.zip` | 약 `200G` | AI Hub 513 외부 validation 입력 원본 |
| `VL1.zip`, `VL2.zip` | 약 `108M` | 외부 validation label, 작으므로 보존 |
| `TS8.zip`, `TS9.zip` | 약 `200G` | v2 재생성 원본. 삭제 가능 후보지만 재다운로드 비용 큼 |
| `datasets/walksafe_kr_v2` | `199G` | v2 test/external validation, 실패 프레임 분석 기반 |
| `datasets/walksafe_kr_v1` | `49G` | 삭제 가능성이 가장 높은 프로젝트 내부 후보지만 승인 후 삭제 |

권장 삭제 순서:

1. 외부 validation용 converter가 준비되고 `VS1/VS2` 사용 계획이 확정될 때까지 `VS1/VS2`는 보존한다.
2. 공간 확보가 급하면 먼저 `datasets/walksafe_kr_v1` 삭제를 검토한다.
3. v2 재생성 가능성이 낮고 원본 재다운로드를 감수할 수 있으면 `TS8/TS9.zip` 삭제를 검토한다.
4. `datasets/walksafe_kr_v2`는 외부 validation과 실패 프레임 분석 전에는 삭제하지 않는다.

## External Validation Readiness

현재 판단:

```text
입력 zip 발견됨 / v2 모델 있음 / full external validation 즉시 실행 불가
```

막힘:

- 디스크 여유가 약 `25G`뿐이라 `VS1/VS2`를 압축 해제하거나 복사형 변환을 돌릴 수 없다.
- `VL1/VL2/VS1/VS2` 전용 converter가 없다.
- 기존 `data_sources/scripts/build_walksafe_kr_tactile.py`는 `TL8/TL9/TS8/TS9` 중심으로 되어 있어 validation zip 전용 처리와 official validation split 보존이 부족하다.

권장 다음 작업:

1. `VL/VS` 전용 read-only dry-run converter를 만든다.
2. zip 전체 압축 해제 없이 내부 파일 목록과 label-image 매칭 수를 먼저 계산한다.
3. 공간이 부족하므로 full copy dataset 대신 symlink/hardlink 기반 또는 subset 기반 validation부터 설계한다.
4. 먼저 `VL1 + VS1` subset으로 시작하고, 성공 후 `VL2 + VS2`로 확장한다.

## 실폰 제외 다음 PR 후보

우선순위:

1. `voice/server.py` CORS와 `/speech/stt` 오류 계약 정리
2. PWA `NEXT_PUBLIC_VOICE_API_BASE` + `MediaRecorder` STT 업로드 UI
3. PWA voice intent handler 연결
4. 백엔드 `/detect` adapter 전 설정 계약 freeze
5. `get_current_location` intent 추가 여부 결정 및 구현 준비

## 생성/수정된 실행 문서

- `docs/execution/2026-05-14_dataset_discovery.md`
- `docs/execution/2026-05-14_cleanup_candidates.md`
- `docs/execution/2026-05-14_external_validation_readiness.md`
- `docs/execution/2026-05-14_non_phone_next_steps.md`
- `docs/execution/2026-05-14_parallel_followup_summary.md`
