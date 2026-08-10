# WalkSafe 전체 제품 RC 생성·검증·서명 절차

> `LEGACY_REFERENCE_ONLY` — Web을 필수 제품으로 묶었던 2026-07-13 역사 절차다. 현재 제품 출시 절차로 실행하지 않는다. Web release builder와 Web-inclusive Full RC builder·validator, release evidence의 `web-release`·`full` profile은 역사 코드를 불러오기 전에 종료 코드 78로 차단되어 있다. 새 Android 사용자 앱·관리자 앱·독립 Android API gateway 릴리스 계약이 승인되기 전까지 아래 명령은 참고자료일 뿐이다.

이 절차는 한 개의 clean source commit에 Web, Android, Backend, Voice를 묶는다. 생성 결과의 Android APK는 **unsigned / non-deployable**이며, 서명 gate가 통과해도 외부 런타임 입력과 현장 acceptance가 없으면 전체 배포 완료가 아니다.

## 1. clean source에서 구성 요소 빌드

각 quality runner와 최종 packaging은 **같은 commit의 서로 다른 `--no-local` fresh clone**에서 실행한다. common `.git`/object DB/config를 공유하는 linked worktree는 clean-room 경계로 사용하지 않는다. runner 시작 시 tracked 변경과 untracked/ignored 파일이 하나도 없어야 하며, Web/Android가 만든 ignored 산출물은 실행 후 허용된 경로에서만 검증된다. Web archive 생성 clone과 Web quality clone도 분리해, 외부에 보존한 archive를 두 번째 fresh build와 byte·mode 단위로 대조한다. 단, Next가 fresh build마다 생성하는 아래의 검증된 보안 키와 서로 다른 clone의 절대 build root만 명시적 v2 정규화 계약을 적용한다. Android quality clone은 builder/validator가 완료될 때까지 유지해 receipt가 검증한 APK 경로를 그대로 입력하고, 네 quality receipt는 packaging source 밖의 전용 디렉터리에 보존한다.

`RELEASE_PYTHON`은 stdlib-only 오케스트레이션용이다. `WEB_PYTHON`은 `apps/web/quality-requirements.lock`, `BACKEND_PYTHON`은 `backend/requirements.lock`, `VOICE_PYTHON`은 `voice/requirements.lock`과 `voice/quality-requirements.lock` 전체를 `--require-hashes --no-compile`로 설치한 서로 다른 전용 interpreter여야 한다. 세 품질 환경은 정책의 trusted tool layer인 `pip==26.1.1`만 lock 밖에 둘 수 있다. 로컬 계층형 테스트 환경은 별도로 `backend/requirements.lock`과 `tests/requirements.lock`을 함께 설치한다. GitHub hosted CI의 CPython 3.12/Linux x86_64 환경은 CPU-only PyTorch wheel을 고정한 단일 `tests/general-quality-cp312-linux-x86_64-cpu.lock`을 설치하며, 이 플랫폼 lock은 RC runtime archive나 production dependency SBOM을 바꾸지 않는다. `tests/requirements.txt`와 테스트용 `.in`·lock은 `RELEASE_SOURCE_INPUTS`에는 넣지 않지만, 모든 tracked source inventory에는 포함되어 source commit에 결속된다. `wheel`을 포함한 다른 distribution, normalized 이름 중복, version 차이, RECORD에 소유되지 않은 loose file/directory, symlink/special file, `.pyc`/`.pyo`가 하나라도 있으면 runner가 거부한다. 모든 production Python entrypoint와 품질 Python step은 `-I -S -B`를 강제한다. 직접 실행하는 entrypoint와 Web shell 자체의 bytes는 operator가 별도 승인한 trust root다. entrypoint는 sibling integrity helper의 SHA-256을 실행 전에 고정하고 stable regular non-symlink `.py` snapshot만 cache-free compile/exec한다. runner는 bootstrap SHA-256, signed gate는 operator verifier SHA-256도 추가로 고정하며 Web shell도 bootstrap hash를 실행 전에 확인한다. stdlib-only bootstrap은 site closure 검증 후에도 stdlib search path를 앞에 유지한 채 site와 repo root를 뒤에 추가한다. RECORD에 포함된 `.pth`는 inert bytes로만 검사하고 `site`/`addsitedir`로 실행하지 않는다. 설치기가 만든 unhashed `.pyc`/`.pyo` RECORD 행은 실제 bytecode와 빈 cache directory가 모두 제거된 경우에만 absent generated entry로 허용한다. 각 runner는 ambient `HOME`을 전달하지 않고 실행 전체에 빈 private HOME을 사용하며 Python user site와 pytest cache를 비활성화한다.

