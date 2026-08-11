# WalkSafe 개발 환경 가이드

이 문서는 일반 개발·검증 환경을 준비하는 절차입니다. current code map과 각 모듈의 실제 source·lock·버전 관리 설정이 실행 계약이며, 이 문서는 그 위치를 안내합니다. hash로 봉인된 과거 README는 현행 계약에서 제외합니다. 운영 배포나 정식 시험 환경을 구성하는 문서가 아닙니다.

## 기준 도구

| 도구 | 일반 개발 기준 | 근거 |
|---|---|---|
| Python | CI는 CPython 3.12.13 | [quality workflow](../../.github/workflows/quality.yml) |
| Node.js | Gateway는 22 이상 23 미만, CI는 22.23.1 | [Gateway package 계약](../../apps/android-gateway/package.json), [quality workflow](../../.github/workflows/quality.yml) |
| Java | JDK 21 | [사용자 앱 build 설정](../../apps/android/app/build.gradle.kts), [관리자 앱 build 설정](../../apps/android/adminapp/build.gradle.kts) |
| Gradle | 저장소 wrapper 9.3.1 사용 | [Gradle wrapper 설정](../../apps/android/gradle/wrapper/gradle-wrapper.properties) |
| Android SDK | compile/target SDK 36, min SDK 26 | [사용자 앱 build 설정](../../apps/android/app/build.gradle.kts), [관리자 앱 build 설정](../../apps/android/adminapp/build.gradle.kts) |
| 데이터베이스 | PostgreSQL/PostGIS 16-3.5 개발 컨테이너 | [Compose 설정](../../docker-compose.yml) |

CI의 정확한 도구와 lock은 [quality workflow](../../.github/workflows/quality.yml)가 기준입니다. 다른 운영체제나 CPU에서 CI 전용 Python lock을 그대로 재사용하지 않습니다.

## 공통 준비

저장소는 비공개 코드와 증거 경로를 포함하므로 새 clone은 사용자 전용 기본 권한을 권장합니다.

```bash
umask 077
git status --short --branch
python3 --version
node --version
java -version
docker compose version
```

작업 시작 규칙은 [저장소 작업 지침](../../AGENTS.md), 현재 focus와 통제 검사는 [프로젝트 체크포인트](../control/walksafe-project-continuation-checkpoint.json)와 checkpoint가 가리키는 focus Goal 계약을 먼저 확인합니다. hash로 봉인된 과거 재개 안내서의 gate 명령은 현행 절차로 실행하지 않습니다.

## Python과 Backend

Backend 실행 환경과 저장소 품질 테스트 환경은 서로 다른 hash lock과 의존성 집합을 가지므로 별도 venv로 만듭니다. 두 lock을 한 venv에 동시에 설치하지 않습니다.

```bash
python3.12 -m venv .venv-backend
source .venv-backend/bin/activate
python -m pip install --require-hashes --no-compile -r backend/requirements.lock
python -m pip check
deactivate

python3.12 -m venv .venv-tests
source .venv-tests/bin/activate
python -m pip install --require-hashes --only-binary=:all: --no-compile \
  -r tests/general-quality-cp312-linux-x86_64-cpu.lock
python -m pip check
deactivate

test "$(python3.14 -I -S -B -c 'import sys; print(".".join(map(str, sys.version_info[:3])))')" = "3.14.6"
python3.14 -m venv .venv-backup-tests
source .venv-backup-tests/bin/activate
python -m pip install --require-hashes --only-binary=:all: --no-compile \
  -r tests/backup-integrity-cp314.lock
python -m pip check
python -I -S -B scripts/walksafe_backup_integrity.py --runtime-capability-preflight
deactivate
export WALKSAFE_BACKUP_PYTHON_BIN="$PWD/.venv-backup-tests/bin/python"
```

