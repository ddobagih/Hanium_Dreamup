# WalkSafe 요구사항 추적·기준선·변경·용어 Draft

> 이 문서는 승인된 기능정책을 요구사항으로 옮긴 **검토 전 Draft**입니다. 정책은 승인됐지만 이 요구 문서, 설계, 구현, 시험은 아직 승인되거나 완료된 것이 아닙니다.

| 항목 | 값 |
|---|---|
| 문서 묶음 | `BND-REQ-TRACE` |
| 문서 버전 | `0.2.0` |
| 기준일 | `2026-07-22` |
| 포함 산출물 유형 | `DLV-REQ-16`, `DLV-REQ-17`, `DLV-REQ-18`, `DLV-REQ-19` |
| 생명주기 | `DRAFT` |
| 승인 | `NOT_APPROVED` |
| 출시 판단 | `NOT_ELIGIBLE` |

상태를 읽는 법: `SOURCE_ONLY`는 자료가 있다는 뜻일 뿐 요구 충족 판정이 아닙니다. `REQUIREMENT_DRAFTED`는 정책을 요구 문장으로 옮겼다는 뜻입니다. `NOT_RUN`은 시험하지 않았다는 뜻입니다.

추적표의 다른 상태도 완료 판정이 아닙니다. `DRAFT_DESIGN_LINKS_DECLARED`는 설계 Draft로 가는 길을 연결했다는 뜻, `EXISTS_REVALIDATION_REQUIRED`는 기존 코드 후보를 다시 확인해야 한다는 뜻, `NOT_LINKED`는 증거 연결이 없다는 뜻, `NOT_APPLICABLE_AT_COMMON_POLICY_LEVEL`은 공통정책 행 자체에는 코드 하나를 직접 연결하지 않는다는 뜻입니다.

각 REQ 장의 첫 표에는 작성 목적, 필수 내용, 입력, 선후관계, 담당 역할, 완료 기준, 갱신·대체 방법을 함께 적었습니다. 정책 내용과 문서 관리 방법을 한 곳에서 확인하기 위한 것입니다.

<a id="req-16"></a>

## REQ-16 RTM(Requirements Traceability Matrix)

| 항목 | 현재 값 |
|---|---|
| 산출물 유형 | `REQ-16` (기계관리 ID: `DLV-REQ-16`) |
| 생명주기 | `DRAFT` |
| 승인 | `NOT_APPROVED` |
| 기준선 | `NOT_BASELINED` |
| 검증 | `NOT_RUN` |
| 작성 목적 | 각 요구가 설계·코드·시험·증거·결함·릴리스까지 빠짐없이 연결되게 한다. |
| 필수/조건 | `REQUIRED` · 제품 범위 승인 후 구현·변경·인수 전 과정에 활성 |
| 들어갈 내용 | - 요구 ID·버전·우선순위<br>- 상위 목표·출처<br>- 설계·API·데이터 연결<br>- 구현 모듈·설정<br>- 테스트·실행 증거<br>- 결함·waiver·릴리스 상태 |
| 작성 입력 | - 승인된 제품 비전·MVP·범위 기준선<br>- 사용자 시나리오·정책 결정·법적 제약<br>- 현행 코드·계약·시험에서 확인된 구현 사실 |
| 선행 → 후속 | `REQ-03`, `REQ-04`, `REQ-05`, `REQ-06`, `REQ-07`, `REQ-08`, `REQ-09`, `REQ-10`, `REQ-11`, `REQ-12`, `REQ-13`, `REQ-14`, `REQ-15` → `DES-01`, `REQ-17`, `TST-02`, `TST-05`, `TST-19` |
| 작성·검토·승인 | 요구사항책임자 · 제품책임자, 기술책임자, QA책임자 · 제품책임자 |
| 형식·정본 위치 | `REGISTER` · `docs/deliverables/03-requirements/requirements-traceability.md#req-16` |
| 보조 파일 | `docs/deliverables/03-requirements/rtm.json`, `docs/deliverables/03-requirements/rtm.html` |
| 완료·승인 기준 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 요구 ID·버전·우선순위, 상위 목표·출처.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |
| 갱신 조건 | - 승인된 사용자 결정·범위·정책·법규·인수조건 변경<br>- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경<br>- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료 |
| 검토 주기 | 초안 필수내용 작성 완료 시, 상위 기준선·정책 변경 시, 요구 기준선 승인 전마다 검토한다. |
| 변경·대체·폐기 | 승인본을 덮어쓰지 않는다. 변경요청과 영향분석을 기록해 새 버전을 만들고, 대체된 버전은 Superseded로 표시한 뒤 보존기간 종료 시 Archived로 옮긴다. |

