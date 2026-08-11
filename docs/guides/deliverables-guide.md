# 산출물 가이드

이 문서는 WalkSafe 산출물을 **찾고 변경하는 절차**를 안내한다. 257개 목록이나 현재 상태를 다시 정의하지 않으며, 정책·산출물 정본을 대신하지 않는다.

## 먼저 읽는 순서

1. [DOC-01 현행 상태 안내](../deliverables/00-control/artifact-register-current-notice-20260728-r001.md)에서 현재 판정에 사용할 자료와 과대해석 금지 경계를 확인한다.
2. [DOC-01 산출물 관리대장](../deliverables/00-control/artifact-register.json)에서 대상 산출물의 ID, 정본 경로, 생명주기·신선도·검증 상태, 선후행 관계를 찾는다.
3. [DOC-05 변경 이력](../deliverables/00-control/artifact-change-log.json)에서 해당 산출물에 이미 적용된 변경을 확인한다.
4. 이름·버전·검토·승인 규칙은 [문서·산출물 통제 규정](../deliverables/00-control/document-control-manual.md)을 따른다.
5. 현재 실행 작업과 연결되는 변경이면 [프로젝트 재개 체크포인트](../control/walksafe-project-continuation-checkpoint.json)의 canonical binding과 변경 금지 경계를 추가로 확인한다.

`artifact-register.html`은 과거 승인 시점 화면이다. 현행 상태 판정에는 사용하지 않는다. 최신 수량이나 상태표가 필요하면 이 문서에 복사하지 말고 위 현행 안내와 DOC-01을 직접 조회한다.

## 작업 흐름

1. **대상 식별**: 산출물 코드와 DOC-01의 현재 정본 경로를 확인한다.
2. **변경 분류**: 오탈자, 내용 변경, 기준선 변경, 실행 사건, 외부 원본 수령 중 무엇인지 구분한다.
3. **영향 확인**: 상위 정책, 요구사항, 설계, 코드, 시험, 위험, 릴리스와 연결된 ID·경로를 찾는다.
4. **변경요청 기록**: 승인 의미·범위·경로가 달라지면 [변경요청 원장](../deliverables/01-management/registers/change-requests.json)에 통제된 방식으로 요청과 영향을 남긴다.
5. **새 revision 작성**: 승인본·기준선·실행 증거를 덮어쓰지 않고 새 Draft, successor 또는 append-only instance를 만든다.
6. **자동 확인**: JSON 구조, ID·경로·링크, 생성 재현성, 파일 SHA-256과 추적 관계를 검사한다.
7. **내용 검토**: 책임자가 실제 정책·코드·시험 사실과 문서 주장이 일치하는지 확인한다. 독립성이 필요한 검토는 작성자의 자기검토로 대체하지 않는다.
8. **승인**: 지정 승인자가 대상 버전·hash·범위·미해결 위험·효력 시각을 명시한 경우에만 승인 상태로 바꾼다.
9. **원장 반영**: DOC-01과 DOC-05를 같은 논리적 변경으로 갱신하고, 관련 register와 체크포인트 결속을 다시 검증한다.

자동검사 성공이나 파일 존재만으로 `Approved`, `Baselined`, 정식 시험 `PASS`, 배포 완료 또는 출시 가능 상태가 되지 않는다.

## 직접 편집 금지 경계

- 승인 기준선, 승인 receipt, 기존 revision, Goal event, gate·시험·배포 실행 증거처럼 immutable 또는 append-only로 관리되는 파일은 직접 고치지 않는다.
- checkpoint가 canonical binding으로 고정한 경로는 정식 transaction 없이 이동·이름 변경·삭제하지 않는다.
- DOC-01·DOC-05처럼 계속 갱신되는 정본도 생성기나 지정된 통제 절차를 우회해 손으로 수정하지 않는다.
- 자동 생성 보기는 입력과 생성기를 수정한 뒤 다시 만들며, 생성된 HTML·README만 고치지 않는다.
- 민감 원본, 서명 원본, 비밀값은 Git에 추가하지 않는다. 승인된 통제 저장소의 참조 ID와 필요한 최소 메타데이터만 연결한다.

[과거 DOC-01~CLS-16 bootstrap 생성기](../../scripts/build_walksafe_control_bootstrap.py)는 최초 257개 Draft를 재현하는 `HISTORICAL` 도구다. 현재 승인본·후속 revision과 bytes가 달라 `--check`의 불일치가 예상되며, 인자 없는 build를 현행 정본에 실행하면 안 된다. 변경 전 DOC-01의 현재 경로와 해당 산출물의 소유 successor 생성기·새 revision 절차를 확인한다.

## 관련 원장 찾기

| 확인할 내용 | 정본 또는 원장 |
|---|---|
| 요구사항 추적 | [RTM](../deliverables/03-requirements/rtm.json) |
| 설계 추적 | [설계 추적 원장](../deliverables/04-design/design-traceability-register.json) |
| 구현 모듈 | [모듈 원장](../deliverables/05-implementation/module-register.json) |
| 정식 시험계획 | [시험 케이스 원장](../deliverables/06-testing/registers/test-cases.json) |
| 취약점 | [취약점 원장](../deliverables/07-security/registers/vulnerabilities.json) |
| 릴리스 통제 | [릴리스 통제 원장](../deliverables/09-release/registers/release-control-register.json) |

원장을 갱신할 때는 해당 파일의 소유 생성기·절차와 DOC-01의 `canonical_path`를 먼저 확인한다. 위 링크는 탐색을 위한 것이며 직접 편집 권한을 부여하지 않는다.

## 검토 체크리스트

- 대상 산출물 ID와 current 정본 경로가 맞는가?
- 계획, Draft, 내부 검증, 정식 실행, 승인 상태를 서로 바꾸어 쓰지 않았는가?
- 상위 정책과 요구→설계→코드→시험 추적이 유지되는가?
- 이전 승인본과 실행 증거의 경로·bytes를 보존했는가?
- reviewer와 approver가 실제로 확인했으며 필요한 독립성을 충족하는가?
- DOC-01·DOC-05와 관련 register가 같은 변경을 가리키는가?
- 비밀값·개인정보·위치·영상·음성·서명 원본이 Git에 포함되지 않았는가?
