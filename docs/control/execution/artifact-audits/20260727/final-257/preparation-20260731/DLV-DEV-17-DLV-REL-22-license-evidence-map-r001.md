# DLV-DEV-17 · DLV-REL-22 라이선스 증거 맵 R001

```text
STATUS=NONCANONICAL_PREPARATION_ONLY
ARTIFACT_CREDIT_DELTA=0
OWNER_CREDIT=0
ACCEPTANCE_CREDIT=0
EXECUTION_CREDIT=0
OWNER/ACCEPTANCE/EXECUTION=0
RELEASE_CREDIT=0
```

- 작성일: `2026-07-31`
- exact 대상: `DLV-DEV-17`, `DLV-REL-22`
- 목적: R004 Lane A의 read-only 조사 결과를 비정규 준비자료로 고정한다.
- 경계: 이 파일은 controlled artifact, R011 ledger, checkpoint, runner 또는 승인
  기록이 아니다. content authored, owner approval, acceptance, execution,
  artifact closure, release eligibility를 주장하거나 변경하지 않는다.
- 법적 경계: 아래 판정은 파일 존재성과 trace 완전성 조사다. 라이선스 의무나
  호환성을 추정하지 않으며 법무·라이선스검토자의 승인 또는 법률 자문을
  대체하지 않는다.

## 1. R011 source binding

| 항목 | 값 |
|---|---|
| ledger ID | `WS-PHASE1-EXACT257-SUCCESSOR-LEDGER-20260729-R011` |
| locator | `docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-exact257-successor-r011/phase1-exact257-successor-ledger-r011.json` |
| SHA-256 | `7fc4bd6f2242b17de75faa040e42b4744c972a492f87ff4365caaeae53f93368` |
| bytes | `2637012` |
| exact record indices | `records[85]` = `DLV-DEV-17`; `records[173]` = `DLV-REL-22` |
| exact ID count | `2` |
| projection serialization | 두 record를 ledger 순서로 고르고 아래 6개 필드를 recursive-key-sorted compact JSON 한 행 + LF로 직렬화 |
| projection SHA-256 | `ab1303bc8b57ac44273068f986d0468d942d586a6f64b9f598c313f9e73cd0fe` |

Projection 필드는
`artifact_type_code`, `predecessor_phase1_action_queue_record.dependencies`,
`predecessor_phase1_action_queue_record.evidence_predicate`,
`predecessor_phase1_action_queue_record.owner_role`,
`predecessor_phase1_action_queue_record.resource_class`, `claim_boundary`다.

## 2. Exact R011 contract extraction

### 2.1 DLV-DEV-17

```json
{
  "artifact_type_code": "DLV-DEV-17",
  "dependencies": [
    "SOURCE_SNAPSHOT:WS-FINAL-257-SUCCESSOR-20260727-001",
    "PHASE0_ROUTE:SNAPSHOT_INTERNAL_GAP_PRESERVED"
  ],
  "evidence_predicate": {
    "authority_level": "DESIGNATED_PROJECT_APPROVER",
    "execution_predicate": {
      "all_of": [
        "No production event is required for submission content acceptance.",
        "Any later execution claim must create a separately bound receipt."
      ],
      "mode": "NOT_REQUIRED_FOR_SUBMISSION"
    },
    "minimum_evidence": [
      {
        "evidence_type": "CONTROLLED_SUBJECT_LOCATOR",
        "value": "docs/deliverables/05-implementation/implementation-quality-record.md#dev-17"
      },
      {
        "evidence_type": "CURRENT_REQUIRED_ACTION",
        "value": "source·binary·model·dataset·SDK 의무를 release 형상에 맞춰 검토한다."
      },
      {
        "evidence_type": "REQUIRED_CONTENT_BOUNDARY",
        "value": [
          "프로젝트 라이선스",
          "제3자 명칭·버전",
          "저작권·license text"
        ]
      },
      {
        "evidence_type": "REQUIRED_INPUT",
        "value": [
          "승인된 설계·인터페이스·인수조건",
          "현재 source tree·lock·설정·toolchain"
        ]
      },
      {
        "evidence_type": "CONTENT_TRACE_REVIEW",
        "value": "required content, named sources, explicit assumptions/open items, trace and approval record"
      }
    ],
    "review_method": "CONTENT_TRACE_AND_AUTHORITY_REVIEW",
    "state_exit": "Submission content and current evidence satisfy the bound predicate and receive the designated review disposition.",
    "submission_predicate": {
      "all_of": [
        "Controlled content locator resolves.",
        "Required contents and inputs are addressed or explicitly blocked with owner and due condition.",
        "Trace, review and approval are recorded without claiming an execution event."
      ],
      "mode": "SUBMISSION_CONTENT_ONLY"
    }
  },
  "owner_role": "개발책임자",
  "resource_class": "LIGHT",
  "claim_boundary": {
    "artifact_completion_claimed": false,
    "current_scope_n_a_closure_claimed": false,
    "execution_completion_claimed": false,
    "external_fact_verified_claimed": false,
    "formal_pass_claimed": false,
    "global_artifact_completion_claimed": false,
    "owner_approval_claimed": false,
    "real_event_claimed": false,
    "release_eligible_claimed": false,
    "scope_decision_claimed": false
  }
}
```

