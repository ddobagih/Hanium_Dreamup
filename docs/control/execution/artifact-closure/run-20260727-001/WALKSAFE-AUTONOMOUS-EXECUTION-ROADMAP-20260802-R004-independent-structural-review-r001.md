# WalkSafe R004 독립 structural review r001

```text
review_id = WS-WALKSAFE-R004-INDEPENDENT-STRUCTURAL-REVIEW-R001
review_type = INTERNAL_STRUCTURAL_REVIEW
reviewer_agent = /root/r004_structural
reviewer_session = /root/r004_structural@20260802-r001
independence_attestation = TRUE
target_sha256 = e4c1542b4e5199df874fc6376b54731951ed673755853378c6a1cbadb4084d57
target_bytes = 21133
target_lines = 355
verdict = REVISION_REQUIRED
blocking = 3
major = 1
minor = 0
```

## 범위와 판정

frozen target만 기준으로 구조를 검수했고 다른 R004 review는 읽거나 기다리지 않았다.
target identity를 직접 재계산했고 요청값과 일치했다. R004는 live 사용자 권한과 review
quality gate를 논리곱으로 분리했고, final publication을 static source review 이후의
dirfd-relative exclusive create로 미뤘으며, consume-time metadata CAS도 명시했다.
그러나 아래 결함 때문에 현재 R004에서 E1으로 전이할 수 없다.

## 차단 결함

### B1. 고정 backup root의 현재 mode가 계약값과 다르다

§3은 backup root를 mode `0755`로 고정하고 §4·§5의 consumer가 입력 metadata를 다시
검사하도록 한다. 현재 frozen root를 `stat -c '%a %u %g %F'`로 확인하면
`775 1000 1000 directory`다. 네 파일의 size/hash/mode와 두 keyed Git selector는
기재값과 일치하지만 root precondition은 이미 false다. backup mutation은 허용되지
않으므로 현재 계약을 그대로 둔 source와 successor는 fail-closed할 수밖에 없다.
실제 mode를 successor input으로 고정하거나 별도의 승인된 backup을 선택해야 한다.

### B2. E1 writer가 명시한 in-scope 위협에 대해 exact write boundary를 강제하지 않는다

§1.1은 잘못된 path, 기존 symlink/hardlink, 선의의 동시 변경을 in-scope로 둔다.
그런데 §2는 target ABSENT 검사 뒤 pathname 기반 `apply_patch Add File`을 사용하면서
그 primitive에 exclusive/no-follow/held-dirfd 성질이 없음을 명시적으로 인정한다.
single-writer 가정은 악의적인 동시 writer 제외에는 맞지만, 문서가 별도로 in-scope로
둔 실수 경로와 선의의 collision을 제거하지 않는다. protected digest도 live tree,
backup, frozen control files만 포함하므로 잘못된 patch path가 그 집합 밖에 있으면
허용 root 밖 write를 계상하지 못한 채 completion 실패 후 남을 수 있다. 이는 R003의
writer-boundary finding을 final publication에 대해서만 닫고 E1 authoring에는 남긴다.
E1 writer 자체를 draft/evidence leaf만 RW인 namespace 또는 pinned dirfd create-only
primitive로 제한해야 한다.

### B3. 세 epoch의 claimed exact allowlist가 실제로 열거되지 않았다

§1.2는 E1 완료 조건을 exact draft/evidence allowlist라고 하지만 §5는 draft의 9개
file만 열거한다. evidence 쪽은 `protected-pre.json`, `protected-post.json`만 이름이
나오고 E1 post receipt의 exact basename과 전체 file set이 없다. E2도 source review
두 파일은 exact하지만 daylog는 “한 파일”일 뿐 absolute/relative path가 없으며,
§8의 nonzero 경로가 만들라고 하는 successor plan은 E2 허용 write에 포함되지 않는다.
E0 역시 frozen R004 자체를 허용 write로 적어 §7의 immutable review target과 문언상
충돌한다. 따라서 허용 delta 밖 extra file, daylog 위치, successor-plan write를 같은
predicate로 판정할 수 없고 R003의 control/write-accounting finding은 완전히 닫히지
않았다. 각 epoch의 exact path set과 successor plan이 시작되는 별도 epoch를 고정해야
한다.

## 주요 결함

### M1. runtime mount closure가 basename 수준이라 expected identity를 재검사할 수 없다

§4는 executable과 Python stdlib는 absolute path와 digest로 고정하지만 loader와 13개
library는 basename만 열거한다. 각 library의 absolute source/destination, bytes, mode,
SHA가 없는데 §4 마지막은 successor가 모든 bind source의 SHA/type/mode를 재검사한다고
요구한다. `runtime-closure.json`도 seven handcrafted payload 중 하나이므로 source
author가 roadmap review 이후에 이 expected 값과 mount path를 선택할 수 있다. 이는
실행 전 source review라는 추가 gate 덕분에 즉시 unsafe execution을 만들지는 않지만,
R003 runtime-closure finding을 roadmap 수준에서 완전히 닫지는 못한다. loader와 각
library의 exact absolute mount pair 및 identity를 successor roadmap에서 선고정해야
한다.

## 결론

authority/review 분리, draft→review→exclusive publication 순서, final consumer의
metadata CAS 방향은 유지할 수 있다. 다만 current backup fact, E1 writer confinement,
epoch별 write set을 고친 add-only successor roadmap이 필요하며 이 R004로 draft root나
evidence root를 만들면 안 된다.
