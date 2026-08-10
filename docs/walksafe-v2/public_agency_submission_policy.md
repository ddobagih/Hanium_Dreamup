# Manual public agency reporting policy

- 기준일: 2026-07-02 KST
- 2026-07-11 보정: 기관 자동 API 제출은 범위 밖이다. 관리자가 대시보드에서 검수·필터링한 뒤 `agency` 정확 위치 최소 CSV/manifest를 내려받아 외부 기관에 수동 신고하고 receipt를 기록한다. 임의 선택·병합 기능은 없다.

## 결론

WalkSafe 서버는 자동 신고 결과를 공공기관 민원 API로 직접 전송하지 않는다. 외부 기관 신고는 관리자가 검수한 데이터를 CSV로 내려받아 수동으로 처리한다.

제품 정책:

1. 앱은 `damaged_tactile_block`만 자동 신고로 저장한다.
2. 자동 신고는 aria-live와 haptic으로 결과를 알리되 불필요한 TTS는 하지 않는다.
3. 사용자가 명시적으로 "신고해줘"라고 요청한 경우에만 신고 성공/실패를 TTS로 안내한다.
4. 신고는 운영자 대시보드 리스트로 들어간다.
5. 운영자는 상태·기간·위치·source 등으로 신고를 필터링하고 검수·누적 관리한다. 화면에서 임의 항목을 선택·병합하지 않는다.
6. 운영자는 `agency` profile로 named 검수된 reviewed·damage·high≤15m·non-fake 허용 필드와 손상 위치를 찾는 데 필요한 정확 좌표를 내려받아 해당 기관 채널에 수동 신고한다. 모델 성능 제외는 이 human review 적격성과 분리한다. 접수 ID는 receipt CLI로 기록하고 JSON/GeoJSON은 내부 검수·분석에 사용할 수 있다.
7. 기관 connector, submissions 계층, 접수번호 자동 저장과 외부 민원 API 전송은 제품 범위에 넣지 않는다.

## 운영자 export 범위

`GET /reports/export`는 관리자 검수·운영 판단과 수동 외부 신고 자료 준비를 위한 다운로드 기능이다.

- CSV 기본
- `format=json`
- `format=geojson`
- `status`, `class_name`, `source`, `model_key`, `trigger`, `auto_reported`, 날짜, 위치 반경 필터
- `demo_filter=exclude_fake`로 demo/fake 제외 가능
- `profile=minimum`으로 좌표를 반올림하고 민감 필드를 제거
- `profile=agency`로 기관 허용 필드와 정확 위치만 포함하고 Fake를 강제 제외한다. `performance_excluded`는 모델 성능 집계 경계로 유지하되 named human review의 기관 신고 적격성과 분리한다.
- `aggregate=grid`는 관리자 정확 위치 확인용 `internal` profile만 허용

Export 자체는 기관 API 연계나 자동 민원 접수가 아니다. 관리자가 검수·필터링한 `agency` CSV를 내려받아 외부 기관에 수동 신고하고 실제 접수 receipt를 남겨야 한다.

기관 제출 전에는 자동 OCR/얼굴/번호판 판별을 완료로 간주하지 않는다. 사람이 제출 대상 7 PNG와 8 DOCX의 hash를 확인한 visual privacy receipt를 남겨야 하며, 정확 좌표는 손상 지점 신고 목적으로만 전달한다.

## 자동 API 제출을 제외한 이유

- 기관별 공식 접수 권한/API와 제출 양식이 다르다.
- 오탐이 그대로 민원으로 접수되면 기관 업무 부담과 서비스 신뢰도 문제가 생긴다.
- 위치정보, 이미지, 신고자 식별자 제공 동의와 운영 보안이 별도 출시 lane에 있다.
- 현재 사용자 정책은 "자동 신고가 운영자 대시보드 리스트로 가고, 관리자가 검수·필터링한 뒤 CSV로 내려받아 외부 기관에 수동 신고"하는 것이다.

## 추후 변경 조건

나중에 기관 API 자동 제출을 제품에 넣으려면 별도 정책 결정과 구현 범위가 필요하다.

- 제출 대상 기관/채널
- 제출 payload와 동의 범위
- auth/RBAC/audit/rate-limit
- 중복 제출 방지
- 제출 전 human review
- 접수번호 저장과 정정/취소 정책

이 문서 기준에서는 위 자동 연계 항목을 모두 `OUT_OF_SCOPE`로 둔다. 수동 신고 도구·receipt schema는 구현됐지만 실제 기관 제출·수용 evidence가 없으므로 완료로 주장하지 않는다. release gate는 기관 receipt와 사람이 검수한 제출 이미지 privacy receipt가 없으면 FAIL한다.
