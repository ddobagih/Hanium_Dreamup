# 배포 파일 분류

현재 제품은 일반 사용자용 Android 앱과 별도 Android 관리자 앱입니다. 이 디렉터리의 서버·데이터베이스 자료는 향후 Android 제품 운영 설계의 입력 후보지만, 다음 세 Web 파일은 과거 RC를 재현하기 위한 `LEGACY_REFERENCE_ONLY` 자료입니다.

- `systemd/walksafe-web.service`
- `nginx/walksafe-web.conf.example`
- `config/walksafe-web.env.example`

세 파일은 Web UI/PWA와 Next API route를 하나의 런타임으로 공개하던 과거 구조이므로 설치·enable·start·nginx reload 절차에 사용하면 안 됩니다. 현재 파일은 활성 systemd 섹션, nginx 지시문, 환경변수 할당이 하나도 없는 주석 전용 stub입니다. 파일 안의 `production`, `field`, `release` 명칭은 과거 설명이며 현재 승인 상태가 아닙니다.

Phase D 기준 Android 사용자 앱은 `/api/field-session`, `/api/navigation/walking`, `/api/navigation/destinations/search`, `/api/reports/v2` Next route를 전환형 BFF로 사용했습니다. Phase E는 이 네 경로를 공개 Web UI/PWA와 무관한 독립 Android API Gateway로 옮기며, 아래 예제는 그 목표 배치 계약만 표현합니다. 예제 파일의 존재는 추출·설치·외부 배포 완료 증거가 아니며 과거 Web service를 대체 경로로 재활성화하지 않습니다.

현재 외부 Web 실행기는 fail-closed이고 출시는 `NOT_ELIGIBLE`입니다. 새 배포 경로를 만들 때는 승인된 Android 제품 경계, 별도 사용자·관리자 앱 식별자와 서명, 불변 앱·서버·모델·설정 묶음, 5개 미실행 gate를 기준으로 새 IaC와 검증 증거를 작성해야 합니다.

## 독립 Android Gateway 배포 예제

다음 세 파일은 Phase E의 목표 배치 계약을 검토하기 위한 `DRAFT_DEPLOYMENT_EXAMPLE / NOT_APPLIED` 자료입니다.

- `config/walksafe-android-gateway.env.example`
- `systemd/walksafe-android-gateway.service`
- `nginx/walksafe-android-gateway.conf.example`

예제의 잠긴 Node v22.23.1 서비스는 `/srv/walksafe/apps/android-gateway/dist/server.js`를 전용 비권한 계정으로 한 프로세스만 실행하고 `127.0.0.1:8081`에만 결속합니다. upstream은 loopback의 보호 Backend와 명시적으로 활성화한 Voice뿐이며 Next/Web fallback은 없습니다. Voice token은 Gateway 환경에만 두고 APK에는 넣지 않습니다. nginx 예제는 TLS 뒤에서 열거한 Android API 경로와 로그인 없는 `/privacy/rights` 안내 화면만 `127.0.0.1:8081`로 전달하고 나머지 경로를 차단합니다. STT/TTS는 각각 10 MiB/2 KiB ingress 상한과 40초/70초 upstream 상한을 둡니다. 계정 삭제는 exact 요청 경로와 16~128자 request ID를 제한하는 두 anchored 정규식 경로만 열며, 요청·상태·기기증거 본문 상한은 각각 4 KiB·1 KiB·16 KiB입니다. 목적지 검색어와 정확 좌표가 query string에 있으므로 기본 combined access log 상속을 끄며, 실제 배포 검토에서 필요하면 query를 제외한 `$uri` 기반 최소 형식과 보존기간을 별도로 승인해야 합니다.

이 예제는 실제 계정·디렉터리·Node 22·빌드·환경파일·인증서가 준비됐다는 뜻이 아닙니다. 별도 배포 검토 전에는 파일을 `/etc`에 복사하거나 systemd unit을 설치·enable·start하거나 nginx 설정을 적용·reload하지 않습니다. 향후 적용한다면 환경파일은 `root:root`와 `0600`, rate-limit 상태 디렉터리는 서비스 계정 전용 `0700`을 확인하고, 실제 비밀값·도메인·인증서는 Git 밖에서 프로비저닝해야 합니다.

## Backend와 backup 계정 분리 예제

`sysusers.d/walksafe-backend.conf`, `tmpfiles.d/walksafe-backend.conf`와 네 Backend 운영 unit도 아직 `DRAFT_DEPLOYMENT_EXAMPLE / NOT_APPLIED`입니다. 이 계약은 API와 backup을 같은 Unix 계정으로 실행하지 않습니다. API는 `walksafe-backend`, backup은 `walksafe-backup`으로 실행하며, backup은 `walksafe-backup-readers` 그룹을 통해 암호화된 flat `.wse` 객체만 읽습니다. API·삭제 worker·retention·backup은 root가 미리 만든 `/run/walksafe-maintenance-lock/maintenance.lock` 하나를 함께 잠급니다.

기존 upload 자료가 있는 host에서는 다음 순서를 모두 정지된 서비스 상태에서 수행해야 합니다.

1. Backend, 삭제 worker, retention, backup이 모두 멈췄고 lock 보유자가 없음을 확인한다.
2. sysusers·tmpfiles를 적용하기 **전에** 기존 `walksafe-backup` UID/GID·home·shell·추가 group과 upload/raw-object/lock 경로의 owner·mode·access/default ACL을 읽기 전용으로 검사한다. 이름이 같은 기존 계정이 있거나 예상 밖 group·ACL·symlink·hardlink가 하나라도 있으면 적용을 중단한다.
3. 2단계를 통과한 host에만 sysusers와 tmpfiles를 적용해 전용 계정·그룹, upload/raw-object root `walksafe-backend:walksafe-backup-readers 2750`, lock parent `root:walksafe-maintenance-lock 0750`, lock leaf `root:walksafe-maintenance-lock 0440`을 만든다. tmpfiles가 기존 ACL을 제거한다고 가정하지 않는다.
4. upload/raw-object root와 바로 아래의 각 항목을 symlink를 따라가지 않고 다시 검사한다. root에는 access/default ACL이 없어야 하며, UUID 형식의 flat `.wse`와 canonical `.wsrc`도 ACL이 없는 `walksafe-backend` 소유 regular file, link count 1, 기존 mode `0600` 또는 이미 전환된 `0640`만 허용한다. 하나라도 다르면 적용을 중단한다.
5. 4단계에서 검증한 파일만 group을 `walksafe-backup-readers`, mode를 `0640`으로 바꾼다. 재귀 `chgrp`·`chmod`로 legacy 파일, symlink, hardlink, 작업 journal을 함께 바꾸지 않는다.
6. upload root, raw-object root, account-deletion journal root가 같은 filesystem인지와 `stat`, Backend 저장소 사전검사, backup source 사전검사, 각 systemd unit 검증을 통과한 뒤에만 Backend를 시작한다. backup 실행기는 `walksafe-backup`의 exact UID/GID와 세 group 외 예상 밖 effective group이 없음을 다시 검사한다. raw 원본 backup은 이 B1e 예제의 완료 범위가 아니며, backup timer는 격리 backup→restore와 별도 승인 전까지 비활성으로 둔다.

이 저장소에는 실제 host의 계정·기존 객체·비밀값·승인 표식이 없으므로 위 마이그레이션과 서비스 시작은 실행하지 않았습니다.
