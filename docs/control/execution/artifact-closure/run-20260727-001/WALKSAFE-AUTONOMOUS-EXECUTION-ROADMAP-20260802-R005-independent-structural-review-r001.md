# WalkSafe 자율 실행 로드맵 R005 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R005-INDEPENDENT-STRUCTURAL-REVIEW-R001
review_type = INTERNAL_STRUCTURAL_REVIEW
reviewer_agent = /root/r005_structural
reviewer_session = /root/r005_structural@20260802-r001
independence_attestation = TRUE
target_sha256 = 75b7b2742a898ddae05f44ee2fb6378ce5c00f8982c7c4a9e74b0e7809d692c0
target_bytes = 21066
target_lines = 362
verdict = PASS
blocking = 0
major = 0
minor = 0
```

## 범위와 독립성

지정된 frozen R005를 독립 검수했으며 다른 R005 review를 읽거나 기다리지 않았다. R004의
structural/skeptical findings와 R005가 밝힌 별도 branch-status 결함의 종결만 선행 역사로
대조했다. 현재 허용 범위는 WP001 bootstrap draft source authoring뿐이며 source나 project
command의 실행, final publication, projection 복구는 이번 판정에 포함하지 않았다. 후속
실행은 source 정적 검수와 exact fd map·bwrap argv를 고정한 successor plan 및 그 독립
review가 있어야 한다는 R005 경계를 그대로 적용했다. 악의적인 same-UID/root process와
kernel·trusted-tool compromise도 선언된 위협 모델 밖으로 두었다.

## 구조 검수 결과

- 동결 대상의 SHA-256, byte 수, 행 수를 직접 재계산해 위 identity와 일치함을 확인했다.
- live 사용자 권한과 두 독립 품질 검수의 `0/0/0`을 논리곱으로 분리했고, review만으로
  권한을 재생하지 않는다. 어느 severity든 nonzero이면 E1/E2 source write를 금지하고
  add-only successor 하나만 허용한다.
- E0 author/review, E1 draft author, E2 source-review/log, failure-successor의 exact path가
  분리되어 있다. E1은 draft 9개와 evidence 3개를 이름·순서까지 열거하고 마지막
  `e1-result.json`의 terminal status 및 exact-set 검사를 요구하므로 R004의 write-accounting
  결함이 닫혔다.
- E1 writer는 검증한 apply-patch regular-file fd와 단 하나의 writable root directory fd만
  native `--ro-bind-fd`/`--bind-fd`로 bwrap에 넘긴다. host filesystem과 repository가
  보이지 않는 namespace, Add File only, invocation 전 ABSENT와 invocation 후 exact-set
  검사가 결합되어 잘못된 patch path의 허용 root 밖 write를 차단한다. 설치된 bwrap
  0.11.1 도움말에서도 두 fd bind primitive가 각각 open path/directory fd를 받음을
  확인했다.
- backup root의 실제 `0775`, 네 child의 type/mode/uid/gid/nlink/size, status 압축본과
  decode hash·bytes를 재확인했다. decoded status 첫 record들은 exact attached branch OID와
  head이고 upstream record가 없다. bundle의 keyed `HEAD`와 지정 branch도 같은 OID로
  각각 존재한다.
- runtime mount table은 loader/library의 absolute source/destination과 identity를 고정하고,
  backup regular files 및 draft/runtime 입력을 검증한 같은 fd로 mount·consume한 뒤 다시
  검사한다. stdlib는 pinned directory와 pre/post tree digest를 함께 요구하므로 R004의
  pathname 재조회 및 post-use provenance 결함이 닫혔다.
- restore는 지정 branch로 clone한 뒤 upstream만 제거하며 detached checkout을 금지한다.
  exact `status --porcelain=v2 --branch -z --untracked-files=normal` raw-byte 비교를 보존하고,
  tar parent를 component별 no-follow/same-device dirfd로 순회한 뒤 final leaf를 exclusive
  create한다. intermediate symlink·regular parent·mount crossing 및 실패 후 부작용에 대한
  negative oracle도 명시돼 있다.
- draft seal과 외부 evidence를 분리하고, source static review 뒤에도 실행 권한을 부여하지
  않은 채 publication/projection을 별도 successor review로 넘긴다. source → manifest →
  external review 전이에서 자기 참조나 review/authority 혼합은 발견되지 않았다.

## 판정

현재 draft-source authoring gate를 막는 재현 가능한 finding은 없다. 이 판정은 미래
publication/projection의 실행 승인이나 제품·canonical 진행 credit이 아니다.
