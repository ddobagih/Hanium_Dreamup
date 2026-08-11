# WalkSafe 테스트 계층

핵심 정책을 UI와 장치 코드에서 분리해 다음 세 계층으로 실행한다.

실행 정본은 `scripts/run_walksafe_test_layers_current.sh`다. 이름에 날짜가 붙은 `scripts/run_walksafe_test_layers_20260711.sh`는 과거 제어 해시에 결속된 동결 자료이며 현행 명령에 사용하지 않는다.

아래 명령은 저장소 루트에서 `PYTHON_BIN="$PWD/.venv-tests/bin/python"`과 잠금에 일치하는 Node 22.23.1의 절대 `WALKSAFE_NODE_BIN_DIR`를 먼저 export한 상태를 전제로 한다. Integration과 All은 추가로 정확한 CPython 3.14.6 전용 venv의 절대경로를 `WALKSAFE_BACKUP_PYTHON_BIN`에 지정한다. 환경 준비는 `docs/guides/development-environment-guide.md`를 따른다.

| 계층 | 검증 범위 | 실행 명령 |
| --- | --- | --- |
| Validate | Python 테스트 파일의 경로 존재·중복·미분류 여부 | `PYTHON_BIN="$PYTHON_BIN" scripts/run_walksafe_test_layers_current.sh validate` |
| Unit | 음성 intent/STT 실행 gate, TMAP-점자블록 선택 정책, actor assertion, 위험·보존·readiness 순수 정책, Android JVM 정책 | `PYTHON_BIN="$PYTHON_BIN" WALKSAFE_NODE_BIN_DIR="$WALKSAFE_NODE_BIN_DIR" scripts/run_walksafe_test_layers_current.sh unit` |
| Functional | Unit·Integration 전용 파일을 제외한 FastAPI API/DB와 root Python, Web 타입·lint, Android lint | `PYTHON_BIN="$PYTHON_BIN" WALKSAFE_NODE_BIN_DIR="$WALKSAFE_NODE_BIN_DIR" WALKSAFE_TEST_DATABASE_URL=... scripts/run_walksafe_test_layers_current.sh functional` |
| Integration | PT/TFLite/runtime config/registry 교차 계약, TMAP API 계약, detect v2, backup/release evidence, Web production build, Android debug·AndroidTest·release APK | `PYTHON_BIN="$PYTHON_BIN" WALKSAFE_BACKUP_PYTHON_BIN="$WALKSAFE_BACKUP_PYTHON_BIN" WALKSAFE_NODE_BIN_DIR="$WALKSAFE_NODE_BIN_DIR" WALKSAFE_TEST_DATABASE_URL=... scripts/run_walksafe_test_layers_current.sh integration` |
| Model audit | 격리 fixture 기반 데이터 무결성·offline depth 감사 테스트 | `PYTHON_BIN="$PYTHON_BIN" scripts/run_walksafe_test_layers_current.sh model-audit` |
| Active session control | strict continuation 검사 뒤 활성 v2.4 checkpoint의 transition lifecycle 제어 테스트 | `PYTHON_BIN="$PYTHON_BIN" scripts/run_walksafe_test_layers_current.sh active-session-control` |
| All | 일반 회귀 세 계층을 Unit→Functional→Integration 순서로 실행 | `PYTHON_BIN="$PYTHON_BIN" WALKSAFE_BACKUP_PYTHON_BIN="$WALKSAFE_BACKUP_PYTHON_BIN" WALKSAFE_NODE_BIN_DIR="$WALKSAFE_NODE_BIN_DIR" WALKSAFE_TEST_DATABASE_URL=... scripts/run_walksafe_test_layers_current.sh all` |

`functional`과 `integration`은 `postgresql+psycopg` driver를 쓰고 이름에 `test`가 포함된 격리 PostGIS URL을 명시해야 한다. runner는 연결 가능 여부와 `current_database()` 일치, 운영 DB와 다른 이름을 먼저 확인한 뒤 migration을 적용한다. 이후 DB 테스트 fixture가 요구하는 advisory lock도 통과하지 못하면 해당 테스트는 실패한다. DB가 없을 때 테스트를 건너뛰어 성공처럼 보이지 않도록 fail-closed한다.

세 계층의 Python 파일 집합은 서로 겹치지 않는다. `backend/requirements.txt`와 `tests/requirements.txt`는 직접 의존성 source input이다. 현재 `backend/requirements.lock`과 `tests/requirements.lock`은 Pillow pin이 서로 달라 한 환경에 동시 설치하지 않는다. Backend 전용·root 테스트 전용 환경을 나누거나, CPython 3.12/Linux x86_64 CPU 환경에서 통합 runner를 실행할 때는 두 입력을 함께 해석해 생성한 `tests/general-quality-cp312-linux-x86_64-cpu.lock`의 단일 플랫폼 환경을 사용한다. CPU lock은 `--require-hashes --only-binary=:all: --no-compile`로만 설치하고 PyTorch·TorchVision의 `+cpu` version과 CUDA 부재를 확인한다. `tests/test_walksafe_backup_integrity.py`는 `tests/backup-integrity-cp314.lock`만 설치한 정확한 CPython 3.14.6/Linux 전용 배열에서 한 번 실행하며, 누락·잘못된 runtime이면 3.12로 fallback하지 않는다. Voice production 품질 환경과 제출물용 bytecode 없는 exact-8 Python은 각각의 lock 경계가 다르므로 일반 계층 환경과 합치지 않는다. exact-8 실제 attestation은 artifact clean-room 절차가 production submission runner `--verify-only`로 builder보다 먼저 실행하고 실패를 deselect하지 않는다.

