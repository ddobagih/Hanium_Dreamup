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

예제의 잠긴 Node v22.23.1 서비스는 `/srv/walksafe/apps/android-gateway/dist/server.js`를 전용 비권한 계정으로 한 프로세스만 실행하고 `127.0.0.1:8081`에만 결속합니다. upstream은 `http://127.0.0.1:8000`의 보호 backend 하나이며 Next/Web fallback은 없습니다. nginx 예제는 TLS 뒤에서 정확한 네 Android API 경로와 로그인 없는 `/privacy/rights` 안내 화면만 `127.0.0.1:8081`로 전달하고 나머지 경로를 차단합니다. 목적지 검색어와 정확 좌표가 query string에 있으므로 기본 combined access log 상속을 끄며, 실제 배포 검토에서 필요하면 query를 제외한 `$uri` 기반 최소 형식과 보존기간을 별도로 승인해야 합니다.

이 예제는 실제 계정·디렉터리·Node 22·빌드·환경파일·인증서가 준비됐다는 뜻이 아닙니다. 별도 배포 검토 전에는 파일을 `/etc`에 복사하거나 systemd unit을 설치·enable·start하거나 nginx 설정을 적용·reload하지 않습니다. 향후 적용한다면 환경파일은 `root:root`와 `0600`, rate-limit 상태 디렉터리는 서비스 계정 전용 `0700`을 확인하고, 실제 비밀값·도메인·인증서는 Git 밖에서 프로비저닝해야 합니다.
