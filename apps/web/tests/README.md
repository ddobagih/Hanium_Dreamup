# Web Policy Tests

이 디렉터리는 TypeScript 순수 정책과 정적 계약을 검증한다. 현재 Jest/Vitest/browser runner가 아니라 `scripts/check_frontend_*.sh`가 필요한 파일을 `/tmp`에 compile한 뒤 Node로 실행한다.

주요 검증:

- 위험/TTC/ROI와 v2 자동 신고
- 목적지 후보, 길안내, 경로 이탈/재탐색/도착
- 목적지 변경·취소, 다음 안내 질의, 길안내 시작·중지 음성 callback dispatch
- 보폭 보정
- PWA 상태와 offline queue 정책
- admin 요약/export, local-only 설정, test-log gate

대표 실행:

```bash
cd apps/web
npm run typecheck
npm run lint
cd ../..
bash scripts/check_frontend_risk_evaluator_policy_20260523.sh
bash scripts/check_frontend_navigation_guidance_policy_20260524.sh
bash scripts/check_frontend_pwa_policy_20260526.sh
```

이 테스트는 camera, GPS, MediaRecorder, service worker, React interaction 또는 실제 backend를 띄운 browser E2E가 아니다.