쉽게 말하면, 이 표는 ‘왜 만드는지, 무엇을 채워야 하는지, 누가 확인하며 언제 다시 고치는지’를 이 산출물 하나에 대해 정한 관리 약속입니다.

RTM은 ‘왜 이 요구가 생겼는지’와 ‘앞으로 무엇으로 확인할지’를 연결합니다. 코드나 자료 링크가 있어도 현재 요구를 충족했다는 뜻은 아닙니다.

| 확인 항목 | 수 |
|---|---:|
| 요구 | 68 |
| 기능 / 공통정책 / 게이트 | 54 / 9 / 5 |
| 세부 clause | 1461 |
| 인수조건·예정 시험 | 279 / 279 |
| 정렬된 결정 / 결정-기능 연결 | 135 / 428 |
| 미결 게이트 | 5 |
| 열린 정책 정합성 이슈 | 0 |
| 새 제품질문 필요 | 0 |
| 연결된 미효력 정정 후보 | 1 |
| 시험 실행 완료 주장 | 0 |

기계가독 원본은 [`rtm.json`](rtm.json), 사람이 검색하기 쉬운 화면은 [`rtm.html`](rtm.html)입니다.

| 요구 ID | 출처 | 상태 | 결정 수 | 인수조건 | 설계 | 코드 | 자료 |
|---|---|---|---:|---:|---|---|---|
| `RQ-NPC-RAW-ORIGINAL-COLLECTION-001` | `NPC-RAW-ORIGINAL-COLLECTION` | `REQUIREMENT_DRAFTED` | 110 | 1 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_APPLICABLE_AT_COMMON_POLICY_LEVEL` | `NOT_LINKED` |
| `RQ-NPC-DATA-LIFECYCLE-001` | `NPC-DATA-LIFECYCLE` | `REQUIREMENT_DRAFTED` | 108 | 1 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_APPLICABLE_AT_COMMON_POLICY_LEVEL` | `NOT_LINKED` |
| `RQ-NPC-SERVER-STORAGE-CAPACITY-001` | `NPC-SERVER-STORAGE-CAPACITY` | `REQUIREMENT_DRAFTED` | 74 | 1 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_APPLICABLE_AT_COMMON_POLICY_LEVEL` | `NOT_LINKED` |
| `RQ-NPC-PHONE-QUEUE-CAPACITY-001` | `NPC-PHONE-QUEUE-CAPACITY` | `REQUIREMENT_DRAFTED` | 82 | 1 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_APPLICABLE_AT_COMMON_POLICY_LEVEL` | `NOT_LINKED` |
| `RQ-NPC-AUTO-REPORT-001` | `NPC-AUTO-REPORT` | `REQUIREMENT_DRAFTED` | 66 | 1 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_APPLICABLE_AT_COMMON_POLICY_LEVEL` | `NOT_LINKED` |
| `RQ-NPC-PERMISSION-SESSION-LIFECYCLE-001` | `NPC-PERMISSION-SESSION-LIFECYCLE` | `REQUIREMENT_DRAFTED` | 82 | 1 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_APPLICABLE_AT_COMMON_POLICY_LEVEL` | `NOT_LINKED` |
| `RQ-NPC-NAVIGATION-ROUTE-DIRECTION-001` | `NPC-NAVIGATION-ROUTE-DIRECTION` | `REQUIREMENT_DRAFTED` | 62 | 1 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_APPLICABLE_AT_COMMON_POLICY_LEVEL` | `NOT_LINKED` |
| `RQ-NPC-SINGLE-ADMIN-RECOVERY-001` | `NPC-SINGLE-ADMIN-RECOVERY` | `REQUIREMENT_DRAFTED` | 43 | 1 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_APPLICABLE_AT_COMMON_POLICY_LEVEL` | `NOT_LINKED` |
| `RQ-NPC-SERVER-CAPACITY-STATE-SYNC-001` | `NPC-SERVER-CAPACITY-STATE-SYNC` | `REQUIREMENT_DRAFTED` | 74 | 1 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_APPLICABLE_AT_COMMON_POLICY_LEVEL` | `NOT_LINKED` |
| `RQ-FP-001-001` | `FP-001` | `REQUIREMENT_DRAFTED` | 3 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `EXISTS_REVALIDATION_REQUIRED` | `NOT_LINKED` |
| `RQ-FP-002-001` | `FP-002` | `REQUIREMENT_DRAFTED` | 4 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `EXISTS_INSUFFICIENT` |
| `RQ-FP-003-001` | `FP-003` | `REQUIREMENT_DRAFTED` | 2 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-004-001` | `FP-004` | `REQUIREMENT_DRAFTED` | 3 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `EXISTS_REVALIDATION_REQUIRED` | `NOT_LINKED` |
| `RQ-FP-005-001` | `FP-005` | `REQUIREMENT_DRAFTED` | 2 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `EXISTS_REVALIDATION_REQUIRED` | `EXISTS_INSUFFICIENT` |
| `RQ-FP-006-001` | `FP-006` | `REQUIREMENT_DRAFTED` | 3 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `EXISTS_INSUFFICIENT` |
| `RQ-FP-007-001` | `FP-007` | `REQUIREMENT_DRAFTED` | 5 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `EXISTS_NOT_VALIDATED` | `NOT_LINKED` |
| `RQ-FP-008-001` | `FP-008` | `REQUIREMENT_DRAFTED` | 7 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-009-001` | `FP-009` | `REQUIREMENT_DRAFTED` | 7 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `EXISTS_INSUFFICIENT` |
| `RQ-FP-010-001` | `FP-010` | `REQUIREMENT_DRAFTED` | 7 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `EXISTS_REVALIDATION_REQUIRED` | `NOT_LINKED` |
| `RQ-FP-011-001` | `FP-011` | `REQUIREMENT_DRAFTED` | 6 | 5 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-012-001` | `FP-012` | `REQUIREMENT_DRAFTED` | 5 | 5 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-013-001` | `FP-013` | `REQUIREMENT_DRAFTED` | 8 | 5 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-014-001` | `FP-014` | `REQUIREMENT_DRAFTED` | 7 | 3 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-015-001` | `FP-015` | `REQUIREMENT_DRAFTED` | 5 | 5 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-016-001` | `FP-016` | `REQUIREMENT_DRAFTED` | 7 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `EXISTS_REVALIDATION_REQUIRED` | `NOT_LINKED` |
| `RQ-FP-017-001` | `FP-017` | `REQUIREMENT_DRAFTED` | 11 | 6 | `DRAFT_DESIGN_LINKS_DECLARED` | `EXISTS_REVALIDATION_REQUIRED` | `NOT_LINKED` |
| `RQ-FP-018-001` | `FP-018` | `REQUIREMENT_DRAFTED` | 19 | 6 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-019-001` | `FP-019` | `REQUIREMENT_DRAFTED` | 17 | 6 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `EXISTS_INSUFFICIENT` |
| `RQ-FP-020-001` | `FP-020` | `REQUIREMENT_DRAFTED` | 11 | 8 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `EXISTS_INSUFFICIENT` |
| `RQ-FP-021-001` | `FP-021` | `REQUIREMENT_DRAFTED` | 7 | 9 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `EXISTS_INSUFFICIENT` |
| `RQ-FP-022-001` | `FP-022` | `REQUIREMENT_DRAFTED` | 10 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `EXISTS_REVALIDATION_REQUIRED` | `NOT_LINKED` |
| `RQ-FP-023-001` | `FP-023` | `REQUIREMENT_DRAFTED` | 7 | 3 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-024-001` | `FP-024` | `REQUIREMENT_DRAFTED` | 2 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `EXISTS_REVALIDATION_REQUIRED` | `NOT_LINKED` |
| `RQ-FP-025-001` | `FP-025` | `REQUIREMENT_DRAFTED` | 9 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `EXISTS_REVALIDATION_REQUIRED` | `NOT_LINKED` |
| `RQ-FP-026-001` | `FP-026` | `REQUIREMENT_DRAFTED` | 9 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `EXISTS_REVALIDATION_REQUIRED` | `NOT_LINKED` |
| `RQ-FP-027-001` | `FP-027` | `REQUIREMENT_DRAFTED` | 10 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `EXISTS_NOT_VALIDATED` | `EXISTS_NOT_VALIDATED` |
| `RQ-FP-028-001` | `FP-028` | `REQUIREMENT_DRAFTED` | 6 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-029-001` | `FP-029` | `REQUIREMENT_DRAFTED` | 4 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-030-001` | `FP-030` | `REQUIREMENT_DRAFTED` | 3 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-031-001` | `FP-031` | `REQUIREMENT_DRAFTED` | 13 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-032-001` | `FP-032` | `REQUIREMENT_DRAFTED` | 9 | 3 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-033-001` | `FP-033` | `REQUIREMENT_DRAFTED` | 3 | 5 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-034-001` | `FP-034` | `REQUIREMENT_DRAFTED` | 9 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `EXISTS_REVALIDATION_REQUIRED` | `NOT_LINKED` |
| `RQ-FP-035-001` | `FP-035` | `REQUIREMENT_DRAFTED` | 15 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-036-001` | `FP-036` | `REQUIREMENT_DRAFTED` | 12 | 5 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-037-001` | `FP-037` | `REQUIREMENT_DRAFTED` | 3 | 6 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `EXISTS_INSUFFICIENT` |
| `RQ-FP-038-001` | `FP-038` | `REQUIREMENT_DRAFTED` | 7 | 6 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-039-001` | `FP-039` | `REQUIREMENT_DRAFTED` | 7 | 5 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-040-001` | `FP-040` | `REQUIREMENT_DRAFTED` | 7 | 6 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-041-001` | `FP-041` | `REQUIREMENT_DRAFTED` | 2 | 5 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-042-001` | `FP-042` | `REQUIREMENT_DRAFTED` | 8 | 6 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-043-001` | `FP-043` | `REQUIREMENT_DRAFTED` | 14 | 3 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-044-001` | `FP-044` | `REQUIREMENT_DRAFTED` | 9 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `EXISTS_REVALIDATION_REQUIRED` | `NOT_LINKED` |
| `RQ-FP-045-001` | `FP-045` | `REQUIREMENT_DRAFTED` | 11 | 4 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `EXISTS_INSUFFICIENT` |
| `RQ-FP-046-001` | `FP-046` | `REQUIREMENT_DRAFTED` | 19 | 5 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-047-001` | `FP-047` | `REQUIREMENT_DRAFTED` | 17 | 7 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-048-001` | `FP-048` | `REQUIREMENT_DRAFTED` | 10 | 7 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-049-001` | `FP-049` | `REQUIREMENT_DRAFTED` | 6 | 6 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `EXISTS_INSUFFICIENT` |
| `RQ-FP-050-001` | `FP-050` | `REQUIREMENT_DRAFTED` | 12 | 5 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `EXISTS_INSUFFICIENT` |
| `RQ-FP-051-001` | `FP-051` | `REQUIREMENT_DRAFTED` | 12 | 7 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-052-001` | `FP-052` | `REQUIREMENT_DRAFTED` | 7 | 8 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `EXISTS_INSUFFICIENT` |
| `RQ-FP-053-001` | `FP-053` | `REQUIREMENT_DRAFTED` | 6 | 6 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-FP-054-001` | `FP-054` | `REQUIREMENT_DRAFTED` | 4 | 7 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_LINKED` | `NOT_LINKED` |
| `RQ-GATE-PHONE-QUEUE-BYTE-LIMIT-001` | `GATE-PHONE-QUEUE-BYTE-LIMIT` | `REQUIREMENT_DRAFTED` | 39 | 1 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_APPLICABLE` | `NOT_CREATED` |
| `RQ-GATE-SERVER-CAPACITY-STATE-CONTRACT-001` | `GATE-SERVER-CAPACITY-STATE-CONTRACT` | `REQUIREMENT_DRAFTED` | 43 | 1 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_APPLICABLE` | `NOT_CREATED` |
| `RQ-GATE-RAW-COLLECTION-RELEASE-REVIEW-001` | `GATE-RAW-COLLECTION-RELEASE-REVIEW` | `REQUIREMENT_DRAFTED` | 99 | 1 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_APPLICABLE` | `NOT_CREATED` |
| `RQ-GATE-CLOUD-COST-MEASUREMENT-001` | `GATE-CLOUD-COST-MEASUREMENT` | `REQUIREMENT_DRAFTED` | 6 | 1 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_APPLICABLE` | `NOT_CREATED` |
| `RQ-GATE-SINGLE-ADMIN-RECOVERY-DRILL-001` | `GATE-SINGLE-ADMIN-RECOVERY-DRILL` | `REQUIREMENT_DRAFTED` | 50 | 1 | `DRAFT_DESIGN_LINKS_DECLARED` | `NOT_APPLICABLE` | `NOT_CREATED` |

