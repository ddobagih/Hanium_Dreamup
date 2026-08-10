# Object Depth Pipeline

이 패키지는 detector 결과를 ARCore depth와 결합해 객체별 거리, 추적 상태, 접근 위험과 사용자 메시지 후보를 만든다. Android API에 직접 의존하는 adapter와 JVM에서 테스트 가능한 정책을 분리한다.

## 단계

1. `ArCoreFrameProvider`가 camera/depth image를 획득한다.
2. `CoordinateMapper`가 camera image polygon을 depth 좌표로 옮긴다.
3. `DepthSampler`가 polygon 내부 depth를 robust sampling한다.
4. `ObjectTracker`와 `DepthEstimator`가 metric 거리, 추세, TTC, 신뢰도를 만든다.
5. `MessagePolicy`가 실제 사용자 안내 여부와 문구를 결정한다.

## 핵심 규칙

- Raw Depth와 confidence가 충분하면 우선하고, 부족할 때 Full Depth로 fallback한다.
- 둘 다 신뢰할 수 없으면 bbox/polygon 변화 기반 pseudo trend만 만든다.
- pseudo trend에는 meter와 `N보`를 부여하지 않는다.
- 손상 점자블록은 `REPORT_ONLY`다. 정상 점자블록은 active TMAP 경로·GPS·heading·tracking 안정성을 별도 `TactileRoutePolicy`가 확인한 경우에만 local path guidance가 되고, 횡단보도는 주의 안내다.
- 현재 mapper 경로는 코드에 연결됐지만 preview/bbox/depth 정합은 실기기 PASS가 아니다.