### 2.2 DLV-REL-22

```json
{
  "artifact_type_code": "DLV-REL-22",
  "dependencies": [
    "SOURCE_SNAPSHOT:WS-FINAL-257-SUCCESSOR-20260727-001",
    "PHASE0_ROUTE:SNAPSHOT_INTERNAL_GAP_PRESERVED"
  ],
  "evidence_predicate": {
    "authority_level": "DESIGNATED_PROJECT_APPROVER",
    "execution_predicate": {
      "all_of": [
        "No production event is required for submission content acceptance.",
        "Any later execution claim must create a separately bound receipt."
      ],
      "mode": "NOT_REQUIRED_FOR_SUBMISSION"
    },
    "minimum_evidence": [
      {
        "evidence_type": "CONTROLLED_SUBJECT_LOCATOR",
        "value": "docs/deliverables/09-release/delivery-and-handover.md#rel-22"
      },
      {
        "evidence_type": "CURRENT_REQUIRED_ACTION",
        "value": "직접 및 전이 dependency, 라이선스 본문과 고지 의무를 결속한다."
      },
      {
        "evidence_type": "REQUIRED_CONTENT_BOUNDARY",
        "value": [
          "component·version·저작권",
          "license 명칭·text",
          "attribution·notice"
        ]
      },
      {
        "evidence_type": "REQUIRED_INPUT",
        "value": [
          "승인된 정책 기준선·릴리스 범위·출시 gate와 판정 기준",
          "source·build·model·config·migration artifact"
        ]
      },
      {
        "evidence_type": "CONTENT_TRACE_REVIEW",
        "value": "required content, named sources, explicit assumptions/open items, trace and approval record"
      }
    ],
    "review_method": "CONTENT_TRACE_AND_AUTHORITY_REVIEW",
    "state_exit": "Submission content and current evidence satisfy the bound predicate and receive the designated review disposition.",
    "submission_predicate": {
      "all_of": [
        "Controlled content locator resolves.",
        "Required contents and inputs are addressed or explicitly blocked with owner and due condition.",
        "Trace, review and approval are recorded without claiming an execution event."
      ],
      "mode": "SUBMISSION_CONTENT_ONLY"
    }
  },
  "owner_role": "릴리스책임자",
  "resource_class": "LIGHT",
  "claim_boundary": {
    "artifact_completion_claimed": false,
    "current_scope_n_a_closure_claimed": false,
    "execution_completion_claimed": false,
    "external_fact_verified_claimed": false,
    "formal_pass_claimed": false,
    "global_artifact_completion_claimed": false,
    "owner_approval_claimed": false,
    "real_event_claimed": false,
    "release_eligible_claimed": false,
    "scope_decision_claimed": false
  }
}
```

두 record 모두 R011에서 `queue_route.current=INTERNAL_READY`,
`artifact_closure.status=OPEN`, `release_eligibility.status=NOT_ELIGIBLE`이다.

## 3. 판정 규칙