정책은 Web·Backend·Voice의 CPython 3.14.4 전체 site closure count/SHA-256을 source-pin한다. 따라서 package payload와 RECORD를 함께 다시 작성한 환경도 실행 전·후 및 독립 RC 검증에서 거부한다. GitHub hosted CI도 Web artifact용 3.14.4 venv와 일반 CPU 테스트용 3.12.13 환경을 분리한다.

Web release build의 `WALKSAFE_NODE_BIN_DIR`은 `configs/walksafe_node_toolchain_lock_20260715.json`과 일치하는 공식 Node v22.23.1 tree의 `bin` 경로여야 한다. builder는 source-pinned checker로 npm launcher와 전체 npm package closure를 build 전후에 다시 계산하며, 두 attestation과 lock을 Web manifest v4·release evidence·Full-RC closure에 함께 보존한다.

```bash
set -euo pipefail
seed_source="/path/to/approved/walksafe"
source_commit="$(git -C "${seed_source}" rev-parse HEAD)"
clone_root="/secure/build/walksafe-clones-${source_commit}"
web_root="/secure/build/walksafe-web-${source_commit}"
quality_root="/secure/build/walksafe-quality-${source_commit}"
validation_root="/secure/build/walksafe-validation-${source_commit}"
RELEASE_PYTHON="/opt/walksafe/release-venv/bin/python"
WEB_PYTHON="/opt/walksafe/web-quality-venv/bin/python"
BACKEND_PYTHON="/opt/walksafe/backend-quality-venv/bin/python"
VOICE_PYTHON="/opt/walksafe/voice-quality-venv/bin/python"
NODE_ROOT="/opt/walksafe/node-v22.23.1-linux-x64"
NODE_BIN_DIR="${NODE_ROOT}/bin"
umask 022

# destructive install/cleanup 전에 요청한 interpreter가 real 전용 venv인지 검사한다.
quality_python_guard() {
  local requested_python="$1"
  local action="$2"
  local expected_prefix="${3:-}"
  "${requested_python}" -I -S -B - \
    "${requested_python}" "${action}" "${expected_prefix}" <<'PY'
import os
import stat
import sys
import sysconfig
from pathlib import Path

requested = Path(os.path.abspath(sys.argv[1]))
action = sys.argv[2]
expected_prefix = sys.argv[3]
if action not in {"check", "clean"} or not Path(sys.argv[1]).is_absolute():
    raise SystemExit("quality interpreter path/action is invalid")
prefix = Path(os.path.abspath(sys.prefix))
base_prefix = Path(os.path.abspath(sys.base_prefix))
if (
    sys.prefix == sys.base_prefix
    or prefix.resolve(strict=True) != prefix
    or not stat.S_ISDIR(prefix.lstat().st_mode)
    or prefix.resolve(strict=True) == base_prefix.resolve(strict=True)
):
    raise SystemExit(f"refusing non-venv or non-real prefix: {prefix}")
if expected_prefix and prefix != Path(expected_prefix):
    raise SystemExit(f"refusing changed venv prefix before cleanup: {prefix}")
marker = prefix / "pyvenv.cfg"
if (
    marker.resolve(strict=True) != marker
    or not stat.S_ISREG(marker.lstat().st_mode)
):
    raise SystemExit(f"refusing missing/non-real pyvenv.cfg: {marker}")
executable = Path(os.path.abspath(sys.executable))
bin_directory = prefix / "bin"
if (
    executable != requested
    or executable.parent != bin_directory
    or bin_directory.resolve(strict=True) != bin_directory
    or not stat.S_ISDIR(bin_directory.lstat().st_mode)
    or not (stat.S_ISREG(executable.lstat().st_mode) or stat.S_ISLNK(executable.lstat().st_mode))
    or not executable.resolve(strict=True).is_file()
):
    raise SystemExit(f"refusing interpreter outside the venv bin directory: {requested}")
roots = []
for name in ("purelib", "platlib"):
    root = Path(os.path.abspath(sysconfig.get_path(name)))
    if (
        root.resolve(strict=True) != root
        or not stat.S_ISDIR(root.lstat().st_mode)
    ):
        raise SystemExit(f"refusing non-real site root: {root}")
    try:
        relative = root.relative_to(prefix)
    except ValueError as exc:
        raise SystemExit(f"refusing site root outside venv: {root}") from exc
    if not relative.parts:
        raise SystemExit(f"refusing site root outside venv: {root}")
    if root not in roots:
        roots.append(root)
if action == "clean":
    for root in roots:
        for directory, directory_names, file_names in os.walk(
            root, topdown=False, followlinks=False
        ):
            directory_path = Path(directory)
            for file_name in file_names:
                path = directory_path / file_name
                if path.suffix.lower() not in {".pyc", ".pyo"}:
                    continue
                if not stat.S_ISREG(path.lstat().st_mode):
                    raise SystemExit(f"refusing non-regular bytecode path: {path}")
                path.unlink()
            for directory_name in directory_names:
                if directory_name != "__pycache__":
                    continue
                path = directory_path / directory_name
                if not stat.S_ISDIR(path.lstat().st_mode):
                    raise SystemExit(f"refusing non-real cache directory: {path}")
                try:
                    path.rmdir()
                except OSError as exc:
                    raise SystemExit(f"refusing non-empty cache directory: {path}") from exc
print(prefix)
PY
}

quality_pythons=("${WEB_PYTHON}" "${BACKEND_PYTHON}" "${VOICE_PYTHON}")
quality_prefixes=()
for quality_python in "${quality_pythons[@]}"; do
  quality_prefix="$(quality_python_guard "${quality_python}" check)"
  for existing_prefix in "${quality_prefixes[@]}"; do
    if [[ "${quality_prefix}" == "${existing_prefix}" ]]; then
      printf 'quality venv prefix is reused: %s\n' "${quality_prefix}" >&2
      exit 1
    fi
  done
  quality_prefixes+=("${quality_prefix}")
done

# 위 검사를 통과하고 pip==26.1.1이 준비된 fresh 전용 venv에 lock을 설치한다.
PYTHONDONTWRITEBYTECODE=1 "${WEB_PYTHON}" -I -B -m pip install \
  --require-hashes --no-compile -r "${seed_source}/apps/web/quality-requirements.lock"
PYTHONDONTWRITEBYTECODE=1 "${BACKEND_PYTHON}" -I -B -m pip install \
  --require-hashes --no-compile -r "${seed_source}/backend/requirements.lock"
PYTHONDONTWRITEBYTECODE=1 "${VOICE_PYTHON}" -I -B -m pip install \
  --require-hashes --no-compile \
  -r "${seed_source}/voice/requirements.lock" \
  -r "${seed_source}/voice/quality-requirements.lock"

# 같은 venv 경계를 다시 검사하며 venv/ensurepip가 미리 만든 bytecode까지 제거한다.
# non-empty __pycache__나 non-regular bytecode path는 조용히 지우지 않는다.
for quality_index in "${!quality_pythons[@]}"; do
  quality_python="${quality_pythons[${quality_index}]}"
  expected_prefix="${quality_prefixes[${quality_index}]}"
  quality_prefix="$(
    quality_python_guard "${quality_python}" clean "${expected_prefix}"
  )"
  if [[ "${quality_prefix}" != "${expected_prefix}" ]]; then
    printf 'quality venv prefix changed after install: %s\n' "${quality_prefix}" >&2
    exit 1
  fi
  chmod -R go-rwx -- "${quality_prefix}"
  if [[ "$(quality_python_guard "${quality_python}" check "${expected_prefix}")" != "${expected_prefix}" ]]; then
    printf 'quality venv prefix changed after permission hardening: %s\n' "${quality_prefix}" >&2
    exit 1
  fi
done

# 이 시점부터 receipt 생성이 끝날 때까지 pip/ensurepip/install/upgrade를 다시 실행하지 않는다.
install -d -m 0700 /secure/build
install -d -m 0700 "${clone_root}" "${quality_root}" "${validation_root}"

for name in web-artifact web-quality android-quality backend-quality voice-quality package; do
  git clone --no-local --no-checkout \
    "${seed_source}" "${clone_root}/${name}"
  git -C "${clone_root}/${name}" checkout --detach "${source_commit}"
done

web_artifact_source="${clone_root}/web-artifact"
web_quality_source="${clone_root}/web-quality"
android_quality_source="${clone_root}/android-quality"
backend_quality_source="${clone_root}/backend-quality"
voice_quality_source="${clone_root}/voice-quality"
package_source="${clone_root}/package"
android_apk="${android_quality_source}/apps/android/app/build/outputs/apk/release/app-release-unsigned.apk"

(
  cd "${web_artifact_source}"
  PYTHONDONTWRITEBYTECODE=1 PYTHON_BIN="${WEB_PYTHON}" \
    WALKSAFE_NODE_BIN_DIR="${NODE_BIN_DIR}" \
    scripts/build_walksafe_web_release_20260711.sh \
      "${web_root}/web-build-manifest.json"
)

(
  cd "${web_quality_source}"
  "${WEB_PYTHON}" -I -S -B scripts/run_walksafe_product_quality_20260713.py \
    --product web \
    --node-bin-dir "${NODE_BIN_DIR}" \
    --web-release-archive "${web_root}/web-standalone-${source_commit}.tar.gz" \
    --receipt "${quality_root}/walksafe-web-quality-receipt.json"
)

(
  cd "${android_quality_source}"
  "${RELEASE_PYTHON}" -I -S -B scripts/run_walksafe_product_quality_20260713.py \
    --product android \
    --android-gateway-origin "https://walksafe.example.invalid" \
    --receipt "${quality_root}/walksafe-android-quality-receipt.json"
)

(
  cd "${backend_quality_source}"
  WALKSAFE_TEST_DATABASE_URL="postgresql+psycopg://.../walksafe_test?sslmode=verify-full&gssencmode=disable" \
  "${BACKEND_PYTHON}" -I -S -B scripts/run_walksafe_product_quality_20260713.py \
    --product backend \
    --receipt "${quality_root}/walksafe-backend-quality-receipt.json"
)

(
  cd "${voice_quality_source}"
  "${VOICE_PYTHON}" -I -S -B scripts/run_walksafe_product_quality_20260713.py \
    --product voice \
    --receipt "${quality_root}/walksafe-voice-quality-receipt.json"
)

cd "${package_source}"
"${RELEASE_PYTHON}" -I -S -B scripts/build_walksafe_full_rc_20260713.py \
  --source-root "${package_source}" \
  --web-build-manifest "${web_root}/web-build-manifest.json" \
  --web-artifact-root "${web_root}" \
  --web-deployment-archive "${web_root}/web-standalone-${source_commit}.tar.gz" \
  --unsigned-apk "${android_apk}" \
  --web-quality-receipt "${quality_root}/walksafe-web-quality-receipt.json" \
  --android-quality-receipt "${quality_root}/walksafe-android-quality-receipt.json" \
  --backend-quality-receipt "${quality_root}/walksafe-backend-quality-receipt.json" \
  --voice-quality-receipt "${quality_root}/walksafe-voice-quality-receipt.json" \
  --output-root "/secure/build/walksafe-full-rc-${source_commit}"

"${RELEASE_PYTHON}" -I -S -B scripts/validate_walksafe_full_rc_20260713.py \
  --source-root "${package_source}" \
  --rc-root "/secure/build/walksafe-full-rc-${source_commit}" \
  --java /usr/lib/jvm/APPROVED_JRE/bin/java \
  --expected-java-sha256 APPROVED_JAVA_64_HEX_SHA256 \
  --apksigner-jar /opt/android-sdk/build-tools/APPROVED_VERSION/lib/apksigner.jar \
  --expected-apksigner-jar-sha256 APPROVED_APKSIGNER_JAR_64_HEX_SHA256 \
  --receipt "${validation_root}/walksafe-full-rc-validation-receipt.json"
```

