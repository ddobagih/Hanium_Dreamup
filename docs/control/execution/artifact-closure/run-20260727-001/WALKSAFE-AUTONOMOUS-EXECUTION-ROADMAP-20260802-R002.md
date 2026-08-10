# WalkSafe 자율 실행 로드맵 20260802 R002

## 0. 지위와 이번 revision의 실행 범위

```text
document_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R002
document_class = INTERNAL_EXECUTION_ROADMAP
review_status = PENDING_TWO_INTERNAL_STRUCTURAL_REVIEWS
executable_scope = WP001_CANDIDATE_BUILD_ONLY
canonical_mutation_allowed = false
live_product_source_or_test_mutation_allowed = false
official_progress_delta = 0
artifact_completion_credit_delta = 0
release_claim = NOT_ELIGIBLE
```

R001과 그 두 검수는 변경하지 않는 역사다. R001은 formal 검수 `3/4/3`,
skeptical 검수 `2/3/0`으로 `REVISION_REQUIRED`였다. 이 R002는 그 지적을
해소하기 위해 현재 실행을 **저장소 밖의 WP001 격리 후보 빌드** 하나로 줄인다.

현재 대화에서 사용자는 추가 확인 없이 로컬 구현을 계속하도록 직접 지시했다.
그 지시는 이 살아 있는 세션에서 아래 격리 후보를 만드는 실행 근거지만, 이
문서가 권한을 새로 만들거나 그 지시를 새 세션에서 재생하는 근거는 아니다.
아래 내부 검수도 품질 gate일 뿐 승인, formal test, canonical 전환 권한이 아니다.

이번 revision이 허용하는 write는 다음뿐이다.

1. 이 R002와 인접한 두 내부 검수 문서
2. `/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/`의 작업 파일
3. `/home/ddobagi/.codex/candidates/walksafe/20260802-wp001-r001/`의 봉인 후보

live repository의 source, test, lock, runner, product, checkpoint, event,
Gap/Backlog, active pointer를 수정하거나 후보를 적용하는 행위는 이 revision의
범위가 아니다. 외부 행위, 배포, 비밀값, 유료 자원, 실제 데이터도 범위 밖이다.

## 1. 고정 시작점과 사실 경계

| 항목 | 값 |
|---|---|
| repository | `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715` |
| branch / HEAD | `codex/walksafe-rc2-hardening-20260715` / `a3ad7eead6b5d834d3e0675422475a9aad351e3d` |
| control | v2.4 ACTIVE, seq39 `c12be7a16436d96b8939028d7b5bedb50580ad7761955410669d4c9d1cb7cf0a` |
| checkpoint | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` |
| Goal | focus EPIC-03, ready EPIC-03·EPIC-12, active leaf 없음 |
| artifact | closed-equivalent 126, open 131, artifact completion credit delta 0 |
| formal / actual / Gate | `0/279` / device·event `0/0` / `0/5`, waived false |
| release / project | `NOT_ELIGIBLE` / `NOT_COMPLETE` |
| dirty snapshot | tracked 125개, archived untracked files 2,550개 |

시작 복구본은
`/home/ddobagi/.codex/backups/walksafe/20260802T0125KST-rc2-pre-roadmap`이며
bundle, tracked patch, untracked archive 검증은 PASS했다. 복구본은 이번 후보가
수정하지 않는다. v2.4 Quick2도 시작 시 PASS했다.

`tests/requirements.lock`은 inode를 공유하는 hardlink(`nlink=4`)이므로 live
파일을 in-place로 쓰면 안 된다. 이번 단계는 그 파일을 읽기만 하며 후보 출력은
새 inode로 만든다.

## 2. 역할 분리와 독립 검수 gate

- roadmap 작성자와 WP001 후보 구현자: root agent
- structural reviewer: 작성·수정·구현·apply에 참여하지 않은 agent 1명
- skeptical reviewer: 위 역할들과 겸임하지 않은 별도 agent 1명
- 미래 apply/canonical authorizer: 이번 revision에서 배정하지 않음

두 reviewer는 같은 frozen R002 SHA를 서로의 verdict를 받기 전에 독립적으로
검수한다. 검수 이름은 `INTERNAL_STRUCTURAL_REVIEW`와
`INTERNAL_SKEPTICAL_REVIEW`이며 formal test나 owner attestation이 아니다.

인접 검수 파일은 다음 두 개다.

- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R002-independent-structural-review-r001.md`
- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R002-independent-skeptical-review-r001.md`

각 검수에는 다음 필드가 줄 단위로 정확히 한 번 있어야 한다.

```text
review_id: <unique>
review_type: INTERNAL_STRUCTURAL_REVIEW | INTERNAL_SKEPTICAL_REVIEW
reviewer_agent: <identity>
reviewer_session: <identity>
independence_attestation: TRUE
target_sha256: <64 lowercase hex>
target_bytes: <decimal>
target_lines: <decimal>
verdict: PASS | REVISION_REQUIRED
blocking: <decimal>
major: <decimal>
minor: <decimal>
```

freeze 뒤 root는 두 파일이 regular/non-symlink이고 서로 다른 reviewer, session,
review type을 가지며, `sha256sum`, `stat -c %s`, `wc -l`로 얻은 R002 값과 각
target 필드가 같은지 검사한다. 시작 조건은 두 검수 모두 `verdict: PASS`이고
`blocking/major/minor: 0/0/0`인 경우뿐이다. 어느 severity든 하나라도 nonzero,
필드 중복·누락, target 불일치 또는 독립성 불일치이면 candidate write는 0이고
R002를 고치지 않은 채 add-only R003를 작성·재검수한다.

판정 명령은 repository root에서 다음 exact Python을 실행하며 expected exit는
0, stdout은 `R002 review gate: PASS` 한 줄이다.

```bash
python3 -B - \
  docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R002.md \
  docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R002-independent-structural-review-r001.md \
  docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R002-independent-skeptical-review-r001.md <<'PY'
