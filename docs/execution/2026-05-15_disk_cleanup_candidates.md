# 2026-05-15 Disk Cleanup Candidate Scan

작성 시각: 2026-05-15 KST

## 범위와 원칙

- 사용자 요청에 따라 병렬 에이전트로 디스크 정리 후보를 찾았다.
- 삭제, 이동, 압축, prune은 실행하지 않았다.
- 아래 항목은 후보이며, 특히 원본 데이터/학습 데이터/모델 캐시는 사용자 승인 전 삭제 금지다.
- 현재 `/` 파티션은 `915G` 중 `863G` 사용, 가용 `5.9G`, 사용률 `100%`로 확인됐다.

## 실폰 테스트 메모

- 사용자 확인: 실폰 테스트는 이전에 수행한 적이 있다.
- 이번 병렬 검증에서는 Android 기기가 연결되지 않아 실폰 테스트를 재실행하지 않았다.
- 실폰 재검증은 후속 일정에서 별도로 진행한다.

## 우선순위별 정리 후보

### 1. 먼저 해볼 만한 낮은 위험 후보

| 후보 | 예상 확보 | 판단 |
| --- | ---: | --- |
| `~/.local/share/Trash` | `2.4G` | 휴지통. 복원 필요 없으면 비우기 가능 |
| `~/.cache/pip` | `3.2G` | pip 다운로드 캐시. 재생성 가능 |
| `~/.npm/_cacache` | `624M` | npm 다운로드 캐시. 재생성 가능 |
| `~/.npm/_npx` | `439M` | npx 도구 캐시. 삭제 시 재설치 필요 |
| `/tmp/vello-jdk21` | `543M` | 임시 JDK로 보임. 사용 중 프로세스 확인 후 정리 후보 |
| `/tmp/runapp-remote-check` | `38M` | 임시 작업물 후보 |
| `/tmp/node-compile-cache` | `17M` | node compile cache 후보 |
| unused Docker images: `eclipse-temurin:21-jdk`, `nvidia/cuda:12.0.1-base-ubuntu22.04`, `hello-world:latest` | 약 `1.0G` | 연결된 컨테이너가 없는 이미지 중심 |
| `apps/web/.next` | `4.8M` | Next.js 빌드 산출물. 효과는 작음 |

낮은 위험 후보 합산은 대략 `8G` 안팎이다. `/` 100% 상태를 벗어나는 응급 조치로는 충분할 수 있지만, 모델 export나 대형 validation에는 부족할 수 있다.

### 2. 재생성 가능하지만 개발 편의 영향이 있는 후보

| 후보 | 예상 확보 | 판단 |
| --- | ---: | --- |
| `/home/ddobagi/Code/hanium-dreamup/.venv` | `5.4G` | 백엔드/모델 Python env. 재생성 가능하지만 설치 시간 큼 |
| `/home/ddobagi/Code/hanium-dreamup/.venv-voice` | `5.4G` | voice Python env. 재생성 가능하지만 STT/TTS 의존성 설치 시간 큼 |
| `/home/ddobagi/Code/hanium-dreamup/apps/web/node_modules` | `625M` | `package-lock.json` 기준 재설치 가능 |
| `/home/ddobagi/.cache/huggingface` | `6.1G` | Qwen TTS, faster-whisper cache. 삭제 시 재다운로드/토큰/네트워크 영향 |

이 후보는 최대 약 `17G` 정도다. 단, 바로 이어서 backend/voice 검증을 할 계획이면 `.venv`, `.venv-voice`, Hugging Face cache 삭제는 작업 지연이 크다.

### 3. 큰 효과가 있지만 고위험/승인 필요 후보