품질 runner는 고정 정책의 Web 8단계, Android 4단계, Backend 5단계, Voice 2단계를 실행하고, 명령·실행 파일·공개 환경·로그·산출물 hash를 같은 clean Git commit/tree와 전체 tracked-source inventory에 결속한다. Web은 `npm-build` 직전 `.next`를 제거하고 fresh standalone/static/public/BUILD_ID와 입력 tar.gz의 파일 byte·mode를 다시 대조한다. 정확히 `.next/prerender-manifest.json`의 세 preview key, 두 `server-reference-manifest`의 동일한 32-byte canonical base64 RSC key, `.next/required-server-files.json`과 `server.js`의 교차 일치하는 absolute `*/apps/web` build root만 strict JSON·필드·길이·형식·raw occurrence를 검증한 뒤 span sentinel로 정규화한다. 두 build의 보안 키 재사용과 이 다섯 파일의 나머지 byte 차이는 거부하며 source-derived 고정 key를 만들지 않는다. Android는 `assembleRelease` 직전 기존 APK를 제거하고 생성 직후 hash가 후속 model-asset gate 뒤에도 같은지 확인한다. stdout/stderr 원문이나 DB credential 값은 영수증에 저장하지 않는다.

Web/Backend/Voice runner는 테스트를 실행한 Python binary hash와 해시 고정 lock을 영수증에 함께 기록한다. lock-only distribution identity와 `locks ∪ trusted_tool_distributions` 전체 installed identity를 각각 count·canonical SHA-256으로 남긴다. `walksafe.record-claimed-site-closure.v2`는 root slot/mode, RECORD 소유 regular file의 모든 조상 directory 상대 경로/mode, file 상대 경로/mode/size/content SHA-256을 하나의 deterministic count/digest로 기록한다. absolute root는 별도 provenance이고 digest에는 넣지 않으며, root/directory/file의 device/inode는 명령 전후 private identity로 비교한다. venv `bin`의 외부 RECORD claim은 raw hash/size 검증 뒤 정확한 venv Python shebang만 위치 독립으로 정규화하며, marker literal이나 다른 shebang은 거부한다. 실제 interpreter의 distribution 집합과 전체 site closure가 모두 같아야 통과한다. Web verifier 환경은 `apps/web/quality-requirements.lock`, Voice 환경은 두 Voice lock을 `--require-hashes --no-compile`로 설치한다. Web SBOM package에는 `package-lock.json`의 SHA-512 SRI가 SPDX checksum으로 포함된다.