from hashlib import sha256
from pathlib import Path
import re
import stat
import sys

roadmap = Path(sys.argv[1])
reviews = [Path(value) for value in sys.argv[2:]]
expected_types = ["INTERNAL_STRUCTURAL_REVIEW", "INTERNAL_SKEPTICAL_REVIEW"]
required = (
    "review_id", "review_type", "reviewer_agent", "reviewer_session",
    "independence_attestation", "target_sha256", "target_bytes",
    "target_lines", "verdict", "blocking", "major", "minor",
)
roadmap_raw = roadmap.read_bytes()
target = {
    "target_sha256": sha256(roadmap_raw).hexdigest(),
    "target_bytes": str(len(roadmap_raw)),
    "target_lines": str(roadmap_raw.count(b"\n")),
}
parsed = []
for path, review_type in zip(reviews, expected_types, strict=True):
    mode = path.lstat().st_mode
    assert stat.S_ISREG(mode) and not path.is_symlink()
    text = path.read_text(encoding="utf-8")
    item = {}
    for key in required:
        values = re.findall(rf"(?m)^{re.escape(key)}: (.+)$", text)
        assert len(values) == 1, (path, key, values)
        item[key] = values[0]
    assert item["review_type"] == review_type
    assert item["independence_attestation"] == "TRUE"
    assert item["verdict"] == "PASS"
    assert all(item[key] == value for key, value in target.items())
    assert all(item[key] == "0" for key in ("blocking", "major", "minor"))
    parsed.append(item)
