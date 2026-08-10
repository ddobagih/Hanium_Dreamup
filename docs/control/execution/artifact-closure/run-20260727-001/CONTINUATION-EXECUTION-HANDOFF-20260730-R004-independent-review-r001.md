# WalkSafe 실행 준비 인계 R004 독립검수 R001

- 검토일: `2026-07-30`
- 대상:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R004.md`
- target SHA-256:
  `ff2d91def55c4959d19b6a0e1e61694bbec3315e9b2ba6d06ca1a9728a669eb5`
- target bytes: `24,117`
- target lines: `415`
- 판정: `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R005`
- 적용 권한: 없음

두 독립 검토 축은 같은 target의 시작·종료 SHA-256/bytes를 읽기 전용으로
재확인했다.

| 검토 축 | BLOCKING | MAJOR | MINOR |
|---|---:|---:|---:|
| 물리 결속·재개 사용성 | 0 | 0 | 0 |
| source 전략·fail-closed 안전성 | 0 | 3 | 0 |
| 중복 제거 | 0 | 3 | 0 |

R004는 findings-zero가 아니므로 최종 재개 인계나 source 조정 근거로 사용할 수
없다.

## 1. 통합 MAJOR 판정

### 1.1 expected FAIL 검사가 추가 오류를 허용

R004 fail-fast는 현재 continuation의 필수 두 문구와 Goal의 필수 세 문구를
`grep -Fq`로 포함 확인한다. 현재 출력에는 정확하지만, 미래에 예상하지 않은
추가 오류가 생겨도 필수 문구가 남아 있으면 블록이 통과한다.

R005는 rc=1, header와 error line의 exact ordered set 전체를 비교해야 한다.
continuation은 정확히 두 오류, Goal은 정확히 세 오류 외의 누락·추가·순서 변경을
모두 실패시켜야 한다.

### 1.2 물리 부재와 source 파일 검사가 fail-closed가 아님

R004의 `test ! -e`는 dangling symlink를 부재로 오인한다. R007 P/M의 아직
없어야 하는 NOREPLACE final 9개도 absence 목록에서 빠졌다.

R005는 모든 부재 경로에 `! -e && ! -L` 또는 동등한 lstat 검사를 사용하고 다음
final path를 추가해야 한다.

- P: active-control discovery checker, launcher, discovery JSON,
  active-discovery test
- M: active v2.5 validation core, continuation wrapper, Goal wrapper,
  authorized writer, active-control test

runner와 두 evidence copy도 symlink를 따라가지 않고 regular file, nlink 1,
expected mode를 검증해야 한다.

### 1.3 두 source 전략의 validator convergence를 혼동

R004 §8은 두 전략 모두 source 조정 뒤 현행 v2.4 quick 두 개 PASS를 요구하는
것처럼 읽힌다. restore 전략에서는 맞지만 live `4280...`을 유지하는 add-only
acceptance 전략은 현행 checkpoint/checker를 유지하는 동안 두 quick이 exact
expected FAIL이어야 한다.

R005는 다음을 분리해야 한다.

- restore: intended owner/mode/nlink를 보존하고 새 inode/ctime을 허용하는 exact
  CAS 뒤 현행 v2.4 quick 두 개 PASS
- add-only acceptance: 현행 quick expected FAIL 유지, 새 reviewed
  source-acceptance validator PASS, 별도 승인된 successor checkpoint/checker
  transition 뒤에만 active quick 상태 변경

`metadata 보존`은 inode/ctime까지 보존한다는 뜻으로 쓰지 않고 승인·검증 대상
owner/mode/nlink와 새 inode 허용 경계를 명시해야 한다.

## 2. 확인된 정상 부분

- branch/HEAD, 활성 정본, exact68, FP-008, R002~R007 design/review pin과 수치가
  실제 파일과 일치한다.
- R006 `FAIL 0/2/0`, R007
  `PASS_FOR_DESIGN_ONLY_SOURCE_DRIFT_BLOCKED 0/0/0` 경계가 정확하다.
- 단일 runner drift의 before/after SHA·bytes, 5개 등록 줄, 두 evidence copy와
  aggregate 역대입 증명은 정확하다.
- 두 source 전략의 덮어쓰기·조기 overlay 정본화 위험과 새 사용자 승인 필요성은
  정확하다.
- P/M fresh 승인 분리, FP-008 deny-all, dirty tree와 synthetic authority 금지,
  현재 상태 delta 0은 정확하다.

## 3. R005 correction allowlist

다음 허용 행동은 add-only
`CONTINUATION-EXECUTION-HANDOFF-20260730-R005.md`에서 R004와 이 FAIL receipt를
결속하고 정확히 다음만 교정하는 것이다.

1. current expected FAIL의 exact ordered output set을 검증한다.
2. dangling symlink와 R007 P/M NOREPLACE final 9개를 포함해 physical absence와
   source regular/type/nlink/mode를 fail-closed 검증한다.
3. restore와 add-only acceptance의 validator convergence 및 metadata 경계를
   분리한다.

R004 수정, source 전략의 임의 선택, source/checkpoint 변경, candidate build,
authorization request, canonical/Goal/product write는 허용하지 않는다.
