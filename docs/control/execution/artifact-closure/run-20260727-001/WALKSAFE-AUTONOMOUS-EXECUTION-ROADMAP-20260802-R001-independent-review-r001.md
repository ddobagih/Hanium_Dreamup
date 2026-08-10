# WalkSafe 자율 실행 로드맵 20260802 R001 독립 formal 검수

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R001-INDEPENDENT-REVIEW-R001
verdict = REVISION_REQUIRED
target_sha256 = 73d3bcc1e47a896fbc7e5156639a85a7f44ba29e3ec90d24d25d0a80f333d94b
target_bytes = 16268
target_lines = 334
blocking = 3
major = 4
minor = 3
official_progress_delta = 0
release_claim = NOT_ELIGIBLE
```

## 판정

시작 수치·hash·claim 경계는 live 근거와 일치하지만 아래 BLOCKING이 남아
PASS가 아니다. R001은 고치지 말고 add-only R002 교정 뒤 다시 검수한다.

## BLOCKING

### B-01 — canonical leaf와 실행 권한을 우회하는 WP-001

- 대상: 14~17, 24~34, 81~84, 104~107, 127~142, 184~226, 328~332행.
- live 정본은 focus `WS-GOAL-EPIC-03`, ready `EPIC-03/EPIC-12`, active leaf 없음,
  r021 next pointer는 알려진 stale `FP-048/GAP-057`이다.
- 대상은 그 pointer를 시작하지 않으면서 `WP-001` source/test 후보를 canonical
  Goal·event 없이 실행한다. 2026-07-31 기술 인계의 실행 권한은
  `ABSENT_DENY_ALL`이고, 새 지시는 본문에 재서술됐을 뿐 exact WP-001 권한과
  canonical apply 범위로 결속되지 않았다. S3의 “허용된 경우”도 미정의다.
- 최소 교정: R002에서 새 지시의 exact add-only 범위·금지 write를 결속하고,
  live 변경 전 control-repair leaf의 유효 canonical transition을 선행시킨다.

### B-02 — 중단 후 제품 write 재개 gate가 빠짐

- 대상: 203~204, 311~326행.
- 부분/crash fixture와 bytes 대조만 있고, 진행 중 leaf 재개 시 필요한 snapshot
  reconcile → fresh full gate와 새 원출력/receipt → `WORK_SESSION_RESUMED` →
  current-session Goal graph PASS 전 write 금지가 없다.
- 최소 교정: 위 재개 순서를 §5의 필수 barrier 한 항목으로 추가한다.

### B-03 — MINOR-only review 결과에서 교착

- 대상: 17, 131~136행.
- blocking/major가 있을 때만 R002를 만들지만 WP-001은 각 review `0/0/0`일 때만
  시작한다. 따라서 `0/0/1`이면 수정도 실행도 할 수 없다.
- 최소 교정: finding 하나라도 nonzero이면 R002를 만들도록 조건을 통일한다.

## MAJOR

### M-01 — WP-001의 물리적 재현 단위가 부족함

- 대상: 140~193행.
- 7개 실패의 exact selector·재현 명령·expected failure가 없고 immutable raw
  receipt도 존재하지 않는다. A~D의 canonical input, add-only output 경로와
  실행 명령이 없어 서로 다른 후보가 같은 prose 수용조건을 만족할 수 있다.
- 최소 교정: lane마다 기존 input, 새 output/test 경로, 한 개의 재현/검증 명령,
  기대 exit를 고정한다. 새 FSM·authority protocol은 추가하지 않는다.

### M-02 — 독립검수 역할 분리가 불충분함

- 대상: 19~22, 95, 129~136, 197~210행.
- 두 reviewer가 서로 다르다는 조건만으로는 target 작성자·구현자·apply/canonical
  writer의 자기검수를 배제하지 못한다.
- 최소 교정: 두 reviewer 모두 대상 작성·수정·구현·apply·canonical write에
  참여하지 않았고 서로의 verdict를 보지 않은 동일 frozen SHA 검수자임을 명시한다.

### M-03 — S3 crash/CAS 수용의 후보별 판정 기준이 없음

- 대상: 197~216행.
- write set, injection 지점, old/new complete-state oracle와 checker가 후보 계약의
  필수 산출물로 지정되지 않아 `write 0`과 원자 상태를 판정할 수 없다.
- 최소 교정: 미래 SHA를 미리 쓰지 말고, candidate freeze 전에 위 네 항목을 담은
  작은 versioned apply contract와 실행 checker를 필수로 만들도록 한다.

### M-04 — S7 진입에 필요한 artifact subset이 불명확함

- 대상: 263~294행.
- S6의 owner/attestation/real-event 항목은 S8 실제 행위 전 닫을 수 없는데 S7은
  “모든 ... artifact 준비가 닫힌” 상태를 요구한다.
- 최소 교정: S7 전에 닫을 내부 lane/ID와 S8까지 OPEN을 허용할 외부 lane을
  명시해 candidate freeze와 외부 증거가 순환하지 않게 한다.

## MINOR

1. 248~261행의 화살표는 r021 DAG보다 과도하게 직렬화한다. exact edge를 쓰거나
   `dependency가 아닌 보수적 priority illustration`이라고 명명한다.
2. 54행은 porcelain 축약과 구분해 `archived untracked files 2,550개`로 쓴다.
3. 48행의 `global completion credit 0`은 단일 정본 필드가 아니다.
   `artifact completion credit delta 0`과 다른 공식 delta 0을 분리한다.

## 확인된 정합성과 claim ceiling

- v2.4/seq39, checkpoint/r021 hash, Gap `5/16/4/11/32/0`, focus/ready/no leaf,
  artifact `126/131`, scheduler `104/1/87/65`, formal `0/279`, device/event `0/0`,
  Gate `0/5`, `NOT_ELIGIBLE/NOT_COMPLETE`가 일치했다.
- 네 epoch와 lane 합계도 일치하며 외부행위·실삭제·배포·공식 claim 경계는 적절하다.
- 이 검수의 공식 delta와 실행 권한은 0이다. 종료 시 대상은 같은 SHA,
  16,268 bytes, 334 lines였다.
