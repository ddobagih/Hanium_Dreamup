# WalkSafe R010 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R010-INDEPENDENT-SKEPTICAL-REVIEW-R001
review_type = INDEPENDENT_SKEPTICAL
reviewer_agent = /root/r010_skeptical_review
reviewer_session = /root/r010_skeptical_review@20260802-r001
independence_attestation = TRUE; 다른 R010 reviewer의 파일·결과를 열거나 요청하거나 전달받지 않았고 그 reviewer와 통신하지 않았다.
target_sha256 = 731eed15ed4074134372666c0223f2aa4a1fdcdc0b3efc06e88b79c0fee541eb
target_bytes = 16233
target_lines = 291
verdict = REVISION_REQUIRED
blocking = 3
major = 2
minor = 0
```

## 범위와 관찰

동결 R010, 그 frozen base인 R009, 그리고 정확히 두 R009 plan review만 대조했다. 필요한
exact-file·marker 상속의 출처는 predecessor roadmap 문구에서만 좁게 역추적했다. 다른 R010
review 산출물은 읽지 않았다. 후보 source는 실행·import·byte-compile·pycompile하지 않았고
network도 사용하지 않았다.

대상과 세 frozen input의 SHA-256·bytes·lines는 선언값과 일치했다. 관찰 시점에 R009와 R010의
draft/final/evidence root 여섯 개는 모두 absent였다. daylog preimage도 regular `0664`,
uid/gid `1000/1000`, nlink 1, 2,987 bytes, 56 lines 및 선언 SHA와 일치했다. 이 관찰은 아래
계약 결함을 상쇄하지 않는다.

## Findings

### B-01 — E1 뒤 상태와 실패 전이가 없어 source-review barrier가 R011을 영구 차단한다

근거: R010:38-59의 상태기계는 plan-review barrier에서 `S1_E1_AUTHORIZED` 또는 plan finding용
`S_FAIL_R011_AUTHORIZED`로만 간다. R010:61-65는 S1에서 허용되는 것을 corrected
draft/evidence authoring으로 한정하지만, E1 성공 뒤 `E2_SOURCE_REVIEW`를 여는 상태도, E1
실패 뒤 실패 snapshot을 검수하는 상태도, source finding 뒤 R011을 여는 전이도 정의하지
않는다. 그럼에도 R010:259-261과 R010:286-288은 E1 실패까지 “두 source review가 모두
terminal”인 barrier 뒤에만 E3/R011을 허용한다. E1이 seal·post·observation·marker 중 하나를
만들기 전에 실패하면 R010:259의 “같은 seal/post/observation/marker”라는 source-review
대상 자체가 존재하지 않는다. 따라서 source review 없이 R011로 가는 길은 없고, source
review를 쓰는 권한과 불완전 대상을 판정할 계약도 없어 add-only dead end다. plan barrier를
source barrier에 “같게” 적용한다는 R010:64도 plan target SHA/bytes/lines와 source physical
identity가 다른데 parameterization을 주지 않는다.

최소 교정: successor에 E1 성공, E1 불완전 실패, source-review 진행, source barrier 성공/실패를
서로 다른 상태로 열거한다. 완전한 marker가 있는 성공만 두 source review로 보내고, E1
authoring 실패는 preserved partial-root inventory와 실패 지문을 동결한 뒤 source review 없이
R011을 허용하거나 별도로 정의한 두 failure review 뒤 R011을 허용한다. source barrier에는
두 review가 공유해야 할 seal/post/observation/marker의 exact identity와 불완전 상태용 대체
identity를 직접 정의한다.

### B-02 — daylog terminal block이 자기 postimage SHA를 포함해야 해 생성 불가능하다

근거: R010:239-243은 기존 daylog에 terminal block을 단 한 번 append하면서 그 block 안에
postimage SHA/bytes/lines를 기록하라고 한다. 그러나 block에 계산된 SHA를 삽입하면 postimage
bytes가 다시 바뀌므로 그 SHA는 더 이상 최종 파일의 SHA가 아니다. 한 번의 Update만 허용되어
사후 두 번째 기록도 금지된다. bytes/lines는 고정 폭을 정해 선계산할 수 있지만, 최종 파일
자신의 SHA-256 fixed point를 요구하는 계약은 실현 가능한 생성·검증 절차가 아니다. 따라서
E3가 성공할 수 없고 이후 기록·인계도 막힌다.

최소 교정: block에는 preimage identity와 canonical block payload hash 또는 명시적 placeholder
제외 규칙으로 계산한 hash만 넣고, 진짜 최종 daylog postimage SHA/bytes/lines는 E3 뒤
local-memory에만 기록한다. 저장소 안의 final hash receipt가 필요하면 별도 Add File receipt를
허용하고 그 receipt 자신은 daylog hash 대상에서 제외한다. path-based `apply_patch Update File`
로 same-file identity를 보장하려면 pre/post 확인만 선언하지 말고 pinned-FD/CAS 또는 명시적
비경쟁 전제도 함께 고정해야 한다.

### B-03 — attempt commit은 alternate scratch 재시행과 pre-commit crash를 봉쇄하지 못한다

근거: R010:112-130은 sandbox 내부 `/control/negative/<case-id>/attempt`만 고정하고 attempt
root가 처음에는 exact empty라고 하면서, pair absent를 `ATTEMPT_ABSENT`로 거부한다고도 한다.
fresh 최초 진입의 empty 상태와 claim 생성 전 crash 뒤 재진입의 empty 상태는 관찰상 동일해
어느 쪽에서 pair를 생성해야 하는지 결정할 수 없다. 더구나 claim은 output-root dev/ino만
담고 host scratch/case-root/attempt-root의 외부 고정 identity나 전역 attempt ID를 담지 않는다.
R010:130은 partial 상태에서 “새 scratch”를 요구하고, exact committed pair의 rc 81 거부는
그 동일 root에만 적용된다. caller argv로 claim root를 못 고르게 해도 controller를 새 sandbox
root에서 다시 시작하면 새 output dev/ino를 담은 새 claim을 만들 수 있어, 이미 child가
SIGKILL된 `publish-crash-before-first`도 alternate-root에서 다시 producer에 진입할 수 있다.
이는 R010:75-77의 no-rerun과 R010:175-176의 one-shot 주장보다 약하다. 또한 네 상태
absent/partial/wrong/conflicting을 세 class에 매핑하는 exact RC/JSON도 없다.

최소 교정: 각 case의 host scratch, case root, attempt root와 output root를 외부 frozen registry의
dev/ino/path-set 및 전역 attempt ID에 결속하고 다른 root를 같은 case의 재시도로 거부한다.
fresh reservation과 recovery inspection을 별도 상태로 나누고 reservation 전 crash의 의미를
명시한다. claim schema의 exact key/type/value, 각 commit syscall 경계, 네 불완전 상태 각각의
RC/stdout/stderr JSON과 successor scratch 권한을 직접 표로 고정한다.

### M-01 — exact 30 산술은 맞지만 per-case delta와 mutation oracle은 여전히 자기선택 가능하다

근거: R010:142-176의 12 base case와 10 crash case, R010:178-192의 4 recovery case,
R010:194-208의 4 mutation case는 22+4+4=30이고 ID도 고유하다. 그러나 `status-drift`의
“permitted projection prefix”, 두 preexisting fixture의 “exact tree”, crash case의 “first N”은
N과 literal basename 집합을 case별로 고정하지 않는다. outer controller RC와 observation의
stdout/stderr 위치도 child RC와 분리해 정하지 않는다. 특히 R010:196-204는 candidate의
registry-owned harness가 무엇을 어떻게 mutate할지 고정하지 않은 채 candidate verifier가
정해진 class를 반환하는지만 요구한다. producer·harness·verifier가 같은 무관한 변형과 기대값을
공유해도 표를 만족할 수 있어 R009 M-01의 tautology가 남는다.

최소 교정: 30개 각 행에 fixture preimage identity, exact mutation/fault point, child와 controller의
각 RC/stdout/stderr canonical bytes, first/second 전체 허용 delta를 직접 둔다. 10개 crash 행은
각각 허용되는 literal prefix basename 수와 목록을 열거한다. 네 mutation은 독립 anchor에서
재구성한 preimage에 대한 정확한 from→to 변환으로 고정하고, candidate harness가 expected나
mutation 대상을 선택하지 못하게 한다.

### M-02 — E1의 “exact 9/4” write set이 literal 목록이 아니라 다단계 predecessor selector다

근거: R010:79-86은 E1 write set을 `draft exact 9`와 `evidence exact 4`라고만 쓰고,
R010:253-254는 basename·순서를 R009 §2/§5에서 유지한다고 한다. R009 §2는 다시 draft 9를
“R008과 같은” 집합으로 보내고, 그 predecessor도 R006/R005 상속을 따라야 실제 literal 9개를
얻는다. evidence 4는 R009에 보이지만 R010 자체의 write-set 행에는 직접 열거되지 않는다.
과거 체인에는 `run-readonly-sandbox.sh`에서 `.py`로 바뀐 revision도 있어 올바른 replacement
chain을 모두 해석해야만 target basename을 고를 수 있다. 이는 보안 경계인 exact write set이
literal이어야 한다는 R010:88-90의 취지와, 앞선 source-review basename 간접 선택을 교정했던
predecessor 원칙을 퇴행시킨다.

최소 교정: successor의 E1 행에 draft 9개와 evidence 4개 basename을 모두 literal로 직접
열거하고, 7 payload → manifest/seal → 4 evidence의 exact 생성 순서를 같은 절에서 고정한다.
상속 문서는 의미 계약의 근거로만 쓰고 파일 선택자로 사용하지 않는다.

## 공격 후 확인된 방어

- R009의 3B/2M ledger는 R010 §3에 이름 기준으로 정확히 한 번씩 매핑됐고 plan-review late
  finding을 기다리는 barrier, designated root author, E1 직전 contemporaneous live authority는
  R009 B-01/B-03의 핵심 방향을 보존한다. 위 B-01은 그 다음 E1/source 상태가 빠진 별도 결함이다.
- `e1-marker-exact-after-write`는 writer와 attempt claim을 실행하지 않는 read-only recovery로
  두 번 rc 0을 요구한다. producer의 committed attempt 재호출 rc 81과 주체·효과가 분리돼 있어
  marker recovery 성공 자체는 one-shot 모순이 아니다.
- R010 §5는 공통 Git confinement 뒤 producer의 선언된 create-only projection delta와 verifier의
  read-only pre/post equality를 역할별로 분리한다. R009 M-02에서 지적한 직접적인 scope 충돌은
  재현되지 않았다.
- frozen source를 정적으로만 검토하면서 dynamic validation과 publication/projection 실행 권한을
  계속 0으로 둔 경계, final root absent 및 공식·제품·canonical·Gate·release delta 0 경계는
  유지된다.

## 결론

R010은 R009 review race와 Git 역할 혼동의 방향은 교정했지만, E1 실패 시 R011로 갈 수 없는
상태기계, 불가능한 daylog 자기해시, 우회 가능한 attempt-root 범위 때문에 실행 gate로 사용할
수 없다. exact negative oracle과 E1 literal write set도 successor에서 직접 동결해야 한다.
R010 roots는 absent로 유지하고 두 plan review가 모두 terminal인 뒤 전체 finding을 결속한 R011만
작성해야 한다.
