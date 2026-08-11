# WalkSafe 저장소 가이드

이 문서는 저장소를 탐색하고 새 파일을 올바른 위치에 두기 위한 안내입니다. 파일의 정책·승인·증거 효력을 정하는 정본이 아니며, 통제 자료의 경로·해시 계약이 우선합니다.

## 최상위 구조

| 경로 | 역할 | 안내 |
|---|---|---|
| `apps/` | Android 사용자·관리자 앱, Android Gateway, Legacy Web | [애플리케이션 안내](../../apps/README.md) |
| `backend/` | FastAPI, PostgreSQL/PostGIS, 서버 서비스 | [Backend 안내](../../backend/README.md) |
| `model/` | 모델 코드, registry, deployment manifest | [모델 안내](../../model/README.md) |
| `data_sources/` | 데이터 변환·검수 코드와 provenance | [데이터 소스 안내](../../data_sources/README.md) |
| `contracts/` | 생성·검증되는 API 계약 | [계약 안내](../../contracts/README.md) |
| `configs/` | 버전 관리되는 비밀값 없는 실행 계약 | [설정 안내](../../configs/README.md) |
| `deploy/` | 배포 예제와 운영 전 검토 경계 | [배포 안내](../../deploy/README.md) |
| `docs/` | 가이드, 통제 기록, 산출물, 시험·계획 자료 | [문서 지도](../README.md) |
| `scripts/` | 빌드·검사·생성·운영 보조 도구 | [스크립트 안내](../../scripts/README.md) |
| `tests/` | Python 회귀와 통제 검사 | [Python 테스트 안내](../../tests/README.md) |

Android 사용자 앱 구조는 [현재 Android 코드 지도](code/android-user.md), 관리자 앱은 [관리자 앱 안내](../../apps/android/adminapp/README.md), Gateway 구조는 [현재 Gateway 코드 지도](code/android-gateway.md)를 따릅니다. hash로 결속된 Android·Gateway README는 과거 snapshot으로만 봅니다.

## 분류 기준

저장소 현대화에서 사용하는 분류와 처리 원칙은 [현대화 계획](../planning/repository-modernization-20260811/README.md)에 기록합니다.

| 분류 | 저장소에서의 의미 |
|---|---|
| `CURRENT_PRODUCT` | Android 사용자·관리자 제품 코드 |
| `SUPPORT` | Gateway, Backend, 모델·데이터, 계약·배포, 개발 도구 |
| `GOVERNANCE` | 현재 가이드, 정책, 계획, CI와 작업 규칙 |
| `EVIDENCE` | 산출물, 체크포인트, 시험·실행 증거, 작업 기록 |
| `GENERATED` | 정해진 생성기로 다시 만들 수 있는 카탈로그·보고서 |
| `LEGACY_REFERENCE` | 현재 제품 판단에 쓰지 않는 과거 코드·문서 |

분류는 완료 수준이 아닙니다. 예를 들어 `CURRENT_PRODUCT`는 현재 제품 경계라는 뜻이지 출시 준비 완료라는 뜻이 아닙니다.

제품 경계 설정의 Web/PWA 값 `LEGACY_REFERENCE_ONLY`는 이 저장소 카탈로그의 `LEGACY_REFERENCE`에 대응합니다. 전자는 제품 포함 여부, 후자는 파일 보존·이동 상태를 나타내는 서로 다른 필드입니다.

캐시, 임시 clone, 비공개 bundle과 원본 민감자료는 Git 경로 카탈로그의 입력에서 제외하는 `LOCAL_ONLY` 정책 대상이며, 저장소 파일 상태 분류로 사용하지 않습니다.

## 새 파일 배치

- 제품 코드는 소유 모듈 아래에 두고 해당 모듈 테스트와 함께 변경합니다.
- 공용 API 형식은 독립 복제하지 않고 [계약 안내](../../contracts/README.md)의 생성·검증 흐름을 사용합니다.
- 비밀값 없는 실행 설정은 `configs/`, 배포 예제는 `deploy/`에 둡니다.
- 반복 실행 도구는 `scripts/`, Python 회귀는 `tests/`, 모듈 전용 테스트는 모듈 내부 기존 위치에 둡니다.
- 승인·상태 관리가 필요한 산출물은 [산출물 안내](../deliverables/README.md)와 관리대장 절차를 먼저 확인합니다.
- 팀 탐색·절차 문서는 `docs/guides/`, 기간이 있는 정비 계획은 `docs/planning/`에 둡니다.
- 프로젝트 변경 기록은 기존 형식에 맞춰 `daylog/YYYY-MM-DD.md`에 통합합니다.

