# USB 없이 야외에서 테스트하기

대상은 별도 `visualTest` Android 사용자 앱입니다. 관리자 앱이나 위치 평가 앱의 서버 주소까지 자동으로 바뀌는 설정이 아닙니다. 이 문서는 테스트 연결 절차이며 운영 배포 안내가 아닙니다.

## 연결 구조

```text
휴대폰의 모바일 데이터 또는 Wi-Fi
  -> Cloudflare 임시 HTTPS 주소
  -> 이 PC의 cloudflared
  -> 보호 프록시 127.0.0.1:8082
  -> Android Gateway 127.0.0.1:8081
  -> Backend 127.0.0.1:8000 -> DB
```

서버를 클라우드로 이전하는 방식이 아닙니다. PC와 DB·Backend·Gateway·프록시·터널·인터넷을 유지해야 합니다. PC가 꺼지거나 절전 상태가 되면 사용할 수 없습니다. 자동 실행 서비스와 절전 방지는 이 작업에 포함되어 있지 않습니다.

Quick Tunnel은 임시 주소를 만드는 테스트용 서비스이며, 재시작하면 주소가 바뀔 수 있습니다. 가동 시간을 보장하지 않고, 동시 처리 중인 요청 200개 제한과 SSE 미지원 제약이 있습니다. [Cloudflare 공식 안내](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/)

## 1. 서버를 먼저 준비하기

기존에 실제 계정 로그인과 Backend 연동이 가능한 DB·서버 설정을 사용합니다. 신규 PC라면 [개발 환경](development-environment-guide.md), [Gateway 코드 지도](code/android-gateway.md), [배포 설정 예제](../../deploy/config/walksafe-android-gateway.env.example)를 먼저 확인합니다. 예제의 `CHANGE_ME`는 실제 설정이 아니며 그대로 실행하지 않습니다.

**개발 환경 가이드의 인증을 생략하는 loopback 전용 프로필을 인터넷에 공개하면 안 됩니다.** 외부 연결 전 다음 조건이 필요합니다.

- Backend와 Gateway의 `WALKSAFE_ALLOW_INSECURE_LOCAL_DEV=false`. 실제 계정·세션 검증을 유지합니다.
- Gateway는 `WALKSAFE_ANDROID_GATEWAY_HOST=127.0.0.1`, `WALKSAFE_ANDROID_GATEWAY_PORT=8081`로 실행합니다.
- Backend는 `127.0.0.1:8000`에만 바인딩하고 DB 포트를 공개하지 않습니다.
- Gateway의 `BACKEND_API_BASE_URL=http://127.0.0.1:8000`과 서비스 인증값을 기존 Backend 설정에 맞춥니다.
- `WALKSAFE_GATEWAY_SESSION_SECRET`, 상태 암호화 키와 DB 인증값을 Git 밖에서 관리합니다. 기존 DB에 연결할 때 임의로 다시 생성하지 않습니다.
- Gateway는 `WALKSAFE_GATEWAY_TRUSTED_IP_HEADER=x-real-ip`를 사용합니다. 클라이언트가 직접 보낸 IP 헤더가 아니라 아래 프록시가 덮어쓴 값만 신뢰합니다.
- TMAP 실제 검색·보행 경로 제공 설정과 서버용 키도 별도로 필요합니다. 터널을 켜는 것만으로 mock 설정이나 비어 있는 키가 해결되지 않습니다.

이미 실행 중인 서버가 있으면 중복 실행하지 않습니다. 의존성과 안전한 환경변수를 준비한 서버를 직접 시작할 때의 명령은 다음과 같습니다. 비밀값 파일을 터미널 출력이나 Git에 남기지 않습니다.

```bash
# Backend용 가상환경을 활성화하고 DB/보안 설정을 먼저 준비한 터미널
python -m uvicorn backend.app.main:app --host 127.0.0.1 --port 8000

# 별도의 Gateway용 환경변수와 Node.js 22를 준비한 터미널
npm --prefix apps/android-gateway ci
npm --prefix apps/android-gateway run build
npm --prefix apps/android-gateway start
```

## 2. 프록시와 터널 켜기

저장소 루트의 별도 터미널에서 프록시를 실행합니다.

```bash
node scripts/visual-test-tunnel-proxy.mjs
```

다른 터미널에서 설치된 `cloudflared`를 실행합니다. 설치가 필요하면 [공식 다운로드](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/downloads/)를 따릅니다.

```bash
cloudflared tunnel --no-autoupdate --url http://127.0.0.1:8082
```

출력되는 `https://...trycloudflare.com` 주소를 이번 테스트의 Gateway 주소로 사용합니다. 위 두 터미널은 닫지 않습니다. 기존 터널의 종료·재시작은 휴대폰 연결을 끊으므로 테스트 중 임의로 실행하지 않습니다.

**터널 대상을 8000이나 8081로 바꾸지 않습니다.** 보호 프록시는 `/api/`의 앱 경로와 필요한 개인정보 권리 경로를 전달하고, 내부·관리·디버그 경로와 WebSocket을 차단합니다. 인증을 대신하는 서버가 아니므로 Gateway 인증은 그대로 필요합니다. Cloudflare 클라이언트 IP가 없는 요청은 거절하며, 쿠키를 보존하고 HTTPS 전달 헤더와 캐시 금지 헤더를 적용합니다.

## 3. 외부 주소로 테스트 APK 만들기

[격리 소스 안내](../../tools/visual-test/README.md)에 따라 새 작업 폴더와 모델 자산을 먼저 준비합니다. 빌드할 터미널에서 실제 임시 주소와 지도용 키를 입력합니다.

