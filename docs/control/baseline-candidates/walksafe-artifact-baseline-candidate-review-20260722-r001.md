# WalkSafe 2026-07-22 산출물 기준선 승인 후보

> 이 문서를 만들거나 읽는 것은 승인이 아닙니다. 아래 정확한 한 문장을 사용자가 별도로 승인하기 전에는 어떤 상태도 바뀌지 않습니다.

## 한눈에 보기

- 버전형 내용 기준선 후보: **102개**
- 계속 갱신형 Active 최초본 후보: **27개**
- 이번 승인 후보 합계: **129개**
- 외부값·실행근거 대기: **53개**
- Planned/NOT_RUN 유지: **75개**
- 이번에 승인하지 않는 합계: **128개**
- 남은 gate: **5개, 전부 NOT_RUN·미면제**
- 출시 상태: **NOT_ELIGIBLE**

## 이전 후보와 FP-035

- `WS-ARTIFACT-BASELINE-CANDIDATE-20260721-001`는 승인·적용되지 않았습니다. 원본을 보존하고 후보 선택 관계에서만 이 후보가 뒤를 잇습니다.
- `WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001`는 현재 NOT_APPROVED/NOT_EFFECTIVE입니다. 아래 승인문이 승인될 때 0단계에서 정확한 정정 오버레이와 정책 1.0.1을 먼저 논리적으로 성립시킵니다.
- FP-035 직접 의존 산출물은 REQ-03, REQ-06, DES-04, DES-13, DES-20 다섯 개입니다.

## 원자적 처리 경계

모든 경로와 SHA-256을 한 번 검증해 불변 snapshot으로 고른 뒤 각 단계는 live 파일을 수정하지 않고 논리적으로만 준비합니다. 한 단계라도 실패하면 준비 사건 전부를 버립니다. 전 단계가 성공해야 별도 반영 절차가 정책·129개 상태를 한 번 전환하고 DOC-01·DOC-05를 한 번만 갱신할 수 있습니다.

## 정확한 승인문

승인 후보 WS-ARTIFACT-BASELINE-CANDIDATE-20260722-001 버전 1.0.0, 승인대상 지문 c907a0d213590ba69db732450cc5f79cbe4f3acf0f95bd3af2e323b2aed55193, 분류 지문 6c8856d5f50f087f9e26d8608f9bc800d4ac75aba75cda4694f2caf8c51fe324, 파일집합 지문 34625f9776c0f9d716f6fec5f674927c1b7f2d37c95854b87b909e42d0f69ed7, 단계순서 지문 4a6c375abd34d4c6d956147a72d4a5db0d5f818cd49784b8fbe657150a6a3e0a, 입력집합 지문 04ef9eb739ecbacbc6940799124a863451afaad9a763c0334feed56e8369ca5e를 근거로 기존 미승인 후보 WS-ARTIFACT-BASELINE-CANDIDATE-20260721-001 버전 1.0.0 파일 지문 af0d9ca906cd40a3ca1aa65fa9e34d5a0733add4563e6299ef5a0c9147aa3683은 승인·적용하지 않은 채 기록으로 보존하고 후보 선택 관계에서만 새 후보로 대체하며, 0단계에서 FP-035 정정 후보 WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001 파일 지문 7298e024cbb88e8fda5e27dfd9e97f7ebf23250a7adf4ea6185c406adb4ed742을 정확히 승인하여 정책 기준선 1.0.1을 논리적으로 성립시킨 뒤, 의존성 단계 순서에 따라 버전형 내용 기준선 102개와 계속 갱신형 Active 최초본 27개를 합한 129개 복합 승인단위를 승인하고, 외부값·실행근거 대기 53개와 Planned/NOT_RUN 75개를 합한 128개는 승인하지 않으며, 모든 단계는 하나의 불변 snapshot에서 live 전환 없이 준비하고 어느 단계든 실패하면 전부 폐기한 후 전 단계 성공 뒤 별도 반영 절차에서만 상태를 한 번 전환하고, 이 승인으로 현행 구현 적합성·시험·배포·운영·인수·종료 완료를 주장하지 않으며 5개 gate는 NOT_RUN·미면제, 출시는 NOT_ELIGIBLE로 유지하는 것을 승인합니다.

## 핵심 지문

- 승인대상: `c907a0d213590ba69db732450cc5f79cbe4f3acf0f95bd3af2e323b2aed55193`
- 후보 JSON 파일: `9616bfd3f3bdaf355f0f92082d38fc00c65cfefac766613714e4d449e92b21f0`
- 분류: `6c8856d5f50f087f9e26d8608f9bc800d4ac75aba75cda4694f2caf8c51fe324`
- 파일집합: `34625f9776c0f9d716f6fec5f674927c1b7f2d37c95854b87b909e42d0f69ed7`
- 단계순서: `4a6c375abd34d4c6d956147a72d4a5db0d5f818cd49784b8fbe657150a6a3e0a`

## 단계 요약

| 단계 | 의미 | 대상 수 | live 전환 |
|---:|---|---:|---|
| 0 | FP-035 정정 + 정책 1.0.1 | 1 | 허용 안 함 |
| 1 | 의존성 순서에 따른 산출물 논리 승인 | 8 | 허용 안 함 |
| 2 | 의존성 순서에 따른 산출물 논리 승인 | 10 | 허용 안 함 |
| 3 | 의존성 순서에 따른 산출물 논리 승인 | 6 | 허용 안 함 |
| 4 | 의존성 순서에 따른 산출물 논리 승인 | 7 | 허용 안 함 |
| 5 | 의존성 순서에 따른 산출물 논리 승인 | 9 | 허용 안 함 |
| 6 | 의존성 순서에 따른 산출물 논리 승인 | 5 | 허용 안 함 |
| 7 | 의존성 순서에 따른 산출물 논리 승인 | 4 | 허용 안 함 |
| 8 | 의존성 순서에 따른 산출물 논리 승인 | 4 | 허용 안 함 |
| 9 | 의존성 순서에 따른 산출물 논리 승인 | 11 | 허용 안 함 |
| 10 | 의존성 순서에 따른 산출물 논리 승인 | 10 | 허용 안 함 |
| 11 | 의존성 순서에 따른 산출물 논리 승인 | 4 | 허용 안 함 |
| 12 | 의존성 순서에 따른 산출물 논리 승인 | 4 | 허용 안 함 |
| 13 | 의존성 순서에 따른 산출물 논리 승인 | 3 | 허용 안 함 |
| 14 | 의존성 순서에 따른 산출물 논리 승인 | 4 | 허용 안 함 |
| 15 | 의존성 순서에 따른 산출물 논리 승인 | 2 | 허용 안 함 |
| 16 | 의존성 순서에 따른 산출물 논리 승인 | 6 | 허용 안 함 |
| 17 | 의존성 순서에 따른 산출물 논리 승인 | 7 | 허용 안 함 |
| 18 | 의존성 순서에 따른 산출물 논리 승인 | 8 | 허용 안 함 |
| 19 | 의존성 순서에 따른 산출물 논리 승인 | 7 | 허용 안 함 |
| 20 | 의존성 순서에 따른 산출물 논리 승인 | 6 | 허용 안 함 |
| 21 | 의존성 순서에 따른 산출물 논리 승인 | 3 | 허용 안 함 |
| 22 | 의존성 순서에 따른 산출물 논리 승인 | 1 | 허용 안 함 |

