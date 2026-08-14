# 테스트 가이드

이 문서는 저장소의 개발 자동 테스트를 실행하고 결과를 해석하는 입구다. 정식 시험의 계획·판정 정본은 [시험계획](../deliverables/06-testing/test-plan.md)과 [279개 시험 케이스 원장](../deliverables/06-testing/registers/test-cases.json)이며, 이 문서가 그 내용을 복제하거나 바꾸지 않는다.

## 먼저 구분할 것

| 구분 | 목적 | 현재 상태 | 결과가 증명하지 않는 것 |
| --- | --- | --- | --- |
| 저장소 자동 테스트 | 코드·계약·정적 정책의 빠른 회귀 확인 | 여러 프레임워크에 구현되어 있으며 계층 실행기로 묶음 | 정식 279개 시험 PASS, 실기기·현장·사용자 검증, 배포·출시 승인 |
| 정식 시험 279개 | 승인된 후보 한 묶음을 정책·요구·설계·환경·원자료와 함께 판정 | 원장상 279개 모두 `NOT_RUN` | 자동 테스트 PASS만으로 상태를 바꾸거나 증거를 대신할 수 없음 |

정식 시험을 실행하려면 같은 소스 커밋, APK·모델·설정, 승인된 시험 환경, 기기와 원자료 지문을 하나의 실행 인스턴스에 결속해야 한다. 결과는 정식 절차에 따라 별도 append-only 실행 기록에 남긴다. 개발자가 `pytest`, Gradle 또는 Node 테스트를 통과시켰다는 사실만으로 [시험 케이스 원장](../deliverables/06-testing/registers/test-cases.json)의 `execution_status`나 `result`를 수정하지 않는다.

## 통합 계층 실행기

정본 실행기는 [`scripts/run_walksafe_test_layers_current.sh`](../../scripts/run_walksafe_test_layers_current.sh)다. 저장소 루트에서 실행하며, `PYTHON_BIN`에는 필요한 lock 의존성이 설치된 실행 가능한 Python을 지정한다. Node가 필요한 계층은 [잠금 파일](../../configs/walksafe_node_toolchain_lock_20260715.json)과 일치하는 Node 22.23.1의 절대 `bin` 경로를 `WALKSAFE_NODE_BIN_DIR`에 지정해야 한다. 날짜가 붙은 [`run_walksafe_test_layers_20260711.sh`](../../scripts/run_walksafe_test_layers_20260711.sh)는 과거 제어 해시에 결속된 동결 자료이므로 일반 개발·신규 명령에 사용하지 않는다. 현재 checkpoint가 가리키는 exact event 계약이 hash-bound 과거 명령을 직접 요구할 때만 그 계약 재현 범위에서 실행하고, 현행 runner의 `validate`를 추가로 수행한다.

| 선택자·분류 | 실제 범위 | 필수 전제 | 해석 한계 |
| --- | --- | --- | --- |
| `validate` | `backend/tests`, `tests`, `model`의 모든 `test_*.py`가 정확히 한 계층에 있고 등록 경로가 실제 존재하는지 확인 | 실행 가능한 `PYTHON_BIN` | 테스트 본문은 실행하지 않으며 PASS여도 제품 동작을 증명하지 않음 |
| `unit` | 선택된 Python 단위 테스트, Legacy Web 정책 테스트, Gateway Node 테스트, Android 사용자·관리자 JVM `testDebugUnitTest` | Python lock, 잠긴 Node, 두 package에 `npm ci`로 사전 설치한 의존성, Java 21·Android SDK | DB·실기기·외부 API·정식 시험 근거가 아님 |
| `functional` | 격리 PostGIS를 사용하는 Python 기능/API 테스트, Legacy Web lint·typecheck, Gateway typecheck, Android lint | `unit` 전제와 접근 가능한 `WALKSAFE_TEST_DATABASE_URL` | 운영 DB·실제 네트워크·실기기·배포 검증이 아님 |
| `integration` | 모델 registry/TFLite 계약/PT smoke, 선택된 Python 통합 테스트, Legacy Web production build·trace, Android debug·AndroidTest APK·unsigned release build와 model asset 검사 | 격리 PostGIS, 모델 파일, Node·Java·Android build 환경, 백업 검사 전용 CPython 3.14.6 | AndroidTest APK 생성은 기기 실행이 아니며 unsigned APK는 배포 서명본이 아님 |
| `model-audit` | 격리 fixture 기반 데이터 무결성·offline depth 감사 테스트 | 해당 Python 의존성. 실제 데이터가 필요한 후속 감사에는 별도 로컬 입력 | `all`과 분리된 CI 단계이며 정확도·동등성·실기기 성능·데이터 권리 검증을 대신하지 않음 |
| `HISTORICAL_CONTROL_PYTHON_TESTS` | 동결된 v2.2/v2.3 제어, 완료된 FP046 등 과거 작성·trace snapshot, Web 포함 2026-07-13 Full-RC·release host와 기존 제출 후보 자료에 결속된 검사를 inventory에 보존 | 해당 archive·history 계약 또는 로컬 전용 과거 입력 | 실행 선택자가 없고 `all`에서도 실행되지 않으며 현재 제품 PASS 근거가 아님 |
| `active-session-control` | strict continuation 검사 뒤 활성 v2.4 checkpoint의 transition lifecycle을 재검증하는 제어 테스트 | 정확한 활성 checkpoint와 예상 managed content | commit-stable이며 CI에서 별도 실행하지만 기능·정식 시험·Goal 상태 승격을 대신하지 않음 |
| `all` | `unit` → `functional` → `integration` 순서 | 세 계층의 전제 전부 | `model-audit`·`active-session-control`·외부 작업을 포함하지 않으며, 한 번의 PASS를 정식 279개 시험 또는 출시 PASS로 해석하지 않음 |

