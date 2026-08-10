# W8 release-readiness 최종 독립 검토

> 검토일: `2026-07-27`  
> 판정: `GO_STATUS_DELTA_ALLOWED`  
> finding: `BLOCKING 0 / MAJOR 0 / MINOR 0`

## 결론

최신 W8 pair와 source의 stale binding count는 `0`이다. M01 attribution/successor lineage와 M02 semantic projection 계약은 닫혔고 exact-five 및 전역 보수 경계의 회귀도 없다.

기존 `independent-review.md`의 `NO_GO`와 `independent-rereview.md`의 GO는 당시 검토 이력으로 보존한다. 이전 rereview의 subject digest `216ede1c6c2c810608fa6292a8a372f249152299e913c114aa106aa7b52b91ae`는 다음과 같이 분류한다.

`HISTORICAL_GO_WITH_NON_REPRODUCIBLE_SUBJECT_PREIMAGE_NOT_TRANSITION_AUTHORITY`

이 문서의 완전한 physical-file preimage와 새 digest만 최종 W8 상태 delta 판단의 review subject이다.

## 최종 subject-set 규약

- 포함 범위:
  - 최신 W8 current-state JSON/Markdown pair 2개
  - W8 pair의 11개 logical source binding을 뒷받침하는 중복 제거 physical file 10개
  - 보존된 W8 `NO_GO` review와 기존 rereview 2개
- record count: `14`
- 경로: repository-relative file path만 허용
- 중복: 같은 physical path는 한 번만 기록
- 정렬: path의 UTF-8 bytes를 오름차순 bytewise sort
- record 직렬화: `<path><TAB><sha256><TAB><byte_length><LF>`
- SHA-256 표기: 실제 전체 파일 바이트의 lowercase 64자리 hex
- byte length: 실제 전체 파일 바이트 수의 base-10 ASCII
- 줄바꿈: 모든 record 뒤에 LF 1 byte, 마지막 record 뒤에도 LF 포함
- preimage encoding: UTF-8
- preimage byte length: `2004`
- subject digest algorithm: 위 preimage 전체에 대한 SHA-256
- subject digest: `ef8e490bf88dc157a3f9f8b159a7777ee74407c88cb87a69c9a5afbe9b14f31d`

REL17 section은 별도 repository file이 아니므로 subject table에 가상 path나 logical record로 추가하지 않는다. 대신 전체 container 파일을 table에 포함하고, pair에 기록된 byte offset·length·section hash를 별도로 검증한다.

## 완전한 subject preimage

다음 fenced block 내부의 각 두 구분자는 literal TAB 문자이다. 마지막 행 뒤에도 LF가 한 개 있다.

```text
apps/android/USER_GUIDE.md	746d5c13b99171e8da2d562efcdfb5ec0de47ba9fe5d167b11d9edfcb2f2a82b	13833
docs/control/execution/artifact-audits/20260726/release-ops-ws-closure-audit.json	d465905e6e33c720aa776ffb48145c29ef7b8036d7d1da416641fb5c9226af44	39673
docs/control/execution/artifact-remediation/20260726/w4/implementation-receipt.json	e924b5216da5ba174fb765c8c5d8d18e91a9b2a8aeec8dd8b6876b0a644210ba	19207
docs/control/execution/artifact-remediation/20260726/w5/implementation-receipt.json	c229b45989463a5f1eb4943289af42a4a9a8a720131de25421683ca59dc12dbe	47592
docs/control/execution/artifact-remediation/20260726/w7/aiml-model-governance-current-state.json	887fa726af570aa506e906dff5202818a14137b42009a1bee13b2cbf5cffa611	41414
docs/control/execution/artifact-remediation/20260726/w7/independent-rereview.md	4c1aa9d62ffd936672a697ffcc03e3d8d8a4b9fa49ee5c0e38d3cfc19f2da3c8	3444
docs/control/execution/artifact-remediation/20260726/w8/independent-rereview.md	a2acd1e0555cfce4b5fad6a53cb557cb919ee359b98e3da8f029d0caeebf71b3	6180
docs/control/execution/artifact-remediation/20260726/w8/independent-review.md	556ba08d0b88d96760309e5226a9f09bb5647705b2787761cc3054ca25937010	9302
docs/control/execution/artifact-remediation/20260726/w8/release-readiness-current-state.json	49a5885953c32031eb977181c3d76b359b88eeb2b1b13d66ab320b52974c2879	56684
docs/control/execution/artifact-remediation/20260726/w8/release-readiness-current-state.md	8a1575492fc58f0bb28e8d6d05cc1a0d0b5429354741e5c9404e4950637ff50e	31392
docs/deliverables/09-release/delivery-and-handover.md	34a263359cab57d9245520a50f2733f50ab4848322646d771410738b9b82c82c	31028
docs/deliverables/manifests/rel-ops-cls-draft-20260721-r001.json	c02986f83e36140903f5d001cfb32fe36902dad28753d37d6ffe7f98e70c4c1a	125261
scripts/build_walksafe_formal_rel_ops_cls_20260721.py	3bf8c8b918b8c23d7da9ff111bfd3318593c60798447a10adef248effba92cd6	132367
tests/test_walksafe_formal_rel_ops_cls.py	78d627a79202decdbadf81051b4700280b0c06056eebd8dacec359be64d75c12	28178
```