품질 영수증 자체는 로컬 서명물이 아니다. 신뢰 경계는 clean source에 고정된 runner/policy, fresh-output postcondition, builder와 독립 validator의 artifact 재계산, 그리고 최종 reviewer detached signature다. Web shell은 `/bin/bash -p`로 직접 실행하고 output 생성 전 untracked/ignored path와 Git replace ref를 거부한다. parent runner가 exact source를 검증한 뒤에만 child quality step의 repository application module을 허용한다. 이 비-hermetic 절차는 권한이 분리된 clean-room에서 외부 concurrent writer 없이 실행해야 한다. 시작·종료 exact Git/closure 검사는 지속 변경과 inode 교체를 거부하지만, 공격자가 실행 경계를 장악해 A→B→A로 되돌리는 상황을 영수증 하나만으로 증명한다고 간주하지 않는다.

validator는 generator를 import하지 않고 Git HEAD/tree/index/blob, 명시적 파일 closure, 모든 파일 hash/bytes, 네 제품 품질 영수증과 단계별 JSON 로그, tar path·symlink·mtime·owner·mode·order, Web v4 manifest와 source dependency 입력, APK의 유일한 WalkSafe `BuildConfig` DEX static release/source 값·내장 모델·unsigned 상태, Alembic migrations, 설정 예제, SPDX dependency provenance를 불변 RC snapshot에서 다시 계산한다. RC 디렉터리의 미기록 파일도 거부한다. root-owned canonical Java는 실행 전 snapshot hash와 실행 후 inode/bytes/runtime trust를 다시 확인하고, `apksigner.jar`는 검증한 proc-fd snapshot을 `java -jar`에 전달한다. `--receipt`는 미리 만든 빈 validation bundle에 네 제품 영수증의 검증된 snapshot 사본과 Java/apksigner.jar exact record를 함께 만든다. 출력 parent는 symlink ancestry가 없는 existing real directory여야 하며, 실패 후 남은 partial quality copy는 자동 삭제하지 않으므로 원인을 확인한 뒤 bundle 전체를 수동 폐기하고 새 빈 디렉터리에서 재시도한다.

