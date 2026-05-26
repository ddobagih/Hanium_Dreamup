# Public agency submission policy

- 기준일: 2026-05-24 KST
- 상태: 직접 자동 민원 전송은 보류. 관리자 검토 후 CSV/수동 제출을 기본 흐름으로 둔다.

## 1. 결론

현재 WalkSafe의 자동 신고 결과를 곧바로 공공기관 민원으로 제출하는 기능은 1차 구현 대상에서 제외한다.

우선 순서:

1. 앱은 타일/점자블록 손상을 자동 신고로 저장한다.
2. 운영자는 관리자 화면에서 중복/오탐/위치/이미지를 검토한다.
3. 검토된 건만 `/reports/export` CSV/JSON/GeoJSON으로 묶어 제출 준비한다.
4. 기관별 공식 연계 권한/API가 확인되면 별도 connector로 자동 전송을 붙인다.

테스트/개발 단계에서는 모델 개선과 운영 검수를 위해 이미지, 위치, heading, detection metadata, threshold, 중복 후보, 어드민 판정 등 도움이 되는 데이터를 충분히 수집한다. 단, 외부 기관 제출과 공개 공유는 어드민 검수 이후로 제한한다.

## 2. 직접 자동 전송을 보류하는 이유

- 공식 민원/안전신고 제출은 로그인, 본인확인, 신고자 정보, 위치, 사진, 내용 같은 절차가 요구될 수 있다.
- 모델 오탐이 그대로 민원으로 접수되면 기관 업무 부담과 서비스 신뢰도 문제가 생긴다.
- 자동 제출은 개인정보, 위치정보, 이미지 보존/제공 동의 범위를 먼저 정해야 한다.
- 기관별 소관과 접수 채널이 다르다. 보도블록/점자블록은 지자체 도로/보도 관리 부서일 가능성이 높지만, 위치에 따라 처리기관이 달라질 수 있다.
- 공개된 공공데이터 API는 대체로 데이터 조회/개방 목적이며, 민원 “접수” API와는 다르다. 자동 접수 가능 여부는 기관 협의가 필요하다.

## 3. 현재 구현된 안전한 중간 단계

- `GET /reports/export`
  - CSV 기본
  - `format=json` 지원
  - `format=geojson` 지원: 위치가 있는 신고는 Point Feature, 위치가 없으면 `geometry: null`
  - `status`, `class_name`, `source`, `model_key`, `trigger`, `auto_reported`, 날짜, 위치 반경 필터 지원
- 관리자 화면
  - 현재 필터 조건으로 `CSV 내보내기`
  - v2 metadata 필터로 자동 신고/음성 요청 신고 분리 가능

이 단계는 “앱에서 모으고, 검토된 자료를 묶어 제출”하는 운영 방식이다.

## 4. 추후 자동 전송을 하려면 필요한 것

### 4.1 기관/채널 확정

- 안전신문고
- 국민신문고
- 지자체 민원/도로관리 부서
- 학교/시설관리 주체 등 사유지/특수구역

### 4.2 제출 payload 확정

- 신고 제목
- 신고 내용
- 위치 좌표와 주소
- 이미지
- 발생/촬영 시각
- 신고자 또는 단체 정보
- 모델 판단 결과와 사람이 검토했는지 여부

### 4.3 운영 안전장치

- `reviewed` 이상 상태만 제출
- 같은 위치/손상 중복 제출 방지
- 제출 전 최종 preview
- 제출 결과/접수번호 저장
- 취소/정정/재제출 정책
- 개인정보/위치정보 동의와 보존 기간

## 5. 권장 구현 방향

바로 외부 민원 시스템에 POST하는 방식이 아니라 내부에 `submissions` 계층을 둔다.

```text
reports
  -> review/cluster/export
  -> submissions
  -> agency connector
  -> receipt number/status sync
```

초기 connector는 `manual_export`로 둔다.

추후 기관 연계가 확인되면 connector를 추가한다.

- `safety_report`
- `epeople`
- `local_government_email`
- `local_government_api`

## 6. 참고 링크

- 안전신문고: https://www.safetyreport.go.kr/
- 국민신문고: https://www.epeople.go.kr/
- 공공데이터포털: https://www.data.go.kr/
- 민원 처리 절차 안내: https://www.easylaw.go.kr/
