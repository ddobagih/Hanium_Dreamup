# WalkSafe 자율 실행 로드맵 20260802 R002 독립 skeptical review R001

review_id: WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R002-SKEPTICAL-R001
review_type: INTERNAL_SKEPTICAL_REVIEW
reviewer_agent: /root/r002_skeptical_review
reviewer_session: /root/r002_skeptical_review@20260802-r001
independence_attestation: TRUE
target_sha256: d2a7b2c54e5b7ce7f7c1baebd94800f3404fea7429631612eebb39901dd35576
target_bytes: 32534
target_lines: 584
verdict: REVISION_REQUIRED
blocking: 4
major: 5
minor: 0

## 독립성 및 범위

대상 R002의 작성, 수정, 후보 구현 또는 apply에 참여하지 않았다. 다른 reviewer의
판정이나 검수 파일을 보거나 기다리지 않고, 위 SHA의 32,534 bytes·584 lines를
직접 읽어 공격 관점으로 검수했다. 이번 검수는 내부 구조 검수일 뿐 formal test,
owner attestation 또는 canonical 승인 증거가 아니다.

## 판정 요약

R002는 live apply와 공식 진척을 명시적으로 금지하고 미래 canonical 단계를 별도
gate로 분리한 점은 타당하다. 그러나 현재 gate와 후보 봉인 계약에는 독립 검수
위조, 실행 입력의 사후 세탁, live write 누출, 자기참조 manifest라는 네 개의
차단 결함이 있다. 따라서 WP001 후보 write를 시작하면 안 되며, add-only R003에서
아래 항목을 닫고 새 SHA로 다시 검수해야 한다.

## Blocking findings

### B1. 독립 검수 gate가 identity를 검증하지 않아 구현자가 두 검수를 위조할 수 있다

R002 §2의 exact Python은 두 파일에 서로 다른 임의 문자열이 있는지만 확인한다.
`reviewer_agent`와 `reviewer_session`이 root가 아닌 실제 독립 실행 주체인지, 그
주체가 파일을 작성했는지, 서로의 판정을 보기 전에 작성했는지는 어떤 신뢰 근거와도
결속하지 않는다. root가 두 파일에 다른 문자열과 `PASS`, 영점 count를 쓰면 gate가
통과하므로, 독립 검수를 시작 권한처럼 사용하는 핵심 장벽이 self-authorization으로
축소된다.

최소 교정은 roadmap 작성자·후보 구현자의 identity를 명시적으로 금지하고, 두
review 생성 주체와 session을 root가 수정할 수 없는 orchestrator receipt 또는
동등한 신뢰 근거에 결속하며, review bytes의 생성·동결 순서도 검증하는 것이다.

### B2. start-state가 미래에 생기는 실행 입력까지 선결속하라는 시간적 모순이 있다

R002 §3은 실행 시작의 `start-state.json`에 “모든 실제 consumed input”을 넣고
각 lane 전에 같은 content-set을 재검증하라고 한다. 그러나 A/B/D의 후보 checker와
runner는 start-state 뒤에 생성되어 다시 실행 입력이 되고, pip wheel closure와
resolver 응답도 start 뒤에 내려받는다. 아직 존재하지 않는 bytes는 시작 CAS에
해시할 수 없다. 이를 consumed input에서 제외하면 “실제 접근한 파일 전체”라는
완전성 주장이 거짓이 되고, 포함하면 실행 순서가 성립하지 않는다. syscall 추적,
read-only input projection 또는 단계별 input seal도 없어 누락 입력을 검출할 수 없다.

최소 교정은 immutable bootstrap harness를 먼저 별도 SHA로 동결하고, 각 lane마다
입력 생성 단계와 소비 단계를 나눠 새 epoch CAS를 만든 뒤, 허용된 read set 밖 접근을
실제로 거부하는 격리 실행과 독립 verifier를 두는 것이다.

### B3. “live repository mutation 0”은 사후 porcelain 비교로 보장되지 않는다

R002 §3·§5C는 후보 작성자가 만든 Python/shell checker와 npm package script를
호스트 권한 그대로 실행한다. repository를 read-only mount로 두거나 쓰기 syscall을
막지 않는다. 종료 시 `git status --porcelain --untracked-files=all`을 비교해도 ignored
파일, 저장소 밖 경로, write 후 원상복구된 transient mutation은 보이지 않는다.
따라서 버그가 있거나 악의적인 후보 checker가 live source나 control을 건드려도
최종 상태 digest가 같을 수 있으며, 허용 write 경계와 안전 중단 조건을 우회한다.

최소 교정은 고정 입력을 새 inode의 read-only projection으로 복사하고 실행 프로세스가
live repository 및 backup에 쓰지 못하도록 filesystem sandbox/read-only bind를
강제해야 한다. 사후 CAS는 그 물리적 격리의 보조 검증으로만 사용해야 한다.

### B4. final manifest가 자기 자신의 hash를 포함해야 해 봉인 완료 상태가 불가능하다

R002 §3의 exact output allowlist에는 `candidate-manifest.json`이 포함되고, §5E는
“각 output”의 SHA-256·bytes·mode와 producer receipt가 final manifest에 존재해야
한다고 요구한다. 별도 예외나 detached seal이 없으므로 final manifest가 자기 자신의
최종 SHA와 bytes를 자기 내용 안에 포함해야 한다. 일반적인 SHA-256 파일로는 이
고정점을 구성할 수 없어 `INTERNAL_CANDIDATE_BUILT` 조건을 만족할 수 없다.

