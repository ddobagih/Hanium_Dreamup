# 보안·개인정보 가이드

이 문서는 저장소 작업자가 따라야 할 최소 보안·개인정보 수칙을 정리한 탐색 가이드다. 상세 정책과 현재 산출물 상태는 [보안·개인정보 계획](../deliverables/07-security/security-and-privacy-plan.md)과 [보안 대응·모니터링](../deliverables/07-security/security-response-and-monitoring.md)을 정본으로 확인한다.

## 절대 Git에 넣지 않는 것

- 비밀번호, API key, access·refresh token, private key, 인증서 비밀값, TOTP seed, 관리자 credential issuer key, 복구코드
- 사용자 실명·연락처·계정 식별정보 등 불필요한 개인정보
- 정확 위치, 원본 영상·음성, 얼굴·번호판·목소리, 서명 원본
- 운영 DB dump, 실제 운영 로그, 비공개 취약점 재현자료와 외부 저장소의 실제 접근정보

로컬 `.env`와 비밀 저장소를 사용하고, 예제 설정에는 무효한 placeholder만 둔다. 비밀값을 issue, PR, 채팅, 스크린샷, 테스트 fixture나 로그에 복사하지 않는다. 노출이 의심되면 값을 다시 숨기는 것만으로 끝내지 말고 즉시 폐기·회전, 관련 session 무효화, 영향 범위 확인과 재검사를 수행한다.

## 개인정보·위치·영상 처리

1. 승인된 목적과 동의 범위에 필요한 필드만 처리한다.
2. 수집 전에 목적, 데이터 항목, 접근 주체, 보존·삭제 조건을 확인한다. 미승인 값은 추정하지 않고 blocker로 둔다.
3. 테스트는 가능한 한 합성·비식별 자료를 사용한다. 실사용 원본은 승인된 격리 환경과 접근권한이 준비된 경우에만 사용한다.
4. 민감 원본은 Git 밖의 암호화된 통제 저장소에 둔다. Git에는 참조 ID, SHA-256, 접근등급, 목적, 보존·삭제 상태처럼 필요한 최소 메타데이터만 남긴다.
5. 로그와 분석자료에는 가명 ID, 상태, 시각, correlation ID만 우선 사용하고 정확 위치·원본 내용·인증정보를 기록하지 않는다.
6. 목적이 끝난 복제본과 export는 승인된 삭제 절차와 receipt 없이 임의로 보유하지 않는다.

데이터 항목·보존·삭제의 상세 기준은 [데이터 관리 산출물](../deliverables/08-ai-ml-data/data-management.md)과 보안·개인정보 계획의 SEC-05~09를 따른다.

## 취약점 발견·신고

공개 issue에 취약점 세부정보, 개인정보 또는 비밀값을 올리지 않는다.