```bash
read -r -p '이번 터널의 HTTPS 주소: ' WALKSAFE_GATEWAY_ORIGIN
export WALKSAFE_GATEWAY_ORIGIN
read -r -s -p 'TMAP 지도용 키: ' WALKSAFE_TMAP_MAP_KEY
printf '\n'
export WALKSAFE_TMAP_MAP_KEY

cd "$TEST_WORKSPACE/apps/android"
./gradlew :app:assembleVisualTest --console=plain
unset WALKSAFE_TMAP_MAP_KEY
```

주소에는 경로·쿼리·계정정보를 넣지 않습니다. 기존 Gradle 속성에 같은 이름의 주소가 저장돼 있다면 환경변수보다 우선할 수 있으므로 오래된 설정을 사용하지 않도록 주의합니다. 지도 키와 검색·보행 경로 API의 서버 설정은 역할이 다르며, 지도 키를 APK에 넣었다고 서버 검색 설정이 자동 완성되지는 않습니다.

지도 표시용 키는 APK에서 추출될 수 있습니다. 테스트용 제한 키를 사용하고 허용 범위·할당량을 관리하며, 서버 인증 토큰을 이 값으로 넣지 않습니다. 빠른 시작용 이메일·비밀번호를 빌드에 넣지 않고 정상 로그인합니다.

결과는 `app/build/outputs/apk/visualTest/app-visualTest.apk`입니다. USB는 최초 설치에만 필요합니다.

```bash
adb devices
read -r -p '설치할 기기의 serial: ' DEVICE_SERIAL
adb -s "$DEVICE_SERIAL" install -r app/build/outputs/apk/visualTest/app-visualTest.apk
adb -s "$DEVICE_SERIAL" reverse --remove tcp:8081
```

역방향 포워딩이 처음부터 없었다면 마지막 명령은 제거할 대상이 없다는 메시지를 낼 수 있습니다. `install -r`은 서명이 같을 때 데이터를 유지하는 설치입니다. 다른 PC의 debug 서명이 달라 실패하면 기존 데이터를 지우지 말고 동일한 서명 사용 여부부터 확인합니다.

## 4. 실제 테스트 순서

1. 앱을 실행해 정상 로그인합니다. 기존 로그인과 초기 절차 완료 상태는 앱 데이터에 따라 다릅니다.
2. USB를 빼고 모바일 데이터 또는 외부 Wi-Fi로 목적지 검색을 해봅니다. 로컬 USB 연결이 남아 있는 시험과 구분합니다.
3. 목적지를 정하고 안내 시작을 선택합니다. 메인 화면의 지도에서 경로, 객체 인식 화면에서 영상·상자·거리 상태를 확인합니다.
4. 음성 명령은 장소 요청, 후속 번호 선택, 인식 실패 시 안내, 다시 듣기를 각각 확인합니다. 서버 연결 성공만으로 음성 전체 흐름이 검증된 것은 아닙니다.
5. 위치 측정 기록을 평가하려면 [위치 평가 앱 절차](android-apps-testing-guide.md)를 별도로 따릅니다. 사용자 앱만 설치하거나 터널만 켜면 정답 데이터가 만들어지는 것은 아닙니다.

실제 보행에는 기존 보조수단과 안전한 시험 환경을 유지합니다. 화면에 표시되는 위치 정확도는 기기의 추정값이지 실제 오차의 정답이 아닙니다. 카메라를 책상으로 향하게 둔 상태의 연결 시험은 객체 인식·거리 정확도·보행 안전 시험을 대신하지 않습니다.

## 5. 문제를 구분하기

| 현상 | 우선 확인할 내용 |
|---|---|
| 계정 서버에 연결하지 못함 | PC 절전, 서버·터널 종료, 모바일 데이터, APK에 들어간 이전 주소 |
| 주소는 연결되지만 로그인 실패 | 계정 및 세션 검증, Gateway-Backend 인증 설정, Secure 쿠키 전달 |
| 지도만 빈 화면 | 지도 키, 키 권한과 할당량, 인터넷, 목적지·경로 상태 |
| 검색·안내가 실패 | 실제 TMAP provider와 서버 키, 현재 위치 상태, Backend 응답 |
| 객체 상자는 있지만 거리 없음 | ARCore와 프레임·거리 신뢰도 상태. 터널 문제와 별개이며 임의 거리로 대체하지 않음 |
| 직접 localhost:8082 요청이 403 | Cloudflare IP 헤더가 없는 직접 요청은 의도적으로 거절됨 |
| 새 터널을 켰는데 앱은 연결 실패 | 변경된 임시 주소로 APK를 다시 빌드·설치했는지 확인 |

## 종료와 기록

시험을 마치면 먼저 앱에서 안내를 끝냅니다. 이번에 실행한 cloudflared와 보호 프록시 터미널에서 각각 `Ctrl+C`로 종료합니다. 다른 작업이 사용하는 Backend·DB를 임의로 종료하거나 공용 데이터 폴더를 삭제하지 않습니다.

로그·사진·음성·위치 기록·APK·세션 쿠키·NGII 키·런타임 JSON은 GitHub에 올리지 않습니다. 오류를 공유할 때는 비밀값을 제거한 오류 종류와 시각, 수행 단계만 적습니다.

2026-09-07의 이전 연결 작업에서는 프록시 테스트 10개, Android 관련 테스트 180개와 외부 응답 4건, 모바일 데이터 로그인 및 메인 화면 진입을 확인했습니다. 이는 당시 별도 작업본의 기록이며, 이 가이드 작성이나 소스 재배치 후 재시험 결과가 아닙니다. 실제 야외 보행·객체 거리 정확도·음성 안내 전체 흐름은 이 연결 확인으로 통과 처리하지 않습니다.