<a id="req-17"></a>

## REQ-17 요구사항 기준선

| 항목 | 현재 값 |
|---|---|
| 산출물 유형 | `REQ-17` (기계관리 ID: `DLV-REQ-17`) |
| 생명주기 | `DRAFT` |
| 승인 | `NOT_APPROVED` |
| 기준선 | `NOT_BASELINED` |
| 검증 | `NOT_RUN` |
| 작성 목적 | 승인된 요구 집합과 해시·버전을 고정해 이후 변경의 출발점을 제공한다. |
| 필수/조건 | `REQUIRED` · 제품 범위 승인 후 구현·변경·인수 전 과정에 활성 |
| 들어갈 내용 | - 포함 요구 ID·버전<br>- 제외·유예·N/A 목록<br>- 승인자·승인일<br>- source commit·해시<br>- 열린 가정·waiver<br>- 기준선 manifest와 대체 관계 |
| 작성 입력 | - 승인된 제품 비전·MVP·범위 기준선<br>- 사용자 시나리오·정책 결정·법적 제약<br>- 현행 코드·계약·시험에서 확인된 구현 사실 |
| 선행 → 후속 | `DOC-04`, `REQ-16` → `DES-01`, `DES-06`, `DEV-01`, `REQ-18`, `TST-01`, `TST-22` |
| 작성·검토·승인 | 요구사항책임자 · 제품책임자, 기술책임자, QA책임자 · 제품책임자 |
| 형식·정본 위치 | `CANONICAL_DOCUMENT` · `docs/deliverables/03-requirements/requirements-traceability.md#req-17` |
| 보조 파일 | 없음 |
| 완료·승인 기준 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 포함 요구 ID·버전, 제외·유예·N/A 목록.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |
| 갱신 조건 | - 승인된 사용자 결정·범위·정책·법규·인수조건 변경<br>- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경<br>- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료 |
| 검토 주기 | 초안 필수내용 작성 완료 시, 상위 기준선·정책 변경 시, 요구 기준선 승인 전마다 검토한다. |
| 변경·대체·폐기 | 승인본을 덮어쓰지 않는다. 변경요청과 영향분석을 기록해 새 버전을 만들고, 대체된 버전은 Superseded로 표시한 뒤 보존기간 종료 시 Archived로 옮긴다. |