assert len({item["review_id"] for item in parsed}) == 2
assert len({item["reviewer_agent"] for item in parsed}) == 2
assert len({item["reviewer_session"] for item in parsed}) == 2
print("R002 review gate: PASS")
PY
```

## 3. 시작 CAS, 격리와 manifest 계약

검수 통과 직후 후보 디렉터리가 아직 존재하지 않음을 확인하고 다음 시작 상태를
봉인한다. 경로가 이미 있거나 symlink면 덮어쓰거나 삭제하지 않고 중단한다.

```text
candidate_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-r001
work_root = /home/ddobagi/.codex/work/walksafe/20260802-wp001-r001
```

`start-state.json`은 다음을 기록한다.

- exact branch와 HEAD
- R002 및 두 검수의 SHA-256, byte, line count
- `LC_ALL=C git status --porcelain=v1 -z --untracked-files=all`의 raw SHA-256
- 모든 실제 consumed input의 절대/저장소 상대 경로, SHA-256, byte, file mode
- Python·Node 실행 파일과 lock/checker의 identity
- 후보의 고정 output allowlist

consumed input은 실제 접근한 파일 전체다. builder가 읽은 파일을 manifest에서
빠뜨리거나, regular file이 아닌 저장소/cache 입력을 묵인하면 실패한다. 단,
공식 Node lock이 명시한 `bin/npm` symlink는 먼저 Node toolchain checker가 exact
target과 closure를 검증한 경우에만 도구 입력으로 허용한다.

각 lane 직전과 최종 봉인 직전에 branch, HEAD, porcelain raw SHA와 consumed
input content-set을 다시 계산해 `start-state.json`과 비교한다. 불일치하면 새
bytes를 조용히 받아들이지 않고 후보를 `QUARANTINED_INPUT_DRIFT`로 종료한다.

작업용 venv, wheelhouse, `node_modules`, `dist`, 임시 복사본은 `work_root`에만
둔다. `candidate_root`에는 allowlist의 봉인 결과만 두며 모든 파일은 regular,
`nlink=1`, 모든 디렉터리 아래 symlink 0이어야 한다. 명령별 argv, cwd, 환경
allowlist, exit code, stdout bytes, stderr bytes와 각각의 SHA-256을 receipt에
기록한다. 예상 exit와 다르면 해당 lane은 FAIL이다.

봉인 output allowlist는 다음 경로로 정확히 고정한다. 각 `*-receipt.json` 안에
원 stdout/stderr를 UTF-8 또는 base64와 byte hash로 넣으며 별도 임의 log 파일을
추가하지 않는다.

```text
README.md
start-state.json
input-manifest.json
receipts/fail-first-receipt.json
receipts/v24-start-receipt.json
receipts/v24-end-receipt.json
lanes/a-python-lock/tests-requirements-build-01.lock
lanes/a-python-lock/tests-requirements-build-02.lock
lanes/a-python-lock/tests-requirements-candidate.lock
lanes/a-python-lock/verify-python-lock-candidate.py
lanes/a-python-lock/version-delta.json
lanes/a-python-lock/toolchain-manifest.json
lanes/a-python-lock/wheelhouse.tar
lanes/a-python-lock/compile-01-receipt.json
lanes/a-python-lock/compile-02-receipt.json
lanes/a-python-lock/clean-install-receipt.json
lanes/b-runner/run-walksafe-test-layers-20260802-candidate.sh
lanes/b-runner/check-walksafe-test-layer-assignment-20260802.py
lanes/b-runner/assignment-manifest.json
lanes/b-runner/validate-receipt.json
lanes/b-runner/contract-receipt.json
lanes/c-gateway/gateway-epochs.json
lanes/c-gateway/input-manifest.json
lanes/c-gateway/python-receipt.json
lanes/c-gateway/node-toolchain-receipt.json
lanes/c-gateway/npm-ci-receipt.json
lanes/c-gateway/npm-typecheck-receipt.json
lanes/c-gateway/npm-test-receipt.json
lanes/c-gateway/npm-build-receipt.json
lanes/d-artifact/historical-control-readme.md
lanes/d-artifact/check-walksafe-artifact-baseline-epochs-20260802.py
lanes/d-artifact/artifact-epoch-cases.json
lanes/d-artifact/self-test-receipt.json
candidate-manifest.json
```

## 4. fail-first 기준선

직접 재현한 bounded 기준선은 다음 명령의 `exit=1`, `6 failed, 17 passed`다.

```bash
PYTHONDONTWRITEBYTECODE=1 PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 \
/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python \
-B -m pytest -p no:cacheprovider -q \
tests/test_walksafe_test_database_preflight.py \
tests/test_walksafe_artifact_baseline_materialization_20260722.py
```

직접 확인된 실패 node와 분류는 다음 여섯 개뿐이다.

1. `test_general_test_dependency_lock_matches_source_and_backend_lock` — Pillow 12.2/12.3
2. `test_layer_runner_assigns_model_runtime_pytest_to_unit` — stale pytest-call count 3/4
3. `test_layer_runner_removes_ambient_node_execution_options` — orphan 검사 선행 실패
4. `test_ambient_database_ready_flag_cannot_bypass_preflight[functional]` — 같은 orphan
5. `test_ambient_database_ready_flag_cannot_bypass_preflight[integration]` — 같은 orphan
6. `test_plan_build_is_read_only_and_preflight_cli_is_read_only` — historical/current README epoch 혼합

기술 인계서의 `7 failures`는 당시 full-suite raw manifest가 없으므로 일곱 번째
node를 추정하지 않는다. 후속 full-suite manifest로 재현되기 전에는
`UNCONFIRMED_HISTORICAL_COUNT`다. 이번 후보는 live 파일을 고치지 않으므로 위
fail-first가 live에서 PASS로 바뀌었다고 주장하지 않는다.

## 5. WP001 격리 후보 빌드

실행 순서는 `A → B → C와 D 병렬 → E 봉인`이다. C와 D만 서로 다른 work
subdirectory와 receipt를 사용해 병렬 실행할 수 있다.

### A. Python lock epoch 후보

고정 입력:

| 경로 | SHA-256 |
|---|---|
| `backend/requirements.txt` | `304f4dcca26b40bc7a5e296deaa0c8c32b0bea5122edc50388bef8da2e118e24` |
| `backend/requirements.lock` | `0d17e6bb78f882a7e32b4d14d047c4c34e60737db28c5c163b60c2929fea6700` |
| `tests/requirements.txt` | `ef919bb39f5a67fe3feb7780cb5a9dcc82c27a7306f254ad7c1a6f7a27c86c91` |
| `tests/requirements.lock` | `abd690bacf91a65907e6b8357252ead9082181e9ed1b4db98987b2685e51b9fe` |
| hosted CPU `.in` / `.lock` | `7e19da9fece33e4be7bc4e2f83988494fd52a14d29b77180e65a48750cd3dd57` / `5fe49848eee31f86211fafd6a0077ec0e0859737190221421c74a10db53918aa` |

도구는 CPython 3.12.13
`/home/ddobagi/.local/share/hanium-dreamup/python-3.12.13+20260510/bin/python3.12`와
새 successor 환경의 `pip==25.0.1`, `pip-tools==7.6.0`,
`setuptools==83.0.0`이다. W5와 동일한 보존 환경이라고 주장하지 않는다.
Context7의 `/jazzband/pip-tools` 공식 문서에서 `--output-file`,
`--generate-hashes`, `--reuse-hashes`, `--strip-extras`, `--resolver` 계약을
확인했다. 다운로드한 전체 wheel closure의 파일 hash와 설치 후 `pip freeze`를
receipt에 봉인하고, 설치는 그 wheelhouse만 사용한다.

toolchain 준비 명령은 아래 세 개이며 각 expected exit는 0이다.

```bash
/home/ddobagi/.local/share/hanium-dreamup/python-3.12.13+20260510/bin/python3.12 \
  -m pip download --dest /home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/wheelhouse \
  'pip==25.0.1' 'setuptools==83.0.0' 'pip-tools==7.6.0'