최소 교정은 manifest 자체를 hash 대상에서 명시적으로 제외하고 별도 immutable
`candidate-manifest.sha256`/detached seal을 허용하거나, content manifest와 외부 seal을
분리해 allowlist·producer 규칙을 다시 정의하는 것이다.

## Major findings

### M1. Python toolchain과 resolver 입력이 mutable network 및 ambient pip 설정에 노출된다

§5A의 `pip download`와 `piptools compile`은 `env -i`/isolated config, 고정 index
snapshot, 사전 승인된 distribution hash가 없다. 직접 pin하지 않은 tool dependency와
resolver metadata도 실행 시점에 달라질 수 있다. 다운로드 뒤 hash 기록과 연속 두 번의
동일 출력은 이미 받아 실행한 bytes의 출처를 보증하지 않고, 두 실행에 같은 오염 입력이
공급되는 경우도 잡지 못한다. clean acceptance 역시 project wheel closure를 봉인된
offline source에서 재생하지 않는다.

교정 시 tool 및 project closure를 사전 hash allowlist로 고정하고, 네트워크 없는
clean replay에서 compile·install·검사를 다시 수행하며 pip config/environment와 index
identity를 receipt에 결속해야 한다.

### M2. 후보 작성자가 checker·receipt·판정 manifest를 모두 작성해 증거를 스스로 인증한다

A의 candidate verifier, B의 runner와 checker, D의 dual checker, 모든 receipt와 final
manifest를 같은 root가 만든다. R002에는 receipt schema와 raw output을 독립적으로
재실행·대조하는 frozen verifier가 없으며, 후보 output 독립 검수는 §7에서 비실행 미래
단계다. 구현자가 expected JSON을 직접 출력하거나 receipt를 조작해도 현재 단계의
`INTERNAL_CANDIDATE_BUILT` 판정을 막는 독립 oracle이 없다.

교정 시 후보 생성기와 별도의 사전 동결 verifier를 두고, receipts의 exact schema,
argv/env/output byte 재계산, negative mutation corpus를 제3자 replay gate로 검증해야 한다.

### M3. fail-first receipt의 생성 시점과 재현 gate가 정의되지 않았다

allowlist에는 `fail-first-receipt.json`이 있지만 §4는 이전에 직접 확인했다는 서술과
명령만 제공한다. start CAS 뒤 실제로 재실행할지, `exit=1`과 정확한 여섯 node 및
`6 failed, 17 passed`를 어떤 parser가 검증할지, prior-session output을 금지할지가 없다.
따라서 오래된 출력이나 손으로 만든 receipt가 현재 입력의 fail-first 증거로 세탁될 수
있다.

교정 시 start CAS 뒤 exact 명령을 새로 실행하고 node-id 집합·exit·summary를 frozen
parser로 검증하며, receipt에 current start-state SHA와 command event를 결속해야 한다.

### M4. 최초 실행 규칙과 resume 규칙이 충돌하고 quarantine 상태를 기록할 경로가 없다

§3은 candidate/work root가 이미 있으면 덮어쓰지 말고 중단하라고 하지만, §6은 중단
후 같은 후보의 기존 output을 검증하고 이어 쓰는 절차를 요구한다. 최초 실행과 resume를
구분하는 immutable marker·state machine이 없으므로 재개 시 어느 규칙이 우선하는지
결정할 수 없다. 또한 `QUARANTINED_RESUME`를 기록할 별도 allowlisted marker와 atomic
전환 규칙이 없어 입력 drift를 발견한 뒤 기존 후보를 수정하지 않으면서 그 상태를
물리화할 수 없다.

교정 시 create-only genesis와 resume entry를 분리하고, immutable journal 또는 외부
quarantine receipt, 허용 전이, crash point별 old/new oracle를 정확히 정의해야 한다.

### M5. 후보 경로의 조상·생성 원자성과 copy set이 고정되지 않았다

§3은 두 leaf 경로 자체의 존재/symlink만 언급하고 조상 전체의 symlink·owner·mode,
동시 생성 경쟁, NOREPLACE 생성 성공을 검증하지 않는다. §5C의 “regular source만 복사”도
정확한 include/exclude manifest와 symlink 거부 copy 명령이 없다. 경로 교체 경쟁이나
조상 symlink가 있으면 허용된 문자열 경로로 쓰면서 실제로는 다른 위치를 오염시키고,
stale `dist`/`node_modules` 등 의도하지 않은 bytes를 후보 입력으로 섞을 수 있다.

교정 시 모든 조상을 `lstat`로 고정하고 안전한 create-only primitive와 owner/mode를
검증하며, copy 전에 exact relative-path allowlist를 봉인하고 symlink·hardlink·special
file을 거부해야 한다.

## 종료 조건

이 검수의 finding이 하나라도 존재하므로 R002 §2에 따라 candidate write는 0이어야
한다. R002와 이 검수는 immutable history로 남기고, R003에서 위 결함을 닫은 뒤 새
frozen SHA를 서로 독립인 두 reviewer에게 다시 제출해야 한다.