[Backend lock](../../backend/requirements.lock)과 [CI 일반 품질 테스트 lock](../../tests/general-quality-cp312-linux-x86_64-cpu.lock)은 각각의 설치 재현용입니다. 테스트 lock은 CPython 3.12·Linux x86_64 CPU 환경용이므로 다른 플랫폼에는 그대로 적용하지 않습니다. 백업 무결성 통합 테스트만 Linux memfd sealing을 갖춘 정확한 CPython 3.14.6과 [전용 최소 lock](../../tests/backup-integrity-cp314.lock)을 사용하며 일반 3.12 환경으로 대체하지 않습니다. 새 셸에서는 `WALKSAFE_BACKUP_PYTHON_BIN`을 다시 지정합니다. 직접 의존성을 바꿀 때는 source requirements와 대응 lock 갱신 절차를 함께 따릅니다.

로컬 설정은 예제에서 별도 파일로 만들되 기존 파일을 덮어쓰지 않습니다. 최초 한 번만 복사하고 `CHANGE_ME`와 비밀값을 실제 로컬 값으로 교체합니다. 실제 값은 커밋하지 않습니다.

```bash
if type deactivate >/dev/null 2>&1; then deactivate; fi
source .venv-backend/bin/activate
test -e backend/.env || cp backend/.env.example backend/.env
install -d -m 0700 backend/.local-secrets
export WALKSAFE_LOCAL_KEYRING="$PWD/backend/.local-secrets/report-image-keyring.json"
if test ! -e "$WALKSAFE_LOCAL_KEYRING"; then
  python -I -B - <<'PY'
import base64
import json
import os
import secrets

path = os.environ["WALKSAFE_LOCAL_KEYRING"]
flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
descriptor = os.open(path, flags, 0o600)
payload = {
    "generation": 1,
    "keys": [{
        "id": "local-report-image-key-v1",
        "material": base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip("="),
        "state": "active",
    }],
    "previous_manifest_sha256": None,
    "schema": "walksafe.report-image-keyring.v1",
}
with os.fdopen(descriptor, "w", encoding="utf-8") as output:
    json.dump(payload, output, separators=(",", ":"), sort_keys=True)
    output.write("\n")
PY
fi
python -I -B -c 'import secrets; print(secrets.token_urlsafe(32))'
```

마지막 명령의 새 값을 `WALKSAFE_PRIVACY_HMAC_SECRET`으로 한 번 정해 보관하고, 기존 DB에 연결할 때는 다시 생성하지 않습니다. `backend/.env`의 DB 값과 함께 다음 로컬 전용 값을 설정합니다. keyring 경로는 위에서 만든 파일의 절대경로를 사용합니다.

```env
WALKSAFE_ENVIRONMENT=development
WALKSAFE_FIELD_TEST_SECURITY_ENABLED=false
WALKSAFE_ALLOW_INSECURE_LOCAL_DEV=true
WALKSAFE_ADMIN_SECURITY_ENABLED=false
WALKSAFE_REPORT_IMAGE_KEY_PROVIDER=secret_file
WALKSAFE_REPORT_IMAGE_KEY_FILE=<repository-absolute-path>/backend/.local-secrets/report-image-keyring.json
WALKSAFE_PRIVACY_HMAC_SECRET=<locally-generated-value>
DETECT_V2_MODE=fake
DETECT_V2_UNIFIED_MODEL_PATH=
DETECT_V2_CUSTOM_TACTILE_MODEL_PATH=
DETECT_V2_COCO_MODEL_PATH=
DETECT_V2_RUNTIME_CONFIG_PATH=
INFERENCE_PROCESS_ISOLATION_ENABLED=false
TMAP_POI_PROVIDER=mock
```

이 profile은 loopback 개발용이며 field·staging·production에 사용할 수 없습니다. 값을 저장한 뒤 실행합니다.

```bash
docker compose --env-file backend/.env up -d db
python -m alembic -c backend/alembic.ini upgrade head
PYTHONPATH=. python -m uvicorn backend.app.main:app --reload --port 8000
```

운영 DB와 테스트 DB를 재사용하지 않습니다. 테스트 DB 이름에는 `test`를 포함하고 [Python 테스트 안내](../../tests/README.md)의 보호 검사를 거칩니다. 자세한 환경값과 보안 경계는 [Backend 안내](../../backend/README.md)를 따릅니다.

## Android 사용자·관리자 앱

JDK 21과 Android SDK 36을 준비한 뒤 저장소의 Gradle wrapper만 사용합니다.