단일 `FOUND/MISSING/STALE` 표시는 파일 존재와 출시본 결속을 혼동하게 하므로
아래 세 축을 분리한다.

| 축 | 값 | 이 맵에서의 의미 |
|---|---|---|
| locator | `FOUND` / `MISSING` | 조사 시점에 실제 locator가 존재하는지 여부 |
| release binding | `BOUND` / `UNBOUND` / `STALE` / `LEGACY_SCOPE` | exact release generation에 결속됐는지, 과거 generation인지, 참고 범위인지 |
| disposition | `FOUND_INPUT` / `STALE_OR_SCOPE_PENDING` / `MISSING_REQUIRED_OUTPUT` | 후속 작업에서 재사용할 입력인지, delta 갱신이 필요한 선행근거인지, 새로 만들어야 하는 필수 출력인지 |

`FOUND`나 schema `PASS`는 법적 충분성, predicate 충족, 승인 또는 출시 가능
판정이 아니다. `MISSING_REQUIRED_OUTPUT` 행에 부분 입력이 있더라도 요구되는
최종 출력이 없다는 뜻이다.

부재 조사는 repository root에서 `rg --files .`로 수행하되 `.git`, `.venv`,
`node_modules`, 생성 `build` 디렉터리를 제외했다. project license/notice는
`LICENSE`, `LICENSE.*`, `COPYING`, `COPYING.*`, `NOTICE`, `NOTICE.*` exact
basename을 root 및 전체 제품 source tree에서 찾았고 결과는 0건이었다.
모델 source contract는 아래 두 exact path의 부재를 각각 확인했다.

- `docs/model-data/walksafe_13class_dataset_source_contract_20260619.md`
- `data_sources/manifests/walksafe_aihub_source_usage_20260628.md`

## 4. Dependency · license · attribution trace matrix