CI는 hash-locked CPython 3.12 일반 품질 환경과 `update-environment: false`로 분리한 정확한 CPython 3.14.6 백업 검사 환경, 잠긴 Node 22.23.1, 지정된 Temurin Java 21과 hash-pinned Gradle distribution을 사용한다. 현재 continuation·checkpoint control·문서·catalog·OpenAPI 계약과 test inventory를 먼저 확인하고, 분리된 `model-audit` 뒤 일반 `all` 계층을 실행한다.

`all`은 Unit→Functional→Integration만 순서대로 실행한다. 모델·데이터 감사 `model-audit`과 commit-stable checkpoint 검사 `active-session-control`은 각각 CI 별도 단계로 실행한다. 외부 서비스 작업은 CI에서 실행하지 않는다.

기본 Integration은 canonical img768 PT를 실제 load·blank-frame warm-up하며 opt-in이 아니다. TFLite host invoke는 runtime이 설치된 Python과 이미지 경로를 각각 `WALKSAFE_TFLITE_PYTHON`, `WALKSAFE_TFLITE_SMOKE_IMAGE`로 지정하고, 연결된 emulator/실기기 계측 테스트는 `WALKSAFE_RUN_ANDROID_DEVICE_TESTS=true`로 선택 실행한다. Integration은 Android debug·AndroidTest·release APK를 빌드한 뒤 runtime config와 정확히 일치하는 TFLite asset·hash·금지 payload를 검사하고, Web production build의 모든 NFT trace에서 허용 범위와 secret-like payload 부재를 검사한다. release APK는 사용자 Gateway·관리자 API의 예약된 `.invalid` HTTPS origin과 source commit을 주입한 unsigned 구조 검증물이며 배포 서명본을 의미하지 않는다. Web production build·backup·복원 구간은 해당 worktree의 Git metadata에 둔 process lock으로 직렬화하고 정리는 절대경로만 사용한다. 같은 workspace에서 이 lock을 사용하지 않고 Web 출력을 수정하는 작업까지 보호하는 전역 lock은 아니다.

현재 [quality workflow](../../.github/workflows/quality.yml)는 `WALKSAFE_RUN_ANDROID_DEVICE_TESTS=false`와 빈 `WALKSAFE_TFLITE_SMOKE_IMAGE`를 명시해 두 opt-in 실행을 제외한다. 로컬 `all`은 `integration`의 명시적 환경변수를 그대로 따르므로 이를 `true`로 설정하면 기기 테스트가 포함된다. APK 빌드와 정적 asset 검사는 기기 실행이 아니며, quality 결과를 실기기·현장·외부 서비스 증거로 해석하지 않는다.

TMAP을 전역 경로로 사용하는 점자블록 시나리오는 `tests/fixtures/navigation/tactile_route_policy_cases.json`에 있다. Web 기본·release는 TMAP-only다. 단위 테스트는 supervised 연구 gate에 현재 route ID와 fresh global camera→route projection evidence를 주입한 경우에만 추가 안정화·방향·회랑 조건을 평가하고, evidence 부재·미검출·손상·stale이면 TMAP으로 닫히는지 검증한다. Android production은 detection·depth·mapper·projection context가 같은 ARCore capture frame이고 캡처 시점과 현재 시점 모두 TMAP 경로 안일 때만 정상 점자블록 local steering을 허용하며, 하나라도 불일치·누락·stale이면 TMAP으로 복귀한다.

## 2026-07-13 과거 pre-source-freeze 실행

| 계층 | 결과 | 경계 |
| --- | --- | --- |
| Unit | DB 없이 Python selected unit 238 PASS, Web voice callback dispatch를 포함한 policy PASS, Android JVM 214/214 PASS | 장치·네트워크·PostGIS 근거 아님 |
| Functional | 격리 PostGIS migration 뒤 Python/PostGIS 241 PASS·0 skip, Web policy·lint·typecheck PASS, Android JVM·lint PASS | 실폰·운영 DB 근거 아님 |
| Integration | Python 109 PASS·0 skip와 canonical PT warm-up PASS, Next 16.2.6 production build(Turbopack 경고 0)·NFT trace 58개/unique path 1,303개·동일 build PWA lifecycle PASS, Android debug/AndroidTest/release APK·3개 model asset check와 SM-G981N instrumentation 2/2 PASS | 대화형 camera/depth·실외 보행·서명 Release 근거 아님 |