/home/ddobagi/.local/share/hanium-dreamup/python-3.12.13+20260510/bin/python3.12 \
  -m venv /home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/toolchain
/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/toolchain/bin/python \
  -m pip install --no-index \
  --find-links=/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/wheelhouse \
  'pip==25.0.1' 'setuptools==83.0.0' 'pip-tools==7.6.0'
```

두 독립 build root에 세 입력을 새 inode로 복사한 뒤 같은 tool venv에서 각각
다음 명령을 실행한다. expected exit는 모두 0이다.

```bash
/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/toolchain/bin/python \
  -m piptools compile --resolver=backtracking \
  --constraint=backend/requirements.lock --generate-hashes --reuse-hashes \
  --allow-unsafe --strip-extras --no-header --quiet \
  --output-file=tests/requirements.lock tests/requirements.txt
```

이 argv를 cwd
`/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/a/build-01`과
`.../a/build-02`에서 각각 한 번 실행한다.

수용 oracle:

- build-01과 build-02 output bytes가 동일
- predecessor/candidate package key 집합 동일
- 유일한 version delta가 `pillow 12.2.0 → 12.3.0`
- backend/candidate 공통 pin mismatch 0
- candidate lock은 regular, `nlink=1`
- 새 clean CPython 3.12 venv에서 backend lock + candidate lock을
  `pip install --require-hashes --no-compile`로 함께 설치하고 `pip check` PASS
- clean venv에서 Pillow `12.3.0`, pytest `8.4.2`
- candidate-equivalent lock checker와 hosted CPU lock 기존 node PASS

clean install과 checker의 exact 명령은 다음이며 모두 expected exit 0이다.

```bash
/home/ddobagi/.local/share/hanium-dreamup/python-3.12.13+20260510/bin/python3.12 \
  -m venv /home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/a/clean-acceptance
/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/a/clean-acceptance/bin/python \
  -m pip install --require-hashes --no-compile \
  -r /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715/backend/requirements.lock \
  -r /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-r001/lanes/a-python-lock/tests-requirements-candidate.lock
/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/a/clean-acceptance/bin/python -m pip check
/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/a/clean-acceptance/bin/python -B \
  /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-r001/lanes/a-python-lock/verify-python-lock-candidate.py \
  --repo /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715 \
  --candidate-lock /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-r001/lanes/a-python-lock/tests-requirements-candidate.lock
