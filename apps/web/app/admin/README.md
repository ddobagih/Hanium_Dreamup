# Admin UI

신고 목록, 상세 검수, 상태 변경, source/status/fake 필터, grid heatmap 요약과 CSV/JSON/GeoJSON export를 제공하는 관리자 화면이다.

현재 인증/RBAC가 없으므로 외부 공개 운영 화면으로 간주하지 않는다. 공공기관 API 자동 제출은 없으며, 관리자가 검수·필터링한 신고를 CSV로 내려받아 기관 외부 채널에 수동 신고한다. 임의 체크박스 선택·병합 기능은 없고 현재 필터 전체를 내보낸다. backend 계약은 `../../../../docs/operations/report_operations.md`를 본다.