`functional`과 `integration`은 `postgresql+psycopg` 형식이고 DB 이름에 `test`가 포함된 전용 PostGIS만 허용한다. runner는 보호 DB명, 운영 `DATABASE_URL`과의 중복, 실제 연결 대상 이름을 확인하고 Alembic migration을 적용한다. 따라서 이 두 계층은 읽기 전용이 아니며 운영 DB를 지정하면 안 된다.

백업 무결성 테스트는 `BACKUP_INTEGRITY_PYTHON_TESTS`에 한 번만 등록되어 `integration`과 `all`에서 실행된다. 일반 `PYTHON_BIN`은 CPython 3.12 환경으로 유지하고, Linux memfd sealing preflight와 전용 최소 lock을 통과한 정확한 CPython 3.14.6 venv의 절대경로를 `WALKSAFE_BACKUP_PYTHON_BIN`에 지정한다. 이 값이 없거나 preflight가 실패하면 runner는 일반 Python으로 대신 실행하지 않고 실패한다.

연결된 emulator·실기기 instrumentation은 `integration`에 `WALKSAFE_RUN_ANDROID_DEVICE_TESTS=true`를 명시할 때만 `connectedDebugAndroidTest`로 실행된다. TFLite host invoke도 `WALKSAFE_TFLITE_SMOKE_IMAGE`와 필요 시 `WALKSAFE_TFLITE_PYTHON`을 지정한 경우에만 추가된다. 이 opt-in이 없으면 해당 결과는 `NOT_RUN`이다.

Integration의 unsigned release 정적 빌드는 사용자 Gateway와 관리자 API에 예약된 `.invalid` HTTPS origin을 주입한다. 필요하면 `WALKSAFE_RELEASE_TEST_GATEWAY_ORIGIN`과 `WALKSAFE_RELEASE_TEST_ADMIN_API_ORIGIN`으로 정확한 테스트 origin을 바꿀 수 있지만, 이 값과 unsigned APK는 실제 배포 endpoint·서명·출시 증거가 아니다.

### 2026-08-13 runner inventory 정리

다음 명령은 테스트 본문을 실행하지 않고 모든 Python 테스트가 정확히 한 분류에 속하는지 확인한다.

```bash
export PYTHON_BIN="$PWD/.venv-tests/bin/python"
test -x "$PYTHON_BIN"
PYTHON_BIN="$PYTHON_BIN" scripts/run_walksafe_test_layers_current.sh validate
```

존재하지 않던 v2.5 후보 경로는 분류에서 제거했다. 완료된 FP046 snapshot 6개는 역사 제어에 둔다. NPC seq56/57 materialization, R002 contract, seq58 reanchor, 8-check start gate, seq59 correction과 seq60 start를 임시 fixture로 검증하는 현재 테스트 7개는 단위 계층에 등록하고, publication 전에 대체된 seq59 start projection은 역사 제어에 둔다. 새 중앙 문서 계약 테스트도 단위 계층에 포함한다. Git에 보존되지 않은 옛 AIHub183 helper를 import하는 테스트는 실행 가능한 모델 감사로 가장하지 않고 역사 inventory에 둔다. NPC completion 생성기 test 파일도 `validate`가 요구하는 정확히 한 분류에 등록한 뒤에만 현행 계층 결과에 포함한다. 이 inventory PASS와 completion projection 단위검사는 제품 observation, Goal 완료, 실제 custody·기기·복구훈련 또는 정식 시험 증거가 아니다. 해당 실행과 5개 release gate는 모두 `NOT_RUN`(미면제), 출시는 `NOT_ELIGIBLE`로 유지한다.

모델·데이터 감사와 세션 결속 검사는 필요할 때 각각 별도로 실행한다.

```bash
PYTHON_BIN="$PYTHON_BIN" scripts/run_walksafe_test_layers_current.sh model-audit
PYTHON_BIN="$PYTHON_BIN" scripts/run_walksafe_test_layers_current.sh active-session-control
```

