# FP-008 다음 leaf 준비안 R001 독립검수 R001

- 검토일: `2026-07-30`
- 대상:
  `FP008-NEXT-LEAF-PREPARATION-R001.md`
- target SHA-256:
  `83f806e4596833811eaf8fc563df647eceed44e242f994a4d734b498a4b62ac0`
- target bytes: `81,321`
- target lines: `1,116`
- 판정: `PASS_FOR_PREPARATION_ONLY_NOT_MATERIALIZED_NOT_AUTHORIZED`
- findings: `BLOCKING=0 MAJOR=0 MINOR=0`
- 적용 권한: 없음

## 독립검수 축

| 검토 축 | BLOCKING | MAJOR | MINOR | 판정 |
|---|---:|---:|---:|---|
| 외부 증거·권한·receipt·release 보안 | 0 | 0 | 0 | PASS |
| lock·lease·삭제·stream 동시성 | 0 | 0 | 0 | PASS |

두 검토는 검토 전후에 대상 SHA-256, bytes와 lines를 독립적으로 재확인했다.

## 확인 결과

- caller 입력만으로 authoritative external receipt를 만들 수 없고,
  issuer signature 또는 독립 verifier로 진위를 확인할 수 없는 채널은
  history-only, release credit `0`이다.
- channel과 actor/role/report-set authorization policy의 revision/epoch를
  challenge, grant, admission, effect, read와 attestation에서 다시 확인한다.
- `RECEIVED | ACCEPTED` 뒤 adverse outcome incident는 append-only로 남고 즉시
  terminal, release credit `0`이 된다.
- attempt, raw external evidence, receipt, incident, current-stream attestation과
  artifact lifecycle의 1:1 lineage와 freshness가 끊기면 fail-closed한다.
- 사용자 앱 token과 user gateway audience는 모든 FP-008 admin route에서 업무
  변경·응답 byte 전에 거부되고 사용자 보행 runtime 상태는 바뀌지 않는다.
- 모든 coordination row는 하나의 13단계 전역 lock order를 따른다. 선조회는
  nonlocking이고 전체 ordered lock 뒤 key set, revision, epoch, expiry와 deletion
  state를 다시 검증한다.
- durable writer-preferred `DELETION_DRAINING`은 신규 quote/grant/read/replay/
  lease를 차단하고 기존 lease의 terminal 또는 process-death reconciliation만
  허용해 삭제 기아와 deadlock을 막는다.
- quote 최종 만료 경합은 artifact transaction을 rollback한 뒤 별도
  `ACTIVE → EXPIRED` append-only event로 종결하고 같은 key는 stable `410`이다.
- 최초 export POST, replay, GET과 manual stream은 저장된 grant/session/artifact
  absolute expiry와 server cap 중 가장 이른 deadline을 사용하고 첫 byte와 매
  chunk 전에 trusted time을 재검사한다.
- review decision/event, export audit, receipt/evidence/incident, stream
  attempt/event와 lifecycle event의 append-only/immutable 경계가 수용 기준과
  negative test에 결속됐다.
- 자연 만료와 삭제요청은 logical tombstone, no-follow unlink와 parent fsync,
  `DELETED` event/receipt와 crash reconciliation 순서를 따른다.

## 권한과 완료 상한

이 검수는 FP-008 제품 구현 leaf의 범위와 검증 경로를 후속 materialization
입력으로 사용할 수 있다는 판정만 제공한다. 다음 권한은 부여하지 않는다.

- r022 canonical 적용 또는 v2.5 activation
- FP-008 Goal/leaf materialization, READY/STARTED event와 제품 코드 변경
- actual device, actual agency, formal test 또는 release credit
- artifact 완료 증가, gate closure 또는 release eligibility

현재 active control은 v2.4 / sequence 39 / canonical r021이다. artifact
`126/257`, open `131`, formal `0/279`, actual-device `0`, release gate `0/5`,
release `NOT_ELIGIBLE`를 유지한다.

다음 행동은 findings-zero인 successor control candidate와 별도 activation
승인을 먼저 완성하는 것이다. 그 뒤에도 FP-008 materialization과 제품 시작에는
별도 승인 2가 필요하다.