| 후보 | 예상 확보 | 판단 |
| --- | ---: | --- |
| `/home/ddobagi/Downloads/.../1.Training/원천데이터/TS8.zip` | `100.1G` | AI Hub 원본. v1/v2 파생 데이터가 있으나 재현성 상실 위험 큼 |
| `/home/ddobagi/Downloads/.../1.Training/원천데이터/TS9.zip` | `100.1G` | AI Hub 원본. 삭제 전 외부 백업/재다운로드 가능성 확인 필요 |
| `/home/ddobagi/Downloads/.../2.Validation/원천데이터/VS1.zip` | `100.1G` | 외부 validation/hard-negative 원본. 고위험 |
| `/home/ddobagi/Downloads/.../2.Validation/원천데이터/VS2.zip` | `100.1G` | VL2+VS2 subset은 있지만 full 원본 대체는 아님 |
| `datasets/walksafe_kr_v2/images/` | `199G` | v2 YOLO 이미지. 학습 재현/추가 검증 영향 큼 |
| `datasets/walksafe_kr_v1/images/` | `49G` | v1 YOLO 이미지. 필요성 낮으면 후보지만 승인 필요 |
| `runs/validation/aihub513_vl2_vs2_tactile_subset/` | `18G` | VL2+VS2 tactile subset 산출물. 재검증 재현성 영향 |

가장 큰 후보는 AI Hub 원본 zip `4개` 약 `400G`와 YOLO 변환 이미지 `248G`다. 둘 다 프로젝트 재현성에 직접 영향을 주므로 삭제보다 외장/다른 디스크 이동 또는 명시 승인 후 정리가 안전하다.

## 삭제해도 효과가 작은 항목

| 항목 | 크기 | 메모 |
| --- | ---: | --- |
| `LOCAL_DATASETS/raw_downloads/*.zip` | symlink 자체 `36K` | 실제 zip 복사본이 아니라 Downloads 원본을 가리키는 링크라 공간 확보 거의 없음 |
| `backend/uploads/` | `12K` | 효과 없음 |
| `logs/` | `15M` | 효과 작음. 기록 보존 가치 있음 |
| `outputs/`, `samples/` | 각 `1.7M` | 효과 작음. 음성 샘플은 개인정보 가능성 있어 별도 판단 필요 |

## Docker 후보

- `docker system df`: Images `3.445G`, reclaimable `1.017G`, containers `65.54kB`, volumes `387M`, build cache `0B`.
- 낮은 위험 이미지 후보:
  - `eclipse-temurin:21-jdk` 약 `679M`
  - `nvidia/cuda:12.0.1-base-ubuntu22.04` 약 `338M`
  - `hello-world:latest` 매우 작음
- DB 관련 이미지/볼륨은 보존 권장 또는 별도 승인 필요:
  - `postgis/postgis:16-3.5`, volume `hanium-dreamup_walksafe-postgis-data` 약 `113M`
  - `mysql:8.0`, volume `server_runapp-mysql-data` 약 `205M`
  - `postgres:17-alpine`, volume `ski-lover_postgres-data` 약 `69M`
  - `redis:7-alpine`, volume `server_runapp-redis-data` 미미

## 추천 순서

1. 휴지통, pip cache, npm cache, `/tmp` 후보, unused Docker image부터 정리한다.
2. 그래도 부족하면 `node_modules`, `.next`처럼 재생성 쉬운 프로젝트 산출물을 정리한다.
3. 이후에도 부족하면 `.venv`, `.venv-voice`, Hugging Face cache 삭제 여부를 작업 일정과 재다운로드 가능성 기준으로 결정한다.
4. AI Hub 원본 zip, `datasets/**/images`, `runs/validation/**`는 승인 없이 삭제하지 않는다. 필요하면 먼저 외장/다른 디스크로 이동한다.

## 병렬 스캔 근거

- Repo-local scan: `datasets/` `248G`, `runs/` `19G`, `apps/web/.next` `4.8M` 등 확인.
- Downloads scan: `/home/ddobagi/Downloads` `401G`, AI Hub 원본 zip 4개가 각 약 `100.1G`임을 확인.
- Cache/env scan: Hugging Face `6.1G`, pip cache `3.2G`, `.venv*` 각 `5.4G`, npm `1.1G` 확인.
- Docker/system scan: Docker reclaimable image 약 `1.017G`, Trash `2.4G`, `/tmp` `603M` 확인.