PYTHONDONTWRITEBYTECODE=1 PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 \
/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python \
  -B -m pytest -q -p no:cacheprovider \
  tests/test_walksafe_test_database_preflight.py::test_hosted_cpu_lock_matches_sources_and_production_pins
```

clean install을 자원 문제로 끝내지 못하면 `NOT_RUN_RESOURCE_BLOCKED`이며 A는
PASS가 아니다. 부분 설치나 기존 venv 검사는 대체 증거가 아니다.

봉인 출력은 `lanes/a-python-lock/` 아래 candidate lock, 두 생성 hash,
version-delta JSON, toolchain/wheelhouse manifest, clean-install receipt다.

### B. test runner epoch 후보

고정 입력은 runner
`scripts/run_walksafe_test_layers_20260711.sh` SHA
`4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d`,
preflight test SHA
`a2756e0ae74e01843b44da21a31a6b27c803c95a789038a82f5b8a533e996bec`,
그리고 시작 manifest가 봉인한 `backend/tests`, `tests`, `model`의
`test_*.py` exact universe다. 현재 count는 discovered 132, assigned 127,
orphan 5다.

후보 runner는 기존 파일을 덮지 않는 날짜 붙은 successor이며 외부 후보에서
`WALKSAFE_REPO_ROOT`를 explicit absolute root로 받아 `validate`만 실행할 수
있다. 다섯 orphan의 분류는 다음과 같다.

- `tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py` → active-session-control
- `tests/test_walksafe_phase1_exact257_successor_r011_20260729.py` → historical/targeted
- `tests/test_walksafe_w3_engineering_evidence_20260726.py` → historical/targeted
- `tests/test_walksafe_plan_rebaseline_r022_candidate_20260730.py` → prototype/targeted-only
- `tests/test_walksafe_v2_5_control_candidate_20260730.py` → prototype/targeted-only

`unit`, `functional`, `integration`, `active-session-control`에는 각각 Python pytest
호출이 정확히 하나 있고 `all` 실행 순서도 이 네 순서여야 한다. historical,
model-audit, prototype/targeted-only inventory는 `all`에 암묵 실행하지 않는다.

검증 명령과 expected exit는 다음과 같다.

```bash
WALKSAFE_REPO_ROOT=/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715 \
bash /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-r001/lanes/b-runner/run-walksafe-test-layers-20260802-candidate.sh \
  validate                                       # exit 0
python3 -B /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-r001/lanes/b-runner/check-walksafe-test-layer-assignment-20260802.py \
  --repo /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715 \
  --runner /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-r001/lanes/b-runner/run-walksafe-test-layers-20260802-candidate.sh
