# Web Wire Types

browser와 backend 사이의 직렬화 가능한 계약을 정의한다.

- `inference.ts`: legacy v1 detection/report 호환 타입
- `inference-v2.ts`: unified/legacy model identity, bbox, GPS와 distance source를 포함한 v2 타입
- `navigation.ts`: 목적지 검색과 정규화된 도보 경로 타입

UI 전용 state를 이 디렉터리에 넣지 않는다. 필드를 변경할 때 backend schema, API parser와 fixture test를 함께 갱신한다. `fake`, `server`, `android` source를 합쳐서 성능 근거로 해석하지 않는다.
