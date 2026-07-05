# No public agency submission policy

- 기준일: 2026-07-02 KST
- 상태: 공공기관 제출 기능 없음. 운영자 대시보드와 내부 export만 제공한다.

## 결론

WalkSafe는 자동 신고 결과를 공공기관 민원으로 직접 제출하지 않는다.

제품 정책:

1. 앱은 `damaged_tactile_block`만 자동 신고로 저장한다.
2. 자동 신고는 사용자에게 알리지 않고 조용히 처리한다.
3. 사용자가 명시적으로 "신고해줘"라고 요청한 경우에만 신고 성공/실패를 TTS로 안내한다.
4. 신고는 운영자 대시보드 리스트로 들어간다.
5. 운영자는 필요하면 CSV/JSON/GeoJSON으로 내부 다운로드한다.
6. 기관 connector, submissions 계층, 접수번호 저장, 외부 민원 자동 전송은 제품 범위에 넣지 않는다.

## 운영자 export 범위

`GET /reports/export`는 내부 검수와 운영 판단을 위한 다운로드 기능이다.

- CSV 기본
- `format=json`
- `format=geojson`
- `status`, `class_name`, `source`, `model_key`, `trigger`, `auto_reported`, 날짜, 위치 반경 필터
- `demo_filter=exclude_fake`로 demo/fake 제외 가능
- `redacted=true`로 좌표 정밀도와 image path 노출 제한 가능

Export는 기관 제출, 기관 연계, 자동 민원 접수 기능이 아니다.

## no-submission 이유

- 기관별 공식 접수 권한/API와 제출 양식이 다르다.
- 오탐이 그대로 민원으로 접수되면 기관 업무 부담과 서비스 신뢰도 문제가 생긴다.
- 위치정보, 이미지, 신고자 식별자 제공 동의와 운영 보안이 별도 출시 lane에 있다.
- 현재 사용자 정책은 "자동 신고가 운영자 대시보드 리스트로 가고, 운영자가 CSV 등으로 다운로드 가능"이다.

## 추후 변경 조건

나중에 기관 제출을 제품에 넣으려면 별도 정책 결정과 구현 범위가 필요하다.

- 제출 대상 기관/채널
- 제출 payload와 동의 범위
- auth/RBAC/audit/rate-limit
- 중복 제출 방지
- 제출 전 human review
- 접수번호 저장과 정정/취소 정책

이 문서 기준에서는 위 항목을 모두 `OUT_OF_SCOPE`로 둔다.
