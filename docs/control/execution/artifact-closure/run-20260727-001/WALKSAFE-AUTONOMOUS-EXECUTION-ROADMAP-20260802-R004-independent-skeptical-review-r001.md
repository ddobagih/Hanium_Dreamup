# WalkSafe 자율 실행 로드맵 R004 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R004-INDEPENDENT-SKEPTICAL-REVIEW-R001
review_type = INDEPENDENT_SKEPTICAL
reviewer_agent = /root/r004_skeptical
reviewer_session = /root/r004_skeptical@20260802-r001
independence_attestation = TRUE
target_sha256 = e4c1542b4e5199df874fc6376b54731951ed673755853378c6a1cbadb4084d57
target_bytes = 21133
target_lines = 355
verdict = REVISION_REQUIRED
blocking = 2
major = 3
minor = 0
```

## 검수 범위와 판정 경계

지정된 frozen R004만을 대상으로 authority replay, write escape, TOCTOU,
self-seal/metadata, bwrap runtime closure, Git/tar restore, crash/no-resume,
acceptance oracle, 후속 roadmap을 공격적으로 검수했다. 같은 UID의 악의적 동시
프로세스, root·kernel·검수된 host tool compromise는 finding 근거로 사용하지 않았다.
현재 사용자 thread의 지속 구현 권한도 재심사하지 않았으며, 이 review는 품질 gate일
뿐 실행 권한이 아니다.

결론은 `REVISION_REQUIRED`다. 아래 두 BLOCKING은 R004가 in-scope로 선언한 선의의
입력 drift와 기존 symlink 조건에서도 재현 가능하므로 E1을 시작할 수 없다.

## Findings

### B-01 — fd 검증 결과가 bwrap mount의 source identity로 전달되지 않는다

- 근거: R004 157~172행, 245~251행, 277~288행
- 공격/실패 시나리오: consumer는 draft root와 9개 파일을 fd로 열어 검증한 뒤 그 fd를
  유지한다고 하지만, bwrap에는 어떤 inherited fd를 어떤 `/proc/self/fd/N` source로
  넘기는지, descriptor 상속과 번호를 어떻게 고정하는지, directory와 regular file을
  각각 어떤 argv로 bind하는지가 없다. 구현자가 검증 뒤 원래 pathname을
  `--ro-bind`하면 검증 대상과 mount 대상은 별도 lookup이다. 같은 UID의 악의를
  가정하지 않아도 backup 정리나 선의의 동시 교체가 두 lookup 사이에 일어나면, 검증한
  inode A 대신 inode B가 sandbox 입력이 된다. 실행 직전 pathname 재검사만으로는 이
  간극을 닫지 못한다.
- 영향: source review와 E1 receipt가 승인한 bytes가 실제 publisher/projection 입력이라는
  핵심 CAS가 성립하지 않는다. R004가 명시적으로 in-scope로 둔 input drift와 TOCTOU를
  fail-closed하지 못한다.
- 최소 교정: successor가 검증한 descriptor를 `pass_fds`로 상속하고
  `/proc/self/fd/<고정 fd>`를 source로 쓰는 exact bwrap argv를 파일/디렉터리별로
  고정하거나 동등한 fd-consuming primitive를 명시한다. child 안에서 mount된
  dev/ino를 receipt와 다시 대조하고, 실행 종료 후에도 모든 bind source를 재검증하는
  교체 fixture를 추가한다.

### B-02 — tar 복원의 `O_NOFOLLOW`는 중간 path component symlink를 막지 않는다

- 근거: R004 253~260행, 267~270행
- 공격/실패 시나리오: root dirfd에 대해 `openat(rootfd, "a/b", O_EXCL|O_NOFOLLOW)`를
  호출하면 `O_NOFOLLOW`는 마지막 `b`에만 적용되고 중간 `a`는 따라간다. 기존
  projection에 `a -> /work/output`이 있고 archive에 regular member `a/b`가 있는
  고정 fixture를 만들면 `/work/output/b`가 생성될 수 있다. 이는 악의적 동시 프로세스나
  kernel/tool compromise 없이 재현되는 기존 symlink write escape다. 문서의 일반적인
  “symlink negative fixture”는 중간 component를 지정하지 않아 이 결함을 반드시 잡는
  oracle도 아니다.
- 영향: archive member 자체가 regular이고 `..`가 없어도 declared projection root 밖의
  다른 writable leaf에 쓸 수 있다. write allowlist와 restore 격리가 깨진다.
- 최소 교정: 모든 parent component를 rootfd부터 한 단계씩
  `openat(O_DIRECTORY|O_NOFOLLOW)`하고 없는 directory만 같은 pinned parent fd에서
  단일 생성하거나, Linux `openat2`의 `RESOLVE_BENEATH|RESOLVE_NO_SYMLINKS|RESOLVE_NO_XDEV`
  등 동등한 beneath 보장을 사용한다. final leaf도 같은 directory fd에서 생성하고,
  중간 symlink·regular parent·mount crossing 각각의 no-outside-write fixture를 요구한다.

### M-01 — E1 evidence output의 “exact allowlist”가 물리적으로 열거되지 않았다

- 근거: R004 52~62행, 70~75행, 267~270행
- 실패 시나리오: E1 완료 조건은 draft/evidence exact allowlist라고 하지만 draft의 9개와
  달리 evidence root에는 `protected-pre.json`, `protected-post.json` 외에 post receipt의
  정확한 basename, schema, seal, 실행 로그 유무가 없다. 따라서 서로 다른 파일 집합을
  만든 두 구현이 모두 문서상 합격을 주장할 수 있고, “extra output” 검사도 비교할
  authoritative set이 없다.
- 영향: write 회계와 crash 시 보존해야 할 incomplete set을 결정적으로 판정할 수 없다.
- 최소 교정: evidence root의 exact relative file set, 각 생성 순서, mode와 content
  predicate, terminal marker를 열거하고 그 밖의 entry가 하나라도 있으면 실패시키는
  verifier를 고정한다.

### M-02 — negative fixture의 nonzero-only oracle이 write 부작용을 합격시킬 수 있다

- 근거: R004 267~270행
- 실패 시나리오: 잘못된 구현이 symlink를 따라 허용 밖 파일을 만든 뒤 exit 1을
  반환해도 현재 문장은 해당 fixture를 만족한다. preexisting final file을 일부 변경한
  뒤 실패하거나, partial output을 잘못 재사용 가능한 모양으로 남겨도 exit status만은
  동일하다.
- 영향: write escape, no-replace, crash/no-resume를 검증한다는 oracle이 실제 안전
  사후상태를 보장하지 않는다.
- 최소 교정: 각 fixture를 새 one-shot namespace에서 실행하고 expected failure class와
  함께 protected/outside sentinel의 byte·metadata 불변, 허용 output exact set,
  preexisting target 불변, terminal `INCOMPLETE_UNTRUSTED`, 재실행 거부를 모두
  postcondition으로 검사한다.

### M-03 — backup/runtime bind source는 preflight 뒤 사용 완료까지 봉인되지 않는다

- 근거: R004 119~132행, 157~172행, 253~260행
- 실패 시나리오: 네 backup 파일과 runtime stdlib/tool tree는 실행 직전에 pathname으로
  hash 검사하지만, 긴 bundle/tar 사용 뒤 동일 descriptor의 fstat-hash-fstat 또는
  post-use tree digest가 요구되지 않는다. 선의의 동시 backup 교체나 package update가
  preflight 뒤 일어나면 clone/extraction이 실패할 수도 있지만, 성공하는 경우 어떤
  version을 소비했는지 receipt가 확정하지 못한다. 특히 B-01의 pathname bind 구현과
  결합하면 preflight identity만 옛 값으로 남는다.
- 영향: 성공 output이 고정 input으로부터 유도됐다는 복구 provenance가 불완전하다.
- 최소 교정: 모든 regular input은 검증한 fd 자체를 bind·소비하고 read 전후
  fstat/hash를 비교한다. stdlib directory는 fd-pinned mount와 실행 후 동일 tree digest를
  요구한다. preflight 후 교체 fixture는 실행 0 또는 terminal failure만 허용하며 성공
  receipt 생성을 금지한다.

## 양호한 경계

- live 사용자 권한과 review 품질 gate를 논리곱으로 분리해 review 파일만으로 권한을
  재생하지 않는 점은 타당하다.
- E0/E1/E2 분리, draft-only authoring, create-only final publication, 실패 path의
  add-only 보존은 이전 권한·self-seal·resume 문제를 실질적으로 줄였다.
- 고정 bundle selector, patch/tar/status hash, network·ambient Git/Python 차단과
  formal/device/Gate 상태 비승격은 과장 없이 기술되어 있다.

위 finding을 닫은 add-only successor를 다시 freeze하고 독립 2인 검수에서 모든
severity가 0일 때만 E1을 허용해야 한다.