## 영역별 테스트 역할

| 영역 | 위치·기본 진입점 | 주 역할 | 실행 전제 | 주장 한계 |
| --- | --- | --- | --- | --- |
| Android 사용자 JVM | [`apps/android/app/src/test`](../../apps/android/app/src/test), `cd apps/android && ./gradlew :app:testDebugUnitTest` | 상태기계, 안전정책, 세션·권한, 보안 저장, 모델·경로 계약 | Java 21, Android SDK, Gradle 의존성 | 카메라·ARCore·GPS·진동·음성·화면읽기의 실기기 품질 아님 |
| Android 사용자 instrumentation | [`apps/android/app/src/androidTest`](../../apps/android/app/src/androidTest), `:app:connectedDebugAndroidTest` | Android Keystore와 TFLite device smoke | 연결 기기/emulator, 설치 가능한 debug build | 통제 시나리오의 해당 기기 결과일 뿐 실외 보행·지원기기 전체·서명 release 근거 아님 |
| Android 관리자 JVM | [`apps/android/adminapp/src/test`](../../apps/android/adminapp/src/test), `:adminapp:testDebugUnitTest` | 관리자 인증, 기기증명, 고위험 작업 gate, strict JSON·endpoint 정책 | Java 21, Android SDK | 실제 관리자 기기 분실·복구훈련, 운영 권한·DB 판정 아님 |
| Android 관리자 instrumentation | `apps/android/adminapp/src/androidTest` | 현재 테스트 소스 없음 | 새 시험과 기기 환경을 먼저 준비해야 함 | JVM PASS로 기기 결속·생체/PIN·복구훈련을 완료 처리할 수 없음 |
| Android Gateway | [`apps/android-gateway/test`](../../apps/android-gateway/test), `npm --prefix apps/android-gateway test` | 독립 Gateway의 계약, 암호화 저장, 동의·삭제·telemetry·field ledger | 잠긴 Node, `npm ci` | 배포 endpoint, 실제 Android 연결, Backend/TMAP 운영 품질 아님 |
| Backend | [`backend/tests`](../../backend/tests), 계층 runner | FastAPI/OpenAPI, predecessor 0·1·2행 migration preflight, singleton·custody reset, runtime unaudited update 거부, 대상 key/session 폐기, device proof와 `NORMAL`+`ATTESTED` 고위험 gate | 단위 일부를 제외하면 격리 PostGIS와 migration | forged same-transaction audit, offline migration SQL, 운영 DB·실기기·복구훈련·외부 TMAP·침투시험 아님 |
| 모델·데이터 | [`model/test_two_model_runtime.py`](../../model/test_two_model_runtime.py), `MODEL_AUDIT_PYTHON_TESTS`, integration smoke | runtime 조합, registry·tensor 계약, offline 데이터 무결성 | 모델·데이터·런타임별 의존성 | 정확도·동등성·실기기 FPS·발열·안전성을 자동으로 증명하지 않음 |
| Legacy Web/PWA | [`apps/web`](../../apps/web), 계층 runner의 Node 작업 | 폐쇄된 과거 Web 경계와 회귀 snapshot 보존 | 잠긴 Node와 package lock | 현재 Android 제품·출시·현장 E2E 증거가 아니며 새 기능 기준으로 사용하지 않음 |
| 저장소 제어·Python 계약 | [`tests`](../../tests), 계층 runner | 산출물·정책·Goal·도구·경계의 결정적 회귀 | 테스트별 source/checkpoint 전제 | 문서 구조 PASS를 기능 실행·승인·외부 검수로 승격할 수 없음 |

## 권장 실행 순서

1. `git status --short`로 의도하지 않은 변경이 없는지 확인한다.
2. `PYTHON_BIN=... scripts/run_walksafe_test_layers_current.sh validate`로 inventory부터 검증한다.
3. 변경 영역에 맞는 가장 작은 테스트를 먼저 실행하고 `unit`, 필요한 경우 `functional`·`integration`으로 넓힌다. 세션 checkpoint 변경은 `active-session-control`, 모델·데이터 감사 변경은 `model-audit`을 별도로 실행한다.
4. DB·기기·모델·Node 전제가 없는 검사는 건너뛰어 PASS로 만들지 말고 `BLOCKED` 또는 `NOT_RUN`으로 기록한다.
5. 실행 명령, commit, 환경, 결과와 로그 위치를 남기되 secret·원본 영상·정확 위치는 Git에 넣지 않는다.

세부 Python 테스트 설명은 [tests/README](../../tests/README.md), 기존 계층 계약은 [WalkSafe 테스트 계층](../testing/test_layers_20260711.md)을 참고한다. 두 문서와 runner가 다르면 runner의 실제 동작을 먼저 확인하고 문서·CI·runner를 같은 변경에서 맞춘다.
