# WalkSafe 중앙 가이드

이 디렉터리는 팀원이 프로젝트의 실제 정본과 실행 절차를 빠르게 찾도록 돕는 탐색·절차 계층입니다. 정책, 승인, 현재 작업, 시험 결과를 새로 정의하는 정본이 아닙니다.

## 문서 효력

가이드와 실제 기록이 다르면 다음 순서로 확인합니다.

1. 승인 정책은 [정책 기준선 1.0.1](../control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json)을 따릅니다.
2. 현재 작업과 다음 행동은 [프로젝트 체크포인트](../control/walksafe-project-continuation-checkpoint.json)를 따릅니다. hash로 봉인된 [과거 재개 안내서](../control/walksafe-project-resumption-runbook.md)의 상태·focus·활성화 문구와 v2.3·전환 준비 시점 v2.4 gate 명령은 현행 지시로 실행하지 않습니다.
3. 산출물 상태는 [current notice](../deliverables/00-control/artifact-register-current-notice-20260728-r001.md)에서 연결하는 [산출물 관리대장](../deliverables/00-control/artifact-register.json)과 [변경이력](../deliverables/00-control/artifact-change-log.json)을 따릅니다.
4. 코드별 실행·설정은 실제 source, OpenAPI, lock, runtime config를 따릅니다. 날짜가 있거나 hash로 결속된 모듈 README가 이 계약과 충돌하면 역사 snapshot으로 봅니다.

중앙 가이드가 위 자료의 값을 복제해 새 기준으로 사용되어서는 안 됩니다.

## 시작 안내

| 알고 싶은 내용 | 먼저 볼 문서 |
|---|---|
| 프로젝트 목적, 제품 경계, 현재 상태 | [프로젝트 가이드](project-guide.md) |
| 폴더 역할, 파일 배치, 보존 기준 | [저장소 가이드](repository-guide.md) |
| 코드 구조와 요청 흐름 | [코드 가이드](code-guide.md) |
| 산출물 작성·변경·승인 | [산출물 가이드](deliverables-guide.md) |
| 자동 테스트와 정식 시험 | [테스트 가이드](testing-guide.md) |
| 스크립트 상태와 부작용 | [스크립트 가이드](scripts-guide.md) |
| 모델·데이터 반입과 provenance | [데이터·AI 가이드](data-ai-guide.md) |
| 보안·개인정보 처리 | [보안·개인정보 가이드](security-privacy-guide.md) |
| 릴리스·운영·복구 | [릴리스·운영 가이드](release-operations-guide.md) |
| 기능 분담, 브랜치, 검토, 인수인계 | [팀 작업 흐름](team-workflow.md) |
| 기여·PR / 보안 신고 | [CONTRIBUTING](../../CONTRIBUTING.md) · [SECURITY](../../SECURITY.md) |
| 개발 도구, 로컬 실행, 기본 검사 | [개발 환경 가이드](development-environment-guide.md) |
| 전체 기능과 세부 기능 분담 | [기능 구현·분담 목록](../planning/walksafe_feature_implementation_catalog.html) |

## 분야별 실제 안내

- 애플리케이션 경계: [apps 안내](../../apps/README.md)
- Android 사용자 앱: [현재 코드 지도](code/android-user.md) · [결속된 과거 Android README](../../apps/android/README.md)
- Android 관리자 앱: [관리자 앱 안내](../../apps/android/adminapp/README.md)
- Android Gateway: [현재 코드 지도](code/android-gateway.md) · [결속된 과거 Gateway README](../../apps/android-gateway/README.md)
- Backend: [Backend 안내](../../backend/README.md)
- 모델과 데이터: [모델 안내](../../model/README.md), [데이터 소스 안내](../../data_sources/README.md)
- API 계약과 설정: [계약 안내](../../contracts/README.md), [설정 안내](../../configs/README.md)
- 산출물: [산출물 가이드](deliverables-guide.md), [산출물 current notice](../deliverables/00-control/artifact-register-current-notice-20260728-r001.md)
- 테스트: [테스트 가이드](testing-guide.md), [Python 테스트 안내](../../tests/README.md)
- 스크립트와 배포: [스크립트 가이드](scripts-guide.md), [배포 안내](../../deploy/README.md)

## 현재 판정 경계

저장소 내부 구현과 자동 검사가 존재해도 정식 검증 완료를 뜻하지 않습니다. 예정된 정식 시험은 `279/279 NOT_RUN`, 출시 gate 5개는 모두 `NOT_RUN`·미면제이며 출시는 `NOT_ELIGIBLE`입니다. 실제 시험과 승인 근거 없이 이 상태를 PASS나 출시 가능으로 바꾸지 않습니다.

## 가이드 갱신 규칙

- 중앙 가이드에는 탐색 경로와 반복 가능한 절차만 둡니다.
- 정책값, 현재 목표, 시험 수치처럼 바뀌는 값은 정본을 링크하고 필요한 최소 경계만 요약합니다.
- 링크한 경로를 옮길 때는 참조 검사와 통제 파일 결속을 먼저 확인합니다.
- 새 세부사항은 source 가까운 README에 두되, hash로 봉인돼 직접 고칠 수 없는 과거 README는 중앙 코드 지도에서 현행 계약과 차이를 명시합니다.
- 실행하지 않은 시험, 배포, 외부 검토를 문서 작성만으로 완료 처리하지 않습니다.