쉽게 말하면, 이 표는 ‘왜 만드는지, 무엇을 채워야 하는지, 누가 확인하며 언제 다시 고치는지’를 이 산출물 하나에 대해 정한 관리 약속입니다.

현재 파일은 기준선이 아니라 **기준선 후보 Draft**입니다. 승인된 것은 입력 정책 기준선이며, 이 요구 묶음의 승인·기준선 고정은 별도로 해야 합니다.

| 항목 | 값 |
|---|---|
| 제안 요구 기준선 ID | `RB-WALKSAFE-REQUIREMENTS-1.0.0` |
| 현재 문서 버전 | `0.2.0` |
| 현재 생명주기 | `DRAFT` |
| 승인 | `NOT_APPROVED` |
| 기준선 | `NOT_BASELINED` |
| 출시 | `NOT_ELIGIBLE` |
| Draft manifest | [`requirements-draft-20260721-r001.json`](../manifests/requirements-draft-20260721-r001.json) |

### 기준선 승인 전 체크

1. 68개 요구와 세부 clause의 뜻을 제품책임자·기술책임자·QA책임자가 검토합니다.
2. 279개 인수조건이 실제로 관찰 가능하고 서로 모순되지 않는지 검토합니다.
3. 5개 미결 게이트에 담당자·기한·측정 또는 검토 방법을 배정합니다.
4. `DEC-FP035-NETWORK-NORMALIZATION-20260722`의 네 전송망 분기가 REQ-03·REQ-06·DES-04·DES-13·DES-20에서 같은지 확인합니다.
5. 별도 교차추적 보고서에서 요구·설계·시험 ID와 파일 지문을 확인합니다.
6. 명시적 승인 기록을 만든 뒤에만 버전을 1.0.0으로 올리고 기준선을 고정합니다.

