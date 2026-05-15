# PWA server detector wiring

## 작업 요약

`NEXT_PUBLIC_DETECTOR_MODE=server`일 때 PWA 카메라 프레임을 백엔드 `/detect`로 보내고, 성공 응답의 `detections`를 기존 `DetectionEvent` 상태로 연결했다.

## 변경 파일

- `apps/web/lib/detect-api.ts`
- `apps/web/app/page.tsx`
- `docs/execution/2026-05-14_pwa_server_detector_wiring.md`

## 구현 내용

- 서버 탐지 API 클라이언트를 추가했다.
  - `GET /detect/health`로 모델 준비 상태를 확인한다.
  - `POST /detect`에 이미지와 `gps`, `heading`, `captured_at` 컨텍스트를 전송한다.
  - 성공 응답의 detections를 UI의 `DetectionEvent`로 매핑하고 `source: "server"`를 유지한다.
- PWA 메인 화면에서 server 모드일 때만 주기적으로 서버 탐지를 실행한다.
- fake detector 흐름은 유지했다.
- 모델 미준비, 네트워크 오류, 응답 오류는 탐지/신고 상태 메시지에 반영하고 앱 흐름은 중단하지 않도록 처리했다.

## 검증

- `cd apps/web && npm run lint` 통과
- `cd apps/web && npm run typecheck` 통과
- `grep -R "source: \"server\"\|/detect\|/detect/health\|NEXT_PUBLIC_DETECTOR_MODE" -n apps/web/app/page.tsx apps/web/lib/detect-api.ts apps/web/.env.example`로 연결 지점 확인

## 남은 수동 검증

- 실폰/브라우저에서 `NEXT_PUBLIC_DETECTOR_MODE=server`로 실행 후 카메라 권한, `/detect` 호출, 탐지 박스 표시, 신고 payload 저장 확인이 필요하다.