1. 영향을 받는 component와 version, 발견 시각, 영향, 비파괴 재현 절차와 증거 hash를 최소 범위로 정리한다.
2. 원본 비밀값·개인정보는 제거하고 필요한 경우 접근 통제된 위치의 참조 ID만 남긴다.
3. 현재 프로젝트의 비공개 연락 채널로 보안·개인정보책임자에게 전달한다.
4. [취약점 원장](../deliverables/07-security/registers/vulnerabilities.json)에는 지정된 통제 절차로 triage·소유자·조치·재시험 상태를 연결한다.
5. 실제 유출·계정 탈취·안전기능 변조 가능성이 있으면 [사고 대응 절차](../deliverables/07-security/security-response-and-monitoring.md#sec-17)로 즉시 격상한다.

외부 취약점 접수 채널은 아직 활성화되지 않았다. 공개 beta 전에 [SEC-18 취약점 신고 정책](../deliverables/07-security/security-response-and-monitoring.md#sec-18)의 채널·담당자·비밀신고 방법과 왕복 시험을 실제로 준비해야 하며, 준비 전에는 공개 접수 창구가 운영 중이라고 안내하지 않는다.

## 개발·검토 최소선

- 입력 검증, 인증·인가, 최소 권한, 전송·저장 암호화와 실패 시 안전정지를 설계에서 확인한다.
- 사용자 앱과 관리자 앱의 인증·권한을 분리하고 공용 비밀번호나 숨은 우회 계정을 만들지 않는다.
- 코드 통합 전에 관련 단위·통합검사와 secret·dependency·정적 검사를 실행한다.
- `current`의 quality CI는 checksum으로 고정한 Gitleaks로 크기 제한 없이 checkout의 현재 파일을 검사한다. 보고서 전체 finding 객체를 정렬한 SHA-256과 건수를 검토 정책에 exact 결속하므로 값·경로·줄·규칙·건수 중 하나라도 달라지면 실패한다. 과거 line fingerprint baseline은 CI 예외로 사용하지 않으며, 변경된 탐지는 정책 hash를 별도 검토 없이 갱신해 승인하지 않는다.
- 결과는 대상 commit·artifact·도구·ruleset·시각과 함께 기록한다. 자동검사 통과를 침투시험, 독립 개인정보 검토나 출시 승인으로 바꾸어 쓰지 않는다.
- 발견사항은 영향과 재현 가능성을 근거로 분류하고, 같은 대상의 수정·재시험 증거가 있을 때만 닫는다.

보안 검증 산출물과 상태는 [보안 검증 증거](../deliverables/07-security/security-verification-evidence.md)와 [보안 검증 원장](../deliverables/07-security/registers/security-verification-evidence.json)에서 확인한다.

## 운영 권한 경계

저장소 안에서 승인된 정책을 구현하고 내부 검증 자료를 만드는 일과, 실제 운영 권한을 행사하는 일은 다르다.

- production 비밀 발급·회전, 클라우드·DB 권한 변경, 실제 배포·복귀, 실제 개인정보 삭제, 외부 통지에는 해당 권한자의 명시적 승인과 실행 기록이 필요하다.
- 최소 권한과 개인별 계정을 사용하고, 관리자·배포·감사 권한을 가능한 범위에서 분리한다. 공유 계정·공용 비밀번호를 사용하지 않는다.
- Backend API는 `walksafe-backend`와 `/etc/walksafe/backend-runtime.env`, Alembic은 `walksafe-maintenance`와 `/etc/walksafe/backend-migration.env`를 사용한다. 두 환경파일은 regular file, `root:root`, `0600`, hard-link count 1이며 symlink가 아니어야 한다. systemd가 권한 강하 전에 읽고 각 서비스 namespace에서 상대 환경과 legacy `/etc/walksafe/backend.env`를 가린다. API 환경에 migration DB URL을 넣거나 두 서비스 계정이 같은 환경파일을 읽게 하지 않는다.
- 독립 검토가 필요한 개인정보·보안·안전 판정, 5개 release gate와 출시 승인은 작성자나 자동화의 자기확인으로 대체하지 않는다.
- 실제 연락처, 복구코드와 운영 endpoint는 공개 문서에 쓰지 않고 통제된 참조 ID로 관리한다.
- 관리자 credential issuer key는 `DATABASE_URL`·TOTP seed가 있는 일반 backend 환경파일과 분리한 root 소유 파일에서만 공급한다. 값은 정확히 256-bit canonical unpadded Base64url이며 TOTP·복구자료·gateway session·privacy HMAC·보고서 이미지·서명키와 재사용하지 않는다. 배포 파일과 상위 디렉터리의 소유권·mode·link·identity 검사가 하나라도 실패하면 발급을 중단하고, 키 값과 경로는 로그·오류·readiness 응답에 노출하지 않는다.
- issuer key의 DB 결속은 신규 PostgreSQL provisioning transaction 또는 maintenance window의 `bind_walksafe_admin_credential_issuer_key.py` owner 절차로만 수행한다. 기존 control은 migration 직후 의도적으로 미결속되어 startup/readiness와 credential mutation이 실패한다. runtime 역할의 결속, 기존 지문의 다른 키 교체, 일반 환경파일에 key 원문 저장은 금지한다.
- 권한 상실이나 관리자 기기 분실 때 우회 권한을 만들지 않는다. session을 폐기하고 고위험 작업을 동결한 뒤 승인된 복구 절차를 따른다.
- 관리자 복구코드·보안키·백업 암호문 같은 원 복구자료는 휴대전화 밖의 승인된 암호화 보관소에 두고 앱·서버 DB·Git에는 넣지 않는다. 앱은 요청용 opaque 참조 원문을 지속 저장하지 않고, 서버 DB에는 그 SHA-256과 자료 종류·`OFF_PHONE`·확인 시각·별도 암호화 백업 여부 같은 최소 metadata만 남긴다.
- 장치 증명이 켜진 복구는 시작 전에 대상 기기의 active 신뢰 키가 정확히 하나 있어야 한다. 시작 시 그 키만 보존하고 다른 active device key와 기존 session을 폐기하며 custody 확인을 초기화한다. `RECOVERY_REQUIRED`에서는 신뢰할 수 있는 로컬 절차가 시작 전 대상 키를 bootstrap할 수 있고, `RECOVERY_IN_PROGRESS`에서는 active transaction의 같은 대상 기기만 키를 등록·회전할 수 있다. 보존된 대상 키의 request-bound proof로만 완료하며, 새 custody 확인 전까지 출시 승인·권한 변경·데이터 삭제를 동결한다. offline gate의 단일사용 재확인 nonce는 실제 변경 전에 commit하는 attempt-based 계약이므로 이후 작업 실패를 이유로 재사용하지 않는다.
- 분실 신고는 현재 호출 기기를 대상으로 삼지 않는다. 별도 현재 기기에서 선택한 대상 기기의 key와 session만 같은 transaction에서 폐기하고 감사하며, 해당 key의 challenge·로그인 재사용을 거부한다.
- custody `ATTESTED` 자기확인은 저장소 내부 fail-closed gate 입력일 뿐 실제 보관소 점검, 새 기기 복구, 휴대전화 분실 복구훈련 또는 독립 모델·법률·보안·접근성 검토를 대체하지 않는다. 이 실제 활동과 279개 정식 시험·5개 gate는 증거가 생기기 전까지 모두 `NOT_RUN`(미면제), 출시는 `NOT_ELIGIBLE`로 유지한다.

### API·migration·issuer 운영 전환

새 Android 관리자 앱은 구 backend 응답을 제한적으로 수용하지만 구 APK는 새 확장 응답과 호환되지 않는다. 단일 관리자 소유의 모든 active 관리자 기기를 먼저 새 APK로 갱신·확인하고, 갱신하지 못한 기기의 session과 device key를 폐기한 뒤에만 backend를 전환한다.

1. `walksafe-backend`는 별도로 확인하고, `systemd-sysusers`로 로그인 불가 `walksafe-maintenance`와 `walksafe-issuer-bind`를 만든다. 세 계정은 서로 다른 UID·primary GID를 사용하며 상호 역할 그룹에 가입하지 않는다.
2. 두 env를 `root:root 0600`, issuer 원본을 정확히 `root:walksafe-backend 0440`으로 설치한다. 세 파일은 regular file·hard-link count 1·symlink 아님을 확인하고, issuer 상위 경로도 root 소유·group/other 쓰기 불가인지 확인한다.
3. ingress를 차단하고 API를 명시적으로 정지한다. 이전 통합 env가 노출한 migration credential은 재사용하지 않고 maintenance 전용 값으로 회전한다.
4. `walksafe-backend-migrate.service`를 성공시킨 다음, enable하지 않은 `walksafe-admin-issuer-bind.service`를 비권한 `walksafe-issuer-bind`로 수동 실행한다. Migration 후 bind 전에는 새 backend의 startup/readiness가 fail-closed하는 것이 정상이다. Backend 시작은 bind를 자동 실행하지 않으므로 반드시 `migrate → bind → backend` 순서를 지킨다.
5. bind 뒤 migration credential을 다시 회전·폐기하고 기존 `/etc/walksafe/backend.env`를 제거한다. issuer 원본은 API startup·readiness·발급에 계속 필요하므로 제거하지 않는다.
6. 설치된 파일 owner·mode·link, unit의 root 소유·비쓰기 가능 fragment, 세 계정의 UID·primary GID·그룹 분리를 다시 확인한다. API를 시작해 `/ready`의 TOTP·issuer 결속과 같은 후보 smoke가 성공한 뒤에만 ingress를 연다.

롤백도 maintenance 계정과 migration env에서만 수행한다. migration credential을 runtime env·API·복원한 legacy env에 넣지 않으며, DB 호환성과 권한 분리를 입증할 수 없으면 API와 ingress를 정지한 fail-closed 상태를 유지한다. 명령과 상세 검사항목은 [Backend 운영 전환 절차](../../backend/README.md#운영-권한-분리계약-호환-전환)를 따른다.

운영 역할·사고·복구의 상세 절차는 [운영자 가이드](../deliverables/10-operations/operator-guide.md)와 [복구 계획](../deliverables/10-operations/recovery-plan.md)을 따른다.