<a id="req-18"></a>

## REQ-18 요구사항 변경이력

| 항목 | 현재 값 |
|---|---|
| 산출물 유형 | `REQ-18` (기계관리 ID: `DLV-REQ-18`) |
| 생명주기 | `DRAFT` |
| 승인 | `NOT_APPROVED` |
| 기준선 | `NOT_BASELINED` |
| 검증 | `NOT_RUN` |
| FP-035 추적 역할 | `REQUIRED_RELATED_CHANGE_RECORD` · 직접 영향 아님 |
| 정정 후보·변경통제 | `WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001` · `CR-0002`, `ISS-POLICY-FP035-NETWORK-001`, `RAID-011` |
| 필수 기록 위치 | `docs/deliverables/03-requirements/requirement-change-log.json` |
| 효력 경계 | 이 변경기록만으로 정책을 승인하거나 효력화하지 않음 |
| 작성 목적 | 요구의 추가·수정·삭제·우선순위 변경 사유와 파급효과를 시간순으로 보존한다. |
| 필수/조건 | `REQUIRED` · 제품 범위 승인 후 구현·변경·인수 전 과정에 활성 |
| 들어갈 내용 | - 변경 요구 ID와 전후 내용<br>- CR·결정 근거<br>- 사용자·안전·일정 영향<br>- 설계·코드·시험 영향<br>- 적용 버전·기준선<br>- 검증·승인 결과 |
| 작성 입력 | - 승인된 제품 비전·MVP·범위 기준선<br>- 사용자 시나리오·정책 결정·법적 제약<br>- 현행 코드·계약·시험에서 확인된 구현 사실 |
| 선행 → 후속 | `MGT-16`, `REQ-17` → 없음 |
| 작성·검토·승인 | 요구사항책임자 · 제품책임자, 기술책임자, QA책임자 · 제품책임자 |
| 형식·정본 위치 | `REGISTER` · `docs/deliverables/03-requirements/requirements-traceability.md#req-18` |
| 보조 파일 | `docs/deliverables/03-requirements/requirement-change-log.json` |
| 완료·승인 기준 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 변경 요구 ID와 전후 내용, CR·결정 근거.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |
| 갱신 조건 | - 승인된 사용자 결정·범위·정책·법규·인수조건 변경<br>- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경<br>- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료 |
| 검토 주기 | 초안 필수내용 작성 완료 시, 상위 기준선·정책 변경 시, 요구 기준선 승인 전마다 검토한다. |
| 변경·대체·폐기 | 승인본을 덮어쓰지 않는다. 변경요청과 영향분석을 기록해 새 버전을 만들고, 대체된 버전은 Superseded로 표시한 뒤 보존기간 종료 시 Archived로 옮긴다. |

