# WalkSafe 프로젝트 종합 재개 인계서 R001 독립검수 R001

- 검토일: `2026-07-30`
- 대상:
  `WALKSAFE-PROJECT-MASTER-HANDOFF-20260730-R001.md`
- target SHA-256:
  `5b60ca48becd3634410c68ea6de5863f45881972eb597f0625c8556a4f03a48b`
- target bytes: `29,672`
- target lines: `725`
- 판정: `PASS_FOR_NON_EFFECTIVE_PROJECT_RESUMPTION_HANDOFF`
- findings: `BLOCKING=0 MAJOR=0 MINOR=0`
- 적용 권한: 없음

두 읽기 전용 검토 축은 마지막 수정 뒤 같은 target의 시작·종료
SHA-256/bytes/lines가 일치함을 확인했다.

| 검토 축 | BLOCKING | MAJOR | MINOR |
|---|---:|---:|---:|
| live checkpoint·정본·source drift exactness | 0 | 0 | 0 |
| 전체 계획 계층·next-action·재개 실용성 | 0 | 0 | 0 |

## 1. 현행 상태와 과장 방지

- v2.4 `ACTIVE`, sequence 39, canonical r021, focus EPIC-03 `READY`,
  materialized leaf 없음, frontier EPIC-03/12가 live checkpoint와 일치한다.
- r021 Gap B5/C16/E4/M11/P32/I0, artifact closed-equivalent 126/257와
  open 131, formal 0/279, actual device/event 0, gate 0/5,
  release `NOT_ELIGIBLE`가 정확하다.
- canonical r022, active v2.5, P/M physical candidate가 없다는 경계가
  정확하다.
- exact68 R002, FP-008 preparation, R007 control design은 reviewed staged
  입력이고 적용·materialization·제품 credit가 아니라는 구분이 정확하다.
- R009 review `0/1/0`과 history-only/no-authority 경계를 숨기지 않았고,
  R004~R009 execution block을 재개 근거로 사용하지 않는다.

## 2. source blocker와 검증

- managed snapshot 603개와 path-set은 일치하고 content-set만 다르다.
- 단일 drift는
  `scripts/run_walksafe_test_layers_20260711.sh`이며 sealed copy와의 diff는
  이 세션이 등록한 테스트 5줄과 정확히 일치한다.
- 현행 continuation rc=1의 2개 이유와 Goal rc=1의 3개 이유가 실제 출력과
  일치한다.
- 권고 전략 A는 다섯 등록만 최소 역적용하고 테스트 파일과 다른 dirty-tree
  변경을 보존한다. 전략 B는 새 successor/review/승인을 요구한다.
- destructive command나 source/checkpoint hash만 바꾸는 우회가 없다.

candidate-independent subset을 target의 명령 그대로 실행한 결과:

```text
42 passed, 3 deselected
```

제외한 physical candidate 의존 테스트를 PASS로 표현하지 않았다.

## 3. 전체·단계·세부 계획

- S0~S9는 재기준선 E1~E6를 실행 가능한 단위로 세분화한다.
- EPIC hard dependency는 v2.4 static manifest와 일치한다.
- P selector-preflight와 M v2.5+r022 transition은 서로 다른
  build/review/fresh approval로 분리된다.
- M 뒤 frontier를 재계산하고 FP-008이 여전히 다음일 때만 별도
  materialization/start 절차로 들어간다.
- r021의 FP-048/GAP-057 pointer는 stale defect, R002의
  FP-008/GAP-017은 비정본 candidate로 정확히 제한됐다.
- open 131 lane은 62 + 24 + 24 + 21로 누락·중복 없이 분할된다.
- 구현 loop, 승인 matrix, 외부 stop condition, 예상 시간과 2시간 보고 규칙이
  제품·artifact 결과 중심으로 정리됐다.

## 4. stale instruction 경계

repository `AGENTS.md`의 v2.3 start/quick pointer가 현행 v2.4 checkpoint보다
오래됐다는 사실과 v2.3 FAIL을 source regression으로 보지 않는 경계가
명시됐다. v2.4 checker를 runtime 기준으로 쓰면서도 `AGENTS.md`의 안전,
정책, dirty-tree, 단일 leaf, daylog·local-memory 원칙은 유지한다.

## 5. 효력

이 PASS는 다른 터미널의 Codex가 target을 현 상황·전체/단계/세부 계획과
다음 결정의 orientation 자료로 사용할 수 있다는 뜻만 가진다.

다음 권한은 부여하지 않는다.

- source 전략 선택 또는 runner 변경
- P/M candidate build·apply·승인 요청
- r022/v2.5 activation
- FP-008 Goal/제품 시작
- artifact/formal/device/gate/release 상태 변경
- production·외부 사건·인수·이관·종료

새 터미널은 target §11의 첫 지시문으로 read-only 확인을 시작하고, source
write 전 사용자에게 exact 전략 A/B를 보고한다.