## 2. 런타임 설치 계약

- Web standalone archive는 `/srv/walksafe/web/web`에 푼다. Web runtime-support archive는 최상위 디렉터리를 제거해 `/srv/walksafe/web`에 푼다. `walksafe-web.service`는 `run_walksafe_web_single_instance_20260713.py`를 반드시 통과하며 `WALKSAFE_WEB_REPLICAS=1`과 private process lock을 강제한다.
- Web TLS ingress는 runtime-support의 `deploy/nginx/walksafe-web.conf.example`을 `/etc/nginx/conf.d/walksafe-web.conf`로 복사한 뒤 세 `CHANGE_ME` 값을 실제 hostname·certificate·private-key 경로로 바꾼다. 이 설정은 외부 `CF-Connecting-IP`, `X-Real-IP`, `X-Forwarded-For`를 이어 붙이지 않고 TLS peer 주소로 덮어쓰며 나머지 forwarding identity header를 제거한다. 설치 전 `python3 -I -B /srv/walksafe/web/scripts/check_walksafe_trusted_proxy_20260716.py --nginx /usr/sbin/nginx --config /srv/walksafe/web/deploy/nginx/walksafe-web.conf.example` 실행 회귀와, 실제 경로를 반영한 `nginx -t`가 모두 통과해야 한다.
- Backend archive는 최상위 디렉터리를 제거해 `/srv/walksafe/backend`에 푼다. 품질 receipt와 같은 CPython 3.14.4 runtime에서 `python -m pip install --require-hashes -r backend/requirements.lock`으로 전용 virtualenv를 만든다. Backend service는 migration oneshot unit을 `Requires/After`로 묶어 매 기동 전 `alembic upgrade head` 성공을 요구한다. 모든 replica는 `WALKSAFE_ACTOR_RATE_LIMIT_STORE=postgresql`과 같은 PostGIS를 사용하고, `UPLOAD_DIR`도 동일한 내구성 공유 저장소여야 한다.
- Voice archive는 최상위 디렉터리를 제거해 `/srv/walksafe/voice`에 푼다. 같은 방식으로 `voice/requirements.lock`을 `--require-hashes`로 설치한다. 외부 STT/TTS snapshot은 정확한 40-hex revision과 전체 파일 manifest 이중 SHA-256을 통과해야 하며 service에는 read-only로 노출한다. process-local 제한 때문에 worker/replica를 각각 1로 고정하고 private process lock을 사용한다.
- `/etc/walksafe/*.env`는 `deploy/config/*.env.example`에서 별도로 만들고 root 소유 0600으로 둔다. 예시의 `CHANGE_ME` 값으로는 기동하거나 readiness를 승인하지 않는다.

