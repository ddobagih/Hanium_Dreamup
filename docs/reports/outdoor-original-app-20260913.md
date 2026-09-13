# 원본 사용자 앱 야외 연결

2026-09-13 12:55. 기존 원본 debug 앱에 Cloudflare Quick Tunnel 주소를 넣어 Galaxy S25에 교체 설치했다. 별도 visualTest APK가 아니다.

- HTTPS Gateway: https://yield-bacterial-corners-necklace.trycloudflare.com
- 경로: 휴대폰 → Cloudflare → 127.0.0.1:8082 보호 프록시 → 기존 8081 Gateway → 8000 Backend → 기존 DB.
- Backend/Gateway의 WALKSAFE_ALLOW_INSECURE_LOCAL_DEV=false 및 Gateway x-real-ip 신뢰 설정을 확인했다. DB·Backend를 터널에 직접 공개하지 않았다.
- 외부 GET /api/field-session 200, 미로그인 상태 authenticated=false. /api/admin 404. 기존 프록시 검사 10개 통과. 실제 사용자 로그인/야외 이동통신 시험은 사용자가 수행한다.
- APK: apps/android/app/build/outputs/apk/debug/app-debug.apk
- SHA256: 1e850be9363581cbf9673f63acdabcece5b483f2f6a7dcb0bcd36fb027d8b03a
- adb install -r 성공, 패키지 lastUpdateTime 2026-09-13 12:54:40. 데이터 삭제 없음. ADB reverse 목록 비어 있음. 기존 앱이 이미 로그인 화면이며 저장된 gateway 세션은 없고 설치 ID만 남아 있음을 비밀값 출력 없이 확인했다. 설치 후 앱을 열었다.

## 사용

앱에서 로그인하고 설정의 모바일 데이터 사용을 허용한다. USB를 빼고 모바일 데이터 또는 외부 Wi-Fi에서 목적지 검색/안내를 시험한다. 목적지 검색·회원가입 메일·음성·보행 전체를 이번 API 연결 검사로 검증한 것은 아니다.

PC·인터넷·서버를 유지해야 한다. 터널은 클라우드 서버 이전이 아니며 PC가 꺼지면 연결이 끊긴다. 자동 절전은 이번 테스트 동안 inhibitor로 막았다. Quick Tunnel을 재시작하면 주소가 바뀔 수 있으므로 임의로 재시작하지 않는다.

현재 사용자 systemd 임시 서비스:

```bash
systemctl --user status walksafe-outdoor-proxy walksafe-outdoor-tunnel walksafe-outdoor-awake
```

시험 종료 후 이번에 추가한 터널/프록시/절전 방지만 종료한다. 기존 DB·Gateway·Backend는 이 명령으로 종료하지 않는다.

```bash
systemctl --user stop walksafe-outdoor-tunnel walksafe-outdoor-proxy walksafe-outdoor-awake
```

재빌드 시 현재 터널 주소를 명시해야 한다. 일반 빌드의 localhost 기본값을 전역 수정하지 않았다.

```bash
cd /home/ddobagi/Hanium_Dreamup/apps/android
ANDROID_HOME=/home/ddobagi/Android/Sdk ./gradlew :app:assembleDebug \
  -PWALKSAFE_GATEWAY_ORIGIN=https://yield-bacterial-corners-necklace.trycloudflare.com \
  -Dkotlin.daemon.jvm.options=-Xmx6g
```

[Cloudflare Quick Tunnel 공식 안내](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/)