```

checker JSON의 exact oracle은 `discovered=132`, `assigned=132`, `missing=0`,
`duplicate=0`, `configured_not_discovered=0`, logical pytest calls 4와 위 순서다.
배열별 count는 unit 31, functional 23, integration 7, model-audit 3,
historical 47, active-session 19, prototype/targeted-only 2로 정확히 132다.
successor checker는 `test_*.py`가 아닌 direct-only 파일로 만들어 현재 discovery
universe를 스스로 늘리지 않는다. 봉인 출력은 runner, checker, assignment
manifest와 두 receipt다.

### C. Gateway historical/current epoch 검증

제품 source 변경은 0이다. 고정 checker/test SHA는 다음과 같다.

- boundary checker `a5a55de7426f08da07f7a85488af623a7ce749d4ebfbe3e4253ad1dae9f4cc20`
- boundary test `e02211cfc4aa31e76746530710fbc581de7c5f131ebf00b9da26deca3fac49ee`
- Phase-E trace test `87f15584dce1cdf871197dd751a8137a7119b86d9d861408b74b2fdb2da06e12`
- Phase-E builder `946cce1491074f60f2eaf4fc94a0242ed77b41027dca5ff412f0c8639256e356`
- current OpenAPI `bee3e05de6287ca420b12816d4cc822cc5666093cd260bfe895cdf28ba070f1f` / 39,266 bytes

checker의 `REQUIRED_PATHS`와 trace builder가 실제 읽은 파일을 consumed manifest에
모두 넣는다. historical 계약은 4 API, current 계약은 `/api/field-walk`을 포함한
정확한 5 API다. 현재 경계를 history로 되돌리거나 제품 route를 추가하지 않는다.
historical contract seal은
`9f94ee0d264ef68fb7edb1c6a79c0fc0e247eac12efedd382d65c33268195be7`,
historical Phase-E snapshot seal은
`04f112cd0bc31a07e4d629d2841be4a01eb2118c0793c8c88f2c44415ca3c30c`다.

검증은 Python 두 파일의 34 tests와 boundary CLI를 bytecode/cache 없이 실행하고,
Node v22.23.1/npm 10.9.8 toolchain checker PASS 뒤 `apps/android-gateway`의 regular
source만 `work_root`에 복사해 `npm ci --ignore-scripts`, `npm run typecheck`,
`npm test`, `npm run build`를 실행한다. 모두 expected exit 0이다. missing, extra,
method drift와 field-walk 제거 negative는 Python test에 의해 reject되어야 한다.
Python oracle은 34 passed, Node test oracle은 62 passed다.

exact 명령은 다음 순서이며 모두 expected exit 0이다. Node 명령의 prefix는
격리된 `env -i`와
`/home/ddobagi/.local/share/hanium-dreamup/node-v22.23.1-linux-x64/bin/npm`이고,
candidate package root는
`/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/current-root/apps/android-gateway`다.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -B \
  scripts/build_walksafe_epic01_phase_e_android_gateway_trace_20260723.py --check
PYTHONDONTWRITEBYTECODE=1 python3 -B \
  scripts/check_walksafe_android_gateway_boundary_20260723.py
PYTHONDONTWRITEBYTECODE=1 PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 .venv/bin/python -B \
  -m pytest -q -p no:cacheprovider \
  tests/test_walksafe_android_gateway_boundary_20260723.py \
  tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py
/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python \
  -I -S -B scripts/check_walksafe_node_toolchain_20260715.py \
  --node-root /home/ddobagi/.local/share/hanium-dreamup/node-v22.23.1-linux-x64 \
  --lock configs/walksafe_node_toolchain_lock_20260715.json
/usr/bin/env -i HOME=/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/node-home \
  PATH=/home/ddobagi/.local/share/hanium-dreamup/node-v22.23.1-linux-x64/bin:/usr/bin:/bin \
  LANG=C.UTF-8 LC_ALL=C.UTF-8 CI=true \
  NPM_CONFIG_USERCONFIG=/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/node-home/npmrc \
  NPM_CONFIG_GLOBALCONFIG=/dev/null \
  NPM_CONFIG_CACHE=/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/node-home/npm-cache \
  /home/ddobagi/.local/share/hanium-dreamup/node-v22.23.1-linux-x64/bin/npm \
  --prefix /home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/current-root/apps/android-gateway ci --ignore-scripts
/usr/bin/env -i HOME=/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/node-home \
  PATH=/home/ddobagi/.local/share/hanium-dreamup/node-v22.23.1-linux-x64/bin:/usr/bin:/bin \
  LANG=C.UTF-8 LC_ALL=C.UTF-8 CI=true \
  NPM_CONFIG_USERCONFIG=/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/node-home/npmrc \
  NPM_CONFIG_GLOBALCONFIG=/dev/null \
  NPM_CONFIG_CACHE=/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/node-home/npm-cache \
  /home/ddobagi/.local/share/hanium-dreamup/node-v22.23.1-linux-x64/bin/npm \
  --prefix /home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/current-root/apps/android-gateway run typecheck
/usr/bin/env -i HOME=/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/node-home \
  PATH=/home/ddobagi/.local/share/hanium-dreamup/node-v22.23.1-linux-x64/bin:/usr/bin:/bin \
  LANG=C.UTF-8 LC_ALL=C.UTF-8 CI=true \
  NPM_CONFIG_USERCONFIG=/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/node-home/npmrc \
  NPM_CONFIG_GLOBALCONFIG=/dev/null \
  NPM_CONFIG_CACHE=/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/node-home/npm-cache \
  /home/ddobagi/.local/share/hanium-dreamup/node-v22.23.1-linux-x64/bin/npm \
  --prefix /home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/current-root/apps/android-gateway test
/usr/bin/env -i HOME=/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/node-home \
  PATH=/home/ddobagi/.local/share/hanium-dreamup/node-v22.23.1-linux-x64/bin:/usr/bin:/bin \
  LANG=C.UTF-8 LC_ALL=C.UTF-8 CI=true \
  NPM_CONFIG_USERCONFIG=/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/node-home/npmrc \
  NPM_CONFIG_GLOBALCONFIG=/dev/null \
  NPM_CONFIG_CACHE=/home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/node-home/npm-cache \
  /home/ddobagi/.local/share/hanium-dreamup/node-v22.23.1-linux-x64/bin/npm \
  --prefix /home/ddobagi/.codex/work/walksafe/20260802-wp001-r001/c/current-root/apps/android-gateway run build
```