## 3. 패키지에서 의도적으로 제외한 입력

다음은 manifest의 external runtime input/blocker다. 없거나 hash/권한이 검증되지 않으면 operational deployment complete가 아니다.

- DB, TMAP, Web session, field/admin, Voice 전용 credential
- packaged nginx trusted-proxy 설정에 주입할 public DNS, TLS certificate/private key, 외부에서는 443만 열고 Web 3000 직접 접근을 차단하는 방화벽 경계
- Backend img768 detector `.pt` weight
- Voice STT/TTS model cache와 선택적 reference audio
- Voice 비-WAV duration 검사용 `ffprobe` 실행 파일
- 다중 Backend replica가 공유하는 PostGIS와 내구성 `UPLOAD_DIR`
- Android operator keystore와 사전 승인된 certificate SHA-256
- 실기기·실외·기관 접수 acceptance evidence
- 품질 receipt toolchain과 일치하는 immutable Node/CPython runtime image digest와 OS stdlib/native library inventory
- 독립 validator receipt와 승인된 release-reviewer key의 detached operator attestation

Android APK에는 별도 Android asset gate로 검증하는 on-device TFLite asset이 내장되어 있다. full-RC builder가 loose model weight를 추가하는 것은 금지한다.

## 4. 별도 operator Android 서명 workflow

자동화는 key를 생성·읽기·사용하지 않는다. 권한이 분리된 operator가 manifest의 unsigned APK SHA-256을 먼저 대조하고, 조직의 keystore 정책과 승인된 alias로 별도 경로에 서명한다. 아래 명령은 절차 예시이며 이 저장소 자동화가 실행하지 않는다.

```bash
apksigner sign \
  --ks /operator/private/APPROVED_KEYSTORE \
  --ks-key-alias APPROVED_ALIAS \
  --out /operator/output/walksafe-signed.apk \
  /secure/build/walksafe-full-rc-COMMIT/android/app-release-unsigned.apk
```