## 257개 전수 분류

| ID | 산출물 | 분류 | 현재 → 승인 후/유지 | 정본 | 복합단위 SHA-256 |
|---|---|---|---|---|---|
| DOC-01 | 산출물 관리대장 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/00-control/artifact-register.json` | `6b4c105f18e28914cdbbdf78cf0cb20f49c6f6fcacce5fe5609424d025c404a4` |
| DOC-02 | 문서·파일 명명 규칙 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/00-control/document-control-manual.md` | `96687240ba32087b283946cd730d96780b84a6b5d2a2cfc6cfd0e7effab24932` |
| DOC-03 | 검토·승인 절차 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/00-control/document-control-manual.md` | `afafae1acc96f234f1f31e47fa6b34f861db4c8c49880d3b27afdddc0c832fd5` |
| DOC-04 | 기준선·버전 관리 규칙 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/00-control/document-control-manual.md` | `23836e7796cc471c691f9e198abdbcdb392834ae5f3ccde2a6ae7aea3e130d6d` |
| DOC-05 | 산출물 변경 이력 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/00-control/artifact-change-log.json` | `ff6cdb2a71843600cdf02418b885754d7a35509edcc3098ba3cf7f8ae4a56b3e` |
| MGT-01 | PC(Project Charter): 프로젝트 헌장 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/01-management/project-charter.md` | `8ade3af502047c4bcb11652191e46c02d21614bce47404889f569b74435d8370` |
| MGT-02 | 사업 필요성·기대효과 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/01-management/project-charter.md` | `160ada7d5fca8d5945ad24455df71d88f4d7bcf5b38c1ae4fb62aeddad32c474` |
| MGT-03 | 프로젝트 목표와 성공지표 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/01-management/project-charter.md` | `9f090574e8eb2b358d45b3c626fe336816e7645d4ebc3d5e6186e0f7285290f6` |
| MGT-04 | 범위·제외 범위 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/01-management/project-charter.md` | `37ab666866576c4af79c6db73504f22333a189055481c34a220d2b08794fe072` |
| MGT-05 | PMP(Project Management Plan): 프로젝트 관리계획 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/01-management/project-management-plan.md` | `6af04457fdb73d5b8c93fa58700350bf5fd1dc9cf04c71d3f4aa7c7b1a0f29cc` |
| MGT-06 | WBS: 작업분해구조 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/01-management/project-management-plan.md` | `eb6b597a1fde7c0275a9290c0f10a762ca9cb696947527e8187757d15deb1549` |
| MGT-07 | 일정·마일스톤 계획 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/01-management/project-management-plan.md` | `6505196441d54ff502f3d78fcf44d088c2d8933964ec776983d0d1c23f4c6e0f` |
| MGT-08 | 예산·자원 계획 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/01-management/project-management-plan.md` | `730dd36d7dbf80d7652598819ab52de660ccbba231dc6df7ea50ae419d8027dd` |
| MGT-09 | 이해관계자 목록 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/01-management/project-management-plan.md` | `ee8d68e2d1df5064d3e73e32ef78229a30d6b7fb8b1d5acb2a8903b28a9715d1` |
| MGT-10 | RACI: 역할·책임 매트릭스 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/01-management/project-management-plan.md` | `170618690bf53684f67745e698abf35677b556a2b46e6c009e1bfeea94b6381a` |
| MGT-11 | 의사소통·보고 계획 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/01-management/project-management-plan.md` | `454dcf2b89c11823161e57dc7e749b289e6b85eb9fdaff6b375506a80743cb59` |
| MGT-12 | 품질관리 계획 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/01-management/project-management-plan.md` | `a8892aafdc80d3f8c3c6c864d9d7a62b83a2ca28d123abeb5912334630491c4f` |
| MGT-13 | 형상관리 계획 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/01-management/project-management-plan.md` | `57d4b766f0c860c8991a282309fd81c2d9044b3349f2127c83d71571c71f4f42` |
| MGT-14 | RAID: 위험·가정·이슈·의존성 관리대장 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/01-management/project-control-registers.md` | `b419f82b63b6694744ba5c4232089de3fa7a9e80ed27c6027076b6c6c519df9e` |
| MGT-15 | 의사결정 기록 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/01-management/project-control-registers.md` | `45c84da32156f1194fe8765c46a6afcca6ec1eb16428f2fa6e629d917007853a` |
| MGT-16 | 변경요청·변경이력 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/01-management/project-control-registers.md` | `e8a85d0f4dc78d81377c2a97f1328acbd2dc8ed81424c5d9e4ea28c2e1af1917` |
| MGT-17 | 진행상태 대시보드 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/01-management/project-control-registers.md` | `97f84e0b89bfd9d736b4549a27ac34038fdc9504102bf9350b4df9fb916d2d1a` |
| MGT-18 | 회의 결정·Action Item 기록 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/01-management/project-control-registers.md` | `352233c9fec6ea328a5c7a7add4257d2d59d86ecc3975ef1745ee0f1110934a0` |
| DSC-01 | 문제 정의서 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/02-discovery/discovery-evidence-and-analysis.md` | `2d360fb0fbcd10da7952850b348d967c994c87cacdf0a21f5c0aa1ec29e07da1` |
| DSC-02 | 대상 사용자·이해관계자 정의 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/02-discovery/discovery-evidence-and-analysis.md` | `1219c4cafc8880580d1d02ed537bbf61fc28c74edd9fb5cfe8b503b3cd94a775` |
| DSC-03 | 사용자 조사계획 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/02-discovery/discovery-evidence-and-analysis.md` | `8f075506bb6b709787ba8f677ac2af10182717f72cd5c05bb3ea9ab35472c761` |
| DSC-04 | 사용자 조사 결과와 근거 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/02-discovery/discovery-evidence-and-analysis.md` | `승인 대상 아님` |
| DSC-05 | 페르소나·사용자 프로필 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/02-discovery/discovery-evidence-and-analysis.md` | `승인 대상 아님` |
| DSC-06 | 현재·목표 사용자 여정 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/02-discovery/discovery-evidence-and-analysis.md` | `승인 대상 아님` |
| DSC-07 | 경쟁·유사 서비스 조사 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/02-discovery/discovery-evidence-and-analysis.md` | `승인 대상 아님` |
| DSC-08 | 가정·가설 목록 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/02-discovery/discovery-evidence-and-analysis.md` | `cb965b91f5c14589a5460548cd7157dd3f340a4f55f7dc40bc7fc6257f3b3356` |
| DSC-09 | PoC·기술 타당성 검증 결과 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/02-discovery/discovery-evidence-and-analysis.md` | `승인 대상 아님` |
| DSC-10 | 제품 비전·제품 목표 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/02-discovery/product-definition.md` | `106da3dc98c427c5912462d07b1c0725e5c69fc6ba0a2ddbf66804e160c00f60` |
| DSC-11 | KPI 기준값과 목표값 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/02-discovery/product-definition.md` | `e392dd50df24e9190411fe14c103e383b632f8739baef61e2ce3a7b195ad7be3` |
| DSC-12 | MVP 범위 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/02-discovery/product-definition.md` | `7e034f9f9fcd6dab5c63570e5fac1206e9ee3d95e664552dc36c8b6ab92bb3c9` |
| DSC-13 | 제품 로드맵 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/02-discovery/product-definition.md` | `f42edb8485b4e47c26f32a9bb5c267f80c654776bbbf92f57a6df62aea13c642` |
| DSC-14 | 우선순위 Backlog | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/02-discovery/product-registers.md` | `666810aaddc2c74eb49ed25a7727b2d253a9fee824364a86acf75d06f624ebbb` |
| DSC-15 | 착수·계속·중단 판단 기록 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/02-discovery/product-registers.md` | `d5816c5ca94965e807a22dae6dc06b563c2de9a77e99f06c1821f2de48accc83` |
| REQ-01 | SRD(System Requirements Document) | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/03-requirements/system-requirements.md` | `afe8f758408edcd279f31c4e2cbefce793b481ec44dd9e951dd81ab9bfe268a3` |
| REQ-02 | SRS(Software Requirements Specification) | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/03-requirements/system-requirements.md` | `b5a56445b0ea1c9c6a391f92e531e2a15edc7d0b7be50ce0bbbde3486b72842b` |
| REQ-03 | 기능 요구사항 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/03-requirements/system-requirements.md` | `7ed19636faf1c7021fba0ed6c01f51f93a2313523a520202ca7ae5dae6c4bac3` |
| REQ-04 | 비기능 요구사항 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/03-requirements/system-requirements.md` | `d0b61edf8a4dc6af87e284f1cae374363a21251d254da5602acbab0ac228be64` |
| REQ-05 | 유스케이스·사용자 스토리 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/03-requirements/acceptance-specification.md` | `77b758f0de0381bc28a9e48b25efae5bf9ddccf5ec303f0c8750e98fc40b658b` |
| REQ-06 | 인수 조건 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/03-requirements/acceptance-specification.md` | `73c43739fb0800a3e63b073e37e0f7fd394b8d0bb937470fe0e22c28d874d488` |
| REQ-07 | 외부·내부 인터페이스 요구사항 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/03-requirements/system-requirements.md` | `50b0729be535c6e41d356ccdddcc37eaeea6fd4941cc3ba913151ae6fc4d7df8` |
| REQ-08 | 데이터 요구사항 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/03-requirements/system-requirements.md` | `e1211994af6e5fc9662e7ca7440cf74f02ec30fd0409d0494d44990ca4ecff76` |
| REQ-09 | 보안 요구사항 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/03-requirements/system-requirements.md` | `d50398642ec3dfad0f3c11036d3da3e74864e0847da6c4d3a65b942fd9bc9c32` |
| REQ-10 | 개인정보 요구사항 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/03-requirements/system-requirements.md` | `dfff7cd6ae4859f577579e10643065ca0349128601b863f112215e40f4fdd735` |
| REQ-11 | 접근성 요구사항 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/03-requirements/system-requirements.md` | `977a792c9e748dabbc0d05eca2ba64cbd90fd25cc16e2c2f36100821ceb05d96` |
| REQ-12 | 성능·용량 요구사항 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/03-requirements/system-requirements.md` | `c8584b0bc144a3949b648659d300d9f1874c37f6fefdf016bfb03a891f32adf1` |
| REQ-13 | 가용성·복구 요구사항 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/03-requirements/system-requirements.md` | `7d4d00894d01aaf72d46a9c8f5ca9222911c4cb342f0441b9c1d4663bbbb465e` |
| REQ-14 | 호환성·지원환경 요구사항 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/03-requirements/system-requirements.md` | `590f3b1a42d3fd79758bdec8be33128c6dbc49eeba833e0b1c776b10adf042e2` |
| REQ-15 | 법률·라이선스·규제 요구사항 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/03-requirements/system-requirements.md` | `8d505967f4acfdc3418916025a01e930d6337c7c1c7ae9d71b0b8f700d16f10c` |
| REQ-16 | RTM(Requirements Traceability Matrix) | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/03-requirements/requirements-traceability.md` | `9fe707d67f494bfd3713ff3f73c8c373a99ec390c31404e7a48c0940b73da416` |
| REQ-17 | 요구사항 기준선 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/03-requirements/requirements-traceability.md` | `a72acc7bb393f8685052e458aca1596bff676ac1fedd41be9f7beee3143c861c` |
| REQ-18 | 요구사항 변경이력 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/03-requirements/requirements-traceability.md` | `22ecb81473d0420e129138ac9394e62b7acb2a79a15975560a0d730767bd99f0` |
| REQ-19 | 용어집 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/03-requirements/requirements-traceability.md` | `763c2e1f382b8d9a09fa09784e8ec344c892b9f727cbfbfc2e5817e7011cf2d1` |
| DES-01 | SDD(Software Design Description) | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/software-architecture.md` | `e78c4ffdc13682389fc9fb0f4bb7f966ffcb3cbc3151f2bdb5e27a826949b372` |
| DES-02 | 시스템 컨텍스트 다이어그램 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/software-architecture.md` | `c9c2abfa4630e62437cff96bf0ba20bc6ac27500a57a350ab346bfb9dd25539c` |
| DES-03 | 구성요소·모듈 구조 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/software-architecture.md` | `4cfd4245b60ffcf3f743e9ce7f7652ee64b3146ee00f0639466a72ca34a5b190` |
| DES-04 | 런타임·시퀀스 흐름 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/software-architecture.md` | `0b95e5bca39f9e227066158564ea964ffac8547407f8695f9c85424ef0051ca8` |
| DES-05 | 배포 아키텍처 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/04-design/software-architecture.md` | `승인 대상 아님` |
| DES-06 | ADR(Architecture Decision Record) | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/04-design/software-architecture.md` | `694cf2cc407e5a016c096df28f5f3503d18ff3ee97ad9bb4693ccd36bdb67bca` |
| DES-07 | 기술 스택·버전 선정 근거 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/04-design/software-architecture.md` | `승인 대상 아님` |
| DES-08 | SIP(Software Integration Plan) | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/04-design/software-architecture.md` | `승인 대상 아님` |
| DES-09 | API·인터페이스 명세 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/interface-and-data-design.md` | `aa007a80193b45b8673bb168a5cf68e46b5a039a9669958cfa81463365503704` |
| DES-10 | OpenAPI·이벤트 스키마 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/interface-and-data-design.md` | `03aafe5b01565f6f804be01a7d85ca7a4a2991332d359acb158a20e75e9c89f0` |
| DES-11 | ERD | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/interface-and-data-design.md` | `00e26488c9d59937b951dfa0f185938251fc3c97b023f81d065290a0bd37382d` |
| DES-12 | 테이블 정의서·데이터 사전 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/interface-and-data-design.md` | `519e03b503fd0341e375ffb34a10167fd7f473e54592867d3e20ce95c993577e` |
| DES-13 | 데이터 생명주기·보존·삭제 설계 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/interface-and-data-design.md` | `339c4a08b01b6a0564df744022e0f33c86587c99f18d85ae49c2e386348cc039` |
| DES-14 | 화면 정보구조 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/user-experience-and-accessibility-design.md` | `7a2914914566e9576b46f3c1ee6f700d21b968de4636d4c8dcbadaa930b219e4` |
| DES-15 | 사용자 흐름 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/user-experience-and-accessibility-design.md` | `d27a5f3314bae4d45d6f788e85e0c2b72947f4b3b779d7aefd79d877d621832d` |
| DES-16 | 와이어프레임·프로토타입 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/user-experience-and-accessibility-design.md` | `aabbb15a6157668a05c78f2aacf5c17f4e74f51ce5f30b0fdd938aefe9e8694e` |
| DES-17 | 디자인 시스템 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/user-experience-and-accessibility-design.md` | `cffa9334e8cb6ac5b3cad878a05eacc3aedeaeda84827d331556bc5ea63837d4` |
| DES-18 | 접근성 설계 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/user-experience-and-accessibility-design.md` | `4aaecb24a604b0967a2c8e2f6b0a653d7951f113b4d9dd0ae507abb1abf98152` |
| DES-19 | 인증·인가 설계 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/security-and-operations-design.md` | `678006f351a576b6421f4434e120e97b10c012fbfd8c8a91a4575ddb44c34318` |
| DES-20 | 위협 모델·신뢰 경계 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/security-and-operations-design.md` | `e1e6b1e958156c3617bca16c1ddb032040c83dd2ae94dfe2a819588324db9a9c` |
| DES-21 | 개인정보 데이터 흐름 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/04-design/security-and-operations-design.md` | `승인 대상 아님` |
| DES-22 | 오류·예외 처리 설계 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/04-design/security-and-operations-design.md` | `승인 대상 아님` |
| DES-23 | 로깅·모니터링 설계 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/security-and-operations-design.md` | `150f3d57703c6e26eed23e7f22156ae457f38dfa7e8d7a53d125b49e1ed50114` |
| DES-24 | 성능·확장성 설계 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/04-design/security-and-operations-design.md` | `승인 대상 아님` |
| DES-25 | 백업·복구·재해복구 설계 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/security-and-operations-design.md` | `d1c6793ca655872ff2f9cbf1cca5428bc1805de87c805c78f4fd8988df953fca` |
| DES-26 | DB·데이터 migration 설계 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/04-design/interface-and-data-design.md` | `승인 대상 아님` |
| DES-27 | 외부 서비스 장애·대체 경로 설계 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/04-design/security-and-operations-design.md` | `124d3e2c109e032adabe715cd931eecc0486028053c56108b37b0adc8151fff3` |
| DEV-01 | 소스코드 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/05-implementation/implementation-configuration.md` | `승인 대상 아님` |
| DEV-02 | 프로젝트 README | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/05-implementation/developer-guide.md` | `승인 대상 아님` |
| DEV-03 | 개발환경 구축 방법 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/05-implementation/developer-guide.md` | `승인 대상 아님` |
| DEV-04 | 실행·테스트 방법 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/05-implementation/developer-guide.md` | `1533a792dc88926fc3bb4b0745a52d7db0cddb038e02d2066bf80e32f174e6af` |
| DEV-05 | 기여·코드리뷰 규칙 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/05-implementation/developer-guide.md` | `92310c85a2664e0684ac8f1e24fa5b50b7752ca0b75b212cc6f2190a957777d2` |
| DEV-06 | 코딩 규칙 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/05-implementation/developer-guide.md` | `e03ddb83ab8e3cb1476a2f395b77fc489597ba6507c21deb2ad3d986ccfded5a` |
| DEV-07 | 의존성 lock 파일 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/05-implementation/implementation-configuration.md` | `승인 대상 아님` |
| DEV-08 | 환경변수·설정 예제 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/05-implementation/developer-guide.md` | `승인 대상 아님` |
| DEV-09 | 빌드 스크립트 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/05-implementation/implementation-configuration.md` | `승인 대상 아님` |
| DEV-10 | CI/CD 파이프라인 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/05-implementation/implementation-configuration.md` | `f54d27e4ce869fe6d8f0195e66b9f1a50deb9f9f0f65bf8fd191f1becbbf2cdf` |
| DEV-11 | IaC | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/05-implementation/implementation-configuration.md` | `d1ba19c17cc92ad883d1afc8f2807e56ddbd91ad26f73d316b9ba795ecce974b` |
| DEV-12 | DB migration | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/05-implementation/implementation-configuration.md` | `승인 대상 아님` |
| DEV-13 | 초기·샘플 데이터 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/05-implementation/implementation-configuration.md` | `8bdcaadc90cf665135a13aa35ed1df782c0687be1e80b78284396b1f0f47b2d3` |
| DEV-14 | 테스트 fixture | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/05-implementation/implementation-configuration.md` | `승인 대상 아님` |
| DEV-15 | 코드리뷰 기록 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/05-implementation/implementation-quality-record.md` | `5e80009f08080777d3b50c8464457d286f5974775863f8a96c76fca85e9a3bb6` |
| DEV-16 | lint·정적분석 결과 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/05-implementation/implementation-quality-record.md` | `승인 대상 아님` |
| DEV-17 | 라이선스·고지 파일 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/05-implementation/implementation-quality-record.md` | `승인 대상 아님` |
| DEV-18 | 프로그램·모듈 목록 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/05-implementation/implementation-quality-record.md` | `승인 대상 아님` |
| DEV-19 | SBOM | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/05-implementation/implementation-quality-record.md` | `승인 대상 아님` |
| DEV-20 | 빌드 provenance·산출물 해시 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/05-implementation/implementation-quality-record.md` | `승인 대상 아님` |
| DEV-21 | SIR(Software Integration Report) | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/05-implementation/implementation-quality-record.md` | `승인 대상 아님` |
| TST-01 | 마스터 테스트 전략 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/06-testing/test-plan.md` | `75f399949e581705bf05c989f71752b48979d2be864214312dc21d29069af953` |
| TST-02 | STP(Software Test Plan) | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/06-testing/test-plan.md` | `60967545f8a3f5135baa7c6c7dd4082fac702be0d14491c47a0923ad310adac3` |
| TST-03 | 테스트 환경·지원 기기 목록 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/06-testing/test-plan.md` | `6238ad1099ed60d3e670bbd7e885eef5e0bed9d0c8e38d8e7a07feb7286c7fcd` |
| TST-04 | 테스트 데이터 계획 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/06-testing/test-plan.md` | `ad3214956034ac7279b84835c49fdea7159f0c4b23c9b37bc25def912abf0d6c` |
| TST-05 | 테스트 케이스·절차 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/06-testing/test-plan.md` | `73f34dff37c565384991ce7c0fd19ee106c3ab5a95620c5b12ef33cda26bd1a7` |
| TST-06 | 단위 테스트 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/06-testing/test-evidence.md` | `승인 대상 아님` |
| TST-07 | 통합 테스트 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/06-testing/test-evidence.md` | `승인 대상 아님` |
| TST-08 | API·계약 테스트 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/06-testing/test-evidence.md` | `승인 대상 아님` |
| TST-09 | E2E 테스트 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/06-testing/test-evidence.md` | `승인 대상 아님` |
| TST-10 | 사용자 인수 테스트 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/06-testing/test-evidence.md` | `승인 대상 아님` |
| TST-11 | 회귀 테스트 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/06-testing/test-evidence.md` | `승인 대상 아님` |
| TST-12 | 성능·부하·스트레스 테스트 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/06-testing/test-evidence.md` | `승인 대상 아님` |
| TST-13 | 호환성 테스트 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/06-testing/test-evidence.md` | `승인 대상 아님` |
| TST-14 | 접근성 테스트 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/06-testing/test-evidence.md` | `승인 대상 아님` |
| TST-15 | 사용성 테스트 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/06-testing/test-evidence.md` | `승인 대상 아님` |
| TST-16 | 장애·복구 테스트 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/06-testing/test-evidence.md` | `승인 대상 아님` |
| TST-17 | 설치·업데이트·롤백 테스트 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/06-testing/test-evidence.md` | `승인 대상 아님` |
| TST-18 | 결함 관리대장 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/06-testing/test-quality-report.md` | `4ba2df298f72eeb10d958f887f5fc94983f6a7b554415ddb5bcd533b77c8a422` |
| TST-19 | 테스트 커버리지·품질지표 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/06-testing/test-quality-report.md` | `fb0adca6e697b9e24a07b47a0ffb80770e06f556dccb8eef23d87e69909d0b49` |
| TST-20 | STR(Software Test Report) | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/06-testing/test-quality-report.md` | `승인 대상 아님` |
| TST-21 | 미해결 결함·잔여 위험 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/06-testing/test-quality-report.md` | `5b76fcd81bf0a5dc8e85f48e309e82d9134bb109c08db5acd01b5d48f62f82c5` |
| TST-22 | 출시 준비도 판단 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/06-testing/test-quality-report.md` | `승인 대상 아님` |
| TST-23 | 인수 확인서 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/06-testing/test-quality-report.md` | `승인 대상 아님` |
| SEC-01 | 보안 개발계획 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/07-security/security-and-privacy-plan.md` | `9ecf1dab9dd6c5efb64e4db65934d2fd7e71a8cad23afc8bf9cda0f695737f65` |
| SEC-02 | 위협 모델 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/07-security/security-and-privacy-plan.md` | `570960a851e6b92efa78c8f249609ed490dd4a88de270ed25132c0e14ebad637` |
| SEC-03 | 보안 위험대장 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/07-security/security-and-privacy-plan.md` | `f8b4baf5f1936775d93301c09c7b41a5c831c74446d94e0a741f5202e657c011` |
| SEC-04 | 보안 설계 검토 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/07-security/security-and-privacy-plan.md` | `승인 대상 아님` |
| SEC-05 | 개인정보 영향평가 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/07-security/security-and-privacy-plan.md` | `승인 대상 아님` |
| SEC-06 | 개인정보 수집·이용·동의 근거 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/07-security/security-and-privacy-plan.md` | `8958ac0e519e1f4afbac2eb4671879016c72e00676208c5b932a0be85c48435f` |
| SEC-07 | 데이터 보존·삭제 정책 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/07-security/security-and-privacy-plan.md` | `9c59412f54416e55268ede4854a093463fb147098cebe178757ab73bdf5cc53f` |
| SEC-08 | 접근권한·RBAC 명세 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/07-security/security-and-privacy-plan.md` | `2ebc80a5e2b9c79949760b424a3aac98240f1f99c3ae0286b2f779be17fc25af` |
| SEC-09 | 비밀정보·인증서 관리 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/07-security/security-and-privacy-plan.md` | `fcf429183c37169a718f17e6e4245c1738bca66d7f3d53df4d2b92c636708dbc` |
| SEC-10 | SAST 결과 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/07-security/security-verification-evidence.md` | `승인 대상 아님` |
| SEC-11 | SCA·의존성 취약점 결과 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/07-security/security-verification-evidence.md` | `승인 대상 아님` |
| SEC-12 | secret scan 결과 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/07-security/security-verification-evidence.md` | `승인 대상 아님` |
| SEC-13 | DAST 결과 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/07-security/security-verification-evidence.md` | `승인 대상 아님` |
| SEC-14 | 침투 테스트 결과 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/07-security/security-verification-evidence.md` | `승인 대상 아님` |
| SEC-15 | 취약점·조치 관리대장 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/07-security/security-response-and-monitoring.md` | `54c40d1db3cf0456e620609319b634366dce2455afb42cc75f4143b97d959d54` |
| SEC-16 | 위험 수용·보안 예외 기록 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/07-security/security-response-and-monitoring.md` | `3f40ed3cb817680c49830ed5dceed0fbabc2c8be011fb3cc0dab466721ced972` |
| SEC-17 | 보안 사고 대응계획 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/07-security/security-response-and-monitoring.md` | `5397cf5bd47a047ff033bb24d7546aa088464d20d887093325a38e00727f595b` |
| SEC-18 | 취약점 신고·처리 정책 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/07-security/security-response-and-monitoring.md` | `f2f752a765b0e8d364e4df27b08aef59911fe65454d2b26ef8ff4dc2f2f0786e` |
| SEC-19 | 감사로그·보안 모니터링 기준 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/07-security/security-response-and-monitoring.md` | `6c7f20c1eb7fd16b87b5a6726e4e9dc6dcecf04227f0a37019d4327c9b29acf7` |
| AIML-01 | 데이터 관리계획 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/08-ai-ml-data/data-management.md` | `승인 대상 아님` |
| AIML-02 | 데이터셋 카드 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/08-ai-ml-data/data-management.md` | `승인 대상 아님` |
| AIML-03 | 데이터 출처·라이선스·동의 기록 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/08-ai-ml-data/data-management.md` | `승인 대상 아님` |
| AIML-04 | 데이터 스키마·버전 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/08-ai-ml-data/data-management.md` | `25b7873334b498929be22b4b8bd0d6128e050924fb16cb32e92346095da7b6dc` |
| AIML-05 | 데이터 품질보고서 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/08-ai-ml-data/data-management.md` | `승인 대상 아님` |
| AIML-06 | 정제·제외 기준 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/08-ai-ml-data/data-management.md` | `승인 대상 아님` |
| AIML-07 | 라벨링 지침 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/08-ai-ml-data/data-management.md` | `bb6544ad9c163b67ee556a133a3a21afe26c9a67610b61d3ef5c12cd71d6dae0` |
| AIML-08 | 라벨링 품질검사 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/08-ai-ml-data/data-management.md` | `승인 대상 아님` |
| AIML-09 | 학습·검증·시험 분할과 해시 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/08-ai-ml-data/data-management.md` | `승인 대상 아님` |
| AIML-10 | 데이터 누수 점검 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/08-ai-ml-data/data-management.md` | `승인 대상 아님` |
| AIML-11 | 학습 코드·설정·seed | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/08-ai-ml-data/model-development.md` | `승인 대상 아님` |
| AIML-12 | 실험 추적 기록 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/08-ai-ml-data/model-development.md` | `승인 대상 아님` |
| AIML-13 | 기준모델·비교실험 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/08-ai-ml-data/model-development.md` | `승인 대상 아님` |
| AIML-14 | 모델 레지스트리 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/08-ai-ml-data/model-development.md` | `승인 대상 아님` |
| AIML-15 | 모델 카드 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/08-ai-ml-data/model-development.md` | `승인 대상 아님` |
| AIML-16 | 모델 파일·버전·해시 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/08-ai-ml-data/model-development.md` | `승인 대상 아님` |
| AIML-17 | 평가 프로토콜 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/08-ai-ml-data/model-evaluation.md` | `0d1f0a189e3153b1d923d189119559b606626f26d5aa80c284c27ad10f81dd45` |
| AIML-18 | 전체·클래스별 평가결과 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/08-ai-ml-data/model-evaluation.md` | `승인 대상 아님` |
| AIML-19 | 오탐·미탐 분석 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/08-ai-ml-data/model-evaluation.md` | `승인 대상 아님` |
| AIML-20 | 강건성·편향·안전 평가 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/08-ai-ml-data/model-evaluation.md` | `승인 대상 아님` |
| AIML-21 | 학습 모델↔배포 모델 동등성 검증 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/08-ai-ml-data/model-evaluation.md` | `승인 대상 아님` |
| AIML-22 | 브라우저·모바일 성능 측정 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/08-ai-ml-data/model-evaluation.md` | `승인 대상 아님` |
| AIML-23 | 추론 임계값 선정 근거 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/08-ai-ml-data/model-evaluation.md` | `승인 대상 아님` |
| AIML-24 | 모델 배포·롤백 계획 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/08-ai-ml-data/model-operations.md` | `51aac03a4ac11145b7ca8b3704d3f586d940ab571d69f23e4c13bf087fd72bf8` |
| AIML-25 | 운영 성능·드리프트 모니터링 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/08-ai-ml-data/model-operations.md` | `승인 대상 아님` |
| AIML-26 | 재학습 기준·승인 절차 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/08-ai-ml-data/model-operations.md` | `3f27bb6bc0d15fa3a33aa8cbc3ccfa8b83bc6be5eb0c0abe5458944b045fbdf9` |
| REL-01 | 릴리스 계획 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/09-release/release-control.md` | `c1270ff8d42183d6625fa7e31d2a9544e2d241ce86740fcfb2b08191869b0155` |
| REL-02 | 릴리스 승인 체크리스트 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/09-release/release-control.md` | `137c2e12af20d0938f6b74a0626a8c6a6c470a838b6a8a1dc75d89e6ce658277` |
| REL-03 | 버전 설명서 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/09-release/release-control.md` | `승인 대상 아님` |
| REL-04 | 릴리스 manifest | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/09-release/release-control.md` | `승인 대상 아님` |
| REL-05 | 소스 커밋·태그 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/09-release/release-control.md` | `승인 대상 아님` |
| REL-06 | 실행 파일·컨테이너·모델·APK·PWA 산출물 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/09-release/release-control.md` | `승인 대상 아님` |
| REL-07 | 해시·서명 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/09-release/release-control.md` | `승인 대상 아님` |
| REL-08 | 릴리스별 SBOM·provenance | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/09-release/release-control.md` | `승인 대상 아님` |
| REL-09 | 릴리스 노트 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/09-release/release-control.md` | `승인 대상 아님` |
| REL-10 | 알려진 문제와 우회법 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/09-release/release-control.md` | `bd161da8edc27f4285d179105064dcd198b4eb0203bac923154500f7d21cb9e4` |
| REL-11 | 배포 절차 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/09-release/deployment-evidence.md` | `dc4b75c93a73b5ca1ef6ea075b0c15b6c0e6c1e8963339642eadcc0189035090` |
| REL-12 | DB·데이터 migration 절차 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/09-release/deployment-evidence.md` | `a90724744acaa7ab5eb00efa8e8da1e8301902c6d1ce3e098ec8c40f3aaa97ae` |
| REL-13 | 배포 후 smoke test | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/09-release/deployment-evidence.md` | `승인 대상 아님` |
| REL-14 | canary·단계적 배포 결과 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/09-release/deployment-evidence.md` | `승인 대상 아님` |
| REL-15 | 롤백 절차·실행 결과 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/09-release/deployment-evidence.md` | `승인 대상 아님` |
| REL-16 | 설치 설명서 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/09-release/delivery-and-handover.md` | `승인 대상 아님` |
| REL-17 | 사용자 설명서 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/09-release/delivery-and-handover.md` | `승인 대상 아님` |
| REL-18 | 관리자 설명서 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/09-release/delivery-and-handover.md` | `승인 대상 아님` |
| REL-19 | 교육·시연 자료 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/09-release/delivery-and-handover.md` | `승인 대상 아님` |
| REL-20 | 인수인계서 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/09-release/delivery-and-handover.md` | `승인 대상 아님` |
| REL-21 | 검수·승인 기록 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/09-release/delivery-and-handover.md` | `승인 대상 아님` |
| REL-22 | 오픈소스 라이선스 고지 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/09-release/delivery-and-handover.md` | `승인 대상 아님` |
| OPS-01 | SMP(Software Maintenance Plan) | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/10-operations/operator-guide.md` | `85cdcfb913ce92398c32d1b92fd1d475e6061bd8604f9abb4d6d61d7081539a3` |
| OPS-02 | 서비스 소유자·지원 책임자 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/10-operations/operator-guide.md` | `d97de05beefcec85965e4d61200c1228b0942ad0b7898aa0a0fb8dcef375e5ba` |
| OPS-03 | 지원시간·연락·에스컬레이션 체계 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/10-operations/operator-guide.md` | `bf266de81149ecb473f6cff780a9cb84d7422e4d944d80d2dc4ffa2b93fe472a` |
| OPS-04 | SLI·SLO·error budget | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/10-operations/operator-guide.md` | `5586b78fb703081713bf231a90d8382cca40ca6ebd5754109fc118da59950844` |
| OPS-05 | 로그·메트릭·트레이스 정의 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/10-operations/operator-guide.md` | `2f5d437d1f143a30bb7539e94b0d556a7be460e02fd11a29bf98730a947822ac` |
| OPS-06 | 운영 대시보드 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/10-operations/operator-guide.md` | `승인 대상 아님` |
| OPS-07 | 알림 기준 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/10-operations/operator-guide.md` | `승인 대상 아님` |
| OPS-08 | 장애별 runbook·playbook | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/10-operations/operator-guide.md` | `62183bfb829875edc3d03557d0391a7cbdef88e3cad1bddcfad286e0aca36ee2` |
| OPS-09 | 운영 점검표 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/10-operations/operator-guide.md` | `3bde621195c732d948b0c244060df410d926baf7680a41ac4cc8aca159e0fdcd` |
| OPS-10 | 백업 정책 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/10-operations/recovery-plan.md` | `5c172ee5d9b6927c917961eab154aaee44d809dba4bfeda177d34076809713ed` |
| OPS-11 | 복원 절차·실제 시험 결과 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/10-operations/recovery-plan.md` | `승인 대상 아님` |
| OPS-12 | RTO·RPO | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/10-operations/recovery-plan.md` | `0e55cee077c55e9cedb27ca43d4d6195c8d1be185ca531135bfada04bb51445e` |
| OPS-13 | 재해복구 계획·훈련 결과 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/10-operations/recovery-plan.md` | `승인 대상 아님` |
| OPS-14 | 접근권한 정기검토 | ACTIVE_OPENING_SNAPSHOT_CANDIDATE | DRAFT → ACTIVE | `docs/deliverables/10-operations/operations-control-registers.md` | `257512e4511cb6137ba856aef63ba5d0a174698f0f5acf3bba77e5c2015cf53e` |
| OPS-15 | 비밀정보·인증서 회전 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/10-operations/operations-control-registers.md` | `b15f024c1d8f78225b63fe39bc683da77322c3c99d88089f2dcf6e16e3366737` |
| OPS-16 | 패치·취약점 대응 절차 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/10-operations/operations-control-registers.md` | `228757495fbdb2896f79379c0690db3c4e55cd5cb13136419b6b55ddee81f2fb` |
| OPS-17 | 장애 기록 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/10-operations/operations-control-registers.md` | `승인 대상 아님` |
| OPS-18 | postmortem | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/10-operations/operations-control-registers.md` | `승인 대상 아님` |
| OPS-19 | 운영 변경이력 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/10-operations/operations-control-registers.md` | `승인 대상 아님` |
| OPS-20 | 유지보수 Backlog | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/10-operations/operations-control-registers.md` | `승인 대상 아님` |
| OPS-21 | 기술부채 목록 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/10-operations/operations-control-registers.md` | `승인 대상 아님` |
| OPS-22 | 용량·비용 관리 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/10-operations/operations-control-registers.md` | `승인 대상 아님` |
| OPS-23 | 데이터 보존·삭제 실행 기록 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/10-operations/operations-control-registers.md` | `승인 대상 아님` |
| OPS-24 | 외부 서비스·API 의존성 현황 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/10-operations/operations-control-registers.md` | `승인 대상 아님` |
| WS-01 | 보행약자 사용자 시나리오 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/11-walksafe/walksafe-safety-and-policy.md` | `c4fd1ff297a87cf79094f2dc7bb77c5cb927d39b5baca16faba8b0aaa3a16bea` |
| WS-02 | 위험정보 신고·검증·반영 흐름 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/11-walksafe/walksafe-safety-and-policy.md` | `f5a584947334076f1f726668066c64b32aecc219694f5345502652ffd57658bd` |
| WS-03 | 잘못된 위험정보에 대한 안전대책 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/11-walksafe/walksafe-safety-and-policy.md` | `cc814807be7a39e9cbd06c8d5d51175291aba6afeaa12df82afdcb5655d98106` |
| WS-04 | 카메라·위치·마이크 권한 흐름 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/11-walksafe/walksafe-safety-and-policy.md` | `3145b57b50fd85c4587bb88d1ea68b286961dc5eab1225226e8673e1705eef9c` |
| WS-05 | 위치·영상·음성 데이터 보존·삭제 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/11-walksafe/walksafe-safety-and-policy.md` | `76f54bb3551f36f1214f076ac589b360f52f49a0ef4a70f7cb9353d81b91fc43` |
| WS-06 | GPS 정확도·이탈·음영지역 시험 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/11-walksafe/walksafe-acceptance-matrix.md` | `승인 대상 아님` |
| WS-07 | 실제 경로 탐색 현장시험 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/11-walksafe/walksafe-acceptance-matrix.md` | `승인 대상 아님` |
| WS-08 | 위험물 탐지 오탐·미탐 안전분석 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/11-walksafe/walksafe-safety-and-policy.md` | `승인 대상 아님` |
| WS-09 | 기기별 카메라 추론 성능 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/11-walksafe/walksafe-acceptance-matrix.md` | `승인 대상 아님` |
| WS-10 | TFLite 변환 동등성 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/11-walksafe/walksafe-acceptance-matrix.md` | `승인 대상 아님` |
| WS-11 | STT 명령어·의도 매핑표 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/11-walksafe/walksafe-acceptance-matrix.md` | `abf50707b17de96305ef9555fc15420f01e549e1c9185369b43a4b18e8b10fe9` |
| WS-12 | 소음환경 STT 시험 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/11-walksafe/walksafe-acceptance-matrix.md` | `승인 대상 아님` |
| WS-13 | TTS·진동 안내 시험 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/11-walksafe/walksafe-acceptance-matrix.md` | `승인 대상 아님` |
| WS-14 | TalkBack·스크린리더 시험 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/11-walksafe/walksafe-acceptance-matrix.md` | `승인 대상 아님` |
| WS-15 | 색상·글자·터치영역 접근성 시험 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/11-walksafe/walksafe-acceptance-matrix.md` | `승인 대상 아님` |
| WS-16 | PWA 설치·오프라인·업데이트 시험 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/11-walksafe/walksafe-acceptance-matrix.md` | `승인 대상 아님` |
| WS-17 | 브라우저·OS·기기 호환성 매트릭스 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/11-walksafe/walksafe-acceptance-matrix.md` | `승인 대상 아님` |
| WS-18 | 지도·경로 API 장애·쿼터·대체경로 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/11-walksafe/walksafe-safety-and-policy.md` | `승인 대상 아님` |
| WS-19 | 사용자 신고 악용·중복·스팸 대응 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/11-walksafe/walksafe-safety-and-policy.md` | `589dac6ab9d0538b296475f2b4a977fedbb421b075b1c570a0741f048d370ee6` |
| WS-20 | 실제 휴대폰 E2E 영상·결과 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/11-walksafe/walksafe-acceptance-matrix.md` | `승인 대상 아님` |
| WS-21 | 현장시험 참여자 동의·안전계획 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/11-walksafe/field-test-safety-records.md` | `승인 대상 아님` |
| WS-22 | 안전상 제한사항·면책·사용자 고지 | VERSIONED_CONTENT_BASELINE_CANDIDATE | DRAFT → APPROVED_BASELINED | `docs/deliverables/11-walksafe/walksafe-safety-and-policy.md` | `4949c85ce2d2ab14eed66be348af137689df94ec1ead57f0910fa5660b3c9cea` |
| CLS-01 | 프로젝트 완료 확인 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/12-closure/project-closure.md` | `승인 대상 아님` |
| CLS-02 | 최종 인수·승인서 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/12-closure/project-closure.md` | `승인 대상 아님` |
| CLS-03 | PCR(Project Closure Report) | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/12-closure/project-closure.md` | `승인 대상 아님` |
| CLS-04 | 목표·KPI 달성 결과 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/12-closure/project-closure.md` | `승인 대상 아님` |
| CLS-05 | 최종 산출물 인덱스 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/12-closure/closure-handover-register.md` | `승인 대상 아님` |
| CLS-06 | 최종 소스·릴리스 아카이브 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/12-closure/closure-handover-register.md` | `승인 대상 아님` |
| CLS-07 | 미해결 결함 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/12-closure/closure-handover-register.md` | `승인 대상 아님` |
| CLS-08 | 잔여 위험 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/12-closure/closure-handover-register.md` | `승인 대상 아님` |
| CLS-09 | 기술부채 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/12-closure/closure-handover-register.md` | `승인 대상 아님` |
| CLS-10 | 운영 소유권·권한 이관 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/12-closure/closure-handover-register.md` | `승인 대상 아님` |
| CLS-11 | 회고·Lessons Learned | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/12-closure/project-closure.md` | `승인 대상 아님` |
| CLS-12 | 후속 개선계획 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/12-closure/project-closure.md` | `승인 대상 아님` |
| CLS-13 | 비용·계약·외부업체 종료 | PLANNED_NOT_RUN_NOT_APPROVED | PLANNED → PLANNED | `docs/deliverables/12-closure/decommissioning-plan.md` | `승인 대상 아님` |
| CLS-14 | 데이터 보존·이관·삭제 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/12-closure/decommissioning-plan.md` | `승인 대상 아님` |
| CLS-15 | 계정·키·인프라 정리 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/12-closure/decommissioning-plan.md` | `승인 대상 아님` |
| CLS-16 | 서비스 종료·폐기 계획 | EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED | DRAFT → DRAFT | `docs/deliverables/12-closure/decommissioning-plan.md` | `승인 대상 아님` |