봉인 출력은 historical exact4/current exact5 route·method JSON, consumed hash
manifest, Python/Node 명령 receipt뿐이다. `node_modules`와 `dist`는 봉인하지 않는다.

### D. artifact baseline historical/current epoch 후보

고정 입력:

| 의미 | 경로 / SHA-256 / bytes |
|---|---|
| legacy materializer | `scripts/materialize_walksafe_artifact_baseline_approval_20260722.py` / `cf06e2c8c5e6635f90dfa73f10b73cf4a47d841fb79e96c2c97e2b4ef9b45607` |
| legacy test | `tests/test_walksafe_artifact_baseline_materialization_20260722.py` / `4fab1a575534b6e2350b8ef4f250a8106a39bac9977a91221e160d0e1d23114e` |
| COMMITTED receipt | `docs/control/baselines/walksafe-artifact-baseline-application-receipt-20260722-r001.json` / `002355b92c9862a9fbdc443a48b28657975f91fb58caf3504a28df5eb1f524bb` |
| pretransition snapshot | `docs/control/baselines/walksafe-artifact-pretransition-snapshot-20260722-r001.json` / `1e1fe3ea0b23c8d8d9264bfeac7ffb8750aa175bc9b8b17b569962dc466f54a7` |
| historical README | external cache / `b3dde3a3c3f74fd383979eb8ed3a8ac9d6727592ddad0ea8c86e8b5143e07fa3` / 2,630 |
| current README | `docs/deliverables/00-control/README.md` / `ed331dd2894a8ccd41b22df8c9272858a9e7ca89b090cd3a20942baf35d74c54` / 3,108 |
| current register | `docs/deliverables/00-control/artifact-register.json` / `a0c993257c1b80211c2e8e4db5aa464708ac4aba9d47ed9b434eda277ee6547f` |
| current change log | `docs/deliverables/00-control/artifact-change-log.json` / `cea8b58fc5afa527614522481df56d69618c6fd1015c10b63d6cc8b6e0678c67` |

cache preimage는
`/home/ddobagi/.cache/walksafe-v24-live-seq1-source.96h9mq/docs/deliverables/00-control/README.md`가
regular/non-symlink이고 위 hash/size일 때만 새 inode로 후보에 복사한다. cache를
정본이나 런타임 의존성으로 삼지 않는다.

direct-only dual checker는 receipt의 historical binding들을 temp root에
재구성한다. DOC-01/DOC-05 preimage는 위 snapshot의 `frozen_documents`에서
canonical JSON bytes로 복원해 receipt의
`c4a5259744febc9cc442785be1748a0e0da88dbc86ef6459f1f6ee74fcaf62b6` /
`c4b63a01c3ae30efecb0f08c21678057626af10ed8cfef258ddf90136d0aca1c`
binding과 일치해야 한다. 그 뒤 기존 `validate_committed_state(temp_root)`를
PASS시키고, 별도 current axis에서 live README, register, change log의 self-seal과
current navigation binding을 검증한다. legacy
materializer, receipt와 historical test는 수정하지 않는다.

아래 명령의 expected exit는 0이다.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -B \
  /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-r001/lanes/d-artifact/check-walksafe-artifact-baseline-epochs-20260802.py \
  --repo /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715 \
  --historical-readme /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-r001/lanes/d-artifact/historical-control-readme.md \
  --self-test
```

self-test는
positive dual PASS 외에 swapped epochs, historical-only, current-only, missing,
symlink, extra-byte를 각각 nonzero/reject로 확인한다. 봉인 출력은 historical
preimage, dual checker, case manifest와 receipt다.

### E. 통합 봉인과 판정

다음 조건이 모두 참일 때만 상태를 `INTERNAL_CANDIDATE_BUILT`로 기록한다.

- A, B, C, D가 모두 PASS이고 `NOT_RUN`/미분류 실패 0
- 시작/종료 branch, HEAD, porcelain raw SHA와 consumed content-set이 동일
- candidate allowlist 밖 경로 0, symlink 0, regular file의 `nlink!=1` 0
- 각 output의 SHA-256, bytes, mode와 producer receipt가 final manifest에 존재
- 기존 v2.4 continuation·Goal graph Quick2는 종료 시에도 exit 0
- live repository의 source/test/canonical/product mutation 0
- formal/device/Gate/release 상태와 모든 공식 progress delta 0

하나라도 실패하면 candidate는 `FAILED` 또는 `QUARANTINED_*`이고 PASS로 축약하지
않는다. candidate bundle은 적용 가능 패치가 아니라 미래 교정 계획의 검토 입력이다.

Quick2 exact 명령은 repository root에서 다음 두 개다.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/check_walksafe_project_continuation_v2_4.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/check_walksafe_goal_graph_v2_4.py
```

