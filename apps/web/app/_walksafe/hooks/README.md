# Browser Lifecycle Hooks

각 hook은 browser API 또는 한 사용자 흐름의 side effect를 소유한다.

| Hook | 책임 |
|---|---|
| `useCamera` | camera permission, stream, monotonic media frame 확인과 JPEG capture |
| `useSensors` | Geolocation alpha-beta filter, 절대 DeviceOrientation과 GPS/heading TTL |
| `useAutoStepLength` | DeviceMotion step, 최근 cadence 속도와 GPS 기반 보폭 보정 |
| `useDepthSensor` | opt-in WebXR depth session과 sampling |
| `useDetectionV1/V2` | fake/server detector polling과 stale result 처리 |
| `useRiskFeedback` | 위험 > 음성 명령 > 길안내 순서의 browser TTS/haptic 중재 |
| `useAutoReportV2`, `useManualReportV1` | 신고 대상, cooldown과 전송 상태 |
| `useVoiceCommands` | MediaRecorder, STT intent, 목적지 변경/취소·다음 안내 질의와 UI action |
| `useNavigationGuidance` | TMAP 목적지/경로, sensor gate, 미래 ROI, 경로 일치 점자블록 정렬 보조, 이탈/재탐색/도착 안내 |
| `usePwaStatus` | opt-in service worker 설치/업데이트 상태 |
| `useWalkSafeSettings` | 기기 local-only 사용자 설정 |

effect cleanup에서 stream, timer, listener와 abort controller를 해제한다. fake/demo, offline, stale 상태는 성공 경로와 분리하고 실제 API 실패를 fake로 자동 대체하지 않는다.
