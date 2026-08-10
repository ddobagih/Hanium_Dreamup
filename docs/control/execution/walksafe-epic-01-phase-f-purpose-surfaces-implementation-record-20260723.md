# EPIC-01 Phase F 제품 목적·안전 한계 사용자 표면 정합화 기록

- 문서 ID: `WS-EPIC-01-PHASE-F-PURPOSE-SURFACES-IMPLEMENTATION-20260723-001`
- 버전: `0.1.0`
- 상태: `INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS`
- EPIC: `EPIC-01 IN_PROGRESS`
- 통제 구현 경로: **9개**

## 같은 의미로 맞춘 기준

목적: 워크세이프는 시각장애인의 도심 보행 중 가까운 위험과 이동 방향을 알려 주고 손상 점자블록 신고를 돕는 안드로이드 보행 보조 서비스입니다.

안전 한계: 워크세이프는 보행 안전을 보장하지 않으며 흰지팡이·안내견·보호자를 대신하지 않습니다.

## 내부 구현한 경계

- **SHARED_PURPOSE_AND_SAFETY_CONSTANTS** — 첫 화면과 신고 동의가 FP-001 목적문·안전 한계 공통 상수를 사용한다.
- **DAMAGED_TACTILE_BLOCK_REPORT_WORDING** — 사용자 신고 문구를 손상 점자블록으로 한정하고 모호한 일반 위험 신고 표현을 제거했다.
- **REL_17_DRAFT_USER_GUIDE** — 비전공자용 설명 후보를 작성했지만 REL-17은 DRAFT·NOT_APPROVED다.
- **REL_09_UNPUBLISHED_RELEASE_DESCRIPTION** — 릴리스 설명 후보를 작성했지만 REL-09는 PLANNED/NOT_RUN이고 게시하지 않았다.

## 주장하지 않는 것

REL-17은 Draft·미승인이고 REL-09는 Planned/NOT_RUN·미게시다. 실제 기기·대상 사용자 접근성·정식 시험·출시 완료를 주장하지 않는다.

## 공개 미결사항

- `EPIC-01-NO-DESTINATION-HAZARD-CONFORMANCE` / **OPEN_NEXT** — 목적지를 선택하지 않은 상태에서도 가까운 위험 안내가 작동하도록 길안내와 위험안내의 경로 의존 조건을 분리하고 검증한다.
- `PHASE-F-REL17-APPROVAL` / **NOT_APPROVED** — 사용자 설명서는 내부 Draft이며 정식 검토·승인되지 않았다.
- `PHASE-F-REL09-EXECUTION-PUBLICATION` / **NOT_RUN** — 릴리스 설명은 내부 후보이며 실행·게시되지 않았다.
- `PHASE-F-ACTUAL-DEVICE-AND-ACCESSIBILITY` / **NOT_RUN** — 실제 기기와 대상 사용자의 첫 화면·동의·접근성 이해 여부를 검증하지 않았다.
- `PHASE-F-FORMAL-TESTS` / **NOT_RUN** — 정식 시험 279개는 모두 미실행이다.

정식 시험 **279/279 NOT_RUN**, 5개 gate **NOT_RUN·미면제**, 출시는 **NOT_ELIGIBLE**이다.

## 다음 한 가지 작업

`EPIC-01-NO-DESTINATION-HAZARD-CONFORMANCE` — 목적지를 선택하지 않은 상태에서도 가까운 위험 안내가 작동하도록 길안내와 위험안내의 경로 의존 조건을 분리하고 검증한다.

내용 지문: `1c532bb993464d431856a441ff391850314b2f58aa2f10947c0a781dcd012f7c`
