# WalkSafe 테스트 계층

핵심 정책을 UI와 장치 코드에서 분리해 다음 세 계층으로 실행한다.

| 계층 | 검증 범위 | 실행 명령 |
| --- | --- | --- |
| Unit | 음성 intent/STT 실행 gate, TMAP-점자블록 선택 정책, actor assertion, 위험·보존·readiness 순수 정책, Android JVM 정책 | `scripts/run_walksafe_test_layers_20260711.sh unit` |
| Functional | Unit·Integration 전용 파일을 제외한 FastAPI API/DB와 root Python, Web 타입·lint, Android lint | `WALKSAFE_TEST_DATABASE_URL=... scripts/run_walksafe_test_layers_20260711.sh functional` |
| Integration | PT/TFLite/runtime config/registry 교차 계약, TMAP API 계약, detect v2, backup/release evidence, Web production build, Android debug·AndroidTest·release APK | `WALKSAFE_TEST_DATABASE_URL=... scripts/run_walksafe_test_layers_20260711.sh integration` |

`functional`과 `integration`은 `postgresql+psycopg` driver를 쓰고 이름에 `test`가 포함된 격리 PostGIS URL을 명시해야 한다. runner는 연결 가능 여부와 `current_database()` 일치, 운영 DB와 다른 이름을 먼저 확인하고, migration 적용과 advisory lock을 통과하지 못하면 테스트를 시작하지 않는다. DB가 없을 때 테스트를 건너뛰어 성공처럼 보이지 않도록 fail-closed한다.

세 계층의 Python 파일 집합은 서로 겹치지 않는다. `backend/requirements.txt`와 `tests/requirements.txt`는 직접 의존성 source input이다. 로컬 일반 계층 테스트는 이 입력에서 생성한 hash-complete `backend/requirements.lock`과 `tests/requirements.lock`을 함께 설치한 `PYTHON_BIN`으로 실행한다. GitHub hosted CI의 CPython 3.12/Linux x86_64 환경은 같은 직접 의존성에 정확한 CPU-only PyTorch wheel을 더한 `tests/general-quality-cp312-linux-x86_64-cpu.in`에서 생성한 단일 플랫폼 lock을 설치한다. CPU lock은 `--require-hashes --only-binary=:all: --no-compile`로만 설치하고 PyTorch·TorchVision의 `+cpu` version과 CUDA 부재를 확인한다. Voice production 품질 환경과 제출물용 bytecode 없는 exact-8 Python은 각각의 lock 경계가 다르므로 일반 계층 환경과 합치지 않는다. exact-8 실제 attestation은 artifact clean-room 절차가 production submission runner `--verify-only`로 builder보다 먼저 실행하고 실패를 deselect하지 않는다.

CI의 source-bound Web artifact는 source-pinned Web closure를 재현하는 별도 CPython 3.14.4 venv에서 일반 3.12 계층보다 먼저 생성한다.

`all`은 Unit→Functional→Integration을 순서대로 실행하며 CI도 같은 runner와 `WALKSAFE_RUN_BROWSER_E2E=true`를 사용한다. 실제 Chromium PWA lifecycle은 브라우저 lifecycle만 확인하며 실폰 camera/GPS/mic/TTS 또는 실외 보행 검증을 대신하지 않는다.

기본 Integration은 canonical img768 PT를 실제 load·blank-frame warm-up하며 opt-in이 아니다. TFLite host invoke는 runtime이 설치된 Python과 이미지 경로를 각각 `WALKSAFE_TFLITE_PYTHON`, `WALKSAFE_TFLITE_SMOKE_IMAGE`로 지정하고, 연결된 emulator/실기기 계측 테스트는 `WALKSAFE_RUN_ANDROID_DEVICE_TESTS=true`로 선택 실행한다. Integration은 Android debug·AndroidTest·release APK를 빌드한 뒤 runtime config와 정확히 일치하는 TFLite asset·hash·금지 payload를 검사하고, Web production build의 모든 NFT trace에서 허용 범위와 secret-like payload 부재를 검사한다. release APK는 테스트용 HTTPS origin과 source commit을 주입한 unsigned 구조 검증물이며 배포 서명본을 의미하지 않는다. Web 임시 production build는 process lock으로 직렬화하므로 같은 workspace의 병렬 runner가 출력 폴더를 서로 지우지 않는다.

TMAP을 전역 경로로 사용하는 점자블록 시나리오는 `tests/fixtures/navigation/tactile_route_policy_cases.json`에 있다. Web 기본·release는 TMAP-only다. 단위 테스트는 supervised 연구 gate에 현재 route ID와 fresh global camera→route projection evidence를 주입한 경우에만 추가 안정화·방향·회랑 조건을 평가하고, evidence 부재·미검출·손상·stale이면 TMAP으로 닫히는지 검증한다. Android production은 detection·depth·mapper·projection context가 같은 ARCore capture frame이고 캡처 시점과 현재 시점 모두 TMAP 경로 안일 때만 정상 점자블록 local steering을 허용하며, 하나라도 불일치·누락·stale이면 TMAP으로 복귀한다.

## 2026-07-13 과거 pre-source-freeze 실행

| 계층 | 결과 | 경계 |
| --- | --- | --- |
| Unit | DB 없이 Python selected unit 238 PASS, Web voice callback dispatch를 포함한 policy PASS, Android JVM 214/214 PASS | 장치·네트워크·PostGIS 근거 아님 |
| Functional | 격리 PostGIS migration 뒤 Python/PostGIS 241 PASS·0 skip, Web policy·lint·typecheck PASS, Android JVM·lint PASS | 실폰·운영 DB 근거 아님 |
| Integration | Python 109 PASS·0 skip와 canonical PT warm-up PASS, Next 16.2.6 production build(Turbopack 경고 0)·NFT trace 58개/unique path 1,303개·동일 build PWA lifecycle PASS, Android debug/AndroidTest/release APK·3개 model asset check와 SM-G981N instrumentation 2/2 PASS | 대화형 camera/depth·실외 보행·서명 Release 근거 아님 |