새 최상위 폴더는 기존 책임으로 표현할 수 없을 때만 추가합니다.

## 통제·증거 경계

`docs/control/`과 `docs/deliverables/`에는 경로와 SHA-256으로 결속된 파일이 많습니다. 보기 좋게 만들기 위한 이동·이름 변경·재생성만으로도 체크포인트와 Goal history가 깨질 수 있습니다.

- 승인된 기준선, 불변 영수증, 과거 Goal 문서를 직접 덮어쓰지 않습니다.
- 통제 파일 변경 전 저장소 [`AGENTS.md`](../../AGENTS.md), 현재 [프로젝트 체크포인트](../control/walksafe-project-continuation-checkpoint.json)와 checkpoint가 가리키는 focus Goal 계약을 확인합니다. hash로 봉인된 과거 재개 안내서의 gate 명령을 현행으로 실행하지 않습니다.
- 파일 이동 전 코드·문서·체크포인트·생성기·테스트의 참조를 모두 조사합니다.
- 생성 파일은 생성기와 입력을 함께 유지하고, 수작업 수정이 허용되는지 확인합니다.
- 실행하지 않은 시험이나 배포를 문서 상태만 바꿔 완료 처리하지 않습니다.

## 레거시 경계

- [Legacy Web 안내](../../apps/web/README.md)의 Web/PWA는 `LEGACY_REFERENCE`입니다. 현재 사용자 제품, Android 완료 증거, 출시 후보로 사용하지 않습니다.
- [로컬 Voice 안내](../../voice/README.md)의 서버는 Android 제품 음성 경로와 분리된 프로토타입입니다.
- [AI 작업 패키지 안내](../../ai_tasks/README.md)의 과거 검수 패키지는 현재 정책이나 모델 승인을 자동으로 만들지 않습니다.
- 날짜가 붙은 과거 계획·보고서는 당시 상태의 증거일 수 있으므로 최신 상태 안내로 재사용하지 않습니다.

레거시는 삭제의 동의어가 아닙니다. 현대화 전 전체 상태는 [복구 지점 기록](../planning/repository-modernization-20260811/preservation-record.md)에 보존되어 있습니다. 레거시 이동은 복구 지점, 참조 감사, 이동 manifest, 검증이 모두 있을 때만 수행합니다. 경로·해시 결속 때문에 이동할 수 없는 자료는 원래 위치에 두고 레거시 표식을 추가합니다.

## Git에 두지 않는 자료

- 비밀번호, API key, token, keystore, 운영 인증서와 실제 환경 파일
- 사용자 영상·음성·정확 위치, 원본 운영 로그와 개인정보
- 원본 데이터셋, 대량 학습 중간물, 임시 빌드·캐시·가상환경
- 사용자 전용 복구 bundle과 제한 권한 임시 clone

모델·데이터 파일은 일반 규칙만으로 판단하지 말고 [모델 안내](../../model/README.md), [데이터 소스 안내](../../data_sources/README.md), 현재 `.gitignore`와 등록된 예외를 함께 확인합니다.

## 이동·정리 체크리스트

1. 대상의 분류와 현재 사용자를 확인합니다.
2. 정적 링크뿐 아니라 코드 문자열, 생성기, 테스트, 체크포인트의 경로 결속을 찾습니다.
3. [복구 지점 기록](../planning/repository-modernization-20260811/preservation-record.md)이 유효한지 확인합니다.
4. 이동 목록과 복원 위치를 먼저 기록합니다.
5. 필요한 최소 경로만 이동하고 기존 참조를 갱신합니다.
6. 관련 빌드·테스트·문서 링크·continuation 검사를 다시 실행합니다.

캐시 정리나 레거시 격리를 이유로 `git clean`, 강제 reset, 광범위 삭제를 실행하지 않습니다.