쉽게 말하면, 이 표는 ‘왜 만드는지, 무엇을 채워야 하는지, 누가 확인하며 언제 다시 고치는지’를 이 산출물 하나에 대해 정한 관리 약속입니다.

기계가독 변경대장은 [`requirement-change-log.json`](requirement-change-log.json)입니다.

| 변경 ID | 종류 | 설명 | 영향 요구 | 정책 변경 | 승인 |
|---|---|---|---:|---|---|
| `REQ-CHG-20260721-001` | `INITIAL_DRAFT_DERIVATION` | 승인된 기능정책 기준선과 정렬된 135개 결정으로 요구사항 Draft를 최초 생성했다. | 68 | `false` | `NOT_APPROVED` |
| `REQ-CHG-20260722-002` | `NOT_EFFECTIVE_FP035_CORRECTION_CANDIDATE_BINDING` | 기존 SP-13·FP-035 답변을 담은 정정 후보를 요구 Draft에 연결했다. 정정 후보는 아직 승인·효력화되지 않았다. | 1 | `false` | `NOT_APPROVED` |

앞으로 요구 문장을 고치면 새 change ID, 변경 전·후 버전, 변경 이유, 영향 요구·설계·시험, 검토자와 승인 결과를 새 항목으로 추가해야 합니다. 기존 이력은 덮어쓰지 않습니다.

<a id="req-19"></a>

## REQ-19 용어집

| 항목 | 현재 값 |
|---|---|
| 산출물 유형 | `REQ-19` (기계관리 ID: `DLV-REQ-19`) |
| 생명주기 | `DRAFT` |
| 승인 | `NOT_APPROVED` |
| 기준선 | `NOT_BASELINED` |
| 검증 | `NOT_RUN` |
| 작성 목적 | 제품·보행·위험·모델·지도·운영 용어를 하나의 의미와 단위로 통일한다. |
| 필수/조건 | `REQUIRED` · 제품 범위 승인 후 구현·변경·인수 전 과정에 활성 |
| 들어갈 내용 | - 용어·약어·영문명<br>- 정의와 사용 맥락<br>- 허용·금지 동의어<br>- 단위·값 범위<br>- 출처·책임자<br>- 관련 요구·schema |
| 작성 입력 | - 승인된 제품 비전·MVP·범위 기준선<br>- 사용자 시나리오·정책 결정·법적 제약<br>- 현행 코드·계약·시험에서 확인된 구현 사실 |
| 선행 → 후속 | `REQ-01`, `REQ-03`, `REQ-04` → 없음 |
| 작성·검토·승인 | 요구사항책임자 · 제품책임자, 기술책임자, QA책임자 · 제품책임자 |
| 형식·정본 위치 | `REGISTER` · `docs/deliverables/03-requirements/requirements-traceability.md#req-19` |
| 보조 파일 | `docs/deliverables/03-requirements/glossary.json` |
| 완료·승인 기준 | - 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 용어·약어·영문명, 정의와 사용 맥락.<br>- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.<br>- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.<br>- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다. |
| 갱신 조건 | - 승인된 사용자 결정·범위·정책·법규·인수조건 변경<br>- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경<br>- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료 |
| 검토 주기 | 초안 필수내용 작성 완료 시, 상위 기준선·정책 변경 시, 요구 기준선 승인 전마다 검토한다. |
| 변경·대체·폐기 | 승인본을 덮어쓰지 않는다. 변경요청과 영향분석을 기록해 새 버전을 만들고, 대체된 버전은 Superseded로 표시한 뒤 보존기간 종료 시 Archived로 옮긴다. |