| # | 대상 요구/trace | locator / release binding / disposition | 실제 locator · SHA-256 · bytes 또는 부재 관찰 | 조사 판정과 claim boundary |
|---:|---|---|---|---|
| 1 | DEV-17 controlled subject | `FOUND / UNBOUND / FOUND_INPUT` | `docs/deliverables/05-implementation/implementation-quality-record.md` · `c880892200f620f559f4b9f9278df092d1cbe1388ca2a4e90c286504a00d49fc` · `6607` bytes; anchor `#dev-17` | anchor는 연결 문서의 검토를 `PENDING_REVIEW`로 둔다. locator 존재만 확인하며 acceptance는 0이다. |
| 2 | REL-22 controlled subject | `FOUND / UNBOUND / FOUND_INPUT` | `docs/deliverables/09-release/delivery-and-handover.md` · `34a263359cab57d9245520a50f2733f50ab4848322646d771410738b9b82c82c` · `31028` bytes; anchor `#rel-22` | anchor는 `DRAFT / NOT_APPROVED`이며 실제 release dependency를 다시 생성·검토하라고 요구한다. owner approval은 0이다. |
| 3 | DEV-17 라이선스·고지 초안 | `FOUND / STALE / STALE_OR_SCOPE_PENDING` | `docs/deliverables/05-implementation/licenses-and-notices.md` · `156af771055c842080248a5b03e93611929fca7c2bb373477d022852b0cdffc3` · `3440` bytes | 문서는 `Draft`, 공급망 검증 `NOT_RUN`이다. Android admin, Android gateway 및 `model/requirements.txt`를 확인 대상 목록에 넣지 않으므로 현재 source tree 전체 trace가 아니다. |
| 4 | Android 사용자 앱 direct/transitive dependency input | `FOUND / UNBOUND / FOUND_INPUT` | `apps/android/app/build.gradle.kts` · `cbc884f8c17f21748c028afee38ffb7e8151ca9404655906d76b7cb9de3de160` · `4652` bytes; `apps/android/app/gradle.lockfile` · `9f62f9279f66c03e58ec07193f56fc6b30209906cc3fabc9db52756c037b3aae` · `67211` bytes; `apps/android/gradle/verification-metadata.xml` · `0f2fc21ad52bd81b877f4a2cecdf587841f4dcac2c87e0e3139a0f94374c0084` · `199913` bytes | strict dependency lock와 artifact checksum 후보가 존재한다. 이 파일들은 license 명칭·text나 attribution 승인 자체가 아니다. |
| 5 | Android 관리자 앱 dependency input | `FOUND / UNBOUND / FOUND_INPUT` | `apps/android/adminapp/build.gradle.kts` · `009e66fbd276c7aed7935ad0f9bbb9e4950e63ac57b213e7f93d27b4668dbdc6` · `3325` bytes; `apps/android/adminapp/gradle.lockfile` · `a4b167f814bbe8df8fcfffeb7114c81024c89913c43af560ffc6f55d48062a13` · `40829` bytes | 현재 입력은 존재하지만 DEV-17 초안의 확인 대상과 exact release scope에는 아직 결속되지 않았다. |
| 6 | Android gateway Node dependency input | `FOUND / UNBOUND / FOUND_INPUT` | `apps/android-gateway/package.json` · `c4aa0b7770443c7299286b4c1e8db8df3777973306e9848c85cbe61c018999e6` · `554` bytes; `apps/android-gateway/package-lock.json` · `cf595d4b768ec3fb00ccd4223249cfd5b33c25b3940c864878d8dc0f04d075d1` · `1435` bytes | lock v3의 비-root package 3개 모두 integrity는 있으나 `license` field는 없다. |
| 7 | Web/PWA Node dependency input | `FOUND / LEGACY_SCOPE / STALE_OR_SCOPE_PENDING` | `apps/web/package.json` · `323527f9f8c6a97a534b0fae495ac57a91db6d1e1e4b03edc5b18d066fe8c513` · `978` bytes; `apps/web/package-lock.json` · `ed0f0daca6c14b01f976c94e473b3f47caba5e809072e85d507dfedba735222f` · `212352` bytes | package와 lock의 root direct dependency 선언이 일치한다. lock v3의 비-root 404개 모두 integrity가 있고 377개만 `license` field가 있으며 27개는 없다. metadata는 license text/notice가 아니다. DEV-17 초안의 범위는 `LEGACY_REFERENCE_ONLY`다. |
| 8 | Web 품질 tool dependency input | `FOUND / LEGACY_SCOPE / STALE_OR_SCOPE_PENDING` | `apps/web/quality-requirements.lock` · `b6a662a22dc0d4e298399402ec096c2046f09af6c5c24febf7e3174b30af3737` · `5202` bytes | 품질 도구 lock 입력은 존재하지만 원문상 `LEGACY_REFERENCE_ONLY`다. 제품 배포 포함 여부와 license/notice 의무는 release scope에서 별도 판정해야 한다. |
| 9 | Backend Python dependency input | `FOUND / UNBOUND / FOUND_INPUT` | `backend/requirements.txt` · `304f4dcca26b40bc7a5e296deaa0c8c32b0bea5122edc50388bef8da2e118e24` · `256` bytes; `backend/requirements.lock` · `0d17e6bb78f882a7e32b4d14d047c4c34e60737db28c5c163b60c2929fea6700` · `148063` bytes | direct manifest와 hash-bearing transitive lock 후보가 존재한다. license 명칭·text·저작권·notice는 이 두 파일에 결속되지 않았다. |
| 10 | Voice Python dependency input | `FOUND / UNBOUND / FOUND_INPUT` | `voice/requirements.txt` · `9d95b3dfcb973c4017c5f3d90d823de43dac6e46d1e08bb3fc4454a567e50884` · `252` bytes; `voice/requirements.lock` · `ff98390abcba89e5e7f6a3c5ef9172b4a195507a990ffcf2975bee26ac206c2b` · `182757` bytes; `voice/quality-requirements.lock` · `056f89f2b828f684dc72882a000d8492b5cdc5c84081ab384f932d8f2f10ef29` · `555` bytes | direct manifest와 hash-bearing lock 후보가 존재한다. release 형상 및 license/attribution 검토에 대한 acceptance는 없다. |
| 11 | Test-only Python dependency input | `FOUND / UNBOUND / FOUND_INPUT` | `tests/requirements.txt` · `ef919bb39f5a67fe3feb7780cb5a9dcc82c27a7306f254ad7c1a6f7a27c86c91` · `68` bytes; `tests/requirements.lock` · `abd690bacf91a65907e6b8357252ead9082181e9ed1b4db98987b2685e51b9fe` · `20288` bytes; `tests/general-quality-cp312-linux-x86_64-cpu.lock` · `5fe49848eee31f86211fafd6a0077ec0e0859737190221421c74a10db53918aa` · `141132` bytes | test asset 후보 입력은 존재한다. production 배포 포함 여부를 추정하지 않으며 release generation에서 별도 범위 판정이 필요하다. |
| 12 | Submission/config/toolchain input | `FOUND / UNBOUND / FOUND_INPUT` | `configs/submission_exact8_requirements.lock` · `d78dfeaf489a25e88a081bf07ca0fe702b16c3c9a6acdde79ba204fa1c6da87f` · `827` bytes; `configs/submission_installer_requirements.lock` · `f18cd67f07b661db59219d1825cd2557d8e2d9996c5183e116b2ddd64d081901` · `97` bytes; `configs/submission_toolchain_lock_20260713.json` · `5ddd5ce86e3080ac278ec9e157e9a3bcb4642c996e1a38be1ba7f77df2a8fc81` · `4703` bytes; `configs/walksafe_node_toolchain_lock_20260715.json` · `3b71481dee20943c6a7039315068809c6858c720bfc9df9133d43048b7b7b1a3` · `1777` bytes; `tests/test_walksafe_node_toolchain_lock.py` · `c826addc7a98b1d9bf1f1943402f38310f2e2b1b10d18847bc6a3a52e960d3e1` · `9436` bytes | R011의 설정·toolchain 및 build/config 입력 후보다. source 존재만 확인하며 release generation이나 license 의무에 결속됐다는 뜻은 아니다. |
| 13 | Model Python dependency closure | `FOUND / UNBOUND / MISSING_REQUIRED_OUTPUT` | 관찰된 direct manifest: `model/requirements.txt` · `f1ed492e19e1a98827b2c32756862f980ca84ef19263d6158af9b000d138e310` · `77` bytes. 대응하는 current hash-bearing `model/requirements.lock`은 발견되지 않음. | direct 4개 항목만으로 전이 dependency·artifact hash·license text를 결속할 수 없다. 필수 closure 출력은 없다. |
| 14 | Project-level license grant/text | `MISSING / UNBOUND / MISSING_REQUIRED_OUTPUT` | repository source tree의 root 또는 제품 source 경계에서 project `LICENSE`, `LICENSE.*`, `COPYING`, `COPYING.*`를 발견하지 못함. | DEV-17 required content `프로젝트 라이선스`가 미충족이다. 라이선스 종류를 추정하지 않는다. |
| 15 | W3 DEV-17 metadata predecessor | `FOUND / STALE / STALE_OR_SCOPE_PENDING` | `docs/control/execution/artifact-remediation/20260726/w3/engineering-execution-current-state.json` · `02a595ad40fd3deb4ff7fa61d31be3b1a115586f7110d6966990af323b146d45` · `48879` bytes; `docs/control/execution/artifact-remediation/20260726/w3/aux-execution-20260726-005/license-review.json` · `428b90d44a9f293d219c06921f1d7a9c829fbf87f5bb2c78a376259b8041f613` · `488821` bytes; `docs/control/execution/artifact-remediation/20260726/w3/independent-review.md` · `006f676b0eb06df3843c1838c7adc247747f886369242cb4c087b26b2f837107` · `11495` bytes | W3는 531 component를 known 346, unknown 185, exception 13으로 분류했고 독립검수는 `APPROVED_WITH_OPEN_GAPS`였다. 이 부분 근거는 폐기하지 않고 delta 입력으로 재사용한다. unknown·exception, NOTICE·법무 검토, fixed release-generation 결속은 여전히 open이다. |
| 16 | Current release-bound component copyright · license text inventory | `MISSING / UNBOUND / MISSING_REQUIRED_OUTPUT` | exact release generation별 `component/version/copyright/license identifier/license text`를 W3 이후 delta까지 포함해 결속한 current locator를 발견하지 못함. | W3 metadata, npm 일부 metadata, Gradle/Python lock 또는 로컬 `.venv` 설치물은 이 최종 출력으로 대체되지 않는다. |
| 17 | Attribution · NOTICE | `MISSING / UNBOUND / MISSING_REQUIRED_OUTPUT` | repository source tree의 root 또는 제품 source 경계에서 release-bound `NOTICE`, `NOTICE.*`, third-party attribution bundle을 발견하지 못함. | REL-22 required content `attribution·notice`가 미충족이다. |
| 18 | Model · dataset 권리 input | `FOUND / UNBOUND / FOUND_INPUT` | `model/registry/walksafe-model-registry.json` · `3768cb39c44c13dc03c827288eabdbf9ec9fb244b7750d5252c0685db5a70b3a` · `2293` bytes; `data_sources/manifests/walksafe_v1_sources.md` · `fcaac487301c80171aec6b83dd0d4c2444edb2ca8162297041d06c58d117684b` · `1482` bytes; `docs/control/execution/artifact-closure/run-20260727-001/data-model-source-current-state.md` · `05c44d43a5bc37b5245304560bf44fd4aa8f336f4f567aacd78160850d6ebf6c` · `9960` bytes; `docs/control/execution/artifact-closure/run-20260727-001/data-model-source-current-state.json` · `ca56b1d373ed09035c4b0968d6461045e50455a484515c82c4ac1dbcd617e766` · `20365` bytes | 후보 registry/source/current-state는 있으나 각 배포 model·dataset의 권리, license text, attribution 및 재배포 조건이 exact artifact에 결속되지 않았다. model README가 지목한 두 source contract는 존재하지 않는다. 모델과 학습데이터 권리를 같다고 가정하지 않는다. |
| 19 | W3 SPDX/CycloneDX predecessor | `FOUND / STALE / STALE_OR_SCOPE_PENDING` | `docs/control/execution/artifact-remediation/20260726/w3/evidence-20260726-003/sbom.cyclonedx.json` · `cdad7be21efe592c5d3ae429b3718a2561f475c3bccc7a98e3430beaf876cfce` · `815731` bytes; `docs/control/execution/artifact-remediation/20260726/w3/evidence-20260726-003/sbom.spdx.json` · `7599f3129872f20b44ca9b8c6363b29880018f536a54100279cd4a90a66ddb9e` · `907864` bytes | W3의 CycloneDX 1.6과 SPDX 2.3은 공식 schema 오류 0으로 PASS했다. 구조 검증일 뿐 license/legal 승인이나 현 release generation 결속은 아니므로 delta 기반 successor 입력으로만 재사용한다. |
| 20 | Current release-bound SBOM/provenance | `MISSING / UNBOUND / MISSING_REQUIRED_OUTPUT` | fixed current release generation에 결속된 SPDX/CycloneDX successor와 provenance locator를 발견하지 못함; release evidence template의 `sbom` 값은 미채움 상태다. | W3 SBOM이나 package/lock 존재를 current release-bound SBOM 완료로 취급하지 않는다. |
| 21 | Exact release generation · artifact binding | `MISSING / UNBOUND / MISSING_REQUIRED_OUTPUT` | 위 source/build/model/config/migration 입력과 license/notice output을 하나의 release manifest, artifact hash 및 검토 판정으로 묶은 current locator를 발견하지 못함. | DEV-17과 REL-22 모두 current source snapshot만으로 acceptance 또는 execution을 주장할 수 없다. |
| 22 | Current designated review · approval record | `MISSING / UNBOUND / MISSING_REQUIRED_OUTPUT` | 두 exact subject와 current release generation에 대한 QA/운영/보안·개인정보/법무·라이선스 검토 disposition 및 지정 승인자의 명시적 approval locator를 발견하지 못함. | W3 독립검수는 선행 generation의 내부 근거 검수다. `OWNER_CREDIT=0`, `ACCEPTANCE_CREDIT=0`; 내부 조사자는 지정 승인자나 법무 검토자를 대체하지 않는다. |
| 23 | License compatibility/legal disposition | `MISSING / UNBOUND / MISSING_REQUIRED_OUTPUT` | 법무·라이선스검토자가 exact release generation에 대해 남긴 compatibility/obligation disposition locator를 발견하지 못함. | 호환성·허용 여부를 이 준비자료에서 판단하지 않는다. |

