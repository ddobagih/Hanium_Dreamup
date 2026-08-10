# Public PWA Assets

정적으로 제공되는 manifest, service worker와 아이콘을 보관한다.

- `manifest.webmanifest`: standalone/portrait 설치 metadata와 192/512 아이콘
- `sw.js`: app shell/static asset cache, version/update message와 navigation fallback
- `icons/`, `icon.svg`: 설치 및 화면 자산

Service worker는 API/upload/speech/navigation 응답을 cache하지 않는다. 비-GET 요청과 offline report 재전송도 처리하지 않는다. 등록은 `NEXT_PUBLIC_WALKSAFE_PWA_ENABLED=true`일 때만 수행되므로 파일 존재만으로 PWA release 완료를 주장하지 않는다.
