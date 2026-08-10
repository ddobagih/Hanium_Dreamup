# WalkSafe R003 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-R003-INDEPENDENT-SKEPTICAL-REVIEW-R001
review_type = INTERNAL_SKEPTICAL_REVIEW
reviewer_agent = /root/r003_skeptical
reviewer_session = /root/r003_skeptical@20260802-r001
independence_attestation = TRUE
target_sha256 = 2c15a76ad4c5e9fe2d9aa1c6558f92df7bba56827d9374b45dcbc92d75b84383
target_bytes = 13798
target_lines = 235
verdict = REVISION_REQUIRED
blocking = 3
major = 3
minor = 0
```

## 범위와 독립성

지정된 frozen R003만 검수했고 다른 R003 review의 내용은 읽거나 기다리지 않았다.
대상 hash/byte/line 수를 직접 재계산했다. 저장소의 직접 근거에 한해 backup 네
파일의 hash/type, bundle 유효성, tar member 수·type, host tool/bwrap 동작을
read-only로 확인했다.

## Findings

### B-01 — findings-zero가 다시 실행 권한으로 변환된다

R003 28~30행은 살아 있는 세션의 사용자 지시를 source build 근거라고 선언하지만,
고정 입력(53~74행)과 input manifest 계약(160~162행) 어디에도 그 지시의 정확한
범위나 build 시점의 유효성 확인 조건은 없다. 반면 93~95행과 231~235행은 두
review가 findings-zero이면 곧바로 §4를 수행하라고 명령한다. 89~91행의
orchestration event는 reviewer identity만 확인하며 사용자 권한을 확인하지 않는다.
따라서 문서상 실효 gate는 다시 review 결과이고, 28~30행의 비재생성 선언은 이를
강제하지 못한다. build 직전에 root가 현재 사용자 지시의 exact scope가 여전히
유효함을 별도로 확인하는 fail-closed gate를 두거나, findings-zero 뒤 사용자에게
명시적으로 재승인받기 전에는 §4로 전이하지 않도록 고쳐야 한다.

### B-02 — live repository write 0 판정은 문서 자신의 절차와 양립하지 않는다

12행과 186행은 live repository mutation/write를 0으로 요구한다. 그러나 82~85행은
review 두 파일을 현재 live repository 내부의 이 문서 옆에 만들도록 하고,
189~190행은 실패 판정을 repository의 기존 `./daylog`에 쓰도록 한다. 성공 경로도
review 파일 두 개가 이미 live tree에 생긴 뒤에야 시작한다. control artifact가
예외인지, review epoch가 source-build epoch 밖인지, write count의 시작 snapshot이
무엇인지 정의되어 있지 않으므로 현재 완료 predicate는 감사 가능하지 않다.
허용되는 control-only write를 명시하고 baseline/allowlist/hash로 계상하거나 모든
review 및 failure log를 repository 밖 add-only 경로로 옮겨야 한다.

### B-03 — create-only 계약을 실제 쓰기 primitive가 보장하지 않는다

105~110행의 선행 `lstat`과 leaf `mkdir`는 이후 pathname 기반 쓰기까지 원자적으로
보호하지 않는다. 112행은 여덟 파일을 `apply_patch`로 쓰게 하지만 이 도구에
`O_CREAT|O_EXCL`, `O_NOFOLLOW`, 고정 directory fd, no-replace semantics를 요구하거나
검증하지 않는다. 검사와 각 순차 write 사이에 leaf/ancestor가 바뀌거나 파일이 먼저
생기면 overwrite 금지와 nlink/type 보장이 깨질 수 있다. 사후 allowlist 검사는 이미
발생한 write를 취소하지 못한다. M4/M5 교정이 성립하려면 검수된 create-only writer가
ancestor fd를 고정하고 각 파일을 exclusive/no-follow로 생성해야 하며, 그 writer를
실행 전에 검수해야 한다. 현재의 ad-hoc `os.mkdir`/`apply_patch` 조합으로는 핵심
acceptance criterion인 one-shot genesis를 입증할 수 없다.

### M-01 — detached seal이 요구하는 전체 filesystem 상태를 봉인하지 않는다

164~174행의 manifest는 여섯 payload에 path/hash/bytes/mode만 담고 자신과 seal을
제외한다. detached seal은 manifest bytes만 봉인한다. 따라서 manifest·seal의
type/mode/nlink/owner, candidate directory metadata, 그리고 payload의 type/nlink는
동일한 seal 아래에서 바뀔 수 있다. 173~174행과 181~184행의 regular/non-symlink,
`nlink=1`, directory/file mode 조건은 검수 시점의 관찰일 뿐 R004 소비 시점까지의
CAS가 아니다. R004가 모든 path를 fd-relative로 다시 열어 metadata와 bytes를
재검증하도록 명시하고, 소비한 inode identity를 receipt에 남겨야 한다.

### M-02 — bwrap profile의 runtime closure가 정해지지 않아 현재 계약대로는 실행되지 않는다

143~151행은 `/usr`, `/bin`, "필요한 exact tool roots", 최소 `/etc`만 말할 뿐 ELF
loader/shared libraries, CPython stdlib, Git helper/template/config, locale 및 device/proc
mount를 열거하지 않는다. 실제 host에서 `/usr`와 `/bin`만 read-only bind한
`bwrap --unshare-all`은 `/bin/true`조차 loader 부재로 `ENOENT`가 났다. 또한
CPython 3.12.13은 PATH에 없고 home 아래 여러 사본이 있으며, 기본 `python3`은 다른
버전이다. 이 상태에서 "exact tool roots"는 구현자가 보안 경계와 실행 가능성을
source 작성 중 임의로 결정하게 한다. R003 단계에서 exact interpreter와 runtime
closure 및 mount argv를 고정하고 hash해야 static source review가 의미가 있다.

### M-03 — executable hash만으로 projection의 결정론적 복원을 결속하지 못한다

71~74행은 executable 자체만 기록한다. 그러나 129~139행의 clone/apply/status는
Git system/global config, template·helper 경로, locale, environment, shared libraries와
Python/tar 구현에 영향을 받는다. launcher의 추상적인 env allowlist 문구만으로는
어떤 config가 차단되고 어떤 dependency가 hash 입력인지 판정할 수 없다. 확인한
backup은 네 hash와 일치했고 bundle은 complete/valid였으며 archive는 regular member
정확히 2,550개였으므로 결함은 backup 자체가 아니라 소비 환경의 미결속이다.
R004 전에 `GIT_CONFIG_NOSYSTEM`, explicit empty global config, locale/timezone, exact
Git exec/template paths와 runtime closure를 source 계약과 manifest에 고정해야 한다.

## 결론

B1/B3/M4/M5 교정은 선언 수준에 머물고, B2/B4와 no-network sandbox의 방향은
개선됐지만 권한 전이, live-write accounting, create-only primitive, seal metadata
CAS, 실행 runtime closure가 닫히지 않았다. 따라서 이 frozen R003으로 source build를
시작하면 안 되며 add-only successor roadmap에서 위 항목을 먼저 수정해야 한다.