### 4.1 Exact locator-set manifest

위 matrix의 실제 존재 locator는 중복 없이 정확히 `36`개다.

| 묶음 | locator 수 |
|---|---:|
| controlled subject | 2 |
| stale draft | 1 |
| dependency · config · toolchain input | 24 |
| W3 metadata/SBOM/review predecessor | 5 |
| model · dataset source/current-state input | 4 |
| 합계 | 36 |

§1의 R011 ledger binding 1개를 더한 이 문서의 전체 실제 locator binding은
`37`개다. 각 locator의 SHA-256과 bytes는 위 표 또는 §1에 기록했다.

## 5. 현재 차단 항목과 소유 경계

| 차단 항목 | 현재 상태 | 다음 증거 조건 | R011 owner / reviewer·approver |
|---|---|---|---|
| 현행 release component 범위 | Android admin/gateway/model dependency가 DEV-17 초안의 확인 목록에 미결속 | exact release generation의 source/build/model/config/migration inventory와 hash | 개발책임자 / 기술책임자·QA책임자·법무·라이선스검토자 |
| dependency closure | Android/Web/Backend/Voice/Test 후보는 있으나 model lock과 통합 release scope가 없음 | direct/transitive component·version·artifact를 같은 release generation에 결속 | 개발책임자, 릴리스책임자 |
| license/copyright text | project license 및 component별 copyright/license text inventory 없음 | 원문 locator와 immutable copy/hash, component/version 매핑 | 개발책임자 / 법무·라이선스검토자; 릴리스책임자 |
| attribution/notice | release-bound NOTICE/attribution bundle 없음 | 의무별 notice·표시·소스 제공·수정 고지와 배포 위치 | 릴리스책임자 / QA책임자·운영책임자·보안·개인정보책임자·법무·라이선스검토자 |
| model/dataset/SDK rights | 후보 source 명칭은 있으나 권리 원문·허용 범위·artifact 결속 없음 | model, training dataset, pretrained input, SDK별 독립 권리 trace와 검토 disposition | 개발책임자, 릴리스책임자 / 법무·라이선스검토자 |
| SBOM·release manifest | current exact locator 없음 | artifact hash, SBOM, notice bundle, review/approval을 하나의 release generation으로 결속 | 릴리스책임자 / 제품책임자 |

