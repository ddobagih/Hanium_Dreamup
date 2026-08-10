# WalkSafe 라이선스·고지 파일

> 포함 산출물: DEV-17  
> 버전: 0.1.0 · 상태: Draft · 승인: 미승인  
> 정책 기준선: WalkSafe 기능 정책 1.0.0  
> 검증: 아직 실행하지 않음 · 출시: NOT_ELIGIBLE

## 이 문서가 답하는 질문

현재 제품 후보에 포함된 코드·모델·의존성의 권리와 사용자 고지를 어떻게 확인할 것인가?

## 쉬운 요약

이 문서는 확정된 기능 정책을 개발과 시험에 옮기는 첫 초안입니다. 저장소에 코드나 시험 파일이 있다는 사실만으로 기능이 완성됐거나 시험에 합격한 것은 아닙니다. 같은 코드·앱·모델·설정 묶음으로 다시 확인해야 합니다. 출시 전에 반드시 끝내야 할 검증 5개도 남아 있습니다.


## 현재 판정

라이선스 원문과 의존성 버전 고정 파일(lock 파일) 후보는 존재하지만, 정식 Android 사용자 앱·Android 관리자 앱·서버·모델을 한 번에 함께 출시할 버전 묶음(release generation)으로 연결한 최종 고지 검토는 아직 실행하지 않았습니다. 따라서 이 문서는 `Draft`, 공급망 라이선스 검증은 `NOT_RUN`입니다.

## 확인 대상

- `apps/android/app/gradle.lockfile` — 범위: `IMPLEMENTATION_CANDIDATE`
- `apps/web/package-lock.json` — 범위: `LEGACY_REFERENCE_ONLY`
- `apps/web/quality-requirements.lock` — 범위: `LEGACY_REFERENCE_ONLY`
- `backend/requirements.lock` — 범위: `IMPLEMENTATION_CANDIDATE`
- `configs/submission_exact8_requirements.lock` — 범위: `SUBMISSION_TOOL_CANDIDATE`
- `configs/submission_installer_requirements.lock` — 범위: `SUBMISSION_TOOL_CANDIDATE`
- `configs/submission_toolchain_lock_20260713.json` — 범위: `SUBMISSION_TOOL_CANDIDATE`
- `configs/walksafe_node_toolchain_lock_20260715.json` — 범위: `MIXED_SCOPE_REVALIDATION_REQUIRED`
- `tests/general-quality-cp312-linux-x86_64-cpu.lock` — 범위: `TEST_ASSET_CANDIDATE`
- `tests/requirements.lock` — 범위: `TEST_ASSET_CANDIDATE`
- `tests/test_walksafe_node_toolchain_lock.py` — 범위: `TEST_ASSET_CANDIDATE`
- `voice/quality-requirements.lock` — 범위: `IMPLEMENTATION_CANDIDATE`
- `voice/requirements.lock` — 범위: `IMPLEMENTATION_CANDIDATE`

추가로 Android Gradle 의존성, Python 의존성, 모델 파일·학습데이터 출처, 아이콘·음성·지도 SDK, TMAP 약관, 복사한 코드와 생성 도구를 확인해야 합니다.

## 승인 전 완료조건

- 각 항목의 이름, 버전, 출처, 라이선스 식별자, 원문 위치, 수정 여부, 배포 방식 기록
- source·binary·model·dataset·외부 SDK의 서로 다른 의무 분리
- notice·소스 제공·표시·재배포 제한과 개인정보 약관 반영
- 정확한 출시 파일 목록·지문 기록(release manifest)과 소프트웨어 구성품 목록(SBOM)에 연결
- 불명확하거나 충돌하는 권리는 출시에서 제외하거나 권리자로부터 허락 확보

## 현재 금지

- 의존성 버전 고정 파일(lock 파일)이 있다는 이유만으로 재배포 권리가 확인됐다고 표시하지 않는다.
- 모델 파일과 학습데이터의 라이선스를 같은 것으로 가정하지 않는다.
- 과거 Web/PWA 의존성을 Android 정식 고지에 무조건 포함하지 않는다.

## 이번 버전 변경점

- DEV-17 정식 초안을 개설하고 최종 판정을 `PENDING_REVIEW`로 유지했다.
