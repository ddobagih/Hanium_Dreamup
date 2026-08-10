# WalkSafe R011 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R011-INDEPENDENT-SKEPTICAL-REVIEW-R001
review_type = INDEPENDENT_SKEPTICAL
reviewer_agent = /root/r009_sandbox_fix_design/r011_skeptical_review
reviewer_session = /root/r009_sandbox_fix_design/r011_skeptical_review@20260802-r001
independence_attestation = TRUE; 다른 R011 reviewer의 파일·결과를 읽거나 요청하거나 전달받지 않았고 그 reviewer 또는 다른 R011 reviewer와 통신하지 않았다.
target_sha256 = 5ad6a09870a30bc91c1c77ff29a731c526809ee86dd23f64d8b41b522b42d6f6
target_bytes = 17611
target_lines = 353
verdict = REVISION_REQUIRED
blocking = 3
major = 4
minor = 0
```

## 범위와 방법

동결 R011과, R011이 명시적으로 frozen input/normative base로 삼은 predecessor 중 판정에 필요한
절만 정적으로 대조했다. 대상 identity는 선언값과 byte-for-byte 일치했다. 다른 R011 review나
그 결과는 보거나 기다리거나 요청하지 않았다. candidate 실행, import, byte-compilation,
pycompile, network 사용은 모두 0이었다.

## Findings

### B-01 — case authority와 fixture oracle이 서로의 전체 SHA를 요구해 생성 순환이 생긴다

**정확한 근거:** R011:153-172의 `case-authority.json` exact key에는
`fixture_oracle_manifest_sha256`가 있다. R011:252-270의
`negative-fixture-oracle-manifest.json` exact key에는 반대로 `authority_sha256`가 있다.
R011:274-275는 authority가 fixture manifest SHA에 결속된다고 재확인하며, 어느 쪽에도 hash
제외 영역, placeholder 또는 단계별 preimage 규칙이 없다.

**영향:** authority bytes를 확정하려면 fixture manifest SHA가 먼저 필요하고, fixture manifest
bytes를 확정하려면 authority SHA가 먼저 필요하다. 두 add-only canonical file을 일반적인
SHA-256 생성 절차로 freeze할 수 없어 future dynamic plan과 C10 검증이 시작될 수 없다.

**적대적 재현:** 임의 authority 초안 A의 fixture hash 칸을 채우려 F를 직렬화하면 F의
authority hash 칸에 `SHA256(A)`가 필요하다. 그 값을 넣어 F를 다시 hash하면 A의 fixture hash가
바뀌고, 이를 A에 넣으면 다시 `SHA256(A)`가 바뀐다. 이 과정을 멈출 수 있는 명시 규칙은 없고
256-bit 상호 고정점 탐색만 남는다.

**최소 successor 교정:** fixture oracle에서 `authority_sha256`를 제거하고 run/case/source 및
fixture preimage를 독립적으로 먼저 freeze한다. 그 후 authority만 fixture manifest SHA를
단방향 결속한다. 역방향 추적이 필요하면 두 파일의 acceptance input이 아닌 사후 add-only
observation에 기록한다.

### B-02 — barrier가 identity/independence failure를 전제에서 배제해 rejection 전이가 도달 불가능하다

**정확한 근거:** R011:38-42의 B0는 두 파일이 같은 R011 identity에 결속되고 reviewer
agent/session이 서로 달라야 true다. 그런데 R011:44-46의 F0는 `B0 and ...
identity/independence failure`를 요구한다. 같은 모순이 R011:67-75의 B2/F2에도 있다. 또한
B0/B2는 exact 두 파일의 존재까지 요구하므로 reviewer task가 terminal이지만 Add File 전에
종료된 경우를 받는 실패 전이도 없다.

**영향:** wrong target identity, duplicate reviewer identity, malformed/missing review file은
실패로 닫혀야 하지만 barrier 자체를 false로 만들어 F0/F2도 false가 된다. S0 또는 S2에서
R012로 갈 권한이 영구히 생기지 않는 add-only dead end다.

**적대적 재현:** 두 plan-review task를 terminal로 만들되 한 파일의 target hash를 한 글자
다르게 쓰거나 두 metadata에 같은 reviewer session을 쓴다. B0는 false이고 F0도 B0를 필요로
하므로 어느 정의된 상태에도 진입하지 못한다. 한 task가 파일 작성 전에 terminal인 경우도
동일하다. source-review 단계에서도 같은 재현이 가능하다.

**최소 successor 교정:** 먼저 `tasks terminal + exact-name inventory captured`만 요구하는
availability barrier를 둔다. 그 뒤 두 파일 존재·parse·target binding·독립성이 모두 valid이면
PASS/nonzero를 분기하고, missing/malformed/wrong/duplicate이면 별도 rejection 상태로 즉시
R012 하나를 허용한다. source-review barrier도 같은 총함수 구조로 바꾼다.

### B-03 — alternate authority/root 충돌 판정에 필요한 전역 anchor가 입력에 없다

**정확한 근거:** R011:153-172의 authority exact keys에는 run-level authority registry,
승인 plan identity, canonical authority literal path/identity가 없다. R011:174-177은 같은
`run_id+global_attempt_id+case_id+source seal`에 다른 root/authority SHA면
`AUTHORITY_CONFLICT`라고 선언하지만 candidate가 받는 것은 선택된 authority와 fixture의 pinned
FD뿐이다. R011:185-202의 claim도 그 한 authority SHA와 그 authority가 고른 값만 담는다.

**영향:** 현재 FD가 같은 tuple에 대한 유일한 승인 authority인지 판정할 관찰 가능한 상태가
없다. 첫 root의 committed attempt를 피해 alternate root에서 새 attempt를 만들고 child를 다시
spawn할 수 있어 one-shot 경계가 우회된다.

**적대적 재현:** tuple T를 담은 authority A가 root A를 가리키게 하고 first run을 commit한다.
그 뒤 같은 T지만 distinct case/attempt/output inode를 담은 authority B를 다른 위치에 freeze해
B와 그 root만 pinned FD로 제공한다. B의 모든 내부 field와 nofollow identity는 일치하고 B 쪽
`attempt`는 absent다. 구현에는 A 또는 T→A의 외부 mapping을 읽을 입력이 없으므로 line 175의
rc86 대신 B에서 reservation을 만들 수 있다.

**최소 successor 교정:** source와 무관하게 먼저 frozen된 run-level selector를 literal path와
pinned FD로 제공하고, 각 tuple을 root identities 및 단 하나의 authority preimage에 결속한다.
authority는 그 selector SHA를 단방향 참조하고 controller가 selector와 authority를 함께 검증해야
한다. selector/authority 사이에 다시 상호 전체-hash 순환을 만들면 안 된다.

### M-01 — 외부 future manifest가 raw stream oracle을 source와 독립적으로 고정하지 않는다

**정확한 근거:** inherited R010:136-155는 base first child의 stdout empty, rc/class와 output
delta는 정하지만 raw child stderr exact bytes는 정하지 않는다. R011:223-225는 verifier의
성공 stdout과 mismatch용 schema를 정하지만 child/controller/verifier 각각의 모든 rc와 stream
bytes를 완전히 분리한 표가 아니다. R011:252-270은 그 빈 값을 future manifest의
`expected_child`에 넘기며, 그 manifest가 source implementation/bytes를 oracle derivation input으로
사용하지 못한다는 규칙이나 독립 생성 알고리즘은 없다.

**영향:** 외부 파일이고 실행 전 frozen이라는 사실만으로 source-independent oracle이 되지
않는다. candidate가 선택한 임의 stderr/result 형식을 future author가 그대로 expected 값으로
옮기면 verifier와 manifest가 일치해도 계획 자체의 expected behavior를 검증하지 못한다.

**적대적 재현:** `tar-dotdot` 구현이 지정 rc/class는 유지하되 stderr에 임의 bytes X를 쓴
source를 준비한다. future author가 source를 읽고 manifest의 child stderr bytes/hash를 X로
채운다. 실행 전에 manifest를 freeze하고 candidate에는 read-only로 주어도 모든 현재 조건을
만족하며 X의 정당성은 외부 규범과 대조되지 않는다.

**최소 successor 교정:** source authoring 전에 frozen된 규범 입력에서 각 case의 child,
controller, verifier rc/stdout/stderr canonical bytes 또는 digest를 결정하는 literal table/독립
generator를 둔다. future manifest는 그 결과만 materialize하고 candidate source/output을
derivation input으로 사용하지 못하게 하며 mismatch 종류별 failure class도 고정한다.

### M-02 — 두 byte mutation은 nonempty preimage를 요구하지 않고 failure receipt 변환도 대상을 고정하지 않는다

**정확한 근거:** R011:282-287은 pass-FD와 sentinel의 offset 0 byte를 XOR하면서 size 불변을
요구하지만 해당 regular file의 size가 최소 1이라는 predicate와 offset-0 preimage byte/SHA를
고정하지 않는다. R011:262-269의 generic preimage rows는 field 이름만 제시한다.
`oracle-malformed-failure-receipt`도 R011:287에서 “otherwise exact failure JSON”이라고만 하여
어느 case의 어느 file/stream bytes를 변환하는지 고정하지 않는다.

**영향:** schema-valid future fixture가 zero-length pass-FD/sentinel을 선택하면 요구된 변환은
size 불변으로 수행할 수 없다. receipt case는 서로 다른 failure JSON을 선택해도 같은 이름의
mutation을 주장할 수 있어 exact four-case registry가 재구성되지 않는다.

**적대적 재현:** preimage row의 pass-FD size를 0, SHA를 empty-file SHA로 freeze한다. offset 0의
`old`가 없어 XOR write는 실패하거나 size를 1로 바꿔 allowed delta를 위반한다. 별도로 rc84와
rc85 failure JSON 중 하나를 임의 선택해 extra key를 넣어도 현재 문구는 어느 preimage가
정답인지 판별하지 못한다.

**최소 successor 교정:** 두 byte targets를 regular/nlink-1/size≥1로 요구하고 exact path/FD role,
preimage bytes 또는 SHA와 offset-0 old byte를 manifest에 고정한다. malformed receipt에는 exact
source case, carrier(file 또는 stream), canonical preimage bytes/SHA와 유일한 transformed bytes를
직접 지정한다.

### M-03 — session-end daylog append가 exact write prohibition과 동일 문서 안에서 충돌한다

**정확한 근거:** R011:122-130은 허용 epoch를 전부 열거한 뒤 그 밖의 repository
Update/Delete/write가 0이라고 한다. R011:304-311은 R010 E3를 폐기하면서도 terminal 상태 뒤
repository global instruction에 따라 daylog block을 append하고 local-memory에 postimage를
기록한다고 한다. 이를 §2 prohibition 밖으로 빼는 명시적 시간 경계나 예외 epoch가 없다.

**영향:** terminal 뒤 daylog를 append하면 exact write set을 위반하고, 하지 않으면 §6의
session-end automation을 위반한다. acceptance에서 daylog를 제거한 방향은 맞지만 실제 종료
write authority가 결정적이지 않다.

**적대적 재현:** F0, F1, F2 또는 S3에 도달한 직후 daylog에 한 block을 append한다. repository
Update가 발생해 R011:130과 충돌한다. 반대로 append를 생략하면 R011:308-310의 지시가 충족되지
않는다.

**최소 successor 교정:** §2의 금지 범위가 bootstrap terminal 관찰까지임을 명시하고, 그 뒤의
global automation을 비-acceptance out-of-band 예외로 정확히 한 번 허용한다. exact daylog path,
append-only method, no-self-hash payload와 local-memory 역할을 별도 epoch에 두거나, 이 실행에서는
daylog write가 없다고 일관되게 선언한다.

### M-04 — `source ... deltas 0`가 E1의 draft source write와 충돌하거나 보호 대상을 특정하지 못한다

**정확한 근거:** R011:96-106과 122-127은 E1이 `publish-source.py` 등 draft payload를 쓰는 것을
필수로 한다. 그런데 R011:342-347의 E1_PHYSICAL_OK는 `predecessor/source/network/product/
canonical/official deltas 0`을 요구한다. `source`의 literal root나 “live source only” 같은 제외
정의가 없다. R011:130-133의 앞선 금지 문구는 source execution과 product/project/backup command를
구분하므로 이 acceptance 표현을 단일 의미로 복원해 주지 못한다.

**영향:** draft source를 source delta로 읽으면 exact9를 만든 모든 성공 E1이 acceptance에서
실패한다. live repository source만 뜻한다고 읽으면 path/범위가 없어 unrelated source mutation을
0으로 판정할 exact inventory가 없다.

**적대적 재현:** exact9/4를 올바르게 만든 뒤 draft root pre/post를 비교한다. `.py` source files의
delta는 nonzero이므로 문언상 E1_PHYSICAL_OK=false다. 반대로 draft를 제외해 판정하면 어떤 live
tree가 `source`인지 두 구현자가 서로 다른 root를 선택할 수 있다.

**최소 successor 교정:** 의도가 command count라면 inherited 표현처럼 `source/project/backup/
network command count = 0`으로 고친다. 의도가 live source-tree 불변이라면 보호할 literal root와
snapshot key를 열거하고 `authorized draft_root excluded`를 명시한다.

## 공격 후 확인된 방어

- R011:179-205의 pre-mkdir crash는 첫 durable effect와 child spawn을 분리한다. mkdir 전에는
  durable effect와 child가 모두 0이고, directory가 관찰된 뒤에는 empty라도 rc84로 닫으므로
  정의된 process-crash 모델에서는 별도 finding을 만들지 않았다.
- R011:96-127은 draft 9/evidence 4 basename과 생성 순서를 직접 열거한다. inherited rootfd
  `mkdirat(0700)` 및 umask-077 confinement과 함께 읽으면 E1 file-selection 간접성은 닫혔다.
- R011:77-84와 298-300, 347-350은 static source PASS를 dynamic PASS나 execution authority로
  승격하지 않고 `NOT_RUN`/unexecuted를 유지한다. 위 M-01은 이 경계와 별개인 oracle 내용의
  독립성 결함이다.
- R011:304-311은 daylog self-postimage hash를 bootstrap acceptance에서 제거해 이전의 자기해시
  순환 자체는 닫았다. 위 M-03은 새 out-of-band write 범위의 모순만 지적한다.

## 결론

R011은 literal E1 file set, reservation 뒤 replay 차단, E1 success/failure 분리와 static/dynamic
표시를 유의미하게 개선했다. 그러나 authority/oracle 상호 hash는 future contract를 생성 불가능하게
하고, barrier failure와 alternate authority는 각각 liveness와 one-shot 보장을 끊는다. stream,
mutation, daylog, source-delta 경계도 successor에서 결정적으로 고정해야 한다. 따라서 R011
candidate/evidence root는 만들지 말고 두 plan-review task가 terminal인 뒤 finding union을 반영한
R012 한 파일만 작성해야 한다.
