# Device Gate

이 패키지는 센서와 모델이 준비됐다는 사실과 사용자 동작을 허용해도 된다는 사실을 분리한다.

`DeviceGateState`는 camera permission, ARCore/depth 지원, TFLite config와 detector, 실행 중 session, fresh metric object, stale reason을 종합한다. 호출자는 다음 출력을 구분해서 사용한다.

- startup/actuator readiness: 일반 TTS 같은 동작을 시작할 수 있는가
- alert readiness: 현재 객체가 위험 경고에 충분히 신선하고 신뢰 가능한가
- report candidate readiness: 신고 후보 생성을 허용할 수 있는가

정적 테스트 통과만으로 이 gate의 실기기 조건을 PASS로 바꾸지 않는다.
