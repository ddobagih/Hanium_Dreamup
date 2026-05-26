# WalkSafe 신고 데이터 보존 정책

기준일: 2026-05-26
범위: `/reports`, `/reports/v2`, 업로드 이미지, export 산출물의 local/mock/static 운영 기준

## 원칙

- 실제 삭제, 운영 DB 초기화, 외부 기관 제출은 사용자 승인 전 실행하지 않는다.
- retention 자동화는 기본값을 dry-run으로 유지하고, 후보 목록만 출력한다.
- 공개/export 용도는 `redacted=true`를 우선 사용해 좌표 정밀도와 이미지 경로 노출을 낮춘다.
- Fake/Demo 데이터는 성능·field evidence·기관 제출 후보에서 제외한다.

## 보존 기간 기준

| 데이터 | 기본 보존 기간 | 비고 |
|---|---:|---|
| Fake/Demo 신고 | 30일 | `source=fake`, `fake_source`, `data_origin=demo`, `performance_excluded=true` |
| 신규/검토 중 신고 | 180일 | 조치/중복 확인 전까지 보존 |
| 처리 완료 신고 | 365일 | 수동 제출/조치 추적 근거 |
| 업로드 이미지 | 신고 record와 동일 | 단, export에는 기본적으로 경로만 포함하고 공개용은 제거 |
| export 파일 | 7일 | 로컬 산출물 기준, 재생성 가능해야 함 |

## 삭제 승인 절차

1. `scripts/check_report_retention_dry_run.py`로 후보를 생성한다.
2. 후보 수, status, demo/fake 여부, 이미지 경로 포함 여부를 검토한다.
3. 삭제/초기화가 필요하면 별도 사용자 승인과 백업 여부 확인 후 진행한다.
4. 승인 전에는 DB row 삭제, 파일 삭제, 외부 전송을 하지 않는다.

## Dry-run evidence

- 명령 예:
  - `python scripts/check_report_retention_dry_run.py --input-json fixture.json --as-of 2026-05-26T00:00:00Z`
  - `python scripts/check_report_retention_dry_run.py --output-md docs/execution/YYYY-MM-DD_report_retention_dry_run.md`
- 출력에는 candidate id, age_days, reason, would_delete=false를 포함한다.

## 개인정보 보호

- 검증 로그에는 실제 전화번호, 정확한 집 주소, 원본 GPS, secret, 식별 가능한 이미지 경로를 그대로 붙이지 않는다.
- 이미지 업로드는 decodable 이미지에 대해 재인코딩을 시도해 EXIF/metadata를 제거한다.
- 보호자 연락처는 local-only 설정으로 유지하며 report/STT/detect payload에 포함하지 않는다.
