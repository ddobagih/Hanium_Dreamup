# WalkSafe 새 터미널 실행 준비 인계 R009

- 문서 ID: `WS-CONTINUATION-EXECUTION-HANDOFF-20260730-R009`
- 상태: `SOURCE_DRIFT_BLOCKED_RESTART_HANDOFF_REVIEW_PENDING`
- 작성일: `2026-07-30`
- predecessor:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R008.md`
- predecessor SHA-256:
  `11c109046feefdf9d85b41dee43b07719c787709bb1871a845bbb0369550c902`
- predecessor bytes: `6,822`
- predecessor FAIL review:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R008-independent-review-r001.md`
- predecessor review SHA-256:
  `4e609e2f898c62a686c6817a439c67975f172f7baa6c02a5f594940182c9bca5`
- predecessor review bytes: `3,425`
- 저장소:
  `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715`
- branch: `codex/walksafe-rc2-hardening-20260715`
- HEAD: `a3ad7eead6b5d834d3e0675422475a9aad351e3d`

## 1. 범위와 상속

이 R009는 R008을 수정하지 않고 R008 독립검수 MAJOR 2건만 닫는 add-only
successor다.

1. 결속할 문서는 `O_NOFOLLOW` single-read로 한 번만 읽는다.
2. 같은 고정 바이트에서 hash/bytes/verdict와 R007 §3 block을 검증한다.
3. 추출 block을 별도 hash/bytes로 pin한다.
4. 그 고정 block만 clean environment의 absolute `/bin/bash`에 전달한다.

R008의 전체 읽기 순서와 R007의 source 상태, 두 source 전략,
P → M → FP-008 승인 DAG, 금지선은 그대로 상속한다. R009도 source 전략
선택, source 변경, candidate build, 승인 요청, canonical/Goal/product write
권한이 아니다.

## 2. 새 터미널 전체 읽기 순서

공통 prefix는
`docs/control/execution/artifact-closure/run-20260727-001/`이다.

1. `CONTINUATION-EXECUTION-HANDOFF-20260730-R004.md`
2. `CONTINUATION-EXECUTION-HANDOFF-20260730-R004-independent-review-r001.md`
3. `CONTINUATION-EXECUTION-HANDOFF-20260730-R005.md`
4. `CONTINUATION-EXECUTION-HANDOFF-20260730-R005-independent-review-r001.md`
5. `CONTINUATION-EXECUTION-HANDOFF-20260730-R006.md`
6. `CONTINUATION-EXECUTION-HANDOFF-20260730-R006-independent-review-r001.md`
7. R004 §6 item 1~15를 그 상대순서대로 실제로 읽는다.
8. `CONTINUATION-EXECUTION-HANDOFF-20260730-R007.md`
9. `CONTINUATION-EXECUTION-HANDOFF-20260730-R007-independent-review-r001.md`
10. `CONTINUATION-EXECUTION-HANDOFF-20260730-R008.md`
11. `CONTINUATION-EXECUTION-HANDOFF-20260730-R008-independent-review-r001.md`
12. 이 R009 §3 fail-fast를 실행한다.
13. `CONTINUATION-EXECUTION-HANDOFF-20260730-R009-independent-review-r001.md`

R004 §6 item 1~15의 exact 목록과 prefix 해석은 R008 §2를 그대로 따른다.
R004~R008의 실행 블록은 별도로 실행하지 않는다.

## 3. single-read isolated frozen R007 fail-fast

아래 block의 embedded Python은 각 문서를 no-follow로 한 번만 읽고 그
고정 바이트에서 모든 pin과 verdict를 검사한다. R007 §3 fenced Bash bytes는
별도 pin을 통과한 뒤 base64로만 외부 shell에 전달된다. 문법 검사와 실제
실행은 `env -i`와 absolute executable을 사용한다.

```bash
(
cd /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715
set -euo pipefail

ws_block_b64="$(
  /usr/bin/python3 -I -S -B <<'PY'
import base64
import hashlib
import os
import stat
import sys

PREFIX = "docs/control/execution/artifact-closure/run-20260727-001"
SPECS = (
    (
        f"{PREFIX}/CONTINUATION-EXECUTION-HANDOFF-20260730-R005-independent-review-r001.md",
        "65a0772e00d7865d12c9c8aee532e6be96935c0922eeee0b784a98afadae88fc",
        2890,
        b"- \xed\x8c\x90\xec\xa0\x95: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R006`",
    ),
    (
        f"{PREFIX}/CONTINUATION-EXECUTION-HANDOFF-20260730-R007.md",
        "df36cd21dc2f1e76f20698a0c1ccd3696f311e1806b2700ea588f86910043c0b",
        8323,
        None,
    ),
    (
        f"{PREFIX}/CONTINUATION-EXECUTION-HANDOFF-20260730-R007-independent-review-r001.md",
        "e9796aa146af6e8f18f1872260116befed731d0266d8a3cfb057e788557f2787",
        3206,
        b"- \xed\x8c\x90\xec\xa0\x95: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R008`",
    ),
    (
        f"{PREFIX}/CONTINUATION-EXECUTION-HANDOFF-20260730-R008.md",
        "11c109046feefdf9d85b41dee43b07719c787709bb1871a845bbb0369550c902",
        6822,
        None,
    ),
    (
        f"{PREFIX}/CONTINUATION-EXECUTION-HANDOFF-20260730-R008-independent-review-r001.md",
        "4e609e2f898c62a686c6817a439c67975f172f7baa6c02a5f594940182c9bca5",
        3425,
        b"- \xed\x8c\x90\xec\xa0\x95: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R009`",
    ),
)