## 6. 중단, 재개와 전역 격리

중단 후에는 새 write 전에 `start-state` reconcile → branch/HEAD/status/input CAS
재검증 → 이미 생성된 output/receipt hash 검증 → 현재 세션의 lane checker PASS
순서를 지킨다. 하나라도 다르면 기존 후보에 이어 쓰지 않고 `QUARANTINED_RESUME`
상태로 보존한다.

정본 hash 손상, 정책 충돌, 안전·보안 critical 또는 예상하지 않은 live write는
candidate/control snapshot 전체의 전역 격리 사유다. 독립 disposition 전에는
다른 lane도 진행하거나 어떤 apply도 하지 않는다. 단순 네트워크·디스크·메모리
부족은 해당 lane만 `NOT_RUN_RESOURCE_BLOCKED`로 두되 전체 PASS를 금지한다.

## 7. 전체 후속 로드맵 — 이번 revision에서는 비실행

아래 단계는 순서와 성공 경계만 정한다. 모두
`NON_EXECUTABLE_UNTIL_STAGE_SPECIFIC_PLAN_AND_GATE`이며 R002 통과만으로 시작하지
않는다.

1. **WP001 output 독립 검수** — 같은 candidate manifest SHA를 구현자와 다른 두
   reviewer가 정확성·fail-closed·역사보존 관점에서 검수한다. any-finding이면
   기존 bundle을 고치지 않고 add-only successor candidate를 만든다.
2. **control-repair 후보** — live 적용이 아닌 versioned candidate로만 작성한다.
   exact allowed-delta manifest, write set, old/new complete-state oracle, crash
   injection, stale-CAS/retry checker와 별도 권한 근거가 없으면 활성화하지 않는다.
3. **canonical 활성화** — 권한 있는 별도 결정과 stage-specific plan을 거친다.
   고정 `19/19`를 재사용하지 않고 당시 active package ID, full-contract SHA,
   registry-derived 명령 집합, source/config/test/toolchain snapshot과 event-scoped
   output hash를 시작·재개 직전에 결속한다. 입력 byte가 바뀌면 gate는 무효다.
4. **68 Gap/frontier와 단일 제품 leaf** — 유효한 control 아래 current r021에서
   control-repair leaf를 먼저 합법적으로 materialize/start한 뒤 atomic pair와
   frontier를 갱신한다. FP-048은 재계산 전 선결론이 아니다.
5. **제품 leaf 반복** — fail-first, 최소 구현, targeted/component 회귀, 독립
   검수, fresh active full gate를 leaf별로 수행한다. 한 번에 active leaf는 하나다.
6. **exact257 artifact** — 내부 ready/run lane과 owner/attestation/real-event 외부
   lane을 분리한다. 외부 lane은 실제 행위 전 OPEN을 유지하며 packet이나 단위시험만
   으로 completion credit을 주지 않는다.
7. **immutable candidate와 formal/release** — 같은 source/config/model candidate에
   대해 실제 권한자가 formal 279, 실기기·현장, 5 Gate, 출시·배포·이관을 수행한다.
   그 전에는 `0/279`, `0/0`, `0/5`, `NOT_ELIGIBLE`, `NOT_COMPLETE`다.

위 번호는 보수적 실행 우선순위이지 현재 r021 dependency DAG를 새로 정의하지
않는다. 각 단계는 직전 출력의 실제 bytes가 생긴 뒤 작고 검증 가능한 별도 계획을
만들어야 하며, 미래 전체를 하나의 authority FSM으로 미리 구현하지 않는다.

## 8. 다음 단일 행동

R002를 freeze하고 서로 독립인 structural/skeptical reviewer 두 명이 같은 SHA를
검수한다. 둘 다 `0/0/0` PASS일 때만 §3의 start CAS를 만들고 WP001-A 격리
후보부터 실행한다. 그 전과 그 후 모두 live apply는 0이다.
