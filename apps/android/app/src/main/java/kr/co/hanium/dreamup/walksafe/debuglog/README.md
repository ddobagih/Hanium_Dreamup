# Debug Log Boundary

이 패키지는 Android runtime 진단 정보를 전송하는 작은 계약만 제공한다. 실제 HTTP 구현은 `src/debug`, release no-op 구현은 `src/release` source set에 있다.

## 구성

- `MetadataLogUploader`: 이미지 없는 frame/depth metadata 전송 계약
- `FrameCaptureUploader`: 사용자가 명시적으로 요청한 debug JPEG 전송 계약
- `MetadataLogEndpointPolicy`: local/dev endpoint 허용 정책
- `MetadataLogJsonEncoder`: metadata JSON 직렬화

## 불변조건

- metadata uploader는 기본 비활성이며 render loop를 실패시키면 안 된다.
- 운영 신고 `/reports/v2`와 debug endpoint를 같은 데이터 흐름으로 취급하지 않는다.
- raw depth, 오디오, GPS, secret은 metadata log에 넣지 않는다.
- release factory는 no-op이지만 현재 server-log 버튼 자체는 별도 UI 정리 전까지 보일 수 있다.