## 최신 source와 fingerprint 재확인

- logical source binding: `11/11 PASS`
- stale logical binding: `0`
- source fingerprint: `fd913a725400b57f274865da0f0c54e60a1025ea8a7634a03aa87764789e5897`
- content fingerprint: `c2b9f33958da5a8c2c83dafd7473c9ad0cb8982f243eee79f3ea247f3efacb45`
- input-set fingerprint: `d6f21a1cc13b62dfa79e8b650889759b5a3bbf5a7fc7699da79c3898be31615c`
- semantic projection fingerprint: `0d457c61834098d327220d63b34df5df13248f21ca63153ec3ff8a1db85f0991`
- 네 fingerprint 독립 재현: 모두 `PASS`
- field-selection 규칙으로 semantic payload 재구성: stored payload와 exact equality

REL17 current section:

- container: `docs/deliverables/09-release/delivery-and-handover.md`
- byte offset start: `6011`
- byte offset end-exclusive: `11332`
- byte length: `5321`
- SHA-256: `f6fe0a8e7cc6788cec9d2a0e56b7b3ccdc06c296b27ab68487a93807d7c2e61a`

## M01/M02 closure 재확인

### M01

- W8 direct current subject: USER_GUIDE 1개
- W9 shared successor: builder/test/manifest 3개
- current REL17 content evidence: 별도 section binding
- exclusive W8 attribution count: `0`
- unsupported preservation claim count: `0`
- historical pre-W9 preservation claimed: `false`
- pre-W9 builder/test/manifest/REL17 digest: `HISTORICAL_PRE_W9_DIGEST_UNKNOWN_NOT_RETAINED`

판정: `CLOSED`

### M02

- projection ID, exact field selection, array order, JSON native type contract와 UTF-8 canonicalization 규칙이 명시됨
- projection payload를 source object에서 독립 재구성 가능
- 재구성 payload digest가 `0d457c61834098d327220d63b34df5df13248f21ca63153ec3ff8a1db85f0991`과 일치

판정: `CLOSED`

## exact-five와 경계

| artifact | 판정 |
|---|---|
| `DLV-REL-15` | `INTERNAL_GAP_RETAINED` |
| `DLV-REL-16` | `INTERNAL_GAP_RETAINED` |
| `DLV-REL-17` | `READY_FOR_INDEPENDENT_REVIEW_AS_OK_CANDIDATE` |
| `DLV-REL-19` | `INTERNAL_GAP_RETAINED` |
| `DLV-REL-22` | `INTERNAL_GAP_RETAINED` |

`DLV-REL-17`만 `ok_candidate=true`이며 나머지 네 건은 gap을 유지한다.

전역 경계:

- formal 279, actual device, live TMAP, signing, deployment: `NOT_RUN`
- legal/model approval: `NOT_APPROVED`
- signing assessment: `NOT_ASSESSED`
- actual execution evidence와 external original: `0`
- release: `NOT_ELIGIBLE`
- central transition applied: `false`

회귀와 완료 과장은 `0`이다.

## 검증 범위

최신 pair/source byte binding, 네 fingerprint, M01/M02, exact-five와 전역 경계를 재확인했다. 지시대로 builder와 product test는 다시 실행하지 않았다.

## 승인 범위

- final independent review: `GO`
- finding: `0 BLOCKING / 0 MAJOR / 0 MINOR`
- status delta: `GO_STATUS_DELTA_ALLOWED`
- transition authority subject: 이 문서에 기록한 14-file preimage와 digest만 사용
- 허용하지 않는 것: release/deployment/signing/legal/model 승인, 실행 완료, W8-exclusive shared-source attribution 또는 historical byte-preservation 주장
