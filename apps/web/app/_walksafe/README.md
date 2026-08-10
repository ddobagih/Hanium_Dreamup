# WalkSafe Browser Features

보행 보조 화면에 필요한 client-side feature 모듈이다. React hook이 browser API lifecycle을 소유하고 순수 정책 파일이 위험/거리/길안내 결정을 담당한다.

## 영역

- `components/`: camera surface, 상태/조작 panel, opt-in test capture UI
- `hooks/`: camera, GPS/orientation/motion, detection, risk feedback, report, voice, PWA, navigation lifecycle
- `risk-*.ts`, `depth-estimator.ts`: metric depth와 pseudo trend를 구분하는 위험 정책
- `route-progress.ts`, `navigation-destination.ts`: 경로 이탈/도착과 목적지 후보 정책
- `voice-intent-executor.ts`: 허용된 목적지·길안내 음성 intent를 주입된 UI action으로 실행하는 테스트 가능한 경계
- `motion-projection.ts`: alpha-beta GPS filter, step/GPS 속도, route bearing 기반 3~5초 미래 ROI와 점자블록 보조 판단
- `admin-heatmap.ts`: report grid 요약을 관리자 좌표 heatmap cell로 투영
- `step-length.ts`: GPS 구간과 motion step을 사용한 보폭 추정
- `config.ts`: 공개 환경변수와 client policy 기본값

## 보행 안전 경계

- `available`인 최신 탐지 응답만 “위험 요소 없음”으로 표시한다. camera 중단, stale frame, timeout과 API 오류는 안전 판정이 아니라 탐지 불가/일시 중지 상태다.
- 자동 신고는 같은 손상 후보의 서로 다른 camera frame 3개와 최소 700ms 지속을 모두 요구한다.
- 실제 길은 TMAP polyline과 guide point가 결정한다. camera의 정상 점자블록 감지는 TMAP 경로 방향과 일치할 때 정렬 보조로만 사용하며 새 경로를 만들지 않는다.
- 활성 회전 안내는 GPS 정확도 15m 이내와 3초 이내 절대 방향값이 있을 때만 재생한다.
- 사용자 TTS는 browser `speechSynthesis`를 사용한다. 위험 경고가 음성 명령과 길안내보다 우선하고, STT 녹음/업로드 중에는 위험 외 TTS를 재생하지 않는다.

`fake`/`fake-v2`는 UI와 API 계약 확인용이다. WebXR depth와 bbox trend도 Android ARCore 또는 실측 거리 evidence를 대체하지 않는다.