기한은 R011에 주어지지 않았으므로 임의 날짜를 만들지 않는다. due trigger는
DEV-17은 다음 `ARTIFACT_WORK` content-review candidate freeze 전, REL-22는
release candidate freeze 또는 외부 배포 판단 전이다. 각 trigger 전에 R011의
지정 owner와 reviewer·approver가 별도 disposition을 남겨야 하며, 그때까지 두
record는 `OPEN`을 유지한다.

## 6. Preparation disposition

```text
EXACT_IDS=2
EXACT_ID_SET=DLV-DEV-17,DLV-REL-22
R011_ROUTE=INTERNAL_READY,INTERNAL_READY
R011_RESOURCE_CLASS=LIGHT,LIGHT
CONTENT_ACCEPTANCE_CLAIMED=false
OWNER_APPROVAL_CLAIMED=false
EXECUTION_COMPLETION_CLAIMED=false
ARTIFACT_COMPLETION_CLAIMED=false
RELEASE_ELIGIBLE_CLAIMED=false
ARTIFACT_CREDIT_DELTA=0
OWNER/ACCEPTANCE/EXECUTION=0
NEXT_STATE_CHANGE_AUTHORIZED=false
```

이 맵의 `FOUND`는 후속 canonical writer와 지정 검토자가 다시 확인할 후보
입력만 뜻한다. `MISSING`과 `STALE`이 해소되고 exact release generation에
결속되더라도, 별도 review·approval 없이는 acceptance나 artifact closure가
발생하지 않는다.