def read_once(path: str) -> bytes:
    flags = os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW
    fd = os.open(path, flags)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            raise RuntimeError(f"not single-link regular: {path}")
        chunks = []
        while True:
            chunk = os.read(fd, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        return b"".join(chunks)
    finally:
        os.close(fd)


observed = {}
for path, expected_sha, expected_bytes, expected_line in SPECS:
    data = read_once(path)
    if len(data) != expected_bytes:
        raise RuntimeError(f"byte mismatch: {path}")
    if hashlib.sha256(data).hexdigest() != expected_sha:
        raise RuntimeError(f"hash mismatch: {path}")
    if expected_line is not None and expected_line not in data.splitlines():
        raise RuntimeError(f"verdict mismatch: {path}")
    observed[path] = data

r007_path = f"{PREFIX}/CONTINUATION-EXECUTION-HANDOFF-20260730-R007.md"
r007 = observed[r007_path]
section = b"\n## 3. "
opening = b"\n```bash\n"
closing = b"\n```\n"
if r007.count(section) != 1:
    raise RuntimeError("R007 section 3 is not unique")
section_at = r007.index(section)
opening_at = r007.index(opening, section_at) + len(opening)
closing_at = r007.index(closing, opening_at)
block = r007[opening_at : closing_at + 1]
if len(block) != 4202:
    raise RuntimeError("R007 block byte mismatch")
if hashlib.sha256(block).hexdigest() != (
    "95bbe9a79e78365f2539660f837da2065d8ffd0b1f54ce2b4fbab9a88e55ae47"
):
    raise RuntimeError("R007 block hash mismatch")
sys.stdout.buffer.write(base64.b64encode(block))
PY
)"
[[ -n "$ws_block_b64" ]] || exit 1

/usr/bin/printf '%s' "$ws_block_b64" \
  | /usr/bin/base64 --decode \
  | /usr/bin/env -i PATH=/usr/bin:/bin LANG=C.UTF-8 \
      /bin/bash --noprofile --norc -n

if ! ws_r007_output="$(
  /usr/bin/printf '%s' "$ws_block_b64" \
    | /usr/bin/base64 --decode \
    | /usr/bin/env -i PATH=/usr/bin:/bin LANG=C.UTF-8 \
        /bin/bash --noprofile --norc
)"; then
  exit 1
fi
[[ -z "$ws_r007_output" ]] || exit 1
)
```

현재 고정 물리 상태의 정상 기대 결과는 rc=0과 stdout 0 bytes다. pin,
verdict, single-read, extracted-block 또는 frozen block 검사 하나라도
실패하면 nonzero로 종료해야 한다.

## 4. 다음 결정과 효력 경계

§3 PASS 뒤에도 공식 상태는 v2.4 / sequence 39 / canonical r021,
artifact `126/257`, open `131`, formal `0/279`, actual event `0`, gate `0/5`,
release `NOT_ELIGIBLE`다. 현행 v2.4 continuation/Goal quick check도 source
content-set drift 때문에 계속 FAIL이 정상이다.

다음 사용자 결정은 R007 §4의 다음 둘 중 하나다.

```text
CHECKPOINT_PROJECTED_SOURCE_RESTORE
REVIEWED_ADD_ONLY_EXACT_SNAPSHOT_ACCEPTANCE
```

선택 전에는 source/checkpoint/checker를 변경하지 않는다. 선택 뒤의 검증과
P → M → FP-008 권한 분리는 R005 §2와 R004 §8을 따른다.

이 R009의 최종 SHA-256/bytes와 findings는 add-only
`CONTINUATION-EXECUTION-HANDOFF-20260730-R009-independent-review-r001.md`가
결속한다. receipt가 없거나 target pin이 다르거나 findings가 0이 아니면
R009를 실행 재개 근거로 사용하지 않는다.
