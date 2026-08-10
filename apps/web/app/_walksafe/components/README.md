# WalkSafe Components

이 디렉터리는 보행 화면의 표현과 사용자 입력을 담당한다.

- `CameraSurface`: camera preview, bbox overlay, 위험 live region과 권한 재시도
- `AssistPanel`: 위험/GPS/음성/길안내/신고/PWA 상태와 조작
- `TestCapturePanel`: 명시적 환경변수와 사용자 동의 뒤 테스트 자료를 저장하는 개발 도구

컴포넌트는 이미 계산된 상태와 callback을 props로 받는다. 위험 판정, API payload 정책, route 상태 전이는 hook이나 `lib`에 둔다. 카메라/bbox 장식은 screen reader에서 숨기고 실제 상태는 live region과 버튼 이름으로 제공한다.
