# WalkSafe 신고 데이터 보존 정책

기준일: 2026-07-02
범위: `/reports`, `/reports/v2`, 업로드 이미지, export 산출물의 local/mock/static 운영 기준

## 원칙

- report image, 위치, 로그인 user id, 신고 시각 같은 기본 report 정보는 서버에 저장한다.
- 얼굴/차량번호 모자이크는 하지 않는다.
- 실제 report row와 업로드 이미지는 6개월이 지나면 자동 삭제하는 제품 정책으로 둔다.
- 현재 구현된 retention 자동화는 dry-run 후보 출력까지만 제공한다. 실제 자동 삭제 job은 운영 보안/백업/audit lane에서 구현해야 한다.
- Fake/Demo 데이터는 성능·field evidence·운영 export 기본 필터에서 제외한다.
- 공공기관 직접 제출 기능은 두지 않는다. 운영자 대시보드와 CSV/JSON/GeoJSON export만 제공한다.

## 보존 기간 기준

| 데이터 | 기본 보존 기간 | 비고 |
|---|---:|---|
| 실제 신고 row | 180일 | `source=android`/server 실사용 후보 전체. 상태와 무관하게 6개월 후 삭제 |
| 업로드 이미지 | 180일 | report row와 같이 삭제 |
| Fake/Demo 신고 | 최대 30일 | `source=fake`, `fake_source`, `data_origin=demo`, `performance_excluded=true` |
| debug/test capture | 최대 7일 권장 | 개발자 전용. 운영 기본 surface 금지 |
| export 파일 | 7일 | 로컬 산출물 기준, 재생성 가능해야 함 |

## 자동 삭제 구현 기준

1. 삭제 대상은 report row와 연결된 upload image를 함께 묶는다.
2. 삭제 전 dry-run summary를 생성한다.
3. 운영 DB에서는 backup/rollback 가능 상태를 확인한다.
4. 삭제 실행자는 audit actor로 남긴다.
5. 삭제 후 row count, file count, 실패 목록을 기록한다.
6. destructive delete는 코드로 구현하더라도 기본 실행값은 off로 둔다.

## Dry-run evidence

- 명령 예:
  - `python scripts/check_report_retention_dry_run.py --input-json fixture.json --as-of 2026-05-26T00:00:00Z`
  - `python scripts/check_report_retention_dry_run.py --output-md docs/execution/YYYY-MM-DD_report_retention_dry_run.md`
- 출력에는 candidate id, age_days, reason, would_delete=false를 포함한다.

## 개인정보 보호

- 검증 로그에는 실제 전화번호, 정확한 집 주소, secret을 그대로 붙이지 않는다.
- 제품 정책상 report image와 GPS는 서버 저장을 허용하지만, 공개/export 기본값은 필요한 범위로 제한한다.
- 이미지 업로드는 decodable 이미지에 대해 재인코딩을 시도해 EXIF/metadata를 제거한다.
- 보호자 연락처는 local-only 설정으로 유지하며 report/STT/detect payload에 포함하지 않는다.