쉽게 말하면, 이 표는 ‘왜 만드는지, 무엇을 채워야 하는지, 누가 확인하며 언제 다시 고치는지’를 이 산출물 하나에 대해 정한 관리 약속입니다.

기계가독 용어집은 [`glossary.json`](glossary.json)입니다. 아래 정의는 어려운 용어를 같은 뜻으로 읽기 위한 Draft이며, 정책 의미를 줄이거나 바꾸지 않습니다.

| 용어 | 영문·약어 | 쉬운 뜻 | 관련 요구 |
|---|---|---|---|
| WalkSafe | - | 전맹과 저시력 사용자를 같은 우선순위로 둔 시각장애인이 휴대전화 카메라·위치·음성 안내를 이용해 도심 보행 중 위험을 알아차리고 길을 따라가도록 돕는 프로젝트다. | `RQ-FP-001-001` |
| 정책 기준선 | Policy baseline | 사용자가 검토하고 승인하여 뒤 문서가 따라야 하는 정책 묶음이다. 정책 승인이 곧 요구·설계·시험의 완료를 뜻하지는 않는다. | `RQ-NPC-DATA-LIFECYCLE-001` |
| 요구사항 | Requirement | 시스템이 해야 하거나 지켜야 할 일을 구현·시험 가능한 문장으로 적은 것이다. | `RQ-FP-001-001` |
| 기능 정책 | FP | FP-001부터 FP-054까지 특정 기능 하나의 목적, 흐름, 실패 동작과 금지 동작을 정한 정책이다. | `RQ-FP-001-001` |
| 공통정책 | NPC | 여러 기능이 함께 지켜야 하는 데이터, 저장공간, 권한, 경로 안내 같은 규칙이다. | `RQ-NPC-DATA-LIFECYCLE-001` |
| 미결 게이트 | Gate | 정책 방향은 정했지만 실제 수치·전문가 검토·운영 증거가 없어 출시 전에 반드시 닫아야 하는 항목이다. | `RQ-GATE-PHONE-QUEUE-BYTE-LIMIT-001` |
| 활성 보행 | Active walk | 사용자가 보행 시작을 확인한 뒤 종료·일시중지하기 전까지의 한 번의 보행 세션이다. | `RQ-NPC-PERMISSION-SESSION-LIFECYCLE-001` |
| 원본 자료 | Raw original | 학습이나 신고 처리를 위해 가공하기 전의 영상·음성·위치 등 최초 자료다. | `RQ-NPC-RAW-ORIGINAL-COLLECTION-001` |
| 목적에 필요한 최소 수집 | Data minimization | 정한 기능 목적에 꼭 필요한 항목만 수집하고, 목적·접근자·보존기간·삭제조건을 미리 정하는 원칙이다. | `RQ-NPC-RAW-ORIGINAL-COLLECTION-001` |
| 자동신고 후보 | - | 위험 탐지 결과가 신고 정책 조건을 충족해 휴대전화의 전송 대기열에 들어간 자료 묶음이다. | `RQ-NPC-AUTO-REPORT-001` |
| 전송 대기열 | Queue | 통신이 가능할 때 서버로 보내기 위해 휴대전화에 암호화해 잠시 보관하는 신고 후보 목록이다. | `RQ-NPC-PHONE-QUEUE-CAPACITY-001` |
| 서버 용량 상태 | - | 서버 저장공간 사용률에 따라 경고·참여자 추가 중단·새 원본 세션 보류 같은 동작을 선택하는 상태다. | `RQ-NPC-SERVER-STORAGE-CAPACITY-001` |
| 동기화 | Sync | 서버의 현재 용량 상태를 휴대전화가 받아 기능 시작 가능 여부를 같은 기준으로 판단하도록 맞추는 일이다. | `RQ-NPC-SERVER-CAPACITY-STATE-SYNC-001` |
| 위치 좌표 | GPS location | 휴대전화 위치 센서가 추정한 현재 지점이다. 오차가 있으므로 단독으로 안전을 보장하는 값으로 쓰지 않는다. | `RQ-FP-022-001` |
| TMAP 경로 | - | TMAP 경로 서비스에서 받은 보행 경로 선과 경로 단계 정보다. | `RQ-FP-022-001` |
| 보폭 | Step length | 한 걸음에 이동한 것으로 추정하는 거리다. 남은 거리·도착·이탈 판단의 보조 입력이며 현재 좌표나 진행 방향을 대신하지 않는다. | `RQ-FP-022-001` |
| 경로 이탈 | Route deviation | 현재 위치와 저장된 경로의 거리가 정책 기준을 벗어난 상태다. 사용자에게 알리고 사용자가 다음 행동을 판단하게 한다. | `RQ-FP-023-001` |
| 객체 탐지 | Object detection | 카메라 영상에서 학습된 모델이 위험물의 종류와 위치 후보를 찾는 기능이다. 탐지 결과만으로 안전을 보장하지 않는다. | `RQ-FP-013-001` |
| 오탐 | False positive | 실제 위험물이 아닌데 위험물이라고 탐지한 경우다. | `RQ-FP-013-001` |
| 미탐 | False negative | 실제 위험물이 있는데 탐지하지 못한 경우다. | `RQ-FP-013-001` |
| 안전 정지 | Safe stop | 필수 입력·권한·외부 연결을 믿을 수 없을 때 추측 안내를 계속하지 않고 관련 처리를 멈추는 동작이다. | `RQ-NPC-NAVIGATION-ROUTE-DIRECTION-001` |
| STT | Speech-to-text | 사용자의 음성을 글자나 명령 의도로 바꾸는 기능이다. | `RQ-FP-027-001` |
| TTS | Text-to-speech | 안내 문장을 음성으로 읽어 주는 기능이다. | `RQ-FP-028-001` |
| TalkBack | - | Android 화면 내용을 음성으로 읽고 조작을 돕는 화면 읽기 기능이다. | `RQ-FP-009-001` |
| RTM | Requirements Traceability Matrix | 정책 결정부터 요구, 인수조건, 예정 시험, 존재하는 코드·증거까지 연결 상태를 보여 주는 표다. | `RQ-FP-001-001` |
| 인수조건 | Acceptance condition | 요구가 충족됐다고 판단하려면 무엇을 준비하고, 무엇을 실행하며, 무엇이 보여야 하는지 적은 조건이다. | `RQ-FP-001-001` |
| 기준선 | Baseline | 검토와 승인을 마쳐 이후 변경을 정식 변경절차로만 할 수 있게 고정한 버전이다. | `RQ-FP-002-001` |
| 출처만 연결 | SOURCE_ONLY | 파일이나 코드가 실제로 존재한다는 연결이다. 최신 요구를 충족했다거나 시험을 통과했다는 뜻은 아니다. | `RQ-FP-002-001` |
| MFA | Multi-factor authentication | 비밀번호 하나 외에 다른 인증수단을 함께 요구하는 관리자 로그인 방식이다. | `RQ-NPC-SINGLE-ADMIN-RECOVERY-001` |
| 복구코드 | Recovery code | 관리자 휴대전화를 잃었을 때 계정 접근을 복구하기 위해 별도로 안전하게 보관하는 일회성 코드다. | `RQ-NPC-SINGLE-ADMIN-RECOVERY-001` |

## 현재 문서 묶음의 승인 경계

- 정책 입력: 승인됨
- 요구사항 68개: Draft, 미승인
- 설계 연결: 별도 교차추적 보고서에서 존재·ID·파일 지문을 검증하고 이 RTM에는 승인되지 않은 Draft 링크로 표시
- 예정 시험 279개: 실행하지 않음
- FP-035 정정 후보: `WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001` / `DEC-FP035-NETWORK-NORMALIZATION-20260722` 1건, 기존 답변을 네 네트워크 분기로 Draft에 결속; `NOT_APPROVED / NOT_EFFECTIVE`, 시험 `NOT_RUN`
- 미결 게이트 5개: `NOT_RUN`, 면제 없음
- 출시: 불가