```bash
(
  cd apps/android
  ./gradlew test --no-daemon
  ./gradlew assembleDebug --no-daemon
)
```

사용자 앱과 관리자 앱은 같은 Gradle 프로젝트에 있지만 식별자·서명·세션·배포 경계가 분리됩니다. 현재 사용자 앱 절차는 [Android 코드 지도](code/android-user.md), 관리자 앱 세부 설정은 [관리자 앱 안내](../../apps/android/adminapp/README.md)를 확인합니다. 결속된 과거 Android README의 절대 APK 경로는 사용하지 않습니다.

debug APK 생성과 JVM 테스트는 실기기, 현장, 접근성, 서명 release 검증을 대신하지 않습니다.

## Android Gateway

Node.js 22 환경에서 Gateway 패키지의 lock을 사용합니다.

```bash
npm --prefix apps/android-gateway ci
npm --prefix apps/android-gateway run typecheck
npm --prefix apps/android-gateway test
```

실행 환경값, 상태 저장소 권한, 단일 프로세스 제약은 [Gateway 코드 지도](code/android-gateway.md)와 source를 따릅니다. 결속된 과거 Gateway README의 “네 개 API” 표를 현행 공개 표면으로 사용하지 않습니다. 로컬 테스트 통과를 실제 배포나 실기기 연결로 기록하지 않습니다.

## Legacy Web 회귀

Web/PWA는 제품 경계상 `LEGACY_REFERENCE_ONLY`, 저장소 카탈로그상 `LEGACY_REFERENCE`입니다. 필요한 경우 과거 경계가 다시 열리지 않는지만 검사합니다.

```bash
npm --prefix apps/web ci
npm --prefix apps/web test
npm --prefix apps/web run lint
npm --prefix apps/web run typecheck
npm --prefix apps/web run build
```

개발 서버나 공개 터널을 현재 제품 실행 경로로 사용하지 않습니다. 상세 경계는 [Legacy Web 안내](../../apps/web/README.md)를 확인합니다.

## 기본 저장소 검사

환경 설치 뒤 먼저 작은 정적 검사를 실행합니다.

```bash
export PYTHON_BIN="$PWD/.venv-tests/bin/python"
test -x "$PYTHON_BIN"
PYTHONDONTWRITEBYTECODE=1 "$PYTHON_BIN" -B scripts/check_walksafe_project_continuation_v2_4.py
PYTHON_BIN="$PYTHON_BIN" scripts/run_walksafe_test_layers_current.sh validate
PYTHONPATH=. "$PYTHON_BIN" scripts/generate_walksafe_openapi.py --check
```

이 검사는 각각 체크포인트 정합, 테스트 분류, API 계약만 확인합니다. 실행 범위와 계층별 전체 명령은 [시험 문서 안내](../testing/README.md), [Python 테스트 안내](../../tests/README.md), [스크립트 안내](../../scripts/README.md)를 따릅니다.

## 모델·데이터 환경

모델 학습과 데이터 materialize는 일반 앱 개발 환경과 분리합니다. GPU, 대용량 원본, 학습 결과를 준비하기 전에 [모델 안내](../../model/README.md)와 [데이터 소스 안내](../../data_sources/README.md)의 현재 후보·provenance·로컬 전용 경계를 확인합니다. `--help`, `--dry-run`, `--print-only`가 있는 도구는 먼저 사전검사를 실행합니다.

## 비밀값과 생성물

- `.env`, token, API key, keystore, 인증서, 운영 URL을 커밋하지 않습니다.
- 가상환경, `node_modules`, Gradle build, Python cache, 임시 로그를 커밋하지 않습니다.
- 사용자 영상·음성·정확 위치와 원본 데이터셋은 Git 밖의 승인된 저장소에 둡니다.
- 생성 파일을 수작업으로 고치기 전에 생성기와 `--check` 경로를 확인합니다.

일반 빌드와 내부 검사가 모두 통과해도 정식 시험은 `279/279 NOT_RUN`, 출시 gate 5개는 `NOT_RUN`, 출시는 `NOT_ELIGIBLE`입니다.