독립 reviewer는 validation receipt, manifest/closure/source/tree/blocker, 네 제품 품질 영수증을 그대로 담은 `walksafe.operator-release-attestation.v1` JSON을 검토한다. 자동화는 reviewer key를 생성하거나 서명하지 않는다. reviewer는 조직 승인 key, binary-document class `00`, SHA-256/384/512 중 하나로 JSON의 detached signature를 만들고, operator는 공개 keyring, 승인된 **primary reviewer key**의 40자리 fingerprint, GPG, Java, apksigner.jar SHA-256을 별도 신뢰 채널에서 받는다. GPG `GOODSIG`/`VALIDSIG`의 signing fingerprint와 primary fingerprint를 분리·결속하고, primary fingerprint만 out-of-band 승인값과 비교해 승인된 signing subkey도 정확히 처리한다. text-document class `01`, SHA-1 등 약한 digest, expired/revoked key, expired/invalid signature status는 GPG return code가 0이어도 거부한다. 다음 검증은 최초에 snapshot한 단일 공개 keyring proc-fd만 GPG에 전달하고 GPG homedir 입력과 자동 key 조회를 허용하지 않는다. GPG/Java는 root 소유이며 group/world writable이 아닌 system runtime만 허용한다. apksigner.jar, keyring, attestation, signature, 서명 APK는 검증한 FileSnapshot bytes를 subprocess·파싱·기록에 일관되게 사용한다.

```bash
"${RELEASE_PYTHON}" -I -S -B scripts/verify_walksafe_operator_attestation_20260713.py \
  --source-root /path/to/clean/source \
  --manifest /secure/build/walksafe-full-rc-COMMIT/walksafe-full-rc-manifest.json \
  --validation-receipt /secure/build/walksafe-validation-COMMIT/walksafe-full-rc-validation-receipt.json \
  --attestation /operator/review/walksafe-operator-attestation.json \
  --signature /operator/review/walksafe-operator-attestation.json.asc \
  --expected-operator-fingerprint APPROVED_PRIMARY_KEY_40_HEX_FINGERPRINT \
  --gpg /usr/bin/gpg \
  --expected-gpg-sha256 APPROVED_GPG_64_HEX_SHA256 \
  --java /usr/lib/jvm/APPROVED_JRE/bin/java \
  --expected-java-sha256 APPROVED_JAVA_64_HEX_SHA256 \
  --apksigner-jar /opt/android-sdk/build-tools/APPROVED_VERSION/lib/apksigner.jar \
  --expected-apksigner-jar-sha256 APPROVED_APKSIGNER_JAR_64_HEX_SHA256 \
  --gpg-keyring /operator/trust/approved-reviewers.gpg
```

operator는 승인된 Android 인증서 SHA-256을 별도 채널에서 받아 최종 gate를 실행한다. gate는 위 운영자 증명을 다시 검증한 뒤 `apksigner verify --print-certs`, 단일 인증서 digest, unsigned manifest hash, 서명 전후 ZIP payload 동등성을 확인한다.

```bash
install -d -m 0700 /operator/output
"${RELEASE_PYTHON}" -I -S -B scripts/verify_walksafe_signed_android_release_20260713.py \
  --source-root /path/to/clean/source \
  --manifest /secure/build/walksafe-full-rc-COMMIT/walksafe-full-rc-manifest.json \
  --unsigned-apk /secure/build/walksafe-full-rc-COMMIT/android/app-release-unsigned.apk \
  --signed-apk /operator/output/walksafe-signed.apk \
  --expected-cert-sha256 APPROVED_64_HEX_SHA256 \
  --validation-receipt /secure/build/walksafe-validation-COMMIT/walksafe-full-rc-validation-receipt.json \
  --operator-attestation /operator/review/walksafe-operator-attestation.json \
  --operator-attestation-signature /operator/review/walksafe-operator-attestation.json.asc \
  --expected-operator-fingerprint APPROVED_PRIMARY_KEY_40_HEX_FINGERPRINT \
  --gpg /usr/bin/gpg \
  --expected-gpg-sha256 APPROVED_GPG_64_HEX_SHA256 \
  --gpg-keyring /operator/trust/approved-reviewers.gpg \
  --java /usr/lib/jvm/APPROVED_JRE/bin/java \
  --expected-java-sha256 APPROVED_JAVA_64_HEX_SHA256 \
  --apksigner-jar /opt/android-sdk/build-tools/APPROVED_VERSION/lib/apksigner.jar \
  --expected-apksigner-jar-sha256 APPROVED_APKSIGNER_JAR_64_HEX_SHA256 \
  --receipt /operator/output/android-signing-gate.json
```

서명 gate receipt도 `deployment_complete=false`를 유지한다. 이 gate는 Android signing만 증명하며 전체 제품 운영 준비나 외부 acceptance를 대신하지 않는다.

최종 production 완료 판정은 위 receipt를 그대로 신뢰하지 않고 `check_walksafe_release_evidence_20260711.py --profile full`이 같은 입력으로 signed gate를 다시 실행한 뒤에만 만든다. `release_evidence`의 signed APK hash·승인 인증서, Full-RC manifest/closure/source, unsigned APK, validation receipt, operator attestation/signature와 승인된 GPG/keyring/Java/apksigner.jar가 모두 재계산 결과에 결속되어야 한다. 별도 signing receipt는 재계산 JSON과 field뿐 아니라 canonical UTF-8 bytes도 정확히 같아야 한다.

Full-RC manifest의 10개 blocker는 기존 운영 결과만으로 자동 삭제하지 않는다. reviewer가 `walksafe.production-blocker-resolution.v1` canonical JSON과 detached signature를 별도로 제공해야 한다. 이 contract는 같은 source/manifest/closure/artifact binding, manifest와 정확히 같은 정렬 blocker id·`required` 문구, blocker별 `status=resolved`, 코드에 고정된 check 전부 `true`, blocker마다 서로 다른 실제 evidence file의 상대 경로·bytes·SHA-256을 포함한다. gate는 같은 승인 primary fingerprint/GPG/keyring으로 signature와 evidence bytes를 검증한다. trusted-edge TLS/firewall, Voice model cache·ffprobe, shared PostGIS/durable upload storage, runtime image/native library도 이 명시적 evidence가 없으면 계속 blocker다.

출력의 `verification_scope=approved-reviewer-signed-operational-attestation-integrity.v1`은 gate가 reviewer 서명, 계약 필드, release binding, evidence 파일 무결성을 기계적으로 확인했다는 뜻이다. gate가 DNS/TLS/firewall, storage replica, model cache, ffprobe, runtime image가 있는 현장을 직접 재실행·재검사했다는 뜻은 아니다. 각 고정 check와 연결 evidence의 현장 진실성은 승인 reviewer가 서명으로 책임지는 운영 attestation이며, 그 구분을 production receipt에서도 유지한다.

기존 실기기·TMAP·기관·retention·backup/restore·privacy gate와 위 10개 resolution을 모두 통과한 마지막 단계에서만 존재하지 않는 외부 경로에 `walksafe.production-release.v1` receipt를 mode `0600`으로 원자 발행한다. 출력은 source, Full-RC, validation bundle, signed APK/signing receipt, attestation, trust tool/keyring, blocker resolution 입력 디렉터리 안에 둘 수 없다. `web-release`와 `android-research` profile은 이 receipt를 발행할 수 없다.

```bash
# 앞선 운영 gate 인수 전체와 함께 실행한다. receipt 파일은 미리 만들지 않는다.
install -d -m 0700 /operator/final
"${BACKEND_PYTHON}" -I -S -B scripts/run_walksafe_isolated_python_20260713.py \
  --repo-root . --product backend -- \
  scripts/check_walksafe_release_evidence_20260711.py \
  --profile full \
  --evidence /operator/evidence/release-evidence.json \
  ... \
  --full-rc-manifest /secure/build/walksafe-full-rc-COMMIT/walksafe-full-rc-manifest.json \
  --full-rc-validation-receipt /secure/build/walksafe-validation-COMMIT/walksafe-full-rc-validation-receipt.json \
  --full-rc-unsigned-apk /secure/build/walksafe-full-rc-COMMIT/android/app-release-unsigned.apk \
  --operator-attestation /operator/review/walksafe-operator-attestation.json \
  --operator-attestation-signature /operator/review/walksafe-operator-attestation.json.asc \
  --expected-operator-fingerprint APPROVED_PRIMARY_KEY_40_HEX_FINGERPRINT \
  --gpg /usr/bin/gpg \
  --expected-gpg-sha256 APPROVED_GPG_64_HEX_SHA256 \
  --gpg-keyring /operator/trust/approved-reviewers.gpg \
  --java /usr/lib/jvm/APPROVED_JRE/bin/java \
  --expected-java-sha256 APPROVED_JAVA_64_HEX_SHA256 \
  --apksigner-jar /opt/android-sdk/build-tools/APPROVED_VERSION/lib/apksigner.jar \
  --expected-apksigner-jar-sha256 APPROVED_APKSIGNER_JAR_64_HEX_SHA256 \
  --android-signing-gate-receipt /operator/output/android-signing-gate.json \
  --blocker-resolution-receipt /operator/resolution/production-blocker-resolution.json \
  --blocker-resolution-signature /operator/resolution/production-blocker-resolution.json.asc \
  --production-receipt /operator/final/walksafe-production-release.json
```
